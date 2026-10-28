#!/system/bin/sh
# Astrion remote tweaks, run as root by init (astrion-tweaks.rc).
#
#   astrion-tweaks.sh firewall   adb (TCP 5555) reachable only from the allowed computers
#   astrion-tweaks.sh boot       adb over Wi-Fi on/off, the wake-key replay, then the Wi-Fi power save watch
#   astrion-tweaks.sh unfirewall removes the firewall (used by uninstall.sh)
#
# Settings: /vendor/etc/astrion-tweaks.conf (written by install.sh)
#   ADB_WIFI=1|0             adb over Wi-Fi
#   ADB_ALLOW="ip mac ..."   computers allowed to reach it (IPv4 addresses and/or MAC addresses)
#   WIFI_POWERSAVE_OFF=1|0   keep the Wi-Fi driver's power save off
#   WAKE_KEYS=1|0            replay the key that woke the screen (Android drops it)
#
# Why a firewall: this firmware is a userdebug build. adbd runs as root and asks
# no key, so with adb over Wi-Fi anyone on the network gets a root shell.
# ro.adb.secure can't be turned on, so access is limited at the network level.

CONF=/vendor/etc/astrion-tweaks.conf
LOG=/data/local/tmp/astrion-tweaks.log
PORT=5555
CHAIN=astrion_adb

ADB_WIFI=0
ADB_ALLOW=
WIFI_POWERSAVE_OFF=0
WAKE_KEYS=0
# shellcheck disable=SC1090
[ -f "$CONF" ] && . "$CONF"

say() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"
    log -p i -t astrion-tweaks "$*" 2>/dev/null
}

is_mac() {
    case "$1" in
        [0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]) return 0 ;;
    esac
    return 1
}

# Removes our chain and the jump to it (IPv4 and IPv6). Safe to run twice.
unfirewall() {
    for ipt in iptables ip6tables; do
        while $ipt -w -D INPUT -p tcp --dport $PORT -j $CHAIN 2>/dev/null; do :; done
        $ipt -w -F $CHAIN 2>/dev/null
        $ipt -w -X $CHAIN 2>/dev/null
    done
}

# Everything to port 5555 goes through our chain: allowed computers accepted,
# the rest dropped. If this can't run, adb stays as it was (fails open), so a
# mistake never locks out USB adb (USB doesn't go through the firewall at all).
firewall() {
    unfirewall
    [ "$ADB_WIFI" = 1 ] || return 0
    if [ -z "$ADB_ALLOW" ]; then
        say "firewall: no allowed computer, adb over Wi-Fi open to the whole network"
        return 0
    fi
    for ipt in iptables ip6tables; do
        $ipt -w -N $CHAIN 2>/dev/null
        ok=1
        for who in $ADB_ALLOW; do
            if is_mac "$who"; then
                $ipt -w -A $CHAIN -m mac --mac-source "$who" -j ACCEPT || ok=0
            elif [ $ipt = iptables ]; then
                $ipt -w -A $CHAIN -s "$who" -j ACCEPT || ok=0
            fi
        done
        # An allowed computer that couldn't be added: no DROP, rather than locking it out.
        if [ $ok = 1 ]; then
            $ipt -w -A $CHAIN -j DROP
            $ipt -w -I INPUT 1 -p tcp --dport $PORT -j $CHAIN
        else
            say "firewall: $ipt refused a rule, adb left open on it"
        fi
    done
    say "firewall: adb only from $ADB_ALLOW"
}

adb_wifi() {
    if [ "$ADB_WIFI" = 1 ]; then
        # persist.*: on the next boots adbd listens on Wi-Fi as soon as it starts,
        # so the remote stays reachable even if the home app never finishes starting.
        setprop persist.adb.tcp.port $PORT
        if [ "$(getprop service.adb.tcp.port)" != $PORT ]; then
            setprop service.adb.tcp.port $PORT
            setprop ctl.restart adbd
        fi
        say "adb over Wi-Fi on (port $PORT)"
    elif [ -n "$(getprop persist.adb.tcp.port)$(getprop service.adb.tcp.port)" ]; then
        setprop persist.adb.tcp.port ""
        setprop service.adb.tcp.port ""
        setprop ctl.restart adbd
        say "adb over Wi-Fi off"
    fi
}

# The Wi-Fi driver turns its power save back on at every boot, and maybe when
# Wi-Fi reconnects: checked every 30 s, turned off again when needed.
wifi_watch() {
    [ "$WIFI_POWERSAVE_OFF" = 1 ] || return 0
    if ! command -v iw >/dev/null 2>&1; then
        say "wifi: iw not found, power save left as it is"
        return 0
    fi
    iface=$(getprop wifi.interface)
    [ -n "$iface" ] || iface=wlan0
    say "wifi: keeping power save off on $iface"
    while :; do
        case "$(iw dev "$iface" get power_save 2>/dev/null)" in
            *": on"*) iw dev "$iface" set power_save off && say "wifi: power save was on, turned off" ;;
        esac
        sleep 30
    done
}

# The screen's backlight level, read from sysfs (0 = screen off). Cheap: read on every key press.
find_backlight() {
    for f in /sys/class/leds/lcd-backlight/brightness /sys/class/backlight/*/brightness; do
        [ -r "$f" ] && { echo "$f"; return 0; }
    done
    return 1
}

# With the screen off, Android uses a key press to wake the screen and drops it: no app ever
# sees it, so the key has to be pressed twice. This watches the keys at the source (getevent).
# A key pressed while the backlight is off is sent again (sendevent, on the same input device)
# once the screen is back on; Android then delivers it to Astrion like any key. With the screen
# on, nothing is done, so a key is never doubled (the replayed one arrives screen on, too).
# Presses made while it waits for the screen aren't read (Android delivers them itself once it's
# on), so they're never doubled either.
wake_keys() {
    [ "$WAKE_KEYS" = 1 ] || return 0
    if ! command -v getevent >/dev/null 2>&1 || ! command -v sendevent >/dev/null 2>&1; then
        say "wake keys: getevent/sendevent not found, not started"
        return 0
    fi
    bl=$(find_backlight) || { say "wake keys: no backlight in /sys, not started"; return 0; }
    say "wake keys: on (screen state from $bl, now $(cat "$bl" 2>/dev/null))"
    # getevent never flushes its output, so piped it would hand over keys only by 4 KB blocks.
    # "-c 2" makes it exit (and flush) after 2 events: a press is [scan code,] key down, sync.
    while :; do
        out=$(getevent -q -c 2 2>/dev/null)
        if [ -z "$out" ]; then sleep 5; continue; fi
        # Lines: "/dev/input/event1: 0001 0073 00000001" (device, type, code, value in hex).
        echo "$out" | while read -r dev type code value; do
            [ "$type" = 0001 ] && [ "$value" = 00000001 ] || continue   # key presses only
            key=$(printf "%d" "0x$code" 2>/dev/null) || continue
            # BTN_* (touch screen, mouse, gamepad: 0x100-0x15f and 0x2c0+) are not keys. The keys
            # past 0x15f are: the HA100's OK sends KEY_SELECT (0x161).
            if [ "$key" -ge 256 ] && { [ "$key" -lt 352 ] || [ "$key" -ge 704 ]; }; then continue; fi
            [ "$key" = 116 ] && continue        # the real power key turns the screen on/off itself
            level=$(cat "$bl" 2>/dev/null)
            [ "$level" = 0 ] || continue
            dev=${dev%:}
            say "wake keys: key $key on $dev with the screen off"
            # Wait (up to 2 s) for the screen; Android then passes keys on again.
            n=0
            while [ "$(cat "$bl" 2>/dev/null)" = 0 ] && [ $n -lt 40 ]; do sleep 0.05; n=$((n + 1)); done
            if [ $n -lt 40 ]; then
                sleep 0.1
                sendevent "$dev" 1 "$key" 1 && sendevent "$dev" 0 0 0
                sendevent "$dev" 1 "$key" 0 && sendevent "$dev" 0 0 0
                say "wake keys: key $key replayed after wake (${n}x50 ms)"
            else
                say "wake keys: screen still off after 2 s, key $key not replayed"
            fi
        done
    done
}

case "$1" in
    firewall) firewall ;;
    unfirewall) unfirewall ;;
    boot)
        echo "$(date '+%Y-%m-%d %H:%M:%S') boot" > "$LOG"
        firewall
        adb_wifi
        wake_keys &
        wifi_watch
        # init stops a service's other processes when its main one ends: stay while wake_keys runs.
        wait
        ;;
    *) echo "usage: $0 firewall|boot|unfirewall" >&2; exit 2 ;;
esac
exit 0
