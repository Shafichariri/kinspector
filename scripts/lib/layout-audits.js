/* Stylesheet audits of what the page draws at a width, read as text from the files in
 * `web/styles.txt`. `lib/web-audits.js` holds the older audits and is near its size limit; these
 * start here, and later layout audits join them.
 *
 * Text heuristics, not a cascade: they read the rule a decision rests on where the decision put
 * it, and among what they miss is a later rule elsewhere undoing it. Chrome is the real check
 * (`shell <width> …` in `measure-web-ui.js`). Zero dependencies, like the rest of
 * `check-web-static.js`.
 */
const { HarnessError } = require('./web-audits');

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

module.exports = { auditNarrowTopBar, auditNarrowTabs };
