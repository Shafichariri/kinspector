'use strict';
/*
 * The web UI polish rules in Chrome (group `polish` in scripts/measure-web-ui.js), on the probe
 * session: sizes and hits, which jsdom cannot see.
 *
 * Judged: at 1440, 1000 and 400, served and in an HTML export opened from disk, dark and light,
 * every icon control drawn at that width — `⤢` and `⇥` in the tools row as a column, `⤢` and the
 * drawer's `✕` below 1200px, `⋮` at the toolbar's end, and served, the Sessions page's row `✕` — is
 * a box at least 24x24, its SVG at least 14x14 (the row `✕`, which is text, at least 14px), and is
 * what `elementFromPoint` finds at its centre (`polish <w> icon targets <where> <scheme>`).
 *
 * Served, at 1440 and 400, on the Stats, Sessions, Processes and Settings pages: every drawn `.btn` is
 * at least 24px tall (`polish <w> page targets`); the density select is at least its longest
 * option's text plus 20px for the chevron, measured in the select's own font (`polish <w> density
 * whole`); and every Sessions row starts with its start time, whole and inside its cell (`polish <w>
 * session time whole`). At 1440, every drawn `.settings-note` is at most 72 widths of `0` in its own
 * font (`polish 1440 prose width`); at 400 the window is narrower than that anyway.
 *
 * Served, at 1440 and 400, `?` pressed on Settings as the browser receives a key: the legend is drawn
 * whole inside the window, lists page keys, is what a pointer at its centre finds, and Escape shuts
 * it (`polish <w> legend inside`). jsdom proves it opens there; only a browser can say where.
 *
 * Each reading leaves the page as it found it: colour scheme the system's.
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const { sleep } = require('./measure-cdp');

const LOADED = `typeof state !== 'undefined' && state.sessionId && document.querySelectorAll('#list .row').length > 0`;
const OPEN_ROW = `(() => { const rows = [...document.querySelectorAll('#list .row')];
  const row = rows.find((r) => r.querySelector('.status').textContent === '500') || rows[0]; if (row) row.click(); })()`;

async function load(page, url, width) {
  await page.setWidth(width);
  await page.navigate('about:blank');
  await page.navigate(url);
  if (await page.waitFor(LOADED, 60000) === null) throw new Error(`${url} drew no rows within 60 s`);
  await sleep(500);
}

// Each control [selectors] finds that is drawn: its box, what it draws (an svg's box, or for text its
// font size and line box) and whether a pointer at its centre lands on it. `missing` names a selector
// that matched nothing drawn.
const ICONS = (selectors) => `(() => {
  const drawn = (e) => e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden';
  const out = { controls: [], missing: [] };
  for (const sel of ${JSON.stringify(selectors)}) {
    const found = [...document.querySelectorAll(sel)].filter(drawn);
    if (!found.length) out.missing.push(sel);
    for (const e of found) {
      const r = e.getBoundingClientRect();
      const svg = e.querySelector('svg');
      let inner;
      if (svg) { const s = svg.getBoundingClientRect(); inner = { kind: 'svg', w: s.width, h: s.height }; }
      else { const range = document.createRange(); range.selectNodeContents(e); const t = range.getBoundingClientRect();
        inner = { kind: 'text', w: t.width, h: Math.min(t.height, parseFloat(getComputedStyle(e).fontSize)) }; }
      const h = document.elementFromPoint((r.left + r.right) / 2, (r.top + r.bottom) / 2);
      out.controls.push({ name: e.id ? '#' + e.id : sel, w: r.width, h: r.height, inner, hit: Boolean(h) && (h === e || e.contains(h)) });
    }
  }
  return out;
})()`;

const small = (c) => c.w < 24 - 0.01 || c.h < 24 - 0.01
  || (c.inner.kind === 'svg' ? c.inner.w < 14 - 0.01 || c.inner.h < 14 - 0.01 : c.inner.h < 14 - 0.01);
const round = (n) => Math.round(n * 10) / 10;

function iconRow(key, reads) {
  const controls = reads.flatMap((r) => (r && !r.evalError ? r.controls : []));
  const errors = reads.filter((r) => !r || r.evalError).map((r) => String(r && r.evalError));
  const missing = reads.flatMap((r) => (r && !r.evalError ? r.missing : []));
  const smallOnes = controls.filter(small);
  const missed = controls.filter((c) => !c.hit);
  const ok = !errors.length && controls.length > 0 && !missing.length && !smallOnes.length && !missed.length;
  const value = errors.length ? 'not measured'
    : !controls.length ? 'NO - found no icon control, so this checked nothing'
      : ok ? 'at least 24x24, hit' : missing.length ? 'missing' : smallOnes.length ? 'small' : 'not hit';
  const detail = errors.length ? errors.join(' | ') : `${controls.length} controls: ${controls.map((c) =>
    `${c.name} ${round(c.w)}x${round(c.h)} ${c.inner.kind} ${round(c.inner.w)}x${round(c.inner.h)}${c.hit ? '' : ' not hit'}`).join(', ')}`
    + (missing.length ? `; not drawn: ${missing.join(' ')}` : '');
  return { key, value, ok, detail };
}

/** Every icon control, at each width, in both colour schemes, served and exported. */
async function iconTargets(page, url, rows) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'polish-export-'));
  const file = path.join(dir, 'export.html');
  try {
    fs.writeFileSync(file, Buffer.from(await (await fetch(`${url}api/sessions/latest/export.html`)).arrayBuffer()));
    for (const [where, base] of [['served', url], ['export', `file://${file}`]]) {
      for (const width of [1440, 1000, 400]) {
        const reads = { dark: [], light: [] };
        const each = async (selectors) => {
          for (const scheme of ['dark', 'light']) {
            await page.colorScheme(scheme);
            await sleep(150);
            reads[scheme].push(await page.ev(ICONS(selectors)));
          }
        };
        await load(page, `${base}#/traffic/network`, width);
        // `⋮` before a row is opened: below 1200px the open drawer's scrim lies over the toolbar.
        await each(['#more-button']);
        await page.ev(OPEN_ROW);
        await page.waitFor(`!document.getElementById('detail').hidden`, 5000);
        await sleep(500); // the drawer's slide
        await each(width >= 1200 ? ['#detail-full', '#detail-hide'] : ['#detail-full', '#drawer-close']);
        if (where === 'served') {
          await page.ev(`navigate({ page: 'sessions' })`);
          await page.waitFor(`document.querySelector('#session-rows .row-delete')`, 5000);
          await sleep(300);
          await each(['#session-rows .row-delete']);
        }
        for (const scheme of ['dark', 'light']) rows.push(iconRow(`polish ${width} icon targets ${where} ${scheme}`, reads[scheme]));
      }
    }
  } finally {
    await page.colorScheme(null);
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

const PAGES = ['stats', 'sessions', 'processes', 'settings'];

// The shown page's drawn buttons and notes, measured; the density select and the Sessions rows' times
// when the page has them. Widths of text are a canvas's `measureText` in the element's own font.
const PAGE_READ = `(() => {
  const page = [...document.querySelectorAll('#pages > .page')].find((p) => !p.hidden);
  const drawn = (e) => e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden';
  const ctx = document.createElement('canvas').getContext('2d');
  const textWidth = (e, text) => { const c = getComputedStyle(e); ctx.font = c.fontWeight + ' ' + c.fontSize + ' ' + c.fontFamily; return ctx.measureText(text).width; };
  const buttons = [...page.querySelectorAll('.btn')].filter(drawn).map((b) => ({ name: b.id || b.textContent.trim().slice(0, 16), h: b.getBoundingClientRect().height }));
  const notes = [...page.querySelectorAll('.settings-note')].filter(drawn).map((n) => ({ w: n.getBoundingClientRect().width, cap: 72 * textWidth(n, '0') }));
  const select = page.querySelector('#density');
  const density = select && drawn(select) ? { client: select.clientWidth,
    need: Math.max(...[...select.options].map((o) => textWidth(select, o.textContent))) + 20, shown: select.options[select.selectedIndex].textContent } : null;
  const times = [...page.querySelectorAll('.session-row')].map((row) => {
    const t = row.querySelector('.session-row-label > :first-child');
    const cell = row.querySelector('.session-name');
    if (!t || !t.classList.contains('session-row-time') || !drawn(t)) return { text: t ? t.textContent : '', whole: false, why: 'not first' };
    const r = t.getBoundingClientRect();
    return { text: t.textContent, whole: t.scrollWidth <= t.clientWidth + 0.5 && r.right <= cell.getBoundingClientRect().right + 0.5 && r.width > 0 };
  });
  return { id: page.id, buttons, notes, density, times };
})()`;

/** Each page document at 1440 and 400, served: button heights, the notes' measure, the select, the rows. */
async function pageDocs(page, url, rows) {
  for (const width of [1440, 400]) {
    await load(page, `${url}#/traffic/network`, width);
    const reads = {};
    for (const name of PAGES) {
      await page.ev(`navigate({ page: ${JSON.stringify(name)} })`);
      await page.waitFor(`!document.getElementById('page-${name}').hidden`, 5000);
      if (name === 'sessions') await page.waitFor(`document.querySelector('#session-rows .session-row')`, 5000);
      await sleep(500);
      reads[name] = await page.ev(PAGE_READ);
    }
    const all = Object.values(reads).filter((r) => r && !r.evalError);
    const errors = Object.values(reads).filter((r) => !r || r.evalError).map((r) => String(r && r.evalError));
    const buttons = all.flatMap((r) => r.buttons.map((b) => ({ ...b, page: r.id })));
    const low = buttons.filter((b) => b.h < 24 - 0.01);
    rows.push({ key: `polish ${width} page targets`, ok: !errors.length && buttons.length > 0 && !low.length,
      value: errors.length ? 'not measured' : !buttons.length ? 'NO - found no button on the four pages, so this checked nothing'
        : low.length ? 'short' : 'at least 24px',
      detail: errors.length ? errors.join(' | ') : `${buttons.length} buttons on ${all.length} pages${low.length
        ? `; short: ${low.slice(0, 6).map((b) => `${b.page} ${b.name} ${Math.round(b.h * 10) / 10}px`).join(', ')}` : ''}` });
    if (width === 1440) {
      const notes = all.flatMap((r) => r.notes);
      const wide = notes.filter((n) => n.w > n.cap + 0.5);
      rows.push({ key: `polish ${width} prose width`, ok: !errors.length && notes.length > 0 && !wide.length,
        value: !notes.length ? 'NO - found no note on the four pages, so this checked nothing' : wide.length ? 'wider than 72ch' : 'at most 72ch',
        detail: `${notes.length} notes, widest ${Math.round(Math.max(0, ...notes.map((n) => n.w)))}px against a 72ch of ${Math.round(notes[0] ? notes[0].cap : 0)}px` });
    }
    const d = reads.settings && reads.settings.density;
    const whole = Boolean(d) && d.client >= d.need - 0.5;
    rows.push({ key: `polish ${width} density whole`, ok: whole,
      value: !d ? 'NO - found no density select on Settings, so this checked nothing' : whole ? 'whole' : 'cut',
      detail: d ? `"${d.shown}": ${d.client}px for ${Math.round(d.need)}px (longest option and its chevron)` : '' });
    const times = (reads.sessions && reads.sessions.times) || [];
    const cut = times.filter((t) => !t.whole);
    rows.push({ key: `polish ${width} session time whole`, ok: times.length > 0 && !cut.length,
      value: !times.length ? 'NO - found no Sessions row, so this checked nothing' : cut.length ? 'cut' : 'whole, first',
      detail: `${times.length} rows: ${times.map((t) => `"${t.text}"${t.whole ? '' : ` ${t.why || 'cut'}`}`).join(', ')}` });
  }
}

const LEGEND_READ = `(() => {
  const pop = document.getElementById('keys-pop');
  if (!pop || pop.hidden || !pop.getClientRects().length) return { shown: false };
  const r = pop.getBoundingClientRect();
  const at = document.elementFromPoint((r.left + r.right) / 2, (r.top + r.bottom) / 2);
  return { shown: true, l: r.left, t: r.top, r: r.right, b: r.bottom, w: innerWidth, h: innerHeight,
    hit: Boolean(at) && pop.contains(at), keys: pop.querySelectorAll('#keys-pages li').length };
})()`;

/** `?` on Settings at 1440 and 400, served: the legend inside the window, on top, and shut by Escape. */
async function legendInside(page, url, rows) {
  for (const width of [1440, 400]) {
    await load(page, `${url}#/traffic/network`, width);
    await page.ev(`navigate({ page: 'settings' })`);
    await page.waitFor(`!document.getElementById('page-settings').hidden`, 5000);
    await sleep(300);
    await page.key('?', 'Slash', 191);
    await sleep(200);
    const r = await page.ev(LEGEND_READ);
    await page.key('Escape', 'Escape', 27);
    await sleep(100);
    const shut = await page.ev(`document.getElementById('keys-pop').hidden`);
    const ok = Boolean(r && !r.evalError && r.shown) && r.keys > 0 && r.l >= -0.5 && r.t >= -0.5 && r.r <= r.w + 0.5 && r.b <= r.h + 0.5 && r.hit && shut === true;
    rows.push({ key: `polish ${width} legend inside`, ok,
      value: !r || r.evalError ? 'not measured' : !r.shown ? 'NO - ? drew no legend on Settings, so this checked nothing' : ok ? 'inside, on top' : 'outside or covered',
      detail: r && r.shown ? `${Math.round(r.l)},${Math.round(r.t)} to ${Math.round(r.r)},${Math.round(r.b)} in ${r.w}x${r.h}; ${r.keys} page keys; hit ${r.hit}; Escape shut it ${shut}` : String(r && r.evalError || '') });
  }
}

async function polish(page, urls) {
  const rows = [];
  await iconTargets(page, urls.probe, rows);
  await pageDocs(page, urls.probe, rows);
  await legendInside(page, urls.probe, rows);
  return rows;
}

module.exports = { polish };
