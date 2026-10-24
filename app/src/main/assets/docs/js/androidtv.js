// ---- Android TV (1.3.6, direct, no Home Assistant) ---------------------------
//
// The Devices page's Android TV view: lists the paired TVs, finds new ones on
// the network (the remote does the mDNS lookup, GET /androidtv-scan) or takes
// an IP typed by hand, and pairs them the way the Google TV app does — the TV
// shows a 6-character code (POST /androidtv-pair-start), the code is sent back
// (POST /androidtv-pair-finish). Paired TVs are saved with the other connection
// settings (`adtv_*[]` rows of POST /save-connection, see persistHaAndHubs()
// in devices-page.js) and get a haDevices catalog entry `androidtv_<localId>`
// so every card picker lists them.
//
// Uses devices-page.js globals: androidTvs, androidTvScanResults,
// androidTvPairing, haStates, dashboardData, and its helpers (showToast,
// countLabel, slugify, atvEsc, persistHaAndHubs, saveHaDevicesSilently,
// setDisplay, closeDeviceForms).

const ANDROID_TV_CODE = /^[0-9A-F]{6}$/;

function renderAndroidTvs() {
  const tile = document.getElementById('tileAndroidTvCount');
  if (tile) {
    tile.textContent = androidTvs.length
      ? countLabel(androidTvs.length, 'web_dev_count_adtv')
      : I18N.t('web_dev_no_android_tv_yet');
  }
  const list = document.getElementById('androidTvsList');
  if (!list) return;
  list.innerHTML = androidTvs.length
    ? androidTvs.map(tv => {
        const status = androidTvStatus(tv);
        const helper = androidTvHelperStatus(tv);
        return `<div class="list-item"><span>${atvEsc(tv.name)} <span style="color:#888">(<code>${atvEsc(tv.entityId)}</code>${status ? ' · ' + status : ''}${helper ? ' · ' + helper : ''})</span></span>` +
          `<span><span class="remove" style="color:#00E5FF" onclick="linkAndroidTvHelper('${atvEsc(tv.localId)}')">${I18N.t(tv.helperCode ? 'web_dev_adtv_helper_edit' : 'web_dev_adtv_helper_add')}</span> ` +
          `<span class="remove" style="color:#00E5FF" onclick="renameAndroidTv('${atvEsc(tv.localId)}')">✎</span> <span class="remove" onclick="removeAndroidTv('${atvEsc(tv.localId)}')">✕</span></span></div>`;
      }).join('')
    : `<div class="hint">${I18N.t('web_dev_list_no_adtv')}</div>`;
}

/** "● on" / "● off" / "○ not reachable" / "⚠ pair again", from the entity the remote publishes. */
function androidTvStatus(tv) {
  const entity = haStates && haStates[tv.entityId];
  if (!entity) return '';
  if (entity.attributes && entity.attributes.astrion_needs_pairing) {
    return `<span style="color:#e0a000">${I18N.t('web_dev_adtv_needs_pairing')}</span>`;
  }
  if (entity.state === 'unavailable') return I18N.t('web_dev_atv_unreachable');
  if (entity.state === 'on') return I18N.t('web_dev_adtv_on');
  if (entity.state === 'off') return I18N.t('web_dev_adtv_off');
  if (entity.state === 'playing') return I18N.t('web_dev_adtv_playing');
  if (entity.state === 'paused') return I18N.t('web_dev_adtv_paused');
  return I18N.t('web_dev_atv_connected');
}

/** The Astrion TV Helper's link (1.3.6), from the entity's `astrion_helper` attribute; '' when none is set up. */
function androidTvHelperStatus(tv) {
  if (!tv.helperCode) return '';
  const entity = haStates && haStates[tv.entityId];
  const link = entity && entity.attributes ? entity.attributes.astrion_helper : null;
  if (link === 'connected') return I18N.t('web_dev_adtv_helper_ok');
  if (link === 'wrong_code') return `<span style="color:#e0a000">${I18N.t('web_dev_adtv_helper_wrong_code')}</span>`;
  if (link === 'unreachable') return I18N.t('web_dev_adtv_helper_unreachable');
  return I18N.t('web_dev_adtv_helper_set');
}

/**
 * Links the Astrion TV Helper installed on [localId]'s TV: asks for the code it shows, checks it
 * against the helper (POST /androidtv-helper-check), then saves it with the TV. Empty = unlink.
 */
async function linkAndroidTvHelper(localId) {
  const tv = androidTvs.find(t => t.localId === localId);
  if (!tv) return;
  const typed = prompt(I18N.t('web_dev_adtv_helper_prompt', tv.name), tv.helperCode || '');
  if (typed === null) return;
  const code = typed.trim().toUpperCase();
  if (!code) {
    tv.helperCode = '';
    await persistHaAndHubs();
    return;
  }
  if (!/^[A-Z0-9]{6}$/.test(code)) { showToast(I18N.t('web_dev_adtv_helper_bad_format'), 'error'); return; }
  try {
    const res = await fetch('/androidtv-helper-check', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ host: tv.host, code }).toString()
    });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
  } catch (e) {
    showToast(I18N.t('web_dev_adtv_helper_failed', e.message), 'error');
    return;
  }
  tv.helperCode = code;
  await persistHaAndHubs();
  showToast(I18N.t('web_dev_adtv_helper_linked', tv.name));
}

function upsertAndroidTvHaDevice(tv) {
  dashboardData.haDevices = dashboardData.haDevices || [];
  const id = 'androidtv_' + tv.localId;
  const existing = dashboardData.haDevices.find(d => d.id === id);
  if (existing) {
    existing.entityId = tv.entityId;
    existing.name = tv.name;
  } else {
    dashboardData.haDevices.push({ id, domain: 'media_player', entityId: tv.entityId, name: tv.name });
  }
}

function removeAndroidTvHaDevice(localId) {
  dashboardData.haDevices = (dashboardData.haDevices || []).filter(d => d.id !== 'androidtv_' + localId);
}

function showAndroidTvStep(step) {
  setDisplay('androidTvScanBox', step === 'scan' ? '' : 'none');
  setDisplay('androidTvCodeBox', step === 'code' ? '' : 'none');
}

/** Browses the LAN for Android TVs and lists them; a TV can also be added by IP below the list. */
async function scanAndroidTvs() {
  androidTvPairing = null;
  showAndroidTvStep('scan');
  const status = document.getElementById('androidTvScanStatus');
  const results = document.getElementById('androidTvScanResults');
  status.textContent = I18N.t('web_dev_searching_your_network');
  results.innerHTML = '';
  try {
    const res = await fetch('/androidtv-scan');
    const data = await res.json();
    androidTvScanResults = Array.isArray(data.devices) ? data.devices : [];
  } catch (e) {
    androidTvScanResults = [];
    status.textContent = I18N.t('web_dev_search_failed', e);
    return;
  }
  if (!androidTvScanResults.length) {
    status.textContent = I18N.t('web_dev_adtv_none_found');
    return;
  }
  status.textContent = I18N.t('web_dev_adtv_choose');
  results.innerHTML = androidTvScanResults.map((tv, i) => {
    const known = androidTvs.some(x => (tv.serviceName && x.serviceName === tv.serviceName) || x.host === tv.host);
    const action = known
      ? `<span style="color:#888">${I18N.t('web_dev_already_added')}</span>`
      : `<button type="button" onclick="startAndroidTvPairing(${i})">${I18N.t('web_dev_pair')}</button>`;
    return `<div class="list-item"><span>${atvEsc(tv.name)} <span style="color:#888">(${atvEsc(tv.host)})</span></span>${action}</div>`;
  }).join('');
}

function startAndroidTvPairing(index) {
  const device = androidTvScanResults[index];
  if (device) beginAndroidTvPairing({ host: device.host, serviceName: device.serviceName || '', name: device.name || '' });
}

function startAndroidTvPairingManual() {
  const host = document.getElementById('androidTvManualHost').value.trim();
  if (!/^[A-Za-z0-9.:-]{1,253}$/.test(host)) {
    showToast(I18N.t('web_dev_adtv_enter_ip'), 'error');
    return;
  }
  beginAndroidTvPairing({ host, serviceName: '', name: '' });
}

/** Asks the TV to show its code (the first pairing also creates this remote's key: a few seconds). */
async function beginAndroidTvPairing(device) {
  const status = document.getElementById('androidTvScanStatus');
  status.textContent = I18N.t('web_dev_contacting', device.name || device.host);
  try {
    const res = await fetch('/androidtv-pair-start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ host: device.host }).toString()
    });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
    androidTvPairing = { sessionId: data.sessionId, device };
  } catch (e) {
    status.textContent = I18N.t('web_dev_pair_start_failed', e.message);
    return;
  }
  showAndroidTvStep('code');
  document.getElementById('androidTvCode').value = '';
  document.getElementById('androidTvName').value = device.name || '';
  document.getElementById('androidTvCode').focus();
}

function uniqueAndroidTvEntityId(name, ignoreLocalId) {
  const base = 'media_player.androidtv_' + slugify(name, 'tv');
  const own = id => androidTvs.some(t => t.entityId === id && t.localId === ignoreLocalId);
  const taken = id => !own(id) && (androidTvs.some(t => t.entityId === id) || appleTvs.some(t => t.entityId === id) || (haStates && haStates[id]));
  let id = base;
  let n = 2;
  while (taken(id)) id = base + '_' + (n++);
  return id;
}

async function finishAndroidTvPairing() {
  if (!androidTvPairing) return;
  const code = document.getElementById('androidTvCode').value.trim().toUpperCase();
  if (!ANDROID_TV_CODE.test(code)) { showToast(I18N.t('web_dev_adtv_enter_code'), 'error'); return; }
  const btn = document.getElementById('androidTvFinishBtn');
  btn.disabled = true;
  btn.textContent = I18N.t('web_dev_pairing');
  try {
    const res = await fetch('/androidtv-pair-finish', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ sessionId: androidTvPairing.sessionId, code }).toString()
    });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
    const device = androidTvPairing.device;
    const name = document.getElementById('androidTvName').value.trim() || data.name || device.name || 'Android TV';
    const existing = androidTvs.find(t => t.localId === data.localId);
    const entry = {
      localId: data.localId,
      name,
      // Re-pairing a known TV keeps its entity id, so the cards using it keep working.
      entityId: existing ? existing.entityId : uniqueAndroidTvEntityId(name, data.localId),
      host: device.host,
      serviceName: device.serviceName || (existing ? existing.serviceName : ''),
      mac: data.mac || '',
      certSha256: data.certSha256,
      helperCode: existing ? existing.helperCode || '' : ''
    };
    androidTvs = existing ? androidTvs.map(t => (t.localId === data.localId ? entry : t)) : androidTvs.concat(entry);
    androidTvPairing = null;
    upsertAndroidTvHaDevice(entry);
    await persistHaAndHubs();
    await saveHaDevicesSilently();
    closeDeviceForms();
    renderDevicesList();
    showToast(I18N.t('web_dev_adtv_paired', entry.entityId));
  } catch (e) {
    showToast(I18N.t('web_dev_pair_failed', e.message), 'error');
    // The TV ends the pairing session after an attempt, right or wrong: start over from the list.
    scanAndroidTvs();
  } finally {
    btn.disabled = false;
    btn.textContent = I18N.t('web_dev_pair');
  }
}

async function renameAndroidTv(localId) {
  const tv = androidTvs.find(t => t.localId === localId);
  if (!tv) return;
  const name = prompt(I18N.t('web_dev_adtv_rename_prompt'), tv.name);
  if (name === null || !name.trim()) return;
  tv.name = name.trim(); // the entity id is kept, so existing cards keep working
  upsertAndroidTvHaDevice(tv);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}

async function removeAndroidTv(localId) {
  const tv = androidTvs.find(t => t.localId === localId);
  if (!tv) return;
  if (!confirm(I18N.t('web_dev_confirm_remove_adtv', tv.name, tv.entityId))) return;
  androidTvs = androidTvs.filter(t => t.localId !== localId);
  removeAndroidTvHaDevice(localId);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}
