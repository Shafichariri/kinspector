/* Probes of the web UI on a session past the route's 1000-row clamp, live and in an export.
 *
 * The CI probe directory holds a second session, written by `writeProbeFixture` beside the probe
 * session: 1203 calls, in which call #0 and call #203 are one request sent twice by different calls
 * and every other call is distinct (`ProbeFixtureTest` asserts each of those). #203 is the oldest of
 * the newest 1000, so one page of the route draws it and holds nothing it repeats. The endpoint
 * chips and the repeat marks read the page's whole-session copy, and only a copy read past the clamp
 * makes the `twice` chip count 2 and marks #203; read from the first page alone, it counts 1 and
 * marks nothing.
 *
 * `render-web-ui.js` switches its page to the session and back through `probeLongSession`;
 * `render-web-ui-archive.js` opens the session's export and asks `readLongSession` the same
 * questions, so the two lines mean the same thing in both. Each returns `{ key: verdict }`, and the
 * caller prints one line per key.
 */

/** The long session's id, as `ProbeFixture.kt` names it. */
const LONG_SESSION_ID = '2025-12-31T09-00-00_probe-long_ci_debug';

const pause = (ms) => new Promise((r) => setTimeout(r, ms));

/** Polls `ready` until it holds or `timeoutMs` passes; whether it held. */
async function waitFor(ready, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (ready()) return true;
    await pause(100);
  }
  return ready();
}

/**
 * The repeated pair in [calls], oldest first: the calls to `/twice`. Taken from the session's own
 * rows — the daemon's, or the export's embedded ones — never from the page under test.
 */
function twicePair(calls) {
  return calls.filter((t) => String(t.path).endsWith('/twice')).sort((a, b) => a.mono - b.mono);
}

/** The two verdicts, read from a page that is showing the long session. */
function readLongSession(doc, calls) {
  const pair = twicePair(calls);
  if (pair.length !== 2) {
    const why = `n/a (the session has ${pair.length} calls to /twice, not 2)`;
    return { longChips: why, longRepeat: why };
  }
  const chip = [...doc.querySelectorAll('#endpoint-chips .chip')].find((c) => c.firstChild?.textContent === 'twice');
  const count = chip?.querySelector('.chip-count')?.textContent;
  const newer = doc.querySelector(`#list .row[data-id="${pair[1].id}"]`);
  return {
    longChips: count === '2'
      ? 'yes (the twice chip counts 2, the oldest call included)'
      : `NO - the twice chip counts ${count ?? 'nothing'} (1 is the newest 1000 alone)`,
    longRepeat: !newer
      ? `NO - no row drawn for ${pair[1].id}, the oldest of the newest 1000`
      : newer.classList.contains('duplicate')
        ? `yes (${pair[1].id} is marked as a repeat of ${pair[0].id}, past the clamp)`
        : `NO - ${pair[1].id} is drawn unmarked (its repeat is older than the newest 1000)`,
  };
}

/** Picks a session through the picker's real `change` event. */
function choose(ctx, sessionId) {
  const picker = ctx.doc.getElementById('session-picker');
  picker.value = sessionId;
  picker.dispatchEvent(new ctx.window.Event('change', { bubbles: true }));
}

/**
 * Switches the live page to the long session, reads both verdicts, and switches back to the session
 * it was showing, so every later probe sees what it saw before.
 *
 * It waits for the newer `/twice` row rather than for the list to stop changing: the list holds the
 * previous session's rows while the whole-session read pages, which a settle can take for done.
 */
async function probeLongSession(ctx) {
  const { doc } = ctx;
  const picker = doc.getElementById('session-picker');
  const before = picker.value;
  if (![...picker.options].some((o) => o.value === LONG_SESSION_ID)) {
    const why = `n/a (no session ${LONG_SESSION_ID})`;
    return { longChips: why, longRepeat: why };
  }
  const calls = (await ctx.sessionTransactions(encodeURIComponent(LONG_SESSION_ID))).items || [];
  const pair = twicePair(calls);
  choose(ctx, LONG_SESSION_ID);
  if (pair.length === 2) await waitFor(() => doc.querySelector(`#list .row[data-id="${pair[1].id}"]`));
  await ctx.settle();
  const verdicts = readLongSession(doc, calls);

  choose(ctx, before);
  const longRows = new Set(calls.map((t) => t.id));
  await waitFor(() => {
    const rows = [...doc.querySelectorAll('#list .row')];
    return rows.length > 0 && rows.every((r) => !longRows.has(r.dataset.id));
  });
  await ctx.settle();
  return verdicts;
}

module.exports = { LONG_SESSION_ID, probeLongSession, readLongSession, waitFor };
