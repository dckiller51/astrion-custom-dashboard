// ---- Load / Save directly to this device ------------------------------------
//
// The builder only exists served by the app itself, at http://<device-ip>:8080/builder/
// — there's no standalone/offline mode. dashboard.json is auto-loaded on open,
// and "Save to device" applies changes live. The paste/download box above stays
// as a manual override (importing a shared config, or a plain-text backup),
// not a fallback for a missing connection.

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

async function loadDashboardFromDevice() {
  try {
    const res = await fetch('/dashboard.json');
    deviceModeAvailable = true; // reaching this line at all means the app answered — even a 404 (no dashboard.json saved yet) still confirms device mode
    await loadHaStates(); // best-effort: lets the preview render with live HA data instead of the static mocks
    if (res.ok) {
      const parsed = await res.json();
      applyParsedDashboard(parsed);
      initEditor();
      console.log('Loaded dashboard.json from this device.');
    } else {
      // No dashboard.json yet — the inline initEditor() already ran with the
      // default empty page; just refresh the preview now that haStates is loaded.
      renderPreview();
    }
  } catch (e) {
    deviceModeAvailable = false;
    showToast("Couldn't reach this device — check the connection, then reload this page.", 'error');
    console.error('Failed to load dashboard.json from this device.', e);
  }
  updateDeviceModeUi();
}

/**
 * Fetches a snapshot of every HA entity the device currently knows, exposed
 * by the app at /ha-states. Sets the global `haStates` (declared in preview.js)
 * so the card renderers can use live state/names/attributes instead of the
 * static *_MOCK examples. Failures are swallowed: when Home Assistant isn't
 * configured or isn't reachable right now, haStates stays null and the
 * preview quietly falls back to the mocks + prettyEntityName().
 */
async function loadHaStates() {
  try {
    const res = await fetch('/ha-states');
    if (!res.ok) return;
    const data = await res.json();
    haStates = (data && data.states) ? data.states : null;
    if (data && data.connected === false) {
      console.log('HA not connected — preview will use example data.');
    }
  } catch (e) {
    haStates = null; // not device mode, or older app build without the endpoint
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
    showToast('Invalid JSON: ' + e.message, 'error');
    return;
  }
  const btn = document.getElementById('saveToDeviceBtn');
  const originalText = btn.textContent;
  btn.textContent = 'Saving…';
  btn.disabled = true;
  try {
    const form = new FormData();
    form.append('file', new Blob([jsonText], { type: 'application/json' }), 'dashboard.json');
    const res = await fetch('/dashboard.json', { method: 'POST', body: form });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    showToast('Saved — the dashboard reloads automatically.');
  } catch (e) {
    showToast('Save failed: ' + e, 'error');
  } finally {
    btn.textContent = originalText;
    btn.disabled = false;
  }
}

function updateDeviceModeUi() {
  const banner = document.getElementById('deviceModeBanner');
  const saveBtn = document.getElementById('saveToDeviceBtn');
  if (banner) {
    banner.style.display = 'block';
    banner.textContent = deviceModeAvailable
      ? '✓ Connected to this device — dashboard.json was loaded automatically, and "Save to device" applies changes live.'
      : 'Connecting to this device…';
  }
  if (saveBtn) saveBtn.style.display = deviceModeAvailable ? 'inline-block' : 'none';
}

loadDashboardFromDevice();
