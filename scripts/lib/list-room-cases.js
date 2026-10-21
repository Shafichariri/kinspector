'use strict';
/*
 * The traffic page's room for the list, read in Chrome (group `room` in scripts/measure-web-ui.js):
 * what jsdom cannot see, because each is layout, focus or paint. On the probe session.
 *
 * Judged: below 1200px the closed drawer neither widens the document (`room <w> doc width closed`)
 * nor takes focus from Tab, which is walked with real key presses until focus comes back round
 * (`room <w> tab never enters closed`), and casts no shadow in either colour scheme (`room <w> closed
 * shadow <scheme>`); the open drawer's `✕` overlaps no header action, served and in an HTML export
 * opened from disk (`room 1000 close disjoint served` / `… export`).
 *
 * Each reading leaves the page as it found it: drawer closed, colour scheme the system's.
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const { sleep } = require('./measure-cdp');

const LOADED = `typeof state !== 'undefined' && state.sessionId && document.querySelectorAll('#list .row').length > 0`;
const DRAWER_OPEN = `document.getElementById('panes').classList.contains('drawer-open') && !document.getElementById('detail').hidden
  && document.querySelectorAll('#detail .detail-actions button').length > 0`;

async function load(page, url, width) {
  await page.setWidth(width);
  await page.navigate(url);
  if (await page.waitFor(LOADED, 60000) === null) throw new Error(`${url} drew no rows within 60 s`);
  await sleep(500);
}

/** Opens the drawer on a row — the 500 where there is one, whose header has the most actions. */
async function openRow(page) {
  await page.ev(`(() => { const rows = [...document.querySelectorAll('#list .row')];
    (rows.find((r) => r.querySelector('.status').textContent === '500') || rows[0]).click(); })()`);
  await page.waitFor(DRAWER_OPEN, 5000);
  await sleep(300);
}

/** A row's detail drawn, then the drawer closed, as `✕` closes it: the case the defects were in. */
async function openThenClose(page) {
  await openRow(page);
  await page.ev(`document.getElementById('drawer-close').click()`);
  await sleep(400); // the slide is 140ms and visibility follows it
}

const DOC_WIDTH = `({ doc: document.documentElement.scrollWidth, width: innerWidth, x: scrollX })`;

// Where focus is, as an id stable across one walk: the element's index in the document.
const FOCUS = `(() => {
  const a = document.activeElement;
  const id = !a || a === document.body ? null : (a.id || a.tagName.toLowerCase()) + '#' + [...document.querySelectorAll('*')].indexOf(a);
  return { id, inDrawer: !!a && document.getElementById('detail-pane').contains(a), x: scrollX };
})()`;

/** Tab, as a person presses it, until focus comes back to where it started; cap 300 presses. */
async function tabWalk(page) {
  await page.ev(`(() => { if (document.activeElement) document.activeElement.blur(); scrollTo(0, 0); })()`);
  let first = null;
  let presses = 0;
  let stops = 0;
  let maxX = 0;
  const inside = [];
  for (; presses < 300; presses++) {
    await page.key('Tab', 'Tab', 9);
    const f = await page.ev(FOCUS);
    if (f.x > maxX) maxX = f.x;
    if (f.inDrawer) inside.push(f.id);
    if (!f.id) continue; // past the last control, focus leaves the page before it comes round
    if (first === null) first = f.id;
    else if (f.id === first) break;
    stops++;
  }
  return { round: presses < 300, presses, stops, inside, maxX };
}

const DISJOINT = `(() => {
  const close = document.getElementById('drawer-close');
  const x = close.getBoundingClientRect();
  const actions = [...document.querySelectorAll('#detail .detail-actions button')].filter((b) => b.getClientRects().length);
  const hit = actions.filter((b) => { const r = b.getBoundingClientRect();
    return r.left < x.right && r.right > x.left && r.top < x.bottom && r.bottom > x.top; }).map((b) => b.textContent.trim());
  return { shown: x.width > 0 && x.height > 0, hit, actions: actions.map((b) => b.textContent.trim()),
    box: [x.left, x.top, x.width, x.height].map(Math.round).join(',') };
})()`;

function disjointRow(key, d) {
  const ok = Boolean(d) && !d.evalError && d.shown && d.actions.length > 0 && d.hit.length === 0;
  return { key, value: ok ? 'disjoint' : d && d.hit && d.hit.length ? 'overlaps' : 'not measured', ok,
    detail: !d || d.evalError ? String(d && d.evalError) : `✕ at ${d.box}; actions ${d.actions.join(', ')}${d.hit.length ? `; under it: ${d.hit.join(', ')}` : ''}` };
}

/** The drawer below 1200px: closed, it is out of the way; open, its `✕` is over nothing. */
async function drawer(page, url, rows) {
  for (const width of [1000, 400]) {
    await load(page, url, width);
    await openThenClose(page);
    const w = await page.ev(DOC_WIDTH);
    rows.push({ key: `room ${width} doc width closed`, value: w.doc === w.width ? 'window' : 'wider', ok: w.doc === w.width,
      detail: `documentElement.scrollWidth ${w.doc} of ${w.width}` });
  }
  await load(page, url, 1000);
  await openThenClose(page);
  const t = await tabWalk(page);
  const ok = t.round && t.inside.length === 0 && t.maxX === 0;
  rows.push({ key: 'room 1000 tab never enters closed', value: ok ? 'never' : t.inside.length ? 'enters' : t.maxX ? 'scrolls' : 'no round',
    ok, detail: `${t.stops} stops in ${t.presses} presses${t.inside.length ? `; inside: ${t.inside.slice(0, 4).join(' ')}` : ''}; scrollX at most ${t.maxX}` });
  for (const scheme of ['light', 'dark']) {
    await page.colorScheme(scheme);
    await sleep(200);
    const closed = await page.ev(`getComputedStyle(document.getElementById('detail-pane')).boxShadow`);
    rows.push({ key: `room 1000 closed shadow ${scheme}`, value: closed === 'none' ? 'none' : 'drawn', ok: closed === 'none', detail: closed });
  }
  await page.colorScheme(null);
  await openRow(page);
  rows.push(disjointRow('room 1000 close disjoint served', await page.ev(DISJOINT)));
  await page.ev(`document.getElementById('drawer-close').click()`);

  // An export draws its own actions (no replay), and is the page as a file, so it is read as one.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'room-export-'));
  try {
    const file = path.join(dir, 'export.html');
    fs.writeFileSync(file, Buffer.from(await (await fetch(`${url}api/sessions/latest/export.html`)).arrayBuffer()));
    await load(page, `file://${file}`, 1000);
    await openRow(page);
    rows.push(disjointRow('room 1000 close disjoint export', await page.ev(DISJOINT)));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

async function room(page, url) {
  const rows = [];
  await drawer(page, url, rows);
  const seen = [...new Set(page.exceptions)];
  rows.push({ key: 'room page exceptions', value: seen.length ? 'thrown' : 'none', ok: seen.length === 0, detail: seen.join(' | ') });
  return rows;
}

module.exports = { room };
