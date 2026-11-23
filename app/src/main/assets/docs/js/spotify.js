// ---- Spotify (1.6.2, direct, no Home Assistant) -----------------------------
//
// The Devices page's Audio tab: lists the Spotify accounts and adds one. Each
// user makes their own app on Spotify's developer dashboard (its Client ID,
// redirect URI http://127.0.0.1:8888/callback). POST /spotify-start gives the
// sign-in page (PKCE); after it, the browser lands on a 127.0.0.1 page that
// doesn't load, whose address (it holds the code) is pasted here and sent to
// POST /spotify-finish. The remote keeps the refresh token (it never comes to
// this page); the account is then saved with the other connection settings
// (`spotify_*[]` rows of POST /save-connection, see persistHaAndHubs()).
//
// Uses devices-page.js globals: spotifies, dashboardData, and its helpers
// (showToast, atvEsc, persistHaAndHubs, saveHaDevicesSilently, closeDeviceForms,
// renderDevicesList, deviceCardHtml).

function renderSpotifies() {
  const list = document.getElementById('spotifyList');
  if (!list) return;
  list.innerHTML = spotifies.length
    ? spotifies.map(a => deviceCardHtml({
        kind: 'audio',
        name: atvEsc(a.name),
        details: [a.account, a.entityId].filter(Boolean).map(atvEsc).join(' · '),
        badge: I18N.t('web_dev_spotify'),
        state: a.paired === false ? I18N.t('web_dev_spotify_not_signed')
          : (a.reachable ? I18N.t('web_dev_hue_online') : I18N.t('web_dev_hue_offline')),
        tone: a.paired === false ? 'warn' : (a.reachable ? 'ok' : 'bad'),
        actions: `<span class="remove" style="color:#00E5FF" onclick="renameSpotify('${atvEsc(a.localId)}')">✎</span> <span class="remove" onclick="removeSpotify('${atvEsc(a.localId)}')">✕</span>`
      })).join('')
    : `<div class="hint">${I18N.t('web_dev_list_no_spotify')}</div>`;
}

function openSpotifyForm() {
  document.getElementById('spotifyForm').style.display = '';
  document.getElementById('spotifyStep2').style.display = 'none';
  document.getElementById('spotifyClientId').value = '';
  document.getElementById('spotifyName').value = '';
  document.getElementById('spotifyLanding').value = '';
  document.getElementById('spotifyForm').scrollIntoView({ behavior: 'smooth', block: 'center' });
  document.getElementById('spotifyClientId').focus();
}

async function postForm(url, fields) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(fields).toString()
  });
  const data = await res.json();
  if (!res.ok || data.error) throw new Error(data.error || 'HTTP ' + res.status);
  return data;
}

/** Step 1: the sign-in page, opened in a new tab. */
async function startSpotify() {
  const clientId = document.getElementById('spotifyClientId').value.trim();
  if (!/^[0-9a-fA-F]{32}$/.test(clientId)) {
    showToast(I18N.t('web_dev_spotify_bad_client'), 'error');
    return;
  }
  const btn = document.getElementById('spotifyStartBtn');
  btn.disabled = true;
  try {
    const data = await postForm('/spotify-start', { clientId });
    window.open(data.url, '_blank', 'noopener');
    document.getElementById('spotifyStep2').style.display = '';
    document.getElementById('spotifyLanding').focus();
  } catch (e) {
    showToast(I18N.t('web_dev_spotify_failed', e.message), 'error');
  } finally {
    btn.disabled = false;
  }
}

/** Step 2: the address the browser landed on; the account is then saved. */
async function finishSpotify() {
  const url = document.getElementById('spotifyLanding').value.trim();
  if (!url) return;
  const btn = document.getElementById('spotifyFinishBtn');
  btn.disabled = true;
  try {
    const data = await postForm('/spotify-finish', { url });
    const name = document.getElementById('spotifyName').value.trim() || 'Spotify';
    const entry = { localId: data.localId, name, account: data.account, paired: true, reachable: true };
    spotifies = spotifies.filter(a => a.localId !== entry.localId).concat(entry);
    await persistHaAndHubs();
    closeDeviceForms();
    renderDevicesList();
    showToast(I18N.t('web_dev_spotify_added', data.account));
  } catch (e) {
    showToast(I18N.t('web_dev_spotify_failed', e.message), 'error');
  } finally {
    btn.disabled = false;
  }
}

async function renameSpotify(localId) {
  const a = spotifies.find(x => x.localId === localId);
  if (!a) return;
  const name = prompt(I18N.t('web_dev_spotify_rename_prompt'), a.name);
  if (name === null || !name.trim()) return;
  a.name = name.trim();
  await persistHaAndHubs();
  renderDevicesList();
}

async function removeSpotify(localId) {
  const a = spotifies.find(x => x.localId === localId);
  if (!a) return;
  if (!confirm(I18N.t('web_dev_spotify_confirm_remove', a.name))) return;
  spotifies = spotifies.filter(x => x.localId !== localId);
  dashboardData.haDevices = (dashboardData.haDevices || []).filter(d => d.spotify !== localId);
  await persistHaAndHubs();
  await saveHaDevicesSilently();
  renderDevicesList();
}
