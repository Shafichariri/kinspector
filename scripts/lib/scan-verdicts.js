/* Verdicts for the live probe's scans: the rules `render-web-ui.js` judges over a set of rows,
 * lines, bars or elements, and what each says when the set came back empty.
 *
 * The probes share one page, so a probe that leaves state behind can empty the next one's scan,
 * and a rule that passes when nothing breaks it then passes over nothing. KIN-55's live probe left
 * a path visit open, the path probe found no summarised line, and `failures unfolded` printed
 * `yes (0 summarised lines in this session)`. `INSPECTOR_UI_EXPECT_CHECKS` could not see it,
 * because a pass over nothing is still a pass. So each scan here asserts that it found at least
 * one of its shape and prints how many it checked. Where the shape cannot exist, it says
 * `n/a (why)`, and that is decided from a source the scan's emptiness cannot have caused: the
 * daemon's rows, or the page's whole-session state, never the elements that were scanned.
 */

const plural = (n, one) => `${n} ${n === 1 ? one : `${one}s`}`;

/** `null` when [count] found something; otherwise `n/a ([absent])` if the shape cannot exist, else a NO naming the empty scan. */
function emptyScan(count, shape, absent) {
  if (count > 0) return null;
  return absent ? `n/a (${absent})` : `NO - found no ${shape}, so this checked nothing`;
}

/**
 * `host not repeated` and `outside rows say it`, over the list's rows while the scope bar is shown.
 * A stripped path has no leading slash; every other row is outside the bar. Rows outside it are
 * legitimately absent when every row the list holds is one the bar covers, which `state.scope`
 * answers from the whole session rather than from what was drawn.
 */
function scopeRowVerdicts(state, paths, hosts) {
  const stripped = paths.map((p, i) => (p && !p.startsWith('/') ? i : -1)).filter((i) => i !== -1);
  const outside = paths.map((_, i) => i).filter((i) => !stripped.includes(i));
  const heldOutside = state.transactions.filter((t) => !state.scope.covers(t)).length;
  const repeated = stripped.filter((i) => hosts[i]).length;
  const nameless = outside.filter((i) => !hosts[i]).length;
  return {
    noRepeatedHost: emptyScan(stripped.length, 'row under the bar')
      ?? (repeated ? `NO - ${repeated} of ${stripped.length} rows under the bar name the host` : `yes (${plural(stripped.length, 'row')} under the bar, none names the host)`),
    uncoveredNameTheirHost: emptyScan(outside.length, 'row outside the bar', heldOutside ? null : 'every row the list holds is under the bar')
      ?? (nameless ? `NO - ${nameless} of ${outside.length} rows outside the bar name no host`
        : `yes (${plural(outside.length, 'row')} outside the bar, each names its host)`),
  };
}

/**
 * The waterfall's four scans: rows, the list's rows they must account for, groups and bars. All
 * four are absent legitimately only when the daemon holds no calls for the session ([txns]).
 */
function waterfallVerdicts(doc, txns) {
  const absent = txns.length ? null : 'no calls in this session';
  const listed = [...doc.querySelectorAll('.wf-row')].map((r) => r.dataset.id);
  const unique = new Set(listed);
  const inList = doc.querySelectorAll('#list .row').length;
  // Oldest first inside each visit. The daemon sends rows newest first, and a grouping that kept
  // that order drew every visit upside down; only a screenshot noticed.
  const monoOf = new Map(txns.map((x) => [x.id, x.mono]));
  const groups = [...doc.querySelectorAll('.wf-group')];
  const backwards = groups.filter((g) => {
    const monos = [...g.querySelectorAll('.wf-row')].map((r) => monoOf.get(r.dataset.id));
    return monos.some((m, i) => i > 0 && m < monos[i - 1]);
  }).length;
  // Every bar has to be visible. A zero-width bar reads as a row that failed to draw.
  const widths = [...doc.querySelectorAll('.wf-bar')].map((b) => parseFloat(b.style.width));
  return {
    everyCallOnce: emptyScan(listed.length, 'waterfall row', absent) ?? (listed.length === unique.size
      ? `yes (${unique.size} of ${inList} calls, each once)` : `NO - ${listed.length} rows for ${unique.size} calls`),
    noneLost: emptyScan(inList, 'row in the list to look for', absent) ?? (unique.size === inList
      ? `yes (${inList} calls)` : `NO - ${inList} calls in the list, ${unique.size} in the waterfall`),
    oldestFirst: emptyScan(groups.length, 'waterfall group', absent)
      ?? (backwards ? `NO - ${backwards} groups out of order` : `yes (${groups.length} groups)`),
    barsVisible: emptyScan(widths.length, 'waterfall bar', absent) ?? (widths.every((w) => w > 0)
      ? `yes (${widths.length} bars)` : `NO - ${widths.filter((w) => !(w > 0)).length} bars have no width`),
  };
}

/** A line's "N failed" against the failures it accounts for: the failed rows drawn when open, its chips' counts when collapsed. */
const failedIn = (c) => Number((c.querySelector('.pv-failed')?.textContent.match(/(\d+) failed/) || [0, 0])[1]);
const shownIn = (c) => (c.classList.contains('pv-open')
  ? [...c.querySelectorAll('.pv-call .pv-status')].filter((n) => /s[45x]/.test(n.className)).length
  : [...c.querySelectorAll('.pv-chip[data-failed]')].reduce((n, chip) => n + Number(chip.dataset.failed), 0));
const lineNames = (lines) => lines.map((c) => c.dataset.screen || 'leading').join(', ');

/**
 * `failures unfolded`, read before the probe opens anything. Its shape is a summarised line, one
 * collapsed behind `N network`. None is legitimately absent only when no line is open either:
 * then every line already shows all it holds. A line left open is what KIN-55's leak looked like,
 * and the only way a long visit stops being summarised, so with one open an empty scan is a NO.
 */
function failuresUnfolded(lines) {
  const hiding = lines.filter((c) => failedIn(c) !== shownIn(c));
  if (hiding.length) return `NO - ${lineNames(hiding)} hide a failure`;
  const summarised = lines.filter((c) => c.querySelector('.pv-chip[title="expand this visit"]')).length;
  const open = lines.filter((c) => c.classList.contains('pv-open'));
  return emptyScan(summarised, `summarised line (${open.length} already open: ${lineNames(open)})`,
    open.length ? null : 'no line is open and none is long enough to summarise')
    ?? `yes (${summarised} summarised ${summarised === 1 ? 'line' : 'lines'}, ${plural(lines.length, 'line')} in all)`;
}

/**
 * The first visit holding a failed call, from the daemon's rows: `{ index, name, failures }`, or
 * null when no visit holds one. `expanded unfolded` opens this one. The second station, which it
 * opened before, is Home on the CI fixture, with no failure, so the rule compared 0 with 0.
 *
 * Visits are split here rather than by the page's `visits()`, for the reason `route matches` is: a
 * probe calling the same function agrees with any bug in it. A visit starts at each change of
 * screen name, plus a leading one when anything came before the first screen ([leading]); a call
 * belongs to the last start at or before it, `seq` deciding a tie when both carry one.
 */
function failingVisit(screens, txns, leading) {
  const starts = [];
  const arrivals = [...screens].sort((a, b) => a.mono - b.mono || (a.seq ?? -Infinity) - (b.seq ?? -Infinity));
  for (const s of arrivals) {
    if (starts[starts.length - 1]?.name !== s.name) starts.push({ name: s.name, mono: s.mono, seq: s.seq ?? null });
  }
  if (leading) starts.unshift({ name: '', mono: -Infinity, seq: null });
  if (!starts.length) return null;
  const opensBy = (start, t) => (start.mono !== t.mono ? start.mono < t.mono
    : start.seq == null || t.seq == null || start.seq < t.seq);
  const failures = new Array(starts.length).fill(0);
  for (const t of txns) {
    if (!(t.error || (t.status ?? 0) >= 400)) continue;
    let owner = 0;
    starts.forEach((start, i) => { if (opensBy(start, t)) owner = i; });
    failures[owner] += 1;
  }
  const index = failures.findIndex((n) => n > 0);
  return index === -1 ? null : { index, name: starts[index].name || 'leading', failures: failures[index] };
}

/** The station `expanded unfolded` opens: the failing visit's, else the second, so `station opens line` still runs. */
function stationToOpen(stations, screens, txns, leading) {
  const held = failingVisit(screens, txns, leading);
  const target = (held && stations.find((s) => s.dataset.visit === String(held.index)))
    || stations[Math.min(1, stations.length - 1)];
  return { held, target };
}

/**
 * `expanded unfolded`, after a station opened its line: the same rule, which needs an open line to
 * mean anything, and a line with a failure in it, or it compares 0 with 0. [held] is
 * `failingVisit`'s answer; the opened line must draw at least the failures the daemon holds there.
 */
function expandedUnfolded(lines, held) {
  if (!held) return 'n/a (no visit holds a failure)';
  const open = lines.filter((c) => c.classList.contains('pv-open'));
  const hiding = lines.filter((c) => failedIn(c) !== shownIn(c));
  const target = open.find((c) => c.dataset.visit === String(held.index));
  const drawn = target ? shownIn(target) : 0;
  return emptyScan(open.length, 'expanded line')
    ?? (hiding.length ? `NO - an expanded line hides a failure (${lineNames(hiding)})`
      : drawn < held.failures ? `NO - ${held.name} holds ${plural(held.failures, 'failure')}, its ${target ? 'open' : 'unopened'} line draws ${drawn}`
        : `yes (${open.length} expanded ${open.length === 1 ? 'line' : 'lines'}, ${plural(drawn, 'failure')} drawn in ${held.name})`);
}

/**
 * `unknown tag renders`: a tag this build has no styling for lands in lane `other`. Whether the
 * session holds such a tag is the daemon's answer ([signals]), so a timeline that drew none of
 * them is a NO rather than "no unknown tags".
 */
function unknownTagVerdict(doc, signals) {
  const known = ['screen', 'state', 'cache', 'session'];
  const held = [...new Set(signals.map((s) => s.tag).filter((t) => !known.includes(t)))];
  const lanes = [...doc.querySelectorAll('#timeline .tl-signal')]
    .filter((n) => !known.includes(n.dataset.tag))
    .map((n) => n.dataset.lane);
  return emptyScan(lanes.length, `timeline row of the session's unknown tags (${held.join(', ')})`,
    held.length ? null : 'no unknown tags in this session')
    ?? (lanes.every((l) => l === 'other') ? `yes (${lanes.length} in lane 'other')` : `NO - landed in ${lanes.join(',')}`);
}

/** `now rows show age`, over the now strip; [current] is the daemon's `/current` for the session. */
function currentAgeVerdict(doc, current) {
  const items = [...doc.querySelectorAll('#current .current-item')];
  const ageless = items.filter((n) => !n.querySelector('.current-age')).length;
  return emptyScan(items.length, 'now row', current.length ? null : 'the session holds no current values')
    ?? (ageless ? `NO - ${ageless} of ${items.length} now rows have no age` : `yes (${items.length} now rows, each with its age)`);
}

module.exports = {
  emptyScan, scopeRowVerdicts, waterfallVerdicts, failuresUnfolded, stationToOpen, expandedUnfolded, unknownTagVerdict, currentAgeVerdict,
};
