// ---- JSON output ------------------------------------------------------------

function updateJsonOutput() {
  const json = JSON.stringify(dashboardData, null, 2);
  document.getElementById('jsonOutput').value = json;
  if (typeof recordUndo === 'function') recordUndo(json); // js/history.js
}

function copyJson() {
  navigator.clipboard.writeText(document.getElementById('jsonOutput').value);
  alert(I18N.t('web_export_copied'));
}

function downloadJson() {
  const blob = new Blob([document.getElementById('jsonOutput').value], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = 'dashboard.json';
  a.click();
  URL.revokeObjectURL(url);
}