/* repeats and endpoints: repeated requests and endpoint chips */
'use strict';

// @mirror-begin duplicates
/**
 * Mirror of `duplicateGroups` in :inspector-model — same rules, so a row that highlights here
 * highlights in the app too. Keep the two in step; the Kotlin one carries the reasoning.
 *
 * The two rules that matter: a *different* callId is required, because redirect hops and retry
 * attempts share one and are a single logical call; and headers are excluded, because a signed
 * app puts a fresh nonce on every request and a key including them would never match twice.
 */
function duplicateKey(txn) {
  return [
    txn.method,
    urlOf(txn),
    txn.status ?? -1,
    `${txn.reqBytes || 0}/${txn.resBytes || 0}`,
  ].join(' ');
}

function computeDuplicates(txns, windowMs) {
  const marks = new Map();
  if (!windowMs || windowMs <= 0 || txns.length < 2) return marks;

  const byKey = new Map();
  for (const txn of txns) {
    const key = duplicateKey(txn);
    if (!byKey.has(key)) byKey.set(key, []);
    byKey.get(key).push(txn);
  }

  let ordinal = 0;
  for (const group of byKey.values()) {
    // `mono` is the monotonic clock. `ts` is a wall clock that can step, and the device and host
    // clocks are unrelated — so timing uses mono and only the display uses ts.
    const ordered = [...group].sort((a, b) => a.mono - b.mono);
    let run = [];
    const flush = () => {
      const calls = new Set(run.map((t) => t.callId)).size;
      if (run.length > 1 && calls > 1) {
        ordinal += 1;
        const span = run[run.length - 1].mono - run[0].mono;
        // Calls, not rows — the model's `callCount`. A group that swept up a retry has more rows
        // than calls, and the reader is asking how many times the app asked.
        for (const t of run) marks.set(t.id, { ordinal, count: calls, spanMs: span });
      }
      run = [];
    };
    for (const txn of ordered) {
      if (run.length && txn.mono - run[run.length - 1].mono > windowMs) flush();
      run.push(txn);
    }
    flush();
  }
  return marks;
}
// @mirror-end duplicates

// @mirror-begin endpointShortcuts
/**
 * The last non-empty path segment: `/v3/accounts/profile/status` -> `status`.
 *
 * Trailing slashes are ignored. A path with no segments at all (`/`) has no shortcut worth
 * offering, so it yields null rather than an empty chip.
 */
function lastSegment(path) {
  const segments = String(path || '').split('/').filter((s) => s.length > 0);
  return segments.length ? segments[segments.length - 1] : null;
}

// Builds a star-glob term - for the segment `status`, the filter `path:` star `/status`.
//
// A bare `path:` term is a substring match, so `path:profile` would also match
// `/v3/accounts/profile/status` and the chip would not mean what its label says. The star makes
// it a glob, and `globMatches` anchors the trailing literal with `endsWith`, so the term matches
// only paths that end in `/status`.
//
// Line comments on purpose: the term contains the sequence that closes a block comment.
const endpointFilter = (segment) => `path:*/${segment}`;

/**
 * One entry per distinct last segment, most-used first.
 *
 * Ordered by count rather than recency deliberately: recency would reshuffle the whole chip row
 * on every request during live tail, and the endpoints worth a one-click filter are the ones
 * that dominate the list. Ties break on the most recent call and then on the segment, so the
 * order is fully determined and the jsdom harness can assert it.
 *
 * The last tie compares UTF-16 code units, as the model's `thenBy { it.segment }` does, and not
 * `localeCompare`: the two disagree on case — `Balance` sorts before `accounts` here — and the
 * overlay already draws the model's order for the same session.
 */
function endpointShortcuts(txns, limit) {
  if (!limit || limit <= 0) return [];
  const bySegment = new Map();
  for (const txn of txns) {
    const segment = lastSegment(txn.path);
    // The filter tokenizer splits on whitespace and `|`, so a segment containing either cannot
    // be expressed as a term. Skipping beats emitting a chip that filters to the wrong thing.
    if (!segment || /[\s|"]/.test(segment)) continue;
    const entry = bySegment.get(segment) || { segment, count: 0, latest: -Infinity };
    entry.count += 1;
    if (txn.mono > entry.latest) entry.latest = txn.mono;
    bySegment.set(segment, entry);
  }
  return [...bySegment.values()]
    .sort((a, b) => b.count - a.count || b.latest - a.latest || (a.segment < b.segment ? -1 : a.segment > b.segment ? 1 : 0))
    .slice(0, limit);
}
// @mirror-end endpointShortcuts

function renderEndpointChips() {
  const box = $('endpoint-chips');
  const shortcuts = endpointShortcuts(state.allTransactions, state.endpointLimit);
  box.innerHTML = '';
  box.hidden = shortcuts.length === 0;
  // Named, like the row above it: two rows of identical chips otherwise read as one list that
  // wrapped, and these are a different kind of thing — the session's own endpoints.
  if (shortcuts.length) box.appendChild(el('span', 'row-label', 'endpoints'));

  for (const { segment, count } of shortcuts) {
    const value = endpointFilter(segment);
    const chip = el('button', 'chip');
    chip.dataset.filter = value;
    chip.title = `${count} request${count === 1 ? '' : 's'} ending in /${segment}`;
    chip.appendChild(el('span', null, segment));
    chip.appendChild(el('span', 'chip-count', String(count)));
    chip.classList.toggle('active', value === state.filter);
    chip.addEventListener('click', () => {
      $('filter').value = $('filter').value === value ? '' : value;
      applyFilter();
    });
    box.appendChild(chip);
  }
}
