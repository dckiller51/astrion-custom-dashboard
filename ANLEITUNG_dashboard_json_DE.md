# Astrion Custom Dashboard — Vollständige Anleitung zu `dashboard.json`

> Sprache: [Italiano](GUIDA_dashboard_json_IT.md) · [English](GUIDE_dashboard_json_EN.md) · [Français](GUIDE_dashboard_json_FR.md) · [Español](GUIA_dashboard_json_ES.md) · **Deutsch**
>
> Referenz: Repository [`dckiller51/astrion-custom-dashboard`](https://github.com/dckiller51/astrion-custom-dashboard), Branch `main`, Commit `d73ec52` (App 1.1.4). Jede beschriebene Option wurde im Kotlin-Quellcode geprüft (`config/DashboardLoader.kt`, `config/AppConfig.kt`, `cards/impl/*.kt`). Spätere Versionen können abweichen.
>
> Alle Beispiele verwenden **fiktive Entitäten** (`light.soggiorno`, `media_player.salotto`, …), identisch in allen Sprachversionen: ersetze sie durch deine eigenen.

## Inhalt

1. [Was es ist (und was nicht)](#1-was-es-ist-und-was-nicht)
2. [Speicherort und Hochladen](#2-speicherort-und-hochladen)
3. [Wurzelstruktur](#3-wurzelstruktur)
4. [Seiten](#4-seiten)
5. [Karten: allgemeine Regeln](#5-karten-allgemeine-regeln)
6. [Karten ohne Editor-Formular](#6-karten-ohne-editor-formular)
7. [Karten mit Editor-Formular](#7-karten-mit-editor-formular)
8. [Physische Tasten (`hotkeys` / `longHotkeys`)](#8-physische-tasten-hotkeys--longhotkeys)
9. [Harmony, lokales IR und Aktivitäten](#9-harmony-lokales-ir-und-aktivitäten)
10. [Theme](#10-theme)
11. [Vollständiges Beispiel](#11-vollständiges-beispiel)
12. [Fehlerbehebung](#12-fehlerbehebung)

---

## 1. Was es ist (und was nicht)

Astrion Custom Dashboard ist eine **Android-App**, die den Launcher der Fernbedienung **Sanytron Astrion HA100** ersetzt. Sie kommuniziert per WebSocket mit Home Assistant.

- Es ist **kein** Lovelace-Dashboard und wird **nicht** über HACS installiert.
- Die „Karten“ sind native App-Komponenten, keine Lovelace-Karten: die Syntax ist **JSON**, nicht YAML.
- Viele Optionsnamen ähneln den Mushroom-Karten, die App liest aber nur die hier aufgeführten Optionen.

## 2. Speicherort und Hochladen

| Element | Pfad auf der Fernbedienung |
|---|---|
| Konfiguration | `/sdcard/astrion/dashboard.json` |
| PNG-Icons | `/sdcard/astrion/icons/` |
| IR-Datenbank (optional) | `/sdcard/astrion/ir-database/` |

Bearbeitungswege:

1. **Online-Editor** — <https://dckiller51.github.io/astrion-custom-dashboard/>: Formulare für Seiten, Karten und Tasten; erzeugtes JSON herunterladen.
2. **Lokale Seite** — `http://<ip-der-fernbedienung>:8080` (Adresse im Einstellungsbereich sichtbar): `dashboard.json` hoch-/herunterladen, Icons hochladen. Das Hochladen lädt das Dashboard **ohne Neustart** neu.
3. **adb** — `adb push dashboard.json /sdcard/astrion/dashboard.json`, dann die App erneut öffnen.

> ⚠️ Ist das JSON ungültig, **stürzt die App nicht ab**: sie lädt ihr eingebautes Layout und zeigt `dashboard.json invalid (…) — using built-in defaults`. Lade vor jedem Hochladen eine Sicherung herunter.

> ⚠️ Wird im Online-Editor eine Karte erneut geöffnet und über das einfache Formular gespeichert, werden ihre Optionen **komplett neu geschrieben**: reine JSON-Felder (Abschnitt 6) gehen verloren. Für diese Karten immer das Feld „Options (raw JSON)“ verwenden.

## 3. Wurzelstruktur

Die Wurzel ist ein JSON-Objekt. Ein **Array von Karten** wird ebenfalls akzeptiert: es wird zu einer einzigen Seite namens `Main`.

| Schlüssel | Typ | Pflicht | Standard | Beschreibung |
|---|---|---|---|---|
| `pages` | Array | **ja** (nicht leer) | — | Seiten, von links nach rechts |
| `startPage` | Ganzzahl | nein | `0` | Index (ab 0) der Startseite |
| `hotkeys` | Array | nein | `[]` | Physische Tasten, kurzer Druck |
| `longHotkeys` | Array | nein | `[]` | Physische Tasten, langer Druck (~500 ms) |
| `irDevices` | Array | nein | `[]` | Lokale IR-Geräte (Abschnitt 9) |
| `activities` | Array | nein | `[]` | Zusammengesetzte Aktivitäten (Abschnitt 9) |
| `theme` | Objekt | nein | dunkles Theme | Farben (Abschnitt 10) |

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

## 4. Seiten

| Schlüssel | Typ | Standard | Beschreibung |
|---|---|---|---|
| `name` | String | `"Page"` | Name; für die Navigation verwendet (Groß-/Kleinschreibung egal) |
| `cards` | Array | `[]` | Karten der Seite, von oben nach unten |
| `hotkeys` / `longHotkeys` | Array | `[]` | Nur auf dieser Seite aktive Tasten; sie **überschreiben** globale mit gleichem `key` |
| `parent` | String | — | Übergeordnete Seite: die Taste `parentKey` kehrt dorthin zurück |
| `parentKey` | String | `"BACK"` | Physische Taste zurück zu `parent` |
| `linkedPage` | String | — | Seite, die durch Wischen **nach oben** auf der Seitenanzeige geöffnet wird |
| `hiddenUnlessActivity` | String | — | Aktivitäts-Id: der Seitenpunkt erscheint nur, solange diese Aktivität aktiv ist (die Seite bleibt erreichbar) |
| `openWhenEntity` | String | — | HA-Entität: wechselt sie auf `on`, öffnet sich die Seite selbst; verlässt sie `on`, schließt sie sich |

> ℹ️ Der Online-Editor kann auch `openWhenState` / `closeWhenState` schreiben, im analysierten Commit **liest** `DashboardLoader.kt` sie aber **nicht**: der Öffnungszustand ist immer `on`. Für eine Entität mit anderen Zuständen einen Template-`binary_sensor` in HA anlegen.

Beispiel — Türsprechstellen-Seite, die beim Klingeln öffnet und mit BACK schließt:

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

## 5. Karten: allgemeine Regeln

Jede Karte hat diese Form:

```json
{ "type": "light", "options": { "entity_id": "light.soggiorno" } }
```

- `type` ist **Pflicht**; `options` ist optional.
- Ein nicht registrierter `type` ist kein Parse-Fehler: die Karte wird einfach nicht gezeichnet.
- `"pin": "bottom"` in `options` (jede Karte) **heftet sie unten** an die Seite, außerhalb des Scrollbereichs.
- **PNG-Icons**: absoluter Pfad, z. B. `/sdcard/astrion/icons/netflix.png`.
- **Farben**: `#RRGGBB` oder `#AARRGGBB` (Alpha zuerst). Ausnahme: `switch.on_color` akzeptiert nur `#AARRGGBB` (siehe 7.2).

Registrierte Typen (21) und Unterstützung im Online-Editor:

| Typ | Editor | Typ | Editor |
|---|---|---|---|
| `title` | Formular (+ reine JSON-Felder) | `media_player` | Formular |
| `light` | Formular | `camera` | Formular |
| `switch` | Formular | `clock_weather` | Formular |
| `cover` | Formular | `vacuum` | Formular |
| `fan` | Formular | `plex` | Formular |
| `climate` | Formular | `speaker_group` | **nur JSON** |
| `source_select` | Formular | `monitor` | **nur JSON** |
| `select` | Formular | `picture_elements` | **nur JSON** |
| `button_grid` | Formular | `row` | **nur JSON** |
| `scene_grid` | Formular | `apple_tv_remote` | Formular |
| `tv_remote` | Formular | | |

## 6. Karten ohne Editor-Formular

Im Online-Editor den Typ wählen (Gruppe *Advanced (raw options)*) und **nur das `options`-Objekt** in *Options (raw JSON)* einfügen. Die Beispiele zeigen die ganze Karte: kopiere den Inhalt von `"options"`.

### 6.1 `speaker_group` — Lautsprechergruppe (Sonos u. ä.)

Eine Zeile pro Lautsprecher mit: Haken = Gruppenmitglied, ziehbarer Lautstärkebalken, Stumm, Lautst.−/+. Die erste Zeile ist der Master.

| Option | Typ | Pflicht | Beschreibung |
|---|---|---|---|
| `master` | String | **ja** | Koordinierender `media_player`. Ohne ihn erscheint die Karte nicht |
| `name` | String | nein | Angezeigter Name des Masters |
| `speakers` | Array | nein | Liste `{ "entity_id", "name" }` der gruppierbaren Lautsprecher |

Verhalten:

- Anhaken → `media_player.join` auf dem Master mit `group_members: [Lautsprecher]`, **sofort**.
- Haken entfernen → `media_player.unjoin` auf dem Lautsprecher.
- Der Zustand „in der Gruppe“ wird aus dem Attribut `group_members` **des Lautsprechers** gelesen (es muss den Master enthalten).
- Lautstärke: `media_player.volume_set`, `volume_up`, `volume_down`, `volume_mute`.

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

### 6.2 `monitor` — Liste von Sensorwerten

Nur-Lese-Liste: Name, aktueller Wert, `unit_of_measurement`. Kein Verlaufsdiagramm. `unknown` / `unavailable` werden als `—` angezeigt.

| Option | Typ | Pflicht | Beschreibung |
|---|---|---|---|
| `title` | String | nein | Überschrift |
| `entities` | Array | ja | `{ "entity_id", "name" }`; `name` optional (Standard: friendly name) |

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

### 6.3 `row` — Karten nebeneinander

Horizontaler Container: Kindkarten erhalten **gleiche Breite**. Jedes Kind ist eine vollständige Karte (`type` + `options`). Jede Karte ist erlaubt, auch eine weitere `row`.

| Option | Typ | Pflicht | Beschreibung |
|---|---|---|---|
| `cards` | Array | ja | Kindkarten |

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

> 💡 In einer `row` besser `"layout": "vertical"` verwenden (light/cover/select): der horizontale Platz ist knapp.

### 6.4 `picture_elements` — interaktiver Grundriss

Hintergrundbild mit Icons, die in **Prozent** positioniert werden. Kann auch Ziele eines mmWave-Radars und den Saugroboter anzeigen.

| Option | Typ | Standard | Beschreibung |
|---|---|---|---|
| `image` | String | `/sdcard/astrion/floorplan.png` | PNG/JPG-Pfad auf der Fernbedienung |
| `aspect` | Zahl | `1.3` | B/H-Verhältnis **bis das Bild geladen ist** (danach gilt das echte) |
| `elements` | Array | `[]` | Icons (Tabelle unten) |
| `radar` | Objekt | — | Radar-Ebene (6.4.1) |
| `vacuum` | Objekt | — | Saugroboter-Ebene (6.4.2) |

Felder jedes Elements:

| Feld | Typ | Standard | Beschreibung |
|---|---|---|---|
| `left` / `top` | Zahl | `50` | Icon-Mitte, % der Breite / Höhe |
| `entity_id` | String | — | Tippen → **Toggle** der Entität; bernsteinfarbenes Icon bei `on` |
| `service` | String | — | Nur ohne `entity_id`: `domain.service` |
| `targets` | Array | — | Entitäten für `service`, ein Aufruf pro Entität |
| `icon` | String | Glühbirne | Einziger alternativer Wert: `"power"` |

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

> 💡 Koordinaten finden: Bild in einem Grafikprogramm öffnen, X/Y in Pixeln ablesen und `left = X / Breite × 100`, `top = Y / Höhe × 100` berechnen.

#### 6.4.1 `radar`-Ebene (mmWave, z. B. LD2450)

Zeichnet bis zu `targets` nummerierte Punkte. Liest die Entitäten `<prefix>_<n>_x` und `<prefix>_<n>_y` mit n = 1…targets. Fehlt die Entität oder ist sie nicht numerisch, erscheint der Punkt nicht.

| Feld | Standard | Beschreibung |
|---|---|---|
| `prefix` | — (**Pflicht**) | Sensor-Präfix, z. B. `sensor.radar_soggiorno_target` |
| `targets` | `3` | Anzahl Ziele |
| `origin_left` / `origin_top` | `50` / `10` | Sensorposition im Bild (%) |
| `scale_x` / `scale_y` | `8` / `8` | % des Bildes pro **Einheit** des Sensorwerts |
| `scale_x_right` | = `scale_x` | X-Skala für Werte ≥ 0 (rechte Seite) |
| `top_offset_left` | `0` | Zusätzlicher vertikaler Versatz (%) der linken Seite |
| `rotation` | `0` | Drehung in Grad |
| `flip_x` / `flip_y` | `false` | Achsen spiegeln |
| `blend` | `"overlay"` | `none`, `multiply`, `screen`, `softlight`, `hardlight`, `difference`, `overlay` |

Angewandte Formel (zum Verständnis der Kalibrierung): `left = origin_left + x_gedreht × x_skala`, `top = origin_top + y_gedreht × scale_y`. Ergebnis auf 0–100 % begrenzt.

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

> 💡 Kalibrierung: an einen bekannten Punkt stellen, beobachten, wo der Punkt erscheint, zuerst `origin_*`, dann `scale_*`, zuletzt `rotation`/`flip_*` korrigieren. Die Einheit von `scale_*` hängt davon ab, wie deine Firmware x/y liefert (mm, cm oder m).

#### 6.4.2 `vacuum`-Ebene

Roboter-Icon **pro Raum** positioniert (HA liefert keine X/Y des Roboters, nur den aktuellen Raum). Tippen → Popup mit allen Steuerungen der `vacuum`-Karte (7.12). Akzeptiert dieselben Optionen wie `vacuum` und zusätzlich:

| Feld | Beschreibung |
|---|---|
| `entity_id` | `vacuum.*`-Entität (**Pflicht**) |
| `room_entity` | Sensor mit dem Namen des aktuellen Raums |
| `room_positions` | Zuordnung `"Raumname": [left, top]` in % |
| `dock_position` | `[left, top]` der Basisstation |

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

### 6.5 Reine JSON-Felder der Karte `title`

Die Karte `title` hat ein Formular, **antippbare** Titel und Untertitel lassen sich aber nur per JSON einrichten. Gleiches Aktionsvokabular wie `scene_grid` (7.10), mit Präfix `title_` oder `subtitle_`:

| Feld | Aktion |
|---|---|
| `title_entity_id` | Szene/Skript aktivieren |
| `title_page` | Seite öffnen |
| `title_activityId` (+ `title_hub`) | Harmony-Aktivität starten |
| `title_harmonyDevice` + `title_harmonyCommand` (+ `title_hub`) | IR-Befehl über Harmony |
| `title_irDevice` + `title_irCommand` | Lokaler IR-Befehl |
| `title_activity` | Zusammengesetzte Aktivität starten |

Beispiel — Untertitel „Alle anzeigen →“, der die Seite Licht öffnet:

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

## 7. Karten mit Editor-Formular

Auch diese lassen sich von Hand schreiben. Alle vom Code gelesenen Optionen sind aufgeführt.

### 7.1 `title`

| Option | Standard | Beschreibung |
|---|---|---|
| `title` / `subtitle` | — | Texte |
| `alignment` | `"start"` | `start`, `center`, `end`, `justify` |
| `icon` | — | PNG vor dem Titel (erzwingt Linksausrichtung der Titelzeile) |
| `divider` | `false` | Linie, die die Zeile nach dem Titel füllt |
| `color` | Theme | Farbe von Titel und Linie |

### 7.2 `switch`

| Option | Beschreibung |
|---|---|
| `entity_id` | Schaltbare Entität (`switch.*`, `input_boolean.*`, …) |
| `name` | Beschriftung |
| `icon` | `heater`/`heat`, `fan`, `bulb`/`light`; sonst → Ein/Aus |
| `on_color` | Farbe im eingeschalteten Zustand. **Nur `#AARRGGBB`** (ein `#RRGGBB`-Wert wird transparent) |

```json
{ "type": "switch", "options": { "entity_id": "switch.stufa", "name": "Stufa", "icon": "heater", "on_color": "#B3902828" } }
```

### 7.3 `light`

Tippen = Toggle; langer Druck = Detail-Popup.

| Option | Standard | Beschreibung |
|---|---|---|
| `entity_id`, `name` | — | |
| `layout` | `default` | `default`, `horizontal`, `vertical` |
| `use_light_color` | `false` | Icon in der RGB-Farbe der Leuchte |
| `show_brightness` | `true` | Zustand „N%“ statt „On“ |
| `show_brightness_control` | siehe Hinweis | Helligkeitsregler |
| `show_color_temp_control` | `false` | Farbtemperaturregler |
| `show_color_control` | `false` | Farbfelder |
| `collapsible_controls` | `false` | Blendet Regler bei ausgeschalteter Leuchte aus |

> Ist **keines** der drei `show_*_control` vorhanden, erscheint nur der Helligkeitsregler. Sobald eines gesetzt ist, erscheinen nur die auf `true` gesetzten. Bei mehreren Reglern wechselt ein Pfeil-Button zwischen ihnen.

```json
{ "type": "light", "options": { "entity_id": "light.soggiorno", "name": "Soggiorno", "layout": "horizontal", "use_light_color": true, "show_brightness_control": true, "show_color_control": true } }
```

### 7.4 `cover`

Langer Druck = Popup mit Voreinstellungen 25/50/75 %.

| Option | Standard | Beschreibung |
|---|---|---|
| `entity_id`, `name`, `layout` | | wie `light` |
| `show_buttons_control` | siehe Hinweis | Öffnen/Stopp/Schließen |
| `show_position_control` | `false` | Positionsregler |
| `show_tilt_position_control` | `false` | Neigungsregler |

> Gleiche Logik wie bei `light`: ohne Flag erscheinen die Tasten. Regler erscheinen nur, wenn die Entität das passende Attribut liefert.

### 7.5 `fan`

| Option | Standard | Beschreibung |
|---|---|---|
| `entity_id`, `name` | | |
| `style` | `auto` | `auto`, `simple`, `step`, `full` |
| `preset_modes` | von der Entität | Array, Reihenfolge und Filter der Presets |
| `step` | `percentage_step` der Entität, sonst `20` | Schrittweite in % |
| `show_captions` | `true` | Beschriftungen über den Chip-Reihen |

### 7.6 `climate`

| Option | Standard | Beschreibung |
|---|---|---|
| `entity_id`, `name` | | |
| `step` | `1.0` | Sollwert-Schritt. **Ignoriert**, wenn die Entität `target_temp_step` liefert |
| `hvac_modes` / `fan_modes` / `swing_modes` | von der Entität | Arrays: Reihenfolge und Filter der Chips (`off` immer ausgeschlossen) |
| `hvac_mode_style` | `icons` | `icons` oder `label` |
| `fan_mode_style` / `swing_mode_style` | `label` | `icons` oder `label` |
| `show_captions` | `true` | Beschriftungen |

```json
{ "type": "climate", "options": { "entity_id": "climate.soggiorno", "name": "Clima", "step": 0.5, "hvac_modes": ["heat", "cool", "auto"], "fan_mode_style": "icons" } }
```

### 7.7 `select` und `source_select`

- `select`: für `input_select.*` / `select.*`. Optionen: `entity_id`, `name`, `icon_color`, `layout`.
- `source_select`: Quelle eines `media_player` (`source_list`). Optionen: `entity_id`, `name`.

### 7.8 `media_player`

Kompakt: Tippen = Wiedergabe/Pause; langer Druck = Details.

| Option | Standard | Beschreibung |
|---|---|---|
| `entity_id`, `name` | | |
| `variant` | kompakt | `"full"` für die große Version |
| `use_media_info` | `true` | Titel/Interpret statt Name |
| `show_volume_level` | `false` | Hängt „⸱ N%“ an |
| `media_controls` | `previous,play_pause,next` | Kommaliste: `on_off`, `shuffle`, `previous`, `play_pause`, `next`, `repeat` |
| `volume_controls` | `mute,buttons` | `mute`, `buttons`, `set` (Regler) |
| `top_buttons` | — | Nur `full`: `{ "name", "service", "entity_id", "data" }` |

Von der Entität nicht unterstützte Tasten (`supported_features`) werden ausgeblendet.

### 7.9 `button_grid`

| Option | Standard | Beschreibung |
|---|---|---|
| `columns` | `3` | Spalten |
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

| Option | Standard | Beschreibung |
|---|---|---|
| `columns` | `2` | Spalten |
| `layout` | Raster | `"row"` = scrollbare Reihe |
| `show_labels` | `true` | Name unter dem Icon |
| `icon_fill` | `false` | Icon füllt die Kachel |
| `tile_height` | `74` (`120` mit `icon_fill`) | Kachelhöhe (dp) |
| `scenes` | — | Kacheln (unten) |

Felder jeder Kachel: `name`, `icon`, `color` und **eine Aktion**: `entity_id` (Szene/Skript), `page`, `activityId` (+`hub`), `harmonyDevice`+`harmonyCommand` (+`hub`), `irDevice`+`irCommand`, `activity`. Optional `track` + `room` (+`devices`), um sie als Aktivität zu verfolgen. Mit `activity` wird `page` ignoriert (Seite an der Aktivität festlegen).

### 7.11 `camera`

| Option | Standard | Beschreibung |
|---|---|---|
| `entity_id` | — (**Pflicht**) | `camera.*` |
| `name` | friendly name | |
| `mode` | `stream` | `stream` (MJPEG) oder `snapshot` |
| `snapshot_interval` | `2` | Sekunden zwischen Bildern |
| `aspect` | `1.777` | B/H-Verhältnis |
| `fit` | `cover` | `cover` oder `contain` |

Tippen = Vollbild mit Zoom (Pinch 1×–8×).

### 7.12 `vacuum`

| Option | Beschreibung |
|---|---|
| `entity_id`, `name` | |
| `map_image` | `image.*`-Entität der Karte |
| `map_rotation` | Kartendrehung (Grad) |
| `map_height` | Kartenhöhe (dp) |
| `rooms` | `{ "name", "id" }` — Segment-Id der Karte |
| `room_clean_action` | Standard Roborock/Xiaomi (`vacuum.send_command app_segment_clean`); sonst `{ "domain", "service", "parameter" }` |

### 7.13 `clock_weather`

Optionen: `entity_id` (`weather.*`), `time_format` (`12` Standard oder `24`), `forecast_rows` (Standard `4`), `calendar_entity`.

### 7.14 `tv_remote`

| Option | Beschreibung |
|---|---|
| `name` | Standard `TV` |
| `remote_entity` | `remote.*` (**Pflicht**) — sendet `remote.send_command` |
| `mute_entity` | Alternative Entität für Stumm |
| `media_entity` | `media_player` zum Starten von Apps |
| `commands` | Befehle umbelegen: `up`, `down`, `left`, `right`, `center`, `back`, `home`, `menu`, `power` |
| `apps` | `{ "name", "app" }` oder `{ "name", "service", "entity_id", "data" }` |

### 7.15 `apple_tv_remote`

Befehle gehen **direkt an den Harmony-Hub**. Optionen: `deviceId` (Harmony-Geräte-Id), `hub` (optional).

### 7.16 `plex`

Optionen: `host`, `token`, `media_entity`, `play_entity`, `play_content_type` (`video`/`url`), `source` (Standard `Plex`), `show_on_deck`, `show_recently_added_movies`, `show_recently_added_shows` (Standard `true`), `items_per_row` (Standard `12`).

> ⚠️ Das Plex-Token steht im Klartext in der Datei: `dashboard.json` nicht weitergeben.

## 8. Physische Tasten (`hotkeys` / `longHotkeys`)

Verfügbare `key`-Namen der HA100:
`UP DOWN LEFT RIGHT CENTER`, `PAGE_UP PAGE_DOWN`, `VOLUME_UP VOLUME_DOWN MUTE`, `BACK HOME POWER VOICE`, `LIGHT CURTAIN SCENE AC`, `CUSTOM_1…CUSTOM_4`.

Felder (Achtung: hier heißt die Entität **`entityId`**, in camelCase):

| Feld | Beschreibung |
|---|---|
| `key` | Taste (**Pflicht**) |
| `openOverlay` | `settings` oder `activities` |
| `openCurrentActivityRoom` | Raumname: öffnet die Seite der dort aktiven Aktivität |
| `page` | Öffnet die Seite |
| `harmonyDevice` + `harmonyCommand` / `harmonyActivity` (+ `hub`) | Harmony (`harmonyActivity: "-1"` = ausschalten) |
| `irDevice` + `irCommand` | Lokales IR |
| `service` + `entityId` + `data` | HA-Dienst |
| `track` + `room` (+ `devices`) | Als Aktivität verfolgen |

**Eine Aktion pro Taste** verwenden. Priorität: `openOverlay` → `openCurrentActivityRoom` → `page` → … → `service`.

```json
{ "key": "CUSTOM_1", "service": "media_player.play_media", "entityId": "media_player.tv", "data": { "media_content_type": "app", "media_content_id": "com.netflix.ninja" } }
```

> 💡 Pfeiltasten und Lautstärke wiederholen sich beim Gedrückthalten: keine `longHotkeys` zuweisen.

## 9. Harmony, lokales IR und Aktivitäten

**`irDevices`** — IR-Geräte, die über den eigenen IR-Sender der Fernbedienung gesteuert werden:

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

Statt `commands`: `category` + `brand` + `model` (Datenbank in `/sdcard/astrion/ir-database/`). Feld `target`: `"local"` (Standard) oder `{ "extender": "<id>" }`.

**`activities`** — Szenarien mit mehreren Geräten:

| Feld | Beschreibung |
|---|---|
| `id`, `name`, `room` | `id` und `room` **Pflicht** |
| `icon`, `page` | Icon; beim Start geöffnete Seite |
| `devices` | **Pflicht, nicht leer**: `deviceId`, `source` (`ir`/`harmony`/`ha`), `hub`, `powerOnCommand`, `powerOffCommand`, `inputCommand`, `powerOnFirst` (Standard `true`), `powerOffOnExit` (Standard `true`), `delayAfterMs` |
| `volumeDeviceId`, `volumeUpCommand`, `volumeDownCommand`, `muteCommand` | Lautstärkesteuerung |

Die Harmony-Konfiguration (Hub-IP/-Id) erfolgt auf der Seite `:8080`, nicht im JSON.

## 10. Theme

Alle optional (Standardwerte in Klammern): `background` (#0E2229), `cardSurface` (#1B343D), `insetSurface` (#152B33), `controlBackground` (#2C4C58), `primaryText` (#E6F0F1), `mutedText` (#93AFB6), `iconTint` (#CBDCE0), `accent` (#6EA8FE), `accentSecondary` (#4C6EF5), `amber` (#FFC24B), `danger` (#E06767), `success` (#4CAF50).

## 11. Vollständiges Beispiel

Fünf Seiten: Luci (Licht), Casa (Grundriss + Sensoren), Musica (Lautsprechergruppe), Clima (Klima), Citofono (Türsprechstelle mit automatischem Öffnen).

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

## 12. Fehlerbehebung

| Symptom | Wahrscheinliche Ursache | Lösung |
|---|---|---|
| Das Beispiel-Dashboard erscheint | Ungültiges JSON oder `pages` fehlt/leer | JSON prüfen (z. B. `python -m json.tool dashboard.json`) |
| Eine Karte erscheint nicht | Falscher `type` oder Pflichtoption fehlt (`master`, `remote_entity`, `entity_id`) | Typ und Optionen prüfen |
| Felder nach Bearbeitung im Editor verloren | Über das einfache Formular gespeichert | „Options (raw JSON)“ verwenden |
| `switch` an, aber Farbe unsichtbar | 6-stelliges `on_color` | `#AARRGGBB` verwenden |
| PNG-Icons fehlen | Relativer Pfad oder Datei nicht hochgeladen | Absoluter Pfad `/sdcard/astrion/icons/…` |
| Dienst-Taste ohne Wirkung | `entity_id` statt `entityId` | In Tasten `entityId` verwenden |
| Keine Radarpunkte | Entitäten `<prefix>_<n>_x/_y` fehlen oder nicht numerisch | Namen in Entwicklerwerkzeuge → Zustände prüfen |
| Automatisch öffnende Seite öffnet nicht | Die Entität wechselt nie auf `on` | Binäre Entität oder Template-`binary_sensor` verwenden |
| Lautsprecher immer „nicht gruppiert“ | Integration liefert kein `group_members` am Lautsprecher | Attribut in HA prüfen |
