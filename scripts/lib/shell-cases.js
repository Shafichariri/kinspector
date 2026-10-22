'use strict';
/*
 * The shell in Chrome, at 1440, 1000, 600 and 400 px on the fixture's 1203-call session: what jsdom
 * cannot see, because every one of these is layout. The tab bar is read on the probe session
 * instead, whose nine views overflow a narrow bar where the long session's four would fit anyway.
 *
 * Judged per width: the top bar never wider than the window (`topbar fits`); the picker, live and
 * connection controls each inside the window, hit by `elementFromPoint` at their centre
 * and focusable (`controls`); the picker's start time whole and inside its face (`picker time`);
 * every view a visible tab or an item of a visible `views ▾` with the tab bar not overflowing
 * (`tabs reachable`); each rail link inside the window (`rail`); and the Stats, Findings, Sessions,
 * Processes and Settings pages never scrolling the window sideways nor clipping themselves (`page
 * <name> fits`). The Findings page again on the payments session, whose messages, routes and
 * evidence are the longest text it draws (`findings <w> payments fits`). Once: rail clicks, then Back (`shell back`). And, in measure-web-ui.js's `first` group,
 * how long the Stats page takes to draw its first row on the 160,000-call session, reported and not
 * judged (`stats huge first draw`): the daemon folds the whole session on a cold read.
 *
 * And at 400px on the `routes` session, served and in an export opened from disk: every line of a
 * route cell ends at a `.`, `:` or `/` of its server, never inside a bracketed IPv6 address, and at
 * a `/` of its path (`stats 400 route breaks <where>`), read per character from where Chrome put
 * it, and every route whole on screen while the document stays as wide as the window (`stats 400
 * routes whole <where>`). `overflow-wrap: anywhere` read `api.qa.invali` / `d` (KIN-93). At 1440,
 * served, every route is still one line (`stats 1440 routes one line served`).
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const { sleep } = require('./measure-cdp');
const { LONG_SESSION_ID } = require('./long-session-probes');
const { PROBE_SESSION_ID } = require('./measure-fixtures');
const { PAYMENTS_SESSION_ID } = require('./findings-probes');

const WIDTHS = [1440, 1000, 600, 400];
const CONTROLS = ['#session-picker', '#live-btn', '#conn'];
// Stats draws fifteen columns; its table scrolls in its own box, so the page must still fit.
const PAGES = ['stats', 'findings', 'sessions', 'processes', 'settings'];

const READ = `(() => {
  const inside = (r) => r.left >= -0.5 && r.right <= innerWidth + 0.5 && r.top >= -0.5 && r.bottom <= innerHeight + 0.5;
  const bar = document.getElementById('topbar');
  const controls = ${JSON.stringify(CONTROLS)}.map((s) => {
    const e = document.querySelector(s);
    if (!e || !e.getClientRects().length) return s + ':hidden';
    const r = e.getBoundingClientRect();
    const hit = inside(r) && (() => {
      const h = document.elementFromPoint((r.left + r.right) / 2, (r.top + r.bottom) / 2);
      return Boolean(h) && (h === e || e.contains(h));
    })();
    e.focus();
    const keyboard = document.activeElement === e;
    e.blur();
    return s + ':' + (hit ? 'pointer' : 'no-pointer') + '+' + (keyboard ? 'keyboard' : 'no-keyboard');
  });
  const time = document.querySelector('.picker-time');
  const face = document.querySelector('.picker-face');
  const tr = time ? time.getBoundingClientRect() : null;
  const fr = face ? face.getBoundingClientRect() : null;
  const timeWhole = Boolean(time && time.textContent.trim()) && time.scrollWidth <= time.clientWidth + 0.5
    && tr.left >= fr.left - 0.5 && tr.right <= fr.right + 0.5 && inside(tr);
  const rail = [...document.querySelectorAll('#rail .rail-item')];
  const railOut = rail.filter((a) => !a.getClientRects().length || !inside(a.getBoundingClientRect())).map((a) => a.dataset.page);
  return {
    bar: { scroll: bar.scrollWidth, width: innerWidth, height: Math.round(bar.getBoundingClientRect().height) },
    controls, timeWhole, time: time ? time.textContent : null, rail: rail.length, railOut,
    railWidth: Math.round(document.getElementById('rail').getBoundingClientRect().width),
  };
})()`;

const TABS = `(() => {
  const inside = (r) => r.left >= -0.5 && r.right <= innerWidth + 0.5 && r.top >= -0.5 && r.bottom <= innerHeight + 0.5;
  const tabs = document.getElementById('tabs');
  const more = document.getElementById('tabs-more');
  const moreShown = Boolean(more && more.getClientRects().length && inside(more.getBoundingClientRect()));
  const views = availableViews().map((v) => v.id);
  const unreachable = views.filter((id) => {
    const tab = [...tabs.querySelectorAll('.tab')].find((t) => t.dataset.view === id);
    if (tab && tab.getClientRects().length && inside(tab.getBoundingClientRect())) return false;
    return !(moreShown && [...more.querySelectorAll('.menu-item')].some((i) => i.dataset.view === id));
  });
  return { fits: tabs.scrollWidth <= tabs.clientWidth + 0.5, scroll: tabs.scrollWidth, client: tabs.clientWidth, views: views.length, unreachable, moreShown };
})()`;

const PAGE_FITS = (name) => `(() => {
  const page = document.getElementById('page-${name}');
  return { doc: document.documentElement.scrollWidth, width: innerWidth, page: page.scrollWidth, client: page.clientWidth };
})()`;

async function openLong(page, url) {
  await page.setWidth(1440);
  await page.navigate(url);
  if (await page.waitFor(`document.querySelectorAll('#list .row').length > 0`, 60000) === null) {
    throw new Error(`${url} drew no rows within 60 s`);
  }
  const has = await page.ev(`[...document.getElementById('session-picker').options].some((o) => o.value === ${JSON.stringify(LONG_SESSION_ID)})`);
  if (!has) return false;
  await page.ev(`(() => { const p = document.getElementById('session-picker'); p.value = ${JSON.stringify(LONG_SESSION_ID)};
    p.dispatchEvent(new Event('change', { bubbles: true })); })()`);
  await page.waitFor(`/1203/.test(document.getElementById('counts').textContent)`, 20000);
  await sleep(1000);
  return true;
}

async function shell(page, url, session) {
  if (!await openLong(page, url)) return [{ key: `shell ${session}`, value: `n/a (no session ${LONG_SESSION_ID})`, ok: null, detail: '' }];
  const rows = [];
  for (const width of WIDTHS) {
    await page.setWidth(width);
    await sleep(500);
    const r = await page.ev(READ);
    if (r && r.evalError) {
      rows.push({ key: `shell ${width} read`, value: 'threw', ok: false, detail: r.evalError });
      continue;
    }
    const fits = r.bar.scroll <= r.bar.width;
    rows.push({ key: `shell ${width} topbar fits`, value: fits ? 'fits' : 'overflows', ok: fits,
      detail: `scrollWidth ${r.bar.scroll} of ${r.bar.width}, ${r.bar.height}px tall` });
    const reached = r.controls.every((c) => c.endsWith(':pointer+keyboard'));
    rows.push({ key: `shell ${width} controls`, value: reached ? 'pointer+keyboard' : r.controls.filter((c) => !c.endsWith(':pointer+keyboard')).join(' '),
      ok: reached, detail: '' });
    rows.push({ key: `shell ${width} picker time`, value: r.timeWhole ? 'whole' : 'cut', ok: r.timeWhole, detail: `"${r.time}"` });
    // Every rail link inside the window passes over no link at all, so the scan must find one: with
    // `class="rail-item"` renamed it read `inside` at every width (KIN-87).
    const railOk = r.rail > 0 && r.railOut.length === 0;
    rows.push({ key: `shell ${width} rail`, ok: railOk,
      value: !r.rail ? 'NO - found no rail item, so this checked nothing' : r.railOut.length ? 'outside' : 'inside',
      detail: `${r.railWidth}px, ${r.rail} rail items${r.railOut.length ? `, outside: ${r.railOut.join(' ')}` : ''}` });
    for (const name of PAGES) {
      await page.ev(`location.hash = '#/${name}'`);
      const shown = await page.waitFor(`document.getElementById('page-${name}') && !document.getElementById('page-${name}').hidden`, 3000);
      await sleep(500);
      const p = shown === null ? null : await page.ev(PAGE_FITS(name));
      const ok = Boolean(p) && p.doc <= p.width && p.page <= p.client + 1;
      rows.push({ key: `shell ${width} page ${name} fits`, value: !p ? 'no page' : ok ? 'fits' : 'overflows', ok,
        detail: p ? `document ${p.doc} of ${p.width}, page ${p.page} of ${p.client}` : '' });
    }
    await page.ev(`location.hash = '#/traffic'`);
    await sleep(400);
  }
  await page.ev(`(() => { const p = document.getElementById('session-picker'); p.value = ${JSON.stringify(PROBE_SESSION_ID)};
    p.dispatchEvent(new Event('change', { bubbles: true })); })()`);
  await page.waitFor(`state.sessionId === ${JSON.stringify(PROBE_SESSION_ID)} && state.signalSessionId === state.sessionId`, 20000);
  for (const width of WIDTHS) {
    await page.setWidth(width);
    await sleep(500);
    const t = await page.ev(TABS);
    const ok = Boolean(t) && !t.evalError && t.fits && t.unreachable.length === 0;
    rows.push({ key: `shell ${width} tabs reachable`, value: ok ? 'every view' : 'not every view', ok,
      detail: !t || t.evalError ? String(t && t.evalError) : `probe session, ${t.views} views; scrollWidth ${t.scroll} of ${t.client}`
        + `${t.moreShown ? ', views ▾ shown' : ''}${t.unreachable.length ? `, unreachable: ${t.unreachable.join(' ')}` : ''}` });
  }
  await page.setWidth(1000);
  await page.ev(`(() => { document.querySelector('#rail .rail-item[data-page="sessions"]').click();
    document.querySelector('#rail .rail-item[data-page="processes"]').click(); })()`);
  await sleep(300);
  await page.ev('history.back()');
  const back = await page.waitFor(`!document.getElementById('page-sessions').hidden && document.getElementById('page-processes').hidden`, 3000);
  rows.push({ key: 'shell back', value: back !== null ? 'sessions' : 'not sessions', ok: back !== null, detail: '' });
  return rows;
}

/**
 * The Findings page on the payments session at each width: the document no wider than the window,
 * the page not clipping itself, and every finding inside the page's box. A page that drew no finding
 * checked nothing, and fails.
 */
async function findingsFits(page, url) {
  const rows = [];
  for (const width of WIDTHS) {
    await page.setWidth(width);
    await page.navigate('about:blank');
    await page.navigate(`${url}#/findings?session=${encodeURIComponent(PAYMENTS_SESSION_ID)}`);
    const drawn = await page.waitFor(`document.querySelectorAll('#findings-body article.finding').length > 0`, 20000);
    await sleep(300);
    const r = drawn === null ? null : await page.ev(`(() => {
      const fits = ${PAGE_FITS('findings')};
      const box = document.getElementById('page-findings').getBoundingClientRect();
      const articles = [...document.querySelectorAll('#findings-body article.finding')];
      const out = articles.filter((a) => a.getBoundingClientRect().right > box.right + 0.5 || a.scrollWidth > a.clientWidth + 0.5);
      return { ...fits, articles: articles.length, out: out.length };
    })()`);
    const ok = Boolean(r) && !r.evalError && r.doc <= r.width && r.page <= r.client + 1 && r.out === 0;
    rows.push({ key: `findings ${width} payments fits`, ok,
      value: !r ? 'NO - found no finding, so this checked nothing' : r.evalError ? 'threw' : ok ? 'fits' : 'overflows',
      detail: !r || r.evalError ? String(r && r.evalError || '') : `${r.articles} findings, ${r.out} wider than the page; document ${r.doc} of ${r.width}, page ${r.page} of ${r.client}` });
  }
  return rows;
}

/** Opens `#/stats` on [url]'s session and times its first row; `ok: null`, a reading, not a verdict. */
async function statsFirstDraw(page, url, session) {
  await page.setWidth(1440);
  await page.navigate(`${url}#/stats`);
  const drawn = await page.waitFor(`document.querySelectorAll('#stats-rows .stats-row').length > 0`, 60000);
  return [{ key: `stats ${session} first draw`, value: drawn === null ? 'none in 60 s' : 'drawn', ok: null,
    detail: drawn === null ? '' : `${drawn} ms` }];
}

// Per route cell, the server's and the path's characters in order with the top of each glyph's box:
// a later glyph lower than the one before it started a line. `<wbr>` splits a span into several
// text nodes, so the characters are walked across all of them.
const ROUTE_BREAKS = `(() => {
  const cells = [...document.querySelectorAll('#stats-rows .stats-route')];
  const bad = [];
  const cut = [];
  let spans = 0;
  let hosts = 0;
  let breaks = 0;
  for (const cell of cells) {
    const box = cell.getBoundingClientRect();
    for (const span of cell.querySelectorAll('.stats-host, .stats-path')) {
      const host = span.classList.contains('stats-host');
      spans++;
      if (host) hosts++;
      const chars = [];
      const walker = document.createTreeWalker(span, NodeFilter.SHOW_TEXT);
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        for (let i = 0; i < node.length; i++) {
          const range = document.createRange();
          range.setStart(node, i);
          range.setEnd(node, i + 1);
          const rect = range.getClientRects()[0];
          chars.push({ c: node.data[i], top: rect ? rect.top : null });
        }
      }
      const text = chars.map((x) => x.c).join('');
      for (let i = 1; i < chars.length; i++) {
        if (chars[i].top === null || chars[i - 1].top === null || chars[i].top <= chars[i - 1].top + 2) continue;
        breaks++;
        const before = text.slice(0, i);
        const inBrackets = before.lastIndexOf('[') > before.lastIndexOf(']');
        const at = (host ? '.:/' : '/').includes(text[i - 1]) && !inBrackets;
        if (!at) bad.push(before + '|' + text.slice(i));
      }
      const r = span.getBoundingClientRect();
      if (span.scrollWidth > span.clientWidth + 0.5 || r.left < box.left - 0.5 || r.right > box.right + 0.5) cut.push(text);
    }
  }
  return { cells: cells.length, spans, hosts, breaks, bad, cut, doc: document.documentElement.scrollWidth, width: innerWidth };
})()`;

function routeRows(where, r) {
  if (!r || r.evalError) return [{ key: `stats 400 route breaks ${where}`, value: 'threw', ok: false, detail: String(r && r.evalError) }];
  // The routes session has more than one server, so a cell with no server drawn is a scan of nothing.
  const found = r.cells > 0 && r.hosts > 0;
  const nothing = `NO - found no route cell${r.cells ? ' with a server' : ''}, so this checked nothing`;
  const breaksOk = found && r.bad.length === 0;
  const wholeOk = found && r.cut.length === 0 && r.doc <= r.width;
  return [
    { key: `stats 400 route breaks ${where}`, value: !found ? nothing : breaksOk ? 'at . : /' : 'mid-word', ok: breaksOk,
      detail: `${r.cells} route cells, ${r.hosts} servers, ${r.breaks} line breaks${r.bad.length ? `; ${r.bad.join(', ')}` : ''}` },
    { key: `stats 400 routes whole ${where}`, value: !found ? nothing : wholeOk ? 'whole' : 'cut', ok: wholeOk,
      detail: `document ${r.doc} of ${r.width}${r.cut.length ? `; cut: ${r.cut.join(', ')}` : ''}` },
  ];
}

/** Reads the route cells at 400px on [url]'s latest session, served and from its export on disk. */
async function statsRouteBreaks(page, url) {
  const drawn = `document.querySelectorAll('#stats-rows .stats-row').length > 0`;
  const rows = [];
  await page.setWidth(400);
  await page.navigate(`${url}#/stats`);
  if (await page.waitFor(drawn, 20000) === null) throw new Error(`${url}#/stats drew no route within 20 s`);
  await sleep(500);
  rows.push(...routeRows('served', await page.ev(ROUTE_BREAKS)));
  // Wide, a route is one line and its path ends in an ellipsis: Chrome honours a `<wbr>` under
  // `nowrap`, so the pieces wrapped there too until the stylesheet took them out above 700px.
  await page.setWidth(1440);
  await sleep(500);
  const wide = await page.ev(ROUTE_BREAKS);
  const wideFound = Boolean(wide) && !wide.evalError && wide.cells > 0 && wide.hosts > 0;
  rows.push({ key: 'stats 1440 routes one line served', ok: wideFound && wide.breaks === 0,
    value: !wideFound ? 'NO - found no route cell with a server, so this checked nothing' : wide.breaks === 0 ? 'one line' : 'wrapped',
    detail: wideFound ? `${wide.cells} route cells, ${wide.breaks} line breaks` : String(wide && wide.evalError) });
  await page.setWidth(400);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'stats-export-'));
  try {
    const file = path.join(dir, 'export.html');
    fs.writeFileSync(file, Buffer.from(await (await fetch(`${url}api/sessions/latest/export.html`)).arrayBuffer()));
    await page.navigate(`file://${file}#/stats`);
    if (await page.waitFor(drawn, 20000) === null) throw new Error('the export drew no route within 20 s');
    await sleep(500);
    rows.push(...routeRows('export', await page.ev(ROUTE_BREAKS)));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
  return rows;
}

module.exports = { shell, findingsFits, statsFirstDraw, statsRouteBreaks };
