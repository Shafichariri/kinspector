/* Stylesheet audits of what the page draws at a width, read as text from the files in
 * `web/styles.txt`. `lib/web-audits.js` holds the older audits and is near its size limit; these
 * start here, and later layout audits join them.
 *
 * Text heuristics, not a cascade: they read the rule a decision rests on where the decision put
 * it, and among what they miss is a later rule elsewhere undoing it. Chrome is the real check
 * (`shell <width> …` in `measure-web-ui.js`). Zero dependencies, like the rest of
 * `check-web-static.js`.
 */
const { HarnessError, cssBlocks, selectorList } = require('./web-audits');

const uncomment = (text) => text.replace(/\/\*[\s\S]*?\*\//g, '');

function sheet(styles, file) {
  const found = styles.find((s) => s.file === file);
  if (!found) throw new HarnessError(`${file} is not in web/styles.txt; update layout-audits.js`);
  return uncomment(found.text);
}

/** The body of the first `@media (<query>)` block in [css], braces matched, or null. */
function mediaBody(css, query) {
  const at = css.indexOf(`@media (${query}) {`);
  if (at === -1) return null;
  let depth = 0;
  for (let i = css.indexOf('{', at); i < css.length; i++) {
    if (css[i] === '{') depth++;
    else if (css[i] === '}' && --depth === 0) return css.slice(css.indexOf('{', at) + 1, i);
  }
  return null;
}

/** The declarations of the first rule whose selector is exactly [selector] in [css], or ''. */
function rule(css, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const m = css.match(new RegExp(`(?:^|[}\\s])${escaped}\\s*\\{([^}]*)\\}`));
  return m ? m[1] : '';
}
const sets = (body, property, value) => new RegExp(`(^|;)\\s*${property}\\s*:\\s*${value}\\s*(;|$)`).test(body.trim());

/**
 * Below 720px the top bar wraps to two lines and the count takes the second whole; the brand goes;
 * and the picker's face keeps its start time whole while the rest ellipsizes. `05-base.css`.
 */
function auditNarrowTopBar(styles) {
  const css = sheet(styles, 'css/05-base.css');
  const narrow = mediaBody(css, 'max-width: 719px');
  if (narrow === null) return 'NO - no @media (max-width: 719px) block in css/05-base.css';
  const rest = rule(css, '.picker-rest');
  const missing = [
    !sets(rule(narrow, '#topbar'), 'flex-wrap', 'wrap') && '#topbar does not wrap',
    !sets(rule(narrow, '#counts'), 'flex-basis', '100%') && '#counts has no line of its own',
    !sets(rule(narrow, '.brand'), 'display', 'none') && '.brand is not hidden',
    !sets(rule(css, '.picker-time'), 'flex', 'none') && '.picker-time can shrink',
    !(sets(rest, 'overflow', 'hidden') && sets(rest, 'text-overflow', 'ellipsis') && sets(rest, 'white-space', 'nowrap'))
      && '.picker-rest does not ellipsize',
  ].filter(Boolean);
  return missing.length ? `NO - ${missing.join('; ')}` : 'yes (the bar wraps, the count has line 2, no brand; the time never shrinks, the rest ellipsizes)';
}

/** Below 700px the tab bar draws the active tab and `views ▾`. `65-tabs.css`. */
function auditNarrowTabs(styles) {
  const css = sheet(styles, 'css/65-tabs.css');
  const narrow = mediaBody(css, 'max-width: 699px');
  if (narrow === null) return 'NO - no @media (max-width: 699px) block in css/65-tabs.css';
  const missing = [
    !sets(rule(narrow, '#tabs .tab:not(.active)'), 'display', 'none') && 'the inactive tabs are not hidden',
    !sets(rule(narrow, '.tabs-more'), 'display', 'flex') && '.tabs-more is not shown',
    !sets(rule(css, '.tabs-more'), 'display', 'none') && '.tabs-more is drawn at every width',
  ].filter(Boolean);
  return missing.length ? `NO - ${missing.join('; ')}` : 'yes (below 700px: the active tab and views ▾; above, no menu)';
}

/**
 * Nothing sticky in the list pane stops above the scope bar (`web-ui.md`). Every selector declared
 * `position: sticky`, other than `.scope-bar` itself, must take any `top` it declares — in any file,
 * plain or inside `@media` — from `--sticky-top`, bare or inside `calc()`. Read from the text because
 * jsdom has no layout, so a computed check would pass whatever the sheet says.
 *
 * A heuristic over selector text, not the cascade: among what it misses are a `top` set for the same
 * element through another selector, and an `inset` shorthand. It fails when it found no sticky rule,
 * because then it checked nothing.
 */
function auditStickyTop(styles) {
  if (!styles.length) return 'NO - no stylesheets to read';
  const rules = [];
  const walk = (file, text, query) => {
    for (const { prelude, body } of cssBlocks(text)) {
      if (/^@media\b/i.test(prelude)) { walk(file, body, prelude); continue; }
      if (prelude.startsWith('@')) continue;
      const decls = uncomment(body).split(';').map((d) => d.match(/^\s*([a-zA-Z-]+)\s*:\s*([\s\S]*?)\s*$/)).filter(Boolean)
        .map(([, name, value]) => [name.toLowerCase(), value]);
      for (const selector of selectorList(prelude)) rules.push({ file, query, selector, decls });
    }
  };
  for (const { file, text } of styles) walk(file, text, null);
  const sticky = new Set(rules.filter((r) => r.decls.some(([n, v]) => n === 'position' && /^sticky\b/.test(v)))
    .map((r) => r.selector).filter((s) => s !== '.scope-bar'));
  if (!sticky.size) return 'NO - found no sticky rule, so this checked nothing';
  const fromStickyTop = /^(var\(--sticky-top\b[^)]*\)|calc\([\s\S]*var\(--sticky-top\b[\s\S]*\))$/;
  let tops = 0;
  const offenders = [];
  for (const r of rules) {
    if (!sticky.has(r.selector)) continue;
    for (const [name, value] of r.decls) {
      if (name !== 'top') continue;
      tops++;
      if (!fromStickyTop.test(value)) offenders.push(`${r.selector} { top: ${value} }${r.query ? ` in ${r.query}` : ''} (${r.file})`);
    }
  }
  return offenders.length
    ? `NO - sticky, and stops above the scope bar: ${offenders.join('; ')}`
    : `yes (${sticky.size} sticky selector(s), ${tops} top(s), each from --sticky-top)`;
}

/**
 * Below 1200px the closed drawer is out of the tab order and the page's width, and casts no shadow:
 * the narrow block in `35-list.css` makes the closed `#detail-pane` `visibility: hidden`, clips
 * `#panes`, and draws `box-shadow` on `#detail-pane` only under `.drawer-open`. The behaviour is
 * Chrome's to judge (`room <w> tab never enters closed`, `room <w> doc width closed` in
 * `measure-web-ui.js`); this reads that the rules are where the decision put them.
 */
function auditClosedDrawer(styles) {
  const css = sheet(styles, 'css/35-list.css');
  const narrow = mediaBody(css, 'max-width: 1199px');
  if (narrow === null) return 'NO - no @media (max-width: 1199px) block in css/35-list.css';
  const shadows = cssBlocks(narrow).filter(({ prelude, body }) => /#detail-pane/.test(prelude) && /(^|;)\s*box-shadow\s*:/.test(body.trim()));
  const missing = [
    !sets(rule(narrow, '#detail-pane'), 'visibility', 'hidden') && 'the closed #detail-pane is not visibility: hidden',
    !sets(rule(narrow, '#panes.drawer-open #detail-pane'), 'visibility', 'visible') && 'the open #detail-pane is not visibility: visible',
    !sets(rule(narrow, '#panes'), 'overflow', 'clip') && '#panes is not overflow: clip',
    !shadows.length && 'no #detail-pane rule draws the shadow',
    shadows.some(({ prelude }) => !/\.drawer-open/.test(prelude)) && 'a box-shadow applies to the closed #detail-pane',
  ].filter(Boolean);
  return missing.length ? `NO - ${missing.join('; ')}` : 'yes (closed: visibility hidden, #panes clipped; shadow only under .drawer-open)';
}

/**
 * Below 520px a row keeps its path's last segment, its byte count and its repeat mark whole: the
 * `max-width: 519px` blocks hide the clock and the long repeat mark and show the short one
 * (`55-duplicates.css`, where those rules are), and the last segment does not shrink
 * (`35-list.css`). `room 400 rows` in `measure-web-ui.js` measures the rows themselves.
 */
function auditNarrowRow(styles) {
  const dup = mediaBody(sheet(styles, 'css/55-duplicates.css'), 'max-width: 519px');
  const list = mediaBody(sheet(styles, 'css/35-list.css'), 'max-width: 519px');
  if (dup === null || list === null) return `NO - no @media (max-width: 519px) block in ${dup === null ? 'css/55-duplicates.css' : 'css/35-list.css'}`;
  const missing = [
    !sets(rule(dup, '.row .clock'), 'display', 'none') && 'the clock is drawn',
    !sets(rule(dup, '.dup-long'), 'display', 'none') && '.dup-long is drawn',
    !sets(rule(dup, '.dup-short'), 'display', 'inline') && '.dup-short is not shown',
    !sets(rule(list, '.row .path-tail'), 'flex', 'none') && '.path-tail can shrink',
  ].filter(Boolean);
  return missing.length ? `NO - ${missing.join('; ')}` : 'yes (below 520px: no clock, the repeat mark as N×, the last segment never shrinks)';
}

/**
 * While the whole-session copy is read the overview line holds its band alone, as tall as the axis
 * it stands in for, so rows move only by the scope bar's height when the axis takes its place
 * (KIN-42). `.axis-hint.axis-reading` sets a `min-height` equal to `.axis-svg`'s `height`, both in
 * `css/25-axis.css`. Without it rows moved 44 px, and `measure-web-ui.js`, which CI does not run,
 * was the only other reader (KIN-110). `copy lands huge` there measures the move itself.
 */
function auditOverviewBand(styles) {
  const css = sheet(styles, 'css/25-axis.css');
  const px = (body, property) => {
    const m = new RegExp(`(?:^|;)\\s*${property}\\s*:\\s*(\\d+(?:\\.\\d+)?)px\\s*(?:;|$)`).exec(body.trim());
    return m ? Number(m[1]) : null;
  };
  const axis = px(rule(css, '.axis-svg'), 'height');
  const band = px(rule(css, '.axis-hint.axis-reading'), 'min-height');
  if (axis === null) return 'NO - .axis-svg sets no height in px in css/25-axis.css';
  if (band === null) return 'NO - .axis-hint.axis-reading sets no min-height in px: the band collapses while the copy is read';
  if (band !== axis) return `NO - .axis-hint.axis-reading's min-height is ${band}px, the axis is ${axis}px`;
  return `yes (the reading band holds ${band}px, the axis's height)`;
}

module.exports = { auditNarrowTopBar, auditNarrowTabs, auditStickyTop, auditClosedDrawer, auditNarrowRow, auditOverviewBand };
