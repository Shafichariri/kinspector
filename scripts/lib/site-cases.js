'use strict';
/*
 * The landing page in Chrome (group `site` in scripts/measure-web-ui.js): `site/index.html` opened
 * from disk, with no daemon, so CI's `web` job runs it before Gradle on the runner's own Chrome.
 *
 * Judged, at 400 px: the document is no wider than the window (`site 400 document width`); no
 * element outside a scroll container ends past the window's right edge (`site 400 past the edge`);
 * and every bullet of every card ends inside the window (`site 400 card bullets`). The page was 605
 * px wide at 400 because the `.step` and `.grid2` grid items kept `min-width: auto`, so a 531 px
 * `pre` and a 454 px `.term`, each meant to scroll in its own box, set their column's width
 * instead. A diagram's 640 px SVG does not count: `.diagram-box` scrolls it.
 *
 * Not judged, at 1440: the document's size and a digest of every element's box (`site 1440
 * layout`). With --base, a value that moved is printed, which is how a fix at 400 is shown to
 * narrow nothing that fits at 1440.
 */
const path = require('path');
const { pathToFileURL } = require('url');
const { sleep } = require('./measure-cdp');

// What a 400 px window holds. Elements inside a box that clips or scrolls sideways are that box's
// business; the box itself is still measured. html and body are the viewport's scroller, so the
// walk up stops below them, or nothing would ever count as past the edge.
const MEASURE = `(() => {
  const w = window.innerWidth;
  const clipped = (e) => {
    for (let a = e.parentElement; a && a !== document.body && a !== document.documentElement; a = a.parentElement) {
      if (getComputedStyle(a).overflowX !== 'visible') return true;
    }
    return false;
  };
  const name = (e) => e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (e.classList.length ? '.' + [...e.classList].join('.') : '');
  const past = [];
  for (const e of document.body.querySelectorAll('*')) {
    const r = e.getBoundingClientRect();
    if (!r.width && !r.height) continue;
    if (r.right > w + 0.5 && !clipped(e)) past.push(name(e) + ' ' + Math.round(r.right));
  }
  const bullets = [...document.querySelectorAll('.card li')];
  const cut = bullets.filter((li) => li.getBoundingClientRect().right > w + 0.5);
  return { w, doc: document.documentElement.scrollWidth, past, bullets: bullets.length,
    cut: cut.map((li) => li.textContent.trim().slice(0, 40) + ' ' + Math.round(li.getBoundingClientRect().right)) };
})()`;

// Every element's box, rounded to a pixel, folded into one number: two trees whose 1440 layouts
// match to the pixel print the same value. Shapes inside an SVG are left out, its own box is not:
// the diagram's pulses move along their wires, so with them two loads of one file disagreed.
const DIGEST = `(() => {
  let h = 0;
  for (const e of document.querySelectorAll('*')) {
    if (e.ownerSVGElement) continue;
    const r = e.getBoundingClientRect();
    for (const v of [r.left, r.top, r.width, r.height]) h = (Math.imul(h, 31) + Math.round(v)) | 0;
  }
  return { size: document.documentElement.scrollWidth + 'x' + document.documentElement.scrollHeight,
    digest: (h >>> 0).toString(16), count: document.querySelectorAll('*').length };
})()`;

async function load(page, url, width) {
  await page.setWidth(width);
  await page.navigate('about:blank');
  await page.navigate(url);
  if (await page.waitFor(`document.readyState === 'complete' && document.querySelector('.card li')`, 20000) === null) {
    throw new Error(`${url} did not load within 20 s`);
  }
  await settle(page);
}

// `complete` covers the images; a font can still swap in after it and move the layout.
async function settle(page) {
  await page.waitFor(`document.fonts.status === 'loaded'`, 5000);
  await sleep(300);
}

async function landingFits(page, tree) {
  const url = pathToFileURL(path.join(tree, 'site/index.html')).href;
  const rows = [];

  await load(page, url, 400);
  const m = await page.ev(MEASURE);
  if (!m || m.evalError) throw new Error(`site 400: ${m ? m.evalError : 'no reading'}`);
  rows.push({ key: 'site 400 document width', value: `${m.doc} px in ${m.w}`, ok: m.doc <= m.w });
  rows.push({ key: 'site 400 past the edge', value: `${m.past.length} elements`, ok: m.past.length === 0,
    detail: m.past.slice(0, 6).join(', ') || undefined });
  // No bullets at all would pass vacuously, so a renamed class fails here instead.
  rows.push({ key: 'site 400 card bullets', value: `${m.cut.length} of ${m.bullets} past the edge`,
    ok: m.bullets > 0 && m.cut.length === 0, detail: m.cut.slice(0, 4).join('; ') || undefined });

  await load(page, url, 1440);
  const d = await page.ev(DIGEST);
  if (!d || d.evalError) throw new Error(`site 1440: ${d ? d.evalError : 'no reading'}`);
  rows.push({ key: 'site 1440 layout', value: `${d.size}, ${d.count} boxes, digest ${d.digest}`, ok: null });
  return rows;
}

module.exports = { landingFits };
