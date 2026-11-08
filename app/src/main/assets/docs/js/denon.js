// ---- Denon / Marantz receivers (1.5.4, direct, no Home Assistant) ------------------------
//
// The Devices page's receiver view (Audio tab): lists the receivers and adds one by its IP
// address. No pairing: the remote asks its web API what answers there (POST /denon-check: its
// model and port, 8080 or 80), then the receiver is saved with the other connection settings
// (`denon_*[]` rows of POST /save-connection, see persistHaAndHubs() in devices-page.js) and gets
// a haDevices catalog entry `denon_<localId>` so every card picker lists it.
//
// Uses devices-page.js globals: denons, skys, samsungs, zidoos, lgTvs, androidTvs, appleTvs,
// haStates, dashboardData, and its helpers (showToast, slugify, atvEsc, deviceCardHtml,
// deviceStateTone, persistHaAndHubs, saveHaDevicesSilently, closeDeviceForms, renderDevicesList).

function renderDenons() {
  const list = document.getElementById('denonsList');
  if (!list) return;
  list.innerHTML = denons.length
    ? denons.map(b => {
        const e = haStates && haStates[b.entityId];
        const a = (e && e.attributes) || {};
        const now = e && e.state === 'on' ? [a.source, a.volume_level != null ? Math.round(a.volume_level * 100) + ' %' : ''].filter(Boolean).join(' · ') : '';
        return deviceCardHtml({
          kind: 'audio',
          name: atvEsc(b.name),
          details: `${now ? atvEsc(now) + ' · ' : ''}${atvEsc(b.host)} · <code>${atvEsc(b.entityId)}</code>`,
          badge: a.model || 'Denon / Marantz',
          state: denonStatus(b), tone: deviceStateTone(b.entityId),
          actions: `<span class="remove" style="color:#00E5FF" title="${atvEsc(I18N.t('web_denon_inputs_title'))}" onclick="editDenonInputs('${atvEsc(b.localId)}')">⇄</span> <span class="remove" style="color:#00E5FF" onclick="renameDenon('${atvEsc(b.localId)}')">✎</span> <span class="remove" onclick="removeDenon('${atvEsc(b.localId)}')">✕</span>`
        });
      }).join('')
    : `<div class="hint">${I18N.t('web_dev_list_no_denon')}</div>`;
}

/** "● on" / "○ off or standby". */
function denonStatus(b) {
  const entity = haStates && haStates[b.entityId];
  if (!entity) return '';
  return I18N.t(entity.state === 'on' ? 'web_dev_adtv_on' : 'web_dev_sky_off');
}

function upsertDenonHaDevice(b) {
  dashboardData.haDevices = dashboardData.haDevices || [];
  const id = 'denon_' + b.localId;
  const existing = dashboardData.haDevices.find(d => d.id === id);
  if (existing) {
    existing.entityId = b.entityId;
    existing.name = b.name;
  } else {
    dashboardData.haDevices.push({ id, domain: 'media_player', entityId: b.entityId, name: b.name });
  }
}

function uniqueDenonEntityId(name) {
  const base = 'media_player.denon_' + slugify(name, 'receiver');
  const taken = id => denons.some(b => b.entityId === id) || zidoos.some(z => z.entityId === id) ||
    lgTvs.some(t => t.entityId === id) || samsungs.some(t => t.entityId === id) || androidTvs.some(t => t.entityId === id) ||
    appleTvs.some(t => t.entityId === id) || (haStates && haStates[id]);
  let id = base;
  let n = 2;
  while (taken(id)) id = base + '_' + (n++);
  return id;
}

/** Asks what answers at the typed IP, then saves the receiver. */
async function addDenon() {
  const host = document.getElementById('denonHost').value.trim();
  if (!/^[A-Za-z0-9.:-]{1,253}$/.test(host)) {
    showToast(I18N.t('web_dev_adtv_enter_ip'), 'error');
    return;
  }
  if (denons.some(b => b.host === host)) {
    showToast(I18N.t('web_dev_already_added'), 'error');
    return;
  }
  const btn = document.getElementById('denonAddBtn');
  btn.disabled = true;
  btn.textContent = I18N.t('web_dev_contacting', host);
  try {
    const res = await fetch('/denon-check', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ host }).toString()
    });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
    const name = document.getElementById('denonName').value.trim() || data.model || 'Denon';
    const entry = {
      localId: slugify(name, 'denon') + '_' + Date.now().toString(36),
      name,
      entityId: uniqueDenonEntityId(name),
      host,
      port: data.port || 8080
    };
    denons = denons.concat(entry);
    upsertDenonHaDevice(entry);
    await persistHaAndHubs();
    await saveHaDevicesSilently();
    closeDeviceForms();
    renderDevicesList();
    showToast(I18N.t('web_dev_denon_added', data.model || 'Denon', entry.entityId));
  } catch (e) {
    showToast(I18N.t('web_dev_denon_failed', e.message), 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = I18N.t('web_dev_sky_add_btn');
  }
}

async function renameDenon(localId) {
  const b = denons.find(x => x.localId === localId);
  if (!b) return;
  const name = prompt(I18N.t('web_dev_zidoo_rename_prompt'), b.name);
  if (name === null || !name.trim()) return;
  b.name = name.trim(); // the entity id is kept, so existing cards keep working
  upsertDenonHaDevice(b);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}

// ---- The inputs' names and visibility, set in Astrion -------------------------------------
// For receivers that don't say the names given on them (AVC-X4800H: its AppCommand interface
// answers empty), and to hide inputs nobody uses. The receiver's own list comes from its media
// player's `astrion_inputs` ([{code, name}]); a blank name keeps the receiver's.

function editDenonInputs(localId) {
  const b = denons.find(x => x.localId === localId);
  const box = document.getElementById('denonInputsEditor');
  if (!b || !box) return;
  const e = haStates && haStates[b.entityId];
  const inputs = (e && e.attributes && Array.isArray(e.attributes.astrion_inputs)) ? e.attributes.astrion_inputs : [];
  const names = b.inputNames || {};
  const hidden = new Set(b.hiddenInputs || []);
  box.dataset.localId = localId;
  box.innerHTML = `
    <h3 class="subsection-title" style="margin-top:0">${atvEsc(I18N.t('web_denon_inputs_title'))} — ${atvEsc(b.name)}</h3>
    <div class="hint">${I18N.t('web_denon_inputs_hint')}</div>
    ${inputs.length ? inputs.map((i, n) => `
      <div style="display:flex;gap:8px;align-items:center;margin:6px 0">
        <label class="inline-check" style="min-width:0;flex:0 0 auto"><input type="checkbox" id="denonIn${n}Show" ${hidden.has(i.code) ? '' : 'checked'}> ${atvEsc(I18N.t('web_denon_inputs_show'))}</label>
        <input type="text" id="denonIn${n}Name" maxlength="40" style="flex:1" placeholder="${atvEsc(i.name)}" value="${atvEsc(names[i.code] || '')}" data-code="${atvEsc(i.code)}">
        <code style="flex:0 0 auto;opacity:.6">${atvEsc(i.code)}</code>
      </div>`).join('') : `<div class="hint">${I18N.t('web_denon_inputs_none')}</div>`}
    <div class="btn-row" style="margin-top:10px">
      ${inputs.length ? `<button type="button" onclick="saveDenonInputs(${inputs.length})">${atvEsc(I18N.t('web_denon_inputs_save'))}</button>` : ''}
      <button type="button" class="secondary" onclick="document.getElementById('denonInputsEditor').style.display='none'">${atvEsc(I18N.t('web_denon_inputs_close'))}</button>
    </div>`;
  box.style.display = '';
  box.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

async function saveDenonInputs(count) {
  const box = document.getElementById('denonInputsEditor');
  const b = box && denons.find(x => x.localId === box.dataset.localId);
  if (!b) return;
  const names = {};
  const hidden = [];
  for (let n = 0; n < count; n++) {
    const field = document.getElementById('denonIn' + n + 'Name');
    if (!field) continue;
    const code = field.dataset.code;
    if (field.value.trim()) names[code] = field.value.trim();
    if (!document.getElementById('denonIn' + n + 'Show').checked) hidden.push(code);
  }
  b.inputNames = names;
  b.hiddenInputs = hidden;
  await persistHaAndHubs();
  box.style.display = 'none';
  showToast(I18N.t('web_denon_inputs_saved'));
}

async function removeDenon(localId) {
  const b = denons.find(x => x.localId === localId);
  if (!b) return;
  if (!confirm(I18N.t('web_dev_confirm_remove_zidoo', b.name, b.entityId))) return;
  denons = denons.filter(x => x.localId !== localId);
  dashboardData.haDevices = (dashboardData.haDevices || []).filter(d => d.id !== 'denon_' + localId);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
}
