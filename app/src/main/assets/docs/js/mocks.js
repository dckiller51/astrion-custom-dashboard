const CLIMATE_MOCK = {
  hvac_modes: ['fan_only', 'heat', 'cool', 'heat_cool', 'dry'],
  fan_modes: ['auto', 'quiet', '1', '2', '3', '4', '5'],
  swing_modes: ['stop', 'swing'],
  state: 'cool',
  fan_mode: 'auto',
  swing_mode: 'stop',
  temperature: 24,
  current_temperature: 27,
};

// HA state-label lookups now go through the shared i18n table's nested "ha"
// object (I18N.ha.<category>[rawValue]) — the exact data the app loads from
// its generated assets/ha_labels/<lang>.json, so the preview shows the same
// labels the device does in the viewer's language.
function climateHvacLabel(m) { return I18N.ha.hvac_mode[m] || (m.charAt(0).toUpperCase() + m.slice(1)); }
function climateFanLabel(f) { return I18N.ha.fan_mode[f.toLowerCase()] || (f.charAt(0).toUpperCase() + f.slice(1)); }
function climateSwingLabel(s) { return I18N.ha.swing_mode[s.toLowerCase()] || (s.charAt(0).toUpperCase() + s.slice(1)); }

// Same MDI path data as MdiIcons.kt — see that file for the source/rationale.
const MDI = {
  power: 'M16.56,5.44L15.11,6.89C16.84,7.94 18,9.83 18,12A6,6 0 0,1 12,18A6,6 0 0,1 6,12C6,9.83 7.16,7.94 8.88,6.88L7.44,5.44C5.36,6.88 4,9.28 4,12A8,8 0 0,0 12,20A8,8 0 0,0 20,12C20,9.28 18.64,6.88 16.56,5.44M13,3H11V13H13',
  fire: 'M17.66,11.2C17.43,10.9 17.15,10.64 16.89,10.38C16.22,9.78 15.46,9.35 14.82,8.72C13.33,7.26 13,4.85 13.95,3C13,3.23 12.17,3.75 11.46,4.32C8.87,6.4 7.85,10.07 9.07,13.22C9.11,13.32 9.15,13.42 9.15,13.55C9.15,13.77 9,13.97 8.8,14.05C8.57,14.15 8.33,14.09 8.14,13.93C8.08,13.88 8.04,13.83 8,13.76C6.87,12.33 6.69,10.28 7.45,8.64C5.78,10 4.87,12.3 5,14.47C5.06,14.97 5.12,15.47 5.29,15.97C5.43,16.57 5.7,17.17 6,17.7C7.08,19.43 8.95,20.67 10.96,20.92C13.1,21.19 15.39,20.8 17.03,19.32C18.86,17.66 19.5,15 18.56,12.72L18.43,12.46C18.22,12 17.66,11.2 17.66,11.2M14.5,17.5C14.22,17.74 13.76,18 13.4,18.1C12.28,18.5 11.16,17.94 10.5,17.28C11.69,17 12.4,16.12 12.61,15.23C12.78,14.43 12.46,13.77 12.33,13C12.21,12.26 12.23,11.63 12.5,10.94C12.69,11.32 12.89,11.7 13.13,12C13.9,13 15.11,13.44 15.37,14.8C15.41,14.94 15.43,15.08 15.43,15.23C15.46,16.05 15.1,16.95 14.5,17.5H14.5Z',
  snowflake: 'M20.79,13.95L18.46,14.57L16.46,13.44V10.56L18.46,9.43L20.79,10.05L21.31,8.12L19.54,7.65L20,5.88L18.07,5.36L17.45,7.69L15.45,8.82L13,7.38V5.12L14.71,3.41L13.29,2L12,3.29L10.71,2L9.29,3.41L11,5.12V7.38L8.5,8.82L6.5,7.69L5.92,5.36L4,5.88L4.47,7.65L2.7,8.12L3.22,10.05L5.55,9.43L7.55,10.56V13.45L5.55,14.58L3.22,13.96L2.7,15.89L4.47,16.36L4,18.12L5.93,18.64L6.55,16.31L8.55,15.18L11,16.62V18.88L9.29,20.59L10.71,22L12,20.71L13.29,22L14.7,20.59L13,18.88V16.62L15.5,15.17L17.5,16.3L18.12,18.63L20,18.12L19.53,16.35L21.3,15.88L20.79,13.95M9.5,10.56L12,9.11L14.5,10.56V13.44L12,14.89L9.5,13.44V10.56Z',
  heatCool: 'M12.92,1.58L11.18,2.58L12.39,4.67L11.8,6.85L9,7.6L7.38,6L7.42,3.59L5.43,3.59L5.43,5.42L3.59,5.42L3.6,7.42L6,7.42L7.65,9.03L6.9,11.82L4.68,12.4L2.59,11.2L1.59,12.93L3.17,13.84L2.26,15.42L4,16.42L5.19,14.33L7.42,13.75L7.92,14.26L9.32,12.86L8.78,12.32L9.53,9.54L12.32,8.78L12.85,9.32L14.26,7.91L13.73,7.37L14.32,5.19L16.41,4L15.41,2.25L13.83,3.16L12.92,1.58M20.72,4L4,20.72L5.27,22L10.16,17.11C10.63,17.43 11.15,17.68 11.71,17.83C14.38,18.55 17.12,16.96 17.83,14.29C18.22,12.86 17.93,11.36 17.11,10.16L22,5.27L20.72,4M18.74,9C19.18,9.63 19.53,10.38 19.75,11.19C19.97,12 20.03,12.81 19.96,13.61L22.65,10.41L18.74,9M19.32,15.95C19,16.67 18.5,17.35 17.93,17.94C17.34,18.53 16.66,19 15.96,19.34L20.05,20.06L19.32,15.95M9,18.71L10.41,22.66L13.59,19.95C12.81,20 12,19.97 11.19,19.76C10.36,19.54 9.62,19.17 9,18.71Z',
  fan: 'M12,11A1,1 0 0,0 11,12A1,1 0 0,0 12,13A1,1 0 0,0 13,12A1,1 0 0,0 12,11M12.5,2C17,2 17.11,5.57 14.75,6.75C13.76,7.24 13.32,8.29 13.13,9.22C13.61,9.42 14.03,9.73 14.35,10.13C18.05,8.13 22.03,8.92 22.03,12.5C22.03,17 18.46,17.1 17.28,14.73C16.78,13.74 15.72,13.3 14.79,13.11C14.59,13.59 14.28,14 13.88,14.34C15.87,18.03 15.08,22 11.5,22C7,22 6.91,18.42 9.27,17.24C10.25,16.75 10.69,15.71 10.89,14.79C10.4,14.59 9.97,14.27 9.65,13.87C5.96,15.85 2,15.07 2,11.5C2,7 5.56,6.89 6.74,9.26C7.24,10.25 8.29,10.68 9.22,10.87C9.41,10.39 9.73,9.97 10.14,9.65C8.15,5.96 8.94,2 12.5,2Z',
  fanAuto: 'M12.5,2C8.93,2 8.14,5.96 10.13,9.65C9.72,9.97 9.4,10.39 9.21,10.87C8.28,10.68 7.23,10.25 6.73,9.26C5.56,6.89 2,7 2,11.5C2,15.07 5.95,15.85 9.64,13.87C9.96,14.27 10.39,14.59 10.88,14.79C10.68,15.71 10.24,16.75 9.26,17.24C6.9,18.42 7,22 11.5,22C12.31,22 13,21.78 13.5,21.41C13.19,20.67 13,19.86 13,19C13,17.59 13.5,16.3 14.3,15.28C14.17,14.97 14.03,14.65 13.86,14.34C14.26,14 14.57,13.59 14.77,13.11C15.26,13.21 15.78,13.39 16.25,13.67C17.07,13.25 18,13 19,13C20.05,13 21.03,13.27 21.89,13.74C21.95,13.37 22,12.96 22,12.5C22,8.92 18.03,8.13 14.33,10.13C14,9.73 13.59,9.42 13.11,9.22C13.3,8.29 13.74,7.24 14.73,6.75C17.09,5.57 17,2 12.5,2M12,11C12.54,11 13,11.45 13,12C13,12.55 12.54,13 12,13C11.43,13 11,12.55 11,12C11,11.45 11.43,11 12,11M18,15C16.89,15 16,15.9 16,17V23H18V21H20V23H22V17C22,15.9 21.1,15 20,15M18,17H20V19H18Z',
  fanQuiet: 'M13,19C13,17.59 13.5,16.3 14.3,15.28C14.17,14.97 14.03,14.65 13.86,14.34C14.26,14 14.57,13.59 14.77,13.11C15.26,13.21 15.78,13.39 16.25,13.67C17.07,13.25 18,13 19,13C20.05,13 21.03,13.27 21.89,13.74C21.95,13.37 22,12.96 22,12.5C22,8.92 18.03,8.13 14.33,10.13C14,9.73 13.59,9.42 13.11,9.22C13.3,8.29 13.74,7.24 14.73,6.75C17.09,5.57 17,2 12.5,2C8.93,2 8.14,5.96 10.13,9.65C9.72,9.97 9.4,10.39 9.21,10.87C8.28,10.68 7.23,10.25 6.73,9.26C5.56,6.89 2,7 2,11.5C2,15.07 5.95,15.85 9.64,13.87C9.96,14.27 10.39,14.59 10.88,14.79C10.68,15.71 10.24,16.75 9.26,17.24C6.9,18.42 7,22 11.5,22C12.31,22 13,21.78 13.5,21.41C13.19,20.67 13,19.86 13,19M12,13C11.43,13 11,12.55 11,12S11.43,11 12,11C12.54,11 13,11.45 13,12S12.54,13 12,13M19,19.17L22.17,16L23.59,17.41L19,22L14.41,17.41L15.83,16L19,19.17',
  fan1: 'M10,7V9H12V17H14V7H10Z',
  fan2: 'M9,7V9H13V11H11A2,2 0 0,0 9,13V17H11L15,17V15H11V13H13A2,2 0 0,0 15,11V9A2,2 0 0,0 13,7H9Z',
  fan3: 'M15,15V13.5A1.5,1.5 0 0,0 13.5,12A1.5,1.5 0 0,0 15,10.5V9C15,7.89 14.1,7 13,7H9V9H13V11H11V13H13V15H9V17H13A2,2 0 0,0 15,15',
  fan4: 'M9,7V13H13V17H15V7H13V11H11V7H9Z',
  fan5: 'M9,7V13H13V15H9V17H13A2,2 0 0,0 15,15V13A2,2 0 0,0 13,11H11V9H15V7H9Z',
  swingOff: 'M13,8.1V6.1C18.3,6.6 20,11.4 20,14H23L20.1,16.9L17.2,14H18C18,11.9 16.4,8.6 13,8.1M7.8,7.1L2.4,1.7L1.1,3L6.3,8.2C4.7,10 4,12.4 4,14H1L5,18L9,14H6C6,12.7 6.6,11 7.9,9.7L20.9,22.7L22.2,21.4L9.3,8.7L7.8,7.1M11,6.1L9.5,6.4L11,7.8V6.1Z',
  swingOn: 'M17.45,17.55L12,23L6.55,17.55L7.96,16.14L11,19.17V4.83L7.96,7.86L6.55,6.45L12,1L17.45,6.45L16.04,7.86L13,4.83V19.17L16.04,16.14L17.45,17.55Z',
  waterPercent: 'M12,3.25C12,3.25 6,10 6,14C6,17.32 8.69,20 12,20A6,6 0 0,0 18,14C18,10 12,3.25 12,3.25M14.47,9.97L15.53,11.03L9.53,17.03L8.47,15.97M9.75,10A1.25,1.25 0 0,1 11,11.25A1.25,1.25 0 0,1 9.75,12.5A1.25,1.25 0 0,1 8.5,11.25A1.25,1.25 0 0,1 9.75,10M14.25,14.5A1.25,1.25 0 0,1 15.5,15.75A1.25,1.25 0 0,1 14.25,17A1.25,1.25 0 0,1 13,15.75A1.25,1.25 0 0,1 14.25,14.5Z',
  video: 'M17,10.5V7A1,1 0 0,0 16,6H4A1,1 0 0,0 3,7V17A1,1 0 0,0 4,18H16A1,1 0 0,0 17,17V13.5L21,17.5V6.5L17,10.5Z',
  videoOff: 'M3.27,2L2,3.27L4.73,6H4A1,1 0 0,0 3,7V17A1,1 0 0,0 4,18H16A1,1 0 0,0 16.73,17.73L19.73,20.73L21,19.46M21,6.5L17,10.5V7A1,1 0 0,0 16,6H9.82L21,17.18V6.5Z',
};
function mdiSvg(path) { return `<svg viewBox="0 0 24 24" fill="currentColor"><path d="${path}"/></svg>`; }

// Mimics Home Assistant's default friendly_name generation: strip the domain
// prefix, replace underscores with spaces, and title-case each word. Used by
// the preview as a stand-in for the live friendly_name the app fetches from HA
// (which the static editor page can't reach).
function prettyEntityName(entityId) {
  if (!entityId || typeof entityId !== 'string') return null;
  const tail = entityId.includes('.') ? entityId.slice(entityId.indexOf('.') + 1) : entityId;
  return tail.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
}

// Builds a per-card "mock" object for the preview that's live where possible:
// starts from the static `baseMock` (so every field a renderer expects still
// exists), then overlays the matching HA entity's real `state`, `friendly_name`
// and `attributes` (which use the same HA attribute keys — brightness,
// current_position, hvac_mode, supported_features, … — the renderers already
// read off the mock). Returns `baseMock` unchanged when there's no live entity
// for `entityId` (no HA connection, unknown entity, or not device mode), so
// every renderer falls back to the example data it always showed.
function liveMock(entityId, baseMock) {
  const e = (typeof haEntity === 'function') ? haEntity(entityId) : null;
  if (!e) return baseMock;
  // Merge attributes over the mock, but skip null values — HA sends null for
  // attributes a climate/cover/etc. doesn't currently have (e.g. temperature
  // on a heat_cool thermostat with no setpoint), and those shouldn't clobber
  // the mock's sane defaults (otherwise the preview renders "null°").
  const merged = Object.assign({}, baseMock);
  if (e.attributes) {
    for (const k in e.attributes) {
      if (e.attributes[k] != null) merged[k] = e.attributes[k];
    }
  }
  merged.state = e.state;
  merged.friendly_name = e.friendly_name || baseMock.friendly_name;
  return merged;
}

function climateHvacIcon(m) {
  if (m === 'heat') return MDI.fire;
  if (m === 'cool') return MDI.snowflake;
  if (m === 'heat_cool' || m === 'auto') return MDI.heatCool;
  if (m === 'dry') return MDI.waterPercent;
  if (m === 'fan_only') return MDI.fan;
  return MDI.power;
}
function climateFanIcon(f) {
  const l = f.toLowerCase();
  if (l === 'auto') return MDI.fanAuto;
  if (l === 'quiet' || l === 'silent') return MDI.fanQuiet;
  if (['1', '2', '3', '4', '5'].includes(l)) return MDI['fan' + l];
  return MDI.fan;
}
function climateSwingIcon(s) {
  const l = s.toLowerCase();
  return (l === 'off' || l === 'stop') ? MDI.swingOff : MDI.swingOn;
}

// Mirrors ClimateCard.kt's balancedRows(): splits into rows of at most
// maxPerRow, but balanced (5 items @ max 4 -> 3+2, not a lopsided 4+1).
function climateBalancedRows(items, maxPerRow) {
  if (!items.length) return [];
  const rows = Math.ceil(items.length / maxPerRow);
  const perRow = Math.ceil(items.length / rows);
  const out = [];
  for (let i = 0; i < items.length; i += perRow) out.push(items.slice(i, i + perRow));
  return out;
}

function climateChipRowsHtml(items, selected, style, maxPerRow, labelFn, iconFn) {
  return climateBalancedRows(items, maxPerRow).map(row => `
    <div class="cc-row">
      ${row.map(v => {
        const isSel = (v || '').toLowerCase() === (selected || '').toLowerCase();
        const content = style === 'label' ? `<span>${labelFn(v)}</span>` : mdiSvg(iconFn(v));
        return `<div class="cc-chip ${isSel ? 'cc-chip-selected' : ''}">${content}</div>`;
      }).join('')}
    </div>`).join('');
}

// Fake example weather entity — real data (from weather.saint_martin_sur_le_pre)
// for the current condition/temp; the day-by-day forecast is synthetic since
// the real card fetches it via a live service call (weather.get_forecasts),
// not something visible in Dev Tools > States for a static preview. Forecast
// days are stored as ISO dates so the preview can render weekday names in
// the viewer's own locale (like ClockWeatherCard does on-device).
const WEATHER_MOCK = {
  friendly_name: 'Saint-Martin-sur-le-Pré',
  state: 'sunny',
  temperature: 21.1,
  forecast: (() => {
    const days = [];
    const d = new Date();
    for (let i = 1; i <= 4; i++) {
      const day = new Date(d.getFullYear(), d.getMonth(), d.getDate() + i);
      days.push({
        date: day.toISOString().slice(0, 10),
        condition: ['sunny', 'partlycloudy', 'cloudy', 'rainy'][i - 1],
        low: [12, 13, 11, 10][i - 1],
        high: [22, 20, 18, 16][i - 1],
      });
    }
    return days;
  })(),
};

// Weather condition names come from the shared i18n table's "ha" section —
// same translations the app's HaLabels.weatherCondition() uses.
function weatherConditionLabel(c) { return I18N.ha.weather_condition[c] || c; }

// Mirrors ClockWeatherCard.kt's emojiFor().
function weatherEmoji(c) {
  if (c === 'sunny' || c === 'clear') return '☀️';
  if (c === 'clear-night') return '🌙';
  if (c === 'partlycloudy') return '⛅';
  if (c === 'cloudy') return '☁️';
  if (c === 'fog') return '🌫️';
  if (c === 'rainy' || c === 'pouring') return '🌧️';
  if (c === 'lightning' || c === 'lightning-rainy') return '⛈️';
  if (c === 'snowy' || c === 'snowy-rainy') return '❄️';
  if (c === 'windy' || c === 'windy-variant') return '💨';
  if (c === 'hail') return '🌨️';
  return '🌡️';
}
function weatherTrim(n) { return Number.isInteger(n) ? String(n) : n.toFixed(1); }

// Fake example vacuum entity (real data, from
// vacuum.xiaomi_roborock_vacuum_s50_xiaomi_vacuum_roborock_s50).
const VACUUM_MOCK = {
  friendly_name: 'Xiaomi Roborock Vacuum S50',
  state: 'docked',
  fan_speed: 'high',
  fan_speed_list: ['min', 'medium', 'high', 'max', 'mop'],
};

// Vacuum state names come from the shared i18n table's "ha" section — same
// translations the app's HaLabels.vacuumState() uses.
function vacuumStateLabel(s) { return I18N.ha.vacuum_state[s] || vacuumPrettyLabel(s); }
// Mirrors VacuumCard.kt's prettyVacuumLabel() — used for fan_speed only,
// since (unlike vacuum_state) it isn't a fixed HA-wide value set.
function vacuumPrettyLabel(s) { return s.split('_').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' '); }

// Fake example input_select entity — mirrors the "video output" use case
// from the GitHub feature request this card answers, more useful as a
// builder preview than an arbitrary generic example would be.
const SELECT_MOCK = {
  friendly_name: 'Living Room Output',
  entity_id: 'input_select.video_output_living_room',
  state: 'TV',
  options: ['TV', 'Projector'],
};

const FAN_MOCK = {
  friendly_name: 'Mi Smart Standing Fan 2',
  state: 'on',
  preset_modes: ['off', 'Level 1', 'Level 2', 'Level 3', 'Level 4'],
  preset_mode: 'Level 4',
  oscillating: true,
  percentage: 100,
  percentage_step: 1,
};

// Mirrors FanCard.kt's oscillate-toggle label — reuses the swing_mode
// on/off translation, same concept (oscillation on/off) as ClimateCard's swing.
function fanOscillateLabel(on) { return I18N.ha.swing_mode[on ? 'on' : 'off'] || (on ? 'On' : 'Off'); }

// Fake example cover entity (real data, from cover.volet_chambre_noham).
const COVER_MOCK = {
  friendly_name: 'Volet Chambre Noham',
  state: 'open',
  is_closed: false,
  current_position: 100,
  current_tilt_position: 50,
  device_class: 'shutter',
};

// Cover state names come from the shared i18n table's "ha" section — same
// translations the app's HaLabels.coverState() uses (this is only the
// fallback for covers with no current_position — see coverPositionLabel below).
function coverStateLabel(s) { return I18N.ha.cover_state[s] || (s.charAt(0).toUpperCase() + s.slice(1)); }

// Mirrors CoverCard.kt's stateLabel logic: 100% -> "Open", 0% -> "Closed",
// anything in between -> "N% open"; falls back to the raw HA state when the
// entity has no current_position attribute at all. Uses the same shared
// string keys as the app (cover_open / cover_closed / cover_position_open).
function coverPositionLabel(position, rawState) {
  if (position === 100) return I18N.t('cover_open');
  if (position === 0) return I18N.t('cover_closed');
  if (position != null) return I18N.t('cover_position_open', position);
  return coverStateLabel(rawState);
}

// Same MDI path data as MdiIcons.kt's WindowShutterClosed/Open and
// CoverUp/CoverDown — see that file for the source/rationale. "stop" mirrors
// Material Icons' filled Stop glyph (a plain filled square), used as-is by
// CoverCard.kt for the middle button.
MDI.windowShutterClosed = 'M3,4H21V8H19V20H17V8H7V20H5V8H3V4M8,9H16V11H8V9M8,12H16V14H8V12M8,15H16V17H8V15M8,18H16V20H8V18Z';
MDI.windowShutterOpen = 'M3,4H21V8H19V20H17V8H7V20H5V8H3V4M8,9H16V11H8V9Z';
MDI.coverUp = 'M21,19A2,2 0 0,1 19,21H5A2,2 0 0,1 3,19V5A2,2 0 0,1 5,3H19C20.11,3 21,3.9 21,5V19M13,18V9.5L16.5,13L17.92,11.58L12,5.66L6.08,11.58L7.5,13L11,9.5V18H13Z';
MDI.coverDown = 'M3,5A2,2 0 0,1 5,3H19A2,2 0 0,1 21,5V19A2,2 0 0,1 19,21H5C3.89,21 3,20.1 3,19V5M11,6V14.5L7.5,11L6.08,12.42L12,18.34L17.92,12.42L16.5,11L13,14.5V6H11Z';
MDI.stop = 'M6,6H18V18H6V6Z';
// Approximates AndroidX's Icons.Filled.List glyph (a bulleted list) used by
// SelectCard.kt's icon — preview-only, not guaranteed pixel-identical.
MDI.list = 'M7,5H21V7H7V5M7,13V11H21V13H7M4,4.5A1.5,1.5 0 0,1 5.5,6A1.5,1.5 0 0,1 4,7.5A1.5,1.5 0 0,1 2.5,6A1.5,1.5 0 0,1 4,4.5M4,10.5A1.5,1.5 0 0,1 5.5,12A1.5,1.5 0 0,1 4,13.5A1.5,1.5 0 0,1 2.5,12A1.5,1.5 0 0,1 4,10.5M7,19V17H21V19H7M4,16.5A1.5,1.5 0 0,1 5.5,18A1.5,1.5 0 0,1 4,19.5A1.5,1.5 0 0,1 2.5,18A1.5,1.5 0 0,1 4,16.5Z';
// Standard Material "chevron-right" glyph — mirrors Icons.Filled.ChevronRight
// used by CoverCard.kt/LightCard.kt's CycleControlButton to switch between
// several enabled controls (buttons/position/tilt, brightness/colour-temp/colour).
MDI.chevronRight = 'M8.59,16.59L13.17,12L8.59,7.41L10,6L16,12L10,18L8.59,16.59Z';
// Same shape as MdiIcons.kt's LightbulbOn/LightbulbOff, used by LightCard.kt
// (swaps automatically with on/off, same as that card).
MDI.lightbulbOn = 'M12,2A7,7 0 0,0 5,9C5,11.38 6.19,13.47 8,14.74V17A1,1 0 0,0 9,18H15A1,1 0 0,0 16,17V14.74C17.81,13.47 19,11.38 19,9A7,7 0 0,0 12,2M9,21A1,1 0 0,0 10,22H14A1,1 0 0,0 15,21V20H9V21Z';
MDI.lightbulbOff = 'M12,2C9.76,2 7.78,3.05 6.5,4.68L16.31,14.5C17.94,13.21 19,11.24 19,9A7,7 0 0,0 12,2M3.28,4L2,5.27L5.04,8.3C5,8.53 5,8.76 5,9C5,11.38 6.19,13.47 8,14.74V17A1,1 0 0,0 9,18H14.73L18.73,22L20,20.72L3.28,4M9,20V21A1,1 0 0,0 10,22H14A1,1 0 0,0 15,21V20H9Z';

// Fake example light entity — colour-temp/xy profile (no rgb_color), the
// same shape a Hue-style colour-temp bulb reports, generic name (no local
// IP or personal entity id baked into a file this builder ships publicly).
const LIGHT_MOCK = {
  friendly_name: 'Living Room Lamp',
  state: 'on',
  brightness: 191, // 0..255, ≈75%
  supported_color_modes: ['color_temp', 'xy'],
  color_temp_kelvin: 3000, // used only to derive a warm preview tint below
  min_color_temp_kelvin: 2000,
  max_color_temp_kelvin: 6535,
  rgb_color: null,
};

// Approximates the on-screen warmth of a given colour temperature for the
// "use_light_color" preview, since this mock has no rgb_color of its own
// (color_temp/xy lights report kelvin, not RGB — LightCard.kt itself only
// tints from a real rgb_color, this is preview-only convenience).
function kelvinToPreviewRgb(k) {
  if (k <= 2700) return [255, 179, 102]; // warm
  if (k <= 4000) return [255, 214, 170]; // neutral-warm
  return [255, 249, 253]; // cool/neutral white
}

// Mirrors LightCard.kt's stateLabel logic: off -> "Off", 0% -> "Off",
// otherwise "N%" — only shown when showBrightness is on and the light
// reports a brightness; a plain toggle-only light just shows "On". Uses the
// same shared string keys as the app (light_state_on/off, light_brightness_pct).
function lightStateLabel(isOn, brightnessPct, showBrightness) {
  if (!isOn) return I18N.t('light_state_off');
  if (!showBrightness || brightnessPct == null) return I18N.t('light_state_on');
  if (brightnessPct <= 0) return I18N.t('light_state_off');
  return I18N.t('light_brightness_pct', brightnessPct);
}

// Same shapes as MdiIcons.kt's Play/Pause/SkipPrevious/SkipNext/VolumeHigh/VolumeOff,
// used by the media_player preview below — kept pixel-identical to the app.
MDI.play = 'M8,5.14V19.14L19,12.14L8,5.14Z';
MDI.pause = 'M14,19H18V5H14M6,19H10V5H6V19Z';
MDI.skipPrevious = 'M6,18V6H8V18H6M9.5,12L18,6V18L9.5,12Z';
MDI.skipNext = 'M16,18H18V6H16M6,18L14.5,12L6,6V18Z';
MDI.volumeHigh = 'M14,3.23V5.29C16.89,6.15 19,8.83 19,12C19,15.17 16.89,17.84 14,18.7V20.77C18,19.86 21,16.28 21,12C21,7.72 18,4.14 14,3.23M16.5,12C16.5,10.23 15.5,8.71 14,7.97V16C15.5,15.29 16.5,13.76 16.5,12M3,9V15H7L12,20V4L7,9H3Z';
MDI.volumeOff = 'M12,4L9.91,6.09L12,8.18M4.27,3L3,4.27L7.73,9H3V15H7L12,20V13.27L16.25,17.53C15.58,18.04 14.83,18.46 14,18.7V20.77C15.38,20.45 16.63,19.82 17.68,18.96L19.73,21L21,19.73L12,10.73M19,12C19,12.94 18.8,13.82 18.46,14.64L19.97,16.15C20.62,14.91 21,13.5 21,12C21,7.72 18,4.14 14,3.23V5.29C16.89,6.15 19,8.83 19,12M16.5,12C16.5,10.23 15.5,8.71 14,7.97V10.18L16.45,12.63C16.5,12.43 16.5,12.21 16.5,12Z';
MDI.power = 'M16.56,5.44L15.11,6.89C16.84,7.94 18,9.83 18,12A6,6 0 0,1 12,18A6,6 0 0,1 6,12C6,9.83 7.16,7.94 8.88,6.88L7.44,5.44C5.36,6.88 4,9.28 4,12A8,8 0 0,0 12,20A8,8 0 0,0 20,12C20,9.28 18.64,6.88 16.56,5.44M13,3H11V13H13';
// Same shapes as MdiIcons.kt's Repeat (single "off" glyph, tinted rather
// than swapped when active) and Shuffle.
MDI.repeat = 'M2,5.27L3.28,4L20,20.72L18.73,22L15.73,19H7V22L3,18L7,14V17H13.73L7,10.27V11H5V8.27L2,5.27M17,13H19V17.18L17,15.18V13M17,5V2L21,6L17,10V7H8.82L6.82,5H17Z';
MDI.shuffle = 'M16,4.5V7H5V9H16V11.5L19.5,8M16,12.5V15H5V17H16V19.5L19.5,16';
// Same shapes as MdiIcons.kt's CastOff/Cast — the compact tile's (and full
// page's) fallback avatar when there's no entity_picture, off vs on.
MDI.castOff = 'M1.6,1.27L0.25,2.75L1.41,3.8C1.16,4.13 1,4.55 1,5V8H3V5.23L18.2,19H14V21H20.41L22.31,22.72L23.65,21.24M6.5,3L8.7,5H21V16.14L23,17.95V5C23,3.89 22.1,3 21,3M1,10V12A9,9 0 0,1 10,21H12C12,14.92 7.08,10 1,10M1,14V16A5,5 0 0,1 6,21H8A7,7 0 0,0 1,14M1,18V21H4A3,3 0 0,0 1,18Z';
MDI.cast = 'M21,3H3C1.89,3 1,3.89 1,5V8H3V5H21V19H14V21H21A2,2 0 0,0 23,19V5C23,3.89 22.1,3 21,3M1,10V12A9,9 0 0,1 10,21H12C12,14.92 7.07,10 1,10M19,7H5V8.63C8.96,9.91 12.09,13.04 13.37,17H19M1,14V16A5,5 0 0,1 6,21H8A7,7 0 0,0 1,14M1,18V21H4A3,3 0 0,0 1,18Z';

// Fake example media_player entity — mirrors a real Nest Hub Max playing
// YouTube (generic title/artwork, no personal entity id or local proxy URL
// baked into a file this builder ships publicly).
const MEDIA_MOCK = {
  friendly_name: 'Living Room Speaker',
  state: 'playing', // off | idle | paused | playing | buffering | on
  supported_features: 152511, // pause+seek+volume_set+volume_mute+prev+next+turn_on+turn_off+play_media+stop+play+browse_media
  volume_level: 0.4,
  is_volume_muted: false,
  media_title: 'Example Song Title',
  media_artist: 'Example Artist',
  app_name: 'YouTube',
  media_position: 1,
  media_duration: 183,
  shuffle: false,
  repeat: 'off',
};

const CAMERA_MOCK = {
  friendly_name: 'Front Door',
  state: 'streaming', // idle | streaming
  // entity_picture normally looks like /api/camera_proxy/camera.x?token=…; the
  // static editor can't reach it, so the preview only uses this to know the
  // entity is a camera. On a real device the preview fetches a live frame from
  // /camera-snapshot instead.
  entity_picture: null,
};

// Fake example Plex library — the plex card talks straight to a Plex server
// (not an HA entity), so the static editor page has no live data to show at
// all; these generic titles just make the preview read like a populated row
// instead of empty placeholder boxes. Real posters/titles load on-device.
const PLEX_MOCK = {
  on_deck: [
    { title: 'Example Movie Night', subtitle: '42 min left' },
    { title: 'Example Series', subtitle: 'S2E4 · 12 min left' },
    { title: 'Example Documentary', subtitle: '18 min left' },
    { title: 'Example Feature', subtitle: '55 min left' },
  ],
  recently_added_movies: [
    { title: 'Example Feature Film', subtitle: '2024' },
    { title: 'Example Action Movie', subtitle: '2023' },
    { title: 'Example Comedy', subtitle: '2024' },
    { title: 'Example Drama', subtitle: '2022' },
  ],
  recently_added_shows: [
    { title: 'Example Series', subtitle: 'S3E1 · Example Episode' },
    { title: 'Example Show', subtitle: 'S1E6 · Example Episode' },
    { title: 'Example Sitcom', subtitle: 'S5E12 · Example Episode' },
    { title: 'Example Drama Series', subtitle: 'S2E3 · Example Episode' },
  ],
};


// Mirrors MediaPlayerCard.kt's Feature bitmask check (EntityState.supports).
function mediaSupports(mock, bit) {
  return ((mock.supported_features || 0) & bit) === bit;
}
const MEDIA_FEATURE = {
  PAUSE: 1, SEEK: 2, VOLUME_SET: 4, VOLUME_MUTE: 8, PREVIOUS_TRACK: 16, NEXT_TRACK: 32,
  TURN_ON: 128, TURN_OFF: 256, VOLUME_STEP: 1024, STOP: 4096, PLAY: 16384,
  SHUFFLE_SET: 32768, REPEAT_SET: 262144,
};

// Mirrors MediaPlayerCard.kt's mediaStateLabel() — same shared string keys
// (media_state_*), so the preview shows the same label the device does, in
// the viewer's language.
function mediaStateLabel(state) {
  switch (state) {
    case 'playing': return I18N.t('media_state_playing');
    case 'paused': return I18N.t('media_state_paused');
    case 'idle': return I18N.t('media_state_idle');
    case 'buffering': return I18N.t('media_state_buffering');
    case 'on': return I18N.t('media_state_on');
    case 'off': default: return I18N.t('media_state_off');
  }
}

// Mirrors MediaPlayerCard.kt's computeMediaButtons() — same control set,
// same supported_features gating, in the same order.
function mediaComputeButtons(mock, controls) {
  const out = [];
  if (mock.state === 'off') {
    if (controls.includes('on_off') && mediaSupports(mock, MEDIA_FEATURE.TURN_ON)) out.push({ icon: MDI.power, action: 'turn_on' });
    return out;
  }
  if (controls.includes('on_off') && mediaSupports(mock, MEDIA_FEATURE.TURN_OFF)) out.push({ icon: MDI.power, action: 'turn_off' });
  const activeish = ['playing', 'paused', 'idle', 'on'].includes(mock.state);
  if (activeish && controls.includes('shuffle') && mediaSupports(mock, MEDIA_FEATURE.SHUFFLE_SET)) {
    out.push({ icon: MDI.shuffle, action: 'shuffle_set', active: mock.shuffle === true });
  }
  if (activeish && controls.includes('previous') && mediaSupports(mock, MEDIA_FEATURE.PREVIOUS_TRACK)) {
    out.push({ icon: MDI.skipPrevious, action: 'media_previous_track' });
  }
  if (controls.includes('play_pause')) {
    if (mock.state === 'playing' && mediaSupports(mock, MEDIA_FEATURE.PAUSE)) out.push({ icon: MDI.pause, action: 'media_pause' });
    else if (mock.state === 'playing' && mediaSupports(mock, MEDIA_FEATURE.STOP)) out.push({ icon: MDI.pause, action: 'media_stop' });
    else if (['paused', 'idle', 'on'].includes(mock.state) && mediaSupports(mock, MEDIA_FEATURE.PLAY)) out.push({ icon: MDI.play, action: 'media_play' });
  }
  if (activeish && controls.includes('next') && mediaSupports(mock, MEDIA_FEATURE.NEXT_TRACK)) {
    out.push({ icon: MDI.skipNext, action: 'media_next_track' });
  }
  if (activeish && controls.includes('repeat') && mediaSupports(mock, MEDIA_FEATURE.REPEAT_SET)) {
    out.push({ icon: MDI.repeat, action: 'repeat_set', active: (mock.repeat || 'off') !== 'off' });
  }
  return out;
}

// Mirrors MediaPlayerCard.kt's computeVolumeButtons().
function mediaComputeVolumeButtons(mock, controls) {
  if (!mock || mock.state === 'off') return [];
  const out = [];
  if (controls.includes('mute') && mediaSupports(mock, MEDIA_FEATURE.VOLUME_MUTE)) {
    out.push({ icon: mock.is_volume_muted ? MDI.volumeOff : MDI.volumeHigh, action: 'volume_mute' });
  }
  if (controls.includes('buttons') && mediaSupports(mock, MEDIA_FEATURE.VOLUME_STEP)) {
    out.push({ icon: MDI.volumeOff, action: 'volume_down' });
    out.push({ icon: MDI.volumeHigh, action: 'volume_up' });
  }
  return out;
}