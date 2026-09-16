package io.github.lcebot.clipsync.xposed;

import android.util.Log;

import java.io.FileInputStream;
import java.lang.reflect.Method;

/**
 * Everything the system_server module does, independent of which Xposed API loaded it.
 * {@link Entry} (libxposed API 100+) and {@link LegacyEntry} (classic de.robv API) are thin
 * adapters that install the same hooks and call into here. Must not reference either API.
 *
 * <ul>
 *   <li>Clipboard: writes are allowed without focus on every release, so "writing works in the
 *       background" proves nothing about the hooks. Reads and listener dispatch require focus;
 *       clipboardAccessAllowed is forced to true for our package, and on top of that every
 *       clipboard change is pushed straight into SyncService with the ClipData attached to the
 *       intent (read grants for content:// URIs included).</li>
 *   <li>Keep-alive: our ProcessRecord is flagged shouldNotFreeze when created; as a safety net
 *       Process.setProcessFrozen is a no-op for our uid.</li>
 *   <li>Watchdog: the process-death path schedules a restart check; a slow poll is the net.</li>
 * </ul>
 */
final class Common {
    private Common() {}

    static final String TAG = "ClipSync";
    static final String PKG = "io.github.lcebot.clipsync";
    static final String CLIP_SVC = "com.android.server.clipboard.ClipboardService";
    static final String SERVICE = PKG + ".SyncService";
    static final String AUTOSTART = PKG + ".BootReceiver";      // enabled = auto-start wanted
    static final String ACTION_CLIP = PKG + ".CLIP";
    static final long WATCHDOG_AFTER_DEATH_MS = 3_000;          // prompt reaction to a kill
    // poll interval: a mere safety net when the death hook is in place, the primary mechanism otherwise
    static final long POLL_WITH_HOOK_MS = 60_000, POLL_WITHOUT_HOOK_MS = 15_000;
    static volatile long pollMs = POLL_WITHOUT_HOOK_MS;

    // ------------------------------------------------------------------ one worker for all async work
    private static final android.os.HandlerThread WORKER =
            new android.os.HandlerThread("clipsync-hook", android.os.Process.THREAD_PRIORITY_BACKGROUND);

    static android.os.Handler handler() {
        synchronized (WORKER) {
            if (!WORKER.isAlive()) WORKER.start();
        }
        return new android.os.Handler(WORKER.getLooper());
    }

    static android.content.Context systemContext() {
        try {
            Object at = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null);
            return at == null ? null : (android.content.Context) at.getClass().getMethod("getSystemContext").invoke(at);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ clipboard push
    /**
     * Hand a freshly set clip to SyncService. Plain startService from system uid: never subject
     * to background limits, and a running service just gets onStartCommand. Off the binder
     * thread (outside ClipboardService's lock, system identity rather than the copying app's).
     */
    static void pushClip(android.content.ClipData clip) {
        handler().post(() -> {
            android.content.Context ctx = systemContext();
            if (ctx == null) return;
            android.content.Intent i = new android.content.Intent(ACTION_CLIP).setClassName(PKG, SERVICE);
            i.setClipData(clip);
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                ctx.startService(i);
            } catch (Throwable e) {
                // e.g. TransactionTooLarge for a huge text: send a bare trigger, the service reads
                try {
                    ctx.startService(new android.content.Intent(ACTION_CLIP).setClassName(PKG, SERVICE).putExtra("fetch", true));
                } catch (Throwable e2) {
                    Log.w(TAG, "clip push failed: " + e2);
                }
            }
        });
    }

    // ------------------------------------------------------------------ freezer exemption
    private static volatile boolean exemptLogged;

    /** Tell the freezer itself to leave this record alone (setter names vary by release). */
    static void exempt(Object processRecord) {
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

    /** ProcessRecord -> is it our app? (processName is PKG or PKG:sync) */
    static boolean isOurs(Object processRecord) {
        if (processRecord == null) return false;
        try {
            Object name = field(processRecord, "processName");
            if (name instanceof String && ((String) name).startsWith(PKG)) return true;
            Object info = field(processRecord, "info");
            return info != null && PKG.equals(field(info, "packageName"));
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile int ourUid = -1;
    private static volatile long uidLookupAt;

    /** uid == ours? Resolved once from the system context; refreshed every 10 min (reinstall). */
    static boolean isOurUid(Object uidArg, Object pidArg) {
        int uid = uidArg instanceof Integer ? (Integer) uidArg : -1;
        long now = android.os.SystemClock.elapsedRealtime();
        if (ourUid < 0 || now - uidLookupAt > 600_000L) {
            uidLookupAt = now;
            try {
                android.content.Context ctx = systemContext();
                ourUid = ctx == null ? -1 : ctx.getPackageManager().getPackageUid(PKG, 0);
            } catch (Throwable t) {
                ourUid = -1;
            }
        }
        if (ourUid >= 0) return uid == ourUid;
        return pidArg instanceof Integer && isOurPid((Integer) pidArg);      // fallback: /proc/pid/cmdline
    }

    static boolean isOurPid(int pid) {
        try (FileInputStream in = new FileInputStream("/proc/" + pid + "/cmdline")) {
            byte[] b = new byte[256];
            int n = in.read(b);
            if (n <= 0) return false;
            int end = 0;
            while (end < n && b[end] != 0) end++;
            return new String(b, 0, end).startsWith(PKG);
        } catch (Throwable t) {
            return false;
        }
    }

    static Object field(Object o, String name) throws Exception {
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

    // ------------------------------------------------------------------ watchdog
    static boolean autoStartWanted(android.content.Context ctx) {
        try {
            int s = ctx.getPackageManager().getComponentEnabledSetting(new android.content.ComponentName(PKG, AUTOSTART));
            return s != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) {
            return false;                       // package not installed
        }
    }

    @SuppressWarnings("deprecation")
    static boolean serviceRunning(android.content.Context ctx) {
        android.app.ActivityManager am = ctx.getSystemService(android.app.ActivityManager.class);
        if (am == null) return true;
        for (android.app.ActivityManager.RunningServiceInfo s : am.getRunningServices(Integer.MAX_VALUE)) {
            if (SERVICE.equals(s.service.getClassName())) return true;
        }
        return false;
    }

    /** A force-stopped package receives nothing and cannot be started until the flag is cleared. */
    static void unstop(android.content.Context ctx) {
        try {
            Object pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
            int user = android.os.Process.myUserHandle().hashCode();
            pm.getClass().getMethod("setPackageStoppedState", String.class, boolean.class, int.class)
                    .invoke(pm, PKG, false, user);
        } catch (Throwable ignored) {
        }
    }

    private static final Runnable CHECK = new Runnable() {
        @Override
        public void run() {
            try {
                android.content.Context ctx = systemContext();
                if (ctx != null) {
                    android.os.UserManager um = ctx.getSystemService(android.os.UserManager.class);
                    boolean unlocked = um == null || um.isUserUnlocked();          // app data still encrypted otherwise
                    if (unlocked && autoStartWanted(ctx) && !serviceRunning(ctx)) {
                        unstop(ctx);
                        ctx.startForegroundService(new android.content.Intent().setClassName(PKG, SERVICE));
                        Log.i(TAG, "watchdog: SyncService was not running, started it");
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "watchdog: " + e);
            }
            handler().postDelayed(this, pollMs);
        }
    };

    static void scheduleCheck(long delayMs) {
        android.os.Handler h = handler();
        h.removeCallbacks(CHECK);
        h.postDelayed(CHECK, delayMs);
    }

    /** ClipboardService's outermost clip setter (13+: …InternalLocked → …InternalNoClassifyLocked). */
    static Method clipSetter(Class<?> svc) {
        Method setter = null;
        for (Method m : svc.getDeclaredMethods()) {
            if (m.getName().equals("setPrimaryClipInternalLocked")) return m;
            if (m.getName().startsWith("setPrimaryClipInternal") && setter == null) setter = m;
        }
        return setter;
    }

    /** Pull the ClipData out of a clip-setter call and push it unless we wrote it ourselves. */
    static void onClipSet(Object[] args) {
        try {
            android.content.ClipData clip = null;
            boolean ours = false;
            for (Object a : args) {
                if (a instanceof android.content.ClipData) clip = (android.content.ClipData) a;
                else if (PKG.equals(a)) ours = true;           // sourcePackage: our own write
            }
            if (clip != null && !ours) pushClip(clip);
        } catch (Throwable ignored) {
        }
    }
}
