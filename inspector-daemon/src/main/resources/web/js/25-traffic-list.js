/* traffic list: filter, markers, the scope bar, rows, order and selection */
'use strict';

function showFilterError(message) {
  const node = $('filter-error');
  node.hidden = !message;
  node.textContent = message || '';
}

// --- rendering ----------------------------------------------------------

function renderMarkers() {
  const list = $('markers');
  list.innerHTML = '';
  const labels = [...new Set(state.markers.map((m) => m.label))];
  // The count on the button, so a session with markers says so without opening anything.
  $('markers-button').textContent = labels.length ? `markers · ${labels.length}` : 'markers';
  if (!labels.length) {
    list.appendChild(el('li', 'muted', 'none'));
    return;
  }
  for (const label of labels) {
    const item = el('li', null, label);
    item.title = 'Filter to traffic after this marker';
    item.onclick = () => {
      $('filter').value = `since:marker("${label}")`;
      applyFilter();
    };
    list.appendChild(item);
  }
}

/**
 * Dropping a marker from here.
 *
 * The daemon refuses a marker on a session that is not currently recording — there is no clock
 * to place it on once the app has gone — so the form says that up front instead of letting
 * someone type a label and collect a 409.
 *
 * The check is `/api/recording`, **not** `sessionIsLive()`. They disagree exactly where it
 * matters: `sessionIsLive` asks whether `endedAt` is absent, and a session whose daemon was
 * killed or whose app vanished without a clean close has no `endedAt` and no open connection
 * either. Gating on that would leave the control enabled on precisely the sessions where the
 * app went away, which is when somebody most wants to mark where it happened.
 *
 * `source: 'user'` matters. `MarkerSource.USER` existed and had no caller: every marker in every
 * archive so far says `app` or `agent`. A marker dropped by the person watching is a different
 * claim from one an agent left while working through the session, and whoever reads the archive
 * later is entitled to tell them apart.
 */
function markerFormState() {
  const form = $('marker-form');
  const input = $('marker-label');
  const status = $('marker-status');
  const live = state.recording.includes(state.sessionId);
  input.disabled = !live;
  $('marker-add').disabled = !live;
  if (!live) {
    status.hidden = false;
    status.textContent = 'no app is connected to this session — a marker needs a live one';
  } else if (status.dataset.sticky !== '1') {
    status.hidden = true;
    status.textContent = '';
  }
  return form;
}

function wireMarkerForm() {
  const form = $('marker-form');
  const input = $('marker-label');
  const status = $('marker-status');

  const say = (text, isError) => {
    status.hidden = false;
    status.textContent = text;
    status.classList.toggle('bad', Boolean(isError));
    status.dataset.sticky = '1';
    setTimeout(() => { status.dataset.sticky = '0'; markerFormState(); }, 2500);
  };

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const label = input.value.trim();
    // A marker with no label is a line across the list that explains nothing.
    if (!label) return;
    $('marker-add').disabled = true;
    try {
      const res = await source.fetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/markers`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ label, source: 'user' }),
      });
      if (res.ok) {
        input.value = '';
        say('added');
        // The marker arrives over the live socket like any other, but only for a viewer that
        // has one. Re-reading is what makes the divider appear for everyone else.
        state.markers = await api(`/api/sessions/${encodeURIComponent(state.sessionId)}/markers`)
          .catch(() => state.markers);
        renderMarkers();
        renderList();
      } else {
        // The daemon's own message names the reason — "markers can only be added to a session
        // that is currently recording" is more use than "failed".
        const detail = await res.json().catch(() => ({}));
        say(detail.error || `refused (${res.status})`, true);
      }
    } catch {
      say('could not reach the daemon', true);
    } finally {
      markerFormState();
    }
  });
}

/** Inside the brushed stretch of the session, if one is selected. */
const inRange = (mono) =>
  !state.timeRange || (mono >= state.timeRange.from && mono <= state.timeRange.to);

const visibleTransactions = () => state.transactions.filter((txn) => inRange(txn.mono));

/**
 * Transactions in display order — the single source of truth for both rendering and j/k
 * navigation, so "next row" always means the row visually below the current one.
 */
const orderedRows = () => {
  const rows = visibleTransactions().sort((a, b) => a.mono - b.mono);
  return state.newestFirst ? rows.reverse() : rows;
};

// --- the scope bar ------------------------------------------------------

// @mirror-begin pathScope
/**
 * A mirror of `pathScope` in `:inspector-model`. Keep them in step.
 *
 * It lives there because the overlay draws the same bar, and three thresholds plus a segment
 * walk are exactly the kind of rule that drifts invisibly: a bar that appears on one surface and
 * not the other, for a session both are showing, reads as a bug in whichever one you are looking
 * at. `PathScopeTest` in that module is the authority; this is the copy that must follow it.
 *
 * Three rules, each of which has a test named after it over there:
 *
 * - **The dominant host only.** A session talking to an API and an auth server has no single
 *   prefix. Rows outside the scope name their host, so the bar is never read as covering them.
 * - **Never the last segment.** `/v3/orders/submit` and `/v3/orders/cancel` share `/v3/orders/`
 *   and that is as far as this may go; a prefix that swallowed a whole path leaves a blank row.
 * - **Thresholds.** A four-row session, or a two-character saving, is not worth a bar.
 */
const SCOPE_MIN_ROWS = 4;
const SCOPE_MIN_LENGTH = 6;

function pathScope(transactions) {
  if (transactions.length < SCOPE_MIN_ROWS) return null;

  const counts = new Map();
  for (const txn of transactions) counts.set(txn.host, (counts.get(txn.host) || 0) + 1);
  let host = null;
  let best = -1;
  for (const [candidate, count] of counts) {
    if (count > best) { host = candidate; best = count; }
  }
  if (host === null) return null;

  const paths = transactions.filter((t) => t.host === host).map((t) => t.path);
  if (paths.length < SCOPE_MIN_ROWS) return null;

  const segmented = paths.map((path) => path.split('/').filter((s) => s.length > 0));
  const shared = [];
  for (let i = 0; ; i++) {
    const segment = segmented[0][i];
    if (segment === undefined) break;
    // `length <= i + 1` is the never-the-last-segment rule: this path has nothing left over.
    if (segmented.some((s) => s.length <= i + 1 || s[i] !== segment)) break;
    shared.push(segment);
  }
  if (!shared.length) return null;

  const prefix = `/${shared.join('/')}/`;
  if (prefix.length < SCOPE_MIN_LENGTH) return null;

  return {
    host,
    prefix,
    covered: paths.length,
    label: host + prefix,
    covers: (txn) => txn.host === host && txn.path.startsWith(prefix),
    strip: (path) => (path.startsWith(prefix) ? path.slice(prefix.length) : path),
  };
}
// @mirror-end pathScope

/**
 * Drawn above the list and not scrolled with it.
 *
 * It is a standing claim about what every path beneath it is missing, so a row read after the
 * bar had scrolled off would be read wrong. Same reasoning as the overlay's, and the same place
 * on screen.
 */
function renderScopeBar() {
  const bar = $('scope-bar');
  const scope = state.scope;
  bar.hidden = !scope;
  if (!scope) return;
  bar.innerHTML = '';
  // Host and prefix as two parts, so a narrow bar clips the end of the prefix rather than the
  // front of the host. The full text is in the tooltip either way.
  const label = el('span', 'scope-label mono', `${scope.host} · ${scope.prefix.replace(/\/$/, '')}`);
  label.title = `${scope.label} — shared by most of this session, and lifted out of the rows below`;
  bar.appendChild(label);
  bar.appendChild(el('span', 'scope-count muted mono', `${scope.covered} of ${state.allTransactions.length}`));
}

/** The list's "clear filter" control, shared by every pane a filter can empty or thin. */
function clearFilterButton() {
  const clear = el('button', 'btn btn-sm', 'clear filter');
  clear.type = 'button';
  clear.addEventListener('click', () => {
    $('filter').value = '';
    applyFilter();
  });
  return clear;
}

/**
 * How many calls and signals the session holds that the filter left out — within the brushed
 * stretch when `ranged`, since a pane that brushes is not hiding what lies outside it, and without
 * `screen` signals when `screens` is false, for the path, which draws every screen whatever the filter.
 *
 * Calls are counted from the route's own numbers, the session total less the filter's matches,
 * never from the rows held: the filtered list stops at the route's clamp of 1000 rows, so a
 * difference of two lists was a difference of two clamps — "1000 calls hidden" where 3016 were, and
 * no note at all for a filter matching 1200. A brushed stretch has no route number, so there the
 * held rows are counted, only from the oldest row at which every list holding them is still whole,
 * and `atLeast` says that some of the stretch lay below it. Signals are read whole and need neither.
 */
function hiddenByFilter({ signals = true, ranged = false, screens = true } = {}) {
  const stretch = ranged && state.timeRange;
  const count = (rows, floor = -Infinity) => rows.filter((row) => row.mono >= floor && (!stretch || inRange(row.mono))).length;
  const kept = (rows) => (screens ? rows : rows.filter((s) => tagOf(s) !== 'screen'));
  const signalCount = signals ? Math.max(0, count(kept(state.allSignals)) - count(kept(state.signals))) : 0;
  if (!stretch && state.sessionTotal !== null && state.matched !== null) {
    return { calls: Math.max(0, state.sessionTotal - state.matched), signals: signalCount, atLeast: false };
  }
  // Each list is the newest rows of what it holds, so it is whole from its oldest row up, or whole
  // outright when it is as long as the route says.
  const wholeFrom = (rows, total) => (rows.length && rows.length < (total ?? 0) ? Math.min(...rows.map((r) => r.mono)) : -Infinity);
  const floor = Math.max(wholeFrom(state.transactions, state.matched), wholeFrom(state.allTransactions, state.sessionTotal));
  return {
    calls: Math.max(0, count(state.allTransactions, floor) - count(state.transactions, floor)),
    signals: signalCount,
    atLeast: floor > -Infinity && (!stretch || state.timeRange.from < floor),
  };
}

/**
 * What the filter took out of a pane, said in the pane, naming the filter and offering to clear
 * it as the list's empty state does. `emptied` when nothing is left: a pane that went blank under a
 * filter, or one reading "nothing recorded", says the app did nothing — which is false, and sends
 * someone to debug the app instead of the query. `lead` opens the emptied note; a pane that draws
 * only calls says "no calls match", since the filter may well have matched signals.
 */
function filterNote(node, hidden, emptied, lead = 'nothing matches') {
  const count = (n, noun) => (n ? `${n} ${noun}${n === 1 ? '' : 's'}` : null);
  const calls = count(hidden.calls, 'call');
  const what = [calls && hidden.atLeast ? `at least ${calls}` : calls, count(hidden.signals, 'signal')].filter(Boolean).join(' and ');
  node.replaceChildren();
  node.append(emptied ? `${lead} the filter ` : `${what} hidden by the filter `);
  node.appendChild(el('code', null, state.filter));
  if (emptied && what) node.append(` — ${what} hidden`);
  node.append(' — ');
  node.appendChild(clearFilterButton());
  return node;
}

/**
 * Why the list is empty, and what would fix it.
 *
 * Four different things empty this list and each has a different fix, so a single "no
 * transactions" sent people looking in the wrong place — most often at the app, when the cause
 * was a filter they had forgotten. The filter case gets a button, because clearing it is the
 * whole of the fix. "Waiting for an app" is said only where it is true: with no session at all.
 * A session an app is attached to but has not called anything from, and an archived session that
 * recorded no calls, are both different claims and are worded as such.
 */
function renderListEmpty() {
  const node = $('list-empty');
  node.replaceChildren();
  const inSession = state.allSessionId === state.sessionId ? state.allTransactions.length : null;
  if (state.timeRange && state.transactions.length) {
    node.textContent = 'no calls in the selected stretch';
  } else if (state.filter && inSession !== 0) {
    node.append('no calls match — ');
    node.appendChild(clearFilterButton());
  } else if (!state.sessionId) {
    node.textContent = 'waiting for an app with inspector-stream to connect';
  } else if ((state.recording || []).includes(state.sessionId)) {
    node.textContent = 'the app is connected — no calls yet';
  } else {
    node.textContent = 'no calls were recorded in this session';
  }
}

function renderList() {
  const list = $('list');
  list.innerHTML = '';
  // The map follows the data. Suspended during a brush, where `setTimeRange` has already drawn
  // it and redrawing per pointermove is the one place this is hot.
  if (!axisPaintSuspended) renderAxis();

  // Over *all* transactions, not the filtered view: a duplicate whose twin is filtered out is
  // still a duplicate, and making the highlight depend on the current filter would hide exactly
  // the case you go looking for.
  state.duplicates = computeDuplicates(state.allTransactions, state.duplicateWindowMs);
  // Same source as the duplicates and the endpoint chips, and for the same reason.
  state.scope = pathScope(state.allTransactions);
  renderScopeBar();
  renderEndpointChips();

  const rows = orderedRows();
  $('list-empty').hidden = rows.length > 0;
  renderListEmpty();

  // Build the interleaved sequence oldest-first, where "marker, then the rows after it" is the
  // only arrangement that makes causal sense, then reverse the whole thing. Reversing after
  // interleaving keeps each divider attached to the same rows: read downward in newest-first
  // and a divider below a row still means that row happened after the marker. The interleave
  // is `timelineEntries`, the mirror of the model's `timeline()` that the mirror cases check,
  // rather than a second copy of it here.
  const markers = state.markers.filter((m) => inRange(m.mono));
  const sequence = timelineEntries(visibleTransactions(), markers, []);
  if (state.newestFirst) sequence.reverse();

  for (const entry of sequence) {
    list.appendChild(
      entry.kind === 'marker'
        ? el('div', 'marker-divider', entry.marker.label)
        : rowFor(entry.txn),
    );
  }

  // Follow the newest row, wherever it now lives.
  if (state.liveTail) {
    const pane = list.parentElement;
    pane.scrollTop = state.newestFirst ? 0 : pane.scrollHeight;
  }
}

function setSortOrder(newestFirst) {
  state.newestFirst = newestFirst;
  prefs.set('inspector.newestFirst', newestFirst ? '1' : '0');
  $('order-oldest').classList.toggle('on', !newestFirst);
  $('order-newest').classList.toggle('on', newestFirst);
  $('order-oldest').setAttribute('aria-pressed', String(!newestFirst));
  $('order-newest').setAttribute('aria-pressed', String(newestFirst));
  renderList();
  renderTimeline();
  // Order is a reading preference, not a view: it applies to the key list and to every
  // history under it too.
  if (state.view !== 'all' && state.view !== 'network') renderBrowser();
}

function rowFor(txn) {
  const cls = statusClass(txn.status);
  const row = el('div', 'row');
  row.dataset.id = txn.id;
  if (txn.id === state.selectedId) row.classList.add('selected');

  const duplicate = state.duplicates.get(txn.id);
  if (duplicate) {
    row.classList.add('duplicate');
    row.title =
      `sent ${duplicate.count}x by separate calls within ${fmtMs(duplicate.spanMs)} — ` +
      'same method, URL, status and byte counts';
  }

  // The status leads. It is the column scanned for — "which of these failed" — and it used to be
  // a 7px dot at the start plus a number near the end, so reading the actual code meant crossing
  // the whole row. The number is the colour and the fact at once.
  row.appendChild(el('span', `status lead s${cls}`, txn.status === null || txn.status === undefined ? 'ERR' : txn.status));
  // The start time is what makes a duplicate legible: two rows 1.9s apart is the whole finding.
  row.appendChild(el('span', 'clock mono muted', fmtClock(txn.ts)));
  row.appendChild(el('span', `method ${methodClass(txn.method)}`, txn.method));

  // The host appears only when it is not the one the scope bar claims. Repeating it on every
  // row is noise when it never changes, and load-bearing on the one row where it does — the
  // same rule the overlay applies, which is why both read `scope.covers`.
  const scope = state.scope;
  if (!scope || txn.host !== scope.host) {
    row.appendChild(el('span', 'host mono muted', txn.host));
  }

  // Stripped when the bar above already says this front. The bar is what makes that safe: it is
  // on screen, it does not scroll away, and it names the exact prefix that was removed.
  const path = el('span', 'path', scope && scope.covers(txn) ? scope.strip(txn.path) : txn.path);
  if (attemptOf(txn) > 1) {
    const badge = el('span', 'attempt', ` ·attempt ${attemptOf(txn)}`);
    path.appendChild(badge);
  }
  if (duplicate) {
    // Colour alone is invisible to some readers and unexplained to the rest.
    path.appendChild(el('span', 'dup-badge', ` ·repeated ${duplicate.count}x`));
  }
  row.appendChild(path);

  row.appendChild(el('span', 'ms', fmtMs(txn.ms)));
  row.appendChild(el('span', 'size', fmtBytes(Math.max(txn.reqBytes || 0, txn.resBytes || 0))));

  row.onclick = () => select(txn.id);
  return row;
}

// --- interaction --------------------------------------------------------

function select(id) {
  state.selectedId = id;
  for (const row of document.querySelectorAll('.row, .wf-row, .pv-call, .pv-chip-call')) {
    row.classList.toggle('selected', row.dataset.id === id);
  }
  const txn = state.transactions.find((t) => t.id === id);
  if (txn) renderDetail(txn);
}

function move(delta) {
  // Display order, so j always moves down the screen regardless of sort direction.
  const rows = orderedRows();
  if (!rows.length) return;
  const current = rows.findIndex((t) => t.id === state.selectedId);
  const next = Math.min(rows.length - 1, Math.max(0, (current === -1 ? 0 : current + delta)));
  select(rows[next].id);
  document.querySelector('.row.selected')?.scrollIntoView({ block: 'nearest' });
}

let filterTimer = null;
function applyFilter() {
  // Every way of setting a filter — typing, a quick chip, an endpoint chip, a marker, a glance
  // row, "clear filter" — arrives here, which is the only reason one guard covers them all. In
  // an export the grammar has no evaluator, and letting the request through would empty the
  // list behind a refusal: a marker click becoming a dead end.
  if (ARCHIVE) {
    $('filter').value = '';
    showFilterError(
      'Filters are evaluated by the daemon, and this exported session has none behind it. ' +
      'Drag across the time axis to narrow instead.',
    );
    return;
  }
  clearTimeout(filterTimer);
  filterTimer = setTimeout(() => {
    state.filter = $('filter').value;
    for (const chip of document.querySelectorAll('#chips .chip, #endpoint-chips .chip')) {
      chip.classList.toggle('active', chip.dataset.filter === state.filter);
    }
    loadTransactions();
  }, 150);
}

/**
 * Follow new traffic, or hold the list where it is.
 *
 * Paused drops live rows rather than queueing them, so resuming re-reads the session, the
 * whole-session copies included: a list that picked up from wherever the next message happened to
 * land would be missing everything that arrived while it was paused, with nothing on screen to say
 * so.
 */
function setLiveTail(on) {
  state.liveTail = on;
  const button = $('live-btn');
  button.classList.toggle('on', on);
  button.classList.toggle('paused', !on);
  button.setAttribute('aria-pressed', String(on));
  button.querySelector('.live-label').textContent = on ? 'live' : 'paused';
  button.title = on
    ? 'Following new traffic as it arrives. Click to pause.'
    : 'Paused: new traffic is not added to the list. Click to catch up and follow again.';
  if (on) {
    // The rows missed while paused are missing from the whole-session copies too.
    forgetWholeSession();
    loadTransactions();
  }
}
