/* The web UI's source files, as the checks read them.
 *
 * The one place the *checks* learn which files make up the page: `web/scripts.txt` and
 * `web/styles.txt`, in load order. Every check takes the list this returns rather than a path,
 * which is why `liftBlock` and `countNetworkPrimitives` search all of them.
 *
 * It reads a manifest with the rule `WebPage.entries` in the daemon uses: a line that is blank or
 * starts with `#` is skipped, and any other line is an entry exactly as written. Trimming here
 * would let a stray space pass the checks while the daemon failed to resolve the same entry.
 *
 * It is not the only place that knows the page's shape. Two others expand the slots in
 * `index.html`, `<!-- inspector:styles -->` and `<!-- inspector:scripts -->`: `render-web-ui.js`,
 * to build the page it probes, and `WebPage.kt`, for `GET /` and for an exported session. Each
 * refuses to proceed when an expansion misses, so a slot that moved fails loudly rather than
 * shipping a page with no code.
 *
 * Zero dependencies: `check-web-static.js` must run with nothing installed.
 */
const fs = require('fs');
const path = require('path');

const WEB = path.join(__dirname, '..', '..', 'inspector-daemon', 'src', 'main', 'resources', 'web');

/** The two slot literals `index.html` holds; `WebPage.STYLES_SLOT` and `SCRIPTS_SLOT` in the daemon. */
const STYLES_SLOT = '<!-- inspector:styles -->';
const SCRIPTS_SLOT = '<!-- inspector:scripts -->';

/**
 * A manifest's entries in order, e.g. `['js/00-core.js', 'js/05-network.js', …]`.
 *
 * Split where Kotlin's `lines()` splits — `\r\n`, `\n` or a lone `\r` — so a manifest saved with CRLF
 * endings gives the same entries here as in the daemon, rather than every entry ending in `\r`.
 */
function manifest(name) {
  return fs.readFileSync(path.join(WEB, name), 'utf8')
    .split(/\r\n|\n|\r/)
    .filter((line) => !(line.trim() === '' || line.trimStart().startsWith('#')));
}

function load(name) {
  return manifest(name).map((file) => ({ file, text: fs.readFileSync(path.join(WEB, file), 'utf8') }));
}

/** Every script the page loads, in load order: `[{ file, text }]`. */
function loadScripts() {
  return load('scripts.txt');
}

/** Every stylesheet the page loads, in cascade order: `[{ file, text }]`. */
function loadStyles() {
  return load('styles.txt');
}

/** The page shell. */
function loadIndexHtml() {
  return fs.readFileSync(path.join(WEB, 'index.html'), 'utf8');
}

module.exports = { WEB, STYLES_SLOT, SCRIPTS_SLOT, manifest, loadScripts, loadStyles, loadIndexHtml };
