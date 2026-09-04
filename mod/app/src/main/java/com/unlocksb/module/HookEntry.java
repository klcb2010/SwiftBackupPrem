package com.unlocksb.module;

import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * Swift Backup 免强制 Google 登录（通杀版）
 * 适配 Swift Backup 多个版本，动态查找类和方法
 */
public class HookEntry extends XposedModule {

    private static final String TAG = "SBNoLogin";
    private static final String PKG = "org.swiftapps.swiftbackup";

    // 缓存反射对象
    private static Object cachedVInstance = null;
    private static Method cachedGetNon = null;
    private static Method cachedSetNon = null;
    private static Object cachedAnonHelper = null;
    private static Method cachedAnonDMethod = null;
    private static Class<?> cachedMFirebaseUser = null;

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!PKG.equals(param.getPackageName())) {
            return;
        }
        ClassLoader cl = param.getDefaultClassLoader();
        try {
            hookIsAnonymous(cl);
            hookAutoLogin(cl);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook setup failed", t);
        }
    }

    /** 云功能门禁：让 MFirebaseUser.isAnonymous() 恒为 false */
    private void hookIsAnonymous(ClassLoader cl) throws Exception {
        // 动态查找 MFirebaseUser 类（支持多个可能的类名）
        Class<?> mfu = findClass(cl, "org.swiftapps.swiftbackup.anonymous.MFirebaseUser");
        if (mfu == null) {
            mfu = findClass(cl, "org.swiftapps.swiftbackup.anonymous.MFirebaseUserKt");
        }
        if (mfu == null) {
            log(Log.WARN, TAG, "MFirebaseUser not found, skip isAnonymous hook");
            return;
        }
        cachedMFirebaseUser = mfu;
        Method isAnonymous = mfu.getDeclaredMethod("isAnonymous");
        hook(isAnonymous).setPriority(PRIORITY_HIGHEST).intercept(chain -> false);
        log(Log.INFO, TAG, "MFirebaseUser.isAnonymous -> false");
    }

    /** 自动匿名登录：进入 IntroActivity 前若未登录则注入本地匿名用户 */
    private void hookAutoLogin(ClassLoader cl) throws Exception {
        // 动态查找 IntroActivity（支持多个可能的类名）
        Class<?> intro = findClass(cl, "org.swiftapps.swiftbackup.intro.IntroActivity");
        if (intro == null) {
            intro = findClass(cl, "org.swiftapps.swiftbackup.activities.IntroActivity");
        }
        if (intro == null) {
            intro = findClass(cl, "org.swiftapps.swiftbackup.ui.IntroActivity");
        }
        if (intro == null) {
            log(Log.WARN, TAG, "IntroActivity not found, skip auto login hook");
            return;
        }

        Method onCreate = intro.getDeclaredMethod("onCreate", Bundle.class);
        hook(onCreate).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
            ensureLoggedIn(cl);
            return chain.proceed();
        });
        log(Log.INFO, TAG, "IntroActivity.onCreate hooked (auto anonymous login)");
    }

    /** 若会话为空，写入 anonymous.a.b.d() 构造的匿名用户 */
    private void ensureLoggedIn(ClassLoader cl) {
        try {
            // 1. 动态查找 V 类
            Class<?> vCls = findClass(cl, "org.swiftapps.swiftbackup.common.V");
            if (vCls == null) {
                vCls = findClass(cl, "org.swiftapps.swiftbackup.common.v");
            }
            if (vCls == null) {
                log(Log.WARN, TAG, "V class not found");
                return;
            }

            // 2. 获取 INSTANCE
            if (cachedVInstance == null) {
                cachedVInstance = vCls.getField("INSTANCE").get(null);
            }
            if (cachedVInstance == null) {
                log(Log.WARN, TAG, "V.INSTANCE is null");
                return;
            }

            // 3. getNon 检查是否已登录
            if (cachedGetNon == null) {
                cachedGetNon = vCls.getMethod("getNon");
            }
            if (cachedGetNon.invoke(cachedVInstance) != null) {
                return;
            }

            // 4. 动态查找 anonymous.a 类
            Class<?> aCls = findClass(cl, "org.swiftapps.swiftbackup.anonymous.a");
            if (aCls == null) {
                aCls = findClass(cl, "org.swiftapps.swiftbackup.anonymous.i");
            }
            if (aCls == null) {
                log(Log.WARN, TAG, "anonymous.a not found");
                return;
            }

            // 5. 获取字段 b
            if (cachedAnonHelper == null) {
                cachedAnonHelper = aCls.getField("b").get(null);
            }
            if (cachedAnonHelper == null) {
                log(Log.WARN, TAG, "anonymous.a.b is null");
                return;
            }

            // 6. 调用 d() 获取匿名用户
            if (cachedAnonDMethod == null) {
                cachedAnonDMethod = cachedAnonHelper.getClass().getMethod("d");
            }
            Object anonUser = cachedAnonDMethod.invoke(cachedAnonHelper);
            if (anonUser == null) {
                log(Log.WARN, TAG, "anonymous user is null");
                return;
            }

            // 7. setNon 注入
            if (cachedSetNon == null) {
                cachedSetNon = vCls.getMethod("setNon", anonUser.getClass());
            }
            cachedSetNon.invoke(cachedVInstance, anonUser);
            log(Log.INFO, TAG, "anonymous login injected, uid=" + safeUid(anonUser));

        } catch (Throwable t) {
            log(Log.WARN, TAG, "ensureLoggedIn failed (non-fatal)", t);
        }
    }

    /** 动态查找类，支持多个可能的类名 */
    private Class<?> findClass(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private String safeUid(Object user) {
        try {
            Method m = user.getClass().getMethod("getUid");
            Object r = m.invoke(user);
            return r == null ? "null" : r.toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }
}