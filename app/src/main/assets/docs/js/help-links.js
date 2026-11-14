// ---- Help links to the wiki (1.5.7, by Xavier) ----------------------------------------
//
// A small "Help" link, always up to date because it points at the wiki itself:
//  - in each card form, to the wiki page of that card type (CARD_WIKI_PAGES);
//  - next to the section titles of the page (SECTION_WIKI_PAGES, by the title's data-i18n key).
// Nothing is copied into the app, so it weighs nothing and never goes stale. A link opens
// GitHub in a new tab (the remote's browser needs internet for that).
//
// Loads after cards.js: it wraps updateCardFormInputs() to add the link once a form is drawn.

const WIKI_BASE = 'https://github.com/dckiller51/astrion-custom-dashboard/wiki/';

/** Card type (the card dialog's value) -> wiki page. */
const CARD_WIKI_PAGES = {
  title: 'Card-Title',
  light: 'Card-Light',
  switch: 'Card-Switch',
  cover: 'Card-Cover',
  fan: 'Card-Fan',
  climate: 'Card-Climate',
  select: 'Card-Select',
  monitor: 'Card-Monitor',
  info: 'Card-Info',
  media_player: 'Card-MediaPlayer',
  source_select: 'Card-SourceSelect',
  speaker_group: 'Card-SpeakerGroup',
  camera: 'Card-Camera',
  plex: 'Card-Plex',
  xbox: 'Card-Xbox',
  zidoo: 'Card-Zidoo',
  media_remote: 'Card-MediaRemote',
  button_grid: 'Card-ButtonGrid',
  scene_grid: 'Card-SceneGrid',
  apple_tv_remote: 'Card-Remote#-apple-tv-remote-card-apple_tv_remote',
  tv_remote: 'Card-Remote',
  clock_weather: 'Card-ClockWeather',
  picture_elements: 'Card-PictureElements',
  vacuum: 'Card-Vacuum',
  row: 'Card-Row'
};

/** Section title (its data-i18n key) -> wiki page. */
const SECTION_WIKI_PAGES = {
  web_ui_section_hotkeys: 'Hotkeys',
  web_ui_2_activities: 'Activities',
  web_ui_3_theme_amp_colors: 'Builder',
  web_ui_4_dock_screen: 'Dock-Screen',
  web_ui_5_standby_amp_battery: 'Standby-and-Battery',
  // The remote's home page (devices.html, 1.5.7).
  web_dev_connection: 'Home-Assistant',
  web_dev_add_device_2: 'IR-Devices',
  web_dev_hub: 'Harmony',
  web_dev_extender: 'IR-Extenders',
  web_dev_add_an_apple_tv: 'Apple-TV',
  web_dev_add_an_android_tv: 'Android-TV',
  web_dev_add_a_samsung: 'Samsung-TV',
  web_dev_add_a_sky: 'Sky',
  web_dev_add_a_lgtv: 'LG-TV',
  web_dev_add_a_zidoo: 'Zidoo-and-Eversolo',
  web_dev_add_a_denon: 'Denon-Marantz',
  web_dev_backup_and_restore: 'Web-Page',
  web_dev_ir_database: 'IR-Devices',
  web_updates_heading: 'Updates-and-Beta',
  web_shots_heading: 'Web-Page',
  web_config_ring_heading: 'Find-My-Remote',
  web_ui_6_import_export_json: {
    en: 'GUIDE_dashboard_json_EN', fr: 'GUIDE_dashboard_json_FR', de: 'ANLEITUNG_dashboard_json_DE',
    it: 'GUIDA_dashboard_json_IT', es: 'GUIA_dashboard_json_ES'
  }
};

function wikiHelpLink(page, extraStyle) {
  const name = typeof page === 'object' ? (page[I18N.lang] || page.en) : page;
  const a = document.createElement('a');
  a.className = 'help-link';
  a.href = WIKI_BASE + name;
  a.target = '_blank';
  a.rel = 'noopener';
  a.textContent = '📖 ' + I18N.t('web_help_link');
  a.style.cssText = 'font-size:13px;font-weight:normal;margin-left:10px;color:#9fdfe6;text-decoration:none;' + (extraStyle || '');
  // Inside a <summary>, a click must follow the link, not fold the section.
  a.addEventListener('click', ev => ev.stopPropagation());
  return a;
}

/** Adds the help link at the top of the card form that is drawn. */
function injectCardHelp() {
  const type = (document.getElementById('cardTypeSelect') || {}).value;
  const box = document.getElementById('dynamicCardInputs');
  const page = CARD_WIKI_PAGES[type];
  if (!box || !page || box.querySelector('.help-link')) return;
  const row = document.createElement('div');
  row.style.cssText = 'margin:0 0 8px';
  row.appendChild(wikiHelpLink(page, 'margin-left:0'));
  box.insertBefore(row, box.firstChild);
}

/** A row of links to the wiki at the top of the remote's home page (1.5.7). */
function addDocumentationRow() {
  const title = document.querySelector('h1[data-i18n="web_dev_astrion_custom_dashboard"]');
  if (!title || document.getElementById('docLinks')) return;
  const row = document.createElement('div');
  row.id = 'docLinks';
  row.style.cssText = 'display:flex;flex-wrap:wrap;justify-content:center;gap:4px 14px;margin:4px 0 10px;font-size:13px';
  [['web_help_doc_wiki', 'Home'], ['web_help_doc_install', 'Installation'], ['web_help_doc_builder', 'Builder'],
    ['web_help_doc_recipes', 'Recipes'], ['web_help_doc_faq', 'FAQ']].forEach(([key, page]) => {
    const a = wikiHelpLink(page, 'margin-left:0');
    a.textContent = '📖 ' + I18N.t(key);
    row.appendChild(a);
  });
  title.insertAdjacentElement('afterend', row);
}

function addSectionHelpLinks() {
  addDocumentationRow();
  document.querySelectorAll('h2[data-i18n], h3[data-i18n]').forEach(h => {
    const page = SECTION_WIKI_PAGES[h.getAttribute('data-i18n')];
    const next = h.nextElementSibling;
    if (!page || (next && next.classList.contains('help-link'))) return;
    // Beside the title, never inside it: the translations rewrite a title's text, link and all.
    // In a fold's summary (a flex row) it sits next to it; elsewhere on the line below.
    const inSummary = h.parentNode.tagName === 'SUMMARY';
    h.insertAdjacentElement('afterend', wikiHelpLink(page, inSummary ? '' : 'display:inline-block;margin:-4px 0 10px'));
  });
}

if (typeof updateCardFormInputs === 'function') (function () {
  const draw = updateCardFormInputs;
  window.updateCardFormInputs = function () {
    const r = draw.apply(this, arguments);
    try { injectCardHelp(); } catch (e) { /* the help link is optional */ }
    return r;
  };
})();

if (window.I18N && I18N.ready) I18N.ready.then(addSectionHelpLinks); else document.addEventListener('DOMContentLoaded', addSectionHelpLinks);
