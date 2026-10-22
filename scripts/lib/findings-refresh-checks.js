/* The Findings page's live refresh, against a daemon that answers late.
 *
 * A live call owes the Findings page a fresh report (`oweFindings`, `findingsRefresh` in
 * `js/97-findings.js`), by the rule the Stats page keeps (`stats-refresh-checks.js`). These hold
 * what `render-web-ui.js` cannot time: how many requests a burst of 100 calls costs, that no second
 * request goes out while one is held, that nothing is asked while live tail is paused or the page is
 * not shown, and that an answer for a session no longer selected is not drawn. Each runs the real
 * file in its own context with a findings route that holds a request until told to answer, and
 * drawing is replaced after the file loads, so a check counts what would have been drawn. "Not shown"
 * is the Sessions page: Traffic draws the report too, as row badges, and asks as the page does.
 *
 * Zero dependencies, like the rest of `check-web-static.js`.
 */
const vm = require('vm');
const { runFile } = require('./session-state-checks');
const { HarnessError } = require('./web-audits');

const FILE = 'js/97-findings.js';
const pause = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * The findings route: answers an empty report for the session asked, and holds the next request
 * while `holding` is set, until `release()`. `asked` lists every session asked; `flight.most` is the
 * most out at once.
 */
function findingsRoute() {
  const asked = [];
  const flight = { now: 0, most: 0 };
  const held = [];
  const route = { holding: false, asked, flight, release: () => held.splice(0).forEach((go) => go()) };
  route.api = async (path) => {
    const url = new URL(path, 'http://stub.invalid');
    const sessionId = decodeURIComponent(url.pathname.split('/')[3]);
    asked.push(`${sessionId}|${url.searchParams.get('limit')}`);
    flight.most = Math.max(flight.most, ++flight.now);
    if (route.holding) await new Promise((go) => held.push(go));
    flight.now--;
    return { sessionId, ruleVersion: '00000000', total: 1, findingCount: 0, high: 0, medium: 0, info: 0, rules: [], findings: [] };
  };
  return route;
}

/** The findings file over `state` on [shown]'s page, or Sessions, with `findingsRender` counting the draws. */
function page(sources, route, state, { shown = 'findings' } = {}) {
  const context = runFile(sources, FILE, {
    state, api: route.api, ARCHIVE: null, clearTimeout, currentPage: () => shown || 'sessions',
  });
  for (const name of ['oweFindings', 'findingsRefresh', 'findingsLoad', 'findingsRender', 'findingsApply']) {
    if (typeof context[name] !== 'function') throw new HarnessError(`${name} is not declared in ${FILE}`);
  }
  const draws = [];
  const findings = vm.runInContext('findingsState', context);
  context.findingsRender = () => draws.push(findings.report);
  context.findingsApply = () => {};   // Traffic's badges and detail section, not timed here
  return { context, findings, draws };
}

const live = () => ({ sessionId: 'S', sessionsLoaded: true, liveTail: true });

/** 100 calls over a little more than a second, none held: at most two requests. */
async function checkBurst(sources) {
  const route = findingsRoute();
  const { context } = page(sources, route, live());
  const started = Date.now();
  for (let n = 0; n < 100; n++) {
    context.oweFindings();
    await pause(11);
  }
  const spent = Date.now() - started;
  await pause(2300);
  return route.asked.length >= 1 && route.asked.length <= 2
    ? `yes (100 owed calls over ${spent} ms cost ${route.asked.length} requests)`
    : `NO - 100 owed calls over ${spent} ms cost ${route.asked.length} requests; at most 2 were due`;
}

/** A request held for 2.5 s while more calls are owed: one out at a time, and one more once it lands. */
async function checkOneFlight(sources) {
  const route = findingsRoute();
  const { context } = page(sources, route, live());
  route.holding = true;
  context.oweFindings();
  for (let waited = 0; route.asked.length === 0 && waited < 3000; waited += 50) await pause(50);
  for (let n = 0; n < 30; n++) {
    context.oweFindings();
    await pause(80);
  }
  route.holding = false;
  route.release();
  await pause(1500);
  return route.flight.most === 1 && route.asked.length === 2
    ? `yes (calls owed during a held request sent ${route.asked.length - 1} more, after it landed; ${route.flight.most} out at once)`
    : `NO - ${route.flight.most} out at once, ${route.asked.length} requests; one at a time, and one more after the held one, were due`;
}

/** Calls owed while paused, or while the page is not shown, ask nothing. */
async function checkQuiet(sources, why) {
  const route = findingsRoute();
  const state = { ...live(), liveTail: why !== 'paused' };
  const { context } = page(sources, route, state, { shown: why === 'hidden' ? null : 'findings' });
  for (let n = 0; n < 20; n++) context.oweFindings();
  await pause(1400);
  const what = why === 'paused' ? 'with live tail paused' : 'with the page not shown';
  return route.asked.length === 0
    ? `yes (20 calls owed ${what} sent no request)`
    : `NO - 20 calls owed ${what} sent ${route.asked.length} request(s)`;
}

/**
 * Calls owed on Traffic ask once: its rows carry the findings as badges, and its detail has a section
 * of them, so a live call there owes the report as it does on the Findings page.
 */
async function checkTraffic(sources) {
  const route = findingsRoute();
  const { context } = page(sources, route, live(), { shown: 'traffic' });
  for (let n = 0; n < 20; n++) context.oweFindings();
  await pause(1400);
  return route.asked.length === 1
    ? 'yes (20 calls owed on Traffic sent 1 request)'
    : `NO - 20 calls owed on Traffic sent ${route.asked.length} request(s); 1 was due`;
}

/**
 * A read answering after the session changed under it is not drawn, nor a refresh answering after
 * live tail was paused, and one that still stands is: the last keeps the check from passing on a page
 * that draws nothing. The read asks for every finding the daemon will send, 5000.
 */
async function checkStaleDropped(sources) {
  const wrong = [];
  for (const [name, change, read] of [
    ['the session changed', (state) => { state.sessionId = 'T'; }, (context) => context.findingsLoad()],
    ['live tail was paused', (state) => { state.liveTail = false; }, (context) => vm.runInContext('findingsRead(true)', context)],
  ]) {
    const route = findingsRoute();
    const state = live();
    const { context, findings, draws } = page(sources, route, state);
    route.holding = true;
    const pending = read(context);
    await pause(0);
    change(state);
    route.release();
    await pending;
    if (draws.length || findings.report) wrong.push(`an answer from before ${name} was drawn`);
  }
  const route = findingsRoute();
  const { context, findings, draws } = page(sources, route, live());
  await context.findingsLoad();
  if (draws.length !== 1 || !findings.report) wrong.push('an answer that still stood was not drawn');
  if (route.asked[0] !== 'S|5000') wrong.push(`asked ${route.asked[0]}, where S|5000 was due`);
  return wrong.length
    ? `NO - ${wrong.join('; ')}`
    : 'yes (answers from before a session change or a pause were dropped; one that stood, asking for 5000, was drawn)';
}

async function checkFindingsRefresh(sources) {
  return [
    ['findings refresh burst', await checkBurst(sources)],
    ['findings refresh one flight', await checkOneFlight(sources)],
    ['findings refresh paused', await checkQuiet(sources, 'paused')],
    ['findings refresh hidden', await checkQuiet(sources, 'hidden')],
    ['findings refresh traffic', await checkTraffic(sources)],
    ['findings refresh stale dropped', await checkStaleDropped(sources)],
  ];
}

module.exports = { checkFindingsRefresh };
