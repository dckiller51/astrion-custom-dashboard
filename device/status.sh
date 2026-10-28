#!/system/bin/sh
# What the Astrion remote tweaks are doing right now. Changes nothing:
#
#   adb root
#   adb shell sh /data/local/tmp/astrion-device/status.sh

line() { printf '  %-22s %s\n' "$1" "$2"; }

echo "== Files"
for f in /vendor/etc/init/astrion-tweaks.rc /vendor/bin/astrion-tweaks.sh /vendor/etc/astrion-tweaks.conf; do
    if [ -f "$f" ]; then line ok "$f"; else line MISSING "$f"; fi
done

echo "== Settings (/vendor/etc/astrion-tweaks.conf)"
grep -v '^#' /vendor/etc/astrion-tweaks.conf 2>/dev/null | sed 's/^/  /'

echo "== Services"
line astrion_adbfw "$(getprop init.svc.astrion_adbfw)"
line astrion_tweaks "$(getprop init.svc.astrion_tweaks)"

echo "== adb over Wi-Fi"
line service.adb.tcp.port "$(getprop service.adb.tcp.port)"
line persist.adb.tcp.port "$(getprop persist.adb.tcp.port)"
if [ "$(id -u)" = 0 ]; then
    echo "  firewall (IPv4):"
    iptables -w -S astrion_adb 2>/dev/null | sed 's/^/    /' || echo "    none"
else
    echo "  firewall: run 'adb root' first to see it"
fi

echo "== Wi-Fi"
iface=$(getprop wifi.interface); [ -n "$iface" ] || iface=wlan0
line "power save" "$(iw dev "$iface" get power_save 2>/dev/null || echo 'unknown (needs adb root)')"

echo "== Wake keys"
if grep -q '^WAKE_KEYS=1' /vendor/etc/astrion-tweaks.conf 2>/dev/null; then
    line "replay" "on"
    line "getevent running" "$(pgrep -f '^getevent' >/dev/null 2>&1 && echo yes || echo 'no (see the log)')"
else
    line "replay" "off (install.sh --wake-keys)"
fi

echo "== Log (/data/local/tmp/astrion-tweaks.log)"
tail -n 15 /data/local/tmp/astrion-tweaks.log 2>/dev/null | sed 's/^/  /'
exit 0
