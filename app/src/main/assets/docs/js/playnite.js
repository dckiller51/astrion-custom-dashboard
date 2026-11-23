// ---- PC running Playnite (1.6.3, direct, no Home Assistant) -----------------------------
//
// The Devices page's Playnite view: lists the PCs and adds one by its IP address and the
// code the Astrion Custom Dashboard extension shows in Playnite (Extensions › Astrion). POST /playnite-check
// asks the extension (it gives the PC's name and MAC address, for Wake-on-LAN), then the PC
// is saved with the other connection settings (`playnite_*[]` rows of POST /save-connection,
// see persistHaAndHubs() in devices-page.js) and gets a haDevices catalog entry
// `playnite_<localId>` so every card picker lists it. The code is sent once and stays on
// the remote.
//
// Uses devices-page.js globals and helpers, as sky.js does.

function renderPlaynites() {
  const list = document.getElementById('playnitesList');
  if (!list) return;
  list.innerHTML = playnites.length
    ? playnites.map(p => {
        const e = haStates && haStates[p.entityId];
        const a = (e && e.attributes) || {};
        return deviceCardHtml({
          kind: 'player',
          name: atvEsc(p.name),
          details: `${a.media_title ? atvEsc(a.media_title) + ' · ' : ''}${atvEsc(p.host)} · <code>${atvEsc(p.entityId)}</code>`
            + `<br><span class="hint">${I18N.t('web_dev_adtv_wol')}: ${p.mac ? atvEsc(p.mac) : '—'}</span>`,
          badge: 'Playnite',
          state: playniteStatus(p), tone: deviceStateTone(p.entityId),
          actions: `<span class="remove" style="color:#00E5FF" title="${I18N.t('web_dev_adtv_wol')}" onclick="editPlayniteMac('${atvEsc(p.localId)}')">⏻</span> <span class="remove" style="color:#00E5FF" onclick="renamePlaynite('${atvEsc(p.localId)}')">✎</span> <span class="remove" onclick="removePlaynite('${atvEsc(p.localId)}')">✕</span>`
        });
      }).join('')
    : `<div class="hint">${I18N.t('web_dev_list_no_playnite')}</div>`;
}

/** "▶ playing" / "● on" / "○ off" / "⚠ code refused". */
function playniteStatus(p) {
  const entity = haStates && haStates[p.entityId];
  if (!entity) return '';
  if (entity.attributes && entity.attributes.astrion_code_refused) return I18N.t('web_dev_playnite_code_refused');
  if (entity.state === 'playing') return I18N.t('web_dev_adtv_playing');
  if (entity.state === 'on') return I18N.t('web_dev_adtv_on');
  return I18N.t('web_dev_zidoo_off');
}

function openPlayniteForm() {
  document.getElementById('playniteForm').style.display = '';
  ['playniteHost', 'playniteCode', 'playniteName'].forEach(id => { document.getElementById(id).value = ''; });
  document.getElementById('playniteForm').scrollIntoView({ behavior: 'smooth', block: 'center' });
  document.getElementById('playniteHost').focus();
}

function upsertPlayniteHaDevice(p) {
  dashboardData.haDevices = dashboardData.haDevices || [];
  const id = 'playnite_' + p.localId;
  const existing = dashboardData.haDevices.find(d => d.id === id);
  if (existing) {
    existing.entityId = p.entityId;
    existing.name = p.name;
  } else {
    dashboardData.haDevices.push({ id, domain: 'media_player', entityId: p.entityId, name: p.name });
  }
}

function uniquePlayniteEntityId(name) {
  const base = 'media_player.playnite_' + slugify(name, 'pc');
  const taken = id => playnites.some(p => p.entityId === id) || (haStates && haStates[id]);
  let id = base;
  let n = 2;
  while (taken(id)) id = base + '_' + (n++);
  return id;
}

/** Asks the extension at the typed IP, with the code, then saves the PC. */
async function addPlaynite() {
  const host = document.getElementById('playniteHost').value.trim();
  const code = document.getElementById('playniteCode').value.replace(/\s/g, '');
  if (!/^[A-Za-z0-9.:-]{1,253}$/.test(host)) {
    showToast(I18N.t('web_dev_adtv_enter_ip'), 'error');
    return;
  }
  if (!/^[0-9]{6}$/.test(code)) {
    showToast(I18N.t('web_dev_playnite_enter_code'), 'error');
    return;
  }
  if (playnites.some(p => p.host === host)) {
    showToast(I18N.t('web_dev_already_added'), 'error');
    return;
  }
  const btn = document.getElementById('playniteAddBtn');
  btn.disabled = true;
  btn.textContent = I18N.t('web_dev_contacting', host);
  try {
    const res = await fetch('/playnite-check', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ host, code }).toString()
    });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
    const name = document.getElementById('playniteName').value.trim() || data.name || 'PC';
    const entry = {
      localId: slugify(name, 'pc') + '_' + Date.now().toString(36),
      name,
      entityId: uniquePlayniteEntityId(name),
      host,
      mac: data.mac || '',
      code,
      hasCode: true
    };
    playnites = playnites.concat(entry);
    upsertPlayniteHaDevice(entry);
    await persistHaAndHubs();
    await saveHaDevicesSilently();
    closeDeviceForms();
    renderDevicesList();
    showToast(I18N.t('web_dev_playnite_added', entry.entityId));
    if (!entry.mac) showToast(I18N.t('web_dev_playnite_no_mac'), 'error');
  } catch (e) {
    showToast(I18N.t('web_dev_playnite_failed', e.message), 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = I18N.t('web_dev_sky_add_btn');
  }
}

async function renamePlaynite(localId) {
  const p = playnites.find(x => x.localId === localId);
  if (!p) return;
  const name = prompt(I18N.t('web_dev_zidoo_rename_prompt'), p.name);
  if (name === null || !name.trim()) return;
  p.name = name.trim(); // the entity id is kept, so existing cards keep working
  upsertPlayniteHaDevice(p);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}

/** The MAC address Wake-on-LAN uses: the extension (0.1.1+) gives the wired card's, this corrects it by hand. */
async function editPlayniteMac(localId) {
  const p = playnites.find(x => x.localId === localId);
  if (!p) return;
  const mac = prompt(I18N.t('web_dev_playnite_mac_prompt', p.name), p.mac || '');
  if (mac === null) return;
  const clean = mac.trim().toLowerCase().replace(/-/g, ':');
  if (clean && !/^[0-9a-f]{2}(:[0-9a-f]{2}){5}$/.test(clean)) {
    showToast(I18N.t('web_dev_adtv_wol_bad'), 'error');
    return;
  }
  p.mac = clean;
  await persistHaAndHubs();
  renderPlaynites();
}

async function removePlaynite(localId) {
  const p = playnites.find(x => x.localId === localId);
  if (!p) return;
  if (!confirm(I18N.t('web_dev_confirm_remove_zidoo', p.name, p.entityId))) return;
  playnites = playnites.filter(x => x.localId !== localId);
  dashboardData.haDevices = (dashboardData.haDevices || []).filter(d => d.id !== 'playnite_' + localId);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}
