/* The web UI's source files, as the checks read them.
 *
 * The one place that knows which files make up the page. Today that is `app.js` and `style.css`;
 * when `app.js` splits into ordered scripts behind `web/scripts.txt`, this is the file that changes
 * and nothing that calls it does. Every check takes the list it returns rather than a path, which is
 * why `liftBlock` and `countNetworkPrimitives` search all of them.
 *
 * Zero dependencies: `check-web-static.js` must run with nothing installed.
 */
const fs = require('fs');
const path = require('path');

const WEB = path.join(__dirname, '..', '..', 'inspector-daemon', 'src', 'main', 'resources', 'web');

/** Every script the page loads, in load order: `[{ file, text }]`. */
function loadScripts() {
  return ['app.js'].map((file) => ({ file, text: fs.readFileSync(path.join(WEB, file), 'utf8') }));
}

/** Every stylesheet the page loads, in load order: `[{ file, text }]`. */
function loadStyles() {
  return ['style.css'].map((file) => ({ file, text: fs.readFileSync(path.join(WEB, file), 'utf8') }));
}

/** The page shell. */
function loadIndexHtml() {
  return fs.readFileSync(path.join(WEB, 'index.html'), 'utf8');
}

module.exports = { WEB, loadScripts, loadStyles, loadIndexHtml };
