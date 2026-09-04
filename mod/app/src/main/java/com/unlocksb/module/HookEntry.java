package io.github.s1ddhants1.swiftbackupprem.hook

import android.os.Bundle
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import java.lang.reflect.Method

/**
 * Migrated from the original com.unlocksb.module.HookEntry.
 *
 * This file is the API-102 version maintained by the local mod/ layer.
 */
@Keep
object ModHook : HookHandler {

    private const val TAG = "SBNoLogin"
    private const val PKG = "org.swiftapps.swiftbackup"

    private var cachedClassLoader: ClassLoader? = null

    private var cachedVInstance: Any? = null
    private var cachedGetNon: Method? = null
    private var cachedSetNon: Method? = null

    private var cachedAnonHelper: Any? = null
    private var cachedAnonDMethod: Method? = null

    private var cachedMFirebaseUser: Class<*>? = null

    override fun apply(
        module: XposedModule,
        context: android.content.Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        if (context.packageName != PKG) {
            return
        }

        resetCacheIfClassLoaderChanged(classLoader)

        try {
            hookIsAnonymous(module, classLoader)
            hookAutoLogin(module, classLoader)
        } catch (t: Throwable) {
            Log.e(TAG, "hook setup failed", t)
        }
    }

    private fun resetCacheIfClassLoaderChanged(classLoader: ClassLoader) {
        if (cachedClassLoader === classLoader) {
            return
        }

        cachedClassLoader = classLoader

        cachedVInstance = null
        cachedGetNon = null
        cachedSetNon = null
        cachedAnonHelper = null
        cachedAnonDMethod = null
        cachedMFirebaseUser = null
    }

    /**
     * Cloud feature gate:
     * make MFirebaseUser.isAnonymous() return false.
     */
    private fun hookIsAnonymous(
        module: XposedModule,
        classLoader: ClassLoader
    ) {
        var mfu = cachedMFirebaseUser

        if (mfu == null) {
            mfu = findClass(
                classLoader,
                "org.swiftapps.swiftbackup.anonymous.MFirebaseUser"
            )

            if (mfu == null) {
                mfu = findClass(
                    classLoader,
                    "org.swiftapps.swiftbackup.anonymous.MFirebaseUserKt"
                )
            }

            cachedMFirebaseUser = mfu
        }

        if (mfu == null) {
            Log.w(TAG, "MFirebaseUser not found, skip isAnonymous hook")
            return
        }

        val isAnonymous = try {
            mfu.getDeclaredMethod("isAnonymous")
        } catch (t: Throwable) {
            Log.w(TAG, "MFirebaseUser.isAnonymous not found", t)
            return
        }

        module.hookTracked(
            isAnonymous,
            idPrefix = "mod-is-anonymous",
            priority = XposedInterface.PRIORITY_HIGHEST
        ).intercept {
            false
        }

        Log.i(TAG, "MFirebaseUser.isAnonymous -> false")
    }

    /**
     * Entering IntroActivity:
     * if the local session is empty, populate it with the locally
     * constructed anonymous user.
     */
    private fun hookAutoLogin(
        module: XposedModule,
        classLoader: ClassLoader
    ) {
        var intro = findClass(
            classLoader,
            "org.swiftapps.swiftbackup.intro.IntroActivity"
        )

        if (intro == null) {
            intro = findClass(
                classLoader,
                "org.swiftapps.swiftbackup.activities.IntroActivity"
            )
        }

        if (intro == null) {
            intro = findClass(
                classLoader,
                "org.swiftapps.swiftbackup.ui.IntroActivity"
            )
        }

        if (intro == null) {
            Log.w(TAG, "IntroActivity not found, skip auto login hook")
            return
        }

        val onCreate = try {
            intro.getDeclaredMethod("onCreate", Bundle::class.java)
        } catch (t: Throwable) {
            Log.w(TAG, "IntroActivity.onCreate not found", t)
            return
        }

        module.hookTracked(
            onCreate,
            idPrefix = "mod-intro-on-create",
            priority = XposedInterface.PRIORITY_HIGHEST
        ).intercept { chain ->
            ensureLoggedIn(classLoader)
            chain.proceed()
        }

        Log.i(TAG, "IntroActivity.onCreate hooked")
    }

    /**
     * If the current session is empty, construct the anonymous user
     * through anonymous.a.b.d() and place it into V.setNon().
     */
    private fun ensureLoggedIn(classLoader: ClassLoader) {
        try {
            /*
             * 1. Locate V.
             */
            var vClass = findClass(
                classLoader,
                "org.swiftapps.swiftbackup.common.V"
            )

            if (vClass == null) {
                vClass = findClass(
                    classLoader,
                    "org.swiftapps.swiftbackup.common.v"
                )
            }

            if (vClass == null) {
                Log.w(TAG, "V class not found")
                return
            }

            /*
             * 2. Obtain V.INSTANCE.
             */
            if (cachedVInstance == null) {
                val instanceField = try {
                    vClass.getField("INSTANCE")
                } catch (_: Throwable) {
                    vClass.getDeclaredField("INSTANCE").apply {
                        isAccessible = true
                    }
                }

                cachedVInstance = instanceField.get(null)
            }

            val vInstance = cachedVInstance

            if (vInstance == null) {
                Log.w(TAG, "V.INSTANCE is null")
                return
            }

            /*
             * 3. Check V.getNon().
             */
            if (cachedGetNon == null) {
                cachedGetNon = vClass.getMethod("getNon")
            }

            val currentUser = cachedGetNon?.invoke(vInstance)

            if (currentUser != null) {
                return
            }

            /*
             * 4. Locate anonymous.a / anonymous.i.
             */
            var aClass = findClass(
                classLoader,
                "org.swiftapps.swiftbackup.anonymous.a"
            )

            if (aClass == null) {
                aClass = findClass(
                    classLoader,
                    "org.swiftapps.swiftbackup.anonymous.i"
                )
            }

            if (aClass == null) {
                Log.w(TAG, "anonymous.a/i not found")
                return
            }

            /*
             * 5. Obtain static field b.
             */
            if (cachedAnonHelper == null) {
                val bField = try {
                    aClass.getField("b")
                } catch (_: Throwable) {
                    aClass.getDeclaredField("b").apply {
                        isAccessible = true
                    }
                }

                cachedAnonHelper = bField.get(null)
            }

            val helper = cachedAnonHelper

            if (helper == null) {
                Log.w(TAG, "anonymous.a.b is null")
                return
            }

            /*
             * 6. Call b.d().
             */
            if (cachedAnonDMethod == null) {
                cachedAnonDMethod = helper.javaClass.getMethod("d")
            }

            val anonUser = cachedAnonDMethod?.invoke(helper)

            if (anonUser == null) {
                Log.w(TAG, "anonymous user is null")
                return
            }

            /*
             * 7. V.setNon(anonymousUser).
             *
             * Resolve the method by name and parameter compatibility instead
             * of requiring an exact Java reflection class match.
             */
            if (cachedSetNon == null) {
                cachedSetNon = vClass.methods.firstOrNull { method ->
                    method.name == "setNon" &&
                        method.parameterCount == 1 &&
                        (
                            method.parameterTypes[0].isAssignableFrom(
                                anonUser.javaClass
                            ) ||
                                method.parameterTypes[0] == anonUser.javaClass
                            )
                }

                if (cachedSetNon == null) {
                    cachedSetNon = vClass.declaredMethods.firstOrNull { method ->
                        method.name == "setNon" &&
                            method.parameterCount == 1 &&
                            (
                                method.parameterTypes[0].isAssignableFrom(
                                    anonUser.javaClass
                                ) ||
                                    method.parameterTypes[0] == anonUser.javaClass
                                )
                    }?.apply {
                        isAccessible = true
                    }
                }
            }

            val setNon = cachedSetNon

            if (setNon == null) {
                Log.w(TAG, "V.setNon(user) not found")
                return
            }

            setNon.invoke(vInstance, anonUser)

            Log.i(
                TAG,
                "anonymous login injected, uid=${safeUid(anonUser)}"
            )
        } catch (t: Throwable) {
            /*
             * Keep this hook non-fatal. A changed Swift Backup version
             * should not bring down the host process.
             */
            Log.w(TAG, "ensureLoggedIn failed (non-fatal)", t)
        }
    }

    private fun findClass(
        classLoader: ClassLoader,
        name: String
    ): Class<*>? {
        return try {
            Class.forName(name, false, classLoader)
        } catch (_: ClassNotFoundException) {
            null
        } catch (t: Throwable) {
            Log.w(TAG, "failed loading class: $name", t)
            null
        }
    }

    private fun safeUid(user: Any): String {
        return try {
            val method = user.javaClass.methods.firstOrNull {
                it.name == "getUid" && it.parameterCount == 0
            } ?: user.javaClass.declaredMethods.firstOrNull {
                it.name == "getUid" && it.parameterCount == 0
            }

            if (method == null) {
                "?"
            } else {
                method.isAccessible = true
                method.invoke(user)?.toString() ?: "null"
            }
        } catch (_: Throwable) {
            "?"
        }
    }
}