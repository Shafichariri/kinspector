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
 * (`tabs reachable`); each rail link inside the window (`rail`); and the Sessions, Processes and
 * Settings pages never scrolling the window sideways nor clipping themselves (`page <name> fits`).
 * Once: rail clicks, then Back (`shell back`).
 */
const { sleep } = require('./measure-cdp');
const { LONG_SESSION_ID } = require('./long-session-probes');
const { PROBE_SESSION_ID } = require('./measure-fixtures');

const WIDTHS = [1440, 1000, 600, 400];
const CONTROLS = ['#session-picker', '#live-btn', '#conn'];
const PAGES = ['sessions', 'processes', 'settings'];

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
  const railOut = [...document.querySelectorAll('#rail .rail-item')]
    .filter((a) => !a.getClientRects().length || !inside(a.getBoundingClientRect())).map((a) => a.dataset.page);
  return {
    bar: { scroll: bar.scrollWidth, width: innerWidth, height: Math.round(bar.getBoundingClientRect().height) },
    controls, timeWhole, time: time ? time.textContent : null, railOut, railWidth: Math.round(document.getElementById('rail').getBoundingClientRect().width),
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
    rows.push({ key: `shell ${width} rail`, value: r.railOut.length ? 'outside' : 'inside', ok: r.railOut.length === 0,
      detail: `${r.railWidth}px${r.railOut.length ? `, outside: ${r.railOut.join(' ')}` : ''}` });
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

module.exports = { shell };
