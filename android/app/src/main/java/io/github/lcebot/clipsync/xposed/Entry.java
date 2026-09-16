package io.github.lcebot.clipsync.xposed;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Runs inside system_server (scope: "android").
 * <p>
 * Two hooks on com.android.server.clipboard.ClipboardService:
 * <ul>
 *   <li>clipboardAccessAllowed(int op, String callingPackage, ...)  -> true for our package,
 *       so SyncService can read/write the clipboard and receive change callbacks in background.</li>
 *   <li>showAccessNotificationLocked(String callingPackage, ...)     -> skipped for our package,
 *       so Android 12+ does not toast "ClipSync pasted from ..." on every read.</li>
 * </ul>
 * Methods are matched by name only; the package argument sits at the same index on Android 10-15.
 */
public class Entry extends XposedModule {

    private static final String TAG = "ClipSync";
    private static final String PKG = "io.github.lcebot.clipsync";
    private static final String SVC = "com.android.server.clipboard.ClipboardService";

    @Override
    public void onSystemServerStarting(@NonNull XposedModuleInterface.SystemServerStartingParam param) {
        install(param.getClassLoader());
    }

    private void install(ClassLoader cl) {
        try {
            Class<?> svc = cl.loadClass(SVC);
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
            log(Log.ERROR, TAG, "hook failed", t);
        }
    }
}
