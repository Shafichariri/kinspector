/* What the traffic list says it holds, on a session past the route's 1000-row clamp.
 *
 * The list draws the newest 1000 calls that match (`LIST_CEILING`). Under a filter matching more,
 * the header read `1000 of 3016 calls`, which reads as "1000 calls match" where 1200 did, and the
 * list was the one pane without the `newest N of M matches loaded` note. A stretch brushed below the
 * oldest call the list held read "no calls in the selected stretch" over calls the glance counted,
 * and one drawing loaded matches said nothing of older matches in it the list never loaded.
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
    hidden: false, title: '', parts, parentElement: { scrollTop: 0 },
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

  // The header with no stretch, over the whole session, and over the newest 1016 calls, which hold
  // 200 of the loaded matches (even monos from 2000 to 2398). A stretch is no exception: the plain
  // count read `1000 of 3016 calls` over the whole session and `200 of 3016 calls` over the part.
  state.filter = 'path:/v3/shop/bulk';
  await page.loadTransactions();
  const counts = [];
  for (const [range, due] of [
    [null, 'newest 1000 of 1200 matches shown'],
    [{ from: 0, to: 3015 }, '1000 of the newest 1000 of 1200 matches shown'],
    [{ from: 2000, to: 3015 }, '200 of the newest 1000 of 1200 matches shown'],
  ]) {
    state.timeRange = range;
    page.renderCounts();
    counts.push([text('counts'), due]);
  }
  state.timeRange = null;
  const wrongCount = counts.filter(([got, due]) => got !== due);
  lines.push(['filtered count', wrongCount.length === 0
    ? `yes ("${counts[0][0]}", "${counts[2][0]}"; 1200 matched of 3016, ${state.transactions.length} held)`
    : `NO - ${wrongCount.map(([got, due]) => `"${got}" where "${due}" was due`).join('; ')} (1200 matched, from the route)`]);

  page.renderListNote();
  const note = text('list-note');
  const noteDue = '1816 calls hidden by the filter path:/v3/shop/bulk — newest 1000 of 1200 matches loaded';
  lines.push(['list note past the clamp', !nodes['list-note'].hidden && note.startsWith(noteDue)
    ? `yes ("${note.slice(0, noteDue.length)}…")`
    : `NO - "${note}"${nodes['list-note']?.hidden ? ' (hidden)' : ''}, where "${noteDue}" was due`]);

  // The stretch: the oldest 151 calls, all below the newest 1000, with no filter, where the copy
  // counts them; under a filter, where only 76 of them match and nothing on the page can say so,
  // so no number is claimed — over those 151, and over one call that does not match; a stretch with
  // no calls at all, under the filter; and the whole copy short of the session, where only "at
  // least" is true.
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
  const filteredDue = 'none of the newest 1000 of 1200 matches the list loaded is in this stretch, and the 200 older ones are not loaded — narrow the filter to reach any here';
  stretch.push([text('list-empty'), filteredDue]);
  state.timeRange = { from: 1, to: 1 };
  page.renderListEmpty();
  stretch.push([text('list-empty'), filteredDue]);
  state.timeRange = { from: -100, to: -1 };
  page.renderListEmpty();
  stretch.push([text('list-empty'), 'no calls in the selected stretch']);
  state.filter = '';
  state.timeRange = null;
  await page.loadTransactions();
  state.allTransactions = state.allTransactions.slice().sort((a, b) => b.mono - a.mono).slice(0, 2000);
  state.timeRange = { from: 0, to: 1500 };
  page.renderListEmpty();
  stretch.push([text('list-empty'), 'at least 485 calls in this stretch are older than the newest 1000 the list loaded — filter to reach them']);
  const wrong = stretch.filter(([got, due]) => got !== due);
  lines.push(['stretch older than loaded', wrong.length === 0
    ? `yes (${stretch.length} stretches, "${stretch[0][0].slice(0, 48)}…", no count claimed under the filter, "at least" past the whole copy)`
    : `NO - ${wrong.map(([got, due]) => `"${got}" where "${due}" was due`).join('; ')}`]);
  lines.push(await stretchBelowMatches(sources));
  return lines;
}

/**
 * The list note over a stretch that draws loaded matches and reaches below the oldest of them: the
 * matches loaded are even monos 400 to 2398, so 300 to 500 draws 51 of them and holds 50 older
 * matches the list never loaded. The note said only "at least 50 calls hidden", which read as every
 * match in the stretch; it now says older matches in it are not drawn, with no count of them. A
 * stretch wholly above mono 400, and one the list draws none of, whose empty text says it, read as
 * they did.
 */
async function stretchBelowMatches(sources) {
  const { state, page, text } = listPage(sources);
  state.filter = 'path:/v3/shop/bulk';
  await page.loadTransactions();
  const lead = 'hidden by the filter path:/v3/shop/bulk — newest 1000 of 1200 matches loaded';
  const notes = [];
  for (const [range, due] of [
    [{ from: 300, to: 500 }, `at least 50 calls ${lead} — this stretch reaches below the oldest of the 1000 matches loaded, ` +
      'so any older matches in it are not drawn — narrow the filter to reach them — clear filter'],
    [{ from: 2000, to: 3015 }, `816 calls ${lead} — clear filter`],
    [{ from: 0, to: 150 }, 'the filter path:/v3/shop/bulk — newest 1000 of 1200 matches loaded — clear filter'],
  ]) {
    state.timeRange = range;
    page.renderListNote();
    notes.push([text('list-note'), due]);
  }
  const wrong = notes.filter(([got, due]) => got !== due);
  return ['stretch below loaded matches', wrong.length === 0
    ? `yes ("…${notes[0][0].slice(notes[0][0].indexOf('this stretch'), notes[0][0].indexOf(', so'))}…" over 300..500, no count of the older matches; ${notes.length - 1} other stretches as before)`
    : `NO - ${wrong.map(([got, due]) => `"${got}" where "${due}" was due`).join('; ')}`];
}

module.exports = { checkListHolds };
