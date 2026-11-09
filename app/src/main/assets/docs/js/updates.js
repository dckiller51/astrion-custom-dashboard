// ---- App updates (1.5.5: the home page's Updates view, and the builder's header) ---------
//
// The installed version (GET /version), the official release and the beta (dev-latest) with
// Install / Reinstall, and test builds (POST /install-test-update, a pre-release tagged
// test-latest / test-…). Loaded by devices.html and index.html; needs showToast().

/** On the remote: the version answered, and in the builder its dashboard loaded too. */
function onRemote() {
  return installedVersion != null && (typeof deviceModeAvailable === 'undefined' || deviceModeAvailable);
}

// ---- Release badges (official + beta) --------------------------------------

// Release-badge and physical-key strings live in the shared i18n tables
// (keys web_release_* / web_hwkey_* — see js/i18n.js); applyUiI18n() lives
// in js/i18n.js too.
const RELEASE_KEYS = {
  stableUnavailable: 'web_release_stable_unavailable',
  installOfficial: 'web_release_install_official',
  installBeta: 'web_release_install_beta',
  installing: 'web_release_installing',
  installStarted: 'web_release_install_started',
  installFailed: 'web_release_install_failed',
  alsoBeta: 'web_release_also_beta'
};
const relT = (key) => I18N.t(RELEASE_KEYS[key] || key);

// 1.4.0: the row reads "Installed: 1.3.9-beta · Official: v1.3.0 (older) · Beta: 1.4.0-beta ⬆️ Install".
// A user took the old ✅ in front of the official release to mean "installed". Now each release
// says how it compares with what this remote runs, and only a newer one offers to install.
let installedVersion = null; // from the remote's /version; null outside the remote or before it answered

/** The release's name/tag, its version (x.y.z, beta or not) and its APK link; null when GitHub didn't answer. */
async function fetchRelease(url) {
  try {
    const res = await fetch(url);
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    const asset = (data.assets || []).find(a => a.name.endsWith('.apk'));
    const label = data.name || data.tag_name || '';
    return { label, version: parseVersion(label) || parseVersion(data.tag_name), apk: asset ? asset.browser_download_url : null };
  } catch (e) {
    console.log('Release fetch failed', e);
    return null;
  }
}

/** "v1.3.9-beta" → { nums: [1,3,9], beta: true }; null without an x.y.z in it. */
function parseVersion(text) {
  const m = /(\d+)\.(\d+)\.(\d+)(-beta)?/i.exec(String(text || ''));
  return m ? { nums: [+m[1], +m[2], +m[3]], beta: !!m[4] } : null;
}

/** <0: [a] is older than [b], 0: the same, >0: newer. A release beats its own beta. */
function compareVersions(a, b) {
  for (let i = 0; i < 3; i++) if (a.nums[i] !== b.nums[i]) return a.nums[i] - b.nums[i];
  return (a.beta ? 0 : 1) - (b.beta ? 0 : 1);
}

/** One release's badge: its name, then installed / newer (+ install button) / older, or its APK link outside the remote. */
function releaseBadgeHtml(prefix, release, installFn, installKey) {
  const name = `<span class="version-chip">${escapeHtmlText(release.label)}</span>`;
  const mine = installedVersion ? parseVersion(installedVersion) : null;
  if (!mine || !release.version || !onRemote()) {
    return `${prefix} ${name}` + (release.apk ? ` — <a href="${release.apk}">${I18N.t('web_release_download_apk')}</a>` : '');
  }
  const cmp = compareVersions(release.version, mine);
  // 1.5.5: the version installed can be installed again, as on the remote itself.
  const again = `<button type="button" class="secondary" onclick="${installFn}(this, true)" style="padding:2px 8px;font-size:0.75rem;margin:0 0 0 4px">${I18N.t('web_release_reinstall')}</button>`;
  if (cmp === 0) return `${prefix} ${name} ✅ ${I18N.t('web_release_is_installed')}${again}`;
  // Same numbers, beta build installed (e.g. 1.4.0-beta vs v1.4.0): the same version, not an update (1.4.1).
  if (release.version.nums.join('.') === mine.nums.join('.')) {
    // The beta badge only: on the official one, it would install the official app next to the beta.
    return `${prefix} ${name} ✅ ${I18N.t('web_release_same_beta')}${installFn === 'installBetaUpdate' ? again : ''}`;
  }
  if (cmp < 0) return `${prefix} ${name} <span style="color:#888">${I18N.t('web_release_older')}</span>`;
  return `${prefix} ${name} ⬆️ <button type="button" onclick="${installFn}(this)" style="padding:4px 10px;font-size:0.8rem">${relT(installKey)}</button>`;
}

function escapeHtmlText(v) {
  return String(v == null ? '' : v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** "📱 Installed: 1.4.0-beta", from the remote itself; then the releases are compared with it. */
async function loadInstalledVersion() {
  await I18N.ready;
  const badge = document.getElementById('installedBadge');
  try {
    const res = await fetch('/version');
    if (!res.ok) throw new Error('HTTP ' + res.status);
    installedVersion = (await res.json()).version || null;
  } catch (e) {
    installedVersion = null;
  }
  if (badge) {
    badge.innerHTML = installedVersion
      ? `📱 ${I18N.t('web_release_installed')} <span class="version-chip">${escapeHtmlText(installedVersion)}</span>`
      : '';
  }
  loadStableBadge();
  if (document.getElementById('betaToggle')?.checked) toggleBetaBadge();
}

async function loadStableBadge() {
  await I18N.ready;
  const badge = document.getElementById('stableBadge');
  if (!badge) return;
  badge.textContent = I18N.t('web_release_loading');
  const release = await fetchRelease('https://api.github.com/repos/dckiller51/astrion-custom-dashboard/releases/latest');
  badge.innerHTML = release
    ? releaseBadgeHtml(`🏷️ ${I18N.t('web_release_official')}`, release, 'installStableUpdate', 'installOfficial')
    : relT('stableUnavailable');
}

async function toggleBetaBadge() {
  await I18N.ready;
  const on = document.getElementById('betaToggle').checked;
  const badge = document.getElementById('betaBadge');
  if (!on) { badge.style.display = 'none'; return; }
  badge.textContent = I18N.t('web_release_loading');
  badge.style.display = 'inline-block';
  const release = await fetchRelease('https://api.github.com/repos/dckiller51/astrion-custom-dashboard/releases/tags/dev-latest');
  badge.innerHTML = release
    ? releaseBadgeHtml(`🧪 ${I18N.t('web_release_beta')}`, release, 'installBetaUpdate', 'installBeta')
    : I18N.t('web_release_beta_unavailable');
}

async function installStableUpdate(btn, force) {
  const original = btn.textContent;
  btn.textContent = relT('installing');
  btn.disabled = true;
  try {
    const res = await fetch(force ? '/install-update?force=1' : '/install-update', { method: 'POST' });
    const text = await res.text();
    if (!res.ok) throw new Error(text || ('HTTP ' + res.status));
    showToast(relT('installStarted'));
  } catch (e) {
    showToast(relT('installFailed') + e.message, 'error');
    btn.textContent = original;
    btn.disabled = false;
  }
}

async function installBetaUpdate(btn) {
  await I18N.ready;
  const original = btn.textContent;
  btn.textContent = I18N.t('web_release_installing');
  btn.disabled = true;
  try {
    const res = await fetch('/install-beta-update', { method: 'POST' });
    const text = await res.text();
    if (!res.ok) throw new Error(text || ('HTTP ' + res.status));
    showToast(I18N.t('web_release_install_started'));
  } catch (e) {
    showToast(I18N.t('web_release_install_failed') + e.message, 'error');
    btn.textContent = original;
    btn.disabled = false;
  }
}
/**
 * A test build (1.5.5): the pre-release tagged test-latest (or test-samsung…, see push-test.ps1),
 * which the official and beta checks never offer. Installed whatever the version, as often as
 * needed; the tag asked once and remembered.
 */
async function installTestBuild(btn) {
  await I18N.ready;
  if (!onRemote()) {
    showToast(I18N.t('web_release_test_device_only'), 'error');
    return;
  }
  let last = 'test-latest';
  try { last = localStorage.getItem('astrion.testTag') || last; } catch (e) { /* private mode */ }
  const tag = (prompt(I18N.t('web_release_test_prompt'), last) || '').trim().toLowerCase();
  if (!tag) return;
  if (!/^test(-[a-z0-9]+)*$/.test(tag)) {
    showToast(I18N.t('web_release_test_bad_tag'), 'error');
    return;
  }
  try { localStorage.setItem('astrion.testTag', tag); } catch (e) { /* private mode */ }
  const release = await fetchRelease('https://api.github.com/repos/dckiller51/astrion-custom-dashboard/releases/tags/' + encodeURIComponent(tag));
  if (!release || !release.apk) {
    showToast(I18N.t('web_release_test_none', tag), 'error');
    return;
  }
  if (!confirm(I18N.t('web_release_test_confirm', release.label))) return;
  const original = btn.textContent;
  btn.textContent = I18N.t('web_release_installing');
  btn.disabled = true;
  try {
    const res = await fetch('/install-test-update', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ tag }).toString()
    });
    const text = await res.text();
    if (!res.ok) throw new Error(text || ('HTTP ' + res.status));
    showToast(I18N.t('web_release_install_started'));
  } catch (e) {
    showToast(I18N.t('web_release_install_failed') + e.message, 'error');
  } finally {
    btn.textContent = original;
    btn.disabled = false;
  }
}


/**
 * A short "⬆️ 1.5.6 available" link to the home page's Updates view, in [elId], when a release
 * newer than the one installed exists: the official one, and the beta on a beta build. Nothing
 * otherwise (or off the remote).
 */
async function loadUpdateNotice(elId) {
  await I18N.ready;
  const el = document.getElementById(elId);
  if (!el) return;
  try {
    const res = await fetch('/version');
    if (!res.ok) return;
    installedVersion = installedVersion || (await res.json()).version || null;
  } catch (e) {
    return;
  }
  const mine = installedVersion ? parseVersion(installedVersion) : null;
  if (!mine) return;
  const urls = ['https://api.github.com/repos/dckiller51/astrion-custom-dashboard/releases/latest'];
  if (mine.beta) urls.push('https://api.github.com/repos/dckiller51/astrion-custom-dashboard/releases/tags/dev-latest');
  for (const url of urls) {
    const release = await fetchRelease(url);
    if (release && release.version && compareVersions(release.version, mine) > 0 &&
        release.version.nums.join('.') !== mine.nums.join('.')) {
      el.innerHTML = `<a href="/#updates" style="color:#00E5FF">⬆️ ${escapeHtmlText(I18N.t('web_updates_available', release.label))}</a>`;
      el.style.display = '';
      return;
    }
  }
}
