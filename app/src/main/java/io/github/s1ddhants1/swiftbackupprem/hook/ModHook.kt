package io.github.s1ddhants1.swiftbackupprem.hook

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.github.libxposed.api.XposedModule
import io.github.s1ddhants1.swiftbackupprem.Consts
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager

/**
 * Local modification hook entry.
 *
 * This file is injected into the upstream project by:
 *
 *     mod/apply.sh
 *
 * It is intentionally implemented as the upstream HookHandler
 * instead of creating another XposedModule entry point.
 */
@Keep
object ModHook : HookHandler {

    override fun apply(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
        targets: ResolvedTargets,
        prefs: PreferencesManager
    ) {
        Log.i(Consts.TAG, "Applying local ModHook")

        /*
         * Put your API-102-compatible test hooks here.
         *
         * Important:
         *
         * 1. Do NOT create another XposedModule.
         * 2. Do NOT add another java_init.list entry.
         * 3. Use the upstream module instance passed as `module`.
         * 4. Use the upstream HookHandler / hookTracked infrastructure.
         *
         * Example structure:
         *
         * val target = classLoader.loadClass("your.test.Class")
         *
         * target.getDeclaredMethod("yourTestMethod")
         *     .let { method ->
         *         module.hookTracked(
         *             method,
         *             idPrefix = "local-test-hook"
         *         ).intercept { chain ->
         *             chain.proceed()
         *         }
         *     }
         */
    }
}