// ---- Zidoo (1.4.6, direct, no Home Assistant) -------------------------------
//
// The Devices page's Zidoo view: lists the players and adds one by its IP
// address. No pairing: the remote asks the player's control API (port 9529,
// POST /zidoo-check) for its model and MAC address (Wake-on-LAN), then the
// player is saved with the other connection settings (`zidoo_*[]` rows of
// POST /save-connection, see persistHaAndHubs() in devices-page.js) and gets a
// haDevices catalog entry `zidoo_<localId>` so every card picker lists it.
//
// Uses devices-page.js globals: zidoos, androidTvs, appleTvs, haStates,
// dashboardData, and its helpers (showToast, countLabel, slugify, atvEsc,
// persistHaAndHubs, saveHaDevicesSilently, closeDeviceForms, renderDevicesList).

function renderZidoos() {
  const tile = document.getElementById('tileZidooCount');
  if (tile) {
    tile.textContent = zidoos.length
      ? countLabel(zidoos.length, 'web_dev_count_zidoo')
      : I18N.t('web_dev_no_zidoo_yet');
  }
  const list = document.getElementById('zidoosList');
  if (!list) return;
  list.innerHTML = zidoos.length
    ? zidoos.map(z => {
        const kind = zidooKind(z);
        const model = zidooModel(z);
        return deviceCardHtml({
          kind: kind === 'audio' ? 'audio' : 'player',
          name: atvEsc(z.name),
          details: `${model ? atvEsc(model) + ' · ' : ''}${atvEsc(z.host)} · <code>${atvEsc(z.entityId)}</code>`,
          badge: I18N.t(kind === 'audio' ? 'web_dev_kind_audio_streamer' : 'web_dev_kind_video_player'),
          state: zidooStatus(z), tone: deviceStateTone(z.entityId),
          actions: `<span class="remove" style="color:#00E5FF" onclick="renameZidoo('${atvEsc(z.localId)}')">✎</span> <span class="remove" onclick="removeZidoo('${atvEsc(z.localId)}')">✕</span>`
        });
      }).join('')
    : `<div class="hint">${I18N.t('web_dev_list_no_zidoo')}</div>`;
}

/** The model the player announces (Z9X, DMP-A6…), from the entity the remote publishes. */
function zidooModel(z) {
  const e = haStates && haStates[z.entityId];
  return (e && e.attributes && e.attributes.model) || '';
}

/** 'audio' for an Eversolo streamer (DMP-…), else 'video' (a Zidoo player) (1.4.9). */
function zidooKind(z) {
  const e = haStates && haStates[z.entityId];
  const a = (e && e.attributes) || {};
  return /eversolo/i.test(a.manufacturer || '') || /^DMP/i.test(a.model || '') ? 'audio' : 'video';
}

/** "● on" / "▶ playing" / "❚❚ paused" / "○ off", from the entity the remote publishes. */
function zidooStatus(z) {
  const entity = haStates && haStates[z.entityId];
  if (!entity) return '';
  if (entity.state === 'playing') return I18N.t('web_dev_adtv_playing');
  if (entity.state === 'paused') return I18N.t('web_dev_adtv_paused');
  if (entity.state === 'on') return I18N.t('web_dev_adtv_on');
  return I18N.t('web_dev_zidoo_off');
}

function upsertZidooHaDevice(z) {
  dashboardData.haDevices = dashboardData.haDevices || [];
  const id = 'zidoo_' + z.localId;
  const existing = dashboardData.haDevices.find(d => d.id === id);
  if (existing) {
    existing.entityId = z.entityId;
    existing.name = z.name;
  } else {
    dashboardData.haDevices.push({ id, domain: 'media_player', entityId: z.entityId, name: z.name });
  }
}

function removeZidooHaDevice(localId) {
  dashboardData.haDevices = (dashboardData.haDevices || []).filter(d => d.id !== 'zidoo_' + localId);
}

function uniqueZidooEntityId(name) {
  const base = 'media_player.zidoo_' + slugify(name, 'player');
  const taken = id => zidoos.some(z => z.entityId === id) || androidTvs.some(t => t.entityId === id) ||
    appleTvs.some(t => t.entityId === id) || (haStates && haStates[id]);
  let id = base;
  let n = 2;
  while (taken(id)) id = base + '_' + (n++);
  return id;
}

/** Asks the player at the typed IP who it is, then saves it. */
async function addZidoo() {
  const host = document.getElementById('zidooHost').value.trim();
  if (!/^[A-Za-z0-9.:-]{1,253}$/.test(host)) {
    showToast(I18N.t('web_dev_adtv_enter_ip'), 'error');
    return;
  }
  if (zidoos.some(z => z.host === host)) {
    showToast(I18N.t('web_dev_already_added'), 'error');
    return;
  }
  const btn = document.getElementById('zidooAddBtn');
  btn.disabled = true;
  btn.textContent = I18N.t('web_dev_contacting', host);
  try {
    const res = await fetch('/zidoo-check', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ host }).toString()
    });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
    const name = document.getElementById('zidooName').value.trim() || data.model || 'Zidoo';
    const mac = (data.mac || '').toLowerCase();
    const entry = {
      localId: mac ? mac.replace(/:/g, '') : slugify(name, 'zidoo') + '_' + Date.now(),
      name,
      entityId: uniqueZidooEntityId(name),
      host,
      mac
    };
    zidoos = zidoos.filter(z => z.localId !== entry.localId).concat(entry);
    upsertZidooHaDevice(entry);
    await persistHaAndHubs();
    await saveHaDevicesSilently();
    closeDeviceForms();
    renderDevicesList();
    showToast(I18N.t('web_dev_zidoo_added', entry.entityId));
    if (!mac) showToast(I18N.t('web_dev_zidoo_no_mac'), 'error');
  } catch (e) {
    showToast(I18N.t('web_dev_zidoo_failed', e.message), 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = I18N.t('web_dev_zidoo_add_btn');
  }
}

async function renameZidoo(localId) {
  const z = zidoos.find(x => x.localId === localId);
  if (!z) return;
  const name = prompt(I18N.t('web_dev_zidoo_rename_prompt'), z.name);
  if (name === null || !name.trim()) return;
  z.name = name.trim(); // the entity id is kept, so existing cards keep working
  upsertZidooHaDevice(z);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}

async function removeZidoo(localId) {
  const z = zidoos.find(x => x.localId === localId);
  if (!z) return;
  if (!confirm(I18N.t('web_dev_confirm_remove_zidoo', z.name, z.entityId))) return;
  zidoos = zidoos.filter(x => x.localId !== localId);
  removeZidooHaDevice(localId);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}
