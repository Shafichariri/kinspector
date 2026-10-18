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

/** Runs `file` from the manifest in a fresh context holding `globals`; returns the context. */
function runFile(sources, file, globals) {
  const source = sources.find((s) => s.file === file);
  if (!source) throw new HarnessError(`${file} is not in web/scripts.txt; update session-state-checks.js`);
  const context = vm.createContext({ URL, URLSearchParams, encodeURIComponent, console, setTimeout, ...globals });
  new vm.Script(source.text, { filename: file }).runInContext(context);
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
    await behave(filter);
    const want = filter.startsWith('tag:') ? rows.filter((r) => r.tag === filter.slice(4)) : filter ? [] : rows;
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
  const element = { hidden: false, textContent: '' };
  const page = runFile(sources, 'js/15-session-data.js', {
    state, api, ARCHIVE: null, tagOf: (s) => s.tag,
    availableViews: () => [{ id: 'network' }], visibleTransactions: () => state.transactions,
    renderTabs() {}, renderCurrent() {}, applyView() {}, renderSessionGlance() {}, renderListEmpty() {},
    showFilterError() {}, renderMarkers() {}, markerFormState() {}, renderList() {}, renderTimeline() {},
    $: () => element,
  });
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

async function checkSessionState(sources) {
  return [
    ['last capture whole', checkLastCapture(sources)],
    ['whole read failure', await checkWholeSignalsFailure(sources)],
    ['older signal load dropped', await checkSignalRace(sources)],
    ['older call load dropped', await checkCallRace(sources)],
  ];
}

module.exports = { checkSessionState };
