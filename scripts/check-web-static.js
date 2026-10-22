/* The web UI checks that need no daemon, no network and nothing installed.
 *
 *   node scripts/check-web-static.js
 *
 * What it covers, each of which used to run only inside `render-web-ui.js` and so only when somebody
 * remembered to start a daemon and install jsdom:
 *
 *   - network call sites: one bare `fetch(`, none through `window.`, `globalThis.` or `self.`, one
 *     `new WebSocket(`, and no XMLHttpRequest, `sendBeacon` or EventSource — across every script;
 *   - the stylesheet audits: `[hidden]` overrides, nothing under 11px, long tokens wrap, the drawer;
 *   - the JSON tree's parser;
 *   - every shared mirror case in `mirror-cases/`, against the functions lifted out of the real
 *     web UI by their `@mirror-begin`/`@mirror-end` anchors;
 *   - the scripts as classic scripts sharing one global scope: each file starts with `'use strict'`,
 *     no top-level name is declared twice, and none takes a browser global's name — of the names a
 *     column-0 line heuristic can read, not a parse, so some declarations escape both;
 *   - every stylesheet closing its own braces and comments, since the stylesheet audits read them
 *     joined and a file left open would swallow the next one there and nowhere else;
 *   - no `@media` rule loading before a plain rule with the same selector that sets the same
 *     property, which is how the cascade between files depends on `styles.txt` order;
 *   - the pager, `routePages`, as `signalPages` and as `transactionPages`, against stub routes that
 *     clamp, ignore `offset` or shift a row across a page seam;
 *   - the session data under a filter: which list "last capture" dates the session by, and which
 *     answer survives when two loads overlap, a whole-session read fails, or the page forgets the
 *     whole session while a read is in flight; and what the filter notes and the path count on a
 *     session past the route's clamp; what a session switch clears, and the one "being read" wording;
 *   - a session too long to spread into an argument list: no `f(...rows)` anywhere in the scripts,
 *     and the axis, the waterfall, the stretch note, the timeline and the path run over 200,000 rows;
 *     and the path, past the clamp, says which visits' calls are not loaded rather than "0 of N";
 *   - the timeline under live tail: a run opened or closed leaves the pane where it is, a new row is
 *     followed only from the end it follows, and opening the view follows once; and the live socket's
 *     calls and markers leave the timeline, the path and the waterfall where the reader put them;
 *   - the shell: the address parser, the Sessions and Processes presets, the control header written in
 *     one place, and the narrow top bar and tab bar rules;
 *   - the list pane's room: nothing sticky in it taking its `top` from anything but `--sticky-top`,
 *     a closed drawer hidden from the keyboard and the page's width, with no shadow, and a narrow
 *     row keeping its path's last segment and its repeat mark;
 *   - the Stats page's live refresh: a burst of rows costing at most two requests, one out at a time,
 *     none while paused or not shown, and no answer drawn for a filter, sort or session since changed;
 *     and a route's server named through the `urlOf` block, so an IPv6 one is bracketed;
 *   - the Findings page's live refresh, by the same rule: at most two requests a burst, one out at a
 *     time, none while paused or not shown, and no answer drawn for a session since changed;
 *   - the polish rules: an icon-only control is an SVG in a box sized by one rule from `--target-min`,
 *     a page document's buttons are as tall and its prose stops at `--prose-max`; and the page keys,
 *     `g` and a letter for each page `PAGES` offers, `g g` the top, and no key while typing.
 *
 * The scripts section printed last so the lines above it stayed comparable with reports from before
 * it existed, and the per-file stylesheet check prints in it for the same reason: under `stylesheet`
 * it would push every later line down. The signal pages section came after it, on the same rule,
 * the session state section after that, then the long session and live follow sections.
 *
 * Output is the same `label : value` shape as the other reports, ending `N checks, M failed`.
 * Exit 1 when a check fails; exit 2 when the harness cannot run at all — an anchor missing or
 * doubled, a mirrored block with no case file or a case file with no block, a case file that is not
 * JSON. A harness that cannot run must not be mistaken for one that found nothing.
 */
const {
  HarnessError, auditHiddenPanes, auditMinTextSize, auditLongTokenWrap, auditDrawerCss,
  countNetworkPrimitives, NETWORK_EXPECTED, mirrorBlockNames,
  auditUseStrict, auditOneDeclarationPerName, auditWindowNames, auditCssClosesPerFile,
  auditMediaOverrideOrder,
} = require('./lib/web-audits');
const { loadScripts, loadStyles } = require('./lib/web-sources');
const { RULES, NON_RULE_BLOCKS, loadCaseFiles, runRule, checkJsonParser, checkSignalPages } = require('./lib/mirror-runner');
const { checkSessionState } = require('./lib/session-state-checks');
const { checkBeingRead } = require('./lib/being-read-checks');
const { checkListHolds } = require('./lib/list-holds-checks');
const { checkLiveReread } = require('./lib/live-reread-checks');
const { checkLongSession, checkPathPastTheClamp, auditNoArgumentSpread } = require('./lib/long-session-checks');
const { checkTimelineFollow, checkSocketFollow } = require('./lib/follow-checks');
const { checkShell } = require('./lib/shell-checks');
const { checkStatsRefresh } = require('./lib/stats-refresh-checks');
const { checkFindingsRefresh } = require('./lib/findings-refresh-checks');
const { checkPolish } = require('./lib/polish-checks');
const { auditNarrowTopBar, auditNarrowTabs, auditStickyTop, auditClosedDrawer, auditNarrowRow, auditOverviewBand } = require('./lib/layout-audits');

const results = [];
const report = (label, value) => {
  const text = String(value);
  results.push(text.startsWith('yes'));
  console.error(`${label.padEnd(26)}: ${text}`);
};

async function main() {
  const scripts = loadScripts();
  const styles = loadStyles();
  const css = styles.map((s) => s.text).join('\n');

  console.error('--- network call sites ---');
  const counts = countNetworkPrimitives(scripts);
  for (const [name, want] of Object.entries(NETWORK_EXPECTED)) {
    const got = counts[name];
    report(name, got === want ? `yes (${got})` : `NO - ${got}, must be ${want}`);
  }

  console.error('--- stylesheet ---');
  report('hidden panes hide', auditHiddenPanes(css));
  report('min text size', auditMinTextSize(css));
  report('long tokens wrap', auditLongTokenWrap(css));
  report('drawer css', auditDrawerCss(css));

  console.error('--- json tree ---');
  report('parser', checkJsonParser(scripts));

  console.error('--- mirror cases ---');
  const { defaults, byRule, problems } = loadCaseFiles();
  if (problems.length) {
    for (const p of problems) report('case file', `NO - ${p}`);
  }
  // Every anchored block is used by some rule (or is checked above), and every rule's blocks exist.
  const used = new Set([...NON_RULE_BLOCKS, ...Object.values(RULES).flatMap((r) => r.blocks)]);
  const orphans = mirrorBlockNames(scripts).filter((name) => !used.has(name));
  if (orphans.length) throw new HarnessError(`mirrored block(s) with no case file: ${orphans.join(', ')}`);
  const missing = Object.keys(RULES).filter((rule) => !byRule[rule] && !problems.some((p) => p.startsWith(`${rule}.json`)));
  if (missing.length) throw new HarnessError(`rule(s) with a block and no case file: ${missing.join(', ')}`);

  for (const rule of Object.keys(RULES)) {
    if (!byRule[rule]) continue;
    const { total, failures } = runRule(scripts, rule, byRule[rule], defaults);
    report(rule, failures.length
      ? `NO - ${failures.length} of ${total} cases failed\n    ${failures.join('\n    ')}`
      : `yes (${total} cases)`);
  }

  console.error('--- scripts ---');
  report('use strict first', auditUseStrict(scripts));
  report('one declaration per name', auditOneDeclarationPerName(scripts));
  report('no browser global names', auditWindowNames(scripts));
  report('css files close', auditCssClosesPerFile(styles));
  report('media overrides load last', auditMediaOverrideOrder(styles));

  console.error('--- signal pages ---');
  for (const [label, value] of await checkSignalPages(scripts)) report(label, value);

  console.error('--- session state ---');
  for (const [label, value] of await checkSessionState(scripts)) report(label, value);
  for (const [label, value] of await checkLiveReread(scripts)) report(label, value);
  for (const [label, value] of await checkBeingRead(scripts)) report(label, value);

  console.error('--- long session ---');
  report('no argument spread', auditNoArgumentSpread(scripts));
  for (const [label, value] of await checkLongSession(scripts)) report(label, value);
  for (const [label, value] of checkPathPastTheClamp(scripts)) report(label, value);
  for (const [label, value] of await checkListHolds(scripts)) report(label, value);

  console.error('--- live follow ---');
  for (const [label, value] of await checkTimelineFollow(scripts)) report(label, value);
  for (const [label, value] of await checkSocketFollow(scripts)) report(label, value);

  console.error('--- shell ---');
  for (const [label, value] of checkShell(scripts)) report(label, value);
  report('narrow top bar css', auditNarrowTopBar(styles));
  report('narrow tabs css', auditNarrowTabs(styles));
  report('sticky top', auditStickyTop(styles));
  report('closed drawer css', auditClosedDrawer(styles));
  report('narrow row css', auditNarrowRow(styles));
  report('overview band css', auditOverviewBand(styles));

  console.error('--- stats ---');
  for (const [label, value] of await checkStatsRefresh(scripts)) report(label, value);

  console.error('--- findings ---');
  for (const [label, value] of await checkFindingsRefresh(scripts)) report(label, value);

  console.error('--- polish ---');
  for (const [label, value] of checkPolish(scripts, styles)) report(label, value);

  const failed = results.filter((ok) => !ok).length;
  console.error(`${results.length} checks, ${failed} failed`);
  return failed ? 1 : 0;
}

main().then((code) => { process.exitCode = code; }, (e) => {
  // Anything thrown here means the checks did not run; a crash is not a result.
  console.error(`harness: ${e instanceof HarnessError ? e.message : e.stack}`);
  process.exitCode = 2;
});
