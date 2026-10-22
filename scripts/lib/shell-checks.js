/* The shell's rules that need no page: the address parser, the two pages' selection presets, and
 * the one place the page writes the control header.
 *
 * Each runs the real file from `web/scripts.txt` with `runFile`, over stubs, and calls the pure
 * function it declares: `parseRoute` and `routeHash` (js/08-routes.js), `sessionPresets`
 * (js/90-sessions.js) and `peerPresets` (js/85-processes.js). See docs/decisions/web-ui.md.
 *
 * Zero dependencies, like the rest of `check-web-static.js`.
 */
const { HarnessError } = require('./web-audits');
const { runFile } = require('./session-state-checks');

const declared = (sources, file, name) => {
  const context = runFile(sources, file, {});
  if (typeof context[name] !== 'function') throw new HarnessError(`${name} is not declared in ${file}`);
  return context;
};
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/**
 * Nineteen addresses: each page, a view, an encoded tag and session, an unknown page, a call's own
 * page with and without an id, Stats with and without a session, Findings with and without the
 * finding it marks, an `id` on a page that takes none, and round trips.
 */
function checkRouteParse(sources) {
  const { parseRoute, routeHash } = declared(sources, 'js/08-routes.js', 'parseRoute');
  const traffic = (view = null, session = null, known = true) => ({ page: 'traffic', view, session, known });
  const cases = [
    ['', traffic()],
    ['#/', traffic()],
    ['#/traffic', traffic()],
    ['#/traffic/all', traffic('all')],
    ['#/traffic/cache?session=a%2Fb', traffic('cache', 'a/b')],
    ['#/sessions', { page: 'sessions', view: null, session: null, known: true }],
    ['#/nope', traffic(null, null, false)],
    ['#/settings?session=x', { page: 'settings', view: null, session: 'x', known: true }],
    ['#/traffic/my%20tag', traffic('my tag')],
    ['#/processes/', { page: 'processes', view: null, session: null, known: true }],
    // One call on the whole page; its id is decoded as a view is, and with no id it names nothing.
    ['#/txn/t-42', { page: 'txn', view: null, session: null, known: true, txn: 't-42' }],
    ['#/txn/a%2Fb?session=s1', { page: 'txn', view: null, session: 's1', known: true, txn: 'a/b' }],
    ['#/txn', traffic(null, null, false)],
    ['#/stats', { page: 'stats', view: null, session: null, known: true }],
    ['#/stats?session=a%2Fb', { page: 'stats', view: null, session: 'a/b', known: true }],
    // `#/findings?id=` marks one finding; no other page reads an `id`.
    ['#/findings', { page: 'findings', view: null, session: null, known: true, id: null }],
    ['#/findings?id=x', { page: 'findings', view: null, session: null, known: true, id: 'x' }],
    ['#/findings?id=x&session=y', { page: 'findings', view: null, session: 'y', known: true, id: 'x' }],
    ['#/stats?id=x', { page: 'stats', view: null, session: null, known: true }],
  ];
  const wrong = cases.filter(([hash, due]) => !same({ ...parseRoute(hash) }, due))
    .map(([hash, due]) => `${hash || "''"} -> ${JSON.stringify(parseRoute(hash))}, where ${JSON.stringify(due)} was due`);
  const trips = ['#/traffic', '#/traffic/all', '#/traffic/cache?session=a%2Fb', '#/sessions', '#/settings?session=x', '#/traffic/my%20tag',
    '#/txn/t-42?session=s1', '#/txn/a%2Fb', '#/stats', '#/stats?session=a%2Fb', '#/findings', '#/findings?id=x',
    '#/findings?id=a%2Fb&session=y'];
  const broken = trips.filter((hash) => routeHash(parseRoute(hash)) !== hash)
    .map((hash) => `${hash} came back ${routeHash(parseRoute(hash))}`);
  const all = [...wrong, ...broken];
  return all.length ? `NO - ${all.join('; ')}` : `yes (${cases.length} addresses and a round trip of ${trips.length})`;
}

/** Six sessions, two of them recording, one tied with another on `startedAt`, in two orders. */
function checkSessionPresets(sources) {
  const { sessionPresets } = declared(sources, 'js/90-sessions.js', 'sessionPresets');
  const ended = '2026-01-10T00:00:00Z';
  const sessions = [
    { sessionId: 's1', startedAt: '2026-01-09T00:00:00Z', endedAt: ended },
    { sessionId: 's2', startedAt: '2026-01-08T00:00:00Z', endedAt: ended },
    { sessionId: 's3', startedAt: '2026-01-05T00:00:00Z', endedAt: ended },
    { sessionId: 's4', startedAt: '2026-01-02T00:00:00Z' },
    { sessionId: 's5', startedAt: '2026-01-01T00:00:00Z', endedAt: ended },
    { sessionId: 's6', startedAt: '2026-01-01T00:00:00Z', endedAt: ended },
  ];
  // s1 has an app attached right now; s4's writer never closed it. Neither may ever be selected.
  const recording = ['s1'];
  const now = Date.parse('2026-01-10T00:00:00Z');
  const cases = [
    [{ keepLast: 2, olderThanDays: 7, now }, { keepLast: ['s3', 's6', 's5'], olderThan: ['s6', 's5'] }],
    [{ keepLast: 0, olderThanDays: 0, now }, { keepLast: ['s2', 's3', 's6', 's5'], olderThan: ['s2', 's3', 's6', 's5'] }],
    [{ keepLast: 10, olderThanDays: 30, now }, { keepLast: [], olderThan: [] }],
  ];
  const wrong = [];
  for (const order of [sessions, sessions.slice().reverse()]) {
    for (const [options, due] of cases) {
      const got = sessionPresets(order, recording, options);
      if (!same(got, due)) wrong.push(`${JSON.stringify(options).replace(/,"now":\d+/, '')} -> ${JSON.stringify(got)}, where ${JSON.stringify(due)} was due`);
    }
  }
  return wrong.length ? `NO - ${wrong.join('; ')}`
    : `yes (${cases.length} presets over 6 sessions in two orders; recording never selected, the tie broken by id)`;
}

/** Six processes: this daemon, two servers, an MCP server, a `sessions` command and an older server. */
function checkPeerPresets(sources) {
  const { peerPresets } = declared(sources, 'js/85-processes.js', 'peerPresets');
  const peers = [
    { pid: 1, role: 'serve', startedEpochMs: 1000, self: true },
    { pid: 2, role: 'serve', startedEpochMs: 2000, self: false },
    { pid: 3, role: 'serve', startedEpochMs: 3000, self: false },
    { pid: 4, role: 'mcp', startedEpochMs: 500, self: false },
    { pid: 5, role: 'sessions', startedEpochMs: 1500, self: false },
    { pid: 6, role: 'serve', startedEpochMs: 100, self: false },
  ];
  const due = { servers: [2, 3, 6], others: [2, 3, 5, 6], stale: [6] };
  const got = peerPresets(peers);
  const touched = Object.values(got).flat().filter((pid) => pid === 1 || pid === 4);
  return same(got, due) && !touched.length
    ? 'yes (all servers 2,3,6; all except this one 2,3,5,6; stale 6; this daemon and MCP in none)'
    : `NO - ${JSON.stringify(got)}, where ${JSON.stringify(due)} was due`;
}

/**
 * The page writes `X-Inspector-Control` in one place, `controlFetch`, so whatever the daemon later
 * asks of a control request is added once. Counted as text across every file the manifest lists.
 */
function checkControlHeaderOnce(sources) {
  const literal = 'X-Inspector-Control';
  const where = sources.map(({ file, text }) => [file, text.split(literal).length - 1]).filter(([, n]) => n > 0);
  const total = where.reduce((sum, [, n]) => sum + n, 0);
  return total === 1 && where[0][0] === 'js/05-network.js'
    ? 'yes (once, in js/05-network.js)'
    : `NO - ${total} times: ${where.map(([file, n]) => `${file} ${n}`).join(', ') || 'nowhere'}`;
}

function checkShell(sources) {
  return [
    ['route parse', checkRouteParse(sources)],
    ['session presets', checkSessionPresets(sources)],
    ['peer presets', checkPeerPresets(sources)],
    ['control header once', checkControlHeaderOnce(sources)],
  ];
}

module.exports = { checkShell };
