/* timeline: runs, the signal timeline, now and signal selection */
'use strict';

/**
 * Spans for observations that claim to stay true.
 *
 * A `screen` signal is true at an instant; a `cache` snapshot claims to be true from its `mono`
 * until the next observation of the same `(tag, name)`. That difference is not in the schema
 * because it is derivable - consecutive rows for a key define the intervals - so it is computed
 * here, where it is a rendering rule.
 */
function spanEndsFor(signals) {
  const ends = new Map();
  const byKey = new Map();
  for (const s of [...signals].sort((a, b) => a.mono - b.mono)) {
    const key = `${s.tag} ${s.name}`;
    const previous = byKey.get(key);
    if (previous) ends.set(previous.id, s.mono);
    byKey.set(key, s);
  }
  return ends;
}

// @mirror-begin timelineRuns
/**
 * How many adjacent identical observations it takes before they are worth collapsing.
 *
 * Two is not clutter and hiding it behind a twisty costs more than it saves. Three is where a
 * run starts pushing other lanes off the screen.
 */
const RUN_THRESHOLD = 3;

/** What makes two adjacent rows "the same thing happening again". Transactions never group. */
const runKeyOf = (entry) =>
  entry.kind === 'signal' ? `${entry.signal.tag}\u0000${entry.signal.name}` : null;

/**
 * Groups *consecutive* identical observations.
 *
 * A state holder that emits on every keystroke produces dozens of adjacent rows differing only
 * in a payload you cannot see from the row — one real session had 48 in a row — and they push
 * the traffic the timeline exists to correlate clean off the screen.
 *
 * Only adjacent rows group. A run interrupted by a call or a screen change is information: it
 * says the state settled, something else happened, and it moved again. Collapsing across that
 * gap would erase the very ordering the merged view is for.
 */
function timelineRuns(entries) {
  const runs = [];
  for (const entry of entries) {
    const key = runKeyOf(entry);
    const open = runs[runs.length - 1];
    if (key !== null && open && open.key === key) open.entries.push(entry);
    else runs.push({ key, entries: [entry] });
  }
  return runs;
}

/**
 * Mirror of `TimelineRun.id`: keyed on the run's **earliest** member, never its head.
 *
 * A run grows at its newest end, so in newest-first order the head is the newest observation and
 * changes on every one — an id taken from it re-keyed the run and collapsed an expanded run under
 * the reader during live tail, which is exactly when somebody is watching it. The earliest member
 * also survives flipping the order. The first minimum in draw order wins a tie, as `minBy` does.
 */
const runIdOf = (run) => {
  let earliest = run.entries[0];
  for (const entry of run.entries) if (entry.mono < earliest.mono) earliest = entry;
  const token = earliest.kind === 'signal' ? earliest.signal.id : String(earliest.mono);
  return `${run.key ?? ''}\u0000${token}`;
};
// @mirror-end timelineRuns

/**
 * The merged timeline. `follow` is false for a redraw the reader asked for — a run expanded or
 * collapsed — which must leave the pane where it is.
 */
function renderTimeline({ follow = true } = {}) {
  if (state.view !== 'all') return;
  const root = $('timeline');
  const pane = root.parentElement;
  // Measured before the rows are replaced. The timeline flows in the pane since KIN-33, so a follow
  // here scrolls the whole pane: following on every redraw threw a reader who expanded a run in the
  // middle of a 3016-call session to its end. It follows only when the pane was already at the end
  // it follows, or the view was just opened, as the path's log does.
  const atEnd = state.newestFirst
    ? pane.scrollTop <= 8
    : pane.scrollTop + pane.clientHeight >= pane.scrollHeight - 8;
  const fresh = state.timelineFresh;
  state.timelineFresh = false;
  root.innerHTML = '';

  const ends = spanEndsFor(state.allSignals);
  // Through `timelineEntries`, the mirror of the model's `timeline()`, so a marker at exactly a
  // call's `mono` is drawn before it. One stable sort over all three streams with markers added
  // last put a tied marker after the call it introduced — the opposite of the list beside it.
  const calls = state.transactions.filter((txn) => inRange(txn.mono));
  const signals = state.signals.filter((signal) => inRange(signal.mono));
  const entries = timelineEntries(calls, state.markers.filter((marker) => inRange(marker.mono)), signals);

  if (state.newestFirst) entries.reverse();

  // Empty is counted on calls and signals, never markers: markers ignore the filter, so a marker in
  // range hid the empty state of a pane the filter had emptied. And what the filter took out is
  // said, emptied or only thinned, because rows that silently vanish read as rows never recorded.
  const empty = $('timeline-empty');
  const hidden = state.filter ? hiddenByFilter({ ranged: true }) : { calls: 0, signals: 0 };
  const filtered = hidden.calls + hidden.signals > 0;
  empty.hidden = calls.length + signals.length > 0;
  if (!empty.hidden) {
    if (filtered) filterNote(empty, hidden, true);
    else empty.textContent = state.timeRange ? 'nothing in the selected stretch' : 'nothing was recorded in this session';
  } else if (filtered) {
    root.appendChild(filterNote(el('div', 'filter-note muted'), hidden, false));
  }

  // Spans are scaled against the session's own duration, not a fixed divisor. A fixed one
  // made a cache snapshot held for 230ms draw narrower than an instantaneous screen event,
  // which reads as the opposite of what it means.
  let first = entries.length ? Infinity : 0;
  let last = entries.length ? -Infinity : 0;
  for (const e of entries) {
    if (e.mono < first) first = e.mono;
    if (e.mono > last) last = e.mono;
  }
  const span = Math.max(1, last - first);
  for (const run of timelineRuns(entries)) {
    const collapsible = run.entries.length >= RUN_THRESHOLD;
    if (collapsible && !state.expandedRuns.has(runIdOf(run))) {
      root.appendChild(runRow(run));
      continue;
    }
    if (collapsible) root.appendChild(runRow(run, { expanded: true }));
    for (const entry of run.entries) root.appendChild(timelineRow(entry, ends, last, span));
  }

  if (state.liveTail && follow && (fresh || atEnd)) {
    // An empty timeline has nothing newest to follow, and its note sits above it: following the
    // foot of the pane scrolled the note out of sight under whatever markers were left.
    pane.scrollTop = state.newestFirst || !empty.hidden ? 0 : pane.scrollHeight;
  }
}

/**
 * One row standing in for a run of identical observations, or the header above an expanded one.
 *
 * It reports the count and the wall of time the run covers, which is the part a collapsed run
 * must not lose: "48 times" and "48 times over 23 seconds" mean different things about the app.
 */
function runRow(run, { expanded = false } = {}) {
  const first = run.entries[0].signal;
  const lane = laneFor(first.tag);
  const node = el('div', `tl-row tl-run lane-${lane}${expanded ? ' tl-run-open' : ''}`);
  node.dataset.kind = 'run';
  node.dataset.lane = lane;
  node.dataset.tag = first.tag;
  node.dataset.runId = runIdOf(run);
  node.tabIndex = 0;

  node.appendChild(el('span', 'tl-twisty', expanded ? '\u25be' : '\u25b8'));
  node.appendChild(el('span', 'tl-tag', first.tag));
  node.appendChild(el('span', 'tl-name', first.name));
  node.appendChild(el('span', 'tl-run-count mono', `\u00d7${run.entries.length}`));

  let from = Infinity;
  let to = -Infinity;
  for (const entry of run.entries) {
    if (entry.mono < from) from = entry.mono;
    if (entry.mono > to) to = entry.mono;
  }
  const held = el('span', 'tl-mono muted mono', from === to ? `${from}ms` : `${from}\u2013${to}ms`);
  held.title = expanded
    ? 'collapse these observations'
    : `${run.entries.length} observations over ${to - from} ms — click to expand`;
  node.appendChild(held);

  const toggle = () => {
    const id = runIdOf(run);
    if (state.expandedRuns.has(id)) state.expandedRuns.delete(id);
    else state.expandedRuns.add(id);
    renderTimeline({ follow: false });
  };
  node.addEventListener('click', toggle);
  node.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      toggle();
    }
  });
  return node;
}

function timelineRow(entry, ends, lastMono, sessionSpan) {
  if (entry.kind === 'marker') {
    const node = el('div', 'marker-divider', entry.marker.label);
    node.dataset.kind = 'marker';
    return node;
  }

  if (entry.kind === 'txn') {
    const node = rowFor(entry.txn);
    node.classList.add('tl-row');
    node.dataset.kind = 'txn';
    node.dataset.lane = 'traffic';
    return node;
  }

  const signal = entry.signal;
  const lane = laneFor(signal.tag);
  // Deliberately not `.row`: that class means "a transaction row", and `select()` matches
  // on dataset.id across all of them. Signal and transaction ids come from the same 8-hex
  // generator, so sharing the class would let a collision highlight the wrong thing.
  const node = el('div', `tl-row tl-signal lane-${lane}`);
  node.dataset.kind = 'signal';
  node.dataset.lane = lane;
  node.dataset.tag = signal.tag;
  node.dataset.id = signal.id;
  node.tabIndex = 0;

  // A point observation gets a dot; an interval claim gets a bar, drawn to the next observation
  // of the same key or to the end of what we have.
  const end = ends.get(signal.id);
  const isSpan = lane === 'cache' || lane === 'session';
  const glyph = el('span', `tl-glyph ${isSpan ? 'tl-span' : 'tl-point'}`);
  if (isSpan) {
    // Never narrower than a point glyph: an interval that looks smaller than an instant is
    // actively misleading.
    const held = (end ?? lastMono) - signal.mono;
    const width = 10 + Math.round(Math.min(1, held / sessionSpan) * 90);
    glyph.style.width = `${width}px`;
    glyph.title = end === undefined
      ? 'still current as far as this session knows'
      : `held for ${end - signal.mono} ms`;
  }
  node.appendChild(glyph);

  node.appendChild(el('span', 'tl-tag', signal.tag));
  node.appendChild(el('span', 'tl-name', signal.name));

  // Provenance, always visible. An app-start snapshot reported as live state is the exact
  // failure `trigger` exists to prevent, so the UI never leaves it to be inferred.
  const trigger = signal.trigger === 'request' ? 'pulled' : 'pushed';
  const badge = el('span', `tl-trigger tl-${trigger}`, trigger);
  badge.title = trigger === 'pulled'
    ? 'the host asked for this value'
    : 'the app pushed this and has not re-read it since';
  node.appendChild(badge);

  if (signal.dataRef) node.appendChild(el('span', 'tl-bytes muted mono', fmtBytes(signal.bytes)));
  node.appendChild(el('span', 'tl-mono muted mono', `${signal.mono}ms`));

  node.addEventListener('click', () => selectSignal(signal.id));
  return node;
}

/**
 * The rail panel answering "what screen, what is cached" without reading any rows.
 *
 * The last observation of each `(tag, name)`, whatever tab you are on — which is the point of
 * it being in the rail rather than inside one view.
 */
function renderCurrent() {
  const list = $('current');
  const rows = state.current;
  $('now-strip').hidden = state.view !== 'all' || rows.length === 0;
  list.innerHTML = '';

  // Collapsed, the strip has to earn its line: how much there is, and how stale the oldest of
  // it is — which is the question the panel exists to answer and the one a count alone dodges.
  const oldest = rows.reduce(
    (worst, signal) => Math.max(worst, ageOf(signal.ts) ?? 0),
    0,
  );
  const byTag = new Map();
  for (const signal of rows) byTag.set(signal.tag, (byTag.get(signal.tag) || 0) + 1);
  const parts = [...byTag].map(([tag, n]) => `${n} ${tag}`);
  $('now-summary-text').textContent = rows.length
    ? `${parts.join(' · ')} — oldest ${fmtAge(oldest)}`
    : '';

  for (const signal of rows) {
    const item = el('li', 'current-item');
    item.dataset.tag = signal.tag;
    item.appendChild(el('span', 'tl-tag', signal.tag));
    item.appendChild(el('span', 'current-name', signal.name));
    item.appendChild(
      signal.trigger === 'request'
        ? el('span', 'tl-trigger tl-pulled', 'pulled')
        : el('span', 'tl-trigger tl-pushed', 'pushed'),
    );
    item.appendChild(ageNode(signal.ts, 'current-age muted'));
    item.addEventListener('click', () => selectSignal(signal.id));
    list.appendChild(item);
  }
}

/** Renders one signal into the detail pane, fetching its payload on demand. */
async function selectSignal(id) {
  state.selectedSignalId = id;
  state.selectedId = null;
  const signal =
    state.allSignals.find((s) => s.id === id) ||
    state.current.find((s) => s.id === id);
  if (!signal) return;

  for (const node of document.querySelectorAll('.tl-signal.sel, .pv-sig.sel, .pv-chip-sig.sel')) node.classList.remove('sel');
  for (const row of document.querySelectorAll(`.tl-signal[data-id="${id}"], .pv-sig[data-id="${id}"], .pv-chip-sig[data-id="${id}"]`)) {
    row.classList.add('sel');
  }

  const detail = $('detail');
  $('detail-empty').hidden = true;
  detail.hidden = false;
  detail.innerHTML = '';
  openDrawer();

  const head = el('div', 'detail-head');
  head.appendChild(el('span', 'tl-tag', signal.tag));
  head.appendChild(el('span', 'detail-title', signal.name));
  detail.appendChild(head);

  const trigger = signal.trigger === 'request' ? 'pulled on demand' : 'pushed by the app';
  detail.appendChild(
    el('div', 'detail-meta muted mono', `${signal.ts} - mono ${signal.mono}ms - ${trigger}`),
  );

  if (signal.trigger !== 'request') {
    detail.appendChild(
      el(
        'div',
        'detail-note muted',
        'Pushed by the app at that moment. It has not been re-read since, so this is not ' +
          'necessarily what the app holds now.',
      ),
    );
  }

  if (!signal.dataRef) {
    detail.appendChild(el('div', 'empty muted', 'no payload was captured for this signal'));
    return;
  }

  const pre = el('pre', 'body');
  pre.textContent = 'loading...';
  detail.appendChild(pre);

  const url =
    `/api/sessions/${encodeURIComponent(state.sessionId)}` +
    `/signals/${encodeURIComponent(id)}/data`;
  try {
    const res = await source.fetch(url);
    const text = await res.text();
    // Payloads are captured verbatim and never redacted; this shows what was recorded.
    const tree = res.ok ? jsonTree(text, `sig:${id}`, `Payload · ${fmtBytes(new TextEncoder().encode(text).length)}`) : null;
    if (tree) pre.replaceWith(tree);
    else pre.textContent = prettyJson(text);
  } catch (e) {
    pre.textContent = `could not read the payload: ${e.message}`;
  }
}



/**
 * Re-reads the current-state panel. Cheap, and the derivation lives on the daemon.
 *
 * A live signal starts one, so with traffic a read is often in flight when the session changes, and
 * two can be in flight for one session. `readCurrent` decides which answer the strip shows: the
 * later-started read for the session still selected, and on a failure the values the strip held
 * when they were this session's.
 */
async function refreshCurrent() {
  if (!state.sessionId) return;
  await readCurrent(state.sessionId);
  renderCurrent();
}
