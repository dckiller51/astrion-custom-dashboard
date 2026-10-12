// ---- Standby & battery ------------------------------------------------------
//
// dashboardData.power: how the remote saves battery while its screen is off
// (config/AppConfig.kt PowerConfig, applied by StandbyController.kt). Every key
// is optional; like `dock`, the block is only written when something differs
// from the defaults.

const POWER_DEFAULTS = { motion_wake_minutes: 5, screen_off_filter_seconds: 30, dock_debounce_seconds: 5 };
const POWER_MOTION_CHOICES = [[0, 'Off — only the buttons wake the screen'], [1, '1 minute'], [2, '2 minutes'],
  [5, '5 minutes (default)'], [10, '10 minutes'], [30, '30 minutes'], [-1, 'Always (uses the most battery)']];
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
    ? POWER_MOTION_CHOICES : POWER_MOTION_CHOICES.concat([[p.motion_wake_minutes, p.motion_wake_minutes + ' minutes']]);
  const timeout = p.screen_timeout_seconds && !POWER_TIMEOUT_CHOICES.some(([v]) => v === p.screen_timeout_seconds)
    ? POWER_TIMEOUT_CHOICES.concat([[p.screen_timeout_seconds, p.screen_timeout_seconds + ' s']]) : POWER_TIMEOUT_CHOICES;
  box.innerHTML = `
    <div class="hint" style="margin-bottom:10px">How this remote saves battery while its screen is off. The defaults suit most setups.</div>
    <label for="powerMotion">Wake the screen when picked up — for how long after it goes off</label>
    <select id="powerMotion" onchange="onPowerInput()">
      ${motion.map(([v, l]) => `<option value="${v}" ${v === p.motion_wake_minutes ? 'selected' : ''}>${powerEsc(l)}</option>`).join('')}
    </select>
    <div class="hint">After that the motion sensor sleeps until a button turns the screen on. Never wakes on the dock.</div>

    <label for="powerFilter">Go quiet with Home Assistant after the screen is off for (seconds)</label>
    <input type="number" id="powerFilter" min="-1" max="3600" placeholder="${POWER_DEFAULTS.screen_off_filter_seconds}"
      value="${p.screen_off_filter_seconds !== POWER_DEFAULTS.screen_off_filter_seconds ? p.screen_off_filter_seconds : ''}" onchange="onPowerInput()">
    <div class="hint">Quiet = the remote stops receiving every state change (the main battery drain on a busy Home Assistant)
      and catches up the moment the screen comes on. -1 = never go quiet. Not applied while docked.</div>

    <label>Entities kept live while quiet (optional)</label>
    <div id="powerKeptList" class="power-chips">
      ${kept.length ? kept.map((id, i) => `<span class="power-chip">${powerEsc(id)}
        <button class="secondary" title="Remove" onclick="removePowerEntity(${i})">×</button></span>`).join('')
        : '<div class="hint">None — nothing is received while quiet.</div>'}
    </div>
    <div class="power-add-row">
      <input type="text" id="powerEntityInput" list="powerEntityList" placeholder="e.g. input_boolean.find_remote">
      <datalist id="powerEntityList">${powerEntityIds().map(id => `<option value="${powerEsc(id)}">`).join('')}</datalist>
      <button class="secondary" onclick="addPowerEntity()">+ Add</button>
    </div>
    <div class="hint">Only for something the remote must react to with its screen off. "Find my remote" doesn't need it.</div>

    <label for="powerDebounce">Dock detection delay (seconds)</label>
    <input type="number" id="powerDebounce" min="0" max="60" placeholder="${POWER_DEFAULTS.dock_debounce_seconds}"
      value="${p.dock_debounce_seconds !== POWER_DEFAULTS.dock_debounce_seconds ? p.dock_debounce_seconds : ''}" onchange="onPowerInput()">
    <div class="hint">Charging must hold this long before the dock screen shows — a remote set down crooked doesn't flicker it on and off.</div>

    <label for="powerTimeout">Screen timeout</label>
    <select id="powerTimeout" onchange="onPowerInput()">
      <option value="" ${!p.screen_timeout_seconds ? 'selected' : ''}>Leave the remote's system setting</option>
      ${timeout.map(([v, l]) => `<option value="${v}" ${v === p.screen_timeout_seconds ? 'selected' : ''}>${l}</option>`).join('')}
    </select>
    <div class="hint">Also settable on the remote (Settings › Display › Screen timeout). The remote needs
      "Modify system settings" — the same permission as its brightness slider; it asks for it there the first time.</div>`;
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
