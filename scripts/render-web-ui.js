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
  const orderNewest = doc.getElementById('order-newest');
  const orderOldest = doc.getElementById('order-oldest');
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
  const startedNewest = orderNewest.classList.contains('on');
  await click(startedNewest ? orderOldest : orderNewest);
  const afterFlip = rowIds();
  const afterSequence = sequence();
  const reversed =
    beforeFlip.length > 1 &&
    afterFlip.length === beforeFlip.length &&
    afterFlip.every((id, i) => id === beforeFlip[beforeFlip.length - 1 - i]);
  // Exactly one chip must read as selected. Two lit chips, or none, is a control that no longer
  // says what the list is doing.
  const litChips = [orderOldest, orderNewest].filter((c) => c.classList.contains('on')).length;
  const flippedLabel = `${litChips} chip lit`;
  await click(startedNewest ? orderNewest : orderOldest);
  const restored = rowIds().every((id, i) => id === beforeFlip[i]);


  /**
   * The timeline view: lanes, spans, provenance, and the payload viewer.
   *
   * Driven through the real toggle and real clicks rather than by calling render functions, for
   * the same reason the endpoint-chip probe is: asserting on state would only prove a variable
   * changed, not that the page turned data into rows.
   */
  async function probeSignals() {
    const tabFor = (id) => [...doc.querySelectorAll('#tabs .tab')].find((t) => t.dataset.view === id);
    const allTab = tabFor('all');
    const result = {
      switchShown: Boolean(allTab),
      defaultView: (doc.querySelector('#tabs .tab.active') || {}).dataset?.view || 'none',
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

    // Into the merged timeline, however the session happened to open.
    await click(allTab);
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

  // probeSignals selects a signal, which replaces the transaction detail. Put a transaction back
  // before the report reads the detail pane, or `detail sections` measures the wrong pane.
  await click(doc.querySelector('#tabs .tab[data-view="network"]'));
  await new Promise((r) => setTimeout(r, 300));
  const backToRow = doc.querySelector('#list .row');
  if (backToRow) {
    await click(backToRow);
    await new Promise((r) => setTimeout(r, 500));
  }

  /**
   * Tabs: one per kind of thing the session recorded.
   *
   * The load-bearing assertion is that a tag this build has never heard of still gets a tab. The
   * schema says tags are app-defined; a tab bar built from a hardcoded list would make Inspector
   * quietly under-report what the app recorded, and that failure looks like nothing at all.
   */
  function probeTabs() {
    const tabs = [...doc.querySelectorAll('#tabs .tab')];
    const ids = tabs.map((t) => t.dataset.view);
    const knownIds = ['all', 'network', 'cache', 'screen', 'state'];
    return {
      count: tabs.length,
      ids,
      unknownTagsTabbed: ids.filter((id) => !knownIds.includes(id)),
      activeCount: tabs.filter((t) => t.classList.contains('active')).length,
      hasNetwork: ids.includes('network'),
    };
  }

  /**
   * The tag browser: does a key list plus one key's detail come out of app-defined payloads?
   *
   * Driven through the real tab, real input and real clicks rather than by calling render
   * functions — asserting on state would only prove a variable changed, not that the page drew
   * anything.
   */
  async function probeBrowser(tag) {
    const tab = [...doc.querySelectorAll('#tabs .tab')].find((t) => t.dataset.view === tag);
    const result = {
      tag,
      tabShown: Boolean(tab),
      keys: 0,
      observations: 0,
      facets: [],
      dotStates: [],
      detailRendered: false,
      valueShown: 'n/a',
      historyRows: 0,
      historySwitches: 'n/a',
      keyFilterWorks: 'n/a',
      facetFilterWorks: 'n/a',
      pullButton: 'n/a',
      anyValueRendered: 'n/a',
    };
    if (!tab) return result;

    await click(tab);
    await new Promise((r) => setTimeout(r, 700));

    const keysOf = () => [...doc.querySelectorAll('#browser-list .bkey')];
    const keys = keysOf();
    result.keys = keys.length;
    const countText = doc.getElementById('browser-count').textContent;
    result.observations = Number((countText.match(/(\d+) observations/) || [])[1] || 0);
    result.facets = [...doc.querySelectorAll('#browser-facets .facet')].map(
      (f) => f.querySelector('.facet-label').textContent,
    );
    result.dotStates = [...new Set(keys.map((k) => {
      const dot = k.querySelector('.bkey-dot');
      return dot.className.replace('bkey-dot', '').trim() || 'unknown';
    }))].sort();
    if (!keys.length) return result;

    // --- detail ------------------------------------------------------------
    // The key with the longest history, because that is the one whose history list and
    // observation switching can actually be exercised.
    const withHistory = keys.find((k) => /\d+×/.test(k.textContent)) || keys[0];
    await click(withHistory);
    await new Promise((r) => setTimeout(r, 500));

    // Whether *this* key has a value depends on what happened to it — a cleared entry correctly
    // has none — so the "did any payload render at all" assertion walks until one does. Without
    // it, landing on a cleared key reports no value and calls that a pass.
    result.anyValueRendered = 'NO - no key in this tag rendered a payload';
    for (const key of keys) {
      await click(key);
      await new Promise((r) => setTimeout(r, 250));
      const body = doc.querySelector('#browser-detail .bvalue-body');
      if (body && body.textContent.trim()) {
        result.anyValueRendered = `yes (${key.querySelector('.bkey-name').textContent})`;
        break;
      }
    }
    await click(withHistory);
    await new Promise((r) => setTimeout(r, 400));

    result.detailRendered = Boolean(doc.querySelector('#browser-detail .bdetail-key'));
    const pre = doc.querySelector('#browser-detail .bvalue-body');
    const none = doc.querySelector('#browser-detail .bvalue-none');
    // A pretty-printed payload is the point of the detail panel — a one-line blob is what the
    // flat table already did badly, so the assertion is on the newlines, not on the presence.
    result.valueShown = pre
      ? `yes (${pre.textContent.length} chars, ${pre.textContent.split('\n').length} lines)`
      : none
        ? `no value: "${none.textContent.trim().slice(0, 48)}"`
        : 'NO - neither a value nor an explanation';

    const historyItems = () => [...doc.querySelectorAll('#browser-detail .bhistory-item')];
    result.historyRows = historyItems().length;
    if (result.historyRows > 1) {
      const before = doc.querySelector('#browser-detail .bhistory-item.sel');
      const other = historyItems().find((i) => !i.classList.contains('sel'));
      await click(other);
      await new Promise((r) => setTimeout(r, 400));
      const after = doc.querySelector('#browser-detail .bhistory-item.sel');
      result.historySwitches =
        after && before && after.textContent !== before.textContent
          ? `yes (${before.textContent.trim()} -> ${after.textContent.trim()})`
          : 'NO - selecting another observation changed nothing';
    }

    // --- filters, each asserted to actually narrow the list -----------------
    const input = doc.getElementById('browser-filter');
    // A token belonging to exactly one key. Neither end of a cache key is safe to guess at: the
    // head is a shared namespace prefix and the tail is a shared scope epoch, so filtering on
    // either matches everything and the assertion passes while proving nothing.
    // The same three haystacks `groupMatches` searches: what the list shows, the raw stored key,
    // and the storage key on the row's title. Modelling only the display name reports a filter as
    // broken when it merely matched on one of the other two.
    const haystacks = keys.map((k) => [
      decodeURIComponent(String(k.dataset.key)),
      String(k.dataset.key),
      k.querySelector('.bkey-name').title || '',
    ].map((t) => t.toLowerCase()));
    const names = haystacks.map((h) => h[0]);
    // A token that *narrows* — matching at least one key and not all of them. Insisting on a
    // uniquely-matching token does not work here: the same logical key appears once per scope
    // epoch, so almost nothing matches exactly one, and the search falls back to the whole key.
    // Filtering by the entire string it was given is a test that cannot fail.
    const tokens = [...new Set(names.flatMap((n) => n.split(/[^A-Za-z0-9]+/)))]
      .filter((t) => t.length > 3);
    const hits = (t) => haystacks.filter((h) => h.some((k) => k.includes(t.toLowerCase()))).length;
    const token = tokens.find((t) => hits(t) > 0 && hits(t) < haystacks.length) || names[0];
    input.value = token;
    input.dispatchEvent(new window.Event('input', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 200));
    const narrowed = keysOf().length;
    result.keyFilterWorks =
      narrowed > 0 && narrowed < result.keys
        ? `yes (${result.keys} -> ${narrowed} for "${token}")`
        : narrowed === result.keys
          ? `NO - matched every key for "${token}"`
          : `NO (${result.keys} -> ${narrowed})`;
    input.value = '';
    input.dispatchEvent(new window.Event('input', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 200));

    const facetChip = doc.querySelector('#browser-facets .chip-facet');
    if (facetChip) {
      const label = facetChip.textContent;
      await click(facetChip);
      await new Promise((r) => setTimeout(r, 200));
      const on = keysOf().length;
      // Re-queried, not reused: renderFacets rebuilds the chips, so the node clicked a moment ago
      // is detached and its classList says nothing about what is on screen now.
      const relit = [...doc.querySelectorAll('#browser-facets .chip-facet')]
        .find((c) => c.textContent === label);
      const lit = Boolean(relit && relit.classList.contains('on'));
      // Clicking the lit chip again must clear it, or a facet is a one-way door.
      await click(relit || facetChip);
      await new Promise((r) => setTimeout(r, 200));
      const restoredKeys = keysOf().length;
      result.facetFilterWorks =
        lit && on <= result.keys && restoredKeys === result.keys
          ? `yes (${result.keys} -> ${on} -> ${restoredKeys}, chip toggles off)`
          : `NO (lit=${lit}, ${result.keys} -> ${on} -> ${restoredKeys})`;
    }

    const pull = doc.getElementById('browser-pull');
    result.pullButton = pull.hidden ? 'hidden (no provider seen)' : 'present and enabled';
    return result;
  }

  /**
   * Ages must move on their own.
   *
   * They used to be measured against the newest observation in the session, which has no clock
   * in it: it cannot tick, a refresh never moves it, and a pull moves every row at once. The
   * check is therefore that the *text* changes across a second without anything re-rendering —
   * the property the old implementation could not have had.
   */
  async function probeAges() {
    const node = doc.querySelector('#current .current-age') || doc.querySelector('.bkey-age');
    if (!node) return { present: false, ticks: 'n/a' };
    const before = node.textContent;
    // `window.Date` is not Node's `Date`: the app runs inside the jsdom realm, so overriding the
    // outer one moves a clock nothing reads. That mistake makes this probe report a stuck age
    // whatever the page does.
    const realNow = window.Date.now;
    // Jump the clock rather than waiting out a real minute; the ticker reads Date.now() on every
    // repaint, so this is the same code path a slow-moving age takes. The jump has to be big
    // enough to cross a bucket from *any* starting age — +2 minutes leaves an hour-old row still
    // reading "1h ago", which is a pass that proves nothing.
    window.Date.now = () => realNow() + 25 * 3600 * 1000;
    await new Promise((r) => setTimeout(r, 1400));
    const after = node.textContent;
    window.Date.now = realNow;
    return {
      present: true,
      before,
      after,
      ticks: before !== after ? `yes ("${before}" -> "${after}")` : `NO - stuck at "${before}"`,
    };
  }

  /**
   * Sessions can be deleted from the UI, and the one being recorded cannot.
   *
   * Nothing is actually deleted here — the probe runs against the user's real archive. It checks
   * that the controls exist, that a live session's delete is disabled, and that the destructive
   * buttons arm before they fire rather than deleting on the first click.
   */
  async function probeSessions() {
    const rows = [...doc.querySelectorAll('#session-rows .session-row')];
    const clearAll = doc.getElementById('sessions-clear');
    const result = {
      rows: rows.length,
      disabledDeletes: rows.filter((r) => r.querySelector('button').disabled).length,
      clearArms: 'n/a',
      headerDelete: Boolean(doc.getElementById('session-delete')),
    };
    if (clearAll) {
      const idle = clearAll.textContent;
      clearAll.dispatchEvent(new window.Event('click', { bubbles: true }));
      await new Promise((r) => setTimeout(r, 80));
      const armed = clearAll.textContent !== idle && clearAll.classList.contains('armed');
      // Disarm without firing: a second click here would wipe the archive this probe is reading.
      clearAll.dataset.armed = '0';
      clearAll.textContent = idle;
      clearAll.classList.remove('armed');
      result.clearArms = armed
        ? `yes (one click armed it, it did not delete)`
        : 'NO - the first click was not a confirmation step';
    }
    return result;
  }

  const tabProbe = probeTabs();
  const browserProbe = await probeBrowser('cache');
  const stateProbe = await probeBrowser('state');
  const ageProbe = await probeAges();

  /**
   * A hidden pane must actually be invisible.
   *
   * `hidden` only works because of a user agent rule, `[hidden] { display: none }`, and any author
   * rule setting `display` on the same element outranks it — the pane then paints over whichever
   * view is selected while its `hidden` property still reads true. This is checked against the
   * stylesheet text rather than through `getComputedStyle`, because jsdom resolves a hidden
   * element to `display: none` whatever the CSS says: the computed-style version of this check
   * passes just as happily with the guard rule deleted, which makes it worse than no check at all.
   */
  function auditHiddenPanes(cssText) {
    // Panes the app shows and hides by toggling `hidden`.
    const panes = ['.browser', '#browser', '.tabs', '#tabs', '#timeline', '#list'];
    const setsDisplay = new Set();
    const guarded = new Set();
    for (const [, selector, body] of cssText.matchAll(/([.#][A-Za-z0-9_-]+)\s*\{([^}]*)\}/g)) {
      if (/(^|;)\s*display\s*:/.test(body)) setsDisplay.add(selector);
    }
    for (const [, selector] of cssText.matchAll(
      /([.#][A-Za-z0-9_-]+)\[hidden\]\s*\{[^}]*display\s*:\s*none/g,
    )) {
      guarded.add(selector);
    }
    const offenders = panes.filter((s) => setsDisplay.has(s) && !guarded.has(s));
    return offenders.length
      ? `NO - ${offenders.join(', ')} sets display without a [hidden] override`
      : 'yes';
  }

  const hiddenPaneAudit = auditHiddenPanes(css);

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
  // Only reachable once the dialog is open, which is also the only place the session list lives.
  const sessionProbe = await probeSessions();

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
  // Scoped to the list. The timeline draws transactions too, so an unscoped count double-counts
  // every duplicate and turns "badges must equal rows" into a permanent, meaningless failure.
  const dupBadges = [...doc.querySelectorAll('#list .dup-badge')].length;
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
  console.error('all tab present    :', signalProbe.switchShown);
  console.error('opened on view     :', signalProbe.defaultView, "('all' when the session has signals)");
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
  console.error('--- sessions ---');
  console.error('session rows       :', sessionProbe.rows);
  console.error('deletes disabled   :', sessionProbe.disabledDeletes, '(sessions still recording)');
  console.error('clear all arms     :', sessionProbe.clearArms);
  console.error('header delete      :', sessionProbe.headerDelete);

  console.error('--- tabs ---');
  console.error('tabs               :', tabProbe.count, `(${tabProbe.ids.join(', ')})`);
  console.error('exactly one active :', tabProbe.activeCount === 1, `(${tabProbe.activeCount})`);
  console.error('unknown tags tabbed:', tabProbe.unknownTagsTabbed.join(', ') || 'none in this session');

  for (const probe of [browserProbe, stateProbe]) {
    console.error(`--- ${probe.tag} browser ---`);
    console.error('tab present        :', probe.tabShown);
    if (!probe.tabShown) continue;
    console.error('keys               :', probe.keys);
    console.error('observations       :', probe.observations);
    console.error('facets             :', probe.facets.join(', ') || 'none (fewer than two values)');
    console.error('freshness dots     :', probe.dotStates.join(', ') || 'none');
    console.error('detail rendered    :', probe.detailRendered);
    console.error('value              :', probe.valueShown);
    console.error('some key has value :', probe.anyValueRendered);
    console.error('history rows       :', probe.historyRows);
    console.error('history switches   :', probe.historySwitches);
    console.error('key filter         :', probe.keyFilterWorks);
    console.error('facet filter       :', probe.facetFilterWorks);
    console.error('pull button        :', probe.pullButton);
  }

  console.error('--- ages ---');
  console.error('age nodes present  :', ageProbe.present);
  console.error('ages tick          :', ageProbe.ticks);
  console.error('hidden panes hide  :', hiddenPaneAudit);
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
