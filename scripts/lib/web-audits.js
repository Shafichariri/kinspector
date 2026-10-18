/* Checks that read the web UI's source text rather than a rendered page.
 *
 * Moved here from `render-web-ui.js` so `check-web-static.js` can run them with no daemon, no jsdom
 * and no network, in CI, before Gradle has started. `render-web-ui.js` still prints them in its own
 * report; there is one copy of each.
 *
 * The stylesheet audits read CSS text on purpose, not computed style: jsdom has no layout engine,
 * resolves a `hidden` element to `display: none` whatever the CSS says, and computes no font size
 * worth trusting. A computed-style version of each passes with the rule it guards deleted.
 *
 * Each audit returns a report value that starts `yes` or `NO`, which is what both reports classify.
 */

/** Thrown when a check cannot run at all, as distinct from a check that ran and said NO. */
class HarnessError extends Error {}

/**
 * Nothing under 11px except the method badge, the key caps and the fold glyphs.
 *
 * Read from the stylesheet source: jsdom computes no font sizes worth trusting. Each exception is
 * named rather than matched by pattern, so a new 10px rule fails here until someone decides it
 * belongs on the list.
 */
function auditMinTextSize(cssText) {
  // The method badge, key caps, and the three fold glyphs (▸ ▾), which are shapes, not text.
  const allowed = ['.row .method', 'kbd', '.jt-tw', '.now-caret', '.tl-twisty'];
  const offenders = [];
  const source = cssText.replace(/\/\*[\s\S]*?\*\//g, '');
  for (const [, selector, body] of source.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const size = body.match(/font-size:\s*(\d+(?:\.\d+)?)px/);
    if (!size || Number(size[1]) >= 11) continue;
    const name = selector.trim();
    if (!allowed.includes(name)) offenders.push(`${name} ${size[1]}px`);
  }
  return offenders.length ? `NO - ${offenders.join(', ')}` : `yes (only ${allowed.join(', ')} below 11px)`;
}
/**
 * A long unbroken token must not be able to widen the detail pane.
 *
 * Checked against the stylesheet text, not `getComputedStyle`: jsdom has no layout engine, so
 * it reports neither the 7139px line box this guards against nor whether the rule prevented it.
 * A computed-style version of this check passes just as happily with the rule deleted — the
 * same reason `auditHiddenPanes` reads the CSS source. The real measurement belongs in a
 * browser, and is recorded in the commit that added the rule.
 */
function auditLongTokenWrap(cssText) {
  const block = cssText.match(/\.headers\s*\{([^}]*)\}/);
  if (!block) return 'NO - no .headers rule at all';
  const wrap = block[1].match(/overflow-wrap\s*:\s*([a-z-]+)/);
  const breaks = block[1].match(/word-break\s*:\s*([a-z-]+)/);
  const value = wrap?.[1] || breaks?.[1];
  return value && ['anywhere', 'break-all', 'break-word'].includes(value)
    ? `yes (${value})`
    : 'NO - a bearer token will paint outside the pane and scroll the page';
}
/** Is the detail pane a drawer at all below the breakpoint? Read from the stylesheet. */
function auditDrawerCss(cssText) {
  // The block that lays out `#panes`, not merely the first narrow block: another view's narrow
  // rule placed earlier in the file used to make this read the wrong block and fail.
  const block = [...cssText.matchAll(/@media \(max-width: \d+px\) \{([\s\S]*?)\n\}/g)]
    .find((m) => /#panes/.test(m[1]));
  if (!block) return 'NO - no narrow-width media block lays out #panes';
  const body = block[1];
  // Anchored at the end of the declaration: `1fr` alone also prefix-matches `1fr 1fr`, which
  // is the exact two-column layout this is supposed to catch.
  const oneColumn = /#panes[^{]*\{[^}]*grid-template-columns:\s*1fr\s*[;}]/.test(body);
  const floats = /#detail-pane\s*\{[^}]*position:\s*absolute/.test(body);
  const slides = /drawer-open[^{]*#detail-pane\s*\{[^}]*translateX\(0\)/.test(body);
  const missing = [
    !oneColumn && 'the list does not take the full width',
    !floats && 'the detail pane is still in flow',
    !slides && 'nothing brings the drawer on screen',
  ].filter(Boolean);
  return missing.length ? `NO - ${missing.join('; ')}` : 'yes';
}
/**
 * A hidden pane must actually be invisible.
 *
 * `hidden` only works because of a user agent rule, `[hidden] { display: none }`, and any author
 * rule setting `display` on the same element outranks it — the pane then paints over whichever
 * view is selected while its `hidden` property still reads true. This is checked against the
 * stylesheet text rather than through `getComputedStyle`, because jsdom resolves a hidden
 * element to `display: none` whatever the CSS says: the computed-style version of this check
 * passes just as happily with the guard rule deleted, which makes it worse than no check at all.
 */
function auditHiddenPanes(cssText) {
  // Panes the app shows and hides by toggling `hidden`.
  // `.toolbar` and `.now-strip` join the list because they are the newest members of exactly
  // the class of bug it guards: both set `display` and both are shown and hidden with `hidden`.
  const panes = [
    '.browser', '#browser', '.tabs', '#tabs', '#timeline', '#list', '.toolbar', '.now-strip',
    '.drawer-scrim', '.axis', '.waterfall', '.path-view', '.path-new',
    // Hidden on the path tab, where order has no meaning; it rendered anyway until this was here.
    '.seg2',
  ];
  const setsDisplay = new Set();
  const guarded = new Set();
  for (const [, selector, body] of cssText.matchAll(/([.#][A-Za-z0-9_-]+)\s*\{([^}]*)\}/g)) {
    if (/(^|;)\s*display\s*:/.test(body)) setsDisplay.add(selector);
  }
  for (const [, selector] of cssText.matchAll(
    /([.#][A-Za-z0-9_-]+)\[hidden\]\s*\{[^}]*display\s*:\s*none/g,
  )) {
    guarded.add(selector);
  }
  const offenders = panes.filter((s) => setsDisplay.has(s) && !guarded.has(s));
  return offenders.length
    ? `NO - ${offenders.join(', ')} sets display without a [hidden] override`
    : 'yes';
}
/**
 * Network call sites across every loaded script.
 *
 * The page talks to the daemon through one object, `source`, and so calls the browser's `fetch`
 * exactly once; an export swaps `source` for the embedded archive, and any other call site would
 * reach for the network from inside a file opened from disk. Counting only a bare `fetch(` would
 * let `window.fetch(`, `globalThis.fetch(` or `self.fetch(` through, so those are counted
 * separately and must be zero. The other primitives are matched anywhere in the text, comments
 * included: a strict count that a comment can trip is cheaper than one a call can slip past.
 *
 * A known limit: these are text patterns, not a parse, so `window['fetch'](`, `fetch (x)` with a
 * space, or an aliased `const f = fetch` are not counted. They are not uncovered — the archive
 * probe opens the export with `fetch`, `WebSocket` and `XMLHttpRequest` all made to throw, so any
 * such call that runs in an export fails "no network attempts" at runtime — but this count alone
 * would not see them.
 */
function countNetworkPrimitives(sources) {
  const count = (re) => sources.reduce((n, { text }) => n + (text.match(re) || []).length, 0);
  return {
    fetch: count(/(?<![.\w])fetch\(/g),
    windowFetch: count(/\bwindow\.fetch\(/g),
    globalThisFetch: count(/\bglobalThis\.fetch\(/g),
    selfFetch: count(/\bself\.fetch\(/g),
    webSocket: count(/\bnew WebSocket\(/g),
    xmlHttpRequest: count(/\bXMLHttpRequest\b/g),
    sendBeacon: count(/\bsendBeacon\(/g),
    eventSource: count(/\bEventSource\b/g),
  };
}

/** What each count must be. */
const NETWORK_EXPECTED = {
  fetch: 1,
  windowFetch: 0,
  globalThisFetch: 0,
  selfFetch: 0,
  webSocket: 1,
  xmlHttpRequest: 0,
  sendBeacon: 0,
  eventSource: 0,
};

const BEGIN = /^[ \t]*\/\/ @mirror-begin ([A-Za-z]+)[ \t]*$/gm;
const END = /^[ \t]*\/\/ @mirror-end ([A-Za-z]+)[ \t]*$/gm;

/** Every block name anchored in [sources], for the check that each one has a case file. */
function mirrorBlockNames(sources) {
  const names = new Set();
  for (const { text } of sources) for (const [, name] of text.matchAll(BEGIN)) names.add(name);
  return [...names].sort();
}

/**
 * The text between `// @mirror-begin <name>` and `// @mirror-end <name>`.
 *
 * Explicit anchors, because the lift this replaced ended at the next function's doc comment — a
 * boundary that moves whenever somebody adds a function, and that silently lifts too little or too
 * much when it does. Exactly one begin and one end per name, in the same file, in that order;
 * anything else is a `HarnessError`, which `check-web-static.js` reports as exit 2 naming the block.
 */
function liftBlock(sources, name) {
  const found = (re) => sources.flatMap(({ file, text }) =>
    [...text.matchAll(re)].filter((m) => m[1] === name).map((m) => ({ file, index: m.index, end: m.index + m[0].length })));
  const begins = found(BEGIN);
  const ends = found(END);
  if (begins.length !== 1 || ends.length !== 1) {
    throw new HarnessError(
      `mirror block '${name}': ${begins.length} @mirror-begin and ${ends.length} @mirror-end, expected exactly one of each`,
    );
  }
  const [b] = begins;
  const [e] = ends;
  if (b.file !== e.file || e.index < b.end) {
    throw new HarnessError(`mirror block '${name}': @mirror-end must follow @mirror-begin in the same file`);
  }
  return sources.find((s) => s.file === b.file).text.slice(b.end, e.index);
}

/**
 * Every manifest file opts into strict mode as its first statement.
 *
 * The page is classic scripts sharing one global scope, and `'use strict'` applies per file, so a
 * file without it silently turns a misspelt assignment into a new global. jsdom does not throw on
 * an implicit global even under the directive (measured in 29.1.1, through `vm.Script` and through
 * a real `<script>`), so neither probe can see a missing one: this check and a real browser are
 * the only things that can.
 *
 * "First" is the whole rule. A `'use strict'` after any other statement is not a directive, only a
 * string expression that does nothing, so the file is read past its leading comments and blank
 * lines and the next thing must be the directive itself.
 */
function auditUseStrict(sources) {
  if (!sources.length) return 'NO - no scripts to read';
  const offenders = [];
  for (const { file, text } of sources) {
    let rest = text;
    for (;;) {
      rest = rest.replace(/^\s+/, '');
      if (rest.startsWith('//')) {
        const eol = rest.indexOf('\n');
        rest = eol < 0 ? '' : rest.slice(eol + 1);
      } else if (rest.startsWith('/*')) {
        const close = rest.indexOf('*/', 2);
        rest = close < 0 ? '' : rest.slice(close + 2);
      } else break;
    }
    const first = rest.split('\n', 1)[0].trim();
    if (!/^(['"])use strict\1;?$/.test(first)) offenders.push(`${file} starts with ${JSON.stringify(first.slice(0, 40))}`);
  }
  return offenders.length
    ? `NO - ${offenders.join('; ')}, not 'use strict'`
    : `yes (${sources.length} file(s))`;
}

/** Declarations at column 0. Matched by kind, so `for (const x …)` and `(async function init…` are not. */
const TOP_LEVEL = /^(?:async[ \t]+)?(function\b[ \t]*\*?|const\b|let\b|var\b|class\b)[ \t]*([^\s(=;]+)(.*)$/gm;

/** Whether [rest] of a declaration's line has a comma outside brackets and quotes: `let a = 1, b`. */
function declaresMore(rest) {
  let depth = 0;
  let quote = null;
  for (let i = 0; i < rest.length; i++) {
    const c = rest[i];
    if (quote) {
      if (c === '\\') i++;
      else if (c === quote) quote = null;
    } else if (c === "'" || c === '"' || c === '`') quote = c;
    else if ('([{'.includes(c)) depth++;
    else if (')]}'.includes(c)) depth--;
    else if (c === ',' && depth === 0) return true;
    else if (c === ';' && depth === 0) return false;
  }
  return false;
}

/**
 * Every top-level name each script declares: `[{ file, line, name }]`.
 *
 * Read by layout, not by a parse, because the checks run with nothing installed. Every file is
 * written with its top level at column 0 and everything nested indented, which is what lets a
 * column-0 `function`, `const`, `let`, `var` or `class` stand for a top-level declaration. The limit
 * that follows: a top-level declaration written indented is not seen. A form whose names this
 * cannot read — destructuring, or two names in one `let` on its first line — is refused rather than
 * skipped, so a declaration is never silently left out by its shape.
 */
function topLevelDeclarations(sources) {
  const found = [];
  for (const { file, text } of sources) {
    for (const m of text.matchAll(TOP_LEVEL)) {
      const line = text.slice(0, m.index).split('\n').length;
      const [, kind, name, rest] = m;
      if (!/^[A-Za-z_$][\w$]*$/.test(name) || (/^(const|let|var)$/.test(kind) && declaresMore(rest))) {
        throw new HarnessError(`${file}:${line}: cannot read the names declared by: ${m[0].trim()}`);
      }
      found.push({ file, line, name });
    }
  }
  return found;
}

/**
 * No top-level name is declared twice, in one file or across two.
 *
 * Without the IIFE every top-level `function` is a property of `window`, and a second declaration
 * of the same name silently replaces the first wherever it was called from — which is also what a
 * stale copy of a moved function would do, anchors or not. A duplicate `const` would at least be
 * a `SyntaxError`, but only in whichever browser loaded both files.
 */
function auditOneDeclarationPerName(sources) {
  const decls = topLevelDeclarations(sources);
  if (!decls.length) return 'NO - found no top-level declarations, so this checked nothing';
  const byName = new Map();
  for (const d of decls) byName.set(d.name, [...(byName.get(d.name) || []), d]);
  const twice = [...byName].filter(([, ds]) => ds.length > 1)
    .map(([name, ds]) => `${name} in ${ds.map((d) => `${d.file}:${d.line}`).join(' and ')}`);
  return twice.length
    ? `NO - declared more than once: ${twice.join('; ')}`
    : `yes (${decls.length} names in ${sources.length} file(s))`;
}

/**
 * Browser globals no top-level declaration may take.
 *
 * A top-level `function stop()` is `window.stop`: it replaces the browser's for every caller, the
 * browser's own included, and nothing reports it. A `let` or `const` of a name the browser holds as
 * non-configurable (`window`, `document`, `location`, `top`) is instead a redeclaration error that
 * stops the whole file from running. Named rather than read from a `Window` at runtime: the check runs with no browser, and a list
 * someone has to extend is one somebody decided on. The second group is what this page itself
 * calls, where shadowing breaks the page rather than the browser.
 */
const WINDOW_DENYLIST = [
  'top', 'parent', 'self', 'window', 'document', 'location', 'history', 'name', 'status', 'stop',
  'close', 'open', 'print', 'focus', 'blur', 'find', 'scroll', 'event', 'origin', 'external',
  'screen', 'navigator', 'frames', 'length', 'opener', 'closed', 'alert', 'confirm', 'prompt',
  'fetch', 'toolbar', 'menubar', 'crypto', 'performance', 'INSPECTOR_ARCHIVE',
  'localStorage', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'WebSocket',
  'URL', 'URLSearchParams', 'TextDecoder', 'TextEncoder', 'console',
];

/** No top-level name is one of [WINDOW_DENYLIST]. */
function auditWindowNames(sources) {
  const decls = topLevelDeclarations(sources);
  if (!decls.length) return 'NO - found no top-level declarations, so this checked nothing';
  const deny = new Set(WINDOW_DENYLIST);
  const hits = decls.filter((d) => deny.has(d.name)).map((d) => `${d.name} at ${d.file}:${d.line}`);
  return hits.length
    ? `NO - a browser global declared: ${hits.join('; ')}`
    : `yes (${decls.length} names, none of ${deny.size} browser globals)`;
}

module.exports = {
  HarnessError,
  auditUseStrict,
  auditOneDeclarationPerName,
  auditWindowNames,
  WINDOW_DENYLIST,
  auditMinTextSize,
  auditLongTokenWrap,
  auditDrawerCss,
  auditHiddenPanes,
  countNetworkPrimitives,
  NETWORK_EXPECTED,
  mirrorBlockNames,
  liftBlock,
};
