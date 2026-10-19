// ---- Built-in weather (1.3.2) -------------------------------------------------
//
// dashboardData.weather: { name, latitude, longitude, model?, unit? } (config/AppConfig.kt
// WeatherConfig). When set, the remote fetches the forecast itself from Open-Meteo (no key, no
// Home Assistant) and publishes it as the entity "weather.astrion", which the clock & weather
// card and the dock clock pick like any Home Assistant weather entity.
//
// Set up on the remote's web page (devices.html, "Weather" tile: renderWeatherForm + the page's
// own weatherChanged() that saves it); the builder only uses builtinWeatherOption() in its
// weather pickers. The place search goes through the remote (GET /weather-search).

const BUILTIN_WEATHER_ID = 'weather.astrion';
let weatherResults = [];

function weatherEsc(v) {
  return String(v == null ? '' : v).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
}

/** [id, label] of the built-in weather when a place is set, else null — for the weather pickers. */
function builtinWeatherOption() {
  const w = dashboardData.weather;
  if (!w || w.latitude == null || w.longitude == null) return null;
  return [BUILTIN_WEATHER_ID, I18N.t('web_weather_builtin_option', w.name || '')];
}

function weatherPlaceLabel(r) {
  return [r.name, r.admin1, r.country].filter(Boolean).join(', ');
}

function renderWeatherForm() {
  const box = document.getElementById('weatherBox');
  if (!box) return;
  const w = dashboardData.weather;
  const current = w ? `
    <div class="weather-place">📍 <strong>${weatherEsc(w.name || '')}</strong>
      <span class="hint">(${Number(w.latitude).toFixed(4)}, ${Number(w.longitude).toFixed(4)})</span>
      <button type="button" class="secondary" onclick="removeWeatherPlace()">${I18N.t('web_config_harmony_remove_button')}</button>
    </div>
    <label for="weatherModel">${I18N.t('web_weather_model')}</label>
    <select id="weatherModel" onchange="onWeatherInput()">
      <option value="auto" ${w.model !== 'meteofrance' ? 'selected' : ''}>${I18N.t('web_weather_model_auto')}</option>
      <option value="meteofrance" ${w.model === 'meteofrance' ? 'selected' : ''}>${I18N.t('web_weather_model_meteofrance')}</option>
    </select>
    <label for="weatherUnit">${I18N.t('web_weather_unit')}</label>
    <select id="weatherUnit" onchange="onWeatherInput()">
      <option value="celsius" ${w.unit !== 'fahrenheit' ? 'selected' : ''}>°C</option>
      <option value="fahrenheit" ${w.unit === 'fahrenheit' ? 'selected' : ''}>°F</option>
    </select>
    <div class="hint">${I18N.t('web_weather_use_it')}</div>` : '';
  box.innerHTML = `
    <div class="hint" style="margin-bottom:10px">${I18N.t('web_weather_intro')}</div>
    ${current}
    <label for="weatherQuery">${I18N.t(w ? 'web_weather_change_place' : 'web_weather_place')}</label>
    <div class="power-add-row">
      <input type="text" id="weatherQuery" placeholder="${I18N.t('web_weather_place_ph')}" onkeydown="if (event.key === 'Enter') { event.preventDefault(); searchWeatherPlace(); }">
      <button type="button" class="secondary" onclick="searchWeatherPlace()">${I18N.t('web_weather_search')}</button>
    </div>
    <div id="weatherResults"></div>
    <div class="hint" style="margin-top:8px">${I18N.t('web_weather_attribution')}</div>`;
  renderWeatherResults();
}

function renderWeatherResults(message) {
  const el = document.getElementById('weatherResults');
  if (!el) return;
  if (message) { el.innerHTML = `<div class="hint">${weatherEsc(message)}</div>`; return; }
  el.innerHTML = weatherResults.map((r, i) =>
    `<button type="button" class="secondary weather-result" onclick="pickWeatherPlace(${i})">${weatherEsc(weatherPlaceLabel(r))}</button>`).join('');
}

async function searchWeatherPlace() {
  const q = (document.getElementById('weatherQuery')?.value || '').trim();
  if (q.length < 2) return;
  renderWeatherResults(I18N.t('web_weather_searching'));
  try {
    const lang = (I18N.lang || navigator.language || 'en').slice(0, 2);
    const res = await fetch('/weather-search?q=' + encodeURIComponent(q) + '&lang=' + encodeURIComponent(lang));
    if (!res.ok) throw new Error('HTTP ' + res.status);
    weatherResults = (await res.json()).results || [];
    if (weatherResults.length) renderWeatherResults(); else renderWeatherResults(I18N.t('web_weather_no_result', q));
  } catch (e) {
    weatherResults = [];
    renderWeatherResults(I18N.t('web_weather_search_failed', e.message));
  }
}

function pickWeatherPlace(i) {
  const r = weatherResults[i];
  if (!r) return;
  const prev = dashboardData.weather || {};
  dashboardData.weather = Object.assign({}, prev, {
    name: r.name,
    latitude: Math.round(r.latitude * 10000) / 10000,
    longitude: Math.round(r.longitude * 10000) / 10000,
  });
  weatherResults = [];
  afterWeatherChange();
}

function onWeatherInput() {
  if (!dashboardData.weather) return;
  const w = Object.assign({}, dashboardData.weather);
  const model = document.getElementById('weatherModel').value;
  const unit = document.getElementById('weatherUnit').value;
  if (model === 'meteofrance') w.model = model; else delete w.model;
  if (unit === 'fahrenheit') w.unit = unit; else delete w.unit;
  dashboardData.weather = w;
  if (typeof weatherChanged === 'function') weatherChanged();
}

function removeWeatherPlace() {
  delete dashboardData.weather;
  afterWeatherChange();
}

function afterWeatherChange() {
  renderWeatherForm();
  if (typeof weatherChanged === 'function') weatherChanged(); // the page saves it (devices-page.js)
}
