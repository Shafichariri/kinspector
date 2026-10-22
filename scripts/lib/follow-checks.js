/* When the timeline under live tail moves the pane, and when it must not.
 *
 * The timeline flows in `#list-pane` since KIN-33, so its live follow scrolls the whole pane, and it
 * used to on every redraw: expanding a run in the middle of a 3016-call session threw the reader to
 * the end, 24 to 29934 oldest-first and 29800 to 0 newest-first. A redraw the reader asked for keeps
 * the pane where it is; a new row is followed only when the pane was already at the end it follows;
 * opening the view follows once. jsdom has no layout, so these drive the real `70-timeline.js` and
 * `applyView` over a stub pane whose `scrollTop` and sizes are numbers set here — the rule is which
 * position the code writes, and that needs no layout to read.
 *
 * Zero dependencies, like the rest of `check-web-static.js`.
 */
const { HarnessError } = require('./web-audits');
const { stubPage } = require('./long-session-checks');

const HEIGHT = 30000;
const VIEW = 800;
const MIDDLE = 12000;

function followState(newestFirst) {
  const call = (i) => ({ id: `t${i}`, mono: i * 10, method: 'GET', host: 'shop.invalid', path: '/v3/shop/stock', status: 200 });
  const signal = (i) => ({ id: `s${i}`, mono: i * 10 + 5, tag: 'state', name: 'Cart', trigger: 'push' });
  const calls = [call(0), call(1)];
  // Three of one key between two calls: a run, drawn collapsed.
  const signals = [signal(0), { ...signal(0), id: 's0b', mono: 6 }, { ...signal(0), id: 's0c', mono: 7 }];
  return {
    sessionId: 'S', signalSessionId: 'S', filter: '', view: 'all', newestFirst, liveTail: true, timeRange: null, current: [],
    transactions: calls, allTransactions: calls, sessionTotal: 2, matched: 2,
    signals, allSignals: signals.slice(), screens: [], markers: [], recording: [],
    expandedRuns: new Set(), timelineFresh: false,
  };
}

/** Where the pane is when a reader is at the end of [state]'s order. */
const endOf = (state) => (state.newestFirst ? 0 : HEIGHT - VIEW);
/** Where a follow puts it: the top newest-first, the foot of the pane oldest-first. */
const followedTo = (state) => (state.newestFirst ? 0 : HEIGHT);

function followPage(sources, newestFirst) {
  const state = followState(newestFirst);
  const { page, pane } = stubPage(sources, state);
  if (typeof page.renderTimeline !== 'function' || typeof page.applyView !== 'function') {
    throw new HarnessError('renderTimeline or applyView is not declared where follow-checks.js looks for it');
  }
  page.renderAxis = () => {};
  pane.scrollHeight = HEIGHT;
  pane.clientHeight = VIEW;
  page.renderTimeline();
  const run = () => page.$('timeline').children.find((n) => /\btl-run\b/.test(n.className));
  return { state, page, pane, run };
}

async function checkTimelineFollow(sources) {
  const lines = [];
  for (const newestFirst of [false, true]) {
    const order = newestFirst ? 'newest' : 'oldest';
    let { state, page, pane, run } = followPage(sources, newestFirst);

    // A run expanded, then collapsed, in the middle of the pane.
    const toggles = [];
    for (const what of ['expand', 'collapse']) {
      pane.scrollTop = MIDDLE;
      const node = run();
      if (!node || !node.listeners.click) throw new HarnessError('the timeline drew no run row with a click handler');
      node.listeners.click[0]();
      toggles.push(`${what} ${MIDDLE} -> ${pane.scrollTop}`);
    }
    lines.push([`run toggle ${order}`, toggles.every((t) => t.endsWith(` ${MIDDLE}`))
      ? `yes (${toggles.join(', ')}, live on)`
      : `NO - ${toggles.join(', ')} with live on; a run the reader opened moved the pane`]);

    // The same at the end it follows: the reader asked for the redraw, so it does not follow either.
    pane.scrollTop = endOf(state);
    run().listeners.click[0]();
    lines.push([`run toggle at end ${order}`, pane.scrollTop === endOf(state)
      ? `yes (${endOf(state)} -> ${pane.scrollTop})`
      : `NO - ${endOf(state)} -> ${pane.scrollTop}, where the run was opened at the end`]);

    // A new observation arriving, with the pane at the end it follows and then scrolled back.
    ({ state, page, pane } = followPage(sources, newestFirst));
    const arrive = (at) => {
      pane.scrollTop = at;
      const s = { id: `n${state.signals.length}`, mono: 100 + state.signals.length, tag: 'cache', name: 'k', trigger: 'push' };
      state.signals.push(s);
      state.allSignals.push(s);
      page.renderTimeline();
      return pane.scrollTop;
    };
    const near = newestFirst ? 5 : endOf(state) - 5;
    const atEnd = arrive(near);
    lines.push([`arrival at end ${order}`, atEnd === followedTo(state)
      ? `yes (${near} -> ${atEnd})`
      : `NO - ${near} -> ${atEnd}, where a pane at its end follows to ${followedTo(state)}`]);
    const back = arrive(MIDDLE);
    lines.push([`arrival scrolled ${order}`, back === MIDDLE
      ? `yes (${MIDDLE} -> ${back})`
      : `NO - ${MIDDLE} -> ${back}; a reader scrolled back was moved by a new row`]);

    // Opening the view follows once, wherever the pane was.
    page.$('timeline').hidden = true;
    pane.scrollTop = MIDDLE;
    page.applyView();
    lines.push([`opening ${order}`, pane.scrollTop === followedTo(state)
      ? `yes (${MIDDLE} -> ${pane.scrollTop})`
      : `NO - ${MIDDLE} -> ${pane.scrollTop}, where opening the timeline follows to ${followedTo(state)}`]);
  }
  return lines;
}

/**
 * The live socket's own messages, with the timeline, the path or the waterfall shown — the shared
 * pane is moved by whichever renderer follows, and the list's follow ran whatever view was shown.
 * `checkTimelineFollow` calls `renderTimeline` itself and so never saw it: one call through
 * `ws.onmessage` moved a 3016-call session's pane from the middle to its end, a marker too. These go
 * through the real `05-network.js` handler over the real renderers, and through the three things that
 * are meant to follow on the timeline — the order toggle, a load and a brushed stretch — which
 * followed only because the list's follow had moved the pane first.
 */
function socketPage(sources, view, newestFirst) {
  const state = {
    ...followState(newestFirst), view, sessionTotal: 2, matched: 2,
    screens: [{ id: 'home', tag: 'screen', name: 'Home', mono: 0 }],
    expandedVisits: new Set(), pathFocus: null, pathSessionId: null, pathVisitCount: null, pathFresh: true,
    screenPayloadText: new Map(), tagColours: new Map(), tagColoursSession: null, timelineFresh: true,
    callLoad: 0, signalLoad: 0, allSessionId: 'S', allSignalsSessionId: 'S', serverState: 'running',
    arriving: { transactions: null, signals: null },
    reread: { transactions: false, signals: false, timer: null, busy: false },
    readSent: { transactions: 0, signals: 0 }, readWritten: { transactions: 0, signals: 0 },
    currentSessionId: 'S', currentRead: { sent: 0, written: 0 }, signalSessionId: 'S',
    liveDraw: { timer: null },
  };
  state.allSignals.push(state.screens[0]);
  const sockets = [];
  // `05-network.js` declares the page's own `api` over `fetch`, so the routes are answered here.
  const answer = (path) => {
    const at = new URL(path, 'http://stub.invalid').pathname;
    if (at.endsWith('/transactions')) return { items: state.allTransactions.slice(), total: 2, matched: 2 };
    if (at.endsWith('/signals')) return state.allSignals.slice();
    return [];
  };
  // Every path asked, so a check can count the `/current` and payload reads a live row cost.
  const asked = [];
  const fetch = async (path) => {
    asked.push(new URL(path, 'http://stub.invalid').pathname);
    return { ok: true, status: 200, text: async () => JSON.stringify(answer(path)) };
  };
  const { page, pane } = stubPage(sources, state, {
    before: ['js/05-network.js', 'js/15-session-data.js', 'js/30-repeats-endpoints.js'],
    globals: {
      ARCHIVE: null, location: { host: 'stub.invalid' }, fetch,
      WebSocket: class { constructor(url) { this.url = url; sockets.push(this); } },
      prefs: { get: () => null, set() {} }, ageOf: () => 0, fmtAge: () => '', setConn() {},
      showServerBanner() {}, setControlsEnabled() {}, loadServerInfo() {}, loadSessions: async () => {},
      renderBrowser() {}, loadPayload: async () => {}, methodClass: () => 'm-get', statusClass: () => '2',
      followLiveCount() {}, oweStats() {}, oweFindings() {},
    },
  });
  for (const name of ['connectLive', 'setSortOrder', 'setTimeRange', 'loadTransactions', 'applyView']) {
    if (typeof page[name] !== 'function') throw new HarnessError(`${name} is not declared where follow-checks.js looks for it`);
  }
  page.renderAxis = () => {};
  pane.scrollHeight = HEIGHT;
  pane.clientHeight = VIEW;
  page.$('timeline').hidden = true;
  page.applyView();
  page.connectLive();
  const ws = sockets[0];
  if (!ws || typeof ws.onmessage !== 'function') throw new HarnessError('connectLive opened no socket with an onmessage handler');
  const send = (message) => ws.onmessage({ data: JSON.stringify({ sessionId: 'S', ...message }) });
  // A call's timeline row, findable by id; the list's own row shape is not checked here.
  page.rowFor = (txn) => Object.assign(page.el('div', 'row'), { dataset: { id: txn.id } });
  return { state, page, pane, send, asked };
}

const pause = (ms) => new Promise((r) => setTimeout(r, ms));
/** Waits until the redraw a live call armed has run (`drawLive`); a check reads the pane after it. */
async function drawn(state) {
  for (let i = 0; i < 200 && state.liveDraw.timer !== null; i++) await pause(10);
  if (state.liveDraw.timer !== null) throw new HarnessError('the live redraw never ran');
}

const VIEW_NAMES = { all: 'timeline', path: 'path', waterfall: 'waterfall' };

async function checkSocketFollow(sources) {
  const lines = [];
  const message = {
    txn: (n) => ({ type: 'txn', txn: { id: `live${n}`, mono: 50 + n, method: 'GET', host: 'shop.invalid', path: '/v3/shop/stock', status: 200 } }),
    marker: (n) => ({ type: 'marker', marker: { id: `m${n}`, label: `mark ${n}`, mono: 50 + n } }),
    signal: (n) => ({ type: 'signal', signal: { id: `live-s${n}`, mono: 50 + n, tag: 'cache', name: 'k', trigger: 'push' } }),
  };
  // Scrolled back, a call or a marker leaves every one of the three views where the reader put it.
  for (const view of ['all', 'path', 'waterfall']) {
    const moved = [];
    for (const newestFirst of [false, true]) {
      for (const kind of ['txn', 'marker']) {
        const { state, pane, send } = socketPage(sources, view, newestFirst);
        pane.scrollTop = MIDDLE;
        send(message[kind](1));
        await drawn(state);
        if (pane.scrollTop !== MIDDLE) moved.push(`${kind} ${newestFirst ? 'newest' : 'oldest'} ${MIDDLE} -> ${pane.scrollTop}`);
      }
    }
    lines.push([`socket scrolled ${VIEW_NAMES[view]}`, moved.length
      ? `NO - ${moved.join(', ')}; a live row moved a reader scrolled back`
      : `yes (a live call and a marker left ${MIDDLE} at ${MIDDLE}, both orders)`]);
  }
  // At the end it follows, the timeline still follows what it draws from the socket.
  const ends = [];
  let followed = true;
  for (const newestFirst of [false, true]) {
    for (const kind of ['marker', 'signal', 'txn']) {
      const { state, pane, send } = socketPage(sources, 'all', newestFirst);
      const near = newestFirst ? 5 : endOf(state) - 5;
      pane.scrollTop = near;
      send(message[kind](2));
      await drawn(state);
      ends.push(`${kind} ${newestFirst ? 'newest' : 'oldest'} ${near} -> ${pane.scrollTop}`);
      if (pane.scrollTop !== followedTo(state)) followed = false;
    }
  }
  lines.push(['socket at end timeline', followed ? `yes (${ends.join(', ')})` : `NO - ${ends.join(', ')}; a pane at its end was not followed`]);
  // The list itself still follows a live call, as it always has.
  {
    const { state, pane, send } = socketPage(sources, 'network', false);
    pane.scrollTop = MIDDLE;
    send(message.txn(3));
    lines.push(['socket list follows', pane.scrollTop === followedTo(state)
      ? `yes (${MIDDLE} -> ${pane.scrollTop})`
      : `NO - ${MIDDLE} -> ${pane.scrollTop}, where the list follows a live call to ${followedTo(state)}`]);
  }
  // The order toggle, a load and a brushed stretch follow on the timeline from the middle.
  const asked = [];
  let all = true;
  for (const [what, act] of [
    ['order', (page, state) => page.setSortOrder(!state.newestFirst)],
    ['load', (page) => page.loadTransactions()],
    ['stretch', (page) => page.setTimeRange({ from: 0, to: 40 })],
  ]) {
    const { state, page, pane } = socketPage(sources, 'all', false);
    pane.scrollTop = MIDDLE;
    await act(page, state);
    if (what === 'load' && state.wholeRead) await state.wholeRead.done;
    asked.push(`${what} ${MIDDLE} -> ${pane.scrollTop}`);
    if (pane.scrollTop !== followedTo(state)) all = false;
  }
  lines.push(['timeline asked follows', all ? `yes (${asked.join(', ')})` : `NO - ${asked.join(', ')}; each was due to follow`]);
  lines.push(...await checkLiveCallDrawn(sources, message.txn));
  lines.push(...await checkLiveSignalBurst(sources));
  return lines;
}

/**
 * A live call with no filter reaches the timeline and the glance, with no other action, within one
 * redraw: the `txn` branch never called `renderTimeline`, so the timeline left out every call until
 * something else redrew it. An open run stays open, and a burst costs a bounded number of redraws.
 */
async function checkLiveCallDrawn(sources, txn) {
  const lines = [];
  const has = (page, id) => page.$('timeline').children.some((n) => n.dataset && n.dataset.id === id);
  {
    const { state, page, send } = socketPage(sources, 'all', false);
    let glances = 0;
    page.renderSessionGlance = () => { glances++; };
    page.$('detail').hidden = true;
    page.renderTimeline();
    const before = has(page, 'live7');
    send(txn(7));
    await drawn(state);
    lines.push(['socket call drawn', !before && has(page, 'live7') && glances > 0
      ? `yes (live7 drawn on the timeline and the glance redrawn ${glances}x, no other action)`
      : `NO - live7 on the timeline: ${has(page, 'live7')}, glance redraws ${glances}; a live call is drawn without another action`]);
  }
  {
    const { state, page, pane, send } = socketPage(sources, 'all', false);
    page.renderTimeline();
    const run = () => page.$('timeline').children.find((n) => /\btl-run\b/.test(n.className));
    pane.scrollTop = MIDDLE;
    run().listeners.click[0]();
    const opened = /\btl-run-open\b/.test(run().className);
    send(txn(8));
    await drawn(state);
    lines.push(['socket run stays open', opened && /\btl-run-open\b/.test(run().className) && pane.scrollTop === MIDDLE
      ? `yes (an open run stayed open and the pane stayed at ${MIDDLE} after a live call)`
      : `NO - run open before ${opened}, after ${/\btl-run-open\b/.test(run().className)}, pane ${MIDDLE} -> ${pane.scrollTop}`]);
  }
  {
    // 100 calls over a second, one every 10 ms, as `reread burst of 100` sends its rows.
    const { state, page, send } = socketPage(sources, 'all', false);
    page.$('detail').hidden = true;
    const real = page.renderTimeline;
    let timelines = 0;
    let glances = 0;
    page.renderTimeline = (...args) => { timelines++; return real(...args); };
    page.renderSessionGlance = () => { glances++; };
    const started = Date.now();
    for (let n = 0; n < 100; n++) {
      send(txn(100 + n));
      await pause(10);
    }
    await drawn(state);
    const took = Date.now() - started;
    const bound = Math.ceil(took / 250) + 1;
    const all = Array.from({ length: 100 }, (_, n) => `live${100 + n}`).every((id) => has(page, id));
    lines.push(['live draw burst of 100', timelines <= bound && glances <= bound && all
      ? `yes (100 calls in ${took} ms: ${timelines} timeline and ${glances} glance redraws, bound ${bound}; every call drawn)`
      : `NO - 100 calls in ${took} ms: ${timelines} timeline and ${glances} glance redraws, bound ${bound}; every call drawn: ${all}`]);
  }
  return lines;
}

/**
 * A signal or a marker rides the same redraw as a call. The signal branch redrew the timeline, the
 * waterfall and the path and read `/current` for every signal, and the marker branch redrew the
 * timeline and the path for every marker, so a burst of 100 signals cost 300 whole-session redraws
 * and 100 reads. Each burst here counts the renderers' calls, not what they drew: a pane that is
 * not shown returns at once, and the bound is on the calls.
 */
async function checkLiveSignalBurst(sources) {
  const lines = [];
  const counted = (page, names) => {
    const n = Object.fromEntries(names.map((name) => [name, 0]));
    for (const name of names) {
      const real = page[name];
      page[name] = (...args) => { n[name]++; return real(...args); };
    }
    return n;
  };
  const burst = async (send, count, every, make) => {
    const started = Date.now();
    for (let i = 0; i < count; i++) {
      send(make(i));
      await pause(every);
    }
    return Date.now() - started;
  };
  const onTimeline = (page) => page.$('timeline').children;
  // The stub's querySelectorAll finds nothing, so the path's stations are counted by walking it.
  const stations = (node) => (/(^|\s)pv-stop(\s|$)/.test(node.className || '') ? 1 : 0)
    + (node.children || []).reduce((sum, child) => sum + stations(child), 0);
  // 100 signals over a second, on the timeline and then on the path with a screen in every ten.
  for (const view of ['all', 'path']) {
    const { state, page, send, asked } = socketPage(sources, view, false);
    const n = counted(page, ['renderTimeline', 'renderWaterfall', 'renderPath']);
    const before = state.allSignals.length;
    asked.length = 0;
    const took = await burst(send, 100, 10, (i) => ({ type: 'signal', signal: i % 10 === 0 && view === 'path'
      ? { id: `burst-s${i}`, mono: 60 + i, tag: 'screen', name: `Screen ${i}`, dataRef: `signals/burst-s${i}.json` }
      : { id: `burst-s${i}`, mono: 60 + i, tag: 'state', name: `Vm${i}`, trigger: 'push' } }));
    await drawn(state);
    await pause(20);
    const bound = Math.ceil(took / 250) + 1;
    const arrived = state.allSignals.length - before;
    const reads = asked.filter((p) => p.endsWith('/current')).length;
    const counts = `${arrived} signals arrived in ${took} ms: ${n.renderTimeline} timeline, ${n.renderWaterfall} waterfall and ${n.renderPath} path redraws, ${reads} /current reads, bound ${bound}`;
    const shown = view === 'all'
      ? Array.from({ length: 100 }, (_, i) => `burst-s${i}`).filter((id) => onTimeline(page).some((c) => c.dataset && c.dataset.id === id)).length
      : stations(page.$('path'));
    const label = `live signal burst ${view === 'all' ? 'timeline' : 'path'}`;
    if (!arrived) { lines.push([label, `NO - found no signal arriving, so this checked nothing (${counts})`]); continue; }
    const within = [n.renderTimeline, n.renderWaterfall, n.renderPath, reads].every((c) => c <= bound) && reads > 0;
    const drawnAll = view === 'all' ? shown === 100 : shown >= 11;
    lines.push([label, within && drawnAll
      ? `yes (${counts}; ${view === 'all' ? `${shown} of 100 drawn on the timeline` : `${shown} stations drawn`})`
      : `NO - ${counts}; ${view === 'all' ? `${shown} of 100 drawn on the timeline` : `${shown} stations drawn, 11 due`}`]);
  }
  // 50 markers over a second: the timeline and the path, each once per redraw.
  {
    const { state, page, send } = socketPage(sources, 'all', false);
    const n = counted(page, ['renderTimeline', 'renderPath']);
    const before = state.markers.length;
    const took = await burst(send, 50, 20, (i) => ({ type: 'marker', marker: { id: `bm${i}`, label: `burst ${i}`, mono: 60 + i } }));
    await drawn(state);
    const bound = Math.ceil(took / 250) + 1;
    const arrived = state.markers.length - before;
    const dividers = onTimeline(page).filter((c) => c.dataset && c.dataset.kind === 'marker' && /^burst \d+$/.test(c.textContent)).length;
    const counts = `${arrived} markers arrived in ${took} ms: ${n.renderTimeline} timeline and ${n.renderPath} path redraws, bound ${bound}`;
    lines.push(['live marker burst', !arrived ? `NO - found no marker arriving, so this checked nothing (${counts})`
      : n.renderTimeline <= bound && n.renderPath <= bound && dividers === 50
        ? `yes (${counts}; ${dividers} of 50 drawn on the timeline)`
        : `NO - ${counts}; ${dividers} of 50 drawn on the timeline`]);
  }
  // Paused, for another session, or owed by a session the page has since left: nothing is drawn
  // for the row and no `/current` is read. The last is the switch window: the switch's own load
  // reads the strip, and the timer's read would put the new session's values up before it lands.
  {
    const moved = [];
    for (const [why, setup, sessionId] of [
      ['paused', (state) => { state.liveTail = false; }, 'S'],
      ['for another session', () => {}, 'O'],
    ]) {
      const { state, page, send, asked } = socketPage(sources, 'all', false);
      const n = counted(page, ['renderTimeline', 'renderWaterfall', 'renderPath']);
      setup(state);
      asked.length = 0;
      send({ sessionId, type: 'signal', signal: { id: 'still-s', mono: 70, tag: 'state', name: 'Vm', trigger: 'push' } });
      send({ sessionId, type: 'marker', marker: { id: 'still-m', label: 'still', mono: 71 } });
      const armed = state.liveDraw.timer !== null;
      await pause(300);
      const total = n.renderTimeline + n.renderWaterfall + n.renderPath + asked.length;
      if (armed || total) moved.push(`${why}: redraw armed ${armed}, ${total} redraws and reads`);
    }
    const { state, send, asked } = socketPage(sources, 'all', false);
    send({ type: 'signal', signal: { id: 'left-s', mono: 70, tag: 'state', name: 'Vm', trigger: 'push' } });
    const armed = state.liveDraw.timer !== null;
    asked.length = 0;
    // A switch inside the window: the session is another one and not yet read.
    state.sessionId = 'T';
    state.signalSessionId = null;
    await drawn(state);
    await pause(20);
    const currents = asked.filter((p) => p.endsWith('/current')).length;
    if (!armed) moved.push('a signal before the switch armed no redraw, so the switch case checked nothing');
    if (currents) moved.push(`switched before the redraw: ${currents} /current reads`);
    lines.push(['live signal still', moved.length
      ? `NO - ${moved.join('; ')}`
      : 'yes (paused and for another session a signal and a marker armed nothing and read nothing; a switch before the redraw read no /current)']);
  }
  return lines;
}

module.exports = { checkTimelineFollow, checkSocketFollow };
