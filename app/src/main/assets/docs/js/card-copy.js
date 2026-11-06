// ---- Copy a card to other pages (1.4.9) ------------------------------------------
//
// A ⧉ next to each card in the preview: tick pages, choose top or bottom, and the same card
// (a deep copy) is added to each of them — e.g. a row of page buttons at the top of every page.
// Uses: dashboardData, currentActivePage, renderPreview, updateJsonOutput, I18N, cxEsc.

function openCardCopyDialog(idx) {
  const card = dashboardData.pages[currentActivePage].cards[idx];
  if (!card) return;
  let modal = document.getElementById('cardCopyModal');
  if (!modal) {
    modal = document.createElement('div');
    modal.id = 'cardCopyModal';
    modal.className = 'preview-modal';
    document.body.appendChild(modal);
  }
  const pages = dashboardData.pages.map((p, i) => ({ p, i })).filter(x => x.i !== currentActivePage);
  modal.innerHTML = `
    <div class="preview-modal-inner ha-dialog">
      <button class="preview-modal-close" onclick="closeCardCopyDialog()">${I18N.t('web_modal_close')}</button>
      <h2 style="margin-top:0">${I18N.t('web_card_copy_title')}</h2>
      <div class="section-box">
        <div class="hint" style="margin-bottom:10px">${I18N.t('web_card_copy_hint')}</div>
        ${pages.length ? `<div style="display:flex;gap:8px;margin-bottom:8px">
            <button type="button" class="secondary" onclick="setCardCopyAll(true)">${I18N.t('web_card_copy_all')}</button>
            <button type="button" class="secondary" onclick="setCardCopyAll(false)">${I18N.t('web_card_copy_none')}</button>
          </div>
          <div id="cardCopyPages" style="display:flex;flex-direction:column;gap:4px;max-height:40vh;overflow-y:auto">${pages.map(x =>
            `<label class="inline-check"><input type="checkbox" value="${x.i}"> ${cxEsc(x.p.name)}${x.p.parent ? ` <span style="color:#888">(${cxEsc(x.p.parent)})</span>` : ''}</label>`).join('')}</div>
          <label style="margin-top:12px">${I18N.t('web_card_copy_where')}</label>
          <select id="cardCopyWhere">
            <option value="top">${I18N.t('web_card_copy_top')}</option>
            <option value="bottom">${I18N.t('web_card_copy_bottom')}</option>
          </select>
          <div class="btn-row" style="margin-top:12px">
            <button type="button" onclick="copyCardToPages(${idx})">${I18N.t('web_card_copy_btn')}</button>
            <button type="button" class="secondary" onclick="closeCardCopyDialog()">${I18N.t('web_cancel')}</button>
          </div>` : `<div class="hint">${I18N.t('web_card_copy_no_pages')}</div>`}
      </div>
    </div>`;
  modal.classList.add('open');
}

function closeCardCopyDialog() {
  const modal = document.getElementById('cardCopyModal');
  if (modal) modal.classList.remove('open');
}

function setCardCopyAll(on) {
  document.querySelectorAll('#cardCopyPages input[type=checkbox]').forEach(c => { c.checked = on; });
}

function copyCardToPages(idx) {
  const card = dashboardData.pages[currentActivePage].cards[idx];
  const targets = [...document.querySelectorAll('#cardCopyPages input:checked')].map(c => Number(c.value));
  if (!card || !targets.length) { alert(I18N.t('web_card_copy_pick')); return; }
  const top = document.getElementById('cardCopyWhere').value === 'top';
  targets.forEach(i => {
    const page = dashboardData.pages[i];
    if (!page) return;
    page.cards = page.cards || [];
    const copy = JSON.parse(JSON.stringify(card));
    if (top) page.cards.unshift(copy); else page.cards.push(copy);
  });
  closeCardCopyDialog();
  renderPreview();
  updateJsonOutput();
  if (typeof showToast === 'function') showToast(I18N.t('web_card_copy_done', targets.length));
}
