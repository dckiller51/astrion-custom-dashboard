// ---- playstation card (1.5.8) ------------------------------------------------------------
//
// Profile, game and trophies from Home Assistant's official PlayStation Network integration.
// Its entity ids follow Home Assistant's language when it was added (sensor.zavie_statut_en_ligne
// in French), so an account is found by what its entities carry, not by their names: the online
// status sensor (an enum with "availabletoplay"), its siblings sharing the account's prefix, the
// "%" of the next level, the trophy counts' unit… Every field stays editable by hand.

const PSN_FIELDS = ['profile', 'status', 'plus', 'game', 'picture', 'level', 'progress', 'platinum', 'gold', 'silver', 'bronze'];

/** Words of each trophy's name in Home Assistant's languages, for telling the four counts apart. */
const PSN_TROPHY_WORDS = {
  platinum: /platin/,
  gold: /gold|_or(_|$)|oro/,
  silver: /silver|argent|silber|plata/,
  bronze: /bronz|bronce/
};

function psnSlug(text) {
  return String(text || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '');
}

/** Detected accounts: [{tag, profile, status, plus, game, picture, level, progress, platinum, gold, silver, bronze}]. */
function psnAccounts() {
  if (typeof haStates === 'undefined' || !haStates) return [];
  const all = Object.entries(haStates);
  const attrs = e => (e && e.attributes) || {};
  const ps = all.find(([id, e]) => id.startsWith('media_player.') && attrs(e).media_content_type === 'game' && /playstation|ps[45]/.test(id))
    || all.find(([id]) => /^media_player\.playstation/.test(id));
  return all
    .filter(([id, e]) => id.startsWith('sensor.') && Array.isArray(attrs(e).options) && attrs(e).options.includes('availabletoplay'))
    .map(([statusId]) => {
      const object = statusId.slice('sensor.'.length);
      // The account's prefix: its online ID sensor (its state is the name, it carries the avatar).
      const idSensor = all.find(([id, e]) => id.startsWith('sensor.') && attrs(e).entity_picture && e.state &&
        object.startsWith(psnSlug(e.state) + '_') && id.split('.')[1].startsWith(psnSlug(e.state) + '_'));
      const prefix = idSensor ? psnSlug(idSensor[1].state) : object.split('_')[0];
      const mine = all.filter(([id]) => id.split('.')[1].startsWith(prefix + '_'));
      const sensors = mine.filter(([id]) => id.startsWith('sensor.'));
      const acc = { tag: idSensor ? idSensor[1].state : prefix, profile: idSensor ? idSensor[0] : '', status: statusId };
      acc.progress = (sensors.find(([, e]) => attrs(e).unit_of_measurement === '%') || [''])[0];
      // The four counts share a unit ("trophies", translated): by their name, else by their value (platinum is the rarest).
      const counts = sensors.filter(([, e]) => attrs(e).unit_of_measurement && attrs(e).unit_of_measurement !== '%' && !isNaN(parseFloat(e.state)));
      const left = counts.slice();
      Object.entries(PSN_TROPHY_WORDS).forEach(([kind, re]) => {
        const i = left.findIndex(([id]) => re.test(id.split('.')[1].slice(prefix.length)));
        if (i >= 0) acc[kind] = left.splice(i, 1)[0][0];
      });
      left.sort((a, b) => parseFloat(a[1].state) - parseFloat(b[1].state));
      ['platinum', 'gold', 'silver', 'bronze'].filter(k => !acc[k]).forEach(k => { const next = left.shift(); acc[k] = next ? next[0] : ''; });
      // The level: a number without a unit, not a timestamp.
      acc.level = (sensors.find(([id, e]) => id !== statusId && !attrs(e).unit_of_measurement && !attrs(e).device_class &&
        !attrs(e).entity_picture && /^\d+$/.test(e.state)) || [''])[0];
      acc.plus = (mine.find(([id]) => id.startsWith('binary_sensor.')) || [''])[0];
      acc.game = ps ? ps[0] : '';
      return acc;
    });
}

function playstationFormHtml() {
  const field = (key, ph) => `<label>${I18N.t('web_card_psn_' + key)}</label><input type="text" id="psn_${key}" placeholder="${ph}">`;
  return `
    <div id="psnAccountBox">
      <div class="hint">${I18N.t('web_card_psn_hint')}</div>
      <label>${I18N.t('web_card_psn_account')}</label>
      <div style="display:flex;gap:8px;align-items:center">
        <select id="psnAccount" style="flex:1"></select>
        <button type="button" class="secondary" style="margin:0" onclick="fillPsnFromAccount()">${I18N.t('web_card_xbox_fill')}</button>
      </div>
    </div>
    <div id="psnProfileBox">
      <label class="inline-check"><input type="checkbox" id="psnShowProfile" checked> ${I18N.t('web_card_psn_show_profile')}</label>
      ${field('profile', 'sensor.name_online_id')}${field('status', 'sensor.name_online_status')}${field('plus', 'binary_sensor.name_…')}
    </div>
    <div id="psnGameBox">
      <label class="inline-check"><input type="checkbox" id="psnShowGame" checked> ${I18N.t('web_card_psn_show_game')}</label>
      ${field('game', 'media_player.playstation_5')}
      <div class="hint">${I18N.t('web_card_psn_game_hint')}</div>
      ${field('picture', 'image.name_now_playing')}
    </div>
    <div id="psnTrophiesBox">
      <label class="inline-check"><input type="checkbox" id="psnShowTrophies" checked> ${I18N.t('web_card_psn_show_trophies')}</label>
      ${field('level', 'sensor.name_trophy_level')}${field('progress', 'sensor.name_next_level')}
      ${['platinum', 'gold', 'silver', 'bronze'].map(k => field(k, 'sensor.name_…')).join('')}
    </div>`;
}

function initPlaystationForm() {
  window._psnExtra = {};
  const accounts = psnAccounts();
  const select = document.getElementById('psnAccount');
  select.innerHTML = accounts.length
    ? accounts.map((a, i) => `<option value="${i}">${cxEsc(a.tag)}</option>`).join('')
    : `<option value="">${I18N.t('web_card_psn_no_account')}</option>`;
  select.disabled = !accounts.length;
  fillPsnFromAccount(); // the first account found, so a new card works at once
  const domain = { game: 'media_player', picture: 'image', plus: 'binary_sensor' };
  PSN_FIELDS.forEach(k => attachEntityAutocomplete(document.getElementById('psn_' + k), domain[k] || 'sensor'));
}

/** Fills every entity field from the account picked (detected in Home Assistant). */
function fillPsnFromAccount() {
  const acc = psnAccounts()[parseInt(document.getElementById('psnAccount').value, 10)];
  if (!acc) return;
  PSN_FIELDS.forEach(k => { document.getElementById('psn_' + k).value = acc[k] || ''; });
}

function fillPlaystationForm(o) {
  PSN_FIELDS.forEach(k => { document.getElementById('psn_' + k).value = o[k + '_entity'] || ''; });
  document.getElementById('psnShowProfile').checked = o.show_profile !== false;
  document.getElementById('psnShowGame').checked = o.show_game !== false;
  document.getElementById('psnShowTrophies').checked = o.show_trophies !== false;
  window._psnExtra = cxWithout(o, PSN_FIELDS.map(k => k + '_entity').concat(['show_profile', 'show_game', 'show_trophies']));
}

function collectPlaystationOptions() {
  const out = { ...(window._psnExtra || {}) };
  PSN_FIELDS.forEach(k => { const v = document.getElementById('psn_' + k).value.trim(); if (v) out[k + '_entity'] = v; });
  if (!out.profile_entity && !out.game_entity && !out.level_entity) { alert(I18N.t('web_card_psn_err_empty')); return null; }
  [['psnShowProfile', 'show_profile'], ['psnShowGame', 'show_game'], ['psnShowTrophies', 'show_trophies']].forEach(([id, key]) => {
    if (!document.getElementById(id).checked) out[key] = false;
  });
  return out;
}

// ---- preview: real values when the builder runs on the remote, placeholders otherwise ----

function playstationPreviewHtml(o) {
  const st = id => (id && typeof haStates !== 'undefined' && haStates) ? haStates[id] : null;
  const box = inner => `<div style="background:#151A20;border-radius:12px;padding:8px">${inner}</div>`;
  const parts = [];
  if (o.profile_entity && o.show_profile !== false) {
    const p = st(o.profile_entity);
    const s = st(o.status_entity);
    const online = s && s.state === 'availabletoplay';
    const plus = st(o.plus_entity);
    parts.push(`<div style="display:flex;gap:10px;align-items:center"><div style="width:36px;height:36px;border-radius:50%;background:#1B2027"></div>
      <div style="flex:1"><b style="color:#E6EEF2">${cxEsc(p ? p.state : 'PSN')}</b><div style="color:${online ? '#6FD08C' : '#9AA8AD'};font-size:11px">${I18N.t(online ? 'web_card_xbox_online' : 'web_card_xbox_offline')}</div></div>
      ${plus && plus.state === 'on' ? '<span style="background:#E2B33D;color:#000;font-size:10px;font-weight:700;border-radius:6px;padding:2px 6px">PS Plus</span>' : ''}</div>`);
  }
  if (o.game_entity && o.show_game !== false) {
    const g = st(o.game_entity);
    const title = g ? (o.game_entity.startsWith('media_player.') ? (g.attributes && g.attributes.media_title) : g.state) : I18N.t('web_card_xbox_game_ph');
    parts.push(box(`<div style="display:flex;gap:10px;align-items:center"><div style="width:56px;height:56px;border-radius:9px;background:#1B2027;flex:none"></div>
      <b style="color:#E6EEF2">${cxEsc(title && title !== 'unknown' ? title : I18N.t('web_card_psn_no_game'))}</b></div>`));
  }
  if (o.show_trophies !== false && (o.level_entity || o.gold_entity)) {
    const n = id => { const e = st(id); return e ? cxEsc(String(parseInt(e.state, 10) || 0)) : '—'; };
    const lvl = st(o.level_entity);
    const pct = st(o.progress_entity);
    const tiles = [['platinum', '#B4C8DC'], ['gold', '#E2B33D'], ['silver', '#BFC5CB'], ['bronze', '#C4824C']].map(([k, c]) =>
      `<div style="flex:1;background:#1B2027;border-radius:8px;padding:4px;text-align:center"><div style="color:${c}">🏆</div><b style="color:#E6EEF2;font-size:12px">${n(o[k + '_entity'])}</b></div>`).join('');
    parts.push(box(`<div style="display:flex;justify-content:space-between;color:#E6EEF2;font-weight:700;font-size:13px"><span>${I18N.t('web_card_psn_level_preview')} ${lvl ? cxEsc(lvl.state) : '—'}</span>
      <span style="color:#9AA8AD;font-weight:400;font-size:11px">${pct ? cxEsc(pct.state) + ' %' : ''}</span></div>
      <div style="height:5px;border-radius:3px;background:#1B2027;margin:6px 0"><div style="height:5px;border-radius:3px;background:#00E5FF;width:${pct ? Math.min(100, parseFloat(pct.state) || 0) : 0}%"></div></div>
      <div style="display:flex;gap:4px">${tiles}</div>`));
  }
  return `<div style="display:flex;flex-direction:column;gap:8px">${parts.join('')}</div>`;
}
