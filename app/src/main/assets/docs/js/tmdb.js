// ---- TMDB (1.6.0) -----------------------------------------------------------------
//
// The user's own TMDB token, kept on the remote (GET/POST /tmdb, web/TmdbRoutes.kt): the remote
// then gives every media player that plays a film or a series its synopsis, year, rating and
// poster (astrion_overview / astrion_year / astrion_rating / astrion_poster, tmdb/TmdbRegistry.kt).
// The token is never sent back to the page: it only says whether one is saved.

let tmdbState = { configured: false, language: '', effectiveLanguage: '' };

function tmdbEsc(v) {
  return String(v == null ? '' : v).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
}

async function loadTmdb() {
  try {
    const res = await fetch('/tmdb');
    if (res.ok) tmdbState = await res.json();
  } catch (e) { /* the remote didn't answer: the form shows "not set" */ }
  renderTmdb();
}

function renderTmdb() {
  const tile = document.getElementById('tileTmdbCount');
  if (tile) tile.textContent = I18N.t(tmdbState.configured ? 'web_tmdb_tile_on' : 'web_tmdb_tile_off');
  const box = document.getElementById('tmdbBox');
  if (!box) return;
  const langs = ['', 'fr-FR', 'en-US', 'de-DE', 'it-IT', 'es-ES'];
  box.innerHTML = `
    <p class="hint">${I18N.t('web_tmdb_intro')}</p>
    <p class="hint">${I18N.t('web_tmdb_how')} <a href="https://www.themoviedb.org/settings/api" target="_blank" rel="noopener">themoviedb.org/settings/api</a></p>
    <label for="tmdbToken">${I18N.t('web_tmdb_token')}</label>
    <input type="password" id="tmdbToken" autocomplete="off" placeholder="${tmdbEsc(I18N.t(tmdbState.configured ? 'web_tmdb_token_saved' : 'web_tmdb_token_ph'))}">
    <label for="tmdbLanguage">${I18N.t('web_tmdb_language')}</label>
    <select id="tmdbLanguage">
      ${langs.map(l => `<option value="${l}"${l === (tmdbState.language || '') ? ' selected' : ''}>${l ? l : tmdbEsc(I18N.t('web_tmdb_language_auto', tmdbState.effectiveLanguage || ''))}</option>`).join('')}
    </select>
    <div class="btn-row" style="margin-top:10px">
      <button type="button" onclick="saveTmdb()">${I18N.t('web_tmdb_save')}</button>
      ${tmdbState.configured ? `<button type="button" class="secondary" onclick="removeTmdb()">${I18N.t('web_tmdb_remove')}</button>` : ''}
    </div>
    <div id="tmdbStatus" class="hint"></div>
    <h3 style="margin:18px 0 6px">${I18N.t('web_tmdb_test')}</h3>
    <div style="display:flex;gap:8px">
      <input type="text" id="tmdbTestTitle" placeholder="Gemini Man" style="flex:1">
      <button type="button" class="secondary" onclick="testTmdb()">${I18N.t('web_tmdb_test_btn')}</button>
    </div>
    <div id="tmdbTestResult" style="margin-top:8px"></div>
    <p class="hint" style="margin-top:18px">${I18N.t('web_tmdb_attribution')}</p>`;
}

async function postTmdb(path, params) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(params).toString()
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
  return data;
}

async function saveTmdb() {
  const token = document.getElementById('tmdbToken').value.trim();
  const params = { language: document.getElementById('tmdbLanguage').value };
  if (token) params.token = token;
  try {
    tmdbState = await postTmdb('/tmdb', params);
    renderTmdb();
    document.getElementById('tmdbStatus').textContent = I18N.t('web_tmdb_saved');
  } catch (e) {
    document.getElementById('tmdbStatus').textContent = '⚠ ' + e.message;
  }
}

async function removeTmdb() {
  try {
    tmdbState = await postTmdb('/tmdb', { token: '', language: document.getElementById('tmdbLanguage').value });
    renderTmdb();
  } catch (e) {
    document.getElementById('tmdbStatus').textContent = '⚠ ' + e.message;
  }
}

async function testTmdb() {
  const out = document.getElementById('tmdbTestResult');
  const title = document.getElementById('tmdbTestTitle').value.trim();
  if (!title) return;
  const params = { title };
  const token = document.getElementById('tmdbToken').value.trim();
  if (token) params.token = token;
  out.textContent = '…';
  try {
    const r = await postTmdb('/tmdb-test', params);
    if (!r.found) { out.textContent = I18N.t('web_tmdb_test_none'); return; }
    const meta = [r.year, r.rating != null ? '★ ' + Number(r.rating).toFixed(1) : null].filter(Boolean).join(' · ');
    out.innerHTML = `<div style="display:flex;gap:10px;align-items:flex-start">
      ${r.poster ? `<img src="${tmdbEsc(r.poster)}" alt="" style="width:70px;border-radius:8px">` : ''}
      <div><strong>${tmdbEsc(r.title)}</strong><div class="hint">${tmdbEsc(meta)}</div><div class="hint">${tmdbEsc(r.overview)}</div></div>
    </div>`;
  } catch (e) {
    out.textContent = '⚠ ' + e.message;
  }
}

I18N.ready.then(loadTmdb);
