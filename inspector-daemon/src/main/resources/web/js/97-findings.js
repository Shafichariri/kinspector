/* findings: the Findings page, the daemon's findings for one session drawn as it answers them */
'use strict';

/*
 * The page computes nothing. Rules, counts, order, severity, messages, masked evidence and coverage
 * all come from `GET …/findings`, so there is no mirror block and no case file: the daemon's
 * `service/FindingsEngine.kt` and its sources are the only copy of each rule. The page asks once for
 * every finding, with no category, as an export embeds them.
 */

/** Every finding one reply may carry; the daemon's own most. `findingCount` says how many there were. */
const FINDINGS_LIMIT = 5000;
/**
 * How long a live call waits before the findings are asked for again. The calls that arrive
 * meanwhile, or while that request is out, ride on it or on the one armed when it lands, as the
 * Stats page's do (`STATS_REFRESH_MS`): a burst of 100 inside a second costs at most two.
 */
const FINDINGS_REFRESH_MS = 1000;
/** A read quicker than this draws no "checking" line, so a fast answer does not flash one. */
const FINDINGS_SLOW_MS = 300;

/**
 * The page's own state. `sent`/`written` are the ticket pair: of a load and a refresh, the one sent
 * last is kept whichever lands last. `owed`, `timer` and `inFlight` are the refresh rule. `slow` is
 * set once a read has taken `FINDINGS_SLOW_MS`; `marked` is the `?id=` already scrolled to, so a
 * live redraw does not scroll the reader back to it.
 */
const findingsState = {
  sessionId: null, report: null, error: null,
  sent: 0, written: 0, owed: false, timer: null, inFlight: false, slow: false, marked: null,
};

const findingsShown = () => currentPage() === 'findings';

/**
 * Whether the findings are worth asking for again: the page shown, a session selected, and not an
 * export, whose report is fixed at the moment it was written.
 */
const findingsWanted = () => !ARCHIVE && findingsShown() && Boolean(state.sessionId);

/**
 * Whether a live row may ask again: live tail on, and wanted. One predicate for owing and for
 * sending, so a row owed before a pause or a page change sends nothing once the timer fires.
 */
const findingsMayRefresh = () => state.liveTail && findingsWanted();

/**
 * `PAGES`' `enter` for Findings: read every time the page is opened, so a rule changed on Settings
 * shows at once. The daemon reads the `findings` key on every findings read, so nothing else is owed.
 */
function findingsEnter() {
  findingsState.marked = null;
  findingsLoad();
}

/** The findings for the selected session, read now. */
function findingsLoad() {
  if (!state.sessionsLoaded) {
    findingsRender();   // "checking", until `init` has a session and asks again
    return Promise.resolve();
  }
  return findingsRead(false);
}

/** Reads again when shown; resuming live tail, the socket reopening and `init` call it. */
function findingsReload() {
  if (findingsShown()) findingsLoad();
}

/** The session may have changed: a switch, a delete, or the daemon coming back. */
function findingsSessionChanged() {
  if (findingsState.sessionId !== state.sessionId) {
    findingsState.report = null;
    findingsState.error = null;
    findingsState.marked = null;
  }
  findingsReload();
}

/**
 * A live call for the session shown arrived (`ws.onmessage`, after its `liveTail` check). Arms one
 * refresh unless one is armed or out: never two in flight, and the calls that come meanwhile are
 * covered by the one armed when it lands. Markers owe nothing: no rule reads them.
 */
function oweFindings() {
  if (!findingsMayRefresh()) return;
  findingsState.owed = true;
  if (findingsState.timer === null && !findingsState.inFlight) findingsState.timer = setTimeout(findingsRefresh, FINDINGS_REFRESH_MS);
}

/** The refresh the owed calls armed; re-arms in its `finally` if more were owed while it was out. */
async function findingsRefresh() {
  findingsState.timer = null;
  if (!findingsState.owed) return;
  findingsState.owed = false;
  if (!findingsMayRefresh()) return;
  findingsState.inFlight = true;
  try {
    await findingsRead(true);
  } finally {
    findingsState.inFlight = false;
    if (findingsState.owed) findingsState.timer = setTimeout(findingsRefresh, FINDINGS_REFRESH_MS);
  }
}

/**
 * Sends `GET …/findings` and draws the answer, if it still stands: a reply for a session no longer
 * selected is dropped, and so is a refresh's once live tail changed under it.
 */
async function findingsRead(live) {
  const ticket = ++findingsState.sent;
  const { sessionId, liveTail } = state;
  if (!sessionId) {
    findingsState.written = ticket;
    Object.assign(findingsState, { report: null, error: null, sessionId: null });
    findingsRender();
    return;
  }
  const stands = () => state.sessionId === sessionId && (!live || state.liveTail === liveTail);
  const slow = setTimeout(() => {
    if (ticket !== findingsState.sent) return;
    findingsState.slow = true;
    if (findingsShown()) findingsRender();
  }, FINDINGS_SLOW_MS);
  let report = null;
  let error = null;
  try {
    report = await api(`/api/sessions/${encodeURIComponent(sessionId)}/findings?limit=${FINDINGS_LIMIT}`);
  } catch (e) {
    error = e.message;
  }
  clearTimeout(slow);
  if (!stands() || ticket < findingsState.written) return;
  Object.assign(findingsState, { written: ticket, report, error, sessionId, slow: false });
  if (findingsShown()) findingsRender();
}

// --- drawing --------------------------------------------------------------

const findingsPlural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
const findingsCalls = (n) => `${findingsPlural(n, 'call', 'calls')} in this session`;

/** `⚑ high`: a severity in words in the text colour, since every hue already means a status or a method. */
function findingsBadge(severity) {
  const badge = el('span', `finding-badge sev-${severity}`, `⚑ ${severity}`);
  badge.title = `severity: ${severity}`;
  return badge;
}

/** Why there is no report to draw, or null when there is one. */
function findingsEmptyWhy() {
  const { report, error, slow } = findingsState;
  const checking = slow ? 'Checking this session\'s calls…' : '';
  if (!state.sessionsLoaded) return checking;
  if (!state.sessionId) return 'No session selected.';
  if (error) return ARCHIVE && !ARCHIVE.findings ? 'This export was made before findings existed.' : error;
  if (!report || findingsState.sessionId !== state.sessionId) return checking;
  if (report.total === 0) return 'No calls in this session, so nothing to check.';
  return null;
}

/** Draws whatever `findingsState` holds: the count, then each category's rules and findings, or why there are none. */
function findingsRender() {
  const count = $('findings-count');
  const body = $('findings-body');
  count.replaceChildren();
  body.replaceChildren();
  findingsFooter();
  const why = findingsEmptyWhy();
  if (why !== null) {
    count.textContent = why;
    return;
  }
  const { report } = findingsState;
  count.textContent = report.findingCount === 0
    ? `No findings in ${findingsCalls(report.total)}.`
    : `${findingsPlural(report.findingCount, 'finding', 'findings')} in ${findingsCalls(report.total)} · ${report.high} high · ${report.medium} medium · ${report.info} info`;
  if (report.configError) {
    const line = el('span', 'findings-config-error', ` config.json's findings key could not be read: ${report.configError}. Using the last settings that could.`);
    if (!ARCHIVE) line.append(' ', findingsSettingsLink('Settings'));
    count.append(el('br'), line);
  }
  if (report.findingCount > report.findings.length) {
    body.appendChild(el('p', 'settings-note muted', `first ${report.findings.length} of ${report.findingCount} findings, in severity order`));
  }
  const target = findingsTarget();
  if (target && !report.findings.some((f) => f.id === target)) {
    body.appendChild(el('p', 'settings-note muted findings-missing', 'that finding is not in this session\'s findings now'));
  }
  for (const category of [...new Set(report.rules.map((r) => r.category))]) body.appendChild(findingsCategory(report, category));
  findingsMark();
}

/** One category: its title, the daemon's coverage line as given, then every rule it checked. */
function findingsCategory(report, category) {
  const rules = report.rules.filter((r) => r.category === category);
  const section = el('section', 'findings-category');
  section.dataset.category = category;
  section.appendChild(el('h2', 'findings-category-title', rules[0].categoryTitle));
  const line = report.coverage && report.coverage[category] && report.coverage[category].line;
  if (line) section.appendChild(el('p', 'settings-note muted findings-coverage', line));
  for (const rule of rules) section.appendChild(findingsRule(rule, report.findings.filter((f) => f.ruleId === rule.id)));
  return section;
}

/**
 * One rule: its badge, title and count, then each of its findings. A rule with none says it was
 * checked, or that it is off, so an empty page never reads as safe on a rule that never ran.
 */
function findingsRule(rule, findings) {
  const section = el('section', 'findings-rule');
  section.dataset.rule = rule.id;
  const head = el('div', 'findings-rule-head');
  head.append(findingsBadge(rule.severity), el('span', 'findings-rule-title', rule.title));
  if (!rule.enabled) head.appendChild(el('span', 'findings-rule-state muted', '— disabled in config.json'));
  else if (rule.count === 0) head.appendChild(el('span', 'findings-rule-state muted', '— checked, none found'));
  else head.appendChild(el('span', 'findings-rule-count', `(${rule.count})`));
  section.appendChild(head);
  section.appendChild(el('p', 'settings-note muted findings-rule-summary', rule.note ? `${rule.summary} ${rule.note}` : rule.summary));
  for (const finding of findings) section.appendChild(findingsArticle(finding));
  return section;
}

/** One finding: the message, its route, a link per call, and the evidence lines, masked by the daemon. */
function findingsArticle(finding) {
  const article = el('article', 'finding');
  article.dataset.finding = finding.id;
  article.appendChild(el('p', 'finding-message', finding.message));
  if (finding.route) article.appendChild(el('p', 'finding-route mono', finding.route));
  const calls = el('ul', 'finding-txns');
  for (const id of finding.txnIds) calls.appendChild(el('li')).appendChild(findingsTxnLink(id));
  if (finding.txnCount > finding.txnIds.length) calls.appendChild(el('li', 'muted', `and ${finding.txnCount - finding.txnIds.length} more calls`));
  article.appendChild(calls);
  const evidence = el('ul', 'finding-evidence');
  for (const line of finding.evidence) {
    const li = el('li');
    if (line.note) li.appendChild(el('span', null, line.note));
    const where = [line.location, line.path].filter(Boolean).join(' ');
    if (where) li.appendChild(el('span', 'mono finding-where', where));
    if (line.masked) li.appendChild(el('span', 'mono finding-masked', line.masked));
    evidence.appendChild(li);
  }
  article.appendChild(evidence);
  return article;
}

/**
 * A call, opened through `#/txn/{id}`: `navigate` pushes, so Back returns here. The label is the
 * row as the list draws it when the page holds it, and the id when it does not.
 */
function findingsTxnLink(id) {
  const link = el('a', 'finding-txn');
  link.href = routeHash({ page: 'txn', txn: id, session: state.sessionId });
  link.dataset.txn = id;
  link.title = `call ${id}`;   // two attempts of one route draw the same label; the id tells them apart
  const held = (rows) => rows.find((t) => t.id === id);
  const txn = held(state.allTransactions) || held(state.transactions);
  if (txn) {
    link.append(el('span', `method ${methodClass(txn.method)}`, txn.method), el('span', 'mono', txn.path),
      el('span', 'muted', txn.status === null || txn.status === undefined ? 'failed' : String(txn.status)));
  } else {
    link.appendChild(el('span', 'mono', id));
  }
  link.addEventListener('click', (e) => {
    if (e.button > 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    e.preventDefault();
    navigate({ page: 'txn', txn: id });
  });
  return link;
}

function findingsSettingsLink(text) {
  const link = el('a', null, text);
  link.href = routeHash({ page: 'settings' });
  return link;
}

/** What the page is and where its rules live; in an export, when it was computed. */
function findingsFooter() {
  const note = $('findings-note');
  const report = findingsState.report;
  const version = report ? ` Rule version ${report.ruleVersion}.` : '';
  note.replaceChildren();
  if (ARCHIVE) {
    note.textContent = `Computed when this file was exported.${version}`;
    return;
  }
  note.append('Computed by the daemon from every call in this session each time it is asked; nothing is '
    + 'written to the archive. Rules and their settings are on the ', findingsSettingsLink('Settings page'),
  ` and in config.json's findings key, and apply on the next read.${version}`);
}

/** The `?id=` the address names on this page, or null. */
const findingsTarget = () => (findingsShown() ? parseRoute(location.hash).id : null);

/**
 * Marks the finding `?id=` names and scrolls it into view, once per address: a live redraw keeps
 * the mark and leaves the scroll where the reader put it.
 */
function findingsMark() {
  const target = findingsTarget();
  const articles = [...document.querySelectorAll('#findings-body article.finding')];
  for (const article of articles) article.classList.toggle('finding-target', article.dataset.finding === target);
  const article = articles.find((a) => a.dataset.finding === target);
  if (!target) findingsState.marked = null;
  if (!target || !article || findingsState.marked === target) return;
  findingsState.marked = target;
  article.scrollIntoView?.({ block: 'start' });
}
