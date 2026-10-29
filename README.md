<!-- markdownlint-disable-next-line MD033 -->
# <img src="docs/app-icon.svg" width="48" align="center" alt="Astrion Custom Icon"> Astrion Custom Dashboard

<!-- markdownlint-disable-next-line MD033 -->
<p align="center">
  <!-- markdownlint-disable-next-line MD033 -->
  <img src="docs/banner_astrion_custom_dashboard.png" alt="Astrion Custom Dashboard Banner">
</p>

[![GH-release](https://img.shields.io/github/v/release/dckiller51/astrion-custom-dashboard.svg?style=flat-square&label=stable)](https://github.com/dckiller51/astrion-custom-dashboard/releases/latest)
[![Stable downloads](https://img.shields.io/github/downloads/dckiller51/astrion-custom-dashboard/latest/total?style=flat-square&label=stable%20downloads)](https://github.com/dckiller51/astrion-custom-dashboard/releases/latest)
[![Beta downloads](https://img.shields.io/github/downloads/dckiller51/astrion-custom-dashboard/dev-latest/total?style=flat-square&label=beta%20downloads&color=orange)](https://github.com/dckiller51/astrion-custom-dashboard/releases/tag/dev-latest)
[![Wiki](https://img.shields.io/badge/Docs-Wiki-blue?style=flat-square)](https://github.com/dckiller51/astrion-custom-dashboard/wiki)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-Buy_me_a_coffee-F16061?style=flat-square&logo=ko-fi&logoColor=white)](https://ko-fi.com/dckiller)

A standalone Android app that turns the **Sanytron Astrion HA100** remote into a dedicated smart-home and AV remote. It replaces the stock HaRemote launcher with a swipeable, full-screen dashboard, built with Kotlin and Jetpack Compose.

The dashboard doesn't depend on Lovelace or the Home Assistant app. It is driven by one `dashboard.json` file that you build in your browser, and it can talk to Home Assistant, a Harmony Hub, its own IR blaster, and an Apple TV or Android TV directly.

📖 **Documentation lives in the [Wiki](https://github.com/dckiller51/astrion-custom-dashboard/wiki)**. This page is the overview.

🎬 **Your Logitech Harmony died?** Start with [Coming from Harmony](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Coming-from-Harmony): Activities, IR, your Harmony codes and Apple TV, no cloud, Home Assistant optional.

## Features

- **Swipeable pages of cards**: lights, covers, climate, media, cameras, vacuums, Plex, Xbox, scenes, remotes… ([card reference](https://github.com/dckiller51/astrion-custom-dashboard/wiki)).
- **Browser-based builder** at `http://<remote-ip>:8080/builder/`. You edit visually with a live preview of the remote, and no hand-written JSON is needed. Available in English, French, German, Italian and Spanish.
- **Undo and version history**: undo / redo in the builder (Ctrl+Z / Ctrl+Y), and the remote keeps every saved version of its dashboard. Open, restore, pin or annotate any of them ([details](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Builder#undo-and-version-history)).
- **Physical keys**: every hardware key, short or long press, can be bound to a page, a popup, a Home Assistant service or event, a Harmony or IR command, an Apple TV command, an Android TV key or app, or an Activity. Bindings can be global or per page.
- **Activities**: "Watch Apple TV", "Listen to music"… One tap powers on the right devices, switches inputs, binds the volume keys and opens the right page. Activities are tracked per room, and the "Off" button works like Harmony's.
- **Many ways to control your devices**:
  - Home Assistant entities (WebSocket, live state);
  - Harmony Hub, local network, no cloud;
  - the remote's own **IR blaster**, with a built-in IR database ([ir-sniffer](https://dckiller51.github.io/astrion-ir-sniffer/)), or network IR blasters ([astrion-ir-extender](https://github.com/dckiller51/astrion-ir-extender)) to reach a closed cabinet or another room;
  - **Apple TV paired directly**: remote, now playing and app launching, without Home Assistant;
  - **Android TV / Google TV paired directly** (NVIDIA SHIELD, Chromecast with Google TV, Sony, Philips…): remote, power, app launching, volume, without Home Assistant or ADB. With the small **Astrion TV Helper** app installed on the TV: title, cover and progress of what's playing, and any app opens ([Android TV](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Android-TV)).
- **Dock screen**: while charging, show the battery, a dim night-friendly clock (time, weather, what's playing in the room) or the dimmed dashboard.
- **Weather without Home Assistant**: the remote fetches its own forecast from Open-Meteo (free, no key, Météo-France models available) for the clock & weather card and the dock clock.
- **Battery-friendly standby**: quiet Home Assistant mode while the screen is off, a time-limited motion wake, and a screen timeout.
- **Several remotes in one home**: each remote has its own identity and name, so Home Assistant knows which remote and which button sent an event.
- **Home Assistant integration**: [ha-astrion-custom-dashboard](https://github.com/dckiller51/ha-astrion-custom-dashboard) adds each remote as a device in Home Assistant (battery, last seen, button events, "Find my remote").
- **Managing several remotes from Home Assistant**: the community add-on [Astrion Config Manager](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Astrion-Config-Manager) by [@dvbit](https://github.com/dvbit) lists all your remotes in the Home Assistant sidebar, checks entities before sending, copies pages between remotes and has a live simulator.
- **Find my remote**: rings the remote from its web page or from Home Assistant.
- **Self-updating**: the remote's web page checks GitHub Releases and installs updates. No adb needed after the first install.

## Cards

| Card | What it's for | Home Assistant | Harmony | Local IR | Apple TV (direct) | Android TV (direct) |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| [`light`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-Light) | Lights: brightness, colour, colour temperature | ✅ | | | | |
| [`cover`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-Cover) | Shutters, blinds, gates | ✅ | | | | |
| [`switch`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-Switch) | Simple on/off | ✅ | | | | |
| [`climate`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-Climate) | Thermostats, AC | ✅ | | | | |
| [`fan`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-Fan) | Speed, presets, oscillation | ✅ | | | | |
| [`media_player`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-MediaPlayer) | Playback, volume, now playing (compact or full), music search, queue | ✅ | | | ✅ | ✅ |
| [`speaker_group`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-SpeakerGroup) | Multiroom groups (e.g. Sonos) | ✅ | | | | |
| [`source_select`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-SourceSelect) | A media player's input / app list | ✅ | | | ✅ | ✅ |
| [`select`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-Select) | `input_select` / `select` dropdown | ✅ | | | | |
| [`camera`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-Camera) | Live stream or snapshots | ✅ | | | | |
| [`vacuum`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-Vacuum) | Robot vacuum, rooms, map | ✅ | | | | |
| [`clock_weather`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-ClockWeather) | Time, weather, forecast, calendar | ✅ | | | | |
| [`monitor`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-Monitor) | Read-only sensor values (vertical / scrolling for long text) | ✅ | | | | |
| [`plex`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-Plex) | On Deck / recently added browser, search | ✅ | | | | |
| [`xbox`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-Xbox) | Game being played, gamerscore, achievements (Xbox integration or TrueAchievements) | ✅ | | | | |
| [`picture_elements`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-PictureElements) | Interactive floor plan, mmWave radar | ✅ | | | | |
| [`title`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-Title) | Section header, optionally tappable | ✅ | ✅ | ✅ | ✅ | ✅ |
| [`row`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-Row) | Puts several cards side by side | ✅ | ✅ | ✅ | ✅ | ✅ |
| [`button_grid`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-ButtonGrid) | Grid of action buttons | ✅ | ✅ | ✅ | ✅ | ✅ |
| [`scene_grid`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-SceneGrid) | Grid of scenes, shortcuts and Activities | ✅ | ✅ | ✅ | ✅ | ✅ |
| [`tv_remote`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-Remote) | TV / box remote: D-pad, apps, now playing (classic or full) | ✅ | | | | ✅ |
| [`apple_tv_remote`](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-Remote) | Apple TV remote: classic, compact or full | | ✅ | | ✅ | |

## Related projects

| Project | What it does |
| :--- | :--- |
| [ha-astrion-custom-dashboard](https://github.com/dckiller51/ha-astrion-custom-dashboard) | Home Assistant **integration**: each remote as a device (battery, last seen, button events, Activities, Find my remote). |
| [Astrion Config Manager](https://github.com/dvbit/ha-astrion-config-manager) | Home Assistant **add-on** by [@dvbit](https://github.com/dvbit): manage the dashboards of all your remotes from HA, with history, entity checks, page copy and a live simulator. [Wiki page](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Astrion-Config-Manager). |
| [astrion-ir-sniffer](https://dckiller51.github.io/astrion-ir-sniffer/) | IR database picker, Harmony archive import, sends codes to your remote. |
| [astrion-ir-extender](https://github.com/dckiller51/astrion-ir-extender) | Wi-Fi IR extender (ESP board) for closed cabinets and other rooms. |

## Install

You need USB debugging once, for the first install. Updates after that are done from the remote's web page.

**Windows (recommended):** download the [installer](https://github.com/dckiller51/astrion-custom-dashboard/releases/tag/install), enable *Developer options* and *USB debugging* on the remote, then run `install.bat` and choose **Stable** or **Beta**.

**Any OS (adb):** download the APK from [Releases](https://github.com/dckiller51/astrion-custom-dashboard/releases), then:

```bash
adb install <downloaded>.apk
```

Then:

1. **Restart the remote** (permissions and the launcher only take effect after a reboot) and pick **Astrion Custom** as the home app.
2. **Allow storage access** when asked (for `/sdcard/astrion/`).
3. Open **`http://<remote-ip>:8080`** from a browser on the same network. Connect Home Assistant (URL + long-lived token), add your Harmony hub, IR devices, Apple TV or Android TV, then build your dashboard in the builder.

The app ships with **no credentials built in**: every install starts unconfigured. Step by step: [Getting Started](https://github.com/dckiller51/astrion-custom-dashboard/wiki) · [`dashboard.json` guide (EN)](https://github.com/dckiller51/astrion-custom-dashboard/wiki/GUIDE_dashboard_json_EN) · [FR](https://github.com/dckiller51/astrion-custom-dashboard/wiki/GUIDE_dashboard_json_FR) · [DE](https://github.com/dckiller51/astrion-custom-dashboard/wiki/ANLEITUNG_dashboard_json_DE) · [IT](https://github.com/dckiller51/astrion-custom-dashboard/wiki/GUIDA_dashboard_json_IT) · [ES](https://github.com/dckiller51/astrion-custom-dashboard/wiki/GUIA_dashboard_json_ES)

### Updates and beta

- **Updates**: the remote's web page checks [GitHub Releases](https://github.com/dckiller51/astrion-custom-dashboard/releases) and installs a new version in two taps. Android asks once to allow "install unknown apps" for Astrion Custom.
- **Beta**: every push to `dev` is published as the rolling pre-release `dev-latest`. It installs **alongside** the official app (`com.custom.astrion.debug`, configured separately), and its "Also the beta" switch on the web page offers beta updates.

### Uninstall

Run [`uninstall.bat`](https://github.com/dckiller51/astrion-custom-dashboard/releases/tag/uninstall) (Windows) or `adb uninstall com.custom.astrion`. The remote goes back to the stock HaRemote launcher.

## Translations

Three sets of files. Translating needs no programming: you translate text files, and the maintainer adds the new language code to the builder and the in-app language picker ([how to translate](https://github.com/dckiller51/astrion-custom-dashboard/wiki/Translations)):

| What | Files |
| :--- | :--- |
| App text (settings, labels…) | `src/main/res/values-xx/strings.xml` |
| Home Assistant state values (`heat_cool`, `clear-night`…) | `src/main/assets/ha_labels/xx.json` |
| Builder and the remote's web page | `src/main/assets/docs/i18n/xx.json` |
| Astrion TV Helper (the app for the Android TV) | `tvhelper/src/main/res/values-xx/strings.xml` |

English is the fallback for anything missing. Available today: English, French, German, Italian and Spanish.

## Building from source

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

No `secrets.properties` or other setup is needed. Every instance is configured after install from the `:8080` page.

<details>
<summary>Repository structure</summary>

```txt
src/main/
  assets/docs/              builder (/builder/) + the remote's web page, i18n/
  assets/ha_labels/         Home Assistant state translations
  java/com/custom/astrion/
    androidtv/              direct Android TV pairing and control (Android TV Remote v2)
    appletv/                direct Apple TV pairing and control
    cards/impl/             one card = one CardRenderer, registered in AstrionApp
    config/                 dashboard.json parsing, Activities runtime, settings
    extender/               network IR extenders
    ha/                     Home Assistant WebSocket client
    harmony/                Harmony Hub client
    input/                  physical key routing
    ui/                     dashboard, overlays, dock screen
    update/                 GitHub Releases updater
    web/                    local :8080 server
  res/values*/strings.xml   app text, per language
tvhelper/                   Astrion TV Helper, the small app for the Android TV (now playing, opening apps)
```

</details>

## Help and contributing

- Questions: [Discord](https://discord.gg/jqsSAAXSxB) (help forum, beta news). Bug reports and feature requests: [open an issue](https://github.com/dckiller51/astrion-custom-dashboard/issues).
- Questions about the Config Manager add-on go to [its own repository](https://github.com/dvbit/ha-astrion-config-manager/issues).
- Release notes: [CHANGELOG.md](./CHANGELOG.md).
- Translations are the easiest way to contribute: add the files for your language (see above) and open a pull request.

## Credits

- [**@baes-cloud**](https://github.com/baes-cloud/astrion-dashboard) for the original work this project is built on (MIT License, see [`THIRD_PARTY_LICENSES.md`](./THIRD_PARTY_LICENSES.md)).
- Everyone on Discord and GitHub who reports bugs, tests betas and sends ideas.

## License

**Astrion Custom Dashboard** is licensed under the [GNU GPLv3](./LICENSE). It started as a fork of [@baes-cloud](https://github.com/baes-cloud/astrion-dashboard)'s MIT-licensed work, whose notice is kept in [`THIRD_PARTY_LICENSES.md`](./THIRD_PARTY_LICENSES.md).

[![License: GPLv3](https://img.shields.io/badge/License-GPLv3-blue.svg?style=for-the-badge)](./LICENSE)

---

## ☕ Support

If **Astrion Custom Dashboard** is useful to you, you can buy me a coffee!

[![Ko-fi](https://img.shields.io/badge/Buy_me_a_coffee-Ko--fi-F16061?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/dckiller)
