/* What a session switch clears, and the one wording every pane uses until the session is read.
 *
 * Both run the real `js/16-session-load.js` in a `vm` context over stubs (`runFile`), as the
 * session state checks run theirs. `being read wording` holds the text to one function, `beingRead`,
 * so a pane that words it again, or words it differently, fails here rather than drifting on screen;
 * `switch clears calls` holds what `followSession` clears and redraws. See `docs/decisions/web-ui.md`.
 *
 * Zero dependencies, like the rest of `check-web-static.js`.
 */
const { HarnessError } = require('./web-audits');
const { runFile } = require('./session-state-checks');

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

function checkBeingRead(sources) {
  return [
    ['being read wording', checkBeingReadWording(sources)],
    ['switch clears calls', checkSwitchClearsCalls(sources)],
  ];
}

module.exports = { checkBeingRead };
