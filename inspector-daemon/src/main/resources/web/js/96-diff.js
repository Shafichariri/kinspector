/* diff: the Diff page, two sessions compared route by route as the daemon answers it */
'use strict';

/*
 * The page decides nothing. The kinds of change, their order, the loud mark, every verdict, `n<5`
 * and the reason a body shape could not be compared all come from `GET /api/diff`, so there is no
 * mirror block and no case file: the daemon's `service/SessionDiff.kt` is the only copy of each rule.
 * The page words the kinds and hides the rows the daemon counts unchanged, which is display: a route
 * with no change is still drawn when the daemon flags it `unsure`, since a body capture did not keep
 * whole may hide a removed key, and the daemon, not `changes`, says which routes those are.
 *
 * It never reads again on a live message. A re-read costs body reads, and a comparison that reorders
 * under the reader is hard to read; a B still recording says so and offers `refresh`.
 */

/**
 * The page's own state. `a` and `b` are the address's, null where the daemon chooses: B the latest
 * session, A the previous run of B's app. `filter`, `sinceA` and `sinceB` are page state, kept out of
 * the address as Stats keeps its filter. `sent`/`written` are the ticket pair: of two reads, the one
 * sent last is drawn whatever order they land in. `open` holds the routes whose detail is open.
 */
const diffState = {
  a: null, b: null, filter: '', sinceA: '', sinceB: '', report: null, error: null,
  sent: 0, written: 0, typing: null, open: new Set(), unchanged: false,
};

const diffShown = () => currentPage() === 'diff';

/** The words for the daemon's kinds of change; a kind this build does not know is printed as sent. */
const DIFF_KINDS = {
  added: 'route added', removed: 'route removed', calls: 'calls', statusMix: 'status', slower: 'slower',
  faster: 'faster', requestShape: 'request shape', responseShape: 'response shape',
  requestHeaders: 'request headers', responseHeaders: 'response headers', requestSize: 'request size',
  responseSize: 'response size',
};

/**
 * A row the daemon counts in `counts.unchanged`: no change and no `unsure` flag. Read off the reply,
 * never decided here; `changes` empty alone hid a route whose only news was a key capture could not
 * be sure of, as if it were the same on both sides.
 */
const diffUnchanged = (r) => r.changes.length === 0 && !r.unsure;

/** The table's columns after the route, with what each counts. */
const DIFF_COLUMNS = [
  ['change', 'what the daemon found changed on this route, in its order'],
  ['calls A → B', 'distinct calls on each side: the attempts of one call share a callId'],
  ['status A → B', 'attempts per status code on each side; transport is no response at all'],
  ['p50 A → B', 'median latency on each side, and the daemon\'s verdict on it'],
  ['response shape', 'JSON key paths removed, added and changed in type, from the bodies the daemon could read whole'],
  ['headers', 'header names added and removed, request and response together; values are never compared'],
];

/**
 * The address names a page with `a` and `b`; `applyRoute` calls this whenever the Diff page is shown,
 * since a picker changes them on the page shown, which enters nothing. A marker belongs to its
 * session, so a new pair drops both cuts; it reads when entering or when the pair changed.
 */
function diffRoute(route, entering) {
  const changed = route.a !== diffState.a || route.b !== diffState.b;
  if (changed) {
    Object.assign(diffState, { a: route.a, b: route.b, sinceA: '', sinceB: '', report: null, error: null });
    diffState.open.clear();
  }
  if (entering || changed) diffLoad();
}

/** The comparison for the pair, filter and cuts in force, read now; before `init` has the sessions, "loading". */
function diffLoad() {
  if (!state.sessionsLoaded) {
    diffRender();
    return Promise.resolve();
  }
  return diffRead();
}

/**
 * The session list changed: loaded, a session deleted, the archive cleared. The pickers are drawn from
 * it, and a shown page reads again, so a deleted session reads as the daemon's 404.
 */
function diffSessionsChanged() {
  if (diffShown()) diffLoad();
  else diffPaintPickers();
}

/** The query for the state in force; what the daemon chooses is left out, `unchanged` at its default (true). */
function diffQuery() {
  const query = new URLSearchParams();
  for (const key of ['a', 'b', 'filter', 'sinceA', 'sinceB']) if (diffState[key]) query.set(key, diffState[key]);
  return query;
}

/**
 * Sends `GET /api/diff` and draws the answer unless a read sent after it has already been drawn. A
 * cold diff reads both sessions' bodies and can take seconds, so "comparing…" stays up meanwhile.
 */
async function diffRead() {
  const ticket = ++diffState.sent;
  diffRender();
  let report = null;
  let error = null;
  try {
    report = await api(`/api/diff?${diffQuery()}`);
  } catch (e) {
    error = e.message;
  }
  if (ticket < diffState.written) return;
  diffState.written = ticket;
  diffState.report = report;
  diffState.error = error;
  diffRender();
}

// --- drawing --------------------------------------------------------------

const diffPlural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
const diffKey = (r) => `${r.method} ${r.scheme}://${r.host}:${r.port ?? ''}${r.route}`;
/** `200×2 500×1`, from one side's outcomes per code, or `—` for a side the route is not on. */
const diffStatus = (mix) => (mix ? Object.entries(mix).map(([code, n]) => `${code}×${n}`).join(' ') || '0' : '—');
/** How many entries a list held, the ones the daemon cut included. */
const diffCount = (diff, list) => diff[list].length + ((diff.more || {})[list] || 0);

/** A session as the pickers name it, with its id as the title; the id alone when the list lacks it. */
function diffSessionSpan(cls, info) {
  const meta = state.sessions.find((m) => m.sessionId === info.sessionId);
  const span = el('span', cls, meta ? sessionLabel(meta) : info.sessionId);
  span.dataset.session = info.sessionId;
  span.title = info.sessionId;
  return span;
}

/** The four pickers from the session list and the reply's markers; a value the list lacks is kept. */
function diffPaintPickers() {
  const fill = (id, first, values, chosen) => {
    const select = $(id);
    if (!select) return;
    const options = [['', first], ...values];
    if (chosen && !options.some(([value]) => value === chosen)) options.push([chosen, chosen]);
    select.replaceChildren();
    for (const [value, text] of options) {
      const option = el('option', null, text);
      option.value = value;
      option.selected = value === (chosen || '');
      select.appendChild(option);
    }
  };
  const sessions = state.sessions.map((m) => [m.sessionId, sessionLabel(m)]);
  fill('diff-a', 'previous run of B', sessions, diffState.a);
  fill('diff-b', 'latest session', sessions, diffState.b);
  const { report } = diffState;
  for (const side of ['a', 'b']) {
    const markers = report ? report[side].markers.map((label) => [label, `since "${label}"`]) : [];
    fill(`diff-since-${side}`, 'whole session', markers, diffState[side === 'a' ? 'sinceA' : 'sinceB']);
  }
}

/** Draws whatever `diffState` holds: the pickers, the daemon's refusal, the summary, the table or why it is empty. */
function diffRender() {
  if (!$('diff-rows')) return;
  diffPaintPickers();
  const { report, error } = diffState;
  const reading = diffState.sent !== diffState.written;
  $('diff-status').textContent = reading ? 'comparing…' : '';
  $('diff-error').hidden = !error;
  $('diff-error').textContent = error || '';
  const md = $('diff-md');
  md.hidden = !report;
  md.href = `/api/diff.md?${diffQuery()}`;

  const summary = $('diff-summary');
  summary.replaceChildren();
  const note = $('diff-note');
  const notes = [];
  if (report) {
    const counts = report.counts;
    summary.append('B: ', diffSessionSpan('diff-side-b', report.b), ' against A: ', diffSessionSpan('diff-side-a', report.a),
      report.aChosen === 'previousRun' ? ' (the previous run of this app)' : '',
      ` · ${diffPlural(report.routeCount, 'route', 'routes')}${report.filter ? ' match the filter' : ''}: ${counts.added} added, `
        + `${counts.removed} removed, ${counts.changed} changed, ${counts.unsure} with unknown keys, ${counts.unchanged} unchanged`);
    if (report.sameApp === false) notes.push('A and B are different apps.');
    if (!report.b.endedAt || (state.recording || []).includes(report.b.sessionId)) {
      notes.push(`B is still recording; this compares the ${diffPlural(report.b.total, 'call', 'calls')} it held — refresh to compare again.`);
    }
  }
  note.hidden = !notes.length;
  note.textContent = notes.join(' ');

  const empty = $('diff-empty');
  empty.replaceChildren();
  const why = diffEmptyWhy(reading);
  empty.hidden = !why;
  if (why) {
    empty.appendChild(el('span', null, why));
    if (report && report.filter) {
      const clear = el('button', 'btn btn-sm', 'clear filter');
      clear.type = 'button';
      clear.addEventListener('click', () => diffSetFilter(''));
      empty.appendChild(clear);
    }
  }
  diffRenderTable(report);
}

/** Why there is no table: loading, no sessions at all, or no route on either side. A refusal is drawn above. */
function diffEmptyWhy(reading) {
  const { report, error } = diffState;
  if (!state.sessionsLoaded) return 'loading…';
  if (!state.sessions.length) return 'No sessions yet, and a diff needs two. A session is recorded when an app with inspector-stream connects to this daemon.';
  if (error || (!report && reading)) return null;
  if (!report) return 'loading…';
  if (report.routeCount > 0) return null;
  return report.filter ? `No routes match the filter \`${report.filter}\` on either side.` : 'Neither session holds a call.';
}

/** The table, in the reply's order; rows the daemon counts unchanged are drawn only while the toggle shows them. */
function diffRenderTable(report) {
  const rows = $('diff-rows');
  rows.replaceChildren();
  const head = $('diff-head');
  head.replaceChildren(el('th', 'diff-route', 'route'));
  for (const [text, title] of DIFF_COLUMNS) {
    const th = el('th', null, text);
    th.title = title;
    head.appendChild(th);
  }
  const routes = report ? report.routes : [];
  $('diff-scroll').hidden = !routes.length;
  const origins = new Set(routes.map(statsOrigin)).size;
  const unchanged = routes.filter(diffUnchanged).length;
  for (const r of routes) {
    if (diffUnchanged(r) && !diffState.unchanged) continue;
    const tr = diffRow(r, origins);
    rows.appendChild(tr);
    if (diffState.open.has(diffKey(r))) rows.appendChild(diffDetail(r));
  }
  const toggle = $('diff-unchanged-toggle');
  toggle.hidden = !unchanged;
  toggle.textContent = `${diffState.unchanged ? 'hide' : 'show'} ${diffPlural(unchanged, 'unchanged route', 'unchanged routes')}`;
  toggle.setAttribute('aria-pressed', String(diffState.unchanged));
  const more = $('diff-more');
  more.hidden = !report || report.routeCount <= routes.length;
  more.textContent = report ? `first ${routes.length} of ${report.routeCount} routes in the daemon's order` : '';
}

/** One route's row: its route as the button that opens its detail, then each column's cell. */
function diffRow(r, origins) {
  const tr = el('tr', r.loud ? 'diff-row diff-loud' : 'diff-row');
  tr.dataset.route = r.route;
  tr.dataset.method = r.method;
  const th = el('th', 'diff-route');
  th.scope = 'row';
  const open = el('button', 'diff-open');
  open.type = 'button';
  open.title = 'What changed on this route';
  open.setAttribute('aria-expanded', String(diffState.open.has(diffKey(r))));
  open.appendChild(el('span', `method ${methodClass(r.method)}`, r.method));
  if (origins > 1) open.appendChild(statsBreakable('diff-host muted', statsOrigin(r), '.:/'));
  open.appendChild(statsBreakable('diff-path', r.route, '/'));
  open.addEventListener('click', () => diffToggle(r, tr, open));
  th.appendChild(open);
  tr.appendChild(th);

  const change = el('td', 'diff-change');
  if (r.loud) {
    const loud = el('span', 'diff-tag diff-tag-loud', 'loud');
    loud.title = 'a POST, PUT, PATCH or DELETE added, removed or called a different number of times';
    change.appendChild(loud);
  }
  for (const kind of r.changes) {
    const tag = el('span', 'diff-tag', DIFF_KINDS[kind] || kind);
    tag.dataset.kind = kind;
    change.appendChild(tag);
  }
  if (r.unsure) {
    const unsure = el('span', 'diff-tag', 'unknown keys');
    unsure.dataset.kind = 'unsure';
    unsure.title = 'capture did not keep every body on a side, so a key may have been removed or added; open the row for which';
    change.appendChild(unsure);
  } else if (!r.changes.length) change.appendChild(el('span', 'muted', 'no change'));
  tr.appendChild(change);
  tr.appendChild(el('td', 'num diff-calls', `${r.a ? r.a.calls : '—'} → ${r.b ? r.b.calls : '—'}`));
  tr.appendChild(el('td', 'diff-status', `${diffStatus(r.status.a)} → ${diffStatus(r.status.b)}`));
  tr.appendChild(diffLatencyCell(r));
  tr.appendChild(diffShapeCell(r.responseShape));
  const headers = [r.requestHeaders, r.responseHeaders].filter(Boolean);
  const sum = (list) => headers.reduce((n, h) => n + diffCount(h, list), 0);
  tr.appendChild(el('td', 'num diff-headers', headers.length ? `+${sum('added')} −${sum('removed')}` : '—'));
  return tr;
}

/** p50 on each side, then the daemon's verdict: its numbers when it judged, its `reason` verbatim when not. */
function diffLatencyCell(r) {
  const td = el('td', 'diff-latency');
  const latency = r.latency;
  const p50A = latency ? latency.p50A : r.a && r.a.p50Ms;
  const p50B = latency ? latency.p50B : r.b && r.b.p50Ms;
  td.appendChild(el('span', 'num', `${fmtMs(p50A)} → ${fmtMs(p50B)}`));
  if (!latency) return td;
  const signed = (n) => (n > 0 ? `+${n}` : n < 0 ? `−${-n}` : '0');
  const judged = latency.verdict === 'slower' || latency.verdict === 'faster';
  const words = judged
    ? `${latency.verdict} ${signed(latency.deltaMs)} ms${latency.deltaPct === undefined ? '' : ` (${signed(latency.deltaPct)}%)`}`
    : latency.verdict === 'notJudged' ? latency.reason || 'not judged' : latency.verdict;
  const verdict = el('span', `diff-verdict diff-v-${latency.verdict}`, words);
  if (latency.verdict === 'notJudged') verdict.title = 'Too few responses on a side for the daemon to judge latency';
  td.appendChild(verdict);
  return td;
}

/**
 * A direction's shape in one cell: `−removed +added ~type changed`, with how many keys are unknown;
 * when the daemon compared nothing, the two body kinds when both are known, else `unknown` with the
 * daemon's `why` as its title. Never "removed" of a body the daemon could not read.
 */
function diffShapeCell(shape) {
  const td = el('td', 'num diff-shape');
  if (!shape) {
    td.textContent = '—';
    return td;
  }
  if (shape.compared) {
    const unsure = diffCount(shape, 'unsure');
    td.textContent = `−${diffCount(shape, 'removed')} +${diffCount(shape, 'added')} ~${diffCount(shape, 'typeChanged')}`
      + (unsure ? `, ${unsure} unknown` : '');
  } else if (shape.a.kind !== 'unknown' && shape.b.kind !== 'unknown') {
    td.textContent = shape.a.kind === shape.b.kind ? shape.a.kind : `${shape.a.kind} → ${shape.b.kind}`;
  } else {
    td.textContent = 'unknown';
    td.title = shape.why || '';
  }
  return td;
}

/** Opens or closes a route's detail beneath its row, in place, so focus stays on its button. */
function diffToggle(r, tr, button) {
  const key = diffKey(r);
  const opening = !diffState.open.has(key);
  if (opening) diffState.open.add(key);
  else diffState.open.delete(key);
  button.setAttribute('aria-expanded', String(opening));
  const next = tr.nextSibling;
  if (next && next.classList && next.classList.contains('diff-detail')) next.remove();
  if (opening) tr.after(diffDetail(r));
}

/**
 * A route's detail: status per code, each side's latency percentiles and sizes, header names, and per
 * direction both body tallies and every list the daemon sent. Keys the daemon could not decide are
 * under "unknown", never under "removed", and redacted keys under their own heading.
 */
function diffDetail(r) {
  const tr = el('tr', 'diff-detail');
  const td = el('td');
  td.colSpan = DIFF_COLUMNS.length + 1;
  const body = el('div', 'diff-detail-body');
  const line = (label, text) => {
    const p = el('p', 'diff-line');
    p.append(el('span', 'diff-line-label', label), text);
    return p;
  };
  const sides = (show) => `A: ${r.a ? show(r.a, 'a') : '—'} · B: ${r.b ? show(r.b, 'b') : '—'}`;
  body.appendChild(line('status', sides((row, side) => diffStatus(r.status[side]))));
  body.appendChild(line('latency', sides((row) => ['p50', 'p90', 'p95', 'p99']
    .map((p) => `${p} ${row[`${p}Ms`] === undefined && row.tailWithheld && (p === 'p95' || p === 'p99') ? row.tailWithheld : fmtMs(row[`${p}Ms`])}`).join(', '))));
  for (const [label, size] of [['request size', r.requestSize], ['response size', r.responseSize]]) {
    if (!size) continue;
    const verdict = size.verdict === 'notJudged' ? size.reason || 'not judged' : size.verdict;
    body.appendChild(line(label, `mean ${statsBytes(size.meanA)} → ${statsBytes(size.meanB)}: ${verdict}`));
  }
  for (const [label, headers] of [['request headers', r.requestHeaders], ['response headers', r.responseHeaders]]) {
    if (!headers || diffCount(headers, 'added') + diffCount(headers, 'removed') === 0) continue;
    const words = ['added', 'removed'].filter((list) => diffCount(headers, list))
      .map((list) => `${list} ${headers[list].join(', ')}${(headers.more || {})[list] ? ` and ${headers.more[list]} more` : ''}`);
    body.appendChild(line(label, words.join('; ')));
  }
  for (const [label, shape] of [['request body', r.requestShape], ['response body', r.responseShape]]) {
    if (shape) body.appendChild(diffDirection(label, shape));
  }
  // Each side's calls on Traffic, through the Stats page's caveat; only on a side the route is on.
  const report = diffState.report;
  const opens = el('div', 'page-actions diff-calls');
  for (const side of ['a', 'b']) {
    if (!r[side] || !report) continue;
    const word = side.toUpperCase();
    const button = el('button', 'btn btn-sm diff-calls-open', `calls in ${word}`);
    button.type = 'button';
    button.dataset.side = side;
    button.title = `This route's calls in ${word}, ${report[side].sessionId}, on the Traffic page`;
    const back = { page: 'diff', a: diffState.a, b: diffState.b };
    button.addEventListener('click', () => statsOpenRoute(r[side], { from: 'diff', back, sessionId: report[side].sessionId }));
    opens.appendChild(button);
  }
  if (opens.children.length) body.appendChild(opens);
  td.appendChild(body);
  tr.appendChild(td);
  return tr;
}

/** One direction of a route's detail: both tallies, the daemon's `why` when it compared nothing, then each list. */
function diffDirection(label, shape) {
  const section = el('section', 'diff-dir');
  section.appendChild(el('h3', 'diff-dir-title', label));
  const tally = (t) => [t.kind, ...['complete', 'empty', 'truncated', 'omitted', 'missing', 'notJson', 'unparsed', 'unread']
    .filter((name) => t[name]).map((name) => `${t[name]} ${name}`)].join(', ');
  section.appendChild(el('p', 'diff-tally muted', `A: ${tally(shape.a)} · B: ${tally(shape.b)}`));
  if (!shape.compared && shape.why) section.appendChild(el('p', 'diff-why', shape.why));
  const lists = [
    ['added', 'added', (p) => `${p.path} ${p.types.join('|')}, in ${p.in} of ${p.of}`],
    ['removed', 'removed', (p) => `${p.path} ${p.types.join('|')}, in ${p.in} of ${p.of}`],
    ['typeChanged', 'type changed', (p) => `${p.path} ${p.typesA.join('|')} → ${p.typesB.join('|')}`],
    ['unsure', 'unknown — capture did not keep every body',
      (p) => `${p.path} would be ${p.would === 'typeChanged' ? 'a type change' : p.would}; in ${p.inA} of ${p.ofA} of A, ${p.inB} of ${p.ofB} of B`],
    ['notCompared', 'redacted — not compared', (path) => path],
  ];
  for (const [list, heading, words] of lists) {
    if (!diffCount(shape, list)) continue;
    section.appendChild(el('h4', 'diff-list-title', heading));
    const ul = el('ul', `diff-list diff-list-${list}`);
    for (const entry of shape[list]) ul.appendChild(el('li', 'mono', words(entry)));
    const cut = (shape.more || {})[list];
    if (cut) ul.appendChild(el('li', 'muted', `and ${cut} more`));
    section.appendChild(ul);
  }
  return section;
}

// --- controls ---------------------------------------------------------------

function diffSetFilter(text) {
  $('diff-filter').value = text;
  diffState.filter = text.trim() ? text : '';
  diffLoad();
}

/**
 * Choosing a session replaces the address rather than pushing it: Back walks places, not data. Swap
 * names the two sessions the reply resolved, so `#/diff` swapped is the pair it showed, reversed.
 */
function diffChoose(a, b) {
  navigate({ page: 'diff', a: a || null, b: b || null }, { replace: true });
}

/**
 * Builds the page's controls under its heading, from `99-init.js`. Built here rather than written in
 * `index.html`, which holds every page's markup and is kept under its 600 lines.
 */
function wireDiffPage() {
  const page = $('page-diff');
  const control = (tag, id, cls, label) => {
    const node = el(tag, cls);
    node.id = id;
    if (label) node.setAttribute('aria-label', label);
    return node;
  };
  const field = (word, node) => {
    const label = el('label', 'diff-field');
    label.append(el('span', 'muted', word), node);
    return label;
  };
  const button = (id, text, title) => {
    const node = control('button', id, 'btn btn-sm');
    node.type = 'button';
    node.textContent = text;
    node.title = title;
    return node;
  };
  const pickA = control('select', 'diff-a', 'diff-pick', 'Session A, the baseline');
  const pickB = control('select', 'diff-b', 'diff-pick', 'Session B, the session under test');
  const swap = button('diff-swap', 'swap', 'Swap A and B');
  const filter = control('input', 'diff-filter', 'stats-filter', 'Diff filter, applied to both sessions');
  filter.type = 'text';
  filter.spellcheck = false;
  filter.autocomplete = 'off';
  filter.placeholder = 'status>=400  host:api  method:POST';
  const sinceA = control('select', 'diff-since-a', 'diff-pick', 'Cut A at a marker');
  const sinceB = control('select', 'diff-since-b', 'diff-pick', 'Cut B at a marker');
  const refresh = button('diff-refresh', 'refresh', 'Compare again: B may hold more calls than when this was read');
  const md = control('a', 'diff-md', 'btn btn-sm');
  md.textContent = 'markdown';
  md.title = 'This comparison as a Markdown file';
  md.setAttribute('download', '');
  const pairRow = el('div', 'page-actions');
  pairRow.append(field('B', pickB), field('against A', pickA), swap);
  const cutRow = el('div', 'page-actions');
  cutRow.append(filter, field('A', sinceA), field('B', sinceB), refresh, md);
  const error = control('div', 'diff-error', 'filter-error');
  error.hidden = true;
  const status = control('div', 'diff-status', 'page-status muted');
  status.setAttribute('role', 'status');
  const summary = control('p', 'diff-summary', 'diff-summary');
  const note = control('p', 'diff-note', 'settings-note diff-note');
  note.hidden = true;
  const empty = control('div', 'diff-empty', 'empty muted');
  const scroll = control('div', 'diff-scroll', 'diff-scroll');
  scroll.hidden = true;
  const table = control('table', 'diff-table', 'page-table diff-table');
  const thead = el('thead');
  thead.appendChild(control('tr', 'diff-head'));
  table.append(thead, control('tbody', 'diff-rows'));
  scroll.appendChild(table);
  const more = control('p', 'diff-more', 'settings-note muted');
  more.hidden = true;
  const toggle = button('diff-unchanged-toggle', 'show unchanged routes', 'Routes on both sides with nothing the daemon counts as a change');
  toggle.hidden = true;
  const rules = el('p', 'settings-note muted', 'A route is a method, host and path template, as on the Stats page, and calls are never paired '
    + 'across sessions. A body capture did not keep whole is unknown: a key is never called removed because of one.');
  page.append(pairRow, cutRow, error, status, summary, note, empty, scroll, more, toggle, rules);

  pickA.addEventListener('change', () => diffChoose(pickA.value, diffState.b));
  pickB.addEventListener('change', () => diffChoose(diffState.a, pickB.value));
  swap.addEventListener('click', () => {
    const { report } = diffState;
    diffChoose(report ? report.b.sessionId : diffState.b, report ? report.a.sessionId : diffState.a);
  });
  filter.addEventListener('input', () => {
    clearTimeout(diffState.typing);
    diffState.typing = setTimeout(() => diffSetFilter(filter.value), 150);
  });
  sinceA.addEventListener('change', () => { diffState.sinceA = sinceA.value; diffLoad(); });
  sinceB.addEventListener('change', () => { diffState.sinceB = sinceB.value; diffLoad(); });
  refresh.addEventListener('click', () => diffLoad());
  toggle.addEventListener('click', () => {
    diffState.unchanged = !diffState.unchanged;
    diffRender();
  });
}
