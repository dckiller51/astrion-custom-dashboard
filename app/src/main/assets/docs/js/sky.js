// ---- Sky Q / Sky+ HD (1.5.3, direct, no Home Assistant) -------------------------------
//
// The Devices page's Sky view: lists the boxes and adds one by its IP address. No
// pairing: the remote asks what answers there (POST /sky-check: a Sky Q's key port
// 49160, else a Sky+ HD's 5900), then the box is saved with the other connection
// settings (`sky_*[]` rows of POST /save-connection, see persistHaAndHubs() in
// devices-page.js) and gets a haDevices catalog entry `sky_<localId>` so every card
// picker lists it.
//
// Uses devices-page.js globals: skys, zidoos, lgTvs, androidTvs, appleTvs, haStates,
// dashboardData, and its helpers (showToast, slugify, atvEsc, deviceCardHtml,
// deviceStateTone, persistHaAndHubs, saveHaDevicesSilently, closeDeviceForms,
// renderDevicesList).

function renderSkys() {
  const list = document.getElementById('skysList');
  if (!list) return;
  list.innerHTML = skys.length
    ? skys.map(b => {
        const e = haStates && haStates[b.entityId];
        const a = (e && e.attributes) || {};
        const now = [a.media_channel, a.media_title !== a.media_channel ? a.media_title : ''].filter(Boolean).join(' · ');
        return deviceCardHtml({
          kind: 'box',
          name: atvEsc(b.name),
          details: `${now ? atvEsc(now) + ' · ' : ''}${atvEsc(b.host)} · <code>${atvEsc(b.entityId)}</code>`,
          badge: b.kind === 'skyhd' ? 'Sky+ HD' : 'Sky Q',
          state: skyStatus(b), tone: deviceStateTone(b.entityId),
          actions: `<span class="remove" style="color:#00E5FF" onclick="renameSky('${atvEsc(b.localId)}')">✎</span> <span class="remove" onclick="removeSky('${atvEsc(b.localId)}')">✕</span>`
        });
      }).join('')
    : `<div class="hint">${I18N.t('web_dev_list_no_sky')}</div>`;
}

/** "▶ playing" / "❚❚ paused" / "● on" / "○ off or standby"; a Sky+ HD says nothing. */
function skyStatus(b) {
  if (b.kind === 'skyhd') return I18N.t('web_dev_sky_keys_only');
  const entity = haStates && haStates[b.entityId];
  if (!entity) return '';
  if (entity.state === 'playing') return I18N.t('web_dev_adtv_playing');
  if (entity.state === 'paused') return I18N.t('web_dev_adtv_paused');
  if (entity.state === 'on') return I18N.t('web_dev_adtv_on');
  return I18N.t('web_dev_sky_off');
}

function upsertSkyHaDevice(b) {
  dashboardData.haDevices = dashboardData.haDevices || [];
  const id = 'sky_' + b.localId;
  const existing = dashboardData.haDevices.find(d => d.id === id);
  if (existing) {
    existing.entityId = b.entityId;
    existing.name = b.name;
  } else {
    dashboardData.haDevices.push({ id, domain: 'media_player', entityId: b.entityId, name: b.name });
  }
}

function uniqueSkyEntityId(name) {
  const base = 'media_player.sky_' + slugify(name, 'box');
  const taken = id => skys.some(b => b.entityId === id) || zidoos.some(z => z.entityId === id) ||
    lgTvs.some(t => t.entityId === id) || samsungs.some(t => t.entityId === id) || androidTvs.some(t => t.entityId === id) ||
    appleTvs.some(t => t.entityId === id) || (haStates && haStates[id]);
  let id = base;
  let n = 2;
  while (taken(id)) id = base + '_' + (n++);
  return id;
}

/** Asks what answers at the typed IP, then saves the box. */
async function addSky() {
  const host = document.getElementById('skyHost').value.trim();
  if (!/^[A-Za-z0-9.:-]{1,253}$/.test(host)) {
    showToast(I18N.t('web_dev_adtv_enter_ip'), 'error');
    return;
  }
  if (skys.some(b => b.host === host)) {
    showToast(I18N.t('web_dev_already_added'), 'error');
    return;
  }
  const btn = document.getElementById('skyAddBtn');
  btn.disabled = true;
  btn.textContent = I18N.t('web_dev_contacting', host);
  try {
    const res = await fetch('/sky-check', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ host }).toString()
    });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
    const kind = data.kind === 'skyhd' ? 'skyhd' : 'skyq';
    const name = document.getElementById('skyName').value.trim() || (kind === 'skyhd' ? 'Sky+ HD' : 'Sky Q');
    const entry = {
      localId: slugify(name, 'sky') + '_' + Date.now().toString(36),
      name,
      entityId: uniqueSkyEntityId(name),
      host,
      kind
    };
    skys = skys.concat(entry);
    upsertSkyHaDevice(entry);
    await persistHaAndHubs();
    await saveHaDevicesSilently();
    closeDeviceForms();
    renderDevicesList();
    showToast(I18N.t(kind === 'skyhd' ? 'web_dev_sky_added_hd' : 'web_dev_sky_added', entry.entityId));
  } catch (e) {
    showToast(I18N.t('web_dev_sky_failed', e.message), 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = I18N.t('web_dev_sky_add_btn');
  }
}

async function renameSky(localId) {
  const b = skys.find(x => x.localId === localId);
  if (!b) return;
  const name = prompt(I18N.t('web_dev_zidoo_rename_prompt'), b.name);
  if (name === null || !name.trim()) return;
  b.name = name.trim(); // the entity id is kept, so existing cards keep working
  upsertSkyHaDevice(b);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}

async function removeSky(localId) {
  const b = skys.find(x => x.localId === localId);
  if (!b) return;
  if (!confirm(I18N.t('web_dev_confirm_remove_zidoo', b.name, b.entityId))) return;
  skys = skys.filter(x => x.localId !== localId);
  dashboardData.haDevices = (dashboardData.haDevices || []).filter(d => d.id !== 'sky_' + localId);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}
