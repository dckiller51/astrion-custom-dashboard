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
  // A Zidoo / Eversolo added in Astrion too (1.4.9): its remote.zidoo_… / media_player.zidoo_…
  // take the same Android key names and its apps by name.
  if (h.service === 'remote.send_command' && /^remote\.(androidtv|zidoo|lgtv|sky|samsung|denon)_/.test(h.entityId || '')) {
    return { tv: h.entityId.replace(/^remote\./, 'media_player.'), kind: 'key', value: (h.data && h.data.command) || '' };
  }
  if (h.service === 'media_player.select_source' && /^media_player\.(androidtv|zidoo|lgtv|sky|samsung|denon)_/.test(h.entityId || '')) {
    return { tv: h.entityId, kind: 'app', value: (h.data && h.data.source) || '' };
  }
  return null;
}

async function loadNativeAndroidTvs() {
  try {
    const res = await fetch('/devices-config');
    const data = await res.json();
    // The Zidoos / Eversolos added in Astrion (1.4.9) answer the same keys and apps.
    // And the LG TVs (1.5.2): the same Android key names, their apps and inputs by name.
    // And the Denon / Marantz receivers (1.5.4): their own commands (MVUP, MSMOVIE…), an input by name.
    // And the Samsung TVs (1.5.3): the same Android key names and Samsung's KEY_… codes, their apps by name.
    // And the Sky boxes (1.5.3): the same Android key names and Sky's own, a channel by name or number.
    return [...(Array.isArray(data.androidTvs) ? data.androidTvs : []), ...(Array.isArray(data.zidoos) ? data.zidoos : []),
      ...(Array.isArray(data.lgtvs) ? data.lgtvs : []), ...(Array.isArray(data.skys) ? data.skys : []),
      ...(Array.isArray(data.samsungs) ? data.samsungs : []), ...(Array.isArray(data.denons) ? data.denons : [])];
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

// An Eversolo's own commands (1.5.0, from Eversolo's "TCP" command list for the DMP-A6): input,
// output, its screen. Any Key.* name goes to a Zidoo / Eversolo as is; these are offered with a
// readable label for a remote / media player of one added in Astrion.
const EVERSOLO_COMMANDS = [
  ['Key.DAC.XMOS', 'web_evs_in_internal'], ['Key.DAC.BT', 'web_evs_in_bt'], ['Key.DAC.USB', 'web_evs_in_usb'],
  ['Key.DAC.SPDIF', 'web_evs_in_optical'], ['Key.DAC.COA', 'web_evs_in_coax'], ['Key.DAC.Switch', 'web_evs_in_next'],
  ['Key.OUT.XLR', 'web_evs_out_xlr'], ['Key.OUT.RCA', 'web_evs_out_rca'], ['Key.OUT.XLRRCA', 'web_evs_out_xlrrca'],
  ['Key.OUT.HDMI', 'web_evs_out_hdmi'], ['Key.OUT.SPDIF', 'web_evs_out_spdif'], ['Key.OUT.USB', 'web_evs_out_usb'],
  ['Key.Screen.ON', 'web_evs_screen_on'], ['Key.Screen.OFF', 'web_evs_screen_off'], ['Key.Screen.Display', 'web_evs_screen_display'],
  ['Key.Repeat', 'web_evs_repeat'], ['Key.Info', 'web_evs_info'], ['Key.Reboot', 'web_evs_reboot']
];

// An LG TV's own buttons (1.5.2), beyond the Android names every TV takes: sent as they are to the
// remote.lgtv_… of an LG TV added in Astrion, offered here with what they do.
const LG_COMMANDS = [
  ['INPUT_HUB', 'web_lg_input_hub'], ['QMENU', 'web_lg_qmenu'], ['MENU', 'web_lg_menu'], ['EXIT', 'web_lg_exit'],
  ['GUIDE', 'web_lg_guide'], ['LIST', 'web_lg_list'], ['INFO', 'web_lg_info'], ['RECENT', 'web_lg_recent'],
  ['MYAPPS', 'web_lg_myapps'], ['CHANNELUP', 'web_lg_channel_up'], ['CHANNELDOWN', 'web_lg_channel_down'],
  ['CC', 'web_lg_cc'], ['SAP', 'web_lg_sap'], ['AD', 'web_lg_ad'], ['ASPECT_RATIO', 'web_lg_aspect'],
  ['TELETEXT', 'web_lg_teletext'], ['RECORD', 'web_lg_record'], ['SCREEN_REMOTE', 'web_lg_screen_remote'],
  ['LIVE_ZOOM', 'web_lg_live_zoom'], ['MAGNIFIER_ZOOM', 'web_lg_magnifier'], ['ASTERISK', 'web_lg_asterisk'],
  ['DASH', 'web_lg_dash'], ['NETFLIX', 'web_lg_netflix'], ['AMAZON', 'web_lg_amazon']
];

// A Sky box's own keys (1.5.3), beyond the Android names every TV takes: sent as they are to the
// remote.sky_… of a Sky box added in Astrion, offered here with what they do.
const SKY_COMMANDS = [
  ['sky', 'web_sky_sky'], ['home', 'web_sky_home'], ['tvguide', 'web_sky_tvguide'], ['search', 'web_sky_search'],
  ['sidebar', 'web_sky_sidebar'], ['i', 'web_sky_i'], ['help', 'web_sky_help'], ['text', 'web_sky_text'],
  ['boxoffice', 'web_sky_boxoffice'], ['dismiss', 'web_sky_dismiss'], ['record', 'web_sky_record'],
  ['channelup', 'web_lg_channel_up'], ['channeldown', 'web_lg_channel_down']
];

/** The Sky keys as <option>s (value: the key, label: what it does); '' for any other entity. */
function skyCommandOptions(entityId) {
  if (entityId && !/\.sky_/i.test(entityId)) return '';
  return SKY_COMMANDS.map(([key, label]) => `<option value="${key}" label="${escapeAttr('Sky · ' + I18N.t(label))}"></option>`).join('');
}

// A Denon / Marantz receiver's own commands (1.5.4), sent as they are to the remote.denon_… of a
// receiver added in Astrion (any of its commands works), offered here with what they do.
const DENON_COMMANDS = [
  ['ZMON', 'web_denon_pwon'], ['ZMOFF', 'web_denon_pwstandby'], ['PWON', 'web_denon_pw_all_on'], ['PWSTANDBY', 'web_denon_pw_all_off'], ['MVUP', 'web_denon_mvup'], ['MVDOWN', 'web_denon_mvdown'],
  ['MUON', 'web_denon_muon'], ['MUOFF', 'web_denon_muoff'], ['MSMOVIE', 'web_denon_msmovie'], ['MSMUSIC', 'web_denon_msmusic'],
  ['MSGAME', 'web_denon_msgame'], ['MSDIRECT', 'web_denon_msdirect'], ['MSPURE DIRECT', 'web_denon_mspure'], ['MSSTEREO', 'web_denon_msstereo'],
  ['MSAUTO', 'web_denon_msauto'], ['PSDYNEQ ON', 'web_denon_dyneq_on'], ['PSDYNEQ OFF', 'web_denon_dyneq_off'],
  ['PSDIL ON', 'web_denon_dialog_on'], ['PSDIL OFF', 'web_denon_dialog_off'], ['MNMEN ON', 'web_denon_menu'], ['MNOPT', 'web_denon_option'],
  ['MNINF', 'web_denon_info'], ['SIGAME', 'web_denon_sigame'], ['SITV', 'web_denon_sitv'], ['SIBD', 'web_denon_sibd'], ['SIBT', 'web_denon_sibt']
];

/** The receiver's commands as <option>s (value: the command, label: what it does); '' for any other entity. */
function denonCommandOptions(entityId) {
  if (entityId && !/\.denon_/i.test(entityId)) return '';
  return DENON_COMMANDS.map(([cmd, label]) => `<option value="${escapeAttr(cmd)}" label="${escapeAttr('Denon · ' + I18N.t(label))}"></option>`).join('');
}

// A Samsung TV's sources and the apps it opens by name (1.5.3), for the hotkeys' app field when the
// page doesn't know its list: as SamsungEntityMapper.SOURCES and SamsungProtocol.KNOWN_APPS.
const SAMSUNG_SOURCES = ['TV', 'HDMI', 'Netflix', 'YouTube', 'Prime Video', 'Disney+', 'Apple TV', 'Max', 'Paramount+', 'Plex',
  'Spotify', 'Deezer', 'Apple Music', 'Amazon Music', 'YouTube Music', 'Tidal', 'Twitch', 'DAZN', 'Crunchyroll', 'Pluto TV',
  'Xbox', 'GeForce NOW', 'Steam Link', 'Internet'];

// A Samsung TV's own keys (1.5.3), beyond the Android names every TV takes: sent as they are to the
// remote.samsung_… of a Samsung TV added in Astrion (any KEY_… code works), offered here with what they do.
const SAMSUNG_COMMANDS = [
  ['KEY_SOURCE', 'web_samsung_source'], ['KEY_HDMI', 'web_samsung_hdmi'], ['KEY_HDMI1', 'web_samsung_hdmi1'],
  ['KEY_HDMI2', 'web_samsung_hdmi2'], ['KEY_HDMI3', 'web_samsung_hdmi3'], ['KEY_HDMI4', 'web_samsung_hdmi4'],
  ['KEY_TV', 'web_samsung_tv'], ['KEY_MENU', 'web_samsung_menu'], ['KEY_TOOLS', 'web_samsung_tools'],
  ['KEY_GUIDE', 'web_samsung_guide'], ['KEY_CH_LIST', 'web_samsung_ch_list'], ['KEY_PRECH', 'web_samsung_prech'],
  ['KEY_CAPTION', 'web_samsung_caption'], ['KEY_AD', 'web_samsung_ad'], ['KEY_PICTURE_SIZE', 'web_samsung_picture_size'],
  ['KEY_AMBIENT', 'web_samsung_ambient'], ['KEY_E_MANUAL', 'web_samsung_e_manual'], ['KEY_EXIT', 'web_samsung_exit']
];

/** The Samsung keys as <option>s (value: the key, label: what it does); '' for any other entity. */
function samsungCommandOptions(entityId) {
  if (entityId && !/\.samsung_/i.test(entityId)) return '';
  return SAMSUNG_COMMANDS.map(([key, label]) => `<option value="${key}" label="${escapeAttr('Samsung · ' + I18N.t(label))}"></option>`).join('');
}

/** The LG buttons as <option>s (value: the button, label: what it does); '' for any other entity. */
function lgTvCommandOptions(entityId) {
  if (entityId && !/lgtv/i.test(entityId)) return '';
  return LG_COMMANDS.map(([key, label]) => `<option value="${key}" label="${escapeAttr('LG · ' + I18N.t(label))}"></option>`).join('');
}

/** The Eversolo commands as <option>s (value: the key, label: what it does); '' for any other entity. */
function eversoloCommandOptions(entityId) {
  if (entityId && !/zidoo|eversolo/i.test(entityId)) return '';
  return EVERSOLO_COMMANDS.map(([key, label]) => `<option value="${key}" label="${escapeAttr('Eversolo · ' + I18N.t(label))}"></option>`).join('');
}

/** Key names or app names as suggestions, depending on the chosen kind. */
function onHkAndroidTvKindChange() {
  const kind = document.getElementById('hkAndroidTvKind').value;
  const input = document.getElementById('hkAndroidTvValue');
  const list = document.getElementById('hkAndroidTvValues');
  const known = typeof TV_KNOWN_APPS !== 'undefined' ? TV_KNOWN_APPS : [];
  // The device's own apps first (a Zidoo has no Netflix), as its media player publishes them.
  const tv = (document.getElementById('hkAndroidTv') || {}).value || '';
  const attrs = (typeof haStates !== 'undefined' && haStates && haStates[tv] && haStates[tv].attributes) || {};
  let own = Array.isArray(attrs.source_list) ? attrs.source_list.map(s => [String(s), String(s)]) : [];
  // A Samsung TV the page can't see yet, or one that hasn't found its apps (1.5.3): its sources and the apps it knows by name.
  if (/\.samsung_/.test(tv) && own.length <= 2) {
    own = [...new Set([...own.map(([n]) => n), ...SAMSUNG_SOURCES])].map(n => [n, n]);
  }
  const apps = /^media_player\.(zidoo|lgtv|sky|samsung|denon)_/.test(tv) ? own : [...own, ...known.filter(([n]) => !own.some(([o]) => o === n))];
  list.innerHTML = kind === 'app'
    ? apps.map(([n, p]) => `<option value="${escapeAttr(p)}">${escapeAttr(n)}</option>`).join('')
    : ANDROID_TV_COMMANDS.map(c => `<option value="${c}">`).join('') + (/zidoo|eversolo/i.test(tv) ? eversoloCommandOptions(tv) : '') +
      (/lgtv/i.test(tv) ? lgTvCommandOptions(tv) : '') + (/\.sky_/.test(tv) ? skyCommandOptions(tv) : '') +
      (/\.samsung_/.test(tv) ? samsungCommandOptions(tv) : '') + (/\.denon_/.test(tv) ? denonCommandOptions(tv) : '');
  // A Sky box (1.5.3): a channel, by its number or name, instead of an app.
  const sky = /\.sky_/.test(tv);
  const denon = /\.denon_/.test(tv);
  input.placeholder = kind === 'app' ? (sky ? '101' : denon ? 'GAME' : /\.(samsung|lgtv)_/.test(tv) ? 'Netflix' : 'com.canal.android.canal') : (denon ? 'MVUP' : 'CHANNEL_UP');
  // An LG TV (1.5.2) takes its own button names too, and an input as well as an app.
  const lg = /lgtv_/.test(tv);
  const samsung = /\.samsung_/.test(tv);
  const hint = denon ? (kind === 'app' ? 'web_hotkey_denon_app_hint' : 'web_hotkey_denon_key_hint')
    : sky ? (kind === 'app' ? 'web_hotkey_sky_app_hint' : 'web_hotkey_sky_key_hint')
    : samsung ? (kind === 'app' ? 'web_hotkey_samsung_app_hint' : 'web_hotkey_samsung_key_hint')
    : kind === 'app' ? (lg ? 'web_hotkey_lgtv_app_hint' : 'web_hotkey_adtv_app_hint') : (lg ? 'web_hotkey_lgtv_key_hint' : 'web_hotkey_adtv_key_hint');
  document.getElementById('hkAndroidTvHint').textContent = I18N.t(hint);
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
  dl.innerHTML = suggestions.map((c) => `<option value="${c}">`).join('') + (/zidoo|eversolo/i.test(entityId) ? eversoloCommandOptions(entityId) : '') +
    (/lgtv/i.test(entityId) ? lgTvCommandOptions(entityId) : '') + (/\.sky_/.test(entityId) ? skyCommandOptions(entityId) : '') +
    (/\.samsung_/.test(entityId) ? samsungCommandOptions(entityId) : '') + (/\.denon_/.test(entityId) ? denonCommandOptions(entityId) : '');
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
  // 1.5.2: one "Device" action, the device first (Apple TV, LG / Android TV / Zidoo / Eversolo,
  // IR, Harmony), then its keys or apps — the old per-kind actions fill the same fields.
  if (action === 'device') return renderHkDevicePicker(container, preselect && preselect.target);
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
      <select id="hkAndroidTv" onchange="onHkAndroidTvKindChange()"></select>
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
    populateHkAndroidTvSelect(preselect && preselect.androidTv).then(onHkAndroidTvKindChange);
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

// ---- "Device" action (1.5.2): the device first, then what it takes ---------------------------

/** The old actions a picked device stands for, by the prefix of its value. */
const HK_DEVICE_ACTIONS = { atv: 'appleTvCommand', tv: 'androidTvCommand', ir: 'irCommand', harmony: 'harmonyCommand' };

/** The action the form saves: the device's for "Device", else the one picked. */
function hkEffectiveAction() {
  const action = document.getElementById('hkAction').value;
  if (action !== 'device') return action;
  const target = (document.getElementById('hkTarget') || {}).value || '';
  return HK_DEVICE_ACTIONS[target.split(':')[0]] || '';
}

/** "TV LG", "Android TV", "Zidoo", "Eversolo": the kind of a TV / player added in Astrion. */
function hkTvKind(entityId) {
  if (/^media_player\.lgtv_/.test(entityId)) return I18N.t('web_hotkey_kind_lgtv');
  if (/^media_player\.sky_/.test(entityId)) return 'Sky';
  if (/^media_player\.samsung_/.test(entityId)) return I18N.t('web_hotkey_kind_samsung');
  if (/^media_player\.denon_/.test(entityId)) return 'Denon / Marantz';
  if (/^media_player\.androidtv_/.test(entityId)) return 'Android TV';
  const a = (typeof haStates !== 'undefined' && haStates && haStates[entityId] && haStates[entityId].attributes) || {};
  return a.manufacturer === 'Eversolo' ? 'Eversolo' : 'Zidoo';
}

/** The device list, grouped: TVs and players added in Astrion, IR devices, Harmony. [preselect]: a value to select. */
async function renderHkDevicePicker(container, preselect) {
  container.innerHTML = `
    <label>${I18N.t('web_hotkey_device_label')}</label>
    <select id="hkTarget" onchange="onHkTargetChange()"><option value="">${I18N.t('media_loading')}</option></select>
    <div id="hkTargetForm"></div>`;
  const [atvs, tvs] = await Promise.all([loadNativeAppleTvs(), loadNativeAndroidTvs()]);
  const select = document.getElementById('hkTarget');
  if (!select) return;
  const opt = (value, label) => `<option value="${escapeAttr(value)}">${escapeAttr(label)}</option>`;
  const group = (label, items) => items.length ? `<optgroup label="${escapeAttr(label)}">${items.join('')}</optgroup>` : '';
  const direct = atvs.map(t => opt('atv:' + t.entityId, `${t.name} · Apple TV`))
    .concat(tvs.map(t => opt('tv:' + t.entityId, `${t.name} · ${hkTvKind(t.entityId)}`)));
  const ir = (dashboardData.irDevices || []).map(d => opt('ir:' + d.id, `${d.name || d.id} · ${I18N.t('web_hotkey_kind_ir')}`));
  select.innerHTML = `<option value="">${I18N.t('web_hotkey_device_pick')}</option>` +
    group(I18N.t('web_hotkey_group_direct'), direct) +
    group(I18N.t('web_hotkey_group_ir'), ir) +
    group('Harmony', [opt('harmony', I18N.t('web_hotkey_harmony_hub_device'))]);
  if (preselect) {
    if (![...select.options].some(o => o.value === preselect)) select.insertAdjacentHTML('beforeend', opt(preselect, preselect.replace(/^\w+:/, '')));
    select.value = preselect;
  }
  await onHkTargetChange();
}

/** The picked device's own fields (the same ids the per-kind actions always had, the device fixed). */
async function onHkTargetChange() {
  const value = (document.getElementById('hkTarget') || {}).value || '';
  const box = document.getElementById('hkTargetForm');
  if (!box) return;
  const kind = value.split(':')[0];
  const id = value.slice(kind.length + 1);
  const fixed = selectId => `<select id="${selectId}" hidden><option value="${escapeAttr(id)}" selected></option></select>`;
  if (kind === 'atv') {
    box.innerHTML = fixed('hkAppleTv') + `
      <label>${I18N.t('web_hotkey_command_label')}</label>
      <select id="hkAppleTvCommand">${ATV_ALL_COMMANDS.map(c => `<option value="${c.id}">${I18N.t(c.labelKey)}</option>`).join('')}</select>`;
  } else if (kind === 'tv') {
    box.innerHTML = fixed('hkAndroidTv') + `
      <label>${I18N.t('web_hotkey_adtv_kind')}</label>
      <select id="hkAndroidTvKind" onchange="onHkAndroidTvKindChange()">
        <option value="key">${I18N.t('web_hotkey_adtv_kind_key')}</option>
        <option value="app">${I18N.t(/lgtv_/.test(id) ? 'web_hotkey_kind_app_or_input' : /\.sky_/.test(id) ? 'web_hotkey_kind_channel' : /\.denon_/.test(id) ? 'web_hotkey_kind_input' : 'web_hotkey_adtv_kind_app')}</option>
      </select>
      <input type="text" id="hkAndroidTvValue" list="hkAndroidTvValues" style="margin-top:6px" autocomplete="off">
      <datalist id="hkAndroidTvValues"></datalist>
      <div class="hint" id="hkAndroidTvHint"></div>`;
    onHkAndroidTvKindChange();
  } else if (kind === 'ir') {
    box.innerHTML = fixed('hkIrDevice') + `
      <label>${I18N.t('web_hotkey_ir_command_label')}</label>
      <input type="text" id="hkIrCommand" list="hkIrCommandHints" placeholder="${I18N.t('web_hotkey_ir_command_ph')}">
      <datalist id="hkIrCommandHints"></datalist>`;
    await onHkIrDeviceChange();
  } else if (kind === 'harmony') {
    if (harmonyAvailable) {
      renderHarmonyHubSelect(box, 'command', 'hk');
    } else {
      box.innerHTML = `
        <label>${I18N.t('web_hotkey_harmony_device_label')}</label><input type="text" id="hkDevice" placeholder="${I18N.t('web_hotkey_harmony_device_ph')}">
        <label>${I18N.t('web_hotkey_harmony_command_label')}</label><input type="text" id="hkCommand" placeholder="${I18N.t('web_hotkey_harmony_command_ph')}">`;
    }
  } else {
    box.innerHTML = '';
  }
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
  document.getElementById('hkKey').value = hwKeyCanonical(h.key) || 'BACK';
  const adtv = androidTvHotkeyParts(h);
  const action = h.page ? 'page' : h.openOverlay ? 'openOverlay' : h.openCurrentActivityRoom ? 'openCurrentActivity' : h.stopActivity ? 'stopActivity' : h.activity ? 'startActivity' : h.event ? 'haEvent' : adtv ? 'androidTvCommand' : h.service === 'remote.send_command' ? 'remoteCommand' : h.service === 'astrion_appletv.send_command' ? 'appleTvCommand' : h.service ? 'service' : h.harmonyCommand ? 'harmonyCommand' : h.irDevice ? 'irCommand' : 'harmonyActivity';
  // The four device actions open in the "Device" action (1.5.2), their device picked.
  const device = action === 'appleTvCommand' ? 'atv:' + (h.entityId || '') : action === 'androidTvCommand' ? 'tv:' + adtv.tv
    : action === 'irCommand' ? 'ir:' + (h.irDevice || '') : action === 'harmonyCommand' ? 'harmony' : null;
  document.getElementById('hkAction').value = device ? 'device' : action;
  await updateHotkeyActionInputs(device ? { target: device } : undefined);

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
  const action = hkEffectiveAction();
  if (!action) { alert(I18N.t('web_hotkey_err_device')); return; }

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

/** "Menu (MAIN)": the key's name as printed on the remote, then its id — or just the id when they match. */
// The remote's buttons, and the Android names the app also takes for them (1.4.9, as
// HardwareKey.fromName): a copied dashboard said "MENU" for MAIN and that hotkey never fired.
function cxEscHk(v) { return String(v).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }

const HW_KEYS = ['BACK', 'HOME', 'POWER', 'VOLUME_UP', 'VOLUME_DOWN', 'MUTE', 'PAGE_UP', 'PAGE_DOWN',
  'UP', 'DOWN', 'LEFT', 'RIGHT', 'CENTER', 'VOICE', 'MAIN', 'REWIND', 'PLAY', 'STOP', 'FASTFORWARD',
  'RED_BUTTON', 'GREEN_BUTTON', 'BLUE_BUTTON', 'YELLOW_BUTTON'];
const HW_KEY_ALIASES = {
  MENU: 'MAIN', FAST_FORWARD: 'FASTFORWARD', MEDIA_FAST_FORWARD: 'FASTFORWARD', MEDIA_REWIND: 'REWIND',
  MEDIA_PLAY_PAUSE: 'PLAY', PLAY_PAUSE: 'PLAY', MEDIA_STOP: 'STOP', VOLUME_MUTE: 'MUTE',
  RED: 'RED_BUTTON', GREEN: 'GREEN_BUTTON', BLUE: 'BLUE_BUTTON', YELLOW: 'YELLOW_BUTTON',
  PROG_RED: 'RED_BUTTON', PROG_GREEN: 'GREEN_BUTTON', PROG_BLUE: 'BLUE_BUTTON', PROG_YELLOW: 'YELLOW_BUTTON',
};

/** The remote's button a hotkey key names; null when the remote has none (the hotkey never fires). */
function hwKeyCanonical(key) {
  const k = String(key || '').trim().toUpperCase().replace(/^KEYCODE_/, '');
  return HW_KEYS.includes(k) ? k : (HW_KEY_ALIASES[k] || null);
}

function hwKeyLabel(key) {
  const canonical = hwKeyCanonical(key);
  if (!canonical) return `<span style="color:#ff8a65" title="${cxEscHk(I18N.t('web_hotkey_unknown_key'))}">⚠ ${cxEscHk(key)}</span>`;
  const id = 'web_hwkey_' + canonical.toLowerCase();
  const title = I18N.strings[id] !== undefined ? I18N.t(id) : '';
  return title && title.toUpperCase() !== String(key).toUpperCase() ? `${title} (${key})` : key;
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
