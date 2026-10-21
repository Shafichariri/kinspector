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
