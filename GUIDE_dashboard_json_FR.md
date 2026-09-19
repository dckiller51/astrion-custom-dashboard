# Astrion Custom Dashboard — Guide complet de `dashboard.json`

> Langue : [Italiano](GUIDA_dashboard_json_IT.md) · [English](GUIDE_dashboard_json_EN.md) · **Français** · [Español](GUIA_dashboard_json_ES.md) · [Deutsch](ANLEITUNG_dashboard_json_DE.md)
>
> Référence : dépôt [`dckiller51/astrion-custom-dashboard`](https://github.com/dckiller51/astrion-custom-dashboard), branche `main`, commit `d73ec52` (app 1.1.4). Chaque option décrite a été vérifiée dans le code Kotlin (`config/DashboardLoader.kt`, `config/AppConfig.kt`, `cards/impl/*.kt`). Les versions ultérieures peuvent changer.
>
> Tous les exemples utilisent des **entités fictives** (`light.soggiorno`, `media_player.salotto`, …), identiques dans toutes les langues : remplacez-les par les vôtres.

## Sommaire

1. [Ce que c'est (et ce que ce n'est pas)](#1-ce-que-cest-et-ce-que-ce-nest-pas)
2. [Emplacement du fichier et envoi](#2-emplacement-du-fichier-et-envoi)
3. [Structure racine](#3-structure-racine)
4. [Pages](#4-pages)
5. [Cartes : règles communes](#5-cartes--règles-communes)
6. [Cartes sans formulaire dans l'éditeur](#6-cartes-sans-formulaire-dans-léditeur)
7. [Cartes avec formulaire](#7-cartes-avec-formulaire)
8. [Touches physiques (`hotkeys` / `longHotkeys`)](#8-touches-physiques-hotkeys--longhotkeys)
9. [Harmony, IR local et Activités](#9-harmony-ir-local-et-activités)
10. [Thème](#10-thème)
11. [Exemple complet](#11-exemple-complet)
12. [Dépannage](#12-dépannage)

---

## 1. Ce que c'est (et ce que ce n'est pas)

Astrion Custom Dashboard est une **application Android** qui remplace le lanceur de la télécommande **Sanytron Astrion HA100**. Elle communique avec Home Assistant via WebSocket.

- Ce **n'est pas** un tableau de bord Lovelace et elle **ne** s'installe **pas** via HACS.
- Les « cartes » sont des composants natifs de l'app, pas des cartes Lovelace : la syntaxe est du **JSON**, pas du YAML.
- Beaucoup de noms d'options imitent les cartes Mushroom, mais seules les options listées ici sont lues par l'app.

## 2. Emplacement du fichier et envoi

| Élément | Chemin sur la télécommande |
|---|---|
| Configuration | `/sdcard/astrion/dashboard.json` |
| Icônes PNG | `/sdcard/astrion/icons/` |
| Base IR (optionnelle) | `/sdcard/astrion/ir-database/` |

Méthodes de modification :

1. **Éditeur en ligne** — <https://dckiller51.github.io/astrion-custom-dashboard/> : formulaires pour pages, cartes et touches ; téléchargez le JSON généré.
2. **Page locale** — `http://<ip-télécommande>:8080` (adresse affichée dans le panneau Paramètres) : envoi/téléchargement de `dashboard.json`, envoi d'icônes. L'envoi recharge le tableau de bord **sans redémarrage**.
3. **adb** — `adb push dashboard.json /sdcard/astrion/dashboard.json`, puis rouvrez l'app.

> ⚠️ Si le JSON est invalide, l'app **ne plante pas** : elle charge sa disposition intégrée et affiche `dashboard.json invalid (…) — using built-in defaults`. Téléchargez toujours une sauvegarde avant l'envoi.

> ⚠️ Dans l'éditeur en ligne, rouvrir une carte et l'enregistrer via le formulaire simple **réécrit ses options de zéro** : les champs JSON uniquement (section 6) sont perdus. Pour ces cartes, utilisez toujours le champ « Options (raw JSON) ».

## 3. Structure racine

La racine est un objet JSON. Un **tableau de cartes** est aussi accepté : il devient une page unique nommée `Main`.

| Clé | Type | Obligatoire | Défaut | Description |
|---|---|---|---|---|
| `pages` | tableau | **oui** (non vide) | — | Pages, de gauche à droite |
| `startPage` | entier | non | `0` | Index (à partir de 0) de la page au démarrage |
| `hotkeys` | tableau | non | `[]` | Touches physiques, appui court |
| `longHotkeys` | tableau | non | `[]` | Touches physiques, appui long (~500 ms) |
| `irDevices` | tableau | non | `[]` | Appareils IR locaux (section 9) |
| `activities` | tableau | non | `[]` | Activités composées (section 9) |
| `theme` | objet | non | thème sombre | Couleurs (section 10) |

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

| Clé | Type | Défaut | Description |
|---|---|---|---|
| `name` | chaîne | `"Page"` | Nom ; utilisé pour la navigation (insensible à la casse) |
| `cards` | tableau | `[]` | Cartes de la page, de haut en bas |
| `hotkeys` / `longHotkeys` | tableau | `[]` | Touches actives sur cette page seulement ; elles **remplacent** les globales ayant le même `key` |
| `parent` | chaîne | — | Page parente : la touche `parentKey` y revient |
| `parentKey` | chaîne | `"BACK"` | Touche physique qui revient à `parent` |
| `linkedPage` | chaîne | — | Page ouverte par un balayage **vers le haut** sur l'indicateur de pages |
| `hiddenUnlessActivity` | chaîne | — | Id d'Activité : le point de la page n'apparaît que si cette Activité est active (la page reste accessible) |
| `openWhenEntity` | chaîne | — | Entité HA : quand elle passe à `on`, la page s'ouvre seule ; quand elle quitte `on`, elle se ferme |

> ℹ️ L'éditeur en ligne peut aussi écrire `openWhenState` / `closeWhenState`, mais dans le commit analysé `DashboardLoader.kt` **ne les lit pas** : l'état d'ouverture est toujours `on`. Pour une entité avec d'autres états, créez un `binary_sensor` template dans HA.

Exemple — page interphone qui s'ouvre à la sonnette et se ferme avec BACK :

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

## 5. Cartes : règles communes

Chaque carte a cette forme :

```json
{ "type": "light", "options": { "entity_id": "light.soggiorno" } }
```

- `type` est **obligatoire** ; `options` est facultatif.
- Un `type` non enregistré ne provoque pas d'erreur d'analyse : la carte n'est simplement pas dessinée.
- `"pin": "bottom"` dans `options` (toute carte) l'**épingle en bas** de la page, hors défilement.
- **Icônes PNG** : chemin absolu, ex. `/sdcard/astrion/icons/netflix.png`.
- **Couleurs** : `#RRGGBB` ou `#AARRGGBB` (alpha en premier). Exception : `switch.on_color` n'accepte que `#AARRGGBB` (voir 7.2).

Types enregistrés (21) et prise en charge par l'éditeur :

| Type | Éditeur | Type | Éditeur |
|---|---|---|---|
| `title` | formulaire (+ champs JSON) | `media_player` | formulaire |
| `light` | formulaire | `camera` | formulaire |
| `switch` | formulaire | `clock_weather` | formulaire |
| `cover` | formulaire | `vacuum` | formulaire |
| `fan` | formulaire | `plex` | formulaire |
| `climate` | formulaire | `speaker_group` | **JSON uniquement** |
| `source_select` | formulaire | `monitor` | **JSON uniquement** |
| `select` | formulaire | `picture_elements` | **JSON uniquement** |
| `button_grid` | formulaire | `row` | **JSON uniquement** |
| `scene_grid` | formulaire | `apple_tv_remote` | formulaire |
| `tv_remote` | formulaire | | |

## 6. Cartes sans formulaire dans l'éditeur

Dans l'éditeur en ligne, choisissez le type (groupe *Advanced (raw options)*) et collez **uniquement l'objet `options`** dans *Options (raw JSON)*. Les exemples ci-dessous montrent la carte entière : copiez ce qui se trouve dans `"options"`.

### 6.1 `speaker_group` — groupe d'enceintes (Sonos et similaires)

Une ligne par enceinte : coche = membre du groupe, barre de volume déplaçable, sourdine, vol−/vol+. La première ligne est le maître.

| Option | Type | Oblig. | Description |
|---|---|---|---|
| `master` | chaîne | **oui** | `media_player` coordinateur. Sans lui, la carte n'apparaît pas |
| `name` | chaîne | non | Nom affiché pour le maître |
| `speakers` | tableau | non | Liste `{ "entity_id", "name" }` des enceintes regroupables |

Comportement :

- Cocher → `media_player.join` sur le maître avec `group_members: [enceinte]`, **immédiatement**.
- Décocher → `media_player.unjoin` sur l'enceinte.
- L'état « dans le groupe » est lu dans l'attribut `group_members` **de l'enceinte** (il doit contenir le maître).
- Volume : `media_player.volume_set`, `volume_up`, `volume_down`, `volume_mute`.

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

### 6.2 `monitor` — liste de valeurs de capteurs

Liste en lecture seule : nom, valeur actuelle, `unit_of_measurement`. Pas d'historique. `unknown` / `unavailable` s'affichent `—`.

| Option | Type | Oblig. | Description |
|---|---|---|---|
| `title` | chaîne | non | En-tête |
| `entities` | tableau | oui | `{ "entity_id", "name" }` ; `name` facultatif (défaut : friendly name) |

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

### 6.3 `row` — cartes côte à côte

Conteneur horizontal : les cartes enfants ont une **largeur égale**. Chaque enfant est une carte complète (`type` + `options`). Toute carte est permise, y compris une autre `row`.

| Option | Type | Oblig. | Description |
|---|---|---|---|
| `cards` | tableau | oui | Cartes enfants |

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

> 💡 Dans une `row`, préférez `"layout": "vertical"` (light/cover/select) : l'espace horizontal est réduit.

### 6.4 `picture_elements` — plan interactif

Image de fond avec des icônes placées en **pourcentage**. Peut aussi afficher les cibles d'un radar mmWave et le robot aspirateur.

| Option | Type | Défaut | Description |
|---|---|---|---|
| `image` | chaîne | `/sdcard/astrion/floorplan.png` | Chemin PNG/JPG sur la télécommande |
| `aspect` | nombre | `1.3` | Ratio L/H utilisé **jusqu'au chargement de l'image** (ensuite le réel l'emporte) |
| `elements` | tableau | `[]` | Icônes (tableau ci-dessous) |
| `radar` | objet | — | Surcouche radar (6.4.1) |
| `vacuum` | objet | — | Surcouche aspirateur (6.4.2) |

Champs de chaque élément :

| Champ | Type | Défaut | Description |
|---|---|---|---|
| `left` / `top` | nombre | `50` | Centre de l'icône, % de largeur / hauteur |
| `entity_id` | chaîne | — | Appui → **toggle** de l'entité ; icône ambre si `on` |
| `service` | chaîne | — | Utilisé seulement sans `entity_id` : `domaine.service` |
| `targets` | tableau | — | Entités auxquelles appliquer `service`, un appel par entité |
| `icon` | chaîne | ampoule | Seule autre valeur : `"power"` |

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

> 💡 Trouver les coordonnées : ouvrez l'image dans un éditeur, relevez X/Y en pixels et calculez `left = X / largeur × 100`, `top = Y / hauteur × 100`.

#### 6.4.1 Surcouche `radar` (mmWave, ex. LD2450)

Dessine jusqu'à `targets` points numérotés. Lit les entités `<prefix>_<n>_x` et `<prefix>_<n>_y` avec n = 1…targets. Si l'entité manque ou n'est pas numérique, le point n'apparaît pas.

| Champ | Défaut | Description |
|---|---|---|
| `prefix` | — (**oblig.**) | Préfixe capteurs, ex. `sensor.radar_soggiorno_target` |
| `targets` | `3` | Nombre de cibles |
| `origin_left` / `origin_top` | `50` / `10` | Position du capteur sur l'image (%) |
| `scale_x` / `scale_y` | `8` / `8` | % d'image par **unité** de la valeur du capteur |
| `scale_x_right` | = `scale_x` | Échelle X pour les valeurs ≥ 0 (côté droit) |
| `top_offset_left` | `0` | Décalage vertical supplémentaire (%) côté gauche |
| `rotation` | `0` | Rotation en degrés |
| `flip_x` / `flip_y` | `false` | Miroir des axes |
| `blend` | `"overlay"` | `none`, `multiply`, `screen`, `softlight`, `hardlight`, `difference`, `overlay` |

Formule appliquée (pour comprendre l'étalonnage) : `left = origin_left + x_pivoté × échelle_x`, `top = origin_top + y_pivoté × scale_y`. Résultat borné à 0–100 %.

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

> 💡 Étalonnage : placez-vous à un point connu, regardez où apparaît le point, corrigez d'abord `origin_*`, puis `scale_*`, enfin `rotation`/`flip_*`. L'unité de `scale_*` dépend de la façon dont votre firmware expose x/y (mm, cm ou m).

#### 6.4.2 Surcouche `vacuum`

Icône du robot placée **par pièce** (HA n'expose pas X/Y du robot, seulement la pièce actuelle). Appui → popup avec toutes les commandes de la carte `vacuum` (7.12). Accepte les mêmes options que `vacuum`, plus :

| Champ | Description |
|---|---|
| `entity_id` | Entité `vacuum.*` (**oblig.**) |
| `room_entity` | Capteur contenant le nom de la pièce actuelle |
| `room_positions` | Dictionnaire `"NomPièce": [left, top]` en % |
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

### 6.5 Champs JSON uniquement de la carte `title`

La carte `title` a un formulaire, mais le titre et le sous-titre **cliquables** ne se configurent qu'en JSON. Même vocabulaire d'actions que `scene_grid` (7.10), préfixé par `title_` ou `subtitle_` :

| Champ | Action |
|---|---|
| `title_entity_id` | Activer scène/script |
| `title_page` | Ouvrir la page |
| `title_activityId` (+ `title_hub`) | Lancer une Activité Harmony |
| `title_harmonyDevice` + `title_harmonyCommand` (+ `title_hub`) | Commande IR via Harmony |
| `title_irDevice` + `title_irCommand` | Commande IR locale |
| `title_activity` | Lancer une Activité composée |

Exemple — sous-titre « Tout voir → » qui ouvre la page Lumières :

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

## 7. Cartes avec formulaire

Elles peuvent aussi s'écrire à la main. Toutes les options lues par le code sont listées.

### 7.1 `title`

| Option | Défaut | Description |
|---|---|---|
| `title` / `subtitle` | — | Textes |
| `alignment` | `"start"` | `start`, `center`, `end`, `justify` |
| `icon` | — | PNG avant le titre (force l'alignement à gauche de la ligne titre) |
| `divider` | `false` | Ligne remplissant la rangée après le titre |
| `color` | thème | Couleur du titre et de la ligne |

### 7.2 `switch`

| Option | Description |
|---|---|
| `entity_id` | Entité commutable (`switch.*`, `input_boolean.*`, …) |
| `name` | Libellé |
| `icon` | `heater`/`heat`, `fan`, `bulb`/`light` ; sinon → alimentation |
| `on_color` | Couleur à l'état allumé. **`#AARRGGBB` uniquement** (une valeur `#RRGGBB` devient transparente) |

```json
{ "type": "switch", "options": { "entity_id": "switch.stufa", "name": "Stufa", "icon": "heater", "on_color": "#B3902828" } }
```

### 7.3 `light`

Appui = toggle ; appui long = popup de détail.

| Option | Défaut | Description |
|---|---|---|
| `entity_id`, `name` | — | |
| `layout` | `default` | `default`, `horizontal`, `vertical` |
| `use_light_color` | `false` | Icône teintée de la couleur RGB de la lampe |
| `show_brightness` | `true` | État « N% » au lieu de « On » |
| `show_brightness_control` | voir note | Curseur de luminosité |
| `show_color_temp_control` | `false` | Curseur de température de couleur |
| `show_color_control` | `false` | Pastilles de couleur |
| `collapsible_controls` | `false` | Masque les commandes quand la lampe est éteinte |

> Si **aucun** des trois `show_*_control` n'est présent, seul le curseur de luminosité s'affiche. Dès que vous en définissez un, seuls ceux à `true` s'affichent. Avec plusieurs commandes, un bouton flèche les fait défiler.

```json
{ "type": "light", "options": { "entity_id": "light.soggiorno", "name": "Soggiorno", "layout": "horizontal", "use_light_color": true, "show_brightness_control": true, "show_color_control": true } }
```

### 7.4 `cover`

Appui long = popup avec préréglages 25/50/75 %.

| Option | Défaut | Description |
|---|---|---|
| `entity_id`, `name`, `layout` | | comme `light` |
| `show_buttons_control` | voir note | Ouvrir/stop/fermer |
| `show_position_control` | `false` | Curseur de position |
| `show_tilt_position_control` | `false` | Curseur d'inclinaison |

> Même logique que `light` : sans aucun indicateur, les boutons s'affichent. Les curseurs n'apparaissent que si l'entité expose l'attribut correspondant.

### 7.5 `fan`

| Option | Défaut | Description |
|---|---|---|
| `entity_id`, `name` | | |
| `style` | `auto` | `auto`, `simple`, `step`, `full` |
| `preset_modes` | de l'entité | Tableau, ordre et filtre des préréglages |
| `step` | `percentage_step` de l'entité, puis `20` | Pas en % |
| `show_captions` | `true` | Légendes au-dessus des rangées de puces |

### 7.6 `climate`

| Option | Défaut | Description |
|---|---|---|
| `entity_id`, `name` | | |
| `step` | `1.0` | Pas de consigne. **Ignoré** si l'entité expose `target_temp_step` |
| `hvac_modes` / `fan_modes` / `swing_modes` | de l'entité | Tableaux : ordre et filtre des puces (`off` toujours exclu) |
| `hvac_mode_style` | `icons` | `icons` ou `label` |
| `fan_mode_style` / `swing_mode_style` | `label` | `icons` ou `label` |
| `show_captions` | `true` | Légendes |

```json
{ "type": "climate", "options": { "entity_id": "climate.soggiorno", "name": "Clima", "step": 0.5, "hvac_modes": ["heat", "cool", "auto"], "fan_mode_style": "icons" } }
```

### 7.7 `select` et `source_select`

- `select` : pour `input_select.*` / `select.*`. Options : `entity_id`, `name`, `icon_color`, `layout`.
- `source_select` : source d'un `media_player` (`source_list`). Options : `entity_id`, `name`.

### 7.8 `media_player`

Compact : appui = lecture/pause ; appui long = détails.

| Option | Défaut | Description |
|---|---|---|
| `entity_id`, `name` | | |
| `variant` | compact | `"full"` pour la grande version |
| `use_media_info` | `true` | Titre/artiste au lieu du nom |
| `show_volume_level` | `false` | Ajoute « ⸱ N% » |
| `media_controls` | `previous,play_pause,next` | Liste à virgules : `on_off`, `shuffle`, `previous`, `play_pause`, `next`, `repeat` |
| `volume_controls` | `mute,buttons` | `mute`, `buttons`, `set` (curseur) |
| `top_buttons` | — | `full` uniquement : `{ "name", "service", "entity_id", "data" }` |

Les boutons non pris en charge par l'entité (`supported_features`) sont masqués.

### 7.9 `button_grid`

| Option | Défaut | Description |
|---|---|---|
| `columns` | `3` | Colonnes |
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

| Option | Défaut | Description |
|---|---|---|
| `columns` | `2` | Colonnes |
| `layout` | grille | `"row"` = rangée défilante |
| `show_labels` | `true` | Nom sous l'icône |
| `icon_fill` | `false` | Icône pleine tuile |
| `tile_height` | `74` (`120` avec `icon_fill`) | Hauteur de tuile (dp) |
| `scenes` | — | Tuiles (ci-dessous) |

Champs de chaque tuile : `name`, `icon`, `color` et **une action** : `entity_id` (scène/script), `page`, `activityId` (+`hub`), `harmonyDevice`+`harmonyCommand` (+`hub`), `irDevice`+`irCommand`, `activity`. Facultatifs : `track` + `room` (+`devices`) pour la suivre comme Activité. Avec `activity`, le champ `page` est ignoré (définissez la page sur l'Activité).

### 7.11 `camera`

| Option | Défaut | Description |
|---|---|---|
| `entity_id` | — (**oblig.**) | `camera.*` |
| `name` | friendly name | |
| `mode` | `stream` | `stream` (MJPEG) ou `snapshot` |
| `snapshot_interval` | `2` | Secondes entre images |
| `aspect` | `1.777` | Ratio L/H |
| `fit` | `cover` | `cover` ou `contain` |

Appui = plein écran avec zoom (pincement 1×–8×).

### 7.12 `vacuum`

| Option | Description |
|---|---|
| `entity_id`, `name` | |
| `map_image` | Entité `image.*` de la carte |
| `map_rotation` | Rotation de la carte (degrés) |
| `map_height` | Hauteur de la carte (dp) |
| `rooms` | `{ "name", "id" }` — id de segment de la carte |
| `room_clean_action` | Défaut Roborock/Xiaomi (`vacuum.send_command app_segment_clean`) ; sinon `{ "domain", "service", "parameter" }` |

### 7.13 `clock_weather`

Options : `entity_id` (`weather.*`), `time_format` (`12` par défaut ou `24`), `forecast_rows` (défaut `4`), `calendar_entity`.

### 7.14 `tv_remote`

| Option | Description |
|---|---|
| `name` | Défaut `TV` |
| `remote_entity` | `remote.*` (**oblig.**) — envoie `remote.send_command` |
| `mute_entity` | Entité alternative pour la sourdine |
| `media_entity` | `media_player` utilisé pour lancer les apps |
| `commands` | Remappe les commandes : `up`, `down`, `left`, `right`, `center`, `back`, `home`, `menu`, `power` |
| `apps` | `{ "name", "app" }` ou `{ "name", "service", "entity_id", "data" }` |

### 7.15 `apple_tv_remote`

Commandes envoyées **directement au hub Harmony**. Options : `deviceId` (id appareil Harmony), `hub` (facultatif).

### 7.16 `plex`

Options : `host`, `token`, `media_entity`, `play_entity`, `play_content_type` (`video`/`url`), `source` (défaut `Plex`), `show_on_deck`, `show_recently_added_movies`, `show_recently_added_shows` (défaut `true`), `items_per_row` (défaut `12`).

> ⚠️ Le jeton Plex est en clair dans le fichier : ne partagez pas `dashboard.json`.

## 8. Touches physiques (`hotkeys` / `longHotkeys`)

Noms `key` disponibles sur la HA100 :
`UP DOWN LEFT RIGHT CENTER`, `PAGE_UP PAGE_DOWN`, `VOLUME_UP VOLUME_DOWN MUTE`, `BACK HOME POWER VOICE`, `LIGHT CURTAIN SCENE AC`, `CUSTOM_1…CUSTOM_4`.

Champs (attention : ici l'entité s'appelle **`entityId`**, en camelCase) :

| Champ | Description |
|---|---|
| `key` | Touche (**oblig.**) |
| `openOverlay` | `settings` ou `activities` |
| `openCurrentActivityRoom` | Nom de pièce : ouvre la page de l'Activité active dans cette pièce |
| `page` | Ouvre la page |
| `harmonyDevice` + `harmonyCommand` / `harmonyActivity` (+ `hub`) | Harmony (`harmonyActivity: "-1"` = éteindre) |
| `irDevice` + `irCommand` | IR local |
| `service` + `entityId` + `data` | Service HA |
| `track` + `room` (+ `devices`) | Suivre comme Activité |

Utilisez **une action par touche**. Priorité : `openOverlay` → `openCurrentActivityRoom` → `page` → … → `service`.

```json
{ "key": "CUSTOM_1", "service": "media_player.play_media", "entityId": "media_player.tv", "data": { "media_content_type": "app", "media_content_id": "com.netflix.ninja" } }
```

> 💡 Les flèches et le volume se répètent en maintenant l'appui : ne leur attribuez pas de `longHotkeys`.

## 9. Harmony, IR local et Activités

**`irDevices`** — appareils IR pilotés par l'émetteur de la télécommande :

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

À la place de `commands` : `category` + `brand` + `model` (base dans `/sdcard/astrion/ir-database/`). Champ `target` : `"local"` (défaut) ou `{ "extender": "<id>" }`.

**`activities`** — scénarios multi-appareils :

| Champ | Description |
|---|---|
| `id`, `name`, `room` | `id` et `room` **obligatoires** |
| `icon`, `page` | Icône ; page ouverte au lancement |
| `devices` | **Obligatoire, non vide** : `deviceId`, `source` (`ir`/`harmony`/`ha`), `hub`, `powerOnCommand`, `powerOffCommand`, `inputCommand`, `powerOnFirst` (défaut `true`), `powerOffOnExit` (défaut `true`), `delayAfterMs` |
| `volumeDeviceId`, `volumeUpCommand`, `volumeDownCommand`, `muteCommand` | Contrôle du volume |

La configuration Harmony (IP/ID du hub) se fait sur la page `:8080`, pas dans le JSON.

## 10. Thème

Toutes facultatives (défauts entre parenthèses) : `background` (#0E2229), `cardSurface` (#1B343D), `insetSurface` (#152B33), `controlBackground` (#2C4C58), `primaryText` (#E6F0F1), `mutedText` (#93AFB6), `iconTint` (#CBDCE0), `accent` (#6EA8FE), `accentSecondary` (#4C6EF5), `amber` (#FFC24B), `danger` (#E06767), `success` (#4CAF50).

## 11. Exemple complet

Cinq pages : Luci (lumières), Casa (plan + capteurs), Musica (groupe d'enceintes), Clima (climatisation), Citofono (interphone à ouverture automatique).

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

## 12. Dépannage

| Symptôme | Cause probable | Solution |
|---|---|---|
| Le tableau de bord d'exemple apparaît | JSON invalide ou `pages` absent/vide | Validez le JSON (ex. `python -m json.tool dashboard.json`) |
| Une carte n'apparaît pas | `type` erroné, ou option obligatoire manquante (`master`, `remote_entity`, `entity_id`) | Vérifiez le type et les options |
| Champs perdus après modification dans l'éditeur | Enregistrement via le formulaire simple | Utilisez « Options (raw JSON) » |
| `switch` allumé mais couleur invisible | `on_color` à 6 chiffres | Utilisez `#AARRGGBB` |
| Icônes PNG absentes | Chemin relatif ou fichier non envoyé | Chemin absolu `/sdcard/astrion/icons/…` |
| Touche de service sans effet | `entity_id` au lieu de `entityId` | Dans les touches, utilisez `entityId` |
| Pas de points radar | Entités `<prefix>_<n>_x/_y` absentes ou non numériques | Vérifiez les noms dans Outils de développement → États |
| La page à ouverture auto ne s'ouvre pas | L'entité ne passe jamais à `on` | Utilisez une entité binaire ou un `binary_sensor` template |
| Enceinte toujours « hors groupe » | L'intégration n'expose pas `group_members` sur l'enceinte | Vérifiez l'attribut dans HA |
