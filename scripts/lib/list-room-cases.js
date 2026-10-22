'use strict';
/*
 * The traffic page's room for the list, read in Chrome (group `room` in scripts/measure-web-ui.js):
 * what jsdom cannot see, because each is layout, focus or paint. On the probe session, but for one
 * reading on the 3016-call session.
 *
 * Judged: below 1200px the closed drawer neither widens the document (`room <w> doc width closed`)
 * nor takes focus from Tab, which is walked with real key presses until focus comes back round
 * (`room <w> tab never enters closed`), and casts no shadow in either colour scheme (`room <w> closed
 * shadow <scheme>`); the open drawer's `✕` overlaps no header action, served and in an HTML export
 * opened from disk (`room 1000 close disjoint served` / `… export`); at 400px every row of the probe
 * session keeps its path's last segment, its byte count and its repeat mark whole (`room 400 rows`),
 * and on the payments session its findings badge too, short, in the text colour and no taller than the
 * row beside it (`room 400 payments rows`);
 * and at 1440 the list pane starts no lower than 160px (`room 1440 bands list`), the timeline's
 * start reported beside it, with every band's height in the detail; the toolbar is one row at
 * 1440 and 1000 (`room <w> toolbar one row`) and wraps without overflowing at 600 and 400 (`room <w>
 * toolbar fits`); and the divider, dragged with a real mouse at 1440, moves the list's edge by the
 * drag and keeps it across a reload (`room 1440 divider`); and the overview hint's words are whole
 * at 1440 and 1000 on the list (`room <w> hint whole`). On the 3016-call session, each view opens
 * where it was left while live tail is paused (`room views keep place`).
 *
 * Each reading leaves the page as it found it: drawer closed, colour scheme the system's.
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const { sleep } = require('./measure-cdp');
const { PAYMENTS_SESSION_ID } = require('./findings-probes');

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
  // Focus never entering the drawer passes over a drawer with nothing in it, so the closed drawer
  // must still hold the row's detail, the controls a Tab could wrongly reach.
  const held = await page.ev(`document.querySelectorAll('#detail button, #detail a[href], #detail input, #detail select, #detail [tabindex]').length`);
  const t = await tabWalk(page);
  const ok = held > 0 && t.round && t.inside.length === 0 && t.maxX === 0;
  rows.push({ key: 'room 1000 tab never enters closed', ok,
    value: !(held > 0) ? 'NO - found no control in the closed drawer, so this checked nothing'
      : ok ? 'never' : t.inside.length ? 'enters' : t.maxX ? 'scrolls' : 'no round',
    detail: `${held} controls in the closed drawer; ${t.stops} stops in ${t.presses} presses${t.inside.length ? `; inside: ${t.inside.slice(0, 4).join(' ')}` : ''}; scrollX at most ${t.maxX}` });
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

// Every row at this width: its path's last segment, its byte count and its repeat mark whole and
// inside the row, the clock gone, the short repeat mark the one shown. The segment is judged inside
// its path's box, which clips it: inside the row alone, `transi` passed for `transfers`.
const ROWS = `(() => {
  const rowBox = (row) => row.getBoundingClientRect();
  const whole = (e, box) => { const r = e.getBoundingClientRect();
    return e.getClientRects().length > 0 && e.scrollWidth <= e.clientWidth + 0.5 && r.left >= box.left - 0.5 && r.right <= box.right + 0.5; };
  const rows = [...document.querySelectorAll('#list .row')];
  const bad = [];
  let repeats = 0;
  for (const row of rows) {
    const box = row.getBoundingClientRect();
    const tail = row.querySelector('.path-tail');
    const size = row.querySelector('.size');
    const badge = row.querySelector('.dup-badge');
    const said = (tail ? tail.textContent : '?') + ' ' + (size ? size.textContent : '?');
    if (!tail || !whole(tail, box) || !whole(tail, tail.parentElement.getBoundingClientRect())) bad.push('tail of ' + said);
    if (!size || !whole(size, box)) bad.push('size of ' + said);
    if (badge) {
      repeats++;
      const short = badge.querySelector('.dup-short');
      if (!whole(badge, box) || !short || getComputedStyle(short).display === 'none') bad.push('repeat of ' + said);
    }
  }
  const clocks = [...document.querySelectorAll('#list .row .clock')].filter((c) => c.getClientRects().length).length;
  // How many held rows are repeats, from the session's duplicates rather than the marks scanned.
  const due = state.transactions.filter((t) => state.duplicates.has(t.id)).length;
  // Findings badges: whole, short, the row's text colour (muted for info) and the row no taller for one.
  let flags = 0;
  const heights = new Set(rows.map((r) => Math.round(r.getBoundingClientRect().height)));
  for (const row of rows) {
    const flag = row.querySelector('.finding-badge');
    if (!flag) continue;
    flags++;
    const short = flag.querySelector('.fb-short');
    const ink = getComputedStyle(flag.classList.contains('sev-info') ? row.querySelector('.ms') : row).color;
    if (!whole(flag, rowBox(row)) || !short || getComputedStyle(short).display === 'none' || getComputedStyle(flag).color !== ink) {
      bad.push('badge of ' + row.querySelector('.path-tail').textContent + ' (' + getComputedStyle(flag).color + ', ' + ink + ')');
    }
  }
  const flagsDue = typeof findingsState === 'undefined' || !findingsState.byTxn ? 0 : state.transactions.filter((t) => findingsState.byTxn.has(t.id)).length;
  return { rows: rows.length, repeats, due, bad, clocks, flags, flagsDue, heights: [...heights] };
})()`;

/** Below 520px a row gives up its clock and its host before its last segment, bytes or repeat mark. */
async function narrowRows(page, url, rows, key = 'room 400 rows') {
  await load(page, url, 400);
  if (key !== 'room 400 rows') await page.waitFor(`document.querySelectorAll('#list .finding-badge').length > 0`, 10000);
  const r = await page.ev(ROWS);
  // The repeat marks and the badges are scans of their own: none drawn where the list holds one checked nothing.
  const noMarks = Boolean(r) && !r.evalError && ((r.repeats === 0 && r.due > 0) || (r.flags === 0 && r.flagsDue > 0));
  const ok = Boolean(r) && !r.evalError && r.rows > 0 && r.bad.length === 0 && !noMarks && r.heights.length === 1;
  rows.push({ key, ok,
    value: noMarks ? `NO - found no ${r.flags === 0 && r.flagsDue > 0 ? 'findings badge' : 'repeat mark'}, so this checked nothing`
      : ok ? 'whole' : r && r.bad ? (r.bad.length ? 'cut' : 'uneven') : 'not measured',
    detail: !r || r.evalError ? String(r && r.evalError)
      : `${r.rows} rows, ${r.repeats} repeat marks${r.due ? '' : ' (n/a: no row held is a repeat)'}, ${r.flags} findings badges, ${r.clocks} clocks drawn; `
        + `row heights ${r.heights.join('/')}px${r.bad.length ? `; cut: ${r.bad.slice(0, 4).join(', ')}` : ''}` });
}

// Every band above the list, by height, and where the list pane starts. Base and head name their
// bands differently, so each is read by the ids both carry and any it lacks reads 0.
const BANDS = `(() => {
  const h = (id) => { const e = document.getElementById(id); return e && e.getClientRects().length ? Math.round(e.getBoundingClientRect().height) : 0; };
  const bands = ['topbar', 'server-banner', 'tabs', 'toolbar', 'filter-error', 'overview', 'now-strip', 'axis']
    .map((id) => id + ' ' + h(id)).filter((b) => !b.endsWith(' 0'));
  return { top: Math.round(document.getElementById('list-pane').getBoundingClientRect().top), bands };
})()`;

/**
 * Room above the first row at 1440: on the list the pane starts no lower than 160px with no banner
 * and no filter error; on the timeline, where the now strip joins the line, it is reported. The
 * value carries the pixel top, so --base prints both trees' numbers.
 */
async function bands(page, url, rows) {
  await load(page, url, 1440);
  for (const [name, view] of [['list', 'network'], ['timeline', 'all']]) {
    await page.ev(`selectView(${JSON.stringify(view)})`);
    await sleep(600);
    const b = await page.ev(BANDS);
    const ok = name === 'list' ? Boolean(b) && !b.evalError && b.top <= 160 : null;
    rows.push({ key: `room 1440 bands ${name}`, value: !b || b.evalError ? 'not measured' : `top ${b.top}px`, ok,
      detail: !b || b.evalError ? String(b && b.evalError) : `${b.bands.join(', ')}${name === 'list' ? '; judged at most 160' : ''}` });
  }
}

// The toolbar's drawn controls: how many lines they take and whether the row or the document
// overflows sideways. By centre, not top: the row centres its items, so an 11px caption beside the
// 27px filter box shares its line with a top 6px lower.
const TOOLBAR = `(() => {
  const row = document.querySelector('#toolbar .toolbar-row');
  if (!row) return { missing: true };
  const kids = [...row.children].filter((e) => e.getClientRects().length && e.getBoundingClientRect().width > 0 && !e.classList.contains('spacer'));
  const tops = [];
  for (const e of kids) { const r = e.getBoundingClientRect(); const c = (r.top + r.bottom) / 2; if (!tops.some((x) => Math.abs(x - c) <= 4)) tops.push(c); }
  return { lines: tops.length, controls: kids.length, scroll: row.scrollWidth, client: row.clientWidth,
    doc: document.documentElement.scrollWidth, width: innerWidth, height: Math.round(document.getElementById('toolbar').getBoundingClientRect().height) };
})()`;

/** One row from 1000px up; below, it wraps and nothing overflows. */
async function toolbar(page, url, rows) {
  await load(page, url, 1440);
  for (const width of [1440, 1000, 600, 400]) {
    await page.setWidth(width);
    await sleep(400);
    const t = await page.ev(TOOLBAR);
    if (!t || t.evalError || t.missing) {
      rows.push({ key: `room ${width} toolbar`, value: 'not measured', ok: false, detail: String(t && (t.evalError || 'no .toolbar-row')) });
      continue;
    }
    // An empty row fits, so a toolbar that drew no control would pass over nothing.
    const none = t.controls === 0 ? 'NO - found no toolbar control, so this checked nothing' : null;
    const fits = t.controls > 0 && t.scroll <= t.client + 0.5 && t.doc <= t.width;
    const detail = `${t.controls} controls on ${t.lines} line(s), ${t.height}px tall; row ${t.scroll} of ${t.client}, document ${t.doc} of ${t.width}`;
    if (width >= 1000) {
      const ok = t.lines === 1 && fits;
      rows.push({ key: `room ${width} toolbar one row`, value: none || (ok ? 'one row' : t.lines > 1 ? `${t.lines} rows` : 'overflows'), ok, detail });
    } else {
      rows.push({ key: `room ${width} toolbar fits`, value: none || (fits ? 'fits' : 'overflows'), ok: fits, detail });
    }
  }
}

// The list pane's width, the divider's centre and what it says, and whether it is drawn at all.
const DIVIDER = `(() => { const d = document.getElementById('pane-divider'); const r = d.getBoundingClientRect();
  return { list: document.getElementById('list-pane').getBoundingClientRect().width, x: r.left + r.width / 2, y: r.top + r.height / 2,
    drawn: getComputedStyle(d).display !== 'none' && r.width > 0, now: d.getAttribute('aria-valuenow'), pref: localStorage.getItem('inspector.listShare') }; })()`;

/**
 * The divider at 1440, dragged 200px with a real mouse: the list grows by the drag, a reload keeps
 * the share, and a double-click puts the default back and forgets it. At 1000 there are no two
 * columns, so it is not drawn. Leaves no share stored.
 */
async function divider(page, url, rows) {
  await load(page, url, 1440);
  await page.ev(`localStorage.removeItem('inspector.listShare')`);
  await load(page, url, 1440);
  const before = await page.ev(DIVIDER);
  await page.drag(before.x, before.y, before.x + 200);
  await sleep(300);
  const dragged = await page.ev(DIVIDER);
  await load(page, url, 1440);
  const reloaded = await page.ev(DIVIDER);
  await page.ev(`document.getElementById('pane-divider').dispatchEvent(new MouseEvent('dblclick', { bubbles: true }))`);
  await sleep(300);
  const reset = await page.ev(DIVIDER);
  await page.setWidth(1000);
  await sleep(400);
  const narrow = await page.ev(DIVIDER);
  await page.ev(`localStorage.removeItem('inspector.listShare')`);
  const grew = dragged.list - before.list;
  const ok = Math.abs(grew - 200) <= 4 && Math.abs(reloaded.list - dragged.list) <= 1 && dragged.pref !== null
    && Math.abs(reset.list - before.list) <= 1 && reset.pref === null && !narrow.drawn;
  rows.push({ key: 'room 1440 divider', value: ok ? 'resizes' : 'wrong', ok,
    detail: `list ${Math.round(before.list)} -> ${Math.round(dragged.list)}px (+${Math.round(grew)}, share ${dragged.now}, stored ${dragged.pref}); `
      + `reloaded ${Math.round(reloaded.list)}; double-click ${Math.round(reset.list)} (stored ${reset.pref}); at 1000 drawn ${narrow.drawn}` });
}

// The overview hint's words: whether the span holding them is cut, and its text.
const HINT = `(() => { const h = document.querySelector('#axis-hint > span');
  return h && h.getClientRects().length ? { cut: h.scrollWidth > h.clientWidth + 0.5, text: h.textContent,
    hint: Math.round(h.getBoundingClientRect().width), axis: Math.round(document.getElementById('axis').getBoundingClientRect().width) } : null; })()`;

/**
 * The hint says what the overview counts, so its words are whole at 1440 and 1000 on the list: they
 * were cut to `16 calls in this session · 2.1s — drag to …` while the axis took the line, which can
 * stretch its bars to any width. Below that the line is the window's, and the words give way.
 */
async function hint(page, url, rows) {
  await load(page, url, 1440);
  await page.ev(`selectView('network')`);
  for (const width of [1440, 1000]) {
    await page.setWidth(width);
    await sleep(400);
    const h = await page.ev(HINT);
    const ok = Boolean(h) && !h.evalError && !h.cut;
    rows.push({ key: `room ${width} hint whole`, value: !h || h.evalError ? 'not measured' : h.cut ? 'cut' : 'whole', ok,
      detail: !h || h.evalError ? String(h && h.evalError) : `"${h.text}" in ${h.hint}px beside an axis of ${h.axis}px` });
  }
}

const PANE = `(() => { const p = document.getElementById('list-pane'); return { top: Math.round(p.scrollTop), max: p.scrollHeight - p.clientHeight }; })()`;

/**
 * Each view's own place on the 3016-call session at 1440, oldest first. Paused: the list scrolled
 * to 5000, the timeline opens at its start the first time, and the list comes back at 5000. Live:
 * nothing is restored, and the timeline still opens at its end, which it follows.
 */
async function keepPlace(page, url, rows) {
  await load(page, url, 1440);
  const view = async (v) => { await page.ev(`selectView(${JSON.stringify(v)})`); await sleep(700); return page.ev(PANE); };
  await page.ev(`setSortOrder(false); setLiveTail(false)`);
  await view('network');
  await page.ev(`document.getElementById('list-pane').scrollTop = 5000`);
  await sleep(300);
  const list = await page.ev(PANE);
  const timeline = await view('all');
  const back = await view('network');
  await page.ev(`setLiveTail(true)`);
  await sleep(3000);
  const live = await view('all');
  const ok = list.top === 5000 && timeline.top === 0 && back.top === 5000 && live.max > 50 && live.top >= live.max - 1;
  rows.push({ key: 'room views keep place', value: ok ? 'keeps' : 'moves', ok,
    detail: `paused: list ${list.top} -> timeline ${timeline.top} of ${timeline.max} -> list ${back.top}; live: the timeline opens at ${live.top} of ${live.max}` });
}

async function room(page, urls) {
  const url = urls.probe;
  const rows = [];
  await drawer(page, url, rows);
  await divider(page, url, rows);
  await narrowRows(page, url, rows);
  await narrowRows(page, `${url}#/traffic?session=${encodeURIComponent(PAYMENTS_SESSION_ID)}`, rows, 'room 400 payments rows');
  await bands(page, url, rows);
  await toolbar(page, url, rows);
  await hint(page, url, rows);
  await keepPlace(page, urls.big, rows);
  const seen = [...new Set(page.exceptions)];
  rows.push({ key: 'room page exceptions', value: seen.length ? 'thrown' : 'none', ok: seen.length === 0, detail: seen.join(' | ') });
  return rows;
}

module.exports = { room };
