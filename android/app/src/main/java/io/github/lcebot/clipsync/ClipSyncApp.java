package io.github.lcebot.clipsync;

import android.app.Application;
import android.content.Context;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.DynamicColorsOptions;

/**
 * Exists for one job: hand the app the system's palette when the system has one.
 *
 * <p>The theme carries ClipSync's own colours ({@code values/colors.xml}); this overlays the
 * wallpaper-derived (Monet) scheme on top of them, and only when the platform can supply it. That
 * ordering is the whole design, because the alternative, a {@code Theme.Material3*.DynamicColors.*} parent,
 * wires every role straight to {@code @android:color/system_*} and leaves nowhere for an app's own
 * colours to live, so a device without a palette falls back to Material's baseline purple. Which is
 * the library's identity, not this app's.
 *
 * <p><b>Activities only</b>, which is what {@code applyToActivitiesIfAvailable} does and all it does.
 * Dialogs and the bottom sheets inherit, because they are built from an Activity context. Anything
 * built from the application context is not themed at all, and the notification is the one place
 * that matters; see {@link #themed}.
 *
 * <p>This runs in <b>both</b> processes: an Application is instantiated per process, and the service
 * lives in {@code :sync}. Registering activity callbacks there costs nothing, since it has no
 * activities to call back about, and it means {@link #themed} works on either side.
 */
public final class ClipSyncApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this, new DynamicColorsOptions.Builder()
                // Re-pin the roles whose meaning is fixed, once the wallpaper palette is in place.
                //
                // Order is the whole mechanism: the dynamic overlay is applied with force=true, so
                // anything the base theme said about these roles has already been overwritten by the
                // time this runs. A second overlay, applied after, is the only layer that can have
                // the last word, and this callback is the documented place to be standing when the
                // first one has just finished.
                //
                // See ThemeOverlay.ClipSync.Semantic for what is pinned and why it is only the error
                // family.
                .setOnAppliedCallback(activity ->
                        activity.getTheme().applyStyle(R.style.ThemeOverlay_ClipSync_Semantic, true))
                .build());
    }

    /**
     * A context whose theme carries the dynamic palette, for code with no Activity to borrow one
     * from.
     *
     * <p>The notification is built in {@code :sync} from a Service context, which is themed by the
     * manifest's application theme but never passes through the Activity overlay above, so an
     * accent read from it would be the fallback blue even on a device showing a green wallpaper
     * palette everywhere else. Wrapping is the supported way to ask for the overlay directly.
     *
     * <p>This carries the dynamic palette but <b>not</b> the semantic pin above, which rides on the
     * Activity callback. That is correct for its one caller: the notification reads
     * {@code colorPrimary}, which is not a pinned role, and would have to be revisited by anything
     * that wanted an error colour from here.
     */
    static Context themed(Context ctx) {
        return DynamicColors.wrapContextIfAvailable(ctx);
    }
}
