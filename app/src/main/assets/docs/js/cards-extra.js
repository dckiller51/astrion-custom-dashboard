// Builder forms added in 1.2.9 for the cards that used to be raw JSON only
// (speaker_group, row, picture_elements) and the tv_remote's apps/commands.
//
// Shared pattern, same as the monitor rows: each list lives in a window._pending*
// array while the card dialog is open; before any re-render the list is read
// back from the DOM (cxSync*), so typing never gets lost when a row is added,
// moved or removed. Options this form doesn't know about are kept as they are
// (window._*Extra), so editing a hand-written card never drops anything.

function cxEsc(v) {
  return String(v == null ? '' : v).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

function cxCatalog(domain) {
  return (dashboardData.haDevices || []).filter(d => d.domain === domain);
}

// An entity field inside a list row: a <select> over the Devices catalog when
// it has entries for that domain (an entity not in it stays selectable, as in
// setEntitySelectValue), otherwise a text field with Home Assistant
// autocomplete. `cls` identifies the field when reading the row back.
function cxEntityPickerHtml(domain, value, cls) {
  const options = domain ? cxCatalog(domain) : [];
  if (options.length) {
    const known = options.some(d => d.entityId === value);
    return `<select class="${cls}" style="flex:3;min-width:0;margin:0">
      <option value="">${I18N.t('web_card_select')}</option>
      ${value && !known ? `<option value="${cxEsc(value)}" selected>${cxEsc(I18N.t('web_not_in_catalog', value))}</option>` : ''}
      ${options.map(d => `<option value="${cxEsc(d.entityId)}"${d.entityId === value ? ' selected' : ''}>${cxEsc(d.name)}</option>`).join('')}
    </select>`;
  }
  return `<input type="text" class="${cls} cx-ac" data-domain="${domain || ''}" value="${cxEsc(value)}" placeholder="${domain ? domain + '.' : 'light.'}…" style="flex:3;min-width:0;margin:0">`;
}

function cxAttachAutocomplete(root) {
  if (!root || typeof attachEntityAutocomplete !== 'function') return;
  root.querySelectorAll('input.cx-ac').forEach(el => attachEntityAutocomplete(el, el.dataset.domain || null));
}

function cxMoveButtons(fn, i) {
  return `<span class="remove" style="color:#00E5FF" title="${I18N.t('web_preview_move_up')}" onclick="${fn}(${i}, -1)">↑</span>
    <span class="remove" style="color:#00E5FF" title="${I18N.t('web_preview_move_down')}" onclick="${fn}(${i}, 1)">↓</span>`;
}

function cxSwap(list, i, delta) {
  const j = i + delta;
  if (j < 0 || j >= list.length) return false;
  [list[i], list[j]] = [list[j], list[i]];
  return true;
}

function cxWithout(o, keys) {
  return Object.fromEntries(Object.entries(o || {}).filter(([k]) => !keys.includes(k)));
}

// ---- speaker_group ----------------------------------------------------------

function speakerGroupFormHtml() {
  return `
    <label>${I18N.t('web_card_sg_name')}</label><input type="text" id="optSgName" placeholder="${I18N.t('web_card_ph_name_room')}">
    ${haEntityFieldHtml('media_player', 'optSgMaster', I18N.t('web_card_sg_master'))}
    <div class="hint">${I18N.t('web_card_sg_hint')}</div>
    <label>${I18N.t('web_card_sg_speakers')}</label>
    <div id="sgSpeakersList"></div>
    <button type="button" class="secondary" onclick="addSgSpeaker()">${I18N.t('web_card_sg_add')}</button>
  `;
}

function renderSgSpeakers() {
  const list = document.getElementById('sgSpeakersList');
  if (!list) return;
  const rows = window._pendingSpeakers || [];
  if (!rows.length) { list.innerHTML = `<div class="hint">${I18N.t('web_card_sg_none')}</div>`; return; }
  list.innerHTML = rows.map((r, i) => `
    <div class="list-item sg-row" style="gap:6px;align-items:center">
      ${cxEntityPickerHtml('media_player', r.entity_id, 'sg-entity')}
      <input type="text" class="sg-name" value="${cxEsc(r.name)}" placeholder="${I18N.t('web_card_monitor_name_ph')}" style="flex:2;min-width:0;margin:0">
      ${cxMoveButtons('moveSgSpeaker', i)}
      <span class="remove" onclick="removeSgSpeaker(${i})">✕</span>
    </div>`).join('');
  cxAttachAutocomplete(list);
}

function syncSgSpeakers() {
  const rows = window._pendingSpeakers || [];
  document.querySelectorAll('#sgSpeakersList .sg-row').forEach((el, i) => {
    if (!rows[i]) return;
    rows[i].entity_id = el.querySelector('.sg-entity').value.trim();
    rows[i].name = el.querySelector('.sg-name').value.trim();
  });
}

function addSgSpeaker() { syncSgSpeakers(); (window._pendingSpeakers = window._pendingSpeakers || []).push({ entity_id: '', name: '' }); renderSgSpeakers(); }
function removeSgSpeaker(i) { syncSgSpeakers(); window._pendingSpeakers.splice(i, 1); renderSgSpeakers(); }
function moveSgSpeaker(i, d) { syncSgSpeakers(); if (cxSwap(window._pendingSpeakers, i, d)) renderSgSpeakers(); }

function fillSpeakerGroupForm(o) {
  document.getElementById('optSgName').value = o.name || '';
  setEntitySelectValue('optSgMaster', o.master);
  window._pendingSpeakers = (Array.isArray(o.speakers) ? o.speakers : []).map(s => ({ ...s }));
  window._sgExtra = cxWithout(o, ['name', 'master', 'speakers']);
  renderSgSpeakers();
}

/** Returns the options, or null (after an alert) when the form isn't valid. */
function collectSpeakerGroupOptions() {
  syncSgSpeakers();
  const master = document.getElementById('optSgMaster').value.trim();
  if (!master) { alert(I18N.t('web_card_sg_err_master')); return null; }
  const out = { ...(window._sgExtra || {}), master };
  const name = document.getElementById('optSgName').value.trim();
  if (name) out.name = name;
  out.speakers = (window._pendingSpeakers || [])
    .filter(s => s.entity_id && s.entity_id !== master)
    .map(s => { const r = { ...s }; if (!r.name) delete r.name; return r; });
  return out;
}

// ---- tv_remote ----------------------------------------------------------------

const TV_DEFAULT_APPS = [
  { name: 'Netflix', app: 'com.netflix.ninja' },
  { name: 'YouTube', app: 'com.google.android.youtube.tv' },
  { name: 'Prime Video', app: 'com.amazon.amazonvideo.livingroom' },
  { name: 'Plex', app: 'com.plexapp.android' }
];

// Common Android TV apps, offered in the app field's list.
const TV_KNOWN_APPS = [
  ['Netflix', 'com.netflix.ninja'],
  ['YouTube', 'com.google.android.youtube.tv'],
  ['Prime Video', 'com.amazon.amazonvideo.livingroom'],
  ['Disney+', 'com.disney.disneyplus'],
  ['Apple TV', 'com.apple.atve.androidtv.appletv'],
  ['Max', 'com.wbd.stream'],
  ['Plex', 'com.plexapp.android'],
  ['Kodi', 'org.xbmc.kodi'],
  ['VLC', 'org.videolan.vlc'],
  ['Spotify', 'com.spotify.tv.android'],
  ['Twitch', 'tv.twitch.android.app'],
  ['Canal+', 'com.canal.android.canal'],
  ['Molotov', 'tv.molotov.app'],
  ['Google Play Store', 'com.android.vending']
];

// Keys of the card and their default Android TV command.
const TV_COMMAND_KEYS = [
  ['power', 'POWER'], ['up', 'DPAD_UP'], ['down', 'DPAD_DOWN'], ['left', 'DPAD_LEFT'], ['right', 'DPAD_RIGHT'],
  ['center', 'DPAD_CENTER'], ['back', 'BACK'], ['home', 'HOME'], ['menu', 'MENU'],
  ['volume_up', 'VOLUME_UP'], ['volume_down', 'VOLUME_DOWN'], ['mute', 'MUTE']
];

function tvRemoteFormHtml() {
  const cmdList = (typeof ANDROID_TV_COMMANDS !== 'undefined' ? ANDROID_TV_COMMANDS : [])
    .map(c => `<option value="${cxEsc(c)}">`).join('');
  return `
    <label>${I18N.t('web_card_name')}</label><input type="text" id="optName" placeholder="${I18N.t('web_card_ph_tv_name')}">
    <label>${I18N.t('web_card_remote_entity_id')}</label><input type="text" id="optRemoteEntity" placeholder="${I18N.t('web_card_ph_remote')}">
    <label>${I18N.t('web_card_media_entity_id_opt')}</label><input type="text" id="optMediaEntity" placeholder="${I18N.t('web_card_ph_media_tv')}">
    <div class="hint">${I18N.t('web_card_tv_media_hint')}</div>
    <label class="inline-check"><input type="checkbox" id="optTvShowVolume" checked> ${I18N.t('web_card_tv_show_volume')}</label>
    <label>${I18N.t('web_card_tv_volume_entity')}</label><input type="text" id="optMuteEntity" placeholder="remote.soundbar">
    <div class="hint">${I18N.t('web_card_tv_volume_hint')}</div>
    <label>${I18N.t('web_card_tv_apps')}</label>
    <datalist id="tvAppPackages">${TV_KNOWN_APPS.map(([n, p]) => `<option value="${p}">${n}</option>`).join('')}</datalist>
    <div id="tvAppsList"></div>
    <div style="display:flex;gap:8px;flex-wrap:wrap">
      <button type="button" class="secondary" onclick="addTvApp()">${I18N.t('web_card_tv_add_app')}</button>
      <button type="button" class="secondary" onclick="resetTvApps()">${I18N.t('web_card_tv_default_apps')}</button>
    </div>
    <details style="margin-top:10px">
      <summary>${I18N.t('web_card_tv_commands')}</summary>
      <div class="hint">${I18N.t('web_card_tv_commands_hint')}</div>
      <datalist id="tvCommandList">${cmdList}</datalist>
      ${TV_COMMAND_KEYS.map(([k, def]) => `
        <div class="list-item" style="gap:6px;align-items:center">
          <span style="flex:2">${I18N.t('web_card_tv_cmd_' + k)}</span>
          <input type="text" class="tv-cmd" data-key="${k}" list="tvCommandList" placeholder="${def}" style="flex:3;min-width:0;margin:0">
        </div>`).join('')}
    </details>
  `;
}

function tvAppLabel(pkg) {
  const known = TV_KNOWN_APPS.find(([, p]) => p === pkg);
  return known ? known[0] : '';
}

function renderTvApps() {
  const list = document.getElementById('tvAppsList');
  if (!list) return;
  const apps = window._pendingTvApps || [];
  if (!apps.length) { list.innerHTML = `<div class="hint">${I18N.t('web_card_tv_no_apps')}</div>`; return; }
  list.innerHTML = apps.map((a, i) => {
    // An app calling a service (hand-written JSON) is kept and shown, not edited here.
    const main = a.service
      ? `<span style="flex:5;min-width:0">${cxEsc(a.name || '')} <span style="color:#888">→ ${cxEsc(a.service)}</span></span>`
      : `<input type="text" class="tv-app-name" value="${cxEsc(a.name)}" placeholder="${I18N.t('web_card_monitor_name_ph')}" style="flex:2;min-width:0;margin:0">
         <input type="text" class="tv-app-pkg" list="tvAppPackages" value="${cxEsc(a.app)}" placeholder="com.netflix.ninja" style="flex:3;min-width:0;margin:0" onchange="onTvAppPackage(${i}, this.value)">`;
    return `<div class="list-item tv-app-row" style="gap:6px;align-items:center">${main}
      ${cxMoveButtons('moveTvApp', i)}
      <span class="remove" onclick="removeTvApp(${i})">✕</span></div>`;
  }).join('');
}

function syncTvApps() {
  const apps = window._pendingTvApps || [];
  document.querySelectorAll('#tvAppsList .tv-app-row').forEach((el, i) => {
    const a = apps[i];
    if (!a || a.service) return;
    a.name = el.querySelector('.tv-app-name').value.trim();
    a.app = el.querySelector('.tv-app-pkg').value.trim();
  });
}

// Picking a known package fills an empty name with the app's name.
function onTvAppPackage(i, pkg) {
  syncTvApps();
  const a = (window._pendingTvApps || [])[i];
  if (a && !a.name && tvAppLabel(pkg)) { a.name = tvAppLabel(pkg); renderTvApps(); }
}

function addTvApp() { syncTvApps(); (window._pendingTvApps = window._pendingTvApps || []).push({ name: '', app: '' }); renderTvApps(); }
function removeTvApp(i) { syncTvApps(); window._pendingTvApps.splice(i, 1); renderTvApps(); }
function moveTvApp(i, d) { syncTvApps(); if (cxSwap(window._pendingTvApps, i, d)) renderTvApps(); }
function resetTvApps() { window._pendingTvApps = TV_DEFAULT_APPS.map(a => ({ ...a })); renderTvApps(); }

function fillTvRemoteForm(o) {
  document.getElementById('optName').value = o.name || '';
  document.getElementById('optRemoteEntity').value = o.remote_entity || '';
  document.getElementById('optMediaEntity').value = o.media_entity || '';
  document.getElementById('optMuteEntity').value = o.mute_entity || '';
  document.getElementById('optTvShowVolume').checked = o.show_volume !== false;
  // No "apps" in the card = the app's defaults: show them, so what's listed is what the remote shows.
  window._pendingTvApps = (Array.isArray(o.apps) ? o.apps : TV_DEFAULT_APPS).map(a => ({ ...a }));
  const cmds = o.commands || {};
  document.querySelectorAll('.tv-cmd').forEach(el => { el.value = cmds[el.dataset.key] || ''; });
  window._tvExtra = cxWithout(o, ['name', 'remote_entity', 'media_entity', 'mute_entity', 'show_volume', 'apps', 'commands']);
  // Command renames for keys this form doesn't list are kept too.
  window._tvExtraCommands = cxWithout(cmds, TV_COMMAND_KEYS.map(([k]) => k));
  renderTvApps();
}

function initTvRemoteForm() {
  window._pendingTvApps = TV_DEFAULT_APPS.map(a => ({ ...a }));
  window._tvExtra = {};
  window._tvExtraCommands = {};
  renderTvApps();
}

function collectTvRemoteOptions() {
  syncTvApps();
  const remote = document.getElementById('optRemoteEntity').value.trim();
  if (!remote) { alert(I18N.t('web_card_tv_err_remote')); return null; }
  const out = { ...(window._tvExtra || {}), name: document.getElementById('optName').value.trim() || 'TV', remote_entity: remote };
  const media = document.getElementById('optMediaEntity').value.trim();
  if (media) out.media_entity = media;
  const vol = document.getElementById('optMuteEntity').value.trim();
  if (vol) out.mute_entity = vol;
  if (!document.getElementById('optTvShowVolume').checked) out.show_volume = false;
  out.apps = (window._pendingTvApps || []).filter(a => a.service || a.app).map(a => {
    const r = { ...a };
    if (!r.name) r.name = tvAppLabel(r.app) || r.app;
    return r;
  });
  const commands = { ...(window._tvExtraCommands || {}) };
  document.querySelectorAll('.tv-cmd').forEach(el => { if (el.value.trim()) commands[el.dataset.key] = el.value.trim(); });
  if (Object.keys(commands).length) out.commands = commands;
  return out;
}

// ---- picture_elements -----------------------------------------------------------

function pictureElementsFormHtml() {
  return `
    ${iconFieldHtml('optPeImage')}
    <div class="hint">${I18N.t('web_card_pe_image_hint')}</div>
    <div id="pePlanWrap" style="position:relative;margin:8px 0;display:none;cursor:crosshair;border-radius:8px;overflow:hidden;background:#111">
      <img id="pePlanImg" alt="" style="display:block;width:100%" onload="renderPePlan()" onerror="document.getElementById('pePlanWrap').style.display='none'">
      <div id="pePlanDots" style="position:absolute;inset:0"></div>
    </div>
    <div class="hint" id="pePlanHint" style="display:none">${I18N.t('web_card_pe_click_hint')}</div>
    <label>${I18N.t('web_card_pe_elements')}</label>
    <div id="peElementsList"></div>
    <button type="button" class="secondary" onclick="addPeElement()">${I18N.t('web_card_pe_add')}</button>
    <details style="margin-top:10px">
      <summary>${I18N.t('web_card_pe_advanced')}</summary>
      <label>${I18N.t('web_card_pe_aspect')}</label><input type="number" id="optPeAspect" step="0.05" min="0.2" placeholder="1.3">
      <label>${I18N.t('web_card_pe_vacuum')}</label>
      <textarea id="optPeVacuum" rows="4" placeholder='{"entity_id": "vacuum.robot", "dock_position": [12, 85]}'></textarea>
      <label>${I18N.t('web_card_pe_radar')}</label>
      <textarea id="optPeRadar" rows="3" placeholder='{"prefix": "sensor.ld2450_target", "targets": 3}'></textarea>
      <div class="hint">${I18N.t('web_card_pe_advanced_hint')}</div>
    </details>
  `;
}

function peIconKind(icon) {
  if (!icon) return 'bulb';
  if (icon === 'power') return 'power';
  return 'png';
}

function renderPeElements() {
  const list = document.getElementById('peElementsList');
  if (!list) return;
  const els = window._pendingPeElements || [];
  if (window._pePlacing == null || window._pePlacing >= els.length) window._pePlacing = els.length ? 0 : null;
  if (!els.length) { list.innerHTML = `<div class="hint">${I18N.t('web_card_pe_none')}</div>`; renderPePlan(); return; }
  list.innerHTML = els.map((e, i) => {
    const mode = 'service' in e ? 'service' : 'entity';
    const kind = peIconKind(e.icon);
    return `
    <div class="section-box pe-row" style="padding:8px;margin-bottom:6px${window._pePlacing === i ? ';border-color:#00E5FF' : ''}">
      <div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap">
        <label class="inline-check" style="margin:0"><input type="radio" name="pePlacing" ${window._pePlacing === i ? 'checked' : ''} onchange="setPePlacing(${i})"> <b>#${i + 1}</b></label>
        <span style="font-size:0.8rem;color:#aaa">${I18N.t('web_card_pe_left')}</span><input type="number" class="pe-left" value="${e.left ?? 50}" min="0" max="100" step="0.5" style="width:70px;margin:0" onchange="syncPeElements();renderPePlan()">
        <span style="font-size:0.8rem;color:#aaa">${I18N.t('web_card_pe_top')}</span><input type="number" class="pe-top" value="${e.top ?? 50}" min="0" max="100" step="0.5" style="width:70px;margin:0" onchange="syncPeElements();renderPePlan()">
        <span style="flex:1"></span>
        ${cxMoveButtons('movePeElement', i)}
        <span class="remove" onclick="removePeElement(${i})">✕</span>
      </div>
      <div style="display:flex;gap:6px;align-items:center;margin-top:6px">
        <select class="pe-mode" style="flex:2;min-width:0;margin:0" onchange="syncPeElements();renderPeElements()">
          <option value="entity"${mode === 'entity' ? ' selected' : ''}>${I18N.t('web_card_pe_mode_entity')}</option>
          <option value="service"${mode === 'service' ? ' selected' : ''}>${I18N.t('web_card_pe_mode_service')}</option>
        </select>
        ${mode === 'entity'
          ? cxEntityPickerHtml(null, e.entity_id, 'pe-entity')
          : `<input type="text" class="pe-service" value="${cxEsc(e.service)}" placeholder="light.turn_off" style="flex:3;min-width:0;margin:0">`}
      </div>
      ${mode === 'service' ? `<input type="text" class="pe-targets" value="${cxEsc((e.targets || []).join(', '))}" placeholder="${I18N.t('web_card_pe_targets_ph')}" style="margin:6px 0 0">` : ''}
      <div style="display:flex;gap:6px;align-items:center;margin-top:6px">
        <select class="pe-icon-kind" style="flex:2;min-width:0;margin:0" onchange="syncPeElements();renderPeElements()">
          <option value="bulb"${kind === 'bulb' ? ' selected' : ''}>${I18N.t('web_card_pe_icon_bulb')}</option>
          <option value="power"${kind === 'power' ? ' selected' : ''}>${I18N.t('web_card_pe_icon_power')}</option>
          <option value="png"${kind === 'png' ? ' selected' : ''}>${I18N.t('web_card_pe_icon_png')}</option>
        </select>
        ${kind === 'png' ? `<input type="text" class="pe-icon-path" id="peIcon_${i}" value="${cxEsc(e.icon)}" placeholder="/sdcard/astrion/icons/lamp.png" style="flex:3;min-width:0;margin:0">
          <button type="button" class="secondary" style="margin:0" onclick="openIconPicker('peIcon_${i}')">${I18N.t('web_icon_choose')}</button>` : ''}
      </div>
      <div style="display:flex;gap:6px;align-items:center;margin-top:6px">
        <span style="font-size:0.8rem;color:#aaa;flex:2">${I18N.t('web_card_pe_on_color')}</span>
        <input type="text" class="pe-on-color" value="${cxEsc(e.on_color)}" placeholder="#66FFC107" style="flex:3;min-width:0;margin:0">
      </div>
    </div>`;
  }).join('');
  cxAttachAutocomplete(list);
  renderPePlan();
}

function syncPeElements() {
  const els = window._pendingPeElements || [];
  document.querySelectorAll('#peElementsList .pe-row').forEach((row, i) => {
    const e = els[i];
    if (!e) return;
    const num = (sel, def) => { const v = parseFloat(row.querySelector(sel).value); return isNaN(v) ? def : Math.min(100, Math.max(0, v)); };
    e.left = num('.pe-left', 50);
    e.top = num('.pe-top', 50);
    const mode = row.querySelector('.pe-mode').value;
    if (mode === 'entity') {
      const ent = row.querySelector('.pe-entity');
      if (ent) { e.entity_id = ent.value.trim(); delete e.service; delete e.targets; } else { delete e.service; delete e.targets; }
    } else {
      const svc = row.querySelector('.pe-service');
      e.service = svc ? svc.value.trim() : (e.service || '');
      const tg = row.querySelector('.pe-targets');
      e.targets = tg ? tg.value.split(',').map(s => s.trim()).filter(Boolean) : (e.targets || []);
      delete e.entity_id;
    }
    const kind = row.querySelector('.pe-icon-kind').value;
    const path = row.querySelector('.pe-icon-path');
    e.icon = kind === 'power' ? 'power' : kind === 'png' ? (path ? path.value.trim() : (peIconKind(e.icon) === 'png' ? e.icon : '')) : '';
    e.on_color = row.querySelector('.pe-on-color').value.trim();
  });
}

function setPePlacing(i) { syncPeElements(); window._pePlacing = i; renderPeElements(); }
function addPeElement() {
  syncPeElements();
  (window._pendingPeElements = window._pendingPeElements || []).push({ left: 50, top: 50, entity_id: '' });
  window._pePlacing = window._pendingPeElements.length - 1;
  renderPeElements();
}
function removePeElement(i) { syncPeElements(); window._pendingPeElements.splice(i, 1); renderPeElements(); }
function movePeElement(i, d) {
  syncPeElements();
  if (cxSwap(window._pendingPeElements, i, d)) { if (window._pePlacing === i) window._pePlacing = i + d; renderPeElements(); }
}

// The floor plan, with a dot per element: only when the builder runs on the remote
// (the image comes from its /icons/). A click moves the selected element there.
function updatePePlanImage() {
  const img = document.getElementById('pePlanImg');
  const wrap = document.getElementById('pePlanWrap');
  if (!img || !wrap) return;
  const url = iconUrl(document.getElementById('optPeImage').value.trim());
  if (!url) { wrap.style.display = 'none'; document.getElementById('pePlanHint').style.display = 'none'; return; }
  if (img.getAttribute('src') !== url) img.setAttribute('src', url);
  else renderPePlan();
}

function renderPePlan() {
  const wrap = document.getElementById('pePlanWrap');
  const img = document.getElementById('pePlanImg');
  const dots = document.getElementById('pePlanDots');
  if (!wrap || !img || !dots) return;
  if (!img.getAttribute('src') || !img.naturalWidth) return;
  wrap.style.display = 'block';
  document.getElementById('pePlanHint').style.display = 'block';
  const els = window._pendingPeElements || [];
  dots.innerHTML = els.map((e, i) => `
    <div style="position:absolute;left:${e.left ?? 50}%;top:${e.top ?? 50}%;transform:translate(-50%,-50%);width:24px;height:24px;border-radius:50%;
      background:${window._pePlacing === i ? '#00E5FF' : 'rgba(0,0,0,.55)'};color:${window._pePlacing === i ? '#000' : '#fff'};
      font-size:11px;font-weight:bold;display:flex;align-items:center;justify-content:center;border:2px solid #fff;pointer-events:none">${i + 1}</div>`).join('');
}

function onPePlanClick(ev) {
  const els = window._pendingPeElements || [];
  if (window._pePlacing == null || !els[window._pePlacing]) return;
  syncPeElements();
  const rect = document.getElementById('pePlanImg').getBoundingClientRect();
  const round = v => Math.round(Math.min(100, Math.max(0, v)) * 2) / 2;
  els[window._pePlacing].left = round(((ev.clientX - rect.left) / rect.width) * 100);
  els[window._pePlacing].top = round(((ev.clientY - rect.top) / rect.height) * 100);
  renderPeElements();
}

function initPictureElementsForm() {
  window._pendingPeElements = [];
  window._pePlacing = null;
  window._peExtra = {};
  const img = document.getElementById('optPeImage');
  img.addEventListener('input', updatePePlanImage);
  img.addEventListener('change', updatePePlanImage);
  document.getElementById('pePlanWrap').addEventListener('click', onPePlanClick);
  renderPeElements();
}

function fillPictureElementsForm(o) {
  document.getElementById('optPeImage').value = o.image || '';
  updateIconThumb('optPeImage');
  document.getElementById('optPeAspect').value = o.aspect ?? '';
  document.getElementById('optPeVacuum').value = o.vacuum ? JSON.stringify(o.vacuum, null, 2) : '';
  document.getElementById('optPeRadar').value = o.radar ? JSON.stringify(o.radar, null, 2) : '';
  window._pendingPeElements = (Array.isArray(o.elements) ? o.elements : []).map(e => ({ ...e }));
  window._pePlacing = window._pendingPeElements.length ? 0 : null;
  window._peExtra = cxWithout(o, ['image', 'aspect', 'vacuum', 'radar', 'elements']);
  renderPeElements();
  updatePePlanImage();
}

function collectPictureElementsOptions() {
  syncPeElements();
  const out = { ...(window._peExtra || {}) };
  const image = document.getElementById('optPeImage').value.trim();
  if (image) out.image = image;
  const aspect = parseFloat(document.getElementById('optPeAspect').value);
  if (!isNaN(aspect) && aspect > 0) out.aspect = aspect;
  for (const [id, key] of [['optPeVacuum', 'vacuum'], ['optPeRadar', 'radar']]) {
    const raw = document.getElementById(id).value.trim();
    if (!raw) continue;
    try { out[key] = JSON.parse(raw); } catch (e) { alert(I18N.t('web_card_pe_err_json', key)); return null; }
  }
  out.elements = (window._pendingPeElements || [])
    .filter(e => e.entity_id || e.service)
    .map(e => {
      const r = { ...e };
      ['icon', 'on_color', 'entity_id', 'service'].forEach(k => { if (!r[k]) delete r[k]; });
      if (!r.targets || !r.targets.length) delete r.targets;
      return r;
    });
  return out;
}

// ---- row ---------------------------------------------------------------------
// The row's cards are edited with the normal card form: "✎" / "+ Add a card to
// the row" switch the same dialog to that child card (window._rowCtx.active),
// its Save button puts the card back into the row, and Close returns to the row.

function rowFormHtml() {
  return `
    <div class="hint">${I18N.t('web_card_row_hint')}</div>
    <label>${I18N.t('web_card_row_cards')}</label>
    <div id="rowChildrenList"></div>
    <button type="button" class="secondary" onclick="editRowChild(null)">${I18N.t('web_card_row_add')}</button>
  `;
}

function rowChildLabel(c) {
  const o = c.options || {};
  return o.title || o.name || (typeof haFriendlyName === 'function' && haFriendlyName(o.entity_id)) || o.entity_id || o.master || '';
}

function renderRowChildren() {
  const list = document.getElementById('rowChildrenList');
  if (!list) return;
  const kids = (window._rowCtx && window._rowCtx.children) || [];
  if (!kids.length) { list.innerHTML = `<div class="hint">${I18N.t('web_card_row_none')}</div>`; return; }
  list.innerHTML = kids.map((c, i) => `
    <div class="list-item" style="gap:6px;align-items:center">
      <span style="flex:1;min-width:0"><b>${cxEsc(c.type)}</b> <span style="color:#888">${cxEsc(rowChildLabel(c))}</span></span>
      <span class="remove" style="color:#00E5FF" onclick="editRowChild(${i})">✎</span>
      ${cxMoveButtons('moveRowChild', i)}
      <span class="remove" onclick="removeRowChild(${i})">✕</span>
    </div>`).join('') +
    (kids.length > 3 ? `<div class="hint" style="color:#ffb74d">${I18N.t('web_card_row_too_many')}</div>` : '');
}

function moveRowChild(i, d) { if (cxSwap(window._rowCtx.children, i, d)) renderRowChildren(); }
function removeRowChild(i) { window._rowCtx.children.splice(i, 1); renderRowChildren(); }

function initRowForm() {
  if (!window._rowCtx) window._rowCtx = { children: [], extra: {}, active: false, childIdx: null };
  renderRowChildren();
}

function fillRowForm(o) {
  window._rowCtx = {
    children: (Array.isArray(o.cards) ? o.cards : []).map(c => JSON.parse(JSON.stringify(c))),
    extra: cxWithout(o, ['cards']),
    active: false,
    childIdx: null
  };
  renderRowChildren();
}

function collectRowOptions() {
  const ctx = window._rowCtx || { children: [], extra: {} };
  if (!ctx.children.length) { alert(I18N.t('web_card_row_err_empty')); return null; }
  return { ...ctx.extra, cards: ctx.children };
}

function setRowOptionEnabled(enabled) {
  const opt = document.querySelector('#cardTypeSelect option[value="row"]');
  if (opt) opt.disabled = !enabled;
}

// Clears what the per-type forms keep while the dialog is open (not the row itself).
function resetPendingCardForms() {
  window._pendingGridItems = [];
  window._pendingVacuumRooms = [];
  window._pendingMonitorRows = [];
  window._monitorExtraOptions = {};
  window._pendingSpeakers = [];
  window._pendingTvApps = [];
  window._pendingPeElements = [];
}

function editRowChild(i) {
  const ctx = window._rowCtx;
  if (!ctx) return;
  ctx.active = true;
  ctx.childIdx = i;
  resetPendingCardForms();
  setRowOptionEnabled(false);
  const select = document.getElementById('cardTypeSelect');
  const child = i == null ? null : ctx.children[i];
  document.getElementById('cardEditorTitle').textContent = I18N.t(child ? 'web_card_row_edit_child' : 'web_card_row_add_child');
  document.getElementById('addCardBtn').innerText = I18N.t('web_card_row_save_child');
  if (child) {
    const known = Array.from(select.options).map(o => o.value).filter(v => v !== 'custom' && v !== 'row');
    select.value = known.includes(child.type) ? child.type : 'custom';
  } else {
    select.value = select.options[0].value;
  }
  updateCardFormInputs();
  if (child) fillCardForm(child);
}

// Called by addCardToPage() in child mode, with the card the form just built.
function rowChildSaved(card) {
  const ctx = window._rowCtx;
  if (ctx.childIdx == null) ctx.children.push(card);
  else ctx.children[ctx.childIdx] = card;
  returnToRowForm();
}

function returnToRowForm() {
  const ctx = window._rowCtx;
  ctx.active = false;
  ctx.childIdx = null;
  resetPendingCardForms();
  setRowOptionEnabled(true);
  const isNew = editingCard === null;
  document.getElementById('cardEditorTitle').textContent = isNew ? I18N.t('web_modal_add_card') : I18N.t('web_modal_edit_card');
  document.getElementById('addCardBtn').innerText = isNew ? I18N.t('web_modal_add_card_to_page') : I18N.t('web_save_changes');
  document.getElementById('cardTypeSelect').value = 'row';
  updateCardFormInputs();
}

// ---- previews ----------------------------------------------------------------

function rowPreviewHtml(o) {
  const kids = Array.isArray(o.cards) ? o.cards : [];
  if (!kids.length) return `<div class="hint">${I18N.t('web_card_row_none')}</div>`;
  return `<div style="display:flex;gap:8px">${kids.map(c => `
    <div style="flex:1;min-width:0;background:#1B2027;border-radius:12px;padding:10px;text-align:center">
      <div style="font-size:10px;color:#748790">${cxEsc(c.type)}</div>
      <div style="font-size:13px;color:#E6EEF2;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${cxEsc(rowChildLabel(c)) || '&nbsp;'}</div>
    </div>`).join('')}</div>`;
}

function speakerGroupPreviewHtml(o) {
  const rows = [{ entity_id: o.master, name: o.name, master: true }].concat(Array.isArray(o.speakers) ? o.speakers : []);
  return `<div style="font-size:11px;color:#748790;letter-spacing:1px;margin-bottom:6px">${I18N.t('web_card_sg_preview_title')}</div>` +
    rows.filter(r => r.entity_id).map(r => `
      <div style="display:flex;gap:8px;align-items:center;margin:4px 0">
        <span style="color:${r.master ? '#00E5FF' : '#748790'}">${r.master ? '🔊' : '○'}</span>
        <span style="flex:1;color:#E6EEF2">${cxEsc(r.name || (typeof haFriendlyName === 'function' && haFriendlyName(r.entity_id)) || r.entity_id)}</span>
        <span style="color:#748790;font-size:11px">—</span>
      </div>`).join('');
}

function pictureElementsPreviewHtml(o) {
  const url = iconUrl(o.image);
  const els = Array.isArray(o.elements) ? o.elements : [];
  const dots = els.map(e => `<div style="position:absolute;left:${e.left ?? 50}%;top:${e.top ?? 50}%;transform:translate(-50%,-50%);width:20px;height:20px;border-radius:50%;background:rgba(0,0,0,.45);color:#fff;font-size:11px;display:flex;align-items:center;justify-content:center">${e.icon === 'power' ? '⏻' : '💡'}</div>`).join('');
  return url
    ? `<div style="position:relative;border-radius:12px;overflow:hidden;background:#111"><img src="${url}" alt="" style="display:block;width:100%" onerror="this.style.display='none'">${dots}</div>`
    : `<div style="position:relative;aspect-ratio:${o.aspect || 1.3};border-radius:12px;background:#1B2027">${dots}</div>`;
}

function tvRemotePreviewHtml(o) {
  const apps = Array.isArray(o.apps) ? o.apps : TV_DEFAULT_APPS;
  const btn = (t) => `<div style="width:40px;height:40px;border-radius:50%;background:#1B2027;display:flex;align-items:center;justify-content:center;color:#B6C9CE">${t}</div>`;
  return `
    <div style="display:flex;justify-content:space-between;align-items:center"><b style="color:#E6EEF2">${cxEsc(o.name || 'TV')}</b>${btn('⏻')}</div>
    <div style="display:flex;justify-content:center;margin:10px 0"><div style="width:110px;height:110px;border-radius:50%;background:#1B2027;display:flex;align-items:center;justify-content:center;color:#B6C9CE">OK</div></div>
    <div style="display:flex;justify-content:space-evenly">${btn('↩')}${btn('⌂')}${btn('☰')}</div>
    ${o.show_volume === false ? '' : `<div style="display:flex;justify-content:space-evenly;margin-top:8px">${btn('−')}${btn('🔇')}${btn('+')}</div>`}
    <div style="display:grid;grid-template-columns:1fr 1fr;gap:6px;margin-top:10px">${apps.map(a => `<div style="background:#1B2027;border-radius:10px;padding:8px;text-align:center;color:#E6EEF2;font-size:12px">${cxEsc(a.name || 'App')}</div>`).join('')}</div>`;
}