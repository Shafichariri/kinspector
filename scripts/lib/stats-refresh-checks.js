/* The Stats page's live refresh, against a daemon that answers late.
 *
 * A live call or marker owes the Stats page a fresh table (`oweStats`, `statsRefresh` in
 * `js/95-stats.js`). These hold what `render-web-ui.js` cannot time: how many requests a burst of
 * 100 rows costs, that no second request goes out while one is held, that nothing is asked while live
 * tail is paused or the page is not shown, and that an answer for a filter, sort or session no longer
 * in force is not drawn. Each runs the real file in its own context, as `live-reread-checks.js` runs
 * `15-session-data.js`, with a stats route that holds a request until told to answer. Drawing is
 * replaced after the file loads, so a check counts what would have been drawn.
 *
 * Zero dependencies, like the rest of `check-web-static.js`.
 */
const vm = require('vm');
const { runFile } = require('./session-state-checks');
const { HarnessError, liftBlock } = require('./web-audits');

const FILE = 'js/95-stats.js';
const pause = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * The stats route: answers `{ filter, sort, sessionId }` as asked, and holds the next request while
 * `holding` is set, until `release()`. `asked` lists every query; `flight.most` is the most out at once.
 */
function statsRoute() {
  const asked = [];
  const flight = { now: 0, most: 0 };
  const held = [];
  const route = { holding: false, asked, flight, release: () => held.splice(0).forEach((go) => go()) };
  route.api = async (path) => {
    const url = new URL(path, 'http://stub.invalid');
    const sessionId = decodeURIComponent(url.pathname.split('/')[3]);
    asked.push(`${sessionId}|${url.searchParams.get('filter')}|${url.searchParams.get('sort')}`);
    flight.most = Math.max(flight.most, ++flight.now);
    if (route.holding) await new Promise((go) => held.push(go));
    flight.now--;
    return { sessionId, filter: url.searchParams.get('filter'), sort: url.searchParams.get('sort'), total: 1, matched: 1, routeCount: 0, routes: [] };
  };
  return route;
}

/** The stats file over `state`, shown or not, with `statsRender` counting the draws. */
function page(sources, route, state, { shown = true } = {}) {
  const context = runFile(sources, FILE, {
    state, api: route.api, ARCHIVE: null, clearTimeout, currentPage: () => (shown ? 'stats' : 'traffic'),
  });
  for (const name of ['oweStats', 'statsRefresh', 'statsLoad', 'statsRender']) {
    if (typeof context[name] !== 'function') throw new HarnessError(`${name} is not declared in ${FILE}`);
  }
  const draws = [];
  const stats = vm.runInContext('statsState', context);
  context.statsRender = () => draws.push(stats.report);
  return { context, stats, draws };
}

const live = () => ({ sessionId: 'S', sessionsLoaded: true, liveTail: true, filter: '' });

/** 100 rows over a little more than a second, none held: at most two requests. */
async function checkBurst(sources) {
  const route = statsRoute();
  const { context } = page(sources, route, live());
  const started = Date.now();
  for (let n = 0; n < 100; n++) {
    context.oweStats();
    await pause(11);
  }
  const spent = Date.now() - started;
  await pause(2300);
  return route.asked.length >= 1 && route.asked.length <= 2
    ? `yes (100 owed rows over ${spent} ms cost ${route.asked.length} requests)`
    : `NO - 100 owed rows over ${spent} ms cost ${route.asked.length} requests; at most 2 were due`;
}

/** A request held for 2.5 s while more rows are owed: one out at a time, and one more once it lands. */
async function checkOneFlight(sources) {
  const route = statsRoute();
  const { context } = page(sources, route, live());
  route.holding = true;
  context.oweStats();
  for (let waited = 0; route.asked.length === 0 && waited < 3000; waited += 50) await pause(50);
  for (let n = 0; n < 30; n++) {
    context.oweStats();
    await pause(80);
  }
  route.holding = false;
  route.release();
  await pause(1500);
  return route.flight.most === 1 && route.asked.length === 2
    ? `yes (rows owed during a held request sent ${route.asked.length - 1} more, after it landed; ${route.flight.most} out at once)`
    : `NO - ${route.flight.most} out at once, ${route.asked.length} requests; one at a time, and one more after the held one, were due`;
}

/** Rows owed while paused, or while the page is not shown, ask nothing. */
async function checkQuiet(sources, why) {
  const route = statsRoute();
  const state = { ...live(), liveTail: why !== 'paused' };
  const { context } = page(sources, route, state, { shown: why !== 'hidden' });
  for (let n = 0; n < 20; n++) context.oweStats();
  await pause(1400);
  const what = why === 'paused' ? 'with live tail paused' : 'with the page not shown';
  return route.asked.length === 0
    ? `yes (20 rows owed ${what} sent no request)`
    : `NO - 20 rows owed ${what} sent ${route.asked.length} request(s)`;
}

/**
 * A read answering after the filter, the sort or the session changed under it is not drawn, and one
 * that still stands is: the last case keeps the check from passing on a page that draws nothing.
 */
async function checkStaleDropped(sources) {
  const changes = {
    filter: (stats) => { stats.filter = 'status>=500'; },
    sort: (stats) => { stats.sort = 'p90'; },
    session: (stats, state) => { state.sessionId = 'T'; },
  };
  const wrong = [];
  for (const [name, change] of Object.entries(changes)) {
    const route = statsRoute();
    const state = live();
    const { context, stats, draws } = page(sources, route, state);
    route.holding = true;
    const read = context.statsLoad();
    await pause(0);
    change(stats, state);
    route.release();
    await read;
    if (draws.length || stats.report) wrong.push(`an answer from before the ${name} changed was drawn`);
  }
  const route = statsRoute();
  const { context, stats, draws } = page(sources, route, live());
  await context.statsLoad();
  if (draws.length !== 1 || !stats.report) wrong.push('an answer that still stood was not drawn');
  return wrong.length
    ? `NO - ${wrong.join('; ')}`
    : 'yes (answers from before a filter, sort or session change were dropped; one that stood was drawn)';
}

/**
 * A route row's server, `statsOrigin`, is named by the `urlOf` block's `hostPortOf` rather than a
 * rule of its own. It joined host and port by hand and read an IPv6 server as `http://::1:8080`,
 * which is not a URL and does not say where the address ends. The real file runs over the lifted
 * block, so a stats file with its own join again fails here even while `urlOf`'s cases pass.
 */
function checkStatsOrigin(sources) {
  const context = runFile(sources, FILE, { state: {}, api: null, ARCHIVE: null, clearTimeout, currentPage: () => 'stats' });
  vm.runInContext(liftBlock(sources, 'urlOf'), context);
  const statsOrigin = vm.runInContext('typeof statsOrigin === "function" ? statsOrigin : null', context);
  if (!statsOrigin) throw new HarnessError(`statsOrigin is not declared in ${FILE}`);
  // Route rows carry `port` only when it is not the scheme's default.
  const cases = [
    [{ scheme: 'http', host: '::1', port: 8080 }, 'http://[::1]:8080'],
    [{ scheme: 'https', host: '[::1]', port: null }, 'https://[::1]'],
    [{ scheme: 'http', host: '127.0.0.1', port: 9090 }, 'http://127.0.0.1:9090'],
    [{ scheme: 'https', host: 'api.example.invalid', port: null }, 'https://api.example.invalid'],
  ];
  const wrong = cases.filter(([row, want]) => statsOrigin(row) !== want).map(([row, want]) => `${statsOrigin(row)} for ${want}`);
  return wrong.length ? `NO - ${wrong.join(', ')}` : `yes (${cases.length} origins, IPv6 bracketed once)`;
}

/**
 * The pieces `statsBreakable` cuts a route cell's server and path into, which the narrow stylesheet
 * keeps whole with a line break allowed only between them. Chrome's `stats 400 route breaks` reads
 * where the lines fell, but only on what was long enough to wrap; this holds the cut itself: after a
 * run of `.`, `:` or `/`, never inside `[::1]`, and the path only at a `/`. `el` is stubbed, so the
 * pieces are read as text with a `|` for each `<wbr>`.
 */
function checkStatsPieces(sources) {
  const el = (tag, cls, text) => {
    const node = { tag, text: text || '', kids: [] };
    node.appendChild = (kid) => node.kids.push(kid);
    node.append = (...kids) => node.kids.push(...kids);
    return node;
  };
  const context = runFile(sources, FILE, { state: {}, api: null, ARCHIVE: null, clearTimeout, el, currentPage: () => 'stats' });
  const breakable = vm.runInContext('typeof statsBreakable === "function" ? statsBreakable : null', context);
  if (!breakable) throw new HarnessError(`statsBreakable is not declared in ${FILE}`);
  const read = (node) => node.kids.map((k) => (k.tag === 'wbr' ? '|' : k.text)).join('');
  const cases = [
    ['http://[::1]:8080', '.:/', 'http://|[::1]:|8080'],
    ['https://api.qa.example.invalid', '.:/', 'https://|api.|qa.|example.|invalid'],
    ['http://127.0.0.1:8080', '.:/', 'http://|127.|0.|0.|1:|8080'],
    ['/v3/files/{id}/report.pdf', '/', '/v3/|files/|{id}/|report.pdf'],
  ];
  const wrong = cases.filter(([text, at, want]) => read(breakable('x', text, at)) !== want)
    .map(([text, at, want]) => `${read(breakable('x', text, at))} for ${want}`);
  return wrong.length ? `NO - ${wrong.join(', ')}` : `yes (${cases.length} routes cut only after . : /, [::1] whole)`;
}

async function checkStatsRefresh(sources) {
  return [
    ['stats refresh burst', await checkBurst(sources)],
    ['stats refresh one flight', await checkOneFlight(sources)],
    ['stats refresh paused', await checkQuiet(sources, 'paused')],
    ['stats refresh hidden', await checkQuiet(sources, 'hidden')],
    ['stats refresh stale dropped', await checkStaleDropped(sources)],
    ['stats origin', checkStatsOrigin(sources)],
    ['stats route pieces', checkStatsPieces(sources)],
  ];
}

module.exports = { checkStatsRefresh };
