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
 * Both call the page's `refreshCurrent` directly, which is what a live signal does, so the probe
 * holds the very read it releases instead of racing one the socket might start. Each stubs the page's
 * `fetch` for the one route it holds and puts the real one back before it returns, and the page is
 * left on the session it started on. Returns `[label, verdict]` pairs, one per line the caller prints.
 */

const { choose, waitFor } = require('./long-session-probes');

const CURRENT_LABELS = ['now late reply dropped', 'now failed read empty'];
const every = (why) => CURRENT_LABELS.map((label) => [label, why]);

/** What the strip lists, as `tag name` in drawn order. */
const stripOf = (doc) =>
  [...doc.querySelectorAll('#current .current-item')].map(
    (li) => `${li.dataset.tag} ${li.querySelector('.current-name')?.textContent}`,
  );
/** The same, for rows a route answered with. */
const keysOf = (rows) => rows.map((s) => `${s.tag} ${s.name}`);
const same = (a, b) => a.join('|') === b.join('|');

async function probeCurrentAfterSwitch(ctx) {
  const { window, doc, ORIGIN, settle } = ctx;
  const picker = doc.getElementById('session-picker');
  const from = picker.value;
  const to = [...picker.options].map((o) => o.value).find((v) => v && v !== from);
  if (!to) return every('n/a (one session; nothing to switch to)');
  if (typeof window.refreshCurrent !== 'function') return every('NO - refreshCurrent is not a page function');
  const currentOf = (id) => fetch(`${ORIGIN}/api/sessions/${encodeURIComponent(id)}/current`).then((r) => r.json());
  const fromKeys = keysOf(await currentOf(from));
  const toKeys = keysOf(await currentOf(to));
  if (!fromKeys.length) return every(`n/a (${from} holds no current values to leak)`);
  if (same(fromKeys, toKeys)) return every('n/a (both sessions hold the same current values)');
  const routeOf = (id) => `/api/sessions/${encodeURIComponent(id)}/current`;
  const realFetch = window.fetch;
  const shows = (keys) => () => same(stripOf(doc), keys);
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
  } finally {
    await back();
  }
  return verdicts;
}

module.exports = { CURRENT_LABELS, probeCurrentAfterSwitch };
