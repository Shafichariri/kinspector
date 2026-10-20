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
 * Past the ceiling the list says what it holds: under a filter matching 1201 the header reads
 * `newest 1000 of 1201 matches shown` and the list draws the panes' `filterNote`, and a stretch over
 * the oldest calls says they are older than what the list loaded (`probeCapped`, live only).
 *
 * `render-web-ui.js` switches its page to the session and back through `probeLongSession`;
 * `render-web-ui-archive.js` opens the session's export and asks `readLongSession` the same
 * questions, so the lines mean the same thing in both, the count's due text aside. Each returns `[label, verdict]` pairs, one
 * per line the caller prints, so a new line here needs no wiring there.
 */

/** The long session's id, as `ProbeFixture.kt` names it. */
const LONG_SESSION_ID = '2025-12-31T09-00-00_probe-long_ci_debug';

const { typeFilter } = require('./filter-probes');

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

/** The capped list's lines, live only: an export draws every call it embeds and has no filter. */
const CAPPED_LABELS = ['long filtered count', 'long list note', 'long stretch older'];

/**
 * The list past its ceiling, on the page showing the long session with no filter. Under
 * `path:/v3/long/bulk`, which matches 1201, the header numbers the matches and the list draws the
 * note the other panes draw; with no filter, a stretch over the oldest 151 calls, all below the
 * newest 1000, says they are older than what the list loaded rather than that there are none.
 * Leaves the page unfiltered and unbrushed.
 */
async function probeCapped(ctx, calls) {
  const { doc, window } = ctx;
  const counts = () => doc.getElementById('counts').textContent;
  const bulk = calls.filter((t) => String(t.path).endsWith('/bulk')).length;
  // The note belongs to the list, so it shows only on the list's tab; put back whichever was shown.
  const tab = (view) => doc.querySelector(`#tabs .tab[data-view="${view}"]`);
  const shown = doc.querySelector('#tabs .tab.active')?.dataset.view;
  tab('network')?.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  await typeFilter(ctx, 'path:/v3/long/bulk');
  const filtered = counts();
  const note = doc.getElementById('list-note');
  const noteText = note ? note.textContent : '';
  const noteHidden = !note || note.hidden;
  const rows = doc.querySelectorAll('#list .row').length;
  await typeFilter(ctx, '');
  const byMono = calls.slice().sort((a, b) => a.mono - b.mono);
  window.setTimeRange({ from: byMono[0].mono, to: byMono[150].mono });
  await ctx.settle();
  const empty = doc.getElementById('list-empty');
  const stretchText = empty.hidden ? '(hidden)' : empty.textContent;
  window.setTimeRange(null);
  if (shown && shown !== 'network') tab(shown)?.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  await ctx.settle();
  const countDue = `newest 1000 of ${bulk} matches shown`;
  const noteDue = `newest 1000 of ${bulk} matches loaded`;
  const stretchDue = '151 calls in this stretch are older than the newest 1000 the list loaded — filter to reach them';
  return [
    [CAPPED_LABELS[0], filtered === countDue
      ? `yes ("${filtered}", ${bulk} matched by the route, ${rows} rows drawn)`
      : `NO - "${filtered}", where "${countDue}" was due`],
    [CAPPED_LABELS[1], !noteHidden && noteText.includes(noteDue)
      ? `yes ("${noteText.slice(0, noteText.indexOf(noteDue) + noteDue.length)}")`
      : `NO - ${note ? `"${noteText}"${noteHidden ? ' (hidden)' : ''}` : 'no #list-note'}, where "… ${noteDue}" was due`],
    [CAPPED_LABELS[2], stretchText === stretchDue
      ? `yes ("${stretchText.slice(0, 60)}…")`
      : `NO - "${stretchText}", where "${stretchDue}" was due`],
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
  const none = (why) => [...every(why), ...CAPPED_LABELS.map((label) => [label, why])];
  if (![...picker.options].some((o) => o.value === LONG_SESSION_ID)) return none(`n/a (no session ${LONG_SESSION_ID})`);
  const calls = (await ctx.sessionTransactions(encodeURIComponent(LONG_SESSION_ID))).items || [];
  const pair = twicePair(calls);
  choose(ctx, LONG_SESSION_ID);
  if (pair.length === 2) await waitFor(() => doc.querySelector(`#list .row[data-id="${pair[1].id}"]`));
  await ctx.settle();
  const verdicts = readLongSession(doc, calls);
  verdicts.push(...(calls.length > 1150 ? await probeCapped(ctx, calls) : CAPPED_LABELS.map((label) => [label, `n/a (${calls.length} calls)`])));

  choose(ctx, before);
  const longRows = new Set(calls.map((t) => t.id));
  await waitFor(() => {
    const rows = [...doc.querySelectorAll('#list .row')];
    return rows.length > 0 && rows.every((r) => !longRows.has(r.dataset.id));
  });
  await ctx.settle();
  return verdicts;
}

module.exports = { LONG_LABELS, LONG_SESSION_ID, choose, probeLongSession, readLongSession, waitFor };
