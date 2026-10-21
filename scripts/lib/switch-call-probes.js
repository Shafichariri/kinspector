/* Probes of the call side across a session switch: what each pane draws while the new session's first page is held.
 *
 * A switch used to clear the signal side alone (`now switch shows none`), and the list, the
 * timeline, the waterfall, the path, a tag browser, the endpoint chips, the tab counts, an open call
 * and the header went on drawing the session left until the whole load ended — 26 s on a large one.
 * Each line here holds the switch's own first page of calls, the first request a load makes, so
 * nothing of the new session has landed, and reads one pane:
 *
 * - `switch list`, `switch timeline`, `switch waterfall`, `switch path` and `switch browser`: no
 *   row of the session left, and the pane's empty line reads `beingRead()`, the page's one wording;
 * - `switch endpoints`: the button hidden and no chip in its popover;
 * - `switch tabs`: the same tabs as before, the timeline's included, with no count on a tab or on a
 *   `views ▾` item;
 * - `switch detail`: the call the session left had open is closed, and the glance reads `beingRead()`;
 * - `switch counts`: the header empty, the markers button bare, and its popover reading `beingRead()`.
 *
 * Each reads its pane on the session left first, and fails with `found no …` when that drew nothing
 * there to leak (`scan-verdicts.js`); `n/a` only where the daemon's own rows say the shape cannot
 * exist. Called by `probeCurrentAfterSwitch` with the pair it chose, so the page is on `from` before
 * and after, in the view it was in. Returns `[label, verdict]` pairs, one per line the caller prints.
 */

const { choose, waitFor } = require('./long-session-probes');
const { emptyScan } = require('./scan-verdicts');

const SWITCH_LABELS = [
  'switch list', 'switch timeline', 'switch waterfall', 'switch path', 'switch browser',
  'switch endpoints', 'switch tabs', 'switch detail', 'switch counts',
];

const textOf = (node) => (node ? node.textContent.replace(/\s+/g, ' ').trim() : '');
const shown = (node) => Boolean(node) && !node.hidden;
const all = (doc, selector) => [...doc.querySelectorAll(selector)];

/** What the probe reads of every pane, the same before the switch and while its first page is held. */
function sample(doc, fromIds) {
  const ofFrom = (selector) => all(doc, selector).filter((n) => fromIds.has(n.dataset.id)).length;
  const button = doc.getElementById('endpoints-button');
  return {
    listRows: ofFrom('#list .row'),
    listEmpty: shown(doc.getElementById('list-empty')) ? textOf(doc.getElementById('list-empty')) : null,
    timelineCalls: ofFrom('#timeline .row[data-kind="txn"]'),
    timelineEmpty: shown(doc.getElementById('timeline-empty')) ? textOf(doc.getElementById('timeline-empty')) : null,
    wfRows: all(doc, '#waterfall .wf-row').length,
    wfEmpty: shown(doc.getElementById('waterfall-empty')) ? textOf(doc.getElementById('waterfall-empty')) : null,
    stops: all(doc, '#path .pv-stop').length,
    pathEmpty: doc.querySelector('#path .pv-empty') ? textOf(doc.querySelector('#path .pv-empty')) : null,
    keys: all(doc, '#browser-list li').length,
    browserEmpty: shown(doc.getElementById('browser-list-empty')) ? textOf(doc.getElementById('browser-list-empty')) : null,
    endpoints: shown(button) ? textOf(button) : null,
    chips: all(doc, '#endpoint-chips .chip').map((c) => textOf(c.firstChild)),
    tabs: all(doc, '#tabs .tab').map((t) => t.dataset.view),
    tabCounts: all(doc, '#tabs .tab-count').length,
    menuCounts: all(doc, '#tabs-more .menu-item').map(textOf).filter((t) => /\d/.test(t)),
    detail: shown(doc.getElementById('detail')),
    glance: shown(doc.getElementById('detail-empty')) ? textOf(doc.getElementById('detail-empty')) : null,
    counts: textOf(doc.getElementById('counts')),
    markersButton: textOf(doc.getElementById('markers-button')),
    markers: all(doc, '#markers li').map(textOf),
  };
}

/**
 * Switches to [to] with its first page of calls held, samples the page, then lets the load land and
 * comes back to [from]. The sample is null when the switch never asked for that page.
 */
async function heldSwitch(ctx, { from, to, realFetch }, fromIds) {
  const { window, doc, settle } = ctx;
  const firstPage = `/api/sessions/${encodeURIComponent(to)}/transactions?`;
  const held = [];
  window.fetch = (url, init) => {
    const asked = String(url);
    if (!asked.includes(firstPage) || asked.includes('offset=')) return realFetch(url, init);
    return new Promise((r) => held.push(r)).then(() => realFetch(url, init));
  };
  let during = null;
  try {
    choose(ctx, to);
    if (await waitFor(() => held.length > 0, 5000)) {
      await settle();
      during = sample(doc, fromIds);
    }
  } finally {
    window.fetch = realFetch;
    for (const r of held) r();
  }
  await waitFor(() => ctx.pageState().signalSessionId === to);
  await settle();
  choose(ctx, from);
  await waitFor(() => ctx.pageState().signalSessionId === from);
  await settle();
  return during;
}

/** The pane's empty line against the page's wording: null when it reads `beingRead()`, else why not. */
function readsBeingRead(text, node, what) {
  if (!/being read/.test(text)) return `NO - beingRead() reads "${text}", which does not say the session is being read`;
  if (node === null) return `NO - ${what} is not shown`;
  return node === text ? null : `NO - ${what} reads "${node}"`;
}

async function probeCallsAfterSwitch(ctx, pair) {
  const { window, doc, settle } = ctx;
  const { from, to } = pair;
  const viewBefore = ctx.pageState().view;
  const enc = encodeURIComponent(from);
  const calls = (await ctx.sessionTransactions(enc)).items || [];
  const signals = await ctx.sessionSignals(enc);
  const marked = await fetch(`${ctx.ORIGIN}/api/sessions/${enc}/markers`).then((r) => r.json()).catch(() => []);
  const fromIds = new Set(calls.map((t) => t.id));
  const text = window.beingRead();
  const lost = `the switch to ${to} never asked for its first page, so this checked nothing`;
  const verdicts = new Map();
  const say = (label, verdict) => verdicts.set(label, verdict);

  /** Shows [view] on `from`, reads it, switches with the first page held, reads it again. */
  const run = async (view, prepare = async () => {}) => {
    await window.selectView(view);
    await prepare();
    await settle();
    const before = sample(doc, fromIds);
    const during = await heldSwitch(ctx, pair, fromIds);
    return { before, during };
  };

  try {
    // The list, the chips, the tabs, an open call and the header: one switch from the traffic view.
    const first = calls.slice().sort((a, b) => b.mono - a.mono)[0];
    const net = await run('network', async () => { if (first) window.select(first.id); });
    const { before: b, during: d } = net;
    const none = calls.length ? null : `${from} holds no calls`;
    say(SWITCH_LABELS[0], emptyScan(b.listRows, `row of ${from} in the list`, none) ?? (!d ? `NO - ${lost}`
      : d.listRows ? `NO - the list draws ${d.listRows} of ${from}'s rows`
        : readsBeingRead(text, d.listEmpty, '#list-empty')
          ?? `yes (with ${to}'s first page held: none of ${from}'s ${b.listRows} rows, #list-empty reads "${d.listEmpty}")`));
    say(SWITCH_LABELS[5], emptyScan(b.endpoints === null ? 0 : b.chips.length, `endpoint chip of ${from}`, none)
      ?? (!d ? `NO - ${lost}` : d.endpoints !== null || d.chips.length
        ? `NO - ${d.endpoints ?? 'endpoints ▾ (hidden)'} with ${d.chips.join(', ') || 'no chip'}`
        : `yes (the button hidden and no chip, where ${from} drew "${b.endpoints}" with ${b.chips.length})`));
    const lostTabs = b.tabs.filter((v) => !d?.tabs.includes(v));
    const addedTabs = (d?.tabs || []).filter((v) => !b.tabs.includes(v));
    say(SWITCH_LABELS[6], emptyScan(b.tabCounts, `tab count on ${from}'s bar`) ?? (!d ? `NO - ${lost}`
      : lostTabs.length || addedTabs.length
        ? `NO - the bar ${lostTabs.length ? `lost ${lostTabs.join(', ')}` : ''}${lostTabs.length && addedTabs.length ? ' and ' : ''}${addedTabs.length ? `gained ${addedTabs.join(', ')}` : ''}, where ${b.tabs.join(', ')} were drawn`
        : d.tabCounts || d.menuCounts.length
          ? `NO - ${d.tabCounts} tab counts and ${d.menuCounts.length} counted views ▾ items (${d.menuCounts.join(', ')})`
          : `yes (${d.tabs.join(', ')} kept with no count, where ${from}'s bar had ${b.tabCounts})`));
    const opened = first ? `${first.method} ${first.path}` : '';
    say(SWITCH_LABELS[7], emptyScan(b.detail ? 1 : 0, `call of ${from} open in the detail`, none) ?? (!d ? `NO - ${lost}`
      : d.detail ? `NO - the detail shows ${from}'s ${opened}`
        : readsBeingRead(text, d.glance, 'the glance') ?? `yes (${from}'s ${opened} closed, the glance reads "${d.glance}")`));
    const counted = b.counts ? 1 : 0;
    const markerAbsent = marked.length ? null : `${from} holds no markers`;
    say(SWITCH_LABELS[8], emptyScan(counted, `count in ${from}'s header`, none)
      ?? emptyScan(b.markersButton === 'markers' ? 0 : 1, `marker on ${from}'s button`, markerAbsent)
      ?? (!d ? `NO - ${lost}` : d.counts ? `NO - #counts reads "${d.counts}"`
        : d.markersButton !== 'markers' ? `NO - ${d.markersButton}`
          : readsBeingRead(text, d.markers.length === 1 ? d.markers[0] : d.markers.join(' | '), '#markers')
            ?? `yes (#counts empty, "markers", its popover reads "${d.markers[0]}"; ${from} had "${b.counts}" and "${b.markersButton}")`));

    // The four views, one switch each.
    const tl = await run('all');
    say(SWITCH_LABELS[1], emptyScan(tl.before.timelineCalls, `call of ${from} on the timeline`, none) ?? (!tl.during ? `NO - ${lost}`
      : tl.during.timelineCalls ? `NO - the timeline draws ${tl.during.timelineCalls} of ${from}'s calls`
        : readsBeingRead(text, tl.during.timelineEmpty, '#timeline-empty')
          ?? `yes (none of ${from}'s ${tl.before.timelineCalls} calls, #timeline-empty reads "${tl.during.timelineEmpty}")`));

    const hasScreens = signals.some((s) => s.tag === 'screen');
    if (calls.length && hasScreens) {
      const wf = await run('waterfall');
      say(SWITCH_LABELS[2], emptyScan(wf.before.wfRows, `waterfall row of ${from}`) ?? (!wf.during ? `NO - ${lost}`
        : wf.during.wfRows ? `NO - the waterfall draws ${wf.during.wfRows} rows`
          : readsBeingRead(text, wf.during.wfEmpty, '#waterfall-empty')
            ?? `yes (none of ${from}'s ${wf.before.wfRows} rows, #waterfall-empty reads "${wf.during.wfEmpty}")`));
    } else {
      say(SWITCH_LABELS[2], `n/a (${from} has ${calls.length ? 'no screens' : 'no calls'}, so no waterfall)`);
    }

    const path = await run('path');
    say(SWITCH_LABELS[3], emptyScan(path.before.stops, `path station of ${from}`, none) ?? (!path.during ? `NO - ${lost}`
      : path.during.stops ? `NO - the path draws ${path.during.stops} stations`
        : readsBeingRead(text, path.during.pathEmpty, '.pv-empty')
          ?? `yes (none of ${from}'s ${path.before.stops} stations, .pv-empty reads "${path.during.pathEmpty}")`));

    const tag = ['state', 'cache'].find((t) => signals.some((s) => s.tag === t));
    if (tag) {
      const br = await run(tag);
      say(SWITCH_LABELS[4], emptyScan(br.before.keys, `${tag} key of ${from}`) ?? (!br.during ? `NO - ${lost}`
        : br.during.keys ? `NO - the ${tag} browser lists ${br.during.keys} keys`
          : readsBeingRead(text, br.during.browserEmpty, '#browser-list-empty')
            ?? `yes (none of ${from}'s ${br.before.keys} ${tag} keys, #browser-list-empty reads "${br.during.browserEmpty}")`));
    } else {
      say(SWITCH_LABELS[4], `n/a (${from} recorded no state or cache signals, so no tag browser)`);
    }
  } finally {
    window.fetch = pair.realFetch;
    if (ctx.pageState().sessionId !== from) {
      choose(ctx, from);
      await waitFor(() => ctx.pageState().signalSessionId === from);
    }
    await window.selectView(viewBefore);
    await settle();
  }
  return SWITCH_LABELS.map((label) => [label, verdicts.get(label) ?? 'NO - the probe stopped before this line']);
}

module.exports = { SWITCH_LABELS, probeCallsAfterSwitch };
