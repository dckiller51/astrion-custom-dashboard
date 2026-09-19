# Astrion Custom Dashboard — Guía completa de `dashboard.json`

> Idioma: [Italiano](GUIDA_dashboard_json_IT.md) · [English](GUIDE_dashboard_json_EN.md) · [Français](GUIDE_dashboard_json_FR.md) · **Español** · [Deutsch](ANLEITUNG_dashboard_json_DE.md)
>
> Referencia: repositorio [`dckiller51/astrion-custom-dashboard`](https://github.com/dckiller51/astrion-custom-dashboard), rama `main`, commit `d73ec52` (app 1.1.4). Cada opción descrita se ha verificado en el código Kotlin (`config/DashboardLoader.kt`, `config/AppConfig.kt`, `cards/impl/*.kt`). Las versiones posteriores pueden cambiar.
>
> Todos los ejemplos usan **entidades ficticias** (`light.soggiorno`, `media_player.salotto`, …), iguales en todos los idiomas: sustitúyelas por las tuyas.

## Índice

1. [Qué es (y qué no es)](#1-qué-es-y-qué-no-es)
2. [Dónde está el archivo y cómo subirlo](#2-dónde-está-el-archivo-y-cómo-subirlo)
3. [Estructura raíz](#3-estructura-raíz)
4. [Páginas](#4-páginas)
5. [Tarjetas: reglas comunes](#5-tarjetas-reglas-comunes)
6. [Tarjetas sin formulario en el editor](#6-tarjetas-sin-formulario-en-el-editor)
7. [Tarjetas con formulario](#7-tarjetas-con-formulario)
8. [Teclas físicas (`hotkeys` / `longHotkeys`)](#8-teclas-físicas-hotkeys--longhotkeys)
9. [Harmony, IR local y Actividades](#9-harmony-ir-local-y-actividades)
10. [Tema](#10-tema)
11. [Ejemplo completo](#11-ejemplo-completo)
12. [Solución de problemas](#12-solución-de-problemas)

---

## 1. Qué es (y qué no es)

Astrion Custom Dashboard es una **app Android** que sustituye el lanzador del mando **Sanytron Astrion HA100**. Se comunica con Home Assistant por WebSocket.

- **No** es un panel Lovelace y **no** se instala desde HACS.
- Sus "tarjetas" son componentes nativos de la app, no tarjetas Lovelace: la sintaxis es **JSON**, no YAML.
- Muchos nombres de opciones imitan las tarjetas Mushroom, pero la app solo lee las opciones listadas aquí.

## 2. Dónde está el archivo y cómo subirlo

| Elemento | Ruta en el mando |
|---|---|
| Configuración | `/sdcard/astrion/dashboard.json` |
| Iconos PNG | `/sdcard/astrion/icons/` |
| Base de datos IR (opcional) | `/sdcard/astrion/ir-database/` |

Formas de editarlo:

1. **Editor en línea** — <https://dckiller51.github.io/astrion-custom-dashboard/>: formularios para páginas, tarjetas y teclas; descarga el JSON generado.
2. **Página local** — `http://<ip-del-mando>:8080` (dirección visible en el panel Ajustes): subir/descargar `dashboard.json`, subir iconos. La subida recarga el panel **sin reiniciar**.
3. **adb** — `adb push dashboard.json /sdcard/astrion/dashboard.json` y vuelve a abrir la app.

> ⚠️ Si el JSON no es válido, la app **no se cierra**: carga su diseño interno y muestra `dashboard.json invalid (…) — using built-in defaults`. Descarga siempre una copia antes de subir.

> ⚠️ En el editor en línea, reabrir una tarjeta y guardarla con el formulario simple **reescribe sus opciones desde cero**: los campos solo-JSON (sección 6) se pierden. Para esas tarjetas usa siempre el campo "Options (raw JSON)".

## 3. Estructura raíz

La raíz es un objeto JSON. También se acepta un **array de tarjetas**: se convierte en una única página llamada `Main`.

| Clave | Tipo | Obligatoria | Por defecto | Descripción |
|---|---|---|---|---|
| `pages` | array | **sí** (no vacío) | — | Páginas, de izquierda a derecha |
| `startPage` | entero | no | `0` | Índice (desde 0) de la página al iniciar |
| `hotkeys` | array | no | `[]` | Teclas físicas, pulsación corta |
| `longHotkeys` | array | no | `[]` | Teclas físicas, pulsación larga (~500 ms) |
| `irDevices` | array | no | `[]` | Dispositivos IR locales (sección 9) |
| `activities` | array | no | `[]` | Actividades compuestas (sección 9) |
| `theme` | objeto | no | tema oscuro | Colores (sección 10) |

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

## 4. Páginas

| Clave | Tipo | Por defecto | Descripción |
|---|---|---|---|
| `name` | cadena | `"Page"` | Nombre; se usa para navegar (sin distinguir mayúsculas) |
| `cards` | array | `[]` | Tarjetas de la página, de arriba abajo |
| `hotkeys` / `longHotkeys` | array | `[]` | Teclas activas solo en esta página; **sustituyen** a las globales con la misma `key` |
| `parent` | cadena | — | Página padre: la tecla `parentKey` vuelve a ella |
| `parentKey` | cadena | `"BACK"` | Tecla física que vuelve a `parent` |
| `linkedPage` | cadena | — | Página abierta deslizando **hacia arriba** sobre el indicador de páginas |
| `hiddenUnlessActivity` | cadena | — | Id de Actividad: el punto de la página solo aparece cuando esa Actividad está activa (la página sigue accesible) |
| `openWhenEntity` | cadena | — | Entidad HA: cuando pasa a `on` la página se abre sola; cuando deja `on` se cierra |

> ℹ️ El editor en línea también puede escribir `openWhenState` / `closeWhenState`, pero en el commit analizado `DashboardLoader.kt` **no los lee**: el estado de apertura es siempre `on`. Para una entidad con otros estados, crea un `binary_sensor` template en HA.

Ejemplo — página de portero que se abre al sonar el timbre y se cierra con BACK:

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

## 5. Tarjetas: reglas comunes

Cada tarjeta tiene esta forma:

```json
{ "type": "light", "options": { "entity_id": "light.soggiorno" } }
```

- `type` es **obligatorio**; `options` es opcional.
- Un `type` no registrado no provoca error de análisis: la tarjeta simplemente no se dibuja.
- `"pin": "bottom"` dentro de `options` (cualquier tarjeta) la **fija abajo** de la página, fuera del desplazamiento.
- **Iconos PNG**: ruta absoluta, p. ej. `/sdcard/astrion/icons/netflix.png`.
- **Colores**: `#RRGGBB` o `#AARRGGBB` (alfa primero). Excepción: `switch.on_color` solo acepta `#AARRGGBB` (ver 7.2).

Tipos registrados (21) y soporte en el editor:

| Tipo | Editor | Tipo | Editor |
|---|---|---|---|
| `title` | formulario (+ campos solo JSON) | `media_player` | formulario |
| `light` | formulario | `camera` | formulario |
| `switch` | formulario | `clock_weather` | formulario |
| `cover` | formulario | `vacuum` | formulario |
| `fan` | formulario | `plex` | formulario |
| `climate` | formulario | `speaker_group` | **solo JSON** |
| `source_select` | formulario | `monitor` | **solo JSON** |
| `select` | formulario | `picture_elements` | **solo JSON** |
| `button_grid` | formulario | `row` | **solo JSON** |
| `scene_grid` | formulario | `apple_tv_remote` | formulario |
| `tv_remote` | formulario | | |

## 6. Tarjetas sin formulario en el editor

En el editor en línea elige el tipo (grupo *Advanced (raw options)*) y pega **solo el objeto `options`** en *Options (raw JSON)*. Los ejemplos muestran la tarjeta completa: copia lo que está dentro de `"options"`.

### 6.1 `speaker_group` — grupo de altavoces (Sonos y similares)

Una fila por altavoz con: marca = miembro del grupo, barra de volumen arrastrable, silencio, vol−/vol+. La primera fila es el maestro.

| Opción | Tipo | Oblig. | Descripción |
|---|---|---|---|
| `master` | cadena | **sí** | `media_player` coordinador. Sin él la tarjeta no aparece |
| `name` | cadena | no | Nombre mostrado para el maestro |
| `speakers` | array | no | Lista `{ "entity_id", "name" }` de altavoces agrupables |

Comportamiento:

- Marcar → `media_player.join` sobre el maestro con `group_members: [altavoz]`, **inmediato**.
- Desmarcar → `media_player.unjoin` sobre el altavoz.
- El estado "en el grupo" se lee del atributo `group_members` **del altavoz** (debe contener al maestro).
- Volumen: `media_player.volume_set`, `volume_up`, `volume_down`, `volume_mute`.

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

### 6.2 `monitor` — lista de valores de sensores

Lista de solo lectura: nombre, valor actual, `unit_of_measurement`. Sin gráfico histórico. `unknown` / `unavailable` se muestran como `—`.

| Opción | Tipo | Oblig. | Descripción |
|---|---|---|---|
| `title` | cadena | no | Encabezado |
| `entities` | array | sí | `{ "entity_id", "name" }`; `name` opcional (por defecto: friendly name) |

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

### 6.3 `row` — tarjetas lado a lado

Contenedor horizontal: las tarjetas hijas tienen **igual anchura**. Cada hija es una tarjeta completa (`type` + `options`). Se admite cualquier tarjeta, incluida otra `row`.

| Opción | Tipo | Oblig. | Descripción |
|---|---|---|---|
| `cards` | array | sí | Tarjetas hijas |

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

> 💡 Dentro de una `row` usa preferiblemente `"layout": "vertical"` (light/cover/select): el espacio horizontal es reducido.

### 6.4 `picture_elements` — plano interactivo

Imagen de fondo con iconos colocados en **porcentaje**. También puede mostrar los objetivos de un radar mmWave y el robot aspirador.

| Opción | Tipo | Por defecto | Descripción |
|---|---|---|---|
| `image` | cadena | `/sdcard/astrion/floorplan.png` | Ruta PNG/JPG en el mando |
| `aspect` | número | `1.3` | Relación An/Al usada **hasta que la imagen carga** (después prevalece la real) |
| `elements` | array | `[]` | Iconos (tabla inferior) |
| `radar` | objeto | — | Capa radar (6.4.1) |
| `vacuum` | objeto | — | Capa aspirador (6.4.2) |

Campos de cada elemento:

| Campo | Tipo | Por defecto | Descripción |
|---|---|---|---|
| `left` / `top` | número | `50` | Centro del icono, % de anchura / altura |
| `entity_id` | cadena | — | Toque → **toggle** de la entidad; icono ámbar cuando está `on` |
| `service` | cadena | — | Solo si falta `entity_id`: `dominio.servicio` |
| `targets` | array | — | Entidades a las que aplicar `service`, una llamada por entidad |
| `icon` | cadena | bombilla | Único valor alternativo: `"power"` |

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

> 💡 Para hallar coordenadas: abre la imagen en un editor, toma X/Y en píxeles y calcula `left = X / anchura × 100`, `top = Y / altura × 100`.

#### 6.4.1 Capa `radar` (mmWave, p. ej. LD2450)

Dibuja hasta `targets` puntos numerados. Lee las entidades `<prefix>_<n>_x` y `<prefix>_<n>_y` con n = 1…targets. Si la entidad falta o no es numérica, el punto no aparece.

| Campo | Por defecto | Descripción |
|---|---|---|
| `prefix` | — (**oblig.**) | Prefijo de sensores, p. ej. `sensor.radar_soggiorno_target` |
| `targets` | `3` | Número de objetivos |
| `origin_left` / `origin_top` | `50` / `10` | Posición del sensor en la imagen (%) |
| `scale_x` / `scale_y` | `8` / `8` | % de imagen por **unidad** del valor del sensor |
| `scale_x_right` | = `scale_x` | Escala X para valores ≥ 0 (lado derecho) |
| `top_offset_left` | `0` | Desplazamiento vertical extra (%) del lado izquierdo |
| `rotation` | `0` | Rotación en grados |
| `flip_x` / `flip_y` | `false` | Refleja los ejes |
| `blend` | `"overlay"` | `none`, `multiply`, `screen`, `softlight`, `hardlight`, `difference`, `overlay` |

Fórmula aplicada (para entender la calibración): `left = origin_left + x_rotada × escala_x`, `top = origin_top + y_rotada × scale_y`. Resultado limitado a 0–100 %.

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

> 💡 Calibración: colócate en un punto conocido, observa dónde aparece el punto, corrige primero `origin_*`, luego `scale_*` y por último `rotation`/`flip_*`. La unidad de `scale_*` depende de cómo tu firmware expone x/y (mm, cm o m).

#### 6.4.2 Capa `vacuum`

Icono del robot situado **por habitación** (HA no expone X/Y del robot, solo la habitación actual). Toque → ventana con todos los controles de la tarjeta `vacuum` (7.12). Acepta las mismas opciones que `vacuum` y además:

| Campo | Descripción |
|---|---|
| `entity_id` | Entidad `vacuum.*` (**oblig.**) |
| `room_entity` | Sensor con el nombre de la habitación actual |
| `room_positions` | Mapa `"NombreHabitación": [left, top]` en % |
| `dock_position` | `[left, top]` de la base |

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

### 6.5 Campos solo-JSON de la tarjeta `title`

La tarjeta `title` tiene formulario, pero el título y subtítulo **pulsables** solo se configuran en JSON. Usa el mismo vocabulario de acciones que `scene_grid` (7.10) con prefijo `title_` o `subtitle_`:

| Campo | Acción |
|---|---|
| `title_entity_id` | Activar escena/script |
| `title_page` | Abrir la página |
| `title_activityId` (+ `title_hub`) | Iniciar Actividad Harmony |
| `title_harmonyDevice` + `title_harmonyCommand` (+ `title_hub`) | Comando IR vía Harmony |
| `title_irDevice` + `title_irCommand` | Comando IR local |
| `title_activity` | Iniciar Actividad compuesta |

Ejemplo — subtítulo "Ver todas →" que abre la página Luces:

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

## 7. Tarjetas con formulario

También pueden escribirse a mano. Se listan todas las opciones que lee el código.

### 7.1 `title`

| Opción | Por defecto | Descripción |
|---|---|---|
| `title` / `subtitle` | — | Textos |
| `alignment` | `"start"` | `start`, `center`, `end`, `justify` |
| `icon` | — | PNG antes del título (fuerza alineación a la izquierda de la fila del título) |
| `divider` | `false` | Línea que rellena la fila tras el título |
| `color` | tema | Color del título y de la línea |

### 7.2 `switch`

| Opción | Descripción |
|---|---|
| `entity_id` | Entidad conmutable (`switch.*`, `input_boolean.*`, …) |
| `name` | Etiqueta |
| `icon` | `heater`/`heat`, `fan`, `bulb`/`light`; cualquier otro → encendido |
| `on_color` | Color cuando está encendido. **Solo `#AARRGGBB`** (un valor `#RRGGBB` queda transparente) |

```json
{ "type": "switch", "options": { "entity_id": "switch.stufa", "name": "Stufa", "icon": "heater", "on_color": "#B3902828" } }
```

### 7.3 `light`

Toque = toggle; pulsación larga = ventana de detalle.

| Opción | Por defecto | Descripción |
|---|---|---|
| `entity_id`, `name` | — | |
| `layout` | `default` | `default`, `horizontal`, `vertical` |
| `use_light_color` | `false` | Icono teñido con el color RGB de la luz |
| `show_brightness` | `true` | Estado "N%" en lugar de "On" |
| `show_brightness_control` | ver nota | Control deslizante de brillo |
| `show_color_temp_control` | `false` | Control de temperatura de color |
| `show_color_control` | `false` | Muestras de color |
| `collapsible_controls` | `false` | Oculta los controles con la luz apagada |

> Si **ninguno** de los tres `show_*_control` está presente, solo aparece el control de brillo. En cuanto defines uno, solo aparecen los que están a `true`. Con varios controles, un botón de flecha los alterna.

```json
{ "type": "light", "options": { "entity_id": "light.soggiorno", "name": "Soggiorno", "layout": "horizontal", "use_light_color": true, "show_brightness_control": true, "show_color_control": true } }
```

### 7.4 `cover`

Pulsación larga = ventana con preajustes 25/50/75 %.

| Opción | Por defecto | Descripción |
|---|---|---|
| `entity_id`, `name`, `layout` | | como `light` |
| `show_buttons_control` | ver nota | Abrir/parar/cerrar |
| `show_position_control` | `false` | Control de posición |
| `show_tilt_position_control` | `false` | Control de inclinación |

> Misma lógica que `light`: sin ningún indicador aparecen los botones. Los controles deslizantes solo aparecen si la entidad expone el atributo correspondiente.

### 7.5 `fan`

| Opción | Por defecto | Descripción |
|---|---|---|
| `entity_id`, `name` | | |
| `style` | `auto` | `auto`, `simple`, `step`, `full` |
| `preset_modes` | de la entidad | Array, orden y filtro de preajustes |
| `step` | `percentage_step` de la entidad, luego `20` | Paso en % |
| `show_captions` | `true` | Rótulos sobre las filas de chips |

### 7.6 `climate`

| Opción | Por defecto | Descripción |
|---|---|---|
| `entity_id`, `name` | | |
| `step` | `1.0` | Paso de consigna. **Se ignora** si la entidad expone `target_temp_step` |
| `hvac_modes` / `fan_modes` / `swing_modes` | de la entidad | Arrays: orden y filtro de chips (`off` siempre excluido) |
| `hvac_mode_style` | `icons` | `icons` o `label` |
| `fan_mode_style` / `swing_mode_style` | `label` | `icons` o `label` |
| `show_captions` | `true` | Rótulos |

```json
{ "type": "climate", "options": { "entity_id": "climate.soggiorno", "name": "Clima", "step": 0.5, "hvac_modes": ["heat", "cool", "auto"], "fan_mode_style": "icons" } }
```

### 7.7 `select` y `source_select`

- `select`: para `input_select.*` / `select.*`. Opciones: `entity_id`, `name`, `icon_color`, `layout`.
- `source_select`: fuente de un `media_player` (`source_list`). Opciones: `entity_id`, `name`.

### 7.8 `media_player`

Compacto: toque = reproducir/pausa; pulsación larga = detalles.

| Opción | Por defecto | Descripción |
|---|---|---|
| `entity_id`, `name` | | |
| `variant` | compacto | `"full"` para la versión grande |
| `use_media_info` | `true` | Título/artista en lugar del nombre |
| `show_volume_level` | `false` | Añade "⸱ N%" |
| `media_controls` | `previous,play_pause,next` | Lista con comas: `on_off`, `shuffle`, `previous`, `play_pause`, `next`, `repeat` |
| `volume_controls` | `mute,buttons` | `mute`, `buttons`, `set` (deslizante) |
| `top_buttons` | — | Solo `full`: `{ "name", "service", "entity_id", "data" }` |

Los botones no soportados por la entidad (`supported_features`) se ocultan.

### 7.9 `button_grid`

| Opción | Por defecto | Descripción |
|---|---|---|
| `columns` | `3` | Columnas |
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

| Opción | Por defecto | Descripción |
|---|---|---|
| `columns` | `2` | Columnas |
| `layout` | cuadrícula | `"row"` = fila desplazable |
| `show_labels` | `true` | Nombre bajo el icono |
| `icon_fill` | `false` | Icono a toda la baldosa |
| `tile_height` | `74` (`120` con `icon_fill`) | Altura de baldosa (dp) |
| `scenes` | — | Baldosas (abajo) |

Campos de cada baldosa: `name`, `icon`, `color` y **una acción**: `entity_id` (escena/script), `page`, `activityId` (+`hub`), `harmonyDevice`+`harmonyCommand` (+`hub`), `irDevice`+`irCommand`, `activity`. Opcionales `track` + `room` (+`devices`) para rastrearla como Actividad. Con `activity` el campo `page` se ignora (define la página en la Actividad).

### 7.11 `camera`

| Opción | Por defecto | Descripción |
|---|---|---|
| `entity_id` | — (**oblig.**) | `camera.*` |
| `name` | friendly name | |
| `mode` | `stream` | `stream` (MJPEG) o `snapshot` |
| `snapshot_interval` | `2` | Segundos entre imágenes |
| `aspect` | `1.777` | Relación An/Al |
| `fit` | `cover` | `cover` o `contain` |

Toque = pantalla completa con zoom (pellizco 1×–8×).

### 7.12 `vacuum`

| Opción | Descripción |
|---|---|
| `entity_id`, `name` | |
| `map_image` | Entidad `image.*` del mapa |
| `map_rotation` | Rotación del mapa (grados) |
| `map_height` | Altura del mapa (dp) |
| `rooms` | `{ "name", "id" }` — id de segmento del mapa |
| `room_clean_action` | Por defecto Roborock/Xiaomi (`vacuum.send_command app_segment_clean`); si no, `{ "domain", "service", "parameter" }` |

### 7.13 `clock_weather`

Opciones: `entity_id` (`weather.*`), `time_format` (`12` por defecto o `24`), `forecast_rows` (por defecto `4`), `calendar_entity`.

### 7.14 `tv_remote`

| Opción | Descripción |
|---|---|
| `name` | Por defecto `TV` |
| `remote_entity` | `remote.*` (**oblig.**) — envía `remote.send_command` |
| `mute_entity` | Entidad alternativa para silencio |
| `media_entity` | `media_player` usado para lanzar apps |
| `commands` | Reasigna comandos: `up`, `down`, `left`, `right`, `center`, `back`, `home`, `menu`, `power` |
| `apps` | `{ "name", "app" }` o `{ "name", "service", "entity_id", "data" }` |

### 7.15 `apple_tv_remote`

Comandos enviados **directamente al hub Harmony**. Opciones: `deviceId` (id del dispositivo Harmony), `hub` (opcional).

### 7.16 `plex`

Opciones: `host`, `token`, `media_entity`, `play_entity`, `play_content_type` (`video`/`url`), `source` (por defecto `Plex`), `show_on_deck`, `show_recently_added_movies`, `show_recently_added_shows` (por defecto `true`), `items_per_row` (por defecto `12`).

> ⚠️ El token de Plex se guarda en claro en el archivo: no compartas `dashboard.json`.

## 8. Teclas físicas (`hotkeys` / `longHotkeys`)

Nombres `key` disponibles en el HA100:
`UP DOWN LEFT RIGHT CENTER`, `PAGE_UP PAGE_DOWN`, `VOLUME_UP VOLUME_DOWN MUTE`, `BACK HOME POWER VOICE`, `LIGHT CURTAIN SCENE AC`, `CUSTOM_1…CUSTOM_4`.

Campos (atención: aquí la entidad se llama **`entityId`**, en camelCase):

| Campo | Descripción |
|---|---|
| `key` | Tecla (**oblig.**) |
| `openOverlay` | `settings` o `activities` |
| `openCurrentActivityRoom` | Nombre de habitación: abre la página de la Actividad activa en ella |
| `page` | Abre la página |
| `harmonyDevice` + `harmonyCommand` / `harmonyActivity` (+ `hub`) | Harmony (`harmonyActivity: "-1"` = apagar) |
| `irDevice` + `irCommand` | IR local |
| `service` + `entityId` + `data` | Servicio HA |
| `track` + `room` (+ `devices`) | Rastrear como Actividad |

Usa **una acción por tecla**. Prioridad: `openOverlay` → `openCurrentActivityRoom` → `page` → … → `service`.

```json
{ "key": "CUSTOM_1", "service": "media_player.play_media", "entityId": "media_player.tv", "data": { "media_content_type": "app", "media_content_id": "com.netflix.ninja" } }
```

> 💡 Las flechas y el volumen se repiten al mantenerlos pulsados: no les asignes `longHotkeys`.

## 9. Harmony, IR local y Actividades

**`irDevices`** — dispositivos IR controlados por el emisor del propio mando:

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

En lugar de `commands`: `category` + `brand` + `model` (base de datos en `/sdcard/astrion/ir-database/`). Campo `target`: `"local"` (por defecto) o `{ "extender": "<id>" }`.

**`activities`** — escenarios multidispositivo:

| Campo | Descripción |
|---|---|
| `id`, `name`, `room` | `id` y `room` **obligatorios** |
| `icon`, `page` | Icono; página abierta al iniciar |
| `devices` | **Obligatorio, no vacío**: `deviceId`, `source` (`ir`/`harmony`/`ha`), `hub`, `powerOnCommand`, `powerOffCommand`, `inputCommand`, `powerOnFirst` (por defecto `true`), `powerOffOnExit` (por defecto `true`), `delayAfterMs` |
| `volumeDeviceId`, `volumeUpCommand`, `volumeDownCommand`, `muteCommand` | Control de volumen |

La configuración de Harmony (IP/ID del hub) se hace en la página `:8080`, no en el JSON.

## 10. Tema

Todas opcionales (valores por defecto entre paréntesis): `background` (#0E2229), `cardSurface` (#1B343D), `insetSurface` (#152B33), `controlBackground` (#2C4C58), `primaryText` (#E6F0F1), `mutedText` (#93AFB6), `iconTint` (#CBDCE0), `accent` (#6EA8FE), `accentSecondary` (#4C6EF5), `amber` (#FFC24B), `danger` (#E06767), `success` (#4CAF50).

## 11. Ejemplo completo

Cinco páginas: Luci (luces), Casa (plano + sensores), Musica (grupo de altavoces), Clima (climatización), Citofono (portero de apertura automática).

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

## 12. Solución de problemas

| Síntoma | Causa probable | Solución |
|---|---|---|
| Aparece el panel de ejemplo | JSON no válido o `pages` ausente/vacío | Valida el JSON (p. ej. `python -m json.tool dashboard.json`) |
| Una tarjeta no aparece | `type` erróneo, o falta la opción obligatoria (`master`, `remote_entity`, `entity_id`) | Revisa tipo y opciones |
| Campos perdidos tras editar en el editor | Guardado con el formulario simple | Usa "Options (raw JSON)" |
| `switch` encendido pero color invisible | `on_color` de 6 dígitos | Usa `#AARRGGBB` |
| Faltan iconos PNG | Ruta relativa o archivo no subido | Ruta absoluta `/sdcard/astrion/icons/…` |
| Tecla de servicio sin efecto | `entity_id` en lugar de `entityId` | En las teclas usa `entityId` |
| No aparecen puntos de radar | Entidades `<prefix>_<n>_x/_y` inexistentes o no numéricas | Comprueba los nombres en Herramientas para desarrolladores → Estados |
| La página de apertura automática no se abre | La entidad nunca pasa a `on` | Usa una entidad binaria o un `binary_sensor` template |
| Altavoz siempre "fuera del grupo" | La integración no expone `group_members` en el altavoz | Comprueba el atributo en HA |
