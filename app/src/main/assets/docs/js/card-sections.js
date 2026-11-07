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
//
// 1.5.0: every card with more than a handful of settings gets its sections (lights, covers,
// climate, grids, Plex, Xbox…), its list of buttons / scenes / rooms in a "Content" section, and
// on every form the tick boxes that follow each other share a row instead of one line each.

const CARD_SECTION_ORDER = ['essential', 'items', 'display', 'controls', 'apps', 'library', 'advanced', 'visibility'];

/** Open when the form is drawn: the essentials and the list of what the card holds. */
const CARD_SECTION_OPEN = ['essential', 'items'];

/** Up to this many fields, a form opens all its sections (but Advanced). */
const SMALL_FORM = 16;

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
    apps: ['optTvShowSources', 'optTvShowBrowse', 'optTvAppsStyle', 'optTvShowApps', 'tvAppPackages', 'tvAppsList'],
    advanced: ['tvCommandList']
  },
  apple_tv_remote: {
    essential: ['atvSourceBox', 'atvHarmonyPicker', 'atvLayout'],
    display: ['atvRichBox', 'atvEntity'],
    controls: ['atvShowClickpad', 'atvButtonsBox'],
    apps: ['atvAppsList', 'atvSourceHints', 'atvShowApps']
  },
  light: {
    essential: ['optName', 'optEntityId'],
    display: ['optLightLayout', 'optLightUseColor', 'optLightShowBrightness'],
    controls: ['optLightCtrlBrightness', 'optLightCollapsible']
  },
  cover: {
    essential: ['optName', 'optEntityId'],
    display: ['optIcon', 'optCoverLayout'],
    controls: ['optCoverCtrlButtons']
  },
  fan: {
    essential: ['optName', 'optEntityId'],
    display: ['optFanStyle', 'optFanShowCaptions'],
    controls: ['optFanPresetModes', 'fanStepWrap']
  },
  climate: {
    essential: ['optName', 'optEntityId'],
    controls: ['optHvacModes', 'optHvacModeStyle', 'optFanModes', 'optFanModeStyle', 'optSwingModes', 'optSwingModeStyle'],
    display: ['optShowCaptions']
  },
  camera: {
    essential: ['optName', 'optEntityId'],
    display: ['optCameraMode', 'optCameraInterval', 'optCameraAspect', 'optCameraFit']
  },
  zidoo: {
    essential: ['optName', 'optZidooEntity'],
    display: ['optZidooQuality', 'optZidooApps'],
    controls: ['optZidooSources', 'optZidooKeypad', 'optZidooTracks', 'optZidooQueue', 'optZidooIo', 'optZidooSkip', 'optZidooRepeat', 'optZidooVolume']
  },
  plex: {
    essential: ['optPlexHost', 'optPlexToken'],
    controls: ['optPlexMediaEntity', 'optPlexSource', 'optPlexPlayEntity', 'optPlexPlayContentType'],
    display: ['optPlexShowOnDeck', 'optPlexItemsPerRow', 'optPlexShowSearch']
  },
  xbox: {
    essential: ['xboxSource', 'xboxAccountBox'],
    display: ['xboxProfileBox', 'xboxShowGame', 'xboxGame', 'xboxPicture'],
    items: ['xboxShowStats', 'xboxPerRow', 'xboxStatsList', 'xboxAddStat'],
    advanced: ['xboxSyncBox']
  },
  button_grid: {
    display: ['optColumns', 'optIconPosition', 'optShowLabels', 'optTileHeight', 'optFontSize', 'optIconSize', 'optIconFit', 'optIconTrim', 'optCenterLastRow'],
    items: ['gridItemsList', 'giName']
  },
  scene_grid: {
    display: ['optColumns', 'optIconPosition', 'optShowLabels', 'optIconFill', 'optTileHeight', 'optFontSize', 'optIconSize', 'optIconFit', 'optIconTrim', 'optCenterLastRow'],
    items: ['gridItemsList', 'giName']
  },
  vacuum: {
    essential: ['optName', 'optEntityId'],
    display: ['optMapImage', 'optMapRotation', 'optMapHeight'],
    items: ['vacuumRoomsList', 'vrName'],
    advanced: ['optRoomCleanDomain']
  },
  clock_weather: {
    essential: ['optEntityId'],
    display: ['optTimeFormat', 'optForecastRows', 'optCalendarEntity']
  },
  monitor: {
    essential: ['optMonitorTitle'],
    display: ['optMonitorLayout', 'optMonitorMarquee'],
    items: ['monitorRowsList']
  },
  picture_elements: {
    essential: ['optPeImage', 'pePlanWrap'],
    items: ['peElementsList'],
    advanced: ['optPeAspect']
  }
};

/** A tick box on its own line (a label holding a checkbox and its text, nothing else to fill in). */
function isCheckLabel(el) {
  if (el.tagName !== 'LABEL') return false;
  const box = el.querySelector('input[type=checkbox]');
  return !!box && !el.querySelector('select, textarea, input:not([type=checkbox])');
}

/** A part of a form that takes the whole width: a list, an editor box, a text area. */
function isWideControl(el) {
  if (!el || !el.matches) return false;
  if (el.matches('textarea, details, .section-box, .hint-row, .form-check-row') || el.querySelector('textarea, .section-box, .list-item')) return true;
  return /(List|Box|Wrap|Field|Fields)$/.test(el.id || '');
}

/**
 * A section's fields two by two (1.5.0, the look of the device page): each label with its field
 * and its hints becomes one cell of a two-column grid; lists, editors and tick-box rows take the
 * whole width. The fields keep their ids and their order.
 */
function gridFields(body) {
  const cells = [];
  let field = null;
  [...body.children].forEach(el => {
    const tag = el.tagName;
    if (el.classList.contains('form-check-row') || isCheckLabel(el)) {
      field = null;
      cells.push({ els: [el], wide: true });
    } else if (tag === 'LABEL' || tag === 'H4') {
      field = { els: [el], wide: false };
      cells.push(field);
    } else if (field && (field.els.length === 1 || el.classList.contains('hint'))) {
      field.els.push(el);
      if (isWideControl(el)) field.wide = true;
    } else {
      field = null;
      cells.push({ els: [el], wide: true });
    }
  });
  body.classList.add('form-grid');
  cells.forEach(cell => {
    const box = document.createElement('div');
    box.className = 'form-field' + (cell.wide ? ' wide' : '');
    cell.els[0].before(box);
    cell.els.forEach(el => box.appendChild(el));
  });
}

/** Tick boxes that follow each other share a wrapping row (1.5.0), on any card form. */
function groupCheckRows(parent) {
  if (!parent) return;
  const kids = [...parent.children];
  let run = [];
  const flush = () => {
    if (run.length > 1) {
      const row = document.createElement('div');
      row.className = 'form-check-row';
      run[0].before(row);
      run.forEach(el => row.appendChild(el));
    }
    run = [];
  };
  kids.forEach(el => {
    if (isCheckLabel(el)) run.push(el);
    else flush();
  });
  flush();
}

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
  if (!container || container.querySelector('.form-section, .form-check-row')) return;
  if (!map) {
    const narrow = container.closest('.ha-dialog');
    if (narrow) narrow.classList.remove('form-wide');
    groupCheckRows(container);
    return;
  }
  // A short form (a light, a cover…) opens every section but Advanced: folding three lines helps nobody.
  const small = container.children.length - (document.getElementById('optVisBox') ? 1 : 0) <= SMALL_FORM;
  const sectionOf = {};
  Object.entries(map).forEach(([sec, ids]) => ids.forEach(id => { sectionOf[id] = sec; }));
  sectionOf.optVisBox = 'visibility'; // "Show only when", on every form (cards.js)
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
  const dialog = container.closest('.ha-dialog');
  if (dialog) dialog.classList.add('form-wide');
  const present = CARD_SECTION_ORDER.filter(sec => groups[sec] && groups[sec].length);
  const opened = small
    ? present.filter(sec => sec !== 'advanced' && sec !== 'visibility')
    : present.some(sec => CARD_SECTION_OPEN.includes(sec)) ? CARD_SECTION_OPEN : present.slice(0, 1);
  present.forEach(sec => {
    const details = document.createElement('details');
    details.className = 'form-section';
    details.dataset.section = sec;
    if (opened.includes(sec)) details.open = true;
    details.innerHTML = `<summary><span class="form-section-name">${I18N.t('web_form_section_' + sec)}</span><span class="form-section-badge" hidden></span></summary>`;
    const body = document.createElement('div');
    body.className = 'form-section-body';
    groups[sec].forEach(el => body.appendChild(el));
    groupCheckRows(body);
    // A list of buttons, scenes or rooms keeps its own editor layout, one column.
    if (sec !== 'items') gridFields(body);
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
    const shown = c => c.tagName !== 'DATALIST' && c.style.display !== 'none' &&
      (!c.matches('.form-check-row, .form-field') || [...c.children].some(shown));
    sec.hidden = ![...body.children].some(shown);
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
