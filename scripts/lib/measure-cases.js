'use strict';
/*
 * What scripts/measure-web-ui.js measures, in a real browser. Every case returns rows of
 *
 *   { key, value, ok, detail }
 *
 * `key` names the measurement and is the same on every revision. `value` is a word or two that
 * a regression changes ("stays" becomes "moves") and is what the base-versus-head mode compares.
 * `detail` carries the pixels and milliseconds, which are printed but never compared, because
 * they wobble between runs. `ok` is true, false, or null for a reading that is reported but not
 * judged.
 *
 * Each case exists because jsdom could not see the defect it pins, and a verify panel found it
 * only in Chrome: layout (KIN-33), a run toggle throwing the pane (KIN-38), a live row throwing it
 * (KIN-41), and a huge session drawing nothing (KIN-37, KIN-42).
 */
const { sleep } = require('./measure-cdp');

const WIDTHS = [1440, 400];
const FILTERS = [['none', ''], ['stock', 'path:/v3/shop/stock'], ['screen', 'tag:screen']];
const ORDERS = [['oldest-first', false], ['newest-first', true]];

const LOADED = `typeof state !== 'undefined' && state.sessionId && document.querySelectorAll('#list .row').length > 0`;
const LIVE_OPEN = `(window.__sockets || []).some((s) => s.url.includes('/api/live') && s.readyState === 1)`;

// The pane a view scrolls in. Before KIN-33 the timeline had a scroller of its own; since, it
// flows in #list-pane. Choosing by computed style measures both revisions honestly.
const SCROLLER = `(() => {
  const tl = document.getElementById('timeline');
  const own = tl && state.view === 'all' && /auto|scroll/.test(getComputedStyle(tl).overflowY);
  return own ? tl : document.getElementById('list-pane');
})()`;

// Is the scope bar the topmost element across its own width? A sticky note drawn over it (KIN-33)
// fails this.
const BAR_ON_TOP = `(() => {
  const bar = document.getElementById('scope-bar');
  if (!bar || bar.hidden) return { hidden: true };
  const b = bar.getBoundingClientRect();
  const hits = [b.left + 12, (b.left + b.right) / 2, b.right - 30].map((x) => {
    const e = document.elementFromPoint(x, (b.top + b.bottom) / 2);
    return e && bar.contains(e) ? 'bar' : (e ? (e.id || e.className || e.tagName) : 'nothing');
  });
  return { onTop: hits.every((h) => h === 'bar'), hits };
})()`;

async function load(page, url, width) {
  await page.setWidth(width);
  await page.navigate(url);
  const ms = await page.waitFor(LOADED, 60000);
  if (ms === null) throw new Error(`${url} drew no rows within 60 s`);
}

async function setFilter(page, text) {
  await page.ev(`(() => { const f = document.getElementById('filter'); f.value = ${JSON.stringify(text)};
    f.dispatchEvent(new Event('input', { bubbles: true })); })()`);
  await sleep(1500);
}

async function view(page, name) {
  await page.ev(`selectView(${JSON.stringify(name)})`);
  await sleep(600);
}

function exceptionsRow(key, page) {
  const seen = [...new Set(page.exceptions)];
  return { key: `${key} page exceptions`, value: seen.length ? 'thrown' : 'none', ok: seen.length === 0, detail: seen.join(' | ') };
}

// Scope bar on top, the timeline's first row whole, and each path station landing below the
// sticky box, per width and filter.
async function layout(page, url, session) {
  const rows = [];
  for (const width of WIDTHS) {
    await load(page, url, width);
    for (const [fname, ftext] of FILTERS) {
      const key = `layout ${session} ${width} filter=${fname}`;
      await setFilter(page, ftext);

      await view(page, 'all');
      let r = await page.ev(BAR_ON_TOP);
      if (!r.hidden) rows.push({ key: `${key} timeline scope bar`, value: r.onTop ? 'on top' : 'covered', ok: r.onTop, detail: r.hits.join(',') });
      r = await page.ev(`(async () => {
        const sc = ${SCROLLER}; sc.scrollTop = 0; await new Promise((res) => setTimeout(res, 200));
        const bar = document.getElementById('scope-bar');
        const note = document.querySelector('#timeline > .filter-note');
        const tl = document.getElementById('timeline');
        const first = [...tl.children].find((c) => !c.classList.contains('filter-note') && !c.hidden);
        if (!first) return { none: true };
        const ceiling = Math.max(bar.hidden ? sc.getBoundingClientRect().top : bar.getBoundingClientRect().bottom,
          note ? note.getBoundingClientRect().bottom : 0);
        const top = first.getBoundingClientRect().top;
        const hit = document.elementFromPoint(first.getBoundingClientRect().left + 40, top + 3);
        return { top: Math.round(top), ceiling: Math.round(ceiling), visible: top >= ceiling - 0.5 && first.contains(hit) };
      })()`);
      if (r.none) rows.push({ key: `${key} timeline first row`, value: 'no row', ok: null, detail: '' });
      else rows.push({ key: `${key} timeline first row`, value: r.visible ? 'visible' : 'covered', ok: r.visible, detail: `top ${r.top}, ceiling ${r.ceiling}` });

      await view(page, 'path');
      r = await page.ev(BAR_ON_TOP);
      if (!r.hidden) rows.push({ key: `${key} path scope bar`, value: r.onTop ? 'on top' : 'covered', ok: r.onTop, detail: r.hits.join(',') });
      const stations = await page.ev(`document.querySelectorAll('#path .pv-stop').length`);
      for (let i = 0; i < stations; i++) {
        r = await page.ev(`(async () => {
          const lp = document.getElementById('list-pane'); lp.scrollTop = 0; await new Promise((res) => setTimeout(res, 100));
          document.querySelectorAll('#path .pv-stop')[${i}].click(); await new Promise((res) => setTimeout(res, 500));
          const bar = document.getElementById('scope-bar'); const box = document.querySelector('#path .pv-top');
          const ceiling = Math.max(bar.hidden ? 0 : bar.getBoundingClientRect().bottom, box ? box.getBoundingClientRect().bottom : 0);
          const visit = document.querySelector('#path .pv-visit[data-visit="${i}"]');
          if (!visit) return { none: true };
          return { top: Math.round(visit.getBoundingClientRect().top), ceiling: Math.round(ceiling) };
        })()`);
        if (r.none) continue;
        const below = r.top >= r.ceiling - 0.5;
        rows.push({ key: `${key} path station ${i}`, value: below ? 'below the box' : 'under the box', ok: below, detail: `top ${r.top}, ceiling ${r.ceiling}` });
      }

      if (await page.ev(`!!document.querySelector('#tabs .tab[data-view="waterfall"]')`)) {
        await view(page, 'waterfall');
        r = await page.ev(`(async () => { const lp = document.getElementById('list-pane'); lp.scrollTop = lp.scrollHeight / 2;
          await new Promise((res) => setTimeout(res, 200)); return ${BAR_ON_TOP}; })()`);
        if (!r.hidden) rows.push({ key: `${key} waterfall scope bar mid-scroll`, value: r.onTop ? 'on top' : 'covered', ok: r.onTop, detail: r.hits.join(',') });
      }
      await view(page, 'network');
    }
    rows.push(exceptionsRow(`layout ${session} ${width}`, page));
  }
  await setFilter(page, '');
  return rows;
}

// KIN-38: with live tail on, expanding a timeline run leaves the pane and the run where they are.
async function runToggle(page, url, session) {
  const rows = [];
  for (const width of WIDTHS) {
    await load(page, url, width);
    for (const [oname, newest] of ORDERS) {
      await page.ev(`setSortOrder(${newest}); setLiveTail(true)`);
      await view(page, 'all');
      const r = await page.ev(`(async () => {
        const runs = [...document.querySelectorAll('#timeline .tl-run')];
        if (!runs.length) return { none: true };
        const run = runs[Math.floor(runs.length / 2)];
        run.scrollIntoView({ block: 'center' }); await new Promise((res) => setTimeout(res, 200));
        const sc = ${SCROLLER};
        const before = { scroll: sc.scrollTop, y: run.getBoundingClientRect().top };
        // The click redraws the timeline, so the run is found again by its id: the old node is
        // detached and would report y 0.
        const id = run.dataset.runId;
        run.click(); await new Promise((res) => setTimeout(res, 500));
        const again = [...document.querySelectorAll('#timeline .tl-run')].find((n) => n.dataset.runId === id);
        if (!again) return { lost: true };
        const after = { scroll: sc.scrollTop, y: again.getBoundingClientRect().top };
        return { before, after };
      })()`);
      const key = `run toggle ${session} ${width} ${oname}`;
      if (r.none) { rows.push({ key, value: 'no run', ok: null, detail: '' }); continue; }
      if (r.lost) { rows.push({ key, value: 'run gone after toggle', ok: false, detail: '' }); continue; }
      const stays = Math.abs(r.after.scroll - r.before.scroll) <= 1 && Math.abs(r.after.y - r.before.y) <= 1;
      rows.push({ key, value: stays ? 'stays' : 'moves', ok: stays,
        detail: `scrollTop ${Math.round(r.before.scroll)} -> ${Math.round(r.after.scroll)}, run y ${Math.round(r.before.y)} -> ${Math.round(r.after.y)}` });
      await page.ev(`document.querySelectorAll('#timeline .tl-run-open').forEach((n) => n.click())`);
    }
    rows.push(exceptionsRow(`run toggle ${session} ${width}`, page));
  }
  return rows;
}

// KIN-41: a live call or marker, handed to the page's own ws.onmessage, leaves a scrolled-back
// pane where it is, in every view that scrolls; and a timeline at the end it follows keeps
// following. Other views' following is reported, not judged: no decision says they follow.
async function liveArrival(page, url, session) {
  const rows = [];
  let mono = 0;
  const deliver = (kind) => page.ev(`(() => {
    const sock = window.__sockets.find((s) => s.url.includes('/api/live'));
    const last = state.transactions.reduce((a, t) => (t.mono > a.mono ? t : a), state.transactions[0]);
    const mono = last.mono + ${++mono};
    const message = ${JSON.stringify(kind)} === 'txn'
      ? { type: 'txn', sessionId: state.sessionId, txn: Object.assign({}, last, { id: 'measure-' + mono, mono }) }
      : { type: 'marker', sessionId: state.sessionId, marker: { label: 'measure', mono, ts: last.ts, source: 'user' } };
    sock.onmessage({ data: JSON.stringify(message) });
  })()`);
  for (const width of WIDTHS) {
    for (const [oname, newest] of ORDERS) {
      await load(page, url, width);
      if (await page.waitFor(LIVE_OPEN, 10000) === null) throw new Error('the page opened no live socket');
      await page.ev(`setSortOrder(${newest}); setLiveTail(true)`);
      for (const v of ['all', 'path', 'waterfall']) {
        if (!(await page.ev(`!!document.querySelector('#tabs .tab[data-view="${v}"]')`))) continue;
        await view(page, v);
        for (const kind of ['txn', 'marker']) {
          const key = `live ${session} ${width} ${oname} ${v} ${kind}`;
          const max = await page.ev(`(() => { const sc = ${SCROLLER}; return sc.scrollHeight - sc.clientHeight; })()`);
          if (max < 50) { rows.push({ key: `${key} scrolled back`, value: 'too short', ok: null, detail: `max ${max}` }); continue; }

          const before = await page.ev(`(async () => { const sc = ${SCROLLER}; sc.scrollTop = Math.round((sc.scrollHeight - sc.clientHeight) / 2);
            await new Promise((res) => setTimeout(res, 200)); return sc.scrollTop; })()`);
          await deliver(kind); await sleep(600);
          const after = await page.ev(`${SCROLLER}.scrollTop`);
          const stays = Math.abs(after - before) <= 1;
          rows.push({ key: `${key} scrolled back`, value: stays ? 'stays' : 'moves', ok: stays, detail: `scrollTop ${Math.round(before)} -> ${Math.round(after)}` });

          await page.ev(`(async () => { const sc = ${SCROLLER}; sc.scrollTop = ${newest} ? 0 : sc.scrollHeight;
            await new Promise((res) => setTimeout(res, 200)); })()`);
          await deliver(kind); await sleep(600);
          const end = await page.ev(`(() => { const sc = ${SCROLLER}; const max = sc.scrollHeight - sc.clientHeight;
            return { top: sc.scrollTop, max, atEnd: ${newest} ? sc.scrollTop <= 1 : sc.scrollTop >= max - 1 }; })()`);
          rows.push({ key: `${key} at its end`, value: end.atEnd ? 'follows' : 'left behind', ok: v === 'all' ? end.atEnd : null,
            detail: `scrollTop ${Math.round(end.top)} of ${Math.round(end.max)}` });
        }
      }
      rows.push(exceptionsRow(`live ${session} ${width} ${oname}`, page));
    }
  }
  return rows;
}

// KIN-37 and KIN-42: how long a session takes to draw its first rows and to connect.
async function firstRows(page, url, session, limitMs) {
  await page.setWidth(1440);
  await page.navigate(url);
  const start = Date.now();
  const drawn = await page.waitFor(`document.querySelectorAll('#list .row').length > 0`, 60000);
  const connected = await page.waitFor(`(document.querySelector('#conn .conn-label') || {}).textContent === 'connected'`,
    Math.max(1000, 60000 - (Date.now() - start)));
  const total = connected === null ? null : Date.now() - start;
  const judge = (ms) => (ms !== null && ms <= limitMs);
  return [
    { key: `first rows ${session}`, value: judge(drawn) ? `within ${limitMs} ms` : `over ${limitMs} ms`, ok: judge(drawn),
      detail: drawn === null ? 'none in 60 s' : `${drawn} ms` },
    { key: `connected ${session}`, value: judge(total) ? `within ${limitMs} ms` : `over ${limitMs} ms`, ok: judge(total),
      detail: total === null ? 'not in 60 s' : `${total} ms` },
    exceptionsRow(`first rows ${session}`, page),
  ];
}

module.exports = { layout, runToggle, liveArrival, firstRows };
