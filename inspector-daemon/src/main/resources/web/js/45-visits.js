/* visits: screen visits, timeline entries and the waterfall */
'use strict';

// --- visits: the rule the path, the waterfall and the axis band share -----

// @mirror-begin visits
/**
 * Mirror of `visits` in `:inspector-model`'s `Visits.kt`; `VisitsTest` is the authority and this
 * follows it. Keep them in step.
 *
 * A session split into the screens the user was on. Attribution is strictly by start — a visit
 * owns `[from, to)` — with no grace window for a `screen` signal that lands after the calls its
 * view-model fired, because any window would also move the call that *caused* a navigation onto
 * the screen it navigated to. Consecutive same-name signals are one visit, with the later ones
 * kept as `repeats` and drawn in place. Anything before the first screen is a leading visit whose
 * `screen` is null: the app was somewhere, and nothing recorded where.
 *
 * The skeleton comes from `screens`, never from `signals`. The filter grammar drops every signal
 * under `path:` and every call under `tag:`, so a skeleton built from the filtered rows lost every
 * screen the moment a filter was typed — and a visit nothing matched vanished, drawing a route
 * the user never took. Callers pass `state.screens`, which ignores the filter, and a visit with
 * no matching entries comes back empty rather than missing. The leading visit is skeleton too,
 * so `sessionStart` — the earliest `mono` of the unfiltered session — decides whether it exists.
 * A tie in the same millisecond goes by capture `seq` when both sides carry one, so a tap made
 * just before the navigation it triggered stays on the screen it was made on.
 */
function visits(screens, transactions, markers, signals, sessionStart = null) {
  const isScreen = (s) => tagOf(s) === 'screen';
  const arrivals = screens.filter(isScreen)
    .sort((a, b) => a.mono - b.mono || (a.seq ?? -Infinity) - (b.seq ?? -Infinity));
  const others = signals.filter((s) => !isScreen(s));

  const out = [];
  for (const arrival of arrivals) {
    const open = out[out.length - 1];
    if (open && open.screen.name === arrival.name) open.repeats.push(arrival);
    else out.push({ screen: arrival, repeats: [], from: arrival.mono, fromSeq: arrival.seq ?? null });
  }

  let earliest = sessionStart;
  for (const item of [...transactions, ...markers, ...others]) {
    if (earliest === null || item.mono < earliest) earliest = item.mono;
  }
  if (earliest !== null && (!out.length || earliest < out[0].from)) {
    out.unshift({ screen: null, repeats: [], from: earliest, fromSeq: null });
  }
  if (!out.length) return [];

  const starts = out.map((v) => ({ mono: v.from, seq: v.fromSeq }));
  for (const v of out) { v.txns = []; v.markers = []; v.signals = [...v.repeats]; }
  for (const txn of transactions) out[ownerOf(starts, txn.mono, txn.seq)].txns.push(txn);
  for (const marker of markers) out[ownerOf(starts, marker.mono, marker.seq)].markers.push(marker);
  for (const signal of others) out[ownerOf(starts, signal.mono, signal.seq)].signals.push(signal);

  out.forEach((v, i) => {
    v.to = i + 1 < out.length ? out[i + 1].from : null;
    // The daemon sends calls newest first. A visit's own list is oldest first, as its entries are,
    // or the waterfall draws every visit upside down — which it did, until a screenshot showed it.
    v.txns.sort((a, b) => a.mono - b.mono);
    v.entries = timelineEntries(v.txns, v.markers, v.signals);
  });
  return out;
}

/** Whether `start` opened at or before an entry: `seq` decides a tie when both have one. */
function opensBy(start, mono, seq) {
  if (start.mono !== mono) return start.mono < mono;
  if (start.seq == null || seq == null) return true;
  return start.seq < seq;
}

/** The last start at or before the entry; anything earlier than every start goes to the first. */
function ownerOf(starts, mono, seq) {
  let lo = 0;
  let hi = starts.length - 1;
  let found = 0;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (opensBy(starts[mid], mono, seq)) { found = mid; lo = mid + 1; } else hi = mid - 1;
  }
  return found;
}

/** Mirror of `visitIndexAt`: which visit was open at `mono`, or -1 before all of them. */
function visitIndexAt(list, mono) {
  if (!list.length || mono < list[0].from) return -1;
  return ownerOf(list.map((v) => ({ mono: v.from, seq: null })), mono, null);
}

/**
 * Mirror of `timeline()` for one visit's contents, oldest first: calls then signals in a stable
 * sort, with each marker placed before anything at the same `mono` (`<=`).
 */
function timelineEntries(txns, markers, signals) {
  const body = [
    ...txns.map((txn) => ({ kind: 'txn', mono: txn.mono, txn })),
    ...signals.map((signal) => ({ kind: 'signal', mono: signal.mono, signal })),
  ].sort((a, b) => a.mono - b.mono);
  const ordered = [...markers].sort((a, b) => a.mono - b.mono);
  const entries = [];
  let next = 0;
  for (const entry of body) {
    while (next < ordered.length && ordered[next].mono <= entry.mono) {
      entries.push({ kind: 'marker', mono: ordered[next].mono, marker: ordered[next] });
      next++;
    }
    entries.push(entry);
  }
  for (; next < ordered.length; next++) {
    entries.push({ kind: 'marker', mono: ordered[next].mono, marker: ordered[next] });
  }
  return entries;
}
// @mirror-end visits

/**
 * One colour per screen name, in order of first appearance in the session's skeleton.
 *
 * Built from `state.screens` alone, so the waterfall, the axis band and the path agree on a
 * screen's colour whatever each one is showing.
 */
function screenColours() {
  const colours = new Map();
  for (const s of [...state.screens].sort((a, b) => a.mono - b.mono)) {
    if (!colours.has(s.name)) colours.set(s.name, SCREEN_COLOURS[colours.size % SCREEN_COLOURS.length]);
  }
  return (name) => (name === null ? 'var(--divider)' : colours.get(name) || 'var(--divider)');
}

/**
 * The earliest `mono` this page holds for the session, unfiltered: every call, every marker and
 * every signal, screens among them. It read the filtered signals until the page kept a whole-session
 * copy of them, so a launch stretch holding nothing but non-screen signals lost its column under a
 * filter that dropped them.
 */
function sessionStartMono() {
  let start = null;
  for (const item of [...state.allTransactions, ...state.markers, ...state.allSignals]) {
    if (start === null || item.mono < start) start = item.mono;
  }
  return start;
}

/** Whether a visit overlaps the brushed stretch. Outside it, a visit dims rather than vanishing. */
const visitInRange = (visit) =>
  !state.timeRange ||
  (visit.from <= state.timeRange.to && (visit.to === null || visit.to >= state.timeRange.from));

// --- the waterfall ------------------------------------------------------

/**
 * Calls grouped by the screen that was showing when each one started.
 *
 * The join is the point: screens and traffic are recorded by different mechanisms that share
 * only a clock, and nothing until now put them together. "This screen costs six calls and two
 * seconds" is not in either stream alone.
 *
 * Grouping is `visits()`, shared with the path view and the axis band. It used to be a private
 * segmentation here that read the *filtered* signals and dropped any screen with no matching
 * calls — so typing `path:` took every screen away, and a filter that matched nothing on Cart drew
 * a route that skipped it. A visit with no matching calls is now a stub.
 */
function waterfallGroups() {
  const win = sessionWindow();
  if (!win) return [];
  const rows = visibleTransactions();
  if (!rows.length) return [];

  const colour = screenColours();
  const lastMono = win.to;
  return visits(state.screens, rows, [], [], sessionStartMono()).map((visit) => {
    const name = visit.screen ? visit.screen.name : null;
    const group = {
      segment: { name, from: visit.from, to: visit.to ?? lastMono, colour: colour(name) },
      rows: visit.txns,
      dim: !visitInRange(visit),
    };
    if (group.rows.length) {
      const starts = group.rows.map((t) => t.mono);
      const ends = group.rows.map((t) => t.mono + (t.ms ?? 0));
      group.from = Math.min(...starts);
      group.to = Math.max(...ends);
    } else {
      group.from = group.segment.from;
      group.to = group.segment.from;
    }
    group.span = Math.max(1, group.to - group.from);
    group.bytes = group.rows.reduce((n, t) => n + (t.resBytes || 0), 0);
    group.failed = group.rows.filter((t) => t.error || (t.status ?? 0) >= 400).length;
    // Wall time is not the sum of the durations: calls overlap, and reporting the sum would
    // claim a screen took far longer than the user waited.
    group.wall = group.to - group.from;
    return group;
  });
}

function renderWaterfall() {
  if (state.view !== 'waterfall') return;
  const root = $('waterfall');
  root.innerHTML = '';
  const groups = waterfallGroups();
  $('waterfall-empty').hidden = groups.length > 0;
  if (!groups.length) return;

  for (const group of groups) {
    const block = el('div', `wf-group${group.dim ? ' wf-dim' : ''}${group.rows.length ? '' : ' wf-stub'}`);

    const head = el('div', 'wf-head');
    const swatch = el('span', 'wf-swatch');
    swatch.style.background = group.segment.colour;
    head.appendChild(swatch);
    head.appendChild(el('span', 'wf-name', group.segment.name || 'before the first screen'));
    const stats = el('span', 'wf-stats muted mono');
    stats.textContent = `${group.rows.length} calls · ${fmtMs(group.wall)} · ${fmtBytes(group.bytes)}`;
    head.appendChild(stats);
    if (group.failed) head.appendChild(el('span', 'wf-failed', `${group.failed} failed`));
    block.appendChild(head);
    // Kept rather than dropped: a route with a hole in it names a navigation nobody made.
    if (!group.rows.length) {
      block.appendChild(el('div', 'wf-empty muted', state.filter || state.timeRange ? 'no matching calls' : 'no calls'));
    }

    // Each group gets its own scale. A shared one would squeeze a 200 ms screen into a sliver
    // next to a 30 s one, and the question here is "what did *this* screen do", not "which
    // screen was longest" — the stats line answers that.
    for (const txn of group.rows) {
      const row = el('div', `wf-row${txn.id === state.selectedId ? ' selected' : ''}`);
      row.dataset.id = txn.id;
      row.tabIndex = 0;

      row.appendChild(el('span', `method ${methodClass(txn.method)}`, txn.method));
      const path = el('span', 'wf-path');
      // See `.wf-path`: isolated so the RTL box trims the prefix without reordering the path.
      const isolated = el('bdi', null, txn.path);
      isolated.dir = 'ltr';
      path.appendChild(isolated);
      path.title = txn.path;
      row.appendChild(path);

      const track = el('span', 'wf-track');
      const left = ((txn.mono - group.from) / group.span) * 100;
      const width = ((txn.ms ?? 0) / group.span) * 100;
      const bar = el('span', `wf-bar s${statusClass(txn.status)}`);
      bar.style.left = `${left}%`;
      // Never zero-width: an instant call is still a call, and one that vanishes reads as a
      // row that failed to draw.
      bar.style.width = `${Math.max(0.8, width)}%`;
      bar.title = `${fmtMs(txn.ms)} · started ${txn.mono - group.from}ms into this screen`;
      track.appendChild(bar);
      row.appendChild(track);

      row.appendChild(el('span', 'wf-ms muted mono', fmtMs(txn.ms)));
      row.addEventListener('click', () => select(txn.id));
      row.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); select(txn.id); }
      });
      block.appendChild(row);
    }
    root.appendChild(block);
  }
}
