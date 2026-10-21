/* path: the map of screens and the log of visits */
'use strict';

// --- the path -----------------------------------------------------------

/**
 * How many entries a visit shows before folding.
 *
 * A dashboard visit can fire thirty calls, and one tall column would turn the storyboard into a
 * single list you scroll past the rest of. A collapsed run of identical signals counts as one.
 */
const PATH_FOLD = 15;

const isFailure = (txn) => Boolean(txn.error) || (txn.status ?? 0) >= 400;

/** Fetches the captured text of every screen payload not yet held. */
async function loadScreenPayloads() {
  if (!state.sessionId) return;
  const id = encodeURIComponent(state.sessionId);
  const missing = state.screens.filter((s) => s.dataRef && !state.screenPayloadText.has(s.id));
  await Promise.all(missing.map(async (signal) => {
    try {
      const res = await source.fetch(`/api/sessions/${id}/signals/${encodeURIComponent(signal.id)}/data`);
      state.screenPayloadText.set(signal.id, res.ok ? await res.text() : null);
    } catch {
      // Nothing to compare is not an error; recording it stops a retry on every render.
      state.screenPayloadText.set(signal.id, null);
    }
  }));
}

/**
 * How many items a collapsed log row draws as chips before it summarises instead.
 *
 * Under it, the row *is* the visit, read left to right. Over it, a chip per call would wrap into a
 * wall, so calls become one count — but failures still get a chip each, grouped by endpoint.
 */
const LOG_CHIPS = 8;

/**
 * The route as a map on top and a log beneath: one station per visit, then one line per visit.
 *
 * Built from `visits()`, the rule the waterfall and the axis band also use. The decisions that
 * are not layout:
 *
 * - **The order toggle does not apply.** A path has one true order; reversed, it reads as the
 *   user walking backwards.
 * - **The filter narrows what is inside a visit, never which visits there are.** The skeleton is
 *   `state.screens`, and a visit nothing matched is drawn as a stub — dropping it would draw a
 *   route the user never took. A brushed time range dims visits for the same reason.
 * - **A failure is never summarised away.** A collapsed row names every failed call; an expanded
 *   one floats a failure above the fold.
 * - **A live view does not move under the reader.** It follows only when the log is already at
 *   its end; scrolled back to read an earlier visit, a new one is announced instead.
 */
function renderPath() {
  if (state.view !== 'path') return;
  const root = $('path');
  const pane = root.parentElement;
  if (state.pathSessionId !== state.sessionId) {
    state.pathSessionId = state.sessionId;
    state.pathVisitCount = null;
    state.expandedVisits = new Set();
    state.pathFocus = null;
    state.pathFresh = true;
    $('path-new').hidden = true;
  }
  const oldMetro = root.querySelector('.pv-metro');
  const metroAtEnd = !oldMetro || oldMetro.scrollLeft + oldMetro.clientWidth >= oldMetro.scrollWidth - 8;
  const metroLeft = oldMetro ? oldMetro.scrollLeft : 0;
  const logAtEnd = pane.scrollTop + pane.clientHeight >= pane.scrollHeight - 8;
  const top = pane.scrollTop;

  const list = visits(state.screens, state.transactions, state.markers, state.signals, sessionStartMono());
  root.innerHTML = '';
  if (!list.length || sessionPending()) {
    saysBeingRead(root.appendChild(el('div', 'empty muted pv-empty', state.sessionId ? 'nothing recorded in this session yet' : 'no session')));
    state.pathVisitCount = sessionPending() ? null : 0;
    return;
  }

  if (state.filter) countRecorded(list);
  markUnloaded(list);
  const header = el('div', 'pv-top');
  const hidden = state.filter ? hiddenByFilter({ screens: false }) : { calls: 0, signals: 0 };
  if (hidden.calls + hidden.signals) header.appendChild(filterNote(el('div', 'filter-note muted'), hidden, false));

  const colour = screenColours();
  let lastMono = -Infinity;
  for (const rows of [state.allTransactions, state.allSignals, state.markers]) {
    for (const x of rows) if (x.mono > lastMono) lastMono = x.mono;
  }
  // Tag colours go out in the order tags first happened, not the order they are drawn in.
  for (const s of [...state.allSignals].sort((a, b) => a.mono - b.mono)) tagColour(tagOf(s));
  const metro = el('div', 'pv-metro');
  metro.setAttribute('role', 'list');
  metro.setAttribute('aria-label', 'Screens visited');
  const log = el('div', 'pv-log');
  list.forEach((visit, index) => {
    metro.appendChild(pathStop(visit, index, list, colour));
    log.appendChild(pathRow(visit, index, list, colour, lastMono));
  });
  header.appendChild(metro);
  root.appendChild(header);
  root.appendChild(log);

  const fresh = state.pathFresh;
  state.pathFresh = false;
  const follow = state.liveTail && (fresh || logAtEnd);
  pane.scrollTop = follow ? pane.scrollHeight : fresh ? 0 : top;
  metro.scrollLeft = state.liveTail && (fresh || metroAtEnd) ? metro.scrollWidth : metroLeft;

  const grew = state.pathVisitCount !== null && list.length > state.pathVisitCount;
  state.pathVisitCount = list.length;
  if (follow) {
    $('path-new').hidden = true;
  } else if (grew) {
    const newest = list[list.length - 1];
    const pill = $('path-new');
    pill.textContent = `new visit: ${newest.screen ? newest.screen.name : 'unnamed'} ↓`;
    pill.hidden = false;
  }
}

/**
 * Under a filter, each visit also carries how much it recorded, from the same skeleton over the
 * whole session — so an emptied visit reads "0 of 9 match" rather than "nothing recorded". The
 * whole-session copies are read past the route's clamp, so N counts every call the visit holds.
 */
function countRecorded(list) {
  const whole = visits(state.screens, state.allTransactions, state.markers, state.allSignals, sessionStartMono());
  if (whole.length !== list.length) return;
  list.forEach((visit, i) => { visit.recorded = visitSegments(whole[i]).reduce((n, s) => n + s.count, 0); });
}

/**
 * Marks each visit holding a call, in the whole-session skeleton, older than the oldest the list
 * holds, while the list is short of what the route matched: it stops at the clamp, so whether those
 * calls match is unknown. Counting the held rows said "0 of 5 match" of visits whose calls matched.
 */
function markUnloaded(list) {
  if (state.matched === null || state.transactions.length >= state.matched) return;
  let oldest = Infinity;
  for (const txn of state.transactions) if (txn.mono < oldest) oldest = txn.mono;
  const whole = visits(state.screens, state.allTransactions, [], [], sessionStartMono());
  if (whole.length !== list.length) return;
  list.forEach((visit, i) => { visit.unloaded = whole[i].txns.some((txn) => txn.mono < oldest); });
}

const visitKey = (visit) => (visit.screen ? visit.screen.id : 'leading');

/**
 * What a visit holds, by kind: network calls first, then each signal tag, largest first.
 *
 * "11 calls · 29 signals" said how much happened and not what; a visit that is 20 cache writes
 * and one that is 20 analytics events read the same. A screen re-signalled inside its own visit
 * is a step, not something it held, so it is not counted. Markers are dividers, not contents.
 */
function visitSegments(visit) {
  const byTag = new Map();
  for (const entry of visit.entries) {
    if (entry.kind !== 'signal') continue;
    const tag = tagOf(entry.signal);
    if (visit.screen && tag === 'screen') continue;
    byTag.set(tag, (byTag.get(tag) || 0) + 1);
  }
  const segments = visit.txns.length ? [{ key: 'network', count: visit.txns.length }] : [];
  for (const [key, count] of [...byTag].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))) {
    segments.push({ key, count });
  }
  return segments;
}

/** Lane colours the stylesheet already gives these tags; everything else is an app's own. */
const LANE_TAGS = ['screen', 'state', 'cache', 'session'];
const TAG_SLOTS = 5;

/**
 * The colour an app's own tag wears in this session, or null once the five slots are taken.
 *
 * Handed out in order of first appearance and **remembered for the session**, so a colour
 * follows its tag: a filter that hides one tag must not repaint the rest. A sixth tag stays the
 * generic grey rather than getting a generated hue — past five, colour stops telling tags apart
 * and the words beside the bar are what do.
 */
function tagColour(tag) {
  if (LANE_TAGS.includes(tag)) return null;
  if (state.tagColoursSession !== state.sessionId) {
    state.tagColoursSession = state.sessionId;
    state.tagColours = new Map();
  }
  if (!state.tagColours.has(tag)) {
    const used = [...state.tagColours.values()].filter(Boolean).length;
    state.tagColours.set(tag, used < TAG_SLOTS ? `var(--tag-${used + 1})` : null);
  }
  return state.tagColours.get(tag);
}

const segmentText = (segments) => segments.map((s) => `${s.count} ${s.key}`).join(' · ');

/**
 * What a visit with nothing to show says. "Nothing recorded" only when that is true: under a filter
 * it is "0 of N match", N counted over the whole session by `renderPath`; and where the visit began
 * before the oldest call loaded (`markUnloaded`) neither is known, so it says that instead.
 */
const emptyVisitText = (visit, fallback) =>
  (visit.unloaded ? 'calls not loaded' : visit.recorded ? `0 of ${visit.recorded} match` : fallback);

/** The segments as a thin bar, each kind as wide as its share, in its lane colour. */
function segmentBar(segments) {
  const bar = el('span', 'pv-segbar');
  const total = segments.reduce((n, s) => n + s.count, 0);
  for (const s of segments) {
    const part = el('span', `pv-seg ${s.key === 'network' ? 'pv-seg-network' : `lane-${laneFor(s.key)}`}`);
    const own = s.key === 'network' ? null : tagColour(s.key);
    if (own) part.style.background = own;
    part.style.flexGrow = String(s.count / Math.max(1, total));
    part.title = `${s.count} ${s.key}`;
    bar.appendChild(part);
  }
  return bar;
}

function visitName(visit, list) {
  if (visit.screen) return visit.screen.name;
  return list.some((v) => v.screen) ? 'before first screen' : 'no screen signals in this session';
}

/** How long a visit lasted, or that it is still going. Returns a node, because "now" ticks. */
function visitWhen(visit, lastMono) {
  const node = el('span', 'pv-when muted mono');
  if (visit.to !== null) {
    node.textContent = fmtMs(visit.to - visit.from);
  } else if (sessionIsLive() && visit.screen) {
    node.appendChild(el('span', 'pv-now', 'here now · '));
    node.appendChild(ageNode(visit.screen.ts, 'age'));
  } else {
    node.textContent = `${fmtMs(Math.max(0, lastMono - visit.from))} · to the end`;
  }
  return node;
}

/** One station on the map: the screen, and how much happened there. Clicking opens its line. */
function pathStop(visit, index, list, colour) {
  const key = visitKey(visit);
  const stop = el('button', 'pv-stop');
  stop.type = 'button';
  stop.dataset.visit = String(index);
  stop.dataset.screen = visit.screen ? visit.screen.name : '';
  if (!visit.screen) stop.classList.add('pv-stop-leading');
  if (!visitInRange(visit)) stop.classList.add('pv-dim');
  if (state.pathFocus === key) stop.classList.add('pv-stop-on');
  if (index === list.length - 1 && visit.to === null && sessionIsLive()) stop.classList.add('pv-stop-now');
  stop.style.setProperty('--pv-colour', colour(visit.screen ? visit.screen.name : null));

  const name = el('span', 'pv-stop-name', visit.screen ? visit.screen.name : 'launch');
  if (visit.repeats.length) name.appendChild(el('span', 'pv-times mono', ` ×${visit.repeats.length + 1}`));
  stop.appendChild(name);
  stop.appendChild(el('span', 'pv-stop-dot'));
  // A station is narrow: a bar for the mix, the two biggest kinds in words, the rest on hover.
  const segments = visitSegments(visit);
  if (segments.length) {
    stop.appendChild(segmentBar(segments));
    const shown = segments.slice(0, 2);
    const more = segments.length - shown.length;
    stop.appendChild(el('span', 'pv-stop-stat muted', segmentText(shown) + (more ? ` +${more}` : '')));
  } else {
    stop.appendChild(el('span', 'pv-stop-stat muted', emptyVisitText(visit, 'nothing')));
  }
  const failed = visit.txns.filter(isFailure).length;
  if (failed) stop.appendChild(el('span', 'pv-stop-fail', `${failed} failed`));
  stop.title = `${visitName(visit, list)}${segments.length ? ` — ${segmentText(segments)}` : ''}` +
    `${visit.unloaded && segments.length ? `, ${visit.txns.length ? 'older ' : ''}calls not loaded` : ''}\nopen its line in the log`;

  stop.addEventListener('click', () => {
    state.pathFocus = key;
    state.expandedVisits.add(key);
    renderPath();
    const row = $('path').querySelector(`.pv-visit[data-visit="${index}"]`);
    if (row) scrollBelowMap(row);
  });
  return stop;
}

/**
 * Scrolls the pane so `row` starts just under the sticky box — the scope bar, then the map and any
 * filter note. A fixed `scroll-margin-top` did this and was always wrong by something: the note
 * wraps to two lines on a narrow pane and the bar is there or not, so a clicked station's line
 * landed 27px under the box at 1440 and 41px at 400 under a filter. Measured instead, at click time.
 */
function scrollBelowMap(row) {
  const pane = $('path').parentElement;
  const box = $('path').querySelector('.pv-top');
  const stuck = box ? (parseFloat(getComputedStyle(box).top) || 0) + box.offsetHeight : 0;
  pane.scrollTop += row.getBoundingClientRect().top - pane.getBoundingClientRect().top - stuck;
}

/** One line of the log: the visit's name and length, then what happened there, in order. */
function pathRow(visit, index, list, colour, lastMono) {
  const key = visitKey(visit);
  const open = state.expandedVisits.has(key);
  const row = el('section', 'pv-visit');
  row.dataset.visit = String(index);
  row.dataset.screen = visit.screen ? visit.screen.name : '';
  row.style.setProperty('--pv-colour', colour(visit.screen ? visit.screen.name : null));
  if (!visit.screen) row.classList.add('pv-leading');
  if (!visitInRange(visit)) row.classList.add('pv-dim');
  if (!visit.entries.length) row.classList.add('pv-stub');
  if (open) row.classList.add('pv-open');
  if (state.pathFocus === key) row.classList.add('pv-focus');

  const head = el('div', 'pv-head');
  const title = el('div', 'pv-title');
  const toggle = el('button', 'pv-twisty', open ? '▾' : '▸');
  toggle.type = 'button';
  toggle.setAttribute('aria-expanded', String(open));
  toggle.setAttribute('aria-label', open ? 'collapse this visit' : 'expand this visit');
  toggle.addEventListener('click', () => {
    if (state.expandedVisits.has(key)) state.expandedVisits.delete(key);
    else state.expandedVisits.add(key);
    renderPath();
  });
  title.appendChild(toggle);
  const name = el('span', 'pv-name', visitName(visit, list));
  if (visit.screen) {
    name.classList.add('pv-sig');
    name.dataset.id = visit.screen.id;
    name.tabIndex = 0;
    name.title = 'open this screen signal';
    name.addEventListener('click', () => selectSignal(visit.screen.id));
  }
  title.appendChild(name);
  if (visit.repeats.length) {
    const times = el('span', 'pv-times mono', `×${visit.repeats.length + 1}`);
    times.title = `the app signalled this screen ${visit.repeats.length + 1} times without leaving it`;
    title.appendChild(times);
  }
  head.appendChild(title);
  head.appendChild(visitWhen(visit, lastMono));

  const failed = visit.txns.filter(isFailure).length;
  const stats = el('span', 'pv-stats muted');
  stats.dataset.calls = String(visit.txns.length);
  const segments = segmentText(visitSegments(visit));
  stats.textContent = segments || emptyVisitText(visit, 'nothing recorded');
  if (segments && visit.unloaded) stats.append(visit.txns.length ? ' · older calls not loaded' : ' · calls not loaded');
  if (failed) stats.appendChild(el('span', 'pv-failed', ` · ${failed} failed`));
  head.appendChild(stats);
  if (!list.some((v) => v.screen)) {
    head.appendChild(el(
      'span',
      'pv-hint muted',
      'Emit a `screen` signal when navigation commits to split this into visits — see INTEGRATION.md.',
    ));
  }
  row.appendChild(head);

  if (!visit.entries.length) {
    row.appendChild(el('div', 'pv-nothing muted', visit.unloaded
      ? `calls not loaded — the list holds the newest ${state.transactions.length} of ${state.matched}`
      : visit.recorded ? 'hidden by the filter' : 'nothing recorded'));
    return row;
  }
  row.appendChild(open ? visitBody(visit, index, list) : visitChips(visit, index, list));
  return row;
}

/** Repeated screens in a visit, each mapped to the observation before it, for "what changed". */
function previousScreens(visit) {
  const previous = new Map();
  if (visit.screen) {
    let prior = visit.screen;
    for (const r of visit.repeats) { previous.set(r.id, prior); prior = r; }
  }
  return previous;
}

/** A collapsed visit as a line of chips — every one clickable, every failure named. */
function visitChips(visit, index, list) {
  const line = el('div', 'pv-chips');
  const key = visitKey(visit);
  const runs = timelineRuns(visit.entries);
  const expand = () => { state.expandedVisits.add(key); renderPath(); };

  const callChip = (txn) => {
    const chip = el('button', `pv-chip pv-chip-call${isFailure(txn) ? ' pv-fail' : ''}`);
    chip.type = 'button';
    chip.dataset.id = txn.id;
    if (isFailure(txn)) chip.dataset.failed = '1';
    chip.appendChild(el('span', 'pv-chip-method', txn.method));
    const path = el('span', 'pv-chip-path');
    const isolated = el('bdi', null, txn.path);
    isolated.dir = 'ltr';
    path.appendChild(isolated);
    chip.appendChild(path);
    chip.appendChild(el('span', 'pv-chip-status', String(txn.status ?? 'ERR')));
    chip.title = `${txn.method} ${txn.path}${txn.query ? `?${txn.query}` : ''} · ${fmtMs(txn.ms)}`;
    chip.addEventListener('click', () => select(txn.id));
    return chip;
  };
  const otherChip = (run) => {
    const entry = run.entries[run.entries.length - 1];
    if (entry.kind === 'marker') {
      const chip = el('span', 'pv-chip pv-chip-marker', entry.marker.label);
      chip.title = 'marker';
      return chip;
    }
    const signal = entry.signal;
    const repeat = visit.screen && tagOf(signal) === 'screen';
    const chip = el('button', `pv-chip pv-chip-sig lane-${laneFor(signal.tag)}`);
    chip.type = 'button';
    const own = repeat ? null : tagColour(tagOf(signal));
    if (own) chip.style.borderLeftColor = own;
    chip.dataset.id = signal.id;
    chip.appendChild(el('span', 'pv-chip-tag', repeat ? 'step' : signal.tag));
    chip.appendChild(el('span', null, repeat ? `${signal.name} again` : signal.name));
    if (run.entries.length > 1) chip.appendChild(el('span', 'mono', ` ×${run.entries.length}`));
    chip.addEventListener('click', () => selectSignal(signal.id));
    return chip;
  };

  if (runs.length <= LOG_CHIPS) {
    for (const run of runs) {
      const entry = run.entries[0];
      line.appendChild(entry.kind === 'txn' && run.entries.length === 1 ? callChip(entry.txn) : otherChip(run));
    }
    return line;
  }

  // Summarised: the calls as one count, every failure by endpoint, then the rest in order.
  const total = el('button', 'pv-chip', `${visit.txns.length} network`);
  total.type = 'button';
  total.title = 'expand this visit';
  total.addEventListener('click', expand);
  line.appendChild(total);
  const failures = new Map();
  for (const txn of visit.txns.filter(isFailure)) {
    const k = `${txn.method} ${txn.path} ${txn.status ?? 'ERR'}`;
    if (!failures.has(k)) failures.set(k, []);
    failures.get(k).push(txn);
  }
  for (const group of failures.values()) {
    const chip = callChip(group[0]);
    chip.dataset.failed = String(group.length);
    if (group.length > 1) chip.appendChild(el('span', 'mono', ` ×${group.length}`));
    line.appendChild(chip);
  }
  const rest = runs.filter((run) => run.entries[0].kind !== 'txn');
  for (const run of rest.slice(0, 4)) line.appendChild(otherChip(run));
  if (rest.length > 4) {
    const more = el('button', 'pv-chip', `+${rest.length - 4} more`);
    more.type = 'button';
    more.addEventListener('click', expand);
    line.appendChild(more);
  }
  return line;
}

/** An expanded visit: every item in order, folded past `PATH_FOLD` with failures floated. */
function visitBody(visit, index, list) {
  const key = visitKey(visit);
  const previous = previousScreens(visit);
  const items = timelineRuns(visit.entries).map((run, order) => ({
    order,
    failed: run.entries.length === 1 && run.entries[0].kind === 'txn' && isFailure(run.entries[0].txn),
    node: () => pathItem(run, index, list, previous),
  }));
  const unfolded = state.expandedVisits.has(`${key}:all`);
  const body = el('div', 'pv-items');
  if (unfolded || items.length <= PATH_FOLD) {
    for (const item of items) body.appendChild(item.node());
  } else {
    const rest = items.slice(PATH_FOLD);
    for (const item of items.slice(0, PATH_FOLD)) body.appendChild(item.node());
    // Never folded: a 500 behind "+28 more" hides the one thing somebody came for. Drawn out of
    // position, and saying so.
    const floated = rest.filter((item) => item.failed);
    for (const item of floated) {
      const node = item.node();
      node.classList.add('pv-floated');
      const hint = el('span', 'pv-order muted', `↓ #${item.order + 1} in order`);
      hint.title = 'shown above the fold because it failed; it happened later in this visit';
      node.appendChild(hint);
      body.appendChild(node);
    }
    const hidden = rest.length - floated.length;
    if (hidden > 0) {
      const more = el('button', 'pv-more btn btn-sm btn-quiet', `+${hidden} more`);
      more.addEventListener('click', () => { state.expandedVisits.add(`${key}:all`); renderPath(); });
      body.appendChild(more);
    }
  }
  return body;
}

/** One thing that happened during a visit: a call, a signal, a run of them, or a marker. */
function pathItem(run, index, list, previous) {
  const entry = run.entries[0];
  if (entry.kind === 'marker') {
    const node = el('div', 'marker-divider pv-marker', entry.marker.label);
    node.dataset.kind = 'marker';
    return node;
  }

  if (entry.kind === 'txn') {
    const txn = entry.txn;
    const node = el('div', `pv-item pv-call${txn.id === state.selectedId ? ' selected' : ''}`);
    node.dataset.kind = 'txn';
    node.dataset.id = txn.id;
    node.tabIndex = 0;
    const line = el('div', 'pv-line');
    line.appendChild(el('span', `method ${methodClass(txn.method)}`, txn.method));
    const path = el('span', 'pv-path');
    const isolated = el('bdi', null, txn.path);
    isolated.dir = 'ltr';
    path.appendChild(isolated);
    path.title = txn.query ? `${txn.path}?${txn.query}` : txn.path;
    line.appendChild(path);
    line.appendChild(el('span', `pv-status st s${statusClass(txn.status)}`, txn.status ?? 'ERR'));
    node.appendChild(line);

    const meta = el('div', 'pv-meta muted mono');
    meta.appendChild(el('span', null, fmtMs(txn.ms)));
    if (state.duplicates.has(txn.id)) {
      const dup = el('span', 'pv-dup', 'repeat');
      dup.title = 'the same request was sent again by a separate call close by';
      meta.appendChild(dup);
    }
    if (txn.attempt > 1) meta.appendChild(el('span', null, `attempt ${txn.attempt}`));
    // A cue, never a reassignment: the call belongs to the visit that asked for it.
    if (txn.ms != null) {
      const ended = visitIndexAt(list, txn.mono + txn.ms);
      if (ended > index) {
        const later = list[ended].screen;
        meta.appendChild(el('span', 'pv-finish', `→ finished on ${later ? later.name : 'a later screen'}`));
      }
    }
    node.appendChild(meta);
    node.addEventListener('click', () => select(txn.id));
    node.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); select(txn.id); }
    });
    return node;
  }

  const signal = run.entries[run.entries.length - 1].signal;
  const lane = laneFor(signal.tag);
  const isRepeat = previous.has(signal.id) && run.entries.length === 1;
  const node = el('div', `pv-item pv-sig lane-${lane}${isRepeat ? ' pv-repeat' : ''}`);
  const own = isRepeat ? null : tagColour(tagOf(signal));
  if (own) node.style.borderLeftColor = own;
  node.dataset.kind = isRepeat ? 'repeat' : run.entries.length > 1 ? 'run' : 'signal';
  node.dataset.id = signal.id;
  node.dataset.tag = signal.tag;
  node.tabIndex = 0;
  const line = el('div', 'pv-line');
  line.appendChild(el('span', 'tl-tag', signal.tag));
  line.appendChild(el('span', 'pv-sname', isRepeat ? `${signal.name} again` : signal.name));
  if (run.entries.length > 1) {
    const count = el('span', 'tl-run-count mono', `×${run.entries.length}`);
    count.title = `${run.entries.length} adjacent observations — opens the latest`;
    line.appendChild(count);
  }
  node.appendChild(line);

  if (isRepeat) {
    // What changed, when the payloads are here to say. A re-sent identical payload is a
    // double-firing effect, which is sometimes the bug; a different one is a step inside the
    // screen.
    const now = state.screenPayloadText.get(signal.id);
    const before = state.screenPayloadText.get(previous.get(signal.id).id);
    let text = null;
    if (now !== undefined && before !== undefined) {
      if (now === before) text = now === null ? 'no payload' : 'same payload';
      else if (now !== null) text = now.replace(/\s+/g, ' ').slice(0, 80);
    }
    if (text) node.appendChild(el('div', 'pv-meta muted mono', text));
  }

  node.addEventListener('click', () => selectSignal(signal.id));
  node.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); selectSignal(signal.id); }
  });
  return node;
}

/** Wired once: the pill jumps to the newest visit, and reaching the end yourself dismisses it. */
function wirePath() {
  const pane = $('path').parentElement;
  const pill = $('path-new');
  pill.addEventListener('click', () => {
    pane.scrollTop = pane.scrollHeight;
    const metro = $('path').querySelector('.pv-metro');
    if (metro) metro.scrollLeft = metro.scrollWidth;
    pill.hidden = true;
  });
  pane.addEventListener('scroll', () => {
    if (state.view === 'path' && pane.scrollTop + pane.clientHeight >= pane.scrollHeight - 8) pill.hidden = true;
  });
}
