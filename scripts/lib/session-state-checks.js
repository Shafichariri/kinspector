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
 *
 * `readAt` says when the rows are read. `arrival`, the default, is what the daemon held when the
 * request reached it, however long the answer then takes, which is what a read from before a gap
 * needs. `answer` reads them as the answer leaves, so a row added while `behave` holds the request
 * is in that page, as a row stored before a page is read is in the daemon's.
 */
function signalRoute(rows, behave = () => null, { readAt = 'arrival' } = {}) {
  return async (path) => {
    const url = new URL(path, 'http://stub.invalid');
    if (!url.pathname.endsWith('/signals')) return [];
    const filter = url.searchParams.get('filter') || '';
    let held = rows.slice();
    await behave(filter, url);
    if (readAt === 'answer') held = rows.slice();
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
  arriving: { transactions: null, signals: null },
  reread: { transactions: false, signals: false, timer: null, busy: false },
  readSent: { transactions: 0, signals: 0 }, readWritten: { transactions: 0, signals: 0 },
  currentSessionId: 'S', currentRead: { sent: 0, written: 0 }, signalSessionId: 'S',
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
 * waiting where `behave` says. `readAt` as for `signalRoute`.
 */
function callRoute(rows, { clamp = 1000, behave = () => null, readAt = 'arrival' } = {}) {
  return async (path) => {
    const url = new URL(path, 'http://stub.invalid');
    if (!url.pathname.endsWith('/transactions')) return [];
    const filter = url.searchParams.get('filter') || '';
    let held = rows.slice();
    await behave(filter, url);
    if (readAt === 'answer') held = rows.slice();
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
    renderTimeline() {}, renderSessionGlance() {},
  });
  await page.afterSessionChange();
  const ok = state.allSessionId === null && state.allSignalsSessionId === null && state.callLoad > 4 && state.signalLoad > 7;
  return ok
    ? 'yes (both copies unmarked, and the call and signal loads moved on)'
    : `NO - allSessionId ${state.allSessionId}, allSignalsSessionId ${state.allSignalsSessionId}, callLoad ${state.callLoad} (was 4), signalLoad ${state.signalLoad} (was 7)`;
}

/**
 * The page over a session past the route's 1000-row clamp: 3016 calls, 1200 of them to
 * `/v3/shop/bulk`, over two screens — `Old` from the start, `New` from call 2500. The route stub
 * clamps a page as the daemon does and answers `total` and `matched` as it does. The files run are
 * the real load and the real notes and path; drawing is stubbed.
 */
function clampPage(sources) {
  const calls = Array.from({ length: 3016 }, (_, i) => ({
    id: `t${String(i).padStart(4, '0')}`, mono: i, host: 'shop.invalid',
    path: i % 2 === 0 && i < 2400 ? '/v3/shop/bulk' : '/v3/shop/stock',
  }));
  const screens = [
    { id: 'old', tag: 'screen', name: 'Old', mono: 0 },
    { id: 'new', tag: 'screen', name: 'New', mono: 2500 },
  ];
  const callApi = callRoute(calls);
  const signalApi = signalRoute(screens);
  const api = async (path) => {
    const pathname = new URL(path, 'http://stub.invalid').pathname;
    if (pathname.endsWith('/transactions')) return callApi(path);
    if (pathname.endsWith('/signals')) return signalApi(path);
    return [];
  };
  const element = { hidden: false, textContent: '' };
  const el = (tag, cls, text) => ({ textContent: text || '', addEventListener() {} });
  const state = { ...sessionState(''), timeRange: null, recording: [] };
  const page = runFile(sources, ['js/15-session-data.js', 'js/25-traffic-list.js', 'js/45-visits.js', 'js/50-path.js'], {
    state, api, el, ARCHIVE: null, tagOf: (x) => String(x.tag).toLowerCase(),
    availableViews: () => [{ id: 'network' }],
    renderTabs() {}, renderCurrent() {}, applyView() {}, renderSessionGlance() {}, showFilterError() {},
    renderMarkers() {}, markerFormState() {}, renderList() {}, renderTimeline() {}, $: () => element,
  });
  // The files declare their own renderers over the stubs; drawing is not what is checked here. The
  // real header count and empty text are kept aside for the checks that read them.
  const real = { renderCounts: page.renderCounts, renderListEmpty: page.renderListEmpty };
  for (const name of ['renderMarkers', 'renderList', 'renderListEmpty', 'renderCounts', 'markerFormState']) {
    page[name] = () => {};
  }
  return { state, page, real, element };
}

/** What the path says of each visit, as `renderPath` would draw an emptied one, joined by ` / `. */
function pathCounts(page, state) {
  if (typeof page.countRecorded !== 'function') return 'no countRecorded in js/50-path.js';
  const list = page.visits(state.screens, state.transactions, [], state.signals, page.sessionStartMono());
  page.countRecorded(list);
  // A top-level `const`, so it lives in the scripts' shared scope rather than on the context.
  const emptyVisitText = vm.runInContext('emptyVisitText', page);
  return list.map((visit) => emptyVisitText(visit, 'nothing recorded')).join(' / ');
}

/**
 * A session past the route's 1000-row clamp, filter first. The rows a load holds stop at the
 * clamp, so a count taken from them is a count of the clamp: the session total less the filter's
 * matches is the answer, and where only held rows can answer — a brushed stretch — the note says
 * "at least".
 */
async function checkPastTheClamp(sources) {
  const { state, page } = clampPage(sources);
  const node = () => {
    const parts = [];
    return {
      parts, replaceChildren() { parts.length = 0; }, append(...xs) { parts.push(...xs.map(String)); },
      appendChild(child) { parts.push(child.textContent || ''); return child; },
      get textContent() { return parts.join(''); },
    };
  };
  const lines = [];
  const note = (hidden) => page.filterNote(node(), hidden, false).textContent;

  state.filter = 'path:/v3/shop/bulk';
  await page.loadTransactions();
  let hidden = page.hiddenByFilter({ signals: false });
  let text = note(hidden);
  lines.push(['hidden past the clamp', text.startsWith('1816 calls hidden')
    ? `yes ("${text.slice(0, 40)}…", 3016 calls less 1200 matched, with ${state.transactions.length} rows held)`
    : `NO - "${text.slice(0, 40)}", where 1816 calls hidden was due (3016 less 1200 matched)`]);

  state.timeRange = { from: 0, to: 500 };
  hidden = page.hiddenByFilter({ signals: false, ranged: true });
  text = note(hidden);
  state.timeRange = null;
  lines.push(['stretch past the clamp', hidden.calls > 0 && text.startsWith('at least ')
    ? `yes ("${text.slice(0, 40)}…", a stretch older than the matches held)`
    : `NO - "${text.slice(0, 40)}" (${hidden.calls} calls) for a stretch the rows held cannot count; "at least" was due`]);

  state.filter = 'path:/nothing';
  await page.loadTransactions();
  hidden = page.hiddenByFilter({ signals: false });
  text = note(hidden);
  lines.push(['hidden when none match', text.startsWith('3016 calls hidden')
    ? `yes ("${text.slice(0, 40)}…")`
    : `NO - "${text.slice(0, 40)}", where 3016 calls hidden was due`]);

  const got = pathCounts(page, state);
  lines.push(['path N past the clamp', got === '0 of 2500 match / 0 of 516 match'
    ? `yes ("${got}", N over every call)`
    : `NO - "${got}", where "0 of 2500 match / 0 of 516 match" was due`]);
  return lines;
}

/**
 * The same session loaded with no filter first, then filtered — the order a reader usually takes.
 * The unfiltered page is the newest 1000 calls, short of the 3016 the route counts, so it is not the
 * whole session: the whole-session copy is read in pages there too. Copied from the page and marked
 * as the session, as `if (!filter)` alone did, it was trusted once the filter was typed, and the
 * path counted every visit older than the newest 1000 calls from the clamp, calling them emptier
 * than they were — on the real session, "nothing recorded" over visits with calls. The filter-first
 * check above cannot see it, because a filtered load always reads the copy whole.
 */
async function checkUnfilteredThenFilter(sources) {
  const { state, page } = clampPage(sources);
  await page.loadTransactions();
  const unfiltered = `${state.allTransactions.length} of ${state.sessionTotal}`;
  state.filter = 'path:/nothing';
  await page.loadTransactions();
  const got = pathCounts(page, state);
  return got === '0 of 2500 match / 0 of 516 match'
    ? `yes ("${got}" after an unfiltered load held ${unfiltered} in the whole copy)`
    : `NO - "${got}", where "0 of 2500 match / 0 of 516 match" was due; the unfiltered load left ${unfiltered} in the whole copy`;
}

/**
 * The same session with no filter: the list holds the newest 1000 of 3016, and the header says so
 * in words a filter does not produce. `1000 of 3016 calls` is what a filter matching 1000 reads.
 * Through the real `renderCounts`, put back over the stub before the load calls it.
 */
async function checkNoFilterCount(sources) {
  const { state, page, real, element } = clampPage(sources);
  page.renderCounts = real.renderCounts;
  await page.loadTransactions();
  const text = element.textContent;
  return text === 'newest 1000 of 3016 shown'
    ? `yes ("${text}", ${state.transactions.length} rows held of ${state.sessionTotal}, no filter)`
    : `NO - "${text}", where "newest 1000 of 3016 shown" was due`;
}

/**
 * A live row arriving while the whole-session read is in flight is in the copy when the read lands,
 * and in it once — for the calls and for the signals. The session is 1500 rows, so the read takes
 * two pages, and the routes read their rows as they answer, as the daemon's do.
 *
 * - kept: the second page (`offset=1000`) is held while the row arrives, so the read never returns
 *   it — every page it could be on was answered already. Only the buffer has it. It was in neither
 *   while a live row joined a copy only once the copy was marked, which happens when the read lands.
 * - once: the first page is held instead, so the read returns the row and the buffer holds it too.
 *   Merged by id it is there once; appended whole it is there twice.
 */
async function checkMidRead(sources) {
  const lines = [];
  for (const held of ['kept', 'once']) {
    for (const kind of ['calls', 'signals']) {
      const calls = kind === 'calls';
      const rows = Array.from({ length: 1500 }, (_, i) => (calls
        ? { id: `t${i}`, path: '/a', mono: i }
        : { id: `s${i}`, tag: 'screen', mono: i }));
      const holdAt = held === 'kept' ? '1000' : '0';
      let release;
      const gate = new Promise((resolve) => { release = resolve; });
      let reached;
      const asked = new Promise((resolve) => { reached = resolve; });
      const behave = (filter, url) => {
        if (filter || url.searchParams.get('offset') !== holdAt) return null;
        reached();
        return gate;
      };
      const api = calls ? callRoute(rows, { behave, readAt: 'answer' }) : signalRoute(rows, behave, { readAt: 'answer' });
      const state = sessionState(calls ? 'path:/b' : 'tag:cache');
      const page = sessionData(sources, api, state);
      const read = calls ? page.syncAllTransactions(state.filter) : page.syncAllSignals(state.filter);
      await asked;
      const live = calls ? { id: 'live', path: '/a', mono: 1500 } : { id: 'live', tag: 'screen', mono: 1500 };
      page.admitLive(calls ? 'transactions' : 'signals', live);
      rows.push(live);
      release();
      await read;
      const copy = calls ? state.allTransactions : state.allSignals;
      const marked = (calls ? state.allSessionId : state.allSignalsSessionId) === state.sessionId;
      const times = copy.filter((r) => r.id === 'live').length;
      const where = `page offset=${holdAt} held`;
      lines.push([`mid-read ${kind} ${held}`, marked && times === 1 && copy.length === 1501
        ? `yes (a live row arriving with the ${where} is in the landed copy once, ${copy.length} rows)`
        : `NO - with the ${where}, the landed copy holds the live row ${times} time(s) in ${copy.length} rows${marked ? '' : ', unmarked'}; once in 1501 was due`]);
    }
  }
  return lines;
}

/** A gate a stub route waits on: `asked` settles when the route reaches it, `open` lets it answer. */
function routeGate() {
  const gate = {};
  gate.held = new Promise((resolve) => { gate.open = resolve; });
  gate.asked = new Promise((resolve) => { gate.reached = resolve; });
  return gate;
}

/**
 * A copy already marked as the session's but empty is read again by a filtered load, and the read
 * replaces it. A live row arriving meanwhile went onto the marked copy alone, and the landed read
 * threw it away with the array it was pushed into. The route reads its rows as each request
 * arrives, so the row, stored after the only page was read, can reach the landed copy only through
 * the buffer.
 */
async function checkMidReadEmpty(sources) {
  const lines = [];
  for (const kind of ['calls', 'signals']) {
    const calls = kind === 'calls';
    const rows = [];
    const gate = routeGate();
    const behave = (filter) => (filter ? null : (gate.reached(), gate.held));
    const api = calls ? callRoute(rows, { behave }) : signalRoute(rows, behave);
    const state = sessionState(calls ? 'path:/b' : 'tag:cache');
    if (calls) state.allSessionId = 'S'; else state.allSignalsSessionId = 'S';
    const page = sessionData(sources, api, state);
    const read = calls ? page.syncAllTransactions(state.filter) : page.syncAllSignals(state.filter);
    await gate.asked;
    const live = calls ? { id: 'live', path: '/a', mono: 1 } : { id: 'live', tag: 'screen', mono: 1 };
    rows.push(live);
    page.admitLive(calls ? 'transactions' : 'signals', live);
    gate.open();
    await read;
    const copy = calls ? state.allTransactions : state.allSignals;
    const times = copy.filter((r) => r.id === 'live').length;
    lines.push([`mid-read ${kind} empty`, times === 1
      ? 'yes (a live row arriving while a marked, empty copy is read again is in the landed copy once)'
      : `NO - the landed copy holds the live row ${times} time(s); the marked, empty copy took it and the read replaced it`]);
  }
  return lines;
}

/**
 * Two whole-session reads of the calls overlap: an older load's, then a newer one's, which opens
 * its own buffer. The older read lands, overtaken, while the newer one's only page is held, and a
 * live row arrives after that. An overtaken read that cleared whatever buffer was open, rather
 * than its own, took the newer read's with it, and the row was in neither the read nor the copy.
 */
async function checkMidReadOvertaken(sources) {
  const rows = [{ id: 't0', path: '/a', mono: 0 }];
  const gates = [routeGate(), routeGate()];
  let asked = 0;
  const behave = (filter, url) => (filter || url.searchParams.get('offset') !== '0' ? null : gates[asked++].held);
  const state = sessionState('path:/b');
  const page = sessionData(sources, callRoute(rows, { behave }), state);
  let latest = 1;
  const older = page.syncAllTransactions(state.filter, () => latest === 1);
  latest = 2;
  const newer = page.syncAllTransactions(state.filter, () => latest === 2);
  await pause(0);
  gates[0].open();
  await older;
  const live = { id: 'live', path: '/a', mono: 1 };
  rows.push(live);
  page.admitLive('transactions', live);
  gates[1].open();
  await newer;
  const times = state.allTransactions.filter((r) => r.id === 'live').length;
  return times === 1
    ? `yes (a live row arriving after an overtaken read landed is in the newer read's copy once, ${state.allTransactions.length} rows)`
    : `NO - the newer read's copy holds the live row ${times} time(s); the overtaken read closed its buffer`;
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
    ...(await checkPastTheClamp(sources)),
    ['unfiltered then filter', await checkUnfilteredThenFilter(sources)],
    ['no-filter count', await checkNoFilterCount(sources)],
    ...(await checkMidRead(sources)),
    ...(await checkMidReadEmpty(sources)),
    ['mid-read overtaken kept', await checkMidReadOvertaken(sources)],
  ];
}

module.exports = { checkSessionState, clampPage, runFile };
