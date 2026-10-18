/* Live probes of the web UI under a filter, for `render-web-ui.js`.
 *
 * Kept out of that file because it sits at its line budget, and these are one subject: what the
 * page says, and what it still knows about the whole session, while a filter is set. Each probe
 * takes the page through `ctx` — `window`, `doc`, `ORIGIN`, `settle`, `sessionTransactions`,
 * `sessionSignals` and `lastSocket()` — and drives it through its real controls, as every probe
 * there does. Each returns `{ key: verdict }`; the caller prints one line per key.
 */

const pause = (ms) => new Promise((r) => setTimeout(r, ms));

/** Types into the real filter box and waits for the page to settle on the answer. */
async function typeFilter(ctx, text) {
  const input = ctx.doc.getElementById('filter');
  input.value = text;
  input.dispatchEvent(new ctx.window.Event('input', { bubbles: true }));
  await pause(400);
  await ctx.settle();
  await pause(300);
}

async function press(ctx, node) {
  node.dispatchEvent(new ctx.window.MouseEvent('click', { bubbles: true }));
  await pause(100);
}

/**
 * Under a filter the page caches its whole-session copies, and only the live socket keeps them
 * current. Whatever arrived while nothing was listening must still reach the tabs and the endpoint
 * chips once the page listens again — after the list was paused and resumed, and after the daemon
 * went away and came back.
 *
 * Rows "arrive" by rewriting the daemon's unfiltered answers from the moment the gap opens: one
 * signal under a tag the session never used, so a tab appears for it, and one more call to the
 * busiest endpoint, so its chip counts one more. The filter is `tag:screen`, which matches no call
 * and no tag but `screen`, so the daemon's filtered answers are right to leave both out and only
 * the unfiltered reads are touched. Nothing is written to the archive. The stop request is stubbed,
 * as the top bar probe does, so the daemon under test keeps running. The third gap is a drop nobody
 * asked for, played through the page's own `onclose` and its own redial: the stubbed stop marks the
 * daemon away by itself, so it could never show whether a plain drop does.
 */
async function probeWholeSessionRefresh(ctx) {
  const { window, doc, ORIGIN } = ctx;
  const na = (why) => ({ resumeRefresh: `n/a (${why})`, reconnectRefresh: `n/a (${why})`, dropRefresh: `n/a (${why})` });
  const id = encodeURIComponent(doc.getElementById('session-picker').value);
  const calls = (await ctx.sessionTransactions(id)).items || [];
  const signals = await ctx.sessionSignals(id);
  const chip = doc.querySelector('#endpoint-chips .chip');
  if (!calls.length || !signals.length || !chip) return na('needs calls, signals and an endpoint chip');
  const segment = chip.firstChild.textContent;
  const template = calls.find((t) => t.path.split('/').pop() === segment);
  if (!template) return na(`no call ends in /${segment}`);
  const top = Math.max(...calls.map((t) => t.mono), ...signals.map((s) => s.mono));

  const realFetch = window.fetch;
  let arrived = null;
  window.fetch = async (path, init) => {
    const response = await realFetch(path, init);
    const url = new URL(String(path), ORIGIN);
    if (!arrived || url.searchParams.get('filter') || (init?.method || 'GET') !== 'GET') return response;
    if (/\/signals$/.test(url.pathname) && (url.searchParams.get('offset') || '0') === '0') {
      return new Response(JSON.stringify([arrived.signal, ...(await response.json())]));
    }
    if (/\/transactions$/.test(url.pathname) && (url.searchParams.get('offset') || '0') === '0') {
      const page = await response.json();
      return new Response(JSON.stringify({ ...page, items: [arrived.call, ...page.items], total: page.total + 1 }));
    }
    return response;
  };
  const arrive = (tag, n) => {
    arrived = {
      signal: { ...signals[0], id: `probe-${tag}`, tag, name: 'arrived', mono: top + n, dataRef: undefined },
      call: { ...template, id: `probe-${tag}-call`, mono: top + n, seq: undefined },
    };
  };
  const chipCount = () => Number(
    [...doc.querySelectorAll('#endpoint-chips .chip')].find((c) => c.firstChild.textContent === segment)
      ?.querySelector('.chip-count')?.textContent ?? NaN,
  );
  const seen = (tag, before) => {
    const tab = Boolean(doc.querySelector(`#tabs .tab[data-view="${tag}"]`));
    const count = chipCount();
    return tab && count === before + 1
      ? `yes (a tab for '${tag}', and /${segment} counts ${count}, under tag:screen)`
      : `NO - tab for '${tag}' ${tab}, /${segment} counts ${count} where ${before + 1} was due, under tag:screen`;
  };

  const result = {};
  try {
    await typeFilter(ctx, 'tag:screen');
    let before = chipCount();
    const live = doc.getElementById('live-btn');
    await press(ctx, live);
    arrive('probe-resumed', 1);
    await press(ctx, live);
    await ctx.settle();
    await pause(300);
    result.resumeRefresh = seen('probe-resumed', before);

    arrived = null;
    await typeFilter(ctx, '');
    await typeFilter(ctx, 'tag:screen');
    before = chipCount();
    const stop = doc.getElementById('server-stop');
    const stubbed = window.fetch;
    window.fetch = (path, init) => (String(path).includes('/api/server/stop')
      ? Promise.resolve(new Response('{}'))
      : stubbed(path, init));
    await press(ctx, stop);
    await press(ctx, stop);
    window.fetch = stubbed;
    arrive('probe-reconnected', 2);
    const socket = ctx.lastSocket();
    if (socket && socket.onopen) socket.onopen();
    await pause(300);
    await ctx.settle();
    await pause(300);
    result.reconnectRefresh = seen('probe-reconnected', before);

    // An unplanned drop: nobody pressed stop, the socket just closed — the daemon was killed, the
    // laptop slept. The page's own `onclose` runs and its own redial opens a new socket, which the
    // stub answers at once; nothing here fires `onopen` by hand.
    arrived = null;
    await typeFilter(ctx, '');
    await typeFilter(ctx, 'tag:screen');
    before = chipCount();
    const dropped = ctx.lastSocket();
    arrive('probe-dropped', 3);
    if (dropped && dropped.onclose) dropped.onclose();
    await pause(1500);
    await ctx.settle();
    await pause(300);
    result.dropRefresh = ctx.lastSocket() === dropped
      ? 'NO - the page never redialled after the drop'
      : seen('probe-dropped', before);
  } finally {
    window.fetch = realFetch;
    arrived = null;
    await typeFilter(ctx, '');
  }
  return result;
}

/** Whether `node` says what the filter took, names `filter`, and carries the list's clear control. */
function namesFilter(node, filter) {
  return Boolean(node) && [...node.querySelectorAll('code')].some((c) => c.textContent === filter)
    && [...node.querySelectorAll('button')].some((b) => b.textContent === 'clear filter');
}

/**
 * The waterfall, the path and the timeline under a filter that empties them and one that thins
 * them. Each must say the filter did it, name it, and offer the list's `clear filter` — which is
 * pressed, because the offer is only true if pressing it brings the rows back. "Nothing recorded"
 * may appear only where the whole session recorded nothing; under a filter a visit reads "0 of N
 * match", with N read off the same visit before the filter was typed. The timeline is judged empty
 * on calls and signals alone, so a marker in range, which the filter does not touch, must not hide
 * the note. The tab bar must not move throughout.
 *
 * Expected counts come from the daemon, not the page: the session's calls and signals, and how many
 * calls the thinning filter matches.
 */
async function probeFilterPanes(ctx) {
  const { doc, ORIGIN } = ctx;
  const keys = ['waterfallEmptied', 'waterfallThinned', 'pathCounts', 'pathNote', 'timelineEmptied', 'timelineThinned', 'tabsSteady'];
  const id = encodeURIComponent(doc.getElementById('session-picker').value);
  const calls = (await ctx.sessionTransactions(id)).items || [];
  const signals = await ctx.sessionSignals(id);
  const tab = (view) => doc.querySelector(`#tabs .tab[data-view="${view}"]`);
  if (!calls.length || !signals.length || !tab('waterfall') || !tab('path') || !tab('all')) {
    return Object.fromEntries(keys.map((k) => [k, 'n/a (needs calls, signals and the waterfall, path and timeline tabs)']));
  }
  const nothing = 'path:/__inspector_probe_matches_nothing__';
  const thin = `path:${calls[0].path}`;
  const matched = await fetch(`${ORIGIN}/api/sessions/${id}/transactions?${new URLSearchParams({ filter: thin, limit: '1' })}`)
    .then((r) => r.json()).then((page) => page.matched);
  const plural = (n, noun) => `${n} ${noun}${n === 1 ? '' : 's'}`;
  const tabIds = () => [...doc.querySelectorAll('#tabs .tab')].map((t) => t.dataset.view).join(',');
  const tabsBefore = tabIds();
  const tabsDuring = [];
  const open = async (view) => { await press(ctx, tab(view)); await pause(300); await ctx.settle(); };
  const filtered = async (text) => { await typeFilter(ctx, text); tabsDuring.push(tabIds()); };
  const clearIn = async (node) => {
    const button = [...node.querySelectorAll('button')].find((b) => b.textContent === 'clear filter');
    if (!button) return false;
    await press(ctx, button);
    await pause(300);
    await ctx.settle();
    await pause(300);
    return doc.getElementById('filter').value === '';
  };
  const result = {};

  // The waterfall: emptied, then thinned.
  await open('waterfall');
  await filtered(nothing);
  let empty = doc.getElementById('waterfall-empty');
  const wfEmptied = !empty.hidden && namesFilter(empty, nothing) && empty.textContent.includes(plural(calls.length, 'call'));
  const wfText = empty.textContent;
  const wfCleared = await clearIn(empty) && doc.querySelectorAll('#waterfall .wf-group').length > 0 && empty.hidden;
  result.waterfallEmptied = wfEmptied && wfCleared
    ? `yes ("${wfText}", and clearing brought the groups back)`
    : `NO - shown ${!empty.hidden}, "${wfText}", cleared ${wfCleared}`;
  await filtered(thin);
  let note = doc.querySelector('#waterfall .filter-note');
  const wfHidden = plural(calls.length - matched, 'call');
  result.waterfallThinned = namesFilter(note, thin) && note.textContent.startsWith(`${wfHidden} hidden`)
    ? `yes ("${note.textContent}")`
    : `NO - ${note ? `"${note.textContent}"` : 'no note'}, ${wfHidden} hidden was due`;
  await typeFilter(ctx, '');

  // The path: what each visit recorded, then the same visits under a filter that matches nothing.
  await open('path');
  const recorded = [...doc.querySelectorAll('#path .pv-visit')].map((row) => {
    const text = row.querySelector('.pv-stats')?.firstChild?.textContent || '';
    return [...text.matchAll(/(\d+) \S+/g)].reduce((n, m) => n + Number(m[1]), 0);
  });
  await filtered(nothing);
  const rows = [...doc.querySelectorAll('#path .pv-visit')];
  const stops = [...doc.querySelectorAll('#path .pv-stop .pv-stop-stat')];
  const wrong = rows.map((row, i) => {
    const want = recorded[i] ? `0 of ${recorded[i]} match` : 'nothing recorded';
    const stats = row.querySelector('.pv-stats')?.firstChild?.textContent;
    const stop = stops[i]?.textContent;
    return stats === want && stop === (recorded[i] ? want : 'nothing') && (!recorded[i] || !row.textContent.includes('nothing recorded'))
      ? null : `visit ${i + 1}: "${stats}" / "${stop}", "${want}" was due`;
  }).filter(Boolean);
  const emptied = recorded.filter(Boolean).length;
  result.pathCounts = rows.length === recorded.length && emptied > 0 && !wrong.length
    ? `yes (${emptied} of ${rows.length} visits read "0 of N match", N as recorded)`
    : `NO - ${rows.length} visits for ${recorded.length}; ${wrong.join('; ') || `${emptied} with anything recorded`}`;
  note = doc.querySelector('#path .filter-note');
  const pathText = note ? note.textContent : '';
  const pathHidden = plural(calls.length, 'call');
  const pathCleared = note ? await clearIn(note) && !doc.querySelector('#path .filter-note') : false;
  result.pathNote = namesFilter(note, nothing) && pathText.startsWith(`${pathHidden} and`) && pathCleared
    ? `yes ("${pathText}", and clearing took it away)`
    : `NO - ${note ? `"${pathText}"` : 'no note'}, cleared ${pathCleared}`;

  // The timeline: emptied with markers still drawn, then thinned.
  await open('all');
  await filtered(nothing);
  empty = doc.getElementById('timeline-empty');
  const markers = doc.querySelectorAll('#timeline [data-kind="marker"]').length;
  const tlText = empty.textContent;
  const tlEmptied = !empty.hidden && namesFilter(empty, nothing)
    && tlText.includes(`${plural(calls.length, 'call')} and ${plural(signals.length, 'signal')} hidden`);
  const tlCleared = await clearIn(empty) && empty.hidden && doc.querySelectorAll('#timeline [data-kind="txn"]').length > 0;
  result.timelineEmptied = tlEmptied && tlCleared
    ? `yes ("${tlText}", over ${plural(markers, 'marker')} in range, and clearing brought the rows back)`
    : `NO - shown ${!empty.hidden}, "${tlText}", ${plural(markers, 'marker')} drawn, cleared ${tlCleared}`;
  await filtered(thin);
  note = doc.querySelector('#timeline .filter-note');
  const tlHidden = `${plural(calls.length - matched, 'call')} and ${plural(signals.length, 'signal')} hidden`;
  result.timelineThinned = namesFilter(note, thin) && note.textContent.startsWith(tlHidden)
    ? `yes ("${note.textContent}")`
    : `NO - ${note ? `"${note.textContent}"` : 'no note'}, "${tlHidden}" was due`;
  await typeFilter(ctx, '');
  await open('network');

  result.tabsSteady = tabsDuring.every((ids) => ids === tabsBefore) && tabIds() === tabsBefore
    ? `yes (${tabsBefore.split(',').length} tabs under ${tabsDuring.length} filters)`
    : `NO - before ${tabsBefore}; during ${tabsDuring.join(' / ')}`;
  return result;
}

/**
 * Which of its texts an emptied waterfall picks, in the order the reader needs them: a filter that
 * hid calls says so first; then a brushed stretch, while the session has calls, says the stretch is
 * empty; and "no calls were recorded" only where the session recorded none. And emptied by a filter
 * that kept signals — `tag:screen` — it says no *calls* match, since it draws nothing else.
 *
 * The stretch is set through `setTimeRange`, the function the axis brush calls; a brush needs a
 * layout jsdom does not do. The empty stretch sits inside the widest gap between two calls, and
 * the full one spans every call, both read from the daemon.
 */
async function probeWaterfallText(ctx) {
  const { window, doc } = ctx;
  const keys = ['stretchUnderFilter', 'filterInStretch', 'callsMatch'];
  const id = encodeURIComponent(doc.getElementById('session-picker').value);
  const monos = ((await ctx.sessionTransactions(id)).items || []).map((t) => t.mono).sort((a, b) => a - b);
  const tab = doc.querySelector('#tabs .tab[data-view="waterfall"]');
  let gap = null;
  for (let i = 1; i < monos.length; i++) {
    if (monos[i] - monos[i - 1] > 2 && (!gap || monos[i] - monos[i - 1] > gap.to - gap.from)) {
      gap = { from: monos[i - 1] + 1, to: monos[i] - 1 };
    }
  }
  if (!tab || !gap || typeof window.setTimeRange !== 'function') {
    return Object.fromEntries(keys.map((k) => [k, 'n/a (needs the waterfall tab and two calls with a gap between them)']));
  }
  const nothing = 'path:/__inspector_probe_matches_nothing__';
  const empty = doc.getElementById('waterfall-empty');
  const shown = () => (empty.hidden ? '(hidden)' : `"${empty.textContent}"`);
  const result = {};
  try {
    await press(ctx, tab);
    await pause(300);
    await ctx.settle();
    await typeFilter(ctx, nothing);

    window.setTimeRange(gap);
    await pause(100);
    result.stretchUnderFilter = !empty.hidden && empty.textContent === 'no calls in the selected stretch'
      ? `yes (${shown()}, a stretch with no calls under a filter matching none)`
      : `NO - ${shown()}, where "no calls in the selected stretch" was due`;

    window.setTimeRange({ from: monos[0], to: monos[monos.length - 1] });
    await pause(100);
    result.filterInStretch = !empty.hidden && namesFilter(empty, nothing) && empty.textContent.startsWith('no calls match the filter')
      ? `yes (${shown()}, the filter hid calls inside the stretch)`
      : `NO - ${shown()}, where the filter note was due`;

    window.setTimeRange(null);
    await typeFilter(ctx, 'tag:screen');
    result.callsMatch = !empty.hidden && namesFilter(empty, 'tag:screen') && empty.textContent.startsWith('no calls match the filter')
      ? `yes (${shown()})`
      : `NO - ${shown()}, where "no calls match the filter tag:screen …" was due`;
  } finally {
    window.setTimeRange(null);
    await typeFilter(ctx, '');
    await press(ctx, doc.querySelector('#tabs .tab[data-view="network"]'));
  }
  return result;
}

module.exports = { probeWholeSessionRefresh, probeFilterPanes, probeWaterfallText };
