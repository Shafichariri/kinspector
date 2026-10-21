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

module.exports = { probeDrawer, probeRowParts };
