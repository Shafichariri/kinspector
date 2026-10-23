'use strict';
/*
 * Where a measure case writes an exported session: a directory inside the run's own temp directory,
 * never directly under the OS temp directory.
 *
 * Each case still removes its directory in a `finally`, as soon as it is done with it. That alone
 * left them behind on Ctrl-C: Node ends on SIGINT or SIGTERM without running any `finally`, so
 * `stats-export-`, `room-export-`, `polish-export-` and `sheet-export-` directories piled up under
 * the OS temp directory, one set per interrupted run (KIN-109 named the first two). Inside the run's
 * directory they go with it, which `measure-web-ui.js`'s `cleanUp` removes on exit and on a signal.
 *
 * No fallback to the OS temp directory: a case run before the root is set throws, so a new caller
 * cannot quietly bring the leak back.
 */
const fs = require('fs');
const path = require('path');

let root = null;

/** Called once by measure-web-ui.js with the run's temp directory, before any case runs. */
function setScratchRoot(dir) {
  root = dir;
}

/** A fresh directory named `<prefix>XXXXXX` inside the run's temp directory. */
function scratchDir(prefix) {
  if (!root) throw new Error(`scratchDir('${prefix}'): no run directory set; measure-web-ui.js sets it with setScratchRoot`);
  return fs.mkdtempSync(path.join(root, prefix));
}

module.exports = { setScratchRoot, scratchDir };
