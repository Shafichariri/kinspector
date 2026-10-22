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

async function polish(page, urls) {
  const rows = [];
  await iconTargets(page, urls.probe, rows);
  return rows;
}

module.exports = { polish };
