package io.github.lcebot.clipsync.xposed;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Classic Xposed API entry — kept as reference / fallback, currently NOT declared (assets/xposed_init
 * is empty, no xposedmodule meta-data); the active entry is {@link Entry} (libxposed API 102).
 * To switch back: put this class name into assets/xposed_init and restore the meta-data
 * (xposedmodule, xposedminversion 93, xposedscope = @array/xposedscope, i.e. "android").
 * The logic lives in {@link Common}; this class only installs the hooks.
 *
 * <p>system_server is AOT-compiled and the small private methods we hook
 * ({@code clipboardAccessAllowed}, {@code setPrimaryClipInternalLocked}) get inlined into their
 * callers, where a hook on the callee never runs. LSPosed exposes
 * {@code XposedBridge.deoptimizeMethod(Member)} for exactly this; it is called reflectively on
 * every method of the classes involved before hooking (small classes, one-off cost).
 */
public class LegacyEntry implements IXposedHookLoadPackage {

    private static Method deopt;        // XposedBridge.deoptimizeMethod(Member), LSPosed-specific

    private static int deoptAll(Class<?> c) {
        if (deopt == null) {
            try {
                deopt = XposedBridge.class.getDeclaredMethod("deoptimizeMethod", Member.class);
                deopt.setAccessible(true);
            } catch (Throwable t) {
                return -1;                                       // framework without deoptimize support
            }
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isAbstract(m.getModifiers()) || Modifier.isNative(m.getModifiers())) continue;
            try { deopt.invoke(null, m); n++; } catch (Throwable ignored) { }
        }
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            try { deopt.invoke(null, k); n++; } catch (Throwable ignored) { }
        }
        return n;
    }

    private static int deoptNamed(Class<?> c, String prefix) {
        if (deopt == null && deoptAll(Object.class) < 0) return -1;
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().startsWith(prefix)) continue;
            try { deopt.invoke(null, m); n++; } catch (Throwable ignored) { }
        }
        return n;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) return;
        ClassLoader cl = lpparam.classLoader;
        List<String> hooked = new ArrayList<>();
        Log.i(Common.TAG, "module loaded in system_server (legacy API)");

        // ---- clipboard: background access + no toast + push every change to SyncService
        try {
            Class<?> svc = cl.loadClass(Common.CLIP_SVC);
            int d = deoptAll(svc);
            for (Class<?> inner : svc.getDeclaredClasses()) d += Math.max(0, deoptAll(inner));   // ClipboardImpl (binder stub)
            hooked.add("deopt ClipboardService ×" + d);
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
                        final Object skip = m.getReturnType() == boolean.class ? Boolean.FALSE : null;   // boolean on 15
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                if (Common.PKG.equals(p.args[0])) p.setResult(skip);
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

        // ---- keep-alive: exempt our ProcessRecord from the freezer; refuse setProcessFrozen for our uid
        try {
            Class<?> pr = cl.loadClass("com.android.server.am.ProcessRecord");
            try {
                deoptNamed(cl.loadClass("com.android.server.am.ProcessList"), "newProcessRecord");   // the constructor's caller
            } catch (Throwable ignored) {
            }
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

        // ---- watchdog: restart the service when our process dies
        int died = 0;
        try {
            Class<?> ams = cl.loadClass("com.android.server.am.ActivityManagerService");
            deoptNamed(ams, "appDiedLocked");
            deoptNamed(ams, "handleAppDiedLocked");
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
