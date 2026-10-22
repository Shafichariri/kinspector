'use strict';
/* Probes of the web UI polish, for render-web-ui.js: what the polish rules draw, read in jsdom.
 * Sizes and widths are Chrome's to judge (`lib/polish-cases.js`); these read the markup.
 *
 * Each returns `[label, value]` pairs, as `lib/shell-probes.js` does, and leaves the page as it was.
 */

/**
 * The Sessions page's row leads with the start time, as the picker does: the label's first child is
 * `.session-row-time` holding `sessionLabelParts(meta).when`, and the app follows it. An error-free
 * session's errors cell reads `0`, as Stats prints `0%`, rather than nothing. Read on the page as
 * drawn; which sessions are error-free is the daemon's list (`state.sessions`), never the cells read.
 */
function probeSessionRowTime(ctx) {
  const { window, doc, pageState } = ctx;
  const rows = [...doc.querySelectorAll('#session-rows .session-row')];
  if (!rows.length) return [['session row time', 'NO - found no session row, so this checked nothing']];
  const sessions = pageState().sessions;
  const bad = [];
  let errorFree = 0;
  for (const row of rows) {
    const meta = sessions.find((m) => m.sessionId === row.dataset.id);
    if (!meta) { bad.push(`${row.dataset.id} is not in the session list`); continue; }
    const when = window.sessionLabelParts(meta).when;
    const first = row.querySelector('.session-row-label')?.firstElementChild;
    if (!first || !first.classList.contains('session-row-time') || first.textContent !== when) {
      bad.push(`${meta.sessionId} leads with ${first ? `${first.className} "${first.textContent}"` : 'no element'}, not the time "${when}"`);
    }
    if (!meta.errorCount) {
      errorFree++;
      const cell = row.querySelector('.session-errors');
      if (!cell || cell.textContent !== '0') bad.push(`${meta.sessionId} has no errors and its cell reads "${cell ? cell.textContent : 'nothing'}"`);
    }
  }
  return [['session row time', bad.length ? `NO - ${bad.join('; ')}`
    : `yes (${rows.length} rows lead with their start time; ${errorFree ? `${errorFree} error-free read 0` : 'n/a for 0: every session has errors'})`]];
}

module.exports = { probeSessionRowTime };
