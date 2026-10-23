'use strict';
/*
 * Live traffic through the whole path, in a real browser: a stream client (`lib/ingest-client.js`)
 * puts rows into a real daemon's `/ingest`, the daemon archives and broadcasts them on `/api/live`,
 * and the served page draws them and re-reads its filtered rows. Run by scripts/measure-web-ui.js
 * as `--only traffic`, against a daemon of its own with an empty data directory; rows are in the
 * `{ key, value, ok, detail }` shape `lib/measure-cases.js` describes.
 *
 * The committed checks each held a piece of this. `lib/live-reread-checks.js` runs the re-read's
 * races and burst against stubbed routes, with no browser and no daemon; `liveArrival` hands
 * messages to the page's own `onmessage`; `lib/live-filter-probes.js` fakes an arrival by wrapping
 * `fetch`. Before this, only a scratch driver of QA's ran ingest, broadcast, page and re-read
 * together, so what `docs/STATUS.md` listed as verified there had no check behind it.
 *
 * Judged (`docs/decisions/web-ui.md`, under "Under a filter a live row waits for the daemon"):
 *
 *   traffic burst reads         a burst of 50 calls and 50 signals under a filter, lasting T ms, costs
 *                               at most ⌈T/1000⌉ + 1 re-reads (two inside a second), each
 *                               `1 + ⌈S/1000⌉ + 1` requests, S the signals the filter matches
 *   traffic burst counts        ... and once settled the page holds what the daemon matched
 *   traffic filter edit race, … a re-read sent for the old filter, in flight across an edit, never
 *                               reaches the list, whether it lands after the edit's own read or before
 *   traffic session switch race, … the same across a session switch
 *   traffic session switch window while the switch's first page is held, the list draws none of
 *                               the session left's rows and says the session is being read, the
 *                               endpoints button is hidden and no tab and no header counts anything
 *   traffic signal burst        100 signals with no filter, arriving over T ms, cost at most
 *                               ⌈T/250⌉ + 1 calls each of the timeline, the waterfall and the path
 *                               renderers and as many `/current` reads (`drawLive`), every one drawn
 *   traffic marker burst        50 markers, the same bound for the timeline and the path
 *   traffic call burst          50 calls, the same bound for the timeline, the waterfall and the path
 *   traffic lone signal drawn, … one signal or marker is on the timeline within the live redraw
 *   traffic lone call waterfall, … one call is on the waterfall, and one on the path, within it
 *   traffic paused still, other still   a signal or marker while paused, or for another session,
 *                               calls no renderer and reads no `/current`
 *   traffic hosts               every call the daemon archived has a host ending in `.invalid`
 *   traffic reaches             every request and socket the page opened was to 127.0.0.1
 *   traffic exceptions          the page threw nothing throughout
 *
 * Each race holds the old re-read's request in the page (`window.__delay`) across the edit or the
 * switch, in both orders (`race`), because each order is held by a different guard. A race in which
 * no held re-read was seen fails rather than passing with nothing tested.
 */
const { sleep } = require('./measure-cdp');
const { openApp } = require('./ingest-client');

const FILTER = 'status>=500 | tag:state';
const EDITED = 'path:/cart';
const HOLD_MS = 2500;

// Installed in every document before the page's scripts. It records each request the page makes
// in `window.__asked`, which the cases clear between readings, and every origin asked in
// `window.__origins`, which nothing clears: a first draft read the origins from `__asked` and so
// missed every request made before the last race. It holds a request for `window.__delay(url)`
// milliseconds when that is set, which the races use to choose the order two answers land in.
// The page calls `fetch` by its global name at call time (`source.fetch`), so this sees every read.
const RECORDER = `(() => {
  window.__asked = [];
  window.__origins = [];
  window.__delay = null;
  const real = window.fetch.bind(window);
  window.fetch = async (input, init) => {
    const url = new URL(typeof input === 'string' ? input : input.url, location.href);
    const entry = { origin: url.origin, path: url.pathname, filter: url.searchParams.get('filter'), at: Date.now(), held: 0 };
    window.__asked.push(entry);
    if (!window.__origins.includes(url.origin)) window.__origins.push(url.origin);
    const ms = window.__delay ? window.__delay(url) : 0;
    if (ms) { entry.held = ms; await new Promise((resolve) => setTimeout(resolve, ms)); }
    return real(input, init);
  };
})()`;

// The routes a load and a re-read read the filtered rows through. Detail rows and payloads are
// other paths, so they are not counted against the burst.
const LIST_READ = String.raw`/\/api\/sessions\/[^/]+\/(transactions|signals)$/`;
const LIVE_OPEN = `(window.__sockets || []).some((s) => s.url.includes('/api/live') && s.readyState === 1)`;

async function daemon(origin, route, params) {
  const res = await fetch(`${origin.replace(/\/$/, '')}${route}?${new URLSearchParams(params)}`);
  if (!res.ok) throw new Error(`${route} answered ${res.status}`);
  return res.json();
}

const calls = (origin, sessionId, filter) =>
  daemon(origin, `/api/sessions/${encodeURIComponent(sessionId)}/transactions`, { filter, limit: '1000' });
const signals = (origin, sessionId, filter) =>
  daemon(origin, `/api/sessions/${encodeURIComponent(sessionId)}/signals`, { filter, limit: '1000' });

// The daemon stores a row before it broadcasts it, but the client's frames are fire-and-forget, so
// a case that compares against the daemon first waits until the daemon holds every row sent.
async function stored(origin, sessionId, total, timeoutMs = 15000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if ((await calls(origin, sessionId, '')).total >= total) return;
    await sleep(100);
  }
  throw new Error(`the daemon did not store ${total} calls for ${sessionId} within ${timeoutMs} ms`);
}

async function setFilter(page, text) {
  await page.ev(`(() => { const f = document.getElementById('filter'); f.value = ${JSON.stringify(text)};
    f.dispatchEvent(new Event('input', { bubbles: true })); })()`);
  if (await page.waitFor(`state.filter === ${JSON.stringify(text.trim())}`, 5000) === null) {
    throw new Error(`the page never took the filter ${JSON.stringify(text)}`);
  }
  await page.waitFor(`!state.reread.busy && state.reread.timer === null`, 5000);
  await sleep(800); // the load the edit fired, and its whole-session and signal reads
}

// The page's filtered calls against the daemon's answer for the same session and filter.
async function agreesWithDaemon(page, origin) {
  const shown = await page.ev(`({ session: state.sessionId, filter: state.filter,
    ids: state.transactions.map((t) => t.id).sort(), matched: state.matched })`);
  const answer = await calls(origin, shown.session, shown.filter);
  const want = answer.items.map((t) => t.id).sort();
  const same = shown.ids.length === want.length && shown.ids.every((id, i) => id === want[i]) && shown.matched === answer.matched;
  return { shown, want, same };
}

const callRoute = (sessionId) => `/api/sessions/${encodeURIComponent(sessionId)}/transactions`;

/**
 * One race: a matching call owes a re-read under FILTER, the page holds that re-read in flight, and
 * `act` edits the filter or switches the session meanwhile. The read `act` fires is held too, for
 * `newHoldMs`: 0 lets it land first, so the old re-read lands last and both its guards stand in its
 * way (the ticket, `readSent`/`readWritten`, and `stands()`); longer than HOLD_MS lets the old one
 * land first, where only `stands()` does. The held call matches only FILTER on the old session,
 * so it must never reach the list: a watcher in the page records it if it ever does, even for the
 * moment before a later load overwrites it.
 */
async function race(page, origin, app, { key, newRoute, newFilter, newHoldMs, act, expect }) {
  const oldRoute = callRoute(app.sessionId);
  await page.ev(`window.__asked.length = 0; window.__stale = false; window.__delay = (u) => {
    const filter = u.searchParams.get('filter');
    if (u.pathname === ${JSON.stringify(oldRoute)} && filter === ${JSON.stringify(FILTER)}) return ${HOLD_MS};
    if (u.pathname === ${JSON.stringify(newRoute)} && filter === ${JSON.stringify(newFilter)}) return ${newHoldMs};
    return 0;
  }`);
  const held = app.call('/pay', 500);
  await page.ev(`window.__watch = setInterval(() => {
    if (state.transactions.some((t) => t.id === ${JSON.stringify(held)})) window.__stale = true; }, 20)`);
  try {
    if (await page.waitFor(`window.__asked.some((e) => e.held === ${HOLD_MS})`, 4000) === null) {
      return { key, value: 'no re-read held', ok: false, detail: 'nothing was tested' };
    }
    const acted = Date.now();
    await act();
    await sleep(Math.max(0, acted + Math.max(HOLD_MS, newHoldMs) + 600 - Date.now()));
    await page.waitFor(`!state.reread.busy`, 5000);
  } finally {
    await page.ev('clearInterval(window.__watch); window.__delay = null');
  }
  const stale = await page.ev('window.__stale');
  const { shown, want, same } = await agreesWithDaemon(page, origin);
  const ok = !stale && same && expect(shown);
  return { key, value: ok ? 'old dropped' : 'old landed', ok,
    detail: `${stale ? 'the held call reached the list; ' : ''}${shown.session} ${JSON.stringify(shown.filter)}: page ${shown.ids.length} rows, daemon ${want.length}` };
}

const LATE_MS = HOLD_MS + 1500;

async function filterEditRaces(page, origin, app) {
  const rows = [];
  for (const [order, newHoldMs] of [['old lands last', 0], ['old lands first', LATE_MS]]) {
    await setFilter(page, FILTER);
    rows.push(await race(page, origin, app, {
      key: `traffic filter edit race, ${order}`, newRoute: callRoute(app.sessionId), newFilter: EDITED, newHoldMs,
      act: () => setFilter(page, EDITED), expect: (shown) => shown.filter === EDITED,
    }));
  }
  return rows;
}

async function switchTo(page, sessionId) {
  await page.ev(`(() => { const p = document.getElementById('session-picker'); p.value = ${JSON.stringify(sessionId)};
    p.dispatchEvent(new Event('change', { bubbles: true })); })()`);
}

// What the switch window reads of the page: the list's rows and empty line, the wording the page
// says it with, the endpoints button and chips, the tab counts and the header.
const WINDOW_SAMPLE = `(() => { const empty = document.getElementById('list-empty');
  return { rows: [...document.querySelectorAll('#list .row')].map((r) => r.dataset.id),
    empty: empty.hidden ? null : empty.textContent.trim(),
    text: typeof beingRead === 'function' ? beingRead() : null,
    endpoints: !document.getElementById('endpoints-button').hidden,
    chips: document.querySelectorAll('#endpoint-chips .chip').length,
    tabCounts: document.querySelectorAll('#tabs .tab-count').length,
    counts: document.getElementById('counts').textContent.trim() }; })()`;

/**
 * `traffic session switch window`: the switch to [other] made inside the `old lands first` race,
 * whose own first page is held LATE_MS, read while that page is held. Read on [main] first, where
 * the list, the chips and a tab count must be drawn, or the reading checked nothing.
 */
async function switchWindow(page, origin, main, other) {
  const key = 'traffic session switch window';
  const ids = new Set((await calls(origin, main, '')).items.map((t) => t.id));
  const before = await page.ev(WINDOW_SAMPLE);
  await switchTo(page, other);
  const held = await page.waitFor(`state.sessionId === ${JSON.stringify(other)}
    && window.__asked.some((e) => e.path === ${JSON.stringify(callRoute(other))} && e.held === ${LATE_MS})`, 1000);
  const during = await page.ev(WINDOW_SAMPLE);
  if (held === null) return { key, value: 'no read held', ok: false, detail: `the switch to ${other} held no first page` };
  const missing = [!before.rows.some((id) => ids.has(id)) && 'list row', !(before.endpoints && before.chips) && 'endpoint chip',
    !before.tabCounts && 'tab count'].filter(Boolean);
  if (missing.length) return { key, value: `checked nothing (found no ${missing.join(', no ')} on ${main} before the switch)`, ok: false };
  const old = during.rows.filter((id) => ids.has(id)).length;
  if (old) return { key, value: 'old rows', ok: false, detail: `the list drew ${old} of ${main}'s rows while its answer was held` };
  const leaks = [
    !(during.text && /being read/.test(during.text)) && `the page words it "${during.text}"`,
    during.empty !== during.text && `#list-empty reads ${during.empty === null ? 'nothing (hidden)' : `"${during.empty}"`}`,
    during.endpoints && 'the endpoints button is shown', during.chips && `${during.chips} chips`,
    during.tabCounts && `${during.tabCounts} tab counts`, during.counts && `#counts reads "${during.counts}"`,
  ].filter(Boolean);
  if (leaks.length) return { key, value: 'leaked', ok: false, detail: leaks.join('; ') };
  return { key, value: 'being read', ok: true,
    detail: `list, endpoints and ${before.tabCounts} tab counts cleared while ${other}'s first page was held ${LATE_MS} ms` };
}

async function sessionSwitchRaces(page, origin, app, other) {
  const rows = [];
  let reading = { key: 'traffic session switch window', value: 'not taken', ok: false, detail: 'its race never switched' };
  await setFilter(page, FILTER);
  for (const [order, newHoldMs] of [['old lands last', 0], ['old lands first', LATE_MS]]) {
    await switchTo(page, app.sessionId);
    if (await page.waitFor(`state.sessionId === ${JSON.stringify(app.sessionId)} && state.signalSessionId === state.sessionId`, 10000) === null) {
      throw new Error(`the page did not come back to ${app.sessionId}`);
    }
    await sleep(800);
    // The later order holds the switch's own first page, which is the window the reading needs.
    const act = newHoldMs ? async () => { reading = await switchWindow(page, origin, app.sessionId, other); } : () => switchTo(page, other);
    rows.push(await race(page, origin, app, {
      key: `traffic session switch race, ${order}`, newRoute: callRoute(other), newFilter: FILTER, newHoldMs,
      act, expect: (shown) => shown.session === other,
    }));
  }
  rows.push(reading);
  return rows;
}

async function burst(page, origin, app, sent) {
  await page.ev('window.__asked.length = 0');
  const start = Date.now();
  for (let i = 0; i < 50; i++) {
    app.call(i % 10 ? '/feed' : '/pay', i % 10 ? 200 : 500);
    app.signal(i % 2 ? 'state' : 'cache', `burst${i}`);
    sent.calls++;
    await sleep(15);
  }
  const lasted = Date.now() - start;
  await stored(origin, app.sessionId, sent.calls);
  const answer = await calls(origin, app.sessionId, FILTER);
  const matchedSignals = (await signals(origin, app.sessionId, FILTER)).length;
  // Settled: the page agrees with the daemon and owes nothing. Then a while longer, so a re-read
  // fired late still counts against the bound.
  await page.waitFor(`state.matched === ${answer.matched} && state.sessionTotal === ${answer.total}
    && state.signals.length === ${matchedSignals} && !state.reread.busy && state.reread.timer === null`, 15000);
  await sleep(1500);
  const seen = await page.ev(`({ reads: window.__asked.filter((e) => ${LIST_READ}.test(e.path)).length,
    matched: state.matched, total: state.sessionTotal, signals: state.signals.length })`);
  // `docs/decisions/web-ui.md`: one re-read per second of burst plus the one armed as it ends,
  // each one call read and `⌈S/1000⌉ + 1` signal pages.
  const rereads = Math.ceil(lasted / 1000) + 1;
  const bound = rereads * (1 + Math.ceil(matchedSignals / 1000) + 1);
  const within = seen.reads <= bound;
  const agrees = seen.matched === answer.matched && seen.total === answer.total && seen.signals === matchedSignals;
  return [
    { key: 'traffic burst reads', value: within ? 'within bound' : 'over bound', ok: within,
      detail: `${seen.reads} list reads, at most ${bound} (${rereads} re-reads) for 100 rows over ${lasted} ms` },
    { key: 'traffic burst counts', value: agrees ? 'agrees' : 'disagrees', ok: agrees,
      detail: `page ${seen.matched} of ${seen.total} calls, ${seen.signals} signals; daemon ${answer.matched} of ${answer.total}, ${matchedSignals}` },
  ];
}

// Installed once the page is on the session the bursts are sent to. It counts the calls of the three
// renderers a live row redraws, by replacing the page's global functions, which is where the
// page's own calls resolve; and it records each live call, signal or marker as the socket hands it over,
// with its session, so a reading's T is the span the page saw and an empty scan can be told apart.
const DRAW_COUNTER = `(() => {
  window.__draws = { renderTimeline: 0, renderWaterfall: 0, renderPath: 0 };
  for (const name of Object.keys(window.__draws)) {
    const real = window[name];
    window[name] = function (...args) { window.__draws[name]++; return real.apply(this, args); };
  }
  window.__arrived = [];
  const sock = window.__sockets.find((s) => s.url.includes('/api/live') && s.readyState === 1);
  sock.addEventListener('message', (event) => {
    const m = JSON.parse(event.data);
    if (m.type === 'signal' || m.type === 'marker' || m.type === 'txn') window.__arrived.push({ type: m.type, sessionId: m.sessionId, at: performance.now() });
  });
  return true;
})()`;

const RESET = `window.__asked.length = 0; window.__arrived.length = 0;
  for (const k of Object.keys(window.__draws)) window.__draws[k] = 0`;

/** What the page drew and read since RESET, and what arrived for [sessionId]. */
const drawsSince = (page, sessionId) => page.ev(`(() => {
  const mine = window.__arrived.filter((a) => a.sessionId === ${JSON.stringify(sessionId)});
  return { ...window.__draws, current: window.__asked.filter((e) => /\\/current$/.test(e.path)).length,
    arrived: mine.length, span: mine.length ? Math.round(mine[mine.length - 1].at - mine[0].at) : 0,
    elsewhere: window.__arrived.length - mine.length, armed: state.liveDraw.timer !== null };
})()`);

/** Sends [count] rows [every] ms apart, then waits for the page to see all of them and settle. */
async function sendAndSettle(page, sessionId, count, every, send) {
  for (let i = 0; i < count; i++) {
    send(i);
    await sleep(every);
  }
  await page.waitFor(`window.__arrived.filter((a) => a.sessionId === ${JSON.stringify(sessionId)}).length >= ${count}`, 10000);
  // The redraw armed by the last row, and a while longer, so a late one still counts.
  await page.waitFor('state.liveDraw.timer === null', 3000);
  await sleep(600);
}

const burstRow = (key, what, seen, names, extra) => {
  const counts = names.map((n) => seen[n]);
  if (!seen.arrived) {
    return { key, value: 'checked nothing', ok: false,
      detail: `found no ${what} arriving (${seen.elsewhere} for other sessions): ${names.map((n, i) => `${counts[i]} ${n}`).join(', ')}` };
  }
  const bound = Math.ceil(seen.span / 250) + 1;
  const within = counts.every((c) => c <= bound) && !extra.missing;
  return { key, value: within ? 'within bound' : 'over bound', ok: within,
    detail: `${seen.arrived} ${what}s over ${seen.span} ms: ${names.map((n, i) => `${counts[i]} ${n}`).join(', ')}, at most ${bound} each; ${extra.text}` };
};

/**
 * The live redraw (`drawLive`, `docs/decisions/web-ui.md`): signal and marker bursts on the timeline
 * and a call burst on the waterfall, with no filter, a lone row's delay, and nothing moved while
 * paused or for another session. Run on [app]'s session, the one with signals, so the timeline, the
 * waterfall and the path tabs are offered.
 */
async function signalBursts(page, origin, app, other) {
  await switchTo(page, app.sessionId);
  if (await page.waitFor(`state.sessionId === ${JSON.stringify(app.sessionId)} && state.signalSessionId === state.sessionId`, 10000) === null) {
    throw new Error(`the page did not come back to ${app.sessionId}`);
  }
  await setFilter(page, '');
  await page.ev(`document.querySelector('#tabs .tab[data-view="all"]').click()`);
  if (await page.waitFor(`state.view === 'all'`, 3000) === null) throw new Error('the timeline tab did not open');
  await sleep(400);
  await page.ev(DRAW_COUNTER);
  const rows = [];
  const names = ['renderTimeline', 'renderWaterfall', 'renderPath', 'current'];

  // For another session, then paused: nothing called, nothing read.
  for (const [key, sessionId, before, after] of [
    ['traffic other still', other.sessionId, null, null],
    ['traffic paused still', app.sessionId, `document.getElementById('live-btn').click()`, `document.getElementById('live-btn').click()`],
  ]) {
    const sender = sessionId === app.sessionId ? app : other;
    if (before) await page.ev(before);
    await page.ev(RESET);
    await sendAndSettle(page, sessionId, 20, 10, (i) => (i % 4 ? sender.signal('state', `still${i}`) : sender.marker(`still ${i}`)));
    const seen = await drawsSince(page, sessionId);
    if (after) {
      await page.ev(after);
      await page.waitFor(`state.liveTail && !state.reread.busy`, 5000);
      await sleep(1500);
    }
    const moved = names.reduce((n, k) => n + seen[k], 0);
    rows.push(!seen.arrived ? { key, value: 'checked nothing', ok: false, detail: `found no signal or marker arriving for ${sessionId}` }
      : { key, value: moved || seen.armed ? 'moved' : 'still', ok: !moved && !seen.armed,
        detail: `${seen.arrived} signals and markers: ${names.map((n) => `${seen[n]} ${n}`).join(', ')}, redraw armed ${seen.armed}` });
  }

  // A lone signal and a lone marker, each timed from the socket handing it over to its timeline row.
  for (const [key, send, found] of [
    ['traffic lone signal drawn', () => app.signal('state', 'Lone'), (id) => `document.querySelector('#timeline [data-id="${id}"]')`],
    ['traffic lone marker drawn', () => app.marker('lone marker'), () => `[...document.querySelectorAll('#timeline .marker-divider')].some((n) => n.textContent === 'lone marker')`],
  ]) {
    await page.ev(RESET);
    const id = send();
    await page.ev(`window.__lone = null; (function poll() {
      if (${found(id)}) { const a = window.__arrived[0]; window.__lone = a ? Math.round(performance.now() - a.at) : -1; return; }
      setTimeout(poll, 5); })()`);
    const ms = await page.waitFor('window.__lone !== null', 3000) === null ? null : await page.ev('window.__lone');
    rows.push(ms === null || ms < 0 ? { key, value: 'not drawn', ok: false, detail: 'not on the timeline 3 s after it was sent' }
      : { key, value: ms <= 300 ? 'within the redraw' : 'late', ok: ms <= 300, detail: `on the timeline ${ms} ms after it arrived (redraw 250 ms, 50 ms slack)` });
  }

  await page.ev(RESET);
  const ids = [];
  await sendAndSettle(page, app.sessionId, 100, 10, (i) => ids.push(app.signal(i % 2 ? 'state' : 'cache', `Burst${i}`)));
  let seen = await drawsSince(page, app.sessionId);
  const drawn = await page.ev(`${JSON.stringify(ids)}.filter((id) => document.querySelector('#timeline [data-id="' + id + '"]')).length`);
  rows.push(burstRow('traffic signal burst', 'signal', seen, names,
    { missing: seen.arrived && drawn < ids.length, text: `${drawn} of ${ids.length} on the timeline` }));

  await page.ev(RESET);
  await sendAndSettle(page, app.sessionId, 50, 20, (i) => app.marker(`burst ${i}`));
  seen = await drawsSince(page, app.sessionId);
  const dividers = await page.ev(`[...document.querySelectorAll('#timeline .marker-divider')].filter((n) => /^burst \\d+$/.test(n.textContent)).length`);
  rows.push(burstRow('traffic marker burst', 'marker', seen, ['renderTimeline', 'renderPath'],
    { missing: seen.arrived && dividers < 50, text: `${dividers} of 50 on the timeline` }));
  rows.push(...await callBursts(page, app));
  return rows;
}

const openTab = async (page, view) => {
  await page.ev(`document.querySelector('#tabs .tab[data-view="${view}"]').click()`);
  if (await page.waitFor(`state.view === '${view}'`, 3000) === null) throw new Error(`the ${view} tab did not open`);
  await sleep(400);
};

/**
 * A call owes the waterfall and the path to the same redraw: a lone call on each, timed from the
 * socket handing it over to its row there, and a burst of 50 on the waterfall, the pane it draws.
 */
async function callBursts(page, app) {
  const rows = [];
  for (const view of ['waterfall', 'path']) {
    await openTab(page, view);
    await page.ev(RESET);
    // A failure, on an endpoint of its own: the path summarises a busy visit's calls into one count
    // and names only its failures, so a lone 200 has no element there to find.
    const id = app.call(`/lone/${view}`, 500);
    await page.ev(`window.__lone = null; (function poll() {
      if (document.querySelector('#${view} [data-id="${id}"]')) { const a = window.__arrived[0]; window.__lone = a ? Math.round(performance.now() - a.at) : -1; return; }
      setTimeout(poll, 5); })()`);
    const ms = await page.waitFor('window.__lone !== null', 3000) === null ? null : await page.ev('window.__lone');
    rows.push(ms === null || ms < 0 ? { key: `traffic lone call ${view}`, value: 'not drawn', ok: false, detail: `not on the ${view} 3 s after it was sent` }
      : { key: `traffic lone call ${view}`, value: ms <= 300 ? 'within the redraw' : 'late', ok: ms <= 300,
        detail: `on the ${view} ${ms} ms after it arrived (redraw 250 ms, 50 ms slack)` });
  }
  await openTab(page, 'waterfall');
  await page.ev(RESET);
  const ids = [];
  await sendAndSettle(page, app.sessionId, 50, 20, (i) => ids.push(app.call('/feed', 200)));
  const seen = await drawsSince(page, app.sessionId);
  const drawn = await page.ev(`${JSON.stringify(ids)}.filter((id) => document.querySelector('#waterfall [data-id="' + id + '"]')).length`);
  rows.push(burstRow('traffic call burst', 'call', seen, ['renderTimeline', 'renderWaterfall', 'renderPath'],
    { missing: seen.arrived && drawn < ids.length, text: `${drawn} of ${ids.length} on the waterfall` }));
  return rows;
}

async function reaches(page, origin, sessions) {
  const seen = await page.ev(`({ asked: window.__origins || [],
    sockets: [...new Set((window.__sockets || []).map((s) => new URL(s.url).host))] })`);
  const host = new URL(origin).host;
  const elsewhere = [...seen.asked.filter((o) => o !== `http://${host}`), ...seen.sockets.filter((h) => h !== host)];
  // "None went elsewhere" passes over a recorder that recorded nothing. The page has fetched its
  // rows and opened `/api/live` by now (the case waited for both), so an empty record is a NO.
  const unseen = [!seen.asked.length && 'request', !seen.sockets.length && 'socket'].filter(Boolean);
  const hosts = new Set();
  for (const id of sessions) for (const t of (await calls(origin, id, '')).items) hosts.add(t.host);
  const invalid = [...hosts].filter((h) => !/\.invalid$/.test(h));
  return [
    { key: 'traffic reaches', ok: !unseen.length && !elsewhere.length,
      value: unseen.length ? `NO - found no ${unseen.join(' and no ')}, so this checked nothing`
        : elsewhere.length ? 'beyond 127.0.0.1' : '127.0.0.1 only',
      detail: elsewhere.length ? elsewhere.join(', ')
        : `the page's requests and sockets, ${host}: ${seen.asked.length} origins asked, ${seen.sockets.length} socket hosts` },
    { key: 'traffic hosts', value: invalid.length ? 'not .invalid' : '.invalid only', ok: hosts.size > 0 && !invalid.length,
      detail: hosts.size ? [...hosts].join(', ') : 'the daemon archived no call' },
  ];
}

/**
 * The case. `origin` is a daemon of its own with an empty data directory: the rows it streams
 * there are the only sessions it holds.
 */
async function liveTraffic(page, origin) {
  // The older session first, so the page opens on the newer one, as it opens on any daemon.
  const other = await openApp(origin, 'measure.other');
  const app = await openApp(origin, 'measure.main');
  try {
    other.call('/b/one', 200);
    other.call('/b/fail', 503);
    const sent = { calls: 0 };
    app.signal('screen', 'Home');
    for (const [path, status] of [['/feed', 200], ['/feed', 200], ['/pay', 500], ['/cart', 200], ['/pay', 502], ['/cart', 404]]) {
      app.call(path, status);
      sent.calls++;
    }
    app.signal('state', 'VM', '"Idle"');
    await stored(origin, other.sessionId, 2);
    await stored(origin, app.sessionId, sent.calls);

    await page.onNewDocument(RECORDER);
    await page.setWidth(1440);
    await page.navigate(origin);
    if (await page.waitFor(`typeof state !== 'undefined' && state.sessionId === ${JSON.stringify(app.sessionId)}
      && document.querySelectorAll('#list .row').length >= ${sent.calls} && ${LIVE_OPEN}`, 30000) === null) {
      throw new Error(`the page did not open ${app.sessionId} with its live socket`);
    }
    await setFilter(page, FILTER);

    const rows = await burst(page, origin, app, sent);
    rows.push(...await filterEditRaces(page, origin, app));
    rows.push(...await sessionSwitchRaces(page, origin, app, other.sessionId));
    rows.push(...await signalBursts(page, origin, app, other));
    rows.push(...await reaches(page, origin, [app.sessionId, other.sessionId]));
    const thrown = page.exceptions.length;
    rows.push({ key: 'traffic exceptions', value: thrown ? 'thrown' : 'none', ok: !thrown,
      detail: thrown ? page.exceptions.slice(0, 3).join(' | ') : undefined });
    return rows;
  } finally {
    app.close();
    other.close();
  }
}

module.exports = { liveTraffic };
