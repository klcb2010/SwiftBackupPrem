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
import io.github.libxposed.api.XposedInterface.Chain

/**
 * Local API-102 hook entry (功能版 + 上传链探针)。
 *
 * 注入：mod/apply.sh → app/.../hook/ModHook.kt
 *
 * 功能（不变）：
 *  1. Auto anonymous sign-in（IntroActivity.onCreate 前注入匿名会话）
 *  2. MFirebaseUser.isAnonymous() -> false（云功能门禁）
 *
 * 探针（临时，定位 v620 云备份 "Uploaded 无 PUT" 断点，跑完即删）：
 *  P1 Lag8.c()    after  —— 上传状态机结果（zf8 ok/err）
 *  P2 g62.f()     before —— 是否进入 executeUpload 层
 *  P3 g62.i(...)  before —— executeUpload 参数（文件名）
 *  P4 mj1.e(...)  before —— put 层是否发起（含子类 e 分派）
 *  P5 vq8.x(...)  before —— WebDAV chunk PUT 是否真正发起
 *  P6 cf3.a(...)  after  —— FireSynchronizer 读结果类型
 *  P7 cf3.b(...)  after  —— FireSynchronizer 写结果
 *
 * 全部探针 attempt 包裹：类缺失/签名变化自动跳过，不影响功能。
 * 日志 tag：SBLocalProbe（logcat 过滤即可）。
 */
@Keep
object ModHook : HookHandler {

    private const val TAG = "SBLocalMod"
    private const val PROBE = "SBLocalProbe"
    private const val PKG = "org.swiftapps.swiftbackup"

    // Fallback class names (v620 defpackage 混淆布局 + 可读名)。
    private const val INTRO_ACTIVITY = "org.swiftapps.swiftbackup.intro.IntroActivity"
    private const val ANON_A = "org.swiftapps.swiftbackup.anonymous.a"
    private const val SESSION_V = "org.swiftapps.swiftbackup.common.V"
    private const val M_FIREBASE_USER = "org.swiftapps.swiftbackup.anonymous.MFirebaseUser"

    // ---- v620 探针目标（R8 混淆名，仅当前 5.1.0/620 有效）----
    private const val P_LAG8 = "Lag8"   // 上传状态机
    private const val P_G62 = "Lg62"    // executeUpload
    private const val P_MJ1 = "Lmj1"    // put 基类
    private const val P_VQ8 = "Lvq8"    // WebDAV chunk upload
    private const val P_CF3 = "Lcf3"    // FireSynchronizer

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
            applyLocalHooks(module, context, classLoader, targets)
            installProbes(module, classLoader)
        } catch (t: Throwable) {
            Log.e(TAG, "local hook setup failed", t)
        }
    }

    // ------------------------------------------------------------------
    // 功能 hook
    // ------------------------------------------------------------------

    private fun applyLocalHooks(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets
    ) {
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
            }
        } else {
            Log.w(TAG, "IntroActivity not found; one-shot sign-in")
            ensureSignedIn(classLoader, sessionClass, anonFactory, userClass)
        }

        Log.i(TAG, "local hook initialization complete")
    }

    private fun hookIsAnonymousFalse(module: XposedModule, userClass: Class<*>) {
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
            return // already signed in
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
    // Class resolution
    // ------------------------------------------------------------------

    private fun resolveUserClass(classLoader: ClassLoader, targets: ResolvedTargets): Class<*>? {
        val fromAnon = attempt("infer MFirebaseUser from anon target", silent = true) {
            targets.anonUserClass?.declaredMethods?.firstOrNull { m ->
                m.parameterCount == 0 && m.returnType.name.endsWith("MFirebaseUser")
            }?.returnType
        }
        if (fromAnon != null) return fromAnon
        return findClass(classLoader, M_FIREBASE_USER)
    }

    private fun resolveSessionClass(classLoader: ClassLoader, targets: ResolvedTargets): Class<*>? {
        targets.vClass?.let { return it }
        return findClass(classLoader, SESSION_V)
    }

    private fun resolveAnonFactory(classLoader: ClassLoader, targets: ResolvedTargets): Any? {
        val aClass = targets.anonUserClass
            ?: findClass(classLoader, ANON_A)
            ?: return null

        attempt("get anonymous factory instance (field b)", silent = true) {
            aClass.getField("b").get(null)
        }?.let { return it }

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

        return attempt("construct MFirebaseUser reflectively", silent = true) {
            val ctor = userClass.getDeclaredConstructor(
                String::class.java, String::class.java,
                java.lang.Boolean.TYPE, String::class.java, String::class.java,
                List::class.java, String::class.java
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
    // 探针（v620 专用，定位后删除本段）
    // ------------------------------------------------------------------

    private fun installProbes(module: XposedModule, classLoader: ClassLoader) {
        Log.i(PROBE, "=== installing upload-chain probes ===")
        probeStateMachine(module, classLoader)   // P1 Lag8.c()
        probeExecuteUpload(module, classLoader)  // P2/P3 g62.f / g62.i
        probePutLayer(module, classLoader)       // P4 mj1.e (+vq8/o23 分派)
        probeWebDavChunk(module, classLoader)    // P5 vq8.x
        probeFireSynchronizer(module, classLoader) // P6/P7 cf3.a / cf3.b
    }

    private fun probeStateMachine(module: XposedModule, cl: ClassLoader) {
        attempt("probe Lag8.c", silent = true) {
            val k = cl.loadClass(P_LAG8)
            val m = k.declaredMethods.first { it.name == "c" && it.parameterCount == 0 }
            module.hookTracked(m, idPrefix = "probe-lag8-c").intercept { chain ->
                val r = chain.proceed()
                attempt("read zf8", silent = true) {
                    val z = r
                    val zc = z?.javaClass
                    val ok = zc?.getMethod("a")?.invoke(z) as? Boolean
                    val err = zc?.getMethod("b")?.invoke(z) as? String
                    val cancelled = attempt("read zf8.c", silent = true) {
                        zc?.getMethod("c")?.invoke(z) as? Boolean
                    }
                    Log.i(PROBE, "P1 Lag8.c() -> ok=$ok cancelled=$cancelled err=${err?.take(200)}")
                }
                r
            }
        }
    }

    private fun probeExecuteUpload(module: XposedModule, cl: ClassLoader) {
        attempt("probe g62.f", silent = true) {
            val k = cl.loadClass(P_G62)
            val m = k.declaredMethods.first { it.name == "f" && it.parameterCount == 0 }
            module.hookTracked(m, idPrefix = "probe-g62-f").intercept { chain ->
                Log.i(PROBE, "P2 g62.f() reached")
                chain.proceed()
            }
        }
        attempt("probe g62.i", silent = true) {
            val k = cl.loadClass(P_G62)
            val m = k.declaredMethods.first {
                it.name == "i" && it.parameterCount == 2 && it.parameterTypes[0] == String::class.java
            }
            module.hookTracked(m, idPrefix = "probe-g62-i").intercept { chain ->
                Log.i(PROBE, "P3 g62.i() file=${chain.args.getOrNull(0)}")
                chain.proceed()
            }
        }
    }

    private fun probePutLayer(module: XposedModule, cl: ClassLoader) {
        attempt("probe mj1.e", silent = true) {
            val k = cl.loadClass(P_MJ1)
            val m = k.declaredMethods.firstOrNull {
                it.name == "e" && it.parameterCount == 4 && it.parameterTypes[1] == String::class.java
            } ?: k.methods.firstOrNull {
                it.name == "e" && it.parameterCount == 4 && it.parameterTypes[1] == String::class.java
            }
            if (m != null) {
                module.hookTracked(m, idPrefix = "probe-mj1-e").intercept { chain ->
                    Log.i(PROBE, "P4 mj1.e() put path=${chain.args.getOrNull(1)} this=${chain.thisObject?.javaClass?.simpleName}")
                    chain.proceed()
                }
            }
        }
    }

    private fun probeWebDavChunk(module: XposedModule, cl: ClassLoader) {
        attempt("probe vq8.x", silent = true) {
            val k = cl.loadClass(P_VQ8)
            val m = k.declaredMethods.firstOrNull {
                it.name == "x" && java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 5 && it.parameterTypes[2] == String::class.java
            }
            if (m != null) {
                module.hookTracked(m, idPrefix = "probe-vq8-x").intercept { chain ->
                    Log.i(PROBE, "P5 vq8.x() WebDAV PUT path=${chain.args.getOrNull(2)} len=${chain.args.getOrNull(3)}")
                    chain.proceed()
                }
            }
        }
    }

    private fun probeFireSynchronizer(module: XposedModule, cl: ClassLoader) {
        attempt("probe cf3.a", silent = true) {
            val k = cl.loadClass(P_CF3)
            val m = k.declaredMethods.firstOrNull {
                it.name == "a" && it.parameterCount == 2 && it.parameterTypes[1] == java.lang.Boolean.TYPE
            }
            if (m != null) {
                module.hookTracked(m, idPrefix = "probe-cf3-a").intercept { chain ->
                    val r = chain.proceed()
                    Log.i(PROBE, "P6 cf3.a(readReference) -> ${r?.javaClass?.simpleName} (ref=${chain.args.getOrNull(0)?.javaClass?.simpleName})")
                    r
                }
            }
        }
        attempt("probe cf3.b", silent = true) {
            val k = cl.loadClass(P_CF3)
            val m = k.declaredMethods.firstOrNull { it.name == "b" && it.parameterCount == 2 }
            if (m != null) {
                module.hookTracked(m, idPrefix = "probe-cf3-b").intercept { chain ->
                    val r = chain.proceed()
                    Log.i(PROBE, "P7 cf3.b(runTransaction) -> ${r?.javaClass?.simpleName}")
                    r
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
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
