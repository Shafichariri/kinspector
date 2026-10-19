/* Probes of the web UI on a session past the route's 1000-row clamp, live and in an export.
 *
 * The CI probe directory holds a second session, written by `writeProbeFixture` beside the probe
 * session: 1203 calls, in which call #0 and call #203 are one request sent twice by different calls
 * and every other call is distinct (`ProbeFixtureTest` asserts each of those). #203 is the oldest of
 * the newest 1000, so one page of the route draws it and holds nothing it repeats. The endpoint
 * chips and the repeat marks read the page's whole-session copy, and only a copy read past the clamp
 * makes the `twice` chip count 2 and marks #203; read from the first page alone, it counts 1 and
 * marks nothing. With no filter the live list draws the page's own 1000, and the header says
 * `newest 1000 of 1203 shown`, never the `1000 of 1203 calls` a filter produces. An export draws
 * every call it embeds and says `1203 of 1203 calls`: it has no filter to reach past a ceiling.
 *
 * `render-web-ui.js` switches its page to the session and back through `probeLongSession`;
 * `render-web-ui-archive.js` opens the session's export and asks `readLongSession` the same
 * questions, so the lines mean the same thing in both, the count's due text aside. Each returns `[label, verdict]` pairs, one
 * per line the caller prints, so a new line here needs no wiring there.
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

/** The report's labels, in the order the lines print. */
const LONG_LABELS = ['long chips count oldest', 'long repeat marked', 'long count no filter'];
const every = (why) => LONG_LABELS.map((label) => [label, why]);

/**
 * The verdicts, read from a page that is showing the long session with no filter; `exported` says
 * the page is an export, which draws every call rather than the newest 1000.
 */
function readLongSession(doc, calls, { exported = false } = {}) {
  const pair = twicePair(calls);
  if (pair.length !== 2) return every(`n/a (the session has ${pair.length} calls to /twice, not 2)`);
  const chip = [...doc.querySelectorAll('#endpoint-chips .chip')].find((c) => c.firstChild?.textContent === 'twice');
  const count = chip?.querySelector('.chip-count')?.textContent;
  const newer = doc.querySelector(`#list .row[data-id="${pair[1].id}"]`);
  const counts = doc.getElementById('counts').textContent;
  const due = exported ? `${calls.length} of ${calls.length} calls` : `newest 1000 of ${calls.length} shown`;
  return [
    [LONG_LABELS[0], count === '2'
      ? 'yes (the twice chip counts 2, the oldest call included)'
      : `NO - the twice chip counts ${count ?? 'nothing'} (1 is the newest 1000 alone)`],
    [LONG_LABELS[1], !newer
      ? `NO - no row drawn for ${pair[1].id}, the oldest of the newest 1000`
      : newer.classList.contains('duplicate')
        ? `yes (${pair[1].id} is marked as a repeat of ${pair[0].id}, past the clamp)`
        : `NO - ${pair[1].id} is drawn unmarked (its repeat is older than the newest 1000)`],
    [LONG_LABELS[2], counts === due
      ? `yes ("${counts}", ${exported ? 'every call the export embeds' : 'which no filter reads like'})`
      : `NO - "${counts}", where "${due}" was due`],
  ];
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
  if (![...picker.options].some((o) => o.value === LONG_SESSION_ID)) return every(`n/a (no session ${LONG_SESSION_ID})`);
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

module.exports = { LONG_LABELS, LONG_SESSION_ID, probeLongSession, readLongSession, waitFor };
