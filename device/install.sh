#!/system/bin/sh
# Installs the Astrion remote tweaks. Runs ON THE REMOTE, so it works the same
# from Windows, macOS or Linux. From the computer, remote plugged in by USB:
#
#   adb root
#   adb shell rm -rf /data/local/tmp/astrion-device
#   adb push device /data/local/tmp/astrion-device
#
# The rm first: when the folder is already there, adb push copies into a
# "device" subfolder inside it and the old scripts stay in place.
#   adb shell sh /data/local/tmp/astrion-device/install.sh --adb-allow 192.168.1.20
#   adb reboot
#
# Options (run it again with other options to change them):
#   --adb-allow WHO   a computer allowed to use adb over Wi-Fi: its IPv4 address
#                     or its MAC address (aa:bb:cc:dd:ee:ff). Repeat for several.
#   --adb-open        adb over Wi-Fi with no firewall: ANYONE on the network gets
#                     a root shell. Not recommended.
#   --no-adb          no adb over Wi-Fi (only the Wi-Fi power save part)
#   --no-wifi         leave the Wi-Fi power save alone (only the adb part)
#   --wake-keys       also replay the key that wakes the screen (with the screen
#                     off, Android uses the first press to wake it and drops it)
#   --wake-only KEY   with --wake-keys: this key only wakes the screen, it is not
#                     replayed. Its name, as in the builder's key list: ok, back,
#                     home, power, volume_up, volume_down, mute, page_up,
#                     page_down, up, down, left, right, voice, menu, rewind, play,
#                     stop, fastforward, red, green, blue, yellow; or a Linux key
#                     code (decimal, or hex like 0x161). Repeat for several keys.
#
# What it writes: /vendor/etc/init/astrion-tweaks.rc, /vendor/bin/astrion-tweaks.sh,
# /vendor/etc/astrion-tweaks.conf. Nothing existing is changed. Takes effect at
# the next reboot. Undo with uninstall.sh.

HERE=$(cd "$(dirname "$0")" && pwd)
ADB_WIFI=1
ADB_ALLOW=
OPEN=0
WIFI_POWERSAVE_OFF=1
WAKE_KEYS=0
WAKE_ONLY=

die() { echo "error: $*" >&2; exit 1; }

# An HA100 key's Linux code (what the kernel sends, see: adb shell getevent -l), by the name
# the builder gives it; or a code given as is (decimal, or hex like 0x161). Android then turns
# KEY_F1..KEY_F11 into its keycodes 131..141 (reference/device_key_code.json, HardwareKeys.kt).
key_code() {
    case "$(echo "$1" | tr 'A-Z-' 'a-z_')" in
        back) echo 158 ;;
        home) echo 59 ;;           # KEY_F1
        power) echo 60 ;;          # KEY_F2
        volume_up) echo 115 ;;
        volume_down) echo 114 ;;
        mute) echo 113 ;;
        page_up) echo 104 ;;
        page_down) echo 109 ;;
        up) echo 103 ;;
        down) echo 108 ;;
        left) echo 105 ;;
        right) echo 106 ;;
        ok|center|select) echo 353 ;;   # KEY_SELECT
        voice|mic) echo 61 ;;      # KEY_F3
        menu|main) echo 139 ;;
        rewind|light) echo 62 ;;           # KEY_F4, Android 134
        play|curtain) echo 63 ;;           # KEY_F5, Android 135
        stop|scene) echo 64 ;;             # KEY_F6, Android 136
        fastforward|ac) echo 65 ;;         # KEY_F7, Android 137
        red|red_button) echo 66 ;;         # KEY_F8
        green|green_button) echo 67 ;;     # KEY_F9
        blue|blue_button) echo 68 ;;       # KEY_F10
        yellow|yellow_button) echo 87 ;;   # KEY_F11
        0x*) printf '%d\n' "$1" 2>/dev/null ;;
        *[!0-9]*|'') return 1 ;;
        *) echo "$1" ;;
    esac
}

while [ $# -gt 0 ]; do
    case "$1" in
        --adb-allow) [ -n "$2" ] || die "--adb-allow needs an IP or MAC address"; ADB_ALLOW="$ADB_ALLOW $2"; shift ;;
        --adb-open) OPEN=1 ;;
        --no-adb) ADB_WIFI=0 ;;
        --no-wifi) WIFI_POWERSAVE_OFF=0 ;;
        --wake-keys) WAKE_KEYS=1 ;;
        --wake-only)
            [ -n "$2" ] || die "--wake-only needs a key: ok, or a key code"
            code=$(key_code "$2") || die "--wake-only: unknown key $2 (see --help)"
            WAKE_ONLY="$WAKE_ONLY $code"
            shift ;;
        -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
        *) die "unknown option $1 (see --help)" ;;
    esac
    shift
done

[ "$(id -u)" = 0 ] || die "not root: run 'adb root' on the computer first, then try again"
[ -f "$HERE/astrion-tweaks.rc" ] && [ -f "$HERE/astrion-tweaks.sh" ] || die "astrion-tweaks.rc / astrion-tweaks.sh not found next to install.sh"
# Release (com.custom.astrion) or debug build (com.custom.astrion.debug).
pm list packages 2>/dev/null | grep -q 'package:com\.custom\.astrion' || die "Astrion custom is not installed: is this the right device?"
[ "$ADB_WIFI" = 1 ] || [ "$WIFI_POWERSAVE_OFF" = 1 ] || [ "$WAKE_KEYS" = 1 ] || die "--no-adb and --no-wifi together: nothing to install"
if [ "$ADB_WIFI" = 1 ] && [ -z "$ADB_ALLOW" ] && [ $OPEN = 0 ]; then
    die "say which computer may use adb over Wi-Fi: --adb-allow <its IP>  (or --no-adb, or --adb-open)"
fi

# The remote's own address given by mistake (it's the one used for its web page): the firewall
# would then let only the remote itself in and lock the computer out of adb over Wi-Fi.
own_ips=$(ip -4 addr 2>/dev/null | sed -n 's/.*inet \([0-9.]*\).*/\1/p')
for who in $ADB_ALLOW; do
    for own in $own_ips; do
        [ "$who" = "$own" ] && die "$who is this remote's own address. Give your COMPUTER's IP (Windows: ipconfig, IPv4 address; macOS: ipconfig getifaddr en0; Linux: ip -4 addr)"
    done
done

# An allowed IP the remote has already seen: add its MAC too, so a new address
# from the router (DHCP) doesn't lock the computer out.
for who in $ADB_ALLOW; do
    case "$who" in
        *.*.*.*)
            mac=
            while read -r ip _ _ hw _; do
                [ "$ip" = "$who" ] && [ "$hw" != 00:00:00:00:00:00 ] && mac=$hw
            done < /proc/net/arp
            if [ -n "$mac" ]; then
                case " $ADB_ALLOW " in *" $mac "*) ;; *) ADB_ALLOW="$ADB_ALLOW $mac"; echo "found $who's MAC address: $mac (allowed too)" ;; esac
            fi
            ;;
    esac
done
# shellcheck disable=SC2086,SC2116 # trims the spaces
ADB_ALLOW=$(echo $ADB_ALLOW)
[ -z "$WAKE_ONLY" ] || [ "$WAKE_KEYS" = 1 ] || die "--wake-only goes with --wake-keys"
# shellcheck disable=SC2086,SC2116 # trims the spaces
WAKE_ONLY=$(echo $WAKE_ONLY)

# /vendor is its own partition on some firmwares, part of /system on others.
if grep -q ' /vendor ' /proc/mounts; then PART=/vendor; else PART=/system; fi
mount -o rw,remount $PART || die "can't make $PART writable"
trap 'mount -o ro,remount $PART' EXIT

mkdir -p /vendor/etc/init /vendor/bin
# tr -d '\r': files edited on Windows may have CRLF line ends, which sh and init can't read.
tr -d '\r' < "$HERE/astrion-tweaks.rc" > /vendor/etc/init/astrion-tweaks.rc || die "copy failed"
tr -d '\r' < "$HERE/astrion-tweaks.sh" > /vendor/bin/astrion-tweaks.sh || die "copy failed"
cat > /vendor/etc/astrion-tweaks.conf <<EOF
# Astrion remote tweaks (written by install.sh; run it again to change)
ADB_WIFI=$ADB_WIFI
ADB_ALLOW="$ADB_ALLOW"
WIFI_POWERSAVE_OFF=$WIFI_POWERSAVE_OFF
WAKE_KEYS=$WAKE_KEYS
WAKE_ONLY="$WAKE_ONLY"
EOF
chmod 644 /vendor/etc/init/astrion-tweaks.rc /vendor/etc/astrion-tweaks.conf
chmod 755 /vendor/bin/astrion-tweaks.sh
chown root:root /vendor/etc/init/astrion-tweaks.rc /vendor/bin/astrion-tweaks.sh /vendor/etc/astrion-tweaks.conf
restorecon /vendor/etc/init/astrion-tweaks.rc /vendor/bin/astrion-tweaks.sh /vendor/etc/astrion-tweaks.conf 2>/dev/null
sync

echo "Installed:"
[ "$ADB_WIFI" = 1 ] && echo "  adb over Wi-Fi, port 5555, allowed: ${ADB_ALLOW:-EVERYONE (no firewall)}"
[ "$WIFI_POWERSAVE_OFF" = 1 ] && echo "  Wi-Fi power save kept off"
[ "$WAKE_KEYS" = 1 ] && echo "  the key that wakes the screen is replayed"
[ "$WAKE_KEYS" = 1 ] && [ -n "$WAKE_ONLY" ] && echo "  except, only waking the screen: key(s)$WAKE_ONLY"
echo "Now restart the remote: adb reboot"
[ "$ADB_WIFI" = 1 ] && echo "Then, without USB: adb connect <remote IP>:5555"
exit 0
