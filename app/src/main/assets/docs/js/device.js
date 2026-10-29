// ---- Load / Save directly to this device ------------------------------------
//
// The builder only exists served by the app itself, at http://<device-ip>:8080/builder/
// — there's no standalone/offline mode. dashboard.json is auto-loaded on open,
// and "Save to device" applies changes live. The paste/download box above stays
// as a manual override (importing a shared config, or a plain-text backup),
// not a fallback for a missing connection.

let remoteName = ''; // this remote's name (web page › its name), from /info (1.4.1)

/** The remote's name for the banner and the tab title; the banner without it if /info doesn't answer. */
async function loadRemoteName() {
  try {
    const res = await fetch('/info');
    if (res.ok) remoteName = ((await res.json()).name || '').trim();
  } catch (e) {
    remoteName = '';
  }
  if (remoteName) document.title = `${remoteName} — ${document.title}`;
  updateDeviceModeUi();
}

let deviceModeAvailable = false; // true once dashboard.json has actually loaded — used to gate "Save to device" while that first fetch is in flight

// Slide-up toast notification. Replaces the blocking alert() that "Save to
// device" used to pop — non-modal, auto-dismisses (3s success / 5s error),
// falls back to alert() if the #toast element isn't present.
let _toastTimer = null;
function showToast(msg, type) {
  const el = document.getElementById('toast');
  if (!el) { alert(msg); return; }
  el.textContent = msg;
  el.className = 'toast' + (type === 'error' ? ' error' : '');
  void el.offsetWidth; // restart transition if a toast is already showing
  el.classList.add('show');
  clearTimeout(_toastTimer);
  _toastTimer = setTimeout(() => el.classList.remove('show'), type === 'error' ? 5000 : 3000);
}

/**
 * 1.4.0: dashboard.json first, behind a loading screen that blocks editing (a change made on the
 * empty default page used to be lost when the real one arrived); Home Assistant's states come
 * after, freshly re-read from Home Assistant, and only redraw the preview.
 */
async function loadDashboardFromDevice() {
  await I18N.ready; // the preview's mock/state labels render during init, so strings must be in first
  let loaded = false;
  try {
    const res = await fetch('/dashboard.json');
    deviceModeAvailable = true; // reaching this line at all means the app answered — even a 404 (no dashboard.json saved yet) still confirms device mode
    if (res.ok) {
      const parsed = await res.json();
      applyParsedDashboard(parsed);
      initEditor();
      if (typeof resetUndo === 'function') { resetUndo(); recordUndo(document.getElementById('jsonOutput').value); }
      console.log('Loaded dashboard.json from this device.');
    }
    // No dashboard.json yet: the inline initEditor() already ran with the default empty page.
    loaded = true;
  } catch (e) {
    deviceModeAvailable = false;
    showToast(I18N.t('web_device_unreachable'), 'error');
    console.error('Failed to load dashboard.json from this device.', e);
  }
  hideLoadingOverlay();
  updateDeviceModeUi();
  if (deviceModeAvailable) loadRemoteName();
  if (typeof openFromHash === 'function') openFromHash(); // deep links: #history, #page=…, #activity=…, #hotkeys
  if (typeof loadInstalledVersion === 'function') loadInstalledVersion(); // js/hotkeys.js
  if (loaded) {
    await loadHaStates(true); // best-effort: lets the preview render with live HA data instead of the static mocks
    renderPreview();
  }
}

function hideLoadingOverlay() {
  const el = document.getElementById('loadingOverlay');
  if (el) el.classList.add('hidden');
}

/** The 🔄 button (1.4.0): re-reads every entity from Home Assistant (renamed or new ones show up). */
async function reloadHaStates() {
  const btn = document.getElementById('haReloadBtn');
  if (btn) btn.disabled = true;
  const ok = await loadHaStates(true);
  if (btn) btn.disabled = false;
  renderPreview();
  if (ok) {
    showToast(I18N.t('web_device_ha_reloaded', Object.keys(haStates || {}).length));
  } else {
    showToast(I18N.t('web_device_ha_reload_failed'), 'error');
  }
}

/**
 * Fetches a snapshot of every HA entity the device currently knows, exposed
 * by the app at /ha-states. Sets the global `haStates` (declared in preview.js)
 * so the card renderers can use live state/names/attributes instead of the
 * static *_MOCK examples. Failures are swallowed: when Home Assistant isn't
 * configured or isn't reachable right now, haStates stays null and the
 * preview quietly falls back to the mocks + prettyEntityName().
 */
async function loadHaStates(refresh) {
  try {
    // refresh: the remote re-reads every state from Home Assistant first (1.4.0).
    const res = await fetch(refresh ? '/ha-states?refresh=1' : '/ha-states');
    if (!res.ok) return false;
    const data = await res.json();
    haStates = (data && data.states) ? data.states : null;
    if (data && data.connected === false) {
      console.log('HA not connected — preview will use example data.');
      return false;
    }
    return true;
  } catch (e) {
    haStates = null; // not device mode, or older app build without the endpoint
    return false;
  }
}

/** Normalizes a parsed dashboard.json into dashboardData via
 * normalizeDashboardData() (pages.js) — the single shared implementation
 * importJson() also uses, so this path and the paste-box path can't
 * silently diverge on which fields survive a round-trip again (see that
 * function's own doc comment for the history of exactly that bug). */
function applyParsedDashboard(parsed) {
  dashboardData = normalizeDashboardData(parsed);
  currentActivePage = 0;
}

async function saveDashboardToDevice() {
  const jsonText = document.getElementById('jsonOutput').value;
  try {
    JSON.parse(jsonText); // fail fast with a clear message rather than let the app reject a bad upload silently
  } catch (e) {
    showToast(I18N.t('web_page_invalid_json', e.message), 'error');
    return;
  }
  // Both "Save to device" buttons: the one under the JSON and the floating one, top right (1.4.0).
  const buttons = ['saveToDeviceBtn', 'saveToDeviceFab'].map(id => document.getElementById(id)).filter(Boolean);
  const originals = buttons.map(b => b.textContent);
  buttons.forEach(b => { b.textContent = I18N.t('web_device_saving'); b.disabled = true; });
  try {
    const form = new FormData();
    form.append('file', new Blob([jsonText], { type: 'application/json' }), 'dashboard.json');
    // Optional note for this version in the remote's history (1.3.0).
    const noteEl = document.getElementById('saveNote');
    if (noteEl && noteEl.value.trim()) form.append('note', noteEl.value.trim());
    const res = await fetch('/dashboard.json', { method: 'POST', body: form });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    if (noteEl) noteEl.value = '';
    notifyOpener('save', res.headers.get('X-Astrion-Version-Id'));
    showToast(I18N.t('web_device_saved'));
    if (document.getElementById('historyModal')?.classList.contains('open')) refreshHistory();
  } catch (e) {
    showToast(I18N.t('web_device_save_failed', e), 'error');
  } finally {
    buttons.forEach((b, i) => { b.textContent = originals[i]; b.disabled = false; });
  }
}

/**
 * 1.3.3: tells the window that opened the builder (Astrion Config Manager, a script…) that the
 * remote's dashboard just changed, so it can pick it up without waiting for the user to come back:
 *   { type: 'astrion-saved', versionId: '<history id>' | null, reason: 'save' | 'restore' }
 * Only a version id goes out, so any origin may receive it; the receiver checks event.origin.
 */
function notifyOpener(reason, versionId) {
  try {
    if (window.opener && !window.opener.closed) {
      window.opener.postMessage({ type: 'astrion-saved', versionId: versionId || null, reason }, '*');
    }
  } catch (e) {
    console.warn('Could not notify the opener window.', e);
  }
}

function updateDeviceModeUi() {
  const banner = document.getElementById('deviceModeBanner');
  const saveBtn = document.getElementById('saveToDeviceBtn');
  if (banner) {
    banner.style.display = 'block';
    // 1.4.1: says which remote (several remotes, several tabs): "Connected to Living room".
    banner.textContent = deviceModeAvailable
      ? (remoteName ? I18N.t('web_device_connected_to', remoteName) : I18N.t('web_device_connected_banner'))
      : I18N.t('web_device_connecting');
  }
  if (saveBtn) saveBtn.style.display = deviceModeAvailable ? 'inline-block' : 'none';
  const fab = document.getElementById('saveToDeviceFab');
  if (fab) fab.style.display = deviceModeAvailable ? '' : 'none';
  const reloadBtn = document.getElementById('haReloadBtn');
  if (reloadBtn) reloadBtn.style.display = deviceModeAvailable ? '' : 'none';
  const noteEl = document.getElementById('saveNote');
  if (noteEl) noteEl.style.display = deviceModeAvailable ? '' : 'none';
  const histBtn = document.getElementById('historyBtn');
  if (histBtn) histBtn.style.display = deviceModeAvailable ? '' : 'none';
}

loadDashboardFromDevice();