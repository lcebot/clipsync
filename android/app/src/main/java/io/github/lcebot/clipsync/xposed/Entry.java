package io.github.lcebot.clipsync.xposed;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed API 102 entry (META-INF/xposed/java_init.list; scope.list names system_server as
 * "system"). The only entry point this module declares. See {@link Common} for what the hooks
 * do; that is where the logic lives.
 *
 * <p>Hooks are kept to the minimum: only the overloads whose signature we actually rely on are
 * intercepted (package name at the expected argument index), every interceptor is a couple of
 * comparisons, and nothing is deoptimized, since LSPosed's hooking already takes care of inlined
 * callees on the releases this targets.
 */
public class Entry extends XposedModule {

    private final AtomicBoolean installed = new AtomicBoolean(false);

    /** The framework's log() goes to LSPosed's module log; write logcat (tag ClipSync) as well. */
    private void both(int level, String msg, Throwable t) {
        if (t != null) { Log.println(level, Common.TAG, msg + "\n" + Log.getStackTraceString(t)); log(Log.INFO, Common.TAG, msg, t); }
        else { Log.println(level, Common.TAG, msg); log(Log.INFO, Common.TAG, msg); }
    }

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        both(Log.INFO, "module loaded in " + param.getProcessName() + " (libxposed)", null);
    }

    @Override
    public void onSystemServerStarting(@NonNull XposedModuleInterface.SystemServerStartingParam param) {
        install(param.getClassLoader());
    }

    /** Parameter {@code i} of {@code m} is a String, which is the overload shape our interceptors assume. */
    private static boolean stringAt(Method m, int i) {
        return m.getParameterCount() > i && m.getParameterTypes()[i] == String.class;
    }

    private void install(ClassLoader cl) {
        if (!installed.compareAndSet(false, true)) return;
        List<String> hooked = new ArrayList<>();

        // ---- clipboard
        try {
            Class<?> svc = cl.loadClass(Common.CLIP_SVC);
            for (Method m : svc.getDeclaredMethods()) {
                switch (m.getName()) {
                    case "clipboardAccessAllowed" -> {
                        if (!stringAt(m, 1)) continue;                 // (int op, String callingPackage, …)
                        hook(m).setId("access").setExceptionMode(ExceptionMode.PROTECTIVE)
                                .intercept(chain -> Common.PKG.equals(chain.getArg(1)) ? Boolean.TRUE : chain.proceed());
                        hooked.add("clipboardAccessAllowed");
                    }
                    case "showAccessNotificationLocked" -> {
                        if (!stringAt(m, 0)) continue;                 // (String callingPackage, …)
                        // Verified void on AOSP 14 (android-14.0.0_r18) and 15 (android-15.0.0_r1):
                        // showAccessNotificationLocked(String callingPackage, int uid, int userId,
                        // Clipboard clipboard). We still branch on the return type: should any ROM/
                        // future release make it boolean ("was a toast shown?"), returning null there
                        // would NPE inside system_server and break every getPrimaryClip() of ours, so
                        // hand back FALSE in that case and null (skip) otherwise.
                        final Object skip = m.getReturnType() == boolean.class ? Boolean.FALSE : null;
                        hook(m).setId("toast").setExceptionMode(ExceptionMode.PROTECTIVE)
                                .intercept(chain -> Common.PKG.equals(chain.getArg(0)) ? skip : chain.proceed());
                        hooked.add("showAccessNotificationLocked");
                    }
                    default -> { }
                }
            }
            Method setter = Common.clipSetter(svc);
            if (setter != null) {
                final int argc = setter.getParameterCount();
                // Resolved once, here, from the overload we actually hooked, not re-derived per
                // call and never guessed from the argument VALUES. See Common.sourceArg.
                final int clipAt = Common.clipArg(setter), sourceAt = Common.sourceArg(setter);
                hook(setter).setId("push").setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object r = chain.proceed();
                            Object[] args = new Object[argc];
                            for (int k = 0; k < argc; k++) args[k] = chain.getArg(k);
                            Common.onClipSet(args, clipAt, sourceAt);
                            return r;
                        });
                // The indices are in the log on purpose: a ROM with a reshaped setter shows up as
                // "clip -1" or "src -1" in one line, rather than as a clipboard that quietly echoes.
                hooked.add(setter.getName() + " hooked to push (clip " + clipAt + ", src " + sourceAt + ")");
            }
        } catch (Throwable t) {
            both(Log.ERROR, "clipboard hook failed", t);
        }

        // ---- keep-alive
        try {
            Class<?> pr = cl.loadClass("com.android.server.am.ProcessRecord");
            for (Constructor<?> c : pr.getDeclaredConstructors()) {
                hook(c).setId("pr").setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object r = chain.proceed();
                            Object rec = chain.getThisObject();
                            if (Common.isOurs(rec)) Common.exempt(rec);
                            return r;
                        });
            }
            hooked.add("ProcessRecord.<init> hooked to shouldNotFreeze");
        } catch (Throwable t) {
            both(Log.WARN, "ProcessRecord hook failed: " + t, null);
        }
        try {
            for (Method m : android.os.Process.class.getDeclaredMethods()) {
                if (!m.getName().equals("setProcessFrozen") || !Modifier.isStatic(m.getModifiers())) continue;
                hook(m).setId("freeze").setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> Boolean.TRUE.equals(chain.getArg(2)) && Common.isOurUid(chain.getArg(1), chain.getArg(0))
                                ? null : chain.proceed());
                hooked.add("Process.setProcessFrozen");
            }
        } catch (Throwable t) {
            both(Log.WARN, "Process.setProcessFrozen hook failed: " + t, null);
        }

        // ---- watchdog
        int died = 0;
        try {
            Class<?> ams = cl.loadClass("com.android.server.am.ActivityManagerService");
            for (Method m : ams.getDeclaredMethods()) {
                String n = m.getName();
                if (!(n.equals("handleAppDiedLocked") || n.equals("appDiedLocked")) || m.getParameterCount() < 1
                        || !m.getParameterTypes()[0].getSimpleName().equals("ProcessRecord")) continue;
                hook(m).setId("died").setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object r = chain.proceed();
                            if (Common.isOurs(chain.getArg(0))) Common.scheduleCheck(Common.WATCHDOG_AFTER_DEATH_MS);
                            return r;
                        });
                died++;
            }
        } catch (Throwable t) {
            both(Log.WARN, "watchdog: death hook failed: " + t, null);
        }
        Common.pollMs = died > 0 ? Common.POLL_WITH_HOOK_MS : Common.POLL_WITHOUT_HOOK_MS;
        Common.scheduleCheck(Common.pollMs);
        hooked.add("watchdog(death hooks " + died + ", poll " + Common.pollMs / 1000 + " s)");

        both(Log.INFO, "hooked (libxposed) " + hooked, null);
    }
}
