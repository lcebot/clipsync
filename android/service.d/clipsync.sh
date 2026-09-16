#!/system/bin/sh
# Optional: copy to /data/adb/service.d/clipsync.sh (chmod 755).
# Clears the app's "stopped" state after install and guarantees the service is up after boot.
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done
sleep 10
am start-foreground-service -n io.github.lcebot.clipsync/.SyncService >/dev/null 2>&1
