// ---- Undo / redo, and the remote's version history (1.3.0) -------------------
//
// Undo / redo: every change in the builder ends in updateJsonOutput(), which hands the
// new JSON to recordUndo(). Changes less than a second apart (typing in a colour field,
// dragging a slider) count as one step. Ctrl+Z / Ctrl+Y (or Ctrl+Shift+Z) outside text
// fields, or the ↶ ↷ buttons above the preview.
//
// Version history: the remote keeps every dashboard.json saved from here (or from its web
// page) in /sdcard/astrion/history — see DashboardHistory.kt. The 🕘 button lists them:
// open one in the editor (nothing saved until "Save to device"), restore it on the remote
// at once, download it, pin it (never deleted automatically), add a note, delete it.

const UNDO_MAX = 100;
const UNDO_MERGE_MS = 1000;
let undoStack = [];
let redoStack = [];
let undoCurrent = null;
let undoLastAt = 0;
let undoRestoring = false;

function recordUndo(json) {
  if (undoRestoring || undoCurrent === null) { undoCurrent = json; updateUndoButtons(); return; }
  if (json === undoCurrent) return;
  const now = Date.now();
  if (now - undoLastAt > UNDO_MERGE_MS || !undoStack.length) {
    undoStack.push(undoCurrent);
    if (undoStack.length > UNDO_MAX) undoStack.shift();
  }
  undoLastAt = now;
  undoCurrent = json;
  redoStack = [];
  updateUndoButtons();
}

/** Forget the steps — after loading the remote's dashboard, which is the starting point. */
function resetUndo() {
  undoStack = [];
  redoStack = [];
  undoCurrent = null;
  undoLastAt = 0;
  updateUndoButtons();
}

function applyUndoState(json) {
  undoRestoring = true;
  try {
    const page = currentActivePage;
    dashboardData = normalizeDashboardData(JSON.parse(json));
    currentActivePage = Math.min(page, Math.max(0, dashboardData.pages.length - 1));
    closeOpenDialogs();
    initEditor();
    undoCurrent = json;
  } finally {
    undoRestoring = false;
  }
  undoLastAt = 0;
  updateUndoButtons();
}

function undoChange() {
  if (!undoStack.length) return;
  redoStack.push(undoCurrent);
  applyUndoState(undoStack.pop());
  showToast(I18N.t('web_history_undone'));
}

function redoChange() {
  if (!redoStack.length) return;
  undoStack.push(undoCurrent);
  applyUndoState(redoStack.pop());
  showToast(I18N.t('web_history_redone'));
}

function updateUndoButtons() {
  const u = document.getElementById('undoBtn');
  const r = document.getElementById('redoBtn');
  if (u) u.disabled = !undoStack.length;
  if (r) r.disabled = !redoStack.length;
}

// A dialog left open over a state that just changed would edit the wrong thing.
function closeOpenDialogs() {
  if (typeof cancelCardEdit === 'function' && document.getElementById('cardEditorModal')?.classList.contains('open')) cancelCardEdit();
  document.querySelectorAll('.preview-modal.open').forEach(m => { if (m.id !== 'historyModal') m.classList.remove('open'); });
}

document.addEventListener('keydown', (e) => {
  if (!(e.ctrlKey || e.metaKey) || e.altKey) return;
  const t = e.target;
  // Text fields keep their own undo.
  if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable)) return;
  const k = e.key.toLowerCase();
  if (k === 'z' && !e.shiftKey) { e.preventDefault(); undoChange(); }
  else if (k === 'y' || (k === 'z' && e.shiftKey)) { e.preventDefault(); redoChange(); }
});

// ---- version history ------------------------------------------------------------

let historyVersions = [];

function historyEsc(v) {
  return String(v == null ? '' : v).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

function historyDate(ms) {
  const d = new Date(ms);
  return d.toLocaleDateString(undefined, { day: '2-digit', month: '2-digit', year: 'numeric' }) + ' ' +
    d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

async function openHistory() {
  document.getElementById('historyModal').classList.add('open');
  await refreshHistory();
}

function closeHistory() {
  document.getElementById('historyModal').classList.remove('open');
}

async function refreshHistory() {
  const list = document.getElementById('historyList');
  list.innerHTML = `<div class="hint">${I18N.t('media_loading')}</div>`;
  try {
    const res = await fetch('/history');
    if (!res.ok) throw new Error('HTTP ' + res.status);
    historyVersions = (await res.json()).versions || [];
  } catch (e) {
    list.innerHTML = `<div class="hint" style="color:#ff8a8a">${I18N.t('web_history_unavailable')}</div>`;
    return;
  }
  renderHistory();
}

/** "20261016-100350" (or with a "-n" suffix) → that date, for a version no longer in the list. */
function historyIdDate(id) {
  const m = /^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})/.exec(id || '');
  return m ? historyDate(new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]).getTime()) : (id || '?');
}

function historyStats(v) {
  return I18N.t(v.pages === 1 ? 'web_history_pages_one' : 'web_history_pages_many', v.pages) + ', ' +
    I18N.t(v.cards === 1 ? 'web_history_cards_one' : 'web_history_cards_many', v.cards);
}

function historyReasonLabel(v) {
  if (v.reason === 'restore') return I18N.t('web_history_reason_restore', historyIdDate(v.restoredFrom));
  if (v.reason === 'outside') return I18N.t('web_history_reason_outside');
  return '';
}

function renderHistory() {
  const list = document.getElementById('historyList');
  if (!historyVersions.length) { list.innerHTML = `<div class="hint">${I18N.t('web_history_empty')}</div>`; return; }
  list.innerHTML = historyVersions.map((v, i) => `
    <div class="list-item" style="flex-direction:column;align-items:stretch;gap:6px">
      <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
        <b>${historyDate(v.time)}</b>
        ${i === 0 ? `<span class="history-badge">${I18N.t('web_history_latest')}</span>` : ''}
        ${v.pinned ? `<span class="history-badge pinned">📌 ${I18N.t('web_history_pinned')}</span>` : ''}
        <span style="color:#888">${historyStats(v)}</span>
        ${historyReasonLabel(v) ? `<span style="color:#888">· ${historyEsc(historyReasonLabel(v))}</span>` : ''}
      </div>
      <input type="text" class="history-note" value="${historyEsc(v.note)}" maxlength="200" placeholder="${I18N.t('web_history_note_ph')}"
        style="margin:0" onchange="setHistoryNote('${v.id}', this.value)">
      <div style="display:flex;gap:6px;flex-wrap:wrap">
        <button type="button" class="secondary" onclick="openHistoryVersion('${v.id}')">${I18N.t('web_history_open')}</button>
        <button type="button" onclick="restoreHistoryVersion('${v.id}')"${i === 0 ? ' disabled' : ''}>${I18N.t('web_history_restore')}</button>
        <a class="secondary-link" href="/history/${v.id}" download="dashboard-${v.id}.json">${I18N.t('web_history_download')}</a>
        <button type="button" class="secondary" onclick="pinHistoryVersion('${v.id}', ${!v.pinned})">${I18N.t(v.pinned ? 'web_history_unpin' : 'web_history_pin')}</button>
        <button type="button" class="secondary" onclick="deleteHistoryVersion('${v.id}')"${i === 0 ? ' disabled' : ''}>${I18N.t('web_history_delete')}</button>
      </div>
    </div>`).join('');
}

async function historyPost(action, fields) {
  const form = new FormData();
  Object.entries(fields).forEach(([k, v]) => form.append(k, v));
  const res = await fetch('/history/' + action, { method: 'POST', body: form });
  if (!res.ok) throw new Error('HTTP ' + res.status);
}

async function openHistoryVersion(id) {
  try {
    const res = await fetch('/history/' + id);
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const parsed = await res.json();
    closeOpenDialogs();
    applyParsedDashboard(parsed);
    initEditor(); // an ordinary, undoable change: ↶ comes back to what was in the editor
    closeHistory();
    showToast(I18N.t('web_history_opened'));
  } catch (e) {
    showToast(I18N.t('web_history_failed', e.message), 'error');
  }
}

async function restoreHistoryVersion(id) {
  const v = historyVersions.find(x => x.id === id);
  if (!confirm(I18N.t('web_history_confirm_restore', v ? historyDate(v.time) : id))) return;
  try {
    await historyPost('restore', { id });
    const res = await fetch('/dashboard.json');
    if (res.ok) {
      closeOpenDialogs();
      applyParsedDashboard(await res.json());
      initEditor();
    }
    showToast(I18N.t('web_history_restored'));
    await refreshHistory();
  } catch (e) {
    showToast(I18N.t('web_history_failed', e.message), 'error');
  }
}

async function pinHistoryVersion(id, pinned) {
  try { await historyPost('pin', { id, pinned: String(pinned) }); await refreshHistory(); } catch (e) { showToast(I18N.t('web_history_failed', e.message), 'error'); }
}

async function setHistoryNote(id, note) {
  try { await historyPost('note', { id, note }); showToast(I18N.t('web_history_note_saved')); } catch (e) { showToast(I18N.t('web_history_failed', e.message), 'error'); }
}

async function deleteHistoryVersion(id) {
  const v = historyVersions.find(x => x.id === id);
  if (!confirm(I18N.t('web_history_confirm_delete', v ? historyDate(v.time) : id))) return;
  try { await historyPost('delete', { id }); await refreshHistory(); } catch (e) { showToast(I18N.t('web_history_failed', e.message), 'error'); }
}

// /builder/#history (the link on the remote's web page) opens the list once the dashboard is in.
function openHistoryFromHash() {
  if (location.hash === '#history' && typeof deviceModeAvailable !== 'undefined' && deviceModeAvailable) openHistory();
}