/* init: key handling, wiring and start-up; the only file that runs anything at load, beside other files' constants */
'use strict';

document.addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT') {
    if (e.key === 'Escape') e.target.blur();
    return;
  }
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
$('session-picker').addEventListener('change', (e) => {
  state.sessionId = e.target.value;
  state.selectedId = null;
  state.timeRange = null;
  showSessionLabel();
  loadTransactions();
});
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

// Two clicks, like deleting a session: stopping ends recording for every app on this machine.
// It replaces a `confirm()` dialog, which blocks the whole page and is easy to dismiss by reflex.
$('server-stop').addEventListener('click', () => {
  confirmThen($('server-stop'), 'stop', 'stop daemon?', requestStop);
});
$('server-restart').addEventListener('click', requestRestart);
$('order-oldest').addEventListener('click', () => setSortOrder(false));
$('order-newest').addEventListener('click', () => setSortOrder(true));

$('browser-pull').addEventListener('click', pullProviders);
$('browser-filter').addEventListener('input', (e) => {
  state.browserFilter = e.target.value;
  renderBrowser();
});

$('session-delete').addEventListener('click', () => {
  if (state.sessionId) {
    confirmThen($('session-delete'), '✕', 'delete session?', () => deleteSession(state.sessionId));
  }
});
$('sessions-clear').addEventListener('click', () => {
  confirmThen($('sessions-clear'), 'clear all', 'delete every session?', clearSessions);
});
$('sessions-refresh').addEventListener('click', async () => {
  await loadSessions();
  renderSessionRows();
});

(async function init() {
  setSortOrder(state.newestFirst);   // paints the chips to match the remembered preference
  wirePops();
  wireMarkerForm();
  wireAxis();
  wirePath();
  $('drawer-close').addEventListener('click', closeDrawer);
  // The scrim is the whole point of a scrim: click anywhere off the drawer and it goes away.
  $('drawer-scrim').addEventListener('click', closeDrawer);

  $('now-toggle').addEventListener('click', () => {
    const strip = $('now-strip');
    const open = $('current').hidden;
    $('current').hidden = !open;
    strip.classList.toggle('open', open);
    $('now-toggle').setAttribute('aria-expanded', String(open));
  });

  $('open-settings').addEventListener('click', () => {
    $('settings').showModal();
    loadPeers();
    loadMcp();
    applyDuplicateWindow(state.duplicateWindowMs);   // refreshes the count for this session
    applyEndpointLimit(state.endpointLimit);
    renderSessionRows();
  });
  $('peers-refresh').addEventListener('click', loadPeers);
  $('dup-window').value = state.duplicateWindowMs;
  $('dup-window').addEventListener('change', (e) => applyDuplicateWindow(Number(e.target.value)));
  $('endpoint-limit').value = state.endpointLimit;
  $('endpoint-limit').addEventListener('change', (e) => applyEndpointLimit(Number(e.target.value)));
  $('mcp-probe').addEventListener('click', probeMcp);
  $('mcp-copy').addEventListener('click', () => {
    const text = $('mcp-command').textContent;
    navigator.clipboard.writeText(text).then(() => {
      const button = $('mcp-copy');
      button.textContent = 'copied';
      setTimeout(() => (button.textContent = 'copy command'), 1200);
    });
  });

  // Ages are wall-clock now, so they have to be repainted or they are wrong the moment they
  // are drawn. Only the text of the age nodes changes; nothing re-renders around them.
  setInterval(paintAges, 1000);

  if (ARCHIVE) enterArchiveMode(ARCHIVE);
  else await loadServerInfo();
  wireExportMenu();
  await loadSessions();
  await loadTransactions();
  // Not merely pointless in an export: opened from disk, `location.host` is empty and the
  // socket URL is malformed, so the constructor throws and takes the rest of init with it.
  if (!ARCHIVE) connectLive();
})();
