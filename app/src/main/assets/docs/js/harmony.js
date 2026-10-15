// ---- Harmony Hub pickers ----------------------------------------------------
//
// Cascading Hub -> Device -> Command (or Hub -> Activity) dropdowns, fed live
// from this device's own paired hub(s) via the endpoints exposed by the app's
// local ConfigServer (/harmony-hubs, /harmony-config). Used by both the
// hotkey form (hotkeys.js, idPrefix "hk") and the scene item form (cards.js,
// idPrefix "gi") — pass a distinct idPrefix per form so their generated
// element ids never collide when both are in the DOM at once.
//
// The builder only ever runs served *from the app itself*
// (http://<device-ip>:8080/builder/), so these fetches normally succeed;
// if a hub can't be reached this still degrades to a plain text input
// rather than blocking the form.

let harmonyHubsList = [];        // [{localId, name}], loaded once
const harmonyConfigCache = {};   // hubLocalId -> {devices, activities}
let harmonyAvailable = false;    // true once loadHarmonyHubs() finds >=1 hub

async function loadHarmonyHubs() {
  try {
    const res = await fetch('/harmony-hubs');
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    harmonyHubsList = Array.isArray(data) ? data : [];
    harmonyAvailable = harmonyHubsList.length > 0;
  } catch (e) {
    harmonyHubsList = [];
    harmonyAvailable = false;
    console.log('Harmony hub list unavailable (expected when this page isn\'t served by the app) — falling back to manual entry', e);
  }
}

async function loadHarmonyConfig(hubLocalId) {
  if (harmonyConfigCache[hubLocalId]) return harmonyConfigCache[hubLocalId];
  const res = await fetch('/harmony-config?hub=' + encodeURIComponent(hubLocalId));
  if (!res.ok) throw new Error('HTTP ' + res.status);
  const data = await res.json();
  harmonyConfigCache[hubLocalId] = data;
  return data;
}

function resetHarmonySelect(sel, placeholder) {
  sel.innerHTML = `<option value="">${placeholder}</option>`;
  sel.disabled = true;
}

/** Renders the Hub (+ Device/Command, Device only, or Activity) selects into
 * `container`. `mode` is 'command' (device + command), 'device' (device only —
 * for cards like apple_tv_remote whose commands are fixed in the UI, not
 * picked in the form), or 'activity'. `idPrefix` scopes the generated element
 * ids (e.g. 'hk' -> #hkHub, 'gi' -> #giHub) so multiple forms can coexist. */
function renderHarmonyHubSelect(container, mode, idPrefix) {
  const hubOptions = harmonyHubsList.map(h => `<option value="${h.localId}">${h.name}</option>`).join('');
  const deviceSelect = `
    <label>${I18N.t('web_harmony_label_device')}</label>
    <select id="${idPrefix}DeviceSelect" disabled${mode === 'command' ? ` onchange="onHarmonyDeviceChange('${idPrefix}')"` : ''}><option value="">${I18N.t('web_harmony_select_hub_first')}</option></select>
  `;
  const commandSelect = `
    <label>${I18N.t('web_harmony_label_command')}</label>
    <select id="${idPrefix}CommandSelect" disabled><option value="">${I18N.t('web_harmony_select_device_first')}</option></select>
  `;
  const activitySelect = `
    <label>${I18N.t('web_harmony_label_activity')}</label>
    <select id="${idPrefix}ActivitySelect" disabled><option value="">${I18N.t('web_harmony_select_hub_first')}</option></select>
  `;
  container.innerHTML = `
    <label>${I18N.t('web_harmony_label_hub')}</label>
    <select id="${idPrefix}Hub" onchange="onHarmonyHubChange('${mode}', '${idPrefix}')">
      <option value="">${I18N.t('web_harmony_select_hub')}</option>
      ${hubOptions}
    </select>
    ${mode === 'command' ? deviceSelect + commandSelect : mode === 'device' ? deviceSelect : activitySelect}
  `;
}

async function onHarmonyHubChange(mode, idPrefix) {
  const hubId = document.getElementById(idPrefix + 'Hub').value;
  if (mode === 'command' || mode === 'device') {
    const deviceSel = document.getElementById(idPrefix + 'DeviceSelect');
    const cmdSel = mode === 'command' ? document.getElementById(idPrefix + 'CommandSelect') : null;
    if (cmdSel) resetHarmonySelect(cmdSel, I18N.t('web_harmony_select_device_first'));
    if (!hubId) { resetHarmonySelect(deviceSel, I18N.t('web_harmony_select_hub_first')); return; }
    deviceSel.innerHTML = `<option value="">${I18N.t('media_loading')}</option>`;
    try {
      const data = await loadHarmonyConfig(hubId);
      deviceSel.innerHTML = `<option value="">${I18N.t('web_harmony_select_device')}</option>` +
        (data.devices || []).map(d => `<option value="${d.id}">${d.label}</option>`).join('');
      deviceSel.disabled = false;
    } catch (e) {
      deviceSel.innerHTML = `<option value="">${I18N.t('web_harmony_load_failed')}</option>`;
      console.error('Failed to load Harmony config for hub ' + hubId, e);
    }
  } else {
    const actSel = document.getElementById(idPrefix + 'ActivitySelect');
    if (!hubId) { resetHarmonySelect(actSel, I18N.t('web_harmony_select_hub_first')); return; }
    actSel.innerHTML = `<option value="">${I18N.t('media_loading')}</option>`;
    try {
      const data = await loadHarmonyConfig(hubId);
      actSel.innerHTML = `<option value="">${I18N.t('web_harmony_select_activity')}</option>` +
        (data.activities || []).map(a => `<option value="${a.id}">${a.label}</option>`).join('');
      actSel.disabled = false;
    } catch (e) {
      actSel.innerHTML = `<option value="">${I18N.t('web_harmony_load_failed')}</option>`;
      console.error('Failed to load Harmony activities for hub ' + hubId, e);
    }
  }
}

function onHarmonyDeviceChange(idPrefix) {
  const hubId = document.getElementById(idPrefix + 'Hub').value;
  const deviceId = document.getElementById(idPrefix + 'DeviceSelect').value;
  const cmdSel = document.getElementById(idPrefix + 'CommandSelect');
  if (!deviceId) { resetHarmonySelect(cmdSel, I18N.t('web_harmony_select_device_first')); return; }
  const data = harmonyConfigCache[hubId];
  const device = (data && data.devices || []).find(d => d.id === deviceId);
  const commands = (device && device.commands) || [];
  cmdSel.innerHTML = `<option value="">${I18N.t('web_harmony_select_command')}</option>` +
    commands.map(c => {
      const alias = dashboardData.harmonyAliases?.[hubId]?.[deviceId]?.[c.name];
      return `<option value="${c.name}">${alias || c.label}</option>`;
    }).join('');
  cmdSel.disabled = false;
}

// Exposed as a promise (not just fire-and-forget) so other modules loaded
// after this one — activities.js in particular, which renders a Harmony
// device picker immediately at script-load time, before any user
// interaction — can await it instead of racing it.
const harmonyHubsReady = loadHarmonyHubs();