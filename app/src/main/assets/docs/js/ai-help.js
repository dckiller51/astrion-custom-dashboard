// ---- "Ask an AI" (1.5.7, Xavier's first level) ------------------------------------------------
//
// No AI in the app: a text ready to paste into the assistant the user picks (ChatGPT, Claude,
// Gemini, Le Chat…), with what it needs to answer for Astrion and nothing else: the wiki's JSON
// guide, the card pages, the current dashboard.json (secrets left out) and the remote's entity ids.
// The assistant's answer is pasted back into "Load an existing dashboard.json", and undo is there.

/** Keys whose values never leave the builder (a Plex token, a password…). */
const AI_SECRET = /token|password|passwd|secret|api_?key/i;

function aiRedact(value) {
  if (Array.isArray(value)) return value.map(aiRedact);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, AI_SECRET.test(k) && typeof v === 'string' ? '<hidden>' : aiRedact(v)]));
  }
  return value;
}

/** The remote's entity ids worth knowing for a dashboard (at most 300), when the builder runs on it. */
function aiEntities() {
  if (typeof haStates === 'undefined' || !haStates) return [];
  const useful = /^(media_player|remote|light|switch|cover|fan|climate|sensor|binary_sensor|scene|script|select|input_select|camera|vacuum)\./;
  return Object.keys(haStates).filter(id => useful.test(id)).sort().slice(0, 300);
}

function aiPrompt() {
  const guide = { fr: 'GUIDE_dashboard_json_FR', de: 'ANLEITUNG_dashboard_json_DE', it: 'GUIDA_dashboard_json_IT', es: 'GUIA_dashboard_json_ES' }[I18N.lang] || 'GUIDE_dashboard_json_EN';
  const json = JSON.stringify(aiRedact(typeof dashboardData === 'undefined' ? {} : dashboardData), null, 2);
  const entities = aiEntities();
  return [
    I18N.t('web_ai_prompt_intro'),
    '',
    I18N.t('web_ai_prompt_docs'),
    `- ${WIKI_BASE}${guide}`,
    `- ${WIKI_BASE}Home`,
    '',
    I18N.t('web_ai_prompt_rules'),
    '',
    I18N.t('web_ai_prompt_request'),
    I18N.t('web_ai_prompt_placeholder'),
    '',
    entities.length ? I18N.t('web_ai_prompt_entities') + '\n' + entities.join('\n') + '\n' : '',
    I18N.t('web_ai_prompt_current'),
    '```json',
    json,
    '```'
  ].join('\n');
}

/** Shows the text, selected and copied when the browser lets it, with what to do next. */
function openAiHelp() {
  const text = aiPrompt();
  let dlg = document.getElementById('aiHelpDialog');
  if (!dlg) {
    dlg = document.createElement('div');
    dlg.id = 'aiHelpDialog';
    dlg.className = 'ha-dialog-backdrop';
    dlg.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,.6);display:flex;align-items:center;justify-content:center;z-index:1000;padding:16px';
    dlg.innerHTML = `
      <div style="background:#1e1e1e;border:1px solid #333;border-radius:12px;max-width:720px;width:100%;max-height:90vh;display:flex;flex-direction:column;gap:10px;padding:16px">
        <h2 style="margin:0">${I18N.t('web_ai_title')}</h2>
        <div class="hint" style="margin:0">${I18N.t('web_ai_steps')}</div>
        <textarea id="aiHelpText" readonly style="flex:1;min-height:260px;font-family:monospace;font-size:12px"></textarea>
        <div class="btn-row" style="margin:0">
          <button onclick="copyAiHelp()">${I18N.t('web_ai_copy')}</button>
          <button class="secondary" onclick="document.getElementById('aiHelpDialog').remove()">${I18N.t('web_ai_close')}</button>
        </div>
      </div>`;
    dlg.addEventListener('click', ev => { if (ev.target === dlg) dlg.remove(); });
    document.body.appendChild(dlg);
  }
  document.getElementById('aiHelpText').value = text;
  copyAiHelp();
}

async function copyAiHelp() {
  const box = document.getElementById('aiHelpText');
  let ok = false;
  if (navigator.clipboard && window.isSecureContext) {
    try { await navigator.clipboard.writeText(box.value); ok = true; } catch (e) { ok = false; }
  }
  if (!ok) {
    box.focus();
    box.select();
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
  }
  if (typeof showToast === 'function') showToast(I18N.t(ok ? 'web_ai_copied' : 'web_export_copy_failed'), ok ? undefined : 'error');
}
