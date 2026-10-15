package io.github.lcebot.clipsync.xposed

import android.app.ActivityManager
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import java.io.FileInputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Everything the system_server module does, independent of the Xposed API that loaded it. [Entry]
 * installs the hooks and calls into here. Nothing here references an Xposed type, which keeps the
 * logic readable on its own and independent of the API version that loads it.
 *
 * - Clipboard: writes are allowed without focus on every release, so "writing works in the
 *   background" proves nothing about the hooks. Reads and listener dispatch require focus;
 *   clipboardAccessAllowed is forced to true for our package, and on top of that every clipboard
 *   change is pushed straight into SyncService with the ClipData attached to the intent. Read
 *   access to its content:// URIs is granted on behalf of the copying app and held by
 *   ClipboardService's own permission owner, see [uriGranter].
 * - Keep-alive: our ProcessRecord is flagged shouldNotFreeze when created; as a safety net
 *   Process.setProcessFrozen is a no-op for our uid.
 * - Watchdog: the process-death path schedules a restart check; a slow poll is the net.
 *
 * **This code runs inside system_server.** An exception that escapes it does not crash this app, it
 * crashes the system. So every function here that is reached from a hook either cannot throw or
 * catches `Throwable` itself, every value that comes from the framework or from reflection is typed
 * nullable (a non-null Kotlin parameter is a null check the compiler inserts, and a failed one is an
 * exception), and nothing here uses `!!`.
 */
internal object Common {
    const val TAG = "ClipSync"
    const val PKG = "io.github.lcebot.clipsync"
    const val CLIP_SVC = "com.android.server.clipboard.ClipboardService"
    const val SERVICE = "$PKG.SyncService"

    /** Enabled means auto-start is wanted. */
    const val AUTOSTART = "$PKG.BootReceiver"
    const val ACTION_CLIP = "$PKG.CLIP"

    /** Apps of one user occupy one range of this size; a uid divided by it is its user id. */
    private const val PER_USER_RANGE = 100_000

    /** Prompt reaction to a kill. */
    const val WATCHDOG_AFTER_DEATH_MS = 3_000L

    /** The poll: a mere safety net when the death hook is in place, the primary mechanism otherwise. */
    const val POLL_WITH_HOOK_MS = 60_000L
    const val POLL_WITHOUT_HOOK_MS = 15_000L

    @Volatile
    var pollMs = POLL_WITHOUT_HOOK_MS

    // ------------------------------------------------------------------ one worker for all async work

    private val handlerLock = Any()

    /**
     * ONE Handler, shared by everything here. It has to be one object, not one per call.
     *
     * `MessageQueue.removeMessages` matches on `msg.target == handler`, and the target is whichever
     * Handler *posted* the message. A fresh Handler per call would still share the looper, so
     * posting would work, but [scheduleCheck]'s `removeCallbacks` needs to cancel a message posted
     * by an earlier call, and a different Handler instance can never match one it did not post
     * itself. A single shared instance is what makes cancellation possible at all.
     *
     * Created on first use rather than when this object initialises: a failure in an object's
     * initialiser would make every later use of the object fail too, and this one is reached from
     * binder threads inside system_server.
     */
    @Volatile
    private var handler: Handler? = null

    fun handler(): Handler {
        handler?.let { return it }
        synchronized(handlerLock) {
            handler?.let { return it }
            val worker = HandlerThread("clipsync-hook", android.os.Process.THREAD_PRIORITY_BACKGROUND)
            worker.start()
            val h = Handler(worker.looper)
            handler = h
            return h
        }
    }

    fun systemContext(): Context? {
        return try {
            val at = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null)
                ?: return null
            at.javaClass.getMethod("getSystemContext").invoke(at) as? Context
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ clipboard push

    /**
     * Hand a freshly set clip to SyncService. Plain startService from system uid: never subject to
     * background limits, and a running service just gets onStartCommand. Off the binder thread
     * (outside ClipboardService's lock, system identity rather than the copying app's).
     *
     * No FLAG_GRANT_READ_URI_PERMISSION: UriGrantsManagerService refuses every grant whose caller is
     * the system uid, so the flag would only log a warning per copy. [grantReads] issues the grant
     * that matters, with the copying app as the grantor.
     */
    fun pushClip(clip: ClipData?) {
        if (clip == null) return
        try {
            handler().post {
                try {
                    val ctx = systemContext() ?: return@post
                    val i = Intent(ACTION_CLIP).setClassName(PKG, SERVICE)
                    i.clipData = clip
                    try {
                        ctx.startService(i)
                    } catch (e: Throwable) {
                        // For example TransactionTooLarge for a huge text: send a bare trigger, and
                        // the service reads the clipboard itself.
                        try {
                            ctx.startService(Intent(ACTION_CLIP).setClassName(PKG, SERVICE).putExtra("fetch", true))
                        } catch (e2: Throwable) {
                            Log.w(TAG, "clip push failed: $e2")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "clip push failed: $t")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "clip push not scheduled: $t")
        }
    }

    // ------------------------------------------------------------------ freezer exemption

    @Volatile
    private var exemptLogged = false

    /** Tell the freezer itself to leave this record alone (setter names vary by release). */
    fun exempt(processRecord: Any?) {
        try {
            val opt = field(processRecord, "mOptRecord") ?: return
            for (setter in arrayOf("setShouldNotFreeze", "setFreezeExempt")) {
                try {
                    val m = opt.javaClass.getDeclaredMethod(setter, java.lang.Boolean.TYPE)
                    m.isAccessible = true
                    m.invoke(opt, true)
                    if (!exemptLogged) {
                        exemptLogged = true
                        Log.i(TAG, "process record exempted from freezing via $setter")
                    }
                    return
                } catch (ignored: NoSuchMethodException) {
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    /** Tells whether a ProcessRecord is our app (processName is PKG or PKG:sync). */
    fun isOurs(processRecord: Any?): Boolean {
        if (processRecord == null) return false
        return try {
            val name = field(processRecord, "processName")
            if (name is String && name.startsWith(PKG)) return true
            val info = field(processRecord, "info")
            info != null && PKG == field(info, "packageName")
        } catch (t: Throwable) {
            false
        }
    }

    @Volatile
    private var ourUid = -1

    @Volatile
    private var uidLookupAt = 0L

    /** uid == ours? Resolved once from the system context; refreshed every 10 min (reinstall). */
    fun isOurUid(uidArg: Any?, pidArg: Any?): Boolean {
        return try {
            val uid = uidArg as? Int ?: -1
            val now = SystemClock.elapsedRealtime()
            if (ourUid < 0 || now - uidLookupAt > 600_000L) {
                uidLookupAt = now
                ourUid = try {
                    systemContext()?.packageManager?.getPackageUid(PKG, 0) ?: -1
                } catch (t: Throwable) {
                    -1
                }
            }
            if (ourUid >= 0) uid == ourUid
            else pidArg is Int && isOurPid(pidArg)          // fallback: /proc/pid/cmdline
        } catch (t: Throwable) {
            false
        }
    }

    fun isOurPid(pid: Int): Boolean {
        return try {
            FileInputStream("/proc/$pid/cmdline").use { input ->
                val b = ByteArray(256)
                val n = input.read(b)
                if (n <= 0) return false
                var end = 0
                while (end < n && b[end].toInt() != 0) end++
                String(b, 0, end).startsWith(PKG)
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** A field by name, searched up the class hierarchy, or null. May throw; callers catch. */
    fun field(o: Any?, name: String): Any? {
        if (o == null) return null
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(o)
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    // ------------------------------------------------------------------ watchdog

    fun autoStartWanted(ctx: Context): Boolean {
        return try {
            val s = ctx.packageManager.getComponentEnabledSetting(ComponentName(PKG, AUTOSTART))
            s != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (t: Throwable) {
            false                       // package not installed
        }
    }

    @Suppress("DEPRECATION")
    fun serviceRunning(ctx: Context): Boolean {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return true
        val running: List<ActivityManager.RunningServiceInfo>? = am.getRunningServices(Int.MAX_VALUE)
        for (s in running.orEmpty()) {
            if (SERVICE == s.service?.className) return true
        }
        return false
    }

    /** A force-stopped package receives nothing and cannot be started until the flag is cleared. */
    fun unstop(ctx: Context) {
        try {
            val pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null) ?: return
            val user = android.os.Process.myUserHandle().hashCode()
            pm.javaClass.getMethod(
                "setPackageStoppedState", String::class.java, java.lang.Boolean.TYPE, java.lang.Integer.TYPE,
            ).invoke(pm, PKG, false, user)
        } catch (ignored: Throwable) {
        }
    }

    private val check: Runnable = object : Runnable {
        override fun run() {
            try {
                val ctx = systemContext()
                if (ctx != null) {
                    val um = ctx.getSystemService(UserManager::class.java)
                    val unlocked = um == null || um.isUserUnlocked          // app data still encrypted otherwise
                    if (unlocked && autoStartWanted(ctx) && !serviceRunning(ctx)) {
                        unstop(ctx)
                        ctx.startForegroundService(Intent().setClassName(PKG, SERVICE))
                        Log.i(TAG, "watchdog: SyncService was not running, started it")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "watchdog: $e")
            }
            try {
                handler().postDelayed(this, pollMs)
            } catch (t: Throwable) {
                Log.w(TAG, "watchdog: not rescheduled: $t")
            }
        }
    }

    fun scheduleCheck(delayMs: Long) {
        try {
            val h = handler()
            h.removeCallbacks(check)
            h.postDelayed(check, delayMs)
        } catch (t: Throwable) {
            Log.w(TAG, "watchdog: not scheduled: $t")
        }
    }

    /**
     * ClipboardService's clip setter. On the releases this app installs on (minSdk 35, Android 15
     * and up) there are TWO methods named exactly `setPrimaryClipInternalLocked`, verified against
     * AOSP `services/core/java/com/android/server/clipboard/ClipboardService.java` at
     * android-15.0.0_r1 (android-14.0.0_r18 has the same shape and corroborates it):
     *
     * - `setPrimaryClipInternalLocked(ClipData clip, int uid, int deviceId, String sourcePackage)`
     * - `setPrimaryClipInternalLocked(Clipboard clipboard, ClipData clip, int uid, String sourcePackage)`
     *   (the first calls the second for the current user; related profiles go through
     *   `setPrimaryClipInternalNoClassifyLocked`, so exactly ONE of these fires per set)
     *
     * Whichever exact-name match `getDeclaredMethods()` yields first is returned; both carry the clip
     * as their only ClipData and sourcePackage as their last String, so [clipArg] and [sourceArg]
     * resolve correctly either way and a set is pushed exactly once.
     */
    fun clipSetter(svc: Class<*>): Method? {
        var setter: Method? = null
        for (m in svc.declaredMethods) {
            if (m.name == "setPrimaryClipInternalLocked") return m
            if (m.name.startsWith("setPrimaryClipInternal") && setter == null) setter = m
        }
        return setter
    }

    /**
     * Where the ClipData sits in the clip setter's parameter list, or -1.
     *
     * The first ClipData parameter. Every known overload has exactly one, so "first" and "the one"
     * are the same thing; taking the first rather than the last is what stops a future overload with
     * an extra ClipData (a previous value, say) from being read as the new clip.
     */
    fun clipArg(m: Method): Int {
        val p = m.parameterTypes
        for (i in p.indices) if (p[i] == ClipData::class.java) return i
        return -1
    }

    /**
     * Where `sourcePackage` sits in the clip setter's parameter list, or -1 if it has none.
     *
     * Resolved from the signature, once, at hook time, rather than by scanning every argument for a
     * String equal to our package name, because that scan is a different question with the same
     * answer most of the time: it also says "ours" for a clip whose *label* or whose calling-package
     * parameter happens to be our package name, and it silently stops working the day an overload
     * puts our name somewhere else. Reading a known position cannot drift quietly; if the shape
     * changes, this returns -1 and the effect is visible rather than subtle.
     *
     * **The LAST String**, because that is where sourcePackage sits in every internal-setter shape
     * AOSP ships on the releases this runs on (verified against ClipboardService.java at
     * android-15.0.0_r1, with android-14.0.0_r18 agreeing):
     *
     * - `setPrimaryClipInternalLocked(ClipData, int uid, int deviceId, String sourcePackage)`
     * - `setPrimaryClipInternalLocked(Clipboard, ClipData, int uid, String sourcePackage)`
     *
     * The middle int is `deviceId` (a virtual-display id), not a userId, but its type is irrelevant
     * here; only "last String == sourcePackage" matters, and it holds. NOTE the trailing String is
     * sourcePackage ONLY on these INTERNAL methods; the public binder entry points
     * (`setPrimaryClip(ClipData, String callingPackage, String attributionTag, int, int)` and
     * `clipboardAccessAllowed(int, String callingPackage, String attributionTag, ...)`) end in
     * attributionTag, not sourcePackage, which is why the internal setter is the one hooked, and the
     * access hooks read callingPackage by fixed index 0 or 1, never "the last String".
     *
     * If a ROM reshapes the internal setter with no String at all this returns -1: nothing is
     * recognised as our own write, and the echo is caught one layer up by SyncService's sent-hash
     * set instead: a graceful degradation, not a crash.
     */
    fun sourceArg(m: Method): Int {
        val p = m.parameterTypes
        for (i in p.indices.reversed()) if (p[i] == String::class.java) return i
        return -1
    }

    /**
     * Where the copying app's `uid` sits in the clip setter's parameter list, or -1.
     *
     * The first int. In both shapes listed at [clipSetter] that is `uid`, ahead of `deviceId` in
     * the first one and the only int in the second, on Android 15 through 17 alike.
     */
    fun uidArg(m: Method): Int {
        val p = m.parameterTypes
        for (i in p.indices) if (p[i] == java.lang.Integer.TYPE) return i
        return -1
    }

    /**
     * Grants this app read access to the URIs of one clip item on behalf of the copying app.
     *
     * @property path which of [uriGranter]'s ways was resolved, for the hook log line
     */
    class UriGranter(
        val path: String,
        private val grantItem: (svc: Any, item: ClipData.Item, sourceUid: Int, targetUser: Int) -> Unit,
    ) {
        fun grant(svc: Any, item: ClipData.Item, sourceUid: Int, targetUser: Int) =
            grantItem(svc, item, sourceUid, targetUser)
    }

    /**
     * The way to grant read access to a clip's URIs on this ROM, or null when none resolves.
     *
     * Each way issues the same grant: the copying app is the grantor, because UriGrantsManagerService
     * refuses one from the system uid, and ClipboardService's own permission owner holds it, so that
     * `revokeUris` withdraws it at the next copy exactly as it does for an ordinary foreground reader.
     * They are tried from the most faithful to the most robust:
     *
     * 1. `grantItemPermission(ClipData.Item, int, String, int)`, the helper `getPrimaryClip()`
     *    grants through.
     * 2. `grantUriPermission(Uri, int, String, int)`, the per-URI helper underneath it. The item
     *    helper has a single caller, so an optimizing build may inline it there and keep only this
     *    one, which a One UI build for Android 16 was reported to do.
     * 3. `IUriGrantsManager.grantUriPermissionFromOwner` with the service's `mPermissionOwner`,
     *    the call both helpers end in. An interface method cannot be inlined away, but this way
     *    reproduces the helpers' logic here and misses anything a ROM adds to them, which is why
     *    it comes last.
     *
     * The helpers' signatures are the same on Android 15 through 17.
     */
    fun uriGranter(svc: Class<*>): UriGranter? {
        val intType = java.lang.Integer.TYPE
        declared(svc, "grantItemPermission", ClipData.Item::class.java, intType, String::class.java, intType)
            ?.let { m ->
                return UriGranter("grantItemPermission") { s, item, uid, user -> call(m, s, item, uid, PKG, user) }
            }
        declared(svc, "grantUriPermission", Uri::class.java, intType, String::class.java, intType)
            ?.let { m ->
                return UriGranter("grantUriPermission") { s, item, uid, user ->
                    eachContentUri(item) { uri -> call(m, s, uri, uid, PKG, user) }
                }
            }
        return ownerGranter(svc)
    }

    /**
     * The third way of [uriGranter]. The grants service is looked up on first use rather than here,
     * because the hooks are installed before system_server has registered it.
     */
    private fun ownerGranter(svc: Class<*>): UriGranter? {
        return try {
            val ownerField = svc.getDeclaredField("mPermissionOwner").apply { isAccessible = true }
            val getService = Class.forName("android.app.UriGrantsManager").getDeclaredMethod("getService")
                .apply { isAccessible = true }
            val grant = Class.forName("android.app.IUriGrantsManager").getMethod(
                "grantUriPermissionFromOwner", IBinder::class.java, java.lang.Integer.TYPE, String::class.java,
                Uri::class.java, java.lang.Integer.TYPE, java.lang.Integer.TYPE, java.lang.Integer.TYPE,
            )
            UriGranter("IUriGrantsManager") { s, item, uid, user ->
                val owner = ownerField.get(s) as? IBinder
                val ugm = getService.invoke(null)
                if (owner == null || ugm == null) throw IllegalStateException("permission owner or grants service missing")
                eachContentUri(item) { uri ->
                    // The helpers clear the identity too. The binder thread still carries the copying
                    // app's, and the grants service would otherwise judge the call by it.
                    val ident = Binder.clearCallingIdentity()
                    try {
                        call(
                            grant, ugm, owner, uid, PKG, uriWithoutUser(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            userOfUri(uri, uid / PER_USER_RANGE), user,
                        )
                    } finally {
                        Binder.restoreCallingIdentity(ident)
                    }
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** A declared method made accessible, or null when the class has no such method. */
    private fun declared(c: Class<*>, name: String, vararg params: Class<*>): Method? {
        return try {
            c.getDeclaredMethod(name, *params).apply { isAccessible = true }
        } catch (t: Throwable) {
            null
        }
    }

    /** Invoke, rethrowing what the method itself threw rather than the reflection wrapper around it. */
    private fun call(m: Method, target: Any?, vararg args: Any?): Any? {
        try {
            return m.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    /**
     * Run [grant] for the item's URI and its intent's data, the two places a clip item carries one,
     * skipping anything but content:// as ClipboardService does. One URI the copying app cannot grant
     * still leaves the other readable.
     */
    private fun eachContentUri(item: ClipData.Item, grant: (Uri) -> Unit) {
        for (uri in arrayOf(item.uri, item.intent?.data)) {
            if (uri == null || ContentResolver.SCHEME_CONTENT != uri.scheme) continue
            try {
                grant(uri)
            } catch (t: Throwable) {
                Log.w(TAG, "uri grant refused for $uri: $t")
            }
        }
    }

    /** A user id outside every real range, so a malformed authority makes the grant fail. */
    private const val USER_NULL = -10_000

    /**
     * The user a content URI points into: the number before '@' in its authority, or [default] when
     * there is none. Written out rather than reached through ContentProvider's hidden helpers so that
     * this way depends on one less framework internal.
     */
    private fun userOfUri(uri: Uri, default: Int): Int {
        val auth = uri.authority ?: return default
        val at = auth.lastIndexOf('@')
        if (at < 0) return default
        return auth.substring(0, at).toIntOrNull() ?: USER_NULL
    }

    /** The URI with the user prefix removed from its authority, as the grants service expects it. */
    private fun uriWithoutUser(uri: Uri): Uri {
        val auth = uri.authority ?: return uri
        val at = auth.lastIndexOf('@')
        return if (at < 0) uri else uri.buildUpon().authority(auth.substring(at + 1)).build()
    }

    /**
     * Pull the ClipData out of a clip-setter call and push it unless we wrote it ourselves.
     *
     * Runs synchronously inside the setter, under ClipboardService's lock, so the grant is issued
     * while this clip is still the primary one: a later copy revokes it, and a grant issued after
     * that would never be revoked.
     *
     * @param svc the ClipboardService the setter was called on
     * @param clipAt [clipArg]'s answer for the hooked method
     * @param sourceAt [sourceArg]'s answer, or -1 when the overload carries no source package
     * @param uidAt [uidArg]'s answer, or -1
     * @param granter [uriGranter]'s answer, or null
     */
    fun onClipSet(svc: Any?, args: Array<Any?>, clipAt: Int, sourceAt: Int, uidAt: Int, granter: UriGranter?) {
        try {
            val clip = args.getOrNull(clipAt) as? ClipData ?: return
            if (PKG == args.getOrNull(sourceAt)) return
            val uid = args.getOrNull(uidAt) as? Int
            if (svc != null && granter != null && uid != null) grantReads(svc, granter, clip, uid)
            pushClip(clip)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * Grant this app read access to every URI in [clip], with the copying app as the grantor.
     *
     * The grantor has to be the copying app: a grant from the system uid is refused outright, which
     * leaves a pushed image unreadable. Per item, so one URI the source cannot grant (a
     * SecurityException) still leaves the others readable. A failure is logged, because a silent one
     * looks exactly like a missing grant path. Never clears the clip on failure, unlike
     * `getPrimaryClip()`, because that is the copying app's clipboard, not ours.
     */
    private fun grantReads(svc: Any, granter: UriGranter, clip: ClipData, uid: Int) {
        val user = uid / PER_USER_RANGE
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i) ?: continue
            if (item.uri == null && item.intent?.data == null) continue
            try {
                granter.grant(svc, item, uid, user)
            } catch (t: Throwable) {
                Log.w(TAG, "uri grant via ${granter.path} failed: $t")
            }
        }
    }
}
