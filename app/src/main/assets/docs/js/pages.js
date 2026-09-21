let dashboardData = { startPage: 0, pages: [ { name: "Home", cards: [], hotkeys: [], longHotkeys: [] } ], hotkeys: [], longHotkeys: [], irDevices: [], haDevices: [], harmonyAliases: {}, activities: [], theme: {} };
let currentActivePage = 0;
let editingCard = null;    // index of the card being edited within the current page, or null
let editingHotkey = null;  // { scope, listType, i } of the hotkey being edited, or null
let editingPage = null;    // index of the page being edited in the page dialog, or null (= adding a new one)

/**
 * Turns a display name into a stable, ASCII id — used by activities.js
 * (activities) here, and by devices-page.js's own copy for irDevices/
 * haDevices (a separate page, can't share this one) — kept in sync so both
 * slugify the same way. Diacritics are stripped via Unicode NFD decomposition rather than dropped
 * outright: "Série" -> "e" would silently swallow the accented letter if we
 * matched straight against [^a-z0-9], producing "s_rie" instead of "serie".
 * `normalize('NFD')` splits "é" into "e" + a separate combining accent
 * codepoint (U+0301), which \u0300-\u036f then strips, leaving the plain
 * letter behind.
 */
function slugify(name, fallbackPrefix) {
  const base = name
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
  return base || (fallbackPrefix + '_' + Date.now());
}

// Mirrors ActivityRuntime.scan()/activityFrom()'s exact id derivation
// (config/ActivityRuntime.kt) — deliberately NOT slugify() above, which
// strips accents via NFD and trims underscores; the Kotlin side does
// neither, so reusing it here would produce an id that doesn't actually
// match what ActivityRuntime tracks at runtime.
function trackedActivityFallbackId(name) {
  return String(name).toLowerCase().replace(/[^a-z0-9]+/g, '_');
}

/**
 * Every Activity that can end up in ActivityRuntime.activeByRoom, mirroring
 * ActivityRuntime.scan() in Kotlin: composed AppConfig.activities, PLUS
 * every "track": true scene_grid tile and every track:true hotkey (global
 * or per-page) — which is where a Harmony Activity lives (a tile with
 * "activityId"+"hub", tracked the same way as any other trackable tile).
 * Used to populate the "Hide unless this Activity" picker — listing only
 * dashboardData.activities there left out every Harmony one, since those
 * are scene_grid tiles/hotkeys, not entries in that array.
 */
function collectTrackableActivities() {
  const seen = new Set();
  const options = [];
  function add(id, label) {
    if (!id || seen.has(id)) return;
    seen.add(id);
    options.push({ id, label });
  }

  (dashboardData.activities || []).forEach(a => add(a.id, `${a.name} (${a.room})`));

  function addFromScene(scene) {
    if (scene.track !== true || !scene.room) return;
    const name = scene.name || scene.entity_id || 'Activity';
    const id = scene.entity_id || scene.activityId || trackedActivityFallbackId(name);
    add(id, `${name} (${scene.room})${scene.activityId ? ' · Harmony' : ''}`);
  }
  function addFromHotkey(hk) {
    if (!hk.track || !hk.room) return;
    const name = hk.harmonyActivity || hk.page || hk.key;
    const id = hk.entityId || hk.harmonyActivity || trackedActivityFallbackId(name);
    add(id, `${name} (${hk.room})${hk.harmonyActivity ? ' · Harmony' : ''}`);
  }

  (dashboardData.hotkeys || []).forEach(addFromHotkey);
  (dashboardData.longHotkeys || []).forEach(addFromHotkey);
  (dashboardData.pages || []).forEach(page => {
    (page.cards || []).forEach(card => {
      if (card.type !== 'scene_grid') return;
      ((card.options && card.options.scenes) || []).forEach(addFromScene);
    });
    (page.hotkeys || []).forEach(addFromHotkey);
    (page.longHotkeys || []).forEach(addFromHotkey);
  });

  return options;
}

function resetAll() {
  dashboardData = { startPage: 0, pages: [ { name: "Home", cards: [], hotkeys: [], longHotkeys: [] } ], hotkeys: [], longHotkeys: [], irDevices: [], haDevices: [], harmonyAliases: {}, activities: [], theme: {} };
  currentActivePage = 0;
  document.getElementById('importBox').value = '';
  initEditor();
}

// Turns one parsed page object from dashboard.json into the shape
// dashboardData.pages expects — every optional page field the app
// understands, listed once here so importJson() and device.js's
// applyParsedDashboard() (device auto-load) can't silently diverge on
// which ones survive a round-trip the way they used to: page fields like
// parent/linkedPage/hiddenUnlessActivity/openWhen* were only ever added to
// this function's old importJson()-only copy, so loading a dashboard via
// the device (not the paste box) silently dropped every one of them.
function normalizePageFromJson(p) {
  return {
    name: p.name || 'Page',
    cards: p.cards || [],
    hotkeys: p.hotkeys || [],
    longHotkeys: p.longHotkeys || [],
    ...(p.parent ? { parent: p.parent } : {}),
    ...(p.parent && p.parentKey && p.parentKey.toUpperCase() !== 'BACK' ? { parentKey: p.parentKey.toUpperCase() } : {}),
    ...(p.linkedPage ? { linkedPage: p.linkedPage } : {}),
    ...(p.linkedPage && p.linkedPageMode === 'popup' ? { linkedPageMode: 'popup' } : {}),
    ...(p.linkedPage && p.linkedPageMode === 'popup' && p.popupWidth != null ? { popupWidth: p.popupWidth } : {}),
    ...(p.linkedPage && p.linkedPageMode === 'popup' && p.popupHeight != null ? { popupHeight: p.popupHeight } : {}),
    ...(p.linkedPage && p.linkedPageMode === 'popup' && p.popupPosition && p.popupPosition !== 'center' ? { popupPosition: p.popupPosition } : {}),
    ...(p.hiddenUnlessActivity ? { hiddenUnlessActivity: p.hiddenUnlessActivity } : {}),
    ...(p.openWhenEntity ? { openWhenEntity: p.openWhenEntity } : {}),
    ...(p.openWhenEntity && p.openWhenState && p.openWhenState !== 'on' ? { openWhenState: p.openWhenState } : {}),
    ...(p.openWhenEntity && p.closeWhenState ? { closeWhenState: p.closeWhenState } : {}),
  };
}

// Single source of truth for turning a parsed dashboard.json into
// dashboardData — shared by importJson() below (the paste box) and
// device.js's applyParsedDashboard() (device auto-load / "Save to
// device"'s own reload). The reverse divergence from normalizePageFromJson's
// existed at this level too: this function's old importJson()-only copy
// never carried haDevices/harmonyAliases at all, while the device.js copy
// had those but not the page fields above — each path was missing
// whichever fields had only ever been added to the *other* one.
function normalizeDashboardData(parsed) {
  const data = {
    startPage: parsed.startPage || 0,
    pages: (parsed.pages || []).map(normalizePageFromJson),
    hotkeys: parsed.hotkeys || [],
    longHotkeys: parsed.longHotkeys || [],
    irDevices: parsed.irDevices || [],
    haDevices: parsed.haDevices || [],
    harmonyAliases: parsed.harmonyAliases || {},
    activities: parsed.activities || [],
    theme: parsed.theme || {},
  };
  if (data.pages.length === 0) {
    data.pages.push({ name: "Home", cards: [], hotkeys: [], longHotkeys: [] });
  }
  return data;
}

function importJson() {
  const raw = document.getElementById('importBox').value.trim();
  if (!raw) return;
  try {
    const parsed = JSON.parse(raw);
    dashboardData = normalizeDashboardData(parsed);
    currentActivePage = 0;
    initEditor();
    alert('dashboard.json loaded — you can now edit it below.');
  } catch (e) {
    alert('Invalid JSON: ' + e.message);
  }
}

function initEditor() {
  editingCard = null;
  editingHotkey = null;
  editingPage = null;
  renderTabs();
  renderPreview();
  renderHotkeysList();
  renderActivitiesList();
  if (typeof renderThemeForm === 'function') renderThemeForm();
  if (typeof applyThemeToPreview === 'function') applyThemeToPreview();
  updateJsonOutput();
  updateHotkeyActionInputs();
}

// ---- Pages: add / rename / delete, all via the page dialog -----------------
// Mirrors Home Assistant's "views" editor: pages live as tabs above the
// preview; a "+" tab adds one, and each tab's own settings (rename, delete,
// set-as-start-page) open in a small dialog instead of a permanent form.

function onPageChange(index) {
  currentActivePage = index;
  cancelCardEdit();
  renderTabs();
  renderPreview();
  renderHotkeysList();
}

function openPageDialog(index) {
  editingPage = index; // null = adding a new page
  const isNew = index === null;
  document.getElementById('pageDialogTitle').textContent = isNew ? 'Add page' : 'Page settings';
  document.getElementById('pageDialogName').value = isNew ? '' : dashboardData.pages[index].name;
  populatePageParentSelect(index);
  document.getElementById('pageDialogParent').value = isNew ? '' : (dashboardData.pages[index].parent || '');
  document.getElementById('pageDialogParentKey').value = isNew ? 'BACK' : (dashboardData.pages[index].parentKey || 'BACK');
  onPageDialogParentChange();
  populatePageLinkedPageSelect(index);
  document.getElementById('pageDialogLinkedPage').value = isNew ? '' : (dashboardData.pages[index].linkedPage || '');
  document.getElementById('pageDialogLinkedPageMode').value = isNew ? 'page' : (dashboardData.pages[index].linkedPageMode === 'popup' ? 'popup' : 'page');
  document.getElementById('pageDialogPopupWidth').value = isNew ? 70 : Math.round((dashboardData.pages[index].popupWidth ?? 0.7) * 100);
  document.getElementById('pageDialogPopupHeight').value = isNew ? 50 : Math.round((dashboardData.pages[index].popupHeight ?? 0.5) * 100);
  document.getElementById('pageDialogPopupPosition').value = isNew ? 'center' : (dashboardData.pages[index].popupPosition || 'center');
  onPageDialogLinkedPageChange();
  onPageDialogLinkedPageModeChange();
  populatePageHiddenUnlessActivitySelect();
  document.getElementById('pageDialogHiddenUnlessActivity').value = isNew ? '' : (dashboardData.pages[index].hiddenUnlessActivity || '');
  document.getElementById('pageDialogOpenWhenEntity').value = isNew ? '' : (dashboardData.pages[index].openWhenEntity || '');
  document.getElementById('pageDialogOpenWhenState').value = isNew ? '' : (dashboardData.pages[index].openWhenState || '');
  document.getElementById('pageDialogCloseWhenState').value = isNew ? '' : (dashboardData.pages[index].closeWhenState || '');
  document.getElementById('pageDialogOpenMode').value = isNew ? 'page' : (dashboardData.pages[index].openMode === 'popup' ? 'popup' : 'page');
  onPageDialogOpenWhenEntityChange();
  onPageDialogOpenModeChange();
  attachEntityAutocomplete(document.getElementById('pageDialogOpenWhenEntity'), null);
  document.getElementById('pageDialogStart').checked = isNew ? false : (dashboardData.startPage === index);
  document.getElementById('pageDialogDeleteBtn').style.display = isNew ? 'none' : '';
  document.getElementById('pageDialogModal').classList.add('open');
  document.getElementById('pageDialogName').focus();
}

// Shows the "return-to-parent button" picker only once a parent page is
// actually selected — it has nothing to configure otherwise.
function onPageDialogParentChange() {
  const hasParent = !!document.getElementById('pageDialogParent').value;
  document.getElementById('pageDialogParentKeyRow').style.display = hasParent ? '' : 'none';
}

// Shows the "how it opens" (page/popup) picker only once a linked page is
// actually selected — it has nothing to configure otherwise.
function onPageDialogLinkedPageChange() {
  const hasLinked = !!document.getElementById('pageDialogLinkedPage').value;
  document.getElementById('pageDialogLinkedPageModeRow').style.display = hasLinked ? '' : 'none';
}

// Shows the "how it opens" picker for auto-open only once an entity is set.
function onPageDialogOpenWhenEntityChange() {
  const hasEntity = !!document.getElementById('pageDialogOpenWhenEntity').value.trim();
  document.getElementById('pageDialogOpenModeRow').style.display = hasEntity ? '' : 'none';
}

// The shared width/height/position block (and each mode's own one-line
// hint) shows whenever EITHER trigger is set to popup — it's one setting
// on the page, not two, however it gets opened.
function onPageDialogLinkedPageModeChange() {
  updatePopupRowVisibility();
}

function onPageDialogOpenModeChange() {
  updatePopupRowVisibility();
}

function updatePopupRowVisibility() {
  const linkedIsPopup = document.getElementById('pageDialogLinkedPageMode').value === 'popup';
  const openIsPopup = document.getElementById('pageDialogOpenMode').value === 'popup';
  document.getElementById('pageDialogPopupRow').style.display = (linkedIsPopup || openIsPopup) ? '' : 'none';
  document.getElementById('pageDialogLinkedPopupHint').style.display = linkedIsPopup ? '' : 'none';
  document.getElementById('pageDialogOpenPopupHint').style.display = openIsPopup ? '' : 'none';
}

// Every page that would create a cycle if picked as `excludeIndex`'s
// parent: the page itself, plus every one of its own descendants
// (transitively) — picking a descendant as your own parent would make the
// tree loop back on itself. `excludeIndex === null` (adding a brand new
// page) has no descendants yet, so nothing to exclude beyond nothing.
function pagesUnavailableAsParentOf(excludeIndex) {
  if (excludeIndex === null) return new Set();
  const excludedNames = new Set([dashboardData.pages[excludeIndex].name]);
  let grew = true;
  while (grew) {
    grew = false;
    dashboardData.pages.forEach(p => {
      if (p.parent && excludedNames.has(p.parent) && !excludedNames.has(p.name)) {
        excludedNames.add(p.name);
        grew = true;
      }
    });
  }
  return excludedNames;
}

function populatePageParentSelect(excludeIndex) {
  const select = document.getElementById('pageDialogParent');
  const unavailable = pagesUnavailableAsParentOf(excludeIndex);
  select.innerHTML = '<option value="">— None (top-level page) —</option>' +
    dashboardData.pages
      .filter(p => !unavailable.has(p.name))
      .map(p => `<option value="${p.name.replace(/"/g, '&quot;')}">${p.name}</option>`)
      .join('');
}

// Any other page can be the swipe-up target — unlike parent (which must
// stay acyclic to keep BACK well-defined), linking to a page is just a
// jump, so the only page excluded is this one itself (a page linking to
// its own name would swipe-up into a no-op).
function populatePageLinkedPageSelect(excludeIndex) {
  const select = document.getElementById('pageDialogLinkedPage');
  const selfName = excludeIndex === null ? null : dashboardData.pages[excludeIndex].name;
  select.innerHTML = '<option value="">— None —</option>' +
    dashboardData.pages
      .filter(p => p.name !== selfName)
      .map(p => `<option value="${p.name.replace(/"/g, '&quot;')}">${p.name}</option>`)
      .join('');
}

// Same id space as a scene_grid tile's own "activity" field (dashboardData.activities[].id) —
// reusing that exact picker pattern (see cards.js) rather than inventing a
// second one, so the id always resolves the same way in both places.
function populatePageHiddenUnlessActivitySelect() {
  const select = document.getElementById('pageDialogHiddenUnlessActivity');
  const options = collectTrackableActivities();
  select.innerHTML = '<option value="">— Always shown —</option>' +
    options.map(o => `<option value="${o.id.replace(/"/g, '&quot;')}">${o.label}</option>`).join('');
  if (options.length === 0) {
    select.innerHTML += '<option value="" disabled>(no trackable Activities yet — add one in the Activities tab, or a "track": true scene_grid tile)</option>';
  }
}

function closePageDialog() {
  document.getElementById('pageDialogModal').classList.remove('open');
  editingPage = null;
}

function savePageDialog() {
  const name = document.getElementById('pageDialogName').value.trim();
  if (!name) { alert('Give the page a name.'); return; }
  const parent = document.getElementById('pageDialogParent').value || undefined;
  const parentKey = document.getElementById('pageDialogParentKey').value || 'BACK';
  const linkedPage = document.getElementById('pageDialogLinkedPage').value || undefined;
  const linkedPageMode = document.getElementById('pageDialogLinkedPageMode').value;
  const popupWidth = (document.getElementById('pageDialogPopupWidth').value || 70) / 100;
  const popupHeight = (document.getElementById('pageDialogPopupHeight').value || 50) / 100;
  const popupPosition = document.getElementById('pageDialogPopupPosition').value;
  const hiddenUnlessActivity = document.getElementById('pageDialogHiddenUnlessActivity').value || undefined;
  const openWhenEntity = document.getElementById('pageDialogOpenWhenEntity').value.trim() || undefined;
  const openWhenState = document.getElementById('pageDialogOpenWhenState').value.trim() || undefined;
  const closeWhenState = document.getElementById('pageDialogCloseWhenState').value.trim() || undefined;
  const openMode = document.getElementById('pageDialogOpenMode').value;
  const makeStart = document.getElementById('pageDialogStart').checked;

  if (editingPage === null) {
    const page = { name, cards: [], hotkeys: [], longHotkeys: [] };
    if (parent) {
      page.parent = parent;
      if (parentKey !== 'BACK') page.parentKey = parentKey;
    }
    if (linkedPage) {
      page.linkedPage = linkedPage;
      if (linkedPageMode === 'popup') page.linkedPageMode = 'popup';
    }
    if (hiddenUnlessActivity) page.hiddenUnlessActivity = hiddenUnlessActivity;
    if (openWhenEntity) {
      page.openWhenEntity = openWhenEntity;
      if (openWhenState && openWhenState !== 'on') page.openWhenState = openWhenState;
      if (closeWhenState) page.closeWhenState = closeWhenState;
      if (openMode === 'popup') page.openMode = 'popup';
    }
    if (linkedPageMode === 'popup' || openMode === 'popup') {
      page.popupWidth = popupWidth;
      page.popupHeight = popupHeight;
      if (popupPosition !== 'center') page.popupPosition = popupPosition;
    }
    dashboardData.pages.push(page);
    currentActivePage = dashboardData.pages.length - 1;
    if (makeStart) dashboardData.startPage = currentActivePage;
  } else {
    const page = dashboardData.pages[editingPage];
    const oldName = page.name;
    page.name = name;
    if (parent) {
      page.parent = parent;
      if (parentKey !== 'BACK') page.parentKey = parentKey; else delete page.parentKey;
    } else {
      delete page.parent;
      delete page.parentKey;
    }
    if (linkedPage) {
      page.linkedPage = linkedPage;
      if (linkedPageMode === 'popup') page.linkedPageMode = 'popup'; else delete page.linkedPageMode;
    } else {
      delete page.linkedPage;
      delete page.linkedPageMode;
    }
    if (hiddenUnlessActivity) page.hiddenUnlessActivity = hiddenUnlessActivity; else delete page.hiddenUnlessActivity;
    if (openWhenEntity) {
      page.openWhenEntity = openWhenEntity;
      if (openWhenState && openWhenState !== 'on') page.openWhenState = openWhenState; else delete page.openWhenState;
      if (closeWhenState) page.closeWhenState = closeWhenState; else delete page.closeWhenState;
      if (openMode === 'popup') page.openMode = 'popup'; else delete page.openMode;
    } else {
      delete page.openWhenEntity;
      delete page.openWhenState;
      delete page.closeWhenState;
      delete page.openMode;
    }
    if (linkedPageMode === 'popup' || openMode === 'popup') {
      page.popupWidth = popupWidth;
      page.popupHeight = popupHeight;
      if (popupPosition !== 'center') page.popupPosition = popupPosition; else delete page.popupPosition;
    } else {
      delete page.popupWidth;
      delete page.popupHeight;
      delete page.popupPosition;
    }
    if (makeStart) dashboardData.startPage = editingPage;
    else if (dashboardData.startPage === editingPage) dashboardData.startPage = 0;

    // Renaming a page that others point to as their parent OR their linked
    // page — keep both trees intact instead of silently orphaning them to
    // a name that no longer exists (which the app would then just treat as
    // "no parent/linked page found").
    if (oldName !== name) {
      dashboardData.pages.forEach(p => {
        if (p.parent === oldName) p.parent = name;
        if (p.linkedPage === oldName) p.linkedPage = name;
      });
    }
  }

  closePageDialog();
  renderTabs(); renderPreview(); renderHotkeysList(); updateJsonOutput();
}

function deletePageFromDialog() {
  const i = editingPage;
  if (i === null) return;
  if (dashboardData.pages.length <= 1) { alert('You need at least one page.'); return; }
  const deletedName = dashboardData.pages[i].name;
  const children = dashboardData.pages.filter(p => p.parent === deletedName);
  const childWarning = children.length
    ? ` ${children.length} child page(s) (${children.map(c => c.name).join(', ')}) will become top-level pages instead of being deleted.`
    : '';
  if (!confirm(`Delete page "${deletedName}" and everything on it (cards, page hotkeys)?${childWarning}`)) return;
  children.forEach(c => delete c.parent);
  // Any page linking up to the one being deleted loses that link too —
  // otherwise its swipe-up would silently point at a page that no longer
  // exists (the app just treats that as "nothing happens", but cleaning it
  // up here keeps the exported JSON honest about what's actually wired).
  dashboardData.pages.forEach(p => { if (p.linkedPage === deletedName) delete p.linkedPage; });
  dashboardData.pages.splice(i, 1);
  if (currentActivePage >= dashboardData.pages.length) currentActivePage = dashboardData.pages.length - 1;
  if (dashboardData.startPage >= dashboardData.pages.length) dashboardData.startPage = 0;
  editingCard = null; editingHotkey = null;
  closePageDialog();
  renderTabs(); renderPreview(); renderHotkeysList(); updateJsonOutput();
}

// Reorder a page from fromIdx to toIdx by dragging its tab. Mirrors
// reorderCard() but operates on dashboardData.pages and fixes the two
// positional indices that reference pages by number: startPage (the launch
// page) and currentActivePage (the tab being viewed in the editor). Both must
// follow the page they point to across the splice, otherwise a drag would
// silently change which page launches at boot or which one is shown.
function reorderPage(fromIdx, toIdx) {
  const pages = dashboardData.pages;
  if (fromIdx < 0 || fromIdx >= pages.length || toIdx < 0 || toIdx >= pages.length || fromIdx === toIdx) return;
  const [moved] = pages.splice(fromIdx, 1);
  pages.splice(toIdx, 0, moved);
  const fixIndex = (i) => {
    if (i === fromIdx) return toIdx;
    if (fromIdx < toIdx) { if (i > fromIdx && i <= toIdx) return i - 1; }
    else { if (i >= toIdx && i < fromIdx) return i + 1; }
    return i;
  };
  dashboardData.startPage = fixIndex(dashboardData.startPage);
  currentActivePage = fixIndex(currentActivePage);
  renderTabs(); renderPreview(); renderHotkeysList(); updateJsonOutput();
}

// previewModal/modalScreenSlot: scaffolded in index.html (a "✕ Close" button
// already referenced this) but nothing in this codebase currently opens it —
// no ReferenceError either way now, and this'll do the right thing the day
// something does.
function closePreviewModal() {
  document.getElementById('previewModal')?.classList.remove('open');
}

// ---- Modal backdrops: close only on a genuine click on the backdrop itself ---
//
// Every modal here is `<div class="preview-modal"><div class="preview-modal-inner">
// ...content...</div></div>` — clicking the OUTER div (not its content) should
// close it, same idea as `onclick="if (event.target === this) close()"` which
// this used to be, directly on each modal's HTML.
//
// That inline version had a real bug: picking an option from a native <select>
// inside the modal (a card type, a domain, a device...) sometimes closed the
// whole card being edited. Native <select> dropdowns are rendered by the OS/
// browser outside the normal DOM click flow — dismissing one after picking an
// option can dispatch a synthetic click that browsers/WebViews report as
// landing directly on the backdrop, even though the person's finger/mouse
// never touched it. A plain `click` listener can't tell that apart from a real
// backdrop tap.
//
// Fix: require the *press* (mousedown) to also have started on the backdrop
// itself, not just the click's target. A real backdrop click always starts
// and ends there; a synthetic post-select-dismiss click never had a real
// mousedown on the backdrop to begin with.
function wireModalBackdropClose(modalId, closeFn) {
  const el = document.getElementById(modalId);
  if (!el) return;
  let downOnBackdrop = false;
  el.addEventListener('mousedown', e => { downOnBackdrop = (e.target === el); });
  el.addEventListener('click', e => {
    if (downOnBackdrop && e.target === el) closeFn();
    downOnBackdrop = false;
  });
}

[
  ['previewModal', () => closePreviewModal()],
  ['cardEditorModal', () => cancelCardEdit()],
  ['pageDialogModal', () => closePageDialog()],
  ['activityWizardModal', () => closeActivityWizard()],
  ['iconPickerModal', () => closeIconPicker()],
].forEach(([id, fn]) => wireModalBackdropClose(id, fn));
