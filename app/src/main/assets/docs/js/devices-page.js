// ---- Devices page --------------------------------------------------------
//
// The landing page at this device's own IP: "+ Add device" -> Home
// Assistant / Harmony Hub / IR Device, each saved immediately (no separate
// global "Save" step, unlike the dashboard builder's cards/pages/theme).
// The dashboard builder (/builder/) only *references* what's configured
// here — it has no device-editing UI of its own anymore.
//
// Two independent backing stores, both already existed before this page did:
//  - Home Assistant + Harmony Hubs live in the app's SharedPreferences
//    (RemoteSettings.kt) — read via GET /devices-config, written via the
//    existing POST /save-connection (same endpoint, just called with fetch
//    instead of a real <form> submit).
//  - IR Devices are part of dashboard.json's "irDevices" array — read/written
//    via GET/POST /dashboard.json, preserving every other field (pages,
//    cards, theme, ...) exactly as fetched, since this page never touches them.

let haConfig = { url: '', token: '', webhookId: '' };
let harmonyHubs = [];      // full [{localId, name, ip, hubId}]
let extenders = [];        // full [{localId, name, host}]
let appleTvs = [];         // full [{localId, name, entityId, host, port, serviceName, credentials}]
let appleTvScanResults = []; // last /appletv-scan result
let appleTvPairing = null;  // { sessionId, device } while waiting for the PIN
let appleTvMrpPairing = null; // { sessionId, device, localId } while waiting for the second (MRP) PIN
let appleTvMrpScanResults = []; // last /appletv-mrp-scan result
let fullDashboard = null;  // raw dashboard.json, round-tripped untouched except irDevices
let dashboardData = { irDevices: [], haDevices: [] }; // irDevices/haDevices editing lives here, mirroring the builder's own global of the same name

let editingHarmonyHubId = null;
let editingExtenderId = null;
let editingIrDevice = null;
let editingHaDevice = null;
let haStates = null; // { entity_id: {state, friendly_name, attributes} }, from /ha-states

// Slide-up toast — same behavior as the builder's (device.js), duplicated
// here since this page doesn't load that file.
let _toastTimer = null;
function showToast(msg, type) {
  const el = document.getElementById('toast');
  if (!el) { alert(msg); return; }
  el.textContent = msg;
  el.className = 'toast' + (type === 'error' ? ' error' : '');
  void el.offsetWidth;
  el.classList.add('show');
  clearTimeout(_toastTimer);
  _toastTimer = setTimeout(() => el.classList.remove('show'), type === 'error' ? 5000 : 3000);
}

// "1 device" / "3 devices": picks <base>_one or <base>_many.
function countLabel(n, base) {
  return I18N.t(base + (n === 1 ? '_one' : '_many'), n);
}

function slugify(name, fallbackPrefix) {
  const base = name
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
  return base || (fallbackPrefix + '_' + Date.now());
}

async function loadAll() {
  try {
    const res = await fetch('/devices-config');
    const data = await res.json();
    haConfig = data.ha || { url: '', token: '', webhookId: '' };
    harmonyHubs = Array.isArray(data.harmonyHubs) ? data.harmonyHubs : [];
    extenders = Array.isArray(data.extenders) ? data.extenders : [];
    appleTvs = Array.isArray(data.appleTvs) ? data.appleTvs : [];
    showRemoteIdentity(data.remote);
  } catch (e) {
    showToast(I18N.t('web_device_unreachable'), 'error');
    console.error('Failed to load /devices-config', e);
  }
  try {
    const res = await fetch('/dashboard.json');
    fullDashboard = res.ok ? await res.json() : {};
  } catch (e) {
    fullDashboard = {};
  }
  dashboardData.irDevices = fullDashboard.irDevices || [];
  dashboardData.haDevices = fullDashboard.haDevices || [];
  dashboardData.harmonyAliases = fullDashboard.harmonyAliases || {};
  await reconcileAppleTvHaDevices();
  await refreshHaStates();
  renderDevicesList();
}

/** One-time catch-up for Apple TVs paired before auto-sync into haDevices
 * existed (or added from another remote): makes sure every currently paired
 * Apple TV has a matching catalog entry, without waiting for the person to
 * rename or re-pair it. No-op, no save, once everything is already in sync. */
async function reconcileAppleTvHaDevices() {
  dashboardData.haDevices = dashboardData.haDevices || [];
  let changed = false;
  appleTvs.forEach(tv => {
    const id = 'appletv_' + tv.localId;
    const existing = dashboardData.haDevices.find(d => d.id === id);
    if (!existing) {
      dashboardData.haDevices.push({ id, domain: 'media_player', entityId: tv.entityId, name: tv.name });
      changed = true;
    } else if (existing.entityId !== tv.entityId || existing.name !== tv.name) {
      existing.entityId = tv.entityId;
      existing.name = tv.name;
      changed = true;
    }
  });
  if (changed) await saveHaDevicesSilently();
}

async function refreshHaStates() {
  try {
    const res = await fetch('/ha-states');
    const data = await res.json();
    haStates = (data && data.states) ? data.states : null;
  } catch (e) {
    haStates = null;
  }
}

function renderDevicesList() {
  const haSummary = document.getElementById('haConnectionSummary');
  if (haSummary) {
    haSummary.textContent = haConfig.url ? I18N.t('web_dev_connected_to', haConfig.url) : I18N.t('web_dev_not_connected_yet');
  }
  const tileHaCount = document.getElementById('tileHaCount');
  if (tileHaCount) {
    tileHaCount.textContent = !haConfig.url
      ? I18N.t('web_dev_not_connected')
      : countLabel(dashboardData.haDevices.length, 'web_dev_count_device');
  }
  const haList = document.getElementById('haDevicesList');
  if (haList) {
    haList.innerHTML = dashboardData.haDevices.length
      ? dashboardData.haDevices.map(dev =>
          `<div class="list-item"><span>${dev.name} <span style="color:#888">(${HA_DOMAIN_LABELS[dev.domain] ? I18N.t(HA_DOMAIN_LABELS[dev.domain]) : dev.domain} · ${dev.entityId})</span></span>` +
          `<span><span class="remove" style="color:#00E5FF" onclick="editHaEntity('${dev.id}')">✎</span> <span class="remove" onclick="removeHaEntityFromList('${dev.id}')">✕</span></span></div>`
        ).join('')
      : `<div class="hint">${I18N.t('web_dev_list_no_devices')}</div>`;
  }

  const tileHarmonyCount = document.getElementById('tileHarmonyCount');
  if (tileHarmonyCount) {
    tileHarmonyCount.textContent = harmonyHubs.length
      ? countLabel(harmonyHubs.length, 'web_dev_count_hub')
      : I18N.t('web_dev_no_hub_yet');
  }
  const harmonyList = document.getElementById('harmonyHubsList');
  if (harmonyList) {
    harmonyList.innerHTML = harmonyHubs.length
      ? harmonyHubs.map(hub => {
          const summary = hub.ip ? `${hub.ip}${hub.hubId ? ' · id ' + hub.hubId : ''}` : I18N.t('web_dev_no_ip_set');
          return `<div class="list-item"><span><span class="remove" style="color:#00E5FF;cursor:pointer" onclick="browseHarmonyDevices('${hub.localId}')">${hub.name}</span> <span style="color:#888">(${summary})</span></span>` +
            `<span><span class="remove" style="color:#00E5FF" onclick="editHarmonyHub('${hub.localId}')">✎</span> <span class="remove" onclick="removeHarmonyHub('${hub.localId}')">✕</span></span></div>`;
        }).join('')
      : `<div class="hint">${I18N.t('web_dev_list_no_hub')}</div>`;
  }

  const tileExtenderCount = document.getElementById('tileExtenderCount');
  if (tileExtenderCount) {
    tileExtenderCount.textContent = extenders.length
      ? countLabel(extenders.length, 'web_dev_count_extender')
      : I18N.t('web_dev_no_extender_yet');
  }
  const extenderList = document.getElementById('extendersList');
  if (extenderList) {
    extenderList.innerHTML = extenders.length
      ? extenders.map(ext => {
          const summary = [ext.host || I18N.t('web_dev_no_host_set'), ext.mac ? 'MAC ' + ext.mac : null].filter(Boolean).join(' · ');
          return `<div class="list-item"><span>${ext.name} <span style="color:#888">(${summary})</span></span>` +
            `<span><span class="remove" style="color:#00E5FF" onclick="editExtender('${ext.localId}')">✎</span> <span class="remove" onclick="removeExtender('${ext.localId}')">✕</span></span></div>`;
        }).join('')
      : `<div class="hint">${I18N.t('web_dev_list_no_extender')}</div>`;
  }

  const tileAppleTvCount = document.getElementById('tileAppleTvCount');
  if (tileAppleTvCount) {
    tileAppleTvCount.textContent = appleTvs.length
      ? countLabel(appleTvs.length, 'web_dev_count_atv')
      : I18N.t('web_dev_no_apple_tv_yet');
  }
  const appleTvList = document.getElementById('appleTvsList');
  if (appleTvList) {
    appleTvList.innerHTML = appleTvs.length
      ? appleTvs.map(tv => {
          const st = haStates && haStates[tv.entityId] ? haStates[tv.entityId].state : null;
          const status = st ? (st === 'unavailable' ? I18N.t('web_dev_atv_unreachable') : I18N.t('web_dev_atv_connected')) : '';
          const nowPlayingAction = tv.mrpCredentials
            ? `<span style="color:#888">${I18N.t('web_dev_atv_np_on')}</span>`
            : `<span class="remove" style="color:#00E5FF" onclick="scanAppleTvMrp('${tv.localId}')">${I18N.t('web_dev_atv_np_add')}</span>`;
          return `<div class="list-item"><span>${atvEsc(tv.name)} <span style="color:#888">(<code>${atvEsc(tv.entityId)}</code>${status ? ' · ' + status : ''})</span></span>` +
            `<span>${nowPlayingAction} <span class="remove" style="color:#00E5FF" onclick="renameAppleTv('${tv.localId}')">✎</span> <span class="remove" onclick="removeAppleTv('${tv.localId}')">✕</span></span></div>`;
        }).join('')
      : `<div class="hint">${I18N.t('web_dev_list_no_atv')}</div>`;
  }

  const tileIrCount = document.getElementById('tileIrCount');
  if (tileIrCount) {
    tileIrCount.textContent = dashboardData.irDevices.length
      ? countLabel(dashboardData.irDevices.length, 'web_dev_count_device')
      : I18N.t('web_dev_no_devices_yet');
  }
  const irList = document.getElementById('irDevicesList');
  if (irList) {
    irList.innerHTML = dashboardData.irDevices.length
      ? dashboardData.irDevices.map(dev => {
          const summary = dev.commands
            ? I18N.t(Object.keys(dev.commands).length === 1 ? 'web_ir_summary_inline_one' : 'web_ir_summary_inline_many', Object.keys(dev.commands).length)
            : I18N.t('web_ir_summary_reference', dev.brand, dev.model, dev.category);
          return `<div class="list-item"><span>${dev.name} <span style="color:#888">(${summary})</span></span>` +
            `<span><span class="remove" style="color:#00E5FF" onclick="editIrDevice('${dev.id}')">✎</span> <span class="remove" onclick="removeIrDeviceFromList('${dev.id}')">✕</span></span></div>`;
        }).join('')
      : `<div class="hint">${I18N.t('web_dev_list_no_devices')}</div>`;
  }
}

// ---- navigation: home tiles <-> one category view at a time ---------------

function showView(id) {
  document.querySelectorAll('#viewHome, .category-view').forEach(el => {
    el.style.display = el.id === id ? 'block' : 'none';
  });
  if (id !== 'viewHome') closeDeviceForms();
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

// ---- form show/hide ---------------------------------------------------------

/** Sets [id]'s display style if the element is actually in the DOM right now; a silent no-op
 * otherwise. Several flows below call closeDeviceForms()/openDeviceForm() back-to-back with other
 * renders (e.g. persistHaAndHubs() already closes every form on save, so a caller that also
 * closes them afterward would previously crash on the second, now-redundant call) — this keeps
 * that merely a no-op instead of a thrown TypeError that aborts whatever else that handler meant
 * to do (and surfaces to the user as a misleading "Save failed"/"Pairing failed" toast even when
 * the save itself already succeeded). */
function setDisplay(id, value) {
  const el = document.getElementById(id);
  if (el) el.style.display = value;
}

function closeDeviceForms() {
  setDisplay('haForm', 'none');
  setDisplay('haEntityForm', 'none');
  setDisplay('harmonyForm', 'none');
  setDisplay('extenderForm', 'none');
  setDisplay('appleTvForm', 'none');
  appleTvPairing = null;
  appleTvMrpPairing = null;
  setDisplay('irForm', 'none');
  editingHarmonyHubId = null;
  editingExtenderId = null;
  editingIrDevice = null;
  editingHaDevice = null;
}

async function openDeviceForm(type) {
  if (type === 'ha') {
    document.getElementById('haForm').style.display = '';
    document.getElementById('haUrl').value = haConfig.url || '';
    document.getElementById('haToken').value = haConfig.token || '';
    document.getElementById('haWebhookId').value = haConfig.webhookId || '';
    document.getElementById('removeHaBtn').style.display = haConfig.url ? '' : 'none';
    document.getElementById('haForm').scrollIntoView({ behavior: 'smooth', block: 'center' });
  } else if (type === 'haEntity') {
    document.getElementById('haEntityForm').style.display = '';
    document.getElementById('haEntityDomain').value = 'light';
    document.getElementById('haEntityName').value = '';
    document.getElementById('saveHaEntityBtn').textContent = I18N.t('web_save');
    document.getElementById('removeHaEntityBtn').style.display = 'none';
    await refreshHaStates(); // may have gone stale since page load, e.g. HA was just connected
    onHaEntityDomainChange();
    document.getElementById('haEntityForm').scrollIntoView({ behavior: 'smooth', block: 'center' });
  } else if (type === 'harmony') {
    document.getElementById('harmonyForm').style.display = '';
    document.getElementById('hubName').value = '';
    document.getElementById('hubIp').value = '';
    document.getElementById('hubHubId').value = '';
    document.getElementById('saveHarmonyHubBtn').textContent = I18N.t('web_save');
    document.getElementById('removeHarmonyHubBtn').style.display = 'none';
    document.getElementById('harmonyForm').scrollIntoView({ behavior: 'smooth', block: 'center' });
  } else if (type === 'extender') {
    document.getElementById('extenderForm').style.display = '';
    document.getElementById('extenderName').value = '';
    document.getElementById('extenderHost').value = '';
    document.getElementById('extenderMac').value = '';
    editingExtenderId = null;
    document.getElementById('saveExtenderBtn').textContent = I18N.t('web_save');
    document.getElementById('removeExtenderBtn').style.display = 'none';
    document.getElementById('extenderForm').scrollIntoView({ behavior: 'smooth', block: 'center' });
  } else if (type === 'appleTv') {
    document.getElementById('appleTvForm').style.display = '';
    document.getElementById('appleTvForm').scrollIntoView({ behavior: 'smooth', block: 'center' });
    scanAppleTvs();
  } else if (type === 'ir') {
    document.getElementById('irForm').style.display = '';
    renderIrTargetOptions();
    cancelIrDeviceEdit();
    document.getElementById('removeIrDeviceBtn').style.display = 'none';
    document.getElementById('irForm').scrollIntoView({ behavior: 'smooth', block: 'center' });
  }
}

/** Rebuilds the "where do commands get sent from" dropdown from the
 * current `extenders` list. Called every time the IR device form opens,
 * so it stays current even if an extender was just added/removed. */
function renderIrTargetOptions() {
  const select = document.getElementById('irTargetSelect');
  if (!select) return;
  const current = select.value;
  select.innerHTML = `<option value="">${I18N.t('web_dev_this_remote_s_own_ir_blaster')}</option>` +
    extenders.map(ext => `<option value="${ext.localId}">${ext.name}</option>`).join('');
  if (extenders.some(ext => ext.localId === current)) select.value = current;
}

// ---- This remote --------------------------------------------------------------

/** "This remote: Living room" under the title — the name Home Assistant and every event use. */
function showRemoteIdentity(remote) {
  const box = document.getElementById('remoteIdentity');
  if (!box || !remote) return;
  box.style.display = '';
  document.getElementById('remoteNameLabel').textContent = remote.name || '';
  box.title = 'ID: ' + (remote.id || '');
}

async function renameRemote() {
  const current = document.getElementById('remoteNameLabel').textContent;
  const name = prompt(I18N.t('web_dev_rename_prompt'), current);
  if (name === null) return;
  try {
    const res = await fetch('/remote-name', { method: 'POST', body: new URLSearchParams({ name }) });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    showRemoteIdentity(await res.json());
    showToast(I18N.t('web_dev_renamed'), 'success');
  } catch (e) {
    showToast(I18N.t('web_dev_rename_failed', e.message), 'error');
  }
}

// ---- Home Assistant ----------------------------------------------------------

async function saveHa() {
  haConfig = {
    url: document.getElementById('haUrl').value.trim(),
    token: document.getElementById('haToken').value.trim(),
    webhookId: document.getElementById('haWebhookId').value.trim()
  };
  await persistHaAndHubs();
}

async function removeHa() {
  if (!confirm(I18N.t('web_dev_confirm_remove_ha'))) return;
  haConfig = { url: '', token: '', webhookId: '' };
  await persistHaAndHubs();
}

/** Always resends the full Harmony hub, extender AND Apple TV lists alongside HA
 * fields — /save-connection replaces all three together, so omitting
 * either here would wipe it even though this form never touched it. */
async function persistHaAndHubs() {
  const btn = document.activeElement;
  const originalText = btn ? btn.textContent : null;
  if (btn) { btn.textContent = I18N.t('web_device_saving'); btn.disabled = true; }
  try {
    const body = new URLSearchParams();
    body.set('ha_url', haConfig.url);
    body.set('ha_token', haConfig.token);
    body.set('ha_webhook_id', haConfig.webhookId);
    harmonyHubs.forEach(hub => {
      body.append('hub_localid[]', hub.localId || '');
      body.append('hub_name[]', hub.name || '');
      body.append('hub_ip[]', hub.ip || '');
      body.append('hub_hubid[]', hub.hubId || '');
    });
    extenders.forEach(ext => {
      body.append('extender_localid[]', ext.localId || '');
      body.append('extender_name[]', ext.name || '');
      body.append('extender_host[]', ext.host || '');
      body.append('extender_mac[]', ext.mac || '');
    });
    body.set('atv_present', '1'); // tells the device this form carries the Apple TV list (even if empty)
    appleTvs.forEach(tv => {
      body.append('atv_localid[]', tv.localId || '');
      body.append('atv_name[]', tv.name || '');
      body.append('atv_entityid[]', tv.entityId || '');
      body.append('atv_host[]', tv.host || '');
      body.append('atv_port[]', tv.port || 0);
      body.append('atv_service[]', tv.serviceName || '');
      body.append('atv_credentials[]', tv.credentials || '');
      body.append('atv_mrphost[]', tv.mrpHost || '');
      body.append('atv_mrpport[]', tv.mrpPort || 0);
      body.append('atv_mrpservice[]', tv.mrpServiceName || '');
      body.append('atv_mrpcredentials[]', tv.mrpCredentials || '');
      body.append('atv_mrptransport[]', tv.mrpTransport || 'airplay');
    });
    const res = await fetch('/save-connection', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString()
    });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    showToast(I18N.t('web_dev_saved_reconnecting'));
    closeDeviceForms();
    renderDevicesList();
  } catch (e) {
    showToast(I18N.t('web_device_save_failed', e), 'error');
  } finally {
    if (btn) { btn.textContent = originalText; btn.disabled = false; }
  }
}

// ---- Apple TV (direct, no Home Assistant) --------------------------------

/**
 * Keeps dashboardData.haDevices (the catalog the media_player / source_select
 * / etc. card pickers read from — see the "Home Assistant Device" section
 * below) in sync with the media_player entity a paired Apple TV publishes.
 * Without this, using an Apple TV in anything other than the Apple TV remote
 * card would need a separate, easy-to-miss "Add device" step; pairing it
 * already is that explicit step, so this makes the sync automatic.
 */
function upsertAppleTvHaDevice(tv) {
  dashboardData.haDevices = dashboardData.haDevices || [];
  const id = 'appletv_' + tv.localId;
  const existing = dashboardData.haDevices.find(d => d.id === id);
  if (existing) {
    existing.entityId = tv.entityId;
    existing.name = tv.name;
  } else {
    dashboardData.haDevices.push({ id, domain: 'media_player', entityId: tv.entityId, name: tv.name });
  }
}

function removeAppleTvHaDevice(localId) {
  dashboardData.haDevices = (dashboardData.haDevices || []).filter(d => d.id !== 'appletv_' + localId);
}

/** Saves dashboardData.haDevices only — no toast/button/UI side effects, so
 * it's safe to call from flows (pairing, rename, remove) that aren't the
 * "Add device" form itself. Errors still surface via a toast. */
async function saveHaDevicesSilently() {
  try {
    const payload = { ...fullDashboard, haDevices: dashboardData.haDevices };
    const form = new FormData();
    form.append('file', new Blob([JSON.stringify(payload)], { type: 'application/json' }), 'dashboard.json');
    const res = await fetch('/dashboard.json', { method: 'POST', body: form });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    fullDashboard = payload;
  } catch (e) {
    showToast(I18N.t('web_dev_catalog_update_failed', e), 'error');
  }
}

function atvEsc(text) {
  return String(text == null ? '' : text).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/** Browses the LAN for Apple TVs (the device does the mDNS lookup) and lists them. */
async function scanAppleTvs() {
  appleTvPairing = null;
  appleTvMrpPairing = null;
  document.getElementById('appleTvScanBox').style.display = '';
  document.getElementById('appleTvPinBox').style.display = 'none';
  document.getElementById('appleTvMrpOfferBox').style.display = 'none';
  document.getElementById('appleTvMrpScanBox').style.display = 'none';
  document.getElementById('appleTvMrpPinBox').style.display = 'none';
  const status = document.getElementById('appleTvScanStatus');
  const results = document.getElementById('appleTvScanResults');
  status.textContent = I18N.t('web_dev_searching_your_network');
  results.innerHTML = '';
  try {
    const res = await fetch('/appletv-scan');
    const data = await res.json();
    appleTvScanResults = Array.isArray(data.devices) ? data.devices : [];
  } catch (e) {
    appleTvScanResults = [];
    status.textContent = I18N.t('web_dev_search_failed', e);
    return;
  }
  if (!appleTvScanResults.length) {
    status.textContent = I18N.t('web_dev_atv_none_found');
    return;
  }
  status.textContent = I18N.t('web_dev_atv_choose');
  results.innerHTML = appleTvScanResults.map((tv, i) => {
    const known = appleTvs.some(x => x.serviceName === tv.serviceName);
    const action = known
      ? `<span style="color:#888">${I18N.t('web_dev_already_added')}</span>`
      : `<button type="button" onclick="startAppleTvPairing(${i})">${I18N.t('web_dev_pair')}</button>`;
    return `<div class="list-item"><span>${atvEsc(tv.name)} <span style="color:#888">(${atvEsc(tv.model || 'Apple TV')} · ${atvEsc(tv.host)})</span></span>${action}</div>`;
  }).join('');
}

/** Asks the Apple TV to show its PIN, then reveals the code entry. */
async function startAppleTvPairing(index) {
  const device = appleTvScanResults[index];
  if (!device) return;
  const status = document.getElementById('appleTvScanStatus');
  status.textContent = I18N.t('web_dev_contacting', device.name);
  try {
    const body = new URLSearchParams({ host: device.host, port: String(device.port) });
    const res = await fetch('/appletv-pair-start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString()
    });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
    appleTvPairing = { sessionId: data.sessionId, device };
  } catch (e) {
    status.textContent = I18N.t('web_dev_pair_start_failed', e.message);
    return;
  }
  document.getElementById('appleTvScanBox').style.display = 'none';
  document.getElementById('appleTvPinBox').style.display = '';
  document.getElementById('appleTvPin').value = '';
  document.getElementById('appleTvName').value = device.name || '';
  document.getElementById('appleTvPin').focus();
}

function uniqueAppleTvEntityId(name, ignoreLocalId) {
  const base = 'media_player.appletv_' + slugify(name, 'tv');
  const taken = id => appleTvs.some(t => t.entityId === id && t.localId !== ignoreLocalId) || (haStates && haStates[id]);
  let id = base;
  let n = 2;
  while (taken(id)) id = base + '_' + (n++);
  return id;
}

async function finishAppleTvPairing() {
  if (!appleTvPairing) return;
  const pin = document.getElementById('appleTvPin').value.trim();
  const name = document.getElementById('appleTvName').value.trim() || appleTvPairing.device.name || 'Apple TV';
  if (!/^\d{4}$/.test(pin)) { showToast(I18N.t('web_dev_enter_pin'), 'error'); return; }
  const btn = document.getElementById('appleTvFinishBtn');
  btn.disabled = true;
  btn.textContent = I18N.t('web_dev_pairing');
  try {
    const body = new URLSearchParams({ sessionId: appleTvPairing.sessionId, pin });
    const res = await fetch('/appletv-pair-finish', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString()
    });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
    const device = appleTvPairing.device;
    const existing = appleTvs.find(t => t.localId === data.localId);
    const entry = {
      localId: data.localId,
      name,
      entityId: existing ? existing.entityId : uniqueAppleTvEntityId(name, data.localId),
      host: device.host,
      port: device.port,
      serviceName: device.serviceName,
      credentials: data.credentials
    };
    appleTvs = existing ? appleTvs.map(t => (t.localId === data.localId ? entry : t)) : appleTvs.concat(entry);
    appleTvPairing = null;
    btn.disabled = false;
    btn.textContent = I18N.t('web_dev_pair');
    upsertAppleTvHaDevice(entry);
    await persistHaAndHubs();
    await saveHaDevicesSilently();
    showToast(I18N.t('web_dev_atv_paired', entry.entityId));
    if (!entry.mrpCredentials) {
      // persistHaAndHubs() above already closed every form, including this one's parent — reopen
      // it so the "Now Playing info" offer below is actually visible instead of hidden inside a
      // display:none container.
      setDisplay('appleTvForm', '');
      setDisplay('appleTvPinBox', 'none');
      setDisplay('appleTvMrpOfferBox', '');
      document.getElementById('appleTvMrpOfferBox').dataset.localId = entry.localId;
    } else {
      closeDeviceForms();
    }
  } catch (e) {
    showToast(I18N.t('web_dev_pair_failed', e.message), 'error');
    // The device drops the pairing session after a failed attempt, so start over from the list.
    btn.disabled = false;
    btn.textContent = I18N.t('web_dev_pair');
    scanAppleTvs();
  }
}

async function renameAppleTv(localId) {
  const tv = appleTvs.find(t => t.localId === localId);
  if (!tv) return;
  const name = prompt(I18N.t('web_dev_atv_rename_prompt'), tv.name);
  if (name === null || !name.trim()) return;
  tv.name = name.trim(); // the entity id is kept, so existing cards keep working
  upsertAppleTvHaDevice(tv);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}

async function removeAppleTv(localId) {
  const tv = appleTvs.find(t => t.localId === localId);
  if (!tv) return;
  if (!confirm(I18N.t('web_dev_confirm_remove_atv', tv.name, tv.entityId))) return;
  appleTvs = appleTvs.filter(t => t.localId !== localId);
  removeAppleTvHaDevice(localId);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}

/**
 * Step 2 (optional): the Apple TV's separate MRP service, which is what actually gives
 * title/artist/artwork/position. [localId] defaults to whichever Apple TV was just paired
 * (see finishAppleTvPairing); pass it explicitly when pairing this in later from the list's
 * "+ Now Playing info" link.
 */
async function scanAppleTvMrp(localId) {
  const targetId = localId || document.getElementById('appleTvMrpOfferBox').dataset.localId;
  appleTvMrpPairing = null;
  document.getElementById('appleTvScanBox').style.display = 'none';
  document.getElementById('appleTvPinBox').style.display = 'none';
  document.getElementById('appleTvMrpOfferBox').style.display = 'none';
  document.getElementById('appleTvMrpScanBox').style.display = '';
  document.getElementById('appleTvMrpPinBox').style.display = 'none';
  document.getElementById('appleTvForm').style.display = '';
  const status = document.getElementById('appleTvMrpScanStatus');
  const results = document.getElementById('appleTvMrpScanResults');
  status.textContent = I18N.t('web_dev_searching_for_the_now_playing_service');
  results.innerHTML = '';
  try {
    const res = await fetch('/appletv-mrp-scan');
    const data = await res.json();
    appleTvMrpScanResults = Array.isArray(data.devices) ? data.devices : [];
  } catch (e) {
    appleTvMrpScanResults = [];
    status.textContent = I18N.t('web_dev_search_failed', e);
    return;
  }
  if (!appleTvMrpScanResults.length) {
    status.textContent = I18N.t('web_dev_mrp_none_found');
    return;
  }
  status.textContent = I18N.t('web_dev_mrp_choose');
  results.innerHTML = appleTvMrpScanResults.map((tv, i) => {
    const badge = tv.transport === 'classic' ? ' · ' + I18N.t('web_dev_classic_mrp') : '';
    return `<div class="list-item"><span>${atvEsc(tv.name)} <span style="color:#888">(${atvEsc(tv.host)}${badge})</span></span>` +
      `<button type="button" onclick="startAppleTvMrpPairing(${i}, '${targetId}')">${I18N.t('web_dev_pair')}</button></div>`;
  }).join('');
}

async function startAppleTvMrpPairing(index, localId) {
  const device = appleTvMrpScanResults[index];
  if (!device) return;
  const status = document.getElementById('appleTvMrpScanStatus');
  status.textContent = I18N.t('web_dev_contacting', device.name);
  try {
    const body = new URLSearchParams({ host: device.host, port: String(device.port), transport: device.transport || 'airplay' });
    const res = await fetch('/appletv-mrp-pair-start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString()
    });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
    appleTvMrpPairing = { sessionId: data.sessionId, device, localId };
  } catch (e) {
    status.textContent = I18N.t('web_dev_pair_start_failed', e.message);
    return;
  }
  document.getElementById('appleTvMrpScanBox').style.display = 'none';
  document.getElementById('appleTvMrpPinBox').style.display = '';
  document.getElementById('appleTvMrpPin').value = '';
  document.getElementById('appleTvMrpPin').focus();
}

async function finishAppleTvMrpPairing() {
  if (!appleTvMrpPairing) return;
  const pin = document.getElementById('appleTvMrpPin').value.trim();
  if (!/^\d{4}$/.test(pin)) { showToast(I18N.t('web_dev_enter_pin'), 'error'); return; }
  const btn = document.getElementById('appleTvMrpFinishBtn');
  btn.disabled = true;
  btn.textContent = I18N.t('web_dev_pairing');
  try {
    const body = new URLSearchParams({ sessionId: appleTvMrpPairing.sessionId, pin });
    const res = await fetch('/appletv-mrp-pair-finish', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString()
    });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
    const { device, localId } = appleTvMrpPairing;
    appleTvs = appleTvs.map(t => (t.localId === localId
      ? {
          ...t,
          mrpHost: device.host,
          mrpPort: device.port,
          mrpServiceName: device.serviceName,
          mrpCredentials: data.credentials,
          mrpTransport: device.transport || 'airplay'
        }
      : t));
    appleTvMrpPairing = null;
    btn.disabled = false;
    btn.textContent = I18N.t('web_dev_pair');
    await persistHaAndHubs(); // already closes every open form and re-renders the list on success
    showToast(I18N.t('web_dev_np_enabled'));
  } catch (e) {
    showToast(I18N.t('web_dev_pair_failed', e.message), 'error');
    btn.disabled = false;
    btn.textContent = I18N.t('web_dev_pair');
    scanAppleTvMrp(appleTvMrpPairing && appleTvMrpPairing.localId);
  }
}

// ---- Harmony Hub ---------------------------------------------------------

function editHarmonyHub(localId) {
  const hub = harmonyHubs.find(h => h.localId === localId);
  if (!hub) return;
  editingHarmonyHubId = localId;
  openDeviceForm('harmony');
  document.getElementById('hubName').value = hub.name || '';
  document.getElementById('hubIp').value = hub.ip || '';
  document.getElementById('hubHubId').value = hub.hubId || '';
  document.getElementById('saveHarmonyHubBtn').textContent = I18N.t('web_save');
  document.getElementById('removeHarmonyHubBtn').style.display = '';
}

async function saveHarmonyHub() {
  const name = document.getElementById('hubName').value.trim();
  const ip = document.getElementById('hubIp').value.trim();
  const hubId = document.getElementById('hubHubId').value.trim();
  if (!name) { alert(I18N.t('web_dev_err_hub_name')); return; }
  if (!ip) { alert(I18N.t('web_dev_err_hub_ip')); return; }

  if (editingHarmonyHubId !== null) {
    const hub = harmonyHubs.find(h => h.localId === editingHarmonyHubId);
    if (hub) { hub.name = name; hub.ip = ip; hub.hubId = hubId; }
  } else {
    harmonyHubs.push({
      localId: 'hub_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
      name, ip, hubId
    });
  }
  await persistHaAndHubs();
}

async function removeHarmonyHub(localId) {
  if (!confirm(I18N.t('web_dev_confirm_remove_hub'))) return;
  harmonyHubs = harmonyHubs.filter(h => h.localId !== localId);
  await persistHaAndHubs();
}

async function removeHarmonyHubFromForm() {
  if (editingHarmonyHubId !== null) await removeHarmonyHub(editingHarmonyHubId);
}

// ---- Astrion IR Extender ---------------------------------------------------

/** Strips separators and lowercases, so the same physical MAC always maps to
 * the same id no matter how it was typed (2C:B4:71:FF:C7:98, 2c-b4-71-ff-c7-98,
 * 2cb471ffc798 are all the same extender). Returns '' if it isn't 12 hex
 * digits once cleaned. */
function normalizeMac(raw) {
  const cleaned = (raw || '').replace(/[^0-9a-fA-F]/g, '').toLowerCase();
  return cleaned.length === 12 ? cleaned : '';
}

function editExtender(localId) {
  const ext = extenders.find(e => e.localId === localId);
  if (!ext) return;
  openDeviceForm('extender');
  editingExtenderId = localId;
  document.getElementById('extenderName').value = ext.name || '';
  document.getElementById('extenderHost').value = ext.host || '';
  document.getElementById('extenderMac').value = ext.mac || '';
  document.getElementById('saveExtenderBtn').textContent = I18N.t('web_save');
  document.getElementById('removeExtenderBtn').style.display = '';
}

async function saveExtender() {
  const name = document.getElementById('extenderName').value.trim();
  const host = document.getElementById('extenderHost').value.trim();
  const macRaw = document.getElementById('extenderMac').value.trim();
  if (!name) { alert(I18N.t('web_dev_err_extender_name')); return; }
  if (!host) { alert(I18N.t('web_dev_err_extender_host')); return; }
  const mac = normalizeMac(macRaw);
  if (!mac) {
    alert(I18N.t('web_dev_err_extender_mac', host));
    return;
  }

  // localId is derived from the MAC, never random: IR devices reference an
  // extender by this id, so it has to survive a rename, an IP change, or a
  // remove-and-re-add of the same physical unit. (A random id here is
  // exactly the bug already fixed once for Harmony hubs -- and hit again
  // in testing here, before this field existed.)
  const localId = 'ext_' + mac;
  const existing = extenders.find(e => e.localId === localId);

  if (editingExtenderId !== null) {
    const ext = extenders.find(e => e.localId === editingExtenderId);
    if (!ext) return;
    if (localId !== editingExtenderId && existing) {
      alert(I18N.t('web_dev_err_mac_used', existing.name));
      return;
    }
    ext.localId = localId; // may change if the MAC was corrected
    ext.name = name;
    ext.host = host;
    ext.mac = mac;
  } else {
    if (existing) {
      alert(I18N.t('web_dev_err_mac_exists', existing.name));
      return;
    }
    extenders.push({ localId, name, host, mac });
  }
  await persistHaAndHubs();
}

async function removeExtender(localId) {
  if (!confirm(I18N.t('web_dev_confirm_remove_extender'))) return;
  extenders = extenders.filter(e => e.localId !== localId);
  await persistHaAndHubs();
}

async function removeExtenderFromForm() {
  if (editingExtenderId !== null) await removeExtender(editingExtenderId);
}

async function discoverHarmonyHubId() {
  const ip = document.getElementById('hubIp').value.trim();
  if (!ip) { alert(I18N.t('web_dev_err_hub_ip_first')); return; }
  const btn = document.getElementById('discoverHubIdBtn');
  const originalText = btn.textContent;
  btn.textContent = I18N.t('web_dev_detecting');
  btn.disabled = true;
  try {
    const res = await fetch('/harmony-discover?ip=' + encodeURIComponent(ip));
    const data = await res.json();
    if (data.hubId) document.getElementById('hubHubId').value = data.hubId;
    else alert(data.error || I18N.t('web_dev_hubid_detect_failed'));
  } catch (e) {
    alert(I18N.t('web_dev_autodetect_failed', e));
  } finally {
    btn.textContent = originalText;
    btn.disabled = false;
  }
}

// ---- Harmony devices + commands: browse a hub's own device/command list
// (from GET /harmony-config, the same data cards.js/hotkeys.js/activities.js
// already use for their pickers), and give any command a local alias — never
// changes what's sent (still `command.name`, Harmony's own raw id), purely
// a display label. Some Harmony device profiles genuinely have no distinct
// "OK"/"Enter" command, only e.g. "Select" — this can't be fixed on
// Harmony's side (that's their database, not ours), but every place a
// command gets picked in this app can at least show a clearer name for it. ---

let currentHarmonyHubId = null;
let currentHarmonyDeviceId = null;
let currentHarmonyConfig = null; // last-fetched /harmony-config response, cached for the Devices-list -> Commands-list drill-down

async function browseHarmonyDevices(hubLocalId) {
  currentHarmonyHubId = hubLocalId;
  const hub = harmonyHubs.find(h => h.localId === hubLocalId);
  document.getElementById('harmonyDevicesHubName').textContent = hub ? hub.name : I18N.t('web_dev_devices_title');
  showView('viewHarmonyDevices');
  const list = document.getElementById('harmonyDevicesList');
  list.innerHTML = `<div class="hint">${I18N.t('web_release_loading')}</div>`;
  try {
    const res = await fetch('/harmony-config?hub=' + encodeURIComponent(hubLocalId));
    if (!res.ok) throw new Error('HTTP ' + res.status);
    currentHarmonyConfig = await res.json();
    const devices = currentHarmonyConfig.devices || [];
    list.innerHTML = devices.length
      ? devices.map(d => {
          const count = (d.commands || []).length;
          return `<div class="list-item"><span class="remove" style="color:#00E5FF;cursor:pointer" onclick="browseHarmonyCommands('${d.id}')">${d.label || d.id}</span>` +
            `<span style="color:#888">${countLabel(count, 'web_dev_count_command')}</span></div>`;
        }).join('')
      : `<div class="hint">${I18N.t('web_dev_hub_no_devices')}</div>`;
  } catch (e) {
    list.innerHTML = `<div class="hint" style="color:#ff8a8a">${I18N.t('web_dev_hub_load_failed')}</div>`;
    console.error('Failed to load /harmony-config for hub ' + hubLocalId, e);
  }
}

function browseHarmonyCommands(deviceId) {
  currentHarmonyDeviceId = deviceId;
  const device = (currentHarmonyConfig?.devices || []).find(d => d.id === deviceId);
  document.getElementById('harmonyCommandsDeviceName').textContent = device ? (device.label || device.id) : I18N.t('web_dev_commands');
  showView('viewHarmonyCommands');
  renderHarmonyCommandsList();
}

function renderHarmonyCommandsList() {
  const list = document.getElementById('harmonyCommandsList');
  const device = (currentHarmonyConfig?.devices || []).find(d => d.id === currentHarmonyDeviceId);
  if (!device) { list.innerHTML = ''; return; }
  const deviceAliases = dashboardData.harmonyAliases?.[currentHarmonyHubId]?.[currentHarmonyDeviceId] || {};
  const commands = device.commands || [];
  list.innerHTML = commands.length
    ? commands.map(cmd => `
        <div class="list-item" style="align-items:center">
          <span>${cmd.label} <span style="color:#888">(${cmd.name})</span></span>
          <input type="text" value="${deviceAliases[cmd.name] || ''}" placeholder="${I18N.t('web_dev_alias_ph')}"
                 style="width:160px" data-cmd="${cmd.name}" onchange="setHarmonyAlias(this)">
        </div>
      `).join('')
    : `<div class="hint">${I18N.t('web_dev_no_commands_found')}</div>`;
}

async function setHarmonyAlias(input) {
  const commandName = input.dataset.cmd;
  const alias = input.value.trim();
  dashboardData.harmonyAliases = dashboardData.harmonyAliases || {};
  dashboardData.harmonyAliases[currentHarmonyHubId] = dashboardData.harmonyAliases[currentHarmonyHubId] || {};
  const deviceAliases = dashboardData.harmonyAliases[currentHarmonyHubId][currentHarmonyDeviceId] =
    dashboardData.harmonyAliases[currentHarmonyHubId][currentHarmonyDeviceId] || {};
  if (alias) deviceAliases[commandName] = alias;
  else delete deviceAliases[commandName];
  await persistHarmonyAliases();
}

/** Same round-trip pattern as persistIrDevices/persistHaDevices below —
 * only "harmonyAliases" actually changes. No toast/spinner here: this
 * fires on every input blur while browsing a device's whole command list,
 * a toast per keystroke-adjacent save would be noisy rather than helpful. */
async function persistHarmonyAliases() {
  try {
    const payload = { ...fullDashboard, harmonyAliases: dashboardData.harmonyAliases };
    const jsonText = JSON.stringify(payload);
    const form = new FormData();
    form.append('file', new Blob([jsonText], { type: 'application/json' }), 'dashboard.json');
    const res = await fetch('/dashboard.json', { method: 'POST', body: form });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    fullDashboard = payload;
  } catch (e) {
    showToast(I18N.t('web_device_save_failed', e), 'error');
  }
}

// ---- Home Assistant Device (one named HA entity in the catalog) ------------
//
// The dashboard builder's per-card "Entity ID" field used to be a free-text
// input with live autocomplete over every entity Home Assistant has. Now it's
// a <select> populated ONLY from dashboardData.haDevices, filtered to the
// domain(s) that card type actually uses — so this is the one place that
// list gets built. Stored in dashboard.json (like irDevices), not
// RemoteSettings: it's dashboard content, not a connection secret.
//
// Keep HA_DOMAIN_ENTITY_PREFIXES in sync with cards.js's own per-type domain
// mapping (the `mainDomain` logic in updateCardFormInputs) if a new HA card
// type is ever added.
const HA_DOMAIN_LABELS = { // i18n keys
  light: 'web_domain_light', switch: 'web_domain_switch', cover: 'web_domain_cover', climate: 'web_domain_climate',
  media_player: 'web_domain_media_player', camera: 'web_domain_camera', fan: 'web_domain_fan', vacuum: 'web_domain_vacuum',
  weather: 'web_domain_weather', select: 'web_domain_select'
};
const HA_DOMAIN_ENTITY_PREFIXES = {
  light: ['light.'], switch: ['switch.'], cover: ['cover.'], climate: ['climate.'],
  media_player: ['media_player.'], camera: ['camera.'], fan: ['fan.'], vacuum: ['vacuum.'],
  weather: ['weather.'], select: ['select.', 'input_select.']
};

function onHaEntityDomainChange() {
  const domain = document.getElementById('haEntityDomain').value;
  const sel = document.getElementById('haEntityId');
  const hint = document.getElementById('haEntityUnavailableHint');
  const prefixes = HA_DOMAIN_ENTITY_PREFIXES[domain] || [];

  const entries = haStates
    ? Object.entries(haStates).filter(([id]) => prefixes.some(p => id.startsWith(p)))
    : [];
  entries.sort((a, b) => (a[1].friendly_name || a[0]).localeCompare(b[1].friendly_name || b[0]));

  if (!entries.length) {
    sel.innerHTML = '';
    hint.style.display = '';
    return;
  }
  hint.style.display = 'none';
  sel.innerHTML = entries.map(([id, e]) => `<option value="${id}">${e.friendly_name || id}</option>`).join('');
  onHaEntityPicked();
}

/** Prefills the Name field from the picked entity's HA friendly_name — but
 * only while adding, and only if the person hasn't already typed something,
 * so it never clobbers a deliberate rename while editing. */
function onHaEntityPicked() {
  if (editingHaDevice !== null) return;
  const nameField = document.getElementById('haEntityName');
  if (nameField.value.trim()) return;
  const entityId = document.getElementById('haEntityId').value;
  const entity = haStates && haStates[entityId];
  if (entity) nameField.value = entity.friendly_name || entityId;
}

async function editHaEntity(id) {
  const dev = (dashboardData.haDevices || []).find(d => d.id === id);
  if (!dev) return;
  editingHaDevice = id;
  await openDeviceForm('haEntity');
  document.getElementById('haEntityDomain').value = dev.domain;
  onHaEntityDomainChange();
  document.getElementById('haEntityId').value = dev.entityId;
  document.getElementById('haEntityName').value = dev.name;
  document.getElementById('saveHaEntityBtn').textContent = I18N.t('web_save');
  document.getElementById('removeHaEntityBtn').style.display = '';
}

async function saveHaEntity() {
  const domain = document.getElementById('haEntityDomain').value;
  const entityId = document.getElementById('haEntityId').value;
  const name = document.getElementById('haEntityName').value.trim();
  if (!entityId) { alert(I18N.t('web_dev_err_no_entities')); return; }
  if (!name) { alert(I18N.t('web_ir_err_name')); return; }

  dashboardData.haDevices = dashboardData.haDevices || [];
  if (editingHaDevice !== null) {
    const dev = dashboardData.haDevices.find(d => d.id === editingHaDevice);
    if (dev) { dev.domain = domain; dev.entityId = entityId; dev.name = name; }
  } else {
    const id = slugify(name, 'ha_device');
    let uniqueId = id;
    let n = 2;
    while (dashboardData.haDevices.some(d => d.id === uniqueId)) uniqueId = `${id}_${n++}`;
    dashboardData.haDevices.push({ id: uniqueId, domain, entityId, name });
  }
  await persistHaDevices();
}

async function removeHaEntityFromList(id) {
  if (!confirm(I18N.t('web_dev_confirm_remove_ha_device'))) return;
  dashboardData.haDevices = (dashboardData.haDevices || []).filter(d => d.id !== id);
  await persistHaDevices();
}

async function removeHaEntityFromForm() {
  if (editingHaDevice !== null) await removeHaEntityFromList(editingHaDevice);
}

/** Same pattern as persistIrDevices() below: round-trips the whole
 * dashboard.json, only "haDevices" actually changes. */
async function persistHaDevices() {
  const btn = document.getElementById('saveHaEntityBtn');
  const originalText = btn.textContent;
  btn.textContent = I18N.t('web_device_saving');
  btn.disabled = true;
  try {
    const payload = { ...fullDashboard, haDevices: dashboardData.haDevices };
    const jsonText = JSON.stringify(payload);
    const form = new FormData();
    form.append('file', new Blob([jsonText], { type: 'application/json' }), 'dashboard.json');
    const res = await fetch('/dashboard.json', { method: 'POST', body: form });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    fullDashboard = payload;
    showToast(I18N.t('web_dev_saved'));
    closeDeviceForms();
    renderDevicesList();
  } catch (e) {
    showToast(I18N.t('web_device_save_failed', e), 'error');
  } finally {
    btn.textContent = originalText;
    btn.disabled = false;
  }
}

// ---- IR Device (ported from the builder's former ir.js — same shapes,
// same Pronto decoding, same on-device ir-database quick-pick; only the
// save/remove tail end changed, to persist to /dashboard.json immediately
// instead of feeding the builder's own live JSON preview) -----------------

let pendingIrCommands = {};
const IR_CATEGORIES = [
  { id: 'ac', label: 'AC' },
  { id: 'audio', labelKey: 'web_ir_category_audio' },
  { id: 'camera', labelKey: 'web_ir_category_camera' },
  { id: 'fan', labelKey: 'web_ir_category_fan' },
  { id: 'lights', labelKey: 'web_ir_category_lights' },
  { id: 'player', labelKey: 'web_ir_category_player' },
  { id: 'plug', labelKey: 'web_ir_category_plug' },
  { id: 'robot', labelKey: 'web_ir_category_robot' },
  { id: 'set-top-box', labelKey: 'web_ir_category_set_top_box' },
  { id: 'tv', label: 'TV' }
];
const irCategoryLabel = c => (c.labelKey ? I18N.t(c.labelKey) : c.label);

function prontoToPattern(pronto) {
  const words = pronto.trim().split(/\s+/).map(w => parseInt(w, 16));
  const [type, freqCode, onceLen, repeatLen] = words;
  if (type !== 0x0000) {
    throw new Error(I18N.t('web_ir_err_pronto_type', type.toString(16)));
  }
  const carrierHz = Math.round(4145146 / freqCode);
  const periodUs = 1000000 / carrierHz;
  const rest = words.slice(4);
  const once = rest.slice(0, onceLen * 2);
  const repeat = rest.slice(onceLen * 2, onceLen * 2 + repeatLen * 2);
  const chosen = once.length ? once : repeat;
  if (!chosen.length) throw new Error(I18N.t('web_ir_err_pronto_sections'));
  return { freq: carrierHz, pattern: chosen.map(c => Math.round(c * periodUs)) };
}

function initIrCategorySelect() {
  const sel = document.getElementById('irRefCategory');
  if (!sel) return;
  sel.innerHTML = IR_CATEGORIES.map(c => `<option value="${c.id}">${irCategoryLabel(c)}</option>`).join('');
}

function onIrSourceModeChange() {
  const mode = document.querySelector('input[name="irSourceMode"]:checked')?.value || 'inline';
  document.getElementById('irInlineForm').style.display = mode === 'inline' ? '' : 'none';
  document.getElementById('irRefForm').style.display = mode === 'reference' ? '' : 'none';
}

let irOnDeviceCache = {};

async function tryLoadOnDeviceIrDatabase() {
  const quickPick = document.getElementById('irOnDeviceQuickPick');
  try {
    const res = await fetch('/ir-database');
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const categoryIds = await res.json();
    if (!Array.isArray(categoryIds) || !categoryIds.length) return;

    const sel = document.getElementById('irOnDeviceCategory');
    sel.innerHTML = `<option value="">${I18N.t('web_ir_ph_category')}</option>` +
      categoryIds.map(id => {
        const known = IR_CATEGORIES.find(c => c.id === id);
        return `<option value="${id}">${known ? irCategoryLabel(known) : id}</option>`;
      }).join('');
    quickPick.style.display = '';
  } catch (e) {
    quickPick.style.display = 'none';
    console.debug('No on-device ir-database reachable:', e.message);
  }
}

function resetIrOnDeviceSelect(sel, placeholder) {
  sel.innerHTML = `<option value="">${placeholder}</option>`;
  sel.disabled = true;
}

async function onIrOnDeviceCategoryChange() {
  const categoryId = document.getElementById('irOnDeviceCategory').value;
  const brandSel = document.getElementById('irOnDeviceBrand');
  const modelSel = document.getElementById('irOnDeviceModel');
  resetIrOnDeviceSelect(modelSel, I18N.t('web_ir_ph_brand_first'));
  if (!categoryId) { resetIrOnDeviceSelect(brandSel, I18N.t('web_ir_ph_category_first')); return; }

  try {
    if (!irOnDeviceCache[categoryId]) {
      const res = await fetch(`/ir-database/${categoryId}.json`);
      if (!res.ok) throw new Error('HTTP ' + res.status);
      irOnDeviceCache[categoryId] = await res.json();
    }
    const brands = irOnDeviceCache[categoryId].brands || [];
    brandSel.innerHTML = `<option value="">${I18N.t('web_ir_ph_brand')}</option>` +
      brands.map((b, i) => `<option value="${i}">${b.brand_name}</option>`).join('');
    brandSel.disabled = false;
  } catch (e) {
    brandSel.innerHTML = `<option value="">${I18N.t('web_ir_ph_failed')}</option>`;
    console.error('Failed to load on-device ir-database category', e);
  }
}

function onIrOnDeviceBrandChange() {
  const categoryId = document.getElementById('irOnDeviceCategory').value;
  const brandIdx = document.getElementById('irOnDeviceBrand').value;
  const modelSel = document.getElementById('irOnDeviceModel');
  if (brandIdx === '') { resetIrOnDeviceSelect(modelSel, I18N.t('web_ir_ph_brand_first')); return; }
  const models = irOnDeviceCache[categoryId].brands[brandIdx].models || [];
  modelSel.innerHTML = `<option value="">${I18N.t('web_ir_ph_model')}</option>` +
    models.map((m, i) => `<option value="${i}">${m.model_name}</option>`).join('');
  modelSel.disabled = false;
}

function onIrOnDeviceModelChange() {
  const categoryId = document.getElementById('irOnDeviceCategory').value;
  const brandIdx = document.getElementById('irOnDeviceBrand').value;
  const modelIdx = document.getElementById('irOnDeviceModel').value;
  if (modelIdx === '') return;

  const data = irOnDeviceCache[categoryId];
  const brand = data.brands[brandIdx];
  const model = brand.models[modelIdx];

  document.getElementById('irRefCategory').value = categoryId;
  document.getElementById('irRefBrand').value = brand.brand_name;
  document.getElementById('irRefModel').value = model.model_name;
  document.getElementById('irRefKnownCommands').value = Object.keys(model.commands || {}).join(', ');
}

function addIrCommand() {
  const commandId = document.getElementById('irCommandId').value.trim();
  if (!commandId) { alert(I18N.t('web_ir_err_command_id')); return; }

  const manualPronto = document.getElementById('irManualPronto').value.trim();
  if (!manualPronto) { alert(I18N.t('web_ir_err_pronto')); return; }

  let resolved;
  try {
    resolved = prontoToPattern(manualPronto);
  } catch (e) {
    alert(I18N.t('web_ir_err_decode', e.message));
    return;
  }
  const label = document.getElementById('irManualLabel').value.trim() || commandId;

  pendingIrCommands[commandId] = { freq: resolved.freq, pattern: resolved.pattern, label };
  document.getElementById('irCommandId').value = '';
  document.getElementById('irManualPronto').value = '';
  document.getElementById('irManualLabel').value = '';
  renderIrCommandsList();
}

function removeIrCommand(commandId) {
  delete pendingIrCommands[commandId];
  renderIrCommandsList();
}

function renderIrCommandsList() {
  const list = document.getElementById('irCommandsList');
  if (!list) return;
  const ids = Object.keys(pendingIrCommands);
  if (!ids.length) {
    list.innerHTML = `<div class="hint">${I18N.t('web_ir_no_commands')}</div>`;
    return;
  }
  list.innerHTML = '';
  ids.forEach(commandId => {
    const cmd = pendingIrCommands[commandId];
    const el = document.createElement('div');
    el.className = 'list-item';
    el.innerHTML = `<span><code>${commandId}</code> — ${cmd.label}</span><span><span class="remove" onclick="removeIrCommand('${commandId}')">✕</span></span>`;
    list.appendChild(el);
  });
}

function editIrDevice(id) {
  const dev = (dashboardData.irDevices || []).find(d => d.id === id);
  if (!dev) return;
  // openDeviceForm('ir') calls cancelIrDeviceEdit() internally, which resets
  // editingIrDevice to null -- must be set AFTER that call, not before, or
  // saveIrDevice() thinks it's creating a new device and duplicates this one
  // instead of updating it. (Pre-existing bug, found while wiring the
  // target selector below -- fixed here rather than left in place.)
  openDeviceForm('ir');
  editingIrDevice = id;
  document.getElementById('irDevName').value = dev.name;
  document.getElementById('irTargetSelect').value = (dev.target && dev.target.extender) || '';

  const isReference = !dev.commands;
  document.querySelector(`input[name="irSourceMode"][value="${isReference ? 'reference' : 'inline'}"]`).checked = true;
  onIrSourceModeChange();

  if (isReference) {
    document.getElementById('irRefCategory').value = dev.category || '';
    document.getElementById('irRefBrand').value = dev.brand || '';
    document.getElementById('irRefModel').value = dev.model || '';
    document.getElementById('irRefKnownCommands').value = (dev.commandHints || []).join(', ');
    pendingIrCommands = {};
  } else {
    pendingIrCommands = JSON.parse(JSON.stringify(dev.commands || {}));
  }
  renderIrCommandsList();
  document.getElementById('saveIrDeviceBtn').textContent = I18N.t('web_save');
  document.getElementById('removeIrDeviceBtn').style.display = '';
}

function cancelIrDeviceEdit() {
  editingIrDevice = null;
  pendingIrCommands = {};
  document.getElementById('irDevName').value = '';
  document.getElementById('irRefBrand').value = '';
  document.getElementById('irRefModel').value = '';
  document.getElementById('irRefKnownCommands').value = '';
  const targetSelect = document.getElementById('irTargetSelect');
  if (targetSelect) targetSelect.value = '';
  document.querySelector('input[name="irSourceMode"][value="inline"]').checked = true;
  onIrSourceModeChange();
  document.getElementById('saveIrDeviceBtn').textContent = I18N.t('web_save');
  renderIrCommandsList();
}

async function saveIrDevice() {
  const name = document.getElementById('irDevName').value.trim();
  if (!name) { alert(I18N.t('web_ir_err_name')); return; }

  const mode = document.querySelector('input[name="irSourceMode"]:checked')?.value || 'inline';
  let deviceFields;

  if (mode === 'inline') {
    if (!Object.keys(pendingIrCommands).length) { alert(I18N.t('web_ir_err_no_commands')); return; }
    deviceFields = { commands: pendingIrCommands, category: undefined, brand: undefined, model: undefined, commandHints: undefined };
  } else {
    const category = document.getElementById('irRefCategory').value;
    const brand = document.getElementById('irRefBrand').value.trim();
    const model = document.getElementById('irRefModel').value.trim();
    if (!category || !brand || !model) { alert(I18N.t('web_ir_err_ref_fields')); return; }
    const rawHints = document.getElementById('irRefKnownCommands').value.trim();
    const commandHints = rawHints ? rawHints.split(',').map(s => s.trim()).filter(Boolean) : undefined;
    deviceFields = { category, brand, model, commands: undefined, commandHints };
  }

  const targetExtenderId = document.getElementById('irTargetSelect').value;
  if (targetExtenderId && mode === 'inline') {
    // Matches IrStepConfig's own limitation on the app side: dashboard.json
    // only persists already-decoded freq/pattern for hand-pasted commands,
    // not the original Pronto string an extender needs -- so this can't
    // actually work yet for inline-sourced devices. Block it here rather
    // than silently saving a target the app will just warn-and-no-op on.
    alert(I18N.t('web_dev_err_inline_extender'));
    return;
  }
  deviceFields.target = targetExtenderId ? { extender: targetExtenderId } : undefined;

  dashboardData.irDevices = dashboardData.irDevices || [];
  let savedId;
  if (editingIrDevice !== null) {
    const idx = dashboardData.irDevices.findIndex(d => d.id === editingIrDevice);
    if (idx >= 0) {
      dashboardData.irDevices[idx] = { id: dashboardData.irDevices[idx].id, name, ...deviceFields };
      savedId = dashboardData.irDevices[idx].id;
    }
  } else {
    const id = slugify(name, 'device');
    let uniqueId = id;
    let n = 2;
    while (dashboardData.irDevices.some(d => d.id === uniqueId)) uniqueId = `${id}_${n++}`;
    dashboardData.irDevices.push({ id: uniqueId, name, ...deviceFields });
    savedId = uniqueId;
  }
  const saved = dashboardData.irDevices.find(d => d.id === savedId);
  Object.keys(saved).forEach(k => { if (saved[k] === undefined) delete saved[k]; });

  await persistIrDevices();
}

async function removeIrDeviceFromList(id) {
  if (!confirm(I18N.t('web_dev_confirm_remove_ir'))) return;
  dashboardData.irDevices = (dashboardData.irDevices || []).filter(d => d.id !== id);
  await persistIrDevices();
}

async function removeIrDeviceFromForm() {
  if (editingIrDevice !== null) await removeIrDeviceFromList(editingIrDevice);
}

/** POSTs the full dashboard.json back with only "irDevices" changed —
 * everything else (pages, cards, hotkeys, theme, activities) round-trips
 * untouched, exactly as last fetched by loadAll(). */
async function persistIrDevices() {
  const btn = document.getElementById('saveIrDeviceBtn');
  const originalText = btn.textContent;
  btn.textContent = I18N.t('web_device_saving');
  btn.disabled = true;
  try {
    const payload = { ...fullDashboard, irDevices: dashboardData.irDevices };
    const jsonText = JSON.stringify(payload);
    const form = new FormData();
    form.append('file', new Blob([jsonText], { type: 'application/json' }), 'dashboard.json');
    const res = await fetch('/dashboard.json', { method: 'POST', body: form });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    fullDashboard = payload;
    showToast(I18N.t('web_dev_saved'));
    closeDeviceForms();
    renderDevicesList();
  } catch (e) {
    showToast(I18N.t('web_device_save_failed', e), 'error');
  } finally {
    btn.textContent = originalText;
    btn.disabled = false;
  }
}

// ---- Dashboard backup (restore) — POST /dashboard.json with a raw file,
// same endpoint the builder's "Save to device" already uses; unlike that
// one this replaces the whole file wholesale, so reload after success
// rather than trying to patch the in-memory fullDashboard/dashboardData. ---

async function restoreDashboard() {
  const input = document.getElementById('dashboardFile');
  const file = input.files && input.files[0];
  if (!file) { alert(I18N.t('web_dev_err_choose_backup')); return; }
  if (!confirm(I18N.t('web_dev_confirm_restore'))) return;
  const btn = document.getElementById('restoreDashboardBtn');
  const originalText = btn.textContent;
  btn.textContent = I18N.t('web_dev_restoring');
  btn.disabled = true;
  try {
    const form = new FormData();
    form.append('file', file, file.name);
    const res = await fetch('/dashboard.json', { method: 'POST', body: form });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    showToast(I18N.t('web_dev_restored'));
    setTimeout(() => location.reload(), 800);
  } catch (e) {
    showToast(I18N.t('web_dev_restore_failed', e), 'error');
    btn.textContent = originalText;
    btn.disabled = false;
  }
}

// ---- IR database (category/brand/model files for the on-device picker
// used by the IR Device form above) — POST /ir-database, unrelated to the
// "IR Device" catalog entries themselves (those live in dashboard.json). ---

async function uploadIrDatabase() {
  const input = document.getElementById('irDatabaseFile');
  const file = input.files && input.files[0];
  if (!file) { alert(I18N.t('web_dev_err_choose_irdb')); return; }
  const btn = document.getElementById('uploadIrDatabaseBtn');
  const originalText = btn.textContent;
  btn.textContent = I18N.t('web_dev_uploading');
  btn.disabled = true;
  try {
    const form = new FormData();
    form.append('file', file, file.name);
    const res = await fetch('/ir-database', { method: 'POST', body: form });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    showToast(I18N.t('web_dev_irdb_updated'));
    input.value = '';
    irOnDeviceCache = {};
    await tryLoadOnDeviceIrDatabase();
    await loadIrDatabaseFiles();
  } catch (e) {
    showToast(I18N.t('web_dev_upload_failed', e), 'error');
  } finally {
    btn.textContent = originalText;
    btn.disabled = false;
  }
}

/** Lists the category files in /sdcard/astrion/ir-database/ (GET
 * /ir-database), each with its brands/models and a delete button. */
async function loadIrDatabaseFiles() {
  const list = document.getElementById('irDatabaseFilesList');
  if (!list) return;
  try {
    const res = await fetch('/ir-database');
    const ids = await res.json();
    list.innerHTML = '';
    if (!Array.isArray(ids) || !ids.length) {
      list.innerHTML = `<div class="hint">${I18N.t('web_dev_irdb_none')}</div>`;
      return;
    }
    for (const id of ids) {
      const row = document.createElement('div');
      row.className = 'list-item';
      const label = document.createElement('span');
      const title = document.createElement('strong');
      title.textContent = `${id}.json`;
      const models = document.createElement('div');
      models.className = 'hint';
      models.style.margin = '2px 0 0';
      models.textContent = await describeIrDatabaseFile(id);
      label.append(title, models);
      const del = document.createElement('span');
      del.className = 'remove';
      del.title = I18N.t('web_dev_irdb_delete_title');
      del.textContent = '✕';
      del.addEventListener('click', () => deleteIrDatabaseFile(id));
      row.append(label, del);
      list.appendChild(row);
    }
  } catch (e) {
    list.innerHTML = `<div class="hint">${I18N.t('web_dev_irdb_load_failed')}</div>`;
    console.error('Failed to load /ir-database', e);
  }
}

/** "LG: OLED65B8, HU710PW-GL · Samsung: UE48H6200" — or '' if unreadable. */
async function describeIrDatabaseFile(id) {
  try {
    const res = await fetch(`/ir-database/${encodeURIComponent(id)}.json`);
    if (!res.ok) return '';
    const file = await res.json();
    return (file.brands || [])
      .map(b => `${b.brand_name}: ${(b.models || []).map(m => m.model_name).join(', ')}`)
      .join(' · ');
  } catch (e) {
    return '';
  }
}

async function deleteIrDatabaseFile(id) {
  // IR devices in dashboard.json that point at this category stop sending
  // anything once the file is gone — say which before deleting.
  let users = [];
  try {
    const res = await fetch('/dashboard.json');
    if (res.ok) {
      const dash = await res.json();
      users = (dash.irDevices || []).filter(d => !d.commands && (d.category || '').toLowerCase() === id).map(d => d.name || d.id);
    }
  } catch (e) { /* no warning possible — still ask */ }
  const warning = users.length
    ? '\n\n' + I18N.t('web_dev_irdb_still_used', users.join(', '))
    : '';
  if (!confirm(I18N.t('web_dev_confirm_delete_file', id + '.json') + warning)) return;
  try {
    const res = await fetch(`/ir-database/${encodeURIComponent(id)}.json`, { method: 'DELETE' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    showToast(I18N.t('web_dev_irdb_deleted'));
    irOnDeviceCache = {};
    await tryLoadOnDeviceIrDatabase();
  } catch (e) {
    showToast(I18N.t('web_dev_delete_failed', e), 'error');
  }
  await loadIrDatabaseFiles();
}

// ---- Icons (upload PNGs for use on scene_grid/button_grid cards in the
// builder) — unchanged endpoints, POST /icons + GET /icons-list, this is
// just the upload form + read-only list that used to live on the old
// plain-HTML "/" page. ------------------------------------------------------

async function loadIconsList() {
  const list = document.getElementById('iconsList');
  if (!list) return;
  try {
    const res = await fetch('/icons-list');
    const names = await res.json();
    list.innerHTML = '';
    if (!Array.isArray(names) || !names.length) {
      list.innerHTML = `<div class="hint">${I18N.t('web_dev_no_icons')}</div>`;
      return;
    }
    names.forEach(name => {
      const row = document.createElement('div');
      row.className = 'list-item';
      const label = document.createElement('span');
      label.style.cssText = 'display:flex;align-items:center;gap:8px';
      const img = document.createElement('img');
      img.src = `/icons/${encodeURIComponent(name)}`;
      img.alt = '';
      img.loading = 'lazy';
      img.style.cssText = 'width:24px;height:24px;object-fit:contain';
      label.append(img, document.createTextNode(name));
      const del = document.createElement('span');
      del.className = 'remove';
      del.title = I18N.t('web_dev_icon_delete_title');
      del.textContent = '✕';
      del.addEventListener('click', () => deleteIcon(name));
      row.append(label, del);
      list.appendChild(row);
    });
  } catch (e) {
    list.innerHTML = `<div class="hint">${I18N.t('web_dev_icons_load_failed')}</div>`;
    console.error('Failed to load /icons-list', e);
  }
}

/** How many times dashboard.json mentions this icon's file (cards, scene/
 * button tiles, Activities…) — 0 if the file can't be read. Used only to
 * warn before deleting an icon that's still in use. */
async function countIconUses(name) {
  try {
    const res = await fetch('/dashboard.json');
    if (!res.ok) return 0;
    const text = await res.text();
    return text.split(`/icons/${name}"`).length - 1;
  } catch (e) {
    return 0;
  }
}

async function deleteIcon(name) {
  const uses = await countIconUses(name);
  const warning = uses
    ? '\n\n' + I18N.t('web_dev_icon_still_used', uses)
    : '';
  if (!confirm(I18N.t('web_dev_confirm_delete_file', name) + warning)) return;
  try {
    const res = await fetch(`/icons/${encodeURIComponent(name)}`, { method: 'DELETE' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    showToast(I18N.t('web_dev_icon_deleted'));
  } catch (e) {
    showToast(I18N.t('web_dev_delete_failed', e), 'error');
  }
  await loadIconsList();
}

async function uploadIcon() {
  const input = document.getElementById('iconFile');
  const file = input.files && input.files[0];
  if (!file) { alert(I18N.t('web_dev_err_choose_icon')); return; }
  const btn = document.getElementById('uploadIconBtn');
  const originalText = btn.textContent;
  btn.textContent = I18N.t('web_dev_uploading');
  btn.disabled = true;
  try {
    const form = new FormData();
    form.append('file', file, file.name);
    const res = await fetch('/icons', { method: 'POST', body: form });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    showToast(I18N.t('web_dev_icon_uploaded'));
    input.value = '';
    await loadIconsList();
  } catch (e) {
    showToast(I18N.t('web_dev_upload_failed', e), 'error');
  } finally {
    btn.textContent = originalText;
    btn.disabled = false;
  }
}

// ---- Find my remote — unchanged endpoints, POST /ring + POST /ring/stop ---

async function ringDevice() {
  const btn = document.getElementById('ringBtn');
  const originalText = btn.textContent;
  btn.textContent = I18N.t('web_dev_ringing');
  btn.disabled = true;
  try {
    const body = new URLSearchParams();
    body.set('sound', document.getElementById('ringSound').value);
    body.set('volume', document.getElementById('ringVolume').value);
    body.set('duration', document.getElementById('ringDuration').value);
    const res = await fetch('/ring', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString()
    });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    showToast(I18N.t('web_dev_ringing'));
  } catch (e) {
    showToast(I18N.t('web_dev_ring_failed', e), 'error');
  } finally {
    btn.textContent = originalText;
    btn.disabled = false;
  }
}

async function stopRingDevice() {
  try {
    const res = await fetch('/ring/stop', { method: 'POST' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    showToast(I18N.t('web_dev_stopped'));
  } catch (e) {
    showToast(I18N.t('web_dev_stop_failed', e), 'error');
  }
}

I18N.ready.then(() => {
  applyUiI18n();
  initIrCategorySelect();
  onIrSourceModeChange();
  renderIrCommandsList();
  tryLoadOnDeviceIrDatabase();
  loadIconsList();
  loadIrDatabaseFiles();
  loadAll();
});