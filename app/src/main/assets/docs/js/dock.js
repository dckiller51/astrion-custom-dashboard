// ---- Dock screen ------------------------------------------------------------
//
// dashboardData.dock: what the remote shows while it sits on its charging dock
// (config/AppConfig.kt DockConfig). Every key is optional; the block is only
// written when something differs from the defaults, so an untouched builder
// keeps producing the same dashboard.json as before. The remote's own Settings
// page edits the same `mode` key in its dashboard.json.

const DOCK_DEFAULTS = { mode: 'charging', brightness: 0.2, night_brightness: 0.05, show_battery: true };
const DOCK_IDLE_DEFAULTS = { charging: 15, clock: 30, dashboard: 60 };

function dockValues() {
  return Object.assign({}, DOCK_DEFAULTS, dashboardData.dock || {});
}

/** Entity ids of [domain] from the device catalog and the live /ha-states data, as [id, name] pairs. */
function dockEntityOptions(domain) {
  const seen = new Map();
  (dashboardData.haDevices || []).filter(d => d.domain === domain).forEach(d => seen.set(d.entityId, d.name || d.entityId));
  const states = (typeof haStates !== 'undefined' && haStates) ? haStates : {};
  Object.keys(states).filter(id => id.startsWith(domain + '.')).forEach(id => {
    if (!seen.has(id)) seen.set(id, (states[id] && states[id].friendly_name) || id);
  });
  return [...seen.entries()].sort((a, b) => String(a[1]).localeCompare(String(b[1])));
}

function dockEsc(v) {
  return String(v == null ? '' : v).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
}

function renderDockForm() {
  const box = document.getElementById('dockBox');
  if (!box) return;
  const d = dockValues();
  const weatherOptions = dockEntityOptions('weather');
  if (d.weather_entity && !weatherOptions.some(([id]) => id === d.weather_entity)) weatherOptions.unshift([d.weather_entity, d.weather_entity]);
  const mediaOptions = dockEntityOptions('media_player');
  const selectedMedia = d.media_entities || [];
  selectedMedia.filter(id => !mediaOptions.some(([m]) => m === id)).forEach(id => mediaOptions.unshift([id, id]));
  const clock = d.mode === 'clock';
  box.innerHTML = `
    <div class="hint" style="margin-bottom:10px">What the remote shows while it sits on its charging dock. The mode is also switchable on the remote (Settings › Dock screen) — it writes this same value.</div>
    <label for="dockMode">Mode</label>
    <select id="dockMode" onchange="onDockInput()">
      <option value="charging" ${d.mode === 'charging' ? 'selected' : ''}>Charging — battery screen (as before)</option>
      <option value="clock" ${clock ? 'selected' : ''}>Clock — dashboard, then a dim clock when idle</option>
      <option value="dashboard" ${d.mode === 'dashboard' ? 'selected' : ''}>Dashboard — just dims when idle</option>
    </select>
    <label for="dockIdle">Idle time before dimming / clock (seconds)</label>
    <input type="number" id="dockIdle" min="5" max="3600" placeholder="${DOCK_IDLE_DEFAULTS[d.mode] || 15}" value="${d.idle_seconds != null ? d.idle_seconds : ''}" onchange="onDockInput()">
    <div id="dockClockFields" style="display:${d.mode === 'charging' ? 'none' : ''}">
      <label for="dockBrightness">Brightness when idle — day: <span id="dockBrightnessVal">${Math.round(d.brightness * 100)}%</span></label>
      <input type="range" id="dockBrightness" min="1" max="100" value="${Math.round(d.brightness * 100)}" oninput="onDockInput(true)" onchange="onDockInput()">
      <label for="dockNightBrightness">Night (sun below the horizon, else 21:00–07:00): <span id="dockNightBrightnessVal">${Math.round(d.night_brightness * 100)}%</span></label>
      <input type="range" id="dockNightBrightness" min="1" max="100" value="${Math.round(d.night_brightness * 100)}" oninput="onDockInput(true)" onchange="onDockInput()">
    </div>
    <div id="dockClockOnly" style="display:${clock ? '' : 'none'}">
      <label for="dockTimeFormat">Time format</label>
      <select id="dockTimeFormat" onchange="onDockInput()">
        <option value="" ${!d.time_format ? 'selected' : ''}>Like the remote's system setting</option>
        <option value="24" ${d.time_format === 24 ? 'selected' : ''}>24-hour (21:41)</option>
        <option value="12" ${d.time_format === 12 ? 'selected' : ''}>12-hour (9:41 pm)</option>
      </select>
      <label for="dockWeather">Weather (optional)</label>
      <select id="dockWeather" onchange="onDockInput()">
        <option value="">— none —</option>
        ${weatherOptions.map(([id, name]) => `<option value="${dockEsc(id)}" ${id === d.weather_entity ? 'selected' : ''}>${dockEsc(name)} (${dockEsc(id)})</option>`).join('')}
      </select>
      <label class="inline-check"><input type="checkbox" id="dockBattery" ${d.show_battery !== false ? 'checked' : ''} onchange="onDockInput()"> Show the battery level</label>
      <label>Now playing from (none ticked = any media player that is playing)</label>
      <div id="dockMediaBox">
        ${mediaOptions.length ? mediaOptions.map(([id, name]) => `
          <label class="inline-check"><input type="checkbox" class="dock-media-check" value="${dockEsc(id)}" ${selectedMedia.includes(id) ? 'checked' : ''} onchange="onDockInput()"> ${dockEsc(name)}</label>`).join('')
          : '<div class="hint">No media_player found — open the builder from the remote to list them.</div>'}
      </div>
    </div>`;
}

/** Reads the form back into dashboardData.dock, keeping only what differs from the defaults. */
function onDockInput(live) {
  const mode = document.getElementById('dockMode').value;
  const bright = parseInt(document.getElementById('dockBrightness').value, 10) / 100;
  const night = parseInt(document.getElementById('dockNightBrightness').value, 10) / 100;
  document.getElementById('dockBrightnessVal').textContent = Math.round(bright * 100) + '%';
  document.getElementById('dockNightBrightnessVal').textContent = Math.round(night * 100) + '%';
  if (live) return;
  const dock = {};
  if (mode !== DOCK_DEFAULTS.mode) dock.mode = mode;
  const idle = parseInt(document.getElementById('dockIdle').value, 10);
  if (!isNaN(idle)) dock.idle_seconds = Math.max(5, idle);
  if (Math.abs(bright - DOCK_DEFAULTS.brightness) > 0.001) dock.brightness = bright;
  if (Math.abs(night - DOCK_DEFAULTS.night_brightness) > 0.001) dock.night_brightness = night;
  const tf = parseInt(document.getElementById('dockTimeFormat').value, 10);
  if (tf === 12 || tf === 24) dock.time_format = tf;
  const weather = document.getElementById('dockWeather').value;
  if (weather) dock.weather_entity = weather;
  if (!document.getElementById('dockBattery').checked) dock.show_battery = false;
  const media = [...document.querySelectorAll('.dock-media-check:checked')].map(el => el.value);
  if (media.length) dock.media_entities = media;
  if (Object.keys(dock).length) dashboardData.dock = dock; else delete dashboardData.dock;
  // Mode changes show/hide the clock fields, so re-render; other edits keep focus where it is.
  const shownMode = document.getElementById('dockClockOnly').style.display !== 'none' ? 'clock' : null;
  if ((mode === 'clock') !== (shownMode === 'clock') ||
      (document.getElementById('dockClockFields').style.display === 'none') !== (mode === 'charging')) {
    renderDockForm();
  }
  updateJsonOutput();
}
