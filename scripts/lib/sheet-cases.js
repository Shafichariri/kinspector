'use strict';
/*
 * The contact sheet (group `sheet` in scripts/measure-web-ui.js, opt-in, written to `--out <dir>`):
 * every page and view of the web UI as Chrome paints it, at 1440, 1000, 600 and 400 px, dark and
 * light, served and in an HTML export opened from disk, as PNGs and one `index.html` grid of them.
 *
 * It judges nothing. Its rows are notes, so a run that adds `sheet` changes no judged count, and a
 * screen that looks wrong is for whoever reads the sheet to say. It exists because on every web
 * item of the 2026-10-20/21 run each verifier wrote a screenshot script of their own, and none of
 * them was kept: the next reviewer started again, at other widths, on other pages.
 *
 * On the probe session: its 500 is the row opened, since its header draws the most actions. The
 * pages an export does not offer (Sessions, Processes, Settings) are taken served only. Findings is
 * taken on the payments session, served and from that session's own export, and so are the payments
 * session's list with its findings badges and the detail of its first flagged row. The probe session's
 * own Findings page, its planted sensitive values masked, is taken served and exported, and with a
 * dismiss form open served only, since an export offers none. The Diff page is taken served only, an
 * export holding one session: as `#/diff` opens it, and with the baseline against the probe session
 * and the orders route's detail open.
 */
const fs = require('fs');
const path = require('path');
const { sleep } = require('./measure-cdp');
const { scratchDir } = require('./measure-scratch');
const { PAYMENTS_SESSION_ID } = require('./findings-probes');
const { BASE_SESSION_ID } = require('./diff-probes');
const { PROBE_SESSION_ID } = require('./measure-fixtures');

const WIDTHS = [1440, 1000, 600, 400];
const SCHEMES = ['dark', 'light'];
// `hash` is the address the shot is loaded at; `open` runs on the loaded page and is awaited.
const OPEN_ROW = `(() => { const rows = [...document.querySelectorAll('#list .row')];
  const row = rows.find((r) => r.querySelector('.status').textContent === '500') || rows[0]; if (row) row.click(); return Boolean(row); })()`;
// The first row with a findings badge, once the report has drawn one; the badges follow the first page.
const OPEN_FLAGGED = `new Promise((done) => { const at = Date.now(); const tick = () => {
  const row = document.querySelector('#list .row .finding-badge')?.closest('.row');
  if (row || Date.now() - at > 5000) { if (row) row.click(); done(Boolean(row)); } else setTimeout(tick, 100); }; tick(); })`;
// The first finding's dismiss form, once the Findings page has drawn its control.
const OPEN_DISMISS = `new Promise((done) => { const at = Date.now(); const tick = () => {
  const open = document.querySelector('#findings-body .finding-dismiss');
  if (open || Date.now() - at > 5000) { if (open) open.click(); done(Boolean(open)); } else setTimeout(tick, 100); }; tick(); })`;
const PAYMENTS_LIST = `#/traffic/network?session=${encodeURIComponent(PAYMENTS_SESSION_ID)}`;
// The orders route's detail, once the comparison is drawn.
const OPEN_DIFF = `new Promise((done) => { const at = Date.now(); const tick = () => {
  const open = document.querySelector('#diff-rows tr[data-route="/v3/shop/orders/submit"] .diff-open');
  if (open || Date.now() - at > 10000) { if (open) open.click(); done(Boolean(open)); } else setTimeout(tick, 100); }; tick(); })`;
const DIFF_PAIR = `#/diff?a=${encodeURIComponent(BASE_SESSION_ID)}&b=${encodeURIComponent(PROBE_SESSION_ID)}`;
const SHOTS = [
  { name: 'list', hash: '#/traffic/network', exported: true },
  { name: 'detail', hash: '#/traffic/network', exported: true, open: OPEN_ROW, ready: `!document.getElementById('detail').hidden` },
  { name: 'txn', hash: '#/traffic/network', exported: true, open: OPEN_ROW, then: `document.getElementById('detail-full').click()`,
    ready: `document.getElementById('panes').classList.contains('txn-full')` },
  { name: 'timeline', hash: '#/traffic/all', exported: true },
  { name: 'path', hash: '#/traffic/path', exported: true },
  { name: 'stats', hash: '#/stats', exported: true, ready: `document.querySelector('#page-stats tbody tr')` },
  { name: 'findings', hash: `#/findings?session=${encodeURIComponent(PAYMENTS_SESSION_ID)}`, exported: true, payments: true,
    ready: `document.querySelector('#findings-body article.finding')` },
  { name: 'sensitive', hash: '#/findings', exported: true, ready: `document.querySelector('#findings-body .ev-masked')` },
  { name: 'dismiss form', hash: '#/findings', exported: false, open: OPEN_DISMISS,
    ready: `document.querySelector('#findings-body form.dismiss-form')` },
  { name: 'flagged list', hash: PAYMENTS_LIST, exported: true, payments: true, ready: `document.querySelector('#list .finding-badge')` },
  { name: 'flagged detail', hash: PAYMENTS_LIST, exported: true, payments: true, open: OPEN_FLAGGED,
    ready: `document.querySelector('#detail .detail-findings:not([hidden])')` },
  { name: 'diff', hash: '#/diff', exported: false, ready: `document.querySelector('#diff-summary [data-session]')` },
  { name: 'diff detail', hash: DIFF_PAIR, exported: false, open: OPEN_DIFF, ready: `document.querySelector('#diff-rows .diff-detail')` },
  { name: 'sessions', hash: '#/sessions', exported: false, ready: `document.querySelector('#session-rows .session-row')` },
  { name: 'processes', hash: '#/processes', exported: false },
  { name: 'settings', hash: '#/settings', exported: false },
];

const LOADED = `typeof state !== 'undefined' && state.sessionId && document.querySelectorAll('#list .row').length > 0 && (typeof wholePending !== 'function' || !wholePending())`;

/** One shot: a fresh load at the width and scheme already set, the shot's steps, then the paint. */
async function take(page, base, shot) {
  await page.navigate('about:blank');
  await page.navigate(`${base}${shot.hash}`);
  if (await page.waitFor(LOADED, 60000) === null) throw new Error(`${base}${shot.hash} drew no rows within 60 s`);
  if (shot.open) {
    await page.ev(shot.open);
    await sleep(300);
  }
  if (shot.then) await page.ev(shot.then);
  const ready = shot.ready ? await page.waitFor(shot.ready, 5000) : 0;
  await sleep(700); // the drawer's slide, the axis and the stats table's first paint
  return { png: await page.screenshot(), ready: ready !== null };
}

const file = (where, scheme, width, shot) => `${where}-${scheme}-${width}-${shot}.png`;

/** The grid: one table per place and scheme, a row per shot, a column per width, each cell a link to its PNG. */
function indexHtml(tree, taken) {
  const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;');
  const tables = [];
  for (const where of ['served', 'export']) {
    for (const scheme of SCHEMES) {
      const rows = SHOTS.filter((s) => where === 'served' || s.exported).map((s) => `<tr><th>${s.name}</th>${WIDTHS.map((w) => {
        const name = file(where, scheme, w, s.name);
        const shot = taken.get(name);
        return `<td>${shot ? `<a href="${name}"><img src="${name}" width="${Math.round(w / 4)}" loading="lazy" alt="${esc(name)}"></a>${shot.ready ? '' : '<br><b>not ready</b>'}` : 'missing'}</td>`;
      }).join('')}</tr>`);
      tables.push(`<h2>${where}, ${scheme}</h2><table><tr><th></th>${WIDTHS.map((w) => `<th>${w}px</th>`).join('')}</tr>${rows.join('')}</table>`);
    }
  }
  return `<!doctype html><meta charset="utf-8"><title>web UI contact sheet</title>
<style>body{font:13px system-ui,sans-serif;margin:16px}td,th{vertical-align:top;text-align:left;padding:4px}img{border:1px solid #888}</style>
<h1>web UI contact sheet</h1><p>${esc(tree)}, ${new Date().toISOString()}. Each image is the window at that width, 900px tall; click for full size.</p>
${tables.join('\n')}\n`;
}

async function sheet(page, url, outDir, tree) {
  fs.mkdirSync(outDir, { recursive: true });
  const taken = new Map();
  const rows = [];
  const thrown = new Set(); // each load clears the page's list, so they are gathered per shot
  const tmp = scratchDir('sheet-export-');
  try {
    const exportFile = path.join(tmp, 'export.html');
    fs.writeFileSync(exportFile, Buffer.from(await (await fetch(`${url}api/sessions/latest/export.html`)).arrayBuffer()));
    const paymentsFile = path.join(tmp, 'payments.html');
    fs.writeFileSync(paymentsFile, Buffer.from(await (await fetch(`${url}api/sessions/${encodeURIComponent(PAYMENTS_SESSION_ID)}/export.html`)).arrayBuffer()));
    for (const [where, base, payments] of [['served', url, url], ['export', `file://${exportFile}`, `file://${paymentsFile}`]]) {
      for (const scheme of SCHEMES) {
        await page.colorScheme(scheme);
        let count = 0;
        const unready = [];
        for (const width of WIDTHS) {
          await page.setWidth(width);
          for (const shot of SHOTS.filter((s) => where === 'served' || s.exported)) {
            const name = file(where, scheme, width, shot.name);
            const { png, ready } = await take(page, shot.payments ? payments : base, shot);
            fs.writeFileSync(path.join(outDir, name), png);
            taken.set(name, { ready });
            for (const e of page.exceptions) thrown.add(e);
            count++;
            if (!ready) unready.push(`${width} ${shot.name}`);
          }
        }
        // A note, never a verdict: the sheet is for looking at, and a shot not ready is still drawn.
        rows.push({ key: `sheet ${where} ${scheme}`, value: `${count} shots`, ok: null,
          detail: unready.length ? `not ready: ${unready.join(', ')}` : '' });
      }
    }
  } finally {
    await page.colorScheme(null);
    fs.rmSync(tmp, { recursive: true, force: true });
  }
  fs.writeFileSync(path.join(outDir, 'index.html'), indexHtml(tree, taken));
  rows.push({ key: 'sheet index', value: path.join(outDir, 'index.html'), ok: null,
    detail: thrown.size ? `page exceptions: ${[...thrown].join(' | ')}` : '' });
  return rows;
}

module.exports = { sheet };
