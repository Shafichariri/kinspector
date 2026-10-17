/* The web UI's source files, as the checks read them.
 *
 * The one place the *checks* learn which files make up the page. Today that is `app.js` and
 * `style.css`; when `app.js` splits into ordered scripts behind `web/scripts.txt`, this is the file
 * the checks need changed, and nothing that calls it does. Every check takes the list it returns
 * rather than a path, which is why `liftBlock` and `countNetworkPrimitives` search all of them.
 *
 * It is not the only place that knows the page's shape. Two others replace the literal
 * `<script src="/app.js"></script>` tag in `index.html` with inlined code, and both must follow a
 * split: `render-web-ui.js` (its `SCRIPT_TAG`, to build the snapshot) and `HtmlExport.kt` (its
 * `SCRIPT_TAG`, to build an exported session). Each refuses to proceed when its replacement misses,
 * so a split that forgets one fails loudly rather than shipping a page with no code.
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
