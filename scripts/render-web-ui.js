/* Smoke-test and snapshot the web UI.
 *
 * Runs the REAL app.js against the REAL index.html with fetch proxied to a running daemon, then
 * reports what actually rendered. This exists because the web UI is the one surface with no
 * Kotlin test behind it — everything below the HTTP boundary is covered by :inspector-daemon
 * tests, but whether the page turns that data into rows is otherwise unverified.
 *
 * Usage:
 *   inspector-daemon/build/install/inspector/bin/inspector serve &
 *   ./gradlew :sample:desktop:run -Dinspector.sample.autofire=true
 *   npm install jsdom && node scripts/render-web-ui.js > /tmp/ui.html
 *
 * INSPECTOR_UI_SESSION=<id or substring> targets a session other than the newest.
 *
 * The report goes to stderr; a self-contained static snapshot goes to stdout.
 * A healthy run reports non-zero rows and 'errors: none'.
 */
const fs = require('fs');

let JSDOM, VirtualConsole;
try {
  ({ JSDOM, VirtualConsole } = require('jsdom'));
} catch {
  console.error('This script needs jsdom:\n  npm install jsdom\n(node_modules/ is gitignored.)');
  process.exit(2);
}

const WEB = require('path').join(__dirname, '..', 'inspector-daemon', 'src', 'main', 'resources', 'web');
const ORIGIN = 'http://127.0.0.1:8099';

const css = fs.readFileSync(`${WEB}/style.css`, 'utf8');
const js = fs.readFileSync(`${WEB}/app.js`, 'utf8');
let html = fs.readFileSync(`${WEB}/index.html`, 'utf8');

html = html
  .replace('<link rel="stylesheet" href="/style.css">', `<style>\n${css}\n</style>`)
  .replace('<script src="/app.js"></script>', '');

const errors = [];
const virtualConsole = new VirtualConsole();
virtualConsole.on('jsdomError', (e) => errors.push(`jsdomError: ${e.message}`));
virtualConsole.on('error', (...a) => errors.push(`console.error: ${a.join(' ')}`));

const dom = new JSDOM(html, {
  runScripts: 'outside-only',
  pretendToBeVisual: true,
  url: ORIGIN + '/',
  virtualConsole,
});

const { window } = dom;

// Proxy the page's fetch to the live daemon.
window.fetch = (path, init) =>
  fetch(path.startsWith('http') ? path : ORIGIN + path, init);

// The live socket is irrelevant to a static snapshot.
window.WebSocket = class {
  constructor() { setTimeout(() => this.onopen && this.onopen(), 0); }
  close() {}
};
window.navigator.clipboard = { writeText: async () => {} };

(async () => {
  window.eval(js);

  // Let init() finish its fetches, then select a row so the detail pane renders too.
  await new Promise((r) => setTimeout(r, 1500));

  // The page opens on the newest session, which is whatever ran last on this machine and not
  // necessarily the one worth snapshotting. INSPECTOR_UI_SESSION picks another by id or by
  // substring, so a session with real endpoint variety can be exercised on purpose.
  const wanted = process.env.INSPECTOR_UI_SESSION;
  if (wanted) {
    const picker = window.document.getElementById('session-picker');
    const match = [...picker.options].find((o) => o.value === wanted || o.value.includes(wanted));
    if (!match) {
      console.error(`no session matches INSPECTOR_UI_SESSION=${wanted}; using the newest.`);
    } else {
      picker.value = match.value;
      picker.dispatchEvent(new window.Event('change', { bubbles: true }));
      await new Promise((r) => setTimeout(r, 900));   // settle() is defined further down

    }
  }

  const rows = window.document.querySelectorAll('#list .row');
  const interesting =
    [...rows].find((r) => r.querySelector('.status')?.textContent === '500') || rows[rows.length - 1];
  if (interesting) interesting.dispatchEvent(new window.Event('click', { bubbles: true }));
  await new Promise((r) => setTimeout(r, 900));

  const doc = window.document;

  /**
   * Wait for the list to stop changing, rather than sleeping a hopeful number of milliseconds.
   *
   * `renderList` clears the list and re-appends, and a fetch sits in front of it, so a fixed sleep
   * can sample a half-built list — a read taken mid-render reported 6 rows for a 10-row session.
   * That makes a count assertion pass or fail on machine speed. Two identical consecutive samples
   * mean the render finished.
   */
  const settle = async (timeoutMs = 4000) => {
    const sample = () => [
      doc.querySelectorAll('#list .row').length,
      doc.querySelectorAll('#endpoint-chips .chip').length,
      doc.getElementById('counts').textContent,
    ].join('|');

    const deadline = Date.now() + timeoutMs;
    let previous = null;
    while (Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 120));
      const current = sample();
      if (current === previous) return;
      previous = current;
    }
    console.error(`settle() timed out after ${timeoutMs}ms — counts below may be mid-render.`);
  };

  /**
   * Click the first endpoint chip and check three things at once.
   *
   * The load-bearing one is `survived`. Chips are derived from the *unfiltered* session; if that
   * ever regresses to the filtered rows the list would collapse to the single chip just clicked,
   * and every other endpoint would become unreachable in one click. That failure looks like a
   * cosmetic glitch and is really a dead end, so it is asserted rather than eyeballed.
   */
  async function probeEndpointChip() {
    const chip = doc.querySelector('#endpoint-chips .chip');
    const before = doc.querySelectorAll('#list .row').length;
    if (!chip) return { before, after: before, narrowed: 'no chips', survived: 'n/a', active: 'n/a', chipsAfter: 0 };

    const chipsBefore = doc.querySelectorAll('#endpoint-chips .chip').length;
    const label = chip.firstChild.textContent;
    chip.dispatchEvent(new window.Event('click', { bubbles: true }));
    await settle();

    const after = doc.querySelectorAll('#list .row').length;
    const chipsAfter = doc.querySelectorAll('#endpoint-chips .chip').length;
    const actives = [...doc.querySelectorAll('#endpoint-chips .chip.active')];

    const result = {
      before,
      after,
      chipsAfter,
      narrowed: after > 0 && after < before,
      survived: chipsAfter === chipsBefore,
      active: actives.length === 1 && actives[0].firstChild.textContent === label,
    };

    // Put the page back the way it was, so the snapshot and every later count are unaffected.
    chip.dispatchEvent(new window.Event('click', { bubbles: true }));
    await settle();
    return result;
  }

  const chipProbe = await probeEndpointChip();

  /**
   * The configurable cap, driven through the settings input rather than the state object.
   *
   * Going through the real `change` event is the point: it covers the listener, the localStorage
   * write and the re-render together. Asserting on `state.endpointLimit` would only prove a number
   * was stored somewhere.
   */
  async function probeEndpointLimit() {
    const input = doc.getElementById('endpoint-limit');
    const chipCount = () => doc.querySelectorAll('#endpoint-chips .chip').length;
    const setLimit = async (n) => {
      input.value = String(n);
      input.dispatchEvent(new window.Event('change', { bubbles: true }));
      await settle();
    };

    const original = input.value;
    await setLimit(3);
    const capped = chipCount();
    const summary = doc.getElementById('endpoint-summary').textContent;
    await setLimit(0);
    const off = chipCount() === 0 && doc.getElementById('endpoint-chips').hidden;
    await setLimit(original);
    return { capped, off, restored: chipCount(), summary };
  }

  const limitProbe = await probeEndpointLimit();

  // Sort toggle: flip it, confirm the rendered order actually reverses, flip back. Comparing the
  // real .row order matters — asserting on the state flag would only prove the flag changed, not
  // that the list re-rendered from it.
  const sortButton = doc.getElementById('sort-order');
  const rowIds = () => [...doc.querySelectorAll('#list .row')].map((r) => r.dataset.id);
  const click = async (node) => {
    node.dispatchEvent(new window.Event('click', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 100));
  };

  // Row/divider sequence, so a divider that detaches from its rows on reversal is visible here
  // rather than only on screen. R = transaction, M = marker divider.
  const sequence = () =>
    [...doc.getElementById('list').children]
      .map((n) => (n.classList.contains('marker-divider') ? 'M' : 'R'))
      .join('');

  const beforeFlip = rowIds();
  const beforeSequence = sequence();
  await click(sortButton);
  const afterFlip = rowIds();
  const afterSequence = sequence();
  const reversed =
    beforeFlip.length > 1 &&
    afterFlip.length === beforeFlip.length &&
    afterFlip.every((id, i) => id === beforeFlip[beforeFlip.length - 1 - i]);
  const flippedLabel = sortButton.textContent;
  await click(sortButton);
  const restored = rowIds().every((id, i) => id === beforeFlip[i]);


  /**
   * The timeline view: lanes, spans, provenance, and the payload viewer.
   *
   * Driven through the real toggle and real clicks rather than by calling render functions, for
   * the same reason the endpoint-chip probe is: asserting on state would only prove a variable
   * changed, not that the page turned data into rows.
   */
  async function probeSignals() {
    const switchEl = doc.getElementById('view-switch');
    const result = {
      switchShown: switchEl && !switchEl.hidden,
      defaultView: doc.getElementById('view-timeline')?.classList.contains('active')
        ? 'timeline'
        : 'traffic',
      entries: 0,
      lanes: [],
      unknownTagRendered: 'n/a',
      spans: 0,
      points: 0,
      triggers: [],
      merged: 'n/a',
      currentRows: 0,
      currentHasAge: false,
      payloadShown: 'n/a',
      pushedWarned: 'n/a',
    };
    if (!result.switchShown) return result;

    // Into the timeline, however the session happened to open.
    await click(doc.getElementById('view-timeline'));
    await new Promise((r) => setTimeout(r, 400));

    const children = [...doc.getElementById('timeline').children];
    result.entries = children.length;
    result.lanes = [...new Set(children.map((n) => n.dataset.lane).filter(Boolean))].sort();
    result.spans = doc.querySelectorAll('#timeline .tl-span').length;
    result.points = doc.querySelectorAll('#timeline .tl-point').length;
    result.triggers = [...new Set(
      [...doc.querySelectorAll('#timeline .tl-trigger')].map((n) => n.textContent),
    )].sort();

    // A tag this build has no styling for must still render, in the generic lane. Dropping it
    // would silently discard whatever an app chose to record.
    const tags = [...doc.querySelectorAll('#timeline .tl-signal')].map((n) => n.dataset.tag);
    const known = ['screen', 'state', 'cache', 'session'];
    const unknown = tags.filter((t) => !known.includes(t));
    if (unknown.length) {
      const laneOf = [...doc.querySelectorAll('#timeline .tl-signal')]
        .filter((n) => !known.includes(n.dataset.tag))
        .map((n) => n.dataset.lane);
      result.unknownTagRendered = laneOf.every((l) => l === 'other')
        ? `yes (${unknown.length} in lane 'other')`
        : `NO - landed in ${laneOf.join(',')}`;
    } else {
      result.unknownTagRendered = 'no unknown tags in this session';
    }

    // The merge is the whole point: traffic and signals must interleave by mono, not sit in
    // separate blocks. Check that both kinds appear and that the order is monotonic.
    const kinds = children.map((n) => n.dataset.kind).filter(Boolean);
    const hasBoth = kinds.includes('txn') && kinds.includes('signal');
    const firstSignal = kinds.indexOf('signal');
    const lastTxn = kinds.lastIndexOf('txn');
    result.merged = hasBoth
      ? firstSignal < lastTxn
        ? 'yes (interleaved)'
        : 'NO - signals all after traffic'
      : `n/a (kinds: ${[...new Set(kinds)].join(',') || 'none'})`;

    // The "Now" panel.
    const currentItems = [...doc.querySelectorAll('#current .current-item')];
    result.currentRows = currentItems.length;
    result.currentHasAge = currentItems.every((n) => n.querySelector('.current-age'));

    // Click a signal and confirm the payload viewer actually fetched and rendered it.
    const withPayload = [...doc.querySelectorAll('#timeline .tl-signal')].find(
      (n) => n.querySelector('.tl-bytes'),
    );
    if (withPayload) {
      await click(withPayload);
      await new Promise((r) => setTimeout(r, 600));
      const pre = doc.querySelector('#detail pre.body');
      const text = pre ? pre.textContent : '';
      result.payloadShown =
        text && text !== 'loading...' && !text.startsWith('could not read')
          ? `yes (${text.length} chars)`
          : `NO (${text ? text.slice(0, 40) : 'no pre'})`;
      const pushed = withPayload.querySelector('.tl-pushed');
      const note = doc.querySelector('#detail .detail-note');
      result.pushedWarned = pushed ? Boolean(note) : 'n/a (row was pulled)';
    }

    return result;
  }

  const signalProbe = await probeSignals();

  /**
   * The cache view: does the table turn app-defined payloads into columns, and do the filters
   * actually filter?
   *
   * Driven through the real toggle and real inputs rather than by calling render functions —
   * asserting on state would only prove a variable changed, not that the page drew a table.
   */
  async function probeCache() {
    const button = doc.getElementById('view-cache');
    const result = {
      tabShown: Boolean(button) && !button.hidden,
      rows: 0,
      columnsFilled: 'n/a',
      distinctKeys: 0,
      triggers: [],
      kinds: [],
      storages: [],
      scopeOptions: 0,
      keyFilterWorks: 'n/a',
      expiredFilterWorks: 'n/a',
      latestOnlyCollapses: 'n/a',
      pullButton: 'n/a',
    };
    if (!result.tabShown) return result;

    await click(button);
    await new Promise((r) => setTimeout(r, 600));

    const rowsOf = () => [...doc.querySelectorAll('#cache-rows .cache-row')];
    const rows = rowsOf();
    result.rows = rows.length;
    if (!rows.length) return result;

    result.distinctKeys = new Set(rows.map((r) => r.dataset.key)).size;
    result.triggers = [...new Set(
      [...doc.querySelectorAll('#cache-rows .tl-trigger')].map((n) => n.textContent),
    )].sort();
    result.kinds = [...new Set(
      [...doc.querySelectorAll('#cache-rows .cache-kind')].map((n) => n.textContent),
    )].sort();
    result.storages = [...new Set(
      rows.map((r) => r.querySelector('.cache-col-storage')?.textContent).filter((s) => s && s !== '—'),
    )].sort();
    result.scopeOptions = doc.getElementById('cache-filter-scope').options.length;

    // Every column should carry something for at least one row; a table of em-dashes means the
    // payload convention was not read at all.
    const cellText = (r, col) => {
      // The value column carries a byte count beside the value; reading the whole cell would
      // count "77 B —" as a filled value, which is the opposite of what this checks.
      const node = col === 'value' ? r.querySelector('.cache-value') : r.querySelector(`.cache-col-${col}`);
      return node ? node.textContent.trim() : '';
    };
    const filled = ['time', 'key', 'storage', 'scope', 'expired', 'value'].filter((col) =>
      rows.some((r) => cellText(r, col) && cellText(r, col) !== '—'),
    );
    result.columnsFilled = `${filled.length}/6 (${filled.join(',')})`;
    result.valuesPresent = rows.filter((r) => cellText(r, 'value') !== '—').length;

    // --- filters, each asserted to actually narrow the table -----------------
    const keyInput = doc.getElementById('cache-filter-key');
    // A token that belongs to exactly one row. Neither end of a cache key is safe to guess at:
    // the head is a shared namespace prefix and the tail is a shared scope epoch, so filtering on
    // either matches everything and the assertion passes while proving nothing.
    const keys = rows.map((r) => String(r.dataset.key));
    const someKey =
      (keys[0].split(/[^A-Za-z0-9]+/).filter((t) => t.length > 3)
        .find((token) => keys.filter((k) => k.includes(token)).length === 1)) || keys[0];
    keyInput.value = someKey;
    keyInput.dispatchEvent(new window.Event('input', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 150));
    const narrowed = rowsOf().length;
    result.keyFilterWorks =
      narrowed > 0 && narrowed < result.rows
        ? `yes (${result.rows} -> ${narrowed} for "${someKey}")`
        : narrowed === result.rows
          ? `NO - matched every row for "${someKey}"`
          : `NO (${result.rows} -> ${narrowed})`;
    keyInput.value = '';
    keyInput.dispatchEvent(new window.Event('input', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 150));

    const expiredSelect = doc.getElementById('cache-filter-expired');
    expiredSelect.value = 'no';
    expiredSelect.dispatchEvent(new window.Event('input', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 150));
    const live = rowsOf().length;
    expiredSelect.value = '';
    expiredSelect.dispatchEvent(new window.Event('input', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 150));
    result.expiredFilterWorks =
      rowsOf().length === result.rows ? `yes (live only: ${live}, restored: ${result.rows})` : 'NO - did not restore';

    // "latest per key" must collapse to exactly one row per key, which is what "cached now" means.
    const latest = doc.getElementById('cache-latest-only');
    latest.checked = true;
    latest.dispatchEvent(new window.Event('change', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 150));
    const collapsed = rowsOf();
    const collapsedKeys = new Set(collapsed.map((r) => r.dataset.key)).size;
    result.latestOnlyCollapses =
      collapsed.length === collapsedKeys && collapsedKeys === result.distinctKeys
        ? `yes (${result.rows} -> ${collapsed.length}, one per key)`
        : `NO (${collapsed.length} rows for ${collapsedKeys} keys, expected ${result.distinctKeys})`;
    latest.checked = false;
    latest.dispatchEvent(new window.Event('change', { bubbles: true }));

    const pull = doc.getElementById('cache-pull');
    result.pullButton = pull && !pull.disabled ? 'present and enabled' : 'MISSING or disabled';
    return result;
  }

  const cacheProbe = await probeCache();

  const methodClasses = [...new Set(
    [...doc.querySelectorAll('#list .row .method')].map((n) => n.className.replace('method ', '')),
  )].sort();

  // Settings dialog: jsdom has no real <dialog>, so showModal is stubbed before opening it.
  // The point is not the dialog chrome but that the peer list fetches and renders, and that a
  // `self` peer offers no kill button — the guard that keeps the UI from shooting this daemon.
  const settings = doc.getElementById('settings');
  if (settings && typeof settings.showModal !== 'function') settings.showModal = () => {};
  await click(doc.getElementById('open-settings'));
  await new Promise((r) => setTimeout(r, 600));
  const peerRows = [...doc.querySelectorAll('.peer')];
  const selfRows = peerRows.filter((n) => n.querySelector('.peer-self'));
  const peerRoles = [...new Set(peerRows.map((n) => {
    const role = n.querySelector('.peer-role');
    return role ? role.textContent : '?';
  }))].sort();
  const selfKillButtons = selfRows
    .filter((n) => [...n.querySelectorAll('button')].some((b) => b.textContent === 'kill'))
    .length;

  console.error('--- render report ---');
  console.error('rows rendered      :', doc.querySelectorAll('#list .row').length);
  console.error('sort flip reverses :', reversed, `(button then read "${flippedLabel}")`);
  console.error('sort flip restores :', restored);
  console.error(
    'list sequence      :', `${beforeSequence} -> ${afterSequence}`,
    afterSequence === [...beforeSequence].reverse().join('') ? '(exact mirror)' : '(NOT a mirror)',
  );
  console.error('method classes     :', methodClasses.join(', ') || 'none');
  console.error('marker dividers    :', doc.querySelectorAll('.marker-divider').length);
  console.error('session options    :', doc.querySelectorAll('#session-picker option').length);
  console.error('counts             :', doc.getElementById('counts').textContent);
  console.error('session label      :', doc.getElementById('session-app').textContent);
  console.error('markers listed     :', doc.querySelectorAll('#markers li').length);
  console.error('detail visible     :', !doc.getElementById('detail').hidden);
  console.error('detail sections    :', doc.querySelectorAll('#detail .section-title').length);
  console.error('body blocks        :', doc.querySelectorAll('#detail pre.body').length);
  console.error('chain rows         :', doc.querySelectorAll('#detail .chain-row').length);
  const dupRows = [...doc.querySelectorAll('#list .row.duplicate')];
  const dupBadges = [...doc.querySelectorAll('.dup-badge')].length;
  const clocks = [...doc.querySelectorAll('#list .row .clock')].filter((n) => n.textContent.trim()).length;

  console.error('duplicate rows     :', dupRows.length);
  console.error('duplicate badges   :', dupBadges, '(must equal the rows — colour alone is not enough)');
  console.error('start times shown  :', clocks, 'of', doc.querySelectorAll('#list .row').length);
  // Endpoint chips must be built from the whole session, not the filtered view, and each chip's
  // filter must be the anchored glob rather than a bare substring — a chip labelled `profile` that
  // also matched `profile/status` would quietly be lying.
  const endpointChips = [...doc.querySelectorAll('#endpoint-chips .chip')];
  const chipLabels = endpointChips.map(
    (c) => `${c.firstChild.textContent}(${c.querySelector('.chip-count')?.textContent ?? '?'})`,
  );
  const unanchored = endpointChips.filter((c) => !c.dataset.filter.startsWith('path:*/')).length;

  console.error('endpoint chips     :', endpointChips.length);
  console.error('endpoint labels    :', chipLabels.join(' ') || 'none');
  console.error('unanchored chips   :', unanchored, '(must be 0)');
  console.error('chip filters rows  :', chipProbe.narrowed, `(${chipProbe.before} -> ${chipProbe.after} rows)`);
  console.error('chips survive click:', chipProbe.survived, `(${endpointChips.length} -> ${chipProbe.chipsAfter})`);
  console.error('chip marks active  :', chipProbe.active, '(the clicked chip, and only it)');
  console.error('limit 3 caps to    :', limitProbe.capped, '(must be 3)');
  console.error('limit 0 hides      :', limitProbe.off);
  console.error('limit restored to  :', limitProbe.restored);
  console.error('limit summary      :', limitProbe.summary);
  console.error('--- signals ---');
  console.error('view switch shown  :', signalProbe.switchShown);
  console.error('opened on view     :', signalProbe.defaultView, '(timeline when the session has signals)');
  console.error('timeline entries   :', signalProbe.entries);
  console.error('lanes present      :', signalProbe.lanes.join(', ') || 'none');
  console.error('unknown tag renders:', signalProbe.unknownTagRendered);
  console.error('span glyphs        :', signalProbe.spans, '(cache/session: interval claims)');
  console.error('point glyphs       :', signalProbe.points, '(screen/state: instants)');
  console.error('trigger badges     :', signalProbe.triggers.join(', ') || 'none', '(provenance is never inferred)');
  console.error('traffic + signals  :', signalProbe.merged);
  console.error('now panel rows     :', signalProbe.currentRows);
  console.error('now rows show age  :', signalProbe.currentHasAge);
  console.error('payload viewer     :', signalProbe.payloadShown);
  console.error('pushed row warned  :', signalProbe.pushedWarned, '(a snapshot is not live state)');
  console.error('--- peers ---');
  console.error('peers listed       :', peerRows.length);
  console.error('peer roles         :', peerRoles.join(', ') || 'none');
  console.error('self has kill btn  :', selfKillButtons, '(must be 0)');
  console.error('--- cache view ---');
  console.error('cache tab shown    :', cacheProbe.tabShown);
  console.error('table rows         :', cacheProbe.rows);
  console.error('distinct keys      :', cacheProbe.distinctKeys);
  console.error('columns filled     :', cacheProbe.columnsFilled);
  console.error('rows with a value  :', cacheProbe.valuesPresent, 'of', cacheProbe.rows);
  console.error('storages seen      :', cacheProbe.storages.join(', ') || 'none');
  console.error('scope options      :', cacheProbe.scopeOptions, '(built from the rows, not hardcoded)');
  console.error('change kinds       :', cacheProbe.kinds.join(', ') || 'none');
  console.error('trigger badges     :', cacheProbe.triggers.join(', ') || 'none');
  console.error('key filter         :', cacheProbe.keyFilterWorks);
  console.error('expired filter     :', cacheProbe.expiredFilterWorks);
  console.error('latest per key     :', cacheProbe.latestOnlyCollapses);
  console.error('pull button        :', cacheProbe.pullButton);
  console.error('errors             :', errors.length ? errors.join(' | ') : 'none');

  // Freeze as a static, self-contained page.
  doc.querySelectorAll('script').forEach((s) => s.remove());
  const banner = doc.createElement('div');
  banner.setAttribute(
    'style',
    'padding:6px 14px;background:#3a2f10;color:#e0a030;font:12px system-ui;border-bottom:1px solid #2c3038',
  );
  banner.textContent =
    'Static snapshot of the live UI (rendered from the real app.js against a real session). ' +
    'The interactive version is at http://127.0.0.1:8099';
  doc.body.insertBefore(banner, doc.body.firstChild);

  console.log('<!doctype html>\n' + doc.documentElement.outerHTML);
})();
