#!/system/bin/sh
# Removes the Astrion remote tweaks. Runs on the remote:
#
#   adb root
#   adb shell sh /data/local/tmp/astrion-device/uninstall.sh
#   adb reboot
#
# adb over Wi-Fi stops after the reboot (use USB again); the Wi-Fi power save
# goes back to the driver's default (on).

die() { echo "error: $*" >&2; exit 1; }
[ "$(id -u)" = 0 ] || die "not root: run 'adb root' on the computer first, then try again"

# The firewall goes now; adbd keeps its current port until the reboot.
[ -x /vendor/bin/astrion-tweaks.sh ] && sh /vendor/bin/astrion-tweaks.sh unfirewall
setprop persist.adb.tcp.port ""

if grep -q ' /vendor ' /proc/mounts; then PART=/vendor; else PART=/system; fi
mount -o rw,remount $PART || die "can't make $PART writable"
rm -f /vendor/etc/init/astrion-tweaks.rc /vendor/bin/astrion-tweaks.sh /vendor/etc/astrion-tweaks.conf
sync
mount -o ro,remount $PART
rm -f /data/local/tmp/astrion-tweaks.log

echo "Removed. Restart the remote to finish: adb reboot"
exit 0
