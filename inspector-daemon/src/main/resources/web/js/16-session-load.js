/* session load: what a switch clears, and what a pane says until the session is read */
'use strict';

/**
 * What every pane says while the selected session is being read, and the one place that says it,
 * so a second wording can be found by searching for `being read`. With [total], the header's
 * `sessionTotal`, it counts the calls; the numbers are not grouped, as `newest 1000 of 4210 shown`.
 */
function beingRead(total = null) {
  const what = total === null || total === undefined
    ? 'this session is' : `this session's ${total} ${total === 1 ? 'call is' : 'calls are'}`;
  return `${what} being read…`;
}

/** True from a switch, or the page opening, until the selected session's first load has drawn it:
 *  its calls, markers and signals (`signalSessionId`, set when `loadSignals` lands). */
const sessionPending = () => Boolean(state.sessionId) && state.signalSessionId !== state.sessionId;

/** While the session is pending, puts `beingRead()` in [node], shows it, and says so: the one guard
 *  a call pane takes before it draws rows or an empty state. */
function saysBeingRead(node) {
  if (!sessionPending()) return false;
  node.replaceChildren(beingRead());
  node.hidden = false;
  return true;
}

/**
 * Clears the page the moment the selected session is no longer the one it was loaded for, calls and
 * signals alike.
 *
 * A switch reads a page of calls, the markers and the whole-session calls before `loadSignals`, and
 * until that lands the now strip, the timeline's signal rows and the glance described the session
 * just left under the new one's name — for as long as a whole-session read takes, 25 s on a large
 * one. Called by the picker (through `loadTransactions`) and by `afterSessionChange` before it waits
 * for the session list, so a delete or a clear does not show the deleted session meanwhile. It
 * overtakes the signal load and every `/current` read in flight, which belong to the session left.
 * The calls are cleared too: the first page replaced the list alone, and the timeline, the chips,
 * the axis and the markers drew the session left until the whole load ended.
 */
function followSession() {
  if (state.signalSessionId === state.sessionId) return;
  state.signalSessionId = null;
  state.signalLoad++;
  // `afterSessionChange` waits for the session list before it loads, and a load of the session left
  // still in flight would otherwise pass its `isLatest()` there and draw that session back.
  state.callLoad++;
  state.currentRead.written = state.currentRead.sent;
  state.signals = [];
  state.allSignals = [];
  state.allSignalsSessionId = null;
  state.screens = [];
  state.current = [];
  state.currentSessionId = null;
  state.transactions = [];
  state.allTransactions = [];
  state.allSessionId = null;
  state.markers = [];
  state.sessionTotal = null;
  state.matched = null;
  // The call open is the session left's, unless an address opened it on this one (`showTxnFull`).
  if (!state.selectedId) { $('detail').hidden = true; $('detail-empty').hidden = false; paintDetailTools(); }
  renderCurrent();
  renderCounts();
  renderTabs();
  renderMarkers();
  renderList();
  // Whichever view is shown, with the axis and the glance, so a view added later is covered too.
  applyView();
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
  // With no session there is nothing to load, and the empty list is the only thing that can
  // say what the page is waiting for.
  if (!state.sessionId) { renderListEmpty(); return; }
  followSession();
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
