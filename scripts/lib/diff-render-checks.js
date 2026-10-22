/* The Diff page's drawing, against canned daemon replies.
 *
 * The page decides nothing (`js/96-diff.js`): the order, the kinds, the loud mark, the verdicts and
 * why a body shape could not be compared are the daemon's. These hold that it draws them as sent:
 * every kind as its tag in the reply's order, a direction the daemon could not compare as its `why`
 * and never as "removed", a latency verdict printed rather than recomputed, and of two reads the one
 * sent last drawn whatever order they land in. The live probe's `diff …` lines hold the page against
 * the fixture; these hold what that fixture cannot set up, such as a reply contradicting itself.
 *
 * The real files run in their own context (`00-core.js` for `el` and the formatters, `08-routes.js`,
 * `90-sessions.js` for the session labels, `95-stats.js` for the route pieces, and `96-diff.js`),
 * over a stub DOM just large enough for what they call, so this needs nothing installed, like the
 * rest of `check-web-static.js`. The page's own `wireDiffPage` builds the controls it draws into.
 */
const vm = require('vm');
const { runFile } = require('./session-state-checks');
const { HarnessError } = require('./web-audits');

const FILES = ['js/00-core.js', 'js/08-routes.js', 'js/90-sessions.js', 'js/95-stats.js', 'js/96-diff.js'];
const pause = (ms) => new Promise((r) => setTimeout(r, ms));

/** A stand-in for a DOM node: children, text, classes, attributes, listeners and the few moves the page makes. */
class StubNode {
  constructor(tag, text) {
    Object.assign(this, { tagName: tag.toUpperCase(), children: [], parentNode: null, attrs: {}, dataset: {}, listeners: {} });
    Object.assign(this, { className: '', id: '', hidden: false, title: '', own: text === undefined ? '' : String(text) });
  }
  get classList() {
    const names = () => this.className.split(/\s+/).filter(Boolean);
    return { contains: (c) => names().includes(c) };
  }
  get textContent() { return this.own + this.children.map((c) => c.textContent).join(''); }
  set textContent(text) { this.replaceChildren(); this.own = String(text); }
  appendChild(child) {
    if (child.parentNode) child.remove();
    child.parentNode = this;
    this.children.push(child);
    return child;
  }
  append(...items) { for (const item of items) this.appendChild(typeof item === 'string' ? new StubNode('#text', item) : item); }
  replaceChildren(...items) {
    for (const child of this.children) child.parentNode = null;
    this.children = [];
    this.own = '';
    this.append(...items);
  }
  remove() {
    if (!this.parentNode) return;
    const siblings = this.parentNode.children;
    siblings.splice(siblings.indexOf(this), 1);
    this.parentNode = null;
  }
  get nextSibling() {
    const siblings = this.parentNode ? this.parentNode.children : [];
    return siblings[siblings.indexOf(this) + 1] || null;
  }
  after(node) {
    if (node.parentNode) node.remove();
    const siblings = this.parentNode.children;
    siblings.splice(siblings.indexOf(this) + 1, 0, node);
    node.parentNode = this.parentNode;
  }
  setAttribute(name, value) { this.attrs[name] = String(value); }
  getAttribute(name) { return name in this.attrs ? this.attrs[name] : null; }
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
  click() { for (const fn of this.listeners.click || []) fn({}); }
  /** Every node below this one, depth first. */
  all() { return this.children.flatMap((c) => [c, ...c.all()]); }
  /** Descendants with every class in [cls], a `.a.b` selector without the dots. */
  byClass(...cls) { return this.all().filter((n) => cls.every((c) => n.classList.contains(c))); }
}

/** The page over [api], wired by its own `wireDiffPage`, with two sessions in the list. */
function page(sources, api) {
  const body = new StubNode('body');
  body.dataset.page = 'diff';
  const section = body.appendChild(new StubNode('section'));
  section.id = 'page-diff';
  const document = {
    body,
    createElement: (tag) => new StubNode(tag),
    getElementById: (id) => body.all().find((n) => n.id === id) || null,
  };
  const context = runFile(sources, FILES, { window: {}, document, api, clearTimeout, localStorage: null });
  for (const name of ['wireDiffPage', 'diffRead', 'diffRender']) {
    if (typeof context[name] !== 'function') throw new HarnessError(`${name} is not declared in js/96-diff.js`);
  }
  const state = vm.runInContext('state', context);
  Object.assign(state, { sessionsLoaded: true, recording: [], sessions: [
    { sessionId: 'S-A', appId: 'app', startedAt: '2026-01-01T09:00:00Z', endedAt: '2026-01-01T09:10:00Z', txnCount: 3 },
    { sessionId: 'S-B', appId: 'app', startedAt: '2026-01-02T09:00:00Z', endedAt: '2026-01-02T09:10:00Z', txnCount: 4 },
  ] });
  context.wireDiffPage();
  const diffState = vm.runInContext('diffState', context);
  const $ = document.getElementById;
  const rows = () => $('diff-rows').children.filter((tr) => tr.classList.contains('diff-row'));
  const row = (route) => rows().find((tr) => tr.dataset.route === route) || null;
  // Cells by the table's own header text, so a column moved is still read by what it says it is.
  const cell = (tr, head) => tr.children[$('diff-head').children.findIndex((th) => th.textContent === head)];
  const open = (tr) => {
    tr.byClass('diff-open')[0].click();
    return tr.nextSibling && tr.nextSibling.classList.contains('diff-detail') ? tr.nextSibling : null;
  };
  return { context, state, diffState, $, rows, row, cell, open };
}

// --- canned replies ---------------------------------------------------------------------------------

const tally = (kind, counts = {}) => ({ kind, complete: 0, empty: 0, truncated: 0, omitted: 0, missing: 0, notJson: 0, unparsed: 0, unread: 0, ...counts });
const shape = (more = {}) => ({ compared: true, a: tally('json', { complete: 2 }), b: tally('json', { complete: 2 }),
  added: [], removed: [], typeChanged: [], unsure: [], notCompared: [], ...more });
const side = (calls, more = {}) => ({ calls, attempts: calls, latencySamples: calls, p50Ms: 100, filterTerm: 'x', ...more });

/** A route row as `GET /api/diff` sends it; [on] says which sides it is on. */
function route(method, path, changes, { on = 'both', loud = false, ...more } = {}) {
  const both = on === 'both';
  return {
    method, scheme: 'https', host: 'api.example.invalid', route: path, routeSource: 'heuristic', presence: on,
    mutation: method !== 'GET', loud, changes,
    ...(both || on === 'removed' ? { a: side(2) } : {}), ...(both || on === 'added' ? { b: side(2) } : {}),
    status: { ...(both || on === 'removed' ? { a: { 200: 2 } } : {}), ...(both || on === 'added' ? { b: { 200: 2 } } : {}) },
    ...(both ? {
      latency: { verdict: 'same', p50A: 100, p50B: 100, deltaMs: 0, deltaPct: 0 },
      requestSize: { verdict: 'same', meanA: 10, meanB: 10 }, responseSize: { verdict: 'same', meanA: 10, meanB: 10 },
      requestHeaders: { added: [], removed: [] }, responseHeaders: { added: [], removed: [] },
      requestShape: shape(), responseShape: shape(),
    } : {}),
    ...more,
  };
}

function report(routes, extra = {}) {
  const changed = routes.filter((r) => r.changes.length).length;
  return {
    a: { sessionId: 'S-A', startedAt: '2026-01-01T09:00:00Z', endedAt: '2026-01-01T09:10:00Z', total: 3, matched: 3, filterCarried: true, markers: [] },
    b: { sessionId: 'S-B', startedAt: '2026-01-02T09:00:00Z', endedAt: '2026-01-02T09:10:00Z', total: 4, matched: 4, filterCarried: true, markers: [] },
    aChosen: 'given', sameApp: true, filter: '', routeCount: routes.length,
    counts: { added: 0, removed: 0, changed, unchanged: routes.length - changed },
    shapeBudget: { bodiesRead: 0, bytesRead: 0, limitBytes: 1, exhausted: false }, routes, ...extra,
  };
}

/** Draws [reply] through the page's own read. */
async function drawn(sources, reply) {
  const p = page(sources, async () => reply);
  await p.context.diffRead();
  return p;
}

// --- checks ---------------------------------------------------------------------------------------

/**
 * Every kind the daemon names becomes its tag, in the reply's order, a loud row carries `.diff-loud`,
 * and the rows are drawn in the reply's order, which no sort on the page reproduces. Unchanged rows
 * are drawn once the toggle shows them, still in that order.
 */
async function checkDrawsKinds(sources) {
  const routes = [
    route('POST', '/v1/orders', ['statusMix', 'calls'], { loud: true }),
    route('DELETE', '/v1/cart', ['removed'], { on: 'removed', loud: true }),
    route('GET', '/v1/zeta', ['removed'], { on: 'removed' }),
    route('GET', '/v1/alpha', ['added'], { on: 'added' }),
    route('GET', '/v1/m', ['slower', 'responseShape', 'requestShape', 'responseHeaders', 'requestHeaders', 'responseSize', 'requestSize']),
    route('PUT', '/v1/b', ['faster', 'aKindNotYetNamed']),
    route('GET', '/v1/c', []),
  ];
  const p = await drawn(sources, report(routes));
  const wrong = [];
  const due = routes.filter((r) => r.changes.length).map((r) => r.route);
  const order = () => p.rows().map((tr) => tr.dataset.route);
  if (!p.rows().length) return 'NO - found no drawn row, so this checked nothing';
  if (order().join() !== due.join()) wrong.push(`rows drawn ${order().join(' ')}, the reply's order is ${due.join(' ')}`);
  let tags = 0;
  for (const r of routes.filter((x) => x.changes.length)) {
    const tr = p.row(r.route);
    if (!tr) continue;
    const kinds = tr.byClass('diff-tag').filter((t) => t.dataset.kind).map((t) => [t.dataset.kind, t.textContent]);
    tags += kinds.length;
    if (kinds.map(([k]) => k).join() !== r.changes.join()) wrong.push(`${r.route} tags ${kinds.map(([k]) => k).join(' ')}, sent ${r.changes.join(' ')}`);
    if (kinds.some(([, text]) => !text)) wrong.push(`${r.route} has a tag with no words`);
    if (tr.classList.contains('diff-loud') !== r.loud) wrong.push(`${r.route} loud ${r.loud}, drawn ${tr.classList.contains('diff-loud')}`);
  }
  const words = (path, kind) => p.row(path).byClass('diff-tag').find((t) => t.dataset.kind === kind)?.textContent;
  if (words('/v1/alpha', 'added') !== 'route added' || words('/v1/zeta', 'removed') !== 'route removed') {
    wrong.push(`presence reads "${words('/v1/alpha', 'added')}" and "${words('/v1/zeta', 'removed')}", "route added" and "route removed" due`);
  }
  if (words('/v1/b', 'aKindNotYetNamed') !== 'aKindNotYetNamed') wrong.push('a kind the page has no word for was not printed as sent');
  p.$('diff-unchanged-toggle').click();
  if (order().join() !== routes.map((r) => r.route).join()) wrong.push(`with unchanged shown, rows drawn ${order().join(' ')}`);
  return wrong.length ? `NO - ${wrong.join('; ')}`
    : `yes (${due.length} changed rows in the reply's order, ${tags} tags for 13 kinds, 2 loud rows marked; the unchanged one in place once shown)`;
}

/**
 * A direction the daemon did not compare reads `unknown` with its `why` as the cell's title, and its
 * detail prints the `why`; "removed" is in none of that row's cells or its detail. Keys the daemon was
 * unsure of are listed under the unknown heading, and the removed list is not drawn for them.
 */
async function checkUnknownVerbatim(sources) {
  const why = 'A kept no complete JSON body here: 1 truncated';
  const truncated = route('GET', '/v1/feed', [], {
    responseShape: shape({ compared: false, why, a: tally('unknown', { truncated: 1 }), b: tally('json', { complete: 1 }) }),
  });
  const unsure = route('GET', '/v1/page', ['statusMix'], {
    responseShape: shape({ b: tally('json', { complete: 2, truncated: 1 }),
      unsure: [{ path: '$.next', would: 'removed', inA: 2, ofA: 2, inB: 0, ofB: 2 }] }),
  });
  const p = await drawn(sources, report([unsure, truncated]));
  p.$('diff-unchanged-toggle').click();
  const feed = p.row('/v1/feed');
  const page2 = p.row('/v1/page');
  if (!feed || !page2) return `NO - found no ${feed ? '/v1/page' : '/v1/feed'} row, so this checked nothing`;
  const wrong = [];
  const shapeCell = p.cell(feed, 'response shape');
  if (shapeCell.textContent !== 'unknown' || shapeCell.title !== why) wrong.push(`the feed's shape cell reads "${shapeCell.textContent}", title "${shapeCell.title}"`);
  const detail = p.open(feed);
  if (!detail) wrong.push('the feed row opened no detail');
  else if (!detail.byClass('diff-why').some((n) => n.textContent === why)) wrong.push('the feed\'s detail does not print the daemon\'s why');
  const said = [...feed.children, ...(detail ? [detail] : [])].filter((n) => /removed/.test(n.textContent));
  if (said.length) wrong.push(`"removed" is drawn about the truncated feed: "${said[0].textContent.slice(0, 80)}"`);
  const pageDetail = p.open(page2);
  const lists = pageDetail ? pageDetail.byClass('diff-list') : [];
  const under = (list) => lists.filter((ul) => ul.classList.contains(`diff-list-${list}`)).map((ul) => ul.textContent).join(' ');
  const heading = pageDetail && pageDetail.byClass('diff-list-title').find((h) => h.nextSibling && h.nextSibling.classList.contains('diff-list-unsure'));
  if (!under('unsure').includes('$.next')) wrong.push(`$.next is not under the unknown list (unsure: "${under('unsure')}")`);
  if (under('removed').includes('$.next')) wrong.push('$.next is drawn in the removed list');
  if (!heading || !/^unknown/.test(heading.textContent)) wrong.push(`the unsure keys' heading reads "${heading ? heading.textContent : 'nothing'}"`);
  return wrong.length ? `NO - ${wrong.join('; ')}`
    : `yes (the truncated direction reads unknown with the daemon's why, no "removed" in its row or detail; $.next under "${heading.textContent}")`;
}

/**
 * The p50 cell prints the daemon's verdict, never one the page reached by counting samples: a reply
 * whose verdict contradicts its own sample counts is drawn as the verdict says, and a `reason` this
 * build has never seen is printed as sent.
 */
async function checkVerdictVerbatim(sources) {
  const judged = route('GET', '/v1/four', ['slower'], {
    a: side(4, { latencySamples: 4 }), b: side(4, { latencySamples: 4, p50Ms: 160 }),
    latency: { verdict: 'slower', p50A: 100, p50B: 160, deltaMs: 60, deltaPct: 60 },
  });
  const withheld = route('GET', '/v1/nine', ['calls'], {
    a: side(9, { latencySamples: 9 }), b: side(9, { latencySamples: 9, p50Ms: 400 }),
    latency: { verdict: 'notJudged', reason: 'n<5', p50A: 100, p50B: 400, deltaMs: 300, deltaPct: 300 },
  });
  const unseen = route('GET', '/v1/seven', ['calls'], {
    latency: { verdict: 'notJudged', reason: 'n<7 on a rule this build has not seen', p50A: 100, p50B: 100, deltaMs: 0 },
  });
  const p = await drawn(sources, report([judged, withheld, unseen]));
  const verdict = (path) => {
    const tr = p.row(path);
    const node = tr && p.cell(tr, 'p50 A → B').byClass('diff-verdict')[0];
    return node ? node.textContent : null;
  };
  const got = { four: verdict('/v1/four'), nine: verdict('/v1/nine'), seven: verdict('/v1/seven') };
  if (got.four === null && got.nine === null) return 'NO - found no verdict drawn, so this checked nothing';
  const due = { four: 'slower +60 ms (+60%)', nine: 'n<5', seven: 'n<7 on a rule this build has not seen' };
  const wrong = Object.keys(due).filter((k) => got[k] !== due[k]).map((k) => `${k}: "${got[k]}" where "${due[k]}" was due`);
  return wrong.length ? `NO - ${wrong.join('; ')}`
    : 'yes (slower at n=4 and n<5 at n=9 drawn as the reply says, an unseen reason printed as sent)';
}

/**
 * Two reads of the same comparison, released newest first: the newer is drawn and stays drawn. A
 * single read is drawn too, so the check cannot pass on a page that draws nothing.
 */
async function checkStaleDropped(sources) {
  const held = [];
  const p = page(sources, () => new Promise((answer) => held.push(answer)));
  const first = p.context.diffRead();
  const second = p.context.diffRead();
  await pause(0);
  if (held.length !== 2) return `NO - ${held.length} reads were sent where 2 were due`;
  held[1](report([route('GET', '/v1/newer', ['calls'])]));
  await second;
  held[0](report([route('GET', '/v1/older', ['calls'])]));
  await first;
  const shown = p.rows().map((tr) => tr.dataset.route).join();
  const comparing = p.$('diff-status').textContent;
  const one = await drawn(sources, report([route('GET', '/v1/only', ['calls'])]));
  const single = one.rows().map((tr) => tr.dataset.route).join();
  return shown === '/v1/newer' && !comparing && single === '/v1/only'
    ? 'yes (of two reads released newest first, the newer stayed drawn; a single read was drawn)'
    : `NO - drawn "${shown}" (status "${comparing}") after the older landed last; a single read drew "${single}"`;
}

async function checkDiffRender(sources) {
  return [
    ['diff draws kinds', await checkDrawsKinds(sources)],
    ['diff unknown verbatim', await checkUnknownVerbatim(sources)],
    ['diff verdict verbatim', await checkVerdictVerbatim(sources)],
    ['diff stale dropped', await checkStaleDropped(sources)],
  ];
}

module.exports = { checkDiffRender };
