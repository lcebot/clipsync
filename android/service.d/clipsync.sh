#!/system/bin/sh
# Optional: copy to /data/adb/service.d/clipsync.sh (chmod 755).
# Clears the app's "stopped" state after install and brings the service up after boot.
#
# It runs as root, which is why it can still start SyncService although the component is not
# exported: AMS grants ROOT_UID and SYSTEM_UID before it ever looks at android:exported. (See the
# note on the <service> element in AndroidManifest.xml.)

PKG=io.github.lcebot.clipsync
SERVICE="$PKG/.SyncService"

# Bounded, because this runs at boot on a device that may never finish booting the way we expect.
# An unbounded `while` here is a shell sitting in /data/adb/service.d forever on any ROM that does
# not set sys.boot_completed, and nothing would ever say so.
i=0
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  i=$((i + 1))
  [ "$i" -gt 90 ] && log -p w -t ClipSync "service.d: gave up waiting for boot_completed" && exit 0
  sleep 2
done
sleep 10

# Is auto-start wanted? The BootReceiver component's enabled state IS the app's Stop switch: Stop
# calls setComponentEnabledSetting(DISABLED), Apply re-enables it, and the system_server watchdog
# reads the same flag (PackageManager.getComponentEnabledSetting). Starting the service
# unconditionally from here overrode a Stop the user had pressed: the service came back by itself
# after every reboot, with nothing in the app to explain why.
#
# There is genuinely no `pm get-enabled-setting` (pm only offers enable/disable/disable-user/
# disable-until-used/default-state and `list packages -d/-e`), so we read `pm dump`. IMPORTANT: a
# component's per-component state is NOT printed as an "enabledSetting=<n>" field: that field does
# not exist. dumpsys/pm dump surfaces the sets instead: a component the user disabled appears under
# the package's "disabledComponents:" section (and an explicitly-enabled one under
# "enabledComponents:"); the bare "enabled=<n>" line is the whole-PACKAGE state, not this component.
# Verified against AOSP: Settings.java persists/reads TAG_DISABLED_COMPONENTS / TAG_ENABLED_COMPONENTS
# and has no per-component enabledSetting print. COMPONENT_ENABLED_STATE_* numbers, for reference,
# are DEFAULT=0, ENABLED=1, DISABLED=2, DISABLED_USER=3, but we key off the list, not a number.
#
# So: treat auto-start as OFF only when BootReceiver is clearly listed inside a disabledComponents
# block. Anything else (dump fails, format differs on some ROM, component simply absent) falls
# through to starting the service (fail-open), the same direction as a fresh install where nothing
# has been disabled yet. awk resets the flag at the enabledComponents header and at any other
# section header so a match can only come from the disabled block.
disabled=$(pm dump "$PKG" 2>/dev/null | awk '
  /disabledComponents/ { inblk = 1; next }
  /enabledComponents/  { inblk = 0 }
  /^[[:space:]]*[A-Za-z].*:[[:space:]]*$/ { if ($0 !~ /disabledComponents/) inblk = 0 }
  inblk && /BootReceiver/ { print "1"; exit }
')
if [ "$disabled" = "1" ]; then
  log -p i -t ClipSync "service.d: auto-start is off (the app's Stop switch); not starting"
  exit 0
fi

am start-foreground-service -n "$SERVICE" >/dev/null 2>&1
