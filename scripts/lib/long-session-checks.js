/* The web UI on a session too long to spread into an argument list.
 *
 * Every argument is a stack slot, so `Math.min(...monos)` over a row array throws
 * `RangeError: Maximum call stack size exceeded` once the array is long enough: past about 120,000
 * in Node and 110,000 in Chrome. On a 160,000-call session the axis threw before anything was drawn
 * and the page stayed blank at "connecting…". `auditNoArgumentSpread` reads the source for the
 * shape; `checkLongSession` runs the functions that held one, from the real files, over 200,000
 * rows, so a spread written in a way the text scan cannot read still fails there. Drawing goes to a
 * stub node that holds nothing; what is checked is that each function returns and what it returns.
 *
 * Zero dependencies, like the rest of `check-web-static.js`.
 */
const { HarnessError } = require('./web-audits');
const { runFile } = require('./session-state-checks');

const ROWS = 200000;

/**
 * Source text with comments, strings and template text blanked to spaces, so a scan for syntax
 * does not read prose. A template's `${…}` is kept, since code runs in it. Offsets are unchanged.
 * Regex literals are not recognised; none in `web/js` holds `...` or an unbalanced bracket.
 */
function codeOnly(text) {
  const out = text.split('');
  const blank = (from, to) => { for (let k = from; k < to; k++) if (out[k] !== '\n') out[k] = ' '; };
  const braces = []; // per open `${`, the brace depth inside it
  let i = 0;
  const template = () => {
    // i is just past a backtick or a closing `}` of `${…}`; blank up to the next `${` or backtick.
    const start = i;
    while (i < text.length) {
      if (text[i] === '\\') { i += 2; continue; }
      if (text[i] === '`') { blank(start, i); i++; return; }
      if (text[i] === '$' && text[i + 1] === '{') { blank(start, i); i += 2; braces.push(0); return; }
      i++;
    }
    blank(start, i);
  };
  while (i < text.length) {
    const c = text[i];
    if (c === '/' && text[i + 1] === '/') { const e = text.indexOf('\n', i); const end = e < 0 ? text.length : e; blank(i, end); i = end; }
    else if (c === '/' && text[i + 1] === '*') { const e = text.indexOf('*/', i + 2); const end = e < 0 ? text.length : e + 2; blank(i, end); i = end; }
    else if (c === "'" || c === '"') {
      const start = i++;
      while (i < text.length && text[i] !== c && text[i] !== '\n') i += text[i] === '\\' ? 2 : 1;
      i++;
      blank(start + 1, i - 1);
    } else if (c === '`') { i++; template(); }
    else if (braces.length && c === '{') { braces[braces.length - 1]++; i++; }
    else if (braces.length && c === '}') {
      if (braces[braces.length - 1] === 0) { braces.pop(); i++; template(); } else { braces[braces.length - 1]--; i++; }
    } else i++;
  }
  return out.join('');
}

const NOT_A_CALL = new Set(['if', 'for', 'while', 'switch', 'catch', 'return', 'typeof', 'await', 'function']);

/**
 * No spread into an argument list anywhere in the scripts: `f(...rows)`, `Math.min(...monos)`,
 * `push(a, ...b)`.
 *
 * Every argument is a stack slot, so a spread of a row array throws `RangeError: Maximum call stack
 * size exceeded` once the array is long enough — somewhere past 110,000 in Chrome and 120,000 in
 * Node — and the axis's `Math.min(...monos)` drew a blank page on a 160,000-call session. Spreads
 * into an array literal, `[...a, ...b]`, are not calls and are allowed. Read from the source, with
 * comments and strings blanked, by finding the innermost bracket a `...` sits in: a `(` that follows
 * a name, a `)` or a `]` is a call, unless its `)` is followed by `=>` or `{`, which makes it a
 * parameter list. The long-session checks run the same functions at 200,000 rows, so this is not the
 * only thing that would see one come back.
 */
function auditNoArgumentSpread(sources) {
  if (!sources.length) return 'NO - no scripts to read';
  const offenders = [];
  let spreads = 0;
  for (const { file, text } of sources) {
    const code = codeOnly(text);
    for (let at = code.indexOf('...'); at >= 0; at = code.indexOf('...', at + 3)) {
      spreads++;
      let depth = 0;
      let open = -1;
      for (let k = at - 1; k >= 0; k--) {
        const c = code[k];
        if (')]}'.includes(c)) depth++;
        else if ('([{'.includes(c)) {
          if (depth === 0) { open = k; break; }
          depth--;
        }
      }
      if (open < 0 || code[open] !== '(') continue;
      const before = code.slice(0, open).replace(/\s+$/, '');
      const word = (before.match(/[A-Za-z_$][\w$]*$/) || [''])[0];
      const callable = /[\w$)\]]$/.test(before) && !NOT_A_CALL.has(word) && !/\bfunction\s*\*?\s*[\w$]*$/.test(before);
      if (!callable) continue;
      let close = open;
      for (let k = open, d = 0; k < code.length; k++) {
        if ('([{'.includes(code[k])) d++;
        else if (')]}'.includes(code[k]) && --d === 0) { close = k; break; }
      }
      if (/^\s*(=>|\{)/.test(code.slice(close + 1))) continue;
      const line = text.slice(0, at).split('\n').length;
      offenders.push(`${file}:${line} ${text.split('\n')[line - 1].trim().slice(0, 60)}`);
    }
  }
  return offenders.length
    ? `NO - ${offenders.length} spread(s) into an argument list: ${offenders.join('; ')}`
    : `yes (${spreads} spread(s) read in ${sources.length} file(s), none into an argument list)`;
}

/**
 * A DOM node that accepts what the renderers do to one. It keeps its children, its class and its
 * listeners, so a check can find a row and press it; `scrollTop` and the sizes are plain numbers a
 * check sets, since there is no layout to compute them.
 */
function stubNode(id = null, className = '') {
  const node = {
    id, className, hidden: false, textContent: '', title: '', type: '', tabIndex: 0, value: '',
    scrollTop: 0, scrollLeft: 0, scrollHeight: 0, scrollWidth: 0, clientHeight: 0, clientWidth: 0, offsetHeight: 0,
    children: [], listeners: {}, dataset: {}, style: { setProperty() {} },
    classList: { add() {}, remove() {}, toggle() {}, contains: () => false },
    get innerHTML() { return ''; },
    set innerHTML(html) { node.children.length = 0; },
    appendChild(child) { node.children.push(child); return child; },
    append(...xs) { node.children.push(...xs); }, prepend() {}, replaceChildren() { node.children.length = 0; },
    setAttribute() {}, querySelector: () => null, querySelectorAll: () => [],
    addEventListener(type, fn) { (node.listeners[type] = node.listeners[type] || []).push(fn); },
    getBoundingClientRect: () => ({ top: 0, bottom: 0, left: 0, right: 0, height: 0, width: 0 }),
  };
  return node;
}

/**
 * The traffic views' files run in one scope over [state], with the DOM stubbed: `$` hands out one
 * node per id, each a child of one `list-pane`, and `el` a fresh node with the class it was given.
 * `before` names files to run ahead of them, and `globals` adds to or replaces the stubs.
 */
function stubPage(sources, state, { before = [], globals = {} } = {}) {
  const nodes = new Map();
  const pane = stubNode('list-pane');
  const $ = (id) => {
    if (!nodes.has(id)) { const n = stubNode(id); n.parentElement = pane; nodes.set(id, n); }
    return nodes.get(id);
  };
  const page = runFile(sources, [...before, 'js/16-session-load.js', 'js/20-navigation.js', 'js/25-traffic-list.js', 'js/45-visits.js', 'js/50-path.js', 'js/55-axis.js', 'js/70-timeline.js'], {
    state, $, document: { getElementById: $ },
    el: (tag, cls, text) => Object.assign(stubNode(null, cls || ''), { textContent: text === undefined ? '' : String(text) }),
    getComputedStyle: () => ({ top: '0' }), sessionIsLive: () => false,
    fmtMs: (ms) => `${ms}ms`, fmtBytes: (n) => `${n} B`, ageNode: () => stubNode(),
    selectSignal() {}, source: { fetch: async () => ({ ok: false }) }, ARCHIVE: null,
    closeDrawer() {}, closePops() {}, renderSessionGlance() {},
    // The tab bar's `views ▾` menu, from 40-copy-export.js, which is not run here.
    menuItem: () => stubNode(), menuButton: () => ({ wrap: stubNode(), button: stubNode(), menu: stubNode(), close() {} }),
    ...globals,
  });
  // A call's row is the list's, which reads `00-core.js`'s formatters; its shape is not checked here.
  page.rowFor = () => stubNode(null, 'row');
  return { page, pane };
}

function longState() {
  const calls = Array.from({ length: ROWS }, (_, i) => ({
    id: `t${i}`, mono: i, ms: 5, method: 'GET', host: 'shop.invalid', path: '/v3/shop/stock', status: 200,
  }));
  // One key observed 200,000 times after the last call, so the timeline draws one collapsed run.
  const signals = Array.from({ length: ROWS }, (_, i) => ({
    id: `s${i}`, mono: ROWS + i, tag: 'state', name: 'Busy', trigger: 'push', data: { n: 1 },
  }));
  const screens = [{ id: 'home', tag: 'screen', name: 'Home', mono: 0 }];
  return {
    sessionId: 'S', signalSessionId: 'S', filter: '', view: 'all', newestFirst: false, liveTail: false, timeRange: null,
    transactions: calls, allTransactions: calls, sessionTotal: ROWS + 1000, matched: ROWS + 1000,
    signals, allSignals: signals.concat(screens), screens, markers: [], recording: [],
    expandedRuns: new Set(), expandedVisits: new Set(), pathFocus: null, pathSessionId: null,
    pathVisitCount: null, pathFresh: true, screenPayloadText: new Map(), tagColours: new Map(), tagColoursSession: null,
  };
}

/** Runs [fn], turning a throw into the report's `NO`, and its result into a `yes` or a `NO`. */
function attempt(fn) {
  try {
    return fn();
  } catch (e) {
    return `NO - ${e instanceof RangeError ? 'RangeError' : e.name}: ${e.message}`;
  }
}

async function checkLongSession(sources) {
  const state = longState();
  const { page } = stubPage(sources, state);
  for (const name of ['sessionWindow', 'waterfallGroups', 'hiddenByFilter', 'renderTimeline', 'renderPath']) {
    if (typeof page[name] !== 'function') throw new HarnessError(`${name} is not declared where long-session-checks.js looks for it`);
  }
  const lines = [];
  lines.push([`axis window ${ROWS / 1000}k`, attempt(() => {
    const win = page.sessionWindow();
    return win && win.from === 0 && win.to === 2 * ROWS - 1
      ? `yes (${win.from}..${win.to} over ${state.allTransactions.length} calls and ${state.allSignals.length} signals)`
      : `NO - ${JSON.stringify(win)}, where 0..${2 * ROWS - 1} was due`;
  })]);
  lines.push([`waterfall ${ROWS / 1000}k`, attempt(() => {
    const groups = page.waterfallGroups();
    const g = groups.find((x) => x.rows.length);
    return g && g.rows.length === ROWS && g.from === 0 && g.to === ROWS - 1 + 5
      ? `yes (one visit of ${g.rows.length} calls, ${g.from}..${g.to})`
      : `NO - ${g ? `${g.rows.length} calls, ${g.from}..${g.to}` : 'no group with calls'}, where ${ROWS} calls over 0..${ROWS + 4} were due`;
  })]);
  lines.push([`stretch note ${ROWS / 1000}k`, attempt(() => {
    // From before the oldest held call, so the stretch reaches below where the held rows are whole.
    state.timeRange = { from: -1, to: ROWS };
    const hidden = page.hiddenByFilter({ ranged: true });
    state.timeRange = null;
    return hidden.atLeast === true
      ? `yes (a stretch over ${ROWS} held calls of ${state.sessionTotal} counts from the oldest held)`
      : `NO - ${JSON.stringify(hidden)}, where "at least" was due: the held rows stop short of the session`;
  })]);
  lines.push([`timeline ${ROWS / 1000}k`, attempt(() => {
    page.renderTimeline();
    const drawn = page.$('timeline').children.length;
    return drawn === ROWS + 1
      ? `yes (${ROWS} calls and a run of ${ROWS} observations drawn as ${drawn} rows)`
      : `NO - ${drawn} rows drawn, where ${ROWS + 1} were due: each call and the run as one`;
  })]);
  lines.push([`path ${ROWS / 1000}k`, attempt(() => {
    state.view = 'path';
    page.renderPath();
    state.view = 'all';
    const drawn = page.$('path').children.length;
    return drawn > 0 ? `yes (the map and the log drawn over ${ROWS} calls)` : 'NO - nothing drawn';
  })]);
  return lines;
}

/** A stub node's text with its children's, as `textContent` reads a real one. */
const textOf = (node) => (typeof node === 'string' ? node
  : `${node.textContent || ''}${node.children.map(textOf).join('')}`);

/** Every node under [node] whose class list holds [cls], depth first. */
function findAll(node, cls, out = []) {
  if (typeof node === 'string') return out;
  if ((node.className || '').split(/\s+/).includes(cls)) out.push(node);
  for (const child of node.children) findAll(child, cls, out);
  return out;
}

/**
 * The path on a session past the route's clamp: 2816 calls, all but the first to `/v3/shop`, over
 * `Home` from 0, `Idle` from 300 with no call, `Cart` from 500 and `Checkout` from 1000. `Idle`
 * holds nothing below the oldest held call either, so it still says "nothing" rather than claiming
 * calls exist that were not loaded. The list holds what a load holds there,
 * the newest 1000 rows of what the route matched, so `Home` and `Cart` began before the oldest held
 * call. Counting the held rows called them "0 of 500 match" under `path:/v3/shop`, which matched
 * 3015, and "nothing recorded" with no filter; they say their calls are not loaded, `Checkout` says
 * that its older calls are not, and the filter note says how many of the matches the list holds,
 * which is the number the header counts. A filter matching only the first call is not clamped, so
 * there `Cart` still says "0 of 500 match".
 */
function checkPathPastTheClamp(sources) {
  const calls = Array.from({ length: 3016 }, (_, i) => ({
    id: `t${i}`, mono: i, ms: 5, method: 'GET', host: 'shop.invalid', path: i === 0 ? '/v3/auth' : '/v3/shop', status: 200,
  })).filter((t) => t.mono < 300 || t.mono >= 500);
  const screens = [
    { id: 'home', tag: 'screen', name: 'Home', mono: 0 },
    { id: 'idle', tag: 'screen', name: 'Idle', mono: 300 },
    { id: 'cart', tag: 'screen', name: 'Cart', mono: 500 },
    { id: 'checkout', tag: 'screen', name: 'Checkout', mono: 1000 },
  ];
  const lines = [];
  const notLoaded = { Home: 'calls not loaded', Idle: 'nothing', Cart: 'calls not loaded' };
  const auth = { Home: '1 network', Idle: 'nothing', Cart: '0 of 500 match' };
  for (const [filter, want] of [['path:/v3/shop', notLoaded], ['', notLoaded], ['path:/v3/auth', auth]]) {
    const matched = calls.filter((t) => !filter || `path:${t.path}` === filter);
    const held = matched.slice(-1000);
    const state = {
      sessionId: 'S', signalSessionId: 'S', filter, view: 'path', newestFirst: false, liveTail: false, timeRange: null,
      transactions: held, allTransactions: calls, sessionTotal: calls.length, matched: matched.length,
      signals: [], allSignals: screens, screens, markers: [], recording: [],
      expandedVisits: new Set(), pathFocus: null, pathSessionId: null, pathVisitCount: null, pathFresh: true,
      screenPayloadText: new Map(), tagColours: new Map(), tagColoursSession: null,
    };
    const { page } = stubPage(sources, state);
    page.renderPath();
    const root = page.$('path');
    const stations = findAll(root, 'pv-stop').map((stop) => {
      const name = findAll(stop, 'pv-stop-name').map(textOf).join('');
      return `${name}=${findAll(stop, 'pv-stop-stat').map(textOf).join('')}`;
    });
    const stat = (name) => (stations.find((s) => s.startsWith(`${name}=`)) || '').slice(name.length + 1);
    const checkout = findAll(root, 'pv-stats').map(textOf).find((t) => t.includes('network')) || '';
    const note = findAll(root, 'filter-note').map(textOf).join('');
    const label = `path clamp ${filter || 'no filter'}`;
    const clamped = held.length < matched.length;
    const problems = [];
    for (const [name, text] of Object.entries(want)) {
      if (stat(name) !== text) problems.push(`${name} reads "${stat(name)}", where "${text}" was due`);
    }
    if (clamped && !checkout.includes('older calls not loaded')) problems.push(`Checkout reads "${checkout}", without "older calls not loaded"`);
    if (clamped && filter && !note.includes(`newest ${held.length} of ${matched.length} matches loaded`)) {
      problems.push(`the note reads "${note.slice(0, 90)}", without what its count covers`);
    }
    lines.push([label, problems.length
      ? `NO - ${problems.join('; ')} (${held.length} of ${matched.length} matches held)`
      : `yes (${stations.join(' | ')}; ${held.length} of ${matched.length} matches held)`]);
  }
  return lines;
}

module.exports = { checkLongSession, checkPathPastTheClamp, auditNoArgumentSpread, stubNode, stubPage };
