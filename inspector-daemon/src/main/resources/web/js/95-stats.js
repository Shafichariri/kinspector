/* stats: the Stats page, the daemon's route table drawn as it answers it, and the caveat on Traffic */
'use strict';

/*
 * The page computes nothing. Routes, filter terms, the order, the rates and the `n<20` cell all come
 * from `GET …/stats`, so there is no mirror block and no case file to keep in step: the daemon's
 * `archive/RouteTemplates.kt` and `service/RouteStats.kt` are the only copy of each rule.
 */

/**
 * How long a live call or marker waits before the table is asked for again. The rows that arrive
 * meanwhile, or while that request is out, ride on it or on the one armed when it lands, as the
 * filtered re-read's do (`LIVE_REREAD_MS`): a burst of 100 inside a second costs at most two.
 */
const STATS_REFRESH_MS = 1000;
/** The daemon's own default; `#stats-more` says when a session holds more routes than this. */
const STATS_LIMIT = 1000;

/**
 * The page's own state. `sent`/`written` are the ticket pair: of a load and a refresh, the one sent
 * last is kept whichever lands last. `owed`, `timer` and `busy` are the refresh rule. `caveat` is the
 * route a row opened Traffic on, `{ term, label, sessionId }`, drawn while the filter is that term.
 */
const statsState = {
  filter: '', sort: 'attempts', report: null, error: null, sessionId: null,
  sent: 0, written: 0, owed: false, timer: null, busy: false, typing: null, caveat: null,
};

const statsShown = () => currentPage() === 'stats';

/**
 * Whether a live row may ask for the table again: live tail on, the page shown, a session selected.
 * One predicate for owing and for sending, so a row owed before a pause or a page change sends
 * nothing once the timer fires. Paused, the page drops rows, and resuming reloads it.
 */
const statsMayRefresh = () => state.liveTail && statsShown() && Boolean(state.sessionId);

/** `PAGES`' `enter` for Stats: the table is read every time the page is opened. */
function statsEnter() {
  statsLoad();
}

/** The table for the selected session, filter and sort, read now. */
function statsLoad() {
  if (!state.sessionsLoaded) {
    statsRender();   // "loading", until `init` has a session and asks again
    return Promise.resolve();
  }
  return statsRead(false);
}

/** Reloads the table if the page is shown; resuming live tail and `init` call it. */
function statsReload() {
  if (statsShown()) statsLoad();
}

/**
 * The session may have changed: a switch, a delete, or the daemon coming back. A table for another
 * session is dropped, read again if the page is shown, and a caveat for another session's route is
 * cleared, since the filter it describes was that session's route.
 */
function statsSessionChanged() {
  if (statsState.sessionId !== state.sessionId) {
    statsState.report = null;
    statsState.error = null;
  }
  if (statsState.caveat && statsState.caveat.sessionId !== state.sessionId) statsState.caveat = null;
  statsPaintCaveat();
  statsReload();
}

/**
 * A live call or marker for the session shown arrived (`ws.onmessage`, after its `liveTail` check).
 * A marker counts because it can move a `since:marker(…)` cut. Arms one refresh unless one is armed
 * or out already: never two in flight, and the rows that come meanwhile are covered by the one armed
 * when it lands.
 */
function oweStats() {
  if (!statsMayRefresh()) return;
  statsState.owed = true;
  if (statsState.timer === null && !statsState.busy) statsState.timer = setTimeout(statsRefresh, STATS_REFRESH_MS);
}

/** The refresh the owed rows armed; re-arms in its `finally` if more were owed while it was out. */
async function statsRefresh() {
  statsState.timer = null;
  if (!statsState.owed) return;
  statsState.owed = false;
  if (!statsMayRefresh()) return;
  statsState.busy = true;
  try {
    await statsRead(true);
  } finally {
    statsState.busy = false;
    if (statsState.owed) statsState.timer = setTimeout(statsRefresh, STATS_REFRESH_MS);
  }
}

/**
 * Sends `GET …/stats` and draws the answer, if it still stands. A refresh's answer is also dropped
 * once live tail changed under it; a load's is not, so pausing while the page opens still draws it.
 */
async function statsRead(live) {
  const ticket = ++statsState.sent;
  const { sessionId, liveTail } = state;
  const { filter, sort } = statsState;
  if (!sessionId) {
    statsState.written = ticket;
    statsState.report = null;
    statsState.error = null;
    statsState.sessionId = null;
    statsRender();
    return;
  }
  const stands = () => state.sessionId === sessionId && statsState.filter === filter && statsState.sort === sort
    && (!live || state.liveTail === liveTail);
  const q = new URLSearchParams({ filter, sort, limit: String(STATS_LIMIT) });
  let report = null;
  let error = null;
  try {
    report = await api(`/api/sessions/${encodeURIComponent(sessionId)}/stats?${q}`);
  } catch (e) {
    error = e.message;
  }
  if (!stands() || ticket < statsState.written) return;
  statsState.written = ticket;
  statsState.report = report;
  statsState.error = error;
  statsState.sessionId = sessionId;
  statsRender();
}

// --- drawing --------------------------------------------------------------

const statsPlural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
/** A rate as a percentage with up to one decimal, `—` where the daemon had no outcome to divide by. */
const statsRate = (rate) => (rate === null || rate === undefined ? '—' : `${Number((rate * 100).toFixed(1))}%`);
const statsBytes = (n) => (n === null || n === undefined ? '—' : n === 0 ? '0 B' : fmtBytes(n));
const statsOrigin = (row) => `${row.scheme}://${row.host}${row.port ? `:${row.port}` : ''}`;

/**
 * The columns, in order: the header's text, its title, its sort key if it has one, and the cell.
 * The keys are the daemon's; it also sorts by `errors`, which no one column draws.
 * `attempts` counts rows, as the traffic list does, so the per-route `calls` sum to fewer than the
 * header's count when there are retries; the two titles say why.
 */
const STATS_COLUMNS = [
  { head: 'calls', title: 'distinct calls: the attempts of one call share a callId', cell: (r) => String(r.calls) },
  { head: 'attempts', title: 'rows, as the traffic list counts them — a redirect hop or a retry is one', sort: 'attempts',
    cell: (r, td) => {
      td.appendChild(document.createTextNode(String(r.attempts)));
      if (!r.retried) return;
      const tag = el('span', 'stats-retried', statsPlural(r.retries, 'retry', 'retries'));
      tag.title = 'an attempt after a non-3xx attempt of the same call';
      td.appendChild(tag);
    } },
  { head: '4xx', title: 'share of attempts with an outcome that answered 4xx', cell: (r) => statsRate(r.rate4xx) },
  { head: '5xx', title: 'share of attempts with an outcome that answered 5xx', cell: (r) => statsRate(r.rate5xx) },
  { head: 'failed', title: 'share of attempts with an outcome that got no response: a transport failure', cell: (r) => statsRate(r.transportFailureRate) },
  { head: 'p50', sort: 'p50', latency: 'p50Ms' },
  { head: 'p90', sort: 'p90', latency: 'p90Ms' },
  { head: 'p95', sort: 'p95', latency: 'p95Ms', tail: true },
  { head: 'p99', sort: 'p99', latency: 'p99Ms', tail: true },
  { head: 'max', sort: 'max', latency: 'maxMs' },
  { head: 'mean req', title: 'mean request size over every attempt', cell: (r) => statsBytes(r.meanReqBytes) },
  { head: 'mean res', title: 'mean response size over attempts that got one', cell: (r) => statsBytes(r.meanResBytes) },
];

/**
 * A latency cell. A withheld tail prints the daemon's `tailWithheld` verbatim: the page never
 * compares a sample count against 20 itself, so the threshold has one home.
 */
function statsLatency(row, column, td) {
  const ms = row[column.latency];
  if (column.tail && (ms === null || ms === undefined) && row.tailWithheld) {
    const withheld = el('span', 'stats-withheld', row.tailWithheld);
    withheld.title = 'Fewer than 20 latency samples — a percentile of so few is a guess';
    td.appendChild(withheld);
    return;
  }
  td.textContent = fmtMs(ms);
}

/** The header row: sort buttons live, text in an export, where sorting would need the daemon. */
function statsRenderHead() {
  const tr = $('stats-head');
  tr.replaceChildren();
  const route = el('th', 'stats-route', 'route');
  route.scope = 'col';
  tr.appendChild(route);
  for (const column of STATS_COLUMNS) {
    const th = el('th', 'num');
    th.scope = 'col';
    if (column.title) th.title = column.title;
    if (column.latency) th.title = `${column.head} latency over attempts that got a response, nearest rank`;
    if (column.sort && !ARCHIVE) {
      const button = el('button', 'stats-sort', column.head);
      button.type = 'button';
      button.dataset.sort = column.sort;
      button.addEventListener('click', () => statsSort(button.dataset.sort));
      th.appendChild(button);
      if (statsState.sort === column.sort) th.setAttribute('aria-sort', 'descending');
    } else {
      th.textContent = column.head;
    }
    tr.appendChild(th);
  }
}

/** The route cell: method, the origin when the rows hold more than one, and the route; live, a button. */
function statsRouteCell(row, origins) {
  const th = el('th', 'stats-route');
  th.scope = 'row';
  const label = ARCHIVE ? el('span', 'stats-label') : el('button', 'stats-label stats-open');
  if (!ARCHIVE) {
    label.type = 'button';
    label.title = `Open these calls in Traffic: ${row.filterTerm}`;
  }
  label.appendChild(el('span', `method ${methodClass(row.method)}`, row.method));
  if (origins > 1) label.appendChild(el('span', 'stats-host muted', statsOrigin(row)));
  label.appendChild(el('span', 'stats-path', row.route));
  th.appendChild(label);
  return th;
}

/** Draws whatever `statsState` holds: the count, the filter's refusal, the table or why it is empty. */
function statsRender() {
  const { report, error } = statsState;
  const count = $('stats-count');
  const empty = $('stats-empty');
  const rows = $('stats-rows');
  const filterError = $('stats-filter-error');
  const refused = Boolean(error) && Boolean(statsState.filter);
  filterError.hidden = !refused;
  filterError.textContent = refused ? error : '';
  statsRenderHead();
  rows.replaceChildren();
  $('stats-more').hidden = true;
  count.textContent = '';
  empty.replaceChildren();
  empty.hidden = true;

  const why = statsEmptyWhy();
  if (why) {
    empty.hidden = false;
    empty.appendChild(el('span', null, why));
    if (report && report.total > 0 && statsState.filter && !ARCHIVE) {
      const clear = el('button', 'btn btn-sm', 'clear filter');
      clear.addEventListener('click', () => statsSetFilter(''));
      empty.appendChild(clear);
    }
  }
  $('stats-scroll').hidden = !report || report.routes.length === 0;
  if (!report) return;

  const calls = statsPlural(report.total, 'call', 'calls');
  count.textContent = statsState.filter
    ? `${statsPlural(report.routeCount, 'route', 'routes')} · ${report.matched} of ${calls} in this session match`
    : `${statsPlural(report.routeCount, 'route', 'routes')} · ${calls} in this session`;
  const origins = new Set(report.routes.map(statsOrigin)).size;
  for (const row of report.routes) {
    const tr = el('tr', 'stats-row');
    tr.dataset.term = row.filterTerm;
    tr.appendChild(statsRouteCell(row, origins));
    for (const column of STATS_COLUMNS) {
      const td = el('td', 'num');
      if (column.latency) statsLatency(row, column, td);
      else {
        const text = column.cell(row, td);
        if (text !== undefined) td.textContent = text;
      }
      tr.appendChild(td);
    }
    if (!ARCHIVE) tr.addEventListener('click', () => statsOpenRoute(row));
    rows.appendChild(tr);
  }
  if (report.routeCount > report.routes.length) {
    const more = $('stats-more');
    more.hidden = false;
    more.textContent = `first ${report.routes.length} of ${report.routeCount} routes in this order`;
  }
}

/** Why there is no table, in the four cases the traffic list's empty state also tells apart. */
function statsEmptyWhy() {
  const { report, error } = statsState;
  if (!state.sessionsLoaded) return 'loading…';
  if (!state.sessionId) return 'No session yet. The table fills once an app with inspector-stream has recorded one.';
  if (error) return statsState.filter ? 'The daemon refused the filter; it says why above.' : error;
  if (!report) return 'loading…';
  if (report.total === 0) return 'No calls in this session yet.';
  if (report.routes.length === 0) return `No calls match the filter \`${statsState.filter}\`.`;
  return null;
}

// --- controls ---------------------------------------------------------------

function statsSort(key) {
  statsState.sort = key;
  statsLoad();
}

function statsSetFilter(text) {
  $('stats-filter').value = text;
  statsState.filter = text.trim() ? text : '';
  statsLoad();
}

/**
 * A row opens Traffic on the daemon's term for it. `navigate` pushes, so Back returns here, and the
 * term goes through `applyFilter`, the one funnel every way of setting a filter takes. The caveat is
 * set first, so it is there when `applyFilter`'s timer writes the filter it describes.
 */
async function statsOpenRoute(row) {
  if (ARCHIVE) return;
  statsState.caveat = { term: row.filterTerm, label: `${row.method} ${row.route}`, sessionId: state.sessionId };
  await navigate({ page: 'traffic', view: 'network' });
  $('filter').value = row.filterTerm;
  applyFilter();
}

/**
 * `applyFilter`'s timer just wrote `state.filter`. Any filter but the route's term clears the
 * caveat, because the list is then no longer the route's superset.
 */
function statsFilterChanged() {
  if (statsState.caveat && state.filter !== statsState.caveat.term) statsState.caveat = null;
  statsPaintCaveat();
}

/** `#filter-caveat`, shown exactly while the Traffic filter is the term a Stats row opened. */
function statsPaintCaveat() {
  const node = $('filter-caveat');
  const caveat = statsState.caveat;
  const shown = Boolean(caveat) && state.filter === caveat.term;
  node.hidden = !shown;
  if (shown) $('filter-caveat-route').textContent = caveat.label;
}

/** Wiring, from `99-init.js`. In an export the filter is disabled, as Traffic's is, and the note says why. */
function wireStatsPage() {
  const input = $('stats-filter');
  $('filter-caveat-back').addEventListener('click', () => navigate({ page: 'stats' }));
  if (ARCHIVE) {
    input.disabled = true;
    input.placeholder = 'filters need the daemon — this is an exported session';
    $('stats-export-note').hidden = false;
    return;
  }
  input.addEventListener('input', () => {
    clearTimeout(statsState.typing);
    statsState.typing = setTimeout(() => {
      statsState.filter = input.value.trim() ? input.value : '';
      statsLoad();
    }, 150);
  });
}
