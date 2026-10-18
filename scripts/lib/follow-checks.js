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
    sessionId: 'S', filter: '', view: 'all', newestFirst, liveTail: true, timeRange: null, current: [],
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

module.exports = { checkTimelineFollow };
