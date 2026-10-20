/* What the traffic list says it holds, on a session past the route's 1000-row clamp.
 *
 * The list draws the newest 1000 calls that match (`LIST_CEILING`). Under a filter matching more,
 * the header read `1000 of 3016 calls`, which reads as "1000 calls match" where 1200 did, and the
 * list was the one pane without the `newest N of M matches loaded` note. A stretch brushed below the
 * oldest call the list held read "no calls in the selected stretch" over calls the glance counted.
 * Each check runs the real files over `clampPage`'s 3016-call stub, clamped as the daemon clamps,
 * through the real `renderCounts`, `renderListNote` and `renderListEmpty`; only `$` is a stub, one
 * node per id. See `docs/decisions/web-ui.md`.
 *
 * Zero dependencies, like the rest of `check-web-static.js`.
 */
const { clampPage } = require('./session-state-checks');

/** A stand-in for a DOM node: enough for `textContent`, `filterNote` and `hidden`. */
function node() {
  const parts = [];
  return {
    hidden: false, title: '', parts,
    replaceChildren() { parts.length = 0; },
    append(...xs) { parts.push(...xs.map(String)); },
    appendChild(child) { parts.push(child.textContent || ''); return child; },
    get textContent() { return parts.join(''); },
    set textContent(text) { parts.length = 0; parts.push(String(text)); },
  };
}

/** The clamped page with the real header, note and empty text drawing into one node per id. */
function listPage(sources) {
  const { state, page, real } = clampPage(sources);
  const nodes = {};
  page.$ = (id) => (nodes[id] ??= node());
  page.renderCounts = real.renderCounts;
  page.renderListEmpty = real.renderListEmpty;
  return { state, page, text: (id) => (nodes[id] ? nodes[id].textContent : ''), nodes };
}

async function checkListHolds(sources) {
  const lines = [];
  let { state, page, text, nodes } = listPage(sources);
  if (typeof page.renderListNote !== 'function' || typeof page.olderThanLoaded !== 'function') {
    const missing = 'NO - renderListNote or olderThanLoaded is not declared in js/25-traffic-list.js';
    return [['filtered count', missing], ['list note past the clamp', missing], ['stretch older than loaded', missing]];
  }

  state.filter = 'path:/v3/shop/bulk';
  await page.loadTransactions();
  const count = text('counts');
  lines.push(['filtered count', count === 'newest 1000 of 1200 matches shown'
    ? `yes ("${count}", 1200 matched of 3016, ${state.transactions.length} held)`
    : `NO - "${count}", where "newest 1000 of 1200 matches shown" was due (1200 matched, from the route)`]);

  page.renderListNote();
  const note = text('list-note');
  const noteDue = '1816 calls hidden by the filter path:/v3/shop/bulk — newest 1000 of 1200 matches loaded';
  lines.push(['list note past the clamp', !nodes['list-note'].hidden && note.startsWith(noteDue)
    ? `yes ("${note.slice(0, noteDue.length)}…")`
    : `NO - "${note}"${nodes['list-note']?.hidden ? ' (hidden)' : ''}, where "${noteDue}" was due`]);

  // The stretch: the oldest 151 calls, all below the newest 1000, with no filter and under one; and
  // with the whole copy short of the session, where only "at least" is true.
  const stretch = [];
  ({ state, page, text } = listPage(sources));
  await page.loadTransactions();
  state.timeRange = { from: 0, to: 150 };
  page.renderListEmpty();
  stretch.push([text('list-empty'), '151 calls in this stretch are older than the newest 1000 the list loaded — filter to reach them']);
  state.filter = 'path:/v3/shop/bulk';
  state.timeRange = null;
  await page.loadTransactions();
  state.timeRange = { from: 0, to: 150 };
  page.renderListEmpty();
  stretch.push([text('list-empty'), '151 calls in this stretch are older than the newest 1000 of 1200 matches the list loaded — narrow the filter to reach them']);
  state.filter = '';
  state.timeRange = null;
  await page.loadTransactions();
  state.allTransactions = state.allTransactions.slice().sort((a, b) => b.mono - a.mono).slice(0, 2000);
  state.timeRange = { from: 0, to: 1500 };
  page.renderListEmpty();
  stretch.push([text('list-empty'), 'at least 485 calls in this stretch are older than the newest 1000 the list loaded — filter to reach them']);
  const wrong = stretch.filter(([got, due]) => got !== due);
  lines.push(['stretch older than loaded', wrong.length === 0
    ? `yes (${stretch.length} stretches, "${stretch[0][0].slice(0, 48)}…", "at least" past the whole copy)`
    : `NO - ${wrong.map(([got, due]) => `"${got}" where "${due}" was due`).join('; ')}`]);
  return lines;
}

module.exports = { checkListHolds };
