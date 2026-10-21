/* Smoke-test an exported session: the HTML file `GET /api/sessions/{id}/export.html` produces.
 *
 * The export's whole claim is that it is the web UI working from disk with no daemon. So this
 * opens the file the way a double-click does — a `file:` URL, the inline scripts run by the
 * page's own load — with `fetch`, `WebSocket` and `XMLHttpRequest` all made to throw and record the
 * attempt. A single network call is a failure, which is the proof: the file is self-contained
 * only if nothing it does needs anything else.
 *
 * Usage:
 *   curl -s http://127.0.0.1:8099/api/sessions/latest/export.html -o /tmp/session.html
 *   npm install jsdom && INSPECTOR_UI_ARCHIVE=/tmp/session.html node scripts/render-web-ui-archive.js
 *
 * A sibling of `render-web-ui.js` rather than a mode of it: that script proxies `fetch` to a live
 * daemon and its probes are written against one, while this one forbids the network outright.
 * The report goes to stderr in the same `label : value` shape, ending with how many checks ran;
 * exit 1 means a check said NO, or that INSPECTOR_UI_EXPECT_CHECKS was set and a different number
 * of checks ran — a probe that found nothing to look at reports `n/a` rather than failing, so the
 * count is the only sign a check stopped running.
 *
 * Tested against jsdom 29.1.1, the version CI installs.
 */
const fs = require('fs');
const { countNetworkPrimitives, NETWORK_EXPECTED } = require('./lib/web-audits');
const { LONG_LABELS, LONG_SESSION_ID, readLongSession, waitFor } = require('./lib/long-session-probes');
const { loadScripts, loadStyles } = require('./lib/web-sources');

let JSDOM, VirtualConsole;
try {
  ({ JSDOM, VirtualConsole } = require('jsdom'));
} catch {
  console.error('This script needs jsdom:\n  npm install jsdom\n(node_modules/ is gitignored.)');
  process.exit(2);
}

const FILE = process.env.INSPECTOR_UI_ARCHIVE;
if (!FILE) {
  console.error('Set INSPECTOR_UI_ARCHIVE to an exported session .html file.');
  process.exit(2);
}
// Optional: the long session's export, opened second and asked what the page read past the clamp.
const LONG_FILE = process.env.INSPECTOR_UI_ARCHIVE_LONG;

// A forbidden call throws inside the page. The page's async init does not catch it, so it surfaces
// here as an unhandled rejection — recorded, so the report names the call instead of node dying
// with a stack trace and no report at all. Recorded against the page opened last; the two are
// opened and probed one after the other, never at once.
let current = null;
process.on('unhandledRejection', (e) => current.errors.push(`unhandled: ${e && e.message ? e.message : e}`));
process.on('uncaughtException', (e) => current.errors.push(`uncaught: ${e && e.message ? e.message : e}`));

/** Opens an exported file as a double-click does, with every way to reach the network throwing. */
function openExport(file) {
  const page = { errors: [], attempts: [], copied: null };
  const virtualConsole = new VirtualConsole();
  virtualConsole.on('jsdomError', (e) => page.errors.push(`jsdomError: ${e.message}`));
  virtualConsole.on('error', (...a) => page.errors.push(`console.error: ${a.join(' ')}`));
  current = page;
  page.dom = new JSDOM(fs.readFileSync(file, 'utf8'), {
    // The page's own inline scripts, run by the page's own load — not code injected by this script,
    // which would prove only that this script can assemble a page.
    runScripts: 'dangerously',
    pretendToBeVisual: true,
    url: 'file:///exported/session.html',
    virtualConsole,
    beforeParse(window) {
      const forbid = (kind) => (target) => {
        page.attempts.push(`${kind} ${target}`);
        throw new Error(`network forbidden in an exported session: ${kind} ${target}`);
      };
      window.fetch = forbid('fetch');
      window.WebSocket = class { constructor(url) { forbid('WebSocket')(url); } };
      window.XMLHttpRequest = class { open(method, url) { forbid('XMLHttpRequest')(url); } };
      window.navigator.clipboard = { writeText: async (text) => { page.copied = text; } };
      if (!window.TextEncoder) window.TextEncoder = TextEncoder;
      if (!window.TextDecoder) window.TextDecoder = TextDecoder;
    },
  });
  return page;
}

const main = openExport(FILE);
const { errors, attempts } = main;
const { window } = main.dom;
const doc = window.document;

const results = [];
const check = (label, ok, detail = '') => {
  results.push({ label, ok });
  console.error(`${label.padEnd(26)}: ${ok ? 'yes' : 'NO'}${detail ? ` - ${detail}` : ''}`);
};
const note = (label, value) => console.error(`${label.padEnd(26)}: ${value}`);

/** The closing line, and the exit code: any NO, or a check count other than the one expected. */
function verdict() {
  const passed = results.filter((r) => r.ok).length;
  const failed = results.length - passed;
  console.error(`checks: ${passed} passed, ${failed} failed`);
  const expected = process.env.INSPECTOR_UI_EXPECT_CHECKS || undefined;
  const countWrong = expected !== undefined && Number(expected) !== passed;
  if (countWrong) console.error(`expected ${expected} passing checks, got ${passed}`);
  return failed || countWrong ? 1 : 0;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
/** Two identical consecutive samples mean the render finished; see `settle` in render-web-ui.js. */
async function settle(sample, timeoutMs = 4000) {
  const deadline = Date.now() + timeoutMs;
  let previous = null;
  while (Date.now() < deadline) {
    await sleep(120);
    const current = sample();
    if (current === previous) return;
    previous = current;
  }
}
/**
 * Whether the export is exactly the manifests, inlined: `[ok, detail]`, the detail naming every
 * condition that failed.
 *
 * Compared with the working tree's `web/scripts.txt` and `web/styles.txt`, so an export built from
 * another tree fails here, and says so; export the session again from a daemon built from this one.
 * Each condition closes a way to pass on too little. A count alone passes with the right number of
 * wrong files; a search for one known line passed with every file but one missing. Text is compared
 * exactly, position by position, which is also what makes order count. `@import` and `url(` are
 * searched in the inline stylesheets only, never in the code (a JavaScript function may well be
 * called `url`, and code reaching the network is what the network count holds) and never in the
 * data, where a captured body is free to mention either.
 */
function everyFileInlined(codeScripts) {
  const scripts = loadScripts();
  const styles = loadStyles();
  const inlineStyles = [...doc.querySelectorAll('style')].map((s) => s.textContent);
  const failed = [];
  if (codeScripts.length !== scripts.length) {
    failed.push(`${codeScripts.length} code script(s), scripts.txt lists ${scripts.length}`);
  }
  scripts.forEach(({ file }, i) => {
    if (!codeScripts[i] || codeScripts[i].text !== scripts[i].text) failed.push(`script ${i + 1} is not ${file}`);
  });
  if (inlineStyles.length !== styles.length) {
    failed.push(`${inlineStyles.length} inline <style>(s), styles.txt lists ${styles.length}`);
  }
  styles.forEach(({ file, text }, i) => {
    if (inlineStyles[i] !== text) failed.push(`<style> ${i + 1} is not ${file}`);
  });
  if (doc.querySelector('script[src]')) failed.push('a <script src> is left in the document');
  const linked = [...doc.querySelectorAll('link[rel]')].some((l) => /(^|\s)stylesheet(\s|$)/i.test(l.getAttribute('rel')));
  if (linked) failed.push('a <link rel="stylesheet"> is left in the document');
  inlineStyles.forEach((text, i) => {
    for (const reach of ['@import', 'url(']) {
      if (text.toLowerCase().includes(reach)) failed.push(`<style> ${i + 1} contains ${reach}`);
    }
  });
  return failed.length
    ? [false, failed.join('; ')]
    : [true, `${scripts.length} script(s) and ${styles.length} stylesheet(s), each its file, in manifest order`];
}

const click = (node) => node && node.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
const hiddenByCss = (node) => !node || window.getComputedStyle(node).display === 'none';

(async () => {
  await settle(() => `${doc.querySelectorAll('#list .row').length}|${doc.getElementById('counts').textContent}`);
  const archive = window.INSPECTOR_ARCHIVE;
  if (!archive) {
    console.error('No window.INSPECTOR_ARCHIVE in this file — is it an export?');
    process.exit(2);
  }
  const txns = archive.transactions.map((t) => t.txn);

  console.error(`--- exported session ${archive.meta?.sessionId ?? '(no metadata)'} ---`);
  note('calls / signals / markers', `${txns.length} / ${archive.signals.length} / ${archive.markers.length}`);

  // The code is the page's own: count the network call sites across every inlined script, with the
  // same counter `check-web-static.js` runs over the sources, so the two cannot disagree about what
  // a call site is.
  //
  // Except the data. The export embeds the session as `window.INSPECTOR_ARCHIVE = <json>;`, and a
  // captured body that mentions `fetch(` — any JavaScript the app downloaded, say — would count as a
  // call site and fail an export that is perfectly sound. A script is skipped as data only when the
  // text after the assignment *parses as JSON*: a JSON value cannot call anything, and anything
  // appended to it, a call included, breaks the parse and puts the whole script back among the code.
  // Matching on the prefix alone would let `window.INSPECTOR_ARCHIVE = {}; fetch(x)` through.
  const isArchiveData = (text) => {
    const m = text.match(/^\s*window\.INSPECTOR_ARCHIVE = ([\s\S]*?);?\s*$/);
    if (!m) return false;
    try { JSON.parse(m[1]); return true; } catch { return false; }
  };
  const scripts = [...doc.querySelectorAll('script')].map((s, i) => ({ file: `inline-${i}`, text: s.textContent }));
  const dataScripts = scripts.filter(({ text }) => isArchiveData(text));
  const inlined = scripts.filter((s) => !dataScripts.includes(s));
  check('session data is data only', dataScripts.length === 1,
    `${dataScripts.length} script(s) hold only the embedded session; ${inlined.length} counted as code`);
  check('every file inlined', ...everyFileInlined(inlined));
  const counts = countNetworkPrimitives(inlined);
  const wrong = Object.entries(NETWORK_EXPECTED).filter(([name, want]) => counts[name] !== want);
  check(
    'one network call site', wrong.length === 0,
    wrong.length
      ? wrong.map(([name, want]) => `${name} ${counts[name]}, must be ${want}`).join('; ')
      : `fetch ${counts.fetch}, WebSocket ${counts.webSocket}; every request must go through source.fetch`,
  );

  // --- what renders ---
  const rows = doc.querySelectorAll('#list .row').length;
  check('every call drawn', rows === txns.length, `${rows} of ${txns.length}`);
  const dividers = doc.querySelectorAll('#list .marker-divider').length;
  check('marker dividers', dividers === archive.markers.length, `${dividers} of ${archive.markers.length}`);
  note('counts', doc.getElementById('counts').textContent);
  note('session option', doc.querySelector('#session-picker option')?.textContent ?? 'none');

  // --- archive mode ---
  check('archive class', doc.body.classList.contains('archive'));
  const banner = doc.getElementById('server-banner');
  check(
    'banner names the export',
    !banner.hidden && banner.textContent.includes('exported') &&
      (!archive.meta || banner.textContent.includes(archive.meta.sessionId)),
    banner.textContent.trim().replace(/\s+/g, ' ').slice(0, 140),
  );
  const daemonOnly = ['#live-btn', '.daemon-group', '.export-item', '#chips', '#endpoints-button',
    '#endpoint-chips', '#marker-form', '#browser-pull'];
  const visible = daemonOnly.filter((sel) => !hiddenByCss(doc.querySelector(sel)));
  check('daemon controls hidden', visible.length === 0, visible.join(', ') || 'all hidden');
  // `⋮` is offered, for the shortcut legend and compact rows; the exports in it would ask a daemon
  // for this session, so opened, it draws none of them.
  const more = doc.getElementById('more-button');
  click(more);
  const moreItems = more ? [...more.parentElement.querySelectorAll('.menu-item')] : [];
  const exportsShown = moreItems.filter((i) => i.classList.contains('export-item') && !hiddenByCss(i));
  const othersShown = moreItems.filter((i) => !i.classList.contains('export-item') && !hiddenByCss(i)).map((i) => i.textContent);
  check('export items hidden', Boolean(more) && moreItems.length === 6 && exportsShown.length === 0 && othersShown.length === 2,
    more ? `${exportsShown.length} exports shown; offered: ${othersShown.join(', ') || 'nothing'}` : 'no ⋮');
  click(more);
  // The rail offers Traffic alone: every other page sends something or reads the machine.
  const rail = (page) => doc.querySelector(`#rail .rail-item[data-page="${page}"]`);
  const railShown = ['sessions', 'processes', 'settings'].filter((page) => !hiddenByCss(rail(page)));
  check('pages hidden in export', railShown.length === 0 && !hiddenByCss(rail('traffic')),
    railShown.length ? `shown: ${railShown.join(', ')}` : `traffic ${hiddenByCss(rail('traffic')) ? 'hidden too' : 'alone'}`);
  window.location.hash = '#/processes';
  await sleep(300);
  check(
    'routes fall back in export',
    window.location.hash === '#/traffic' && !doc.getElementById('page-traffic').hidden && doc.getElementById('page-processes').hidden,
    `${window.location.hash}, traffic ${doc.getElementById('page-traffic').hidden ? 'hidden' : 'shown'}`,
  );
  check('filter disabled', doc.getElementById('filter').disabled);

  // A marker item would ask for since:marker(...). It must leave the list alone and say why.
  const markerItem = [...doc.querySelectorAll('#markers li')].find((li) => !li.classList.contains('muted'))
    || doc.querySelector('#chips .chip');
  if (markerItem) {
    click(markerItem);
    await sleep(300);
    const after = doc.querySelectorAll('#list .row').length;
    const error = doc.getElementById('filter-error');
    check('filter request refused', after === rows && !error.hidden,
      `${after} rows, note: "${error.textContent.slice(0, 60)}…"`);
  } else {
    note('filter request refused', 'n/a (no marker or chip to press)');
  }

  // --- the detail pane, from embedded bodies ---
  const jsonRow = archive.transactions.find((t) => {
    if (!t.resBody || t.resBody.encoding !== 'utf8' || t.txn.resBodyTruncated) return false;
    try { JSON.parse(t.resBody.text); return /^[[{]/.test(t.resBody.text.trim()); } catch { return false; }
  });
  if (jsonRow) {
    click(doc.querySelector(`#list .row[data-id="${jsonRow.txn.id}"]`));
    await settle(() => `${doc.querySelectorAll('#detail .jt-row').length}|${doc.getElementById('detail').hidden}`);
    const treeRows = doc.querySelectorAll('#detail .jt-row').length;
    check('JSON body as a tree', treeRows > 0, `${treeRows} rows for ${jsonRow.txn.method} ${jsonRow.txn.path}`);
    const replay = doc.querySelector('#detail .replay-menu');
    check('replay hidden', hiddenByCss(replay), replay ? 'present but hidden' : 'not built');
    const cURL = [...doc.querySelectorAll('#detail button')].find((b) => /cURL/i.test(b.textContent));
    if (cURL) {
      click(cURL);
      await sleep(100);
      check('copy cURL works', typeof main.copied === 'string' && main.copied.startsWith('curl'), (main.copied || '').slice(0, 50));
    }
  } else {
    note('JSON body as a tree', 'n/a (no untruncated JSON response body in this session)');
  }
  const binaryRow = archive.transactions.find((t) => t.resBody && t.resBody.encoding === 'base64');
  if (binaryRow) {
    click(doc.querySelector(`#list .row[data-id="${binaryRow.txn.id}"]`));
    await sleep(300);
    check('binary body drawn', doc.querySelectorAll('#detail pre.body').length > 0);
  } else {
    note('binary body drawn', 'n/a (no base64 body in this session)');
  }

  // --- the timeline tab, merging all three streams ---
  const allTab = doc.querySelector('#tabs .tab[data-view="all"]');
  if (allTab && archive.signals.length) {
    click(allTab);
    await settle(() => `${doc.querySelectorAll('#timeline .tl-row').length}`);
    const tlRows = doc.querySelectorAll('#timeline .tl-row, #timeline .marker-divider').length;
    check('timeline drawn', tlRows > 0 && doc.querySelectorAll('#timeline .tl-signal, #timeline .tl-run').length > 0,
      `${tlRows} rows`);
    const withPayload = archive.signals.find((s) => archive.signalData?.[s.id] && doc.querySelector(`.tl-signal[data-id="${s.id}"]`));
    if (withPayload) {
      click(doc.querySelector(`.tl-signal[data-id="${withPayload.id}"]`));
      await settle(() => `${doc.querySelectorAll('#detail .jt-row, #detail pre').length}`);
      check('signal payload drawn', doc.querySelectorAll('#detail .jt-row, #detail pre').length > 0,
        `${withPayload.tag}/${withPayload.name}`);
    }
  } else {
    note('timeline drawn', 'n/a (no signals, so no timeline tab)');
  }

  // --- the path, whose repeated-screen notes read payloads from signalData ---
  const pathTab = doc.querySelector('#tabs .tab[data-view="path"]');
  if (pathTab) {
    click(pathTab);
    await settle(() => `${doc.querySelectorAll('#path .pv-visit, #path .pv-chip').length}`);
    const cols = doc.querySelectorAll('#path .pv-visit').length;
    const calls = [...doc.querySelectorAll('#path .pv-stats')]
      .reduce((n, s) => n + Number(s.dataset.calls || 0), 0);
    const rows = archive.transactions.length;
    check('path drawn', cols > 0 && calls === rows, `${cols} visits, ${calls} of ${rows} calls`);
  } else {
    check('path drawn', false, 'no path tab — it is meant to be always shown');
  }

  // --- a browser tab, whose payloads come from signalData ---
  const cacheTab = doc.querySelector('#tabs .tab[data-view="cache"]');
  if (cacheTab) {
    click(cacheTab);
    await settle(() => `${doc.querySelectorAll('#browser-list li').length}`);
    const keys = doc.querySelectorAll('#browser-list li').length;
    check('cache browser lists keys', keys > 0, `${keys} keys`);
    check('pull hidden', hiddenByCss(doc.getElementById('browser-pull')));
  } else {
    note('cache browser lists keys', 'n/a (no cache tab)');
  }

  // --- the signal route, as the export answers it ---
  // The page reads signals a page at a time with `offset` and `limit`, so the export has to slice
  // as the daemon does. Asked of the page's own `source` — a global `const`, which only the page's
  // global scope can name — and compared with the embedded rows sorted here, not by the page.
  const source = window.eval('source');
  const route = `/api/sessions/${encodeURIComponent(archive.meta?.sessionId ?? 'x')}/signals`;
  const ask = async (query) => {
    const res = await source.fetch(`${route}?${new URLSearchParams(query)}`);
    return { status: res.status, body: await res.json() };
  };
  const newestFirst = archive.signals.slice().sort((a, b) => b.mono - a.mono).map((s) => s.id);
  const total = newestFirst.length;
  const page = (reply, want) => reply.status === 200 && Array.isArray(reply.body) &&
    reply.body.map((s) => s.id).join(',') === want.join(',');
  const few = Math.max(1, Math.min(3, total - 1));
  const sliced = await ask({ offset: '0', limit: String(few) });
  check('signals sliced by limit', page(sliced, newestFirst.slice(0, few)),
    `limit=${few}: ${Array.isArray(sliced.body) ? sliced.body.length : sliced.status} of ${total}, newest first`);
  const whole = await ask({ offset: '0', limit: String(total + 5) });
  check('signals limit past total', page(whole, newestFirst),
    `limit=${total + 5}: ${Array.isArray(whole.body) ? whole.body.length : whole.status} of ${total}`);
  const beyond = await ask({ offset: String(total + 5), limit: '1000' });
  check('signals offset past end', page(beyond, []), `${JSON.stringify(beyond.body).slice(0, 40)}`);
  const filtered = await ask({ filter: 'tag:screen', offset: '0', limit: '1000' });
  check('signals filter refused', filtered.status === 400, `status ${filtered.status}`);
  const tabTotal = [...doc.querySelectorAll('#tabs .tab')]
    .filter((t) => !['network', 'path', 'all', 'waterfall'].includes(t.dataset.view))
    .reduce((sum, t) => sum + Number(t.querySelector('.tab-count')?.textContent ?? NaN), 0);
  check('signal tabs count all', tabTotal === total, `${tabTotal} of ${total}`);

  // --- the transaction route, as the export answers it ---
  // The same four questions of the page route: it is read a page at a time too, and an export that
  // answered every row whatever it was asked would draw a different list from the daemon's.
  const txnRoute = `/api/sessions/${encodeURIComponent(archive.meta?.sessionId ?? 'x')}/transactions`;
  const askTxns = async (query) => {
    const res = await source.fetch(`${txnRoute}?${new URLSearchParams(query)}`);
    return { status: res.status, body: await res.json() };
  };
  const txnsNewestFirst = txns.slice().sort((a, b) => b.mono - a.mono).map((t) => t.id);
  const txnTotal = txnsNewestFirst.length;
  const txnPage = (reply, want) => reply.status === 200 && Array.isArray(reply.body?.items) &&
    reply.body.total === txnTotal && reply.body.matched === txnTotal &&
    reply.body.items.map((t) => t.id).join(',') === want.join(',');
  const txnCount = (reply) => (Array.isArray(reply.body?.items) ? reply.body.items.length : reply.status);
  const fewTxns = Math.max(1, Math.min(3, txnTotal - 1));
  const txnSliced = await askTxns({ offset: '0', limit: String(fewTxns) });
  check('transactions sliced by limit', txnPage(txnSliced, txnsNewestFirst.slice(0, fewTxns)),
    `limit=${fewTxns}: ${txnCount(txnSliced)} of ${txnTotal}, newest first`);
  const txnWhole = await askTxns({ offset: '0', limit: String(txnTotal + 5) });
  check('transactions limit past total', txnPage(txnWhole, txnsNewestFirst),
    `limit=${txnTotal + 5}: ${txnCount(txnWhole)} of ${txnTotal}`);
  const txnBeyond = await askTxns({ offset: String(txnTotal + 5), limit: '1000' });
  check('transactions offset past end', txnPage(txnBeyond, []), `${txnCount(txnBeyond)} items`);
  const txnFiltered = await askTxns({ filter: 'status:500', offset: '0', limit: '1000' });
  check('transactions filter refused', txnFiltered.status === 400, `status ${txnFiltered.status}`);

  // --- the proof ---
  check('no network attempts', attempts.length === 0, attempts.slice(0, 3).join(' | ') || 'none');
  check('no page errors', errors.length === 0, errors.slice(0, 3).join(' | ') || 'none');
  window.close();

  // --- the long session's export: what the page read past the clamp, offline ---
  // See lib/long-session-probes.js. Its own window, opened as the first was, network forbidden.
  if (LONG_FILE) {
    const long = openExport(LONG_FILE);
    const longDoc = long.dom.window.document;
    const drawn = await waitFor(() => long.dom.window.INSPECTOR_ARCHIVE && longDoc.querySelector('#list .row'));
    const calls = drawn ? long.dom.window.INSPECTOR_ARCHIVE.transactions.map((t) => t.txn) : [];
    await settle(() => `${longDoc.querySelectorAll('#list .row').length}|${longDoc.getElementById('counts').textContent}`);
    console.error(`--- exported session ${long.dom.window.INSPECTOR_ARCHIVE?.meta?.sessionId ?? '(no metadata)'} ---`);
    // The shared verdicts read `yes (…)` or `NO - …`; the verdict word is check's to print.
    const detail = (verdict) => verdict.replace(/^(yes|NO)\s*(- )?/, '');
    for (const [label, value] of readLongSession(longDoc, calls, { exported: true })) check(label, value.startsWith('yes'), detail(value));
    // The pager's second question, the only one with a middle offset: the lines above all pass
    // against a route that slices to `limit` rather than to `offset + limit`, and this one does not.
    // A route that ignores `offset` already fails `transactions offset past end`.
    const oldest = calls.slice().sort((a, b) => b.mono - a.mono).slice(1000).map((t) => t.id);
    const longRoute = `/api/sessions/${encodeURIComponent(LONG_SESSION_ID)}/transactions`;
    const second = await (await long.dom.window.eval('source').fetch(`${longRoute}?offset=1000&limit=1000`)).json();
    const secondIds = (second.items || []).map((t) => t.id);
    check('long offset 1000 sliced', calls.length > 1000 && secondIds.join(',') === oldest.join(','),
      `offset=1000&limit=1000: ${secondIds.length} rows, the ${oldest.length} oldest due`);
    // The oldest stretch, which only an export drawing every call it embeds can show: drawn from
    // the newest 1000, it read "0 of 1203 calls" and "no calls in the selected stretch" while the
    // glance's THIS STRETCH counted them.
    const byMono = calls.slice().sort((a, b) => a.mono - b.mono);
    if (byMono.length > 150) long.dom.window.eval(`setTimeRange({ from: ${byMono[0].mono}, to: ${byMono[150].mono} })`);
    await settle(() => `${longDoc.querySelectorAll('#list .row').length}|${longDoc.getElementById('counts').textContent}`);
    const stretchText = longDoc.getElementById('counts').textContent;
    const stretchRows = longDoc.querySelectorAll('#list .row').length;
    const stretchDue = `151 of ${calls.length} calls`;
    check('long stretch oldest', stretchText === stretchDue && stretchRows === 151,
      `"${stretchText}", ${stretchRows} rows drawn; "${stretchDue}" and 151 rows due`);
    // An export draws every call it embeds, so the list is never short of its count: no note.
    const listNote = longDoc.getElementById('list-note');
    check('long list note absent', !!listNote && listNote.hidden, listNote ? `hidden ${listNote.hidden}, "${listNote.textContent}"` : 'no #list-note');
    check('long no network attempts', long.attempts.length === 0, long.attempts.slice(0, 3).join(' | ') || 'none');
    check('long no page errors', long.errors.length === 0, long.errors.slice(0, 3).join(' | ') || 'none');
    long.dom.window.close();
  } else {
    // Information: CI sets INSPECTOR_UI_ARCHIVE_LONG, and its expected count notices these missing.
    for (const label of [...LONG_LABELS, 'long offset 1000 sliced', 'long stretch oldest', 'long list note absent', 'long no network attempts', 'long no page errors']) {
      note(label, 'n/a (INSPECTOR_UI_ARCHIVE_LONG is not set)');
    }
  }
  process.exit(verdict());
})().catch((e) => {
  // A probe step that throws — a row it expected missing because nothing rendered — is a failure
  // to report, not a reason to hang: the page's timers would otherwise keep node alive forever.
  check('probe completed', false, e && e.stack ? e.stack.split('\n').slice(0, 2).join(' ') : String(e));
  check('no network attempts', attempts.length === 0, attempts.slice(0, 3).join(' | ') || 'none');
  window.close();
  process.exit(1);
});
