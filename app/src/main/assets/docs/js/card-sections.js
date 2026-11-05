// ---- Card forms in folding sections (1.4.9) ----------------------------------------
//
// The big card forms (media player, TV remote, Apple TV remote) grew to dozens of options in one
// long column. Once a form is drawn, its fields are moved into folding sections — Essentials
// (open), Display, Controls, Apps & sources, Library, Advanced — each with a count of the
// settings changed from a new card's. Nothing in the forms changes: the fields keep their ids,
// so every fill / collect function in cards.js and cards-extra.js works as before.
//
// A field is placed by the first id in it (CARD_SECTIONS). A label or heading goes with the next
// field, a hint with the one before; an id not listed stays with the one before.

const CARD_SECTION_ORDER = ['essential', 'display', 'controls', 'apps', 'library', 'advanced'];

const CARD_SECTIONS = {
  media_player: {
    essential: ['optName', 'optEntityId', 'optMediaVariant'],
    display: ['optMediaUseInfo', 'optMediaShowVolume', 'mediaArtworkField'],
    controls: ['optMediaCtrlOnOff', 'optMediaVolMute', 'kpTarget', 'kpRemoteBox', 'kpIrBox', 'kpHarmonyBox', 'kpCommandList'],
    apps: ['optMediaSources', 'mediaAppsField'],
    library: ['optMediaMusicSearch', 'mediaMusicSearchHint', 'optMediaQueue', 'mediaQueueHint', 'mediaMusicSearchFields'],
    advanced: ['mediaTopButtonsField']
  },
  tv_remote: {
    essential: ['optName', 'optRemoteEntity', 'tvRemoteEntities', 'optMediaEntity', 'optTvLayout'],
    display: ['optTvNowPlaying', 'optTvNowPlayingEntity', 'optTvNowPlayingTap', 'optTvShowSkip', 'optTvTransport'],
    controls: ['optTvShowDpad', 'optTvShowKeypad', 'tvKeypadBox', 'optTvShowVolume', 'optMuteEntity'],
    apps: ['optTvShowSources', 'optTvAppsStyle', 'optTvShowApps', 'tvAppPackages', 'tvAppsList'],
    advanced: ['tvCommandList']
  },
  apple_tv_remote: {
    essential: ['atvSourceBox', 'atvHarmonyPicker', 'atvLayout'],
    display: ['atvRichBox', 'atvEntity'],
    controls: ['atvShowClickpad', 'atvButtonsBox'],
    apps: ['atvAppsList', 'atvSourceHints', 'atvShowApps']
  }
};

/** The id a form child is placed by: its own, else the first one inside it. */
function cardSectionAnchor(el) {
  if (el.id) return el.id;
  const inner = el.querySelector && el.querySelector('[id]');
  return inner ? inner.id : null;
}

/** Moves the fields of the form just drawn for [type] into folding sections. */
function applyCardSections(type) {
  const map = CARD_SECTIONS[type];
  const container = document.getElementById('dynamicCardInputs');
  if (!map || !container || container.querySelector('.form-section')) return;
  const sectionOf = {};
  Object.entries(map).forEach(([sec, ids]) => ids.forEach(id => { sectionOf[id] = sec; }));
  const groups = {};
  let current = 'essential';
  let pending = [];
  [...container.children].forEach(el => {
    const anchor = cardSectionAnchor(el);
    const tag = el.tagName;
    if (!anchor && (tag === 'LABEL' || tag === 'H4' || pending.length)) {
      pending.push(el); // a label, heading (and what follows it) goes with the next field
      return;
    }
    if (anchor && sectionOf[anchor]) current = sectionOf[anchor];
    (groups[current] = groups[current] || []).push(...pending, el);
    pending = [];
  });
  if (pending.length) (groups[current] = groups[current] || []).push(...pending);

  container.innerHTML = '';
  CARD_SECTION_ORDER.filter(sec => groups[sec] && groups[sec].length).forEach(sec => {
    const details = document.createElement('details');
    details.className = 'form-section';
    details.dataset.section = sec;
    if (sec === 'essential') details.open = true;
    details.innerHTML = `<summary><span class="form-section-name">${I18N.t('web_form_section_' + sec)}</span><span class="form-section-badge" hidden></span></summary>`;
    const body = document.createElement('div');
    body.className = 'form-section-body';
    groups[sec].forEach(el => body.appendChild(el));
    details.appendChild(body);
    container.appendChild(details);
  });
  snapshotCardSectionDefaults();
}

function cardFieldState(el) {
  if (el.type === 'checkbox' || el.type === 'radio') return el.checked ? '1' : '0';
  return el.value;
}

/** A new card's values, to count what an edit (or the user) changed. */
function snapshotCardSectionDefaults() {
  document.querySelectorAll('#dynamicCardInputs .form-section [id]').forEach(el => {
    if ('value' in el && el.tagName !== 'BUTTON' && el.tagName !== 'DATALIST') el.dataset.formDefault = cardFieldState(el);
  });
}

/** Each section's badge: how many of its settings differ from a new card's (plus its app rows). */
function refreshCardSections() {
  document.querySelectorAll('#dynamicCardInputs .form-section').forEach(sec => {
    let n = 0;
    sec.querySelectorAll('[data-form-default]').forEach(el => { if (cardFieldState(el) !== el.dataset.formDefault) n++; });
    const apps = sec.querySelectorAll('.tv-app-row, #atvAppsList .list-item').length;
    const badge = sec.querySelector('.form-section-badge');
    const parts = [];
    if (n) parts.push(I18N.t(n === 1 ? 'web_form_section_changed_one' : 'web_form_section_changed_many', n));
    if (apps && sec.dataset.section === 'apps') parts.push(I18N.t(apps === 1 ? 'web_form_section_apps_one' : 'web_form_section_apps_many', apps));
    badge.textContent = parts.join(' · ');
    badge.hidden = !parts.length;
    // A section whose fields are all hidden (the media player's full-view options on a compact
    // card) is hidden too.
    const body = sec.querySelector('.form-section-body');
    sec.hidden = ![...body.children].some(c => c.tagName !== 'DATALIST' && c.style.display !== 'none');
  });
}

// The forms are drawn and filled by cards.js: sections are applied right after, and the badges
// follow every change.
(function wrapCardForms() {
  const draw = window.updateCardFormInputs;
  window.updateCardFormInputs = function () {
    draw.apply(this, arguments);
    const select = document.getElementById('cardTypeSelect');
    applyCardSections(select && select.value);
    refreshCardSections();
  };
  const fill = window.fillCardForm;
  window.fillCardForm = function () {
    const out = fill.apply(this, arguments);
    refreshCardSections();
    setTimeout(refreshCardSections, 400); // some fields fill asynchronously (keypad pickers)
    return out;
  };
  document.addEventListener('DOMContentLoaded', () => {
    const box = document.getElementById('dynamicCardInputs');
    if (!box) return;
    ['input', 'change', 'click'].forEach(ev => box.addEventListener(ev, () => setTimeout(refreshCardSections, 0)));
  });
})();
