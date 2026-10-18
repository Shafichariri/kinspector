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
 */
function renderCounts() {
  const total = state.matchTotal;
  if (total === null || total === undefined) {
    $('counts').textContent = '';
    return;
  }
  // With its noun: a bare `12/12` beside a session picker could be a count of anything.
  $('counts').textContent = `${visibleTransactions().length} of ${total} ${total === 1 ? 'call' : 'calls'}`;
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
  const q = new URLSearchParams({ filter, limit: '2000' });
  let page = null;
  let error = null;
  try {
    page = await api(`/api/sessions/${id}/transactions?${q}`);
  } catch (e) {
    error = e;
  }
  if (!isLatest()) return;
  if (page) {
    state.transactions = page.items;
    state.matchTotal = page.total;
    showFilterError(null);
  } else {
    // Parser messages are written to be shown verbatim, so show them verbatim.
    showFilterError(error.message);
    state.transactions = [];
    state.matchTotal = null;
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
  const signals = await signalPages(api, sessionId, filter).catch(() => []);
  if (!isLatest()) return;
  state.signals = signals;
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
  try {
    const all = await signalPages(api, sessionId, '');
    if (!isLatest()) return;
    state.allSignals = all;
    state.allSignalsSessionId = sessionId;
  } catch {
    if (!isLatest()) return;
    state.allSignals = state.signals.slice();
    state.allSignalsSessionId = null;
  }
}

/**
 * Drops both whole-session copies, so the next load reads them again.
 *
 * They are cached per session under a filter, and the live socket is the only thing that keeps
 * them current. Whatever arrived while it was not listening — the list paused, the page
 * disconnected, the daemon restarted — is in neither copy, so a re-read that trusted the cache
 * would draw tabs, the route and endpoint chips without it until the filter was cleared.
 */
function forgetWholeSession() {
  state.allSessionId = null;
  state.allSignalsSessionId = null;
}

// @mirror-begin signalPages
/**
 * Every signal the route returns for [filter], newest first, each once, read a page at a time.
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
async function signalPages(get, sessionId, filter) {
  const limit = 1000;
  const seen = new Set();
  const all = [];
  for (let offset = 0; ;) {
    const q = new URLSearchParams({ filter: filter || '', offset: String(offset), limit: String(limit) });
    const page = await get(`/api/sessions/${encodeURIComponent(sessionId)}/signals?${q}`);
    if (!Array.isArray(page)) throw new Error('the signal route did not answer with a list');
    let added = 0;
    for (const signal of page) {
      if (seen.has(signal.id)) continue;
      seen.add(signal.id);
      all.push(signal);
      added++;
    }
    if (added === 0) return all;
    offset += page.length;
  }
}
// @mirror-end signalPages

/**
 * Keeps `allTransactions` in step with the current session.
 *
 * With no filter the rows just fetched already are the whole session, so the common case costs
 * nothing. With a filter active it costs one extra request per *session*, not per keystroke:
 * the endpoint inventory only grows when new traffic arrives, and the live socket appends to
 * both lists. `filter` and `isLatest` are the calling load's, as for `syncAllSignals`.
 */
async function syncAllTransactions(filter, isLatest = () => true) {
  if (!filter) {
    // A copy, not an alias — the live socket pushes into both, and sharing one array would
    // append every new row twice.
    state.allTransactions = state.transactions.slice();
    state.allSessionId = state.sessionId;
    return;
  }
  if (state.allSessionId === state.sessionId && state.allTransactions.length) return;
  const sessionId = state.sessionId;
  let all;
  try {
    const q = new URLSearchParams({ filter: '', limit: '2000' });
    all = (await api(`/api/sessions/${encodeURIComponent(sessionId)}/transactions?${q}`)).items;
  } catch {
    // Chips are a convenience. If this fails, fall back to what we have rather than taking the
    // list down with it.
    all = state.transactions.slice();
  }
  if (!isLatest()) return;
  state.allTransactions = all;
  state.allSessionId = sessionId;
}
