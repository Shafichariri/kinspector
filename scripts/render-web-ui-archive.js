/* Smoke-test an exported session: the HTML file `GET /api/sessions/{id}/export.html` produces.
 *
 * The export's whole claim is that it is the web UI working from disk with no daemon. So this
 * opens the file the way a double-click does — a `file:` URL, the two inline scripts run by the
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
 * The report goes to stderr in the same `label : value` shape; exit 1 means a check said NO.
 */
const fs = require('fs');

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
const html = fs.readFileSync(FILE, 'utf8');

const errors = [];
const attempts = [];
const virtualConsole = new VirtualConsole();
virtualConsole.on('jsdomError', (e) => errors.push(`jsdomError: ${e.message}`));
virtualConsole.on('error', (...a) => errors.push(`console.error: ${a.join(' ')}`));

// A forbidden call throws inside the page. The page's async init does not catch it, so it surfaces
// here as an unhandled rejection — recorded, so the report names the call instead of node dying
// with a stack trace and no report at all.
process.on('unhandledRejection', (e) => errors.push(`unhandled: ${e && e.message ? e.message : e}`));
process.on('uncaughtException', (e) => errors.push(`uncaught: ${e && e.message ? e.message : e}`));

let copied = null;
const dom = new JSDOM(html, {
  // The page's own inline scripts, run by the page's own load — not app.js injected by this script,
  // which would prove only that this script can assemble a page.
  runScripts: 'dangerously',
  pretendToBeVisual: true,
  url: 'file:///exported/session.html',
  virtualConsole,
  beforeParse(window) {
    const forbid = (kind) => (target) => {
      attempts.push(`${kind} ${target}`);
      throw new Error(`network forbidden in an exported session: ${kind} ${target}`);
    };
    window.fetch = forbid('fetch');
    window.WebSocket = class { constructor(url) { forbid('WebSocket')(url); } };
    window.XMLHttpRequest = class { open(method, url) { forbid('XMLHttpRequest')(url); } };
    window.navigator.clipboard = { writeText: async (text) => { copied = text; } };
    if (!window.TextEncoder) window.TextEncoder = TextEncoder;
    if (!window.TextDecoder) window.TextDecoder = TextDecoder;
  },
});
const { window } = dom;
const doc = window.document;

const results = [];
const check = (label, ok, detail = '') => {
  results.push({ label, ok });
  console.error(`${label.padEnd(26)}: ${ok ? 'yes' : 'NO'}${detail ? ` - ${detail}` : ''}`);
};
const note = (label, value) => console.error(`${label.padEnd(26)}: ${value}`);

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

  // The code is the page's own: count the browser fetch calls in the inlined app.js.
  const appJs = [...doc.querySelectorAll('script')].map((s) => s.textContent).find((t) => t.includes('const ARCHIVE = '));
  const bareFetches = appJs ? (appJs.match(/(?<![.\w])fetch\(/g) || []).length : 0;
  check('app.js inlined', !!appJs);
  check('one network call site', bareFetches === 1, `${bareFetches} in app.js; every request must go through source.fetch`);

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
  const daemonOnly = ['#live-btn', '.daemon-group', '#session-delete', '#export-host', '#chips', '#chips-label',
    '#endpoint-chips', '#marker-form', '#browser-pull'];
  const visible = daemonOnly.filter((sel) => !hiddenByCss(doc.querySelector(sel)));
  check('daemon controls hidden', visible.length === 0, visible.join(', ') || 'all hidden');
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
      check('copy cURL works', typeof copied === 'string' && copied.startsWith('curl'), (copied || '').slice(0, 50));
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

  // --- the proof ---
  check('no network attempts', attempts.length === 0, attempts.slice(0, 3).join(' | ') || 'none');
  check('no page errors', errors.length === 0, errors.slice(0, 3).join(' | ') || 'none');

  window.close();
  process.exit(results.every((r) => r.ok) ? 0 : 1);
})().catch((e) => {
  // A probe step that throws — a row it expected missing because nothing rendered — is a failure
  // to report, not a reason to hang: the page's timers would otherwise keep node alive forever.
  check('probe completed', false, e && e.stack ? e.stack.split('\n').slice(0, 2).join(' ') : String(e));
  check('no network attempts', attempts.length === 0, attempts.slice(0, 3).join(' | ') || 'none');
  window.close();
  process.exit(1);
});
