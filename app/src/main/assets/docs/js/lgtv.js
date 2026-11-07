// ---- LG TV (1.5.2, direct, no Home Assistant) --------------------------------
//
// The Devices page's LG TV view: lists the TVs and pairs one by its IP address.
// POST /lgtv-pair connects to the TV, which asks on screen to accept the remote;
// once accepted the remote keeps the TV's key (it never comes to this page) and
// answers the TV's model and MAC address (Wake-on-LAN). The TV is then saved with
// the other connection settings (`lgtv_*[]` rows of POST /save-connection, see
// persistHaAndHubs() in devices-page.js) and gets a haDevices catalog entry
// `lgtv_<localId>` so every card picker lists it.
//
// Uses devices-page.js globals: lgTvs, zidoos, androidTvs, appleTvs, haStates,
// dashboardData, and its helpers (showToast, countLabel, slugify, atvEsc,
// persistHaAndHubs, saveHaDevicesSilently, closeDeviceForms, renderDevicesList,
// deviceCardHtml, deviceStateTone).

function renderLgTvs() {
  const list = document.getElementById('lgTvsList');
  if (!list) return;
  list.innerHTML = lgTvs.length
    ? lgTvs.map(t => {
        const e = haStates && haStates[t.entityId];
        const model = (e && e.attributes && e.attributes.model) || '';
        return deviceCardHtml({
          kind: 'tv',
          name: atvEsc(t.name),
          details: `${model ? atvEsc(model) + ' · ' : ''}${atvEsc(t.host)} · <code>${atvEsc(t.entityId)}</code>`,
          badge: I18N.t('web_dev_lgtv_badge'),
          state: lgTvStatus(t), tone: deviceStateTone(t.entityId),
          actions: `<span class="remove" style="color:#00E5FF" onclick="renameLgTv('${atvEsc(t.localId)}')">✎</span> <span class="remove" onclick="removeLgTv('${atvEsc(t.localId)}')">✕</span>`
        });
      }).join('')
    : `<div class="hint">${I18N.t('web_dev_list_no_lgtv')}</div>`;
}

/** "● on" / "○ off", or "not paired" when the TV never gave its key. */
function lgTvStatus(t) {
  if (t.paired === false) return I18N.t('web_dev_lgtv_not_paired');
  const entity = haStates && haStates[t.entityId];
  if (!entity) return '';
  return entity.state === 'on' ? I18N.t('web_dev_adtv_on') : I18N.t('web_dev_zidoo_off');
}

function upsertLgTvHaDevice(t) {
  dashboardData.haDevices = dashboardData.haDevices || [];
  const id = 'lgtv_' + t.localId;
  const existing = dashboardData.haDevices.find(d => d.id === id);
  if (existing) {
    existing.entityId = t.entityId;
    existing.name = t.name;
  } else {
    dashboardData.haDevices.push({ id, domain: 'media_player', entityId: t.entityId, name: t.name });
  }
}

function uniqueLgTvEntityId(name, ignoreLocalId) {
  const base = 'media_player.lgtv_' + slugify(name, 'tv');
  const taken = id => lgTvs.some(t => t.entityId === id && t.localId !== ignoreLocalId) || zidoos.some(z => z.entityId === id) ||
    samsungs.some(t => t.entityId === id) || skys.some(b => b.entityId === id) ||
    androidTvs.some(t => t.entityId === id) || appleTvs.some(t => t.entityId === id) || (haStates && haStates[id]);
  let id = base;
  let n = 2;
  while (taken(id)) id = base + '_' + (n++);
  return id;
}

/** Pairs the TV at the typed IP (it asks on screen), then saves it. */
async function addLgTv() {
  const host = document.getElementById('lgTvHost').value.trim();
  if (!/^[A-Za-z0-9.:-]{1,253}$/.test(host)) {
    showToast(I18N.t('web_dev_adtv_enter_ip'), 'error');
    return;
  }
  const again = lgTvs.find(t => t.host === host);
  const btn = document.getElementById('lgTvAddBtn');
  btn.disabled = true;
  btn.textContent = I18N.t('web_dev_lgtv_accept_on_tv');
  try {
    const res = await fetch('/lgtv-pair', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ host }).toString()
    });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
    const mac = (data.mac || '').toLowerCase();
    const name = document.getElementById('lgTvName').value.trim() || (again && again.name) || (data.model ? 'LG ' + data.model : 'LG TV');
    // Paired again (same address): the TV keeps its entity, so cards keep working.
    const localId = again ? again.localId : (mac ? mac.replace(/:/g, '') : slugify(name, 'lgtv') + '_' + Date.now());
    const entry = { localId, name, entityId: again ? again.entityId : uniqueLgTvEntityId(name), host, mac: mac || (again && again.mac) || '', paired: true };
    lgTvs = lgTvs.filter(t => t.localId !== entry.localId && t.host !== host).concat(entry);
    upsertLgTvHaDevice(entry);
    await persistHaAndHubs();
    await saveHaDevicesSilently();
    closeDeviceForms();
    renderDevicesList();
    showToast(I18N.t('web_dev_lgtv_added', entry.entityId));
    if (!entry.mac) showToast(I18N.t('web_dev_lgtv_no_mac'), 'error');
  } catch (e) {
    showToast(I18N.t('web_dev_lgtv_failed', e.message), 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = I18N.t('web_dev_lgtv_add_btn');
  }
}

async function renameLgTv(localId) {
  const t = lgTvs.find(x => x.localId === localId);
  if (!t) return;
  const name = prompt(I18N.t('web_dev_lgtv_rename_prompt'), t.name);
  if (name === null || !name.trim()) return;
  t.name = name.trim(); // the entity id is kept, so existing cards keep working
  upsertLgTvHaDevice(t);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}

async function removeLgTv(localId) {
  const t = lgTvs.find(x => x.localId === localId);
  if (!t) return;
  if (!confirm(I18N.t('web_dev_confirm_remove_zidoo', t.name, t.entityId))) return;
  lgTvs = lgTvs.filter(x => x.localId !== localId);
  dashboardData.haDevices = (dashboardData.haDevices || []).filter(d => d.id !== 'lgtv_' + localId);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}
