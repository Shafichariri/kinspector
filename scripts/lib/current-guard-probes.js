/* Probes of the now strip across a session switch: whose values it draws when `/current` answers late or fails.
 *
 * `refreshCurrent` runs on every live signal, so with traffic a read is often in flight when the
 * picker changes. Two ways the old session's values reached the new session's strip, one line each:
 *
 * - `now late reply dropped`: a reply asked for the old session lands after the switch has settled.
 *   It must be dropped, so the strip lists exactly what the new session's own `/current` says.
 * - `now failed read empty`: a read for the new session fails before the switch's own load has
 *   written `state.current`, which still holds the old session's values. The strip must be empty.
 *
 *
 * Four more lines, from KIN-57, on what the strip and the signal panes show around a switch and of
 * two reads for one session. The fixture's second session holds current values of its own, so
 * "the strip lists the new session's values" can fail apart from "the strip is empty":
 *
 * - `now switch shows none`: the new session's `/signals` read is held, so its load has not landed.
 *   The strip, the timeline's signal rows and the glance must show none of the old session's values.
 * - `now newest read decides`: two live reads for one session, released newest first. The strip
 *   must list the newer reply, which the older one landing last overwrote.
 * - `now load read overtaken`: the same, with the older read started by `loadSignals`.
 * - `now failed read kept`: a failed read on the session already shown keeps its values, each dated.
 *
 * `now switch shows none` runs a second time, as the shell's `route switch window`, with the switch
 * made through an address (`#/traffic?session=…`) instead of the picker: both reach `switchSession`,
 * and the clear must run for either (`probeSwitchWindowByRoute`).
 *
 * All call the page's `refreshCurrent` directly, which is what a live signal does, so the probe
 * holds the very read it releases instead of racing one the socket might start. Each stubs the page's
 * `fetch` for the one route it holds and puts the real one back before it returns, and the page is
 * left on the session it started on. Returns `[label, verdict]` pairs, one per line the caller prints.
 */

const { choose, waitFor } = require('./long-session-probes');
const { SWITCH_LABELS, probeCallsAfterSwitch } = require('./switch-call-probes');

const CURRENT_LABELS = [
  'now late reply dropped', 'now failed read empty',
  'now switch shows none', 'now newest read decides', 'now load read overtaken', 'now failed read kept',
];
const every = (why) => [...CURRENT_LABELS, ...SWITCH_LABELS].map((label) => [label, why]);

/** What the strip lists, as `tag name` in drawn order. */
const stripOf = (doc) =>
  [...doc.querySelectorAll('#current .current-item')].map(
    (li) => `${li.dataset.tag} ${li.querySelector('.current-name')?.textContent}`,
  );
/** The same, for rows a route answered with. */
const keysOf = (rows) => rows.map((s) => `${s.tag} ${s.name}`);
const same = (a, b) => a.join('|') === b.join('|');
/** The timeline's signal entries: single rows and collapsed runs, each drawn from signals alone. */
const timelineSignals = (doc) => doc.querySelectorAll('#timeline .tl-signal, #timeline .tl-run').length;
/** What the glance draws: `glance` with its rows, or a line naming nothing. */
const glanceOf = (doc) => {
  const pane = doc.getElementById('detail-empty');
  return { drawn: pane.classList.contains('glance'), text: pane.textContent.replace(/\s+/g, ' ').trim() };
};
/** A reply the page's `api` reads as a 200 holding [rows]: it asks only `ok` and `text()`. */
const replyOf = (rows) => ({ ok: true, status: 200, text: async () => JSON.stringify(rows) });

/**
 * The two sessions a switch goes between and what each one's strip holds, or the reason none can
 * be told apart (`skip`). Shared by the picker's lines and the shell's address run.
 */
async function switchPair(ctx) {
  const { window, doc, ORIGIN } = ctx;
  const picker = doc.getElementById('session-picker');
  const from = picker.value;
  const to = [...picker.options].map((o) => o.value).find((v) => v && v !== from);
  if (!to) return { skip: 'n/a (one session; nothing to switch to)' };
  if (typeof window.refreshCurrent !== 'function') return { skip: 'NO - refreshCurrent is not a page function' };
  const currentOf = (id) => fetch(`${ORIGIN}/api/sessions/${encodeURIComponent(id)}/current`).then((r) => r.json());
  const fromKeys = keysOf(await currentOf(from));
  const toKeys = keysOf(await currentOf(to));
  if (!fromKeys.length) return { skip: `n/a (${from} holds no current values to leak)` };
  if (same(fromKeys, toKeys)) return { skip: 'n/a (both sessions hold the same current values)' };
  const shows = (keys) => () => same(stripOf(doc), keys);
  return { picker, from, to, fromKeys, toKeys, shows, realFetch: window.fetch };
}

async function probeCurrentAfterSwitch(ctx) {
  const { window, doc, settle } = ctx;
  const pair = await switchPair(ctx);
  if (pair.skip) return every(pair.skip);
  const { picker, from, to, fromKeys, toKeys, shows, realFetch } = pair;
  const routeOf = (id) => `/api/sessions/${encodeURIComponent(id)}/current`;
  const back = async () => {
    window.fetch = realFetch;
    if (picker.value !== from) choose(ctx, from);
    await waitFor(shows(fromKeys));
    await settle();
  };

  const verdicts = [];
  try {
    await waitFor(shows(fromKeys));

    // Late: held while `from` is selected, released once the switch to `to` has drawn its strip.
    let release = null;
    window.fetch = (url, init) => {
      if (!String(url).endsWith(routeOf(from))) return realFetch(url, init);
      window.fetch = realFetch;
      return new Promise((r) => { release = r; }).then(() => realFetch(url, init));
    };
    const late = window.refreshCurrent();
    window.fetch = realFetch;
    if (!release) {
      verdicts.push([CURRENT_LABELS[0], `NO - refreshCurrent asked for no ${routeOf(from)}`]);
    } else {
      choose(ctx, to);
      const drawn = await waitFor(shows(toKeys));
      await settle();
      release();
      await late;
      const strip = stripOf(doc);
      verdicts.push([CURRENT_LABELS[0], !drawn
        ? `n/a (the switch to ${to} never drew its own strip)`
        : same(strip, toKeys)
          ? `yes (${from}'s late reply dropped; the strip lists ${toKeys.length} of ${to}'s own)`
          : `NO - after ${from}'s late reply the strip lists ${strip.join(', ') || 'nothing'}, where ${to}'s ${toKeys.join(', ') || 'none'} were due`]);
    }

    await back();

    // Failed: the switch's own read held, so `state.current` is still `from`'s when the next one fails.
    const held = [];
    let failing = false;
    window.fetch = (url, init) => {
      if (!String(url).endsWith(routeOf(to))) return realFetch(url, init);
      if (failing) return Promise.reject(new TypeError('probe: the read failed'));
      return new Promise((r) => held.push(r)).then(() => realFetch(url, init));
    };
    choose(ctx, to);
    const asked = await waitFor(() => held.length > 0);
    failing = true;
    await window.refreshCurrent();
    const strip = stripOf(doc);
    verdicts.push([CURRENT_LABELS[1], !asked
      ? `n/a (the switch to ${to} never asked for ${routeOf(to)})`
      : strip.length === 0
        ? `yes (a failed read for ${to} leaves the strip empty, not ${from}'s ${fromKeys.length})`
        : `NO - after a failed read for ${to} the strip lists ${strip.join(', ')}`]);
    window.fetch = realFetch;
    for (const r of held) r();
    await waitFor(shows(toKeys));
    await settle();

    await back();
    verdicts.push(...await probeSwitchWindow(ctx, { from, to, fromKeys, toKeys, realFetch, shows }));
    verdicts.push(...await probeReadOrder(ctx, { from, fromKeys, routeOf, realFetch }));
    // The call side across the same switch, one pane a line (`switch-call-probes.js`).
    verdicts.push(...await probeCallsAfterSwitch(ctx, pair));
  } finally {
    await back();
  }
  return verdicts;
}

/** `route switch window`: `now switch shows none` with the switch made through the address. */
async function probeSwitchWindowByRoute(ctx) {
  const pair = await switchPair(ctx);
  if (pair.skip) return pair.skip;
  return (await probeSwitchWindow(ctx, pair, 'route'))[0][1];
}

/** Switches the page to [id] through the picker, or through the address when [via] is `route`. */
function switchVia(ctx, id, via) {
  if (via === 'route') ctx.window.location.hash = `#/traffic?session=${encodeURIComponent(id)}`;
  else choose(ctx, id);
}

/**
 * `now switch shows none`: on the timeline tab with the glance shown, the switch to `to` is held at
 * its `/signals` read. Until it lands, nothing on the signal side may be `from`'s. [via] is how the
 * switch is made, the picker or an address; the page is put back through the picker either way.
 */
async function probeSwitchWindow(ctx, { from, to, fromKeys, toKeys, realFetch, shows }, via = 'picker') {
  const { window, doc, settle } = ctx;
  const label = CURRENT_LABELS[2];
  const viewBefore = ctx.pageState().view;
  // The glance is drawn only while no row is open, as Escape leaves the page.
  const showGlance = () => {
    doc.getElementById('detail').hidden = true;
    doc.getElementById('detail-empty').hidden = false;
    window.renderSessionGlance();
  };
  await window.selectView('all');
  showGlance();
  await settle();
  const before = { strip: stripOf(doc), rows: timelineSignals(doc), glance: glanceOf(doc) };
  if (!same(before.strip, fromKeys) || !before.rows || !before.glance.drawn) {
    await window.selectView(viewBefore);
    return [[label, `NO - ${from} itself drew ${before.strip.length} strip rows, ${before.rows} timeline signals and ${before.glance.drawn ? 'a' : 'no'} glance, so nothing could leak`]];
  }
  const signalsOf = `/api/sessions/${encodeURIComponent(to)}/signals`;
  const held = [];
  window.fetch = (url, init) => {
    if (!String(url).includes(signalsOf)) return realFetch(url, init);
    return new Promise((r) => held.push(r)).then(() => realFetch(url, init));
  };
  let verdict;
  try {
    switchVia(ctx, to, via);
    const asked = await waitFor(() => held.length > 0);
    const during = { strip: stripOf(doc), rows: timelineSignals(doc), glance: glanceOf(doc) };
    const leaks = [];
    if (during.strip.length) leaks.push(`the strip lists ${during.strip.join(', ')}`);
    if (during.rows) leaks.push(`the timeline draws ${during.rows} signal entries`);
    if (during.glance.drawn) leaks.push(`the glance reads "${during.glance.text.slice(0, 60)}"`);
    verdict = !asked
      ? `NO - the switch to ${to} never asked for ${signalsOf}`
      : leaks.length
        ? `NO - while ${to}'s signals were held, ${leaks.join('; ')}`
        : `yes (with ${to}'s signals held: no strip rows, no timeline signals, the glance reads "${during.glance.text}"; ${from} had ${before.strip.length}, ${before.rows} and a glance)`;
  } finally {
    window.fetch = realFetch;
    for (const r of held) r();
  }
  await waitFor(shows(toKeys));
  await settle();
  choose(ctx, from);
  await waitFor(shows(fromKeys));
  await settle();
  await window.selectView(viewBefore);
  await settle();
  return [[label, verdict]];
}

/**
 * The three lines on one session's reads: two in flight released newest first, the older one
 * started by a live signal and then by a load, and a read that fails. The older read answers with
 * all but the first of `from`'s values, so the strip says which reply it drew.
 */
async function probeReadOrder(ctx, { from, fromKeys, routeOf, realFetch }) {
  const { window, doc, ORIGIN, settle } = ctx;
  const rows = await fetch(`${ORIGIN}${routeOf(from)}`).then((r) => r.json());
  const older = rows.slice(1);
  const verdicts = [];

  // Holds every `/current` read for `from`; the first one answers with `older`.
  const holdReads = () => {
    const reads = [];
    window.fetch = (url, init) => {
      if (!String(url).endsWith(routeOf(from))) return realFetch(url, init);
      const answer = reads.length === 0 ? older : rows;
      return new Promise((r) => reads.push(r)).then(() => replyOf(answer));
    };
    return reads;
  };
  const pair = async (label, startOlder) => {
    const reads = holdReads();
    try {
      const first = startOlder();
      const startedFirst = await waitFor(() => reads.length === 1);
      const second = window.refreshCurrent();
      const startedBoth = await waitFor(() => reads.length === 2);
      if (!startedFirst || !startedBoth) return [label, `NO - ${reads.length} of 2 reads of ${routeOf(from)} were asked`];
      reads[1]();
      await second;
      reads[0]();
      await first;
      const strip = stripOf(doc);
      return [label, same(strip, fromKeys)
        ? `yes (the newer read landed first and the older last; the strip lists the newer ${fromKeys.length})`
        : `NO - the older read landed last and the strip lists ${strip.join(', ') || 'nothing'}, where the newer ${fromKeys.length} were due`];
    } finally {
      window.fetch = realFetch;
      for (const r of reads) r();
      await settle();
    }
  };

  verdicts.push(await pair(CURRENT_LABELS[3], () => window.refreshCurrent()));
  verdicts.push(await pair(CURRENT_LABELS[4], () => window.loadSignals()));

  // Failed: on the session already shown, the strip keeps what it held.
  window.fetch = (url, init) => (String(url).endsWith(routeOf(from))
    ? Promise.reject(new TypeError('probe: the read failed'))
    : realFetch(url, init));
  try {
    await window.refreshCurrent();
    const strip = stripOf(doc);
    verdicts.push([CURRENT_LABELS[5], same(strip, fromKeys)
      ? `yes (a failed read on ${from} kept its ${fromKeys.length} values)`
      : `NO - after a failed read on ${from} the strip lists ${strip.join(', ') || 'nothing'}, where its ${fromKeys.length} were held`]);
  } finally {
    window.fetch = realFetch;
  }
  await window.refreshCurrent();
  await settle();
  return verdicts;
}

module.exports = { CURRENT_LABELS, probeCurrentAfterSwitch, probeSwitchWindowByRoute };
