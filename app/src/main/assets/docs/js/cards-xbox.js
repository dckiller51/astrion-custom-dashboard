// ---- xbox card (1.3.7) --------------------------------------------------------------
//
// One card, two sources picked here: the official Xbox integration, or TrueAchievements (custom
// component, TA Pro accounts). The accounts of each are found in Home Assistant's live states
// (haStates, when the builder runs on the remote) and fill every field in one click; every
// field stays editable, so it also works by hand.

const XBOX_MAX_STATS = 8; // two per row on the remote

/** Detected TrueAchievements accounts: [{tag, game, sync, stats: [entity ids]}]. */
function xboxTaAccounts() {
  if (typeof haStates === 'undefined' || !haStates) return [];
  const byTag = {};
  Object.entries(haStates).forEach(([id, e]) => {
    if (!/^(sensor|binary_sensor)\.trueachievements_/.test(id)) return;
    const m = /TrueAchievements \(([^)]+)\)/.exec((e && e.friendly_name) || '');
    const tag = m ? m[1] : id.split('.')[1].replace(/^trueachievements_/, '').split('_')[0];
    const acc = byTag[tag] || (byTag[tag] = { tag, game: '', sync: '', all: [] });
    const a = (e && e.attributes) || {};
    if (id.startsWith('binary_sensor.')) { if (a.last_update !== undefined || a.device_class === 'problem') acc.sync = id; return; }
    if (a.ta_score !== undefined || a.game_ratio !== undefined) { acc.game = id; return; }
    acc.all.push({ id, unit: a.unit_of_measurement || '', icon: a.icon || '' });
  });
  // The four counters of the usual TA dashboard first: Gamerscore, TA score, completion, games finished.
  const rank = s => s.unit === 'G' ? 0 : s.unit === 'TA' ? 1 : s.unit === '%' ? 2 : s.icon === 'mdi:trophy' ? 3 : s.icon === 'mdi:controller' ? 4 : 5;
  return Object.values(byTag).map(acc => ({ ...acc, stats: acc.all.sort((x, y) => rank(x) - rank(y)).map(s => s.id) }));
}

/** Detected Xbox integration accounts: the profile binary_sensors (they carry display_name). */
function xboxIntegrationAccounts() {
  if (typeof haStates === 'undefined' || !haStates) return [];
  return Object.entries(haStates)
    .filter(([id, e]) => id.startsWith('binary_sensor.') && !id.includes('trueachievements') && e && e.attributes && e.attributes.display_name !== undefined)
    .map(([id, e]) => {
      const slug = id.slice('binary_sensor.'.length);
      const exists = x => !!haStates[x];
      return {
        tag: e.attributes.display_name || slug,
        profile: id,
        game: exists(`sensor.${slug}_now_playing`) ? `sensor.${slug}_now_playing` : '',
        picture: xboxCoverImage(slug),
        stats: ['gamer_score', 'friends', 'follower', 'following'].map(s => `sensor.${slug}_${s}`).filter(exists)
      };
    });
}

/** The integration's image entity for the game's cover: image.<slug>_now_playing, else its poster or key art. */
function xboxCoverImage(slug) {
  const ids = Object.keys(haStates || {}).filter(id => id.startsWith(`image.${slug}_`) && !id.endsWith('_gamerpic'));
  const pick = part => ids.find(id => id.includes(part));
  return pick('now_playing') || pick('poster') || pick('key_art') || ids[0] || '';
}

function xboxAccounts(source) {
  return source === 'trueachievements' ? xboxTaAccounts() : xboxIntegrationAccounts();
}

function xboxFormHtml() {
  return `
    <label>${I18N.t('web_card_xbox_source')}</label>
    <select id="xboxSource" onchange="onXboxSourceChange()">
      <option value="xbox">${I18N.t('web_card_xbox_source_xbox')}</option>
      <option value="trueachievements">${I18N.t('web_card_xbox_source_ta')}</option>
    </select>
    <div class="hint" id="xboxSourceHint"></div>
    <div id="xboxAccountBox">
      <label>${I18N.t('web_card_xbox_account')}</label>
      <div style="display:flex;gap:8px;align-items:center">
        <select id="xboxAccount" style="flex:1"></select>
        <button type="button" class="secondary" style="margin:0" onclick="fillXboxFromAccount()">${I18N.t('web_card_xbox_fill')}</button>
      </div>
    </div>
    <div id="xboxProfileBox">
      <label class="inline-check"><input type="checkbox" id="xboxShowProfile" checked> ${I18N.t('web_card_xbox_show_profile')}</label>
      <input type="text" id="xboxProfile" placeholder="binary_sensor.gamertag">
    </div>
    <label class="inline-check"><input type="checkbox" id="xboxShowGame" checked> ${I18N.t('web_card_xbox_show_game')}</label>
    <input type="text" id="xboxGame" placeholder="sensor.…">
    <div class="hint">${I18N.t('web_card_xbox_game_hint')}</div>
    <label>${I18N.t('web_card_xbox_picture')}</label>
    <input type="text" id="xboxPicture" placeholder="image.gamertag_now_playing">
    <div class="hint">${I18N.t('web_card_xbox_picture_hint')}</div>
    <label class="inline-check"><input type="checkbox" id="xboxShowStats" checked> ${I18N.t('web_card_xbox_show_stats')}</label>
    <div style="display:flex;gap:12px;align-items:center;flex-wrap:wrap">
      <label style="margin:0">${I18N.t('web_card_xbox_per_row')}</label>
      <select id="xboxPerRow" style="width:auto;margin:0">${[1, 2, 3, 4].map(n => `<option value="${n}"${n === 2 ? ' selected' : ''}>${n}</option>`).join('')}</select>
      <label class="inline-check" style="margin:0"><input type="checkbox" id="xboxShowIcons" checked> ${I18N.t('web_card_xbox_show_icons')}</label>
    </div>
    <div id="xboxStatsList"></div>
    <button type="button" class="secondary" id="xboxAddStat" onclick="addXboxStat()">${I18N.t('web_card_xbox_add_stat')}</button>
    <div id="xboxSyncBox">
      <label class="inline-check"><input type="checkbox" id="xboxShowSync" checked> ${I18N.t('web_card_xbox_show_sync')}</label>
      <input type="text" id="xboxSync" placeholder="binary_sensor.trueachievements_…">
    </div>
  `;
}

/** Built-in counter icons (XboxModel.ICON_NAMES), with how the builder shows them. */
const XBOX_STAT_ICONS = [
  ['auto', 'web_card_xbox_icon_auto', ''], ['gamerscore', 'web_card_xbox_icon_gamerscore', 'G'],
  ['star', 'web_card_xbox_icon_star', '★'], ['percent', 'web_card_xbox_icon_percent', '%'],
  ['trophy', 'web_card_xbox_icon_trophy', '🏆'], ['medal', 'web_card_xbox_icon_medal', '🎖'],
  ['controller', 'web_card_xbox_icon_controller', '🎮'], ['people', 'web_card_xbox_icon_people', '👥'],
  ['chart', 'web_card_xbox_icon_chart', '📈'], ['none', 'web_card_xbox_icon_none', ''], ['file', 'web_card_xbox_icon_file', '🖼']
];

/** A counter's `icon` option → the select's value ('file' for an uploaded icon path). */
function xboxIconChoice(icon) {
  if (!icon) return 'auto';
  return XBOX_STAT_ICONS.some(([k]) => k === icon && k !== 'file') ? icon : 'file';
}

/** The counters being edited: [{entity_id, name, icon, color}], rendered as rows (entity, label, ↑ ↓ ✕, then icon and colour). */
function renderXboxStats() {
  const list = document.getElementById('xboxStatsList');
  if (!list) return;
  const stats = window._pendingXboxStats || [];
  list.innerHTML = stats.map((st, i) => {
    const choice = xboxIconChoice(st.icon);
    const options = XBOX_STAT_ICONS.map(([k, key, glyph]) =>
      `<option value="${k}"${k === choice ? ' selected' : ''}>${glyph ? glyph + ' ' : ''}${I18N.t(key)}</option>`).join('');
    return `
    <div class="list-item xbox-stat-row" style="flex-direction:column;align-items:stretch;gap:6px">
      <div style="display:flex;gap:6px;align-items:center">
        <input type="text" class="xbox-stat-id" id="xboxStat${i}" value="${cxEsc(st.entity_id || '')}" placeholder="sensor.…" style="flex:3;min-width:0;margin:0">
        <input type="text" class="xbox-stat-name" value="${cxEsc(st.name || '')}" placeholder="${I18N.t('web_card_xbox_stat_name_ph')}" style="flex:2;min-width:0;margin:0">
        ${cxMoveButtons('moveXboxStat', i)}
        <span class="remove" onclick="removeXboxStat(${i})">✕</span>
      </div>
      <div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap">
        <select class="xbox-stat-icon" style="width:auto;margin:0" onchange="syncXboxStats(); renderXboxStats()">${options}</select>
        <span style="display:${choice === 'file' ? 'flex' : 'none'};gap:6px;align-items:center;flex:1;min-width:160px">
          <input type="text" class="xbox-stat-file" id="xboxStatFile${i}" value="${choice === 'file' && st.icon !== 'file' ? cxEsc(st.icon) : ''}" placeholder="/sdcard/astrion/icons/xxx.png" style="flex:1;min-width:0;margin:0">
          <button type="button" class="secondary" style="margin:0" onclick="openIconPicker('xboxStatFile${i}')">${I18N.t('web_icon_choose')}</button>
        </span>
        <span style="display:${choice === 'file' || choice === 'none' ? 'none' : 'flex'};align-items:center;gap:4px">
          <span style="color:#9AA8AD;font-size:12px">${I18N.t('web_card_xbox_icon_color')}</span>${colorFieldHtml('xboxStatColor' + i, st.color || '', '')}
        </span>
      </div>
    </div>`;
  }).join('');
  stats.forEach((_, i) => attachEntityAutocomplete(document.getElementById('xboxStat' + i), 'sensor'));
  document.getElementById('xboxAddStat').style.display = stats.length >= XBOX_MAX_STATS ? 'none' : '';
}

function syncXboxStats() {
  const rows = document.querySelectorAll('#xboxStatsList .xbox-stat-row');
  window._pendingXboxStats = [...rows].map((r, i) => {
    const choice = r.querySelector('.xbox-stat-icon').value;
    const icon = choice === 'file' ? r.querySelector('.xbox-stat-file').value.trim() : (choice === 'auto' ? '' : choice);
    return {
      entity_id: r.querySelector('.xbox-stat-id').value.trim(),
      name: r.querySelector('.xbox-stat-name').value.trim(),
      icon: icon || (choice === 'file' ? 'file' : ''),
      color: choice === 'none' || choice === 'file' ? '' : colorFieldValue('xboxStatColor' + i)
    };
  });
}

function addXboxStat() { syncXboxStats(); window._pendingXboxStats.push({ entity_id: '', name: '' }); renderXboxStats(); }
function removeXboxStat(i) { syncXboxStats(); window._pendingXboxStats.splice(i, 1); renderXboxStats(); }
function moveXboxStat(i, d) { syncXboxStats(); if (cxSwap(window._pendingXboxStats, i, d)) renderXboxStats(); }

function onXboxSourceChange() {
  const source = document.getElementById('xboxSource').value;
  const ta = source === 'trueachievements';
  document.getElementById('xboxSourceHint').textContent = I18N.t(ta ? 'web_card_xbox_ta_hint' : 'web_card_xbox_xbox_hint');
  document.getElementById('xboxProfileBox').style.display = ta ? 'none' : '';
  document.getElementById('xboxSyncBox').style.display = ta ? '' : 'none';
  const accounts = xboxAccounts(source);
  const select = document.getElementById('xboxAccount');
  select.innerHTML = accounts.length
    ? accounts.map((a, i) => `<option value="${i}">${cxEsc(a.tag)}</option>`).join('')
    : `<option value="">${I18N.t('web_card_xbox_no_account')}</option>`;
  select.disabled = !accounts.length;
}

/** Fills every entity field from the account picked (detected in Home Assistant). */
function fillXboxFromAccount() {
  const source = document.getElementById('xboxSource').value;
  const acc = xboxAccounts(source)[parseInt(document.getElementById('xboxAccount').value, 10)];
  if (!acc) return;
  document.getElementById('xboxProfile').value = acc.profile || '';
  document.getElementById('xboxGame').value = acc.game || '';
  document.getElementById('xboxPicture').value = acc.picture || '';
  document.getElementById('xboxSync').value = acc.sync || '';
  window._pendingXboxStats = acc.stats.slice(0, XBOX_MAX_STATS).map(id => ({ entity_id: id, name: '' }));
  renderXboxStats();
}

function initXboxForm() {
  window._xboxExtra = {};
  window._pendingXboxStats = [];
  document.getElementById('xboxSource').value = 'xbox';
  onXboxSourceChange();
  fillXboxFromAccount(); // the first account found, so a new card works at once
  ['xboxProfile', 'xboxSync'].forEach(id => attachEntityAutocomplete(document.getElementById(id), 'binary_sensor'));
  attachEntityAutocomplete(document.getElementById('xboxGame'), 'sensor');
  attachEntityAutocomplete(document.getElementById('xboxPicture'), 'image');
}

function fillXboxForm(o) {
  document.getElementById('xboxSource').value = o.source === 'trueachievements' ? 'trueachievements' : 'xbox';
  onXboxSourceChange();
  document.getElementById('xboxProfile').value = o.profile_entity || '';
  document.getElementById('xboxGame').value = o.game_entity || '';
  document.getElementById('xboxPicture').value = o.picture_entity || '';
  document.getElementById('xboxSync').value = o.sync_entity || '';
  window._pendingXboxStats = (Array.isArray(o.stats) ? o.stats : [])
    .map(st => ({ entity_id: st.entity_id || '', name: st.name || '', icon: st.icon || '', color: st.color || '' }));
  renderXboxStats();
  document.getElementById('xboxPerRow').value = String(Math.min(4, Math.max(1, o.stats_per_row || 2)));
  document.getElementById('xboxShowIcons').checked = o.show_stat_icons !== false;
  document.getElementById('xboxShowProfile').checked = o.show_profile !== false;
  document.getElementById('xboxShowGame').checked = o.show_game !== false;
  document.getElementById('xboxShowStats').checked = o.show_stats !== false;
  document.getElementById('xboxShowSync').checked = o.show_sync !== false;
  window._xboxExtra = cxWithout(o, ['source', 'profile_entity', 'game_entity', 'picture_entity', 'sync_entity', 'stats', 'stats_per_row', 'show_stat_icons',
    'show_profile', 'show_game', 'show_stats', 'show_sync']);
}

function collectXboxOptions() {
  const val = id => document.getElementById(id).value.trim();
  const source = val('xboxSource');
  const out = { ...(window._xboxExtra || {}), source };
  if (source === 'xbox' && val('xboxProfile')) out.profile_entity = val('xboxProfile');
  if (val('xboxGame')) out.game_entity = val('xboxGame');
  if (val('xboxPicture')) out.picture_entity = val('xboxPicture');
  if (source === 'trueachievements' && val('xboxSync')) out.sync_entity = val('xboxSync');
  syncXboxStats();
  const stats = window._pendingXboxStats.filter(st => st.entity_id).slice(0, XBOX_MAX_STATS).map(st => {
    const s = { entity_id: st.entity_id };
    if (st.name) s.name = st.name;
    if (st.icon && st.icon !== 'file') s.icon = st.icon;
    if (st.color) s.color = st.color;
    return s;
  });
  if (stats.length) out.stats = stats;
  const perRow = parseInt(val('xboxPerRow'), 10);
  if (perRow && perRow !== 2) out.stats_per_row = perRow;
  if (!document.getElementById('xboxShowIcons').checked) out.show_stat_icons = false;
  if (!out.game_entity && !stats.length && !out.profile_entity) { alert(I18N.t('web_card_xbox_err_empty')); return null; }
  ['Profile', 'Game', 'Stats', 'Sync'].forEach(part => {
    if (!document.getElementById('xboxShow' + part).checked) out['show_' + part.toLowerCase()] = false;
  });
  return out;
}

/** A small stand-in for a counter's icon in the preview: its glyph (or the uploaded icon), in its colour. */
function xboxStatIconPreview(s) {
  const choice = xboxIconChoice(s.icon);
  if (choice === 'none') return '';
  if (choice === 'file') {
    const url = typeof iconUrl === 'function' ? iconUrl(s.icon) : '';
    return url ? `<img src="${url}" alt="" onerror="this.style.visibility='hidden'" style="width:20px;height:20px;object-fit:contain;flex:none">` : '<div style="width:20px;height:20px;flex:none"></div>';
  }
  const glyph = (XBOX_STAT_ICONS.find(([k]) => k === choice) || [])[2] || '◆';
  return `<div style="width:20px;text-align:center;font-weight:700;flex:none;color:${cxEsc(s.color || '#6FD08C')}">${glyph}</div>`;
}

/** A counter's label without its account part, as the remote shows it (XboxModel.statLabel). */
function xboxStatLabel(id, e) {
  let name = (e && e.friendly_name) || id.split('.')[1];
  name = name.replace(/^TrueAchievements \([^)]*\)\s*/, '');
  const tag = id.split('.')[1].split('_')[0];
  if (tag && name.toLowerCase().startsWith(tag.toLowerCase() + ' ')) name = name.slice(tag.length + 1);
  return name;
}

// ---- preview: real values when the builder runs on the remote, placeholders otherwise ----

function xboxPreviewHtml(o) {
  const st = id => (id && typeof haStates !== 'undefined' && haStates) ? haStates[id] : null;
  const box = (inner, extra = '') => `<div style="background:#151A20;border-radius:12px;padding:8px;${extra}">${inner}</div>`;
  const parts = [];
  const profile = st(o.profile_entity);
  if (o.source !== 'trueachievements' && o.profile_entity && o.show_profile !== false) {
    const name = (profile && profile.attributes && profile.attributes.display_name) || 'Gamertag';
    parts.push(`<div style="display:flex;gap:10px;align-items:center"><div style="width:36px;height:36px;border-radius:50%;background:#1B2027"></div>
      <div><b style="color:#E6EEF2">${cxEsc(name)}</b><div style="color:#9AA8AD;font-size:11px">${I18N.t(profile && profile.state === 'off' ? 'web_card_xbox_offline' : 'web_card_xbox_online')}</div></div></div>`);
  }
  if (o.game_entity && o.show_game !== false) {
    const g = st(o.game_entity);
    const title = g ? ((g.attributes && g.attributes.name) || g.state) : I18N.t('web_card_xbox_game_ph');
    const sub = g && g.attributes ? [g.attributes.platform, g.attributes.publisher].filter(Boolean).join(' · ') : '';
    const lines = g && g.attributes ? ['achievements', 'gamerscore', 'ta_score'].filter(k => g.attributes[k]).map(k => `<div style="color:#9AA8AD;font-size:10px">${cxEsc(g.attributes[k])}</div>`).join('') : '';
    parts.push(box(`<div style="display:flex;gap:10px"><div style="width:56px;height:56px;border-radius:9px;background:#1B2027;flex:none"></div>
      <div style="min-width:0"><b style="color:#E6EEF2">${cxEsc(title)}</b><div style="color:#9AA8AD;font-size:11px">${cxEsc(sub)}</div>${lines}</div></div>`));
  }
  const stats = (Array.isArray(o.stats) ? o.stats : []).slice(0, XBOX_MAX_STATS);
  if (stats.length && o.show_stats !== false) {
    // stats_per_row per row (2 by default), as on the remote; from 3, the icon sits above the value.
    const perRow = Math.min(4, Math.max(1, o.stats_per_row || 2));
    const stacked = perRow >= 3;
    parts.push(`<div style="display:grid;grid-template-columns:repeat(${perRow},minmax(0,1fr));gap:6px">${stats.map(s => {
      const e = st(s.entity_id);
      const label = s.name || xboxStatLabel(s.entity_id, e);
      const icon = o.show_stat_icons === false ? '' : xboxStatIconPreview(s);
      const texts = `<div style="min-width:0"><div style="color:#E6EEF2;font-weight:700;font-size:14px">${cxEsc(e ? e.state : '—')}</div><div style="color:#9AA8AD;font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${cxEsc(label)}</div></div>`;
      return box(`<div style="display:flex;${stacked ? 'flex-direction:column;text-align:center;' : ''}gap:6px;align-items:center">${icon}${texts}</div>`, 'min-width:0');
    }).join('')}</div>`);
  }
  if (o.source === 'trueachievements' && o.sync_entity && o.show_sync !== false) {
    const s = st(o.sync_entity);
    parts.push(`<div style="color:#9AA8AD;font-size:11px">${s && s.state === 'on' ? '⚠' : '✓'} ${I18N.t('web_card_xbox_last_sync')} ${cxEsc((s && s.attributes && s.attributes.last_update) || '')}</div>`);
  }
  return `<div style="display:flex;flex-direction:column;gap:8px">${parts.join('')}</div>`;
}
