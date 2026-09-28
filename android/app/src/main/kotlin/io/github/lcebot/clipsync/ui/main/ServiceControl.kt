package io.github.lcebot.clipsync.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.lcebot.clipsync.BootReceiver
import io.github.lcebot.clipsync.Status
import io.github.lcebot.clipsync.SyncService

/**
 * Everything the screen does to the service, behind one seam, so the action logic around it can be
 * tested without a device.
 */
interface ServiceControl {
    fun isAlive(): Boolean

    /**
     * "Auto-start" is the BootReceiver component being enabled. Disabled, neither BOOT_COMPLETED nor
     * the system_server watchdog brings the service back; that is what makes Stop stick.
     */
    fun isAutoStartEnabled(): Boolean

    fun setAutoStart(on: Boolean)

    /** Starts a stopped service in the foreground. */
    fun start()

    /** Asks a running service to re-read its config in place: no restart, no process churn. */
    fun reload()

    fun stop()
}

class AndroidServiceControl(context: Context) : ServiceControl {
    private val app = context.applicationContext
    private val receiver = ComponentName(app, BootReceiver::class.java)

    override fun isAlive(): Boolean = Status.read(app).alive()

    override fun isAutoStartEnabled(): Boolean =
        app.packageManager.getComponentEnabledSetting(receiver) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    override fun setAutoStart(on: Boolean) {
        app.packageManager.setComponentEnabledSetting(
            receiver,
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            // The receiver lives in :sync; the default would kill this app's processes to apply it.
            PackageManager.DONT_KILL_APP,
        )
    }

    override fun start() {
        app.startForegroundService(Intent(app, SyncService::class.java))
    }

    override fun reload() {
        app.startService(Intent(app, SyncService::class.java).setAction(SyncService.ACTION_RELOAD))
    }

    override fun stop() {
        app.stopService(Intent(app, SyncService::class.java))
    }
}
