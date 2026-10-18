/* settings: duplicate window and endpoint limit */
'use strict';

// --- settings: duplicate detection ------------------------------------------------------------

function applyDuplicateWindow(valueMs) {
  const ms = Number.isFinite(valueMs) && valueMs >= 0 ? Math.round(valueMs) : 3000;
  state.duplicateWindowMs = ms;
  prefs.set('inspector.duplicateWindowMs', String(ms));
  $('dup-window').value = ms;
  // The unfiltered set, matching what the list actually highlights. Counting the filtered rows
  // instead made the summary read 0 while the list showed two tinted rows behind the dialog.
  $('dup-summary').textContent = ms === 0
    ? 'off'
    : `${computeDuplicates(state.allTransactions, ms).size} row(s) in this session`;
  renderList();
}

// --- settings: endpoint shortcuts -------------------------------------------------------------

function applyEndpointLimit(value) {
  const limit = Number.isFinite(value) && value >= 0 ? Math.round(value) : 10;
  state.endpointLimit = limit;
  prefs.set('inspector.endpointChipLimit', String(limit));
  $('endpoint-limit').value = limit;

  const total = new Set(
    state.allTransactions.map((t) => lastSegment(t.path)).filter(Boolean),
  ).size;
  $('endpoint-summary').textContent = limit === 0
    ? 'hidden'
    : `showing ${Math.min(limit, total)} of ${total} in this session`;
  renderEndpointChips();
}
