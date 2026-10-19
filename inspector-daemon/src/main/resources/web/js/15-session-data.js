/* session data: sessions, transactions and signals from the daemon */
'use strict';

async function loadSessions() {
  state.sessions = await api('/api/sessions');
  state.recording = await api('/api/recording').catch(() => state.recording);
  const picker = $('session-picker');
  picker.innerHTML = '';
  for (const s of state.sessions) {
    const option = el('option', null, sessionLabel(s));
    option.value = s.sessionId;
    // The id is what the CLI, the MCP tools and the archive folder use, so it stays reachable.
    option.title = s.sessionId;
    picker.appendChild(option);
  }
  if (!state.sessionId && state.sessions.length) state.sessionId = state.sessions[0].sessionId;
  if (state.sessionId) picker.value = state.sessionId;

  showSessionLabel();
}

/**
 * `● app · device · 09:41 today · 12 calls`.
 *
 * The option used to be the session id, which is a timestamp and three slugs run together — the
 * facts were in it, but in the order a folder name needs rather than the one a reader asks in.
 * The dot marks a session an app is attached to right now, asked of `/api/recording` rather than
 * inferred from `endedAt`: a session whose app vanished has no `endedAt` and no app either.
 */
function sessionLabel(meta) {
  const started = new Date(meta.startedAt);
  let when = '';
  if (!Number.isNaN(started.getTime())) {
    const pad = (n) => String(n).padStart(2, '0');
    const today = new Date();
    const sameDay = started.toDateString() === today.toDateString();
    const day = sameDay
      ? 'today'
      : started.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
    when = `${pad(started.getHours())}:${pad(started.getMinutes())} ${day}`;
  }
  // `?? 0`, not `||`: kotlinx omits a field equal to its default, so a session with no traffic
  // arrives with `txnCount` missing rather than zero.
  const count = meta.txnCount ?? 0;
  const parts = [meta.appId, meta.device, when, `${count} ${count === 1 ? 'call' : 'calls'}`];
  const recording = (state.recording || []).includes(meta.sessionId);
  return `${recording ? '● ' : ''}${parts.filter(Boolean).join(' · ')}`;
}

/**
 * The header count, over whatever is actually on screen.
 *
 * The daemon reports how many rows the *text filter* matched, which was the whole story until
 * the axis could narrow things further. Counting only that would leave the header reading
 * `17/17` above a list of five — a number that is true of a query nobody can see.
 *
 * With no filter and no stretch, a list short of the session is the list's ceiling, and says so in
 * words: `1000 of 4210 calls` is the shape a filter produces, and it sent readers looking for a
 * filter that was not there. Under a filter or a stretch the plain count stays, because the filter
 * note already says when the matches outrun the list.
 */
function renderCounts() {
  const node = $('counts');
  const total = state.sessionTotal;
  node.title = '';
  if (total === null || total === undefined) {
    node.textContent = '';
    return;
  }
  const shown = visibleTransactions().length;
  if (!state.filter && !state.timeRange && shown < total) {
    node.textContent = `newest ${shown} of ${total} shown`;
    node.title = `the list draws the newest ${shown} calls; the session holds ${total}`;
    return;
  }
  // With its noun: a bare `12/12` beside a session picker could be a count of anything.
  node.textContent = `${shown} of ${total} ${total === 1 ? 'call' : 'calls'}`;
}

/**
 * Which app the page is showing, in the window title — the picker already names it in the bar.
 * Also runs on switch, or it would name the old one.
 */
function showSessionLabel() {
  const meta = state.sessions.find((s) => s.sessionId === state.sessionId);
  document.title = meta ? `${meta.appId} · inspector` : 'inspector';
}

/**
 * How many calls the live list asks for, newest first: the page's own number rather than the
 * daemon's clamp happening to match it. The whole-session copy is read past it in pages
 * (`routePages`). An export asks for every call it embeds instead, because it has no filter to
 * reach past a ceiling: at 1000 its oldest calls could not be opened at all, and a stretch brushed
 * over them said they did not exist.
 */
const LIST_CEILING = 1000;

/**
 * The list, its markers and the whole-session copy, then the signals, for the filter as it stands.
 *
 * The filter and the session are read **once**, here, and handed down: a load that read
 * `state.filter` again after an await could fetch half its answer for one filter and half for the
 * next. And a load that a newer one has overtaken drops its answer rather than writing it — typing
 * fires one load per pause, and the daemon is free to answer them out of order, so without
 * `callLoad` a slow unfiltered read landing last put every row back under the filter just typed.
 */
async function loadTransactions() {
  // An export of nothing would be a 404 with a filename; offer it only when there is a session.
  $('export-host').hidden = !!ARCHIVE || !state.sessionId;
  // With no session there is nothing to load, and the empty list is the only thing that can
  // say what the page is waiting for.
  if (!state.sessionId) { renderListEmpty(); return; }
  const load = ++state.callLoad;
  const isLatest = () => load === state.callLoad;
  const filter = state.filter;
  const id = encodeURIComponent(state.sessionId);
  const q = new URLSearchParams({ filter, limit: String(ARCHIVE ? ARCHIVE.transactions.length : LIST_CEILING) });
  const ticket = ++state.readSent.transactions;
  let page = null;
  let error = null;
  try {
    page = await api(`/api/sessions/${id}/transactions?${q}`);
  } catch (e) {
    error = e;
  }
  if (!isLatest()) return;
  // A live re-read sent after this load, for the same filter, may have landed first with newer rows.
  if (ticket > state.readWritten.transactions) {
    state.readWritten.transactions = ticket;
    if (page) {
      state.transactions = page.items;
      state.sessionTotal = page.total;
      state.matched = page.matched;
      showFilterError(null);
    } else {
      // Parser messages are written to be shown verbatim, so show them verbatim.
      showFilterError(error.message);
      state.transactions = [];
      state.sessionTotal = null;
      state.matched = null;
    }
  }
  renderCounts();
  const markers = await api(`/api/sessions/${id}/markers`).catch(() => []);
  const recording = await api('/api/recording').catch(() => []);
  if (!isLatest()) return;
  state.markers = markers;
  state.recording = recording;
  await syncAllTransactions(filter, isLatest);
  if (!isLatest()) return;
  await loadSignals(filter);
  if (!isLatest()) return;
  renderMarkers();
  markerFormState();
  renderList();
  // A new filter, session or re-read is a new list, which the timeline follows from its newest end.
  state.timelineFresh = true;
  renderTimeline();
}

/**
 * Signals and the current-state panel.
 *
 * Two lists, as for transactions. `signals` is what the daemon matched against the filter, and
 * only the timeline tab's entries and the path view's visit lines read it. `allSignals` is the whole
 * session, and everything that describes the session — tabs and their counts, tag browsers, the
 * glance, the axis, `screens` — reads that, so a filter never makes a tab or a screen disappear.
 *
 * `filter` is the one `loadTransactions` read, or the filter as it stands when called on its own.
 * Loads are counted by `signalLoad` apart from `callLoad`, because a pull reloads the signals alone:
 * an older signal load that lands after a newer one is dropped, as an older list load is.
 *
 * Failures are swallowed to an empty list on purpose: a daemon older than this page has no
 * signal routes, and the traffic view must keep working against one rather than going blank.
 */
async function loadSignals(filter = state.filter) {
  if (!state.sessionId) return;
  const load = ++state.signalLoad;
  const isLatest = () => load === state.signalLoad;
  const sessionId = state.sessionId;
  const id = encodeURIComponent(sessionId);
  const ticket = ++state.readSent.signals;
  const signals = await signalPages(api, sessionId, filter).catch(() => []);
  if (!isLatest()) return;
  if (ticket > state.readWritten.signals) {
    state.readWritten.signals = ticket;
    state.signals = signals;
  }
  await syncAllSignals(filter, isLatest);
  if (!isLatest()) return;
  // Taken from the whole session, so the skeleton ignores the filter without a request of its own.
  state.screens = state.allSignals.filter((s) => tagOf(s) === 'screen');
  const current = await api(`/api/sessions/${id}/current`).catch(() => []);
  if (!isLatest()) return;
  state.current = current;

  // A tab whose tag this session never recorded is a dead end, so the set is rebuilt per
  // session and the current view falls back rather than pointing at nothing.
  const ids = availableViews().map((view) => view.id);
  if (!ids.includes(state.view)) state.view = 'network';
  state.viewChosenForSession = true;

  renderTabs();
  renderCurrent();
  applyView();
  if ($('detail').hidden) renderSessionGlance();
}

/**
 * Keeps `allSignals` in step with the current session, as `syncAllTransactions` does for calls:
 * free with no filter, one paged read per *session* with one. A pull invalidates it by clearing
 * `allSignalsSessionId`, since the rows it asked for are new and the cache would not have them.
 * `filter` is the caller's, never re-read; `isLatest` says whether that load still stands.
 *
 * When the whole-session read fails, the filtered rows stand in so the tabs are not blank, but
 * they are **not** marked as the whole session: marking them would cache a filtered list as the
 * session until the filter was cleared, and every later load would trust it.
 */
async function syncAllSignals(filter, isLatest = () => true) {
  if (!filter) {
    // A copy, not an alias — the live socket pushes into both.
    state.allSignals = state.signals.slice();
    state.allSignalsSessionId = state.sessionId;
    return;
  }
  if (state.allSignalsSessionId === state.sessionId && state.allSignals.length) return;
  const sessionId = state.sessionId;
  // Opened before the first page is asked for, so a live signal arriving mid-read is kept; see `admitLive`.
  const buffer = openArrivals('signals', sessionId);
  try {
    const all = await signalPages(api, sessionId, '');
    closeArrivals('signals', buffer);
    if (!isLatest()) return;
    state.allSignals = withArrivals(all, buffer);
    state.allSignalsSessionId = sessionId;
  } catch {
    closeArrivals('signals', buffer);
    if (!isLatest()) return;
    state.allSignals = state.signals.slice();
    state.allSignalsSessionId = null;
  }
}

/**
 * How long a live row under a filter waits before the filtered rows are read again.
 *
 * The rows that arrive while it waits, or while that read is in flight, ride on it or on the one
 * armed when it lands, so a burst of 100 rows inside a second costs at most two re-reads: two
 * requests if they are all calls, and with signals among them `2 × (1 + ⌈S/1000⌉ + 1)`, where S is
 * how many signals the filter matches — six below a thousand.
 */
const LIVE_REREAD_MS = 1000;

/**
 * A live row of `kind` arrived under a filter: owe that kind a re-read, and arm one unless one is
 * armed or in flight already. A filter the daemon refused has nothing to re-read — the list says
 * why — and asking again on every row would only repeat the refusal.
 */
function oweReread(kind) {
  if (state.matched === null) return;
  const owed = state.reread;
  owed[kind] = true;
  // Never two in flight: a row arriving during a read is covered by the one armed when it lands.
  if (owed.timer === null && !owed.busy) owed.timer = setTimeout(rereadLive, LIVE_REREAD_MS);
}

/**
 * The filtered rows of every kind a live row is owed for, read again from the daemon.
 *
 * Whether a live row matches the filter is the daemon's to say. The grammar has one parser, in
 * `:inspector-model`, and a JavaScript evaluator here would be a second, which is why an export
 * disables the filter rather than reimplementing it. So under a filter a live row joins only the
 * whole-session copies when it arrives, and the list, the timeline and the visits take whatever
 * the daemon's filtered answer holds once this lands. It reads only the routes a load reads.
 *
 * Its answer is dropped when the session, the filter or live tail changed under it. Of a load and a
 * re-read for the same rows, whichever was sent last is the one kept, whatever order they land in
 * (`readSent`, `readWritten`): the daemon stores a row before it broadcasts it, so the later read
 * holds everything the earlier one does.
 */
async function rereadLive() {
  const owed = state.reread;
  owed.timer = null;
  const kinds = ['transactions', 'signals'].filter((kind) => owed[kind]);
  owed.transactions = false;
  owed.signals = false;
  // Paused, the page drops rows and resuming reads the session again, so nothing is owed here.
  if (!kinds.length || !state.liveTail || !state.sessionId || !state.filter) return;
  const { sessionId, filter } = state;
  const stands = () => state.liveTail && state.sessionId === sessionId && state.filter === filter;
  let wrote = false;
  owed.busy = true;
  try {
    if (kinds.includes('transactions')) {
      const ticket = ++state.readSent.transactions;
      const q = new URLSearchParams({ filter, limit: String(LIST_CEILING) });
      const page = await api(`/api/sessions/${encodeURIComponent(sessionId)}/transactions?${q}`);
      if (stands() && ticket > state.readWritten.transactions) {
        state.readWritten.transactions = ticket;
        state.transactions = page.items;
        state.sessionTotal = page.total;
        state.matched = page.matched;
        wrote = true;
      }
    }
    if (kinds.includes('signals')) {
      const ticket = ++state.readSent.signals;
      const signals = await signalPages(api, sessionId, filter);
      if (stands() && ticket > state.readWritten.signals) {
        state.readWritten.signals = ticket;
        state.signals = signals;
        wrote = true;
      }
    }
  } catch {
    // The list stays as the last answer drew it; the next live row or load asks again.
  } finally {
    owed.busy = false;
    if (owed.transactions || owed.signals) owed.timer = setTimeout(rereadLive, LIVE_REREAD_MS);
  }
  if (!wrote || !stands()) return;
  renderCounts();
  renderTabs();
  renderList();
  renderWaterfall();
  renderPath();
  renderTimeline();
}

/**
 * A live call or signal joining the whole-session copy of its `kind`, `transactions` or `signals`.
 *
 * A row joins a copy only once the copy is marked as the session's, and the mark is set when the
 * whole-session read lands, so a row that arrived between the read's first page and its last was in
 * neither the read nor the copy. While a read of that kind is in flight for this session, the row
 * also goes into the read's buffer, and the read takes it in by id when it lands. Opening the
 * buffer before the first page is asked for is enough: the daemon stores a row before it broadcasts
 * it (`SessionManager.append`), so a row broadcast earlier is already in what the read returns.
 *
 * The buffer takes the row whether or not the copy is marked. A marked copy that is empty is read
 * again under a filter, and the landed read replaces it, so a row pushed onto the marked copy alone
 * went with the array it was pushed into. With neither a marked copy nor a read in flight the row
 * is dropped, because the next load reads the copy whole.
 */
function admitLive(kind, row) {
  const marked = kind === 'transactions' ? state.allSessionId : state.allSignalsSessionId;
  if (marked === state.sessionId) (kind === 'transactions' ? state.allTransactions : state.allSignals).push(row);
  const buffer = state.arriving[kind];
  if (buffer && buffer.sessionId === state.sessionId) buffer.rows.push(row);
}

/** Starts buffering live rows of `kind` for a whole-session read of `sessionId`; the buffer. */
function openArrivals(kind, sessionId) {
  const buffer = { sessionId, rows: [] };
  state.arriving[kind] = buffer;
  return buffer;
}

/**
 * Stops buffering for the read that opened `buffer` — only if the buffer is still its own: a newer
 * load's read may have opened another since, and clearing that would drop its arrivals.
 */
function closeArrivals(kind, buffer) {
  if (state.arriving[kind] === buffer) state.arriving[kind] = null;
}

/**
 * The read's rows, then each buffered row whose id the read does not already hold. By id, not
 * appended whole: a row that arrived before the page holding it was asked for is in both.
 */
function withArrivals(read, buffer) {
  const held = new Set(read.map((row) => row.id));
  for (const row of buffer.rows) {
    if (held.has(row.id)) continue;
    held.add(row.id);
    read.push(row);
  }
  return read;
}

/**
 * Drops both whole-session copies, so the next load reads them again.
 *
 * They are cached per session under a filter, and the live socket is the only thing that keeps
 * them current. Whatever arrived while it was not listening — the list paused, the page
 * disconnected, the daemon restarted — is in neither copy, so a re-read that trusted the cache
 * would draw tabs, the route and endpoint chips without it until the filter was cleared.
 *
 * It also overtakes every load in flight. A whole-session read sent before the gap answers from
 * before it, and landing after this it would mark the copy again with the old rows, which the next
 * load would then trust. Whoever calls this starts the load that replaces them, or clears the page.
 */
function forgetWholeSession() {
  state.allSessionId = null;
  state.allSignalsSessionId = null;
  state.arriving.transactions = null;
  state.arriving.signals = null;
  state.callLoad++;
  state.signalLoad++;
}

// @mirror-begin routePages
/** Every signal the route returns for [filter], newest first, each once: `routePages` over a bare array. */
async function signalPages(get, sessionId, filter) {
  return routePages(get, `/api/sessions/${encodeURIComponent(sessionId)}/signals`, filter, (page) => {
    if (!Array.isArray(page)) throw new Error('the signal route did not answer with a list');
    return page;
  });
}

/** Every call the route returns for [filter], newest first, each once: `routePages` over `{ items }`. */
async function transactionPages(get, sessionId, filter) {
  return routePages(get, `/api/sessions/${encodeURIComponent(sessionId)}/transactions`, filter, (page) => {
    if (!page || !Array.isArray(page.items)) throw new Error('the transaction route did not answer with a page');
    return page.items;
  });
}

/**
 * Every row a paged route returns for [filter], newest first, each once, read a page at a time;
 * `rowsOf` takes the rows out of one answer.
 *
 * The route clamps a page (to 1000 today), so one request for "all of them" silently got the
 * newest thousand. Two stop rules, and the obvious one is not among them:
 *
 * - **An empty page, or a page that adds no id not already held** — never a *short* page. Stopping
 *   at `page.length < limit` truncates again the day the server's clamp is lowered below the limit
 *   asked for, and against a source that ignores `offset` it never stops at all.
 * - **Dedupe by id.** A live row arriving mid-read shifts every newest-first page by one, so the
 *   last row of one page comes back as the first of the next.
 *
 * Any page failing fails the whole read: a silently partial list is the defect this exists to fix.
 * The offset advances by what the page held, not by the limit asked for, for the clamp's sake.
 */
async function routePages(get, route, filter, rowsOf) {
  const limit = 1000;
  const seen = new Set();
  const all = [];
  for (let offset = 0; ;) {
    const q = new URLSearchParams({ filter: filter || '', offset: String(offset), limit: String(limit) });
    const page = rowsOf(await get(`${route}?${q}`));
    let added = 0;
    for (const row of page) {
      if (seen.has(row.id)) continue;
      seen.add(row.id);
      all.push(row);
      added++;
    }
    if (added === 0) return all;
    offset += page.length;
  }
}
// @mirror-end routePages

/**
 * Keeps `allTransactions` in step with the current session.
 *
 * With no filter the rows just fetched usually are the whole session, so the common case costs
 * nothing. With a filter active — or a session past the route's clamp, where the rows fetched are
 * only the newest 1000 — it costs one paged read per *session*, not per keystroke: the endpoint
 * inventory only grows when new traffic arrives, and the live socket appends to both lists. It is
 * paged because the path counts what each visit recorded from this copy, and a copy stopped at the
 * clamp made every visit older than the newest 1000 calls read as having recorded less, or nothing.
 * `filter` and `isLatest` are the calling load's, as for `syncAllSignals`.
 */
async function syncAllTransactions(filter, isLatest = () => true) {
  if (!filter && state.transactions.length >= (state.sessionTotal ?? 0)) {
    // A copy, not an alias — the live socket pushes into both, and sharing one array would
    // append every new row twice.
    state.allTransactions = state.transactions.slice();
    state.allSessionId = state.sessionId;
    return;
  }
  if (state.allSessionId === state.sessionId && state.allTransactions.length) return;
  const sessionId = state.sessionId;
  // Opened before the first page is asked for, so a live call arriving mid-read is kept; see `admitLive`.
  const buffer = openArrivals('transactions', sessionId);
  let all;
  try {
    all = await transactionPages(api, sessionId, '');
  } catch {
    closeArrivals('transactions', buffer);
    // Chips are a convenience. If this fails, fall back to what we have rather than taking the
    // list down with it — but not marked as the session, as `syncAllSignals` does not mark its
    // stand-in: the list's empty state reads the mark, and a filtered stand-in taken for the whole
    // session said "no calls were recorded" of a session that had them.
    if (!isLatest()) return;
    state.allTransactions = state.transactions.slice();
    state.allSessionId = null;
    return;
  }
  closeArrivals('transactions', buffer);
  if (!isLatest()) return;
  state.allTransactions = withArrivals(all, buffer);
  state.allSessionId = sessionId;
}
