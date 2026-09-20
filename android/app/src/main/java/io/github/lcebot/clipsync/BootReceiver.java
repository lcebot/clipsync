package io.github.lcebot.clipsync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts SyncService on boot / after update. Note: a freshly installed app is in the
 * "stopped" state and will NOT receive BOOT_COMPLETED until a component has been started
 * once explicitly, which opening MainActivity does (or, via root:
 * am start-foreground-service -n io.github.lcebot.clipsync/.SyncService).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.startForegroundService(new Intent(context, SyncService.class));
    }
}
