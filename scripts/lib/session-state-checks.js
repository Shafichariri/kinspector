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

async function checkSessionState(sources) {
  return [
    ['last capture whole', checkLastCapture(sources)],
    ['whole read failure', await checkWholeSignalsFailure(sources)],
  ];
}

module.exports = { checkSessionState };
