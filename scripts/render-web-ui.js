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
 * INSPECTOR_UI_ORIGIN=http://127.0.0.1:PORT reads a daemon somewhere other than 8099.
 *
 * The report goes to stderr; a self-contained static snapshot goes to stdout.
 * A healthy run reports non-zero rows and 'errors: none'.
 *
 * Against an archive with no sessions it runs a shorter report — the empty state, the top bar, the
 * parser and the stylesheet audits — and names every session-dependent probe as n/a. That is how
 * the "waiting for an app" empty state gets checked at all:
 *   inspector serve --port 8238 --data "$(mktemp -d)" &
 *   INSPECTOR_UI_ORIGIN=http://127.0.0.1:8238 node scripts/render-web-ui.js > /dev/null
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
/*
 * The daemon to read. Overridable because 8099 is the port every consuming app dials by default:
 * running a throwaway daemon there to smoke-test the UI invites a real app to connect to it and
 * write its traffic into whatever archive this is pointed at. A private port avoids that entirely.
 */
const ORIGIN = process.env.INSPECTOR_UI_ORIGIN || 'http://127.0.0.1:8099';

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
// Kept, so a probe can play the daemon coming back by firing the page's own `onopen`.
let lastSocket = null;
window.WebSocket = class {
  constructor() { lastSocket = this; setTimeout(() => this.onopen && this.onopen(), 0); }
  close() {}
};
// Captured rather than discarded: the "copy for agent" buttons put their whole payload here, and what
// they put there is the feature.
let lastCopied = null;
window.navigator.clipboard = { writeText: async (text) => { lastCopied = text; } };

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

  /**
   * Views this build ships, as opposed to tabs built from whatever tags the app recorded.
   *
   * Named once because three probes used to spell it as "the first tab that is not network or
   * all", which silently meant "a tag browser" until `waterfall` arrived and became the first
   * such tab — at which point all three started asserting tag-browser behaviour against a view
   * that is not one.
   */
  const BUILT_IN_VIEWS = ['network', 'all', 'waterfall'];
  const firstTagBrowserTab = () =>
    [...doc.querySelectorAll('#tabs .tab')].find((t) => !BUILT_IN_VIEWS.includes(t.dataset.view));

  /**
   * The scope bar, and the rows that depend on it.
   *
   * The bar is a standing claim that every path below it is missing the same front, so the two
   * halves have to be checked together: a bar that renders while the rows still show their full
   * path is merely redundant, but rows stripped with no bar on screen are *wrong* — they read as
   * endpoints nobody called.
   *
   * `stickyInCss` is audited as text rather than as computed style. jsdom resolves layout for
   * nothing, so `getComputedStyle(...).position` would answer `static` whatever the sheet says,
   * and the check would pass with the rule deleted.
   */
  async function probeScopeBar() {
    const bar = doc.querySelector('#scope-bar');
    const stickyInCss = /\.scope-bar\s*\{[^}]*position:\s*sticky/.test(css);
    if (!bar || bar.hidden) {
      return { shown: false, stickyInCss, reason: 'no shared prefix in this session (or fewer than 4 rows)' };
    }

    const label = bar.querySelector('.scope-label')?.textContent || '';
    const count = bar.querySelector('.scope-count')?.textContent || '';
    const prefix = label.slice(label.indexOf('/'));

    // A stripped path has **no leading slash**: the prefix is both-slashed, so removing it leaves
    // a name rather than a path fragment. Testing `!startsWith(prefix)` instead would count the
    // rows the bar does not cover as stripped — they start with a slash and a different prefix —
    // and the check would report 7 of 7 on a session where only 6 are covered.
    const rows = [...doc.querySelectorAll('#list .row')];
    const paths = rows.map((r) => r.querySelector('.path')?.firstChild?.textContent || '');
    const hosts = rows.map((r) => r.querySelector('.host')?.textContent || null);
    const strippedIdx = paths.map((p, i) => (p && !p.startsWith('/') ? i : -1)).filter((i) => i !== -1);

    return {
      shown: true,
      stickyInCss,
      label,
      count,
      strippedRows: strippedIdx.length,
      totalRows: rows.length,
      // A covered row must not also name the host the bar already claims, and a row that was not
      // stripped must name its own — otherwise it reads as belonging under the bar.
      noRepeatedHost: strippedIdx.every((i) => !hosts[i]),
      uncoveredNameTheirHost: paths.every((p, i) => (p && !p.startsWith('/')) || Boolean(hosts[i])),
    };
  }

  /**
   * Adding a marker from the page.
   *
   * Two things worth asserting and one that cannot be. The form must refuse an empty label — a
   * marker with no label is a line across the list that explains nothing — and it must disable
   * itself on a session that has finished recording, because the daemon refuses that with a 409
   * and finding out by submitting is a worse way to learn it.
   *
   * Whether a *successful* add lands is not asserted here: it would write a marker into whatever
   * archive this run is pointed at, and a smoke test that mutates the thing it is reading is one
   * that reports differently the second time it runs. `RestApiTest` covers the POST.
   *
   * The blank-label check watches **fetch**, not the marker list. Watching the list was the first
   * version and it was worthless: the daemon refuses a marker on a session nothing is connected to,
   * so the count stayed put whether the client guarded or not, and the probe reported a pass with
   * the guard deleted. What is being asserted is that the page does not ask — so the thing to
   * observe is the asking.
   */
  async function probeMarkerForm() {
    const form = doc.querySelector('#marker-form');
    if (!form) return { present: false };
    const input = doc.querySelector('#marker-label');
    const button = doc.querySelector('#marker-add');

    let posted = 0;
    const realFetch = window.fetch;
    window.fetch = (url, init) => {
      if (String(url).includes('/markers') && init && init.method === 'POST') posted++;
      return realFetch(url, init);
    };
    input.value = '   ';
    form.dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));
    await settle();
    window.fetch = realFetch;
    input.value = '';

    return {
      present: true,
      blankRejected: posted === 0,
      // The form's enabled state must agree with what the daemon says is recording — an enabled
      // form on a session nothing is connected to is a 409 waiting to happen, and a disabled one
      // on a live session is a dead control.
      matchesRecording: button.disabled === !recordingNow.includes(sessionOnScreen()),
      disabled: button.disabled,
    };
  }

  /**
   * Per-field copy.
   *
   * The count is the point: one button per header, one per overview field, one per body. The
   * failure this catches is a helper that renders a single button for the whole pane, which looks
   * fine in a screenshot and copies the wrong thing.
   *
   * Visibility is audited as CSS text, again because jsdom computes no layout — and specifically
   * that `:focus-within` is in the rule. Without it the button is reachable by Tab, invisible
   * while focused, and the focus ring lands on nothing.
   */
  function probeCopyFields() {
    const pane = doc.querySelector('#detail');
    const buttons = [...(pane?.querySelectorAll('.copy-field') || [])];
    const headers = [...(pane?.querySelectorAll('.headers .copyable') || [])];
    return {
      buttons: buttons.length,
      headerRows: headers.length,
      everyHeaderHasOne: headers.length > 0 && headers.every((h) => h.querySelector('.copy-field')),
      // The value alone, not `name: value` — what gets pasted into a terminal is the value.
      labelled: buttons.length > 0 && buttons.every((b) => b.getAttribute('aria-label')?.startsWith('Copy ')),
      focusWithinInCss: /\.copyable:focus-within\s+\.copy-field/.test(css),
      // A JSON body carries its copy in the tree's toolbar; anything else keeps the overlay button.
      bodyButton: Boolean(pane?.querySelector('.body-wrap .copy-field, .jt-view .jt-copy')),
    };
  }


  /**
   * The list's rows and its empty states.
   *
   * The empty-state check drives a filter that matches nothing through the real input, so it
   * covers the listener, the daemon's 0-row answer and the re-render together — and then presses
   * the button, because "offers to clear the filter" is only true if pressing it brings the rows
   * back. Asserted on the rows returning, not on the input being blank.
   */
  async function probeList() {
    const result = {};
    const rows = [...doc.querySelectorAll('#list .row')];
    const leads = rows.filter((r) => r.firstElementChild?.classList.contains('lead')
      && r.firstElementChild.classList.contains('status'));
    result.statusFirst = !rows.length ? 'n/a (no rows)' : leads.length === rows.length && !doc.querySelector('#list .status-dot')
      && rows.every((r) => r.querySelectorAll('.status').length === 1)
      ? `yes (${rows.length} rows lead with their code, no dots)`
      : `NO - ${leads.length} of ${rows.length} lead with the status`;

    const pill = doc.querySelector('#scope-bar .scope-label');
    // Comments stripped first: the rule's own comment explains why `direction: rtl` is gone, and a
    // check reading prose would fail on the explanation of its own fix.
    const pillRule = (css.replace(/\/\*[\s\S]*?\*\//g, '').match(/\.scope-label\s*\{[^}]*\}/) || [''])[0];
    result.scopePill = pill
      ? !/direction:\s*rtl/.test(pillRule) && pill.textContent.includes(' · ') && pill.title.length > pill.textContent.length
        ? `yes ("${pill.textContent}", full label in the tooltip)`
        : `NO - "${pill.textContent}", rtl ${/direction:\s*rtl/.test(pillRule)}`
      : 'n/a (no scope bar in this session)';

    // Only reachable on a session with no calls, or an archive with none; reported, and checked
    // against the one claim each case is allowed to make.
    if (!rows.length) {
      const text = doc.getElementById('list-empty').textContent;
      const expected = !doc.querySelector('#session-picker option')
        ? 'waiting for an app with inspector-stream to connect'
        : null;
      result.emptySession = expected === null
        ? /^(no calls were recorded in this session|the app is connected — no calls yet)$/.test(text)
          ? `yes ("${text}")`
          : `NO - "${text}"`
        : text === expected ? `yes ("${text}")` : `NO - "${text}", expected "${expected}"`;
      return result;
    }

    const input = doc.getElementById('filter');
    const before = rows.length;
    input.value = 'path:/__inspector_probe_matches_nothing__';
    input.dispatchEvent(new window.Event('input', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 300));
    await settle();
    const empty = doc.getElementById('list-empty');
    const clear = [...empty.querySelectorAll('button')].find((b) => b.textContent === 'clear filter');
    result.noMatch = !empty.hidden && /^no calls match/.test(empty.textContent) && clear
      ? `yes ("${empty.textContent}")`
      : `NO - hidden ${empty.hidden}, "${empty.textContent}"`;
    if (clear) {
      clear.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
      await new Promise((r) => setTimeout(r, 300));
      await settle();
    } else {
      input.value = '';
      input.dispatchEvent(new window.Event('input', { bubbles: true }));
      await new Promise((r) => setTimeout(r, 300));
      await settle();
    }
    const after = doc.querySelectorAll('#list .row').length;
    result.clearFilter = clear && input.value === '' && after === before && empty.hidden
      ? `yes (${after} rows back, no chip left active)`
      : `NO - ${after} of ${before} rows back`;
    result.noActiveChip = !doc.querySelector('#chips .chip.active, #endpoint-chips .chip.active')
      ? 'yes' : 'NO - a chip still claims a filter that is gone';
    return result;
  }


  /*
   * An archive with no sessions: run what still means something, and say n/a for the rest.
   *
   * Nearly every probe below clicks a row, a tab or a session, and with none of them the first
   * `click(null)` threw and took the whole report with it — including the empty-state check, which
   * is the one thing an empty archive is *for* testing. Guarding each click would have been worse:
   * a probe that silently clicked nothing would go on to report on a pane it never opened. So the
   * split is explicit, and every skipped probe is named in the report rather than left out.
   */
  if (!doc.querySelector('#session-picker option')) {
    const listProbe = await probeList();
    const topBarProbe = await probeTopBar();
    console.error('--- empty archive ---');
    console.error('sessions           : 0');
    console.error('emptySession       :', listProbe.emptySession ?? 'NO - the empty-state check did not run');
    for (const [name, value] of Object.entries(topBarProbe)) console.error(`${name.padEnd(19)}:`, value);
    console.error('parser             :', probeJsonParser().ok);
    console.error('min text size      :', auditMinTextSize(css));
    console.error('long tokens wrap   :', auditLongTokenWrap(css));
    console.error('hidden panes hide  :', auditHiddenPanes(css));
    for (const skipped of [
      'scope bar', 'endpoint chips', 'order flip', 'signals', 'tag browsers', 'axis & waterfall',
      'glance', 'handing to an agent', 'replay', 'detail header', 'detail tabs', 'json tree',
      'copy fields', 'drawer', 'toolbar', 'now strip', 'tabs', 'ages', 'sessions',
    ]) {
      console.error(`${skipped.padEnd(19)}: n/a (no sessions)`);
    }
    console.error('errors             :', errors.length ? errors.join(' | ') : 'none');
    writeSnapshot('Static snapshot of the live UI against an empty archive. ');
    window.close();
    return;
  }

  /** Serialises the page as a static, self-contained snapshot on stdout. */
  function writeSnapshot(lead) {
    /*
     * Write every field's value into the markup before serialising.
     *
     * `input.value` and `textarea.value` are DOM *properties*; the `value` attribute and a
     * textarea's text content are what `outerHTML` writes. Anything a page fills in from JavaScript
     * — which here is the filter box, the settings fields and every box in the replay editor — was
     * therefore blank in every snapshot this script has ever produced, and a snapshot of a form is
     * mostly its contents.
     */
    for (const input of doc.querySelectorAll('input')) {
      if (input.type === 'checkbox' || input.type === 'radio') {
        if (input.checked) input.setAttribute('checked', '');
        else input.removeAttribute('checked');
      } else if (input.value) {
        input.setAttribute('value', input.value);
      }
    }
    for (const area of doc.querySelectorAll('textarea')) {
      if (area.value) area.textContent = area.value;
    }

    // Freeze as a static, self-contained page.
    doc.querySelectorAll('script').forEach((s) => s.remove());
    const banner = doc.createElement('div');
    banner.setAttribute(
      'style',
      'padding:6px 14px;background:#3a2f10;color:#e0a030;font:12px system-ui;border-bottom:1px solid #2c3038',
    );
    banner.textContent = lead + 'The interactive version is at http://127.0.0.1:8099';
    doc.body.insertBefore(banner, doc.body.firstChild);

    console.log('<!doctype html>\n' + doc.documentElement.outerHTML);
  }

  const scopeProbe = await probeScopeBar();
  const listProbe = await probeList();

  // Asked of the daemon directly rather than read out of the page's own state, so the check is
  // against the truth the endpoint reports and not against whatever the page believes.
  const recordingNow = await fetch(`${ORIGIN}/api/recording`).then((r) => r.json()).catch(() => []);
  const sessionOnScreen = () => doc.querySelector('#session-picker')?.value || '';
  const markerFormProbe = await probeMarkerForm();

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
  /** The replay menu's item by label, opening the menu first — the items live behind `replay ▾`. */
  const replayItem = async (label) => {
    const trigger = [...doc.querySelectorAll('#detail .detail-head .btn')].find((b) => b.textContent === 'replay ▾');
    if (!trigger) return null;
    trigger.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 40));
    return [...doc.querySelectorAll('#detail .menu-item')].find((b) => b.textContent === label) || null;
  };

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
      const pre = doc.querySelector('#detail pre.body, #detail .jt');
      const text = pre ? pre.textContent : '';
      result.payloadShown =
        text && text !== 'loading...' && !text.startsWith('could not read')
          ? `yes (${text.length} chars)`
          : `NO (${text ? text.slice(0, 40) : 'no pre'})`;
      const pushed = withPayload.querySelector('.tl-pushed');
      const note = doc.querySelector('#detail .detail-note');
      result.pushedWarned = pushed ? Boolean(note) : 'n/a (row was pulled)';
    }

    // Runs of identical adjacent observations collapse to one row.
    //
    // The check that matters is that collapsing is *lossless*: expanding a run must put back
    // exactly the rows it stood for. A collapse that quietly dropped observations would look
    // like a tidier timeline and be a lie about what the app recorded.
    await click(doc.querySelector('#tabs .tab[data-view=\"all\"]'));
    await new Promise((r) => setTimeout(r, 400));
    const timeline = doc.getElementById('timeline');
    const runRows = [...timeline.children].filter((n) => n.dataset.kind === 'run');
    result.runsCollapsed = runRows.length;
    if (runRows.length) {
      const biggest = runRows
        .map((n) => ({ node: n, n: Number((n.querySelector('.tl-run-count')?.textContent || '').slice(1)) }))
        .sort((a, b) => b.n - a.n)[0];
      const before = timeline.children.length;
      await click(biggest.node);
      await new Promise((r) => setTimeout(r, 300));
      const expanded = timeline.children.length;
      // Collapsed, a run is one row. Expanded, it is that row kept as a header plus all n
      // members — so the delta is exactly n, and anything less means observations were dropped.
      result.runExpandsLossless = expanded - before === biggest.n
        ? `yes (x${biggest.n} put back ${expanded - before} rows)`
        : `NO - x${biggest.n} put back ${expanded - before}`;
      const reopened = [...timeline.children].find(
        (n) => n.dataset.kind === 'run' && n.dataset.runId === biggest.node.dataset.runId,
      );
      await click(reopened);
      await new Promise((r) => setTimeout(r, 300));
      result.runCollapsesBack = timeline.children.length === before
        ? 'yes'
        : `NO - ${timeline.children.length} rows, expected ${before}`;
      result.biggestRun = biggest.n;
    } else {
      result.runExpandsLossless = 'n/a (no run reached the threshold)';
      result.runCollapsesBack = 'n/a';
      result.biggestRun = 0;
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
    const knownIds = [...BUILT_IN_VIEWS, 'cache', 'screen', 'state'];
    return {
      count: tabs.length,
      ids,
      unknownTagsTabbed: ids.filter((id) => !knownIds.includes(id)),
      activeCount: tabs.filter((t) => t.classList.contains('active')).length,
      hasNetwork: ids.includes('network'),
      // Traffic leads. This is a network debugger, and in a real session the merged timeline ran
      // 126 rows of which 13 were calls — opening there buries what you came for.
      trafficFirst: ids[0] === 'network'
        ? 'yes'
        : `NO - first tab is '${ids[0]}'`,
      labels: tabs.map((t) => t.querySelector('.tab-label')?.textContent).join(', '),
      // Drawn whatever the session holds; it used to vanish with a single view and move the page.
      alwaysShown: !doc.getElementById('tabs').hidden ? 'yes' : 'NO - the tab bar is hidden',
      // Exactly one separator, on the first tab that is a tag rather than a traffic view.
      signalSeparator: (() => {
        const marked = tabs.filter((t) => t.classList.contains('tab-signal-first'));
        const first = tabs.find((t) => !BUILT_IN_VIEWS.includes(t.dataset.view));
        if (!first) return marked.length === 0 ? 'n/a (no signal tabs)' : 'NO - a separator with no signal tab';
        return marked.length === 1 && marked[0] === first
          ? `yes (before '${first.dataset.view}')`
          : `NO - ${marked.length} marked, first signal tab is '${first.dataset.view}'`;
      })(),
    };
  }

  /**
   * The toolbar's labels and controls, as a reader meets them.
   *
   * The order control is judged by `aria-pressed` agreeing with the visible `on` state, because
   * the segmented control's whole claim is that it states which order the list is in — a
   * control whose two halves disagree about that says nothing.
   */
  function probeToolbarLabels() {
    const toolbar = doc.getElementById('toolbar');
    const labels = [...toolbar.querySelectorAll('.row-label')].map((n) => n.textContent);
    const endpoints = doc.getElementById('endpoint-chips');
    const oldest = doc.getElementById('order-oldest');
    const newest = doc.getElementById('order-newest');
    const pressedAgrees = [oldest, newest].every(
      (b) => b.getAttribute('aria-pressed') === String(b.classList.contains('on')),
    );
    const markerLabels = new Set([...doc.querySelectorAll('#markers li')].filter((li) => !li.classList.contains('muted')).map((li) => li.textContent)).size;
    const markersText = doc.getElementById('markers-button').textContent;
    return {
      rowLabels: labels.includes('filters') && (endpoints.hidden || labels.includes('endpoints'))
        ? `yes (${labels.join(', ')})`
        : `NO - ${labels.join(', ') || 'none'}`,
      orderSegmented: oldest.parentElement.classList.contains('seg2') && pressedAgrees
        && [oldest, newest].filter((b) => b.classList.contains('on')).length === 1
        ? `yes (${oldest.classList.contains('on') ? 'oldest' : 'newest'} first pressed)`
        : 'NO - the order control does not state one order',
      markersCount: markerLabels ? (markersText === `markers · ${markerLabels}` ? `yes ("${markersText}")` : `NO - "${markersText}" for ${markerLabels}`)
        : markersText === 'markers' ? 'yes ("markers", none in this session)' : `NO - "${markersText}"`,
      shortcuts: doc.getElementById('keys-button').textContent === 'shortcuts' ? 'yes' : 'NO',
    };
  }

  /**
   * Nothing under 11px except the method badge, the key caps and the fold glyphs.
   *
   * Read from the stylesheet source: jsdom computes no font sizes worth trusting. Each exception is
   * named rather than matched by pattern, so a new 10px rule fails here until someone decides it
   * belongs on the list.
   */
  function auditMinTextSize(cssText) {
    // The method badge, key caps, and the three fold glyphs (▸ ▾), which are shapes, not text.
    const allowed = ['.row .method', 'kbd', '.jt-tw', '.now-caret', '.tl-twisty'];
    const offenders = [];
    const source = cssText.replace(/\/\*[\s\S]*?\*\//g, '');
    for (const [, selector, body] of source.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
      const size = body.match(/font-size:\s*(\d+(?:\.\d+)?)px/);
      if (!size || Number(size[1]) >= 11) continue;
      const name = selector.trim();
      if (!allowed.includes(name)) offenders.push(`${name} ${size[1]}px`);
    }
    return offenders.length ? `NO - ${offenders.join(', ')}` : `yes (only ${allowed.join(', ')} below 11px)`;
  }

  /**
   * A long unbroken token must not be able to widen the detail pane.
   *
   * Checked against the stylesheet text, not `getComputedStyle`: jsdom has no layout engine, so
   * it reports neither the 7139px line box this guards against nor whether the rule prevented it.
   * A computed-style version of this check passes just as happily with the rule deleted — the
   * same reason `auditHiddenPanes` reads the CSS source. The real measurement belongs in a
   * browser, and is recorded in the commit that added the rule.
   */
  function auditLongTokenWrap(cssText) {
    const block = cssText.match(/\.headers\s*\{([^}]*)\}/);
    if (!block) return 'NO - no .headers rule at all';
    const wrap = block[1].match(/overflow-wrap\s*:\s*([a-z-]+)/);
    const breaks = block[1].match(/word-break\s*:\s*([a-z-]+)/);
    const value = wrap?.[1] || breaks?.[1];
    return value && ['anywhere', 'break-all', 'break-word'].includes(value)
      ? `yes (${value})`
      : 'NO - a bearer token will paint outside the pane and scroll the page';
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
      const body = doc.querySelector('#browser-detail .bvalue-body, #browser-detail .jt-view');
      if (body && body.textContent.trim()) {
        result.anyValueRendered = `yes (${key.querySelector('.bkey-name').textContent})`;
        break;
      }
    }
    await click(withHistory);
    await new Promise((r) => setTimeout(r, 400));

    result.detailRendered = Boolean(doc.querySelector('#browser-detail .bdetail-key'));
    const pre = doc.querySelector('#browser-detail .bvalue-body');
    const tree = doc.querySelector('#browser-detail .jt');
    const none = doc.querySelector('#browser-detail .bvalue-none');
    // A pretty-printed payload is the point of the detail panel — a one-line blob is what the
    // flat table already did badly, so the assertion is on the newlines, not on the presence.
    result.valueShown = tree
      ? `yes (tree, ${tree.querySelectorAll('.jt-row').length} rows)`
      : pre
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

  /**
   * Controls belong to the view they act on.
   *
   * The toolbar filters *transactions*. On a tag browser it would be a second filter box meaning
   * something else entirely, above a list it cannot filter — which is what the rail was, for a
   * fifth of the window, on five tabs out of seven.
   */
  async function probeToolbar() {
    const result = { onTraffic: 'n/a', onBrowser: 'n/a', railGone: !doc.getElementById('rail') };
    const tabOf = (view) => [...doc.querySelectorAll('#tabs .tab')].find((t) => t.dataset.view === view);

    const network = tabOf('network');
    if (network) {
      await click(network);
      await new Promise((r) => setTimeout(r, 400));
      result.onTraffic = doc.getElementById('toolbar').hidden ? 'NO - hidden on traffic' : 'shown';
    }

    const browser = firstTagBrowserTab();
    if (browser) {
      await click(browser);
      await new Promise((r) => setTimeout(r, 700));
      result.onBrowser = doc.getElementById('toolbar').hidden
        ? `hidden (on '${browser.dataset.view}')`
        : `NO - still shown on '${browser.dataset.view}'`;
      result.ownFilterInstead = doc.getElementById('browser-filter') ? 'yes' : 'NO';
    }
    return result;
  }

  /**
   * `Now` is a summary, and a summary that takes 70% of a column is not one.
   *
   * The assertion is that it opens collapsed and still says something useful collapsed — a strip
   * reading only "now" would have moved the problem rather than fixed it.
   */
  async function probeNowStrip() {
    const all = [...doc.querySelectorAll('#tabs .tab')].find((t) => t.dataset.view === 'all');
    const result = { present: false, collapsedSummary: 'n/a', expandsTo: 0, collapsesBack: 'n/a' };
    if (!all) return result;
    await click(all);
    await new Promise((r) => setTimeout(r, 500));

    const strip = doc.getElementById('now-strip');
    result.present = Boolean(strip) && !strip.hidden;
    if (!result.present) return result;

    const list = doc.getElementById('current');
    result.startsCollapsed = list.hidden ? 'yes' : 'NO - opens expanded';
    const summary = doc.getElementById('now-summary-text').textContent.trim();
    result.collapsedSummary = summary || 'NO - the strip says nothing collapsed';

    await click(doc.getElementById('now-toggle'));
    await new Promise((r) => setTimeout(r, 250));
    result.expandsTo = doc.querySelectorAll('#current .current-item').length;
    await click(doc.getElementById('now-toggle'));
    await new Promise((r) => setTimeout(r, 250));
    result.collapsesBack = doc.getElementById('current').hidden ? 'yes' : 'NO';
    return result;
  }

  /**
   * The narrow-width drawer.
   *
   * Split in two on purpose. Whether it *opens and closes* is behaviour and is driven here;
   * whether it is a drawer *at all* is a media query, and jsdom has no layout, so that half is
   * read out of the stylesheet — a computed-style check would pass with the whole media block
   * deleted, the same trap `auditHiddenPanes` and the long-token guard already avoid. The
   * geometry itself was measured in a browser and is recorded in the commit.
   */
  async function probeDrawer() {
    const result = { opensOnSelect: 'n/a', scrim: 'n/a', closeButton: 'n/a', escape: 'n/a', tabSwitch: 'n/a' };
    const panes = doc.getElementById('panes');
    const isOpen = () => panes.classList.contains('drawer-open');

    const network = [...doc.querySelectorAll('#tabs .tab')].find((t) => t.dataset.view === 'network');
    if (network) {
      await click(network);
      await new Promise((r) => setTimeout(r, 400));
    }
    const row = doc.querySelector('#list .row');
    if (!row) return result;

    await click(row);
    await new Promise((r) => setTimeout(r, 400));
    result.opensOnSelect = isOpen() ? 'yes' : 'NO - selecting a row left it shut';
    result.scrim = doc.getElementById('drawer-scrim').hidden ? 'NO - no scrim' : 'shown';

    await click(doc.getElementById('drawer-close'));
    await new Promise((r) => setTimeout(r, 200));
    result.closeButton = isOpen() ? 'NO' : 'closes';

    await click(row);
    await new Promise((r) => setTimeout(r, 300));
    await click(doc.getElementById('drawer-scrim'));
    await new Promise((r) => setTimeout(r, 200));
    result.scrimCloses = isOpen() ? 'NO' : 'closes';

    await click(row);
    await new Promise((r) => setTimeout(r, 300));
    doc.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await new Promise((r) => setTimeout(r, 200));
    result.escape = isOpen() ? 'NO' : 'closes';

    // A drawer covers the list it came from; carrying it across a tab switch would leave it
    // sitting over a list that never produced it.
    await click(row);
    await new Promise((r) => setTimeout(r, 300));
    const other = [...doc.querySelectorAll('#tabs .tab')].find((t) => t.dataset.view !== 'network');
    if (other) {
      await click(other);
      await new Promise((r) => setTimeout(r, 700));
      result.tabSwitch = isOpen() ? `NO - carried onto '${other.dataset.view}'` : 'closes';
    }
    return result;
  }

  /** Is the detail pane a drawer at all below the breakpoint? Read from the stylesheet. */
  function auditDrawerCss(cssText) {
    const block = cssText.match(/@media \(max-width: \d+px\) \{([\s\S]*?)\n\}/);
    if (!block) return 'NO - no narrow-width media block';
    const body = block[1];
    // Anchored at the end of the declaration: `1fr` alone also prefix-matches `1fr 1fr`, which
    // is the exact two-column layout this is supposed to catch.
    const oneColumn = /#panes[^{]*\{[^}]*grid-template-columns:\s*1fr\s*[;}]/.test(body);
    const floats = /#detail-pane\s*\{[^}]*position:\s*absolute/.test(body);
    const slides = /drawer-open[^{]*#detail-pane\s*\{[^}]*translateX\(0\)/.test(body);
    const missing = [
      !oneColumn && 'the list does not take the full width',
      !floats && 'the detail pane is still in flow',
      !slides && 'nothing brings the drawer on screen',
    ].filter(Boolean);
    return missing.length ? `NO - ${missing.join('; ')}` : 'yes';
  }

  /**
   * The transaction detail: one face at a time, response first.
   *
   * The assertions that matter are ordering ones. That the response body is the *first* thing in
   * the default tab is the entire change — it used to be the last of five sections, behind every
   * header on both sides of the call. And that redaction and the attempt chain stay *outside* the
   * tabs: both are corrections to what the rest of the pane appears to say, and a correction you
   * have to go and find is not one.
   */
  async function probeDetailTabs() {
    const result = { tabs: [], defaultTab: 'n/a', firstSection: 'n/a', onePanel: 'n/a' };
    const network = [...doc.querySelectorAll('#tabs .tab')].find((t) => t.dataset.view === 'network');
    if (network) {
      await click(network);
      await new Promise((r) => setTimeout(r, 400));
    }

    const rows = [...doc.querySelectorAll('#list .row')];
    let withChain = null;
    for (const row of rows) {
      await click(row);
      await new Promise((r) => setTimeout(r, 260));
      if (doc.querySelectorAll('#detail .chain-row').length) { withChain = row; break; }
    }
    if (withChain) {
      const kids = [...doc.getElementById('detail').children];
      const tabsAt = kids.findIndex((k) => k.classList.contains('dtabs'));
      const chainAt = kids.findIndex((k) => k.classList.contains('chain-row'));
      result.chainPinned = chainAt !== -1 && chainAt < tabsAt
        ? 'above the tabs'
        : 'NO - an attempt chain is tabbed away';
    } else {
      result.chainPinned = 'n/a (no chained call in this session)';
    }

    const row = rows.find(Boolean);
    if (!row) return result;
    await click(row);
    await new Promise((r) => setTimeout(r, 500));

    const detail = doc.getElementById('detail');
    result.tabs = [...detail.querySelectorAll('.dtab')].map((t) => t.dataset.dtab);
    result.defaultTab = detail.querySelector('.dtab.active')?.dataset.dtab ?? 'none';
    const open = detail.querySelector('.dtab-panel:not([hidden])');
    result.firstSection = open?.querySelector('.section-title')?.textContent ?? 'none';
    result.bodyBeforeHeaders = (() => {
      if (!open) return 'n/a';
      const kids = [...open.children];
      // `.body-wrap` is the body plus its copy button; `.muted` is the branch that explains an
      // absent one. Both are "the body slot", and the claim is about where that slot sits.
      const body = kids.findIndex(
        (k) => k.tagName === 'PRE' || k.classList.contains('body-wrap') || k.classList.contains('jt-view')
          || k.classList.contains('muted'),
      );
      const headers = kids.findIndex((k) => k.classList.contains('headers'));
      return body !== -1 && headers !== -1 && body < headers
        ? 'yes'
        : `NO - body at ${body}, headers at ${headers}`;
    })();
    result.onePanel = detail.querySelectorAll('.dtab-panel:not([hidden])').length === 1
      ? 'yes'
      : `NO - ${detail.querySelectorAll('.dtab-panel:not([hidden])').length} showing`;

    const req = [...detail.querySelectorAll('.dtab')].find((t) => t.dataset.dtab === 'req');
    if (req) {
      await click(req);
      await new Promise((r) => setTimeout(r, 200));
      result.switches = detail.querySelector('.dtab.active')?.dataset.dtab === 'req'
        ? 'yes'
        : 'NO';
      // Comparing one field down a list means picking the same tab on every row otherwise.
      const other = rows[1] || rows[0];
      await click(other);
      await new Promise((r) => setTimeout(r, 400));
      result.remembersAcrossRows = doc.querySelector('#detail .dtab.active')?.dataset.dtab === 'req'
        ? 'yes'
        : 'NO - reset to response';
    }
    return result;
  }

  /**
   * An empty pane that orients you instead of naming itself.
   *
   * The assertion with teeth is that the rows are *ways in*, not a readout — clicking the slowest
   * call has to select it. A summary you can only look at would have replaced three words with a
   * paragraph and still spent half the window saying nothing you can act on.
   */
  async function probeGlance() {
    const result = { session: 'n/a', clickable: 0, slowestSelects: 'n/a', tag: 'n/a' };
    const network = [...doc.querySelectorAll('#tabs .tab')].find((t) => t.dataset.view === 'network');
    if (network) {
      await click(network);
      await new Promise((r) => setTimeout(r, 400));
    }
    // Esc drops any selection probeDetailTabs left behind, which is what puts the pane back.
    doc.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await new Promise((r) => setTimeout(r, 300));

    const pane = doc.getElementById('detail-empty');
    const rows = [...pane.querySelectorAll('.glance-row')];
    result.session = rows.length
      ? `${rows.length} rows`
      : `NO - still reads "${pane.textContent.trim()}"`;
    result.clickable = pane.querySelectorAll('.glance-click').length;

    // Read the value span, not textContent: the spans concatenate without a separator, so a
    // slowest row reads "2.0sGET /path" and a `\bGET` never matches.
    const slow = [...pane.querySelectorAll('.glance-click')].find((r) =>
      /^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS) /.test(
        r.querySelector('.glance-value')?.textContent || '',
      ));
    if (slow) {
      await click(slow);
      await new Promise((r) => setTimeout(r, 600));
      result.slowestSelects = doc.getElementById('detail').hidden
        ? 'NO - clicking a slow call did nothing'
        : 'selects it';
      doc.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await new Promise((r) => setTimeout(r, 300));
      result.escRestores = doc.querySelectorAll('#detail-empty .glance-row').length
        ? 'yes'
        : 'NO';
    }

    const browser = firstTagBrowserTab();
    if (browser) {
      await click(browser);
      await new Promise((r) => setTimeout(r, 900));
      const detail = doc.getElementById('browser-detail');
      const tagRows = detail.querySelectorAll('.glance-row').length;
      result.tag = tagRows
        ? `${tagRows} rows on '${browser.dataset.view}'`
        : `NO - still reads "${detail.textContent.trim()}"`;
    }
    return result;
  }

  /**
   * How many calls the session holds right now, asked of the daemon rather than of the page.
   *
   * The axis is drawn from `allTransactions`, so "did the input move under me" is a question
   * about the session, not about the DOM. Taking it from the same endpoint the page uses keeps
   * the answer honest when the app is still recording into the session on screen.
   */
  const sessionCallCount = async () => {
    const id = doc.getElementById('session-picker').value;
    const q = new URLSearchParams({ filter: '', limit: '2000' });
    try {
      const page = await window.fetch(
        `/api/sessions/${encodeURIComponent(id)}/transactions?${q}`
      ).then((r) => r.json());
      return page.items.length;
    } catch {
      return null; // unknown, which the caller reports rather than guesses about
    }
  };

  /**
   * The time axis: a map of the session, drawn above the list that reads it.
   *
   * The assertion with teeth is that the shape comes from the *whole* session while the
   * highlight comes from the filtered set. A map redrawn from whatever survived the filter
   * cannot answer "where am I in this session", which is the only reason to draw one.
   *
   * That is checked by *layer*, not by bar count. Counting was the obvious way to write it and
   * it false-alarmed on the first live session it met: the app was still recording, the
   * filter-triggered refetch picked up calls that arrived mid-probe, the session window
   * stretched to hold them, and a bucket count went 12 -> 13 with nothing wrong. Bucket
   * positions are a function of the window, so *no* geometric number survives a session that is
   * still growing.
   *
   * What does survive is the relationship between the two layers. Filter to something that
   * removes nearly every call: the dim layer must still be wider than the highlight. Redrawn
   * from the filter, both layers come from one set and the dim one collapses onto the highlight
   * — which is true whether or not the session grew a moment ago. The exact count is still
   * compared, but only after asking the daemon whether the session held still, and a session
   * that moved is reported as having moved instead of failing.
   *
   * The brush itself is not driven here: jsdom has no layout, so `getBoundingClientRect` returns
   * zeros and a pointer position cannot be turned into a time. Dragging was measured in a real
   * browser and the numbers are in the commit; what is checked here is that narrowing actually
   * narrows, via the control that does it without a pointer.
   */
  async function probeAxis() {
    const result = {
      shown: 'n/a', bars: 0, hits: 0, screens: 0,
      shapeIsWholeSession: 'n/a', sessionMoved: 'n/a',
    };
    const network = [...doc.querySelectorAll('#tabs .tab')].find((t) => t.dataset.view === 'network');
    if (network) {
      await click(network);
      await settle();
    }
    const axis = doc.getElementById('axis');
    result.shown = axis.hidden ? 'NO - hidden on traffic' : 'shown';
    if (axis.hidden) return result;

    result.bars = axis.querySelectorAll('.axis-bar-all').length;
    result.hits = axis.querySelectorAll('.axis-bar-hit').length;
    result.screens = axis.querySelectorAll('.axis-screen').length;

    // Filter hard, then check the dim shape held its ground while the highlight shrank.
    const filter = doc.getElementById('filter');
    const before = { bars: result.bars, hits: result.hits };
    const callsBefore = await sessionCallCount();

    filter.value = 'status>=400';
    filter.dispatchEvent(new dom.window.Event('input', { bubbles: true }));
    await settle();
    const after = {
      bars: axis.querySelectorAll('.axis-bar-all').length,
      hits: axis.querySelectorAll('.axis-bar-hit').length,
    };
    const callsAfter = await sessionCallCount();

    const grew = callsBefore !== null && callsAfter !== null ? callsAfter - callsBefore : null;
    result.sessionMoved = grew === null
      ? 'unknown - the daemon did not answer; counts below are not compared'
      : grew > 0
        ? `grew by ${grew} under the probe (${callsBefore} -> ${callsAfter} calls) - still recording`
        : `held still (${callsBefore} calls)`;

    // A filter that removed nothing tells the two layers apart from neither, so say so rather
    // than passing on it: a session of nothing but errors would otherwise read as proof.
    const narrowed = after.hits < before.hits;
    if (!before.bars) {
      result.shapeIsWholeSession = 'n/a - the session drew no bars to test';
    } else if (!narrowed) {
      result.shapeIsWholeSession =
        `n/a - 'status>=400' removed nothing (${before.hits} hits), so the layers are indistinguishable`;
    } else if (after.bars <= after.hits) {
      result.shapeIsWholeSession =
        `NO - the shape was redrawn from the filter (${after.bars} bars to ${after.hits} hits)`;
    } else if (grew === 0 && after.bars !== before.bars) {
      result.shapeIsWholeSession =
        `NO - the shape changed with the filter, ${before.bars} -> ${after.bars}, ` +
        'and the session held still';
    } else {
      result.shapeIsWholeSession = grew
        ? `yes (${after.bars} bars over ${after.hits} hits; the session grew, so counts are not compared)`
        : `yes (${after.bars} bars held, hits ${before.hits} -> ${after.hits})`;
    }

    filter.value = '';
    filter.dispatchEvent(new dom.window.Event('input', { bubbles: true }));
    await settle();
    return result;
  }

  /**
   * The waterfall: calls grouped by the screen that was showing when each one started.
   *
   * The invariant that matters is conservation — every call lands in exactly one group. A join
   * across two independently recorded streams is exactly where rows get dropped or counted
   * twice, and either failure looks like a plausible chart.
   */
  async function probeWaterfall() {
    const tab = [...doc.querySelectorAll('#tabs .tab')].find((t) => t.dataset.view === 'waterfall');
    const result = { tabShown: Boolean(tab), groups: 0, rows: 0 };
    if (!tab) return result;

    await click(tab);
    await new Promise((r) => setTimeout(r, 800));
    result.groups = doc.querySelectorAll('.wf-group').length;
    result.rows = doc.querySelectorAll('.wf-row').length;
    result.names = [...doc.querySelectorAll('.wf-name')].map((n) => n.textContent);

    const listed = [...doc.querySelectorAll('.wf-row')].map((r) => r.dataset.id);
    const unique = new Set(listed);
    const inList = doc.querySelectorAll('#list .row').length;
    result.everyCallOnce = listed.length === unique.size
      ? `yes (${unique.size} of ${inList} calls, each once)`
      : `NO - ${listed.length} rows for ${unique.size} calls`;
    result.noneLost = unique.size === inList
      ? 'yes'
      : `NO - ${inList} calls in the list, ${unique.size} in the waterfall`;

    // Conservation only has teeth where the awkward case exists. A call that starts after the
    // last screen signal is caught by its own branch, and deleting that branch passes this probe
    // unnoticed on a session that happens to end on a signal — so report whether this session
    // exercised it, rather than letting a trivial pass read as a proof.
    const id = encodeURIComponent(doc.getElementById('session-picker').value);
    const [txns, signals] = await Promise.all([
      fetch(`${ORIGIN}/api/sessions/${id}/transactions?limit=2000`).then((r) => r.json()),
      fetch(`${ORIGIN}/api/sessions/${id}/signals?limit=2000`).then((r) => r.json()),
    ]);
    const screens = (Array.isArray(signals) ? signals : []).filter((s) => s.tag === 'screen');
    if (!screens.length) {
      result.trailingCalls = 'n/a (no screen signals)';
    } else {
      const lastScreen = Math.max(...screens.map((s) => s.mono));
      const trailing = (txns.items || []).filter((x) => x.mono >= lastScreen).length;
      result.trailingCalls = trailing
        ? `${trailing} after the last screen — the branch that catches them ran`
        : 'none in this session, so that branch was not exercised here';
    }

    // Every bar has to be visible. A zero-width bar reads as a row that failed to draw.
    const widths = [...doc.querySelectorAll('.wf-bar')].map((b) => parseFloat(b.style.width));
    result.barsVisible = widths.every((w) => w > 0)
      ? 'yes'
      : `NO - ${widths.filter((w) => !(w > 0)).length} bars have no width`;

    const row = doc.querySelector('.wf-row');
    if (row) {
      await click(row);
      await new Promise((r) => setTimeout(r, 600));
      result.rowSelects = doc.getElementById('detail').hidden
        ? 'NO - clicking a bar did nothing'
        : 'selects the call';
    }
    return result;
  }

  /**
   * Handing a finding to an agent.
   *
   * The bundle is a paste, so what matters is its content: that it names the session and the call
   * unambiguously, that it carries the calls that fetch the rest, and that it stays small enough
   * to be worth pasting. A bearer token ran 1300 characters on a real call — a quarter of the
   * payload spent on a value no agent reads — so the header cap is checked rather than trusted.
   *
   * The axis interplay (narrowing the brush narrows the bundle) needs layout and is verified in a
   * browser; the numbers are in the commit.
   */
  async function probeBundles() {
    const result = { txnButton: 'n/a', sessionButton: 'n/a' };
    const network = [...doc.querySelectorAll('#tabs .tab')].find((t) => t.dataset.view === 'network');
    if (network) {
      await click(network);
      await new Promise((r) => setTimeout(r, 400));
    }

    const session = doc.getElementById('session-picker').value;
    const row = doc.querySelector('#list .row');
    if (row) {
      await click(row);
      await new Promise((r) => setTimeout(r, 700));
      const button = [...doc.querySelectorAll('#detail .detail-head button')]
        .find((b) => b.textContent === 'copy for agent');
      if (!button) {
        result.txnButton = 'NO - no "copy for agent" button on a transaction';
      } else {
        lastCopied = null;
        await click(button);
        await new Promise((r) => setTimeout(r, 700));
        const text = lastCopied || '';
        const id = row.dataset.id;
        const missing = [
          !text.includes(session) && 'the session id',
          id && !text.includes(id) && 'the transaction id',
          !text.includes('get_transaction(') && 'the MCP call to read more',
          !/## Around it/.test(text) && 'what surrounded it',
        ].filter(Boolean);
        result.txnButton = missing.length
          ? `NO - missing ${missing.join(', ')}`
          : `${text.length} chars, names the session and the call`;

        // Header lines only. A minified JSON body is legitimately one very long line and has its
        // own, larger cap — measuring "the longest line in the paste" conflates the two and fails
        // on a body that was behaving exactly as intended.
        const headerLines = [];
        let inHeaders = false;
        for (const line of text.split('\n')) {
          if (line.startsWith('### ')) inHeaders = /headers$/.test(line);
          else if (line.startsWith('## ')) inHeaders = false;
          else if (inHeaders && line) headerLines.push(line);
        }
        const longest = headerLines.length ? Math.max(...headerLines.map((l) => l.length)) : 0;
        result.headersCapped = longest <= 260
          ? `yes (${headerLines.length} header lines, longest ${longest})`
          : `NO - a ${longest}-char header survived the cap`;
      }
    }

    // Escape drops the selection, which is what puts the session glance back on screen.
    doc.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await new Promise((r) => setTimeout(r, 400));
    const sessionButton = [...doc.querySelectorAll('#detail-empty button')]
      .find((b) => b.textContent === 'copy for agent');
    if (!sessionButton) {
      result.sessionButton = 'NO - no "copy for agent" button on the session panel';
    } else {
      lastCopied = null;
      await click(sessionButton);
      await new Promise((r) => setTimeout(r, 700));
      const text = lastCopied || '';
      result.sessionButton = text.includes('session_summary(') && text.includes(session)
        ? `${text.length} chars, names the session and the MCP entry point`
        : `NO - ${text ? 'missing the session or its MCP call' : 'nothing was copied'}`;
    }
    return result;
  }

  const bundleProbe = await probeBundles();
  const axisProbe = await probeAxis();
  const waterfallProbe = await probeWaterfall();
  const glanceProbe = await probeGlance();
  /**
   * The replay editor, which is `REPLAY.md` step 3.
   *
   * Nothing here may reach the network. Pressing **send** would fire a real edited request at
   * whatever host the captured session talked to, from a smoke test whose job is to read a page.
   *
   * The malformed-header check does press send, because "the form refuses this" is only
   * observable by trying — so `fetch` is stubbed for the duration. That is not belt-and-braces:
   * the first version relied on the guard being *present* to stop the request, so the one run
   * where the guard was broken was the one run that sent something. Against an unreachable host
   * that surfaced as the whole script dying on an uncaught DOMException; against a reachable one
   * it would have sent a real request and reported a pass.
   *
   * The seeding is the part worth checking. `Replayer` uses a sent header map **verbatim** and
   * skips its own filtering, so a form seeded from the raw capture would quietly put
   * `Content-Length` and `Host` back on a request whose body the user is about to change.
   */
  async function probeReplayEditor() {
    const result = { opened: false };
    const rows = [...doc.querySelectorAll('#list .row')];
    if (!rows.length) return { ...result, reason: 'no rows' };

    await click(rows[0]);
    await new Promise((r) => setTimeout(r, 400));

    const button = await replayItem('edit & replay');
    if (!button) return { ...result, reason: 'no edit button' };

    button.dispatchEvent(new window.Event('click', { bubbles: true }));
    await settle();

    const form = doc.querySelector('.replay-editor');
    if (!form) return { ...result, reason: 'form did not open' };
    const [methodInput, urlInput] = form.querySelectorAll('input.replay-input');
    const [headerBox, bodyBox] = form.querySelectorAll('textarea');

    const headerText = headerBox ? headerBox.value : '';
    const headerNames = headerText.split('\n').filter(Boolean).map((l) => l.split(':')[0].toLowerCase());
    const HOP = ['host', 'content-length', 'connection', 'accept-encoding', 'transfer-encoding'];

    // A malformed line must be reported rather than skipped: a request silently missing a header
    // the user believes they set fails in a way that looks like a server problem.
    const problem = form.querySelector('.replay-error');
    const realFetch = window.fetch;
    let sent = 0;
    window.fetch = (url, init) => {
      if (String(url).includes('/api/replay')) { sent++; return Promise.resolve({ json: async () => ({ ok: false, error: 'stubbed' }) }); }
      return realFetch(url, init);
    };
    headerBox.value = `${headerText}\nthis line has no colon`;
    form.querySelector('.btn').dispatchEvent(new window.Event('click', { bubbles: true }));
    await settle();
    window.fetch = realFetch;
    const rejected = Boolean(problem && !problem.hidden && /no colon/.test(problem.textContent));

    // Put it back, and shut the form so the snapshot and later probes are unaffected.
    headerBox.value = headerText;
    button.dispatchEvent(new window.Event('click', { bubbles: true }));
    await settle();

    return {
      opened: true,
      seeded: Boolean(methodInput && methodInput.value) && Boolean(urlInput && urlInput.value.startsWith('http')),
      headerLines: headerNames.length,
      hopByHopStripped: headerNames.every((n) => !HOP.includes(n)),
      hasBodyBox: Boolean(bodyBox),
      resignDefaultsOn: Boolean(form.querySelector('input[type=checkbox]')?.checked),
      malformedHeaderRejected: rejected,
      // The other half of the same claim, and the one that says the probe itself is safe: a
      // refused line must not have reached `/api/replay` at all.
      nothingSent: sent === 0,
      closesAgain: !doc.querySelector('.replay-editor'),
    };
  }

  /**
   * The plain replay button, pressed — with `fetch` stubbed so nothing reaches the network.
   *
   * This probe exists because its absence hid a real defect. `runReplay` anchored its result panel
   * on `pane.querySelector('.section-title')`, and `querySelector` searches the whole subtree: on
   * an ordinary row — no redaction banner, no attempt chain — the first match is a *grandchild*
   * inside a `.dtab-panel`, so `insertBefore` threw NotFoundError and the replay never ran. It
   * worked on exactly the rows with a direct-child section title, which are the interesting ones
   * somebody reaches for when testing replay by hand.
   *
   * The assertion is only that pressing the button renders a result panel. That is enough: the
   * failure was an exception before the request was even built.
   */
  async function probeReplayButton() {
    const rows = [...doc.querySelectorAll('#list .row')];
    if (!rows.length) return { pressed: false, reason: 'no rows' };
    await click(rows[0]);
    await new Promise((r) => setTimeout(r, 400));

    const pane = doc.getElementById('detail');
    const button = await replayItem('replay');
    if (!button) return { pressed: false, reason: 'no replay button' };

    const realFetch = window.fetch;
    let reached = 0;
    window.fetch = (url, init) => {
      if (String(url).includes('/api/replay')) {
        reached++;
        return Promise.resolve({ json: async () => ({ ok: true, status: 200, ms: 1, resignedHeaders: [] }) });
      }
      return realFetch(url, init);
    };
    button.dispatchEvent(new window.Event('click', { bubbles: true }));
    await settle();
    window.fetch = realFetch;

    const panel = pane.querySelector('.replay-result');
    return {
      pressed: true,
      // Direct children, because that is the thing that was wrong: the anchor has to be one.
      sectionTitlesDirect: [...pane.children].filter((c) => c.classList.contains('section-title')).length,
      sectionTitlesAnywhere: pane.querySelectorAll('.section-title').length,
      panelRendered: Boolean(panel),
      requestBuilt: reached === 1,
      statusShown: Boolean(panel && panel.querySelector('.status')),
    };
  }


  /**
   * The detail header: what is in it, in what order, and whether the replay menu behaves as one.
   *
   * The menu is driven the way a person would — open, Escape, open, click elsewhere — and judged
   * by `hidden` and `aria-expanded` agreeing, because a menu that closed visually while still
   * announcing itself as expanded is one a screen reader user cannot leave. Nothing in the menu
   * is pressed here: the replay probes above do that with the request stubbed.
   */
  async function probeDetailHeader() {
    const rows = [...doc.querySelectorAll('#list .row')];
    if (!rows.length) return { actions: 'n/a (no rows)' };
    await click(rows[0]);
    await new Promise((r) => setTimeout(r, 400));
    const head = doc.querySelector('#detail .detail-head');
    const labels = [...head.querySelectorAll('.detail-actions > .btn, .detail-actions > .menu-wrap > .btn')].map((b) => b.textContent);
    const result = {
      actions: labels.join(' · ') === 'copy cURL · copy for agent · replay ▾'
        ? `yes (${labels.join(' · ')}, right-aligned)`
        : `NO - ${labels.join(' · ')}`,
      noForAi: !doc.body.textContent.includes('for AI') ? 'yes' : 'NO - "for AI" is still on the page',
    };
    const trigger = [...head.querySelectorAll('.btn')].find((b) => b.textContent === 'replay ▾');
    const menu = head.querySelector('.menu');
    const agrees = () => menu.hidden === (trigger.getAttribute('aria-expanded') === 'false');
    const press = async (node) => { node.dispatchEvent(new window.MouseEvent('click', { bubbles: true })); await new Promise((r) => setTimeout(r, 40)); };
    await press(trigger);
    const opened = !menu.hidden && agrees()
      && [...menu.querySelectorAll('.menu-item')].map((i) => i.textContent).join(',') === 'replay,edit & replay'
      && doc.activeElement === menu.querySelector('.menu-item');
    menu.querySelector('.menu-item').dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await new Promise((r) => setTimeout(r, 40));
    const escapes = menu.hidden && agrees() && !doc.getElementById('detail').hidden;
    await press(trigger);
    await press(doc.querySelector('#detail .dtabs'));
    const outside = menu.hidden && agrees();
    result.replayMenu = opened && escapes && outside
      ? 'yes (opens on its first item, Escape and an outside click close it, detail stays open)'
      : `NO - opened ${opened}, Escape ${escapes}, outside click ${outside}`;
    const dtabRule = (css.match(/\.dtab\s*\{[^}]*\}/) || [''])[0];
    result.dtabWeight = /border-bottom:\s*1px/.test(dtabRule) && /font-size:\s*12px/.test(dtabRule)
      ? 'yes (1px underline, 12px)'
      : 'NO';
    return result;
  }

  const detailHeaderProbe = await probeDetailHeader();
  const replayButtonProbe = await probeReplayButton();
  const replayEditorProbe = await probeReplayEditor();


  /**
   * The JSON tree, on a real captured body.
   *
   * Every control is pressed and judged by what the rows do, never by a label or a class flipping:
   * a fold that toggled an arrow and left the rows alone would pass an assertion about the arrow.
   * The two claims with teeth are that hiding is view-only — the copy after hiding a field is the
   * copy from before — and that the tree's keys win over the page's inside it, since `f` also
   * means "focus the filter box" and the arrows also mean something to the list.
   */
  async function probeJsonTree() {
    const result = { found: 'NO - no row in this session has a JSON object or array body' };
    const network = [...doc.querySelectorAll('#tabs .tab')].find((t) => t.dataset.view === 'network');
    if (network) { await click(network); await settle(); }
    const resTab = () => [...doc.querySelectorAll('#detail .dtab')].find((t) => t.dataset.dtab === 'res');

    const rows = [...doc.querySelectorAll('#list .row')];
    let chosen = null;
    let best = 0;
    for (const row of rows.slice(0, 60)) {
      await click(row);
      await new Promise((r) => setTimeout(r, 200));
      if (resTab()) { await click(resTab()); await new Promise((r) => setTimeout(r, 60)); }
      const opens = doc.querySelectorAll('#detail .jt-view .jt-row[data-kind="open"]').length;
      // The richest body in reach, so focus has a branch to keep and siblings to fold.
      if (opens > best) { best = opens; chosen = row; }
      if (opens >= 4) break;
    }
    if (!chosen) return result;
    await click(chosen);
    await new Promise((r) => setTimeout(r, 300));
    if (resTab()) { await click(resTab()); await new Promise((r) => setTimeout(r, 60)); }

    const view = () => doc.querySelector('#detail .jt-view');
    const treeRows = () => [...(view()?.querySelectorAll('.jt-row') || [])];
    const tool = (label) => [...view().querySelectorAll('.jt-tools button')].find((b) => b.textContent === label);
    const press = async (node) => { node.dispatchEvent(new window.MouseEvent('click', { bubbles: true })); await new Promise((r) => setTimeout(r, 30)); };
    const key = async (node, k) => {
      node.dispatchEvent(new window.KeyboardEvent('keydown', { key: k, bubbles: true }));
      await new Promise((r) => setTimeout(r, 30));
    };

    const open = treeRows().length;
    result.found = `yes (${open} rows, ${best} containers open)`;

    await press(tool('⧉ copy'));
    const fullCopy = lastCopied;

    await press(tool('collapse all'));
    const folded = treeRows();
    result.collapseAll = folded.length < open && folded[0].dataset.kind === 'open'
      && folded.slice(1, -1).every((r) => r.dataset.kind !== 'open')
      ? `yes (${open} -> ${folded.length}, root stays open)`
      : `NO - ${open} -> ${folded.length}`;

    // Folding survives the pane being rebuilt: leave the row and come back.
    const other = rows.find((r) => r !== chosen);
    if (other) {
      await click(other);
      await new Promise((r) => setTimeout(r, 200));
      await click(chosen);
      await new Promise((r) => setTimeout(r, 300));
      if (resTab()) { await click(resTab()); await new Promise((r) => setTimeout(r, 60)); }
      result.remembered = treeRows().length === folded.length
        ? 'yes (still folded after selecting another row and coming back)'
        : `NO - ${folded.length} rows before leaving, ${treeRows().length} after`;
    }

    await press(tool('expand all'));
    result.expandAll = treeRows().length === open ? 'yes' : `NO - ${treeRows().length} of ${open}`;

    // A branch with something unrelated beside it, so focus has something to fold — a container
    // whose only other containers are its own ancestors would pass without folding anything.
    const within = (outer, inner) => outer === '' || inner === outer || inner.startsWith(`${outer}/`);
    const opens = treeRows().filter((r) => r.dataset.kind === 'open' && r.dataset.path !== '').map((r) => r.dataset.path);
    const path = opens.find((p) => opens.some((q) => !within(p, q) && !within(q, p))) || opens[0];
    if (path !== undefined) {
      // A summary row expands on click, and the twisty folds it back.
      await press(treeRows().find((r) => r.dataset.path === path).querySelector('.jt-tw'));
      const summary = treeRows().find((r) => r.dataset.path === path);
      const folds = summary?.dataset.kind === 'summary' && /\{ \d+ fields? \}|\[ \d+ items? \]/.test(summary.textContent);
      await press(summary.querySelector('.jt-sum'));
      result.foldOne = folds && treeRows().length === open ? 'yes (twisty folds, summary unfolds)' : 'NO';

      // Keyboard: left folds, right unfolds, and `f` stays in the tree rather than jumping to the
      // filter box.
      let row = treeRows().find((r) => r.dataset.path === path);
      row.focus();
      await key(row, 'ArrowLeft');
      row = treeRows().find((r) => r.dataset.path === path);
      const leftFolds = row.dataset.kind === 'summary' && doc.activeElement === row;
      await key(row, 'ArrowRight');
      row = treeRows().find((r) => r.dataset.path === path);
      const rightOpens = row.dataset.kind === 'open';
      await key(row, 'f');
      const stayed = doc.activeElement !== doc.getElementById('filter');
      const focused = view().querySelector('.jt-row.focus')?.dataset.path === path;
      result.keyboard = leftFolds && rightOpens && stayed && focused
        ? 'yes (← folds, → opens, f focuses the branch and not the filter box)'
        : `NO - left ${leftFolds}, right ${rightOpens}, f stayed ${stayed}, focused ${focused}`;

      // Focus keeps exactly the branch, its ancestors and its descendants open, and folds every
      // other container. Judged row by row, so a focus that folded too much or too little fails.
      const related = (p) => within(p, path) || within(path, p);
      const drawn = treeRows().filter((r) => r.dataset.kind === 'open' || r.dataset.kind === 'summary');
      const wrongOpen = drawn.filter((r) => r.dataset.kind === 'open' && !related(r.dataset.path));
      const wrongFolded = drawn.filter((r) => r.dataset.kind === 'summary' && related(r.dataset.path));
      const foldedOutside = drawn.filter((r) => r.dataset.kind === 'summary').length;
      result.focus = !wrongOpen.length && !wrongFolded.length && foldedOutside > 0
        ? `yes (${foldedOutside} unrelated branches folded, the branch and its ancestors open)`
        : `NO - ${wrongOpen.length} unrelated open, ${wrongFolded.length} related folded, ${foldedOutside} folded`;
      const unfocus = [...view().querySelector('.jt-row.focus .jt-act').children].find((b) => b.textContent === 'unfocus');
      await press(unfocus);
      result.unfocus = treeRows().length === open && !view().querySelector('.jt-row.focus') ? 'yes' : 'NO';
    }

    // Hiding is a view: a stub stands in, the footer lists it, and the copy is unchanged.
    const target = treeRows().find((r) => r.dataset.path !== '' && r.dataset.kind !== 'close');
    const hidePath = target.dataset.path;
    await press([...target.querySelector('.jt-act').children].find((b) => b.textContent === 'hide'));
    const stub = treeRows().find((r) => r.dataset.path === hidePath);
    const foot = view().querySelector('.jt-foot');
    await press(tool('⧉ copy'));
    result.hide = stub?.dataset.kind === 'stub' && foot && /^1 hidden/.test(foot.textContent)
      ? 'yes (stub + footer)'
      : 'NO';
    result.hideIsViewOnly = lastCopied === fullCopy
      ? 'yes (copy after hiding is byte-identical to copy before)'
      : 'NO - hiding changed what copy produces';
    await press([...foot.querySelectorAll('button')].find((b) => b.textContent === 'show all'));
    result.showAll = treeRows().length === open && !view().querySelector('.jt-foot') ? 'yes' : 'NO';

    // Raw is the same text copy produces, and switching back restores the tree.
    await press(tool('raw'));
    const raw = view().querySelector('pre.body');
    result.raw = raw && !view().querySelector('.jt') && raw.textContent === fullCopy
      ? 'yes (pre, identical to the copy)'
      : 'NO';
    await press(tool('raw'));
    result.rawOff = view().querySelector('.jt') ? 'yes' : 'NO';
    return result;
  }

  /**
   * The parser under the tree, which exists because `JSON.parse` rewrites what the app received:
   * doubles for every number, integer-like keys hoisted, duplicate keys dropped. Lifted out of the
   * real app.js by its markers, so this checks the shipped code rather than a copy of it.
   */
  function probeJsonParser() {
    const from = js.indexOf('  const JSON_NUMBER');
    const to = js.indexOf('  // --- JSON tree');
    if (from < 0 || to < from) return { ok: 'NO - the parser markers moved; this check has to follow them' };
    const { parseJsonTree, printJsonTree } =
      new Function(`${js.slice(from, to)}\nreturn { parseJsonTree, printJsonTree };`)();
    const pretty = (t) => printJsonTree(parseJsonTree(t));
    const failures = [];
    const expect = (name, got, want) => { if (got !== want) failures.push(name); };
    expect('big id', pretty('{"id":1234567890123456789}'), '{\n  "id": 1234567890123456789\n}');
    expect('key order', pretty('{"b":1,"2":2}'), '{\n  "b": 1,\n  "2": 2\n}');
    expect('duplicate keys', pretty('{"a":1,"a":2}'), '{\n  "a": 1,\n  "a": 2\n}');
    expect('escapes', pretty('["\\u00e9"]'), '[\n  "\\u00e9"\n]');
    for (const t of ['{}', '[]', '{"a":{"b":[1,{"c":"d"}],"e":[]},"f":{}}', ' [ 1 , "x" ] ']) {
      expect(`layout ${t}`, pretty(t), JSON.stringify(JSON.parse(t), null, 2));
    }
    for (const bad of ['{"a":1', '{"a":1,}', '[01]', '{a:1}', '[1] x', '', '"\\q"']) {
      let threw = false;
      try { parseJsonTree(bad); } catch { threw = true; }
      if (!threw) failures.push(`accepted ${JSON.stringify(bad)}`);
    }
    return { ok: failures.length ? `NO - ${failures.join(', ')}` : 'yes (ids, key order, duplicates, escapes, layout, rejection)' };
  }

  const detailTabProbe = await probeDetailTabs();
  const jsonTreeProbe = await probeJsonTree();
  const jsonParserProbe = probeJsonParser();
  // After the detail probe, which is what leaves a row open and its panes populated.
  const copyProbe = probeCopyFields();
  const drawerProbe = await probeDrawer();
  const toolbarProbe = await probeToolbar();
  const nowProbe = await probeNowStrip();
  const tabProbe = probeTabs();
  const toolbarLabelProbe = probeToolbarLabels();
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
    // `.toolbar` and `.now-strip` join the list because they are the newest members of exactly
    // the class of bug it guards: both set `display` and both are shown and hidden with `hidden`.
    const panes = [
      '.browser', '#browser', '.tabs', '#tabs', '#timeline', '#list', '.toolbar', '.now-strip',
      '.drawer-scrim', '.axis', '.waterfall',
    ];
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

  /**
   * The top bar's controls, pressed.
   *
   * `stop` is the dangerous one, and per the rule in AGENTS.md this probe stubs the request itself
   * rather than trusting the confirm step it is testing — the run where that step is broken is
   * the run that would otherwise stop the daemon this script is reading. The stub also counts
   * calls, so "one click did not stop it" is observed rather than assumed.
   */
  async function probeTopBar() {
    const result = {};
    const option = doc.querySelector('#session-picker option');
    const hasSession = Boolean(option);
    result.sessionOption = !hasSession ? 'n/a (no sessions)' : option && option.title && option.textContent !== option.title
      && / · \d+ calls?$/.test(option.textContent)
      ? `yes ("${option.textContent}", id in the tooltip)`
      : `NO - "${option?.textContent}"`;
    result.countsNoun = !hasSession ? 'n/a (no sessions)' : / of \d+ calls?$/.test(doc.getElementById('counts').textContent)
      ? `yes ("${doc.getElementById('counts').textContent}")`
      : `NO - "${doc.getElementById('counts').textContent}"`;
    result.conn = doc.getElementById('conn').dataset.state === 'connected' ? 'yes (connected)' : `NO - ${doc.getElementById('conn').dataset.state}`;

    // Live: the button names its state, and resuming re-reads the session.
    const live = doc.getElementById('live-btn');
    const press = async (node) => {
      node.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
      await new Promise((r) => setTimeout(r, 80));
    };
    await press(live);
    const paused = live.textContent.trim() === 'paused' && live.getAttribute('aria-pressed') === 'false';
    let reread = false;
    const realFetch = window.fetch;
    window.fetch = (path, init) => {
      if (String(path).includes('/transactions')) reread = true;
      return realFetch(path, init);
    };
    await press(live);
    await settle();
    window.fetch = realFetch;
    // Resuming re-reads the session on screen; with none there is nothing to re-read.
    result.liveToggle = paused && live.textContent.trim() === 'live' && (reread || !hasSession)
      ? `yes (live -> paused -> live${hasSession ? ', and resuming re-read the session' : '; no session to re-read'})`
      : `NO - paused ${paused}, back to "${live.textContent.trim()}", re-read ${reread}`;

    // The header delete arms and names what it deletes; it must not send anything on one click.
    const del = doc.getElementById('session-delete');
    let deletes = 0;
    window.fetch = (path, init) => {
      if ((init?.method || 'GET') === 'DELETE') { deletes++; return Promise.resolve(new Response('{}')); }
      return realFetch(path, init);
    };
    await press(del);
    const delArmed = del.textContent === 'delete session?' && del.classList.contains('armed') && deletes === 0;
    window.fetch = realFetch;
    del.dataset.armed = '0';
    del.textContent = '✕';
    del.classList.remove('armed');
    result.deleteArms = !hasSession
      ? (deletes === 0 ? 'n/a (no sessions; and nothing was sent)' : `NO - ${deletes} sent with no session`)
      : delArmed ? 'yes ("delete session?", nothing sent)' : `NO - "${del.textContent}", ${deletes} sent`;

    // Stop: one click arms, the second stops, and the page says so in a way it cannot miss.
    const stop = doc.getElementById('server-stop');
    let stops = 0;
    window.fetch = (path, init) => {
      if (String(path).includes('/api/server/stop')) { stops++; return Promise.resolve(new Response('{}')); }
      return realFetch(path, init);
    };
    await press(stop);
    const armedOnly = stop.textContent === 'stop daemon?' && stop.classList.contains('armed') && stops === 0;
    await press(stop);
    await new Promise((r) => setTimeout(r, 80));
    window.fetch = realFetch;
    const banner = doc.getElementById('server-banner');
    result.stopArms = armedOnly ? 'yes (one click armed it, nothing sent)' : `NO - "${stop.textContent}", ${stops} sent`;
    result.stopped = stops === 1 && !banner.hidden && banner.classList.contains('big')
      && banner.querySelector('.banner-title')?.textContent === 'daemon stopped'
      && doc.body.classList.contains('stopped')
      && doc.getElementById('conn').dataset.state === 'stopped'
      ? 'yes (banner, page dimmed, connection reads stopped)'
      : `NO - ${stops} stop requests, banner "${banner.textContent.slice(0, 40)}"`;
    // The banner must not offer a restart: the process that would receive it has exited.
    result.noRestartOffered = [...banner.querySelectorAll('button')].length === 0
      && /inspector serve/.test(banner.textContent)
      ? 'yes (says to run `inspector serve`, no button)'
      : 'NO - the banner offers a control nothing can answer';
    result.lastCapture = banner.querySelector('.banner-age')
      ? `yes ("${banner.querySelector('.banner-text').textContent}")`
      : 'n/a (no row to date it by)';

    // The daemon comes back: the page's own onopen must clear all of it.
    if (lastSocket && lastSocket.onopen) lastSocket.onopen();
    await new Promise((r) => setTimeout(r, 150));
    await settle();
    result.recovers = banner.hidden && !doc.body.classList.contains('stopped')
      && doc.getElementById('conn').dataset.state === 'connected' && !stop.disabled
      ? 'yes (banner gone, page undimmed, controls back)'
      : 'NO - the page stayed stopped after the daemon came back';
    return result;
  }

  const sessionProbe = await probeSessions();
  const topBarProbe = await probeTopBar();

  console.error('--- render report ---');
  console.error('rows rendered      :', doc.querySelectorAll('#list .row').length);
  console.error('sort flip reverses :', reversed, `(button then read "${flippedLabel}")`);
  console.error('sort flip restores :', restored);
  console.error(
    'list sequence      :', `${beforeSequence} -> ${afterSequence}`,
    afterSequence === [...beforeSequence].reverse().join('') ? '(exact mirror)' : '(NOT a mirror)',
  );
  console.error('method classes     :', methodClasses.join(', ') || 'none');
  console.error('--- list ---');
  for (const [name, value] of Object.entries(listProbe)) {
    console.error(`${name.padEnd(19)}:`, value);
  }
  console.error('marker dividers    :', doc.querySelectorAll('.marker-divider').length);
  console.error('session options    :', doc.querySelectorAll('#session-picker option').length);
  console.error('counts             :', doc.getElementById('counts').textContent);
  console.error('session option     :', doc.querySelector('#session-picker option')?.textContent ?? 'none');
  console.error('markers listed     :', doc.querySelectorAll('#markers li').length);
  console.error('detail visible     :', !doc.getElementById('detail').hidden);
  console.error(
    'detail sections    :', doc.querySelectorAll('#detail .section-title').length,
    '(pinned ones plus the open tab, not all five faces)',
  );
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
  console.error('opened on view     :', signalProbe.defaultView, "('network' — traffic leads)");
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
  console.error('runs collapsed     :', signalProbe.runsCollapsed, `(biggest x${signalProbe.biggestRun})`);
  console.error('run expands whole  :', signalProbe.runExpandsLossless, '(collapsing must lose nothing)');
  console.error('run collapses back :', signalProbe.runCollapsesBack);
  console.error('--- peers ---');
  console.error('peers listed       :', peerRows.length);
  console.error('peer roles         :', peerRoles.join(', ') || 'none');
  console.error('self has kill btn  :', selfKillButtons, '(must be 0)');
  console.error('--- sessions ---');
  console.error('session rows       :', sessionProbe.rows);
  console.error('deletes disabled   :', sessionProbe.disabledDeletes, '(sessions still recording)');
  console.error('clear all arms     :', sessionProbe.clearArms);
  console.error('header delete      :', sessionProbe.headerDelete);
  console.error('--- top bar ---');
  for (const [name, value] of Object.entries(topBarProbe)) {
    console.error(`${name.padEnd(19)}:`, value);
  }

  console.error('--- tabs ---');
  console.error('tabs               :', tabProbe.count, `(${tabProbe.ids.join(', ')})`);
  console.error('exactly one active :', tabProbe.activeCount === 1, `(${tabProbe.activeCount})`);
  console.error('unknown tags tabbed:', tabProbe.unknownTagsTabbed.join(', ') || 'none in this session');
  console.error('traffic tab first  :', tabProbe.trafficFirst);
  console.error('rail gone          :', toolbarProbe.railGone, '(controls belong to the view)');
  console.error('toolbar on traffic :', toolbarProbe.onTraffic);
  console.error('toolbar on browser :', toolbarProbe.onBrowser, '(it filters calls, not keys)');
  console.error('browser own filter :', toolbarProbe.ownFilterInstead ?? 'n/a');
  console.error('now strip present  :', nowProbe.present);
  console.error('now starts collapsed:', nowProbe.startsCollapsed ?? 'n/a');
  console.error('now says collapsed :', nowProbe.collapsedSummary);
  console.error('now expands to     :', nowProbe.expandsTo, 'rows; collapses back:', nowProbe.collapsesBack);
  console.error('--- handing to an agent ---');
  console.error('call bundle        :', bundleProbe.txnButton);
  console.error('header values cap  :', bundleProbe.headersCapped ?? 'n/a', '(a bearer token must not fill the paste)');
  console.error('session bundle     :', bundleProbe.sessionButton);
  console.error('--- axis & waterfall ---');
  console.error('axis on traffic    :', axisProbe.shown, `(${axisProbe.bars} buckets, ${axisProbe.screens} screen bands)`);
  console.error('session under probe:', axisProbe.sessionMoved);
  console.error('shape is whole sess:', axisProbe.shapeIsWholeSession);
  console.error('waterfall tab      :', waterfallProbe.tabShown, `- ${waterfallProbe.groups} screens, ${waterfallProbe.rows} calls`);
  console.error('screens grouped    :', (waterfallProbe.names || []).join(', ') || 'none');
  console.error('every call once    :', waterfallProbe.everyCallOnce ?? 'n/a');
  console.error('no call lost       :', waterfallProbe.noneLost ?? 'n/a');
  console.error('trailing calls     :', waterfallProbe.trailingCalls ?? 'n/a');
  console.error('every bar visible  :', waterfallProbe.barsVisible ?? 'n/a');
  console.error('bar selects call   :', waterfallProbe.rowSelects ?? 'n/a');
  console.error('empty pane (calls) :', glanceProbe.session, '-', glanceProbe.clickable, 'rows go somewhere');
  console.error('slowest row        :', glanceProbe.slowestSelects, '- esc restores:', glanceProbe.escRestores ?? 'n/a');
  console.error('empty pane (tag)   :', glanceProbe.tag);
  console.error('detail tabs        :', detailTabProbe.tabs.join(', ') || 'none');
  console.error('detail default tab :', detailTabProbe.defaultTab, '- opens on:', detailTabProbe.firstSection);
  console.error('body before headers:', detailTabProbe.bodyBeforeHeaders ?? 'n/a', '(the reason you opened the row)');
  console.error('one panel at a time:', detailTabProbe.onePanel);
  console.error('detail tab switches:', detailTabProbe.switches ?? 'n/a', '- remembered:', detailTabProbe.remembersAcrossRows ?? 'n/a');
  console.error('attempt chain      :', detailTabProbe.chainPinned, '(a correction must not be tabbed away)');
  console.error('drawer css         :', auditDrawerCss(css), '(narrow: list keeps the width)');
  console.error('drawer opens       :', drawerProbe.opensOnSelect, '- scrim:', drawerProbe.scrim);
  console.error('drawer closes by   :', `button ${drawerProbe.closeButton}, scrim ${drawerProbe.scrimCloses ?? 'n/a'}, esc ${drawerProbe.escape}`);
  console.error('drawer on tab swap :', drawerProbe.tabSwitch);
  console.error('tab labels         :', tabProbe.labels);
  console.error('tab bar shown      :', tabProbe.alwaysShown);
  console.error('signal separator   :', tabProbe.signalSeparator);
  console.error('--- toolbar labels ---');
  for (const [name, value] of Object.entries(toolbarLabelProbe)) {
    console.error(`${name.padEnd(19)}:`, value);
  }
  console.error('min text size      :', auditMinTextSize(css));
  console.error('--- scope bar ---');
  console.error('scope bar shown    :', scopeProbe.shown, scopeProbe.shown ? `- ${scopeProbe.label} (${scopeProbe.count})` : `- ${scopeProbe.reason}`);
  console.error('sticky in css      :', scopeProbe.stickyInCss, '(a bar that scrolls away makes the rows below it wrong)');
  console.error('rows stripped      :', scopeProbe.shown ? `${scopeProbe.strippedRows} of ${scopeProbe.totalRows}` : 'n/a');
  console.error('host not repeated  :', scopeProbe.noRepeatedHost ?? 'n/a', '(a covered row must not repeat the bar)');
  console.error('outside rows say it:', scopeProbe.uncoveredNameTheirHost ?? 'n/a', '(a row the bar does not cover names its host)');
  console.error('--- adding a marker ---');
  console.error('form present       :', markerFormProbe.present);
  console.error('blank label refused:', markerFormProbe.blankRejected ?? 'n/a');
  console.error('matches recording  :', markerFormProbe.matchesRecording ?? 'n/a', `- disabled: ${markerFormProbe.disabled}, recording: ${recordingNow.length}`);
  console.error('--- replay ---');
  console.error('button pressed    :', replayButtonProbe.pressed, replayButtonProbe.pressed ? '' : `- ${replayButtonProbe.reason}`);
  console.error('section titles    :', `${replayButtonProbe.sectionTitlesDirect ?? '?'} direct, ${replayButtonProbe.sectionTitlesAnywhere ?? '?'} anywhere (the anchor must be a direct child)`);
  console.error('result panel drawn:', replayButtonProbe.panelRendered ?? 'n/a', '(it threw before building the request until 2026-09-19)');
  console.error('request built     :', replayButtonProbe.requestBuilt ?? 'n/a');
  console.error('status shown      :', replayButtonProbe.statusShown ?? 'n/a');
  console.error('--- replay editor (REPLAY.md step 3) ---');
  console.error('form opens        :', replayEditorProbe.opened, replayEditorProbe.opened ? '' : `- ${replayEditorProbe.reason}`);
  console.error('seeded from capture:', replayEditorProbe.seeded ?? 'n/a');
  console.error('header lines      :', replayEditorProbe.headerLines ?? 'n/a');
  console.error('hop-by-hop stripped:', replayEditorProbe.hopByHopStripped ?? 'n/a', '(a sent map skips the daemon\'s own filtering)');
  console.error('body box present  :', replayEditorProbe.hasBodyBox ?? 'n/a');
  console.error('re-sign defaults on:', replayEditorProbe.resignDefaultsOn ?? 'n/a');
  console.error('bad header refused:', replayEditorProbe.malformedHeaderRejected ?? 'n/a', '(never skipped silently)');
  console.error('and nothing sent  :', replayEditorProbe.nothingSent ?? 'n/a', '(a refused line must not reach the wire)');
  console.error('closes again      :', replayEditorProbe.closesAgain ?? 'n/a');
  console.error('--- per-field copy ---');
  console.error('copy buttons       :', copyProbe.buttons, `- ${copyProbe.headerRows} header rows`);
  console.error('every header has 1 :', copyProbe.everyHeaderHasOne);
  console.error('body has one       :', copyProbe.bodyButton);
  console.error('all labelled       :', copyProbe.labelled, '(a screen reader must be able to say which value)');
  console.error('focus-within in css:', copyProbe.focusWithinInCss, '(without it, tabbing focuses an invisible button)');
  console.error('long tokens wrap   :', auditLongTokenWrap(css), '(a bearer token must not widen the pane)');

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

  console.error('--- detail header ---');
  for (const [name, value] of Object.entries(detailHeaderProbe)) {
    console.error(`${name.padEnd(19)}:`, value);
  }
  console.error('--- json tree ---');
  console.error('parser             :', jsonParserProbe.ok);
  for (const [name, value] of Object.entries(jsonTreeProbe)) {
    console.error(`${name.padEnd(19)}:`, value);
  }

  console.error('--- ages ---');
  console.error('age nodes present  :', ageProbe.present);
  console.error('ages tick          :', ageProbe.ticks);
  console.error('hidden panes hide  :', hiddenPaneAudit);
  console.error('errors             :', errors.length ? errors.join(' | ') : 'none');

  /*
   * Re-open the replay editor for the snapshot only.
   *
   * `probeReplayEditor` shuts it so the probes after it see an unperturbed pane, and one of those
   * probes re-renders the detail pane anyway — so the form was in no snapshot at all, and the
   * snapshot is the only thing anyone *looks* at. Done here, after every assertion, so it cannot
   * affect one.
   */
  const snapshotEditor = await replayItem('edit & replay');
  if (snapshotEditor && !doc.querySelector('.replay-editor')) {
    snapshotEditor.dispatchEvent(new window.Event('click', { bubbles: true }));
    await settle();
  }

  writeSnapshot('Static snapshot of the live UI (rendered from the real app.js against a real session). ');

  // The snapshot is written, but jsdom is still running a window: `pretendToBeVisual` drives a
  // requestAnimationFrame loop and app.js sets a 1s interval to repaint ages. Two live timer
  // handles, neither of which belongs to a page that has already been serialised — and enough to
  // keep node from exiting, so every caller had to notice the report had stopped and kill the
  // process. Closing the window clears them.
  //
  // Deliberately not wrapped in a finally: an unhandled rejection terminates node with exit 1
  // whatever timers are pending, so a crash already ends the process. Catching to close here
  // would only risk turning that failure into a clean exit.
  window.close();
})();
