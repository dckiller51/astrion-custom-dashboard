// ---- Hotkeys --------------------------------------------------------------

// Built-in command suggestions for the HA `androidtv_remote` integration
// (https://www.home-assistant.io/integrations/androidtv_remote/#remote).
// Used as a <datalist> when the selected remote entity has no
// `commands_list` attribute (most Android TV Remote entities don't expose
// one). Integrations that DO populate `commands_list` (e.g. Apple TV)
// override this list with their own values at runtime via
// `remoteCommandSuggestions()`.
//
// Order mirrors the HA docs' grouping (Navigation → Volume → Media → TV →
// Other) so the most-used keys surface first in the dropdown. The older
// `androidtv` (ADB) integration uses a slightly different keymap (bare
// `UP`/`DOWN`/`MUTE`/`PLAY`, `HDMI1`-`HDMI4`, `SLEEP`/`WAKEUP`, etc.); those
// aliases are appended at the end so they still autocomplete for ADB-backed
// remotes, but the androidtv_remote names take precedence.
const ANDROID_TV_COMMANDS = [
  // --- Navigation ---
  'DPAD_UP', 'DPAD_DOWN', 'DPAD_LEFT', 'DPAD_RIGHT', 'DPAD_CENTER',
  'BUTTON_A', 'BUTTON_B', 'BUTTON_X', 'BUTTON_Y',
  'BACK', 'HOME', 'MENU', 'ENTER', 'INFO', 'GUIDE',
  // --- Volume control ---
  'VOLUME_UP', 'VOLUME_DOWN', 'VOLUME_MUTE', 'MUTE',
  // --- Media control ---
  'MEDIA_PLAY_PAUSE', 'MEDIA_PLAY', 'MEDIA_PAUSE',
  'MEDIA_NEXT', 'MEDIA_PREVIOUS', 'MEDIA_STOP', 'MEDIA_RECORD',
  'MEDIA_REWIND', 'MEDIA_FAST_FORWARD',
  // --- TV control ---
  'CHANNEL_UP', 'CHANNEL_DOWN',
  '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
  'DEL',
  'F1', 'F2', 'F3', 'F4', 'F5', 'F6', 'F7', 'F8', 'F9', 'F10', 'F11', 'F12',
  'TV', 'TV_TELETEXT', 'CAPTIONS', 'DVR',
  'PROG_RED', 'PROG_GREEN', 'PROG_YELLOW', 'PROG_BLUE',
  // --- Other ---
  'BUTTON_MODE', 'EXPLORER', 'SETTINGS', 'SEARCH', 'ASSIST', 'POWER',
  'MEDIA_AUDIO_TRACK',
  // --- Legacy `androidtv` (ADB) aliases (not in androidtv_remote docs) ---
  'UP', 'DOWN', 'LEFT', 'RIGHT', 'CENTER',
  'ESCAPE', 'END', 'TOP', 'MOVE_HOME', 'PAIRING', 'TEXT',
  'SLEEP', 'WAKEUP', 'RESUME', 'SUSPEND',
  'PLAY', 'PAUSE', 'REWIND', 'FAST_FORWARD',
  'RED', 'GREEN', 'YELLOW', 'BLUE',
  'INPUT', 'HDMI1', 'HDMI2', 'HDMI3', 'HDMI4',
  'COMPONENT1', 'COMPONENT2', 'COMPOSITE1', 'COMPOSITE2', 'SAT', 'VGA',
  'SYSDOWN', 'SYSUP', 'SYSLEFT', 'SYSRIGHT',
];

// Every command name AppleTvCommands.kt's forCommand() recognizes, one
// canonical spelling each (it accepts several aliases per command — this is
// just what the hotkey form offers/stores). Covers the trackpad directions
// and Select/Play-Pause/volume/power that the apple_tv_remote card's fixed
// controls already send, plus every id from ATV_EXTRA_BUTTONS below.
const ATV_ALL_COMMANDS = [
  { id: 'Up', labelKey: 'web_atv_cmd_up' },
  { id: 'Down', labelKey: 'web_atv_cmd_down' },
  { id: 'Left', labelKey: 'web_atv_cmd_left' },
  { id: 'Right', labelKey: 'web_atv_cmd_right' },
  { id: 'Select', labelKey: 'web_atv_cmd_select' },
  { id: 'SelectHold', labelKey: 'web_atv_cmd_select_hold' },
  { id: 'SeekBackward', labelKey: 'web_atv_cmd_seek_backward' },
  { id: 'SeekForward', labelKey: 'web_atv_cmd_seek_forward' },
  { id: 'Rewind', labelKey: 'web_atv_cmd_rewind' },
  { id: 'FastForward', labelKey: 'web_atv_cmd_fast_forward' },
  { id: 'PlayPause', labelKey: 'web_atv_cmd_play_pause' },
  { id: 'Play', labelKey: 'web_atv_cmd_play' },
  { id: 'Pause', labelKey: 'web_atv_cmd_pause' },
  { id: 'Next', labelKey: 'web_atv_cmd_next' },
  { id: 'Previous', labelKey: 'web_atv_cmd_previous' },
  { id: 'Wake', labelKey: 'web_atv_cmd_wake' },
  { id: 'Sleep', labelKey: 'web_atv_cmd_sleep' },
  { id: 'Menu', labelKey: 'web_atv_cmd_menu' },
  { id: 'Home', labelKey: 'web_atv_cmd_home' },
  { id: 'Siri', labelKey: 'web_atv_cmd_siri' },
  { id: 'Screensaver', labelKey: 'web_atv_cmd_screensaver' },
  { id: 'Guide', labelKey: 'web_atv_cmd_guide' },
  { id: 'VolumeUp', labelKey: 'web_atv_cmd_volume_up' },
  { id: 'VolumeDown', labelKey: 'web_atv_cmd_volume_down' },
  { id: 'ControlCenter', labelKey: 'web_atv_cmd_control_center' },
  { id: 'ChannelUp', labelKey: 'web_atv_cmd_channel_up' },
  { id: 'ChannelDown', labelKey: 'web_atv_cmd_channel_down' }
];

// ---- Android TV (1.3.6): a key or an app on a TV paired with Astrion ---------------------
//
// Stored as plain Home Assistant-style calls on the TV's own entities, which the remote answers
// itself: a key = remote.send_command on remote.androidtv_…, an app = media_player.select_source
// on media_player.androidtv_…. So the hotkey keeps working as is if the TV ever moves to HA.

/** The hotkey's Android TV, kind (key/app) and value, or null when it isn't an Android TV hotkey. */
function androidTvHotkeyParts(h) {
  if (h.service === 'remote.send_command' && /^remote\.androidtv_/.test(h.entityId || '')) {
    return { tv: h.entityId.replace(/^remote\./, 'media_player.'), kind: 'key', value: (h.data && h.data.command) || '' };
  }
  if (h.service === 'media_player.select_source' && /^media_player\.androidtv_/.test(h.entityId || '')) {
    return { tv: h.entityId, kind: 'app', value: (h.data && h.data.source) || '' };
  }
  return null;
}

async function loadNativeAndroidTvs() {
  try {
    const res = await fetch('/devices-config');
    const data = await res.json();
    return Array.isArray(data.androidTvs) ? data.androidTvs : [];
  } catch (e) {
    return [];
  }
}

/** Fills the TV picker with every paired Android TV, keeping [selected] even when it isn't listed. */
async function populateHkAndroidTvSelect(selected) {
  const select = document.getElementById('hkAndroidTv');
  if (!select) return;
  const tvs = await loadNativeAndroidTvs();
  select.innerHTML = tvs.length
    ? tvs.map(tv => `<option value="${escapeAttr(tv.entityId)}">${escapeAttr(tv.name)} (${escapeAttr(tv.entityId)})</option>`).join('')
    : `<option value="">${I18N.t('web_hotkey_adtv_none')}</option>`;
  if (selected) {
    if (![...select.options].some(o => o.value === selected)) {
      const missing = document.createElement('option');
      missing.value = selected;
      missing.textContent = selected;
      select.appendChild(missing);
    }
    select.value = selected;
  }
}

/** Key names or app names as suggestions, depending on the chosen kind. */
function onHkAndroidTvKindChange() {
  const kind = document.getElementById('hkAndroidTvKind').value;
  const input = document.getElementById('hkAndroidTvValue');
  const list = document.getElementById('hkAndroidTvValues');
  const apps = typeof TV_KNOWN_APPS !== 'undefined' ? TV_KNOWN_APPS : [];
  list.innerHTML = kind === 'app'
    ? apps.map(([n, p]) => `<option value="${escapeAttr(p)}">${escapeAttr(n)}</option>`).join('')
    : ANDROID_TV_COMMANDS.map(c => `<option value="${c}">`).join('');
  input.placeholder = kind === 'app' ? 'com.canal.android.canal' : 'CHANNEL_UP';
  document.getElementById('hkAndroidTvHint').textContent = I18N.t(kind === 'app' ? 'web_hotkey_adtv_app_hint' : 'web_hotkey_adtv_key_hint');
}

/** Populates the Apple TV picker for the hotkey form with every paired Apple TV, selecting [selectedEntityId] if given. */
async function populateHkAppleTvSelect(selectedEntityId) {
  const select = document.getElementById('hkAppleTv');
  if (!select) return;
  const tvs = await loadNativeAppleTvs();
  select.innerHTML = tvs.length
    ? tvs.map(tv => `<option value="${tv.entityId}">${tv.name} (${tv.entityId})</option>`).join('')
    : `<option value="">${I18N.t('web_atv_none_paired')}</option>`;
  if (selectedEntityId) {
    if (![...select.options].some(opt => opt.value === selectedEntityId)) {
      const missing = document.createElement('option');
      missing.value = selectedEntityId;
      missing.textContent = I18N.t('web_atv_not_paired', selectedEntityId);
      select.appendChild(missing);
    }
    select.value = selectedEntityId;
  }
}

// Returns the command list to suggest for a given remote entity, or null
// when no live HA state is available (HA not configured, or offline). Prefers
// the entity's own `commands_list` attribute when present; otherwise falls
// back to the built-in Android TV keymap.
function remoteCommandSuggestions(entityId) {
  if (haStates && entityId) {
    const ent = haStates[entityId];
    const list = ent && ent.attributes && ent.attributes.commands_list;
    if (Array.isArray(list) && list.length) return list;
  }
  return ANDROID_TV_COMMANDS;
}

// Build (or rebuild) the <datalist> of command suggestions for #hkCommand
// based on the currently-entered entity ID. Called on entity input.
function refreshRemoteCommandDatalist() {
  const dl = document.getElementById('hkCommandList');
  if (!dl) return;
  const entityId = document.getElementById('hkEntityId') ? document.getElementById('hkEntityId').value.trim() : '';
  const suggestions = remoteCommandSuggestions(entityId) || [];
  dl.innerHTML = suggestions.map((c) => `<option value="${c}">`).join('');
}

// Selecting "Settings" or "Active Activities" here is a shortcut, not a
// real page: addHotkey() below detects these two sentinel values and
// writes an "openOverlay" hotkey instead of a "page" one — same end
// result as picking the separate "Open overlay" action, just reachable
// from the one dropdown someone's already looking at instead of having to
// switch the Action selector for what's arguably the same kind of thing
// ("go here"). Editing an existing hotkey never shows these two selected
// (an openOverlay hotkey routes to the openOverlay action branch instead,
// see editHotkey) — they're an input-only convenience.
const HK_PAGE_OVERLAY_SHORTCUTS = [
  { value: '__overlay_settings__', labelKey: 'web_settings', overlay: 'settings' },
  { value: '__overlay_activities__', labelKey: 'web_active_activities', overlay: 'activities' }
];

function populateHkPageSelect() {
  const select = document.getElementById('hkPage');
  const pageOptions = (dashboardData.pages || [])
    .map(p => `<option value="${p.name.replace(/"/g, '&quot;')}">${p.name}</option>`)
    .join('');
  const shortcutOptions = HK_PAGE_OVERLAY_SHORTCUTS
    .map(s => `<option value="${s.value}">${I18N.t(s.labelKey)}</option>`)
    .join('');
  select.innerHTML =
    `<optgroup label="${I18N.t('web_hotkey_optgroup_pages')}">${pageOptions}</optgroup>` +
    `<optgroup label="${I18N.t('web_hotkey_optgroup_shortcuts')}">${shortcutOptions}</optgroup>`;
}

function updateHotkeyActionInputs(preselect) {
  const action = document.getElementById('hkAction').value;
  const container = document.getElementById('dynamicHotkeyInputs');
  if (action === 'page') {
    container.innerHTML = `<label>${I18N.t('web_hotkey_target_page_label')}</label><select id="hkPage"></select>
      <label class="inline-check"><input type="checkbox" id="hkPagePopup"> ${I18N.t('web_hotkey_page_popup')}</label>
      <div class="hint">${I18N.t('web_hotkey_page_popup_hint')}</div>`;
    populateHkPageSelect();
  } else if (action === 'openOverlay') {
    container.innerHTML = `
      <label>${I18N.t('web_hotkey_overlay_label')}</label>
      <select id="hkOverlay">
        <option value="settings">${I18N.t('web_settings')}</option>
        <option value="activities">${I18N.t('web_hotkey_overlay_activities_top')}</option>
      </select>
    `;
  } else if (action === 'openCurrentActivity') {
    container.innerHTML = `
      <label>${I18N.t('web_hotkey_room_label')}</label><input type="text" id="hkActivityRoom" placeholder="${I18N.t('web_hotkey_room_ph')}">
      <div class="hint" style="margin-top:4px">${I18N.t('web_hotkey_room_hint')}</div>
    `;
  } else if (action === 'startActivity') {
    const acts = dashboardData.activities || [];
    container.innerHTML = acts.length ? `
      <label>${I18N.t('web_hotkey_activity_label')}</label>
      <select id="hkStartActivity">
        ${acts.map(a => `<option value="${escapeAttr(a.id)}">${escapeAttr(a.name || a.id)}${a.room ? ' (' + escapeAttr(a.room) + ')' : ''}</option>`).join('')}
      </select>
      <div class="hint" style="margin-top:4px">${I18N.t('web_hotkey_activity_hint')}</div>
    ` : `<div class="hint">${I18N.t('web_hotkey_no_activities')}</div>`;
  } else if (action === 'stopActivity') {
    container.innerHTML = `
      <label>${I18N.t('web_hotkey_room_label')}</label>
      <input type="text" id="hkStopRoom" list="hkStopRooms" placeholder="${I18N.t('web_hotkey_stop_room_ph')}">
      <datalist id="hkStopRooms">${activityRoomOptionsHtml()}</datalist>
      <div class="hint" style="margin-top:4px">${I18N.t('web_hotkey_stop_hint')}</div>
    `;
  } else if (action === 'haEvent') {
    container.innerHTML = `
      <label>${I18N.t('web_hotkey_event_label')}</label>
      <input type="text" id="hkEventName" placeholder="${I18N.t('web_hotkey_event_ph')}">
      <div class="hint" style="margin-top:4px">${I18N.t('web_hotkey_event_hint')}</div>
    `;
  } else if (action === 'service') {
    container.innerHTML = `
      <label>${I18N.t('web_hotkey_service_label')}</label><input type="text" id="hkService" placeholder="${I18N.t('web_hotkey_service_ph')}">
      <label>${I18N.t('web_hotkey_entity_label')}</label><input type="text" id="hkEntityId" placeholder="${I18N.t('web_hotkey_entity_ph')}">
      <label>${I18N.t('web_hotkey_data_label')}</label><input type="text" id="hkData" placeholder='{"brightness": 255}'>
    `;
  } else if (action === 'remoteCommand') {
    container.innerHTML = `
      <label>${I18N.t('web_hotkey_remote_entity_label')}</label><input type="text" id="hkEntityId" placeholder="${I18N.t('web_hotkey_remote_entity_ph')}" oninput="refreshRemoteCommandDatalist()">
      <label>${I18N.t('web_hotkey_command_label')}</label>
      <input type="text" id="hkCommand" placeholder="${I18N.t('web_hotkey_command_ph')}" list="hkCommandList">
      <datalist id="hkCommandList"></datalist>
      <div class="hint">${I18N.t('web_hotkey_remote_hint')}</div>
    `;
    attachEntityAutocomplete(document.getElementById('hkEntityId'), 'remote');
    refreshRemoteCommandDatalist();
  } else if (action === 'appleTvCommand') {
    container.innerHTML = `
      <label>${I18N.t('web_atv_label')}</label>
      <select id="hkAppleTv"></select>
      <label>${I18N.t('web_hotkey_command_label')}</label>
      <select id="hkAppleTvCommand">
        ${ATV_ALL_COMMANDS.map(c => `<option value="${c.id}">${I18N.t(c.labelKey)}</option>`).join('')}
      </select>
    `;
    populateHkAppleTvSelect(preselect && preselect.appleTvEntityId);
  } else if (action === 'androidTvCommand') {
    container.innerHTML = `
      <label>${I18N.t('web_hotkey_adtv_tv')}</label>
      <select id="hkAndroidTv"></select>
      <label>${I18N.t('web_hotkey_adtv_kind')}</label>
      <select id="hkAndroidTvKind" onchange="onHkAndroidTvKindChange()">
        <option value="key">${I18N.t('web_hotkey_adtv_kind_key')}</option>
        <option value="app">${I18N.t('web_hotkey_adtv_kind_app')}</option>
      </select>
      <input type="text" id="hkAndroidTvValue" list="hkAndroidTvValues" style="margin-top:6px" autocomplete="off">
      <datalist id="hkAndroidTvValues"></datalist>
      <div class="hint" id="hkAndroidTvHint"></div>
    `;
    onHkAndroidTvKindChange();
    populateHkAndroidTvSelect(preselect && preselect.androidTv);
  } else if (action === 'irCommand') {
    const devices = dashboardData.irDevices || [];
    container.innerHTML = `
      <label>${I18N.t('web_hotkey_ir_device_label')}</label>
      <select id="hkIrDevice" onchange="onHkIrDeviceChange()">
        ${devices.map(d => `<option value="${escapeAttr(d.id)}">${escapeAttr(d.name || d.id)}</option>`).join('')}
      </select>
      <label>${I18N.t('web_hotkey_ir_command_label')}</label>
      <input type="text" id="hkIrCommand" list="hkIrCommandHints" placeholder="${I18N.t('web_hotkey_ir_command_ph')}">
      <datalist id="hkIrCommandHints"></datalist>
      ${devices.length ? '' : `<div class="hint">${I18N.t('web_card_no_ir_devices_yet_add_one')}</div>`}
    `;
    onHkIrDeviceChange();
  } else if (action === 'harmonyCommand') {
    if (harmonyAvailable) {
      renderHarmonyHubSelect(container, 'command', 'hk');
    } else {
      container.innerHTML = `
        <label>${I18N.t('web_hotkey_harmony_device_label')}</label><input type="text" id="hkDevice" placeholder="${I18N.t('web_hotkey_harmony_device_ph')}">
        <label>${I18N.t('web_hotkey_harmony_command_label')}</label><input type="text" id="hkCommand" placeholder="${I18N.t('web_hotkey_harmony_command_ph')}">
      `;
    }
  } else if (action === 'harmonyActivity') {
    if (harmonyAvailable) {
      renderHarmonyHubSelect(container, 'activity', 'hk');
    } else {
      container.innerHTML = `<label>${I18N.t('web_hotkey_harmony_activity_label')}</label><input type="text" id="hkActivityId" placeholder="${I18N.t('web_hotkey_harmony_activity_ph')}">`;
    }
  }
}

/**
 * Command suggestions for the picked IR device: an inline device's own commands, else the
 * ir-database model's full list (when the builder is opened from the remote), else the ids
 * typed in when the device was created. Free text either way, like the tiles' IR field.
 */
async function onHkIrDeviceChange() {
  const select = document.getElementById('hkIrDevice');
  const datalist = document.getElementById('hkIrCommandHints');
  if (!select || !datalist) return;
  const deviceId = select.value;
  const device = (dashboardData.irDevices || []).find(d => d.id === deviceId);
  let entries = irDeviceCommandEntries(device);
  if (device && !device.commands) {
    const model = await loadIrDatabaseModel(device);
    if (model) entries = modelCommandEntries(model, false);
    if (select.value !== deviceId) return; // changed while loading
  }
  datalist.innerHTML = entries.map(([id, label]) => `<option value="${escapeAttr(id)}">${escapeAttr(label)}</option>`).join('');
}

function describeHotkey(h) {
  if (h.page) return I18N.t(h.pageMode === 'popup' ? 'web_hotkey_desc_page_popup' : 'web_hotkey_desc_page', h.page);
  if (h.openOverlay) return I18N.t(h.openOverlay === 'activities' ? 'web_hotkey_desc_overlay_activities' : 'web_hotkey_desc_overlay_settings');
  if (h.openCurrentActivityRoom) return I18N.t('web_hotkey_desc_current_activity', h.openCurrentActivityRoom);
  if (h.activity) {
    const act = (dashboardData.activities || []).find(a => a.id === h.activity);
    return I18N.t('web_hotkey_desc_activity', (act && act.name) || h.activity);
  }
  if (h.stopActivity) return h.stopActivity === '*' ? I18N.t('web_hotkey_desc_off_all') : I18N.t('web_hotkey_desc_off_room', h.stopActivity);
  if (h.event) return I18N.t('web_hotkey_desc_event', h.event);
  const adtv = androidTvHotkeyParts(h);
  if (adtv) {
    const tvName = (typeof haFriendlyName === 'function' && haFriendlyName(adtv.tv)) || adtv.tv;
    const appName = adtv.kind === 'app' && typeof tvAppLabel === 'function' ? tvAppLabel(adtv.value) || adtv.value : adtv.value;
    return I18N.t(adtv.kind === 'app' ? 'web_hotkey_desc_adtv_app' : 'web_hotkey_desc_adtv_key', tvName, appName);
  }
  if (h.service === 'remote.send_command') {
    const cmd = h.data && h.data.command ? h.data.command : '?';
    return I18N.t('web_hotkey_desc_remote', h.entityId || '?', cmd);
  }
  if (h.service === 'astrion_appletv.send_command') {
    const cmd = h.data && h.data.command ? h.data.command : '?';
    return I18N.t('web_hotkey_desc_atv', h.entityId || '?', cmd);
  }
  if (h.service) return h.entityId ? I18N.t('web_hotkey_desc_service', h.service, h.entityId) : I18N.t('web_hotkey_desc_service_plain', h.service);
  if (h.irDevice && h.irCommand) {
    const dev = (dashboardData.irDevices || []).find(d => d.id === h.irDevice);
    return I18N.t('web_hotkey_desc_ir', (dev && dev.name) || h.irDevice, h.irCommand);
  }
  if (h.harmonyCommand) return I18N.t('web_hotkey_desc_harmony', h.harmonyDevice || '?', h.harmonyCommand);
  if (h.harmonyActivity) return I18N.t('web_hotkey_desc_harmony_activity', h.harmonyActivity);
  return '';
}

function renderHotkeysList() {
  const container = document.getElementById('hotkeysList');
  if (!container) return;
  container.innerHTML = '';
  const page = dashboardData.pages[currentActivePage];

  // 1.4.3: green = active on the page shown, amber = not used there. A page key replaces the
  // global key of the same press type (the app's own precedence), so that global one is amber.
  const pageKeys = {
    hotkeys: new Set(((page && page.hotkeys) || []).map(h => String(h.key).toUpperCase())),
    longHotkeys: new Set(((page && page.longHotkeys) || []).map(h => String(h.key).toUpperCase()))
  };
  const addRows = (list, scope, listType, label) => {
    (list || []).forEach((h, i) => {
      const replaced = scope === 'global' && pageKeys[listType].has(String(h.key).toUpperCase());
      const tip = I18N.t(replaced ? 'web_hotkey_replaced_here' : 'web_hotkey_active_here');
      const row = document.createElement('div');
      row.className = 'list-item ' + (replaced ? 'hk-replaced' : 'hk-active');
      row.innerHTML = `
        <span><span class="hk-scope ${replaced ? 'hk-off' : 'hk-on'}" title="${tip}">${label}</span> <b>${hwKeyLabel(h.key)}</b> ${describeHotkey(h)}</span>
        <span>
          <span class="remove" style="color:#00E5FF" onclick="editHotkey('${scope}','${listType}',${i})">✎</span>
          <span class="remove" onclick="removeHotkey('${scope}','${listType}',${i})">✕</span>
        </span>
      `;
      container.appendChild(row);
    });
  };

  addRows(dashboardData.hotkeys, 'global', 'hotkeys', I18N.t('web_hotkey_scope_global'));
  addRows(dashboardData.longHotkeys, 'global', 'longHotkeys', I18N.t('web_hotkey_scope_global_long'));
  addRows(page.hotkeys, 'page', 'hotkeys', I18N.t('web_hotkey_scope_page'));
  addRows(page.longHotkeys, 'page', 'longHotkeys', I18N.t('web_hotkey_scope_page_long'));

  if (!container.innerHTML.trim()) {
    container.innerHTML = `<div class="hint">${I18N.t('web_hotkey_none')}</div>`;
  } else {
    container.insertAdjacentHTML('beforeend', `<div class="hint hk-legend">${I18N.t('web_hotkey_legend')}</div>`);
  }

  updateHardwareKeyHighlights();
}

// Highlights the physical buttons on the remote-frame preview that already
// have a hotkey bound — either globally (works on every page) or for the
// currently active page/tab. Cyan glow = bound to a short press, amber glow
// = bound to a long press only; a button bound to both gets the cyan glow
// plus a small amber outline ring layered on top. Mirrors the real device's
// on-press glow (see .hw-btn:hover in styles.css) so it's obvious at a
// glance which buttons already do something, and updates automatically
// every time this runs (add/edit/remove hotkey, switch page, add/rename/
// remove a page — see the other renderHotkeysList() call sites).
function updateHardwareKeyHighlights() {
  const buttons = document.querySelectorAll('[data-hwkey]');
  if (!buttons.length) return;
  const page = dashboardData.pages[currentActivePage];
  const shortKeys = new Set(
    [...(dashboardData.hotkeys || []), ...((page && page.hotkeys) || [])].map((h) => h.key),
  );
  // The parent-navigation fallback also occupies a button on this page,
  // unless an explicit hotkey above already claims it (matches the app's
  // own MainActivity.rebindHotkeysForCurrentPage() precedence).
  if (page && page.parent) {
    const parentKey = (page.parentKey || 'BACK').toUpperCase();
    if (!shortKeys.has(parentKey)) shortKeys.add(parentKey);
  }
  const longKeys = new Set(
    [...(dashboardData.longHotkeys || []), ...((page && page.longHotkeys) || [])].map((h) => h.key),
  );

  buttons.forEach((el) => {
    const key = el.dataset.hwkey;
    el.classList.remove('hw-assigned', 'hw-assigned-long-only', 'hw-has-long');
    const hasShort = shortKeys.has(key);
    const hasLong = longKeys.has(key);
    if (hasShort && hasLong) el.classList.add('hw-assigned', 'hw-has-long');
    else if (hasShort) el.classList.add('hw-assigned');
    else if (hasLong) el.classList.add('hw-assigned-long-only');
  });
}

async function editHotkey(scope, listType, i) {
  const target = scope === 'global' ? dashboardData : dashboardData.pages[currentActivePage];
  const h = target[listType][i];
  editingHotkey = { scope, listType, i };

  document.getElementById('hkScope').value = scope;
  document.getElementById('hkType').value = listType;
  document.getElementById('hkKey').value = h.key;
  const adtv = androidTvHotkeyParts(h);
  const action = h.page ? 'page' : h.openOverlay ? 'openOverlay' : h.openCurrentActivityRoom ? 'openCurrentActivity' : h.stopActivity ? 'stopActivity' : h.activity ? 'startActivity' : h.event ? 'haEvent' : adtv ? 'androidTvCommand' : h.service === 'remote.send_command' ? 'remoteCommand' : h.service === 'astrion_appletv.send_command' ? 'appleTvCommand' : h.service ? 'service' : h.harmonyCommand ? 'harmonyCommand' : h.irDevice ? 'irCommand' : 'harmonyActivity';
  document.getElementById('hkAction').value = action;
  updateHotkeyActionInputs(action === 'appleTvCommand' ? { appleTvEntityId: h.entityId || '' } : adtv ? { androidTv: adtv.tv } : undefined);

  if (action === 'page') {
    document.getElementById('hkPage').value = h.page || '';
    document.getElementById('hkPagePopup').checked = h.pageMode === 'popup';
  } else if (action === 'openOverlay') {
    document.getElementById('hkOverlay').value = h.openOverlay || 'settings';
  } else if (action === 'openCurrentActivity') {
    document.getElementById('hkActivityRoom').value = h.openCurrentActivityRoom || '';
  } else if (action === 'startActivity') {
    const sel = document.getElementById('hkStartActivity');
    if (sel) sel.value = h.activity || '';
  } else if (action === 'stopActivity') {
    document.getElementById('hkStopRoom').value = h.stopActivity || '';
  } else if (action === 'haEvent') {
    document.getElementById('hkEventName').value = h.event || '';
  } else if (action === 'service') {
    document.getElementById('hkService').value = h.service || '';
    document.getElementById('hkEntityId').value = h.entityId || '';
    document.getElementById('hkData').value = h.data ? JSON.stringify(h.data) : '';
  } else if (action === 'remoteCommand') {
    document.getElementById('hkEntityId').value = h.entityId || '';
    document.getElementById('hkCommand').value = (h.data && h.data.command) || '';
    refreshRemoteCommandDatalist();
  } else if (action === 'appleTvCommand') {
    document.getElementById('hkAppleTvCommand').value = (h.data && h.data.command) || '';
  } else if (action === 'androidTvCommand') {
    document.getElementById('hkAndroidTvKind').value = adtv.kind;
    onHkAndroidTvKindChange();
    document.getElementById('hkAndroidTvValue').value = adtv.value;
  } else if (action === 'irCommand') {
    document.getElementById('hkIrDevice').value = h.irDevice || '';
    onHkIrDeviceChange();
    document.getElementById('hkIrCommand').value = h.irCommand || '';
  } else if (action === 'harmonyCommand') {
    if (harmonyAvailable) {
      const hubId = h.hub || (harmonyHubsList[0] && harmonyHubsList[0].localId) || '';
      document.getElementById('hkHub').value = hubId;
      await onHarmonyHubChange('command', 'hk');
      document.getElementById('hkDeviceSelect').value = h.harmonyDevice || '';
      onHarmonyDeviceChange('hk');
      document.getElementById('hkCommandSelect').value = h.harmonyCommand || '';
    } else {
      document.getElementById('hkDevice').value = h.harmonyDevice || '';
      document.getElementById('hkCommand').value = h.harmonyCommand || '';
    }
  } else if (action === 'harmonyActivity') {
    if (harmonyAvailable) {
      const hubId = h.hub || (harmonyHubsList[0] && harmonyHubsList[0].localId) || '';
      document.getElementById('hkHub').value = hubId;
      await onHarmonyHubChange('activity', 'hk');
      document.getElementById('hkActivitySelect').value = h.harmonyActivity || '';
    } else {
      document.getElementById('hkActivityId').value = h.harmonyActivity || '';
    }
  }

  document.getElementById('addHotkeyBtn').innerText = I18N.t('web_save_changes');
  document.getElementById('cancelHotkeyEditBtn').style.display = 'inline-block';
}

function removeHotkey(scope, listType, i) {
  const target = scope === 'global' ? dashboardData : dashboardData.pages[currentActivePage];
  target[listType].splice(i, 1);
  if (editingHotkey && editingHotkey.scope === scope && editingHotkey.listType === listType && editingHotkey.i === i) {
    cancelHotkeyEdit();
  }
  renderHotkeysList(); renderPreview(); updateJsonOutput();
}

function cancelHotkeyEdit() {
  editingHotkey = null;
  document.getElementById('addHotkeyBtn').innerText = I18N.t('web_hotkey_add');
  document.getElementById('cancelHotkeyEditBtn').style.display = 'none';
}

function addHotkey() {
  const scope = document.getElementById('hkScope').value;
  const hkType = document.getElementById('hkType').value;
  const key = document.getElementById('hkKey').value;
  const action = document.getElementById('hkAction').value;

  let hkObj = { key };
  if (action === 'page') {
    const selected = document.getElementById('hkPage').value.trim();
    const shortcut = HK_PAGE_OVERLAY_SHORTCUTS.find(s => s.value === selected);
    if (shortcut) {
      hkObj.openOverlay = shortcut.overlay;
    } else {
      hkObj.page = selected;
      if (document.getElementById('hkPagePopup')?.checked) hkObj.pageMode = 'popup';
    }
  } else if (action === 'openOverlay') {
    hkObj.openOverlay = document.getElementById('hkOverlay').value;
  } else if (action === 'openCurrentActivity') {
    const room = document.getElementById('hkActivityRoom').value.trim();
    if (!room) { alert(I18N.t('web_hotkey_err_room')); return; }
    hkObj.openCurrentActivityRoom = room;
  } else if (action === 'startActivity') {
    const id = document.getElementById('hkStartActivity')?.value || '';
    if (!id) { alert(I18N.t('web_hotkey_err_activity')); return; }
    hkObj.activity = id;
  } else if (action === 'stopActivity') {
    const room = document.getElementById('hkStopRoom').value.trim();
    if (!room) { alert(I18N.t('web_hotkey_err_room_or_all')); return; }
    hkObj.stopActivity = room;
  } else if (action === 'haEvent') {
    const name = document.getElementById('hkEventName').value.trim();
    if (!name) { alert(I18N.t('web_hotkey_err_event')); return; }
    hkObj.event = name;
  } else if (action === 'service') {
    hkObj.service = document.getElementById('hkService').value.trim();
    const entityId = document.getElementById('hkEntityId').value.trim();
    if (entityId) hkObj.entityId = entityId;
    const rawData = document.getElementById('hkData').value.trim();
    if (rawData) {
      try { hkObj.data = JSON.parse(rawData); } catch (e) { alert(I18N.t('web_hotkey_err_json')); return; }
    }
  } else if (action === 'remoteCommand') {
    const entityId = document.getElementById('hkEntityId').value.trim();
    const command = document.getElementById('hkCommand').value.trim();
    if (!entityId || !command) { alert(I18N.t('web_hotkey_err_remote')); return; }
    hkObj.service = 'remote.send_command';
    hkObj.entityId = entityId;
    hkObj.data = { command };
  } else if (action === 'appleTvCommand') {
    const appleTv = document.getElementById('hkAppleTv').value.trim();
    const command = document.getElementById('hkAppleTvCommand').value.trim();
    if (!appleTv || !command) { alert(I18N.t('web_hotkey_err_atv')); return; }
    hkObj.service = 'astrion_appletv.send_command';
    hkObj.entityId = appleTv;
    hkObj.data = { command };
  } else if (action === 'androidTvCommand') {
    const tv = document.getElementById('hkAndroidTv').value.trim();
    const kind = document.getElementById('hkAndroidTvKind').value;
    const value = document.getElementById('hkAndroidTvValue').value.trim();
    if (!tv || !value) { alert(I18N.t('web_hotkey_err_adtv')); return; }
    if (kind === 'app') {
      hkObj.service = 'media_player.select_source';
      hkObj.entityId = tv;
      hkObj.data = { source: value };
    } else {
      hkObj.service = 'remote.send_command';
      hkObj.entityId = tv.replace(/^media_player\./, 'remote.');
      hkObj.data = { command: value };
    }
  } else if (action === 'irCommand') {
    const irDevice = document.getElementById('hkIrDevice').value;
    const irCommand = document.getElementById('hkIrCommand').value.trim();
    if (!irDevice || !irCommand) { alert(I18N.t('web_hotkey_err_ir')); return; }
    hkObj.irDevice = irDevice;
    hkObj.irCommand = irCommand;
  } else if (action === 'harmonyCommand') {
    if (harmonyAvailable) {
      const hub = document.getElementById('hkHub').value.trim();
      const device = document.getElementById('hkDeviceSelect').value.trim();
      const command = document.getElementById('hkCommandSelect').value.trim();
      if (!hub || !device || !command) { alert(I18N.t('web_hotkey_err_harmony_command')); return; }
      hkObj.hub = hub;
      hkObj.harmonyDevice = device;
      hkObj.harmonyCommand = command;
    } else {
      hkObj.harmonyDevice = document.getElementById('hkDevice').value.trim();
      hkObj.harmonyCommand = document.getElementById('hkCommand').value.trim();
    }
  } else if (action === 'harmonyActivity') {
    if (harmonyAvailable) {
      const hub = document.getElementById('hkHub').value.trim();
      const activityId = document.getElementById('hkActivitySelect').value.trim();
      if (!hub || !activityId) { alert(I18N.t('web_hotkey_err_harmony_activity')); return; }
      hkObj.hub = hub;
      hkObj.harmonyActivity = activityId;
    } else {
      hkObj.harmonyActivity = document.getElementById('hkActivityId').value.trim();
    }
  }

  if (editingHotkey) {
    const oldTarget = editingHotkey.scope === 'global' ? dashboardData : dashboardData.pages[currentActivePage];
    oldTarget[editingHotkey.listType].splice(editingHotkey.i, 1);
    cancelHotkeyEdit();
  }

  const target = scope === 'global' ? dashboardData : dashboardData.pages[currentActivePage];
  if (!target[hkType]) target[hkType] = [];
  target[hkType].push(hkObj);

  renderHotkeysList(); renderPreview(); updateJsonOutput();
}

// ---- Release badges (official + beta) --------------------------------------

// Release-badge and physical-key strings live in the shared i18n tables
// (keys web_release_* / web_hwkey_* — see js/i18n.js); applyUiI18n() lives
// in js/i18n.js too.
const RELEASE_KEYS = {
  stableUnavailable: 'web_release_stable_unavailable',
  installOfficial: 'web_release_install_official',
  installBeta: 'web_release_install_beta',
  installing: 'web_release_installing',
  installStarted: 'web_release_install_started',
  installFailed: 'web_release_install_failed',
  alsoBeta: 'web_release_also_beta'
};
const t = (key) => I18N.t(RELEASE_KEYS[key] || key);

/** "Menu (MAIN)": the key's name as printed on the remote, then its id — or just the id when they match. */
function hwKeyLabel(key) {
  const id = 'web_hwkey_' + String(key).toLowerCase();
  const title = I18N.strings[id] !== undefined ? I18N.t(id) : '';
  return title && title.toUpperCase() !== String(key).toUpperCase() ? `${title} (${key})` : key;
}

// 1.4.0: the row reads "Installed: 1.3.9-beta · Official: v1.3.0 (older) · Beta: 1.4.0-beta ⬆️ Install".
// A user took the old ✅ in front of the official release to mean "installed". Now each release
// says how it compares with what this remote runs, and only a newer one offers to install.
let installedVersion = null; // from the remote's /version; null outside the remote or before it answered

/** The release's name/tag, its version (x.y.z, beta or not) and its APK link; null when GitHub didn't answer. */
async function fetchRelease(url) {
  try {
    const res = await fetch(url);
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    const asset = (data.assets || []).find(a => a.name.endsWith('.apk'));
    const label = data.name || data.tag_name || '';
    return { label, version: parseVersion(label) || parseVersion(data.tag_name), apk: asset ? asset.browser_download_url : null };
  } catch (e) {
    console.log('Release fetch failed', e);
    return null;
  }
}

/** "v1.3.9-beta" → { nums: [1,3,9], beta: true }; null without an x.y.z in it. */
function parseVersion(text) {
  const m = /(\d+)\.(\d+)\.(\d+)(-beta)?/i.exec(String(text || ''));
  return m ? { nums: [+m[1], +m[2], +m[3]], beta: !!m[4] } : null;
}

/** <0: [a] is older than [b], 0: the same, >0: newer. A release beats its own beta. */
function compareVersions(a, b) {
  for (let i = 0; i < 3; i++) if (a.nums[i] !== b.nums[i]) return a.nums[i] - b.nums[i];
  return (a.beta ? 0 : 1) - (b.beta ? 0 : 1);
}

/** One release's badge: its name, then installed / newer (+ install button) / older, or its APK link outside the remote. */
function releaseBadgeHtml(prefix, release, installFn, installKey) {
  const name = `<span class="version-chip">${escapeHtmlText(release.label)}</span>`;
  const mine = installedVersion ? parseVersion(installedVersion) : null;
  if (!mine || !release.version || typeof deviceModeAvailable === 'undefined' || !deviceModeAvailable) {
    return `${prefix} ${name}` + (release.apk ? ` — <a href="${release.apk}">${I18N.t('web_release_download_apk')}</a>` : '');
  }
  const cmp = compareVersions(release.version, mine);
  if (cmp === 0) return `${prefix} ${name} ✅ ${I18N.t('web_release_is_installed')}`;
  // Same numbers, beta build installed (e.g. 1.4.0-beta vs v1.4.0): the same version, not an update (1.4.1).
  if (release.version.nums.join('.') === mine.nums.join('.')) {
    return `${prefix} ${name} ✅ ${I18N.t('web_release_same_beta')}`;
  }
  if (cmp < 0) return `${prefix} ${name} <span style="color:#888">${I18N.t('web_release_older')}</span>`;
  return `${prefix} ${name} ⬆️ <button type="button" onclick="${installFn}(this)" style="padding:4px 10px;font-size:0.8rem">${t(installKey)}</button>`;
}

function escapeHtmlText(v) {
  return String(v == null ? '' : v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** "📱 Installed: 1.4.0-beta", from the remote itself; then the releases are compared with it. */
async function loadInstalledVersion() {
  await I18N.ready;
  const badge = document.getElementById('installedBadge');
  try {
    const res = await fetch('/version');
    if (!res.ok) throw new Error('HTTP ' + res.status);
    installedVersion = (await res.json()).version || null;
  } catch (e) {
    installedVersion = null;
  }
  if (badge) {
    badge.innerHTML = installedVersion
      ? `📱 ${I18N.t('web_release_installed')} <span class="version-chip">${escapeHtmlText(installedVersion)}</span>`
      : '';
  }
  loadStableBadge();
  if (document.getElementById('betaToggle')?.checked) toggleBetaBadge();
}

async function loadStableBadge() {
  await I18N.ready;
  const badge = document.getElementById('stableBadge');
  if (!badge) return;
  badge.textContent = I18N.t('web_release_loading');
  const release = await fetchRelease('https://api.github.com/repos/dckiller51/astrion-custom-dashboard/releases/latest');
  badge.innerHTML = release
    ? releaseBadgeHtml(`🏷️ ${I18N.t('web_release_official')}`, release, 'installStableUpdate', 'installOfficial')
    : t('stableUnavailable');
}

async function toggleBetaBadge() {
  await I18N.ready;
  const on = document.getElementById('betaToggle').checked;
  const badge = document.getElementById('betaBadge');
  if (!on) { badge.style.display = 'none'; return; }
  badge.textContent = I18N.t('web_release_loading');
  badge.style.display = 'inline-block';
  const release = await fetchRelease('https://api.github.com/repos/dckiller51/astrion-custom-dashboard/releases/tags/dev-latest');
  badge.innerHTML = release
    ? releaseBadgeHtml(`🧪 ${I18N.t('web_release_beta')}`, release, 'installBetaUpdate', 'installBeta')
    : I18N.t('web_release_beta_unavailable');
}

async function installStableUpdate(btn) {
  const original = btn.textContent;
  btn.textContent = t('installing');
  btn.disabled = true;
  try {
    const res = await fetch('/install-update', { method: 'POST' });
    const text = await res.text();
    if (!res.ok) throw new Error(text || ('HTTP ' + res.status));
    showToast(t('installStarted'));
  } catch (e) {
    showToast(t('installFailed') + e.message, 'error');
    btn.textContent = original;
    btn.disabled = false;
  }
}

async function installBetaUpdate(btn) {
  await I18N.ready;
  const original = btn.textContent;
  btn.textContent = I18N.t('web_release_installing');
  btn.disabled = true;
  try {
    const res = await fetch('/install-beta-update', { method: 'POST' });
    const text = await res.text();
    if (!res.ok) throw new Error(text || ('HTTP ' + res.status));
    showToast(I18N.t('web_release_install_started'));
  } catch (e) {
    showToast(I18N.t('web_release_install_failed') + e.message, 'error');
    btn.textContent = original;
    btn.disabled = false;
  }
}
/**
 * <option>s for every room an Activity can run in — composed Activities, and "track": true tiles
 * and hotkeys — plus "*" (every room). Used by the "Off" (stopActivity) fields.
 */
function activityRoomOptionsHtml() {
  const rooms = new Set();
  (dashboardData.activities || []).forEach(a => { if (a.room) rooms.add(a.room); });
  const scanHotkeys = list => (list || []).forEach(h => { if (h.track && h.room) rooms.add(h.room); });
  scanHotkeys(dashboardData.hotkeys);
  scanHotkeys(dashboardData.longHotkeys);
  (dashboardData.pages || []).forEach(p => {
    scanHotkeys(p.hotkeys);
    scanHotkeys(p.longHotkeys);
    (p.cards || []).forEach(c => {
      const o = c.options || {};
      [...(o.scenes || []), ...(o.buttons || [])].forEach(t => { if (t && t.track && t.room) rooms.add(t.room); });
    });
  });
  const esc = v => String(v).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
  return `<option value="*">${I18N.t('web_every_room')}</option>` + [...rooms].sort().map(r => `<option value="${esc(r)}">`).join('');
}
