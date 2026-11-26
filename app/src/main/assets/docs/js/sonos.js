// ---- Sonos speakers (1.6.3, direct, no Home Assistant) -----------------------------------
//
// The Devices page's Sonos view: lists the speakers and adds them. POST /sonos-discover finds
// the household (by SSDP, or through the speaker at the typed IP address), the speakers ticked
// are saved with the other connection settings (`sonos_*[]` rows of POST /save-connection, see
// persistHaAndHubs() in devices-page.js), and each gets a haDevices catalog entry
// `sonos_<uuid>` so every card picker lists it.
//
// Uses devices-page.js globals and helpers, as playnite.js does.

let sonosFound = []; // last /sonos-discover result

function renderSonos() {
  const list = document.getElementById('sonosList');
  if (!list) return;
  list.innerHTML = sonos.length
    ? sonos.map(s => {
        const e = haStates && haStates[s.entityId];
        const a = (e && e.attributes) || {};
        const inputs = [s.tv ? 'TV' : '', s.lineIn ? I18N.t('web_dev_sonos_line_in') : ''].filter(Boolean).join(', ');
        return deviceCardHtml({
          kind: 'audio',
          name: atvEsc(s.name),
          details: `${a.media_title ? atvEsc(a.media_title) + ' · ' : ''}${s.model ? atvEsc(s.model) + ' · ' : ''}${atvEsc(s.host)} · <code>${atvEsc(s.entityId)}</code>`
            + (inputs ? `<br><span class="hint">${atvEsc(inputs)}</span>` : ''),
          badge: 'Sonos',
          state: sonosStatus(s), tone: deviceStateTone(s.entityId),
          actions: `<span class="remove" style="color:#00E5FF" onclick="renameSonos('${atvEsc(s.uuid)}')">✎</span> <span class="remove" onclick="removeSonos('${atvEsc(s.uuid)}')">✕</span>`
        });
      }).join('')
    : `<div class="hint">${I18N.t('web_dev_list_no_sonos')}</div>`;
}

/** "▶ playing" / "● on" / "○ unavailable". */
function sonosStatus(s) {
  const entity = haStates && haStates[s.entityId];
  if (!entity) return '';
  if (entity.state === 'playing') return I18N.t('web_dev_adtv_playing');
  if (entity.state === 'unavailable') return I18N.t('web_dev_sonos_asleep');
  return I18N.t('web_dev_adtv_on');
}

function openSonosForm() {
  document.getElementById('sonosForm').style.display = '';
  document.getElementById('sonosHost').value = '';
  document.getElementById('sonosFound').innerHTML = '';
  document.getElementById('sonosAddRow').style.display = 'none';
  sonosFound = [];
  document.getElementById('sonosForm').scrollIntoView({ behavior: 'smooth', block: 'center' });
  findSonos();
}

function upsertSonosHaDevice(s) {
  dashboardData.haDevices = dashboardData.haDevices || [];
  const id = 'sonos_' + s.uuid;
  const existing = dashboardData.haDevices.find(d => d.id === id);
  if (existing) {
    existing.entityId = s.entityId;
    existing.name = s.name;
  } else {
    dashboardData.haDevices.push({ id, domain: 'media_player', entityId: s.entityId, name: s.name });
  }
}

function uniqueSonosEntityId(name, extra) {
  const base = 'media_player.sonos_' + slugify(name, 'speaker');
  const taken = id => sonos.some(s => s.entityId === id) || extra.includes(id) || (haStates && haStates[id]);
  let id = base;
  let n = 2;
  while (taken(id)) id = base + '_' + (n++);
  return id;
}

/** Asks the network (or the speaker at the typed IP) for the household's speakers. */
async function findSonos() {
  const host = document.getElementById('sonosHost').value.trim();
  if (host && !/^[A-Za-z0-9.:-]{1,253}$/.test(host)) {
    showToast(I18N.t('web_dev_adtv_enter_ip'), 'error');
    return;
  }
  const btn = document.getElementById('sonosFindBtn');
  const out = document.getElementById('sonosFound');
  btn.disabled = true;
  btn.textContent = I18N.t('web_dev_sonos_searching');
  out.innerHTML = '';
  document.getElementById('sonosAddRow').style.display = 'none';
  try {
    const res = await fetch('/sonos-discover', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ host }).toString()
    });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
    sonosFound = data.speakers || [];
    out.innerHTML = sonosFound.map((s, i) => {
      const added = sonos.some(x => x.uuid === s.uuid);
      return `<label style="display:flex;gap:8px;align-items:center;margin:6px 0">`
        + `<input type="checkbox" id="sonosPick${i}" ${added ? 'disabled' : 'checked'}>`
        + `<span><b>${atvEsc(s.name)}</b> · ${atvEsc(s.model || 'Sonos')} · ${atvEsc(s.host)}`
        + `${added ? ' — ' + I18N.t('web_dev_already_added') : ''}</span></label>`;
    }).join('');
    const any = sonosFound.some(s => !sonos.some(x => x.uuid === s.uuid));
    document.getElementById('sonosAddRow').style.display = any ? '' : 'none';
  } catch (e) {
    out.innerHTML = `<div class="hint">${atvEsc(I18N.t('web_dev_sonos_failed', e.message))}</div>`;
  } finally {
    btn.disabled = false;
    btn.textContent = I18N.t('web_dev_sonos_find_btn');
  }
}

/** Saves the speakers ticked. */
async function addSonos() {
  const picked = sonosFound.filter((s, i) => {
    const box = document.getElementById('sonosPick' + i);
    return box && box.checked && !box.disabled;
  });
  if (!picked.length) return;
  const ids = [];
  const entries = picked.map(s => {
    const entityId = uniqueSonosEntityId(s.name, ids);
    ids.push(entityId);
    return { uuid: s.uuid, name: s.name, entityId, host: s.host, model: s.model || '', lineIn: !!s.lineIn, tv: !!s.tv };
  });
  sonos = sonos.concat(entries);
  entries.forEach(upsertSonosHaDevice);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
  closeDeviceForms();
  renderDevicesList();
  showToast(I18N.t('web_dev_sonos_added', entries.length));
}

async function renameSonos(uuid) {
  const s = sonos.find(x => x.uuid === uuid);
  if (!s) return;
  const name = prompt(I18N.t('web_dev_zidoo_rename_prompt'), s.name);
  if (name === null || !name.trim()) return;
  s.name = name.trim(); // the entity id is kept, so existing cards keep working
  upsertSonosHaDevice(s);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}

async function removeSonos(uuid) {
  const s = sonos.find(x => x.uuid === uuid);
  if (!s) return;
  if (!confirm(I18N.t('web_dev_confirm_remove_zidoo', s.name, s.entityId))) return;
  sonos = sonos.filter(x => x.uuid !== uuid);
  dashboardData.haDevices = (dashboardData.haDevices || []).filter(d => d.id !== 'sonos_' + uuid);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}
