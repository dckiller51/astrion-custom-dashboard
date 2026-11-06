# Remote tweaks (advanced, optional)

Not part of the app: small system files installed on the HA100 itself with adb.
How to use them, step by step: wiki page
[adb over Wi-Fi & Wi-Fi latency](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Remote-Tweaks).

| File | Where it goes | What it does |
|---|---|---|
| `astrion-tweaks.rc` | `/vendor/etc/init/` | Starts the two services below at boot (firewall as soon as the network is up) |
| `astrion-tweaks.sh` | `/vendor/bin/` | `firewall`: adb on port 5555 reachable only from the allowed computers (IPv4 and IPv6). `boot`: adb over Wi-Fi on/off, the wake-key replay (optional), then keeps the Wi-Fi driver's power save off (checked every 30 s) |
| `/vendor/etc/astrion-tweaks.conf` | written by `install.sh` | `ADB_WIFI`, `ADB_ALLOW`, `WIFI_POWERSAVE_OFF`, `WAKE_KEYS` |
| `install.sh`, `uninstall.sh`, `status.sh` | run on the remote from `/data/local/tmp/astrion-device/` | install / remove / show what's running |

The scripts run **on the remote** (`adb shell sh …`), so the same commands work
from Windows, macOS and Linux:

```sh
adb root
adb shell rm -rf /data/local/tmp/astrion-device
adb push device /data/local/tmp/astrion-device
adb shell sh /data/local/tmp/astrion-device/install.sh --adb-allow <your computer's IP>
adb reboot
adb connect <remote IP>:5555
```

## Why

- **adb over Wi-Fi**: `adb tcpip 5555` is lost at every reboot, and this firmware
  has nothing that reads `persist.adb.tcp.port` at boot, so it's back to the USB cable.
- **Firewall**: the firmware is a `userdebug` build. adbd runs as root and asks no
  key, so adb over Wi-Fi gives a root shell to anyone on the network.
  `ro.adb.secure` is read-only, so access is limited with iptables instead. It
  fails open (never locks you out; USB adb doesn't go through it). An allowed IP
  that the remote has already seen also gets its MAC address allowed, so a new
  address from the router doesn't lock the computer out.
- **Wi-Fi power save**: the MT6580 Wi-Fi driver has 802.11 power save on by default
  and turns it back on at every boot. With it off, a ping to the remote went from
  40–50 ms to 11 ms. The app's "Keep Wi-Fi awake" setting doesn't change it (it
  only takes an Android lock), and the app can't: it needs root.
- **Wake keys** (`--wake-keys`, optional, 1.4.0): with the screen off, Android uses the
  key press to wake the screen and drops it before any app sees it (even an
  accessibility service), so the key has to be pressed twice. The service reads the
  keys at the source (`getevent`); a key pressed while the backlight is off is sent
  again (`sendevent`, same input device) once the screen is on, and Android delivers
  it to Astrion like any other. Screen on, it does nothing: no key is ever doubled.
  The real power key (Linux `KEY_POWER`) is left out. Idea from a user's report.
  `--wake-only KEY` (1.4.2; repeat for several keys): with the screen off, that key
  only wakes the screen and isn't replayed, e.g. `--wake-only ok` to look at the
  dashboard without sending OK to the TV. A key is given by its name (the builder's)
  or its Linux code (decimal, or hex like `0x161`):

  | Key | Name | Linux code |
  |---|---|---|
  | Back | `back` | 158 |
  | Home | `home` | 59 |
  | Power | `power` | 60 |
  | Volume + / − | `volume_up` / `volume_down` | 115 / 114 |
  | Mute | `mute` | 113 |
  | Page + / − | `page_up` / `page_down` | 104 / 109 |
  | Up / Down | `up` / `down` | 103 / 108 |
  | Left / Right | `left` / `right` | 105 / 106 |
  | OK | `ok` | 353 |
  | Microphone | `voice` | 61 |
  | Menu | `menu` | 139 |
  | The 4 shortcut keys (light, curtain, scene, AC) | `rewind` / `play` / `stop` / `fastforward` | 62 / 63 / 64 / 65 |
  | Red / Green | `red` / `green` | 66 / 67 |
  | Blue / Yellow | `blue` / `yellow` | 68 / 87 |

## Safety

A **new** `.rc` file: no existing file is changed. If init can't read it, it
skips it and the remote boots as before. A firmware reflash removes everything;
install again afterwards. `uninstall.sh` removes it all.

## Credits

The init hook layout (a `.rc` in `/vendor/etc/init` starting `seclabel u:r:su:s0`
services on `sys.boot_completed`, the persisted adb port, the firewall applied
when netd starts, MAC-based allow rules) follows
[baes-cloud/astrion-dashboard](https://github.com/baes-cloud/astrion-dashboard)'s
`device/` hooks (MIT), rewritten here.
