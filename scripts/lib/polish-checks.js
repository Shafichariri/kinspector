'use strict';
/* The web UI polish rules, read as text from the real files (`check-web-static.js`, `--- polish ---`).
 *
 * Text heuristics, not the cascade, like `lib/layout-audits.js`: they read that a rule is where the
 * decision put it and that nothing else takes it over by name. Among what they miss is a rule that
 * reaches a control through a selector naming none of its classes or ids. Chrome is the real check
 * (`polish <w> …` in `measure-web-ui.js`, `lib/polish-cases.js`). Zero dependencies.
 */
const { HarnessError, cssBlocks, selectorList } = require('./web-audits');
const { loadIndexHtml } = require('./web-sources');

const uncomment = (text) => text.replace(/\/\*[\s\S]*?\*\//g, '');

/** Every plain rule in [styles] in cascade order, `@media` bodies walked: `{ file, query, selector, decls }`. */
function rulesInOrder(styles) {
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
  return rules;
}

const token = (styles, name) => {
  const tokens = styles.find((s) => s.file === 'css/00-tokens.css');
  if (!tokens) throw new HarnessError('css/00-tokens.css is not in web/styles.txt; update polish-checks.js');
  const m = uncomment(tokens.text).match(new RegExp(`${name}\\s*:\\s*([^;]+);`));
  return m ? m[1].trim() : null;
};

// The icon controls by every name a stylesheet could reach them through. `.detail-tools .btn` is the
// rule A1 replaced, which reached them through their row.
const ICON_NAMES = /(\.btn-icon|[.#]more-button|[.#]detail-full|[.#]detail-hide|[.#]drawer-close|\.row-delete)(?![\w-])/;
const SIZES = ['font-size', 'line-height', 'min-height', 'min-width', 'padding', 'padding-top', 'padding-right',
  'padding-bottom', 'padding-left', 'padding-block', 'padding-inline', 'height', 'width'];

/**
 * `--target-min` is at least 24px; `.btn.btn-icon` takes `min-width` and `min-height` from it; and no
 * other rule, in any file or `@media` block, sizes an icon control by name. `⋮`'s 13px rule lost the
 * cascade to `.btn-sm` and nobody saw, because the rule was there to read, so a second rule that sizes
 * these is refused whichever of the two would win.
 */
function auditIconTargets(styles) {
  const min = token(styles, '--target-min');
  const px = min && /^(\d+(?:\.\d+)?)px$/.exec(min);
  if (!px) return `NO - --target-min is ${min === null ? 'not declared in css/00-tokens.css' : `"${min}", not a px size`}`;
  if (Number(px[1]) < 24) return `NO - --target-min is ${min}, under WCAG 2.2's 24px`;
  const rules = rulesInOrder(styles);
  const icon = rules.filter((r) => r.selector === '.btn.btn-icon');
  if (!icon.length) return 'NO - found no .btn.btn-icon rule, so this checked nothing';
  const from = (name) => icon.some((r) => r.decls.some(([n, v]) => n === name && /^var\(--target-min\)$/.test(v)));
  const missing = ['min-width', 'min-height'].filter((n) => !from(n));
  if (missing.length) return `NO - .btn.btn-icon does not take ${missing.join(' and ')} from var(--target-min)`;
  const naming = rules.filter((r) => r.selector !== '.btn.btn-icon' && !/\ssvg$/.test(r.selector)
    && (ICON_NAMES.test(r.selector.split(/\s+|>|\+|~/).filter(Boolean).pop()) || /\.detail-tools\s+\.btn$/.test(r.selector)));
  const offenders = naming.flatMap((r) => r.decls.filter(([n]) => SIZES.includes(n))
    .map(([n, v]) => `${r.selector} { ${n}: ${v} }${r.query ? ` in ${r.query}` : ''} (${r.file})`));
  return offenders.length
    ? `NO - sized outside .btn.btn-icon: ${offenders.join('; ')}`
    : `yes (--target-min ${min}; .btn.btn-icon sizes them alone, ${naming.length} other rules naming an icon control set no size)`;
}

/** The text of [id]'s element in index.html, from its opening tag to the close of its tag name. */
function element(html, id) {
  const open = new RegExp(`<(a|button)\\b[^>]*\\bid="${id}"[^>]*>`).exec(html);
  if (!open) return null;
  const close = html.indexOf(`</${open[1]}>`, open.index + open[0].length);
  return close === -1 ? null : { tag: open[0], inner: html.slice(open.index + open[0].length, close) };
}

/**
 * The tools row's `⤢`, `⇥` and `✕` in index.html carry `btn-icon` and hold one `<svg aria-hidden="true">`
 * and no text; `⋮` is built with `btn-icon` and drawn as an SVG; the Sessions row's `✕` carries
 * `btn-icon` and keeps its text, which arming swaps for `sure?`.
 */
function auditIconControls(scripts) {
  const html = loadIndexHtml();
  const bad = [];
  let seen = 0;
  for (const id of ['detail-full', 'detail-hide', 'drawer-close']) {
    const e = element(html, id);
    if (!e) { bad.push(`no #${id} in index.html`); continue; }
    seen++;
    const svgs = e.inner.match(/<svg\b[^>]*>/g) || [];
    const text = e.inner.replace(/<svg\b[\s\S]*?<\/svg>/g, '').replace(/<!--[\s\S]*?-->/g, '').trim();
    if (!/\bclass="[^"]*\bbtn-icon\b/.test(e.tag)) bad.push(`#${id} has no btn-icon`);
    if (svgs.length !== 1 || !/aria-hidden="true"/.test(svgs[0])) bad.push(`#${id} holds ${svgs.length} svg, ${svgs.length === 1 ? 'not aria-hidden' : 'not one'}`);
    if (text) bad.push(`#${id} holds text "${text}"`);
  }
  const copy = scripts.find((s) => s.file === 'js/40-copy-export.js');
  const sessions = scripts.find((s) => s.file === 'js/90-sessions.js');
  if (!copy || !sessions) throw new HarnessError('js/40-copy-export.js or js/90-sessions.js is not in web/scripts.txt; update polish-checks.js');
  const more = /className:\s*'([^']*\bmore-button\b[^']*)'/.exec(copy.text);
  if (!more) bad.push('found no ⋮ (more-button) in js/40-copy-export.js');
  else {
    seen++;
    if (!/\bbtn-icon\b/.test(more[1])) bad.push('⋮ is built without btn-icon');
    if (!/more\.button\.innerHTML\s*=\s*'<svg\b[^']*aria-hidden="true"/.test(copy.text)) bad.push('⋮ is not drawn as an aria-hidden svg');
  }
  const row = /el\('button',\s*'([^']*\brow-delete\b[^']*)'/.exec(sessions.text);
  if (!row) bad.push('found no row-delete button in js/90-sessions.js');
  else if (seen++, !/\bbtn-icon\b/.test(row[1])) bad.push('the Sessions row ✕ has no btn-icon');
  if (!seen) return 'NO - found no icon control, so this checked nothing';
  return bad.length ? `NO - ${bad.join('; ')}` : `yes (${seen} icon controls: ⤢ ⇥ ✕ and ⋮ as svg, the Sessions row ✕ as text, each btn-icon)`;
}

/**
 * A page document's buttons are at least `--target-min` tall (`.page-doc .btn`), and its prose stops
 * at `--prose-max` (`.page-doc .settings-note`), from tokens that say so. The traffic toolbar is not a
 * page document, so its height stays the band budget it was given (`room 1440 bands list`).
 */
function auditPageDoc(styles, selector, property, name, floor) {
  const value = token(styles, name);
  if (value === null) return `NO - ${name} is not declared in css/00-tokens.css`;
  if (!floor(value)) return `NO - ${name} is "${value}"`;
  const found = rulesInOrder(styles).filter((r) => r.selector === selector);
  if (!found.length) return `NO - found no ${selector} rule, so this checked nothing`;
  const takes = found.some((r) => r.decls.some(([n, v]) => n === property && v === `var(${name})`));
  return takes ? `yes (${selector} takes ${property} from ${name}, ${value})` : `NO - ${selector} does not take ${property} from var(${name})`;
}

/** The lines `check-web-static.js` prints under `--- polish ---`. */
function checkPolish(scripts, styles) {
  return [
    ['icon target css', auditIconTargets(styles)],
    ['icon controls svg', auditIconControls(scripts)],
    ['page targets css', auditPageDoc(styles, '.page-doc .btn', 'min-height', '--target-min', (v) => /^(\d+)px$/.test(v) && parseFloat(v) >= 24)],
    ['prose width css', auditPageDoc(styles, '.page-doc .settings-note', 'max-width', '--prose-max', (v) => /^\d+ch$/.test(v) && parseFloat(v) <= 80)],
  ];
}

module.exports = { checkPolish, rulesInOrder };
