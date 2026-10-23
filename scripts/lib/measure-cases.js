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
const fs = require('fs');
const path = require('path');
const { sleep } = require('./measure-cdp');
const { scratchDir } = require('./measure-scratch');

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
// fails this. A hidden bar is a reading too, not a skipped one: it measured nothing, so it fails
// unless the page holds no scope for the session (`state.scope`, from the whole session, which no
// filter or view clears), and then it says n/a.
const BAR_ON_TOP = `(() => {
  const bar = document.getElementById('scope-bar');
  if (!bar || bar.hidden) return { hidden: true, due: Boolean(state.scope) };
  const b = bar.getBoundingClientRect();
  const hits = [b.left + 12, (b.left + b.right) / 2, b.right - 30].map((x) => {
    const e = document.elementFromPoint(x, (b.top + b.bottom) / 2);
    return e && bar.contains(e) ? 'bar' : (e ? (e.id || e.className || e.tagName) : 'nothing');
  });
  return { onTop: hits.every((h) => h === 'bar'), hits };
})()`;
const barRow = (key, r) => (r.hidden
  ? { key, value: r.due ? 'hidden' : 'n/a (no scope in this session)', ok: r.due ? false : null, detail: r.due ? 'the page holds a scope, so this measured nothing' : '' }
  : { key, value: r.onTop ? 'on top' : 'covered', ok: r.onTop, detail: `${r.hits.length} points: ${r.hits.join(',')}` });

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
      rows.push(barRow(`${key} timeline scope bar`, r));
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
      rows.push(barRow(`${key} path scope bar`, r));
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
        rows.push(barRow(`${key} waterfall scope bar mid-scroll`, r));
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
          const room = () => page.ev(`(() => { const sc = ${SCROLLER}; return sc.scrollHeight - sc.clientHeight; })()`);
          let max = await room();
          // 1b gave the pane its height back, so at 400 on `big` the folded path fits it and these
          // readings stopped judging anything. Unfolded, every visit's log is drawn and it scrolls.
          if (max < 50 && v === 'path') {
            await page.ev(`(() => {
              document.querySelectorAll('#path .pv-visit:not(.pv-open) .pv-twisty').forEach((t) => t.click());
              document.querySelectorAll('#path .pv-more').forEach((m) => m.click());
            })()`);
            await sleep(300);
            max = await room();
          }
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

// KIN-42: what the page says between its first draw and the whole-session copy landing, sampled at
// first rows. No empty-state claim, the glance in `beingRead`'s words, the axis and endpoints hidden.
const DURING_READ = `(() => {
  if (typeof wholePending !== 'function') return { error: 'no wholePending on this page' };
  if (!wholePending()) return { landed: true };
  const text = document.body.innerText;
  const claims = ['waiting for an app', 'nothing recorded', 'nothing was recorded', 'no calls'].filter((c) => text.includes(c));
  const glance = document.getElementById('detail-empty').textContent;
  const line = document.getElementById('overview');
  return { claims, glance, due: beingRead(state.sessionTotal), axis: document.getElementById('axis').hidden,
    endpoints: document.getElementById('endpoints-button').hidden, line: line.hidden ? null : line.innerText.trim() };
})()`;

function beingReadRow(r) {
  const key = 'being read huge';
  if (!r || r.evalError || r.error) return { key, value: 'unreadable', ok: false, detail: (r && (r.evalError || r.error)) || 'no answer' };
  if (r.landed) return { key, value: 'checked nothing', ok: false, detail: 'the copy landed before the sample' };
  const wrong = [...r.claims.map((c) => `the page says "${c}"`),
    ...(r.glance === r.due && /being read/.test(r.glance) ? [] : [`the glance reads "${r.glance.slice(0, 60)}"`]),
    ...(r.axis ? [] : ['the axis is shown']), ...(r.endpoints ? [] : ['the endpoints are shown']),
    // Its band kept through the read so the rows do not drop when the copy lands, and saying why.
    ...(r.line === r.due ? [] : [`the overview line ${r.line === null ? 'is hidden' : `reads "${r.line.slice(0, 60)}"`}`])];
  return { key, value: wrong.length ? 'claims' : 'being read', ok: !wrong.length, detail: wrong.join('; ') || r.glance };
}

// KIN-42: the copy landing redraws in place. The list pane is scrolled during the read; after it,
// the scroll is where it was left and its first visible row moved by at most the scope bar's height,
// measured, which the bar is the one band allowed to add. A fixed 48 px let the overview band's 11 px
// through with its reserved height removed (KIN-110): 44 px, green.
const FIRST_VISIBLE = `(() => {
  const pane = document.getElementById('list').parentElement;
  const top = pane.getBoundingClientRect().top;
  const row = [...document.querySelectorAll('#list .row')].find((r) => r.getBoundingClientRect().bottom > top);
  return row ? { id: row.dataset.id, y: row.getBoundingClientRect().top, scrollTop: pane.scrollTop } : null;
})()`;
const AFTER_LANDING = `(() => {
  const pane = document.getElementById('list').parentElement;
  const row = [...document.querySelectorAll('#detail-empty.glance .glance-row')]
    .find((r) => (r.querySelector('.glance-label') || {}).textContent === 'calls in session');
  const bar = document.getElementById('scope-bar');
  return { scrollTop: pane.scrollTop, scope: bar && !bar.hidden ? bar.getBoundingClientRect().height : 0,
    endpoints: !document.getElementById('endpoints-button').hidden,
    chips: document.querySelectorAll('#endpoint-chips .chip').length,
    glance: row ? 'calls in session ' + row.querySelector('.glance-value').textContent : null, total: state.sessionTotal };
})()`;

async function copyLandsRow(page, start) {
  const key = 'copy lands huge';
  // Read after `connected`, which a socket opened after the load holds for the whole read.
  if (await page.ev(`!wholePending()`) !== false) return { key, value: 'checked nothing', ok: false, detail: 'the copy landed before the scroll' };
  await page.ev(`document.getElementById('list').parentElement.scrollTop = 4000`);
  await sleep(200);
  const before = await page.ev(FIRST_VISIBLE);
  if (!before || before.evalError || before.scrollTop !== 4000) {
    return { key, value: 'unreadable', ok: false, detail: `no row or no scroll to 4000 during the read: ${JSON.stringify(before)}` };
  }
  const landed = await page.waitFor(`!wholePending()`, 60000);
  if (landed === null) return { key, value: 'not landed', ok: false, detail: 'the copy did not land in 60 s' };
  await sleep(300);
  const after = await page.ev(AFTER_LANDING);
  const row = await page.ev(`(() => { const r = document.querySelector('#list .row[data-id="${before.id}"]'); return r ? r.getBoundingClientRect().top : null; })()`);
  const moved = row === null ? null : Math.round(row - before.y);
  // One pixel over the bar for rounding: `moved` is rounded, the bar's height is not.
  const bound = Math.round(after.scope) + 1;
  const wrong = [
    ...(after.scrollTop === 4000 ? [] : [`moved (scrollTop ${after.scrollTop} where 4000 was left)`]),
    ...(moved !== null && Math.abs(moved) <= bound ? [] : [`the first visible row moved ${moved === null ? 'out of the list' : `${moved} px`}, over the scope bar's ${after.scope} px`]),
    ...(after.endpoints && after.chips ? [] : ['endpoints hidden after the copy landed']),
    ...(after.glance === `calls in session ${after.total}` ? [] : [`the glance reads "${after.glance}"`]),
  ];
  return { key, value: wrong.length ? 'moved or stale' : 'in place', ok: !wrong.length,
    detail: `${wrong.join('; ') || `row moved ${moved} px, scope bar ${after.scope} px, ${after.chips} chips, ${after.glance}`}; landed at ${Date.now() - start} ms` };
}

// KIN-37 and KIN-42: how long a session takes to draw its first rows and to connect.
async function firstRows(page, url, session, limitMs) {
  await page.setWidth(1440);
  await page.navigate(url);
  const start = Date.now();
  const drawn = await page.waitFor(`document.querySelectorAll('#list .row').length > 0`, 60000);
  const during = session === 'huge' ? beingReadRow(await page.ev(DURING_READ)) : null;
  const connected = await page.waitFor(`(document.querySelector('#conn .conn-label') || {}).textContent === 'connected'`,
    Math.max(1000, 60000 - (Date.now() - start)));
  const total = connected === null ? null : Date.now() - start;
  const judge = (ms) => (ms !== null && ms <= limitMs);
  return [
    { key: `first rows ${session}`, value: judge(drawn) ? `within ${limitMs} ms` : `over ${limitMs} ms`, ok: judge(drawn),
      detail: drawn === null ? 'none in 60 s' : `${drawn} ms` },
    { key: `connected ${session}`, value: judge(total) ? `within ${limitMs} ms` : `over ${limitMs} ms`, ok: judge(total),
      detail: total === null ? 'not in 60 s' : `${total} ms` },
    ...(during ? [during, await copyLandsRow(page, start)] : []),
    exceptionsRow(`first rows ${session}`, page),
  ];
}

// --- the sensitive findings on the probe session's Findings page (item:sensitive-data-detection) ---

const SENSITIVE_WIDTHS = [1440, 1000, 600, 400];
const SENSITIVE_DRAWN = `document.querySelectorAll('#findings-body .ev-masked').length > 0`;

// Every masked hint whole: one line, not clipped, inside its evidence line. The fixture's paths are
// short enough that nothing presses on a hint, so the first evidence path is made 200 characters long
// first: that path must give way from its start instead, and below 520px take a line under the hint.
const MASKED_WHOLE = `(() => {
  const bdi = document.querySelector('#findings-body .ev-path bdi');
  if (bdi) bdi.textContent = '$.' + 'nested.'.repeat(28) + 'card_number';
  const masks = [...document.querySelectorAll('#findings-body .ev-masked')];
  const bad = masks.filter((m) => {
    const r = m.getBoundingClientRect();
    const li = m.closest('li').getBoundingClientRect();
    const lines = new Set([...m.getClientRects()].map((x) => Math.round(x.top))).size;
    return lines !== 1 || m.scrollWidth > m.clientWidth + 0.5 || r.left < li.left - 0.5 || r.right > li.right + 0.5 || r.height > 20;
  }).map((m) => m.textContent);
  const path = bdi && bdi.parentElement;
  const hint = path && path.parentElement.querySelector('.ev-masked');
  const p = path && path.getBoundingClientRect();
  const h = hint && hint.getBoundingClientRect();
  return { masks: masks.length, bad, cut: path ? path.scrollWidth > path.clientWidth : null,
    below: p && h ? p.top >= h.bottom - 1 : null, doc: document.documentElement.scrollWidth, width: innerWidth };
})()`;

// The open reason form inside its finding and the page: one line from 600px up; below 520px the input
// on its own line and the two buttons under it, at its right edge.
const FORM_FITS = `(() => {
  const form = document.querySelector('#findings-body form.dismiss-form');
  if (!form) return null;
  const box = form.closest('article').getBoundingClientRect();
  const f = form.getBoundingClientRect();
  const input = form.querySelector('.dismiss-reason').getBoundingClientRect();
  const actions = form.querySelector('.dismiss-actions').getBoundingClientRect();
  return { inside: f.left >= box.left - 0.5 && f.right <= box.right + 0.5 && actions.right <= f.right + 0.5,
    oneLine: Math.abs(input.top - actions.top) < 6, stacked: actions.top >= input.bottom - 1 && Math.abs(actions.right - f.right) <= 1,
    input: Math.round(input.width), form: Math.round(f.width), doc: document.documentElement.scrollWidth, width: innerWidth };
})()`;

function maskedRow(key, r) {
  if (!r || r.evalError || !r.masks) {
    return { key, value: r && r.evalError ? 'threw' : 'NO - found no masked hint, so this checked nothing', ok: false, detail: String(r && r.evalError || '') };
  }
  const narrow = r.width < 520;
  const ok = !r.bad.length && r.cut === true && (!narrow || r.below === true) && r.doc <= r.width;
  return { key, value: ok ? 'whole' : 'cut', ok,
    detail: `${r.masks} hints${r.bad.length ? `, not whole: ${r.bad.join(', ')}` : ''}; long path cut from its start ${r.cut}${narrow ? `, under the hint ${r.below}` : ''}; document ${r.doc} of ${r.width}` };
}

/**
 * The per-width spec of the sensitive findings: `findings <w> masked whole` served and exported, and
 * `findings <w> dismiss form fits` served (an export has no form). On [url]'s latest session, which is
 * the probe session and holds the planted values.
 */
async function findingsSensitive(page, url) {
  const rows = [];
  for (const width of SENSITIVE_WIDTHS) {
    await page.setWidth(width);
    await page.navigate('about:blank');
    await page.navigate(`${url}#/findings`);
    const drawn = await page.waitFor(SENSITIVE_DRAWN, 20000);
    await sleep(300);
    rows.push(maskedRow(`findings ${width} masked whole`, drawn === null ? null : await page.ev(MASKED_WHOLE)));
    await page.ev(`document.querySelector('#findings-body .finding-dismiss')?.click()`);
    await sleep(200);
    const r = await page.ev(FORM_FITS);
    const ok = Boolean(r) && !r.evalError && r.inside && r.doc <= r.width && (width < 520 ? r.stacked : r.oneLine);
    rows.push({ key: `findings ${width} dismiss form fits`, ok,
      value: !r ? 'NO - found no dismiss form, so this checked nothing' : r.evalError ? 'threw' : ok ? 'fits' : 'overflows',
      detail: !r || r.evalError ? String(r && r.evalError || '') : `input ${r.input} of ${r.form}; ${width < 520 ? `stacked ${r.stacked}` : `one line ${r.oneLine}`}; inside ${r.inside}; document ${r.doc} of ${r.width}` });
  }
  const dir = scratchDir('sensitive-export-');
  try {
    const file = path.join(dir, 'export.html');
    fs.writeFileSync(file, Buffer.from(await (await fetch(`${url}api/sessions/latest/export.html`)).arrayBuffer()));
    for (const width of SENSITIVE_WIDTHS) {
      await page.setWidth(width);
      await page.navigate('about:blank');
      await page.navigate(`file://${file}#/findings`);
      const drawn = await page.waitFor(SENSITIVE_DRAWN, 20000);
      await sleep(300);
      rows.push(maskedRow(`findings ${width} masked whole export`, drawn === null ? null : await page.ev(MASKED_WHOLE)));
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
  return rows;
}

module.exports = { layout, runToggle, liveArrival, firstRows, findingsSensitive };
