// ---- Standby & battery ------------------------------------------------------
//
// dashboardData.power: how the remote saves battery while its screen is off
// (config/AppConfig.kt PowerConfig, applied by StandbyController.kt). Every key
// is optional; like `dock`, the block is only written when something differs
// from the defaults.

const POWER_DEFAULTS = { motion_wake_minutes: 5, screen_off_filter_seconds: 30, dock_debounce_seconds: 5 };
const POWER_MOTION_CHOICES = [[0, 'web_power_motion_off'], [1, 'web_power_motion_1'], [2, 'web_power_motion_n'],
  [5, 'web_power_motion_default'], [10, 'web_power_motion_n'], [30, 'web_power_motion_n'], [-1, 'web_power_motion_always']];
const POWER_TIMEOUT_CHOICES = [[10, '10 s'], [15, '15 s'], [30, '30 s'], [60, '1 min'], [120, '2 min'], [300, '5 min']];

function powerValues() {
  return Object.assign({}, POWER_DEFAULTS, dashboardData.power || {});
}

function powerEsc(v) {
  return String(v == null ? '' : v).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
}

/** Every entity id the builder knows about (device catalog + live /ha-states), for the picker. */
function powerEntityIds() {
  const ids = new Set((dashboardData.haDevices || []).map(d => d.entityId).filter(Boolean));
  const states = (typeof haStates !== 'undefined' && haStates) ? haStates : {};
  Object.keys(states).forEach(id => ids.add(id));
  return [...ids].sort();
}

function renderPowerForm() {
  const box = document.getElementById('powerBox');
  if (!box) return;
  const p = powerValues();
  const kept = p.screen_off_entities || [];
  const motion = POWER_MOTION_CHOICES.some(([v]) => v === p.motion_wake_minutes)
    ? POWER_MOTION_CHOICES : POWER_MOTION_CHOICES.concat([[p.motion_wake_minutes, 'web_power_motion_n']]);
  const timeout = p.screen_timeout_seconds && !POWER_TIMEOUT_CHOICES.some(([v]) => v === p.screen_timeout_seconds)
    ? POWER_TIMEOUT_CHOICES.concat([[p.screen_timeout_seconds, p.screen_timeout_seconds + ' s']]) : POWER_TIMEOUT_CHOICES;
  box.innerHTML = `
    <div class="hint" style="margin-bottom:10px">${I18N.t('web_power_how_this_remote_saves_battery_while')}</div>
    <label for="powerMotion">${I18N.t('web_power_wake_the_screen_when_picked_up')}</label>
    <select id="powerMotion" onchange="onPowerInput()">
      ${motion.map(([v, l]) => `<option value="${v}" ${v === p.motion_wake_minutes ? 'selected' : ''}>${powerEsc(I18N.t(l, v))}</option>`).join('')}
    </select>
    <div class="hint">${I18N.t('web_power_after_that_the_motion_sensor_sleeps')}</div>

    <label for="powerFilter">${I18N.t('web_power_go_quiet_with_home_assistant_after')}</label>
    <input type="number" id="powerFilter" min="-1" max="3600" placeholder="${POWER_DEFAULTS.screen_off_filter_seconds}"
      value="${p.screen_off_filter_seconds !== POWER_DEFAULTS.screen_off_filter_seconds ? p.screen_off_filter_seconds : ''}" onchange="onPowerInput()">
    <div class="hint">${I18N.t('web_power_quiet_the_remote_stops_receiving_every')}</div>

    <label>${I18N.t('web_power_entities_kept_live_while_quiet_optional')}</label>
    <div id="powerKeptList" class="power-chips">
      ${kept.length ? kept.map((id, i) => `<span class="power-chip">${powerEsc(id)}
        <button class="secondary" title="${I18N.t('web_config_harmony_remove_button')}" onclick="removePowerEntity(${i})">×</button></span>`).join('')
        : `<div class="hint">${I18N.t('web_power_none_nothing_is_received_while_quiet')}</div>`}
    </div>
    <div class="power-add-row">
      <input type="text" id="powerEntityInput" list="powerEntityList" placeholder="${I18N.t('web_power_e_g_input_boolean_find_remote')}">
      <datalist id="powerEntityList">${powerEntityIds().map(id => `<option value="${powerEsc(id)}">`).join('')}</datalist>
      <button class="secondary" onclick="addPowerEntity()">${I18N.t('web_power_add')}</button>
    </div>
    <div class="hint">${I18N.t('web_power_only_for_something_the_remote_must')}</div>

    <label for="powerDebounce">${I18N.t('web_power_dock_detection_delay_seconds')}</label>
    <input type="number" id="powerDebounce" min="0" max="60" placeholder="${POWER_DEFAULTS.dock_debounce_seconds}"
      value="${p.dock_debounce_seconds !== POWER_DEFAULTS.dock_debounce_seconds ? p.dock_debounce_seconds : ''}" onchange="onPowerInput()">
    <div class="hint">${I18N.t('web_power_charging_must_hold_this_long_before')}</div>

    <label for="powerTimeout">${I18N.t('web_power_screen_timeout')}</label>
    <select id="powerTimeout" onchange="onPowerInput()">
      <option value="" ${!p.screen_timeout_seconds ? 'selected' : ''}>${I18N.t('web_power_leave_the_remote_s_system_setting')}</option>
      ${timeout.map(([v, l]) => `<option value="${v}" ${v === p.screen_timeout_seconds ? 'selected' : ''}>${l}</option>`).join('')}
    </select>
    <div class="hint">${I18N.t('web_power_also_settable_on_the_remote_settings')}</div>`;
}

/** Reads the form back into dashboardData.power, keeping only what differs from the defaults. */
function onPowerInput() {
  const prev = dashboardData.power || {};
  const power = {};
  const motion = parseInt(document.getElementById('powerMotion').value, 10);
  if (!isNaN(motion) && motion !== POWER_DEFAULTS.motion_wake_minutes) power.motion_wake_minutes = motion;
  const filter = parseInt(document.getElementById('powerFilter').value, 10);
  if (!isNaN(filter) && filter !== POWER_DEFAULTS.screen_off_filter_seconds) power.screen_off_filter_seconds = Math.max(-1, filter);
  if (prev.screen_off_entities && prev.screen_off_entities.length) power.screen_off_entities = prev.screen_off_entities;
  const debounce = parseInt(document.getElementById('powerDebounce').value, 10);
  if (!isNaN(debounce) && debounce !== POWER_DEFAULTS.dock_debounce_seconds) power.dock_debounce_seconds = Math.min(60, Math.max(0, debounce));
  const timeout = parseInt(document.getElementById('powerTimeout').value, 10);
  if (timeout > 0) power.screen_timeout_seconds = timeout;
  if (Object.keys(power).length) dashboardData.power = power; else delete dashboardData.power;
  updateJsonOutput();
}

function addPowerEntity() {
  const input = document.getElementById('powerEntityInput');
  const id = input.value.trim();
  if (!/^[a-z0-9_]+\.[a-z0-9_]+$/i.test(id)) { input.focus(); return; }
  const kept = (dashboardData.power && dashboardData.power.screen_off_entities) || [];
  if (!kept.includes(id)) {
    dashboardData.power = Object.assign({}, dashboardData.power, { screen_off_entities: kept.concat([id]) });
  }
  renderPowerForm();
  updateJsonOutput();
}

function removePowerEntity(index) {
  const kept = ((dashboardData.power && dashboardData.power.screen_off_entities) || []).slice();
  kept.splice(index, 1);
  const power = Object.assign({}, dashboardData.power);
  if (kept.length) power.screen_off_entities = kept; else delete power.screen_off_entities;
  if (Object.keys(power).length) dashboardData.power = power; else delete dashboardData.power;
  renderPowerForm();
  updateJsonOutput();
}
