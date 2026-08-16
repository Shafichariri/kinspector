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

  const rows = window.document.querySelectorAll('.row');
  const interesting =
    [...rows].find((r) => r.querySelector('.status')?.textContent === '500') || rows[rows.length - 1];
  if (interesting) interesting.dispatchEvent(new window.Event('click', { bubbles: true }));
  await new Promise((r) => setTimeout(r, 900));

  const doc = window.document;
  console.error('--- render report ---');
  console.error('rows rendered      :', doc.querySelectorAll('.row').length);
  console.error('marker dividers    :', doc.querySelectorAll('.marker-divider').length);
  console.error('session options    :', doc.querySelectorAll('#session-picker option').length);
  console.error('counts             :', doc.getElementById('counts').textContent);
  console.error('session label      :', doc.getElementById('session-app').textContent);
  console.error('markers listed     :', doc.querySelectorAll('#markers li').length);
  console.error('detail visible     :', !doc.getElementById('detail').hidden);
  console.error('detail sections    :', doc.querySelectorAll('#detail .section-title').length);
  console.error('body blocks        :', doc.querySelectorAll('#detail pre.body').length);
  console.error('chain rows         :', doc.querySelectorAll('#detail .chain-row').length);
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
