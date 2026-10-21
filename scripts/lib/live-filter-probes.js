/* Probes of what a live row does to the page, under a filter and without one, for `render-web-ui.js`.
 *
 * The page never decides whether a row matches the filter: the grammar has one parser, in
 * `:inspector-model`, and the daemon runs it. So a live row under a filter joins the whole-session
 * copies at once and waits for the daemon's filtered answer before it joins the list, the timeline
 * or a visit. These probes send rows through the page's own socket handler and check:
 *
 * - `blank filter header`: only spaces typed is no filter, as the daemon reads it. On the 1203-call
 *   session the header must say what it says with nothing typed (`newest 1000 of 1203 shown`) when
 *   the daemon answers `filter=%20%20%20` as it answers no filter.
 * - `blank filter free`, `live free no filter`: with nothing, or only spaces, typed, a live row costs
 *   no read of the list routes. `/current` and payload reads are not counted: a live signal asked
 *   for them before any of this, filter or not.
 * - `live header plain`: two live calls with no filter move the header and the traffic tab by two.
 * - `live rows list`, `live rows timeline`, `live rows path`: under a filter, a live call and a
 *   live signal it matches appear without a load, and one of each it does not match appear nowhere.
 * - `live rows whole`: the refused rows still reach the whole-session copies, so a signal tab and an
 *   endpoint chip count them.
 * - `live header filter`: once the daemon has answered, the header and the traffic tab count the
 *   matching call and both calls the session gained.
 * - `live paused no read`: a row that arrived just before pausing is not read in while paused, and
 *   resuming reads it.
 * - `live picker count`, `live glance plain`: after two live calls with no filter, the selected
 *   session's picker option and its face, and the glance's `calls` row, state the header's count, and
 *   the glance's hosts and slowest calls count the live calls. Both read only what the last load had.
 * - `live timeline plain`: with no filter and the timeline shown, a live call is drawn on it with no
 *   other action; the socket's call branch never redrew the timeline.
 * - `live glance filter`: under the filter, the glance still counts every call the session gained.
 * - `live paused still`, `live other still`: a call while paused, or for another session, moves
 *   none of the picker, the glance or the timeline; and resuming after two calls while paused brings
 *   the picker's option and face to the header's count, which only the next live call used to do.
 *
 * Rows "arrive" as `filter-probes.js`'s do: the page's `fetch` is wrapped to answer as the daemon
 * would once it had stored them, which the daemon does before it broadcasts. Which rows the filter
 * matches is the daemon's answer too: each live row is a copy of a row the daemon matched, or of one
 * it left out, under the same filter, with a fresh id and a later `mono`. Nothing is written to the
 * archive, and the page is left on its session with no filter. Returns `[label, verdict]` pairs.
 *
 * It also puts back what those rows and presses leave in the page's `state`, through
 * `ctx.pageState()`, because no control undoes either. A row arriving sets `lastCaptureTs`, which
 * turned the stopped banner's "newest row in this session" into "last capture"; and the newest
 * visit opened to see the rows stayed open, so the path probe after this one found no collapsed
 * visit and its `failures unfolded` and `expanded unfolded` passed over nothing.
 */

const { typeFilter, press } = require('./filter-probes');
const { LONG_SESSION_ID, choose, waitFor } = require('./long-session-probes');

const LIVE_LABELS = [
  'blank filter header', 'blank filter free', 'live free no filter', 'live header plain',
  'live rows list', 'live rows timeline', 'live rows path', 'live rows whole', 'live header filter',
  'live paused no read', 'live picker count', 'live glance plain', 'live timeline plain', 'live glance filter',
  'live paused still', 'live other still',
];
const pause = (ms) => new Promise((r) => setTimeout(r, ms));
/** Longer than the page waits before a re-read, so a re-read that was going to happen has. */
const QUIET_MS = 1800;
const segmentOf = (txn) => String(txn.path).split('/').pop();

/** Switches to the long session, compares the header with nothing typed and with only spaces, and switches back. */
async function blankHeader(ctx) {
  const { doc, ORIGIN } = ctx;
  const picker = doc.getElementById('session-picker');
  const before = picker.value;
  if (![...picker.options].some((o) => o.value === LONG_SESSION_ID)) return `n/a (no session ${LONG_SESSION_ID})`;
  const route = `${ORIGIN}/api/sessions/${encodeURIComponent(LONG_SESSION_ID)}/transactions`;
  const daemon = async (filter) => fetch(`${route}?${new URLSearchParams({ filter, limit: '1' })}`).then((r) => r.json());
  const [none, blank] = [await daemon(''), await daemon('   ')];
  if (none.matched !== blank.matched || none.total !== blank.total) {
    return `n/a (the daemon reads "   " as a filter: ${blank.matched} of ${blank.total}, against ${none.matched})`;
  }
  const counts = () => doc.getElementById('counts').textContent;
  // What the picker and the glance state: the selected option, the face's rest, the glance's rows.
  const pickerText = () => doc.querySelector('#session-picker option:checked')?.textContent || '';
  const faceText = () => doc.querySelector('.picker-face .picker-rest')?.textContent || '';
  const glanceRows = () => [...doc.querySelectorAll('#detail-empty.glance .glance-row')].map((row) => [
    row.querySelector('.glance-label')?.textContent, row.querySelector('.glance-value')?.textContent,
  ]);
  const glanceCalls = () => glanceRows().find(([label]) => label === 'calls')?.[1];
  const showGlance = () => {
    doc.getElementById('detail').hidden = true;
    doc.getElementById('detail-empty').hidden = false;
    window.renderSessionGlance();
  };
  const liveDrawArmed = () => ctx.pageState().liveDraw.timer !== null;
  choose(ctx, LONG_SESSION_ID);
  await waitFor(() => counts().endsWith(`${none.total} shown`) || counts().endsWith(`${none.total} calls`));
  await typeFilter(ctx, '');
  const plain = counts();
  await typeFilter(ctx, '   ');
  const spaces = counts();
  await typeFilter(ctx, '');
  choose(ctx, before);
  await waitFor(() => !counts().includes(String(none.total)));
  await ctx.settle();
  return spaces === plain
    ? `yes ("${spaces}" with only spaces typed, as with nothing, and the daemon reads both alike)`
    : `NO - "${spaces}" with only spaces typed, "${plain}" with nothing; the daemon reads both as no filter`;
}

async function probeLiveRows(ctx) {
  const verdicts = new Map(LIVE_LABELS.map((label) => [label, 'n/a (not reached)']));
  const found = { lastCaptureTs: ctx.pageState().lastCaptureTs, expandedVisits: [...ctx.pageState().expandedVisits] };
  try {
    verdicts.set('blank filter header', await blankHeader(ctx));
    return await liveRows(ctx, verdicts);
  } finally {
    // The page's own Set, refilled: `renderPath` may have replaced the one read above.
    const state = ctx.pageState();
    state.lastCaptureTs = found.lastCaptureTs;
    state.expandedVisits.clear();
    for (const key of found.expandedVisits) state.expandedVisits.add(key);
  }
}

/** The live-row lines after `blank filter header`, into `verdicts`; returns them all. */
async function liveRows(ctx, verdicts) {
  const { window, doc, ORIGIN } = ctx;
  const sessionId = doc.getElementById('session-picker').value;
  const id = encodeURIComponent(sessionId);
  const calls = (await ctx.sessionTransactions(id)).items || [];
  const signals = await ctx.sessionSignals(id);
  const socket = ctx.lastSocket();
  const chips = new Set([...doc.querySelectorAll('#endpoint-chips .chip')].map((c) => c.firstChild.textContent));
  const tags = [...new Set(signals.map((s) => s.tag))].filter((t) => t !== 'screen');
  const failing = calls.find((t) => t.status >= 500);
  const passing = calls.find((t) => t.status < 500 && chips.has(segmentOf(t)));
  if (!socket || !failing || !passing || tags.length < 2 || calls.length >= 1000) {
    const why = 'n/a (needs a socket, a 5xx call, a call under an endpoint chip, two non-screen tags and under 1000 calls)';
    for (const label of LIVE_LABELS.slice(1)) verdicts.set(label, why);
    return [...verdicts];
  }
  // Matches 5xx calls and one tag's signals, and leaves out the other tag and every other call.
  const [kept, left] = tags;
  const filter = `status>=500 | tag:${kept}`;
  const read = async (route) => fetch(`${ORIGIN}/api/sessions/${id}/${route}?${new URLSearchParams({ filter, limit: '1000' })}`).then((r) => r.json());
  const page = await read('transactions');
  const matchedSignals = await read('signals');
  const matchedIds = new Set([...page.items, ...matchedSignals].map((r) => r.id));
  const keptSignal = signals.find((s) => s.tag === kept && matchedIds.has(s.id));
  const leftSignal = signals.find((s) => s.tag === left && !matchedIds.has(s.id));
  if (!matchedIds.has(failing.id) || matchedIds.has(passing.id) || !keptSignal || !leftSignal) {
    const why = `n/a (the daemon does not split this session's rows as "${filter}" should)`;
    for (const label of LIVE_LABELS.slice(1)) verdicts.set(label, why);
    return [...verdicts];
  }
  const top = Math.max(...calls.map((t) => t.mono), ...signals.map((s) => s.mono));
  let n = 0;
  const callLike = (template) => ({ ...template, id: `liveprobe-c${++n}`, mono: top + n, seq: undefined });
  const signalLike = (template) => ({ ...template, id: `liveprobe-s${++n}`, name: `live probe ${n}`, mono: top + n, dataRef: undefined, seq: undefined });

  // What the daemon holds beyond the archive: every row sent, and which of them the filter matches.
  const stored = { calls: [], signals: [] };
  const matches = new Set();
  const asked = [];
  const realFetch = window.fetch;
  window.fetch = async (path, init) => {
    const url = new URL(String(path), ORIGIN);
    asked.push(url.pathname);
    const response = await realFetch(path, init);
    if ((init?.method || 'GET') !== 'GET' || (url.searchParams.get('offset') || '0') !== '0') return response;
    // The probe plays the daemon here, which reads a blank filter as none.
    const filtered = (url.searchParams.get('filter') || '').trim() !== '';
    const newest = (rows) => rows.filter((r) => !filtered || matches.has(r.id)).reverse();
    if (stored.calls.length && /\/transactions$/.test(url.pathname)) {
      const answer = await response.json();
      const added = newest(stored.calls);
      return new Response(JSON.stringify({
        ...answer,
        items: [...added, ...answer.items],
        total: answer.total + stored.calls.length,
        matched: answer.matched + added.length,
      }));
    }
    if (stored.signals.length && /\/signals$/.test(url.pathname)) {
      return new Response(JSON.stringify([...newest(stored.signals), ...(await response.json())]));
    }
    return response;
  };
  const listReads = () => asked.filter((p) => /\/(transactions|signals)$/.test(p));
  const send = (kind, row, { match = false } = {}) => {
    if (kind === 'txn') stored.calls.push(row); else stored.signals.push(row);
    if (match) matches.add(row.id);
    socket.onmessage({ data: JSON.stringify({ type: kind, sessionId, [kind]: row }) });
  };
  const counts = () => doc.getElementById('counts').textContent;
  // What the picker and the glance state: the selected option, the face's rest, the glance's rows.
  const pickerText = () => doc.querySelector('#session-picker option:checked')?.textContent || '';
  const faceText = () => doc.querySelector('.picker-face .picker-rest')?.textContent || '';
  const glanceRows = () => [...doc.querySelectorAll('#detail-empty.glance .glance-row')].map((row) => [
    row.querySelector('.glance-label')?.textContent, row.querySelector('.glance-value')?.textContent,
  ]);
  const glanceCalls = () => glanceRows().find(([label]) => label === 'calls')?.[1];
  const showGlance = () => {
    doc.getElementById('detail').hidden = true;
    doc.getElementById('detail-empty').hidden = false;
    window.renderSessionGlance();
  };
  const liveDrawArmed = () => ctx.pageState().liveDraw.timer !== null;
  const tabCount = (view) => doc.querySelector(`#tabs .tab[data-view="${view}"] .tab-count`)?.textContent;
  const inList = (row) => Boolean(doc.querySelector(`#list .row[data-id="${row.id}"]`));
  const forget = async () => {
    stored.calls.length = 0;
    stored.signals.length = 0;
    matches.clear();
    await typeFilter(ctx, '');
  };

  try {
    // Only spaces typed: a live row is drawn as with nothing typed, and costs no read.
    await typeFilter(ctx, '   ');
    asked.length = 0;
    const spaced = callLike(passing);
    send('txn', spaced);
    await pause(QUIET_MS);
    verdicts.set('blank filter free', listReads().length === 0 && inList(spaced)
      ? 'yes (a live call with only spaces typed was drawn, and no list route was read)'
      : `NO - ${listReads().length} list reads, call drawn: ${inList(spaced)}; only spaces is no filter`);
    await forget();

    // Nothing typed: no read, and the header and the traffic tab count both calls.
    await ctx.settle();
    const total = calls.length;
    showGlance();
    asked.length = 0;
    // The slowest call the session has ever made, so the glance's slowest list must lead with it.
    const slow = { ...callLike(passing), ms: 987654, path: `${passing.path}/live-slow` };
    send('txn', slow);
    send('signal', signalLike(leftSignal));
    send('txn', callLike(failing));
    await pause(QUIET_MS);
    const plainCount = `${total + 2} calls`;
    verdicts.set('live picker count', pickerText().endsWith(plainCount) && faceText().endsWith(plainCount)
      ? `yes (option "${pickerText()}", face "${faceText()}", after two live calls)`
      : `NO - option "${pickerText()}", face "${faceText()}", where both were due to end "${plainCount}"`);
    {
      const rows = glanceRows();
      const hostsDue = new Map();
      for (const t of [...calls, slow, failing]) hostsDue.set(t.host, (hostsDue.get(t.host) || 0) + 1);
      const hostRows = rows.filter(([label]) => label === 'host');
      const wrongHosts = hostRows.filter(([, value]) => {
        const [host, n] = String(value).split(' · ');
        return Number(n) !== hostsDue.get(host);
      });
      // The first row after the `Slowest` title.
      const title = [...doc.querySelectorAll('#detail-empty .glance-title')].find((t) => t.textContent === 'Slowest');
      const slowFirst = title?.nextElementSibling?.querySelector('.glance-value')?.textContent;
      const slowDue = `${slow.method} ${slow.path}`;
      verdicts.set('live glance plain', glanceCalls() === String(total + 2) && hostRows.length && !wrongHosts.length && slowFirst === slowDue
        ? `yes (calls ${glanceCalls()}, hosts ${hostRows.map(([, v]) => v).join(', ')}, slowest "${slowFirst}", after two live calls)`
        : `NO - calls ${glanceCalls()} (${total + 2} due), hosts ${hostRows.map(([, v]) => v).join(', ') || 'none'}${wrongHosts.length ? ' miscounted' : ''}, slowest "${slowFirst}" ("${slowDue}" due)`);
    }
    verdicts.set('live free no filter', listReads().length === 0
      ? 'yes (two live calls and a signal with no filter read no list route)'
      : `NO - ${listReads().length} list reads (${listReads().join(', ')}) with no filter set`);
    const due = `${total + 2} of ${total + 2} calls`;
    verdicts.set('live header plain', counts() === due && tabCount('network') === String(total + 2)
      ? `yes ("${due}", traffic ${total + 2}, after two live calls)`
      : `NO - "${counts()}", traffic ${tabCount('network')}, where "${due}" and ${total + 2} were due after two live calls`);
    await forget();

    // The timeline shown and nothing typed: a live call is drawn with no other action.
    await press(ctx, doc.querySelector('#tabs .tab[data-view="all"]'));
    await ctx.settle();
    {
      const drawnCall = callLike(passing);
      const onTimeline = () => Boolean(doc.querySelector(`#timeline [data-id="${drawnCall.id}"]`));
      const sent = Date.now();
      send('txn', drawnCall);
      const drew = await waitFor(onTimeline, 3000);
      verdicts.set('live timeline plain', drew
        ? `yes (${drawnCall.id} on the timeline ${Date.now() - sent} ms after it arrived, with no other action)`
        : `NO - ${drawnCall.id} not on the timeline 3 s after it arrived with no filter set`);
    }
    await press(ctx, doc.querySelector('#tabs .tab[data-view="network"]'));
    await forget();
    showGlance();

    // Under the filter, per view: one call and one signal it matches, one of each it does not, sent
    // with the view open. Each is judged at arrival, before any re-read can land, so a row pushed
    // and later replaced by the daemon's answer still fails; and again once the daemon has answered.
    await typeFilter(ctx, filter);
    const chipOf = (segment) => Number(
      [...doc.querySelectorAll('#endpoint-chips .chip')].find((c) => c.firstChild.textContent === segment)
        ?.querySelector('.chip-count')?.textContent ?? NaN,
    );
    const VIEWS = [['network', 'list', 'live rows list'], ['all', 'timeline', 'live rows timeline'], ['path', 'path', 'live rows path']];
    for (const [view, rootId, label] of VIEWS) {
      await press(ctx, doc.querySelector(`#tabs .tab[data-view="${view}"]`));
      await ctx.settle();
      if (view === 'path') {
        // The live rows land in the newest visit; opened, and unfolded past its fold, it draws every item.
        const visit = [...doc.querySelectorAll('#path .pv-visit')].pop();
        if (!visit) { verdicts.set(label, 'NO - the path drew no visit'); continue; }
        if (!visit.classList.contains('pv-open')) await press(ctx, visit.querySelector('.pv-twisty'));
        const more = [...doc.querySelectorAll('#path .pv-visit')].pop()?.querySelector('.pv-more');
        if (more) await press(ctx, more);
      }
      const root = doc.getElementById(rootId);
      const has = (row) => Boolean(root.querySelector(`[data-id="${row.id}"]`));
      const before = { chip: chipOf(segmentOf(passing)), left: Number(tabCount(left)), kept: Number(tabCount(kept)) };
      asked.length = 0;
      const out = { call: callLike(passing), signal: signalLike(leftSignal) };
      const inn = { call: callLike(failing), signal: signalLike(keptSignal) };
      send('txn', out.call);
      send('txn', inn.call, { match: true });
      send('signal', out.signal);
      send('signal', inn.signal, { match: true });
      // The list draws calls only.
      const due = view === 'network' ? [[inn.call, true], [out.call, false]]
        : [[inn.call, true], [inn.signal, true], [out.call, false], [out.signal, false]];
      const atArrival = due.filter(([row, wanted]) => !wanted && has(row)).map(([row]) => `${row.id} drawn at arrival`);
      const landed = await waitFor(() => due.every(([row, wanted]) => !wanted || has(row)), 6000);
      await ctx.settle();
      const wrong = [...atArrival, ...due.filter(([row, wanted]) => has(row) !== wanted)
        .map(([row, wanted]) => `${row.id} ${wanted ? 'missing' : 'drawn'} once answered`)];
      const callReads = asked.filter((p) => /\/transactions$/.test(p)).length;
      const loaded = asked.some((p) => /\/markers$/.test(p));
      if (loaded) wrong.push('a load ran');
      if (callReads > 2) wrong.push(`${callReads} call reads for one burst`);
      verdicts.set(label, landed && !wrong.length
        ? `yes (the matching call${view === 'network' ? '' : ' and signal'} drawn after ${callReads} filtered call read, the refused rows never, no load)`
        : `NO - ${wrong.join(', ') || 'the matching rows never drawn'}`);
      if (view !== 'network') continue;
      const dueFiltered = `${page.matched + 1} of ${total + 2} calls`;
      verdicts.set('live header filter', counts() === dueFiltered && tabCount('network') === String(page.matched + 1)
        ? `yes ("${dueFiltered}", traffic ${page.matched + 1}, once the daemon answered)`
        : `NO - "${counts()}", traffic ${tabCount('network')}, where "${dueFiltered}" and ${page.matched + 1} were due`);
      // The glance ignores the filter, so it counts both calls, the refused one too.
      await waitFor(() => !liveDrawArmed(), 2000);
      verdicts.set('live glance filter', glanceCalls() === String(total + 2)
        ? `yes (calls ${glanceCalls()} under "${filter}", as the header's session count)`
        : `NO - the glance reads calls ${glanceCalls()} under "${filter}", where the session holds ${total + 2}`);
      const after = { chip: chipOf(segmentOf(passing)), left: Number(tabCount(left)), kept: Number(tabCount(kept)) };
      // The matching call may share the refused one's endpoint, and then the chip counts both.
      const chipDue = before.chip + [out.call, inn.call].filter((t) => segmentOf(t) === segmentOf(passing)).length;
      verdicts.set('live rows whole', after.chip === chipDue && after.left === before.left + 1 && after.kept === before.kept + 1
        ? `yes (/${segmentOf(passing)} counts ${after.chip}, ${left} ${after.left}, ${kept} ${after.kept}: the refused rows are in the session)`
        : `NO - /${segmentOf(passing)} ${before.chip} -> ${after.chip} (${chipDue} due), ${left} ${before.left} -> ${after.left}, ${kept} ${before.kept} -> ${after.kept}; each tab was due one more`);
    }
    await press(ctx, doc.querySelector('#tabs .tab[data-view="network"]'));
    await ctx.settle();

    // A matching call, then pause at once: nothing is read while paused, and resuming reads it.
    const live = doc.getElementById('live-btn');
    asked.length = 0;
    const late = callLike(failing);
    send('txn', late, { match: true });
    await press(ctx, live);
    await pause(QUIET_MS);
    const pausedReads = listReads().length;
    const pausedDrawn = inList(late);
    await press(ctx, live);
    const resumed = await waitFor(() => inList(late), 6000);
    await ctx.settle();
    verdicts.set('live paused no read', pausedReads === 0 && !pausedDrawn && resumed
      ? 'yes (no read while paused, and the call drawn once resumed)'
      : `NO - ${pausedReads} reads while paused, drawn while paused: ${pausedDrawn}, drawn after resuming: ${resumed}`);

    // Paused, and then for another session: a call moves none of the picker, the glance or the timeline.
    await forget();
    showGlance();
    const still = async (label, deliver, why) => {
      await waitFor(() => !liveDrawArmed(), 2000);
      const before = { picker: pickerText(), face: faceText(), glance: glanceCalls() };
      deliver();
      const armed = liveDrawArmed();
      await pause(600);
      const after = { picker: pickerText(), face: faceText(), glance: glanceCalls() };
      verdicts.set(label, !armed && before.picker === after.picker && before.face === after.face && before.glance === after.glance
        ? `yes (${why}: option, face and glance calls ${after.glance} unchanged, no timeline redraw armed)`
        : `NO - ${why}: option "${before.picker}" -> "${after.picker}", face "${before.face}" -> "${after.face}", glance ${before.glance} -> ${after.glance}, timeline redraw armed: ${armed}`);
    };
    await press(ctx, live);
    await still('live paused still', () => send('txn', callLike(passing)), 'a call while paused');
    // A second call while paused, then resume: the resume's load moves the header, and the picker's
    // option and face must follow it there, not wait for the next live call.
    send('txn', callLike(passing));
    const pausedTotal = ctx.pageState().sessionTotal;
    await press(ctx, live);
    const resumedDue = () => `${ctx.pageState().sessionTotal} calls`;
    const followed = await waitFor(() => ctx.pageState().sessionTotal > pausedTotal
      && pickerText().endsWith(resumedDue()) && faceText().endsWith(resumedDue()), 4000);
    const stillVerdict = verdicts.get('live paused still');
    verdicts.set('live paused still', !stillVerdict.startsWith('yes') ? stillVerdict : followed
      ? `${stillVerdict.slice(0, -1)}; resumed, option and face read ${resumedDue()}, the header's count)`
      : `NO - resumed after two calls while paused: header total ${ctx.pageState().sessionTotal} (was ${pausedTotal}), option "${pickerText()}", face "${faceText()}", where both were due to end "${resumedDue()}"`);
    await forget();
    showGlance();
    const other = [...doc.getElementById('session-picker').options].map((o) => o.value).find((v) => v && v !== sessionId);
    if (other) {
      await still('live other still', () => socket.onmessage({ data: JSON.stringify({ type: 'txn', sessionId: other, txn: callLike(passing) }) }),
        `a call for ${other}`);
    } else {
      verdicts.set('live other still', 'n/a (one session; no other to send a call for)');
    }
  } finally {
    window.fetch = realFetch;
    if (!doc.getElementById('live-btn').classList.contains('on')) await press(ctx, doc.getElementById('live-btn'));
    await forget();
    // The live calls counted into the picker's label are not in the archive; read the labels again.
    await window.loadSessions();
    await ctx.settle();
  }
  return [...verdicts];
}

module.exports = { LIVE_LABELS, probeLiveRows };
