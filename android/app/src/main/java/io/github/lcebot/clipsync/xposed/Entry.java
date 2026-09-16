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
 * libxposed API 102 entry (META-INF/xposed/java_init.list). Runs inside system_server.
 * See {@link Common} for what the hooks do; {@link LegacyEntry} installs the same set through
 * the classic API for frameworks that only implement that one.
 *
 * <p>Two things matter with this API that a plain "hook by name" misses:
 * <ul>
 *   <li><b>Inlining.</b> system_server is AOT-compiled; small private methods such as
 *       {@code clipboardAccessAllowed} and {@code setPrimaryClipInternalLocked} are inlined into
 *       their callers, and a hook on the callee then never runs for those call sites (reads keep
 *       failing, the push never fires — while writes, allowed without focus anyway, "work").
 *       Every caller is therefore deoptimized first; the classes involved are small, so this is
 *       cheap and done once.</li>
 *   <li><b>Lifecycle.</b> Hooks are installed from whichever system_server callback the framework
 *       delivers first ({@code onSystemServerLoaded} / {@code onSystemServerStarting}); the
 *       installer is idempotent.</li>
 * </ul>
 */
public class Entry extends XposedModule {

    private final AtomicBoolean installed = new AtomicBoolean(false);

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, Common.TAG, "module loaded in " + param.getProcessName());
    }

    @Override
    public void onSystemServerLoaded(@NonNull XposedModuleInterface.SystemServerLoadedParam param) {
        install(param.getClassLoader());
    }

    @Override
    public void onSystemServerStarting(@NonNull XposedModuleInterface.SystemServerStartingParam param) {
        install(param.getClassLoader());
    }

    /** Deoptimize every method of a class so hooks on the methods it calls take effect. */
    private void deoptAll(Class<?> c, List<String> report) {
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isAbstract(m.getModifiers()) || Modifier.isNative(m.getModifiers())) continue;
            try { deoptimize(m); n++; } catch (Throwable ignored) { }
        }
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            try { deoptimize(k); n++; } catch (Throwable ignored) { }
        }
        report.add("deopt " + c.getSimpleName() + " ×" + n);
    }

    private void deoptNamed(Class<?> c, String prefix, List<String> report) {
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().startsWith(prefix)) continue;
            try { deoptimize(m); n++; } catch (Throwable ignored) { }
        }
        if (n > 0) report.add("deopt " + c.getSimpleName() + "." + prefix + "* ×" + n);
    }

    private void install(ClassLoader cl) {
        if (!installed.compareAndSet(false, true)) return;
        List<String> hooked = new ArrayList<>();

        // ---- clipboard
        try {
            Class<?> svc = cl.loadClass(Common.CLIP_SVC);
            deoptAll(svc, hooked);                                     // callers of the two hooked methods
            for (Class<?> inner : svc.getDeclaredClasses()) deoptAll(inner, hooked);   // ClipboardImpl (binder stub)
            for (Method m : svc.getDeclaredMethods()) {
                switch (m.getName()) {
                    case "clipboardAccessAllowed" -> {
                        hook(m).setId("access").setExceptionMode(ExceptionMode.PROTECTIVE)
                                .intercept(chain -> Common.PKG.equals(chain.getArg(1)) ? Boolean.TRUE : chain.proceed());
                        hooked.add("clipboardAccessAllowed");
                    }
                    case "showAccessNotificationLocked" -> {
                        hook(m).setId("toast").setExceptionMode(ExceptionMode.PROTECTIVE)
                                .intercept(chain -> Common.PKG.equals(chain.getArg(0)) ? null : chain.proceed());
                        hooked.add("showAccessNotificationLocked");
                    }
                    default -> { }
                }
            }
            Method setter = Common.clipSetter(svc);
            if (setter != null) {
                final int argc = setter.getParameterCount();
                hook(setter).setId("push").setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object r = chain.proceed();
                            Object[] args = new Object[argc];
                            for (int k = 0; k < argc; k++) args[k] = chain.getArg(k);
                            Common.onClipSet(args);
                            return r;
                        });
                hooked.add(setter.getName() + " -> push");
            }
        } catch (Throwable t) {
            log(Log.ERROR, Common.TAG, "clipboard hook failed", t);
        }

        // ---- keep-alive
        try {
            Class<?> pr = cl.loadClass("com.android.server.am.ProcessRecord");
            try {
                deoptNamed(cl.loadClass("com.android.server.am.ProcessList"), "newProcessRecord", hooked);   // the constructor's caller
            } catch (Throwable ignored) {
            }
            for (Constructor<?> c : pr.getDeclaredConstructors()) {
                hook(c).setId("pr").setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object r = chain.proceed();
                            Object rec = chain.getThisObject();
                            if (Common.isOurs(rec)) Common.exempt(rec);
                            return r;
                        });
            }
            hooked.add("ProcessRecord.<init> -> shouldNotFreeze");
        } catch (Throwable t) {
            log(Log.WARN, Common.TAG, "ProcessRecord hook failed: " + t);
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
            log(Log.WARN, Common.TAG, "Process.setProcessFrozen hook failed: " + t);
        }

        // ---- watchdog
        int died = 0;
        try {
            Class<?> ams = cl.loadClass("com.android.server.am.ActivityManagerService");
            deoptNamed(ams, "appDiedLocked", hooked);
            deoptNamed(ams, "handleAppDiedLocked", hooked);
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
            log(Log.WARN, Common.TAG, "watchdog: death hook failed: " + t);
        }
        Common.pollMs = died > 0 ? Common.POLL_WITH_HOOK_MS : Common.POLL_WITHOUT_HOOK_MS;
        Common.scheduleCheck(Common.pollMs);
        hooked.add("watchdog(death hooks " + died + ", poll " + Common.pollMs / 1000 + " s)");

        log(Log.INFO, Common.TAG, "hooked (libxposed) " + hooked);
    }
}
