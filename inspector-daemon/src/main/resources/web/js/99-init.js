/* init: key handling, wiring and start-up; the only file that runs anything at load, beside other files' constants */
'use strict';

document.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT') {
    if (e.key === 'Escape') e.target.blur();
    return;
  }
  // Every key below moves through, filters or copies the traffic list, which only one page draws.
  if (currentPage() !== 'traffic') return;
  switch (e.key) {
    case 'j': move(1); break;
    case 'k': move(-1); break;
    case '/': case 'f': e.preventDefault(); $('filter').focus(); break;
    case 'o': setSortOrder(!state.newestFirst); break;
    case 'g': move(-1e9); break;
    case 'G': move(1e9); break;
    case 'Escape':
      state.selectedId = null;
      $('detail').hidden = true;
      $('detail-empty').hidden = false;
      renderSessionGlance();
      closeDrawer();
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
$('live-btn').addEventListener('click', () => setLiveTail(!state.liveTail));
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

(async function init() {
  setSortOrder(state.newestFirst);   // paints the chips to match the remembered preference
  wirePops();
  wireMarkerForm();
  wireAxis();
  wirePath();
  $('drawer-close').addEventListener('click', closeDrawer);
  // The scrim is the whole point of a scrim: click anywhere off the drawer and it goes away.
  $('drawer-scrim').addEventListener('click', closeDrawer);

  setRailMode();

  // Ages are wall-clock now, so they have to be repainted or they are wrong the moment they
  // are drawn. Only the text of the age nodes changes; nothing re-renders around them.
  setInterval(paintAges, 1000);

  if (ARCHIVE) enterArchiveMode(ARCHIVE);
  else await loadServerInfo();
  wireExportMenu();
  // The page first, and a `?session=` before the list is read, so the first load is that session's.
  await applyRoute();
  await loadSessions();
  await loadTransactions();
  // Then the view, which a session must have loaded to offer.
  await applyRoute();
  // Not merely pointless in an export: opened from disk, `location.host` is empty and the
  // socket URL is malformed, so the constructor throws and takes the rest of init with it.
  if (!ARCHIVE) connectLive();
})();
