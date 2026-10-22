/* Probes of the Diff page, live, for `render-web-ui.js`.
 *
 * The page decides nothing: the order, kinds, verdicts and why a shape was not compared are the
 * daemon's. So the probe compares what the page drew with what `GET /api/diff` answers the probe
 * itself, on the CI fixture (`ProbeFixture.kt`): the probe session's previous run is the 1203-call
 * long session, whose `/v3/long/bulk` (1201 calls, past the 1000 rows one page of `…/transactions`
 * holds) reads removed; and the baseline session, against the probe session, holds one route per
 * rule — `POST …/orders/submit` called 2 → 1 times, 200 → 500, `$.price` number → string on the
 * products detail, the feed truncated in the baseline, the token's redacted `$.expires_in` and its
 * new `x-request-id`, and stock's 5 → 4 attempts, too few to judge latency. On the Sessions page,
 * ticking two sessions offers `compare (2)` with the older as A, and a row's `diff` compares it with
 * its previous run; a route's `calls in A` and `calls in B` open Traffic on that side's session
 * through the Stats page's caveat, which then names Diff and goes back to the diff's address.
 *
 * The orders row is looked up, not read as the first row: the daemon puts loud rows first, and the
 * probe session's added `DELETE /v3/shop/cart/items` is loud too and sorts above it (PR A's order
 * rule, which `SessionDiffTest` pins). What is asserted is that the orders row is loud and that every
 * row above it is loud.
 *
 * `probeDiff` leaves the page on Traffic, on the session it found, with no filter and no caveat, and
 * checks that it did (`diff restored`), so the probe after it does not pass vacuously. Each returns
 * `[label, verdict]` pairs, one per line the caller prints.
 */

const { LONG_SESSION_ID, choose, waitFor } = require('./long-session-probes');

const BASE_SESSION_ID = '2025-12-30T09-00-00_probe_ci_debug';
const DIFF_LABELS = [
  'diff default pair', 'diff long session whole', 'diff explicit pair', 'diff mutation loud', 'diff status mix',
  'diff type changed', 'diff truncated unknown', 'diff redacted not compared', 'diff latency not judged',
  'diff header added', 'diff sessions compare', 'diff row action', 'diff opens calls', 'diff same session', 'diff restored',
];
const pause = (ms) => new Promise((r) => setTimeout(r, ms));

/** The page as drawn: rows by their route, a cell by its column's header, a row's detail opened. */
function drawn(window, doc) {
  const heads = [...doc.querySelectorAll('#diff-head th')].map((th) => th.textContent);
  const rows = [...doc.querySelectorAll('#diff-rows tr.diff-row')];
  const row = (method, route) => rows.find((tr) => tr.dataset.route === route && tr.dataset.method === method) || null;
  const cell = (tr, head) => (tr ? tr.children[heads.indexOf(head)] : null);
  const open = async (tr) => {
    if (!tr) return null;
    const button = tr.querySelector('.diff-open');
    if (button.getAttribute('aria-expanded') !== 'true') button.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    await pause(50);
    const next = tr.nextElementSibling;
    return next && next.classList.contains('diff-detail') ? next : null;
  };
  return { rows, row, cell, open };
}

const shown = (doc, page) => !doc.getElementById(`page-${page}`).hidden;
const text = (node) => (node ? node.textContent.replace(/\s+/g, ' ').trim() : '');
/** A detail's section for one direction, `request body` or `response body`. */
const direction = (detail, label) => [...(detail ? detail.querySelectorAll('.diff-dir') : [])]
  .find((section) => text(section.querySelector('.diff-dir-title')) === label) || null;
/** A detail's line by its label, without the label. */
const detailLine = (detail, label) => {
  const line = [...(detail ? detail.querySelectorAll('.diff-line') : [])].find((p) => text(p.querySelector('.diff-line-label')) === label);
  return line ? text(line).slice(label.length).trim() : '';
};

/** The live page: see the file's header. `ctx` is `render-web-ui.js`'s shell context. */
async function probeDiff(ctx) {
  const { window, doc, ORIGIN } = ctx;
  const out = new Map(DIFF_LABELS.map((label) => [label, 'n/a (not reached)']));
  const picker = doc.getElementById('session-picker');
  const sessionId = picker.value;
  const daemon = async (query = {}) => (await fetch(`${ORIGIN}/api/diff?${new URLSearchParams(query)}`)).json();
  const press = async (node, ms = 150) => {
    node.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    await pause(ms);
  };
  const rail = (page) => doc.querySelector(`#rail .rail-item[data-page="${page}"]`);
  // Read once the page has an answer for the pair asked: no read out, and a summary or a refusal drawn.
  const settled = (pair) => waitFor(() => doc.getElementById('diff-status').textContent === ''
    && (!doc.getElementById('diff-error').hidden || (doc.querySelector('#diff-summary .diff-side-b')
      && (!pair || doc.querySelector('#diff-summary .diff-side-b').dataset.session === pair.b))), 15000);
  const goTo = async (hash, pair) => {
    window.location.hash = hash;
    await pause(100);
    await settled(pair);
  };
  const hasBase = [...picker.options].some((o) => o.value === BASE_SESSION_ID);
  try {
    if (!sessionId || !hasBase) return [...out.keys()].map((label) => [label, `n/a (no session ${BASE_SESSION_ID}; not the CI fixture)`]);

    // The rail opens `#/diff`: the latest against its previous run, which the daemon chose and the
    // address does not name, so a bookmark goes on following the newest run.
    const chosen = await daemon();
    await press(rail('diff'), 100);
    await settled({ b: chosen.b.sessionId });
    const sideA = doc.querySelector('#diff-summary .diff-side-a');
    const summary = text(doc.getElementById('diff-summary'));
    out.set('diff default pair', shown(doc, 'diff') && window.location.hash === '#/diff' && sideA?.dataset.session === LONG_SESSION_ID
      && chosen.a.sessionId === LONG_SESSION_ID && summary.includes('(the previous run of this app)')
      ? `yes (${window.location.hash} names A ${sideA.dataset.session}, "the previous run of this app", and is not rewritten)`
      : `NO - ${window.location.hash}, A drawn ${sideA?.dataset.session}, the daemon chose ${chosen.a?.sessionId}; "${summary.slice(0, 120)}"`);

    let page = drawn(window, doc);
    const bulk = page.row('GET', '/v3/long/bulk');
    const bulkDue = chosen.routes.find((r) => r.route === '/v3/long/bulk');
    const bulkTags = bulk ? [...bulk.querySelectorAll('.diff-tag')].map((t) => t.textContent) : [];
    out.set('diff long session whole', bulk && bulkDue && bulkDue.a.calls > 1000 && bulkTags.includes('route removed')
      && text(page.cell(bulk, 'calls A → B')) === `${bulkDue.a.calls} → —`
      ? `yes (/v3/long/bulk reads route removed, ${text(page.cell(bulk, 'calls A → B'))} calls, past the 1000 rows one page holds)`
      : `NO - /v3/long/bulk ${bulk ? `tags ${bulkTags.join(', ')}, calls "${text(page.cell(bulk, 'calls A → B'))}"` : 'not drawn'}; the daemon counts ${bulkDue?.a?.calls}`);

    // The baseline against the probe session, by address.
    const pair = { a: BASE_SESSION_ID, b: sessionId };
    const report = await daemon(pair);
    await goTo(`#/diff?a=${encodeURIComponent(BASE_SESSION_ID)}&b=${encodeURIComponent(sessionId)}`, pair);
    const c = report.counts;
    const counted = `${c.added} added, ${c.removed} removed, ${c.changed} changed, ${c.unchanged} unchanged`;
    const said = text(doc.getElementById('diff-summary'));
    out.set('diff explicit pair', doc.querySelector('#diff-summary .diff-side-a')?.dataset.session === BASE_SESSION_ID
      && said.includes(`${report.routeCount} routes: ${counted}`)
      ? `yes ("${said.slice(said.indexOf(' · ') + 3)}", the daemon's counts)`
      : `NO - "${said}"; the daemon counts ${report.routeCount} routes: ${counted}`);

    page = drawn(window, doc);
    const orders = page.row('POST', '/v3/shop/orders/submit');
    const above = orders ? page.rows.slice(0, page.rows.indexOf(orders)) : [];
    const calls = text(page.cell(orders, 'calls A → B'));
    out.set('diff mutation loud', orders && orders.classList.contains('diff-loud') && above.every((tr) => tr.classList.contains('diff-loud')) && calls === '2 → 1'
      ? `yes (POST …/orders/submit is .diff-loud, calls ${calls}, row ${page.rows.indexOf(orders) + 1}, under ${above.length} loud row(s): ${above.map((tr) => `${tr.dataset.method} ${tr.dataset.route}`).join(', ') || 'none'})`
      : `NO - ${orders ? `loud ${orders.classList.contains('diff-loud')}, calls "${calls}", rows above not loud: ${above.filter((tr) => !tr.classList.contains('diff-loud')).length}` : 'no POST /v3/shop/orders/submit row'}`);
    const status = text(page.cell(orders, 'status A → B'));
    out.set('diff status mix', status === '200×2 → 500×1' ? `yes (orders reads ${status})` : `NO - orders status "${status}", 200×2 → 500×1 due`);

    const productsDetail = await page.open(page.row('GET', '/v3/shop/products/{id}'));
    const changedTypes = text(direction(productsDetail, 'response body')?.querySelector('.diff-list-typeChanged'));
    out.set('diff type changed', changedTypes.includes('$.price number → string')
      ? `yes (products detail: "${changedTypes}")` : `NO - products detail's type changes "${changedTypes}"${productsDetail ? '' : ' (no detail opened)'}`);

    // The feed has no change the daemon counts, so it is drawn once unchanged routes are shown.
    const toggle = doc.getElementById('diff-unchanged-toggle');
    if (!toggle.hidden && toggle.getAttribute('aria-pressed') !== 'true') await press(toggle, 100);
    page = drawn(window, doc);
    const feed = page.row('GET', '/v3/shop/feed');
    const why = report.routes.find((r) => r.route === '/v3/shop/feed')?.responseShape?.why;
    const shapeCell = page.cell(feed, 'response shape');
    const feedDetail = await page.open(feed);
    const removedListed = feedDetail ? feedDetail.querySelectorAll('.diff-list-removed').length : -1;
    const whyShown = text(direction(feedDetail, 'response body')?.querySelector('.diff-why'));
    out.set('diff truncated unknown', feed && why && text(shapeCell) === 'unknown' && shapeCell.title === why && whyShown === why && removedListed === 0
      ? `yes (feed's response shape reads unknown, "${why}"; its detail lists nothing removed)`
      : `NO - feed ${feed ? `shape "${text(shapeCell)}" title "${shapeCell?.title}", detail why "${whyShown}", ${removedListed} removed lists` : 'not drawn'}; the daemon's why "${why}"`);

    const tokenDetail = await drawn(window, doc).open(drawn(window, doc).row('POST', '/oauth/token'));
    const tokenResponse = direction(tokenDetail, 'response body');
    const redacted = text(tokenResponse?.querySelector('.diff-list-notCompared'));
    const heading = tokenResponse?.querySelector('.diff-list-notCompared')?.previousElementSibling;
    const typed = text(tokenDetail?.querySelector('.diff-list-typeChanged'));
    out.set('diff redacted not compared', redacted.includes('$.expires_in') && !typed.includes('$.expires_in') && text(heading) === 'redacted — not compared'
      ? `yes ($.expires_in under "${text(heading)}", not under type changes)`
      : `NO - redacted list "${redacted}" under "${text(heading)}", type changes "${typed}"`);
    const headerLine = detailLine(tokenDetail, 'request headers');
    out.set('diff header added', /^added x-request-id\b/.test(headerLine)
      ? `yes (token detail, request headers: "${headerLine}")` : `NO - token detail's request headers line "${headerLine}"`);

    page = drawn(window, doc);
    const stock = page.row('GET', '/v3/shop/stock');
    const verdict = text(page.cell(stock, 'p50 A → B')?.querySelector('.diff-verdict'));
    const stockDue = report.routes.find((r) => r.route === '/v3/shop/stock')?.latency;
    out.set('diff latency not judged', stockDue?.verdict === 'notJudged' && verdict === stockDue.reason
      ? `yes (stock's p50 reads ${verdict}, the daemon's reason)` : `NO - stock's verdict "${verdict}", the daemon's ${JSON.stringify(stockDue)}`);

    // Sessions: tick two and compare, older as A; any other number leaves compare disabled.
    const compare = doc.getElementById('sessions-compare');
    const sessionRow = (id) => doc.querySelector(`#session-rows .session-row[data-id="${id}"]`);
    const tick = (id, on) => {
      const box = sessionRow(id)?.querySelector('input[type="checkbox"]');
      if (!box) return false;
      box.checked = on;
      box.dispatchEvent(new window.Event('change', { bubbles: true }));
      return true;
    };
    await press(rail('sessions'), 300);
    await waitFor(() => sessionRow(sessionId) && sessionRow(BASE_SESSION_ID), 5000);
    const states = [];
    for (const [id, on] of [[sessionId, true], [BASE_SESSION_ID, true], [LONG_SESSION_ID, true], [LONG_SESSION_ID, false]]) {
      tick(id, on);
      states.push(`${compare.textContent} ${compare.disabled ? 'disabled' : 'enabled'}`);
    }
    const dueStates = ['compare (1) disabled', 'compare (2) enabled', 'compare (3) disabled', 'compare (2) enabled'];
    const pairHash = `#/diff?a=${encodeURIComponent(BASE_SESSION_ID)}&b=${encodeURIComponent(sessionId)}`;
    await press(compare, 100);
    await settled(pair);
    out.set('diff sessions compare', states.join() === dueStates.join() && window.location.hash === pairHash && shown(doc, 'diff')
      ? `yes (${states.join(', ')}; compare opened ${window.location.hash}, the older as A)`
      : `NO - ${states.join(', ')} where ${dueStates.join(', ')} were due; compare opened ${window.location.hash}, ${pairHash} due`);

    // A row's diff: B is the row, A the daemon's choice. The ticks are taken off first, by hand.
    await press(rail('sessions'), 300);
    await waitFor(() => sessionRow(sessionId), 5000);
    tick(sessionId, false);
    tick(BASE_SESSION_ID, false);
    const cleared = compare.textContent === 'compare (0)' && compare.disabled;
    const rowDiff = sessionRow(sessionId)?.querySelector('.row-diff');
    if (rowDiff) await press(rowDiff, 100);
    await settled({ b: sessionId });
    const rowHash = `#/diff?b=${encodeURIComponent(sessionId)}`;
    const drawnA = doc.querySelector('#diff-summary .diff-side-a')?.dataset.session;
    out.set('diff row action', rowDiff && cleared && window.location.hash === rowHash && drawnA === LONG_SESSION_ID
      ? `yes (${sessionId}'s diff opened ${window.location.hash}; the daemon chose A ${drawnA}; the ticks cleared)`
      : `NO - ${rowDiff ? `opened ${window.location.hash}, A ${drawnA}, ticks cleared ${cleared}` : 'no .row-diff on the probe session\'s row'}`);

    // A route's calls in B, then in A: Traffic on that side's session with its term and the caveat
    // naming Diff, and back to the address it came from.
    const caveat = doc.getElementById('filter-caveat');
    const callsIn = async (side) => {
      await goTo(pairHash, pair);
      const detail = await drawn(window, doc).open(drawn(window, doc).row('GET', '/v3/shop/stock'));
      const button = detail?.querySelector(`.diff-calls-open[data-side="${side}"]`);
      const due = report.routes.find((r) => r.route === '/v3/shop/stock')?.[side];
      const id = side === 'a' ? BASE_SESSION_ID : sessionId;
      if (!button || !due) return `no calls in ${side.toUpperCase()} on stock`;
      await press(button, 100);
      await waitFor(() => shown(doc, 'traffic') && ctx.pageState().sessionId === id && ctx.pageState().filter === due.filterTerm, 15000);
      await ctx.settle();
      const said = text(caveat);
      const back = doc.getElementById('filter-caveat-back');
      const wrong = [];
      if (picker.value !== id) wrong.push(`session ${picker.value}, ${id} due`);
      if (doc.getElementById('filter').value !== due.filterTerm) wrong.push(`filter "${doc.getElementById('filter').value}", "${due.filterTerm}" due`);
      if (caveat.hidden || !said.startsWith('From Diff: GET /v3/shop/stock') || text(back) !== 'back to Diff') wrong.push(`caveat shown ${!caveat.hidden}, "${said.slice(0, 60)}", button "${text(back)}"`);
      await press(back, 100);
      await settled(pair);
      if (window.location.hash !== pairHash || !shown(doc, 'diff')) wrong.push(`back to Diff went to ${window.location.hash}`);
      return wrong.join('; ');
    };
    const inB = await callsIn('b');
    const inA = await callsIn('a');
    out.set('diff opens calls', !inB && !inA
      ? `yes (stock's calls in B and in A each opened Traffic on that session with its filterTerm, "From Diff", and back to Diff returned to ${pairHash})`
      : `NO - ${[inB && `B: ${inB}`, inA && `A: ${inA}`].filter(Boolean).join(' | ')}`);

    // Both pickers on one session: the daemon refuses, and the page says so in its words.
    const refusal = (await daemon({ a: sessionId, b: sessionId })).error;
    const pickA = doc.getElementById('diff-a');
    pickA.value = sessionId;
    pickA.dispatchEvent(new window.Event('change', { bubbles: true }));
    await pause(100);
    await settled(null);
    const error = doc.getElementById('diff-error');
    out.set('diff same session', !error.hidden && text(error) === refusal && window.location.hash.includes(`a=${encodeURIComponent(sessionId)}`)
      ? `yes ("${refusal}")` : `NO - shown ${!error.hidden}, "${text(error)}"; the daemon says "${refusal}"; ${window.location.hash}`);

    await press(rail('traffic'), 300);
    await ctx.settle();
  } finally {
    // `calls in A` left Traffic on the baseline session under stock's term: put both back.
    if (!shown(doc, 'traffic')) await press(rail('traffic'), 300);
    if (ctx.pageState().sessionId !== sessionId && sessionId) {
      choose(ctx, sessionId);
      await waitFor(() => ctx.pageState().sessionId === sessionId && ctx.pageState().signalSessionId === sessionId, 8000);
    }
    const filterBox = doc.getElementById('filter');
    if (filterBox.value) {
      filterBox.value = '';
      filterBox.dispatchEvent(new window.Event('input', { bubbles: true }));
      await pause(400);
    }
    await ctx.settle();
  }
  const after = ctx.pageState();
  const clean = shown(doc, 'traffic') && doc.getElementById('filter-caveat').hidden && after.filter === ''
    && doc.getElementById('filter').value === '' && after.sessionId === sessionId && after.liveTail;
  out.set('diff restored', clean
    ? `yes (Traffic, ${sessionId}, no filter, no caveat, live)`
    : `NO - Traffic ${shown(doc, 'traffic')}, session ${after.sessionId}, filter "${after.filter}", caveat ${!doc.getElementById('filter-caveat').hidden}, live ${after.liveTail}`);
  return [...out];
}

module.exports = { DIFF_LABELS, BASE_SESSION_ID, probeDiff };
