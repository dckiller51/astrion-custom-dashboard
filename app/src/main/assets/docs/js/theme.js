// ---- Theme & Colors ---------------------------------------------------------
//
// Global dashboard theme: 12 semantic color tokens stored in
// dashboardData.theme. The editor preview reflects changes live by setting
// CSS variables on the .remote-screen node (the preview surface), which the
// card renderer styles in styles.css consume via var(--token). On save,
// updateJsonOutput() serializes dashboardData.theme into dashboard.json
// automatically — no separate save step.

// Token definitions: key (JSON/CSS), labelKey (i18n key for the display
// label — resolved via I18N.t() at render time, never at script top level),
// default hex (matches the app's compiled-in ThemeConfig defaults so an
// untouched theme renders identically). `key` is the technical identifier
// used in dashboard.json and CSS variables; only labelKey/groupKey are
// user-facing.
const THEME_TOKENS = [
  { groupKey: 'web_theme_group_backgrounds', items: [
    { key: 'background',       labelKey: 'web_theme_label_window_background', default: '#0E2229' },
    { key: 'cardSurface',      labelKey: 'web_theme_label_card_surface',      default: '#1B343D' },
    { key: 'insetSurface',     labelKey: 'web_theme_label_inset',             default: '#152B33' },
    { key: 'controlBackground',labelKey: 'web_theme_label_control',           default: '#2C4C58' },
  ]},
  { groupKey: 'web_theme_group_text_icons', items: [
    { key: 'primaryText',      labelKey: 'web_theme_label_primary_text',      default: '#E6F0F1' },
    { key: 'mutedText',        labelKey: 'web_theme_label_muted_text',        default: '#93AFB6' },
    { key: 'iconTint',         labelKey: 'web_theme_label_icon_tint',         default: '#CBDCE0' },
  ]},
  { groupKey: 'web_theme_group_accents', items: [
    { key: 'accent',           labelKey: 'web_theme_label_accent_blue',       default: '#6EA8FE' },
    { key: 'accentSecondary',  labelKey: 'web_theme_label_accent_2',          default: '#4C6EF5' },
    { key: 'amber',            labelKey: 'web_theme_label_amber',             default: '#FFC24B' },
  ]},
  { groupKey: 'web_theme_group_status', items: [
    { key: 'danger',           labelKey: 'web_theme_label_danger',            default: '#E06767' },
    { key: 'success',          labelKey: 'web_theme_label_success',           default: '#4CAF50' },
  ]},
];

// All token keys flattened (used for defaults / normalization).
const THEME_KEYS = THEME_TOKENS.flatMap(g => g.items.map(t => t.key));

// Returns dashboardData.theme, backfilled with defaults for any missing token
// so renderers can always read a value.
function themeValues() {
  const t = dashboardData.theme || {};
  const out = {};
  THEME_TOKENS.forEach(g => g.items.forEach(tok => {
    out[tok.key] = t[tok.key] || tok.default;
  }));
  return out;
}

// Builds the Theme & Colors form (swatch + hex text field per token) into
// #themeBox. Called once from initEditor(); inputs persist across re-renders
// of the preview (only the preview re-renders, not this form).
function renderThemeForm() {
  const box = document.getElementById('themeBox');
  if (!box) return;
  const vals = themeValues();
  let html = '';
  html += `<div class="hint" style="margin-bottom:10px">${I18N.t('web_theme_hint')}</div>`;
  THEME_TOKENS.forEach(group => {
    html += `<div class="theme-group-label">${I18N.t(group.groupKey)}</div>`;
    group.items.forEach(tok => {
      const v = vals[tok.key];
      const isDefault = v.toLowerCase() === tok.default.toLowerCase();
      html += `
        <div class="theme-row">
          <input type="color" class="theme-swatch" id="themeSwatch_${tok.key}" value="${v}" onchange="onThemeInput('${tok.key}', this.value)" oninput="onThemeInput('${tok.key}', this.value, true)">
          <input type="text" class="theme-hex" id="themeHex_${tok.key}" value="${v}" placeholder="${tok.default}" onchange="onThemeHexInput('${tok.key}', this.value)" oninput="onThemeHexInput('${tok.key}', this.value, true)">
          <label class="theme-token-label" for="themeHex_${tok.key}">${I18N.t(tok.labelKey)}</label>
          <button type="button" class="theme-reset-btn${isDefault ? ' is-default' : ''}" title="${I18N.t('web_theme_reset_to_default', tok.default)}" onclick="resetThemeToken('${tok.key}')">↺</button>
        </div>`;
    });
  });
  html += `<div class="btn-row" style="margin-top:10px"><button class="secondary" onclick="resetTheme()">${I18N.t('web_theme_reset_all')}</button></div>`;
  box.innerHTML = html;
}

// Swatch -> value (always #RRGGBB). `live` = during drag (input event): update
// preview without re-rendering the whole preview (CSS vars only).
function onThemeInput(key, value, live) {
  const hexInput = document.getElementById('themeHex_' + key);
  if (hexInput) hexInput.value = value;
  dashboardData.theme = dashboardData.theme || {};
  dashboardData.theme[key] = value;
  updateResetBtnState(key, value);
  applyThemeToPreview();
  if (!live) updateJsonOutput();
}

// Hex text field -> normalize to #RRGGBB; keep the swatch in sync.
function onThemeHexInput(key, value, live) {
  let v = (value || '').trim();
  if (!v) v = (dashboardData.theme || {})[key] || (THEME_TOKENS.flatMap(g => g.items).find(t => t.key === key) || {}).default || '#000000';
  if (v[0] !== '#') v = '#' + v;
  if (/^#[0-9A-Fa-f]{6}$/.test(v)) {
    const swatch = document.getElementById('themeSwatch_' + key);
    if (swatch) swatch.value = v;
    dashboardData.theme = dashboardData.theme || {};
    dashboardData.theme[key] = v;
    updateResetBtnState(key, v);
    applyThemeToPreview();
    if (!live) updateJsonOutput();
  }
}

// Toggles the dimmed "is-default" state on a token's ↺ button — gives a visual
// cue that there's nothing to reset when the value already matches the default.
function updateResetBtnState(key, value) {
  const tok = THEME_TOKENS.flatMap(g => g.items).find(t => t.key === key);
  if (!tok) return;
  const btn = document.querySelector(`.theme-reset-btn[onclick="resetThemeToken('${key}')"]`);
  if (!btn) return;
  const isDefault = (value || '').toLowerCase() === tok.default.toLowerCase();
  btn.classList.toggle('is-default', isDefault);
}

function resetTheme() {
  dashboardData.theme = {};
  THEME_TOKENS.forEach(g => g.items.forEach(tok => {
    dashboardData.theme[tok.key] = tok.default;
  }));
  renderThemeForm();
  applyThemeToPreview();
  updateJsonOutput();
}

// Resets a single token to its built-in default and refreshes the swatch, hex
// field, preview, and JSON. Called by the per-row ↺ button.
function resetThemeToken(key) {
  const tok = THEME_TOKENS.flatMap(g => g.items).find(t => t.key === key);
  if (!tok) return;
  dashboardData.theme = dashboardData.theme || {};
  dashboardData.theme[key] = tok.default;
  const swatch = document.getElementById('themeSwatch_' + key);
  const hex = document.getElementById('themeHex_' + key);
  if (swatch) swatch.value = tok.default;
  if (hex) hex.value = tok.default;
  const btn = document.querySelector(`.theme-reset-btn[onclick="resetThemeToken('${key}')"]`);
  if (btn) btn.classList.add('is-default');
  applyThemeToPreview();
  updateJsonOutput();
}

// Sets CSS variables on every .remote-screen so the preview (in-frame and
// expanded modal — same node, moved around) picks them up. Called on every
// theme edit and after initEditor/renderPreview.
function applyThemeToPreview() {
  const vals = themeValues();
  const screens = document.querySelectorAll('.remote-screen');
  screens.forEach(el => {
    Object.keys(vals).forEach(key => {
      el.style.setProperty('--' + cssVarFor(key), vals[key]);
    });
  });
}

// Maps camelCase token keys to the CSS variable names used in styles.css.
function cssVarFor(key) {
  const map = {
    background: 'bg',
    cardSurface: 'card',
    insetSurface: 'inset',
    controlBackground: 'control',
    primaryText: 'text',
    mutedText: 'muted',
    iconTint: 'icon',
    accent: 'accent',
    accentSecondary: 'accent2',
    amber: 'amber',
    danger: 'danger',
    success: 'success',
  };
  return map[key] || key;
}

// ---- Reusable color picker field for card color overrides -------------------
//
// Same swatch + hex + ↺ reset control as the theme form, but for per-card
// color options (scene_grid item `color`, switch `on_color`). These support
// ARGB (#AARRGGBB) — the <input type="color"> swatch only handles #RRGGBB, so
// it shows the RGB part and preserves the alpha prefix from the hex field
// when the swatch changes. Reset clears to empty (= use the built-in default).

// Returns the #RRGGBB portion of an ARGB/RGB hex string for the swatch.
function rgbPartOf(hex) {
  if (!hex) return '#000000';
  let s = hex.trim().replace(/^#/, '');
  if (s.length === 8) s = s.slice(2);      // AARRGGBB -> RRGGBB
  if (s.length === 6) return '#' + s;
  return '#000000';
}

// Returns the alpha prefix (e.g. "66") from an #AARRGGBB string, or '' if
// opaque / no alpha.
function alphaPartOf(hex) {
  if (!hex) return '';
  let s = hex.trim().replace(/^#/, '');
  return s.length === 8 ? s.slice(0, 2) : '';
}

// Generates HTML for a color picker row. `id` is a base id (e.g. 'giColor');
// the swatch gets id `${id}Swatch`, the hex field gets `${id}Hex`.
// `currentValue` is the existing hex string (may be empty). `placeholder`
// is the default hex shown in the hex field + swatch when empty (e.g.
// "#66009688" — the built-in color used when the override is not set).
function colorFieldHtml(id, currentValue, placeholder) {
  const v = (currentValue || '').trim();
  const def = (placeholder || '').trim();
  // Swatch shows the configured color, or the default's RGB part when empty
  // — so an unconfigured field reads as "the default", not black.
  const swatchVal = v ? rgbPartOf(v) : (def ? rgbPartOf(def) : '#000000');
  const isEmpty = !v;
  return `
    <div class="theme-row theme-row-card">
      <input type="color" class="theme-swatch" id="${id}Swatch" value="${swatchVal}" onchange="onColorFieldInput('${id}', this.value, '${def}')" oninput="onColorFieldInput('${id}', this.value, '${def}', true)">
      <input type="text" class="theme-hex" id="${id}Hex" value="${v}" placeholder="${def}" onchange="onColorFieldHexInput('${id}', this.value, '${def}')" oninput="onColorFieldHexInput('${id}', this.value, '${def}', true)">
      <button type="button" class="theme-reset-btn${isEmpty ? ' is-default' : ''}" id="${id}Reset" title="${I18N.t('web_theme_reset_default')}" onclick="resetColorField('${id}', '${def}')">↺</button>
    </div>`;
}

// Swatch changed -> preserve alpha from the hex field, update hex field.
function onColorFieldInput(id, value, def, live) {
  const hexInput = document.getElementById(id + 'Hex');
  const existing = hexInput ? hexInput.value.trim() : '';
  const alpha = alphaPartOf(existing) || alphaPartOf(def);
  const newHex = '#' + (alpha ? alpha : '') + value.replace(/^#/, '').toLowerCase();
  if (hexInput) hexInput.value = newHex;
  updateColorFieldResetBtn(id, newHex);
  if (!live) renderPreview();
}

// Hex text field changed -> update swatch to match (if valid). When cleared,
// the swatch reverts to the default color (not black).
function onColorFieldHexInput(id, value, def, live) {
  let v = (value || '').trim();
  if (v && v[0] !== '#') v = '#' + v;
  if (!v || /^#[0-9A-Fa-f]{6}$/.test(v) || /^#[0-9A-Fa-f]{8}$/.test(v)) {
    const swatch = document.getElementById(id + 'Swatch');
    if (swatch) swatch.value = v ? rgbPartOf(v) : (def ? rgbPartOf(def) : '#000000');
    updateColorFieldResetBtn(id, v);
    if (!live) renderPreview();
  }
}

// Reset button -> clear the field (empty = use built-in default). Swatch
// reverts to the default color so it doesn't read as black.
function resetColorField(id, def) {
  const hexInput = document.getElementById(id + 'Hex');
  const swatch = document.getElementById(id + 'Swatch');
  if (hexInput) hexInput.value = '';
  if (swatch) swatch.value = def ? rgbPartOf(def) : '#000000';
  const btn = document.getElementById(id + 'Reset');
  if (btn) btn.classList.add('is-default');
  renderPreview();
}

// Toggles the dimmed "is-default" state on a card color field's ↺ button.
function updateColorFieldResetBtn(id, value) {
  const btn = document.getElementById(id + 'Reset');
  if (!btn) return;
  btn.classList.toggle('is-default', !value);
}

// Reads the current value of a card color field (the hex text field).
function colorFieldValue(id) {
  const el = document.getElementById(id + 'Hex');
  return el ? el.value.trim() : '';
}

// Sets the value of a card color field (updates both swatch and hex). When
// the value is empty, the swatch reverts to the default color (not black).
function setColorFieldValue(id, value) {
  const v = (value || '').trim();
  const hexInput = document.getElementById(id + 'Hex');
  const swatch = document.getElementById(id + 'Swatch');
  if (hexInput) hexInput.value = v;
  // Read the default from the placeholder so the swatch shows the right color.
  const def = hexInput ? (hexInput.placeholder || '').trim() : '';
  if (swatch) swatch.value = v ? rgbPartOf(v) : (def ? rgbPartOf(def) : '#000000');
  updateColorFieldResetBtn(id, v);
}