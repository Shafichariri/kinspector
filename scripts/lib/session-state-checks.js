/* How the web UI's session data holds up against a daemon that answers late, fails or moves on.
 *
 * Each check runs one real file out of `web/scripts.txt` in its own `vm` context, with `state` and
 * the few page functions the file calls stubbed, and drives it the way the page does. The files
 * only declare at load (`web-ui.md`), so running one whole is the same as lifting every function in
 * it, and needs no anchors to drift. What these hold is ordering and choice of source — which list
 * a function reads, which answer survives two overlapping loads — which no mirror case covers,
 * because no Kotlin rule owns them.
 *
 * Zero dependencies, like the rest of `check-web-static.js`.
 */
const vm = require('vm');
const { HarnessError } = require('./web-audits');

/**
 * Runs each of `files` from the manifest, in order, in one fresh context holding `globals` — one
 * shared scope, as the page's classic scripts share one. Returns the context.
 */
function runFile(sources, files, globals) {
  const context = vm.createContext({ URL, URLSearchParams, encodeURIComponent, console, setTimeout, ...globals });
  for (const file of [].concat(files)) {
    const source = sources.find((s) => s.file === file);
    if (!source) throw new HarnessError(`${file} is not in web/scripts.txt; update session-state-checks.js`);
    new vm.Script(source.text, { filename: file }).runInContext(context);
  }
  return context;
}

/**
 * "Newest row in this session" names the session's newest row, not the newest row the filter
 * kept. Under a filter that matched only an older call, reading the filtered list dated the
 * session by that call.
 */
function checkLastCapture(sources) {
  const older = { id: 'old', ts: '2026-01-01T09:00:01.000Z' };
  const newer = { id: 'new', ts: '2026-01-01T09:00:09.000Z' };
  const state = { lastCaptureTs: null, transactions: [older], allTransactions: [older, newer], allSignals: [] };
  const { lastCapture } = runFile(sources, 'js/85-processes.js', { state });
  if (typeof lastCapture !== 'function') throw new HarnessError('lastCapture is not declared in js/85-processes.js');
  const got = lastCapture();
  return got && got.ts === newer.ts
    ? `yes (${got.what} is ${got.ts.slice(11, 19)}, the session's newest, under a filter that kept an older call)`
    : `NO - ${got ? `${got.what} ${got.ts}` : 'nothing'}, the session's newest is ${newer.ts}`;
}

/**
 * A stub of the signal route over `rows`: a bare array, newest first, sliced by `offset` and
 * `limit`, honouring only `tag:<name>` filters, and failing or waiting where `behave` says.
 */
function signalRoute(rows, behave = () => null) {
  return async (path) => {
    const url = new URL(path, 'http://stub.invalid');
    if (!url.pathname.endsWith('/signals')) return [];
    const filter = url.searchParams.get('filter') || '';
    // What the daemon holds when the request reaches it, however long the answer then takes.
    const held = rows.slice();
    await behave(filter);
    const want = filter.startsWith('tag:') ? held.filter((r) => r.tag === filter.slice(4)) : filter ? [] : held;
    const offset = Number(url.searchParams.get('offset')) || 0;
    const limit = Number(url.searchParams.get('limit')) || 100;
    return want.slice().sort((a, b) => b.mono - a.mono).slice(offset, offset + limit);
  };
}

/** `15-session-data.js` in a context whose page functions do nothing, over `api`. */
function sessionData(sources, api, state) {
  return runFile(sources, 'js/15-session-data.js', {
    state,
    api,
    tagOf: (s) => String(s.tag).toLowerCase(),
    availableViews: () => [{ id: 'network' }],
    renderTabs() {}, renderCurrent() {}, applyView() {}, renderSessionGlance() {},
    $: () => ({ hidden: false }),
  });
}

const sessionState = (filter) => ({
  sessionId: 'S', filter, signals: [], allSignals: [], allSignalsSessionId: null, screens: [], view: 'network',
  transactions: [], allTransactions: [], allSessionId: null, markers: [], callLoad: 0, signalLoad: 0,
});

/**
 * A whole-session read that fails under a filter leaves the filtered rows standing in, and must
 * not mark them as the session: the next load would trust them until the filter was cleared.
 */
async function checkWholeSignalsFailure(sources) {
  const rows = [{ id: 's1', tag: 'screen', mono: 1 }, { id: 'c1', tag: 'cache', mono: 2 }];
  let failures = 1;
  const api = signalRoute(rows, (filter) => {
    if (!filter && failures-- > 0) throw new Error('the whole-session read failed');
  });
  const state = sessionState('tag:cache');
  const page = sessionData(sources, api, state);
  await page.loadSignals();
  const cached = state.allSignalsSessionId === state.sessionId;
  const standIn = state.allSignals.map((s) => s.id).join(',');
  await page.loadSignals();
  const after = state.allSignals.map((s) => s.id).sort().join(',');
  return !cached && after === 'c1,s1'
    ? `yes (the failed read left ${standIn || 'nothing'} unmarked, and the next load read c1,s1)`
    : `NO - ${cached ? `the filtered ${standIn} was cached as the session` : 'not cached'}, and the next load held ${after || 'nothing'}`;
}

const pause = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Two signal loads overlapping: an unfiltered one still waiting on the daemon when a tag chip sets
 * `tag:cache` and a second load answers first. The older answer, landing last, must not replace the
 * newer one — and the filter it was asked for must be the one it reads, not `state.filter` re-read
 * after the wait.
 */
async function checkSignalRace(sources) {
  const rows = [{ id: 's1', tag: 'screen', mono: 1 }, { id: 'c1', tag: 'cache', mono: 2 }];
  const api = signalRoute(rows, (filter) => (filter ? null : pause(50)));
  const state = sessionState('');
  const page = sessionData(sources, api, state);
  await page.loadSignals();
  const slow = page.loadSignals();
  await pause(10);
  state.filter = 'tag:cache';
  await page.loadSignals();
  await slow;
  const ids = state.signals.map((s) => s.id).join(',');
  return ids === 'c1'
    ? 'yes (an unfiltered load landing after a tag:cache one left the signals at c1)'
    : `NO - signals ${ids || 'empty'} under tag:cache, where c1 was due`;
}

/**
 * `15-session-data.js` with every page function `loadTransactions` draws through stubbed, so a
 * whole load can run: what is checked is which answers the state is left holding.
 */
function loadingPage(sources, api, state) {
  const element = { hidden: false, textContent: '' };
  return runFile(sources, 'js/15-session-data.js', {
    state, api, ARCHIVE: null, tagOf: (s) => String(s.tag).toLowerCase(),
    availableViews: () => [{ id: 'network' }], visibleTransactions: () => state.transactions,
    renderTabs() {}, renderCurrent() {}, applyView() {}, renderSessionGlance() {}, renderListEmpty() {},
    showFilterError() {}, renderMarkers() {}, markerFormState() {}, renderList() {}, renderTimeline() {},
    $: () => element,
  });
}

/**
 * The same for the list: an unfiltered read of the calls answering after the read for a filter the
 * user typed since. Every page function the load draws through is a no-op here, and the signal
 * route answers an empty list; what is checked is which answer `state.transactions` is left holding.
 */
async function checkCallRace(sources) {
  const calls = [{ id: 't1', path: '/a', mono: 1 }, { id: 't2', path: '/b', mono: 2 }];
  const api = async (path) => {
    const url = new URL(path, 'http://stub.invalid');
    if (!url.pathname.endsWith('/transactions')) return [];
    const filter = url.searchParams.get('filter') || '';
    if (!filter) await pause(50);
    const items = filter ? calls.filter((t) => `path:${t.path}` === filter) : calls;
    return { items, total: items.length };
  };
  const state = sessionState('');
  const page = loadingPage(sources, api, state);
  const slow = page.loadTransactions();
  await pause(10);
  state.filter = 'path:/b';
  await page.loadTransactions();
  await slow;
  const ids = state.transactions.map((t) => t.id).join(',');
  return ids === 't2'
    ? 'yes (an unfiltered load landing after a path:/b one left the list at t2)'
    : `NO - the list holds ${ids || 'nothing'} under path:/b, where t2 was due`;
}

/**
 * A stub of the transaction route over `rows`: a page, newest first, sliced by `offset` and
 * `limit` and clamped to `clamp` as the daemon clamps, honouring only `path:<path>` filters, with
 * `total` the session's count and `matched` the filter's — the daemon's own shape — and failing or
 * waiting where `behave` says.
 */
function callRoute(rows, { clamp = 1000, behave = () => null } = {}) {
  return async (path) => {
    const url = new URL(path, 'http://stub.invalid');
    if (!url.pathname.endsWith('/transactions')) return [];
    const filter = url.searchParams.get('filter') || '';
    const held = rows.slice();
    await behave(filter);
    const want = filter ? held.filter((t) => `path:${t.path}` === filter) : held;
    const offset = Number(url.searchParams.get('offset')) || 0;
    const limit = Math.min(clamp, Number(url.searchParams.get('limit')) || 50);
    const items = want.slice().sort((a, b) => b.mono - a.mono).slice(offset, offset + limit);
    return { total: held.length, matched: want.length, items };
  };
}

/** The same rule as `whole read failure`, for the calls: a failed whole read marks nothing. */
async function checkWholeCallsFailure(sources) {
  const rows = [{ id: 't1', path: '/a', mono: 1 }, { id: 't2', path: '/b', mono: 2 }];
  let failures = 1;
  const api = callRoute(rows, { behave: (filter) => {
    if (!filter && failures-- > 0) throw new Error('the whole-session read failed');
  } });
  const state = { ...sessionState('path:/b'), transactions: [rows[1]] };
  const page = sessionData(sources, api, state);
  await page.syncAllTransactions(state.filter);
  const cached = state.allSessionId === state.sessionId;
  const standIn = state.allTransactions.map((t) => t.id).join(',');
  await page.syncAllTransactions(state.filter);
  const after = state.allTransactions.map((t) => t.id).sort().join(',');
  return !cached && after === 't1,t2'
    ? `yes (the failed read left ${standIn || 'nothing'} unmarked, and the next load read t1,t2)`
    : `NO - ${cached ? `the filtered ${standIn} was cached as the session` : 'not cached'}, and the next load held ${after || 'nothing'}`;
}

/**
 * A whole-session read already in flight when the page forgets the whole session — the socket came
 * back, the list resumed — answers from before the gap. It must not land as the session: the load
 * after the gap would trust it and miss whatever arrived in between. Played for each copy, by
 * holding that copy's unfiltered read until a row has arrived and `forgetWholeSession` has run.
 */
async function checkForgetInFlight(sources) {
  const lines = [];
  for (const kind of ['calls', 'signals']) {
    const calls = [{ id: 't1', path: '/a', mono: 1 }, { id: 't2', path: '/b', mono: 2 }];
    const signals = [{ id: 's1', tag: 'screen', mono: 1 }, { id: 'c1', tag: 'cache', mono: 2 }];
    let gate = null;
    const hold = (filter) => (!filter && gate ? gate : null);
    const callApi = callRoute(calls, { behave: kind === 'calls' ? hold : () => null });
    const signalApi = signalRoute(signals, kind === 'signals' ? hold : () => null);
    const api = async (path) => {
      const pathname = new URL(path, 'http://stub.invalid').pathname;
      if (pathname.endsWith('/transactions')) return callApi(path);
      if (pathname.endsWith('/signals')) return signalApi(path);
      return [];
    };
    const state = sessionState(kind === 'calls' ? 'path:/b' : 'tag:cache');
    const page = loadingPage(sources, api, state);
    let open;
    gate = new Promise((resolve) => { open = resolve; });
    const old = kind === 'calls' ? page.loadTransactions() : page.loadSignals();
    await pause(10);
    if (kind === 'calls') calls.push({ id: 't3', path: '/c', mono: 3 });
    else signals.push({ id: 'g1', tag: 'netq', mono: 3 });
    page.forgetWholeSession();
    open();
    await old;
    gate = null;
    if (kind === 'calls') await page.loadTransactions();
    else await page.loadSignals();
    const got = (kind === 'calls' ? state.allTransactions : state.allSignals).map((r) => r.id).sort().join(',');
    const due = kind === 'calls' ? 't3' : 'g1';
    lines.push([`forget drops ${kind} read`, got.split(',').includes(due)
      ? `yes (a read from before the gap was dropped, and the next load held ${got})`
      : `NO - the next load held ${got || 'nothing'}, without ${due}, which arrived in the gap`]);
  }
  return lines;
}

/**
 * Deleting the session on screen with no other to show clears the page through
 * `forgetWholeSession`, so a load still in flight for the deleted session is dropped and neither
 * whole-session copy stays marked as some session's.
 */
async function checkAfterSessionChange(sources) {
  const element = { hidden: false, innerHTML: '', value: '', appendChild() {} };
  const state = {
    ...sessionState(''), sessionId: null, allSessionId: 'S', allSignalsSessionId: 'S', callLoad: 4, signalLoad: 7,
    sessions: [], payloads: new Map(), current: [],
  };
  const page = runFile(sources, ['js/15-session-data.js', 'js/90-sessions.js'], {
    state, api: async (path) => (path === '/api/recording' ? [] : []), document: {},
    $: (id) => (id === 'session-rows' ? null : element),
    renderList() {}, renderMarkers() {}, renderCurrent() {}, renderTabs() {}, applyView() {},
  });
  await page.afterSessionChange();
  const ok = state.allSessionId === null && state.allSignalsSessionId === null && state.callLoad > 4 && state.signalLoad > 7;
  return ok
    ? 'yes (both copies unmarked, and the call and signal loads moved on)'
    : `NO - allSessionId ${state.allSessionId}, allSignalsSessionId ${state.allSignalsSessionId}, callLoad ${state.callLoad} (was 4), signalLoad ${state.signalLoad} (was 7)`;
}

async function checkSessionState(sources) {
  return [
    ['last capture whole', checkLastCapture(sources)],
    ['whole read failure', await checkWholeSignalsFailure(sources)],
    ['older signal load dropped', await checkSignalRace(sources)],
    ['older call load dropped', await checkCallRace(sources)],
    ['whole calls read failure', await checkWholeCallsFailure(sources)],
    ...(await checkForgetInFlight(sources)),
    ['session change forgets', await checkAfterSessionChange(sources)],
  ];
}

module.exports = { checkSessionState };
