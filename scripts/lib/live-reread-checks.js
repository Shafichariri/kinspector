/* The live re-read under a filter, against a daemon that answers late or out of order.
 *
 * Under a filter a live row does not join the list: it owes a re-read of the filtered rows
 * (`oweReread`, `rereadLive` in `15-session-data.js`), because whether it matches is the daemon's to
 * say. These hold what `render-web-ui.js` cannot time: which answer survives when a load and a
 * re-read overlap, that a re-read answering for a filter no longer typed or a session no longer
 * shown is dropped, and how many requests a burst of 100 live rows costs. Each runs the real file
 * in its own context, as `session-state-checks.js` does, with routes that hold a request until
 * told to answer.
 *
 * Zero dependencies, like the rest of `check-web-static.js`.
 */
const { runFile } = require('./session-state-checks');
const { HarnessError } = require('./web-audits');

const pause = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * The call and signal routes over `rows`, honouring only `path:<path>` for calls and `tag:<name>`
 * for signals, read when the request arrives as the daemon reads them. A request whose route and
 * filter `hold` names waits for the returned `release`; `asked` lists every route asked, and
 * `flight.most` is the most requests that were out at once.
 */
function routes(rows) {
  const asked = [];
  const holds = [];
  const flight = { now: 0, most: 0 };
  const api = async (path) => {
    const url = new URL(path, 'http://stub.invalid');
    const filter = url.searchParams.get('filter') || '';
    const kind = url.pathname.endsWith('/transactions') ? 'calls' : url.pathname.endsWith('/signals') ? 'signals' : null;
    asked.push(kind || url.pathname);
    if (!kind) return [];
    const held = rows[kind].slice();
    flight.most = Math.max(flight.most, ++flight.now);
    const hold = holds.findIndex((h) => h.kind === kind && h.filter === filter);
    if (hold !== -1) await holds.splice(hold, 1)[0].until;
    flight.now--;
    const want = !filter ? held
      : kind === 'calls' ? held.filter((t) => `path:${t.path}` === filter) : held.filter((s) => `tag:${s.tag}` === filter);
    const offset = Number(url.searchParams.get('offset')) || 0;
    const items = want.slice().sort((a, b) => b.mono - a.mono).slice(offset, offset + (Number(url.searchParams.get('limit')) || 50));
    return kind === 'calls' ? { total: held.length, matched: want.length, items } : items;
  };
  /** Holds the next request for `kind` under `filter` until the returned function is called. */
  const hold = (kind, filter) => {
    let release;
    holds.push({ kind, filter, until: new Promise((r) => { release = r; }) });
    return () => release();
  };
  return { api, asked, hold, flight };
}

function page(sources, api, state) {
  const element = { hidden: false, textContent: '', title: '' };
  const context = runFile(sources, 'js/15-session-data.js', {
    state, api, ARCHIVE: null, tagOf: (s) => String(s.tag).toLowerCase(),
    availableViews: () => [{ id: 'network' }], visibleTransactions: () => state.transactions,
    renderTabs() {}, renderCurrent() {}, applyView() {}, renderSessionGlance() {}, renderListEmpty() {},
    showFilterError() {}, renderMarkers() {}, markerFormState() {}, renderList() {}, renderTimeline() {},
    renderWaterfall() {}, renderPath() {},
    $: () => element,
  });
  for (const name of ['oweReread', 'rereadLive', 'loadTransactions', 'loadSignals']) {
    if (typeof context[name] !== 'function') throw new HarnessError(`${name} is not declared in js/15-session-data.js`);
  }
  return context;
}

/** A session under `filter` whose list already holds what the daemon matched before any live row. */
function filtered(filter, rows) {
  const calls = rows.calls.filter((t) => `path:${t.path}` === filter);
  const signals = rows.signals.filter((s) => `tag:${s.tag}` === filter);
  return {
    sessionId: 'S', filter, liveTail: true, view: 'network',
    transactions: calls, sessionTotal: rows.calls.length, matched: calls.length,
    allTransactions: rows.calls.slice(), allSessionId: 'S', signals, allSignals: rows.signals.slice(),
    allSignalsSessionId: 'S', screens: [], markers: [], current: [], recording: [], callLoad: 0, signalLoad: 0,
    arriving: { transactions: null, signals: null },
    reread: { transactions: false, signals: false, timer: null, busy: false },
    readSent: { transactions: 0, signals: 0 }, readWritten: { transactions: 0, signals: 0 },
  };
}

const ids = (rows) => rows.map((r) => r.id).join(',');

/**
 * A load sent before a live row, answering after the re-read that row caused: the load's answer is
 * older, and must not take the row back out of the list. Once for calls, once for signals.
 */
async function checkLoadLandsLate(sources, kind) {
  const filter = kind === 'calls' ? 'path:/b' : 'tag:state';
  const rows = { calls: [{ id: 'c1', path: '/b', mono: 1 }, { id: 'c2', path: '/a', mono: 2 }], signals: [{ id: 's1', tag: 'state', mono: 1 }] };
  const { api, hold } = routes(rows);
  const state = filtered(filter, rows);
  const p = page(sources, api, state);
  const release = hold(kind, filter);
  const load = kind === 'calls' ? p.loadTransactions() : p.loadSignals();
  await pause(0);
  const live = kind === 'calls' ? { id: 'c3', path: '/b', mono: 3 } : { id: 's2', tag: 'state', mono: 3 };
  rows[kind].push(live);
  state.reread[kind === 'calls' ? 'transactions' : 'signals'] = true;
  await p.rereadLive();
  release();
  await load;
  const list = kind === 'calls' ? state.transactions : state.signals;
  return list.some((r) => r.id === live.id)
    ? `yes (the re-read's ${live.id} stayed when the older load landed after it: ${ids(list)})`
    : `NO - the list holds ${ids(list)}; the load sent before ${live.id} arrived overwrote the re-read`;
}

/**
 * A re-read held while a load for the same filter, sent after it, lands: the re-read's answer is
 * older. Once for calls, once for signals, because each kind's write carries its own ticket.
 */
async function checkRereadLandsLate(sources, kind) {
  const filter = kind === 'calls' ? 'path:/b' : 'tag:state';
  const rows = { calls: [{ id: 'c1', path: '/b', mono: 1 }], signals: [{ id: 's1', tag: 'state', mono: 1 }] };
  const { api, hold } = routes(rows);
  const state = filtered(filter, rows);
  const p = page(sources, api, state);
  const release = hold(kind, filter);
  state.reread[kind === 'calls' ? 'transactions' : 'signals'] = true;
  const reread = p.rereadLive();
  await pause(0);
  const live = kind === 'calls' ? { id: 'c2', path: '/b', mono: 2 } : { id: 's2', tag: 'state', mono: 2 };
  rows[kind].push(live);
  await (kind === 'calls' ? p.loadTransactions() : p.loadSignals());
  release();
  await reread;
  const list = kind === 'calls' ? state.transactions : state.signals;
  return list.some((r) => r.id === live.id)
    ? `yes (a load sent after the re-read kept ${live.id} when the re-read landed last: ${ids(list)})`
    : `NO - the list holds ${ids(list)}; the older re-read overwrote the newer load`;
}

/** A re-read answering after the filter changed, before the new filter's load has answered, is dropped. */
async function checkFilterChanged(sources) {
  const rows = { calls: [{ id: 'c1', path: '/b', mono: 1 }, { id: 'c2', path: '/a', mono: 2 }], signals: [] };
  const { api, hold } = routes(rows);
  const state = filtered('path:/b', rows);
  const p = page(sources, api, state);
  rows.calls.push({ id: 'c3', path: '/b', mono: 3 });
  const release = hold('calls', 'path:/b');
  state.reread.transactions = true;
  const reread = p.rereadLive();
  await pause(0);
  // As `applyFilter` does: the new filter is written, and its load is still out.
  state.filter = 'path:/a';
  const held = hold('calls', 'path:/a');
  const load = p.loadTransactions();
  release();
  await reread;
  const during = ids(state.transactions);
  held();
  await load;
  return !during.includes('c3')
    ? `yes (the path:/b re-read landing under path:/a was dropped; the list held ${during} until its load answered)`
    : `NO - the list held ${during} under path:/a: a re-read for the old filter was written`;
}

/**
 * 100 live rows, 50 calls and 50 signals, half before the re-read's timer fires and half while its
 * read is out — held past the re-read's wait, so a second one armed then would overlap it. Two
 * re-reads, one after the other: 2 × (1 + 2) requests over a few signals, never two out at once.
 */
async function checkBurst(sources) {
  const filter = 'tag:state';
  const rows = { calls: [], signals: [{ id: 's0', tag: 'state', mono: 0 }] };
  const { api, asked, hold, flight } = routes(rows);
  const state = filtered(filter, rows);
  const p = page(sources, api, state);
  const arrive = (n) => {
    rows.calls.push({ id: `c${n}`, path: '/x', mono: n });
    rows.signals.push({ id: `s${n}`, tag: n % 2 ? 'state' : 'cache', mono: n });
    p.oweReread('transactions');
    p.oweReread('signals');
  };
  for (let n = 1; n <= 25; n++) arrive(n);
  const release = hold('calls', filter);
  // Past the re-read's wait, so the first read is out and held.
  for (let waited = 0; asked.length === 0 && waited < 3000; waited += 50) await pause(50);
  for (let n = 26; n <= 50; n++) arrive(n);
  await pause(1500);
  release();
  await pause(2600);
  const reads = asked.filter((r) => r === 'calls' || r === 'signals');
  const newest = state.signals.some((s) => s.id === 's49');
  // Exactly two re-reads here: the first, and the one owed by the rows that came while it was out.
  return reads.length === 6 && flight.most === 1 && newest
    ? `yes (100 live rows cost ${reads.length} requests in two re-reads, one out at a time, and the newest matching signal is held)`
    : `NO - 100 live rows cost ${reads.length} requests (${reads.join(',')}), ${flight.most} out at once, newest matching signal held: ${newest}; 6 were due, one at a time`;
}

/** Under a filter the daemon refused, the list shows the parser's message and a live row arms nothing. */
function checkRefusedFilter(sources) {
  const rows = { calls: [{ id: 'c1', path: '/b', mono: 1 }], signals: [] };
  const state = { ...filtered('path:/b', rows), transactions: [], sessionTotal: null, matched: null };
  const p = page(sources, routes(rows).api, state);
  p.oweReread('transactions');
  const armed = state.reread.timer !== null;
  clearTimeout(state.reread.timer);
  return !armed
    ? 'yes (a live call under a filter the daemon refused armed no re-read)'
    : 'NO - a live call under a refused filter armed a re-read, which could only be refused again';
}

/**
 * A re-read answering after the session changed, before the new session's load has answered, is
 * dropped. Same filter on both, so only the session says the answer is not this list's.
 */
async function checkSessionChanged(sources) {
  const rows = { calls: [{ id: 'c1', path: '/b', mono: 1 }], signals: [] };
  const { api, hold } = routes(rows);
  const state = filtered('path:/b', rows);
  const p = page(sources, api, state);
  rows.calls.push({ id: 'c2', path: '/b', mono: 2 });
  const release = hold('calls', 'path:/b');
  state.reread.transactions = true;
  const reread = p.rereadLive();
  await pause(0);
  // As the session picker does: the new session is written, the list stands until its load answers.
  state.sessionId = 'T';
  const held = hold('calls', 'path:/b');
  const load = p.loadTransactions();
  release();
  await reread;
  const during = ids(state.transactions);
  held();
  await load;
  return !during.includes('c2')
    ? `yes (session S's re-read landing on session T was dropped; the list held ${during} until T's load answered)`
    : `NO - the list held ${during} on session T: a re-read for session S was written`;
}

async function checkLiveReread(sources) {
  return [
    ['reread beats older load', await checkLoadLandsLate(sources, 'calls')],
    ['reread beats older signals', await checkLoadLandsLate(sources, 'signals')],
    ['older reread dropped', await checkRereadLandsLate(sources, 'calls')],
    ['older reread signals', await checkRereadLandsLate(sources, 'signals')],
    ['reread filter changed', await checkFilterChanged(sources)],
    ['reread session changed', await checkSessionChanged(sources)],
    ['reread burst of 100', await checkBurst(sources)],
    ['reread refused filter', checkRefusedFilter(sources)],
  ];
}

module.exports = { checkLiveReread };
