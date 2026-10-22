/* init: key handling, wiring and start-up; the only file that runs anything at load, beside other files' constants */
'use strict';

document.addEventListener('keydown', (e) => {
  // Typing is never a shortcut, in the replay editor's textarea as in the filter (`typingTarget`).
  if (typingTarget(e.target)) {
    if (e.key === 'Escape') e.target.blur();
    return;
  }
  if (e.key === 'Escape' && !$('keys-pop').hidden) {
    $('keys-pop').hidden = true;
    return;
  }
  // `?` and the page keys work on every page, `#/txn` included. A held ⌘, Ctrl or Alt is the
  // browser's shortcut, not ours: ⌘G then S is find-next and a letter, never a page.
  if (!e.metaKey && !e.ctrlKey && !e.altKey) {
    if (e.key === '?') {
      state.pageKeyAt = null;
      showKeys();
      return;
    }
    const read = readPageKey(state.pageKeyAt, e.key, Date.now());
    state.pageKeyAt = read.pending;
    if (read.page) navigate({ page: read.page });
    if (read.top && currentPage() === 'traffic' && !$('panes').classList.contains('txn-full')) move(-1e9);
    if (read.consumed) return;
  }
  // Every key below moves through, filters or copies the traffic list, which only one page draws.
  if (currentPage() !== 'traffic') return;
  // A call's own page has no list to move through; Escape leaves it as Back does.
  if ($('panes').classList.contains('txn-full')) {
    if (e.key === 'Escape') leaveTxnFull();
    return;
  }
  switch (e.key) {
    case 'j': move(1); break;
    case 'k': move(-1); break;
    case '/': case 'f': e.preventDefault(); $('filter').focus(); break;
    case 'o': setSortOrder(!state.newestFirst); break;
    case 'G': move(1e9); break;
    case 'Escape':
      state.selectedId = null;
      $('detail').hidden = true;
      $('detail-empty').hidden = false;
      renderSessionGlance();
      closeDrawer();
      paintDetailTools();
      for (const row of document.querySelectorAll('.row')) row.classList.remove('selected');
      break;
    case 'c': {
      const txn = state.transactions.find((t) => t.id === state.selectedId);
      if (txn) fetchBody(txn, 'req').then((b) => navigator.clipboard.writeText(curlFor(txn, b)));
      break;
    }
  }
});

$('filter').addEventListener('input', applyFilter);
$('session-picker').addEventListener('change', (e) => switchSession(e.target.value));
$('live-btn').addEventListener('click', () => {
  setLiveTail(!state.liveTail);
  // Resuming reads the findings again, as `setLiveTail` reads Stats: the calls missed while paused count.
  if (state.liveTail) findingsReload();
});
// Scoped to the filter rail, not every `.chip` on the page. Order chips and facet chips are
// chips too, and they carry no `data-filter` — a blanket handler set the filter box to
// `undefined`, which parses as a bad filter and empties the whole list.
for (const chip of document.querySelectorAll('#chips .chip')) {
  chip.addEventListener('click', () => {
    const value = chip.dataset.filter;
    $('filter').value = $('filter').value === value ? '' : value;
    applyFilter();
  });
}

$('order-oldest').addEventListener('click', () => setSortOrder(false));
$('order-newest').addEventListener('click', () => setSortOrder(true));

$('browser-pull').addEventListener('click', pullProviders);
$('browser-filter').addEventListener('input', (e) => {
  state.browserFilter = e.target.value;
  renderBrowser();
});

// The rail's links go through `navigate`, so a page is a history entry and its heading takes focus.
// A modified click is left to the browser, which opens the address in a new tab or window.
for (const link of document.querySelectorAll('#rail .rail-item')) {
  link.addEventListener('click', (e) => {
    if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    e.preventDefault();
    navigate({ page: link.dataset.page });
  });
}
$('rail-toggle').addEventListener('click', toggleRail);
// Back and Forward, and an address typed or pasted, arrive here; `navigate` applies its own.
window.addEventListener('hashchange', applyRoute);
wireSessionsPage();
wireProcessesPage();
wireSettingsPage();
wireStatsPage();

(async function init() {
  setSortOrder(state.newestFirst);   // paints the chips to match the remembered preference
  wirePops();
  wireMarkerForm();
  wireAxis();
  wirePath();
  $('drawer-close').addEventListener('click', closeDrawer);
  wireDivider();
  $('detail-hide').addEventListener('click', hideDetail);
  $('detail-restore').addEventListener('click', restoreDetail);
  // `⤢` is a link, so a modified click opens the call in a new tab; a plain one pushes the address,
  // which is what lets Back and Escape return to the list as it was.
  $('detail-full').addEventListener('click', (e) => {
    if (e.button > 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || !state.selectedId) return;
    e.preventDefault();
    state.txnPushed = true;
    navigate({ page: 'txn', txn: state.selectedId, session: state.sessionId });
  });
  // Escape's own handler, not a copy of it, so the page's control and its key cannot disagree about
  // where a call's page returns to.
  $('txn-back').addEventListener('click', leaveTxnFull);
  // The scrim is the whole point of a scrim: click anywhere off the drawer and it goes away.
  $('drawer-scrim').addEventListener('click', closeDrawer);

  setRailMode();

  // Ages are wall-clock now, so they have to be repainted or they are wrong the moment they
  // are drawn. Only the text of the age nodes changes; nothing re-renders around them.
  setInterval(paintAges, 1000);

  if (ARCHIVE) enterArchiveMode(ARCHIVE);
  else await loadServerInfo();
  wireMoreMenu();   // paints the density the page was left in
  // The page first, and a `?session=` before the list is read, so the first load is that session's.
  await applyRoute();
  await loadSessions();
  // The socket opens before the first page, not after the load: a row is matched against the selected
  // session when it arrives and needs nothing a load reads. Opened after the load, on a 160,000-call
  // session the dot read "connecting…" for 26 s and every row that arrived meanwhile was dropped.
  // Not in an export: opened from disk, `location.host` is empty and the socket URL is malformed, so
  // the constructor throws and takes the rest of init with it.
  if (!ARCHIVE) connectLive();
  // Opened on `#/stats` or `#/findings`, it had no session to ask about until now. Asked before the
  // calls are read, not after: on a 160,000-call session that read takes half a minute, and neither
  // page needs any of it. Traffic's badges are asked for after its first page of calls, which is
  // what that page is opened to read.
  statsReload();
  if (findingsShown()) findingsReload();
  await loadTransactions();
  if (!findingsShown()) findingsReload();
  // Then the view, which a session must have loaded to offer.
  await applyRoute();
})();
