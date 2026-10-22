/* What a session switch clears, the one wording every pane uses until the session is read, and
 * the first page drawn before the whole-session copy is read behind it (KIN-42).
 *
 * Each runs the real `js/16-session-load.js` in a `vm` context over stubs (`runFile`), as the
 * session state checks run theirs. `being read wording` holds the text to one function, `beingRead`,
 * so a pane that words it again, or words it differently, fails here rather than drifting on screen;
 * `switch clears calls` holds what `followSession` clears and redraws. `first draw before copy`,
 * `copy lands redraws` and `overtaken read stops` run a whole load over a 1500-call route whose
 * whole-session page at `offset=1000` is held; `reading notes` draws the panes in that window.
 * See `docs/decisions/web-ui.md`.
 *
 * Zero dependencies, like the rest of `check-web-static.js`.
 */
const vm = require('vm');
const { HarnessError } = require('./web-audits');
const { runFile, callRoute } = require('./session-state-checks');
const { stubPage, stubNode } = require('./long-session-checks');

const FILE = 'js/16-session-load.js';

const FORMS = [
  [[], 'this session is being read…'],
  [[1], "this session's 1 call is being read…"],
  [[160016], "this session's 160016 calls are being read…"],
];

/**
 * Every string literal in [text] outside comments: quoted strings whole, and a template's literal
 * parts. A heuristic scan, not a parse: among what it misses are a phrase built by concatenating
 * literals (`'being ' + 'read'`), one built in a `${…}` of a template, and anything after a regex
 * literal holding a quote, which it reads as the start of a string.
 */
function stringLiterals(text) {
  const found = [];
  let i = 0;
  while (i < text.length) {
    const c = text[i];
    if (c === '/' && text[i + 1] === '/') { const e = text.indexOf('\n', i); i = e < 0 ? text.length : e; }
    else if (c === '/' && text[i + 1] === '*') { const e = text.indexOf('*/', i + 2); i = e < 0 ? text.length : e + 2; }
    else if (c === "'" || c === '"' || c === '`') {
      let k = i + 1;
      while (k < text.length && text[k] !== c && (c === '`' || text[k] !== '\n')) k += text[k] === '\\' ? 2 : 1;
      found.push(text.slice(i + 1, k));
      i = k + 1;
    } else i++;
  }
  return found;
}

/**
 * `beingRead` gives the three forms exactly, the last character U+2026, and no other file in
 * `web/scripts.txt` holds `being read` in a string of its own.
 */
function checkBeingReadWording(sources) {
  const own = sources.find((s) => s.file === FILE);
  if (!own) throw new HarnessError(`${FILE} is not in web/scripts.txt; update being-read-checks.js`);
  const { beingRead } = runFile(sources, FILE, { state: {} });
  if (typeof beingRead !== 'function') throw new HarnessError(`beingRead is not declared in ${FILE}`);
  for (const [args, due] of FORMS) {
    const got = beingRead(...args);
    if (got !== due) return `NO - beingRead(${args.join(', ')}) reads "${got}", where "${due}" was due`;
  }
  const holds = (s) => stringLiterals(s.text).filter((literal) => /being read/.test(literal)).length;
  if (holds(own) !== 1) return `NO - ${FILE} holds "being read" in ${holds(own)} literals, where beingRead's one was due`;
  const others = sources.filter((s) => s.file !== FILE && holds(s));
  if (others.length) return `NO - ${others.map((s) => s.file).join(', ')} holds its own "being read" text`;
  return `yes (${FORMS.length} forms, the phrase in ${FILE} only)`;
}

/**
 * `followSession` on a switch from A to B: the calls, the whole-session copy and its mark, the
 * markers and both counts cleared, the call load overtaken, A's open call closed, and each pane
 * redrawn once; then the same switch with a call an address opened on B, which stays open.
 */
function checkSwitchClearsCalls(sources) {
  const switched = (selectedId) => {
    const calls = (n) => Array.from({ length: n }, (_, i) => ({ id: `a${i}`, mono: i }));
    const state = {
      sessionId: 'B', signalSessionId: 'A', selectedId, signalLoad: 2, callLoad: 5,
      currentRead: { sent: 3, written: 1 }, signals: [], allSignals: [], allSignalsSessionId: 'A',
      screens: [], current: [], currentSessionId: 'A',
      transactions: calls(2), allTransactions: calls(3), allSessionId: 'A',
      markers: [{ label: 'm', mono: 1 }], sessionTotal: 3, matched: 2,
    };
    const nodes = new Map();
    const $ = (id) => {
      if (!nodes.has(id)) nodes.set(id, { id, hidden: id === 'detail-empty' });
      return nodes.get(id);
    };
    const ran = {};
    const stubs = {};
    for (const name of ['renderCurrent', 'renderCounts', 'renderTabs', 'renderMarkers', 'renderList', 'applyView', 'paintDetailTools']) {
      ran[name] = 0;
      stubs[name] = () => { ran[name]++; };
    }
    const page = runFile(sources, FILE, { state, $, ...stubs });
    if (typeof page.followSession !== 'function') throw new HarnessError(`followSession is not declared in ${FILE}`);
    page.followSession();
    return { state, ran, detail: $('detail').hidden, glance: !$('detail-empty').hidden };
  };

  const { state, ran, detail, glance } = switched(null);
  if (state.allTransactions.length) return `NO - allTransactions still holds ${state.allTransactions.length} of A's calls (the chips and the axis read it)`;
  if (state.callLoad !== 6) return `NO - callLoad ${state.callLoad}, so a load of A in flight still writes`;
  const left = ['transactions', 'markers'].filter((k) => state[k].length)
    .concat(['allSessionId', 'sessionTotal', 'matched'].filter((k) => state[k] !== null));
  if (left.length) return `NO - ${left.join(', ')} still A's after the switch`;
  if (!detail || !glance) return `NO - A's open call ${detail ? 'closed' : 'still shown'}, the glance ${glance ? 'shown' : 'hidden'}`;
  const redrawn = Object.entries(ran).filter(([, n]) => n !== 1);
  if (redrawn.length) return `NO - ${redrawn.map(([name, n]) => `${name} ran ${n}x`).join(', ')}, where once each was due`;

  const kept = switched('b1');
  if (kept.detail || kept.glance) return 'NO - a call an address opened on B was closed by the switch';
  return 'yes (calls, copy, markers and counts cleared, 6 panes redrawn, a call opened on B kept)';
}

const pause = (ms) => new Promise((r) => setTimeout(r, ms));
/** Waits up to a second for [done]; whether it came is the caller's to judge. */
async function until(done) {
  for (let i = 0; i < 100 && !done(); i++) await pause(10);
}

/** The panes `drawWhole` redraws, each of which the load also draws, or reaches through `loadSignals`. */
const REDRAWN = ['renderTabs', 'renderList', 'renderTimeline', 'renderWaterfall', 'renderPath', 'renderSessionGlance'];

/**
 * `15-session-data.js` and `16-session-load.js` over a route of 1500 calls (`total 1500`, the
 * first page 1000), whose whole-session pages are counted by offset, and whose first `offset=1000`
 * page is held until `release`. Each page function records whether `wholePending()` held when it
 * ran, and its argument; `renderList` sets the list pane's `scrollTop` to 0, as live follow does.
 */
function heldLoad(sources) {
  const calls = Array.from({ length: 1500 }, (_, i) => ({ id: `t${i}`, path: '/a', mono: i }));
  const asked = {};
  let release;
  const held = new Promise((r) => { release = r; });
  const behave = async (filter, url) => {
    const offset = url.searchParams.get('offset');
    if (offset === null) return;
    asked[offset] = (asked[offset] || 0) + 1;
    if (offset === '1000' && asked[offset] === 1) await held;
  };
  const state = {
    sessionId: 'S', signalSessionId: 'S', filter: '', signals: [], allSignals: [], allSignalsSessionId: null,
    screens: [], view: 'network', transactions: [], allTransactions: [], allSessionId: null, markers: [],
    callLoad: 0, signalLoad: 0, arriving: { transactions: null, signals: null },
    readSent: { transactions: 0, signals: 0 }, readWritten: { transactions: 0, signals: 0 },
    currentSessionId: 'S', currentRead: { sent: 0, written: 0 },
  };
  const pane = { scrollTop: 0 };
  const nodes = {};
  const $ = (id) => (nodes[id] ??= { id, hidden: id === 'detail', textContent: '', title: '', parentElement: pane });
  const ran = [];
  let page = null;
  const record = (name) => (arg) => {
    ran.push({ name, arg, whole: vm.runInContext('wholePending()', page) });
    if (name === 'renderList') pane.scrollTop = 0;
  };
  const stubs = {};
  for (const name of REDRAWN) stubs[name] = record(name);
  const route = callRoute(calls, { behave });
  page = runFile(sources, ['js/15-session-data.js', 'js/16-session-load.js'], {
    state, $, api: route, ARCHIVE: null, tagOf: (x) => String(x.tag).toLowerCase(),
    availableViews: () => [{ id: 'network' }], visibleTransactions: () => state.transactions,
    renderCurrent() {}, applyView() {}, renderListEmpty() {}, showFilterError() {}, renderMarkers() {},
    markerFormState() {}, paintDetailTools() {}, ...stubs,
  });
  if (typeof page.loadTransactions !== 'function' || typeof page.drawWhole !== 'function') {
    throw new HarnessError('loadTransactions or drawWhole is not declared in js/16-session-load.js');
  }
  return { state, page, pane, ran, asked, release };
}

/**
 * `first draw before copy` and `copy lands redraws`, one load: the list and the timeline drawn
 * while the whole read's second page is held, then, released, the copy marked whole and each pane
 * `drawWhole` reads drawn once more, the timeline without following and the list's scroll kept.
 */
async function checkFirstDrawThenCopy(sources) {
  const { state, page, pane, ran, asked, release } = heldLoad(sources);
  const load = page.loadTransactions();
  await until(() => asked['1000'] && ran.some((r) => r.name === 'renderTimeline'));
  if (!asked['1000']) {
    release();
    await load;
    const nothing = 'NO - the read never asked offset=1000, so this checked nothing';
    return [['first draw before copy', nothing], ['copy lands redraws', nothing]];
  }
  const drawn = ran.filter((r) => (r.name === 'renderList' || r.name === 'renderTimeline') && r.whole);
  const first = drawn.length >= 2
    ? `yes (${drawn.map((r) => r.name).join(', ')} with the read held at offset=1000)`
    : 'NO - nothing was drawn while the whole-session read was held';
  const before = ran.length;
  pane.scrollTop = 400;
  release();
  await load;
  const after = ran.slice(before);
  const problems = [];
  if (state.allSessionId !== 'S' || state.allTransactions.length !== 1500) {
    problems.push(`the copy holds ${state.allTransactions.length} calls, marked ${state.allSessionId}`);
  }
  const counts = REDRAWN.map((name) => [name, after.filter((r) => r.name === name).length]).filter(([, n]) => n !== 1);
  if (counts.length) problems.push(`${counts.map(([name, n]) => `${name} ran ${n}x`).join(', ')} after the copy landed, where once each was due`);
  const timeline = after.find((r) => r.name === 'renderTimeline');
  if (timeline && timeline.arg?.follow !== false) problems.push('the timeline was drawn following');
  if (pane.scrollTop !== 400) problems.push(`scrollTop ${pane.scrollTop} where 400 was left`);
  const lands = problems.length ? `NO - ${problems.join('; ')}` : `yes (1500 calls marked, ${REDRAWN.length} panes redrawn once, scrollTop 400 kept)`;
  return [['first draw before copy', first], ['copy lands redraws', lands]];
}

/** A load overtaken while its whole read is held asks for no page after the held one. */
async function checkOvertakenReadStops(sources) {
  const { state, page, asked, release } = heldLoad(sources);
  const first = page.loadTransactions();
  await until(() => asked['1000']);
  if (!asked['1000']) {
    release();
    await first;
    return 'NO - the read never asked offset=1000, so this checked nothing';
  }
  state.filter = 'path:/a';
  await page.loadTransactions();
  const read = asked['1500'] || 0;
  release();
  await first;
  await pause(20);
  const more = (asked['1500'] || 0) - read;
  return more === 0
    ? `yes (the overtaken read stopped at offset=1000; the newer load's read asked offset=1500 ${read}x)`
    : `NO - the overtaken read asked ${more} more page${more === 1 ? '' : 's'}`;
}

/** A stub node whose `replaceChildren` keeps what it is given, as a real one does. */
const keeping = (node) => Object.assign(node, {
  replaceChildren(...xs) { node.textContent = ''; node.children.length = 0; node.children.push(...xs); },
});
const textOf = (node) => (typeof node === 'string' ? node : `${node.textContent || ''}${node.children.map(textOf).join('')}`);

/**
 * The panes in the window between the first draw and the copy: the newest 1000 of 1500 calls held,
 * the copy empty and unmarked, its read in flight, and a stretch brushed below the page. The list,
 * the path, the waterfall and the overview line's hint each say the session's calls are being read,
 * and the axis is hidden.
 */
function checkReadingNotes(sources) {
  const calls = Array.from({ length: 1500 }, (_, i) => ({
    id: `t${i}`, mono: i, ms: 5, method: 'GET', host: 'shop.invalid', path: '/v3/shop/stock', status: 200,
  }));
  // Landed before the first draw, as `loadSignals` lands them, and enough for the axis to draw.
  const signals = [0, 1499].map((mono) => ({ id: `s${mono}`, mono, tag: 'state', name: 'Busy', trigger: 'push' }));
  const state = {
    sessionId: 'S', signalSessionId: 'S', filter: '', view: 'network', newestFirst: true, liveTail: false,
    timeRange: { from: 0, to: 400 }, transactions: calls.slice(500), allTransactions: [], allSessionId: null,
    sessionTotal: 1500, matched: 1500, signals: [], allSignals: signals, screens: [], markers: [], recording: [],
    arriving: { transactions: { sessionId: 'S', rows: [] }, signals: null },
    expandedRuns: new Set(), expandedVisits: new Set(), pathFocus: null, pathSessionId: null, pathVisitCount: null,
    pathFresh: true, screenPayloadText: new Map(), tagColours: new Map(), tagColoursSession: null,
  };
  const nodes = new Map();
  const pane = stubNode('list-pane');
  const $ = (id) => {
    if (!nodes.has(id)) nodes.set(id, Object.assign(keeping(stubNode(id)), { parentElement: pane }));
    return nodes.get(id);
  };
  const el = (tag, cls, text) => Object.assign(keeping(stubNode(null, cls || '')), { textContent: text === undefined ? '' : String(text) });
  const { page } = stubPage(sources, state, { before: ['js/30-repeats-endpoints.js'], globals: { $, el, document: { getElementById: $, createElementNS: () => stubNode() } } });
  const due = "this session's 1500 calls are being read…";
  const problems = [];
  // As index.html starts them; this session holds nothing now, so the strip stays hidden.
  $('overview').hidden = true;
  $('now-strip').hidden = true;
  page.renderList();
  if (textOf($('list-empty')) !== due || $('list-empty').hidden) problems.push(`#list-empty reads "${textOf($('list-empty'))}"`);
  if (!$('axis').hidden) problems.push('#axis is shown');
  // The overview line keeps its band through the read, saying why it is empty (`renderAxis`).
  if ($('overview').hidden) problems.push('the overview line is hidden');
  if ($('axis-hint').hidden || textOf($('axis-hint')) !== due) problems.push(`the overview hint reads "${$('axis-hint').hidden ? '(hidden)' : textOf($('axis-hint'))}"`);
  state.view = 'path';
  page.renderPath();
  const path = textOf($('path'));
  if (path !== due) problems.push(`the path reads "${path}"`);
  state.view = 'waterfall';
  page.renderWaterfall();
  if (textOf($('waterfall-empty')) !== due) problems.push(`the waterfall reads "${textOf($('waterfall-empty'))}"`);
  return problems.length ? `NO - ${problems.join('; ')}` : `yes (the list, the path, the waterfall and the overview hint read "${due}", the axis hidden)`;
}

async function checkBeingRead(sources) {
  return [
    ['being read wording', checkBeingReadWording(sources)],
    ['switch clears calls', checkSwitchClearsCalls(sources)],
    ...await checkFirstDrawThenCopy(sources),
    ['overtaken read stops', await checkOvertakenReadStops(sources)],
    ['reading notes', checkReadingNotes(sources)],
  ];
}

module.exports = { checkBeingRead };
