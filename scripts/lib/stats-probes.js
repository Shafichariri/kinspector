/* Probes of the Stats page, live for `render-web-ui.js` and offline for `render-web-ui-archive.js`.
 *
 * The page computes nothing: every route, term, rate, order and `n<20` cell is the daemon's. So the
 * live probe compares what the page drew with what `GET …/stats` answers the probe itself, and
 * drives the parts that are the page's own: the sort and the filter sent as the daemon's
 * parameters, a row opening Traffic on the row's term with the caveat, the caveat clearing, a
 * session switch reloading the table, and a live call asking for it again at most once.
 *
 * On the CI fixture (`ProbeFixture.kt`): `/v3/shop/stock` is one call retried twice and another
 * that failed, 2 calls and 4 attempts of which 3 answered 5xx; `/v3/shop/promo/current` is the end
 * of a redirect, an attempt 2 after a 3xx, which is not a retry; `/v3/shop/products/{id}` is a
 * templated path; and the 1203-call session's `/v3/long/bulk` has 1201 attempts, past the 1000 rows
 * one page of `…/transactions` holds. Nothing is written to the archive: the live calls go through
 * the page's own socket handler, and resuming live tail reads the session again without them.
 *
 * `probeStatsPage` leaves the page on Traffic, on the session it found, with no filter and no
 * caveat, and checks that it did (`stats restored`), so the probe after it does not pass vacuously.
 * Each returns `[label, verdict]` pairs, one per line the caller prints.
 */

const { LONG_SESSION_ID, choose, waitFor } = require('./long-session-probes');

const STATS_LABELS = [
  'stats page drawn', 'stats route templated', 'stats n<20', 'stats retried', 'stats redirect not retry',
  'stats 5xx rate', 'stats sort asks daemon', 'stats filter', 'stats filter error', 'stats opens traffic',
  'stats caveat shown', 'stats caveat clears', 'stats long session whole', 'stats live asks', 'stats restored',
];
const CAVEAT_WORDS = 'may include paths the route keeps apart';
const pause = (ms) => new Promise((r) => setTimeout(r, ms));

/** The table as drawn: each row by its route, and a cell by its column's header text. */
function drawnTable(doc) {
  const heads = [...doc.querySelectorAll('#stats-head th')].map((th) => th.textContent.replace(/\s*↓$/, '').trim());
  const rows = [...doc.querySelectorAll('#stats-rows tr.stats-row')];
  const routeOf = (tr) => tr.querySelector('.stats-path')?.textContent;
  const row = (route) => rows.find((tr) => routeOf(tr) === route) || null;
  const cell = (tr, head) => (tr ? tr.children[heads.indexOf(head)] : null);
  return { heads, rows, routes: rows.map(routeOf), row, cell };
}

const pageShown = (doc, page) => !doc.getElementById(`page-${page}`).hidden;
const caveatShown = (doc) => !doc.getElementById('filter-caveat').hidden;

/** The live page: see the file's header. `ctx` is `render-web-ui.js`'s shell context. */
async function probeStatsPage(ctx) {
  const { window, doc, ORIGIN } = ctx;
  const out = new Map(STATS_LABELS.map((label) => [label, 'n/a (not reached)']));
  const picker = doc.getElementById('session-picker');
  const sessionId = picker.value;
  const daemon = async (id, query = {}) =>
    (await fetch(`${ORIGIN}/api/sessions/${encodeURIComponent(id)}/stats?${new URLSearchParams(query)}`)).json();
  const press = async (node, ms = 150) => {
    node.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    await pause(ms);
  };
  const rail = () => doc.querySelector('#rail .rail-item[data-page="stats"]');
  const statsFilter = doc.getElementById('stats-filter');
  // Every stats request the page sends, by its query; the rest pass through untouched.
  const asked = [];
  const realFetch = window.fetch;
  window.fetch = (path, init) => {
    const url = new URL(path, ORIGIN);
    if (url.pathname.endsWith('/stats')) asked.push(url);
    return realFetch(path, init);
  };
  const typeStats = async (text) => {
    const before = asked.length;
    statsFilter.value = text;
    statsFilter.dispatchEvent(new window.Event('input', { bubbles: true }));
    await waitFor(() => asked.length > before, 3000);
    await pause(400);
  };
  const stateBefore = ctx.pageState();
  const lastCaptureTs = stateBefore.lastCaptureTs;
  try {
    if (!sessionId) return [...out.keys()].map((label) => [label, 'n/a (no session)']);
    const report = await daemon(sessionId);
    await press(rail());
    await waitFor(() => doc.querySelectorAll('#stats-rows tr.stats-row').length === report.routes.length, 5000);
    let table = drawnTable(doc);
    out.set('stats page drawn', pageShown(doc, 'stats') && table.rows.length === report.routeCount
      ? `yes (${table.rows.length} rows, the daemon's routeCount; "${doc.getElementById('stats-count').textContent}")`
      : `NO - ${table.rows.length} rows drawn, page shown ${pageShown(doc, 'stats')}, the daemon counts ${report.routeCount}`);

    const products = table.row('/v3/shop/products/{id}');
    out.set('stats route templated', products ? 'yes (/v3/shop/products/{id} drawn)'
      : `n/a (no /v3/shop/products/{id} among ${table.routes.length} routes)`);
    if (products) {
      const tail = ['p95', 'p99'].map((head) => table.cell(products, head)?.textContent);
      out.set('stats n<20', tail.every((text) => text === 'n<20')
        ? 'yes (p95 and p99 read n<20, as the daemon withheld them)'
        : `NO - p95 "${tail[0]}", p99 "${tail[1]}"; the daemon's tailWithheld is ${report.routes.find((r) => r.route === '/v3/shop/products/{id}')?.tailWithheld}`);
    }

    const stock = table.row('/v3/shop/stock');
    if (stock) {
      const calls = table.cell(stock, 'calls')?.textContent;
      const attempts = table.cell(stock, 'attempts');
      const tag = attempts?.querySelector('.stats-retried')?.textContent;
      out.set('stats retried', calls === '2' && attempts?.firstChild?.textContent === '4' && tag === '2 retries'
        ? 'yes (/v3/shop/stock: 2 calls, 4 attempts, "2 retries")'
        : `NO - calls "${calls}", attempts "${attempts?.textContent}", tag "${tag}"`);
      const rate = table.cell(stock, '5xx')?.textContent;
      out.set('stats 5xx rate', rate === '75%' ? 'yes (/v3/shop/stock 5xx reads 75%)' : `NO - "${rate}", 75% due`);
    }
    const promo = table.row('/v3/shop/promo/current');
    const promoReport = report.routes.find((r) => r.route === '/v3/shop/promo/current');
    if (promo && promoReport) {
      const tag = promo.querySelector('.stats-retried');
      out.set('stats redirect not retry', !tag && promoReport.redirects > 0
        ? `yes (/v3/shop/promo/current: ${promoReport.redirects} redirect, no retried tag)`
        : `NO - tag "${tag?.textContent}", the daemon counts ${promoReport.redirects} redirects`);
    }

    // The sort is the daemon's: a header asks for it, and says it is the one in force.
    const sortButton = doc.querySelector('#stats-head button[data-sort="p90"]');
    if (sortButton) {
      const before = asked.length;
      await press(sortButton, 400);
      const sent = asked.slice(before).some((url) => url.searchParams.get('sort') === 'p90');
      const header = doc.querySelector('#stats-head button[data-sort="p90"]')?.closest('th');
      const ariaSort = header?.getAttribute('aria-sort');
      out.set('stats sort asks daemon', sent && ariaSort === 'descending'
        ? 'yes (p90 sent sort=p90, and its header says aria-sort="descending")'
        : `NO - sort=p90 sent ${sent}, aria-sort "${ariaSort}"`);
      await press(doc.querySelector('#stats-head button[data-sort="attempts"]'), 400);
    } else {
      out.set('stats sort asks daemon', 'NO - no p90 sort button in the header');
    }

    const fives = await daemon(sessionId, { filter: 'status>=500' });
    await typeStats('status>=500');
    table = drawnTable(doc);
    const due = fives.routes.map((r) => r.route).join(', ');
    out.set('stats filter', fives.routes.length > 0 && table.routes.join(', ') === due
      ? `yes (status>=500 drew ${due}, as the daemon matched them)`
      : `NO - drew ${table.routes.join(', ') || 'nothing'}, the daemon matched ${due || 'nothing'}`);
    const refusal = (await daemon(sessionId, { filter: 'status>=x' })).error;
    await typeStats('status>=x');
    const shownError = doc.getElementById('stats-filter-error');
    out.set('stats filter error', !shownError.hidden && shownError.textContent === refusal
      ? `yes ("${refusal}")`
      : `NO - hidden ${shownError.hidden}, "${shownError.textContent}"; the daemon says "${refusal}"`);
    await typeStats('');

    // A row opens Traffic on the daemon's term for it, with the caveat, and Back returns here.
    const stockReport = report.routes.find((r) => r.route === '/v3/shop/stock');
    const filterBox = doc.getElementById('filter');
    const openStock = async (how) => {
      const tr = drawnTable(doc).row('/v3/shop/stock');
      if (tr) await press(how === 'button' ? tr.querySelector('.stats-open') : tr.querySelector('td'), 100);
      await waitFor(() => pageShown(doc, 'traffic') && ctx.pageState().filter === stockReport.filterTerm, 3000);
      await ctx.settle();
    };
    if (stockReport && drawnTable(doc).row('/v3/shop/stock')) {
      await openStock('row');
      const hash = window.location.hash;
      const opened = pageShown(doc, 'traffic') && filterBox.value === stockReport.filterTerm;
      const caveat = doc.getElementById('filter-caveat').textContent.replace(/\s+/g, ' ').trim();
      out.set('stats caveat shown', caveatShown(doc) && caveat.includes(CAVEAT_WORDS) && caveat.includes('GET /v3/shop/stock')
        ? `yes ("${caveat.slice(0, 80)}…")`
        : `NO - shown ${caveatShown(doc)}, "${caveat}"`);
      window.history.back();
      const back = await waitFor(() => pageShown(doc, 'stats'), 3000);
      out.set('stats opens traffic', opened && back
        ? `yes (${hash}, filter "${filterBox.value}", the daemon's term; Back returned to ${window.location.hash})`
        : `NO - Traffic shown ${pageShown(doc, 'traffic')} at ${hash}, filter "${filterBox.value}" where "${stockReport.filterTerm}" was due; Back to Stats ${back}`);

      // Any other filter clears the caveat, and so does a switch to another session. Cleared, not
      // only hidden: typing the term back by hand is not a list a Stats row opened. The switch is
      // made on the Stats page, so it also shows the table reloading for the session switched to.
      await openStock('button');
      const reopened = caveatShown(doc);
      const typeTraffic = async (text) => {
        filterBox.value = text;
        filterBox.dispatchEvent(new window.Event('input', { bubbles: true }));
        await pause(400);
      };
      await typeTraffic(`${stockReport.filterTerm} status>=500`);
      const hidden = !caveatShown(doc);
      await typeTraffic(stockReport.filterTerm);
      const edited = hidden && !caveatShown(doc);
      await press(rail(), 300);
      await openStock('button');
      const again = caveatShown(doc);
      await press(rail(), 400);
      const hasLong = [...picker.options].some((o) => o.value === LONG_SESSION_ID);
      if (hasLong) {
        const long = await daemon(LONG_SESSION_ID);
        const bulk = long.routes.find((r) => r.route === '/v3/long/bulk');
        choose(ctx, LONG_SESSION_ID);
        await waitFor(() => drawnTable(doc).row('/v3/long/bulk'), 8000);
        const cell = drawnTable(doc).cell(drawnTable(doc).row('/v3/long/bulk'), 'attempts')?.firstChild?.textContent;
        out.set('stats long session whole', bulk && cell === String(bulk.attempts) && bulk.attempts > 1000
          ? `yes (/v3/long/bulk reads ${cell} attempts after the switch, past the 1000 rows one page holds)`
          : `NO - /v3/long/bulk reads "${cell}", the daemon counts ${bulk?.attempts}`);
        await press(doc.querySelector('#rail .rail-item[data-page="traffic"]'), 300);
        const switched = !caveatShown(doc);
        out.set('stats caveat clears', reopened && edited && again && switched
          ? 'yes (an edited filter cleared it, typing the term back did not restore it, and a switch cleared it)'
          : `NO - reopened ${reopened}, hidden after an edit ${hidden}, still hidden with the term typed back ${edited}, shown again from Stats ${again}, hidden after a switch ${switched}`);
        choose(ctx, sessionId);
        await waitFor(() => ctx.pageState().sessionId === sessionId && ctx.pageState().signalSessionId === sessionId, 8000);
      } else {
        out.set('stats long session whole', `n/a (no session ${LONG_SESSION_ID})`);
        out.set('stats caveat clears', reopened && edited && again ? 'yes (an edited filter cleared it; no other session to switch to)'
          : `NO - reopened ${reopened}, hidden after an edit ${hidden}, still hidden with the term typed back ${edited}, shown again ${again}`);
      }
      await press(doc.querySelector('#rail .rail-item[data-page="traffic"]'), 200);
      await typeTraffic('');
      await ctx.settle();
    }

    // Live: a call through the page's own socket asks for the table once within 1.5 s; paused, never.
    await press(rail(), 300);
    await waitFor(() => doc.querySelectorAll('#stats-rows tr.stats-row').length > 0, 5000);
    const socket = ctx.lastSocket();
    const template = ctx.pageState().transactions[0];
    if (socket && template) {
      let n = 0;
      const send = () => socket.onmessage({ data: JSON.stringify({
        type: 'txn', sessionId, txn: { ...template, id: `statsprobe-c${++n}`, mono: template.mono + 100000 + n, seq: undefined },
      }) });
      const liveButton = doc.getElementById('live-btn');
      let before = asked.length;
      send();
      await pause(1500);
      const whileLive = asked.length - before;
      await press(liveButton, 300);
      before = asked.length;
      send();
      await pause(1500);
      const whilePaused = asked.length - before;
      await press(liveButton, 300);   // resuming reads the session again, without the probe's calls
      out.set('stats live asks', whileLive === 1 && whilePaused === 0
        ? 'yes (a live call asked for the table once within 1.5 s; paused, a call asked nothing)'
        : `NO - ${whileLive} stats requests after a live call (1 due), ${whilePaused} while paused (0 due)`);
    } else {
      out.set('stats live asks', 'NO - no socket or no call to copy');
    }

    await press(doc.querySelector('#rail .rail-item[data-page="traffic"]'), 300);
    await ctx.settle();
  } finally {
    window.fetch = realFetch;
    ctx.pageState().lastCaptureTs = lastCaptureTs;
  }
  const after = ctx.pageState();
  const clean = pageShown(doc, 'traffic') && !caveatShown(doc) && after.filter === '' && doc.getElementById('filter').value === ''
    && after.sessionId === sessionId && after.liveTail && statsFilter.value === '';
  out.set('stats restored', clean
    ? `yes (Traffic, ${sessionId}, no filter, no caveat, live)`
    : `NO - Traffic ${pageShown(doc, 'traffic')}, session ${after.sessionId}, filter "${after.filter}", caveat ${caveatShown(doc)}, live ${after.liveTail}, stats filter "${statsFilter.value}"`);
  return [...out];
}

/**
 * The Stats page in an exported file, network forbidden: the rail offers it while the other pages
 * stay hidden, it draws the report the export embeds, prints `tailWithheld` where the export's rows
 * withheld a tail, and is inert where the daemon would be needed (no sort buttons, rows that open
 * nothing, the filter disabled). `attempts` is the caller's record of network attempts; drawing the
 * table must add none. Leaves the page on Traffic.
 */
async function probeStatsOffline(window, doc, archive, attempts) {
  const rail = (page) => doc.querySelector(`#rail .rail-item[data-page="${page}"]`);
  const hiddenByCss = (node) => !node || window.getComputedStyle(node).display === 'none';
  // A link that is not there is not "hidden": with none found, this would check nothing.
  const missing = ['sessions', 'processes', 'settings'].filter((page) => !rail(page));
  const others = ['sessions', 'processes', 'settings'].filter((page) => !hiddenByCss(rail(page)));
  const lines = [['stats rail in export', missing.length ? `NO - found no rail link for ${missing.join(', ')}, so this checked nothing`
    : !hiddenByCss(rail('stats')) && others.length === 0
    ? 'yes (Stats shown; sessions, processes and settings hidden)'
    : `NO - Stats ${hiddenByCss(rail('stats')) ? 'hidden' : 'shown'}, also shown: ${others.join(', ') || 'none'}`]];
  const report = archive.routeStats;
  const labels = ['stats offline drawn', 'stats offline n<20', 'stats offline inert', 'stats offline filter disabled'];
  if (!report) return [...lines, ...labels.map((label) => [label, 'NO - the export embeds no routeStats'])];

  const before = attempts.length;
  window.location.hash = '#/stats';
  await waitFor(() => !doc.getElementById('page-stats').hidden
    && doc.querySelectorAll('#stats-rows tr.stats-row').length === report.routes.length, 3000);
  const table = drawnTable(doc);
  const empty = doc.getElementById('stats-empty');
  lines.push(['stats offline drawn', table.rows.length === report.routes.length && attempts.length === before
    ? `yes (${table.rows.length} rows, every route the export embeds, no network attempt)`
    : `NO - ${table.rows.length} rows of ${report.routes.length}, ${attempts.length - before} network attempts; empty: "${empty.hidden ? '' : empty.textContent}"`]);

  const withheld = report.routes.filter((r) => r.tailWithheld && r.p95Ms === undefined);
  const wrong = withheld.filter((r) => ['p95', 'p99'].some((head) => table.cell(table.row(r.route), head)?.textContent !== r.tailWithheld));
  lines.push(['stats offline n<20', withheld.length && !wrong.length
    ? `yes (${withheld.length} rows print ${withheld[0].tailWithheld} for p95 and p99, as embedded)`
    : `NO - ${withheld.length} withheld rows embedded, ${wrong.length} drawn otherwise: ${wrong.map((r) => r.route).join(', ')}`]);

  const sortButtons = doc.querySelectorAll('#stats-head button').length;
  const openButtons = doc.querySelectorAll('#stats-rows .stats-open').length;
  const filter = doc.getElementById('filter').value;
  if (table.rows[0]) table.rows[0].querySelector('td').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  await new Promise((r) => setTimeout(r, 300));
  const stayed = window.location.hash === '#/stats' && !doc.getElementById('page-stats').hidden && doc.getElementById('filter').value === filter;
  lines.push(['stats offline inert', table.rows.length && !sortButtons && !openButtons && stayed
    ? `yes (no sort buttons, no open buttons; a row click left ${window.location.hash} and the filter as they were)`
    : `NO - ${sortButtons} sort buttons, ${openButtons} open buttons, after a row click ${window.location.hash}, filter "${doc.getElementById('filter').value}"`]);

  const input = doc.getElementById('stats-filter');
  const said = !doc.getElementById('stats-export-note').hidden;
  lines.push(['stats offline filter disabled', input.disabled && said
    ? `yes (disabled, "${input.placeholder}"; the note says what needs the daemon)`
    : `NO - disabled ${input.disabled}, note shown ${said}`]);
  window.location.hash = '#/traffic';
  await waitFor(() => !doc.getElementById('page-traffic').hidden, 3000);
  return lines;
}

module.exports = { STATS_LABELS, probeStatsPage, probeStatsOffline };
