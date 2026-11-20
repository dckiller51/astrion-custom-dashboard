// ---- zidoo card (1.5.1) --------------------------------------------------------------
//
// The Zidoo / Eversolo card: a player added in Astrion (Devices › Players), and which of its
// buttons to show. The card itself picks them by what plays — a film's subtitles, audio and
// chapters, music's queue, an Eversolo's input and output — so the form only says which ones may
// show. The player's apps come from the player, unless the card lists its own (1.5.6: the same
// app editor as the TV and media player cards; empty = the player's, in its order).
//
// Uses: I18N, dashboardData, haStates, cxEsc (cards-extra.js).

/** The card's switches: [field id, option, shown by default]. */
const ZIDOO_SWITCHES = [
  ['optZidooSources', 'show_sources', true],
  ['optZidooKeypad', 'show_keypad', true],
  ['optZidooTracks', 'show_tracks', true],
  ['optZidooQueue', 'show_queue', true],
  ['optZidooIo', 'show_io', true],
  ['optZidooSkip', 'show_skip', true],
  ['optZidooRepeat', 'show_repeat', true],
  ['optZidooVolume', 'show_volume', false],
  ['optZidooQuality', 'show_quality', true],
  ['optZidooApps', 'show_apps', true]
];

/** The Zidoos and Eversolos added in Astrion: [{entityId, name}]. */
function zidooCardPlayers() {
  return (dashboardData.haDevices || [])
    .filter(d => d.domain === 'media_player' && /^media_player\.zidoo_/.test(d.entityId))
    .map(d => ({ entityId: d.entityId, name: d.name || d.entityId }));
}

function zidooCardFormHtml() {
  const players = zidooCardPlayers();
  // Ticked as a new card's, so the sections count only what differs from it.
  const check = (id, key) => {
    const def = ZIDOO_SWITCHES.find(s => s[0] === id)[2];
    return `<label class="inline-check"><input type="checkbox" id="${id}"${def ? ' checked' : ''}> ${I18N.t('web_card_zidoo_' + key)}</label>`;
  };
  const picker = players.length
    ? `<select id="optZidooEntity" onchange="onTvEntityChange()">${players.map(p => `<option value="${cxEsc(p.entityId)}">${cxEsc(p.name)} — ${cxEsc(p.entityId)}</option>`).join('')}</select>`
    : `<input type="text" id="optZidooEntity" placeholder="media_player.zidoo_…" onchange="onTvEntityChange()">
       <div class="hint">${I18N.t('web_card_zidoo_no_player')}</div>`;
  return `
    <label>${I18N.t('web_card_name_optional_friendly')}</label><input type="text" id="optName" placeholder="${I18N.t('web_card_zidoo_name_ph')}">
    <label>${I18N.t('web_card_zidoo_player')}</label>${picker}
    <div class="hint">${I18N.t('web_card_zidoo_hint')}</div>
    ${check('optZidooSources', 'show_sources')}
    ${check('optZidooKeypad', 'show_keypad')}
    ${check('optZidooTracks', 'show_tracks')}
    ${check('optZidooQueue', 'show_queue')}
    ${check('optZidooIo', 'show_io')}
    ${check('optZidooSkip', 'show_skip')}
    ${check('optZidooRepeat', 'show_repeat')}
    ${check('optZidooVolume', 'show_volume')}
    ${check('optZidooQuality', 'show_quality')}
    ${check('optZidooApps', 'show_apps')}
    <div id="zidooAppsField">
      <h4 style="margin:12px 0 0">${I18N.t('web_card_tv_apps')}</h4>
      <div class="hint">${I18N.t('web_card_zidoo_apps_hint')}</div>
      ${tvAppsSectionHtml(false)}
    </div>
  `;
}

function fillZidooCardForm(o) {
  document.getElementById('optName').value = o.name || '';
  const field = document.getElementById('optZidooEntity');
  const entity = o.entity || '';
  if (field.tagName === 'SELECT' && entity && ![...field.options].some(op => op.value === entity)) {
    // A player no longer in the catalogue keeps its id.
    field.insertAdjacentHTML('beforeend', `<option value="${cxEsc(entity)}">${cxEsc(entity)}</option>`);
  }
  if (entity || field.tagName !== 'SELECT') field.value = entity;
  ZIDOO_SWITCHES.forEach(([id, key, def]) => { document.getElementById(id).checked = o[key] === undefined ? def : o[key] !== false; });
  fillMediaApps(o); // the card's own apps and their style (cards-extra.js)
  // What the form doesn't show is kept as it is.
  window._zidooExtra = Object.fromEntries(Object.entries(o).filter(([k]) =>
    !['name', 'entity', 'apps', 'apps_style'].includes(k) && !ZIDOO_SWITCHES.some(s => s[1] === k)));
}

/** Writes the form into [out]; false (with a message) when no player is chosen. */
function collectZidooCardForm(out) {
  const entity = document.getElementById('optZidooEntity').value.trim();
  if (!entity) {
    alert(I18N.t('web_card_zidoo_alert_player'));
    return false;
  }
  Object.assign(out, window._zidooExtra || {});
  collectMediaApps(out); // none listed: the player's own apps
  const name = document.getElementById('optName').value.trim();
  if (name) out.name = name;
  out.entity = entity;
  ZIDOO_SWITCHES.forEach(([id, key, def]) => {
    const on = document.getElementById(id).checked;
    if (on !== def) out[key] = on; else delete out[key];
  });
  window._zidooExtra = null;
  return true;
}

/** The builder's preview: the card as it shows a film, or music on an Eversolo. */
function zidooCardPreviewHtml(card, idx) {
  const o = card.options || {};
  const e = (typeof haStates !== 'undefined' && haStates && haStates[o.entity]) || null;
  const a = (e && e.attributes) || {};
  const eversolo = a.manufacturer ? a.manufacturer === 'Eversolo' : /eversolo/i.test((o.entity || '') + ' ' + (o.name || ''));
  const name = o.name || a.friendly_name || (eversolo ? 'Eversolo' : 'Zidoo');
  const video = eversolo ? false : a.media_content_type !== 'music';
  const on = sw => o[sw[1]] === undefined ? sw[2] : o[sw[1]] !== false;
  const show = key => on(ZIDOO_SWITCHES.find(s => s[1] === key));
  const title = a.media_title || (video ? 'Tous les matins du monde' : 'Fight Fire with Fire');
  const sub = video
    ? (a.media_series_title || 'Kaamelott') + ' · ' + I18N.t('web_card_zidoo_preview_season')
    : (a.media_artist || 'Metallica') + ' · ' + (a.media_album_name || 'Ride the Lightning');
  const chip = label => `<span style="flex:1;height:34px;border-radius:9px;background:#24323a;font-size:11px;display:flex;align-items:center;justify-content:center;color:#E6EEF2">${cxEsc(label)}</span>`;
  const chips = [];
  if (video && show('show_tracks')) chips.push(I18N.t('web_card_zidoo_subtitles'), I18N.t('web_card_zidoo_audio'), I18N.t('web_card_zidoo_chapters'));
  if (!video && show('show_queue')) chips.push(I18N.t('web_card_zidoo_queue'));
  if (eversolo && show('show_io')) chips.push(I18N.t('web_card_zidoo_input'), I18N.t('web_card_zidoo_output'));
  const btn = (s, accent) => `<span style="width:${s}px;height:${s}px;border-radius:${s / 2}px;background:${accent ? '#00E5FF' : '#2a383d'};display:inline-block"></span>`;
  const transport = [
    video && show('show_skip') ? btn(30) : '', !video && show('show_repeat') ? btn(30) : '',
    btn(34), btn(44, true), btn(34), video && show('show_skip') ? btn(30) : ''
  ].join('');
  return `
    <div class="card">
      <div class="card-title"><span>zidoo</span><span><span class="remove" style="color:#00E5FF" onclick="editCard(${idx})">✎</span> <span class="remove" onclick="removeCard(${idx})">✕</span></span></div>
      <div style="display:flex;flex-direction:column;gap:8px">
        <div style="display:flex;align-items:center;gap:6px"><b style="flex:1;color:#E6EEF2">${cxEsc(name)}</b>${btn(26)}${show('show_sources') ? btn(26) : ''}${show('show_keypad') && !eversolo ? btn(26) : ''}${btn(26)}</div>
        <div style="height:${video ? 110 : 120}px;${video ? '' : 'width:120px;align-self:center;'}border-radius:10px;background:#2f4248"></div>
        <div style="text-align:center"><b style="color:#E6EEF2;font-size:13px">${cxEsc(title)}</b><div style="color:#9FB6BD;font-size:11px">${cxEsc(sub)}</div>
          ${show('show_quality') && !video ? '<div style="color:#748790;font-size:10px">FLAC · 44.1 kHz · 16 bit</div>' : ''}</div>
        <div style="height:4px;border-radius:2px;background:#2f4248"><div style="width:32%;height:4px;border-radius:2px;background:#00E5FF"></div></div>
        <div style="display:flex;justify-content:center;gap:12px;align-items:center">${transport}</div>
        ${chips.length ? `<div style="display:flex;gap:6px">${chips.map(chip).join('')}</div>` : ''}
        ${show('show_volume') ? `<div style="display:flex;justify-content:center;gap:12px">${btn(26)}${btn(26)}${btn(26)}</div>` : ''}
        ${show('show_apps') ? `<div class="hint">${I18N.t('web_card_zidoo_preview_apps')}</div>` : ''}
      </div>
      <div class="hint" style="margin-top:6px">${I18N.t('web_preview_entity', cxEsc(o.entity || 'media_player.zidoo_…'))}</div>
    </div>`;
}
