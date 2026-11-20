// ---- TV guide (1.6.0) -------------------------------------------------------------
//
// An XMLTV source kept on the remote (GET/POST /epg, web/EpgRoutes.kt): downloaded once a day,
// its chosen channels become entities (sensor.epg_<channel>: what's on now, what's next) and
// what's playing live gets its synopsis from it (epg/EpgRegistry.kt).

const EPG_PRESETS = [
  { id: 'tnt', url: 'https://xmltvfr.fr/xmltv/xmltv_tnt.xml.gz' },
  { id: 'fr', url: 'https://xmltvfr.fr/xmltv/xmltv_fr.xml.gz' },
  { id: 'all', url: 'https://xmltvfr.fr/xmltv/xmltv.xml.gz' }
];

let epgState = { url: '', channels: [], running: false, fetching: false, fetched: 0, bytes: 0, shows: 0, error: null, kept: [], available: [] };
let epgPoll = null;

function epgEsc(v) {
  return String(v == null ? '' : v).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
}

async function loadEpg() {
  try {
    const res = await fetch('/epg');
    if (res.ok) epgState = await res.json();
  } catch (e) { /* the remote didn't answer: the form shows "not set" */ }
  renderEpg();
  clearTimeout(epgPoll);
  if (epgState.fetching) epgPoll = setTimeout(loadEpg, 3000);
}

function epgPresetOf(url) {
  const p = EPG_PRESETS.find(x => x.url === url);
  return p ? p.id : (url ? 'custom' : '');
}

function renderEpg() {
  const tile = document.getElementById('tileEpgCount');
  // A big guide keeps nothing until its channels are ticked: say so rather than "0 channels".
  const toChoose = !epgState.kept.length && epgState.available.length > 0;
  if (tile) {
    tile.textContent = !epgState.url ? I18N.t('web_epg_tile_off')
      : toChoose ? I18N.t('web_epg_tile_choose') : I18N.t('web_epg_tile_on', String(epgState.kept.length));
  }
  const box = document.getElementById('epgBox');
  if (!box) return;
  const preset = epgPresetOf(epgState.url);
  const chosen = new Set(epgState.channels.length ? epgState.channels : epgState.kept.map(c => c.id));
  // Channels saved (or imported) that this guide doesn't have: another name, or another guide.
  const known = new Set(epgState.available.map(c => c.id));
  const missing = epgState.fetched && !epgState.fetching && known.size ? epgState.channels.filter(id => !known.has(id)) : [];
  const when = epgState.fetched ? new Date(epgState.fetched).toLocaleString() : '';
  let status;
  if (epgState.fetching) status = I18N.t('web_epg_fetching');
  else if (epgState.error) status = '⚠ ' + epgEsc(epgState.error);
  else if (epgState.fetched) status = I18N.t('web_epg_status', when, String(Math.round(epgState.bytes / 1024)), String(epgState.kept.length), String(epgState.available.length), String(epgState.shows));
  else status = epgState.url ? I18N.t('web_epg_not_yet') : '';
  box.innerHTML = `
    <p class="hint">${I18N.t('web_epg_intro')}</p>
    <label for="epgPreset">${I18N.t('web_epg_source')}</label>
    <select id="epgPreset" onchange="epgPresetChanged()">
      <option value=""${preset === '' ? ' selected' : ''}>${I18N.t('web_epg_source_none')}</option>
      ${EPG_PRESETS.map(p => `<option value="${p.id}"${preset === p.id ? ' selected' : ''}>${I18N.t('web_epg_source_' + p.id)}</option>`).join('')}
      <option value="custom"${preset === 'custom' ? ' selected' : ''}>${I18N.t('web_epg_source_custom')}</option>
    </select>
    <div id="epgCustomBox" style="display:${preset === 'custom' ? '' : 'none'}">
      <label for="epgUrl">${I18N.t('web_epg_url')}</label>
      <input type="url" id="epgUrl" value="${epgEsc(preset === 'custom' ? epgState.url : '')}" placeholder="https://…/guide.xml.gz">
      <div class="hint">${I18N.t('web_epg_url_hint')} <a href="https://github.com/dckiller51/astrion-custom-dashboard/wiki/TV-Guide" target="_blank" rel="noopener">${I18N.t('web_epg_wiki')}</a></div>
    </div>
    <div class="btn-row" style="margin-top:10px">
      <button type="button" onclick="saveEpg()">${I18N.t('web_epg_save')}</button>
      ${epgState.running ? `<button type="button" class="secondary" onclick="refreshEpg()">${I18N.t('web_epg_refresh')}</button>` : ''}
    </div>
    <div class="btn-row" style="margin-top:6px">
      ${epgState.url ? `<button type="button" class="secondary" onclick="exportEpg()">${I18N.t('web_epg_export')}</button>` : ''}
      <button type="button" class="secondary" onclick="document.getElementById('epgImportFile').click()">${I18N.t('web_epg_import')}</button>
      <input type="file" id="epgImportFile" accept=".json,application/json" style="display:none" onchange="importEpg(this)">
    </div>
    <div id="epgStatus" class="hint">${status}</div>
    ${missing.length ? `<p class="hint" style="color:var(--warning, #ffb347)">${I18N.t('web_epg_missing', String(missing.length), missing.map(epgEsc).join(', '))}</p>` : ''}
    ${toChoose && !epgState.fetching ? `<p class="hint" style="color:var(--accent, #B28BFF);font-weight:600">${I18N.t('web_epg_choose', String(epgState.available.length))}</p>` : ''}
    ${epgState.available.length ? `
      <h3 style="margin:18px 0 6px">${I18N.t('web_epg_channels')}</h3>
      <div class="hint">${I18N.t('web_epg_channels_hint')}</div>
      <input type="search" id="epgFilter" placeholder="${epgEsc(I18N.t('web_epg_filter'))}" oninput="epgFilter()" style="margin:6px 0">
      <div class="btn-row" style="margin-bottom:6px">
        <button type="button" class="secondary" onclick="epgCheckAll(true)">${I18N.t('web_epg_all')}</button>
        <button type="button" class="secondary" onclick="epgCheckAll(false)">${I18N.t('web_epg_none')}</button>
      </div>
      <div id="epgCount" class="hint" style="margin-bottom:6px"></div>
      <div id="epgChannels" onchange="epgCountChanged()" style="max-height:340px;overflow:auto;border:1px solid var(--border, #444);border-radius:8px;padding:6px">
        ${epgState.available.map(c => `<label class="epg-ch" style="display:flex;gap:8px;align-items:center;margin:2px 0" data-q="${epgEsc((c.name + ' ' + c.id).toLowerCase())}">
          <input type="checkbox" value="${epgEsc(c.id)}"${chosen.has(c.id) ? ' checked' : ''}>
          <span>${epgEsc(c.name)} <span class="hint">${epgEsc(c.id)}</span></span></label>`).join('')}
      </div>` : ''}
    ${epgState.kept.length ? `
      <h3 style="margin:18px 0 6px">${I18N.t('web_epg_entities')}</h3>
      <div class="hint">${epgState.kept.map(c => `<code>${epgEsc(c.entity)}</code>`).join(' ')}</div>` : ''}`;
  epgCountChanged();
}

const EPG_WARN = 60;
const EPG_MAX = 150;

/** How many channels are ticked: a warning past 60, the limit at 150 (the remote's memory). */
function epgCountChanged() {
  const el = document.getElementById('epgCount');
  if (!el) return;
  const n = document.querySelectorAll('#epgChannels input:checked').length;
  el.style.color = n > EPG_MAX ? 'var(--danger, #ff6b6b)' : n > EPG_WARN ? 'var(--warning, #ffb347)' : '';
  el.style.fontWeight = n > EPG_WARN ? '600' : '';
  el.textContent = n > EPG_MAX ? I18N.t('web_epg_count_over', String(n), String(EPG_MAX))
    : n > EPG_WARN ? I18N.t('web_epg_count_many', String(n))
    : I18N.t('web_epg_count', String(n));
}

function epgPresetChanged() {
  const v = document.getElementById('epgPreset').value;
  document.getElementById('epgCustomBox').style.display = v === 'custom' ? '' : 'none';
}

function epgFilter() {
  const q = document.getElementById('epgFilter').value.trim().toLowerCase();
  document.querySelectorAll('#epgChannels .epg-ch').forEach(el => {
    el.style.display = !q || el.dataset.q.includes(q) ? '' : 'none';
  });
}

function epgCheckAll(on) {
  document.querySelectorAll('#epgChannels .epg-ch').forEach(el => {
    if (el.style.display !== 'none') el.querySelector('input').checked = on;
  });
  epgCountChanged();
}

async function postEpg(path, params) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(params).toString()
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.error && !('url' in data)) throw new Error(data.error || 'HTTP ' + res.status);
  return data;
}

async function saveEpg() {
  const preset = document.getElementById('epgPreset').value;
  let url = '';
  if (preset === 'custom') url = document.getElementById('epgUrl').value.trim();
  else if (preset) url = EPG_PRESETS.find(p => p.id === preset).url;
  // The channels ticked, only for the same source: another one has other ids.
  let channels = [];
  if (url === epgState.url && document.getElementById('epgChannels')) {
    channels = [...document.querySelectorAll('#epgChannels input:checked')].map(i => i.value);
    if (channels.length > EPG_MAX) {
      alert(I18N.t('web_epg_count_over', String(channels.length), String(EPG_MAX)));
      return;
    }
  }
  try {
    epgState = await postEpg('/epg', { url, channels: channels.join('\n') });
    document.getElementById('epgStatus').textContent = I18N.t('web_epg_saved');
    setTimeout(loadEpg, 1500);
  } catch (e) {
    document.getElementById('epgStatus').textContent = '⚠ ' + e.message;
  }
}

async function refreshEpg() {
  try {
    epgState = await postEpg('/epg-refresh', {});
    epgState.fetching = true;
    renderEpg();
    setTimeout(loadEpg, 2000);
  } catch (e) {
    document.getElementById('epgStatus').textContent = '⚠ ' + e.message;
  }
}

I18N.ready.then(loadEpg);

/**
 * The source and its channels ticked, as a file (1.6.0): ticking 40 channels again after a
 * mistake, or on a second remote, takes a while. The boxes ticked on the page, else those saved.
 */
function exportEpg() {
  const boxes = document.querySelectorAll('#epgChannels input');
  const channels = boxes.length
    ? [...document.querySelectorAll('#epgChannels input:checked')].map(i => i.value)
    : epgState.channels.slice();
  const data = { astrion: 'tv-guide', version: 1, url: epgState.url, channels };
  const blob = new Blob([JSON.stringify(data, null, 2) + '\n'], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'astrion-tv-guide.json';
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
}

/** A file from exportEpg: its source and channels saved on the remote at once. */
async function importEpg(input) {
  const file = input.files && input.files[0];
  input.value = '';
  if (!file) return;
  let data;
  try {
    data = JSON.parse(await file.text());
  } catch (e) {
    alert(I18N.t('web_epg_import_bad'));
    return;
  }
  const channels = Array.isArray(data && data.channels) ? data.channels.filter(c => typeof c === 'string' && c.trim()) : null;
  const url = typeof (data && data.url) === 'string' ? data.url.trim() : '';
  if (!channels || !url) {
    alert(I18N.t('web_epg_import_bad'));
    return;
  }
  if (channels.length > EPG_MAX) {
    alert(I18N.t('web_epg_count_over', String(channels.length), String(EPG_MAX)));
    return;
  }
  // A site's address alone ("https://xmltvfr.fr") is its home page, not a guide file.
  if (/^https?:\/\/[^/]+\/?$/i.test(url)) {
    alert(I18N.t('web_epg_import_site', url));
    return;
  }
  if (!confirm(I18N.t('web_epg_import_confirm', String(channels.length), url))) return;
  try {
    epgState = await postEpg('/epg', { url, channels: channels.join('\n') });
    renderEpg();
    document.getElementById('epgStatus').textContent = I18N.t('web_epg_saved');
    setTimeout(loadEpg, 1500);
  } catch (e) {
    document.getElementById('epgStatus').textContent = '⚠ ' + e.message;
  }
}
