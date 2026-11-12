// ---- media_remote card (builder form) ------------------------------------------------
//
// The universal media remote: one card for any player (Zidoo, Eversolo, Apple TV, Android TV,
// Samsung, LG…). The form says which player to follow (player_entity), which remote entity sends
// the keys (remote_entity), where the volume goes (volume_entity), how the keys are named
// (kind), and which blocks may show. The card itself picks the buttons by what plays.
// Apps written by hand in the JSON ("apps": [{name, app}]) and any other option are kept.
//
// Uses: I18N, haStates, cxEsc (cards-extra.js).

/** The card's switches: [field id, option, shown by default]. */
const MEDIA_REMOTE_SWITCHES = [
  ['optMrName', 'show_name', true],
  ['optMrPower', 'show_power', true],
  ['optMrSkip', 'show_skip', true],
  ['optMrBrowse', 'show_browse', true],
  ['optMrSources', 'show_sources', true],
  ['optMrKeypad', 'show_keypad', true],
  ['optMrDpad', 'show_dpad', true],
  ['optMrVolume', 'show_volume', true],
  ['optMrChannels', 'show_channels', true],
  ['optMrTracks', 'show_tracks', true],
  ['optMrQueue', 'show_queue', true],
  ['optMrIo', 'show_io', true],
  ['optMrQuality', 'show_quality', true]
];

/** Live entity ids of a domain (empty when the builder doesn't run on the remote). */
function mediaRemoteEntities(domain) {
  if (typeof haStates === 'undefined' || !haStates) return [];
  return Object.keys(haStates).filter(id => id.startsWith(domain + '.')).sort();
}

function mediaRemoteFormHtml() {
  const list = (id, domain) =>
    `<datalist id="${id}">${mediaRemoteEntities(domain).map(e => `<option value="${cxEsc(e)}"></option>`).join('')}</datalist>`;
  // Ticked as a new card's, so the sections count only what differs from it.
  const check = (id, key) => {
    const def = MEDIA_REMOTE_SWITCHES.find(s => s[0] === id)[2];
    return `<label class="inline-check"><input type="checkbox" id="${id}"${def ? ' checked' : ''}> ${I18N.t('web_card_mr_' + key)}</label>`;
  };
  return `
    <label>${I18N.t('web_card_name_optional_friendly')}</label><input type="text" id="optName" placeholder="${I18N.t('web_card_mr_name_ph')}">
    <label>${I18N.t('web_card_mr_player')}</label>
    <input type="text" id="optMrPlayer" list="mrPlayers" placeholder="media_player.zidoo_zidoo" onchange="onTvEntityChange()">
    ${list('mrPlayers', 'media_player')}
    <div class="hint">${I18N.t('web_card_mr_player_hint')}</div>
    <label>${I18N.t('web_card_mr_remote')}</label>
    <input type="text" id="optMrRemote" list="mrRemotes" placeholder="remote.zidoo_zidoo" onchange="onTvEntityChange()">
    ${list('mrRemotes', 'remote')}
    <div class="hint">${I18N.t('web_card_mr_remote_hint')}</div>
    <label>${I18N.t('web_card_mr_volume_entity')}</label>
    <input type="text" id="optMrVolumeEntity" list="mrRemotes" placeholder="remote.denon_ampli">
    <div class="hint">${I18N.t('web_card_mr_volume_entity_hint')}</div>
    <label>${I18N.t('web_card_mr_kind')}</label>
    <select id="optMrKind">
      <option value="auto">${I18N.t('web_card_mr_kind_auto')}</option>
      <option value="android">${I18N.t('web_card_mr_kind_android')}</option>
      <option value="apple_tv">${I18N.t('web_card_mr_kind_apple_tv')}</option>
    </select>
    <div class="hint">${I18N.t('web_card_mr_hint')}</div>
    ${MEDIA_REMOTE_SWITCHES.map(s => check(s[0], s[1])).join('\n    ')}
    ${tvAppsSectionHtml(true)}
    <label>${I18N.t('web_card_mr_apps_rows')}</label>
    <select id="optMrAppsRows">
      <option value="0">${I18N.t('web_card_mr_apps_rows_all')}</option>
      <option value="1">1</option>
      <option value="2">2</option>
      <option value="3">3</option>
    </select>
    <div class="hint">${I18N.t('web_card_mr_apps_rows_hint')}</div>
  `;
}

function fillMediaRemoteForm(o) {
  document.getElementById('optName').value = o.name || '';
  document.getElementById('optMrPlayer').value = o.player_entity || '';
  document.getElementById('optMrRemote').value = o.remote_entity || '';
  document.getElementById('optMrVolumeEntity').value = o.volume_entity || '';
  document.getElementById('optMrKind').value = ['android', 'apple_tv'].includes(o.kind) ? o.kind : 'auto';
  MEDIA_REMOTE_SWITCHES.forEach(([id, key, def]) => {
    document.getElementById(id).checked = o[key] === undefined ? def : o[key] !== false;
  });
  // The apps: same editor as the TV remote card's (style, show, list, icons, the device's own apps).
  document.getElementById('optTvShowApps').checked = o.show_apps !== false;
  fillMediaApps(o);
  document.getElementById('optMrAppsRows').value = [1, 2, 3].includes(Number(o.apps_rows)) ? String(o.apps_rows) : '0';
  // What the form doesn't show is kept as it is.
  const known = ['name', 'player_entity', 'remote_entity', 'volume_entity', 'kind', 'apps', 'apps_style', 'show_apps', 'apps_rows'];
  window._mediaRemoteExtra = Object.fromEntries(Object.entries(o).filter(([k]) =>
    !known.includes(k) && !MEDIA_REMOTE_SWITCHES.some(s => s[1] === k)));
}

/** Writes the form into [out]; false (with a message) when no player is chosen. */
function collectMediaRemoteForm(out) {
  const player = document.getElementById('optMrPlayer').value.trim();
  if (!player) {
    alert(I18N.t('web_card_mr_alert_player'));
    return false;
  }
  Object.assign(out, window._mediaRemoteExtra || {});
  const name = document.getElementById('optName').value.trim();
  if (name) out.name = name;
  out.player_entity = player;
  const remote = document.getElementById('optMrRemote').value.trim();
  if (remote) out.remote_entity = remote; else delete out.remote_entity;
  const volume = document.getElementById('optMrVolumeEntity').value.trim();
  if (volume) out.volume_entity = volume; else delete out.volume_entity;
  const kind = document.getElementById('optMrKind').value;
  if (kind !== 'auto') out.kind = kind; else delete out.kind;
  MEDIA_REMOTE_SWITCHES.forEach(([id, key, def]) => {
    const on = document.getElementById(id).checked;
    if (on !== def) out[key] = on; else delete out[key];
  });
  collectMediaApps(out);
  if (document.getElementById('optTvShowApps').checked) delete out.show_apps; else out.show_apps = false;
  const rows = Number(document.getElementById('optMrAppsRows').value);
  if (rows > 0) out.apps_rows = rows; else delete out.apps_rows;
  window._mediaRemoteExtra = null;
  return true;
}

/** The builder's preview: the card's blocks, top to bottom, as the remote lays them out. */
function mediaRemotePreviewHtml(card, idx) {
  // Same blocks, in the same order, as MediaRemoteCard.kt: power, what plays, transport, the
  // player's own buttons, Apps · Home · Menu, the app rows, then the pad with its side keys.
  const o = card.options || {};
  const e = (typeof haStates !== 'undefined' && haStates && haStates[o.player_entity]) || null;
  const a = (e && e.attributes) || {};
  const on = key => { const s = MEDIA_REMOTE_SWITCHES.find(x => x[1] === key); return o[key] === undefined ? s[2] : o[key] !== false; };
  const player = o.player_entity || '';
  const zidoo = /^media_player\.zidoo_/.test(player);
  const eversolo = zidoo && (a.manufacturer ? a.manufacturer === 'Eversolo' : /eversolo/i.test(player + ' ' + (o.name || '')));
  const title = a.media_title || 'Tous les matins du monde';
  const sub = a.media_artist || a.media_series_title || 'Kaamelott';
  const C = { key: '#2a383d', text: '#E6EEF2', muted: '#9FB6BD', accent: '#00E5FF', surface: '#1f2b30' };
  const key = (s, glyph, opts = {}) => `<span style="width:${s}px;height:${s}px;border-radius:50%;background:${opts.accent ? C.accent : C.key};color:${opts.accent ? '#0b1418' : opts.tint || C.text};display:inline-flex;align-items:center;justify-content:center;font-size:${Math.round(s * 0.42)}px;flex:none">${glyph || ''}</span>`;
  const slot = inner => `<span style="width:30px;display:inline-flex;justify-content:center">${inner}</span>`;
  const parts = [];
  const label = o.name || a.friendly_name || player.split('.')[1] || 'Media';
  if (on('show_name') || on('show_power')) {
    parts.push(`<div style="display:flex;align-items:center;gap:8px"><b style="flex:1;color:${C.text};font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${on('show_name') ? cxEsc(label) : ''}</b>${on('show_power') ? key(30, '⏻', { tint: C.accent }) : ''}</div>`);
  }
  // What plays (shown in the preview as if something did).
  parts.push(`<div style="display:flex;align-items:center;gap:10px">
      <span style="width:46px;height:46px;border-radius:8px;background:${C.key};flex:none"></span>
      <div style="min-width:0"><div style="color:${C.accent};font-size:9px;font-weight:600">PLAYING</div>
        <div style="color:${C.text};font-size:12px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${cxEsc(title)}</div>
        <div style="color:${C.muted};font-size:10px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${cxEsc(sub)}</div></div></div>`);
  parts.push(`<div style="height:3px;border-radius:2px;background:#2f4248"><div style="width:32%;height:3px;border-radius:2px;background:${C.accent}"></div></div>`);
  const skip = on('show_skip');
  parts.push(`<div style="display:flex;justify-content:space-evenly;align-items:center">${skip ? key(26, '↺') : ''}${key(26, '⏮')}${key(36, '❚❚', { accent: true })}${key(26, '⏭')}${skip ? key(26, '↻') : ''}</div>`);
  // The library and the player's own buttons (a Zidoo / Eversolo only).
  const chips = [];
  if (on('show_browse')) chips.push(I18N.t('web_card_mr_chip_library'));
  if (zidoo && on('show_queue')) chips.push(I18N.t('web_card_mr_chip_queue'));
  if (eversolo && on('show_io')) chips.push(I18N.t('web_card_mr_chip_input'), I18N.t('web_card_mr_chip_output'));
  if (chips.length) {
    parts.push(`<div style="display:flex;gap:6px">${chips.slice(0, 4).map(c =>
      `<span style="flex:1;min-height:30px;border-radius:8px;background:${C.key};color:${C.text};font-size:10px;font-weight:600;display:flex;align-items:center;justify-content:center;text-align:center;line-height:1.1;padding:2px">${cxEsc(c)}</span>`).join('')}</div>`);
  }
  if (zidoo && on('show_quality')) parts.push(`<div style="text-align:center;color:#748790;font-size:9px">FLAC · 44.1 kHz · 16 bit</div>`);
  // Apps · Home · Menu, over the pad's side keys.
  parts.push(`<div style="display:flex;justify-content:space-between;align-items:center">${slot(on('show_sources') ? key(30, '▦') : '')}${key(30, '⌂')}${slot(key(30, zidoo ? '⚙' : '☰'))}</div>`);
  // The app rows: the card's apps, else the player's own (left to the remote).
  if (o.show_apps !== false) {
    const apps = Array.isArray(o.apps) ? o.apps : [];
    const icons = o.apps_style === 'icons';
    const perRow = icons ? 5 : 2;
    const rows = Number(o.apps_rows) > 0 ? Number(o.apps_rows) : Infinity;
    const shown = apps.slice(0, rows === Infinity ? apps.length : rows * perRow);
    if (shown.length) {
      for (let r = 0; r < shown.length; r += perRow) {
        const row = shown.slice(r, r + perRow);
        parts.push(icons
          ? `<div style="display:flex;justify-content:center;gap:6px">${row.map(app => `<span style="width:40px;text-align:center"><span style="display:block;width:30px;height:30px;margin:0 auto;border-radius:8px;background:${C.key}"></span><span style="display:block;color:${C.muted};font-size:8px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${cxEsc(app.name || app.app || '')}</span></span>`).join('')}</div>`
          : `<div style="display:flex;gap:6px">${row.map(app => `<span style="flex:1;height:24px;border-radius:8px;background:${C.key};color:${C.text};font-size:10px;display:flex;align-items:center;justify-content:center;white-space:nowrap;overflow:hidden">${cxEsc(app.name || app.app || '')}</span>`).join('')}${row.length < perRow ? '<span style="flex:1"></span>' : ''}</div>`);
      }
    } else if (zidoo) {
      parts.push(`<div style="text-align:center;color:#748790;font-size:9px">${I18N.t('web_card_mr_preview_own_apps')}</div>`);
    }
  }
  if (on('show_dpad')) {
    const channels = on('show_channels') && !eversolo;
    const side = (top, bottom) => `<div style="display:flex;flex-direction:column;justify-content:space-between;height:100%">${top}<div style="display:flex;flex-direction:column;gap:4px">${bottom}</div></div>`;
    parts.push(`<div style="display:flex;justify-content:space-between;align-items:stretch;height:118px">
        ${side(key(30, '←'), on('show_volume') ? key(30, '+') + key(30, '−') : '')}
        <span style="align-self:center;width:100px;height:100px;border-radius:50%;background:${C.key};position:relative;display:inline-block">
          <span style="position:absolute;top:4px;left:50%;transform:translateX(-50%);color:${C.text};font-size:12px">▲</span>
          <span style="position:absolute;bottom:4px;left:50%;transform:translateX(-50%);color:${C.text};font-size:12px">▼</span>
          <span style="position:absolute;left:6px;top:50%;transform:translateY(-50%);color:${C.text};font-size:12px">◀</span>
          <span style="position:absolute;right:6px;top:50%;transform:translateY(-50%);color:${C.text};font-size:12px">▶</span>
          <span style="position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:32px;height:32px;border-radius:50%;background:${C.surface};color:${C.text};font-size:9px;display:flex;align-items:center;justify-content:center">OK</span>
        </span>
        ${side(on('show_keypad') ? key(30, '#') : '<span style="width:30px"></span>', channels ? key(30, '▴') + key(30, '▾') : '')}
      </div>`);
    if (on('show_volume')) parts.push(`<div style="display:flex;justify-content:center">${key(30, '🔇')}</div>`);
  }
  return `
    <div class="card">
      <div class="card-title"><span>media_remote${o.name ? ' · ' + cxEsc(o.name) : ''}</span><span><span class="remove" style="color:#00E5FF" onclick="editCard(${idx})">✎</span> <span class="remove" onclick="removeCard(${idx})">✕</span></span></div>
      <div style="display:flex;flex-direction:column;gap:8px">${parts.join('\n')}</div>
      <div class="hint" style="margin-top:6px">${I18N.t('web_preview_entity', cxEsc(player || 'media_player.…'))}</div>
    </div>`;
}
