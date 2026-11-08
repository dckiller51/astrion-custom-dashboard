// ---- Screenshots of the remote (1.5.4) -----------------------------------------------------
//
// devices.html › Screenshots: takes one of the remote's screen (POST /screenshot), lists the
// ones it keeps (GET /screenshots, newest first), shows / downloads one (GET /screenshot-file),
// deletes one or all (POST /screenshot-delete). See ScreenshotRoutes.kt.
//
// Uses devices-page.js helpers: showToast, atvEsc.

async function loadScreenshots() {
  const list = document.getElementById('shotsList');
  if (!list) return;
  try {
    const res = await fetch('/screenshots', { cache: 'no-store' });
    const data = await res.json();
    const items = Array.isArray(data.items) ? data.items : [];
    list.innerHTML = items.length
      ? items.map(it => {
          const url = '/screenshot-file?name=' + encodeURIComponent(it.name) + '&t=' + it.time;
          const when = new Date(it.time).toLocaleString();
          return `<div class="shot">
            <a href="${url}" target="_blank" rel="noopener"><img src="${url}" alt="${atvEsc(it.name)}" loading="lazy"></a>
            <div class="shot-meta">${atvEsc(when)} · ${atvEsc(I18N.t('web_shots_size', Math.max(1, Math.round(it.size / 1024))))}</div>
            <div class="shot-actions">
              <a class="button-link" href="${url}&download=1" download="${atvEsc(it.name)}">${atvEsc(I18N.t('web_shots_download'))}</a>
              <button type="button" class="secondary" onclick="deleteScreenshot('${atvEsc(it.name)}')">${atvEsc(I18N.t('web_shots_delete'))}</button>
            </div>
          </div>`;
        }).join('')
      : `<div class="hint">${I18N.t('web_shots_none')}</div>`;
  } catch (e) {
    list.innerHTML = `<div class="hint">${I18N.t('web_shots_failed', e.message)}</div>`;
  }
}

async function takeScreenshot() {
  const btn = document.getElementById('shotTakeBtn');
  if (btn) btn.disabled = true;
  try {
    const res = await fetch('/screenshot', { method: 'POST' });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
    showToast(I18N.t('web_shots_taken'));
    await loadScreenshots();
  } catch (e) {
    showToast(I18N.t('web_shots_failed', e.message), 'error');
  } finally {
    if (btn) btn.disabled = false;
  }
}

async function deleteScreenshot(name) {
  await postScreenshotDelete({ name });
}

async function deleteAllScreenshots() {
  if (!confirm(I18N.t('web_shots_confirm_all'))) return;
  await postScreenshotDelete({ all: '1' });
}

async function postScreenshotDelete(params) {
  try {
    const res = await fetch('/screenshot-delete', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(params).toString()
    });
    if (!res.ok) throw new Error('HTTP ' + res.status);
  } catch (e) {
    showToast(I18N.t('web_shots_failed', e.message), 'error');
  }
  await loadScreenshots();
}
