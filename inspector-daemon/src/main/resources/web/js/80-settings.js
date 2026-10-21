/* settings: the Settings page — this browser's preferences and the daemon's config.json */
'use strict';

// --- settings: duplicate detection ------------------------------------------------------------

function applyDuplicateWindow(valueMs) {
  const ms = Number.isFinite(valueMs) && valueMs >= 0 ? Math.round(valueMs) : 3000;
  state.duplicateWindowMs = ms;
  prefs.set('inspector.duplicateWindowMs', String(ms));
  $('dup-window').value = ms;
  // The unfiltered set, matching what the list actually highlights. Counting the filtered rows
  // instead made the summary read 0 while the list showed two tinted rows.
  $('dup-summary').textContent = ms === 0
    ? 'off'
    : `${computeDuplicates(state.allTransactions, ms).size} row(s) in this session`;
  renderList();
}

// --- settings: row density ------------------------------------------------------------------

/**
 * The one writer of the row density, for the `⋮` item and the Settings page alike, so the two can
 * never disagree about it or store it under two keys. Compact only takes padding off a row; no
 * text gets smaller, so nothing goes under 11px.
 */
function setDensity(value) {
  state.density = value === 'compact' ? 'compact' : 'comfortable';
  prefs.set('inspector.density', state.density);
  paintDensity();
}

/** The body class and both controls, from `state.density`. */
function paintDensity() {
  const compact = state.density === 'compact';
  document.body.classList.toggle('density-compact', compact);
  $('density').value = state.density;
  const item = document.getElementById('density-item');
  if (item) {
    item.setAttribute('aria-checked', String(compact));
    item.textContent = compact ? 'compact rows ✓' : 'compact rows';
  }
}

// --- settings: endpoint shortcuts -------------------------------------------------------------

function applyEndpointLimit(value) {
  const limit = Number.isFinite(value) && value >= 0 ? Math.round(value) : 10;
  state.endpointLimit = limit;
  prefs.set('inspector.endpointChipLimit', String(limit));
  $('endpoint-limit').value = limit;

  const total = new Set(
    state.allTransactions.map((t) => lastSegment(t.path)).filter(Boolean),
  ).size;
  $('endpoint-summary').textContent = limit === 0
    ? 'hidden'
    : `showing ${Math.min(limit, total)} of ${total} in this session`;
  renderEndpointChips();
}

// --- settings: the daemon's config.json -------------------------------------------------------

/**
 * The keys this page edits, with the flag that overrides each. `port` is shown and not offered:
 * saving a new one and restarting would leave this page redialling a port nobody listens on, so it
 * stays the file's, for `curl` and an editor (`PUT /api/config` still takes it).
 */
const CONFIG_FIELDS = [
  { key: 'port', label: 'port', flag: '--port', readOnly: true },
  { key: 'maxSessions', label: 'keep at most', unit: 'sessions', flag: '--max-sessions' },
  { key: 'maxTotalMb', label: 'and at most', unit: 'MB in all', flag: '--max-mb' },
];

/** The last `GET /api/config`, which a save compares against so it sends only what changed. */
let configReply = null;

async function loadConfig() {
  const status = $('config-status');
  try {
    configReply = await api('/api/config');
  } catch (e) {
    status.textContent = `could not read the config: ${e.message}`;
    status.classList.add('error');
    return;
  }
  status.textContent = '';
  status.classList.remove('error');
  renderConfig(configReply);
}

/**
 * One row per key: what the file says (or, with no file entry, what the daemon runs on) in the
 * input, and what this daemon runs on beside it, since a save changes nothing until it restarts. A
 * key a flag sets is disabled and names the flag, because the flag wins whatever the file says.
 */
function renderConfig(reply) {
  $('config-path').textContent = reply.path;
  const box = $('config-fields');
  box.replaceChildren();
  const file = reply.file || {};
  const flags = reply.flags || [];
  if (reply.fileError) {
    box.appendChild(el('p', 'config-error error',
      `${reply.fileError} — fix or remove the file by hand; a save will not overwrite settings it cannot read.`));
  }
  const row = (label, input, now, flag) => {
    const line = el('label', 'config-row');
    line.appendChild(el('span', 'config-label', label));
    line.appendChild(input);
    line.appendChild(el('span', 'config-now muted', now));
    if (flag) line.appendChild(el('span', 'config-flag muted', `set by ${flag}`));
    box.appendChild(line);
  };
  for (const field of CONFIG_FIELDS) {
    const flagged = flags.includes(field.key);
    const input = el('input');
    input.type = 'number';
    input.min = '1';
    input.step = '1';
    input.dataset.key = field.key;
    input.value = String(file[field.key] ?? reply.effective[field.key]);
    input.readOnly = !!field.readOnly;
    input.disabled = flagged;
    row(field.label, input, `running: ${reply.effective[field.key]}${field.unit ? ` ${field.unit}` : ''}`,
      flagged ? field.flag : null);
  }
  // Each signal tag's cap, blank for "keep every row" (`null` in the file). A tag the file names
  // and the defaults do not is listed too, and one more row adds a tag.
  const caps = { ...reply.effective.signalCaps, ...(file.signalCaps || {}) };
  const flaggedCaps = flags.includes('signalCaps');
  for (const tag of [...Object.keys(caps).sort(), '']) {
    const input = el('input');
    input.type = 'number';
    input.min = '0';
    input.step = '1';
    input.dataset.cap = tag;
    input.placeholder = 'no cap';
    input.disabled = flaggedCaps;
    const value = file.signalCaps && tag in file.signalCaps ? file.signalCaps[tag] : reply.effective.signalCaps[tag];
    input.value = value == null ? '' : String(value);
    if (tag) {
      const now = reply.effective.signalCaps[tag];
      row(`${tag} signals`, input, `running: ${now == null ? 'no cap' : now}`, flaggedCaps ? 'a flag' : null);
    } else {
      const name = el('input', 'config-tag');
      name.type = 'text';
      name.placeholder = 'another tag';
      name.setAttribute('aria-label', 'Signal tag to cap');
      name.disabled = flaggedCaps;
      row('', name, '', null);
      box.lastChild.appendChild(input);
    }
  }
}

/** The keys whose inputs differ from what the file holds now, as a `PUT /api/config` body. */
function configPatch() {
  const file = (configReply && configReply.file) || {};
  const effective = configReply ? configReply.effective : {};
  const patch = {};
  for (const input of document.querySelectorAll('#config-fields input[data-key]')) {
    if (input.disabled || input.readOnly) continue;
    const value = Number(input.value);
    const was = file[input.dataset.key] ?? effective[input.dataset.key];
    if (input.value !== '' && value !== was) patch[input.dataset.key] = value;
  }
  const caps = {};
  for (const input of document.querySelectorAll('#config-fields input[data-cap]')) {
    if (input.disabled) continue;
    const tag = input.dataset.cap || (document.querySelector('#config-fields .config-tag')?.value || '').trim();
    if (!tag) continue;
    caps[tag] = input.value === '' ? null : Number(input.value);
  }
  const before = { ...effective.signalCaps, ...(file.signalCaps || {}) };
  const changed = Object.keys(caps).some((tag) => (before[tag] ?? null) !== caps[tag]);
  if (changed) patch.signalCaps = { ...(file.signalCaps || {}), ...caps };
  return patch;
}

/**
 * Sends what changed, once, through `controlFetch`. The daemon checks every value and names the
 * one it refuses; its words are shown beside the form as they arrive, as every other refusal on
 * this page is.
 */
async function saveConfig() {
  const status = $('config-status');
  status.classList.remove('error');
  const patch = configPatch();
  if (!Object.keys(patch).length) {
    status.textContent = 'nothing changed';
    return;
  }
  status.textContent = 'saving…';
  try {
    const res = await controlFetch('/api/config', { method: 'PUT', json: patch });
    const reply = await res.json();
    if (!res.ok) throw new Error(reply.error || String(res.status));
    configReply = reply;
    renderConfig(reply);
    status.textContent = 'saved — applies when the daemon restarts';
  } catch (e) {
    status.textContent = e.message;
    status.classList.add('error');
  }
}

function wireSettingsPage() {
  $('dup-window').value = state.duplicateWindowMs;
  $('dup-window').addEventListener('change', (e) => applyDuplicateWindow(Number(e.target.value)));
  $('density').addEventListener('change', (e) => setDensity(e.target.value));
  $('endpoint-limit').value = state.endpointLimit;
  $('endpoint-limit').addEventListener('change', (e) => applyEndpointLimit(Number(e.target.value)));
  $('config-form').addEventListener('submit', (e) => {
    e.preventDefault();
    saveConfig();
  });
}
