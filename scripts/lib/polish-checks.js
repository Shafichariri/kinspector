'use strict';
/* The web UI polish rules, read as text from the real files (`check-web-static.js`, `--- polish ---`).
 *
 * Text heuristics, not the cascade, like `lib/layout-audits.js`: they read that a rule is where the
 * decision put it and that nothing else takes it over by name. Among what they miss is a rule that
 * reaches a control through a selector naming none of its classes or ids. Chrome is the real check
 * (`polish <w> …` in `measure-web-ui.js`, `lib/polish-cases.js`). Zero dependencies.
 */
const vm = require('vm');
const { HarnessError, cssBlocks, selectorList } = require('./web-audits');
const { loadIndexHtml } = require('./web-sources');
const { runFile } = require('./session-state-checks');

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

/** The real `js/08-routes.js` with `ARCHIVE` as given and, when [stubs], a `PAGES` row for Diff and Findings. */
function routes(scripts, archive, stubs) {
  const context = runFile(scripts, 'js/08-routes.js', { ARCHIVE: archive });
  for (const name of ['readPageKey', 'pageKeyAvailable', 'typingTarget']) {
    if (typeof context[name] !== 'function') throw new HarnessError(`${name} is not declared in js/08-routes.js; update polish-checks.js`);
  }
  // `PAGES` is a `const`, in the script scope every file run in this context shares, so a second
  // script reaches it as the pages' own items will: a row in the table, and nothing else.
  if (stubs) new vm.Script(`PAGES.push({ id: 'diff', label: 'Diff', live: true }, { id: 'findings', label: 'Findings', live: false })`).runInContext(context);
  return context;
}

/**
 * `g` then a letter goes to its page within 1500 ms, `g g` is the top, any other second key is spent,
 * and a letter whose page is not in `PAGES`, or is live in an export, goes nowhere. Each sequence is
 * `[key, ms]` pairs read from no prefix; what is judged is the last key's reading.
 */
function checkPageKeys(scripts) {
  const run = (context, keys) => {
    let pending = null;
    let read = null;
    for (const [key, at] of keys) ({ pending } = read = context.readPageKey(pending, key, at));
    return { page: read.page ?? null, top: Boolean(read.top), consumed: Boolean(read.consumed), pending: read.pending };
  };
  const g = (second, at = 100) => [['g', 0], [second, at]];
  const cases = [
    // [archive, stub rows, keys, page due, top due, what it shows]
    [null, false, g('s'), 'stats', false, 'g s'],
    [null, false, g('g'), null, true, 'g g'],
    [null, false, g('s', 1501), null, false, 'g, 1501 ms, s'],
    [null, false, g('j'), null, false, 'g j'],
    [null, false, [...g('j'), ['s', 200]], null, false, 'g j s (the prefix spent)'],
    [null, false, [['g', 0], ['Shift', 50], ['s', 100]], 'stats', false, 'g Shift s'],
    [null, false, g('t'), 'traffic', false, 'g t'],
    [null, false, g('a'), 'sessions', false, 'g a'],
    [null, false, g('p'), 'processes', false, 'g p'],
    [null, false, g(','), 'settings', false, 'g ,'],
    [null, false, g('d'), null, false, 'g d with no diff row'],
    [null, false, g('f'), null, false, 'g f with no findings row'],
    [null, true, g('d'), 'diff', false, 'g d over a stub row'],
    [null, true, g('f'), 'findings', false, 'g f over a stub row'],
    [{}, false, g('a'), null, false, 'g a in an export'],
    [{}, false, g('p'), null, false, 'g p in an export'],
    [{}, false, g('s'), 'stats', false, 'g s in an export'],
    [{}, true, g('f'), 'findings', false, 'g f over a stub row in an export'],
    [{}, true, g('d'), null, false, 'g d over a stub row in an export'],
  ];
  const wrong = [];
  for (const [archive, stubs, keys, page, top, what] of cases) {
    const got = run(routes(scripts, archive, stubs), keys);
    if (got.page !== page || got.top !== top) wrong.push(`${what} gave ${JSON.stringify({ page: got.page, top: got.top })}, not ${JSON.stringify({ page, top })}`);
  }
  // The second key is spent whatever it is, and a lone key with no prefix is the list's.
  const plain = routes(scripts, null, false);
  const spent = run(plain, g('j'));
  if (!spent.consumed || spent.pending !== null) wrong.push(`g j left ${JSON.stringify(spent)}, where the j is spent and the prefix cleared`);
  const lone = run(plain, [['j', 0]]);
  if (lone.consumed) wrong.push('j with no prefix was taken from the list');
  return wrong.length ? `NO - ${wrong.join('; ')}` : `yes (${cases.length} sequences, live and exported, with and without stub Diff and Findings rows)`;
}

/** `typingTarget` is every element that takes typing, and nothing that does not. */
function checkTypingTarget(scripts) {
  const { typingTarget } = routes(scripts, null, false);
  const takes = [{ tagName: 'INPUT' }, { tagName: 'SELECT' }, { tagName: 'TEXTAREA' }, { tagName: 'DIV', isContentEditable: true }];
  const not = [{ tagName: 'BUTTON' }, { tagName: 'BODY' }, { tagName: 'DIV', isContentEditable: false }, { tagName: 'A' }];
  const name = (e) => `${e.tagName.toLowerCase()}${e.isContentEditable ? '[contenteditable]' : ''}`;
  const wrong = [...takes.filter((e) => !typingTarget(e)).map((e) => `${name(e)} is not a typing target`),
    ...not.filter((e) => typingTarget(e)).map((e) => `${name(e)} is a typing target`)];
  return wrong.length ? `NO - ${wrong.join('; ')}` : `yes (${takes.map(name).join(', ')} take typing; ${not.map(name).join(', ')} do not)`;
}

/** The body of [name]'s declaration in [text], to the first line that closes it at column 0. */
function body(text, name) {
  const start = text.search(new RegExp(`^(?:async )?function ${name}\\(`, 'm'));
  if (start === -1) return null;
  const end = text.indexOf('\n}', start);
  return end === -1 ? null : text.slice(start, end);
}

/**
 * Why there are no calls is said once: `noCallsWhy` in the real `15-session-data.js` gives the list's
 * three sentences, a session that ended without `endedAt` (a killed daemon) included, and both the
 * list's empty state and the Stats page's ask it. Stats had its own copy and said "yet" of an ended
 * session, the claim `web-ui.md` took out of the list.
 */
function checkNoCallsWhy(scripts) {
  const run = (state) => {
    const { noCallsWhy } = runFile(scripts, 'js/15-session-data.js', { state });
    if (typeof noCallsWhy !== 'function') throw new HarnessError('noCallsWhy is not declared in js/15-session-data.js; update polish-checks.js');
    return noCallsWhy();
  };
  const cases = [
    [{ sessionId: null, recording: [] }, 'waiting for an app with inspector-stream to connect', 'no session'],
    [{ sessionId: 'S', recording: ['S'] }, 'the app is connected — no calls yet', 'recording'],
    [{ sessionId: 'S', recording: ['T'] }, 'no calls were recorded in this session', 'ended, no endedAt, another recording'],
    [{ sessionId: 'S', recording: undefined }, 'no calls were recorded in this session', 'recording not read yet'],
  ];
  const wrong = cases.map(([state, due, what]) => [run(state), due, what]).filter(([got, due]) => got !== due)
    .map(([got, due, what]) => `${what}: "${got}", not "${due}"`);
  const callers = [['js/25-traffic-list.js', 'renderListEmpty'], ['js/95-stats.js', 'statsEmptyWhy']];
  for (const [file, name] of callers) {
    const source = scripts.find((s) => s.file === file);
    if (!source) throw new HarnessError(`${file} is not in web/scripts.txt; update polish-checks.js`);
    const text = body(source.text, name);
    if (text === null) wrong.push(`found no ${name} in ${file}`);
    else if (!/\bnoCallsWhy\(\)/.test(text)) wrong.push(`${name} does not ask noCallsWhy()`);
    // A second copy of a sentence is the drift this rule exists to stop, worded the old way or the new.
    for (const said of [...cases.map(([, due]) => due), 'No calls in this session yet', 'No session yet']) {
      if (text && text.includes(said)) wrong.push(`${name} says "${said}" itself`);
    }
  }
  return wrong.length ? `NO - ${wrong.join('; ')}` : `yes (${cases.length} sessions; ${callers.map(([, n]) => n).join(' and ')} ask it and word none itself)`;
}

/** The lines `check-web-static.js` prints under `--- polish ---`. */
function checkPolish(scripts, styles) {
  return [
    ['page keys', checkPageKeys(scripts)],
    ['typing skips keys', checkTypingTarget(scripts)],
    ['icon target css', auditIconTargets(styles)],
    ['icon controls svg', auditIconControls(scripts)],
    ['page targets css', auditPageDoc(styles, '.page-doc .btn', 'min-height', '--target-min', (v) => /^(\d+)px$/.test(v) && parseFloat(v) >= 24)],
    ['prose width css', auditPageDoc(styles, '.page-doc .settings-note', 'max-width', '--prose-max', (v) => /^\d+ch$/.test(v) && parseFloat(v) <= 80)],
    ['no calls why', checkNoCallsWhy(scripts)],
  ];
}

module.exports = { checkPolish, rulesInOrder };
