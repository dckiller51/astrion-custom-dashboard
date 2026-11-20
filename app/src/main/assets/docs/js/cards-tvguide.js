// ---- TV guide card (1.6.0) -------------------------------------------------------
//
// The remote's TvGuideCard: the TV guide's channels (sensor.epg_…, set under Devices › TV guide)
// with what's on now and next. Its channel rows live in window._pendingTvGuide (same pattern as
// the monitor rows); each may carry an action (the button grid's keys; its entity is
// target_entity, entity_id naming the channel).

let tvGuideKnown = [];

/** The guide's channels from the remote (GET /epg), for the entity suggestions and "Add all". */
async function tvGuideLoadKnown() {
  try {
    const res = await fetch('/epg');
    if (res.ok) tvGuideKnown = (await res.json()).kept || [];
  } catch (e) { /* no remote: free typing */ }
  const dl = document.getElementById('tvGuideEntities');
  if (dl) dl.innerHTML = tvGuideKnown.map(c => `<option value="${cxEsc(c.entity)}">${cxEsc(c.name)}</option>`).join('');
}

function tvGuideFormHtml() {
  return `
    <label>${I18N.t('web_card_title')}</label>
    <input type="text" id="optTvGuideTitle" placeholder="${I18N.t('web_card_tvguide_title_ph')}">
    <label class="inline-check"><input type="checkbox" id="optTvGuideNext" checked> ${I18N.t('web_card_tvguide_show_next')}</label>
    <label class="inline-check"><input type="checkbox" id="optTvGuideProgress" checked> ${I18N.t('web_card_tvguide_show_progress')}</label>
    <label class="inline-check"><input type="checkbox" id="optTvGuideLogo" checked> ${I18N.t('web_card_tvguide_show_logo')}</label>
    <label>${I18N.t('web_card_tvguide_player')}</label>
    <input type="text" id="optTvGuidePlayer" placeholder="media_player.shield">
    <div class="hint">${I18N.t('web_card_tvguide_player_hint')}</div>
    <label>${I18N.t('web_card_tvguide_tap')}</label>
    <select id="optTvGuideTap">
      <option value="details">${I18N.t('web_card_tvguide_tap_details')}</option>
      <option value="action">${I18N.t('web_card_tvguide_tap_action')}</option>
    </select>
    <label style="margin-top:10px">${I18N.t('web_card_tvguide_channels')}</label>
    <div class="hint">${I18N.t('web_card_tvguide_channels_hint')}</div>
    <datalist id="tvGuideEntities"></datalist>
    <div id="tvGuideRows"></div>
    <div class="btn-row">
      <button type="button" class="secondary" onclick="addTvGuideRow()">${I18N.t('web_card_tvguide_add')}</button>
      <button type="button" class="secondary" onclick="addAllTvGuideRows()">${I18N.t('web_card_tvguide_add_all')}</button>
      <button type="button" class="secondary" onclick="sortTvGuideRows()">${I18N.t('web_card_tvguide_sort')}</button>
    </div>
    <details id="tvgTextBox" ontoggle="if (this.open) tvGuideTextFill()" style="margin-top:8px">
      <summary class="hint" style="cursor:pointer">${I18N.t('web_card_tvguide_text')}</summary>
      <div class="hint" style="margin:6px 0">${I18N.t('web_card_tvguide_text_hint')}</div>
      <textarea id="tvgText" rows="12" spellcheck="false" style="width:100%;font-family:monospace;font-size:12px" placeholder="sensor.epg_m6_fr | M6 | https://www.canalplus.com/live/?channel=313"></textarea>
      <div class="btn-row"><button type="button" onclick="tvGuideTextApply()">${I18N.t('web_card_tvguide_text_apply')}</button></div>
    </details>`;
}

function renderTvGuideRows() {
  const list = document.getElementById('tvGuideRows');
  if (!list) return;
  const rows = window._pendingTvGuide || [];
  if (!rows.length) { list.innerHTML = `<div class="hint">${I18N.t('web_card_tvguide_all_listed')}</div>`; return; }
  list.innerHTML = rows.map((r, i) => `
    <div class="list-item tvg-row" style="flex-direction:column;align-items:stretch;gap:6px">
      <div style="display:flex;gap:6px;align-items:center">
        <input type="text" class="tvg-entity" list="tvGuideEntities" value="${cxEsc(r.entity_id)}" placeholder="sensor.epg_france2_fr" style="flex:3;min-width:0;margin:0">
        <input type="text" class="tvg-name" value="${cxEsc(r.name)}" placeholder="${I18N.t('web_card_monitor_name_ph')}" style="flex:2;min-width:0;margin:0">
        <input type="text" class="tvg-link" value="${cxEsc(r.link)}" placeholder="${I18N.t('web_card_tvguide_link_ph')}" style="flex:3;min-width:0;margin:0">
        ${cxMoveButtons('moveTvGuideRow', i)}
        <span class="remove" onclick="removeTvGuideRow(${i})">✕</span>
      </div>
      <details${r.service ? ' open' : ''}>
        <summary class="hint" style="cursor:pointer">${I18N.t('web_card_tvguide_action')}</summary>
        <div style="display:flex;flex-direction:column;gap:6px;margin-top:6px">
          <input type="text" class="tvg-service" value="${cxEsc(r.service)}" placeholder="media_player.select_source" style="margin:0">
          <input type="text" class="tvg-target" value="${cxEsc(r.target_entity)}" placeholder="media_player.tv" style="margin:0">
          <input type="text" class="tvg-data" value="${cxEsc(r.data ? JSON.stringify(r.data) : '')}" placeholder='{"source": "France 2"}' style="margin:0">
        </div>
      </details>
    </div>`).join('');
}

/** The rows as typed, read back before any change to the list. */
function syncTvGuideRows() {
  const rows = window._pendingTvGuide || [];
  document.querySelectorAll('#tvGuideRows .tvg-row').forEach((el, i) => {
    if (!rows[i]) return;
    rows[i].entity_id = el.querySelector('.tvg-entity').value.trim();
    rows[i].name = el.querySelector('.tvg-name').value.trim();
    rows[i].link = el.querySelector('.tvg-link').value.trim();
    rows[i].service = el.querySelector('.tvg-service').value.trim();
    rows[i].target_entity = el.querySelector('.tvg-target').value.trim();
    rows[i]._data = el.querySelector('.tvg-data').value.trim();
  });
}

function addTvGuideRow() {
  syncTvGuideRows();
  (window._pendingTvGuide = window._pendingTvGuide || []).push({ entity_id: '', name: '' });
  renderTvGuideRows();
}

function addAllTvGuideRows() {
  syncTvGuideRows();
  const rows = window._pendingTvGuide = window._pendingTvGuide || [];
  if (!tvGuideKnown.length) { alert(I18N.t('web_card_tvguide_none_in_guide')); return; }
  tvGuideKnown.forEach(c => {
    if (!rows.some(r => r.entity_id === c.entity)) rows.push({ entity_id: c.entity, name: '' });
  });
  renderTvGuideRows();
}

function removeTvGuideRow(i) {
  syncTvGuideRows();
  window._pendingTvGuide.splice(i, 1);
  renderTvGuideRows();
}

function moveTvGuideRow(i, d) {
  syncTvGuideRows();
  const rows = window._pendingTvGuide;
  const j = i + d;
  if (j < 0 || j >= rows.length) return;
  [rows[i], rows[j]] = [rows[j], rows[i]];
  renderTvGuideRows();
}

const TVG_ACTION_KEYS = ['service', 'target_entity', 'data'];

function fillTvGuideForm(o) {
  document.getElementById('optTvGuideTitle').value = o.title || '';
  document.getElementById('optTvGuideNext').checked = o.show_next !== false;
  document.getElementById('optTvGuideProgress').checked = o.show_progress !== false;
  document.getElementById('optTvGuideLogo').checked = o.show_logo !== false;
  document.getElementById('optTvGuideTap').value = o.tap === 'action' ? 'action' : 'details';
  document.getElementById('optTvGuidePlayer').value = o.player || '';
  window._pendingTvGuide = JSON.parse(JSON.stringify(Array.isArray(o.channels) ? o.channels : []));
  renderTvGuideRows();
  tvGuideLoadKnown();
}

function collectTvGuideForm(out) {
  syncTvGuideRows();
  ['title', 'show_next', 'show_progress', 'show_logo', 'tap', 'player', 'channels'].forEach(k => delete out[k]);
  const player = document.getElementById('optTvGuidePlayer').value.trim();
  if (player) out.player = player;
  const title = document.getElementById('optTvGuideTitle').value.trim();
  if (title) out.title = title;
  if (!document.getElementById('optTvGuideNext').checked) out.show_next = false;
  if (!document.getElementById('optTvGuideProgress').checked) out.show_progress = false;
  if (!document.getElementById('optTvGuideLogo').checked) out.show_logo = false;
  if (document.getElementById('optTvGuideTap').value === 'action') out.tap = 'action';
  const channels = [];
  for (const r of window._pendingTvGuide || []) {
    if (!r.entity_id) continue;
    // Keys the builder doesn't edit (IR, Harmony, a page…) stay as they were.
    const c = { ...r };
    delete c._data;
    TVG_ACTION_KEYS.forEach(k => delete c[k]);
    if (!c.name) delete c.name;
    if (!c.link) delete c.link;
    if (r.service) {
      c.service = r.service;
      if (r.target_entity) c.target_entity = r.target_entity;
      const raw = r._data !== undefined ? r._data : (r.data ? JSON.stringify(r.data) : '');
      if (raw) {
        try {
          c.data = JSON.parse(raw);
        } catch (e) {
          alert(I18N.t('web_card_tvguide_data_bad', r.entity_id));
          return false;
        }
      }
    }
    channels.push(c);
  }
  if (channels.length) out.channels = channels;
  window._pendingTvGuide = [];
  return true;
}

function tvGuidePreviewHtml(card, idx) {
  const o = card.options || {};
  const names = Array.isArray(o.channels) && o.channels.length
    ? o.channels.map(c => c.name || String(c.entity_id || '').replace(/^sensor\.epg_/, '').replace(/_/g, ' '))
    : ['France 2', 'TF1', 'France 3', 'Arte'];
  const rows = names.slice(0, 6).map((n, i) => `
    <div style="display:flex;gap:10px;align-items:center;padding:6px 0">
      ${o.show_logo !== false ? '<span style="width:34px;height:34px;border-radius:8px;background:#2a383d;flex:none"></span>' : ''}
      <div style="flex:1;min-width:0">
        <div style="display:flex;justify-content:space-between;color:#9FB6BD;font-size:11px"><span>${cxEsc(n)}</span><span>20:${String(10 + i * 5).padStart(2, '0')} – 21:${String(5 + i * 5).padStart(2, '0')}</span></div>
        <div style="color:#E6F1F3;font-size:13px;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${I18N.t('web_card_tvguide_preview_now')}</div>
        ${o.show_progress !== false ? `<div style="height:3px;border-radius:2px;background:#2a383d;margin:3px 0"><div style="height:3px;width:${30 + i * 12}%;background:#00E5FF;border-radius:2px"></div></div>` : ''}
        ${o.show_next !== false ? `<div style="color:#9FB6BD;font-size:11px">${I18N.t('web_card_tvguide_preview_next')}</div>` : ''}
      </div>
    </div>`).join('');
  return `
    <div class="card">
      <div class="card-title"><span>${cxEsc(o.title || I18N.t('web_modal_type_tv_guide'))}</span><span><span class="remove" style="color:#00E5FF" onclick="editCard(${idx})">✎</span> <span class="remove" onclick="removeCard(${idx})">✕</span></span></div>
      ${rows}
    </div>`;
}

/** A → Z by the name shown (the row's, else the guide's, else the entity). */
function sortTvGuideRows() {
  syncTvGuideRows();
  const nameOf = r => (r.name || (tvGuideKnown.find(c => c.entity === r.entity_id) || {}).name || r.entity_id || '').toLowerCase();
  (window._pendingTvGuide || []).sort((a, b) => nameOf(a).localeCompare(nameOf(b)));
  renderTvGuideRows();
}

/** The list as text, one channel per line in order: "entity | name" (1.6.0). */
function tvGuideTextFill() {
  syncTvGuideRows();
  document.getElementById('tvgText').value = (window._pendingTvGuide || [])
    .filter(r => r.entity_id)
    .map(r => r.link ? `${r.entity_id} | ${r.name || ''} | ${r.link}` : r.name ? `${r.entity_id} | ${r.name}` : r.entity_id)
    .join('\n');
}

/**
 * The text back as the list: its order, its names; a channel already there keeps its action. A
 * line without "sensor.epg_" is taken as a channel id ("France2.fr").
 */
function tvGuideTextApply() {
  syncTvGuideRows();
  const before = new Map((window._pendingTvGuide || []).map(r => [r.entity_id, r]));
  const rows = [];
  const seen = new Set();
  document.getElementById('tvgText').value.split('\n').forEach(line => {
    const [rawId, ...rest] = line.split('|');
    let id = (rawId || '').trim();
    if (!id) return;
    if (!id.includes('.')) id = tvGuideEntityOf(id);
    else if (!id.startsWith('sensor.')) id = tvGuideEntityOf(id);
    if (seen.has(id)) return;
    seen.add(id);
    // "entity | name | link": a link may hold "|" itself, so everything after the second bar.
    const name = (rest[0] || '').trim();
    const link = rest.slice(1).join('|').trim();
    const row = { ...(before.get(id) || {}), entity_id: id, name };
    if (rest.length > 1) row.link = link;
    rows.push(row);
  });
  window._pendingTvGuide = rows;
  renderTvGuideRows();
  document.getElementById('tvgTextBox').open = false;
}

/** A guide channel id ("France2.fr") as its entity, as the remote names it (epg/EpgEntities.kt). */
function tvGuideEntityOf(channelId) {
  const slug = channelId.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  return 'sensor.epg_' + (slug || 'channel');
}
