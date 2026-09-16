package io.github.lcebot.clipsync.xposed;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Runs inside system_server (scope: "android").
 * <p>
 * Clipboard — two hooks on com.android.server.clipboard.ClipboardService:
 * <ul>
 *   <li>clipboardAccessAllowed(int op, String callingPackage, ...)  -> true for our package,
 *       so SyncService can read/write the clipboard and receive change callbacks in background.</li>
 *   <li>showAccessNotificationLocked(String callingPackage, ...)     -> skipped for our package,
 *       so Android 12+ does not toast "ClipSync pasted from ..." on every read.</li>
 * </ul>
 * Methods are matched by name only; the package argument sits at the same index on Android 10-15.
 * <p>
 * Keep-alive — the process must never be frozen, or the clipboard listener, the network loop and
 * the pinger all stop until the user reopens the app (seen as EOF from the PC after 90 s).
 * Two layers, both cheap:
 * <ul>
 *   <li>Declarative: when a ProcessRecord for our package is constructed (once per process start),
 *       flag its ProcessCachedOptimizerRecord with setShouldNotFreeze/setFreezeExempt. The AOSP
 *       freezer (CachedAppOptimizer.freezeAppAsync*) consults that flag itself and skips the whole
 *       chain — binder freeze included — so nothing needs to be intercepted on the hot path.</li>
 *   <li>Safety net: android.os.Process.setProcessFrozen(pid, uid, frozen) -> no-op when frozen=true
 *       and uid is ours. This is the Java choke point vendor "power keepers" that bypass
 *       CachedAppOptimizer still end up in. Our uid is resolved once from the system context and
 *       compared as an int; only if that lookup failed do we fall back to /proc/pid/cmdline.</li>
 * </ul>
 * Neither helps against a vendor freezer implemented natively (cgroup writes from C++); that path
 * never enters Java and cannot be hooked here.
 */
public class Entry extends XposedModule {

    private static final String TAG = "ClipSync";
    private static final String PKG = "io.github.lcebot.clipsync";
    private static final String CLIP_SVC = "com.android.server.clipboard.ClipboardService";

    private static final String SERVICE = PKG + ".SyncService";
    private static final String AUTOSTART = PKG + ".BootReceiver";      // enabled = auto-start wanted
    private static final long WATCHDOG_MS = 20_000;

    @Override
    public void onSystemServerStarting(@NonNull XposedModuleInterface.SystemServerStartingParam param) {
        installClipboard(param.getClassLoader());
        installKeepAlive(param.getClassLoader());
        startWatchdog();
    }

    // ------------------------------------------------------------------ watchdog (restart if killed)
    /**
     * system_server outlives everything, so it is the right place to notice that the sync service
     * is gone — killed by a ROM, force-stopped, crashed — and bring it back.  Every 20 s (after the
     * user is unlocked): if SyncService is not running and the app's BootReceiver component is
     * enabled (Stop in the app disables it), clear the package's "stopped" state and start the
     * service.  Nothing to hook, nothing on a hot path.
     */
    private void startWatchdog() {
        Thread t = new Thread(() -> {
            android.content.Context ctx = null;
            while (true) {
                try {
                    Thread.sleep(WATCHDOG_MS);
                    if (ctx == null) ctx = systemContext();
                    if (ctx == null) continue;
                    android.os.UserManager um = ctx.getSystemService(android.os.UserManager.class);
                    if (um != null && !um.isUserUnlocked()) continue;          // app data still encrypted
                    if (!autoStartWanted(ctx) || serviceRunning(ctx)) continue;
                    unstop(ctx);
                    android.content.Intent i = new android.content.Intent().setClassName(PKG, SERVICE);
                    ctx.startForegroundService(i);
                    Log.i(TAG, "watchdog: SyncService was not running, started it");
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable e) {
                    Log.w(TAG, "watchdog: " + e);
                }
            }
        }, "clipsync-watchdog");
        t.setDaemon(true);
        t.start();
    }

    private static android.content.Context systemContext() {
        try {
            Object at = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null);
            return at == null ? null : (android.content.Context) at.getClass().getMethod("getSystemContext").invoke(at);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean autoStartWanted(android.content.Context ctx) {
        try {
            int s = ctx.getPackageManager().getComponentEnabledSetting(new android.content.ComponentName(PKG, AUTOSTART));
            return s != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) {
            return false;                       // package not installed
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean serviceRunning(android.content.Context ctx) {
        android.app.ActivityManager am = ctx.getSystemService(android.app.ActivityManager.class);
        if (am == null) return true;
        for (android.app.ActivityManager.RunningServiceInfo s : am.getRunningServices(Integer.MAX_VALUE)) {
            if (SERVICE.equals(s.service.getClassName())) return true;
        }
        return false;
    }

    /** A force-stopped package receives nothing and cannot be started until the flag is cleared. */
    private static void unstop(android.content.Context ctx) {
        try {
            Object pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
            int user = android.os.Process.myUserHandle().hashCode();
            pm.getClass().getMethod("setPackageStoppedState", String.class, boolean.class, int.class)
                    .invoke(pm, PKG, false, user);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ clipboard
    private void installClipboard(ClassLoader cl) {
        try {
            Class<?> svc = cl.loadClass(CLIP_SVC);
            List<String> hooked = new ArrayList<>();
            for (Method m : svc.getDeclaredMethods()) {
                switch (m.getName()) {
                    case "clipboardAccessAllowed" -> {
                        hook(m).setId("access")
                                .setExceptionMode(ExceptionMode.PROTECTIVE)
                                .intercept(chain -> PKG.equals(chain.getArg(1))
                                        ? Boolean.TRUE : chain.proceed());
                        hooked.add(m.getName());
                    }
                    case "showAccessNotificationLocked" -> {
                        hook(m).setId("toast")
                                .setExceptionMode(ExceptionMode.PROTECTIVE)
                                .intercept(chain -> PKG.equals(chain.getArg(0))
                                        ? null : chain.proceed());
                        hooked.add(m.getName());
                    }
                    default -> { }
                }
            }
            log(Log.INFO, TAG, "hooked " + hooked);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "clipboard hook failed", t);
        }
    }

    // ------------------------------------------------------------------ keep-alive
    private void installKeepAlive(ClassLoader cl) {
        List<String> hooked = new ArrayList<>();
        // 1. declarative exemption: flag our ProcessRecord when it is created (rare event)
        try {
            Class<?> pr = cl.loadClass("com.android.server.am.ProcessRecord");
            for (Constructor<?> c : pr.getDeclaredConstructors()) {
                hook(c).setId("pr")
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object r = chain.proceed();
                            Object rec = chain.getThisObject();
                            if (isOurs(rec)) exempt(rec);
                            return r;
                        });
            }
            hooked.add("ProcessRecord.<init> -> shouldNotFreeze");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "ProcessRecord hook failed: " + t);
        }
        // 2. safety net at the Java choke point, keyed by uid (an int compare per call)
        try {
            for (Method m : android.os.Process.class.getDeclaredMethods()) {
                if (!m.getName().equals("setProcessFrozen") || !Modifier.isStatic(m.getModifiers())) continue;
                hook(m).setId("freeze")
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (Boolean.TRUE.equals(chain.getArg(2)) && isOurUid(chain.getArg(1), chain.getArg(0))) {
                                return null;                        // refuse to freeze ClipSync
                            }
                            return chain.proceed();
                        });
                hooked.add("Process.setProcessFrozen");
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Process.setProcessFrozen hook failed: " + t);
        }
        log(Log.INFO, TAG, "keep-alive hooks " + hooked);
    }

    private static volatile boolean exemptLogged;

    /** Tell the freezer itself to leave this record alone (field/method names vary by release). */
    private static void exempt(Object processRecord) {
        try {
            Object opt = field(processRecord, "mOptRecord");
            if (opt == null) return;
            for (String setter : new String[]{"setShouldNotFreeze", "setFreezeExempt"}) {
                try {
                    Method m = opt.getClass().getDeclaredMethod(setter, boolean.class);
                    m.setAccessible(true);
                    m.invoke(opt, true);
                    if (!exemptLogged) {
                        exemptLogged = true;
                        Log.i(TAG, "process record exempted from freezing via " + setter);
                    }
                    return;
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** ProcessRecord -> is it our app? (reads processName / info.packageName reflectively) */
    private static boolean isOurs(Object processRecord) {
        if (processRecord == null) return false;
        try {
            Object name = field(processRecord, "processName");     // PKG or PKG:sync
            if (name instanceof String && ((String) name).startsWith(PKG)) return true;
            Object info = field(processRecord, "info");
            return info != null && PKG.equals(field(info, "packageName"));
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile int ourUid = -1;
    private static volatile long uidLookupAt;

    /** uid == ours? Resolved once from the system context; refreshed every 10 min in case of a reinstall. */
    private static boolean isOurUid(Object uidArg, Object pidArg) {
        int uid = uidArg instanceof Integer ? (Integer) uidArg : -1;
        long now = android.os.SystemClock.elapsedRealtime();
        if (ourUid < 0 || now - uidLookupAt > 600_000L) {
            uidLookupAt = now;
            try {
                Object at = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null);
                android.content.Context ctx = (android.content.Context) at.getClass().getMethod("getSystemContext").invoke(at);
                ourUid = ctx.getPackageManager().getPackageUid(PKG, 0);
            } catch (Throwable t) {
                ourUid = -1;
            }
        }
        if (ourUid >= 0) return uid == ourUid;
        return pidArg instanceof Integer && isOurs((Integer) pidArg);      // fallback: /proc/pid/cmdline
    }

    /** pid -> is it our app? /proc/pid/cmdline carries the process name, i.e. the package. */
    private static boolean isOurs(int pid) {
        try (FileInputStream in = new FileInputStream("/proc/" + pid + "/cmdline")) {
            byte[] b = new byte[256];
            int n = in.read(b);
            if (n <= 0) return false;
            int end = 0;
            while (end < n && b[end] != 0) end++;
            return new String(b, 0, end).startsWith(PKG);          // PKG or PKG:sync
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object field(Object o, String name) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
