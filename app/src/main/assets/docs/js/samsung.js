// ---- Samsung TV (1.5.3, direct, no Home Assistant) --------------------------------
//
// The Devices page's Samsung TV view: lists the TVs and adds one by its IP address.
// POST /samsung-pair connects to the TV, which asks on screen to allow the remote;
// once allowed the remote keeps the TV's token (it never comes to this page) and
// answers the TV's model and MAC address (Wake-on-LAN). The TV is then saved with
// the other connection settings (`samsung_*[]` rows of POST /save-connection, see
// persistHaAndHubs() in devices-page.js) and gets a haDevices catalog entry
// `samsung_<localId>` so every card picker lists it.
//
// Uses devices-page.js globals: samsungs, lgTvs, skys, zidoos, androidTvs, appleTvs, haStates,
// dashboardData, and its helpers (showToast, countLabel, slugify, atvEsc,
// persistHaAndHubs, saveHaDevicesSilently, closeDeviceForms, renderDevicesList,
// deviceCardHtml, deviceStateTone).

function renderSamsungs() {
  const list = document.getElementById('samsungsList');
  if (!list) return;
  list.innerHTML = samsungs.length
    ? samsungs.map(t => {
        const e = haStates && haStates[t.entityId];
        const model = (e && e.attributes && e.attributes.model) || '';
        return deviceCardHtml({
          kind: 'tv',
          name: atvEsc(t.name),
          details: `${model ? atvEsc(model) + ' · ' : ''}${atvEsc(t.host)} · <code>${atvEsc(t.entityId)}</code>`,
          badge: I18N.t('web_dev_samsung_badge'),
          state: samsungStatus(t), tone: deviceStateTone(t.entityId),
          actions: `<span class="remove" style="color:#00E5FF" onclick="renameSamsung('${atvEsc(t.localId)}')">✎</span> <span class="remove" onclick="removeSamsung('${atvEsc(t.localId)}')">✕</span>`
        });
      }).join('')
    : `<div class="hint">${I18N.t('web_dev_list_no_samsung')}</div>`;
}

/** "● on" / "○ off or standby", or "not allowed" when the TV never gave its token. */
function samsungStatus(t) {
  if (t.paired === false) return I18N.t('web_dev_samsung_not_paired');
  const entity = haStates && haStates[t.entityId];
  if (!entity) return '';
  return entity.state === 'on' ? I18N.t('web_dev_adtv_on') : I18N.t('web_dev_samsung_off');
}

function upsertSamsungHaDevice(t) {
  dashboardData.haDevices = dashboardData.haDevices || [];
  const id = 'samsung_' + t.localId;
  const existing = dashboardData.haDevices.find(d => d.id === id);
  if (existing) {
    existing.entityId = t.entityId;
    existing.name = t.name;
  } else {
    dashboardData.haDevices.push({ id, domain: 'media_player', entityId: t.entityId, name: t.name });
  }
}

function uniqueSamsungEntityId(name, ignoreLocalId) {
  const base = 'media_player.samsung_' + slugify(name, 'tv');
  const taken = id => samsungs.some(t => t.entityId === id && t.localId !== ignoreLocalId) || zidoos.some(z => z.entityId === id) ||
    lgTvs.some(t => t.entityId === id) || skys.some(b => b.entityId === id) ||
    androidTvs.some(t => t.entityId === id) || appleTvs.some(t => t.entityId === id) || (haStates && haStates[id]);
  let id = base;
  let n = 2;
  while (taken(id)) id = base + '_' + (n++);
  return id;
}

/** Connects to the TV at the typed IP (it asks on screen to allow the remote), then saves it. */
async function addSamsung() {
  const host = document.getElementById('samsungHost').value.trim();
  if (!/^[A-Za-z0-9.:-]{1,253}$/.test(host)) {
    showToast(I18N.t('web_dev_adtv_enter_ip'), 'error');
    return;
  }
  const again = samsungs.find(t => t.host === host);
  const btn = document.getElementById('samsungAddBtn');
  btn.disabled = true;
  btn.textContent = I18N.t('web_dev_samsung_allow_on_tv');
  try {
    const res = await fetch('/samsung-pair', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ host }).toString()
    });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
    const mac = (data.mac || '').toLowerCase();
    const name = document.getElementById('samsungName').value.trim() || (again && again.name) || (data.model ? 'Samsung ' + data.model : 'Samsung TV');
    // Added again (same address): the TV keeps its entity, so cards keep working.
    const localId = again ? again.localId : (mac ? mac.replace(/:/g, '') : slugify(name, 'samsung') + '_' + Date.now());
    const entry = { localId, name, entityId: again ? again.entityId : uniqueSamsungEntityId(name), host, mac: mac || (again && again.mac) || '', paired: true };
    samsungs = samsungs.filter(t => t.localId !== entry.localId && t.host !== host).concat(entry);
    upsertSamsungHaDevice(entry);
    await persistHaAndHubs();
    await saveHaDevicesSilently();
    closeDeviceForms();
    renderDevicesList();
    showToast(I18N.t('web_dev_samsung_added', entry.entityId));
    if (!entry.mac) showToast(I18N.t('web_dev_samsung_no_mac'), 'error');
  } catch (e) {
    showToast(I18N.t('web_dev_samsung_failed', e.message), 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = I18N.t('web_dev_samsung_add_btn');
  }
}

async function renameSamsung(localId) {
  const t = samsungs.find(x => x.localId === localId);
  if (!t) return;
  const name = prompt(I18N.t('web_dev_lgtv_rename_prompt'), t.name);
  if (name === null || !name.trim()) return;
  t.name = name.trim(); // the entity id is kept, so existing cards keep working
  upsertSamsungHaDevice(t);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}

async function removeSamsung(localId) {
  const t = samsungs.find(x => x.localId === localId);
  if (!t) return;
  if (!confirm(I18N.t('web_dev_confirm_remove_zidoo', t.name, t.entityId))) return;
  samsungs = samsungs.filter(x => x.localId !== localId);
  dashboardData.haDevices = (dashboardData.haDevices || []).filter(d => d.id !== 'samsung_' + localId);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}
