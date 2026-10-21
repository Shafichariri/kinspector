/* Probes of the traffic page's room for the list, for render-web-ui.js: the drawer, the rows' parts,
 * the toolbar and its menus, and the overview line.
 *
 * The drawer, toolbar and now-strip probes moved here from render-web-ui.js, which is held to its
 * size budget; their lines and labels are the ones it printed. Layout is Chrome's to judge
 * (`lib/list-room-cases.js`, group `room` in measure-web-ui.js): these drive behaviour and read the
 * DOM, and each puts back whatever pane state it changed, so a later probe does not pass vacuously.
 *
 * Each returns `[label, value]` pairs, one per line the caller prints, or `[label, value, 'note']`
 * for a line that is information rather than a check.
 */

const pause = (ms) => new Promise((r) => setTimeout(r, ms));
const press = async (ctx, node, ms = 100) => {
  node.dispatchEvent(new ctx.window.Event('click', { bubbles: true }));
  await pause(ms);
};
const tabOf = (doc, view) => [...doc.querySelectorAll('#tabs .tab')].find((t) => t.dataset.view === view);

/**
 * The narrow-width drawer.
 *
 * Split in two on purpose. Whether it *opens and closes* is behaviour and is driven here; whether it
 * is a drawer *at all*, and whether a closed one is out of Tab's reach, is a media query, and jsdom
 * has no layout, so that half is read out of the stylesheet (`drawer css`, `closed drawer css`) and
 * measured in Chrome (`room 1000 tab never enters closed`) — a computed-style check would pass with
 * the whole media block deleted, the same trap `auditHiddenPanes` already avoids.
 */
async function probeDrawer(ctx) {
  const { window, doc } = ctx;
  const result = { opensOnSelect: 'n/a', scrim: 'n/a', closeButton: 'n/a', scrimCloses: 'n/a', escape: 'n/a', tabSwitch: 'n/a' };
  const panes = doc.getElementById('panes');
  const isOpen = () => panes.classList.contains('drawer-open');

  const network = tabOf(doc, 'network');
  if (network) await press(ctx, network, 400);
  const row = doc.querySelector('#list .row');
  if (row) {
    await press(ctx, row, 400);
    result.opensOnSelect = isOpen() ? 'yes' : 'NO - selecting a row left it shut';
    result.scrim = doc.getElementById('drawer-scrim').hidden ? 'NO - no scrim' : 'yes (shown)';

    await press(ctx, doc.getElementById('drawer-close'), 200);
    result.closeButton = isOpen() ? 'NO' : 'yes (closes)';

    await press(ctx, row, 300);
    // jsdom dispatches a click to a hidden element as readily as to a shown one, so a scrim that
    // never appeared would still "close" the drawer here. Nobody can click what is not drawn.
    const scrim = doc.getElementById('drawer-scrim');
    const scrimShown = !scrim.hidden;
    await press(ctx, scrim, 200);
    result.scrimCloses = !scrimShown
      ? 'NO - the scrim was hidden, so there was nothing to click'
      : isOpen() ? 'NO' : 'yes (closes)';

    await press(ctx, row, 300);
    doc.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await pause(200);
    result.escape = isOpen() ? 'NO' : 'yes (closes)';

    // A drawer covers the list it came from; carrying it across a tab switch would leave it
    // sitting over a list that never produced it.
    await press(ctx, row, 300);
    const other = [...doc.querySelectorAll('#tabs .tab')].find((t) => t.dataset.view !== 'network');
    if (other) {
      await press(ctx, other, 700);
      result.tabSwitch = isOpen() ? `NO - carried onto '${other.dataset.view}'` : 'yes (closes)';
    }
  }
  const closes = [result.closeButton, result.scrimCloses, result.escape];
  return [
    ['drawer opens', result.opensOnSelect],
    ['drawer scrim', result.scrim],
    ['drawer closes by', `${closes.every((v) => String(v).startsWith('yes')) ? 'yes (button, scrim, esc)' : 'NO -'} `
      + `button ${result.closeButton}, scrim ${result.scrimCloses}, esc ${result.escape}`],
    ['drawer on tab swap', result.tabSwitch],
  ];
}

/**
 * Every drawn row's parts: the path as a head and its last segment, and a repeat mark in both forms.
 *
 * Below 520px the head ellipsizes and the last segment never does, so the segment has to be the
 * last one of the path the row *shows* — the scope bar's prefix stripped — not of the captured one.
 * The repeat mark is its own item with `·repeated Nx` and `N×`, one shown per width; N is the call
 * count the row's tooltip states. Which form shows is layout, measured by `room 400 rows` in Chrome.
 */
function probeRowParts(ctx) {
  const { doc, pageState } = ctx;
  const page = pageState();
  const byId = new Map(page.transactions.map((t) => [t.id, t]));
  const rows = [...doc.querySelectorAll('#list .row')];
  if (!rows.length) return [['row parts', 'NO - the list drew no rows']];
  const wrong = [];
  let repeats = 0;
  for (const row of rows) {
    const txn = byId.get(row.dataset.id);
    if (!txn) { wrong.push(`row ${row.dataset.id} is not in the page's rows`); continue; }
    const shown = page.scope && page.scope.covers(txn) ? page.scope.strip(txn.path) : txn.path;
    const at = shown.search(/[^/]+\/*$/);
    const due = at === -1 ? shown : shown.slice(at);
    const head = row.querySelector('.path-head')?.textContent;
    const tail = row.querySelector('.path-tail')?.textContent;
    if (tail !== due || `${head}${tail}` !== shown) wrong.push(`"${shown}" drawn "${head}" + "${tail}"`);
    const sent = /sent (\d+)x/.exec(row.title || '');
    if (!sent) continue;
    repeats++;
    const long = row.querySelector('.dup-badge .dup-long')?.textContent.trim();
    const short = row.querySelector('.dup-badge .dup-short')?.textContent;
    if (long !== `·repeated ${sent[1]}x` || short !== `${sent[1]}×`) wrong.push(`repeat ${sent[1]}x reads "${long}" / "${short}"`);
  }
  return [['row parts', wrong.length ? `NO - ${wrong.slice(0, 3).join('; ')}`
    : `yes (${rows.length} rows end in their shown path's last segment, ${repeats} repeat marks read ·repeated Nx and N×)`]];
}

/**
 * `Now` is a summary, and a summary that takes 70% of a column is not one.
 *
 * The assertion is that it opens collapsed and still says something useful collapsed — a strip
 * reading only "now" would have moved the problem rather than fixed it. Open, it is a popover under
 * its button in the overview line; the list it holds is the same.
 */
async function probeNowStrip(ctx) {
  const { doc } = ctx;
  const result = { present: false, startsCollapsed: 'n/a', collapsedSummary: 'n/a', expandsTo: 0, collapsesBack: 'n/a' };
  const all = tabOf(doc, 'all');
  if (all) {
    await press(ctx, all, 500);
    const strip = doc.getElementById('now-strip');
    result.present = Boolean(strip) && !strip.hidden;
  }
  if (result.present) {
    result.startsCollapsed = doc.getElementById('current').hidden ? 'yes' : 'NO - opens expanded';
    const summary = doc.getElementById('now-summary-text').textContent.trim();
    result.collapsedSummary = summary ? `yes ("${summary}")` : 'NO - the strip says nothing collapsed';
    await press(ctx, doc.getElementById('now-toggle'), 250);
    result.expandsTo = doc.querySelectorAll('#current .current-item').length;
    await press(ctx, doc.getElementById('now-toggle'), 250);
    result.collapsesBack = doc.getElementById('current').hidden ? 'yes' : 'NO';
  }
  return [
    ['now strip present', result.present],
    ['now starts collapsed', result.startsCollapsed],
    ['now says collapsed', result.collapsedSummary],
    ['now expands', result.expandsTo > 0 ? `yes (${result.expandsTo} rows)` : 'NO - nothing listed'],
    ['now collapses back', result.collapsesBack],
  ];
}

/**
 * The overview line: the axis, its hint and the now strip in one band, every id kept. On the list
 * the now strip is hidden and the axis shown; the hint counts the session's calls, as the bars do,
 * so it is read under a filter that thins the rows, where a count of the rows held would differ.
 */
async function probeOverview(ctx) {
  const { window, doc, ORIGIN, settle, pageState } = ctx;
  const overview = doc.getElementById('overview');
  if (!overview) return [['overview line', 'NO - there is no #overview']];
  const missing = ['axis', 'axis-hint', 'now-strip'].filter((id) => doc.getElementById(id)?.parentElement !== overview);
  await press(ctx, tabOf(doc, 'network'), 400);
  const onList = { overview: !overview.hidden, axis: !doc.getElementById('axis').hidden, now: doc.getElementById('now-strip').hidden };
  const filter = doc.getElementById('filter');
  const type = async (text) => { filter.value = text; filter.dispatchEvent(new window.Event('input', { bubbles: true })); await settle(); };
  await type('status>=500');
  const hint = doc.getElementById('axis-hint').textContent;
  const held = pageState().transactions.length;
  const id = encodeURIComponent(doc.getElementById('session-picker').value);
  const total = (await fetch(`${ORIGIN}/api/sessions/${id}/transactions?limit=1`).then((r) => r.json())).total;
  await type('');
  const said = /^(\d+) calls in this session\b/.exec(hint);
  const verdict = missing.length ? `NO - not in #overview: ${missing.join(', ')}`
    : !onList.overview || !onList.axis || !onList.now ? `NO - on the list: overview shown ${onList.overview}, axis shown ${onList.axis}, now hidden ${onList.now}`
      : !said ? `NO - the hint reads "${hint}"`
        : held >= total ? `n/a (status>=500 kept all ${total} calls, so the session's count and the rows' agree)`
          : Number(said[1]) !== total ? `NO - the hint counts ${said[1]} under a filter keeping ${held} of ${total}`
            : `yes (axis, hint and now in one line; "${said[0]}" under a filter keeping ${held})`;
  return [['overview line', verdict]];
}

/**
 * Controls belong to the view they act on.
 *
 * The toolbar filters *transactions*. On a tag browser it would be a second filter box meaning
 * something else entirely, above a list it cannot filter — which is what the rail was, for a fifth
 * of the window, on five tabs out of seven.
 */
async function probeToolbar(ctx) {
  const { doc } = ctx;
  const lines = [];
  const network = tabOf(doc, 'network');
  lines.push(['toolbar on traffic', !network ? 'n/a'
    : (await press(ctx, network, 400), doc.getElementById('toolbar').hidden ? 'NO - hidden on traffic' : 'yes (shown)')]);
  const browser = [...doc.querySelectorAll('#tabs .tab')].find((t) => !['network', 'path', 'all', 'waterfall'].includes(t.dataset.view));
  if (!browser) return [...lines, ['toolbar on browser', 'n/a'], ['browser own filter', 'n/a']];
  await press(ctx, browser, 700);
  lines.push(['toolbar on browser', `${doc.getElementById('toolbar').hidden
    ? `yes (hidden on '${browser.dataset.view}')` : `NO - still shown on '${browser.dataset.view}'`} (it filters calls, not keys)`]);
  lines.push(['browser own filter', doc.getElementById('browser-filter') ? 'yes' : 'NO']);
  return lines;
}

/** `⋮` opened, its items read, and closed again. */
async function withMore(ctx, read) {
  const button = ctx.doc.getElementById('more-button');
  if (!button) return null;
  await press(ctx, button, 60);
  try { return await read(button.parentElement.querySelector('.menu')); } finally {
    if (button.getAttribute('aria-expanded') === 'true') await press(ctx, button, 60);
  }
}

/**
 * The toolbar's controls, as a reader meets them.
 *
 * `toolbar one row` replaces `rowLabels`, which held that each of two rows was named: there is one
 * row now, so nothing needs a caption, and the quick filters are named for a screen reader instead.
 * The order control is judged by `aria-pressed` agreeing with the visible `on` state, because the
 * segmented control's whole claim is that it states which order the list is in. `shortcuts in
 * menu` replaces `shortcuts`, the button that went: the legend now opens from `⋮`.
 */
async function probeToolbarControls(ctx) {
  const { doc } = ctx;
  const toolbar = doc.getElementById('toolbar');
  const rows = toolbar.querySelectorAll('.toolbar-row');
  const captions = toolbar.querySelectorAll('.row-label');
  const controls = ['filter', 'chips', 'endpoints-button', 'order-seg', 'markers-button', 'more-button'];
  const outside = controls.filter((id) => !rows[0] || doc.getElementById(id)?.closest('.toolbar-row') !== rows[0]);
  const quick = doc.getElementById('chips').getAttribute('aria-label');
  const oldest = doc.getElementById('order-oldest');
  const newest = doc.getElementById('order-newest');
  const pressedAgrees = [oldest, newest].every((b) => b.getAttribute('aria-pressed') === String(b.classList.contains('on')));
  const markerLabels = new Set([...doc.querySelectorAll('#markers li')].filter((li) => !li.classList.contains('muted')).map((li) => li.textContent)).size;
  const markersText = doc.getElementById('markers-button').textContent;
  const shortcuts = await withMore(ctx, async () => {
    const item = doc.getElementById('keys-item');
    if (!item || item.textContent !== 'keyboard shortcuts') return `NO - ⋮ has no keyboard shortcuts item`;
    await press(ctx, item, 60);
    const opened = !doc.getElementById('keys-pop').hidden;
    await press(ctx, doc.body, 60);
    const closed = doc.getElementById('keys-pop').hidden;
    return opened && closed && !doc.getElementById('keys-button')
      ? 'yes (⋮ › keyboard shortcuts opens the legend; a click elsewhere closes it)'
      : `NO - opened ${opened}, closed again ${closed}, old button ${Boolean(doc.getElementById('keys-button'))}`;
  });
  return [
    ['toolbar one row', rows.length === 1 && !captions.length && !outside.length && quick === 'Quick filters'
      ? `yes (one row of ${controls.length} controls, no captions, the quick filters labelled)`
      : `NO - ${rows.length} rows, ${captions.length} captions, outside: ${outside.join(' ') || 'none'}, quick filters "${quick}"`],
    ['orderSegmented', oldest.parentElement.classList.contains('seg2') && pressedAgrees
      && [oldest, newest].filter((b) => b.classList.contains('on')).length === 1
      ? `yes (${oldest.classList.contains('on') ? 'oldest' : 'newest'} first pressed)`
      : 'NO - the order control does not state one order'],
    ['markersCount', markerLabels ? (markersText === `markers · ${markerLabels}` ? `yes ("${markersText}")` : `NO - "${markersText}" for ${markerLabels}`)
      : markersText === 'markers' ? 'yes ("markers", none in this session)' : `NO - "${markersText}"`],
    ['shortcuts in menu', shortcuts ?? 'NO - there is no ⋮'],
  ];
}

/**
 * `endpoints ▾ N`: N is the chips its popover holds, and stays so under the filter a chip sets,
 * because the chips and the count are built from the whole session; pressing a chip filters and the
 * chips survive it (the `survived` rule of the chip probe).
 */
async function probeEndpointsMenu(ctx) {
  const { doc, settle } = ctx;
  const button = doc.getElementById('endpoints-button');
  const box = doc.getElementById('endpoint-chips');
  if (!button || button.hidden) return [['endpoints menu', 'NO - no endpoints ▾ on a session with endpoints']];
  await press(ctx, button, 60);
  const opened = !box.hidden;
  const chips = box.querySelectorAll('.chip').length;
  const rowsBefore = doc.querySelectorAll('#list .row').length;
  const chip = box.querySelector('.chip');
  await press(ctx, chip, 60);
  await settle();
  const rowsAfter = doc.querySelectorAll('#list .row').length;
  const said = button.textContent;
  const survived = box.querySelectorAll('.chip').length;
  await press(ctx, box.querySelector('.chip.active') || box.querySelector('.chip'), 60);
  await settle();
  if (!box.hidden) await press(ctx, button, 60);
  return [['endpoints menu', opened && said === `endpoints ▾ ${chips}` && survived === chips && rowsAfter < rowsBefore
    ? `yes ("${said}" under the filter its first chip set, ${rowsBefore} -> ${rowsAfter} rows)`
    : `NO - opened ${opened}, reads "${said}" for ${chips} chips, ${survived} after the press, rows ${rowsBefore} -> ${rowsAfter}`]];
}

/**
 * `⋮` holds the four exports, the shortcut legend and compact rows; `exportMenu` presses it and
 * requests every export link, because the hrefs are filled in as it opens. It moved here from the
 * top bar's probe with the menu. With no session the exports are hidden: there is nothing to export.
 */
async function probeMoreMenu(ctx) {
  const { doc, ORIGIN } = ctx;
  const hasSession = Boolean(doc.querySelector('#session-picker option'));
  const lines = await withMore(ctx, async (menu) => {
    const items = [...menu.querySelectorAll('.menu-item')];
    const exports = items.filter((i) => i.classList.contains('export-item'));
    const rest = items.filter((i) => !i.classList.contains('export-item')).map((i) => i.textContent);
    const listed = exports.length === 4 && rest.length === 2 && rest[0] === 'keyboard shortcuts' && rest[1].startsWith('compact rows');
    if (!hasSession) {
      return [['more menu', listed ? 'yes (4 exports, keyboard shortcuts, compact rows)' : `NO - ${exports.length} exports, then ${rest.join(', ')}`],
        ['exportMenu', exports.every((a) => a.hidden) ? 'yes (hidden: nothing to export)' : 'NO - offered with no session']];
    }
    const id = encodeURIComponent(doc.getElementById('session-picker').value);
    const expected = ['export.html', 'export.md', 'export.json', 'har'].map((p) => `/api/sessions/${id}/${p}`);
    const hrefs = exports.map((a) => a.getAttribute('href'));
    const statuses = await Promise.all(hrefs.map((h) => fetch(ORIGIN + h).then((r) => r.status).catch(() => 0)));
    return [
      ['more menu', listed ? `yes (${exports.map((a) => a.dataset.export).join(', ')}, ${rest.join(', ')})` : `NO - ${exports.length} exports, then ${rest.join(', ') || 'nothing'}`],
      ['exportMenu', !menu.hidden && JSON.stringify(hrefs) === JSON.stringify(expected) && exports.every((a) => !a.hidden) && statuses.every((c) => c === 200)
        ? `yes (${hrefs.length} links on ⋮, all 200)`
        : `NO - opened ${!menu.hidden}, hrefs ${hrefs.join(' ')}, statuses ${statuses.join(' ')}`],
    ];
  });
  return lines ?? [['more menu', 'NO - there is no ⋮'], ['exportMenu', 'NO - there is no ⋮']];
}

/**
 * Density has one writer, `setDensity`, behind both of its controls: `⋮ › compact rows` sets the
 * body class and `inspector.density`, the Settings page's control then shows `compact`, and that
 * control set back to `comfortable` clears both and unticks the menu item. Left as it was found.
 */
async function probeDensity(ctx) {
  const { window, doc } = ctx;
  const select = doc.getElementById('density');
  const was = select.value;
  const stored = () => window.localStorage.getItem('inspector.density');
  const compact = () => doc.body.classList.contains('density-compact');
  await withMore(ctx, () => press(ctx, doc.getElementById('density-item'), 60));
  const fromMenu = { body: compact(), pref: stored(), settings: select.value };
  select.value = 'comfortable';
  select.dispatchEvent(new window.Event('change', { bubbles: true }));
  await pause(60);
  const fromSettings = { body: compact(), pref: stored(), item: doc.getElementById('density-item').getAttribute('aria-checked') };
  select.value = was;
  select.dispatchEvent(new window.Event('change', { bubbles: true }));
  await pause(60);
  return [['density', fromMenu.body && fromMenu.pref === 'compact' && fromMenu.settings === 'compact'
    && !fromSettings.body && fromSettings.pref === 'comfortable' && fromSettings.item === 'false'
    ? 'yes (⋮ › compact rows sets the class, the pref and Settings; Settings back to comfortable clears all three)'
    : `NO - from ⋮: class ${fromMenu.body}, pref ${fromMenu.pref}, Settings ${fromMenu.settings}; `
      + `from Settings: class ${fromSettings.body}, pref ${fromSettings.pref}, item checked ${fromSettings.item}`]];
}

module.exports = {
  probeDrawer, probeRowParts, probeNowStrip, probeOverview,
  probeToolbar, probeToolbarControls, probeEndpointsMenu, probeMoreMenu, probeDensity,
};
