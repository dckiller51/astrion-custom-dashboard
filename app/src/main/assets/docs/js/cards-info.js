// ---- Info card (1.5.6, `info`) -------------------------------------------------------------
//
// Images and text from anything in Home Assistant (a TMDB sensor's poster and synopsis, an EPG, a
// printer's progress…): a list of blocks, each image URL and each text a Home Assistant template
// rendered by Home Assistant. Mirrors InfoCard.kt. The form edits window._pendingInfoBlocks (same
// pattern as the monitor rows); the preview asks the remote to render each template once (POST
// /ha-template) and shows the template itself when it can't (the builder opened off the remote).

const INFO_SIZES = ['title', 'subtitle', 'body', 'caption'];
const INFO_RATIOS = ['poster', 'wide', 'square'];
const INFO_LIST_RATIOS = ['poster', 'square', 'round'];
/** The field each block type's template goes in. */
const INFO_MAIN = { image: 'url', text: 'text', list: 'source' };
const INFO_ALIGNS = ['start', 'center', 'end'];

function infoFormHtml() {
  return `
    <label>${I18N.t('web_card_name_optional_friendly')}</label><input type="text" id="optName" placeholder="${I18N.t('web_card_info_name_ph')}">
    <label>${I18N.t('web_card_layout')}</label>
    <select id="optInfoLayout">
      <option value="stack">${I18N.t('web_card_info_layout_stack')}</option>
      <option value="side">${I18N.t('web_card_info_layout_side')}</option>
    </select>
    <div class="hint">${I18N.t('web_card_info_hint')}</div>
    <label>${I18N.t('web_card_info_blocks')}</label>
    <div id="infoBlocksList"></div>
    <div style="display:flex;gap:8px;flex-wrap:wrap">
      <button type="button" class="secondary" onclick="addInfoBlock('image')">${I18N.t('web_card_info_add_image')}</button>
      <button type="button" class="secondary" onclick="addInfoBlock('text')">${I18N.t('web_card_info_add_text')}</button>
      <button type="button" class="secondary" onclick="addInfoBlock('list')">${I18N.t('web_card_info_add_list')}</button>
    </div>
    <label>${I18N.t('web_card_info_visible_if')}</label>
    <input type="text" id="optInfoVisibleIf" style="font-family:monospace;font-size:12px" placeholder="{{ states('media_player.kodi') }}">
    <div class="hint">${I18N.t('web_card_info_visible_if_hint')}</div>
    <label class="inline-check"><input type="checkbox" id="optInfoHideEmpty" checked> ${I18N.t('web_card_info_hide_empty')}</label>
  `;
}

function fillInfoForm(o) {
  document.getElementById('optName').value = o.name || '';
  document.getElementById('optInfoLayout').value = o.layout === 'side' ? 'side' : 'stack';
  document.getElementById('optInfoVisibleIf').value = o.visible_if || '';
  document.getElementById('optInfoHideEmpty').checked = o.hide_when_empty !== false;
  window._pendingInfoBlocks = (Array.isArray(o.blocks) ? o.blocks : []).map(b => ({ ...b }));
  // What the form doesn't show is kept as it is.
  window._infoExtra = Object.fromEntries(Object.entries(o).filter(([k]) => !['name', 'layout', 'blocks', 'visible_if', 'hide_when_empty'].includes(k)));
  renderInfoBlocks();
}

/** Writes the form into [out]; false (with a message) when there is no block. */
function collectInfoForm(out) {
  const blocks = (window._pendingInfoBlocks || [])
    .map(b => {
      const field = INFO_MAIN[b.type] || 'text';
      const value = String(b[field] || '').trim();
      if (!value) return null;
      const c = { ...b, [field]: value };
      if (c.hide_missing !== false) delete c.hide_missing;
      if (!c.limit) delete c.limit;
      // Defaults left out, as the remote reads them.
      if (c.ratio === 'poster') delete c.ratio;
      if (c.size === 'body') delete c.size;
      if (c.align === 'start') delete c.align;
      if (!c.lines) delete c.lines;
      if (!c.marquee) delete c.marquee;
      return c;
    })
    .filter(Boolean);
  if (!blocks.length) {
    alert(I18N.t('web_card_info_err_blocks'));
    return false;
  }
  Object.assign(out, window._infoExtra || {});
  const name = document.getElementById('optName').value.trim();
  if (name) out.name = name;
  if (document.getElementById('optInfoLayout').value === 'side') out.layout = 'side'; else delete out.layout;
  const visibleIf = document.getElementById('optInfoVisibleIf').value.trim();
  if (visibleIf) out.visible_if = visibleIf; else delete out.visible_if;
  if (document.getElementById('optInfoHideEmpty').checked) delete out.hide_when_empty; else out.hide_when_empty = false;
  out.blocks = blocks;
  window._pendingInfoBlocks = [];
  window._infoExtra = null;
  return true;
}

function renderInfoBlocks() {
  const list = document.getElementById('infoBlocksList');
  if (!list) return;
  const blocks = window._pendingInfoBlocks || [];
  if (!blocks.length) {
    list.innerHTML = `<div class="hint">${I18N.t('web_card_info_no_blocks')}</div>`;
    return;
  }
  const select = (i, field, values, current, def) =>
    `<select style="margin:0;width:auto" onchange="updateInfoBlock(${i}, '${field}', this.value)">${values.map(v =>
      `<option value="${v}"${(current || def) === v ? ' selected' : ''}>${I18N.t('web_card_info_' + field + '_' + v)}</option>`).join('')}</select>`;
  const field = (i, key, value, ph) =>
    `<input type="text" value="${cxEsc(value || '')}" placeholder="${cxEsc(ph)}" style="margin:0;flex:1;min-width:120px;font-family:monospace;font-size:12px" oninput="updateInfoBlock(${i}, '${key}', this.value)">`;
  list.innerHTML = blocks.map((b, i) => {
    const image = b.type === 'image';
    const isList = b.type === 'list';
    const options = image
      ? select(i, 'ratio', INFO_RATIOS, b.ratio, 'poster')
      : isList
        ? `${field(i, 'image', b.image, 'https://image.tmdb.org/t/p/w185{profile_path}')}
           ${field(i, 'title', b.title, '{name}')}${field(i, 'subtitle', b.subtitle, '{character}')}
           <span style="flex-basis:100%;height:0"></span>
           ${select(i, 'lratio', INFO_LIST_RATIOS, b.ratio, 'poster').replace("'lratio'", "'ratio'")}
           <label style="margin:0;display:flex;align-items:center;gap:4px">${I18N.t('web_card_info_limit')}
             <input type="number" min="1" max="30" value="${Number(b.limit) || 10}" style="width:56px;margin:0" oninput="updateInfoBlock(${i}, 'limit', Number(this.value) || 10)"></label>
           <div class="hint" style="flex-basis:100%;margin:0">${I18N.t('web_card_info_list_hint')}</div>`
        : `${select(i, 'size', INFO_SIZES, b.size, 'body')}${select(i, 'align', INFO_ALIGNS, b.align, 'start')}
         <label style="margin:0;display:flex;align-items:center;gap:4px">${I18N.t('web_card_info_lines')}
           <input type="number" min="0" max="20" value="${Number(b.lines) || 0}" style="width:56px;margin:0" oninput="updateInfoBlock(${i}, 'lines', Number(this.value) || 0)"></label>
         <label class="inline-check" style="margin:0"><input type="checkbox"${b.marquee ? ' checked' : ''} onchange="updateInfoBlock(${i}, 'marquee', this.checked)"> ${I18N.t('web_card_monitor_marquee')}</label>
         <label class="inline-check" style="margin:0"><input type="checkbox"${b.hide_missing !== false ? ' checked' : ''} onchange="updateInfoBlock(${i}, 'hide_missing', this.checked)"> ${I18N.t('web_card_info_hide_missing')}</label>`;
    const main = INFO_MAIN[b.type] || 'text';
    return `
    <div class="list-item" style="flex-direction:column;align-items:stretch;gap:6px">
      <div style="display:flex;align-items:center;gap:6px">
        <b style="flex:1">${I18N.t(image ? 'web_card_info_image' : isList ? 'web_card_info_list' : 'web_card_info_text')}</b>
        <span class="remove" style="color:#00E5FF" title="${I18N.t('web_preview_move_up')}" onclick="moveInfoBlock(${i}, -1)">↑</span>
        <span class="remove" style="color:#00E5FF" title="${I18N.t('web_preview_move_down')}" onclick="moveInfoBlock(${i}, 1)">↓</span>
        <span class="remove" onclick="removeInfoBlock(${i})">✕</span>
      </div>
      <textarea rows="${image || isList ? 2 : 3}" style="margin:0;font-family:monospace;font-size:12px" placeholder="${cxEsc(isList ? "{{ state_attr('sensor.kodi_movie_credits', 'cast') }}" : I18N.t(image ? 'web_card_info_url_ph' : 'web_card_info_text_ph'))}"
        oninput="updateInfoBlock(${i}, '${main}', this.value)">${cxEsc(b[main] || '')}</textarea>
      <div style="display:flex;gap:6px;flex-wrap:wrap;align-items:center">${options}</div>
    </div>`;
  }).join('');
}

function updateInfoBlock(i, field, value) {
  const b = (window._pendingInfoBlocks || [])[i];
  if (b) b[field] = value;
}

function addInfoBlock(type) {
  window._pendingInfoBlocks = window._pendingInfoBlocks || [];
  window._pendingInfoBlocks.push({ type, [INFO_MAIN[type]]: '' });
  renderInfoBlocks();
  const areas = document.querySelectorAll('#infoBlocksList textarea');
  if (areas.length) areas[areas.length - 1].focus();
}

function removeInfoBlock(i) {
  window._pendingInfoBlocks.splice(i, 1);
  renderInfoBlocks();
}

function moveInfoBlock(i, delta) {
  const blocks = window._pendingInfoBlocks || [];
  const j = i + delta;
  if (j < 0 || j >= blocks.length) return;
  [blocks[i], blocks[j]] = [blocks[j], blocks[i]];
  renderInfoBlocks();
}

// ---- preview ------------------------------------------------------------------------------

/** Templates already rendered by the remote: template → {result} | {error} | a pending Promise. */
const infoTemplateCache = {};

function infoIsTemplate(s) {
  return /\{\{|\{%/.test(s || '');
}

/** [template] rendered by the remote's Home Assistant; null when the builder can't ask (off the remote). */
function infoRenderTemplate(template) {
  if (!infoIsTemplate(template)) return Promise.resolve({ result: template });
  const cached = infoTemplateCache[template];
  if (cached) return cached instanceof Promise ? cached : Promise.resolve(cached);
  const p = fetch('/ha-template', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ template }).toString()
  })
    .then(res => res.json().then(data => (res.ok || data.error ? data : null)))
    .catch(() => null)
    .then(data => {
      if (data) infoTemplateCache[template] = data; else delete infoTemplateCache[template];
      return data;
    });
  infoTemplateCache[template] = p;
  return p;
}

const INFO_PREVIEW_TEXT = {
  title: 'font-size:17px;font-weight:600;color:#E6EEF2',
  subtitle: 'font-size:14px;font-weight:500;color:#E6EEF2',
  body: 'font-size:13px;color:#E6EEF2',
  caption: 'font-size:11px;color:#9FB6BD'
};

/** The card in the preview, its templates shown as they are until the remote renders them ([infoResolvePreview]). */
function infoPreviewHtml(card, idx) {
  const o = card.options || {};
  const blocks = Array.isArray(o.blocks) ? o.blocks : [];
  const ratio = r => (r === 'wide' ? '16 / 9' : r === 'square' ? '1 / 1' : '2 / 3');
  const blockHtml = b => {
    if (b.type === 'image') {
      return `<div data-info-img="${cxEsc(b.url || '')}" style="aspect-ratio:${ratio(b.ratio)};border-radius:8px;background:#2a383d;overflow:hidden;display:flex;align-items:center;justify-content:center;color:#748790;font-size:20px">🖼</div>`;
    }
    if (b.type === 'list') {
      const cfg = { image: b.image || '', title: b.title || '', subtitle: b.subtitle || '', limit: Number(b.limit) || 10, ratio: b.ratio || 'poster' };
      const tile = `<span style="width:44px;flex:none"><span style="display:block;aspect-ratio:${cfg.ratio === 'poster' ? '2 / 3' : '1 / 1'};border-radius:${cfg.ratio === 'round' ? '50%' : '6px'};background:#2a383d"></span></span>`;
      return `<div data-info-list="${cxEsc(b.source || '')}" data-info-list-cfg="${cxEsc(JSON.stringify(cfg))}" style="display:flex;gap:6px;overflow:hidden">${tile.repeat(4)}</div>`;
    }
    const lines = Number(b.lines) || 0;
    const clamp = b.marquee
      ? 'white-space:nowrap;overflow:hidden;text-overflow:clip'
      : lines ? `display:-webkit-box;-webkit-line-clamp:${lines};-webkit-box-orient:vertical;overflow:hidden` : 'white-space:pre-wrap';
    return `<div data-info-text="${cxEsc(b.text || '')}" data-info-hide-missing="${b.hide_missing !== false}" style="${INFO_PREVIEW_TEXT[b.size] || INFO_PREVIEW_TEXT.body};text-align:${b.align || 'start'};${clamp};word-break:break-word">${cxEsc(b.text || '')}</div>`;
  };
  const firstImage = o.layout === 'side' ? blocks.findIndex(b => b.type === 'image') : -1;
  const column = list => `<div style="display:flex;flex-direction:column;gap:6px;min-width:0;flex:1">${list.map(blockHtml).join('')}</div>`;
  const body = firstImage >= 0
    ? `<div style="display:flex;gap:10px"><div style="width:38%;flex:none">${blockHtml(blocks[firstImage])}</div>${column(blocks.filter((_, i) => i !== firstImage))}</div>`
    : column(blocks);
  return `
    <div class="card-title"><span>info${o.layout === 'side' ? ' · side' : ''}</span><span><span class="remove" style="color:#00E5FF" onclick="editCard(${idx})">✎</span> <span class="remove" onclick="removeCard(${idx})">✕</span></span></div>
    ${blocks.length ? body : `<small style="color:#888">${I18N.t('web_preview_none')}</small>`}`;
}

/** Puts what the remote's Home Assistant renders into the preview [root] drawn by [infoPreviewHtml]. */
function infoResolvePreview(root) {
  root.querySelectorAll('[data-info-text]').forEach(el => {
    const tpl = el.getAttribute('data-info-text');
    if (!infoIsTemplate(tpl)) return;
    el.style.opacity = '0.6';
    infoRenderTemplate(tpl).then(data => {
      el.style.opacity = '';
      if (!data) return;
      if (data.error) {
        el.textContent = '⚠ ' + data.error;
        el.style.color = '#FF6B6B';
      } else {
        el.textContent = String(data.result || '').trim();
        // Hidden as on the remote: empty, or a value missing (None, unknown, unavailable).
        const missing = el.getAttribute('data-info-hide-missing') !== 'false' &&
          (/\bNone\b/.test(el.textContent) || ['unknown', 'unavailable'].includes(el.textContent.toLowerCase()));
        el.style.display = el.textContent && !missing ? el.style.display : 'none';
      }
    });
  });
  root.querySelectorAll('[data-info-img]').forEach(el => {
    infoRenderTemplate(el.getAttribute('data-info-img')).then(data => {
      const url = data && !data.error ? String(data.result || '').trim() : '';
      if (!url || /(None|null|\/)$/.test(url) || url.includes('{')) return;
      // A Home Assistant path (/api/…) goes through the remote, which adds the token.
      const src = url.startsWith('/api/') ? '/ha-image?path=' + encodeURIComponent(url) : url;
      el.innerHTML = `<img src="${cxEsc(src)}" alt="" style="width:100%;height:100%;object-fit:cover" onerror="this.remove()">`;
    });
  });
  root.querySelectorAll('[data-info-list]').forEach(el => {
    const cfg = JSON.parse(el.getAttribute('data-info-list-cfg') || '{}');
    infoRenderTemplate(el.getAttribute('data-info-list')).then(data => {
      if (!data || data.error) return;
      let items = [];
      try { items = JSON.parse(String(data.result || '')); } catch (e) { items = []; }
      if (!Array.isArray(items) || !items.length) { el.style.display = 'none'; return; }
      // The item's fields as {field}, as InfoBlock.fill does; an image with a field left unfilled isn't shown.
      const fill = (p, it, keep) => String(p || '').replace(/\{([A-Za-z0-9_]+)\}/g, (m, k) => it && it[k] != null ? String(it[k]) : keep ? m : '');
      const round = cfg.ratio === 'round';
      el.innerHTML = items.slice(0, cfg.limit || 10).map(it => {
        const url = fill(cfg.image, it, true).trim();
        const ok = url && !/(None|null|\/)$/.test(url) && !url.includes('{');
        return `<span style="width:44px;flex:none;font-size:8px;line-height:1.15">
          <span style="display:block;aspect-ratio:${cfg.ratio === 'poster' ? '2 / 3' : '1 / 1'};border-radius:${round ? '50%' : '6px'};background:#2a383d;overflow:hidden">${ok ? `<img src="${cxEsc(url)}" alt="" style="width:100%;height:100%;object-fit:cover" onerror="this.remove()">` : ''}</span>
          <span style="display:block;color:#E6EEF2;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${cxEsc(fill(cfg.title, it))}</span>
          <span style="display:block;color:#9FB6BD;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${cxEsc(fill(cfg.subtitle, it))}</span></span>`;
      }).join('');
    });
  });
}
