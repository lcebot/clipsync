package io.github.lcebot.clipsync.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * libxposed API 102 entry (META-INF/xposed/java_init.list; scope.list names system_server as
 * "system"). The only entry point this module declares. See [Common] for what the hooks do; that is
 * where the logic lives.
 *
 * Hooks are kept to the minimum: only the overloads whose signature is actually relied on are
 * intercepted (package name at the expected argument index), every interceptor is a couple of
 * comparisons, and nothing is deoptimized, since LSPosed's hooking already takes care of inlined
 * callees on the releases this targets.
 *
 * **Failure containment, because this runs in system_server.** Three layers, so no failure of this
 * module can take the system down with it:
 * 1. Every hook is installed in PROTECTIVE mode, so the framework itself contains an exception
 *    thrown from an interceptor.
 * 2. Every interceptor decides inside its own `try` and falls back to the original method when the
 *    decision fails. An exception thrown by the original method itself (`chain.proceed()`) is never
 *    caught here: it belongs to the system's caller, exactly as without this module.
 * 3. Work done after `proceed()` is wrapped separately, so it can never replace the original's
 *    result with an exception.
 * Installation is wrapped per hook group, so one group failing to install leaves the others and the
 * system untouched.
 *
 * Public, with the implicit public no-argument constructor, because libxposed instantiates the class
 * named in java_init.list reflectively; R8 keeps it by name (proguard-rules.pro).
 */
class Entry : XposedModule() {

    private val installed = AtomicBoolean(false)

    /** The framework's log() goes to LSPosed's module log; write logcat (tag ClipSync) as well. */
    private fun both(level: Int, msg: String, t: Throwable?) {
        try {
            if (t != null) {
                Log.println(level, Common.TAG, msg + "\n" + Log.getStackTraceString(t))
                log(Log.INFO, Common.TAG, msg, t)
            } else {
                Log.println(level, Common.TAG, msg)
                log(Log.INFO, Common.TAG, msg)
            }
        } catch (ignored: Throwable) {
        }
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        try {
            both(Log.INFO, "module loaded in " + param.processName + " (libxposed)", null)
        } catch (ignored: Throwable) {
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        try {
            install(param.classLoader)
        } catch (t: Throwable) {
            both(Log.ERROR, "install failed", t)
        }
    }

    private fun install(cl: ClassLoader?) {
        if (cl == null) return
        if (!installed.compareAndSet(false, true)) return
        val hooked = ArrayList<String>()

        // ---- clipboard
        try {
            val svc = cl.loadClass(Common.CLIP_SVC)
            for (m in svc.declaredMethods) {
                when (m.name) {
                    "clipboardAccessAllowed" -> {
                        if (!stringAt(m, 1)) continue                  // (int op, String callingPackage, ...)
                        hook(m).setId("access").setExceptionMode(ExceptionMode.PROTECTIVE)
                            .intercept { chain ->
                                val ours = try {
                                    Common.PKG == chain.getArg(1)
                                } catch (t: Throwable) {
                                    false
                                }
                                if (ours) java.lang.Boolean.TRUE else chain.proceed()
                            }
                        hooked.add("clipboardAccessAllowed")
                    }
                    "showAccessNotificationLocked" -> {
                        if (!stringAt(m, 0)) continue                  // (String callingPackage, ...)
                        // Void on AOSP 14 (android-14.0.0_r18) and 15 (android-15.0.0_r1):
                        // showAccessNotificationLocked(String callingPackage, int uid, int userId,
                        // Clipboard clipboard). The return type is still checked: should a ROM or a
                        // future release make it boolean ("was a toast shown?"), returning null there
                        // would throw inside system_server and break every getPrimaryClip() of ours,
                        // so FALSE is handed back in that case and null (skip) otherwise.
                        val skip: Any? = if (m.returnType == java.lang.Boolean.TYPE) java.lang.Boolean.FALSE else null
                        hook(m).setId("toast").setExceptionMode(ExceptionMode.PROTECTIVE)
                            .intercept { chain ->
                                val ours = try {
                                    Common.PKG == chain.getArg(0)
                                } catch (t: Throwable) {
                                    false
                                }
                                if (ours) skip else chain.proceed()
                            }
                        hooked.add("showAccessNotificationLocked")
                    }
                    else -> {}
                }
            }
            val setter = Common.clipSetter(svc)
            if (setter != null) {
                val argc = setter.parameterCount
                // Resolved once, here, from the overload actually hooked, not re-derived per call and
                // never guessed from the argument VALUES. See Common.sourceArg.
                val clipAt = Common.clipArg(setter)
                val sourceAt = Common.sourceArg(setter)
                hook(setter).setId("push").setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val r = chain.proceed()
                        try {
                            val args = arrayOfNulls<Any>(argc)
                            for (k in 0 until argc) args[k] = chain.getArg(k)
                            Common.onClipSet(args, clipAt, sourceAt)
                        } catch (ignored: Throwable) {
                        }
                        r
                    }
                // The indices are in the log on purpose: a ROM with a reshaped setter shows up as
                // "clip -1" or "src -1" in one line, rather than as a clipboard that quietly echoes.
                hooked.add(setter.name + " hooked to push (clip " + clipAt + ", src " + sourceAt + ")")
            }
        } catch (t: Throwable) {
            both(Log.ERROR, "clipboard hook failed", t)
        }

        // ---- keep-alive
        try {
            val pr = cl.loadClass("com.android.server.am.ProcessRecord")
            for (c in pr.declaredConstructors) {
                hook(c).setId("pr").setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val r = chain.proceed()
                        try {
                            val rec = chain.getThisObject()
                            if (Common.isOurs(rec)) Common.exempt(rec)
                        } catch (ignored: Throwable) {
                        }
                        r
                    }
            }
            hooked.add("ProcessRecord.<init> hooked to shouldNotFreeze")
        } catch (t: Throwable) {
            both(Log.WARN, "ProcessRecord hook failed: $t", null)
        }
        try {
            for (m in android.os.Process::class.java.declaredMethods) {
                if (m.name != "setProcessFrozen" || !Modifier.isStatic(m.modifiers)) continue
                hook(m).setId("freeze").setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val ours = try {
                            java.lang.Boolean.TRUE == chain.getArg(2) && Common.isOurUid(chain.getArg(1), chain.getArg(0))
                        } catch (t: Throwable) {
                            false
                        }
                        if (ours) null else chain.proceed()
                    }
                hooked.add("Process.setProcessFrozen")
            }
        } catch (t: Throwable) {
            both(Log.WARN, "Process.setProcessFrozen hook failed: $t", null)
        }

        // ---- watchdog
        var died = 0
        try {
            val ams = cl.loadClass("com.android.server.am.ActivityManagerService")
            for (m in ams.declaredMethods) {
                val n = m.name
                if (!(n == "handleAppDiedLocked" || n == "appDiedLocked") || m.parameterCount < 1 ||
                    m.parameterTypes[0].simpleName != "ProcessRecord"
                ) continue
                hook(m).setId("died").setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val r = chain.proceed()
                        try {
                            if (Common.isOurs(chain.getArg(0))) Common.scheduleCheck(Common.WATCHDOG_AFTER_DEATH_MS)
                        } catch (ignored: Throwable) {
                        }
                        r
                    }
                died++
            }
        } catch (t: Throwable) {
            both(Log.WARN, "watchdog: death hook failed: $t", null)
        }
        Common.pollMs = if (died > 0) Common.POLL_WITH_HOOK_MS else Common.POLL_WITHOUT_HOOK_MS
        Common.scheduleCheck(Common.pollMs)
        hooked.add("watchdog(death hooks " + died + ", poll " + Common.pollMs / 1000 + " s)")

        both(Log.INFO, "hooked (libxposed) $hooked", null)
    }

    private companion object {
        /** Parameter `i` of `m` is a String, which is the overload shape the interceptors assume. */
        fun stringAt(m: Method, i: Int): Boolean = m.parameterCount > i && m.parameterTypes[i] == String::class.java
    }
}
