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
  { id: 'Up', label: 'Up' },
  { id: 'Down', label: 'Down' },
  { id: 'Left', label: 'Left' },
  { id: 'Right', label: 'Right' },
  { id: 'Select', label: 'Select' },
  { id: 'SelectHold', label: 'Select (held — context menu)' },
  { id: 'SeekBackward', label: 'Back 10 s' },
  { id: 'SeekForward', label: 'Forward 10 s' },
  { id: 'Rewind', label: 'Rewind (press again to resume)' },
  { id: 'FastForward', label: 'Fast forward (press again to resume)' },
  { id: 'PlayPause', label: 'Play / Pause' },
  { id: 'Play', label: 'Play' },
  { id: 'Pause', label: 'Pause' },
  { id: 'Next', label: 'Next track' },
  { id: 'Previous', label: 'Previous track' },
  { id: 'Wake', label: 'Wake' },
  { id: 'Sleep', label: 'Sleep' },
  { id: 'Menu', label: 'Menu' },
  { id: 'Home', label: 'Home' },
  { id: 'Siri', label: 'Siri' },
  { id: 'Screensaver', label: 'Screensaver' },
  { id: 'Guide', label: 'Guide' },
  { id: 'VolumeUp', label: 'Volume +' },
  { id: 'VolumeDown', label: 'Volume −' },
  { id: 'ControlCenter', label: 'Control Center' },
  { id: 'ChannelUp', label: 'Channel +' },
  { id: 'ChannelDown', label: 'Channel −' }
];

/** Populates the Apple TV picker for the hotkey form with every paired Apple TV, selecting [selectedEntityId] if given. */
async function populateHkAppleTvSelect(selectedEntityId) {
  const select = document.getElementById('hkAppleTv');
  if (!select) return;
  const tvs = await loadNativeAppleTvs();
  select.innerHTML = tvs.length
    ? tvs.map(tv => `<option value="${tv.entityId}">${tv.name} (${tv.entityId})</option>`).join('')
    : '<option value="">— no Apple TV paired yet —</option>';
  if (selectedEntityId) {
    if (![...select.options].some(opt => opt.value === selectedEntityId)) {
      const missing = document.createElement('option');
      missing.value = selectedEntityId;
      missing.textContent = selectedEntityId + ' (not paired)';
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
  { value: '__overlay_settings__', label: 'Settings', overlay: 'settings' },
  { value: '__overlay_activities__', label: 'Active Activities', overlay: 'activities' }
];

function populateHkPageSelect() {
  const select = document.getElementById('hkPage');
  const pageOptions = (dashboardData.pages || [])
    .map(p => `<option value="${p.name.replace(/"/g, '&quot;')}">${p.name}</option>`)
    .join('');
  const shortcutOptions = HK_PAGE_OVERLAY_SHORTCUTS
    .map(s => `<option value="${s.value}">${s.label}</option>`)
    .join('');
  select.innerHTML =
    `<optgroup label="Pages">${pageOptions}</optgroup>` +
    `<optgroup label="Shortcuts">${shortcutOptions}</optgroup>`;
}

function updateHotkeyActionInputs(preselect) {
  const action = document.getElementById('hkAction').value;
  const container = document.getElementById('dynamicHotkeyInputs');
  if (action === 'page') {
    container.innerHTML = `<label>Target page name</label><select id="hkPage"></select>`;
    populateHkPageSelect();
  } else if (action === 'openOverlay') {
    container.innerHTML = `
      <label>Overlay</label>
      <select id="hkOverlay">
        <option value="settings">Settings</option>
        <option value="activities">Active Activities (same as swipe down from the top bar)</option>
      </select>
    `;
  } else if (action === 'openCurrentActivity') {
    container.innerHTML = `
      <label>Room</label><input type="text" id="hkActivityRoom" placeholder="e.g., Living Room">
      <div class="hint" style="margin-top:4px">Jumps straight to the page of whichever Activity is currently active in this room — no picker. Does nothing if that room has nothing active right now. Must match a room name used by a tracked Activity (a composed Activity, or a scene_grid tile / hotkey with "track": true) exactly.</div>
    `;
  } else if (action === 'service') {
    container.innerHTML = `
      <label>Service (domain.service)</label><input type="text" id="hkService" placeholder="e.g., light.toggle">
      <label>Entity ID (optional)</label><input type="text" id="hkEntityId" placeholder="e.g., light.living_room">
      <label>Extra data (optional, JSON)</label><input type="text" id="hkData" placeholder='{"brightness": 255}'>
    `;
  } else if (action === 'remoteCommand') {
    container.innerHTML = `
      <label>Remote entity</label><input type="text" id="hkEntityId" placeholder="e.g., remote.family_room" oninput="refreshRemoteCommandDatalist()">
      <label>Command</label>
      <input type="text" id="hkCommand" placeholder="e.g., VOLUME_UP" list="hkCommandList">
      <datalist id="hkCommandList"></datalist>
      <div class="hint">Pick a <code>remote.*</code> entity. Command suggestions come from the entity's <code>commands_list</code> attribute when present, otherwise from the built-in Android TV keymap.</div>
    `;
    attachEntityAutocomplete(document.getElementById('hkEntityId'), 'remote');
    refreshRemoteCommandDatalist();
  } else if (action === 'appleTvCommand') {
    container.innerHTML = `
      <label>Apple TV</label>
      <select id="hkAppleTv"></select>
      <label>Command</label>
      <select id="hkAppleTvCommand">
        ${ATV_ALL_COMMANDS.map(c => `<option value="${c.id}">${c.label}</option>`).join('')}
      </select>
    `;
    populateHkAppleTvSelect(preselect && preselect.appleTvEntityId);
  } else if (action === 'harmonyCommand') {
    if (harmonyAvailable) {
      renderHarmonyHubSelect(container, 'command', 'hk');
    } else {
      container.innerHTML = `
        <label>Harmony device ID</label><input type="text" id="hkDevice" placeholder="e.g., 62845789">
        <label>Harmony command</label><input type="text" id="hkCommand" placeholder="e.g., VolumeUp">
      `;
    }
  } else if (action === 'harmonyActivity') {
    if (harmonyAvailable) {
      renderHarmonyHubSelect(container, 'activity', 'hk');
    } else {
      container.innerHTML = `<label>Harmony activity ID</label><input type="text" id="hkActivityId" placeholder="e.g., 12345678 (or -1 for Power Off)">`;
    }
  }
}

function describeHotkey(h) {
  if (h.page) return `→ page "${h.page}"`;
  if (h.openOverlay) return `→ open ${h.openOverlay === 'activities' ? 'Active Activities' : 'Settings'}`;
  if (h.openCurrentActivityRoom) return `→ current Activity in "${h.openCurrentActivityRoom}"`;
  if (h.service === 'remote.send_command') {
    const cmd = h.data && h.data.command ? h.data.command : '?';
    return `→ remote ${h.entityId || '?'} / ${cmd}`;
  }
  if (h.service === 'astrion_appletv.send_command') {
    const cmd = h.data && h.data.command ? h.data.command : '?';
    return `→ Apple TV ${h.entityId || '?'} / ${cmd}`;
  }
  if (h.service) return `→ ${h.service}${h.entityId ? ' (' + h.entityId + ')' : ''}`;
  if (h.harmonyCommand) return `→ Harmony ${h.harmonyDevice || '?'} / ${h.harmonyCommand}`;
  if (h.harmonyActivity) return `→ Harmony activity ${h.harmonyActivity}`;
  return '';
}

function renderHotkeysList() {
  const container = document.getElementById('hotkeysList');
  if (!container) return;
  container.innerHTML = '';
  const page = dashboardData.pages[currentActivePage];

  const addRows = (list, scope, listType, label) => {
    (list || []).forEach((h, i) => {
      const row = document.createElement('div');
      row.className = 'list-item';
      row.innerHTML = `
        <span>[${label}] <b>${h.key}</b> ${describeHotkey(h)}</span>
        <span>
          <span class="remove" style="color:#00E5FF" onclick="editHotkey('${scope}','${listType}',${i})">✎</span>
          <span class="remove" onclick="removeHotkey('${scope}','${listType}',${i})">✕</span>
        </span>
      `;
      container.appendChild(row);
    });
  };

  addRows(dashboardData.hotkeys, 'global', 'hotkeys', 'Global');
  addRows(dashboardData.longHotkeys, 'global', 'longHotkeys', 'Global, long');
  addRows(page.hotkeys, 'page', 'hotkeys', 'Page');
  addRows(page.longHotkeys, 'page', 'longHotkeys', 'Page, long');

  if (!container.innerHTML.trim()) container.innerHTML = '<div class="hint">No hotkeys yet.</div>';

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
  const action = h.page ? 'page' : h.openOverlay ? 'openOverlay' : h.openCurrentActivityRoom ? 'openCurrentActivity' : h.service === 'remote.send_command' ? 'remoteCommand' : h.service === 'astrion_appletv.send_command' ? 'appleTvCommand' : h.service ? 'service' : h.harmonyCommand ? 'harmonyCommand' : 'harmonyActivity';
  document.getElementById('hkAction').value = action;
  updateHotkeyActionInputs(action === 'appleTvCommand' ? { appleTvEntityId: h.entityId || '' } : undefined);

  if (action === 'page') {
    document.getElementById('hkPage').value = h.page || '';
  } else if (action === 'openOverlay') {
    document.getElementById('hkOverlay').value = h.openOverlay || 'settings';
  } else if (action === 'openCurrentActivity') {
    document.getElementById('hkActivityRoom').value = h.openCurrentActivityRoom || '';
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

  document.getElementById('addHotkeyBtn').innerText = 'Save changes';
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
  document.getElementById('addHotkeyBtn').innerText = 'Add hotkey';
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
    }
  } else if (action === 'openOverlay') {
    hkObj.openOverlay = document.getElementById('hkOverlay').value;
  } else if (action === 'openCurrentActivity') {
    const room = document.getElementById('hkActivityRoom').value.trim();
    if (!room) { alert('Enter a room name.'); return; }
    hkObj.openCurrentActivityRoom = room;
  } else if (action === 'service') {
    hkObj.service = document.getElementById('hkService').value.trim();
    const entityId = document.getElementById('hkEntityId').value.trim();
    if (entityId) hkObj.entityId = entityId;
    const rawData = document.getElementById('hkData').value.trim();
    if (rawData) {
      try { hkObj.data = JSON.parse(rawData); } catch (e) { alert('Extra data must be valid JSON'); return; }
    }
  } else if (action === 'remoteCommand') {
    const entityId = document.getElementById('hkEntityId').value.trim();
    const command = document.getElementById('hkCommand').value.trim();
    if (!entityId || !command) { alert('Pick a remote entity and a command.'); return; }
    hkObj.service = 'remote.send_command';
    hkObj.entityId = entityId;
    hkObj.data = { command };
  } else if (action === 'appleTvCommand') {
    const appleTv = document.getElementById('hkAppleTv').value.trim();
    const command = document.getElementById('hkAppleTvCommand').value.trim();
    if (!appleTv || !command) { alert('Pick an Apple TV and a command.'); return; }
    hkObj.service = 'astrion_appletv.send_command';
    hkObj.entityId = appleTv;
    hkObj.data = { command };
  } else if (action === 'harmonyCommand') {
    if (harmonyAvailable) {
      const hub = document.getElementById('hkHub').value.trim();
      const device = document.getElementById('hkDeviceSelect').value.trim();
      const command = document.getElementById('hkCommandSelect').value.trim();
      if (!hub || !device || !command) { alert('Pick a hub, a device, and a command.'); return; }
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
      if (!hub || !activityId) { alert('Pick a hub and an activity.'); return; }
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

const RELEASE_STRINGS = {
  en: {
    loading: 'Loading…',
    stableUnavailable: 'Official release unavailable',
    betaUnavailable: 'Beta unavailable',
    downloadApk: 'download the APK',
    installOfficial: 'Install this official update',
    installBeta: 'Install this beta update',
    installing: 'Installing…',
    installStarted: 'Install launched on the remote.',
    installFailed: 'Install failed: ',
    alsoBeta: 'Also the beta (dev)'
  },
  fr: {
    loading: 'Chargement…',
    stableUnavailable: 'Version officielle indisponible',
    betaUnavailable: 'Bêta indisponible',
    downloadApk: "télécharger l'APK",
    installOfficial: 'Installer cette version officielle',
    installBeta: 'Installer cette bêta',
    installing: 'Installation…',
    installStarted: 'Installation lancée sur la télécommande.',
    installFailed: "Échec de l'installation : ",
    alsoBeta: 'Aussi la bêta (dev)'
  }
};

const HW_KEY_TITLES = {
  en: {
    BACK: 'Back', HOME: 'Home', POWER: 'Power', VOLUME_UP: 'Volume +',
    PAGE_UP: 'Previous page', VOLUME_DOWN: 'Volume -', PAGE_DOWN: 'Next page',
    MUTE: 'Mute', VOICE: 'Microphone', MAIN: 'Menu', REWIND: 'Rewind',
    PLAY: 'Play / Pause', STOP: 'Stop', FASTFORWARD: 'Fast-forward'
  },
  fr: {
    BACK: 'Retour', HOME: 'Accueil', POWER: 'Alimentation', VOLUME_UP: 'Volume +',
    PAGE_UP: 'Page précédente', VOLUME_DOWN: 'Volume -', PAGE_DOWN: 'Page suivante',
    MUTE: 'Muet', VOICE: 'Microphone', MAIN: 'Menu', REWIND: 'Retour rapide',
    PLAY: 'Lecture / Pause', STOP: 'Arrêt', FASTFORWARD: 'Avance rapide'
  }
};

const uiLang = ((navigator.language || 'en').split('-')[0]).startsWith('fr') ? 'fr' : 'en';
const t = (key) => RELEASE_STRINGS[uiLang][key];

function applyUiI18n() {
  const betaLabel = document.getElementById('betaToggleLabel');
  if (betaLabel) betaLabel.textContent = t('alsoBeta');
  document.querySelectorAll('button[data-hwkey]').forEach(btn => {
    const title = HW_KEY_TITLES[uiLang][btn.dataset.hwkey];
    if (title) btn.title = title;
  });
}

async function fetchReleaseBadge(url, icon) {
  try {
    const res = await fetch(url);
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    const asset = (data.assets || []).find(a => a.name.endsWith('.apk'));
    return `${icon} ${data.name || data.tag_name}` +
      (asset ? ` — <a href="${asset.browser_download_url}">${t('downloadApk')}</a>` : '');
  } catch (e) {
    console.log('Release fetch failed', e);
    return null;
  }
}

async function loadStableBadge() {
  const badge = document.getElementById('stableBadge');
  if (!badge) return;
  badge.textContent = t('loading');
  const html = await fetchReleaseBadge(
    'https://api.github.com/repos/dckiller51/astrion-custom-dashboard/releases/latest', '✅'
  );
  if (!html) {
    badge.innerHTML = t('stableUnavailable');
    return;
  }
  if (typeof deviceModeAvailable !== 'undefined' && deviceModeAvailable) {
    const label = html.replace(/ — <a[^>]*>.*?<\/a>/, '');
    badge.innerHTML = `${label} — <button type="button" onclick="installStableUpdate(this)" style="padding:4px 10px;font-size:0.8rem">${t('installOfficial')}</button>`;
  } else {
    badge.innerHTML = html;
  }
}

async function toggleBetaBadge() {
  const on = document.getElementById('betaToggle').checked;
  const badge = document.getElementById('betaBadge');
  if (!on) { badge.style.display = 'none'; return; }
  badge.textContent = t('loading');
  badge.style.display = 'inline-block';
  const html = await fetchReleaseBadge(
    'https://api.github.com/repos/dckiller51/astrion-custom-dashboard/releases/tags/dev-latest', '🧪'
  );
  if (!html) {
    badge.innerHTML = t('betaUnavailable');
    return;
  }
  if (typeof deviceModeAvailable !== 'undefined' && deviceModeAvailable) {
    const label = html.replace(/ — <a[^>]*>.*?<\/a>/, '');
    badge.innerHTML = `${label} — <button type="button" onclick="installBetaUpdate(this)" style="padding:4px 10px;font-size:0.8rem">${t('installBeta')}</button>`;
  } else {
    badge.innerHTML = html;
  }
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
  const original = btn.textContent;
  btn.textContent = t('installing');
  btn.disabled = true;
  try {
    const res = await fetch('/install-beta-update', { method: 'POST' });
    const text = await res.text();
    if (!res.ok) throw new Error(text || ('HTTP ' + res.status));
    showToast(t('installStarted'));
  } catch (e) {
    showToast(t('installFailed') + e.message, 'error');
    btn.textContent = original;
    btn.disabled = false;
  }
}