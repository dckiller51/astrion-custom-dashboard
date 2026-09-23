<!-- markdownlint-disable-next-line MD033 -->
# <img src="docs/app-icon.svg" width="48" align="center" alt="Astrion Custom Icon"> Astrion Custom Dashboard

<!-- markdownlint-disable-next-line MD033 -->
<p align="center">
  <!-- markdownlint-disable-next-line MD033 -->
  <img src="docs/banner_astrion_custom_dashboard.png" alt="Astrion Custom Dashboard Banner">
</p>

[![GH-release](https://img.shields.io/github/v/release/dckiller51/astrion-custom-dashboard.svg?style=flat-square)](https://github.com/dckiller51/astrion-custom-dashboard/releases)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-Buy_me_a_coffee-F16061?style=flat-square&logo=ko-fi&logoColor=white)](https://ko-fi.com/dckiller)

A custom Android application for controlling [Home Assistant](https://www.home-assistant.io/) via a dedicated Sanytron Astrion HA100 remote control, developed using Kotlin and Jetpack Compose.

It features a swipe-navigable, full-screen dashboard driven entirely by a JSON configuration file; the display does not rely on the standard Lovelace/HA app, and the application replaces the original HaRemote launcher.

## How it works

- **Swipeable pages**: the dashboard is a series of pages (`dashboard.json`), each holding cards (lights, climate, media, scenes, cameras, vacuum, etc.). Navigate horizontally by touch or via the remote's physical keys.
- **Settings panel**: reached only by swiping down from the top of the screen (like the Android notification shade). It's a full-screen overlay independent of the dashboard pages, so it never shows up in the horizontal swipe or the page-indicator dots. Dismissed by swiping up, the back button, or the close button. It groups: screen brightness, quick access to Wi-Fi and Android system settings, wake-on-motion, and connection status for Home Assistant / Harmony Hub — plus the local configuration address (see below).
- **Cards (`cards/impl/`)**: each card type (light, climate, media...) is a `CardRenderer` registered in `CardRegistry` (see `AstrionApp.kt`) and instantiated dynamically from `dashboard.json` by its `type`.
- **Live configuration**: `dashboard.json` is read from `/sdcard/astrion/dashboard.json` by `DashboardLoader`, editable directly (adb push, file manager, or the local web page — see below) without recompiling the app. Custom icons live in `/sdcard/astrion/icons`.
- **Home Assistant connection**: WebSocket via `HaClient`.
- **Harmony Hub integration** (optional) for IR remotes/activities.
- **Physical keys**: `HardwareKeyRouter` maps the box's hardware keys to hotkeys defined in the config (page navigation, quick actions...).

### Local configuration (no adb needed)

The app ships with **no Home Assistant credentials baked in** — every install starts unconfigured on purpose, so a prebuilt APK can be shared publicly without exposing anyone's personal setup. The device runs a small local web server on **`http://<remote-ip>:8080`** (the address is also shown in the Settings panel). From any browser on the same network you can:

- set the Home Assistant URL/token and Harmony Hub IP/ID,
- upload a new `dashboard.json` (and download the current one as a backup),
- upload icon PNGs into `/sdcard/astrion/icons/`,
- check for and install app updates (see below).

Saving connection settings restarts the app to reconnect; uploading `dashboard.json` reloads the dashboard live.

### Dashboard editor

Building `dashboard.json` by hand is optional — the [**online dashboard editor**](https://dckiller51.github.io/astrion-custom-dashboard/) lets you add pages, cards, and hotkeys through forms and generates the JSON for you (or lets you load and edit an existing file). Download the result and upload it from the local `:8080` configuration page above, no adb needed.

### Updates

The same local page can check this repository's [GitHub Releases](https://github.com/dckiller51/astrion-custom-dashboard/releases) for a newer build and download + launch the system installer for it — no adb required for updates either. Android requires manually approving "install unknown apps" for Astrion Custom the first time (the page will prompt for it and tell you to try again once granted); after that, updating is just two taps.

### Beta builds

For testing fixes before they land in an official release, pushes to `dev` are also published as a rolling **pre-release** (tag `dev-latest`, overwritten by each new push — never picked up by the updater above, which explicitly skips pre-releases). Grab it from the [**online dashboard editor**](https://dckiller51.github.io/astrion-custom-dashboard/): flip the "Version bêta (dev)" switch near the top to reveal the current beta version and a direct APK download link.

The beta installs as a separate app (`com.custom.astrion.debug`) alongside the official release — no signature conflict, no need to uninstall either one to switch between them. Its Home Assistant/Harmony settings are configured independently the first time you install it (see Local configuration above), then kept per install from then on.

### Translations

Two separate mechanisms, designed so the community can add a language without touching Kotlin code:

- **App's own text** ("Settings", "Wi-Fi network"...) → standard Android resources, `res/values/strings.xml` (English fallback) + `res/values-<lang>/strings.xml`. Follows the system language automatically, handled natively by Android.
- **Values returned by Home Assistant** (`hvac_mode`, `fan_mode`, weather conditions, vacuum states...) → `assets/ha_labels/<lang>.json`, loaded by `HaLabels` (`ha/HaLabels.kt`). These come from the HA integration itself (raw English, sometimes with hyphens like `clear-night`), so they can't be plain `@string` resources.

**Adding a language**: drop in `res/values-xx/strings.xml` (translation of `res/values/strings.xml`) and `assets/ha_labels/xx.json` (same shape as `en.json`/`fr.json`) — no code change needed. `en.json` is the mandatory fallback if the requested language has no file.

## First install

### Option A: Automatic Installer (Recommended for Windows)

If you are on Windows, you can download the ready-to-use automatic installer package from the [**GitHub Releases**](https://github.com/dckiller51/astrion-custom-dashboard/releases/tag/initial-installation-v0.9.0) page:

1. Download and extract **`initial-installation-v0.9.0`**.
2. Enable **Developer options** and **USB debugging** on your remote.
3. Double-click **`install.bat`** and follow the on-screen instructions.
4. **Restart the remote.** Some permissions and the launcher registration only take full effect after a full reboot, not just relaunching the app.
5. On reboot, a prompt appears to choose the home screen app — select **Astrion Custom**.
6. **Grant storage access** when prompted (needed to read/write `dashboard.json` and icons on `/sdcard/astrion/`).
7. Open the **Settings panel** (swipe down from the top of the screen). Above the brightness slider, tap **"Allow modification"**, then select **Astrion Custom** in the system screen that opens, and go back.
8. Open **`http://<remote-ip>:8080`** from a browser on the same network to finish setup: Home Assistant URL/token, optional Harmony Hub, and your `dashboard.json`.

### Option B: Manual via ADB

1. Enable **Developer options** on the remote (usually: *Settings → About device*, tap the build number several times), then enable USB debugging.
2. `adb install app-debug.apk`
3. **Restart the remote.** Some permissions and the launcher registration only take full effect after a full reboot, not just relaunching the app.
4. On reboot, a prompt appears to choose the home screen app — select **Astrion Custom**.
5. **Grant storage access** when prompted (needed to read/write `dashboard.json` and icons on `/sdcard/astrion/`).
6. Open the **Settings panel** (swipe down from the top of the screen). Above the brightness slider, tap **"Allow modification"**, then select **Astrion Custom** in the system screen that opens, and go back.
7. Open **`http://<remote-ip>:8080`** from a browser on the same network to finish setup: Home Assistant URL/token, optional Harmony Hub, and your `dashboard.json`.

For a detailed step-by-step setup guide, including ADB access,
permissions, Home Assistant connection, and the first dashboard, see
[Getting Started](docs/GETTING_STARTED.md).

## Uninstalling

### Option A: Automatic Uninstaller (Windows)

If you are on Windows, you can download the ready-to-use automatic uninstaller package from the [**GitHub Releases**](https://github.com/dckiller51/astrion-custom-dashboard/releases/tag/uninstall) page:

1. Double-click **`uninstall.bat`**.
2. Follow the on-screen instructions. The remote will automatically fall back to the stock **HaRemote** launcher.

### Option B: Manual via ADB

If you prefer using the command line:

```bash
adb uninstall com.custom.astrion
```

The remote falls back to the stock **HaRemote** launcher.

## Building from source

```bash
./gradlew assembleDebug
adb uninstall com.custom.astrion   # avoids signature conflicts with a previous install
adb install app/build/outputs/apk/debug/app-debug.apk
```

No `secrets.properties` or any other pre-build configuration is needed — every instance is configured after install through the local `:8080` page above.

## Repository structure

```txt
src/main/
  assets/ha_labels/        Home Assistant state translations (JSON, per language)
  java/com/custom/astrion/
    cards/impl/             one card = one CardRenderer, registered in AstrionApp
    config/                 dashboard.json loading/parsing, runtime connection settings
    ha/                     HA WebSocket client, models, state translations
    harmony/                Harmony Hub client
    input/                  physical key routing
    ui/                     Dashboard (pager + settings overlay), screen components
    update/                 GitHub Releases update checker
    web/                    local :8080 configuration server
  res/values*/strings.xml   app text, per language
  AndroidManifest.xml
```

See [CHANGELOG.md](./CHANGELOG.md) for release notes.

## Contributing

For now, contributions are welcome specifically for **translations** — see the Translations section above. Drop the two files for your language (`res/values-xx/strings.xml` and `assets/ha_labels/xx.json`) and open a pull request.

## Credits

Special thanks to [**@baes-cloud**](https://github.com/baes-cloud/astrion-dashboard) for the original work this project is built on (MIT License — see [`THIRD_PARTY_LICENSES.md`](./THIRD_PARTY_LICENSES.md)).

## License

**Astrion Custom Dashboard** is licensed under the [GNU GPLv3](./LICENSE).

This project started as a fork of [**@baes-cloud**](https://github.com/baes-cloud/astrion-dashboard)'s original work, released under the MIT License — that original notice is preserved in [`THIRD_PARTY_LICENSES.md`](./THIRD_PARTY_LICENSES.md), as its terms require.

[![License: GPLv3](https://img.shields.io/badge/License-GPLv3-blue.svg?style=for-the-badge)](./LICENSE)

---

## ☕ Support

If you find **Astrion Custom Dashboard** useful and want to support its development, you can buy me a coffee!

[![Ko-fi](https://img.shields.io/badge/Buy_me_a_coffee-Ko--fi-F16061?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/dckiller)
