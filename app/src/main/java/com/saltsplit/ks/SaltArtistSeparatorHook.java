package com.saltsplit.ks;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import dalvik.system.DexFile;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

@SuppressWarnings("unused")
public final class SaltArtistSeparatorHook implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.salt.music";
    private static final String[] ARTIST_SPLIT_CLASSES = {
            "androidx.obf.mu3",
            "androidx.obf.l64",
            "androidx.obf.x64",
            "androidx.obf.y64",
            "androidx.obf.l74"
    };
    private static final String ARTIST_DAO_IMPL_CLASS = "com.salt.music.data.dao.ArtistDao_Impl";
    private static final String ARTIST_ENTRY_CLASS = "com.salt.music.data.entry.Artist";
    private static final String SALT_PREFS_NAME = "saltsplit_artist_separator";
    private static final String KEY_APPLIED_SIGNATURE = "applied_signature";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static volatile Context applicationContext;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        installArtistCacheInvalidationHook();
        installArtistSplitHook(
                lpparam.classLoader,
                lpparam.appInfo == null ? null : lpparam.appInfo.sourceDir);
        installArtistDaoHook(lpparam.classLoader);
    }

    private static void installArtistSplitHook(ClassLoader classLoader, String apkPath) {
        for (String className : ARTIST_SPLIT_CLASSES) {
            if (tryInstallArtistSplitHook(classLoader, className, true)) {
                return;
            }
        }
        if (tryInstallDiscoveredArtistSplitHook(classLoader, apkPath)) {
            return;
        }
        XposedBridge.log("SaltSplit did not find a known Salt Player artist split hook target");
    }

    private static boolean tryInstallArtistSplitHook(
            ClassLoader classLoader,
            String className,
            boolean logUnavailable) {
        try {
            Class<?> targetClass = Class.forName(className, false, classLoader);
            Method method = findArtistSplitMethod(targetClass);
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String artist = (String) param.args[0];
                    ArtistSeparatorConfig config = SeparatorPreferences.loadForXposed(applicationContext);
                    List<String> artists = ArtistSeparatorRules.splitArtists(
                            artist,
                            config.separators,
                            config.excludedArtists);
                    param.setResult(artists);
                }
            });
            XposedBridge.log("SaltSplit installed Salt Player artist separator hook: "
                    + targetClass.getName() + "#" + method.getName());
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException expected) {
            if (logUnavailable) {
                XposedBridge.log("SaltSplit Salt Player artist split target unavailable: " + className);
            }
        } catch (Throwable throwable) {
            XposedBridge.log("SaltSplit failed to install Salt Player artist split hook: " + className);
            XposedBridge.log(throwable);
        }
        return false;
    }

    private static boolean tryInstallDiscoveredArtistSplitHook(ClassLoader classLoader, String apkPath) {
        if (apkPath == null || apkPath.isEmpty()) {
            return false;
        }
        DexFile dexFile = null;
        try {
            dexFile = new DexFile(apkPath);
            Enumeration<String> entries = dexFile.entries();
            while (entries.hasMoreElements()) {
                String className = entries.nextElement();
                if (isArtistSplitClassCandidate(className)
                        && tryInstallArtistSplitHook(classLoader, className, false)) {
                    XposedBridge.log("SaltSplit discovered Salt Player artist split hook target: "
                            + className);
                    return true;
                }
            }
        } catch (Throwable throwable) {
            XposedBridge.log("SaltSplit failed to discover Salt Player artist split hook target");
            XposedBridge.log(throwable);
        } finally {
            if (dexFile != null) {
                try {
                    dexFile.close();
                } catch (IOException ignored) {
                }
            }
        }
        return false;
    }

    private static void installArtistDaoHook(ClassLoader classLoader) {
        try {
            Class<?> daoClass = Class.forName(ARTIST_DAO_IMPL_CLASS, false, classLoader);
            Method method = findArtistDaoInsertAllMethod(daoClass);
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ArtistSeparatorConfig config = SeparatorPreferences.loadForXposed(applicationContext);
                    param.args[0] = ArtistInsertNormalizer.normalize(
                            param.args[0],
                            config,
                            classLoader,
                            ARTIST_ENTRY_CLASS);
                }
            });
            XposedBridge.log("SaltSplit installed Salt Player Artist DAO hook: "
                    + daoClass.getName() + "#" + method.getName());
        } catch (Throwable throwable) {
            XposedBridge.log("SaltSplit failed to install Salt Player Artist DAO hook");
            XposedBridge.log(throwable);
        }
    }

    private static void installArtistCacheInvalidationHook() {
        try {
            Method attach = android.app.Application.class.getDeclaredMethod("attach", Context.class);
            XposedBridge.hookMethod(attach, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Context context = (Context) param.args[0];
                    applicationContext = context.getApplicationContext();
                    invalidateArtistCacheIfConfigChanged(context);
                }
            });
        } catch (Throwable throwable) {
            XposedBridge.log("SaltSplit failed to install Salt Player artist cache invalidation hook");
            XposedBridge.log(throwable);
        }
    }

    private static void invalidateArtistCacheIfConfigChanged(Context context) {
        try {
            ArtistSeparatorConfig config = SeparatorPreferences.loadForXposed(context);
            XposedBridge.log("SaltSplit loaded separator config: separators="
                    + config.separators.size()
                    + ", excludedArtists="
                    + config.excludedArtists.size()
                    + ", source="
                    + config.source
                    + ", ruleVersion="
                    + ArtistCacheSignature.RULE_CACHE_VERSION);
            String appliedSignature = context.getSharedPreferences(SALT_PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_APPLIED_SIGNATURE, null);
            if (config.signature.equals(appliedSignature)) {
                return;
            }
            deleteArtistTable(context);
            context.getSharedPreferences(SALT_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_APPLIED_SIGNATURE, config.signature)
                    .apply();
            XposedBridge.log("SaltSplit cleared Salt Player Artist table after separator config change");
        } catch (Throwable throwable) {
            XposedBridge.log("SaltSplit failed to invalidate Salt Player Artist table");
            XposedBridge.log(throwable);
        }
    }

    private static void deleteArtistTable(Context context) {
        String databasePath = context.getDatabasePath("app_database").getAbsolutePath();
        SQLiteDatabase database = SQLiteDatabase.openDatabase(
                databasePath,
                null,
                SQLiteDatabase.OPEN_READWRITE);
        try {
            database.execSQL("DELETE FROM Artist");
        } finally {
            database.close();
        }
    }

    private static Method findArtistSplitMethod(Class<?> targetClass) throws NoSuchMethodException {
        Method fallback = null;
        for (Method method : targetClass.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (!Modifier.isStatic(modifiers)
                    || parameterTypes.length != 1
                    || parameterTypes[0] != String.class
                    || !List.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            if (!isSlashArtistSplitMethod(method)) {
                continue;
            }
            if (!method.isSynthetic() && !method.isBridge()) {
                return method;
            }
            fallback = method;
        }
        if (fallback != null) {
            return fallback;
        }
        throw new NoSuchMethodException(targetClass.getName() + " static List method(String)");
    }

    private static boolean isSlashArtistSplitMethod(Method method) {
        try {
            method.setAccessible(true);
            Object split = method.invoke(null, "SaltSplit_LEFT/SaltSplit_RIGHT");
            Object single = method.invoke(null, "SaltSplit_SINGLE");
            return split instanceof List<?>
                    && single instanceof List<?>
                    && ((List<?>) split).size() == 2
                    && "SaltSplit_LEFT".equals(((List<?>) split).get(0))
                    && "SaltSplit_RIGHT".equals(((List<?>) split).get(1))
                    && ((List<?>) single).size() == 1
                    && "SaltSplit_SINGLE".equals(((List<?>) single).get(0));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isArtistSplitClassCandidate(String className) {
        if (!className.startsWith("androidx.obf.")) {
            return false;
        }
        String simpleName = className.substring("androidx.obf.".length());
        if (simpleName.length() < 3 || simpleName.length() > 5) {
            return false;
        }
        // Salt Player obfuscation has used letter+digits names with different
        // digit widths, e.g. l64 and mu3, for Kotlin string helpers.
        boolean hasDigit = false;
        for (int i = 0; i < simpleName.length(); i++) {
            char c = simpleName.charAt(i);
            if (c >= '0' && c <= '9') {
                hasDigit = true;
                continue;
            }
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return hasDigit;
    }

    private static Method findArtistDaoInsertAllMethod(Class<?> daoClass)
            throws NoSuchMethodException {
        for (Method method : daoClass.getDeclaredMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if ("insertAll".equals(method.getName())
                    && parameterTypes.length == 2
                    && List.class.isAssignableFrom(parameterTypes[0])) {
                return method;
            }
        }
        throw new NoSuchMethodException(daoClass.getName() + "#insertAll(List, Continuation)");
    }
}
