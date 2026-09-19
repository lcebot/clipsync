package io.github.lcebot.clipsync.xposed;

import android.util.Log;

import java.io.FileInputStream;
import java.lang.reflect.Method;

/**
 * Everything the system_server module does, independent of the Xposed API that loaded it.
 * {@link Entry} installs the hooks and calls into here. Must not reference any Xposed API — that
 * separation is what let a second entry point exist for the classic API, and is worth keeping now
 * that it does not: the logic is testable and readable without an Xposed type in sight.
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

    /**
     * ONE Handler, shared by everything here. It has to be one object, not one per call.
     *
     * <p>{@code MessageQueue.removeMessages} matches on {@code msg.target == handler}, and the
     * target is whichever Handler <em>posted</em> the message. A fresh Handler per call still shares
     * the looper, so posting worked — but {@link #scheduleCheck}'s {@code removeCallbacks} was
     * asking a brand new Handler to cancel a message posted by a different one, which never matched.
     * Every install and every process death therefore left another never-ending 60-second self-
     * rescheduling chain inside system_server, each one calling getRunningServices(MAX_VALUE) — a
     * full sweep of every running service — once a minute, for the life of the boot.
     *
     * <p>Double-checked on a volatile field because this is reached from binder threads and from the
     * worker itself; the inner lock on WORKER is the one that keeps the thread from being started
     * twice.
     */
    private static volatile android.os.Handler HANDLER;

    static android.os.Handler handler() {
        android.os.Handler h = HANDLER;
        if (h != null) return h;
        synchronized (Common.class) {
            if (HANDLER == null) {
                synchronized (WORKER) {
                    if (!WORKER.isAlive()) WORKER.start();
                }
                HANDLER = new android.os.Handler(WORKER.getLooper());
            }
            return HANDLER;
        }
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

    /**
     * ClipboardService's clip setter. On the releases this app installs on (minSdk 35 = Android 15
     * and up) there are TWO methods named exactly {@code setPrimaryClipInternalLocked}, verified
     * against AOSP {@code services/core/java/com/android/server/clipboard/ClipboardService.java}
     * (tags android-14.0.0_r18 / android-15.0.0_r1 — 14 is no longer a supported target, but the
     * shape is the same on both, so the older tag is kept as corroboration):
     * <ul>
     *   <li>{@code setPrimaryClipInternalLocked(ClipData clip, int uid, int deviceId, String sourcePackage)}
     *   <li>{@code setPrimaryClipInternalLocked(Clipboard clipboard, ClipData clip, int uid, String sourcePackage)}
     *       (the first calls the second for the current user; related profiles go via
     *       {@code setPrimaryClipInternalNoClassifyLocked}, so exactly ONE of these fires per set)
     * </ul>
     * We return whichever exact-name match {@code getDeclaredMethods()} yields first; both carry the
     * clip as their only ClipData and sourcePackage as their last String, so {@link #clipArg} and
     * {@link #sourceArg} resolve correctly either way and a set is pushed exactly once.
     */
    static Method clipSetter(Class<?> svc) {
        Method setter = null;
        for (Method m : svc.getDeclaredMethods()) {
            if (m.getName().equals("setPrimaryClipInternalLocked")) return m;
            if (m.getName().startsWith("setPrimaryClipInternal") && setter == null) setter = m;
        }
        return setter;
    }

    /**
     * Where the ClipData sits in the clip setter's parameter list, or -1.
     *
     * <p>First ClipData parameter. Every known overload has exactly one, so "first" and "the one"
     * are the same thing; taking the first rather than the last is what stops a future overload with
     * an extra ClipData (a previous value, say) from being read as the new clip.
     */
    static int clipArg(Method m) {
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) if (p[i] == android.content.ClipData.class) return i;
        return -1;
    }

    /**
     * Where {@code sourcePackage} sits in the clip setter's parameter list, or -1 if it has none.
     *
     * <p>Resolved from the signature, once, at hook time — and that is the point of it. This used to
     * be "scan every argument for a String equal to our package name", which is a different question
     * with the same answer most of the time: it also says "ours" for a clip whose *label* or whose
     * calling-package parameter happens to be our package name, and it silently stops working the
     * day an overload puts our name somewhere else. Reading a known position cannot drift quietly;
     * if the shape changes, this returns -1 and the effect is visible rather than subtle.
     *
     * <p><b>The LAST String</b>, because that is where sourcePackage sits in every internal-setter
     * shape AOSP ships on the releases we run on (verified against ClipboardService.java at
     * android-14.0.0_r18 and android-15.0.0_r1):
     * <ul>
     *   <li>{@code setPrimaryClipInternalLocked(ClipData, int uid, int deviceId, String sourcePackage)}
     *   <li>{@code setPrimaryClipInternalLocked(Clipboard, ClipData, int uid, String sourcePackage)}
     * </ul>
     * The middle int is {@code deviceId} on 14/15 (a virtual-display id), not a userId — but its
     * type is irrelevant here; only "last String == sourcePackage" matters, and it holds. NOTE the
     * trailing String is sourcePackage ONLY on these INTERNAL methods; the public binder entry points
     * ({@code setPrimaryClip(ClipData, String callingPackage, String attributionTag, int, int)} and
     * {@code clipboardAccessAllowed(int, String callingPackage, String attributionTag, …)}) end in
     * attributionTag, not sourcePackage — which is why we hook the internal setter and read
     * callingPackage by fixed index 0/1 in the access hooks, never "the last String" there.
     *
     * <p>If a ROM ever reshapes the internal setter with no String at all this returns -1: nothing is
     * recognised as our own write, and the echo is caught one layer up by SyncService's sent-hash set
     * instead — a graceful degradation, not a crash.
     */
    static int sourceArg(Method m) {
        Class<?>[] p = m.getParameterTypes();
        for (int i = p.length - 1; i >= 0; i--) if (p[i] == String.class) return i;
        return -1;
    }

    /**
     * Pull the ClipData out of a clip-setter call and push it unless we wrote it ourselves.
     *
     * @param clipAt   {@link #clipArg}'s answer for the hooked method
     * @param sourceAt {@link #sourceArg}'s answer, or -1 when the overload carries no source package
     */
    static void onClipSet(Object[] args, int clipAt, int sourceAt) {
        try {
            if (clipAt < 0 || clipAt >= args.length) return;
            if (!(args[clipAt] instanceof android.content.ClipData clip)) return;
            boolean ours = sourceAt >= 0 && sourceAt < args.length && PKG.equals(args[sourceAt]);
            if (!ours) pushClip(clip);
        } catch (Throwable ignored) {
        }
    }
}
