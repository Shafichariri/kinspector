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
  findingsConfigRender(reply);
}

// --- settings: the findings rules, the one key read live --------------------------------------

/** The five `findings.payment` fields, in the order the fieldset draws them; a list is one entry per line. */
const FINDINGS_PAYMENT_FIELDS = [
  { key: 'idempotencyHeaders', label: 'idempotency headers', list: true },
  { key: 'paymentPaths', label: 'payment paths', list: true },
  { key: 'ignorePaths', label: 'ignore paths', list: true },
  { key: 'duplicateWindowMs', label: 'duplicate window', unit: 'ms' },
  { key: 'resubmitWindowMs', label: 'resubmit window', unit: 'ms' },
];

/**
 * The Findings fieldset, generated from `effective.findings`, which the daemon read live: a row per
 * rule it ships, so a family that adds rules adds rows with no change here, and the payment settings.
 * Inputs hold what the daemon runs on, which for this key is the file as last read.
 */
function findingsConfigRender(reply) {
  const box = $('findings-config');
  const findings = reply.effective && reply.effective.findings;
  box.hidden = !findings;
  box.replaceChildren();
  if (!findings) return;
  box.appendChild(el('legend', null, 'Findings'));
  box.appendChild(el('p', 'settings-note muted',
    'Which rules the Findings page and list_findings run, and what they look for. These apply on the next findings read, with no restart.'));
  if (reply.findingsConfigError) {
    box.appendChild(el('p', 'config-error', `${reply.findingsConfigError} — the last settings that could be read are in use.`));
  }
  const table = el('table', 'page-table findings-rules');
  table.appendChild(el('thead')).appendChild(el('tr')).append(el('th', null, 'rule'), el('th', 'col-secondary', 'family'),
    el('th', null, 'on'), el('th', null, 'severity'));
  const rows = table.appendChild(el('tbody'));
  for (const rule of findings.rules) {
    const tr = rows.appendChild(el('tr'));
    tr.dataset.rule = rule.id;
    const on = el('input');
    on.type = 'checkbox';
    on.checked = rule.enabled;
    on.dataset.ruleEnabled = rule.id;
    on.setAttribute('aria-label', `${rule.title} on`);
    const severity = el('select');
    severity.dataset.ruleSeverity = rule.id;
    severity.setAttribute('aria-label', `${rule.title} severity`);
    for (const level of ['high', 'medium', 'info']) {
      const option = el('option', null, level === rule.defaultSeverity ? `${level} (default)` : level);
      option.value = level;
      severity.appendChild(option);
    }
    severity.value = rule.severity;
    tr.append(el('td', null, rule.title), el('td', 'col-secondary muted', rule.categoryTitle), el('td'), el('td'));
    tr.children[2].appendChild(on);
    tr.children[3].appendChild(severity);
  }
  box.appendChild(table);
  for (const field of FINDINGS_PAYMENT_FIELDS) {
    const value = findings.payment[field.key];
    const input = el(field.list ? 'textarea' : 'input');
    input.dataset.payment = field.key;
    if (field.list) {
      input.rows = Math.min(8, Math.max(3, (value || []).length + 1));   // every default shown whole
      input.spellcheck = false;
      input.value = (value || []).join('\n');
    } else {
      Object.assign(input, { type: 'number', min: '1', step: '1' });
      input.value = String(value);
    }
    const line = el('label', 'config-row');
    line.append(el('span', 'config-label', field.label), input,
      el('span', 'config-now muted', field.list ? 'one per line' : field.unit));
    box.appendChild(line);
  }
  box.appendChild(findingsHostsRow(reply));
}

/**
 * `findings.sensitive.firstPartyHosts`, one host per line. `effective.findings` carries no sensitive
 * settings, so the box holds the file's list, which is what the next findings read applies unless the
 * key could not be read, which the fieldset already says above.
 */
function findingsHostsRow(reply) {
  const sensitive = reply.file && reply.file.findings && reply.file.findings.sensitive;
  const hosts = sensitive && Array.isArray(sensitive.firstPartyHosts) ? sensitive.firstPartyHosts : [];
  const area = el('textarea');
  area.dataset.sensitive = 'firstPartyHosts';
  Object.assign(area, { rows: 4, spellcheck: false, placeholder: 'api.example.invalid\n*.example.invalid', value: hosts.join('\n') });
  const now = !hosts.length ? 'none set: nothing is raised for where data was sent'
    : `${hosts.length} set: data sent to any other host is raised one step`;
  const line = el('label', 'config-row findings-hosts');
  line.append(el('span', 'config-label', 'first-party hosts'), area,
    el('span', 'config-now muted', `one per line, a leading *. for subdomains; ${now}`));
  return line;
}

/** Two JSON values equal whatever their keys' order, so a save that changed nothing sends nothing. */
function sameJson(a, b) {
  const canon = (v) => (Array.isArray(v) ? v.map(canon)
    : v && typeof v === 'object' ? Object.keys(v).sort().reduce((o, k) => ({ ...o, [k]: canon(v[k]) }), {}) : v);
  return JSON.stringify(canon(a)) === JSON.stringify(canon(b));
}

/**
 * The whole `findings` key to send, or `undefined` when the inputs say what the file says: a
 * top-level key is replaced whole, so it starts from the file's and carries every sub-object it does
 * not draw as the file holds it, `sensitive` beside its `firstPartyHosts`. `rules` keeps only a rule whose inputs
 * differ from its defaults, and drops one put back to them; `payment` gains only a field whose input
 * differs from the file's value, or the daemon's where the file has none. `null` removes the key.
 */
function findingsConfigPatch() {
  const effective = configReply && configReply.effective && configReply.effective.findings;
  if (!effective) return undefined;
  const was = (configReply.file && configReply.file.findings) || null;
  const next = { ...(was || {}) };
  const rules = { ...(next.rules || {}) };
  for (const rule of effective.rules) {
    const on = document.querySelector(`#findings-config [data-rule-enabled="${rule.id}"]`);
    const severity = document.querySelector(`#findings-config [data-rule-severity="${rule.id}"]`);
    if (!on || !severity) continue;
    const entry = {};
    // Against the rule's own default: email and phone ship off, so turning one on is the entry.
    if (on.checked !== (rule.defaultEnabled !== false)) entry.enabled = on.checked;
    if (severity.value !== rule.defaultSeverity) entry.severity = severity.value;
    if (Object.keys(entry).length) rules[rule.id] = entry;
    else delete rules[rule.id];
  }
  if (Object.keys(rules).length) next.rules = rules;
  else delete next.rules;
  const payment = { ...(next.payment || {}) };
  const lines = (text) => text.split('\n').map((line) => line.trim()).filter(Boolean);
  for (const field of FINDINGS_PAYMENT_FIELDS) {
    const input = document.querySelector(`#findings-config [data-payment="${field.key}"]`);
    if (!input || (!field.list && input.value === '')) continue;
    const before = was && was.payment && field.key in was.payment ? was.payment[field.key] : effective.payment[field.key];
    const value = field.list ? lines(input.value) : Number(input.value);
    const changed = field.list ? lines((before || []).join('\n')).join('\n') !== value.join('\n') : value !== before;
    if (changed) payment[field.key] = value;
  }
  if (Object.keys(payment).length) next.payment = payment;
  else delete next.payment;
  // Only `firstPartyHosts` is drawn, so it alone is replaced, and only when the box differs.
  const hostsBox = document.querySelector('#findings-config [data-sensitive="firstPartyHosts"]');
  const held = next.sensitive && typeof next.sensitive === 'object' && !Array.isArray(next.sensitive) ? next.sensitive : null;
  const hostsWere = held && Array.isArray(held.firstPartyHosts) ? held.firstPartyHosts : [];
  if (hostsBox && lines(hostsBox.value).join('\n') !== lines(hostsWere.join('\n')).join('\n')) {
    const sensitive = { ...(held || {}), firstPartyHosts: lines(hostsBox.value) };
    if (!sensitive.firstPartyHosts.length) delete sensitive.firstPartyHosts;
    if (Object.keys(sensitive).length) next.sensitive = sensitive;
    else delete next.sensitive;
  }
  const out = Object.keys(next).length ? next : null;
  return sameJson(out, was) ? undefined : out;
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
  const findings = findingsConfigPatch();
  if (findings !== undefined) patch.findings = findings;
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
    // Only the findings key applies at once (the daemon reads it on every findings read).
    const others = Object.keys(patch).some((key) => key !== 'findings');
    status.textContent = !('findings' in patch) ? 'saved — applies when the daemon restarts'
      : others ? 'saved — findings use it now; the rest applies when the daemon restarts' : 'saved — findings use it now';
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
