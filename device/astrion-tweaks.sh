#!/system/bin/sh
# Astrion remote tweaks, run as root by init (astrion-tweaks.rc).
#
#   astrion-tweaks.sh firewall   adb (TCP 5555) reachable only from the allowed computers
#   astrion-tweaks.sh boot       adb over Wi-Fi on/off, then the Wi-Fi power save watch
#   astrion-tweaks.sh unfirewall removes the firewall (used by uninstall.sh)
#
# Settings: /vendor/etc/astrion-tweaks.conf (written by install.sh)
#   ADB_WIFI=1|0             adb over Wi-Fi
#   ADB_ALLOW="ip mac ..."   computers allowed to reach it (IPv4 addresses and/or MAC addresses)
#   WIFI_POWERSAVE_OFF=1|0   keep the Wi-Fi driver's power save off
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

case "$1" in
    firewall) firewall ;;
    unfirewall) unfirewall ;;
    boot)
        echo "$(date '+%Y-%m-%d %H:%M:%S') boot" > "$LOG"
        firewall
        adb_wifi
        wifi_watch
        ;;
    *) echo "usage: $0 firewall|boot|unfirewall" >&2; exit 2 ;;
esac
exit 0
