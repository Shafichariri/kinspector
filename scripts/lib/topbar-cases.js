'use strict';
/*
 * The top bar, read in Chrome: does the count stay whole inside the bar, and which controls are on
 * screen, clipped or off it. Promoted from the harness three reviewers each wrote for KIN-49, where
 * a wrapped `newest 1000 of 1203 shown` left `1000 of 1203` visible, the shape only a filter
 * produces. jsdom has no layout, so no static check can see it.
 *
 * Judged: every word of `#counts` is inside the bar and the viewport, at 975 and 800 px, on the
 * 1203-call session (its count is the longest the probes draw). Not judged: each control's state
 * per width. With --base, measure-web-ui prints any that moved, so a control going off screen at
 * head shows up there. Below about 900 px the bar overflows by design until 1a rebuilds it.
 */
const { sleep } = require('./measure-cdp');
const { LONG_SESSION_ID } = require('./long-session-probes');

const WIDTHS = [975, 800];
const CONTROLS = ['.brand', '#session-picker', '#session-delete', '#export-host', '#counts', '#live-btn',
  '#conn', '#open-settings', '#server-restart', '#server-stop'];

const READ = `(() => {
  const bar = document.getElementById('topbar').getBoundingClientRect();
  const inside = (r) => r.left >= -0.5 && r.right <= innerWidth + 0.5 && r.top >= bar.top - 0.5 && r.bottom <= bar.bottom + 0.5;
  const controls = ${JSON.stringify(CONTROLS)}.map((s) => {
    const e = document.querySelector(s);
    if (!e || e.hidden || !e.offsetParent) return s + ':hidden';
    const r = e.getBoundingClientRect();
    return s + ':' + (inside(r) ? 'on' : r.right > 0 && r.left < innerWidth ? 'clipped' : 'off');
  });
  const c = document.getElementById('counts');
  const out = []; const tops = new Set();
  for (const m of c.textContent.matchAll(/\\S+/g)) {
    const range = document.createRange();
    range.setStart(c.firstChild, m.index); range.setEnd(c.firstChild, m.index + m[0].length);
    const r = range.getBoundingClientRect();
    tops.add(Math.round(r.top));
    if (!inside(r)) out.push(m[0]);
  }
  return { text: c.textContent, lines: tops.size, out, controls };
})()`;

async function topBar(page, url, session) {
  const rows = [];
  await page.setWidth(1440);
  await page.navigate(url);
  if (await page.waitFor(`document.querySelectorAll('#list .row').length > 0`, 60000) === null) {
    throw new Error(`${url} drew no rows within 60 s`);
  }
  const has = await page.ev(`[...document.getElementById('session-picker').options].some((o) => o.value === ${JSON.stringify(LONG_SESSION_ID)})`);
  if (!has) return [{ key: `topbar ${session}`, value: `n/a (no session ${LONG_SESSION_ID})`, ok: null, detail: '' }];
  await page.ev(`(() => { const p = document.getElementById('session-picker'); p.value = ${JSON.stringify(LONG_SESSION_ID)};
    p.dispatchEvent(new Event('change', { bubbles: true })); })()`);
  await page.waitFor(`/1203/.test(document.getElementById('counts').textContent)`, 20000);
  await sleep(1000);
  for (const width of WIDTHS) {
    await page.setWidth(width);
    await sleep(400);
    const r = await page.ev(READ);
    const whole = r.lines === 1 && r.out.length === 0;
    rows.push({ key: `topbar ${session} ${width} count`, value: whole ? 'one line, inside the bar' : 'wrapped or clipped',
      ok: whole, detail: `"${r.text}", ${r.lines} line(s)${r.out.length ? `, outside: ${r.out.join(' ')}` : ''}` });
    rows.push({ key: `topbar ${session} ${width} controls`, value: r.controls.join(' '), ok: null, detail: '' });
  }
  return rows;
}

module.exports = { topBar };
