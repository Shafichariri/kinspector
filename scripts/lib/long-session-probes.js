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
const LONG_LABELS = ['long chips count oldest', 'long repeat marked', 'long count no filter', 'counts agree'];

/**
 * Every count the traffic page draws, by the words around it: which numbers it labels the session's,
 * and which it labels as something else — the rows drawn, the matches, the calls under the prefix.
 * Row 3's header and list-note wordings are here as the matches they count. A count that fits none
 * is unlabelled, which is the defect: `traffic 1000` beside `newest 1000 of 1203 shown`, a bare
 * `1203 of 1203` on the scope bar and `1203 calls` on the axis were four numbers for one session.
 * `session` lists the capture groups that are the session's call count.
 */
const COUNT_LABELS = [
  { where: 'header', label: 'rows of the session', re: /^newest (\d+) of (\d+) shown$/, session: [2] },
  { where: 'header', label: 'matches drawn', re: /^newest (\d+) of (\d+) matches shown$/, session: [] },
  { where: 'header', label: 'matches in a stretch', re: /^(\d+) of the newest (\d+) of (\d+) matches shown$/, session: [] },
  { where: 'header', label: 'calls drawn', re: /^(\d+) of (\d+) calls?$/, session: [2] },
  { where: 'list note', label: 'matches loaded', re: /newest (\d+) of (\d+) matches loaded/, session: [] },
  { where: 'scope', label: 'under the prefix', re: /^(\d+) of (\d+) calls under this prefix$/, session: [2] },
  { where: 'scope short', label: 'under the prefix', re: /^(\d+)\/(\d+) under prefix$/, session: [2] },
  { where: 'overview', label: 'the session', re: /^(\d+) calls in this session\b/, session: [1] },
  { where: 'overview', label: 'the stretch', re: /^(\d+) calls in this stretch\b/, session: [] },
  { where: 'glance', label: 'the session', re: /^calls in session (\d+)$/, session: [1] },
  { where: 'glance', label: 'the stretch', re: /^calls in stretch (\d+)$/, session: [] },
  { where: 'tab', label: 'the session', re: /^traffic (\d+)$/, session: [1] },
];

/**
 * `counts agree`: reads the traffic tab, the header, the scope bar's two forms, the overview line's
 * hint, the glance and, where drawn, the list note; each must match a label its place may carry, and
 * every number a label calls the session's must be the route's `total`.
 */
function countsAgree(doc, total, label) {
  const text = (node) => (node && !node.hidden ? node.textContent.replace(/\s+/g, ' ').trim() : null);
  const tab = doc.querySelector('#tabs .tab[data-view="network"]');
  const glance = [...doc.querySelectorAll('#detail-empty.glance .glance-row')].find((r) => /^calls in /.test(r.querySelector('.glance-label')?.textContent || ''));
  const read = [
    ['tab', tab && `${tab.querySelector('.tab-label')?.textContent} ${tab.querySelector('.tab-count')?.textContent}`],
    ['header', text(doc.getElementById('counts'))],
    ['scope', text(doc.querySelector('#scope-bar:not([hidden]) .scope-long'))],
    ['scope short', text(doc.querySelector('#scope-bar:not([hidden]) .scope-short'))],
    ['overview', text(doc.querySelector('#axis-hint > span'))],
    ['glance', glance && `${glance.querySelector('.glance-label').textContent} ${glance.querySelector('.glance-value').textContent}`],
    ['list note', text(doc.getElementById('list-note'))],
  ].filter(([, value]) => value);
  const unlabelled = [];
  const wrong = [];
  for (const [where, value] of read) {
    const fit = COUNT_LABELS.filter((l) => l.where === where).map((l) => [l, l.re.exec(value)]).find(([, m]) => m);
    if (!fit) { unlabelled.push(`${where} "${value}"`); continue; }
    for (const group of fit[0].session) if (Number(fit[1][group]) !== total) wrong.push(`${where} "${value}" says ${fit[1][group]} for the session`);
  }
  const places = read.map(([where]) => where);
  const missing = ['tab', 'header', 'overview', 'glance'].filter((where) => !places.includes(where));
  return [label, unlabelled.length || wrong.length || missing.length
    ? `NO - ${[...unlabelled.map((u) => `unlabelled: ${u}`), ...wrong, ...missing.map((m) => `no ${m} count to read`)].join('; ')}`
    : `yes (${read.map(([where, value]) => `${where} "${value}"`).join(', ')}; the session's ${total} wherever a label says session)`];
}
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
    countsAgree(doc, calls.length, exported ? 'counts agree in export' : LONG_LABELS[3]),
  ];
}

/** The capped list's lines, live only: an export draws every call it embeds and has no filter. */
const CAPPED_LABELS = ['long filtered count', 'long list note', 'long stretch older'];

/**
 * The list past its ceiling, on the page showing the long session with no filter. Under
 * `path:/v3/long/bulk`, which matches 1201, the header numbers the matches and the list draws the
 * note the other panes draw; with no filter, a stretch over the oldest 151 calls, all below the
 * newest 1000, says they are older than what the list loaded rather than that there are none. Under
 * the filter a stretch keeps the matches in the header, and the oldest stretch claims no count of
 * matches, which the page cannot know. Leaves the page unfiltered and unbrushed.
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
  const byMono = calls.slice().sort((a, b) => a.mono - b.mono);
  const empty = doc.getElementById('list-empty');
  const emptyText = () => (empty.hidden ? '(hidden)' : empty.textContent);
  // Still filtered: a stretch over the whole session keeps the matches in the header, and the
  // oldest 151 calls claim no count, since the page cannot tell which of them match.
  window.setTimeRange({ from: byMono[0].mono, to: byMono[byMono.length - 1].mono });
  await ctx.settle();
  const wholeStretch = counts();
  window.setTimeRange({ from: byMono[0].mono, to: byMono[150].mono });
  await ctx.settle();
  const filteredStretch = emptyText();
  window.setTimeRange(null);
  await typeFilter(ctx, '');
  window.setTimeRange({ from: byMono[0].mono, to: byMono[150].mono });
  await ctx.settle();
  const stretchText = emptyText();
  window.setTimeRange(null);
  if (shown && shown !== 'network') tab(shown)?.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  await ctx.settle();
  const countDue = `newest 1000 of ${bulk} matches shown`;
  const noteDue = `newest 1000 of ${bulk} matches loaded`;
  const wholeDue = `1000 of the newest 1000 of ${bulk} matches shown`;
  const stretchDue = '151 calls in this stretch are older than the newest 1000 the list loaded — filter to reach them';
  const filteredDue = `none of the newest 1000 of ${bulk} matches the list loaded is in this stretch, ` +
    `and the ${bulk - 1000} older ones are not loaded — narrow the filter to reach any here`;
  return [
    [CAPPED_LABELS[0], filtered === countDue && wholeStretch === wholeDue
      ? `yes ("${filtered}", and "${wholeStretch}" over the whole session; ${bulk} matched by the route, ${rows} rows drawn)`
      : `NO - "${filtered}" and "${wholeStretch}" over the whole session, where "${countDue}" and "${wholeDue}" were due`],
    [CAPPED_LABELS[1], !noteHidden && noteText.includes(noteDue)
      ? `yes ("${noteText.slice(0, noteText.indexOf(noteDue) + noteDue.length)}")`
      : `NO - ${note ? `"${noteText}"${noteHidden ? ' (hidden)' : ''}` : 'no #list-note'}, where "… ${noteDue}" was due`],
    [CAPPED_LABELS[2], stretchText === stretchDue && filteredStretch === filteredDue
      ? `yes ("${stretchText.slice(0, 60)}…", and no count claimed under the filter)`
      : `NO - "${stretchText}" and, filtered, "${filteredStretch}", where "${stretchDue}" and "${filteredDue}" were due`],
  ];
}

/**
 * `#/txn/<id>` for the oldest call, which the list's newest 1000 do not hold: the page reads it from
 * the single-call route and shows it. The whole-session copy is emptied for the read and put back,
 * standing in for an address opened before that copy has landed — on a loaded page the copy holds
 * every call and the route is never needed. The page's `fetch` is watched for the request. Back then
 * returns to the list's address.
 */
async function probeTxnPastCeiling(ctx, calls) {
  const { window, doc } = ctx;
  const oldest = calls.slice().sort((a, b) => a.mono - b.mono)[0];
  const page = ctx.pageState();
  if (!oldest || page.transactions.some((t) => t.id === oldest.id)) return [['txn route past ceiling', `n/a (the list holds the oldest call)`]];
  const listHash = window.location.hash;
  const asked = [];
  const fetch = window.fetch;
  window.fetch = (path, init) => { asked.push(String(path)); return fetch(path, init); };
  const whole = page.allTransactions;
  page.allTransactions = [];
  window.location.hash = `#/txn/${encodeURIComponent(oldest.id)}?session=${encodeURIComponent(LONG_SESSION_ID)}`;
  const shown = () => doc.querySelector('#detail .detail-head .path')?.textContent;
  const drawn = await waitFor(() => doc.getElementById('panes').classList.contains('txn-full') && shown() === oldest.path, 5000);
  page.allTransactions = whole;
  window.fetch = fetch;
  const read = asked.some((p) => p.endsWith(`/transactions/${encodeURIComponent(oldest.id)}`));
  window.history.back();
  const back = await waitFor(() => !doc.getElementById('panes').classList.contains('txn-full') && window.location.hash === listHash, 5000);
  return [['txn route past ceiling', drawn && read && back
    ? `yes (${oldest.id}, older than the newest 1000, read from its route and shown; Back returned to ${listHash || 'the list'})`
    : `NO - drawn ${drawn} ("${shown()}", "${oldest.path}" due), single-call route read ${read}, Back returned ${back} (${window.location.hash})`]];
}

/** The lines read while the whole-session read is held, and once it lands (KIN-42). */
const FIRST_DRAW_LABELS = ['long first draw', 'long copy lands'];

/**
 * Picks the long session with its whole-session read's `offset=1000` page held: with no filter the
 * first page is the newest 1000 and the copy needs two pages. While it is held the list, the header
 * and the glance are drawn from the first page, the endpoints, which read the copy, are not, and the
 * glance says the session's calls are being read. Released, the copy lands and `drawWhole` draws
 * the endpoints and the glance's count again. The page's `fetch` is wrapped for the hold, as
 * `probeTxnPastCeiling` watches it, and put back whatever happens.
 */
async function probeFirstDraw(ctx, calls) {
  const { window, doc } = ctx;
  const fetch = window.fetch;
  let release;
  const held = new Promise((r) => { release = r; });
  let asked = false;
  const whole = `/sessions/${encodeURIComponent(LONG_SESSION_ID)}/transactions?`;
  window.fetch = (path, init) => {
    if (asked || !String(path).includes(whole) || !/[?&]offset=1000(&|$)/.test(String(path))) return fetch(path, init);
    asked = true;
    return held.then(() => fetch(path, init));
  };
  const rows = () => doc.querySelectorAll('#list .row').length;
  const glance = () => doc.getElementById('detail-empty').textContent.replace(/\s+/g, ' ').trim();
  const endpoints = doc.getElementById('endpoints-button');
  try {
    choose(ctx, LONG_SESSION_ID);
    await waitFor(() => asked && rows() === 1000 && ctx.pageState().signalSessionId === LONG_SESSION_ID, 5000);
    const counts = doc.getElementById('counts').textContent;
    const due = { counts: `newest 1000 of ${calls.length} shown`, glance: window.beingRead(calls.length) };
    const first = !asked ? 'NO - the read never asked offset=1000, so this checked nothing'
      : !rows() ? 'NO - nothing drawn while offset=1000 was held'
        : rows() === 1000 && counts === due.counts && endpoints.hidden && glance() === due.glance
          ? `yes (1000 rows, "${counts}", the endpoints hidden and the glance "${glance()}" while offset=1000 was held)`
          : `NO - ${rows()} rows, "${counts}", endpoints ${endpoints.hidden ? 'hidden' : 'shown'}, glance "${glance()}", ` +
            `where 1000, "${due.counts}", hidden and "${due.glance}" were due`;
    release();
    await waitFor(() => ctx.pageState().allSessionId === LONG_SESSION_ID, 10000);
    await ctx.settle();
    const chips = doc.querySelectorAll('#endpoint-chips .chip').length;
    const count = `calls in session ${calls.length}`;
    const row = [...doc.querySelectorAll('#detail-empty.glance .glance-row')].find((r) => r.querySelector('.glance-label')?.textContent === 'calls in session');
    const read = row ? `calls in session ${row.querySelector('.glance-value')?.textContent}` : glance();
    const lands = !asked ? 'NO - the read never asked offset=1000, so this checked nothing'
      : endpoints.hidden ? 'NO - endpoints hidden after the copy landed'
        : chips && read === count ? `yes (${endpoints.textContent.trim()} with ${chips} chips, the glance "${count}")`
          : `NO - ${chips} chips, the glance "${read.slice(0, 80)}", where "${count}" was due`;
    return [[FIRST_DRAW_LABELS[0], first], [FIRST_DRAW_LABELS[1], lands]];
  } finally {
    release();
    window.fetch = fetch;
  }
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
  const none = (why) => [...FIRST_DRAW_LABELS.map((label) => [label, why]), ...every(why), ['txn route past ceiling', why], ...CAPPED_LABELS.map((label) => [label, why])];
  if (![...picker.options].some((o) => o.value === LONG_SESSION_ID)) return none(`n/a (no session ${LONG_SESSION_ID})`);
  const calls = (await ctx.sessionTransactions(encodeURIComponent(LONG_SESSION_ID))).items || [];
  const pair = twicePair(calls);
  // Nothing selected, so the pane draws the glance the first draw is read on.
  doc.dispatchEvent(new ctx.window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  const firstDraw = await probeFirstDraw(ctx, calls);
  if (pair.length === 2) await waitFor(() => doc.querySelector(`#list .row[data-id="${pair[1].id}"]`));
  await ctx.settle();
  // Nothing selected, so the pane draws the glance, whose count `counts agree` reads; a switch keeps
  // the previous session's call drawn there.
  doc.dispatchEvent(new ctx.window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  const verdicts = [...firstDraw, ...readLongSession(doc, calls)];
  verdicts.push(...await probeTxnPastCeiling(ctx, calls));
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
