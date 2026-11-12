// ---- Info card (1.5.6, `info`) -------------------------------------------------------------
//
// Images and text from anything in Home Assistant (a TMDB sensor's poster and synopsis, an EPG, a
// printer's progress…): a list of blocks, each image URL and each text a Home Assistant template
// rendered by Home Assistant. Mirrors InfoCard.kt. The form edits window._pendingInfoBlocks (same
// pattern as the monitor rows); the preview asks the remote to render each template once (POST
// /ha-template) and shows the template itself when it can't (the builder opened off the remote).

const INFO_SIZES = ['title', 'subtitle', 'body', 'caption'];
const INFO_RATIOS = ['poster', 'wide', 'square'];
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
    </div>
  `;
}

function fillInfoForm(o) {
  document.getElementById('optName').value = o.name || '';
  document.getElementById('optInfoLayout').value = o.layout === 'side' ? 'side' : 'stack';
  window._pendingInfoBlocks = (Array.isArray(o.blocks) ? o.blocks : []).map(b => ({ ...b }));
  // What the form doesn't show is kept as it is.
  window._infoExtra = Object.fromEntries(Object.entries(o).filter(([k]) => !['name', 'layout', 'blocks'].includes(k)));
  renderInfoBlocks();
}

/** Writes the form into [out]; false (with a message) when there is no block. */
function collectInfoForm(out) {
  const blocks = (window._pendingInfoBlocks || [])
    .map(b => {
      const value = String(b.type === 'image' ? b.url || '' : b.text || '').trim();
      if (!value) return null;
      const c = { ...b };
      if (b.type === 'image') c.url = value; else c.text = value;
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
  list.innerHTML = blocks.map((b, i) => {
    const image = b.type === 'image';
    const options = image
      ? select(i, 'ratio', INFO_RATIOS, b.ratio, 'poster')
      : `${select(i, 'size', INFO_SIZES, b.size, 'body')}${select(i, 'align', INFO_ALIGNS, b.align, 'start')}
         <label style="margin:0;display:flex;align-items:center;gap:4px">${I18N.t('web_card_info_lines')}
           <input type="number" min="0" max="20" value="${Number(b.lines) || 0}" style="width:56px;margin:0" oninput="updateInfoBlock(${i}, 'lines', Number(this.value) || 0)"></label>
         <label class="inline-check" style="margin:0"><input type="checkbox"${b.marquee ? ' checked' : ''} onchange="updateInfoBlock(${i}, 'marquee', this.checked)"> ${I18N.t('web_card_monitor_marquee')}</label>`;
    return `
    <div class="list-item" style="flex-direction:column;align-items:stretch;gap:6px">
      <div style="display:flex;align-items:center;gap:6px">
        <b style="flex:1">${I18N.t(image ? 'web_card_info_image' : 'web_card_info_text')}</b>
        <span class="remove" style="color:#00E5FF" title="${I18N.t('web_preview_move_up')}" onclick="moveInfoBlock(${i}, -1)">↑</span>
        <span class="remove" style="color:#00E5FF" title="${I18N.t('web_preview_move_down')}" onclick="moveInfoBlock(${i}, 1)">↓</span>
        <span class="remove" onclick="removeInfoBlock(${i})">✕</span>
      </div>
      <textarea rows="${image ? 2 : 3}" style="margin:0;font-family:monospace;font-size:12px" placeholder="${cxEsc(I18N.t(image ? 'web_card_info_url_ph' : 'web_card_info_text_ph'))}"
        oninput="updateInfoBlock(${i}, '${image ? 'url' : 'text'}', this.value)">${cxEsc(image ? b.url || '' : b.text || '')}</textarea>
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
  window._pendingInfoBlocks.push(type === 'image' ? { type, url: '' } : { type, text: '' });
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
    const lines = Number(b.lines) || 0;
    const clamp = b.marquee
      ? 'white-space:nowrap;overflow:hidden;text-overflow:clip'
      : lines ? `display:-webkit-box;-webkit-line-clamp:${lines};-webkit-box-orient:vertical;overflow:hidden` : 'white-space:pre-wrap';
    return `<div data-info-text="${cxEsc(b.text || '')}" style="${INFO_PREVIEW_TEXT[b.size] || INFO_PREVIEW_TEXT.body};text-align:${b.align || 'start'};${clamp};word-break:break-word">${cxEsc(b.text || '')}</div>`;
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
        el.style.display = el.textContent ? el.style.display : 'none';
      }
    });
  });
  root.querySelectorAll('[data-info-img]').forEach(el => {
    infoRenderTemplate(el.getAttribute('data-info-img')).then(data => {
      const url = data && !data.error ? String(data.result || '').trim() : '';
      if (!url || /(None|\/)$/.test(url)) return;
      // A Home Assistant path (/api/…) goes through the remote, which adds the token.
      const src = url.startsWith('/api/') ? '/ha-image?path=' + encodeURIComponent(url) : url;
      el.innerHTML = `<img src="${cxEsc(src)}" alt="" style="width:100%;height:100%;object-fit:cover" onerror="this.remove()">`;
    });
  });
}
