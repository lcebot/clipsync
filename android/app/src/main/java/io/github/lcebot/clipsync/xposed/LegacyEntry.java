package io.github.lcebot.clipsync.xposed;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Classic Xposed API entry (assets/xposed_init) for frameworks that do not implement libxposed
 * API 100+ — LSPosed proper included. Installs the same hooks as {@link Entry}; the logic is in
 * {@link Common}. Both entries ship in the APK; whichever the framework understands loads.
 */
public class LegacyEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) return;
        ClassLoader cl = lpparam.classLoader;
        List<String> hooked = new ArrayList<>();

        // ---- clipboard
        try {
            Class<?> svc = cl.loadClass(Common.CLIP_SVC);
            for (Method m : svc.getDeclaredMethods()) {
                switch (m.getName()) {
                    case "clipboardAccessAllowed" -> {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                if (Common.PKG.equals(p.args[1])) p.setResult(Boolean.TRUE);
                            }
                        });
                        hooked.add("clipboardAccessAllowed");
                    }
                    case "showAccessNotificationLocked" -> {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                if (Common.PKG.equals(p.args[0])) p.setResult(null);
                            }
                        });
                        hooked.add("showAccessNotificationLocked");
                    }
                    default -> { }
                }
            }
            Method setter = Common.clipSetter(svc);
            if (setter != null) {
                XposedBridge.hookMethod(setter, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        Common.onClipSet(p.args);
                    }
                });
                hooked.add(setter.getName() + " -> push");
            }
        } catch (Throwable t) {
            Log.e(Common.TAG, "clipboard hook failed", t);
        }

        // ---- keep-alive
        try {
            Class<?> pr = cl.loadClass("com.android.server.am.ProcessRecord");
            for (Constructor<?> c : pr.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (Common.isOurs(p.thisObject)) Common.exempt(p.thisObject);
                    }
                });
            }
            hooked.add("ProcessRecord.<init> -> shouldNotFreeze");
        } catch (Throwable t) {
            Log.w(Common.TAG, "ProcessRecord hook failed: " + t);
        }
        try {
            for (Method m : android.os.Process.class.getDeclaredMethods()) {
                if (!m.getName().equals("setProcessFrozen") || !Modifier.isStatic(m.getModifiers())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (Boolean.TRUE.equals(p.args[2]) && Common.isOurUid(p.args[1], p.args[0])) p.setResult(null);
                    }
                });
                hooked.add("Process.setProcessFrozen");
            }
        } catch (Throwable t) {
            Log.w(Common.TAG, "Process.setProcessFrozen hook failed: " + t);
        }

        // ---- watchdog
        int died = 0;
        try {
            Class<?> ams = cl.loadClass("com.android.server.am.ActivityManagerService");
            for (Method m : ams.getDeclaredMethods()) {
                String n = m.getName();
                if (!(n.equals("handleAppDiedLocked") || n.equals("appDiedLocked")) || m.getParameterCount() < 1
                        || !m.getParameterTypes()[0].getSimpleName().equals("ProcessRecord")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (Common.isOurs(p.args[0])) Common.scheduleCheck(Common.WATCHDOG_AFTER_DEATH_MS);
                    }
                });
                died++;
            }
        } catch (Throwable t) {
            Log.w(Common.TAG, "watchdog: death hook failed: " + t);
        }
        Common.pollMs = died > 0 ? Common.POLL_WITH_HOOK_MS : Common.POLL_WITHOUT_HOOK_MS;
        Common.scheduleCheck(Common.pollMs);
        hooked.add("watchdog(death hooks " + died + ", poll " + Common.pollMs / 1000 + " s)");

        Log.i(Common.TAG, "hooked (legacy API) " + hooked);
    }
}
