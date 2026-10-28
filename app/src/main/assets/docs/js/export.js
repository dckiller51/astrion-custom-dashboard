// ---- JSON output ------------------------------------------------------------

function updateJsonOutput() {
  const json = JSON.stringify(dashboardData, null, 2);
  document.getElementById('jsonOutput').value = json;
  if (typeof recordUndo === 'function') recordUndo(json); // js/history.js
}

/**
 * Browsers only give navigator.clipboard to HTTPS pages (and localhost); the remote serves plain
 * HTTP, so it is undefined there and the copy silently failed (1.4.0). Fallback: select the text
 * in a hidden textarea and execCommand('copy'), which plain HTTP still allows.
 */
async function copyJson() {
  const text = document.getElementById('jsonOutput').value;
  let ok = false;
  if (navigator.clipboard && window.isSecureContext) {
    try { await navigator.clipboard.writeText(text); ok = true; } catch (e) { ok = false; }
  }
  if (!ok) {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(area);
  }
  if (ok) {
    showToast(I18N.t('web_export_copied'));
  } else {
    // Last resort: select it in the JSON box so Ctrl+C / long-press copies it.
    const box = document.getElementById('jsonOutput');
    box.focus();
    box.select();
    showToast(I18N.t('web_export_copy_failed'), 'error');
  }
}

function downloadJson() {
  const blob = new Blob([document.getElementById('jsonOutput').value], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = 'dashboard.json';
  a.click();
  URL.revokeObjectURL(url);
}