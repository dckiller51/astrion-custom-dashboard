# Astrion Custom Dashboard — Complete `dashboard.json` guide

> Language: [Italiano](GUIDA_dashboard_json_IT.md) · **English** · [Français](GUIDE_dashboard_json_FR.md) · [Español](GUIA_dashboard_json_ES.md) · [Deutsch](ANLEITUNG_dashboard_json_DE.md)
>
> Reference: repository [`dckiller51/astrion-custom-dashboard`](https://github.com/dckiller51/astrion-custom-dashboard), branch `main`, commit `d73ec52` (app 1.1.4). Every option described here was checked against the Kotlin source (`config/DashboardLoader.kt`, `config/AppConfig.kt`, `cards/impl/*.kt`). Later versions may change.
>
> All examples use **fictitious entities** (`light.soggiorno`, `media_player.salotto`, …) shared across all language versions: replace them with your own.

## Contents

1. [What it is (and what it isn't)](#1-what-it-is-and-what-it-isnt)
2. [Where the file lives and how to upload it](#2-where-the-file-lives-and-how-to-upload-it)
3. [Root structure](#3-root-structure)
4. [Pages](#4-pages)
5. [Cards: common rules](#5-cards-common-rules)
6. [Cards without an editor form](#6-cards-without-an-editor-form)
7. [Cards with an editor form](#7-cards-with-an-editor-form)
8. [Physical keys (`hotkeys` / `longHotkeys`)](#8-physical-keys-hotkeys--longhotkeys)
9. [Harmony, local IR and Activities](#9-harmony-local-ir-and-activities)
10. [Theme](#10-theme)
11. [Complete example](#11-complete-example)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. What it is (and what it isn't)

Astrion Custom Dashboard is an **Android app** that replaces the launcher of the **Sanytron Astrion HA100** remote. It talks to Home Assistant over WebSocket.

- It is **not** a Lovelace dashboard and is **not** installed from HACS.
- Its "cards" are native app components, not Lovelace cards: the syntax is **JSON**, not YAML.
- Many option names mimic Mushroom cards, but only the options listed here are read by the app.

## 2. Where the file lives and how to upload it

| Item | Path on the remote |
|---|---|
| Configuration | `/sdcard/astrion/dashboard.json` |
| PNG icons | `/sdcard/astrion/icons/` |
| IR database (optional) | `/sdcard/astrion/ir-database/` |

Ways to edit it:

1. **Online editor** — <https://dckiller51.github.io/astrion-custom-dashboard/>: forms for pages, cards and keys; download the generated JSON.
2. **Local page** — `http://<remote-ip>:8080` (address shown in the Settings panel): upload/download `dashboard.json`, upload icons. Uploading reloads the dashboard **without a restart**.
3. **adb** — `adb push dashboard.json /sdcard/astrion/dashboard.json`, then reopen the app.

> ⚠️ If the JSON is invalid the app **does not crash**: it loads its built-in layout and shows `dashboard.json invalid (…) — using built-in defaults`. Always download a backup before uploading.

> ⚠️ In the online editor, reopening a card and saving it through the simple form **rewrites its options from scratch**: JSON-only fields (section 6) are lost. For those cards always use the "Options (raw JSON)" field.

## 3. Root structure

The root is a JSON object. An **array of cards** is also accepted: it becomes a single page named `Main`.

| Key | Type | Required | Default | Description |
|---|---|---|---|---|
| `pages` | array | **yes** (non-empty) | — | Pages, left to right |
| `startPage` | integer | no | `0` | Index (0-based) of the page shown at launch |
| `hotkeys` | array | no | `[]` | Physical keys, short press |
| `longHotkeys` | array | no | `[]` | Physical keys, long press (~500 ms) |
| `irDevices` | array | no | `[]` | Local IR devices (section 9) |
| `activities` | array | no | `[]` | Composed Activities (section 9) |
| `theme` | object | no | dark theme | Colours (section 10) |

```json
{
  "startPage": 0,
  "pages": [
    { "name": "Home", "cards": [] }
  ],
  "hotkeys": [],
  "longHotkeys": []
}
```

## 4. Pages

| Key | Type | Default | Description |
|---|---|---|---|
| `name` | string | `"Page"` | Name; used for navigation (case-insensitive) |
| `cards` | array | `[]` | Page cards, top to bottom |
| `hotkeys` / `longHotkeys` | array | `[]` | Keys active only on this page; they **override** global ones with the same `key` |
| `parent` | string | — | Parent page: the `parentKey` key returns to it |
| `parentKey` | string | `"BACK"` | Physical key that returns to `parent` |
| `linkedPage` | string | — | Page opened by swiping **up** on the page indicator |
| `hiddenUnlessActivity` | string | — | Activity id: the page dot shows only while that Activity is active (the page stays reachable) |
| `openWhenEntity` | string | — | HA entity: when it turns `on` the page opens by itself; when it leaves `on` it closes |

> ℹ️ The online editor can also write `openWhenState` / `closeWhenState`, but in the analysed commit `DashboardLoader.kt` **does not read them**: the open state is always `on`. For an entity with other states, create a template `binary_sensor` in HA.

Example — doorbell page that opens when the bell rings and closes with BACK:

```json
{
  "startPage": 0,
  "pages": [
    { "name": "Casa", "cards": [] },
    {
      "name": "Citofono",
      "parent": "Casa",
      "openWhenEntity": "binary_sensor.campanello",
      "cards": [
        { "type": "camera", "options": { "entity_id": "camera.ingresso" } }
      ]
    }
  ]
}
```

## 5. Cards: common rules

Every card has this shape:

```json
{ "type": "light", "options": { "entity_id": "light.soggiorno" } }
```

- `type` is **required**; `options` is optional.
- An unregistered `type` is not a parse error: the card is simply not drawn.
- `"pin": "bottom"` inside `options` (any card) **pins it to the bottom** of the page, outside the scroll area.
- **PNG icons**: absolute path, e.g. `/sdcard/astrion/icons/netflix.png`.
- **Colours**: `#RRGGBB` or `#AARRGGBB` (alpha first). Exception: `switch.on_color` only accepts `#AARRGGBB` (see 7.2).

Registered types (21) and online editor support:

| Type | Editor | Type | Editor |
|---|---|---|---|
| `title` | form (+ JSON-only fields) | `media_player` | form |
| `light` | form | `camera` | form |
| `switch` | form | `clock_weather` | form |
| `cover` | form | `vacuum` | form |
| `fan` | form | `plex` | form |
| `climate` | form | `speaker_group` | **JSON only** |
| `source_select` | form | `monitor` | **JSON only** |
| `select` | form | `picture_elements` | **JSON only** |
| `button_grid` | form | `row` | **JSON only** |
| `scene_grid` | form | `apple_tv_remote` | form |
| `tv_remote` | form | | |

## 6. Cards without an editor form

In the online editor pick the type (group *Advanced (raw options)*) and paste **only the `options` object** into *Options (raw JSON)*. The examples below show the whole card: copy what is inside `"options"`.

### 6.1 `speaker_group` — speaker group (Sonos and similar)

One row per speaker with: tick = group member, draggable volume bar, mute, vol−/vol+. The first row is the master.

| Option | Type | Req. | Description |
|---|---|---|---|
| `master` | string | **yes** | Group coordinator `media_player`. Without it the card is not shown |
| `name` | string | no | Name shown for the master |
| `speakers` | array | no | List of `{ "entity_id", "name" }` of groupable speakers |

Behaviour:

- Ticking → `media_player.join` on the master with `group_members: [speaker]`, **immediately**.
- Unticking → `media_player.unjoin` on the speaker.
- "In group" state is read from the **speaker's** `group_members` attribute (it must contain the master).
- Volume: `media_player.volume_set`, `volume_up`, `volume_down`, `volume_mute`.

```json
{
  "type": "speaker_group",
  "options": {
    "master": "media_player.salotto",
    "name": "Salotto",
    "speakers": [
      { "entity_id": "media_player.cucina", "name": "Cucina" },
      { "entity_id": "media_player.camera", "name": "Camera" },
      { "entity_id": "media_player.bagno", "name": "Bagno" }
    ]
  }
}
```

### 6.2 `monitor` — sensor value list

Read-only list: name, current value, `unit_of_measurement`. No history graph. `unknown` / `unavailable` are shown as `—`.

| Option | Type | Req. | Description |
|---|---|---|---|
| `title` | string | no | Header |
| `entities` | array | yes | `{ "entity_id", "name" }`; `name` optional (default: friendly name) |

```json
{
  "type": "monitor",
  "options": {
    "title": "Ambiente",
    "entities": [
      { "entity_id": "sensor.temperatura_soggiorno", "name": "Temperatura" },
      { "entity_id": "sensor.umidita_soggiorno", "name": "Umidità" },
      { "entity_id": "sensor.co2_soggiorno" },
      { "entity_id": "sensor.potenza_casa", "name": "Consumo" }
    ]
  }
}
```

### 6.3 `row` — side-by-side cards

Horizontal container: child cards get **equal width**. Each child is a full card (`type` + `options`). Any card is allowed, including another `row`.

| Option | Type | Req. | Description |
|---|---|---|---|
| `cards` | array | yes | Child cards |

```json
{
  "type": "row",
  "options": {
    "cards": [
      { "type": "cover", "options": { "entity_id": "cover.tapparella_sx", "name": "Sinistra", "layout": "vertical" } },
      { "type": "cover", "options": { "entity_id": "cover.tapparella_dx", "name": "Destra", "layout": "vertical" } }
    ]
  }
}
```

> 💡 Inside a `row` prefer `"layout": "vertical"` (light/cover/select): horizontal space is limited.

### 6.4 `picture_elements` — interactive floor plan

Background image with icons placed by **percentage**. It can also show mmWave radar targets and the robot vacuum.

| Option | Type | Default | Description |
|---|---|---|---|
| `image` | string | `/sdcard/astrion/floorplan.png` | PNG/JPG path on the remote |
| `aspect` | number | `1.3` | W/H ratio used **until the image is loaded** (then the real one wins) |
| `elements` | array | `[]` | Icons (table below) |
| `radar` | object | — | Radar overlay (6.4.1) |
| `vacuum` | object | — | Vacuum overlay (6.4.2) |

Fields of each element:

| Field | Type | Default | Description |
|---|---|---|---|
| `left` / `top` | number | `50` | Icon centre, % of width / height |
| `entity_id` | string | — | Tap → **toggle** the entity; amber icon when `on` |
| `service` | string | — | Used only if `entity_id` is missing: `domain.service` |
| `targets` | array | — | Entities `service` is applied to, one call per entity |
| `icon` | string | bulb | Only alternative value: `"power"` |

```json
{
  "type": "picture_elements",
  "options": {
    "image": "/sdcard/astrion/planimetria.png",
    "aspect": 1.5,
    "elements": [
      { "entity_id": "light.soggiorno", "left": 25, "top": 35 },
      { "entity_id": "light.cucina", "left": 70, "top": 30 },
      { "entity_id": "switch.lampada_studio", "left": 80, "top": 75 },
      { "service": "light.turn_off", "targets": ["light.soggiorno", "light.cucina"], "icon": "power", "left": 95, "top": 6 }
    ]
  }
}
```

> 💡 Finding coordinates: open the image in an image editor, read X/Y in pixels and compute `left = X / width × 100`, `top = Y / height × 100`.

#### 6.4.1 `radar` overlay (mmWave, e.g. LD2450)

Draws up to `targets` numbered dots. Reads entities `<prefix>_<n>_x` and `<prefix>_<n>_y` with n = 1…targets. If the entity is missing or not numeric, the dot is not shown.

| Field | Default | Description |
|---|---|---|
| `prefix` | — (**req.**) | Sensor prefix, e.g. `sensor.radar_soggiorno_target` |
| `targets` | `3` | Number of targets |
| `origin_left` / `origin_top` | `50` / `10` | Sensor position on the image (%) |
| `scale_x` / `scale_y` | `8` / `8` | % of image per **unit** of the sensor value |
| `scale_x_right` | = `scale_x` | X scale for values ≥ 0 (right side) |
| `top_offset_left` | `0` | Extra vertical offset (%) for the left side |
| `rotation` | `0` | Rotation in degrees |
| `flip_x` / `flip_y` | `false` | Mirror the axes |
| `blend` | `"overlay"` | `none`, `multiply`, `screen`, `softlight`, `hardlight`, `difference`, `overlay` |

Applied formula (to understand calibration): `left = origin_left + rotated_x × x_scale`, `top = origin_top + rotated_y × scale_y`. Result clamped to 0–100 %.

```json
{
  "type": "picture_elements",
  "options": {
    "image": "/sdcard/astrion/planimetria.png",
    "elements": [],
    "radar": {
      "prefix": "sensor.radar_soggiorno_target",
      "targets": 3,
      "origin_left": 50,
      "origin_top": 5,
      "scale_x": 10,
      "scale_y": 10,
      "rotation": 0,
      "flip_x": false,
      "flip_y": false,
      "blend": "overlay"
    }
  }
}
```

> 💡 Calibration: stand at a known spot, see where the dot appears, fix `origin_*` first, then `scale_*`, finally `rotation`/`flip_*`. The unit of `scale_*` depends on how your firmware exposes x/y (mm, cm or m).

#### 6.4.2 `vacuum` overlay

Robot icon placed **per room** (HA exposes no robot X/Y, only the current room). Tap → popup with all the `vacuum` card controls (7.12). Accepts the same options as `vacuum` plus:

| Field | Description |
|---|---|
| `entity_id` | `vacuum.*` entity (**req.**) |
| `room_entity` | Sensor holding the current room name |
| `room_positions` | Map `"RoomName": [left, top]` in % |
| `dock_position` | `[left, top]` of the dock |

```json
{
  "type": "picture_elements",
  "options": {
    "image": "/sdcard/astrion/planimetria.png",
    "elements": [],
    "vacuum": {
      "entity_id": "vacuum.robot",
      "name": "Robot",
      "map_image": "image.robot_mappa",
      "rooms": [
        { "name": "Cucina", "id": 16 },
        { "name": "Soggiorno", "id": 17 }
      ],
      "room_entity": "sensor.robot_stanza_corrente",
      "room_positions": { "Cucina": [70, 30], "Soggiorno": [25, 40] },
      "dock_position": [5, 60]
    }
  }
}
```

### 6.5 JSON-only fields of the `title` card

The `title` card has a form, but **tappable** title and subtitle can only be set in JSON. It uses the same action vocabulary as `scene_grid` (7.10), prefixed with `title_` or `subtitle_`:

| Field | Action |
|---|---|
| `title_entity_id` | Activate scene/script |
| `title_page` | Open page |
| `title_activityId` (+ `title_hub`) | Start Harmony Activity |
| `title_harmonyDevice` + `title_harmonyCommand` (+ `title_hub`) | IR command via Harmony |
| `title_irDevice` + `title_irCommand` | Local IR command |
| `title_activity` | Start composed Activity |

Example — a "See all →" subtitle that opens the Lights page:

```json
{
  "type": "title",
  "options": {
    "title": "Soggiorno",
    "subtitle": "Vedi tutte le luci →",
    "subtitle_page": "Luci",
    "icon": "/sdcard/astrion/icons/sofa.png",
    "divider": true,
    "color": "#7FB3C4"
  }
}
```

## 7. Cards with an editor form

These can also be written by hand. All options read by the code are listed.

### 7.1 `title`

| Option | Default | Description |
|---|---|---|
| `title` / `subtitle` | — | Texts |
| `alignment` | `"start"` | `start`, `center`, `end`, `justify` |
| `icon` | — | PNG before the title (forces left alignment of the title row) |
| `divider` | `false` | Line filling the row after the title |
| `color` | theme | Title and line colour |

### 7.2 `switch`

| Option | Description |
|---|---|
| `entity_id` | Toggleable entity (`switch.*`, `input_boolean.*`, …) |
| `name` | Label |
| `icon` | `heater`/`heat`, `fan`, `bulb`/`light`; anything else → power |
| `on_color` | Colour when on. **`#AARRGGBB` only** (a `#RRGGBB` value ends up transparent) |

```json
{ "type": "switch", "options": { "entity_id": "switch.stufa", "name": "Stufa", "icon": "heater", "on_color": "#B3902828" } }
```

### 7.3 `light`

Tap = toggle; long press = detail popup.

| Option | Default | Description |
|---|---|---|
| `entity_id`, `name` | — | |
| `layout` | `default` | `default`, `horizontal`, `vertical` |
| `use_light_color` | `false` | Icon tinted with the light's RGB colour |
| `show_brightness` | `true` | "N%" state instead of "On" |
| `show_brightness_control` | see note | Brightness slider |
| `show_color_temp_control` | `false` | Colour temperature slider |
| `show_color_control` | `false` | Colour swatches |
| `collapsible_controls` | `false` | Hides controls while the light is off |

> If **none** of the three `show_*_control` is present, only the brightness slider shows. As soon as you set one, only those set to `true` show. With several controls, an arrow button cycles through them.

```json
{ "type": "light", "options": { "entity_id": "light.soggiorno", "name": "Soggiorno", "layout": "horizontal", "use_light_color": true, "show_brightness_control": true, "show_color_control": true } }
```

### 7.4 `cover`

Long press = popup with 25/50/75 % presets.

| Option | Default | Description |
|---|---|---|
| `entity_id`, `name`, `layout` | | as `light` |
| `show_buttons_control` | see note | Open/stop/close |
| `show_position_control` | `false` | Position slider |
| `show_tilt_position_control` | `false` | Tilt slider |

> Same logic as `light`: with no flag the buttons show. Sliders only show if the entity exposes the matching attribute.

### 7.5 `fan`

| Option | Default | Description |
|---|---|---|
| `entity_id`, `name` | | |
| `style` | `auto` | `auto`, `simple`, `step`, `full` |
| `preset_modes` | from entity | Array, order and filter of presets |
| `step` | entity `percentage_step`, then `20` | % step |
| `show_captions` | `true` | Captions above chip rows |

### 7.6 `climate`

| Option | Default | Description |
|---|---|---|
| `entity_id`, `name` | | |
| `step` | `1.0` | Setpoint step. **Ignored** if the entity exposes `target_temp_step` |
| `hvac_modes` / `fan_modes` / `swing_modes` | from entity | Arrays: chip order and filter (`off` is always excluded) |
| `hvac_mode_style` | `icons` | `icons` or `label` |
| `fan_mode_style` / `swing_mode_style` | `label` | `icons` or `label` |
| `show_captions` | `true` | Captions |

```json
{ "type": "climate", "options": { "entity_id": "climate.soggiorno", "name": "Clima", "step": 0.5, "hvac_modes": ["heat", "cool", "auto"], "fan_mode_style": "icons" } }
```

### 7.7 `select` and `source_select`

- `select`: for `input_select.*` / `select.*`. Options: `entity_id`, `name`, `icon_color`, `layout`.
- `source_select`: source of a `media_player` (`source_list`). Options: `entity_id`, `name`.

### 7.8 `media_player`

Compact: tap = play/pause; long press = details.

| Option | Default | Description |
|---|---|---|
| `entity_id`, `name` | | |
| `variant` | compact | `"full"` for the large version |
| `use_media_info` | `true` | Title/artist instead of name |
| `show_volume_level` | `false` | Appends "⸱ N%" |
| `media_controls` | `previous,play_pause,next` | Comma list: `on_off`, `shuffle`, `previous`, `play_pause`, `next`, `repeat` |
| `volume_controls` | `mute,buttons` | `mute`, `buttons`, `set` (slider) |
| `top_buttons` | — | `full` only: `{ "name", "service", "entity_id", "data" }` |

Buttons not supported by the entity (`supported_features`) are hidden.

### 7.9 `button_grid`

| Option | Default | Description |
|---|---|---|
| `columns` | `3` | Columns |
| `buttons` | — | `{ "name", "icon", "service", "entity_id", "data" }` |

```json
{
  "type": "button_grid",
  "options": {
    "columns": 2,
    "buttons": [
      { "name": "Netflix", "icon": "/sdcard/astrion/icons/netflix.png", "service": "media_player.play_media", "entity_id": "media_player.tv", "data": { "media_content_type": "app", "media_content_id": "com.netflix.ninja" } },
      { "name": "Buonanotte", "service": "script.buonanotte" }
    ]
  }
}
```

### 7.10 `scene_grid`

| Option | Default | Description |
|---|---|---|
| `columns` | `2` | Columns |
| `layout` | grid | `"row"` = scrollable row |
| `show_labels` | `true` | Name under the icon |
| `icon_fill` | `false` | Icon fills the tile |
| `tile_height` | `74` (`120` with `icon_fill`) | Tile height (dp) |
| `scenes` | — | Tiles (below) |

Fields of each tile: `name`, `icon`, `color`, and **one action**: `entity_id` (scene/script), `page`, `activityId` (+`hub`), `harmonyDevice`+`harmonyCommand` (+`hub`), `irDevice`+`irCommand`, `activity`. Optional `track` + `room` (+`devices`) to track it as an Activity. With `activity`, the `page` field is ignored (set the page on the Activity).

### 7.11 `camera`

| Option | Default | Description |
|---|---|---|
| `entity_id` | — (**req.**) | `camera.*` |
| `name` | friendly name | |
| `mode` | `stream` | `stream` (MJPEG) or `snapshot` |
| `snapshot_interval` | `2` | Seconds between frames |
| `aspect` | `1.777` | W/H ratio |
| `fit` | `cover` | `cover` or `contain` |

Tap = full screen with zoom (pinch 1×–8×).

### 7.12 `vacuum`

| Option | Description |
|---|---|
| `entity_id`, `name` | |
| `map_image` | Map `image.*` entity |
| `map_rotation` | Map rotation (degrees) |
| `map_height` | Map height (dp) |
| `rooms` | `{ "name", "id" }` — map segment id |
| `room_clean_action` | Default Roborock/Xiaomi (`vacuum.send_command app_segment_clean`); otherwise `{ "domain", "service", "parameter" }` |

### 7.13 `clock_weather`

Options: `entity_id` (`weather.*`), `time_format` (`12` default or `24`), `forecast_rows` (default `4`), `calendar_entity`.

### 7.14 `tv_remote`

| Option | Description |
|---|---|
| `name` | Default `TV` |
| `remote_entity` | `remote.*` (**req.**) — sends `remote.send_command` |
| `mute_entity` | Alternative entity for mute |
| `media_entity` | `media_player` used to launch apps |
| `commands` | Remaps commands: `up`, `down`, `left`, `right`, `center`, `back`, `home`, `menu`, `power` |
| `apps` | `{ "name", "app" }` or `{ "name", "service", "entity_id", "data" }` |

### 7.15 `apple_tv_remote`

Commands are sent **directly to the Harmony hub**. Options: `deviceId` (Harmony device id), `hub` (optional).

### 7.16 `plex`

Options: `host`, `token`, `media_entity`, `play_entity`, `play_content_type` (`video`/`url`), `source` (default `Plex`), `show_on_deck`, `show_recently_added_movies`, `show_recently_added_shows` (default `true`), `items_per_row` (default `12`).

> ⚠️ The Plex token is stored in clear text in the file: do not share `dashboard.json`.

## 8. Physical keys (`hotkeys` / `longHotkeys`)

`key` names available on the HA100:
`UP DOWN LEFT RIGHT CENTER`, `PAGE_UP PAGE_DOWN`, `VOLUME_UP VOLUME_DOWN MUTE`, `BACK HOME POWER VOICE`, `LIGHT CURTAIN SCENE AC`, `CUSTOM_1…CUSTOM_4`.

Fields (note: here the entity is called **`entityId`**, camelCase):

| Field | Description |
|---|---|
| `key` | Key (**req.**) |
| `openOverlay` | `settings` or `activities` |
| `openCurrentActivityRoom` | Room name: opens the page of the Activity active there |
| `page` | Opens the page |
| `harmonyDevice` + `harmonyCommand` / `harmonyActivity` (+ `hub`) | Harmony (`harmonyActivity: "-1"` = power off) |
| `irDevice` + `irCommand` | Local IR |
| `service` + `entityId` + `data` | HA service |
| `track` + `room` (+ `devices`) | Track as an Activity |

Use **one action per key**. Priority: `openOverlay` → `openCurrentActivityRoom` → `page` → … → `service`.

```json
{ "key": "CUSTOM_1", "service": "media_player.play_media", "entityId": "media_player.tv", "data": { "media_content_type": "app", "media_content_id": "com.netflix.ninja" } }
```

> 💡 Arrows and volume auto-repeat while held: do not give them `longHotkeys`.

## 9. Harmony, local IR and Activities

**`irDevices`** — IR devices driven by the remote's own blaster:

```json
{
  "startPage": 0,
  "pages": [ { "name": "TV", "cards": [] } ],
  "irDevices": [
    {
      "id": "tv_salotto",
      "name": "TV Salotto",
      "commands": {
        "power": { "freq": 38000, "pattern": [9000, 4500, 560, 560] }
      }
    }
  ]
}
```

Instead of `commands`: `category` + `brand` + `model` (database in `/sdcard/astrion/ir-database/`). `target` field: `"local"` (default) or `{ "extender": "<id>" }`.

**`activities`** — multi-device scenarios:

| Field | Description |
|---|---|
| `id`, `name`, `room` | `id` and `room` **required** |
| `icon`, `page` | Icon; page opened on start |
| `devices` | **Required, non-empty**: `deviceId`, `source` (`ir`/`harmony`/`ha`), `hub`, `powerOnCommand`, `powerOffCommand`, `inputCommand`, `powerOnFirst` (default `true`), `powerOffOnExit` (default `true`), `delayAfterMs` |
| `volumeDeviceId`, `volumeUpCommand`, `volumeDownCommand`, `muteCommand` | Volume control |

Harmony configuration (hub IP/ID) is done on the `:8080` page, not in the JSON.

## 10. Theme

All optional (defaults in brackets): `background` (#0E2229), `cardSurface` (#1B343D), `insetSurface` (#152B33), `controlBackground` (#2C4C58), `primaryText` (#E6F0F1), `mutedText` (#93AFB6), `iconTint` (#CBDCE0), `accent` (#6EA8FE), `accentSecondary` (#4C6EF5), `amber` (#FFC24B), `danger` (#E06767), `success` (#4CAF50).

## 11. Complete example

Five pages: Luci (lights), Casa (floor plan + sensors), Musica (speaker group), Clima (climate), Citofono (auto-open doorbell).

```json
{
  "startPage": 1,
  "theme": { "background": "#0E2229", "accent": "#6EA8FE" },
  "pages": [
    {
      "name": "Luci",
      "cards": [
        { "type": "title", "options": { "title": "Luci", "subtitle": "Soggiorno e cucina", "divider": true, "subtitle_page": "Casa" } },
        { "type": "light", "options": { "entity_id": "light.soggiorno", "name": "Soggiorno", "show_brightness_control": true, "show_color_temp_control": true } },
        { "type": "row", "options": { "cards": [
          { "type": "switch", "options": { "entity_id": "switch.stufa", "name": "Stufa", "icon": "heater", "on_color": "#B3902828" } },
          { "type": "switch", "options": { "entity_id": "switch.ventilatore", "name": "Ventilatore", "icon": "fan" } }
        ] } },
        { "type": "scene_grid", "options": { "layout": "row", "pin": "bottom", "scenes": [
          { "entity_id": "scene.cena", "name": "Cena", "color": "#66FFB300" },
          { "entity_id": "script.tutto_spento", "name": "Spegni", "color": "#66000000" }
        ] } }
      ]
    },
    {
      "name": "Casa",
      "cards": [
        { "type": "clock_weather", "options": { "entity_id": "weather.casa", "time_format": 24, "forecast_rows": 3 } },
        { "type": "picture_elements", "options": {
          "image": "/sdcard/astrion/planimetria.png",
          "aspect": 1.3,
          "elements": [
            { "entity_id": "light.soggiorno", "left": 30, "top": 40 },
            { "entity_id": "light.cucina", "left": 70, "top": 55 },
            { "service": "light.turn_off", "targets": ["light.soggiorno", "light.cucina"], "icon": "power", "left": 92, "top": 8 }
          ]
        } },
        { "type": "monitor", "options": { "title": "Sensori", "entities": [
          { "entity_id": "sensor.temperatura_soggiorno", "name": "Soggiorno" },
          { "entity_id": "sensor.umidita_soggiorno", "name": "Umidità" },
          { "entity_id": "sensor.potenza_casa", "name": "Consumo" }
        ] } }
      ]
    },
    {
      "name": "Musica",
      "cards": [
        { "type": "media_player", "options": { "entity_id": "media_player.salotto", "variant": "full", "media_controls": "shuffle,previous,play_pause,next,repeat", "volume_controls": "mute,set" } },
        { "type": "speaker_group", "options": { "master": "media_player.salotto", "name": "Salotto", "speakers": [
          { "entity_id": "media_player.cucina", "name": "Cucina" },
          { "entity_id": "media_player.camera", "name": "Camera" }
        ] } },
        { "type": "source_select", "options": { "entity_id": "media_player.salotto", "name": "Sorgente" } }
      ]
    },
    {
      "name": "Clima",
      "cards": [
        { "type": "climate", "options": { "entity_id": "climate.soggiorno", "name": "Clima", "step": 0.5 } },
        { "type": "cover", "options": { "entity_id": "cover.tapparella_soggiorno", "name": "Tapparella", "show_buttons_control": true, "show_position_control": true } }
      ]
    },
    {
      "name": "Citofono",
      "parent": "Casa",
      "openWhenEntity": "binary_sensor.campanello",
      "cards": [
        { "type": "camera", "options": { "entity_id": "camera.ingresso", "mode": "stream", "fit": "cover" } }
      ]
    }
  ],
  "hotkeys": [
    { "key": "LIGHT", "page": "Luci" },
    { "key": "CURTAIN", "page": "Casa" },
    { "key": "SCENE", "page": "Musica" },
    { "key": "AC", "page": "Clima" },
    { "key": "VOLUME_UP", "service": "media_player.volume_up", "entityId": "media_player.salotto" },
    { "key": "VOLUME_DOWN", "service": "media_player.volume_down", "entityId": "media_player.salotto" },
    { "key": "VOICE", "openOverlay": "settings" }
  ],
  "longHotkeys": [
    { "key": "LIGHT", "service": "script.turn_on", "entityId": "script.tutto_spento" }
  ]
}
```

## 12. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| The sample dashboard appears | Invalid JSON or `pages` missing/empty | Validate the JSON (e.g. `python -m json.tool dashboard.json`) |
| A card does not appear | Wrong `type`, or required option missing (`master`, `remote_entity`, `entity_id`) | Check type name and options |
| Fields lost after editing in the editor | Saved through the simple form | Use "Options (raw JSON)" |
| `switch` on but colour invisible | 6-digit `on_color` | Use `#AARRGGBB` |
| PNG icons missing | Relative path or file not uploaded | Absolute path `/sdcard/astrion/icons/…` |
| Service hotkey does nothing | `entity_id` instead of `entityId` | Use `entityId` in keys |
| No radar dots | `<prefix>_<n>_x/_y` entities missing or not numeric | Check names in Developer tools → States |
| Auto-open page never opens | The entity never turns `on` | Use a binary entity or a template `binary_sensor` |
| Speaker always "not grouped" | The integration doesn't expose `group_members` on the speaker | Check the attribute in HA |
