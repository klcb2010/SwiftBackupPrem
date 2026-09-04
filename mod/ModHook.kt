package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt

/**
 * Local API-102 hook entry.
 *
 * This file is injected into the upstream project by:
 *
 *     mod/apply.sh
 *
 * It is intentionally implemented as the upstream HookHandler
 * instead of creating another XposedModule entry point.
 *
 * Functionality migrated from the old local module
 * `com.unlocksb.module.HookEntry` (libxposed API 101 / XposedModule):
 *
 *  1. Auto anonymous sign-in.
 *     If no local session user exists, the official anonymous user factory
 *     (`anonymous.a.b.d()`) is invoked and the produced MFirebaseUser is
 *     persisted through the session holder `common.V.setNon(...)`.
 *     The very next `IntroActivity.onCreate` login check
 *     (`common.a3.i -> anonymous.a.e -> common.V.getNon`) then short-circuits
 *     and the app goes straight to the main UI — no Google login wall.
 *
 *  2. Cloud feature gate bypass.
 *     `MFirebaseUser.isAnonymous()` is forced to `false`; every consumer of
 *     the "is anonymous" flag (`common.a3.c`, `common.a3.g`, home coroutines,
 *     cloud-sync / WebDAV / multithreaded-download gates) is opened.
 *
 * Class lookup keeps the old structure: primary = upstream `ResolvedTargets`
 * (DexKit / versionMap results), fallback = hard-coded readable class names,
 * all wrapped in `attempt(...)` so a resolution failure never breaks the app.
 */
@Keep
object ModHook : HookHandler {

    private const val TAG = "SBLocalMod"
    private const val PKG = "org.swiftapps.swiftbackup"

    // Fallback class names (used only when ResolvedTargets is incomplete,
    // e.g. Swift Backup 5.0.8 / v603 which is absent from the upstream versionMap).
    private const val INTRO_ACTIVITY = "org.swiftapps.swiftbackup.intro.IntroActivity"
    private const val ANON_A = "org.swiftapps.swiftbackup.anonymous.a"
    private const val ANON_A_DEFPACKAGE = "defpackage.b45"
    private const val SESSION_V = "org.swiftapps.swiftbackup.common.V"
    private const val SESSION_V_DEFPACKAGE = "defpackage.d45"
    private const val M_FIREBASE_USER = "org.swiftapps.swiftbackup.anonymous.MFirebaseUser"

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        if (context.packageName != PKG) {
            return
        }

        Log.i(TAG, "ModHook.apply()")

        try {
            applyLocalHooks(
                module = module,
                context = context,
                classLoader = classLoader,
                targets = targets
            )
        } catch (t: Throwable) {
            Log.e(TAG, "local hook setup failed", t)
        }
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private fun applyLocalHooks(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
        // The MFirebaseUser model class anchors both hooks (its isAnonymous
        // getter for the cloud gate, its no-arg boolean shape for the
        // structural fallback lookup).
        val userClass = resolveUserClass(classLoader, targets)
            ?: run {
                Log.w(TAG, "MFirebaseUser class not found; local hooks disabled")
                return
            }

        // 1) Cloud feature gate: MFirebaseUser.isAnonymous() -> false.
        hookIsAnonymousFalse(module, userClass)

        // 2) Auto anonymous sign-in on IntroActivity launch.
        val sessionClass = resolveSessionClass(classLoader, targets)
        val anonFactory = resolveAnonFactory(classLoader, targets)

        val introActivity = findClass(classLoader, INTRO_ACTIVITY)
        if (introActivity != null) {
            val onCreate = attempt("find IntroActivity.onCreate") {
                introActivity.getDeclaredMethod("onCreate", Bundle::class.java)
            }
            if (onCreate != null) {
                module.hookTracked(
                    onCreate,
                    idPrefix = "sbnologin-auto-signin",
                    priority = XposedInterface.PRIORITY_HIGHEST,
                    deoptimize = true
                ).intercept { chain ->
                    ensureSignedIn(classLoader, sessionClass, anonFactory, userClass)
                    chain.proceed()
                }
                Log.i(TAG, "hooked IntroActivity.onCreate (auto anonymous sign-in)")
            } else {
                Log.w(TAG, "IntroActivity.onCreate not found; hook skipped")
            }
        } else {
            // If the activity name ever changes (unlikely: it is referenced by
            // AndroidManifest and R8 must keep it), still try an immediate
            // one-shot injection — the session is read lazily afterwards.
            Log.w(TAG, "IntroActivity not found; performing one-shot sign-in")
            ensureSignedIn(classLoader, sessionClass, anonFactory, userClass)
        }

        Log.i(TAG, "local hook initialization complete")
    }

    // ------------------------------------------------------------------
    // Hook 1: MFirebaseUser.isAnonymous() -> false (cloud feature gate)
    // ------------------------------------------------------------------

    private fun hookIsAnonymousFalse(module: XposedModule, userClass: Class<*>) {
        // Prefer the exact getter name (kept by R8 because the backing field
        // `isAnonymous` is serialized by Gson through common.V); only fall back
        // to the structural "no-arg boolean getter" shape when the name moved.
        val target = attempt("find isAnonymous getter", silent = true) {
            userClass.getDeclaredMethod("isAnonymous")
        } ?: attempt("collect no-arg boolean getters on MFirebaseUser", silent = true) {
            userClass.declaredMethods.firstOrNull { m ->
                m.parameterCount == 0 && m.returnType == java.lang.Boolean.TYPE
            }
        }

        if (target == null) {
            Log.w(TAG, "isAnonymous getter not found on MFirebaseUser; gate bypass skipped")
            return
        }

        attempt("hook ${target.name}") {
            module.hookTracked(
                target,
                idPrefix = "sbnologin-isanonymous-${target.name}",
                deoptimize = true
            ).intercept { false }
        }
        Log.i(TAG, "MFirebaseUser.${target.name}() forced false")
    }

    // ------------------------------------------------------------------
    // Hook 2: auto anonymous sign-in (IntroActivity.onCreate, before)
    // ------------------------------------------------------------------

    private fun ensureSignedIn(
        classLoader: ClassLoader,
        sessionClass: Class<*>?,
        anonFactory: Any?,
        userClass: Class<*>
    ) {
        val session = sessionClass ?: return
        val instance = sessionInstance(session) ?: return

        val getNon = findGetter(session, userClass) ?: run {
            Log.w(TAG, "session getter not found; cannot check current user")
            return
        }
        val existing = attempt("read current session user", silent = true) {
            getNon.invoke(instance)
        }
        if (existing != null) {
            return // already signed in (anonymous or Google)
        }

        val setNon = findSetter(session, userClass) ?: run {
            Log.w(TAG, "session setter not found; cannot persist anonymous user")
            return
        }

        val anonUser = attempt("create anonymous user") {
            createAnonymousUser(classLoader, anonFactory, userClass)
        } ?: run {
            Log.w(TAG, "anonymous user creation failed")
            return
        }

        attempt("persist anonymous user into session", silent = true) {
            setNon.invoke(instance, anonUser)
        }
        Log.i(TAG, "anonymous sign-in injected")
    }

    private fun sessionInstance(sessionClass: Class<*>): Any? =
        attempt("get session singleton instance", silent = true) {
            sessionClass.getField("INSTANCE").get(null)
        } ?: attempt("get session singleton instance (non-public)", silent = true) {
            sessionClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        }

    // Structural lookup: never guesses the method name, matches the session
    // holder's no-arg getter / single-arg setter by the MFirebaseUser type.
    private fun findGetter(sessionClass: Class<*>, userClass: Class<*>): java.lang.reflect.Method? =
        attempt("find session getter", silent = true) {
            sessionClass.declaredMethods.firstOrNull { m ->
                m.parameterCount == 0 && m.returnType == userClass
            }
        }

    private fun findSetter(sessionClass: Class<*>, userClass: Class<*>): java.lang.reflect.Method? =
        attempt("find session setter", silent = true) {
            sessionClass.declaredMethods.firstOrNull { m ->
                m.parameterCount == 1 && m.parameterTypes[0] == userClass
            }
        }

    // ------------------------------------------------------------------
    // Class resolution (targets first, hard-coded fallbacks, cached in apply scope)
    // ------------------------------------------------------------------

    private fun resolveUserClass(classLoader: ClassLoader, targets: ResolvedTargets): Class<*>? {
        // 1) Inferred from the upstream anonymous factory class when available.
        val fromAnon = attempt("infer MFirebaseUser from anon target", silent = true) {
            targets.anonUserClass?.declaredMethods?.firstOrNull { m ->
                m.parameterCount == 0 && m.returnType.name.endsWith("MFirebaseUser")
            }?.returnType
        }
        if (fromAnon != null) return fromAnon

        // 2) Hard-coded fallback (5.0.x layout).
        return findClass(classLoader, M_FIREBASE_USER)
    }

    private fun resolveSessionClass(classLoader: ClassLoader, targets: ResolvedTargets): Class<*>? {
        // 1) Upstream resolved session holder (common.V, DexKit-scanned).
        targets.vClass?.let { return it }
        // 2) Hard-coded fallbacks.
        return findClass(classLoader, SESSION_V)
            ?: findClass(classLoader, SESSION_V_DEFPACKAGE)
    }

    private fun resolveAnonFactory(classLoader: ClassLoader, targets: ResolvedTargets): Any? {
        // Locate the anonymous user factory class (anonymous.a), then read its
        // static singleton holding the a$b factory instance.
        val aClass = targets.anonUserClass
            ?: findClass(classLoader, ANON_A)
            ?: findClass(classLoader, ANON_A_DEFPACKAGE)
            ?: return null

        // Primary: public static field named "b" (anonymous.a.b -> a$b singleton).
        attempt("get anonymous factory instance (field b)", silent = true) {
            aClass.getField("b").get(null)
        }?.let { return it }

        // Structural fallback: any static field whose type declares a no-arg
        // method returning MFirebaseUser (== the official a$b.d() factory).
        return attempt("scan anonymous factory static field", silent = true) {
            aClass.declaredFields.firstOrNull { f ->
                java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                    f.type.declaredMethods.any { m ->
                        m.parameterCount == 0 && m.returnType.name.endsWith("MFirebaseUser")
                    }
            }?.apply { isAccessible = true }?.get(null)
        }
    }

    private fun createAnonymousUser(
        classLoader: ClassLoader,
        anonFactory: Any?,
        userClass: Class<*>
    ): Any? {
        // Primary: official factory a$b.d() -> MFirebaseUser(anonymous@swiftbackup.app).
        if (anonFactory != null) {
            val factoryMethod = attempt("find anonymous factory d()", silent = true) {
                anonFactory.javaClass.methods.firstOrNull { m ->
                    m.parameterCount == 0 && m.returnType == userClass
                }
            }
            if (factoryMethod != null) {
                val user = attempt("invoke anonymous factory", silent = true) {
                    factoryMethod.invoke(anonFactory)
                }
                if (user != null) return user
            }
        }

        // Fallback: construct MFirebaseUser reflectively with a random UID.
        // (Official path is preferred: it derives the UID the same way the app
        // would and keeps every downstream invariant intact.)
        return attempt("construct MFirebaseUser reflectively", silent = true) {
            val ctor = userClass.getDeclaredConstructor(
                String::class.java, // uid
                String::class.java, // email
                java.lang.Boolean.TYPE, // isAnonymous
                String::class.java, // displayName
                String::class.java, // photoUrl (nullable)
                List::class.java, // providerData
                String::class.java // providerId
            )
            ctor.isAccessible = true
            ctor.newInstance(
                "anon_" + java.util.UUID.randomUUID().toString(),
                "anonymous@swiftbackup.app",
                true,
                "Anonymous user",
                null,
                emptyList<Any>(),
                ""
            )
        }
    }

    // ------------------------------------------------------------------
    // Helpers (kept from the original HookEntry structure)
    // ------------------------------------------------------------------

    private fun findClass(
        classLoader: ClassLoader,
        name: String
    ): Class<*>? {
        return try {
            Class.forName(name, false, classLoader)
        } catch (_: ClassNotFoundException) {
            null
        } catch (t: Throwable) {
            Log.w(TAG, "failed to load $name", t)
            null
        }
    }
}
