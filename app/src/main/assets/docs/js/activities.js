// ---- Activities (composed) — step-by-step wizard --------------------------
//
// Builds entries for dashboardData.activities: a composed AV Activity that
// orchestrates more than one device — the multi-device case a Harmony hub
// handles internally, reimplemented here so it also works for IR-only and
// mixed-source setups (see ActivityConfig's doc comment on the Kotlin side).
// A device is sourced from EITHER a local IrDeviceConfig, a Harmony
// hub+device, or a bare Home Assistant entity id — mixable freely within one
// Activity.
//
// The wizard mirrors Logitech Harmony's own Activity setup flow (type ->
// name/icon -> pick devices -> which one controls volume -> pick an input
// per device), one screen at a time, rather than one long form.
//
// This is only for *composed* Activities. A single-device/single-action
// Activity doesn't need this at all — just "track": true + "room" on a
// scene_grid item, same as an existing Harmony Activity picked via its
// activityId. See cards.js's scene_grid form for both paths.

const ACTIVITY_TYPES = [
  { id: 'watch_tv', labelKey: 'web_activity_type_watch_tv' },
  { id: 'watch_movie', labelKey: 'web_activity_type_watch_movie' },
  { id: 'listen_music', labelKey: 'web_activity_type_listen_music' },
  { id: 'smart_tv', labelKey: 'web_activity_type_smart_tv' },
  { id: 'netflix', labelKey: 'web_activity_type_netflix' },
  { id: 'custom', labelKey: 'web_activity_type_custom' },
];

let wizard = null; // null when the wizard modal is closed; see startActivityWizard()

function irDevicesById() {
  return Object.fromEntries((dashboardData.irDevices || []).map(d => [d.id, d]));
}

/**
 * [value, label] entries for an IR device's command fields. Only *actually*
 * known for inline devices (commands map right there in dashboardData). For
 * an ir-database reference device, falls back to whatever "known command
 * ids" were typed in when it was created on the Devices page (see
 * dev.commandHints, set by devices-page.js's saveIrDevice()) — no friendly
 * label available for those, just the id twice. Empty either way if
 * there's nothing to suggest. Only feeds a real `<select>` for a device
 * whose list is actually complete (inline IR, or Harmony) — see
 * hasCompleteCommandList(); a reference device's hints are always partial,
 * so those fields stay free-text with datalist suggestions instead.
 */
function irDeviceCommandEntries(dev) {
  if (!dev) return [];
  if (dev.commands) return Object.entries(dev.commands).map(([id, c]) => [id, `${id} — ${c.label || id}`]);
  const hints = dev.commandHints || [];
  return hints.map(id => [id, id]);
}

// ---- ir-database model lookup (profile-aware Activities, 1.2.2+) ----------
//
// A *reference* IR device's real command list — and, for a device imported
// from the Logitech Harmony archive through the sniffer, its "profile"
// (power type, power-on delay, repeats, named input sequences) — lives in
// /sdcard/astrion/ir-database/<category>.json on the remote, which the
// remote's own server exposes at GET /ir-database/<category>.json (same
// endpoint ir.js already uses). Only reachable when this builder is served
// by the remote itself; resolves to null anywhere else, and every caller
// falls back to the old typed-in hints.

const irDatabaseCategoryCache = {}; // category -> Promise<parsed file | null>

function loadIrDatabaseModel(dev) {
  if (!dev || dev.commands || !dev.category || !dev.brand || !dev.model) return Promise.resolve(null);
  if (!irDatabaseCategoryCache[dev.category]) {
    irDatabaseCategoryCache[dev.category] = fetch(`/ir-database/${encodeURIComponent(dev.category)}.json`)
      .then(r => (r.ok ? r.json() : null))
      .catch(() => null);
  }
  return irDatabaseCategoryCache[dev.category].then(file => {
    const brand = (file?.brands || []).find(b => b.brand_name.toLowerCase() === dev.brand.toLowerCase());
    return (brand?.models || []).find(m => m.model_name.toLowerCase() === dev.model.toLowerCase()) || null;
  });
}

/** [value, label] entries from an ir-database model entry; for the input
 * field, the profile's named input sequences come first — picking one
 * ("HDMI 1") makes the Activity run that whole sequence. */
function modelCommandEntries(model, forInput) {
  const commands = Object.entries(model.commands || {}).map(([id, c]) => [id, `${id} — ${c.label || id}`]);
  if (!forInput) return commands;
  const inputs = (model.profile?.inputs || []).map(i => [i.name, I18N.t('web_activity_input_sequence', i.name, i.steps.length)]);
  return inputs.concat(commands);
}

function profileSummaryHtml(profile) {
  if (!profile) return '';
  const t = profile.timing || {};
  const powerKey = { discrete: 'web_profile_power_discrete', toggle: 'web_profile_power_toggle', none: 'web_profile_power_none' }[profile.power?.type];
  const power = powerKey ? I18N.t(powerKey) : I18N.t('web_unknown');
  const toggleNote = profile.power?.type === 'toggle' ? ' ' + I18N.t('web_profile_toggle_note') : '';
  const inputNote = (profile.inputs || []).length ? ' ' + I18N.t('web_profile_input_note') : '';
  return `<div class="hint" style="border-left:3px solid #00E5FF;padding-left:8px">
    ${I18N.t('web_profile_summary', power, t.power_on_delay_ms || 0, t.repeats || 1)}${toggleNote}${inputNote}
  </div>`;
}

// ---- Home Assistant / network Apple TV entities for the wizard ----------
//
// A network Apple TV paired in Astrion is exposed as a media_player entity
// (media_player.appletv_…) that the app itself answers: turn_on = wake,
// turn_off = sleep, select_source = launch an app by name. So it joins an
// Activity exactly like a Home Assistant entity (source "ha"); it just gets
// its own section so it's easy to find.

const HA_ACTIVITY_DOMAINS = ['media_player', 'switch', 'light', 'remote', 'input_boolean', 'fan', 'climate'];

function isNetworkAppleTv(entityId) {
  return /^media_player\.appletv_/.test(entityId || '');
}

/** [{entityId, name, domain}] — the Devices catalog first, then every live
 * HA entity of a domain an Activity can switch on/off (when /ha-states is
 * reachable, i.e. the builder is opened from the remote). */
function haEntityChoices() {
  const out = new Map();
  (dashboardData.haDevices || []).forEach(d => {
    if (d.entityId) out.set(d.entityId, { entityId: d.entityId, name: d.name || d.entityId, domain: d.entityId.split('.')[0] });
  });
  Object.entries((typeof haStates !== 'undefined' && haStates) || {}).forEach(([entityId, e]) => {
    const domain = entityId.split('.')[0];
    if (!out.has(entityId) && HA_ACTIVITY_DOMAINS.includes(domain)) {
      out.set(entityId, { entityId, name: (e && e.friendly_name) || entityId, domain });
    }
  });
  return [...out.values()].sort((a, b) => a.domain.localeCompare(b.domain) || a.name.localeCompare(b.name));
}

function haEntityName(entityId) {
  return haEntityChoices().find(c => c.entityId === entityId)?.name || entityId;
}

/** Sources/apps the entity reports (HA's source_list attribute — for a
 * network Apple TV, its installed apps), as [value, label] suggestions. */
function haSourceEntries(entityId) {
  const e = (typeof haStates !== 'undefined' && haStates) ? haStates[entityId] : null;
  const list = (e && e.attributes && Array.isArray(e.attributes.source_list)) ? e.attributes.source_list : [];
  return list.map(src => [src, src]);
}

function deviceRefLabel(ref) {
  if (ref.source === 'ir') return `${irDevicesById()[ref.deviceId]?.name || ref.deviceId} (IR)`;
  if (ref.source === 'harmony') return `${ref.deviceLabel || ref.deviceId} (Harmony)`;
  if (isNetworkAppleTv(ref.deviceId)) return I18N.t('web_activity_ref_atv', haEntityName(ref.deviceId));
  return `${haEntityName(ref.deviceId)} (HA — ${ref.deviceId})`;
}


const WIZARD_STEP_LABELS = {
  type: 'web_activity_step_type',
  info: 'web_activity_step_info',
  devices: 'web_activity_step_devices',
  configure: 'web_activity_step_configure',
  volume: 'web_activity_step_volume',
  volumeCommands: 'web_activity_step_volume_commands',
  review: 'web_activity_step_review',
};

// ---- opening / closing / navigating the wizard -----------------------------

function startActivityWizard(editId) {
  const existing = editId ? (dashboardData.activities || []).find(a => a.id === editId) : null;
  wizard = {
    editingId: existing ? existing.id : null,
    phase: existing ? 'info' : 'type', // editing an existing Activity skips the "type" picker
    type: null,
    name: existing?.name || '',
    room: existing?.room || '',
    icon: existing?.icon || '',
    // deviceRefs: [{source, deviceId, hub?, deviceLabel?}] — selection only, no commands yet
    deviceRefs: existing ? existing.devices.map(d => ({ source: d.source, deviceId: d.deviceId, hub: d.hub })) : [],
    // deviceConfig[i] matches deviceRefs[i]: {powerOnCommand, powerOffCommand, inputCommand, powerOnFirst, powerOffOnExit, delayAfterMs}
    deviceConfig: existing ? existing.devices.map(d => ({
      powerOnCommand: d.powerOnCommand || null,
      powerOffCommand: d.powerOffCommand || null,
      inputCommand: d.inputCommand || null,
      powerOnFirst: d.powerOnFirst !== false,
      powerOffOnExit: d.powerOffOnExit !== false,
      delayAfterMs: d.delayAfterMs || 0,
    })) : [],
    configureIndex: 0,
    // Device ids kept on between Activities (dashboardData.keepOnDevices) — a setting of the device,
    // shared by every Activity, edited here and written back on save.
    keepOn: new Set(dashboardData.keepOnDevices || []),
    volumeDeviceId: existing?.volumeDeviceId || null,
    volumeUpCommand: existing?.volumeUpCommand || null,
    volumeDownCommand: existing?.volumeDownCommand || null,
    muteCommand: existing?.muteCommand || null,
    nowPlayingEntity: existing?.nowPlayingEntity || null,
  };
  document.getElementById('activityWizardModal').classList.add('open');
  renderWizard();
}

function closeActivityWizard() {
  wizard = null;
  document.getElementById('activityWizardModal').classList.remove('open');
}

function volumeDeviceRef() {
  return wizard.deviceRefs.find(r => r.deviceId === wizard.volumeDeviceId) || null;
}

// Volume commands only make sense for ir/harmony (named commands to pick
// from); an "ha" volume device always uses the fixed media_player.volume_up/
// volume_down/volume_mute services (see writeVolumeHotkeysForActivity), so that
// phase is skipped entirely for it.
function needsVolumeCommandsPhase() {
  const ref = volumeDeviceRef();
  return !!ref && ref.source !== 'ha';
}

function wizardNext() {
  const phase = wizard.phase;
  if (phase === 'info') {
    wizard.name = document.getElementById('wizName').value.trim();
    wizard.room = document.getElementById('wizRoom').value.trim();
    wizard.icon = document.getElementById('wizIcon').value.trim();
    if (!wizard.name) { alert(I18N.t('web_activity_alert_need_name')); return; }
    if (!wizard.room) { alert(I18N.t('web_activity_alert_need_room')); return; }
    wizard.phase = 'devices';
  } else if (phase === 'devices') {
    if (!wizard.deviceRefs.length) { alert(I18N.t('web_activity_alert_need_device')); return; }
    wizard.configureIndex = 0;
    wizard.phase = 'configure';
  } else if (phase === 'configure') {
    saveCurrentDeviceConfig();
    if (wizard.configureIndex < wizard.deviceRefs.length - 1) {
      wizard.configureIndex++;
    } else {
      wizard.phase = 'volume';
    }
  } else if (phase === 'volume') {
    wizard.volumeDeviceId = document.getElementById('wizVolumeDevice').value || null;
    wizard.phase = needsVolumeCommandsPhase() ? 'volumeCommands' : 'review';
  } else if (phase === 'volumeCommands') {
    wizard.volumeUpCommand = document.getElementById('wizVolUp')?.value || null;
    wizard.volumeDownCommand = document.getElementById('wizVolDown')?.value || null;
    wizard.muteCommand = document.getElementById('wizMute')?.value || null;
    wizard.phase = 'review';
  }
  renderWizard();
}

function wizardBack() {
  const phase = wizard.phase;
  if (phase === 'configure' && wizard.configureIndex > 0) {
    saveCurrentDeviceConfig();
    wizard.configureIndex--;
  } else if (phase === 'configure') {
    wizard.phase = 'devices';
  } else if (phase === 'info' && wizard.editingId) {
    // Editing an existing Activity skipped 'type' — Back from 'info' just closes.
    closeActivityWizard();
    return;
  } else if (phase === 'info') {
    wizard.phase = 'type';
  } else if (phase === 'devices') {
    wizard.phase = 'info';
  } else if (phase === 'volume') {
    wizard.configureIndex = wizard.deviceRefs.length - 1;
    wizard.phase = 'configure';
  } else if (phase === 'volumeCommands') {
    wizard.phase = 'volume';
  } else if (phase === 'review') {
    wizard.phase = needsVolumeCommandsPhase() ? 'volumeCommands' : 'volume';
  }
  renderWizard();
}

function saveCurrentDeviceConfig() {
  const source = wizard.deviceRefs[wizard.configureIndex].source;
  wizard.deviceConfig[wizard.configureIndex] = {
    powerOnCommand: source !== 'ha' ? (document.getElementById('wizPowerOn')?.value || null) : null,
    powerOffCommand: source !== 'ha' ? (document.getElementById('wizPowerOff')?.value || null) : null,
    inputCommand: source === 'ha'
      ? (document.getElementById('wizInputText')?.value.trim() || null)
      : (document.getElementById('wizInput')?.value || null),
    powerOnFirst: document.getElementById('wizPowerOnFirst')?.checked !== false,
    powerOffOnExit: document.getElementById('wizPowerOffOnExit')?.checked !== false,
    delayAfterMs: parseInt(document.getElementById('wizDelay')?.value, 10) || 0,
  };
  const keepOnBox = document.getElementById('wizKeepOn');
  if (keepOnBox) {
    const id = wizard.deviceRefs[wizard.configureIndex].deviceId;
    if (keepOnBox.checked) wizard.keepOn.add(id); else wizard.keepOn.delete(id);
  }
}

// ---- rendering each phase --------------------------------------------------

function renderWizard() {
  if (!wizard) return;
  const phase = wizard.phase;
  document.getElementById('wizStepLabel').textContent = WIZARD_STEP_LABELS[phase] ? I18N.t(WIZARD_STEP_LABELS[phase]) : phase;
  document.getElementById('activityWizardTitle').textContent = wizard.editingId ? I18N.t('web_activity_edit_title', wizard.name) : I18N.t('web_modal_new_activity');

  const body = document.getElementById('wizBody');
  const renderers = {
    type: renderWizardType,
    info: renderWizardInfo,
    devices: renderWizardDevices,
    configure: renderWizardConfigure,
    volume: renderWizardVolume,
    volumeCommands: renderWizardVolumeCommands,
    review: renderWizardReview,
  };
  body.innerHTML = renderers[phase]();
  wireWizardPhase(phase);

  document.getElementById('wizBackBtn').style.display = (phase === 'type') ? 'none' : '';
  document.getElementById('wizNextBtn').style.display = (phase === 'review' || phase === 'type') ? 'none' : '';
  document.getElementById('wizSaveBtn').style.display = (phase === 'review') ? '' : 'none';
}

function renderWizardType() {
  return `
    <div class="hint">${I18N.t('web_activity_type_hint')}</div>
    <div class="btn-row" style="flex-wrap:wrap;margin-top:10px">
      ${ACTIVITY_TYPES.map(t => `<button type="button" class="secondary" onclick="pickActivityType('${t.id}')">${I18N.t(t.labelKey)}</button>`).join('')}
    </div>
  `;
}

function renderWizardInfo() {
  return `
    <label>${I18N.t('web_activity_label_name')}</label>
    <input type="text" id="wizName" value="${wizard.name}" placeholder="${I18N.t('web_activity_name_placeholder')}">
    <label>${I18N.t('web_activity_label_room')}</label>
    <input type="text" id="wizRoom" value="${wizard.room}" placeholder="${I18N.t('web_activity_room_placeholder')}">
    <div id="wizIconField"></div>
    <div class="hint">${I18N.t('web_activity_info_page_hint')}</div>
  `;
}

function renderWizardDevices() {
  const irDevices = dashboardData.irDevices || [];
  const selectedIrIds = new Set(wizard.deviceRefs.filter(r => r.source === 'ir').map(r => r.deviceId));
  const selectedHaIds = new Set(wizard.deviceRefs.filter(r => r.source === 'ha').map(r => r.deviceId));
  const allHa = haEntityChoices();
  const appleTvs = allHa.filter(c => isNetworkAppleTv(c.entityId));
  const haChoices = allHa.filter(c => !isNetworkAppleTv(c.entityId));
  return `
    <div class="hint">${I18N.t('web_activity_devices_hint')}</div>

    <h3>${I18N.t('web_activity_ir_devices_heading')}</h3>
    ${irDevices.length === 0 ? `<div class="hint">${I18N.t('web_activity_no_ir')}</div>` :
      irDevices.map(d => `
        <label class="inline-check">
          <input type="checkbox" ${selectedIrIds.has(d.id) ? 'checked' : ''} onchange="toggleIrDeviceRef('${d.id}', this.checked)">
          ${d.name}
        </label>
      `).join('')}

    <h3 style="margin-top:14px">${I18N.t('web_activity_harmony_heading')}</h3>
    <div id="wizHarmonyAddFields"></div>

    <h3 style="margin-top:14px">${I18N.t('web_activity_atv_heading')}</h3>
    ${appleTvs.length === 0 ? `<div class="hint">${I18N.t('web_activity_no_atv')}</div>` :
      appleTvs.map(c => `
        <label class="inline-check">
          <input type="checkbox" data-ha-ref="${c.entityId}" ${selectedHaIds.has(c.entityId) ? 'checked' : ''} onchange="toggleHaDeviceRef('${c.entityId}', this.checked)">
          ${c.name}
        </label>
      `).join('') + `<div class="hint">${I18N.t('web_activity_atv_hint')}</div>`}

    <h3 style="margin-top:14px">${I18N.t('web_activity_ha_heading')}</h3>
    <select id="wizHaEntitySelect">
      <option value="">${I18N.t('web_pick_entity')}</option>
      ${[...new Set(haChoices.map(c => c.domain))].map(domain => `
        <optgroup label="${domain}">
          ${haChoices.filter(c => c.domain === domain).map(c => `<option value="${c.entityId}">${c.name} — ${c.entityId}</option>`).join('')}
        </optgroup>`).join('')}
    </select>
    <input type="text" id="wizHaEntityId" placeholder="${I18N.t('web_activity_ha_entity_ph')}" style="margin-top:6px">
    ${haChoices.length === 0 ? `<div class="hint">${I18N.t('web_activity_no_ha_list')}</div>` : ''}
    <div class="btn-row" style="margin-top:6px">
      <button type="button" class="secondary" onclick="addHaDeviceRef()">${I18N.t('web_activity_add_ha_entity')}</button>
    </div>

    <h3 style="margin-top:14px">${I18N.t('web_activity_selected_devices')}</h3>
    <div id="wizDeviceRefsList"></div>
  `;
}

/** True when we actually know the *complete* command set for this device —
 * Harmony (live from the hub) or an inline IR device (commands stored right
 * there in dashboard.json) — vs. only ever having partial hints (an
 * ir-database *reference* device, whose real command list lives on the
 * phone's sdcard, unknown to this builder). Complete -> a strict <select>,
 * like everywhere else in this builder. Partial -> keep it a free-text
 * input with <datalist> suggestions, so an id this builder doesn't know
 * about doesn't leave the field with nothing pickable. */
function hasCompleteCommandList(ref) {
  if (ref.source === 'harmony') return true;
  if (ref.source === 'ir') return !!(irDevicesById()[ref.deviceId]?.commands);
  return false;
}

function commandFieldHtml(fieldId, label, ref) {
  if (hasCompleteCommandList(ref)) {
    return `<label>${label}</label><select id="${fieldId}"><option value="">${I18N.t('web_none')}</option></select>`;
  }
  return `<label>${label}</label><input type="text" id="${fieldId}" list="${fieldId}Hints"><datalist id="${fieldId}Hints"></datalist>`;
}

/** Fills fieldId with `entries` ([id, label] pairs) and sets `currentValue`
 * — as real <option>s for a complete list (see hasCompleteCommandList), or
 * as <datalist> suggestions alongside a free-text value otherwise. */
function fillCommandField(fieldId, entries, currentValue, ref) {
  if (hasCompleteCommandList(ref)) {
    const sel = document.getElementById(fieldId);
    if (!sel) return;
    sel.innerHTML = `<option value="">${I18N.t('web_none')}</option>` +
      entries.map(([id, label]) => `<option value="${id}">${label}</option>`).join('');
    sel.value = currentValue || '';
  } else {
    fillWizCommandOptions(fieldId, entries);
    const el = document.getElementById(fieldId);
    if (el) el.value = currentValue || '';
  }
}

function renderWizardConfigure() {
  const ref = wizard.deviceRefs[wizard.configureIndex];
  const cfg = wizard.deviceConfig[wizard.configureIndex] || {};
  const n = wizard.configureIndex + 1;
  const total = wizard.deviceRefs.length;
  return `
    <div class="hint">${I18N.t('web_activity_device_n_of_m', n, total)} <strong>${deviceRefLabel(ref)}</strong></div>

    ${ref.source === 'ha' ? `
      <label>${isNetworkAppleTv(ref.deviceId) ? 'App to launch (optional — e.g. Netflix)' : 'Source (optional, passed to media_player.select_source)'}</label>
      <input type="text" id="wizInputText" value="${cfg.inputCommand || ''}" list="wizInputTextHints" placeholder="${isNetworkAppleTv(ref.deviceId) ? 'e.g. Netflix' : 'e.g. Apple TV'}">
      <datalist id="wizInputTextHints">${haSourceEntries(ref.deviceId).map(([v]) => `<option value="${v}"></option>`).join('')}</datalist>
    ` : `
      <div id="wizProfileHint"></div>
      ${commandFieldHtml('wizPowerOn', 'Power-on command (optional)', ref)}
      ${commandFieldHtml('wizPowerOff', 'Power-off command (optional)', ref)}
      ${commandFieldHtml('wizInput', 'Input/source command (optional — sent after power-on, or on its own if this device is shared with the outgoing Activity)', ref)}
    `}

    <label class="inline-check" style="margin-top:10px"><input type="checkbox" id="wizPowerOnFirst" ${cfg.powerOnFirst !== false ? 'checked' : ''}> ${I18N.t('web_activity_power_on_first')}</label>
    <label class="inline-check"><input type="checkbox" id="wizPowerOffOnExit" ${cfg.powerOffOnExit !== false ? 'checked' : ''}> ${I18N.t('web_activity_power_off_on_exit')}</label>
    <label class="inline-check"><input type="checkbox" id="wizKeepOn" ${wizard.keepOn.has(ref.deviceId) ? 'checked' : ''}> ${I18N.t('web_activity_keep_on')}</label>
    <div class="hint">${I18N.t('web_activity_keep_on_hint')}</div>
    <label>${I18N.t('web_activity_delay_label')}</label>
    <input type="number" id="wizDelay" value="${cfg.delayAfterMs || 0}" min="0">
  `;
}

function renderWizardVolume() {
  return `
    <div class="hint">${I18N.t('web_activity_volume_hint')}</div>
    <select id="wizVolumeDevice">
      <option value="">${I18N.t('web_none')}</option>
      ${wizard.deviceRefs.map(r => `<option value="${r.deviceId}" ${wizard.volumeDeviceId === r.deviceId ? 'selected' : ''}>${deviceRefLabel(r)}</option>`).join('')}
    </select>
    <div class="hint" style="margin-top:8px">${I18N.t('web_activity_volume_bound_hint')}</div>
  `;
}

function renderWizardVolumeCommands() {
  const ref = volumeDeviceRef();
  return `
    <div class="hint">${I18N.t('web_activity_volume_commands_q', deviceRefLabel(ref))}</div>
    ${commandFieldHtml('wizVolUp', I18N.t('web_activity_vol_up_label'), ref)}
    ${commandFieldHtml('wizVolDown', I18N.t('web_activity_vol_down_label'), ref)}
    ${commandFieldHtml('wizMute', I18N.t('web_hwkey_mute'), ref)}
  `;
}

function renderWizardReview() {
  const noCmdCount = wizard.deviceRefs.filter((r, i) => {
    const c = wizard.deviceConfig[i] || {};
    return r.source !== 'ha' && !c.powerOnCommand && !c.powerOffCommand && !c.inputCommand;
  }).length;
  const volRef = volumeDeviceRef();
  let volLine = '';
  if (volRef) {
    volLine = volRef.source === 'ha'
      ? I18N.t('web_activity_review_volume_ha', deviceRefLabel(volRef))
      : I18N.t('web_activity_review_volume_cmds', deviceRefLabel(volRef), wizard.volumeUpCommand || '—', wizard.volumeDownCommand || '—', wizard.muteCommand || '—');
    volLine += ' — ' + I18N.t('web_activity_review_volume_bound');
  }
  return `
    <div class="hint"><strong>${wizard.name}</strong> — ${wizard.room}</div>
    <div id="wizReviewList" style="margin-top:10px"></div>
    ${volLine ? `<div class="hint" style="margin-top:8px">${volLine}</div>` : ''}
    ${noCmdCount > 0 ? `<div class="hint" style="color:#e5984a;margin-top:8px">${I18N.t(noCmdCount === 1 ? 'web_activity_review_no_commands_one' : 'web_activity_review_no_commands_many', noCmdCount)}</div>` : ''}
    <label style="margin-top:12px">${I18N.t('web_activity_now_playing_label')}</label>
    <select id="wizNowPlaying" onchange="wizard.nowPlayingEntity = this.value || null">${wizardNowPlayingOptionsHtml()}</select>
    <div class="hint">${I18N.t('web_activity_now_playing_hint')}</div>
  `;
}

/** The dock clock's "Now playing" source for this Activity: automatic, then the Activity's own
 * media_players, then every other one the builder knows (catalog + live HA states). */
function wizardNowPlayingOptionsHtml() {
  const own = wizard.deviceRefs.filter(r => r.source === 'ha' && /^media_player\./.test(r.deviceId)).map(r => r.deviceId);
  const others = (typeof dockEntityOptions === 'function' ? dockEntityOptions('media_player') : []).filter(([id]) => !own.includes(id));
  const current = wizard.nowPlayingEntity;
  const opt = (id, label) => `<option value="${id}" ${id === current ? 'selected' : ''}>${label}</option>`;
  const known = new Set(['', ...own, ...others.map(([id]) => id)]);
  return opt('', I18N.t('web_activity_now_playing_auto')) +
    own.map(id => opt(id, deviceRefLabel({ source: 'ha', deviceId: id }))).join('') +
    others.map(([id, name]) => opt(id, `${name} (${id})`)).join('') +
    (current && !known.has(current) ? opt(current, current) : '');
}

// ---- phase-specific wiring (dropdowns, live handlers) ----------------------

function wireWizardPhase(phase) {
  if (phase === 'info') {
    document.getElementById('wizIconField').innerHTML = iconFieldHtml('wizIcon');
    document.getElementById('wizIcon').value = wizard.icon;
    updateIconThumb('wizIcon');
  } else if (phase === 'devices') {
    renderWizardDeviceRefsList();
    renderWizardHarmonyAddFields();
  } else if (phase === 'configure') {
    const ref = wizard.deviceRefs[wizard.configureIndex];
    const cfg = wizard.deviceConfig[wizard.configureIndex] || {};
    if (ref.source === 'ir') {
      const dev = irDevicesById()[ref.deviceId];
      const entries = irDeviceCommandEntries(dev);
      fillCommandField('wizPowerOn', entries, cfg.powerOnCommand, ref);
      fillCommandField('wizPowerOff', entries, cfg.powerOffCommand, ref);
      fillCommandField('wizInput', entries, cfg.inputCommand, ref);
      // Reference device: upgrade the suggestions with the real list (and
      // the Harmony profile, if any) once the remote's copy is read.
      loadIrDatabaseModel(dev).then(model => {
        if (!model || wizard?.phase !== 'configure' || wizard.deviceRefs[wizard.configureIndex] !== ref) return;
        fillWizCommandOptions('wizPowerOn', modelCommandEntries(model, false));
        fillWizCommandOptions('wizPowerOff', modelCommandEntries(model, false));
        fillWizCommandOptions('wizInput', modelCommandEntries(model, true));
        const hint = document.getElementById('wizProfileHint');
        if (hint) hint.innerHTML = profileSummaryHtml(model.profile);
      });
    } else if (ref.source === 'harmony') {
      loadHarmonyConfig(ref.hub).then(data => {
        const device = (data.devices || []).find(d => d.id === ref.deviceId);
        const aliases = dashboardData.harmonyAliases?.[ref.hub]?.[ref.deviceId] || {};
        const entries = device ? device.commands.map(c => [c.name, aliases[c.name] || c.label || c.name]) : [];
        fillCommandField('wizPowerOn', entries, cfg.powerOnCommand, ref);
        fillCommandField('wizPowerOff', entries, cfg.powerOffCommand, ref);
        fillCommandField('wizInput', entries, cfg.inputCommand, ref);
      });
    }
  } else if (phase === 'volumeCommands') {
    const ref = volumeDeviceRef();
    if (ref.source === 'ir') {
      const entries = irDeviceCommandEntries(irDevicesById()[ref.deviceId]);
      fillCommandField('wizVolUp', entries, wizard.volumeUpCommand, ref);
      fillCommandField('wizVolDown', entries, wizard.volumeDownCommand, ref);
      fillCommandField('wizMute', entries, wizard.muteCommand, ref);
    } else if (ref.source === 'harmony') {
      loadHarmonyConfig(ref.hub).then(data => {
        const device = (data.devices || []).find(d => d.id === ref.deviceId);
        const aliases = dashboardData.harmonyAliases?.[ref.hub]?.[ref.deviceId] || {};
        const entries = device ? device.commands.map(c => [c.name, aliases[c.name] || c.label || c.name]) : [];
        fillCommandField('wizVolUp', entries, wizard.volumeUpCommand, ref);
        fillCommandField('wizVolDown', entries, wizard.volumeDownCommand, ref);
        fillCommandField('wizMute', entries, wizard.muteCommand, ref);
      });
    }
  } else if (phase === 'review') {
    renderWizardReviewList();
  }
}

/**
 * Populates the `<datalist>` suggestions for a command field that's a
 * free-text input — only reached via [fillCommandField] for a device whose
 * command list is genuinely partial (an ir-database *reference* device with
 * typed-in "known command ids" hints; see [hasCompleteCommandList]).
 * Harmony and inline-IR devices get a real `<select>` instead, filled
 * directly in [fillCommandField].
 */
function fillWizCommandOptions(inputId, commandEntries) {
  const datalist = document.getElementById(inputId + 'Hints');
  if (!datalist) return;
  datalist.innerHTML = commandEntries.map(([id, label]) => `<option value="${id}">${label}</option>`).join('');
}

function renderWizardHarmonyAddFields() {
  const container = document.getElementById('wizHarmonyAddFields');
  if (!harmonyAvailable) {
    container.innerHTML = `<div class="hint">${I18N.t('web_activity_no_harmony')}</div>`;
    return;
  }
  container.innerHTML = `
    <div id="wizHarmonyPicker"></div>
    <div class="btn-row" style="margin-top:6px">
      <button type="button" class="secondary" onclick="addHarmonyDeviceRef()">${I18N.t('web_activity_add_harmony_device')}</button>
    </div>
  `;
  // Renders into its OWN sub-container (#wizHarmonyPicker), not the outer
  // one that also holds the "+ Add Harmony device" button — renderHarmonyHubSelect
  // sets container.innerHTML itself, which would otherwise wipe out that
  // button (and everything else already in `container`) the moment it runs.
  // Reuses the exact same Hub -> Device cascading picker (and its built-in
  // onHarmonyHubChange handler) as the hotkey ('hk') and scene item ('gi')
  // forms — no separate wiring needed here.
  renderHarmonyHubSelect(document.getElementById('wizHarmonyPicker'), 'device', 'wizHarmony');
}

function renderWizardDeviceRefsList() {
  const list = document.getElementById('wizDeviceRefsList');
  // Keep the Apple TV checkboxes in step with the list (✕ removes too).
  document.querySelectorAll('input[data-ha-ref]').forEach(cb => {
    cb.checked = wizard.deviceRefs.some(r => r.source === 'ha' && r.deviceId === cb.dataset.haRef);
  });
  if (!wizard.deviceRefs.length) {
    list.innerHTML = `<div class="hint">${I18N.t('web_activity_none_yet')}</div>`;
    return;
  }
  list.innerHTML = '';
  wizard.deviceRefs.forEach((ref, i) => {
    const el = document.createElement('div');
    el.className = 'list-item';
    el.innerHTML = `<span>${deviceRefLabel(ref)}</span><span class="remove" onclick="removeDeviceRef(${i})">✕</span>`;
    list.appendChild(el);
  });
}

function renderWizardReviewList() {
  const list = document.getElementById('wizReviewList');
  list.innerHTML = '';
  wizard.deviceRefs.forEach((ref, i) => {
    const cfg = wizard.deviceConfig[i] || {};
    const parts = [];
    if (cfg.powerOnCommand) parts.push(I18N.t('web_activity_review_on', cfg.powerOnCommand));
    if (cfg.powerOffCommand) parts.push(I18N.t('web_activity_review_off', cfg.powerOffCommand));
    if (cfg.inputCommand) parts.push(I18N.t('web_activity_review_input', cfg.inputCommand));
    if (wizard.keepOn.has(ref.deviceId)) parts.push(I18N.t('web_activity_review_keep_on'));
    const isVolume = wizard.volumeDeviceId === ref.deviceId;
    const el = document.createElement('div');
    el.className = 'list-item';
    el.innerHTML = `<span>${deviceRefLabel(ref)}${isVolume ? ' 🔊' : ''} <span style="color:#888">${parts.join(', ') || I18N.t('web_activity_review_no_commands')}</span></span>`;
    list.appendChild(el);
  });
}

// ---- device-selection actions (phase: 'devices') ---------------------------

function pickActivityType(typeId) {
  const type = ACTIVITY_TYPES.find(t => t.id === typeId);
  wizard.type = typeId;
  if (!wizard.name && type.id !== 'custom') wizard.name = I18N.t(type.labelKey);
  wizard.phase = 'info';
  renderWizard();
}

function toggleIrDeviceRef(deviceId, checked) {
  if (checked) {
    wizard.deviceRefs.push({ source: 'ir', deviceId });
  } else {
    const i = wizard.deviceRefs.findIndex(r => r.source === 'ir' && r.deviceId === deviceId);
    if (i >= 0) wizard.deviceRefs.splice(i, 1);
  }
  renderWizardDeviceRefsList();
}

function addHarmonyDeviceRef() {
  const hub = document.getElementById('wizHarmonyHub')?.value;
  const deviceId = document.getElementById('wizHarmonyDeviceSelect')?.value;
  if (!hub || !deviceId) { alert(I18N.t('web_activity_alert_pick_harmony')); return; }
  if (wizard.deviceRefs.some(r => r.source === 'harmony' && r.hub === hub && r.deviceId === deviceId)) {
    alert(I18N.t('web_activity_alert_device_dup'));
    return;
  }
  const deviceLabel = document.getElementById('wizHarmonyDeviceSelect').selectedOptions[0]?.textContent || deviceId;
  wizard.deviceRefs.push({ source: 'harmony', deviceId, hub, deviceLabel });
  renderWizardDeviceRefsList();
}

function toggleHaDeviceRef(entityId, checked) {
  if (checked) {
    if (!wizard.deviceRefs.some(r => r.source === 'ha' && r.deviceId === entityId)) wizard.deviceRefs.push({ source: 'ha', deviceId: entityId });
  } else {
    const i = wizard.deviceRefs.findIndex(r => r.source === 'ha' && r.deviceId === entityId);
    if (i >= 0) removeDeviceRef(i); // keeps deviceConfig aligned
    return;
  }
  renderWizardDeviceRefsList();
}

function addHaDeviceRef() {
  // Typed id wins; otherwise whatever is picked in the list.
  const typed = document.getElementById('wizHaEntityId').value.trim();
  const picked = document.getElementById('wizHaEntitySelect')?.value || '';
  const deviceId = typed || picked;
  if (!deviceId) { alert(I18N.t('web_activity_err_pick_entity')); return; }
  if (!/^[a-z_]+\.[a-z0-9_]+$/.test(deviceId)) { alert(I18N.t('web_activity_err_entity_format')); return; }
  if (wizard.deviceRefs.some(r => r.source === 'ha' && r.deviceId === deviceId)) {
    alert(I18N.t('web_activity_alert_entity_dup'));
    return;
  }
  wizard.deviceRefs.push({ source: 'ha', deviceId });
  document.getElementById('wizHaEntityId').value = '';
  const sel = document.getElementById('wizHaEntitySelect');
  if (sel) sel.value = '';
  renderWizardDeviceRefsList();
}

function removeDeviceRef(i) {
  wizard.deviceRefs.splice(i, 1);
  wizard.deviceConfig.splice(i, 1);
  renderWizardDeviceRefsList();
}

// ---- saving / listing / removing Activities --------------------------------

function saveActivityWizard() {
  dashboardData.activities = dashboardData.activities || [];
  if (wizard.keepOn.size) dashboardData.keepOnDevices = [...wizard.keepOn].sort();
  else delete dashboardData.keepOnDevices;
  const devices = wizard.deviceRefs.map((ref, i) => {
    const cfg = wizard.deviceConfig[i] || {};
    return {
      deviceId: ref.deviceId,
      source: ref.source,
      ...(ref.hub ? { hub: ref.hub } : {}),
      ...(cfg.powerOnCommand ? { powerOnCommand: cfg.powerOnCommand } : {}),
      ...(cfg.powerOffCommand ? { powerOffCommand: cfg.powerOffCommand } : {}),
      ...(cfg.inputCommand ? { inputCommand: cfg.inputCommand } : {}),
      ...(cfg.powerOnFirst === false ? { powerOnFirst: false } : {}),
      ...(cfg.powerOffOnExit === false ? { powerOffOnExit: false } : {}),
      ...(cfg.delayAfterMs ? { delayAfterMs: cfg.delayAfterMs } : {}),
    };
  });
  const volRef = volumeDeviceRef();
  const payload = {
    name: wizard.name,
    room: wizard.room,
    ...(wizard.icon ? { icon: wizard.icon } : {}),
    devices,
    ...(wizard.volumeDeviceId ? { volumeDeviceId: wizard.volumeDeviceId } : {}),
    ...(volRef && volRef.source !== 'ha' && wizard.volumeUpCommand ? { volumeUpCommand: wizard.volumeUpCommand } : {}),
    ...(volRef && volRef.source !== 'ha' && wizard.volumeDownCommand ? { volumeDownCommand: wizard.volumeDownCommand } : {}),
    ...(volRef && volRef.source !== 'ha' && wizard.muteCommand ? { muteCommand: wizard.muteCommand } : {}),
    ...(wizard.nowPlayingEntity ? { nowPlayingEntity: wizard.nowPlayingEntity } : {}),
  };

  if (wizard.editingId) {
    const idx = dashboardData.activities.findIndex(a => a.id === wizard.editingId);
    if (idx >= 0) {
      dashboardData.activities[idx] = { ...dashboardData.activities[idx], ...payload };
      if (!wizard.nowPlayingEntity) delete dashboardData.activities[idx].nowPlayingEntity;
    }
  } else {
    const id = slugify(wizard.name, 'activity');
    let uniqueId = id;
    let n = 2;
    while (dashboardData.activities.some(a => a.id === uniqueId)) uniqueId = `${id}_${n++}`;
    dashboardData.activities.push({ id: uniqueId, ...payload });
  }
  // Volume hotkeys (if this Activity has a volume device) get bound to a
  // page wherever this Activity is actually placed on a scene card — see
  // writeVolumeHotkeysForActivity() in cards.js's addGridItem() — not here:
  // an Activity on its own isn't tied to any page, it's only ever reached
  // via a card, so that's the one place the page is actually unambiguous.
  closeActivityWizard();
  renderActivitiesList();
  updateCardFormInputs(); // refreshes the composed-Activity picker inside the scene_grid form, if open
  updateJsonOutput();
}

/**
 * Writes VOLUME_UP/VOLUME_DOWN/MUTE as page-scoped hotkeys (PageConfig.
 * hotkeys, which already override global bindings while that page is on
 * screen — see MainActivity.mergeHotkeys) on this Activity's `page`, so
 * pressing the physical volume keys while that page is showing routes to
 * whichever device this Activity designated for volume. No-op if the
 * Activity has no `page` or no volume device chosen.
 *
 * If that page already has ANY of these three keys bound to something else,
 * confirms before overwriting — the most likely case is the user, like the
 * one this feature was built for, already assigned VOLUME_UP/DOWN/MUTE
 * globally to a different default device, and page-scoped hotkeys silently
 * taking priority there would be a surprise otherwise.
 */
/**
 * Writes VOLUME_UP/VOLUME_DOWN/MUTE as page-scoped hotkeys (PageConfig.
 * hotkeys, which already override global bindings while that page is on
 * screen — see MainActivity.mergeHotkeys) on `pageName`, so pressing the
 * physical volume keys while that page is showing routes to whichever
 * device `activity` designated for volume. No-op if the Activity has no
 * volume device chosen, or `pageName` doesn't resolve to an actual page.
 *
 * Called from cards.js's addGridItem() whenever a composed Activity
 * (dashboardData.activities) is placed on a scene_grid card — the page that
 * card lives on is the one unambiguous place this binding makes sense; an
 * Activity by itself isn't tied to any page; it's only ever reached via a
 * card. See addGridItem's own call site for why this can't run at Activity
 * save time instead: the Activity may not be on any card yet.
 *
 * If that page already has ANY of these three keys bound to something else,
 * confirms before overwriting — the most likely case is the user already
 * assigned VOLUME_UP/DOWN/MUTE globally to a different default device, and
 * page-scoped hotkeys silently taking priority there would be a surprise
 * otherwise.
 */
function writeVolumeHotkeysForActivity(activity, pageName) {
  if (!activity || !activity.volumeDeviceId || !pageName) return;
  const ref = (activity.devices || []).find(d => d.deviceId === activity.volumeDeviceId);
  if (!ref) return;
  const page = dashboardData.pages.find(p => p.name === pageName);
  if (!page) return; // the card being saved always lives on an existing page already

  let bindings;
  if (ref.source === 'ha') {
    bindings = [
      { key: 'VOLUME_UP', service: 'media_player.volume_up', entityId: ref.deviceId },
      { key: 'VOLUME_DOWN', service: 'media_player.volume_down', entityId: ref.deviceId },
      { key: 'MUTE', service: 'media_player.volume_mute', entityId: ref.deviceId, data: { is_volume_muted: true } },
    ];
  } else {
    const actionFor = command => ref.source === 'ir'
      ? { irDevice: ref.deviceId, irCommand: command }
      : { harmonyDevice: ref.deviceId, harmonyCommand: command, ...(ref.hub ? { hub: ref.hub } : {}) };
    bindings = [];
    if (activity.volumeUpCommand) bindings.push({ key: 'VOLUME_UP', ...actionFor(activity.volumeUpCommand) });
    if (activity.volumeDownCommand) bindings.push({ key: 'VOLUME_DOWN', ...actionFor(activity.volumeDownCommand) });
    if (activity.muteCommand) bindings.push({ key: 'MUTE', ...actionFor(activity.muteCommand) });
  }
  if (!bindings.length) return;

  page.hotkeys = page.hotkeys || [];
  const conflictingKeys = bindings
    .map(b => b.key)
    .filter(key => page.hotkeys.some(h => h.key === key));
  if (conflictingKeys.length) {
    const proceed = confirm(
      I18N.t('web_activity_confirm_overwrite_volume', pageName, conflictingKeys.join('/'), activity.name, deviceRefLabel(ref)),
    );
    if (!proceed) return;
  }
  bindings.forEach(b => {
    const i = page.hotkeys.findIndex(h => h.key === b.key);
    if (i >= 0) page.hotkeys[i] = b; else page.hotkeys.push(b);
  });
}

function renderActivitiesList() {
  const list = document.getElementById('activitiesList');
  if (!list) return;
  list.innerHTML = '';
  const byRoom = {};
  (dashboardData.activities || []).forEach(act => { (byRoom[act.room] = byRoom[act.room] || []).push(act); });
  Object.keys(byRoom).sort().forEach(room => {
    const heading = document.createElement('div');
    heading.className = 'list-group-heading';
    heading.textContent = room;
    list.appendChild(heading);
    byRoom[room].forEach(act => {
      const el = document.createElement('div');
      el.className = 'list-item';
      el.innerHTML = `<span>${act.name} <span style="color:#888">${I18N.t(act.devices.length === 1 ? 'web_activity_device_count_one' : 'web_activity_device_count_many', act.devices.length)}</span></span><span><span class="remove" style="color:#00E5FF" onclick="startActivityWizard('${act.id}')">✎</span> <span class="remove" onclick="removeActivity('${act.id}')">✕</span></span>`;
      list.appendChild(el);
    });
  });
}

function removeActivity(id) {
  dashboardData.activities = (dashboardData.activities || []).filter(a => a.id !== id);
  renderActivitiesList();
  updateCardFormInputs();
  updateJsonOutput();
}

renderActivitiesList();