package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager

/**
 * Local API-102 hook entry.
 *
 * Migrated from the old HookEntry/XposedModule architecture
 * to the upstream HookHandler architecture.
 */
@Keep
object ModHook : HookHandler {

    private const val TAG = "SBLocalMod"
    private const val PKG = "org.swiftapps.swiftbackup"

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
                classLoader = classLoader
            )
        } catch (t: Throwable) {
            Log.e(TAG, "local hook setup failed", t)
        }
    }

    private fun applyLocalHooks(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader
    ) {
        /*
         * API-102 hooks belonging to the local modification go here.
         *
         * The important architectural change from the old Java version is:
         *
         * old:
         *     class HookEntry : XposedModule()
         *
         * new:
         *     object ModHook : HookHandler
         *
         * and hooks are registered through the upstream module instance:
         *
         *     module.hookTracked(...)
         *
         * This keeps a single XposedModule entry point and lets the
         * upstream Module.kt manage hook handles and hot reload.
         */

        Log.i(TAG, "local hook initialization complete")
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
            Log.w(TAG, "failed to load $name", t)
            null
        }
    }
}