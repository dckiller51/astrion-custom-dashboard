// ---- Card gallery (1.4.9) ----------------------------------------------------
//
// "Add card" opens a gallery instead of a bare dropdown: the card types as tiles with a line on
// what each does, grouped by use, with a search, and a "For a device" tab that starts from a
// device of the Devices page's catalog (dashboardData.haDevices) and offers the cards that suit
// it, already filled in (a Zidoo's media player in full view with its apps, its TV remote…).
//
// Picking a tile sets #cardTypeSelect and runs the usual updateCardFormInputs() / fillCardForm()
// (cards.js), so every card's own form is unchanged. Editing a card skips the gallery.
//
// Uses: I18N, haStates, dashboardData, cxEsc (cards-extra.js), updateCardFormInputs, fillCardForm.

const GALLERY_CATEGORIES = ['device', 'home', 'media', 'grids', 'layout', 'advanced'];

const GALLERY_TYPES = {
  light: { cat: 'home', icon: 'bulb' },
  switch: { cat: 'home', icon: 'power' },
  cover: { cat: 'home', icon: 'blind' },
  fan: { cat: 'home', icon: 'fan' },
  climate: { cat: 'home', icon: 'thermo' },
  select: { cat: 'home', icon: 'list' },
  vacuum: { cat: 'home', icon: 'vacuum' },
  clock_weather: { cat: 'home', icon: 'sun' },
  monitor: { cat: 'home', icon: 'chart' },
  media_player: { cat: 'media', icon: 'play' },
  tv_remote: { cat: 'media', icon: 'remote' },
  apple_tv_remote: { cat: 'media', icon: 'box' },
  source_select: { cat: 'media', icon: 'apps' },
  speaker_group: { cat: 'media', icon: 'speaker' },
  camera: { cat: 'media', icon: 'camera' },
  plex: { cat: 'media', icon: 'film' },
  xbox: { cat: 'media', icon: 'game' },
  zidoo: { cat: 'media', icon: 'player' },
  button_grid: { cat: 'grids', icon: 'apps' },
  scene_grid: { cat: 'grids', icon: 'spark' },
  title: { cat: 'layout', icon: 'text' },
  row: { cat: 'layout', icon: 'columns' },
  picture_elements: { cat: 'layout', icon: 'image' },
  custom: { cat: 'advanced', icon: 'code' }
};

const GALLERY_ICONS = {
  bulb: '<path d="M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.8.8 1 1.5 1 2.5h6c0-1 .2-1.7 1-2.5A6 6 0 0 0 12 3z"/>',
  power: '<path d="M12 3v9"/><path d="M6.3 7.5a8 8 0 1 0 11.4 0"/>',
  blind: '<rect x="4" y="3" width="16" height="18" rx="1"/><path d="M4 8h16M4 13h16M12 21v-3"/>',
  fan: '<circle cx="12" cy="12" r="2"/><path d="M12 10c0-4 1-7 4-7s2 5-2 7M14 12c4 0 7 1 7 4s-5 2-7-2M12 14c0 4-1 7-4 7s-2-5 2-7M10 12c-4 0-7-1-7-4s5-2 7 2"/>',
  thermo: '<path d="M10 14V5a2 2 0 0 1 4 0v9a4 4 0 1 1-4 0z"/>',
  list: '<path d="M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01"/>',
  vacuum: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="3"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  chart: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>',
  play: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M10 9l5 3-5 3z"/>',
  remote: '<rect x="7" y="2" width="10" height="20" rx="3"/><circle cx="12" cy="8" r="2"/><path d="M10 14h.01M14 14h.01M10 17h.01M14 17h.01"/>',
  box: '<rect x="4" y="8" width="16" height="9" rx="2"/><path d="M9 20h6"/>',
  tv: '<rect x="3" y="5" width="18" height="12" rx="2"/><path d="M8 21h8"/>',
  player: '<rect x="3" y="7" width="18" height="10" rx="2"/><path d="M10 10l4 2-4 2z"/>',
  audio: '<rect x="6" y="3" width="12" height="18" rx="2"/><circle cx="12" cy="14" r="3"/><circle cx="12" cy="7" r="1"/>',
  apps: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
  speaker: '<path d="M11 5L6 9H3v6h3l5 4z"/><path d="M15.5 8.5a5 5 0 0 1 0 7M18.5 5.5a9 9 0 0 1 0 13"/>',
  camera: '<path d="M3 7h4l2-3h6l2 3h4v13H3z"/><circle cx="12" cy="13" r="4"/>',
  film: '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M7 3v18M17 3v18M3 8h4M3 16h4M17 8h4M17 16h4"/>',
  game: '<path d="M6 9h12a4 4 0 0 1 0 8l-2-2H8l-2 2a4 4 0 0 1 0-8z"/><path d="M8 12v2M7 13h2M15 13h.01M17 12h.01"/>',
  spark: '<path d="M12 3l2 6 6 2-6 2-2 6-2-6-6-2 6-2z"/>',
  text: '<path d="M4 6h16M4 12h10M4 18h7"/>',
  columns: '<rect x="3" y="4" width="8" height="16" rx="1"/><rect x="13" y="4" width="8" height="16" rx="1"/>',
  image: '<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="2"/><path d="M21 17l-5-5-9 8"/>',
  code: '<path d="M8 8l-4 4 4 4M16 8l4 4-4 4"/>',
  device: '<rect x="2" y="6" width="14" height="10" rx="2"/><rect x="18" y="8" width="4" height="10" rx="1"/>'
};

function galleryIcon(name, size) {
  const s = size || 26;
  return `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${GALLERY_ICONS[name] || GALLERY_ICONS.apps}</svg>`;
}

/** The card type's name as the dropdown shows it, without its "(type)" suffix. */
function galleryTypeLabel(type) {
  const opt = document.querySelector(`#cardTypeSelect option[value="${type}"]`);
  return opt ? opt.textContent.replace(/\s*\([a-z_]+\)\s*$/, '').replace(/…$/, '') : type;
}

/** The types the dialog offers now (the row card can't go inside a row). */
function galleryAvailableTypes() {
  return Object.keys(GALLERY_TYPES).filter(t => {
    const opt = document.querySelector(`#cardTypeSelect option[value="${t}"]`);
    return opt && !opt.disabled;
  });
}

// ---- open / close ------------------------------------------------------------

function showCardGallery() {
  const g = window._gallery || (window._gallery = {});
  g.query = '';
  g.device = null;
  if (!g.tab) g.tab = galleryDevices().length ? 'device' : 'home';
  document.getElementById('cardGallery').style.display = '';
  document.getElementById('cardFormBox').style.display = 'none';
  document.querySelector('#cardEditorModal .ha-dialog').classList.add('gallery-mode');
  renderCardGallery(true);
}

/** Back to the plain form: [fromGallery] keeps a link back to the gallery and hides the dropdown. */
function hideCardGallery(fromGallery) {
  document.getElementById('cardGallery').style.display = 'none';
  document.getElementById('cardFormBox').style.display = '';
  document.querySelector('#cardEditorModal .ha-dialog').classList.remove('gallery-mode');
  document.getElementById('cardBackToGallery').style.display = fromGallery ? '' : 'none';
  document.getElementById('cardTypeRow').style.display = fromGallery ? 'none' : '';
}

/** A tile picked: the type's usual form, filled with [prefill] when the device tab gave one. */
function pickCardType(type, prefill) {
  hideCardGallery(true);
  document.getElementById('cardTypeSelect').value = type;
  updateCardFormInputs();
  if (prefill) fillCardForm({ type, options: prefill });
}

// ---- rendering -----------------------------------------------------------------

function renderCardGallery(full) {
  const g = window._gallery;
  const box = document.getElementById('cardGallery');
  if (full) {
    box.innerHTML = `
      <div class="gallery-head">
        <label class="gallery-search">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/></svg>
          <input type="search" id="gallerySearch" placeholder="${cxEsc(I18N.t('web_gallery_search_ph'))}" oninput="onGallerySearch(this.value)" autocomplete="off">
        </label>
      </div>
      <div class="gallery-tabs" role="tablist">${GALLERY_CATEGORIES.map(c =>
        `<button type="button" role="tab" class="gallery-tab" data-cat="${c}" onclick="setGalleryTab('${c}')">${I18N.t('web_gallery_tab_' + c)}</button>`).join('')}</div>
      <div id="galleryBody"></div>`;
  }
  box.querySelectorAll('.gallery-tab').forEach(b => {
    const on = !g.query && b.dataset.cat === g.tab;
    b.classList.toggle('active', on);
    b.setAttribute('aria-selected', on ? 'true' : 'false');
  });
  const body = document.getElementById('galleryBody');
  if (g.query) body.innerHTML = gallerySearchHtml(g.query);
  else if (g.tab === 'device') body.innerHTML = galleryDeviceHtml();
  else body.innerHTML = galleryTilesHtml(galleryAvailableTypes().filter(t => GALLERY_TYPES[t].cat === g.tab));
}

function setGalleryTab(cat) {
  const g = window._gallery;
  g.tab = cat;
  g.query = '';
  g.device = null;
  const input = document.getElementById('gallerySearch');
  if (input) input.value = '';
  renderCardGallery(false);
}

function onGallerySearch(value) {
  window._gallery.query = value.trim();
  renderCardGallery(false);
}

function galleryTileHtml(type, extra, onclick) {
  const def = GALLERY_TYPES[type] || { icon: 'apps' };
  return `<button type="button" class="gallery-tile${extra && extra.recommended ? ' recommended' : ''}" onclick="${onclick || `pickCardType('${type}')`}">
      <span class="gallery-tile-icon">${galleryIcon(def.icon)}</span>
      <span class="gallery-tile-text">
        <b>${cxEsc(galleryTypeLabel(type))}</b>
        <span>${cxEsc(I18N.t('web_gallery_desc_' + type))}</span>
        ${extra && extra.recommended ? `<span class="gallery-badge">${I18N.t('web_gallery_recommended')}</span>` : ''}
      </span>
    </button>`;
}

function galleryTilesHtml(types) {
  return `<div class="gallery-grid">${types.map(t => galleryTileHtml(t)).join('')}</div>`;
}

function gallerySearchHtml(query) {
  const q = query.toLowerCase();
  const hits = galleryAvailableTypes().filter(t =>
    (galleryTypeLabel(t) + ' ' + I18N.t('web_gallery_desc_' + t) + ' ' + t).toLowerCase().includes(q));
  return hits.length ? galleryTilesHtml(hits) : `<div class="hint">${I18N.t('web_gallery_no_result')}</div>`;
}

// ---- "For a device" ----------------------------------------------------------------

/** The Devices page's catalog, one entry per entity. */
function galleryDevices() {
  const seen = new Set();
  return ((typeof dashboardData !== 'undefined' && dashboardData.haDevices) || []).filter(d => {
    if (!d || !d.entityId || seen.has(d.entityId)) return false;
    seen.add(d.entityId);
    return true;
  });
}

function galleryDeviceIcon(d) {
  const obj = (d.entityId.split('.')[1] || '');
  if (d.domain === 'media_player') {
    if (/^zidoo_/.test(obj)) {
      const a = (haStates && haStates[d.entityId] && haStates[d.entityId].attributes) || {};
      return /eversolo/i.test(a.manufacturer || '') || /^DMP/i.test(a.model || '') ? 'audio' : 'player';
    }
    if (/^appletv_/.test(obj) || /^sky_/.test(obj)) return 'box';
    if (/^androidtv_/.test(obj) || /^lgtv_/.test(obj) || /^samsung_/.test(obj)) return 'tv';
    return 'play';
  }
  return { light: 'bulb', switch: 'power', cover: 'blind', fan: 'fan', climate: 'thermo', camera: 'camera', vacuum: 'vacuum',
    weather: 'sun', remote: 'remote', select: 'list', input_select: 'list' }[d.domain] || 'device';
}

function galleryDeviceHtml() {
  const devices = galleryDevices();
  if (!devices.length) return `<div class="hint">${I18N.t('web_gallery_no_devices')}</div>`;
  const g = window._gallery;
  const tiles = devices.map((d, i) => {
    const st = haStates && haStates[d.entityId] ? haStates[d.entityId].state : '';
    const on = g.device === d.entityId;
    return `<button type="button" class="gallery-device${on ? ' active' : ''}" onclick="pickGalleryDevice(${i})">
        <span class="gallery-tile-icon">${galleryIcon(galleryDeviceIcon(d), 22)}</span>
        <span class="gallery-tile-text"><b>${cxEsc(d.name || d.entityId)}</b><span>${cxEsc(d.entityId)}${st ? ' · ' + cxEsc(st) : ''}</span></span>
      </button>`;
  }).join('');
  let suggestions = '';
  const dev = devices.find(d => d.entityId === g.device);
  if (dev) {
    const list = gallerySuggestionsFor(dev).filter(s => galleryAvailableTypes().includes(s.type));
    window._gallerySuggestions = list;
    suggestions = `<div class="gallery-suggestions">
        <div class="gallery-suggestions-title">${I18N.t('web_gallery_for', cxEsc(dev.name || dev.entityId))}</div>
        <div class="gallery-grid">${list.map((s, i) => galleryTileHtml(s.type, s, `pickGallerySuggestion(${i})`)).join('')}</div>
      </div>`;
  }
  return `<div class="hint" style="margin-bottom:10px">${I18N.t('web_gallery_device_hint')}</div>
    <div class="gallery-devices">${tiles}</div>${suggestions}`;
}

function pickGalleryDevice(i) {
  const dev = galleryDevices()[i];
  if (!dev) return;
  window._gallery.device = dev.entityId;
  renderCardGallery(false);
  const sug = document.querySelector('.gallery-suggestions');
  if (sug && sug.scrollIntoView) sug.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}

function pickGallerySuggestion(i) {
  const s = (window._gallerySuggestions || [])[i];
  if (s) pickCardType(s.type, s.opts);
}

/** The device's own apps as the remote publishes them (a Zidoo / Eversolo added in Astrion). */
function galleryDeviceApps(entityId) {
  const a = haStates && haStates[entityId] && haStates[entityId].attributes;
  return a && Array.isArray(a.astrion_apps) ? a.astrion_apps.filter(x => x && x.app).map(x => (x.icon ? { name: x.name || x.app, app: x.app, icon: x.icon } : { name: x.name || x.app, app: x.app })) : null;
}

/** The cards that suit [d], best first, each with the options to fill its form with. */
function gallerySuggestionsFor(d) {
  const e = d.entityId;
  const name = d.name || e;
  const obj = e.split('.')[1] || '';
  if (d.domain === 'media_player') {
    const full = { name, entity_id: e, variant: 'full' };
    const remote = { name, remote_entity: 'remote.' + obj, media_entity: e };
    const source = { name, entity_id: e };
    if (/^zidoo_/.test(obj)) {
      const apps = galleryDeviceApps(e);
      return [
        // 1.5.1: its own card first, what plays decides its buttons.
        { type: 'zidoo', recommended: true, opts: { name, entity: e } },
        { type: 'media_player', opts: apps ? { ...full, apps } : full },
        { type: 'tv_remote', opts: remote },
        { type: 'source_select', opts: source }
      ];
    }
    if (/^lgtv_/.test(obj) || /^samsung_/.test(obj)) {
      // An LG (1.5.2) or Samsung (1.5.3) TV added in Astrion: its remote first, its apps already in.
      const apps = galleryDeviceApps(e);
      return [
        { type: 'tv_remote', recommended: true, opts: apps ? { ...remote, apps } : remote },
        { type: 'media_player', opts: full },
        { type: 'source_select', opts: source }
      ];
    }
    if (/^sky_/.test(obj)) {
      // A Sky box added in Astrion (1.5.3): its remote first; a Sky Q's favourite channels are its sources.
      return [
        { type: 'tv_remote', recommended: true, opts: remote },
        { type: 'media_player', opts: full },
        { type: 'source_select', opts: source }
      ];
    }
    if (/^appletv_/.test(obj)) {
      return [
        { type: 'apple_tv_remote', recommended: true, opts: { name, entity: e } },
        { type: 'media_player', opts: full }
      ];
    }
    if (/^androidtv_/.test(obj)) {
      return [
        { type: 'tv_remote', recommended: true, opts: remote },
        { type: 'media_player', opts: full },
        { type: 'source_select', opts: source }
      ];
    }
    return [
      { type: 'media_player', recommended: true, opts: { name, entity_id: e } },
      { type: 'source_select', opts: source },
      { type: 'speaker_group', opts: { name } }
    ];
  }
  if (d.domain === 'remote') return [{ type: 'tv_remote', recommended: true, opts: { name, remote_entity: e } }];
  const type = { light: 'light', switch: 'switch', cover: 'cover', fan: 'fan', climate: 'climate', camera: 'camera',
    vacuum: 'vacuum', select: 'select', input_select: 'select', weather: 'clock_weather' }[d.domain];
  if (type) return [{ type, recommended: true, opts: { name, entity_id: e } }];
  return [{ type: 'button_grid', recommended: true, opts: { name } }];
}
