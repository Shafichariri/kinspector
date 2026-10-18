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
 *     no top-level name is declared twice, and none takes a browser global's name;
 *   - every stylesheet closing its own braces and comments, since the stylesheet audits read them
 *     joined and a file left open would swallow the next one there and nowhere else.
 *
 * The scripts section prints last so the lines above it stay comparable with reports from before it
 * existed, and the per-file stylesheet check prints in it for the same reason: under `stylesheet` it
 * would push every later line down.
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
} = require('./lib/web-audits');
const { loadScripts, loadStyles } = require('./lib/web-sources');
const { RULES, NON_RULE_BLOCKS, loadCaseFiles, runRule, checkJsonParser } = require('./lib/mirror-runner');

const results = [];
const report = (label, value) => {
  const text = String(value);
  results.push(text.startsWith('yes'));
  console.error(`${label.padEnd(26)}: ${text}`);
};

function main() {
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

  const failed = results.filter((ok) => !ok).length;
  console.error(`${results.length} checks, ${failed} failed`);
  return failed ? 1 : 0;
}

try {
  process.exitCode = main();
} catch (e) {
  // Anything thrown here means the checks did not run; a crash is not a result.
  console.error(`harness: ${e instanceof HarnessError ? e.message : e.stack}`);
  process.exitCode = 2;
}
