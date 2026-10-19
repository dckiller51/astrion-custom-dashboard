// ---- Deep links (1.3.1) ------------------------------------------------------
// Tools (Astrion Config Manager, Home Assistant…) open the builder at the
// right place with a hash:
//   /builder/#history                    version history (1.3.0)
//   /builder/#page=<name>                that page
//   /builder/#page=<name>&card=<index>   that card's form (index from 0)
//   /builder/#activity=<id>              that Activity's wizard
//   /builder/#hotkeys                    the physical keys section
// Names are URL-encoded. Anything unknown just leaves the builder as it is.
// Read once the remote's dashboard is loaded (device.js), and on hashchange.

/** Parses a builder hash into {target, …}, or null when it isn't a deep link. */
function parseBuilderHash(hash) {
  const h = String(hash || '').replace(/^#/, '');
  if (!h) return null;
  if (h === 'history' || h === 'hotkeys') return { target: h };
  const params = new URLSearchParams(h);
  const activity = params.get('activity');
  if (activity) return { target: 'activity', id: activity };
  const page = params.get('page');
  if (page) {
    const card = params.get('card');
    return { target: 'page', page, card: card !== null && /^\d+$/.test(card) ? Number(card) : null };
  }
  return null;
}

function openFromHash() {
  const link = parseBuilderHash(location.hash);
  if (!link) return;
  if (link.target === 'history') {
    if (typeof deviceModeAvailable !== 'undefined' && deviceModeAvailable) openHistory();
    return;
  }
  // Every other target replaces whatever was open.
  if (typeof closeOpenDialogs === 'function') closeOpenDialogs();
  document.getElementById('historyModal')?.classList.remove('open');
  if (link.target === 'hotkeys') {
    flashSection(document.getElementById('hotkeysSection'));
  } else if (link.target === 'activity') {
    if ((dashboardData.activities || []).some(a => a.id === link.id)) startActivityWizard(link.id);
  } else if (link.target === 'page') {
    const idx = (dashboardData.pages || []).findIndex(p => p.name === link.page);
    if (idx < 0) return;
    onPageChange(idx);
    const cards = dashboardData.pages[idx].cards || [];
    if (link.card !== null && link.card < cards.length) editCard(link.card);
    else document.getElementById('preview-container')?.scrollIntoView({ block: 'start' });
  }
}

/** Scrolls to a builder section and highlights it for a moment. */
function flashSection(el) {
  if (!el) return;
  el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  el.classList.add('deeplink-flash');
  setTimeout(() => el.classList.remove('deeplink-flash'), 1600);
}

window.addEventListener('hashchange', openFromHash);
