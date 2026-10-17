// ---- Shared i18n string table -----------------------------------------------
//
// Every user-facing string of the dashboard editor lives in one JSON file per
// language, i18n/<lang>.json (en, fr, it, es), next to this page. The language
// follows the browser; a key missing from that language falls back to English,
// and a key missing from English shows the key itself (logged once).
//
// Originally designed by Rob Johnston (ClemsonJeeper) in his feat/i18n-dashboard
// branch; ported onto the current editor.
//
// This file must load BEFORE every other editor script. Consumers either
// `await I18N.ready` before touching strings, or call I18N.t() inside code
// that already runs after page boot (event handlers, async fetch callbacks).

window.I18N = (() => {
  'use strict';

  const SUPPORTED = ['en', 'fr', 'it', 'es'];
  const browserLang = ((navigator.language || 'en').split('-')[0] || 'en').toLowerCase();
  const lang = SUPPORTED.includes(browserLang) ? browserLang : 'en';
  const warned = new Set();

  const load = (code) =>
    fetch('i18n/' + code + '.json')
      .then(res => (res.ok ? res.json() : Promise.reject(new Error('HTTP ' + res.status))));

  const i18n = {
    lang: lang,
    strings: {},
    ha: {},

    // Resolves once the string tables are loaded. Never rejects — a failed
    // load (e.g. opening index.html straight from disk) leaves t() returning
    // raw keys and applyUiI18n() keeping the HTML's English defaults,
    // instead of breaking the editor.
    ready: Promise.all([load('en'), lang === 'en' ? Promise.resolve({}) : load(lang).catch(() => ({}))])
      .then(([en, local]) => {
        i18n.strings = Object.assign({}, en, local);
        // Nested "ha" object: raw Home Assistant state values -> translated
        // labels, by category (hvac_mode, fan_mode, weather_condition, …).
        i18n.ha = {};
        const categories = new Set([...Object.keys(en.ha || {}), ...Object.keys(local.ha || {})]);
        categories.forEach(c => { i18n.ha[c] = Object.assign({}, (en.ha || {})[c], (local.ha || {})[c]); });
      })
      .catch(e => { console.log('i18n load failed', e); }),

    // t('key') → the translated string; t('key', a1, a2, …) substitutes
    // Android-style positional args (%1$s, %2$d, …) (%% — an escaped
    // percent — is collapsed too). Missing keys log once and fall back to
    // the key itself.
    t: (key, ...args) => {
      const value = i18n.strings[key];
      if (typeof value !== 'string') {
        if (!warned.has(key)) { warned.add(key); console.warn('Missing i18n key: ' + key); }
        return key;
      }
      if (!args.length) return value.replace(/%%/g, '%');
      return value
        .replace(/%(\d+)\$[sd]/g, (m, i) => {
          const arg = args[Number(i) - 1];
          return arg === undefined ? m : String(arg);
        })
        .replace(/%%/g, '%');
    },
  };

  return i18n;
})();

// Applies the loaded strings to the static markup. Runs after I18N.ready
// (gated in index.html's boot script); on a failed load the HTML's static
// English defaults stay untouched instead of being splattered with raw keys.
//
// Two markup conventions:
//   data-i18n="key"            → element's textContent is replaced
//   data-i18n-html="key"       → element's innerHTML is replaced (for text
//                                that carries <code>/<a>/<b> markup)
//   data-i18n-attr="attr:key"  → any attribute is replaced (placeholder,
//                                title, optgroup label…); several pairs can
//                                be chained: "placeholder:k1;title:k2"
// NOTE: data-i18n replaces the ENTIRE textContent — for elements that wrap
// child elements (a label around its input, an icon span…), put the
// attribute on a <span> around the text portion instead.
function applyUiI18n() {
  if (!Object.keys(I18N.strings).length) return;
  document.documentElement.lang = I18N.lang;

  const betaLabel = document.getElementById('betaToggleLabel');
  if (betaLabel) betaLabel.textContent = I18N.t('web_release_also_beta');
  document.querySelectorAll('button[data-hwkey]').forEach(btn => {
    const key = 'web_hwkey_' + btn.dataset.hwkey.toLowerCase();
    if (I18N.strings[key] !== undefined) btn.title = I18N.t(key);
  });
  // Physical-key dropdown: same names as the remote picture's tooltips (the
  // option's value stays the key id written into dashboard.json).
  document.querySelectorAll('#hkKey option').forEach(opt => {
    const key = opt.value;
    opt.value = key;
    if (typeof hwKeyLabel === 'function') opt.textContent = hwKeyLabel(key);
  });

  document.querySelectorAll('[data-i18n]').forEach(el => {
    const value = I18N.strings[el.dataset.i18n];
    if (typeof value === 'string') el.textContent = value;
  });
  document.querySelectorAll('[data-i18n-html]').forEach(el => {
    const value = I18N.strings[el.dataset.i18nHtml];
    if (typeof value === 'string') el.innerHTML = value;
  });
  document.querySelectorAll('[data-i18n-attr]').forEach(el => {
    el.dataset.i18nAttr.split(';').forEach(pair => {
      const [attr, key] = pair.split(':').map(s => s.trim());
      const value = I18N.strings[key];
      if (typeof value === 'string') el.setAttribute(attr, value);
    });
  });
}
