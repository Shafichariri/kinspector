/* axis: the time axis and its range selection */
'use strict';

// --- the time axis ------------------------------------------------------

const AXIS_BUCKETS = 200;
const AXIS_W = 1000;          // viewBox units; the element itself stretches to its container
// Bars and the screen band as one 24px strip, the height of the overview line it shares with the
// hint and the now strip. It was 37px over a foot line of its own.
const AXIS_BARS_H = 18;
const AXIS_BAND_H = 6;
const AXIS_H = AXIS_BARS_H + AXIS_BAND_H;

const svgEl = (tag, attrs = {}) => {
  const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, String(v));
  return node;
};

/**
 * One colour per screen, assigned by first appearance rather than hashed.
 *
 * A hash gives a screen the same colour forever, which sounds better than it is: two screens
 * whose names hash adjacent become indistinguishable, and you cannot fix it. Ordering means the
 * first few screens of a session are always maximally far apart, which is what you are actually
 * looking at.
 */
const SCREEN_COLOURS = [
  '#6aa9ff', '#c08cff', '#59c98a', '#ffb454', '#ff7b8a', '#4fd1c5', '#f6c945', '#9aa7ff',
];

/**
 * The wall-clock span of the session, and the `mono` window it maps onto.
 *
 * A loop, never `Math.min(...monos)`: spreading a row array into an argument list throws a
 * RangeError somewhere past 110,000 rows, and this runs before anything is drawn, so a long
 * session drew a blank page stuck at "connecting…". No row array is spread into a call anywhere
 * in `web/js`; `check-web-static.js`'s `no argument spread` holds it.
 */
function sessionWindow() {
  let from = Infinity;
  let to = -Infinity;
  for (const rows of [state.allTransactions, state.allSignals, state.markers]) {
    for (const row of rows) {
      if (row.mono < from) from = row.mono;
      if (row.mono > to) to = row.mono;
    }
  }
  if (from === Infinity) return null;
  return { from, to, span: Math.max(1, to - from) };
}

/**
 * Which screen was on at each moment, as segments, for the axis band.
 *
 * `visits()` over the whole session. With no screen signals at all there is no band: one grey
 * stretch would say nothing the bars above it do not.
 */
function screenSegments(win) {
  if (!win) return [];
  const list = visits(state.screens, state.allTransactions, state.markers, state.allSignals);
  if (!list.some((v) => v.screen)) return [];
  const colour = screenColours();
  return list
    .map((v) => {
      const name = v.screen ? v.screen.name : null;
      return { name, from: v.from, to: v.to ?? win.to, colour: colour(name) };
    })
    .filter((seg) => seg.to > seg.from);
}

function renderAxis() {
  const host = $('axis');
  const win = sessionWindow();
  // Every view built from transactions on a clock, which the waterfall is: brushing already
  // narrows it, so hiding the control that does the brushing would be the odd choice.
  const onTimeView = ['all', 'network', 'waterfall', 'path'].includes(state.view);
  host.hidden = !onTimeView || !win || state.allTransactions.length + state.allSignals.length < 2;
  // The hint sits beside the axis rather than inside it, and goes with it.
  $('axis-hint').hidden = host.hidden;
  syncOverview();
  if (host.hidden) return;

  const svg = $('axis-svg');
  svg.setAttribute('viewBox', `0 0 ${AXIS_W} ${AXIS_H}`);
  svg.innerHTML = '';
  const x = (mono) => ((mono - win.from) / win.span) * AXIS_W;

  // The shape is drawn from the *whole* session, never the filtered set. A map that redraws
  // itself every time you filter cannot answer "where am I", which is what it is for.
  const buckets = new Array(AXIS_BUCKETS).fill(null).map(() => ({ all: 0, hit: 0, bad: 0 }));
  const slot = (mono) =>
    Math.min(AXIS_BUCKETS - 1, Math.floor(((mono - win.from) / win.span) * AXIS_BUCKETS));
  const matching = new Set(state.transactions.map((t) => t.id));
  for (const txn of state.allTransactions) {
    const b = buckets[slot(txn.mono)];
    b.all++;
    if (matching.has(txn.id)) b.hit++;
    if (txn.error || (txn.status ?? 0) >= 400) b.bad++;
  }
  const tallest = buckets.reduce((n, b) => (b.all > n ? b.all : n), 1);
  const bw = AXIS_W / AXIS_BUCKETS;

  for (let i = 0; i < AXIS_BUCKETS; i++) {
    const b = buckets[i];
    if (!b.all) continue;
    const h = Math.max(1.5, (b.all / tallest) * AXIS_BARS_H);
    const bx = i * bw;
    // Three layers, because they answer three different questions: how busy, how much of it
    // your filter kept, and how much of it failed.
    svg.appendChild(svgEl('rect', {
      x: bx, y: AXIS_BARS_H - h, width: Math.max(1, bw - 0.4), height: h, class: 'axis-bar-all',
    }));
    if (b.hit) {
      const hh = Math.max(1.5, (b.hit / tallest) * AXIS_BARS_H);
      svg.appendChild(svgEl('rect', {
        x: bx, y: AXIS_BARS_H - hh, width: Math.max(1, bw - 0.4), height: hh, class: 'axis-bar-hit',
      }));
    }
    if (b.bad) {
      const bh = Math.max(1.5, (b.bad / tallest) * AXIS_BARS_H);
      svg.appendChild(svgEl('rect', {
        x: bx, y: AXIS_BARS_H - bh, width: Math.max(1, bw - 0.4), height: bh, class: 'axis-bar-bad',
      }));
    }
  }

  // The screen band ties the two halves of this release together: the bars say when the app was
  // busy, the band says where it was while it was.
  for (const seg of screenSegments(win)) {
    const rect = svgEl('rect', {
      x: x(seg.from), y: AXIS_BARS_H + 1, width: Math.max(1, x(seg.to) - x(seg.from)),
      height: AXIS_BAND_H - 1, fill: seg.colour, class: 'axis-screen',
    });
    rect.appendChild(svgEl('title')).textContent = seg.name
      ? `${seg.name} — ${fmtMs(seg.to - seg.from)}`
      : 'before the first screen signal';
    svg.appendChild(rect);
  }

  for (const marker of state.markers) {
    const mx = x(marker.mono);
    svg.appendChild(svgEl('line', { x1: mx, y1: 0, x2: mx, y2: AXIS_BARS_H, class: 'axis-marker' }));
    const flag = svgEl('rect', { x: mx - 2, y: 0, width: 4, height: 4, class: 'axis-marker-flag' });
    flag.appendChild(svgEl('title')).textContent = marker.label;
    svg.appendChild(flag);
  }

  if (state.timeRange) {
    const from = x(state.timeRange.from);
    const to = x(state.timeRange.to);
    // Dim what is excluded rather than tint what is kept: the selection should read as the
    // normal view with the rest pushed back, not as a highlight over an unchanged chart.
    svg.appendChild(svgEl('rect', { x: 0, y: 0, width: Math.max(0, from), height: AXIS_H, class: 'axis-mask' }));
    svg.appendChild(svgEl('rect', { x: to, y: 0, width: Math.max(0, AXIS_W - to), height: AXIS_H, class: 'axis-mask' }));
    svg.appendChild(svgEl('line', { x1: from, y1: 0, x2: from, y2: AXIS_H, class: 'axis-edge' }));
    svg.appendChild(svgEl('line', { x1: to, y1: 0, x2: to, y2: AXIS_H, class: 'axis-edge' }));
  }

  renderAxisFoot(win);
}

function renderAxisFoot(win) {
  const clock = (mono) => {
    // `mono` is the device's monotonic clock and means nothing by itself. Anchor it to the
    // wall-clock time of the nearest row that carries both, which every row does.
    const anchor = state.allTransactions[0] || state.allSignals[0];
    if (!anchor) return '';
    const at = Date.parse(anchor.ts);
    if (!Number.isFinite(at)) return '';
    return new Date(at + (mono - anchor.mono)).toLocaleTimeString();
  };
  $('axis-from').textContent = clock(win.from);
  $('axis-to').textContent = clock(win.to);

  const hint = $('axis-hint');
  hint.innerHTML = '';
  // The text in a span of its own, so it ellipsizes inside the flex line rather than pushing the
  // now strip off it. The count is the session's, as the bars are, never the filtered rows'.
  if (!state.timeRange) {
    hint.className = 'axis-hint muted';
    hint.appendChild(el('span', null, `${state.allTransactions.length} calls in this session · ${fmtMs(win.span)} — drag to narrow`));
    return;
  }
  hint.className = 'axis-hint';
  // The session's calls in the stretch, as the bars draw them: the rows held would count only the
  // newest 1000 on a session past the list's ceiling, and only the matches under a filter.
  let inStretch = 0;
  for (const t of state.allTransactions) if (inRange(t.mono)) inStretch++;
  const label = el('span', null, `${inStretch} calls in this stretch · ${clock(state.timeRange.from)} – ${clock(state.timeRange.to)}`);
  const clear = el('button', 'btn btn-sm btn-quiet', 'show all');
  clear.addEventListener('click', () => setTimeRange(null));
  hint.appendChild(label);
  hint.appendChild(clear);
}

/**
 * The overview line holds the axis and, on the timeline, the now strip; it is hidden when both are,
 * so an empty band never takes a row's height. A now strip hidden — another view, or a session that
 * holds nothing now — closes its list, which would otherwise come back open over other rows.
 */
function syncOverview() {
  if ($('now-strip').hidden && !$('current').hidden) {
    $('current').hidden = true;
    $('now-toggle').setAttribute('aria-expanded', 'false');
  }
  $('overview').hidden = $('axis').hidden && $('now-strip').hidden;
}

let axisPaintSuspended = false;

function setTimeRange(range) {
  state.timeRange = range;
  renderAxis();
  renderCounts();
  axisPaintSuspended = true;
  // A brushed stretch is a new list, which the timeline follows from its newest end.
  state.timelineFresh = true;
  try {
    renderList();
    renderTimeline();
    renderWaterfall();
    renderPath();
  } finally {
    axisPaintSuspended = false;
  }
  if ($('detail').hidden) renderSessionGlance();
}

/**
 * Drag a stretch, click to clear.
 *
 * Wired once against the container rather than per render: the SVG is rebuilt on every live
 * signal, and listeners attached to its children would be replaced mid-drag.
 */
function wireAxis() {
  const host = $('axis');
  const svg = $('axis-svg');
  let anchor = null;

  const monoAt = (event) => {
    const win = sessionWindow();
    if (!win) return null;
    const box = svg.getBoundingClientRect();
    // A zero-width box means the element is not laid out — in a headless render, or before
    // first paint. Dividing by it yields NaN, and a NaN range silently filters everything out.
    if (!box.width) return null;
    const ratio = Math.min(1, Math.max(0, (event.clientX - box.left) / box.width));
    return win.from + ratio * win.span;
  };

  host.addEventListener('pointerdown', (event) => {
    if (event.target.closest('button')) return;
    anchor = monoAt(event);
    if (anchor === null) return;
    // Capture keeps the drag alive when the pointer leaves the strip, which it will — the strip
    // is 37px tall. Not fatal if unavailable, so not worth failing the drag over.
    try { host.setPointerCapture(event.pointerId); } catch { /* no capture, drag still works */ }
  });

  host.addEventListener('pointermove', (event) => {
    if (anchor === null) return;
    const now = monoAt(event);
    if (now === null) return;
    setTimeRange({ from: Math.min(anchor, now), to: Math.max(anchor, now) });
  });

  const finish = (event) => {
    if (anchor === null) return;
    const now = monoAt(event);
    const win = sessionWindow();
    // A click is a drag of nothing. Treat anything under 1% of the session as "show me all of
    // it again" rather than selecting a sliver nobody could have aimed at.
    if (win && now !== null && Math.abs(now - anchor) < win.span * 0.01) setTimeRange(null);
    anchor = null;
  };
  host.addEventListener('pointerup', finish);
  host.addEventListener('pointercancel', finish);
}
