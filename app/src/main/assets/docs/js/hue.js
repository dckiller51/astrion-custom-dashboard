// ---- Philips Hue bridge (1.6.1, direct, no Home Assistant) -------------------
//
// The Devices page's Lighting tab: lists the Hue bridges and pairs one. POST
// /hue-discover asks Philips' discovery service which bridges are on this
// network; POST /hue-pair waits up to 30 s for the button on top of the bridge,
// after which the remote keeps the bridge's key (it never comes to this page).
// The bridge is then saved with the other connection settings (`hue_*[]` rows of
// POST /save-connection, see persistHaAndHubs() in devices-page.js). Its lights,
// rooms, zones and scenes become light.hue_… / scene.hue_… entities the builder
// lists with the others.
//
// Uses devices-page.js globals: hues, dashboardData, and its helpers (showToast,
// atvEsc, persistHaAndHubs, saveHaDevicesSilently, closeDeviceForms,
// renderDevicesList, deviceCardHtml). The bridge's entities join the builder's
// catalog (haDevices, marked `hue`) when the remote serves dashboard.json.

function renderHues() {
  const list = document.getElementById('huesList');
  if (!list) return;
  list.innerHTML = hues.length
    ? hues.map(b => deviceCardHtml({
        kind: 'light',
        name: atvEsc(b.name),
        details: `${atvEsc(b.host)}${b.paired !== false && (b.lights || b.groups || b.scenes)
          ? ' · ' + I18N.t('web_dev_hue_counts', b.lights || 0, b.groups || 0, b.scenes || 0) : ''}`,
        badge: I18N.t('web_dev_hue_badge'),
        state: hueStatus(b),
        tone: b.paired === false ? 'warn' : (b.reachable ? 'ok' : 'bad'),
        actions: `<span class="remove" style="color:#00E5FF" onclick="renameHue('${atvEsc(b.localId)}')">✎</span> <span class="remove" onclick="removeHue('${atvEsc(b.localId)}')">✕</span>`
      })).join('')
    : `<div class="hint">${I18N.t('web_dev_list_no_hue')}</div>`;
}

/** "● connected" / "○ not answering", or "not paired" when the bridge never gave its key. */
function hueStatus(b) {
  if (b.paired === false) return I18N.t('web_dev_hue_not_paired');
  return b.reachable ? I18N.t('web_dev_hue_online') : I18N.t('web_dev_hue_offline');
}

function openHueForm() {
  document.getElementById('hueForm').style.display = '';
  document.getElementById('hueHost').value = '';
  document.getElementById('hueName').value = '';
  document.getElementById('hueFound').innerHTML = '';
  document.getElementById('hueForm').scrollIntoView({ behavior: 'smooth', block: 'center' });
  document.getElementById('hueHost').focus();
}

/** Philips' discovery service, through the remote: each bridge found becomes a button that fills the form. */
async function findHueBridges() {
  const btn = document.getElementById('hueFindBtn');
  const box = document.getElementById('hueFound');
  btn.disabled = true;
  btn.textContent = I18N.t('web_dev_hue_finding');
  box.innerHTML = '';
  try {
    const res = await fetch('/hue-discover', { method: 'POST' });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
    const bridges = (data.bridges || []).filter(b => b.host);
    if (!bridges.length) {
      box.innerHTML = `<div class="hint">${I18N.t('web_dev_hue_none_found')}</div>`;
      return;
    }
    box.innerHTML = bridges.map(b => `<button type="button" class="secondary" style="margin:0 8px 8px 0"
        onclick="pickHueBridge('${atvEsc(b.host)}', '${atvEsc(b.name || '')}')">${atvEsc(b.name || 'Hue')} · ${atvEsc(b.host)}</button>`).join('');
    if (bridges.length === 1) pickHueBridge(bridges[0].host, bridges[0].name || '');
  } catch (e) {
    box.innerHTML = `<div class="hint">${I18N.t('web_dev_hue_none_found')}</div>`;
    console.warn('Hue discovery', e);
  } finally {
    btn.disabled = false;
    btn.textContent = I18N.t('web_dev_hue_find_btn');
  }
}

function pickHueBridge(host, name) {
  document.getElementById('hueHost').value = host;
  if (!document.getElementById('hueName').value.trim() && name) document.getElementById('hueName').value = name;
}

/** Waits for the bridge's button (up to 30 s), then saves the bridge. */
async function addHue() {
  const host = document.getElementById('hueHost').value.trim();
  if (!/^[A-Za-z0-9.:-]{1,253}$/.test(host)) {
    showToast(I18N.t('web_dev_hue_enter_ip'), 'error');
    return;
  }
  const again = hues.find(b => b.host === host);
  const btn = document.getElementById('hueAddBtn');
  btn.disabled = true;
  btn.textContent = I18N.t('web_dev_hue_press_button');
  try {
    const remote = (document.getElementById('remoteNameLabel') || {}).textContent || '';
    const res = await fetch('/hue-pair', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ host, remote }).toString()
    });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
    const name = document.getElementById('hueName').value.trim() || (again && again.name) || data.name || 'Hue';
    // The bridge id names it: paired again (or at a new address), it keeps its entities.
    const entry = { localId: data.bridgeId, name, host, paired: true, reachable: true, lights: data.lights, groups: data.groups, scenes: data.scenes };
    hues = hues.filter(b => b.localId !== entry.localId && b.host !== host).concat(entry);
    await persistHaAndHubs();
    closeDeviceForms();
    renderDevicesList();
    showToast(I18N.t('web_dev_hue_added', I18N.t('web_dev_hue_counts', data.lights || 0, data.groups || 0, data.scenes || 0)));
  } catch (e) {
    showToast(I18N.t('web_dev_hue_failed', e.message), 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = I18N.t('web_dev_hue_pair_btn');
  }
}

async function renameHue(localId) {
  const b = hues.find(x => x.localId === localId);
  if (!b) return;
  const name = prompt(I18N.t('web_dev_hue_rename_prompt'), b.name);
  if (name === null || !name.trim()) return;
  b.name = name.trim(); // only the bridge's name here: its lights keep their entities
  await persistHaAndHubs();
  renderDevicesList();
}

async function removeHue(localId) {
  const b = hues.find(x => x.localId === localId);
  if (!b) return;
  if (!confirm(I18N.t('web_dev_hue_confirm_remove', b.name))) return;
  hues = hues.filter(x => x.localId !== localId);
  // Its lights, rooms and scenes leave the builder's catalog too.
  dashboardData.haDevices = (dashboardData.haDevices || []).filter(d => d.hue !== localId);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
  renderDevicesList();
}
