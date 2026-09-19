# Astrion Custom Dashboard — Guida completa a `dashboard.json`

> Lingua: **Italiano** · [English](GUIDE_dashboard_json_EN.md) · [Français](GUIDE_dashboard_json_FR.md) · [Español](GUIA_dashboard_json_ES.md) · [Deutsch](ANLEITUNG_dashboard_json_DE.md)
>
> Riferimento: repository [`dckiller51/astrion-custom-dashboard`](https://github.com/dckiller51/astrion-custom-dashboard), branch `main`, commit `d73ec52` (app 1.1.4). Ogni opzione descritta è stata verificata nel sorgente Kotlin (`config/DashboardLoader.kt`, `config/AppConfig.kt`, `cards/impl/*.kt`). Le versioni successive potrebbero cambiare.
>
> Tutti gli esempi usano **entità fittizie** (`light.soggiorno`, `media_player.salotto`, …): sostituiscile con le tue.

## Indice

1. [Cos'è (e cosa non è)](#1-cosè-e-cosa-non-è)
2. [Dove vive il file e come caricarlo](#2-dove-vive-il-file-e-come-caricarlo)
3. [Struttura radice](#3-struttura-radice)
4. [Pagine](#4-pagine)
5. [Card: regole comuni](#5-card-regole-comuni)
6. [Card senza interfaccia nell'editor](#6-card-senza-interfaccia-nelleditor)
7. [Card con editor](#7-card-con-editor)
8. [Tasti fisici (`hotkeys` / `longHotkeys`)](#8-tasti-fisici-hotkeys--longhotkeys)
9. [Harmony, IR locale e Activity](#9-harmony-ir-locale-e-activity)
10. [Tema](#10-tema)
11. [Esempio completo](#11-esempio-completo)
12. [Risoluzione problemi](#12-risoluzione-problemi)

---

## 1. Cos'è (e cosa non è)

Astrion Custom Dashboard è un'**app Android** che sostituisce il launcher del telecomando **Sanytron Astrion HA100**. Parla con Home Assistant via WebSocket.

- **Non** è una dashboard Lovelace e **non** si installa da HACS.
- Le "card" sono componenti nativi dell'app, non card Lovelace: la sintassi è **JSON**, non YAML.
- Molti nomi di opzione imitano le card Mushroom, ma solo le opzioni elencate qui sono lette dall'app.

## 2. Dove vive il file e come caricarlo

| Elemento | Percorso sul telecomando |
|---|---|
| Configurazione | `/sdcard/astrion/dashboard.json` |
| Icone PNG | `/sdcard/astrion/icons/` |
| Database IR (opzionale) | `/sdcard/astrion/ir-database/` |

Modi per modificarlo:

1. **Editor online** — <https://dckiller51.github.io/astrion-custom-dashboard/>: form per pagine, card e tasti; scarica il JSON risultante.
2. **Pagina locale** — `http://<ip-telecomando>:8080` (l'indirizzo compare nel pannello Impostazioni): carica/scarica `dashboard.json`, carica icone. Il caricamento ricarica la dashboard **senza riavvio**.
3. **adb** — `adb push dashboard.json /sdcard/astrion/dashboard.json`, poi riapri l'app.

> ⚠️ Se il JSON non è valido l'app **non si blocca**: carica il layout predefinito interno e mostra `dashboard.json invalid (…) — using built-in defaults`. Scarica sempre un backup prima di caricare.

> ⚠️ L'editor online, se riapri una card e la salvi dal form semplice, **riscrive le opzioni da zero**: i campi solo-JSON (sezione 6) vanno persi. Per quelle card usa sempre il campo "Options (raw JSON)".

## 3. Struttura radice

La radice è un oggetto JSON. In alternativa è accettato un **array di card**: diventa un'unica pagina chiamata `Main`.

| Chiave | Tipo | Obbligatoria | Default | Descrizione |
|---|---|---|---|---|
| `pages` | array | **sì** (non vuoto) | — | Pagine, da sinistra a destra |
| `startPage` | intero | no | `0` | Indice (da 0) della pagina all'avvio |
| `hotkeys` | array | no | `[]` | Tasti fisici, pressione breve |
| `longHotkeys` | array | no | `[]` | Tasti fisici, pressione lunga (~500 ms) |
| `irDevices` | array | no | `[]` | Dispositivi IR locali (sezione 9) |
| `activities` | array | no | `[]` | Activity composte (sezione 9) |
| `theme` | oggetto | no | tema scuro | Colori (sezione 10) |

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

## 4. Pagine

| Chiave | Tipo | Default | Descrizione |
|---|---|---|---|
| `name` | stringa | `"Page"` | Nome; usato per la navigazione (case-insensitive) |
| `cards` | array | `[]` | Card della pagina, dall'alto verso il basso |
| `hotkeys` / `longHotkeys` | array | `[]` | Tasti validi solo su questa pagina; **sovrascrivono** quelli globali con lo stesso `key` |
| `parent` | stringa | — | Pagina "madre": il tasto `parentKey` torna lì |
| `parentKey` | stringa | `"BACK"` | Tasto fisico che torna a `parent` |
| `linkedPage` | stringa | — | Pagina aperta con swipe **verso l'alto** sull'indicatore pagine |
| `hiddenUnlessActivity` | stringa | — | Id di Activity: il pallino della pagina appare solo quando quell'Activity è attiva (la pagina resta raggiungibile) |
| `openWhenEntity` | stringa | — | Entità HA: quando va a `on` la pagina si apre da sola; quando esce da `on` si richiude |

> ℹ️ L'editor online può scrivere anche `openWhenState` / `closeWhenState`, ma nel commit analizzato `DashboardLoader.kt` **non li legge**: lo stato di apertura è sempre `on`. Per un'entità con stati diversi, crea un `binary_sensor` template in HA.

Esempio — pagina citofono che si apre al suono del campanello e si chiude con BACK:

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

## 5. Card: regole comuni

Ogni card ha questa forma:

```json
{ "type": "light", "options": { "entity_id": "light.soggiorno" } }
```

- `type` è **obbligatorio**; `options` è facoltativo.
- Un `type` non registrato non genera errore di parsing: la card semplicemente non viene disegnata.
- `"pin": "bottom"` dentro `options` (qualsiasi card) la **fissa in fondo** alla pagina, fuori dallo scorrimento.
- **Icone PNG**: percorso assoluto, es. `/sdcard/astrion/icons/netflix.png`.
- **Colori**: `#RRGGBB` o `#AARRGGBB` (alfa per primo). Eccezione: `switch.on_color` accetta solo `#AARRGGBB` (vedi 7.2).

Tipi registrati (21) e supporto nell'editor online:

| Tipo | Editor | Tipo | Editor |
|---|---|---|---|
| `title` | form (+ campi solo JSON) | `media_player` | form |
| `light` | form | `camera` | form |
| `switch` | form | `clock_weather` | form |
| `cover` | form | `vacuum` | form |
| `fan` | form | `plex` | form |
| `climate` | form | `speaker_group` | **solo JSON** |
| `source_select` | form | `monitor` | **solo JSON** |
| `select` | form | `picture_elements` | **solo JSON** |
| `button_grid` | form | `row` | **solo JSON** |
| `scene_grid` | form | `apple_tv_remote` | form |
| `tv_remote` | form | | |

## 6. Card senza interfaccia nell'editor

Nell'editor online scegli il tipo (gruppo *Advanced (raw options)*) e incolla **solo l'oggetto `options`** nel campo *Options (raw JSON)*. Negli esempi sotto è mostrata la card intera: copia ciò che sta dentro `"options"`.

### 6.1 `speaker_group` — gruppo di speaker (Sonos e simili)

Una riga per speaker con: spunta = membro del gruppo, barra volume trascinabile, muto, vol−/vol+. La prima riga è il master.

| Opzione | Tipo | Obbl. | Descrizione |
|---|---|---|---|
| `master` | stringa | **sì** | `media_player` coordinatore del gruppo. Senza, la card non appare |
| `name` | stringa | no | Nome mostrato per il master |
| `speakers` | array | no | Elenco `{ "entity_id", "name" }` degli speaker aggregabili |

Comportamento:

- Spuntare → `media_player.join` sul master con `group_members: [speaker]`, **immediato**.
- Togliere la spunta → `media_player.unjoin` sullo speaker.
- Lo stato "nel gruppo" si legge dall'attributo `group_members` **dello speaker** (deve contenere il master).
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

### 6.2 `monitor` — elenco valori sensori

Elenco in sola lettura: nome, valore attuale, `unit_of_measurement`. Nessun grafico storico. `unknown` / `unavailable` sono mostrati come `—`.

| Opzione | Tipo | Obbl. | Descrizione |
|---|---|---|---|
| `title` | stringa | no | Intestazione |
| `entities` | array | sì | `{ "entity_id", "name" }`; `name` facoltativo (default: friendly name) |

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

### 6.3 `row` — card affiancate

Contenitore orizzontale: le card figlie hanno **larghezza uguale**. Ogni figlia è una card completa (`type` + `options`). È ammessa qualunque card, anche un'altra `row`.

| Opzione | Tipo | Obbl. | Descrizione |
|---|---|---|---|
| `cards` | array | sì | Card figlie |

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

> 💡 Nelle `row` preferisci `"layout": "vertical"` (light/cover/select): lo spazio orizzontale è ridotto.

### 6.4 `picture_elements` — planimetria interattiva

Immagine di sfondo con icone posizionate in **percentuale**. Può mostrare anche i bersagli di un radar mmWave e il robot aspirapolvere.

| Opzione | Tipo | Default | Descrizione |
|---|---|---|---|
| `image` | stringa | `/sdcard/astrion/floorplan.png` | Percorso PNG/JPG sul telecomando |
| `aspect` | numero | `1.3` | Rapporto L/A usato **finché l'immagine non è caricata** (poi prevale quello reale) |
| `elements` | array | `[]` | Icone (tabella sotto) |
| `radar` | oggetto | — | Overlay radar (6.4.1) |
| `vacuum` | oggetto | — | Overlay aspirapolvere (6.4.2) |

Campi di ogni elemento:

| Campo | Tipo | Default | Descrizione |
|---|---|---|---|
| `left` / `top` | numero | `50` | Centro dell'icona, % di larghezza / altezza |
| `entity_id` | stringa | — | Tocco → **toggle** dell'entità; icona ambra quando è `on` |
| `service` | stringa | — | Usato solo se manca `entity_id`: `dominio.servizio` |
| `targets` | array | — | Entità a cui applicare `service`, una chiamata per entità |
| `icon` | stringa | lampadina | Unico valore alternativo: `"power"` |

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

> 💡 Per trovare le coordinate: apri l'immagine in un editor grafico, prendi X/Y in pixel e calcola `left = X / larghezza × 100`, `top = Y / altezza × 100`.

#### 6.4.1 Overlay `radar` (mmWave, es. LD2450)

Disegna fino a `targets` pallini numerati. Legge le entità `<prefix>_<n>_x` e `<prefix>_<n>_y` con n = 1…targets. Se l'entità manca o non è numerica, il pallino non appare.

| Campo | Default | Descrizione |
|---|---|---|
| `prefix` | — (**obbl.**) | Prefisso sensori, es. `sensor.radar_soggiorno_target` |
| `targets` | `3` | Numero bersagli |
| `origin_left` / `origin_top` | `50` / `10` | Posizione del sensore sull'immagine (%) |
| `scale_x` / `scale_y` | `8` / `8` | % di immagine per **unità** del valore del sensore |
| `scale_x_right` | = `scale_x` | Scala X per i valori ≥ 0 (lato destro) |
| `top_offset_left` | `0` | Spostamento verticale extra (%) per il lato sinistro |
| `rotation` | `0` | Rotazione in gradi |
| `flip_x` / `flip_y` | `false` | Specchia gli assi |
| `blend` | `"overlay"` | `none`, `multiply`, `screen`, `softlight`, `hardlight`, `difference`, `overlay` |

Formula applicata (per capire la taratura): `left = origin_left + x_ruotato × scala_x`, `top = origin_top + y_ruotato × scale_y`. Risultato limitato a 0–100 %.

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

> 💡 Taratura: mettiti in un punto noto, guarda dove appare il pallino, correggi prima `origin_*`, poi `scale_*`, infine `rotation`/`flip_*`. L'unità di `scale_*` dipende da come il tuo firmware espone x/y (mm, cm o m).

#### 6.4.2 Overlay `vacuum`

Icona del robot posizionata **per stanza** (HA non espone X/Y del robot, solo la stanza corrente). Tocco → popup con tutti i comandi della card `vacuum` (7.12). Accetta le stesse opzioni di `vacuum` più:

| Campo | Descrizione |
|---|---|
| `entity_id` | Entità `vacuum.*` (**obbl.**) |
| `room_entity` | Sensore con il nome della stanza corrente |
| `room_positions` | Mappa `"NomeStanza": [left, top]` in % |
| `dock_position` | `[left, top]` della base |

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

### 6.5 Campi solo-JSON della card `title`

La card `title` ha un form, ma titolo e sottotitolo **cliccabili** si configurano solo nel JSON. Si usa lo stesso vocabolario di azioni di `scene_grid` (7.10) con prefisso `title_` o `subtitle_`:

| Campo | Azione |
|---|---|
| `title_entity_id` | Attiva scena/script |
| `title_page` | Apre la pagina |
| `title_activityId` (+ `title_hub`) | Avvia Activity Harmony |
| `title_harmonyDevice` + `title_harmonyCommand` (+ `title_hub`) | Comando IR via Harmony |
| `title_irDevice` + `title_irCommand` | Comando IR locale |
| `title_activity` | Avvia Activity composta |

Esempio — sottotitolo "Vedi tutte →" che apre la pagina Luci:

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

## 7. Card con editor

Anche queste si possono scrivere a mano. Sono elencate tutte le opzioni lette dal codice.

### 7.1 `title`

| Opzione | Default | Descrizione |
|---|---|---|
| `title` / `subtitle` | — | Testi |
| `alignment` | `"start"` | `start`, `center`, `end`, `justify` |
| `icon` | — | PNG prima del titolo (forza allineamento a sinistra della riga titolo) |
| `divider` | `false` | Linea che riempie la riga dopo il titolo |
| `color` | tema | Colore titolo e linea |

### 7.2 `switch`

| Opzione | Descrizione |
|---|---|
| `entity_id` | Entità commutabile (`switch.*`, `input_boolean.*`, …) |
| `name` | Etichetta |
| `icon` | `heater`/`heat`, `fan`, `bulb`/`light`; altro → accensione |
| `on_color` | Colore quando acceso. **Solo `#AARRGGBB`** (un valore `#RRGGBB` risulta trasparente) |

```json
{ "type": "switch", "options": { "entity_id": "switch.stufa", "name": "Stufa", "icon": "heater", "on_color": "#B3902828" } }
```

### 7.3 `light`

Tocco = toggle; pressione lunga = popup dettagli.

| Opzione | Default | Descrizione |
|---|---|---|
| `entity_id`, `name` | — | |
| `layout` | `default` | `default`, `horizontal`, `vertical` |
| `use_light_color` | `false` | Icona tinta col colore RGB della luce |
| `show_brightness` | `true` | Stato "N%" invece di "On" |
| `show_brightness_control` | vedi nota | Slider luminosità |
| `show_color_temp_control` | `false` | Slider temperatura colore |
| `show_color_control` | `false` | Campioni di colore |
| `collapsible_controls` | `false` | Nasconde i controlli a luce spenta |

> Se **nessuno** dei tre `show_*_control` è presente, appare solo lo slider luminosità. Appena ne imposti uno, appaiono solo quelli a `true`. Con più controlli, un pulsante a freccia li alterna.

```json
{ "type": "light", "options": { "entity_id": "light.soggiorno", "name": "Soggiorno", "layout": "horizontal", "use_light_color": true, "show_brightness_control": true, "show_color_control": true } }
```

### 7.4 `cover`

Pressione lunga = popup con preset 25/50/75 %.

| Opzione | Default | Descrizione |
|---|---|---|
| `entity_id`, `name`, `layout` | | come `light` |
| `show_buttons_control` | vedi nota | Apri/stop/chiudi |
| `show_position_control` | `false` | Slider posizione |
| `show_tilt_position_control` | `false` | Slider inclinazione |

> Stessa logica di `light`: senza nessun flag appaiono i pulsanti. Slider mostrati solo se l'entità espone l'attributo relativo.

### 7.5 `fan`

| Opzione | Default | Descrizione |
|---|---|---|
| `entity_id`, `name` | | |
| `style` | `auto` | `auto`, `simple`, `step`, `full` |
| `preset_modes` | dall'entità | Array, ordine e filtro dei preset |
| `step` | `percentage_step` dell'entità, poi `20` | Passo % |
| `show_captions` | `true` | Didascalie sopra le righe di chip |

### 7.6 `climate`

| Opzione | Default | Descrizione |
|---|---|---|
| `entity_id`, `name` | | |
| `step` | `1.0` | Passo setpoint. **Ignorato** se l'entità espone `target_temp_step` |
| `hvac_modes` / `fan_modes` / `swing_modes` | dall'entità | Array: ordine e filtro dei chip (`off` è sempre escluso) |
| `hvac_mode_style` | `icons` | `icons` o `label` |
| `fan_mode_style` / `swing_mode_style` | `label` | `icons` o `label` |
| `show_captions` | `true` | Didascalie |

```json
{ "type": "climate", "options": { "entity_id": "climate.soggiorno", "name": "Clima", "step": 0.5, "hvac_modes": ["heat", "cool", "auto"], "fan_mode_style": "icons" } }
```

### 7.7 `select` e `source_select`

- `select`: per `input_select.*` / `select.*`. Opzioni: `entity_id`, `name`, `icon_color`, `layout`.
- `source_select`: sorgente di un `media_player` (`source_list`). Opzioni: `entity_id`, `name`.

### 7.8 `media_player`

Compatto: tocco = play/pausa; pressione lunga = dettagli.

| Opzione | Default | Descrizione |
|---|---|---|
| `entity_id`, `name` | | |
| `variant` | compatto | `"full"` per la versione grande |
| `use_media_info` | `true` | Titolo/artista invece del nome |
| `show_volume_level` | `false` | Aggiunge "⸱ N%" |
| `media_controls` | `previous,play_pause,next` | Lista con virgole: `on_off`, `shuffle`, `previous`, `play_pause`, `next`, `repeat` |
| `volume_controls` | `mute,buttons` | `mute`, `buttons`, `set` (slider) |
| `top_buttons` | — | Solo `full`: `{ "name", "service", "entity_id", "data" }` |

I pulsanti non supportati dall'entità (`supported_features`) vengono nascosti.

### 7.9 `button_grid`

| Opzione | Default | Descrizione |
|---|---|---|
| `columns` | `3` | Colonne |
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

| Opzione | Default | Descrizione |
|---|---|---|
| `columns` | `2` | Colonne |
| `layout` | griglia | `"row"` = riga scorrevole |
| `show_labels` | `true` | Nome sotto l'icona |
| `icon_fill` | `false` | Icona a tutta piastrella |
| `tile_height` | `74` (`120` con `icon_fill`) | Altezza piastrella (dp) |
| `scenes` | — | Piastrelle (sotto) |

Campi di ogni piastrella: `name`, `icon`, `color`, e **un'azione**: `entity_id` (scena/script), `page`, `activityId` (+`hub`), `harmonyDevice`+`harmonyCommand` (+`hub`), `irDevice`+`irCommand`, `activity`. Opzionali `track` + `room` (+`devices`) per tracciarla come Activity. Con `activity` il campo `page` viene ignorato (la pagina si imposta sull'Activity).

### 7.11 `camera`

| Opzione | Default | Descrizione |
|---|---|---|
| `entity_id` | — (**obbl.**) | `camera.*` |
| `name` | friendly name | |
| `mode` | `stream` | `stream` (MJPEG) o `snapshot` |
| `snapshot_interval` | `2` | Secondi tra fotogrammi |
| `aspect` | `1.777` | Rapporto L/A |
| `fit` | `cover` | `cover` o `contain` |

Tocco = schermo intero con zoom (pinch 1×–8×).

### 7.12 `vacuum`

| Opzione | Descrizione |
|---|---|
| `entity_id`, `name` | |
| `map_image` | Entità `image.*` della mappa |
| `map_rotation` | Rotazione mappa (gradi) |
| `map_height` | Altezza mappa (dp) |
| `rooms` | `{ "name", "id" }` — id segmento della mappa |
| `room_clean_action` | Default Roborock/Xiaomi (`vacuum.send_command app_segment_clean`); altrimenti `{ "domain", "service", "parameter" }` |

### 7.13 `clock_weather`

Opzioni: `entity_id` (`weather.*`), `time_format` (`12` default o `24`), `forecast_rows` (default `4`), `calendar_entity`.

### 7.14 `tv_remote`

| Opzione | Descrizione |
|---|---|
| `name` | Default `TV` |
| `remote_entity` | `remote.*` (**obbl.**) — invia `remote.send_command` |
| `mute_entity` | Entità alternativa per il muto |
| `media_entity` | `media_player` usato per lanciare le app |
| `commands` | Rimappa i comandi: `up`, `down`, `left`, `right`, `center`, `back`, `home`, `menu`, `power` |
| `apps` | `{ "name", "app" }` oppure `{ "name", "service", "entity_id", "data" }` |

### 7.15 `apple_tv_remote`

Comandi inviati **direttamente all'hub Harmony**. Opzioni: `deviceId` (id dispositivo Harmony), `hub` (facoltativo).

### 7.16 `plex`

Opzioni: `host`, `token`, `media_entity`, `play_entity`, `play_content_type` (`video`/`url`), `source` (default `Plex`), `show_on_deck`, `show_recently_added_movies`, `show_recently_added_shows` (default `true`), `items_per_row` (default `12`).

> ⚠️ Il token Plex è in chiaro nel file: non condividere `dashboard.json`.

## 8. Tasti fisici (`hotkeys` / `longHotkeys`)

Nomi `key` disponibili sull'HA100:
`UP DOWN LEFT RIGHT CENTER`, `PAGE_UP PAGE_DOWN`, `VOLUME_UP VOLUME_DOWN MUTE`, `BACK HOME POWER VOICE`, `LIGHT CURTAIN SCENE AC`, `CUSTOM_1…CUSTOM_4`.

Campi (nota: qui l'entità si chiama **`entityId`**, in camelCase):

| Campo | Descrizione |
|---|---|
| `key` | Tasto (**obbl.**) |
| `openOverlay` | `settings` o `activities` |
| `openCurrentActivityRoom` | Nome stanza: apre la pagina dell'Activity attiva lì |
| `page` | Apre la pagina |
| `harmonyDevice` + `harmonyCommand` / `harmonyActivity` (+ `hub`) | Harmony (`harmonyActivity: "-1"` = spegni) |
| `irDevice` + `irCommand` | IR locale |
| `service` + `entityId` + `data` | Servizio HA |
| `track` + `room` (+ `devices`) | Traccia come Activity |

Usa **un'azione per tasto**. Priorità: `openOverlay` → `openCurrentActivityRoom` → `page` → … → `service`.

```json
{ "key": "CUSTOM_1", "service": "media_player.play_media", "entityId": "media_player.tv", "data": { "media_content_type": "app", "media_content_id": "com.netflix.ninja" } }
```

> 💡 Frecce e volume si ripetono tenendo premuto: non assegnare loro `longHotkeys`.

## 9. Harmony, IR locale e Activity

**`irDevices`** — dispositivi IR pilotati dal trasmettitore del telecomando:

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

In alternativa a `commands`: `category` + `brand` + `model` (database in `/sdcard/astrion/ir-database/`). Campo `target`: `"local"` (default) o `{ "extender": "<id>" }`.

**`activities`** — scenari multi-dispositivo:

| Campo | Descrizione |
|---|---|
| `id`, `name`, `room` | `id` e `room` **obbligatori** |
| `icon`, `page` | Icona; pagina aperta all'avvio |
| `devices` | **Obbligatorio, non vuoto**: `deviceId`, `source` (`ir`/`harmony`/`ha`), `hub`, `powerOnCommand`, `powerOffCommand`, `inputCommand`, `powerOnFirst` (default `true`), `powerOffOnExit` (default `true`), `delayAfterMs` |
| `volumeDeviceId`, `volumeUpCommand`, `volumeDownCommand`, `muteCommand` | Controllo volume |

La configurazione Harmony (IP/ID hub) si fa dalla pagina `:8080`, non nel JSON.

## 10. Tema

Tutte facoltative (default fra parentesi): `background` (#0E2229), `cardSurface` (#1B343D), `insetSurface` (#152B33), `controlBackground` (#2C4C58), `primaryText` (#E6F0F1), `mutedText` (#93AFB6), `iconTint` (#CBDCE0), `accent` (#6EA8FE), `accentSecondary` (#4C6EF5), `amber` (#FFC24B), `danger` (#E06767), `success` (#4CAF50).

## 11. Esempio completo

Cinque pagine: Luci, Casa (planimetria + sensori), Musica (gruppo speaker), Clima, Citofono (auto-apertura).

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

## 12. Risoluzione problemi

| Sintomo | Causa probabile | Soluzione |
|---|---|---|
| Appare la dashboard di esempio | JSON non valido o `pages` mancante/vuoto | Valida il JSON (es. `python -m json.tool dashboard.json`) |
| Una card non appare | `type` errato, oppure manca l'opzione obbligatoria (`master`, `remote_entity`, `entity_id`) | Controlla nome tipo e opzioni |
| Campi persi dopo modifica nell'editor | Salvataggio dal form semplice | Usa "Options (raw JSON)" |
| `switch` acceso ma colore invisibile | `on_color` a 6 cifre | Usa `#AARRGGBB` |
| Icone PNG assenti | Percorso relativo o file non caricato | Percorso assoluto `/sdcard/astrion/icons/…` |
| Hotkey servizio senza effetto | `entity_id` invece di `entityId` | Nei tasti usa `entityId` |
| Pallini radar assenti | Entità `<prefix>_<n>_x/_y` inesistenti o non numeriche | Verifica i nomi in Strumenti per sviluppatori → Stati |
| Pagina auto-apertura non si apre | L'entità non va a `on` | Usa un'entità binaria o un `binary_sensor` template |
| Speaker sempre "non nel gruppo" | L'integrazione non espone `group_members` sullo speaker | Verifica l'attributo in HA |
