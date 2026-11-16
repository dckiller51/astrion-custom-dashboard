// ---- Plex server (1.5.9) ------------------------------------------------------------
//
// The Plex server and its token, kept on the remote (GET/POST /plex, web/PlexRoutes.kt) rather
// than in dashboard.json: a Plex card without its own host / token uses them. The token is never
// sent back to the page. With it, what's playing (synopsis, poster) comes from Plex and/or TMDB
// (mediaInfo: auto, plex, tmdb).

let plexState = { configured: false, host: '', mediaInfo: 'auto' };

function plexEsc(v) {
  return String(v == null ? '' : v).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
}

async function loadPlexServer() {
  try {
    const res = await fetch('/plex');
    if (res.ok) plexState = await res.json();
  } catch (e) { /* the remote didn't answer: shown as not set */ }
  renderPlexServer();
}

function renderPlexServer() {
  const tile = document.getElementById('tilePlexCount');
  if (tile) tile.textContent = plexState.configured ? (plexState.host || '✓') : I18N.t('web_plexdev_tile_off');
  const box = document.getElementById('plexServerBox');
  if (!box) return;
  box.innerHTML = `
    <p class="hint">${I18N.t('web_plexdev_intro')}</p>
    <label for="plexDevHost">${I18N.t('web_card_plex_host')}</label>
    <input type="text" id="plexDevHost" value="${plexEsc(plexState.host)}" placeholder="http://192.168.1.10:32400">
    <label for="plexDevToken">${I18N.t('web_card_plex_token')}</label>
    <input type="password" id="plexDevToken" autocomplete="off" placeholder="${plexEsc(I18N.t(plexState.configured ? 'web_tmdb_token_saved' : 'web_plexdev_token_ph'))}">
    <div class="hint"><a href="https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-Plex#finding-your-token" target="_blank" rel="noopener">${I18N.t('web_card_plex_token_help')}</a></div>
    <label for="plexDevMediaInfo">${I18N.t('web_plexdev_media_info')}</label>
    <select id="plexDevMediaInfo">
      ${['auto', 'plex', 'tmdb'].map(v => `<option value="${v}"${(plexState.mediaInfo || 'auto') === v ? ' selected' : ''}>${I18N.t('web_plexdev_media_info_' + v)}</option>`).join('')}
    </select>
    <div class="hint">${I18N.t('web_plexdev_media_info_hint')}</div>
    <div class="btn-row" style="margin-top:10px">
      <button type="button" onclick="savePlexServer()">${I18N.t('web_tmdb_save')}</button>
      <button type="button" class="secondary" onclick="testPlexServer()">${I18N.t('web_plexdev_test')}</button>
      ${plexState.configured ? `<button type="button" class="secondary" onclick="removePlexServer()">${I18N.t('web_tmdb_remove')}</button>` : ''}
    </div>
    <div id="plexDevStatus" class="hint"></div>`;
}

async function postPlex(path, params) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(params).toString()
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
  return data;
}

function plexFormParams() {
  const params = {
    host: document.getElementById('plexDevHost').value.trim(),
    mediaInfo: document.getElementById('plexDevMediaInfo').value
  };
  const token = document.getElementById('plexDevToken').value.trim();
  if (token) params.token = token;
  return params;
}

async function savePlexServer() {
  try {
    plexState = await postPlex('/plex', plexFormParams());
    renderPlexServer();
    document.getElementById('plexDevStatus').textContent = I18N.t('web_plexdev_saved');
  } catch (e) {
    document.getElementById('plexDevStatus').textContent = '⚠ ' + e.message;
  }
}

async function testPlexServer() {
  const out = document.getElementById('plexDevStatus');
  out.textContent = '…';
  try {
    await postPlex('/plex-test', plexFormParams());
    out.textContent = I18N.t('web_plexdev_test_ok');
  } catch (e) {
    out.textContent = '⚠ ' + e.message;
  }
}

async function removePlexServer() {
  try {
    plexState = await postPlex('/plex', { host: '', token: '' });
    renderPlexServer();
  } catch (e) {
    document.getElementById('plexDevStatus').textContent = '⚠ ' + e.message;
  }
}

I18N.ready.then(loadPlexServer);
