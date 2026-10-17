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

module.exports = {
  HarnessError,
  auditMinTextSize,
  auditLongTokenWrap,
  auditDrawerCss,
  auditHiddenPanes,
  countNetworkPrimitives,
  NETWORK_EXPECTED,
  mirrorBlockNames,
  liftBlock,
};
