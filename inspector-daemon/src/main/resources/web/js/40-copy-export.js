/* copy and export: agent bundles, copy buttons, menus and exports */
'use strict';

// --- handing a finding to an agent --------------------------------------

/*
 * You spot something here; the agent that can act on it is in your editor. Today the handover
 * is a sentence — "the failing POST to /oauth/token around 15:13" — and the agent has to go
 * find what you were already looking at, which it may or may not land on.
 *
 * These build a paste. Not a screenshot and not a raw dump: an unambiguous identifier, enough
 * context to answer the obvious question without a round trip, and the exact MCP calls that
 * fetch the rest. An agent with the inspector server registered can go deeper; one without it
 * still has the facts.
 */

const BUNDLE_BODY_CAP = 4000;
const BUNDLE_NEAR_MS = 10_000;
/**
 * Per-header cap.
 *
 * Wide enough for every ordinary header and narrow enough to clip a bearer token, which on a
 * real call ran 1300 characters — a quarter of the paste spent on a value no agent reads, and
 * a live credential dropped into a chat log on the way. The length is still reported, so
 * "was a token sent, and did it change between these two calls" stays answerable.
 */
const BUNDLE_HEADER_CAP = 120;

/** `mono` is the device's clock. Offsets from a chosen origin are the only readable form. */
const relMs = (mono, origin) => {
  const delta = mono - origin;
  const sign = delta < 0 ? '-' : '+';
  return `${sign}${fmtMs(Math.abs(delta))}`;
};

const clip = (text, cap) =>
  text.length <= cap ? text : `${text.slice(0, cap)}\n… truncated at ${cap} of ${text.length} chars`;

const clipHeader = (value) =>
  value.length <= BUNDLE_HEADER_CAP
    ? value
    : `${value.slice(0, BUNDLE_HEADER_CAP)}… [${value.length} chars]`;

/** The screen that was showing when something happened, and how long it had been showing. */
function screenAt(mono) {
  const arrivals = state.screens
    .filter((s) => s.mono <= mono)
    .sort((a, b) => b.mono - a.mono);
  return arrivals[0] || null;
}

function bundleHeader() {
  const meta = state.sessions.find((s) => s.sessionId === state.sessionId);
  const lines = [`# Inspector session ${state.sessionId}`];
  if (meta) lines.push(`${meta.appId} · ${meta.device} · ${meta.buildType || 'debug'}`);
  return lines;
}

function nearbyLines(mono) {
  const near = [
    ...state.allSignals.map((s) => ({ mono: s.mono, text: `${s.tag} ${s.name}` })),
    ...state.markers.map((m) => ({ mono: m.mono, text: `marker "${m.label}"` })),
    ...state.allTransactions
      .filter((t) => t.mono !== mono)
      .map((t) => ({ mono: t.mono, text: `${t.method} ${t.path} → ${t.status ?? 'ERR'} ${fmtMs(t.ms)}` })),
  ]
    .filter((e) => Math.abs(e.mono - mono) <= BUNDLE_NEAR_MS)
    .sort((a, b) => a.mono - b.mono);
  // Bounded: a busy ten seconds can hold hundreds of state observations, and a paste nobody
  // reads to the end is worse than a shorter one that is all signal.
  const capped = near.length > 30
    ? [...near.slice(0, 15), { elided: near.length - 30 }, ...near.slice(-15)]
    : near;
  // The elision carries no offset. Giving it one — even the anchor's own — reads as an event
  // that happened at that instant, which is the one thing it is not.
  return capped.map((e) =>
    e.elided ? `${' '.repeat(10)}… ${e.elided} more` : `${relMs(e.mono, mono).padStart(8)}  ${e.text}`);
}

/** Everything an agent needs about one call, and where to get what is not here. */
async function transactionBundle(txn) {
  const [reqBody, resBody] = await Promise.all([fetchBody(txn, 'req'), fetchBody(txn, 'res')]);
  const screen = screenAt(txn.mono);
  const lines = [
    ...bundleHeader(),
    '',
    '## The call',
    `id ${txn.id}`,
    `${txn.method} ${urlOf(txn)}`,
    `${txn.status ?? 'transport failure'} · ${fmtMs(txn.ms)} · req ${fmtBytes(txn.reqBytes)} · res ${fmtBytes(txn.resBytes)}`,
    `started ${txn.ts}`,
  ];
  if (txn.error) lines.push(`error ${txn.error}`);
  if (attemptOf(txn) > 1) lines.push(`attempt ${attemptOf(txn)} of this call`);
  if (txn.redacted && txn.redacted.length) {
    // Say it was removed, not that it was absent, or the agent concludes none was sent.
    lines.push(`redacted at capture: ${txn.redacted.join(', ')}`);
  }
  if (screen) {
    lines.push(`screen at the time: ${screen.name} (arrived ${fmtMs(txn.mono - screen.mono)} earlier)`);
  }

  for (const [title, side, body] of [['Request', 'req', reqBody], ['Response', 'res', resBody]]) {
    const headers = Object.entries(side === 'req' ? txn.reqHeaders || {} : txn.resHeaders || {});
    lines.push('', `### ${title} headers`);
    lines.push(
      headers.length
        ? headers.map(([k, v]) => `${k}: ${clipHeader(v.join(', '))}`).join('\n')
        : 'none',
    );
    lines.push('', `### ${title} body`);
    const absent = bodyAbsenceReason(txn, side, body);
    lines.push(absent !== null ? absent : clip(body, BUNDLE_BODY_CAP));
  }

  lines.push('', `## Around it (±${fmtMs(BUNDLE_NEAR_MS)})`);
  for (const line of nearbyLines(txn.mono)) lines.push(line);
  lines.push(
    '',
    '## Read more (MCP server "inspector")',
    'Long header values above are clipped; get_transaction returns them whole.',
    `get_transaction(session: "${state.sessionId}", id: "${txn.id}")`,
    `get_body(session: "${state.sessionId}", id: "${txn.id}", side: "res")`,
    `timeline(session: "${state.sessionId}", limit: 60)`,
  );
  return lines.join('\n');
}

/**
 * The session as a whole — or, when the axis has narrowed it, the stretch on screen.
 *
 * Honouring the brush is the point. Handing over the whole session when you have spent a minute
 * narrowing to the ten seconds that matter throws away the work you just did.
 */
function sessionBundle() {
  const txns = visibleTransactions();
  const failed = txns.filter((t) => t.error || (t.status ?? 0) >= 400);
  const lines = [...bundleHeader()];

  if (state.timeRange) {
    lines.push(`narrowed to a ${fmtMs(state.timeRange.to - state.timeRange.from)} stretch of it`);
  }
  // Unlike the panel this button sits in, the bundle is "what I am looking at" and so keeps the
  // text filter too. Stated rather than silent: counts below are of the filtered set.
  if (state.filter) lines.push(`filter applied: ${state.filter}`);
  lines.push('', `${txns.length} calls, ${failed.length} failed`);

  const hosts = new Map();
  for (const txn of txns) hosts.set(txn.host, (hosts.get(txn.host) || 0) + 1);
  for (const [host, n] of [...hosts].sort((a, b) => b[1] - a[1])) lines.push(`host ${host} · ${n}`);

  if (failed.length) {
    lines.push('', '## Failed');
    for (const txn of failed.slice(0, 20)) {
      lines.push(`${txn.id}  ${txn.method} ${txn.path} → ${txn.status ?? 'ERR'} ${txn.error || ''}`.trimEnd());
    }
  }

  const slowest = [...txns].filter((t) => t.ms != null).sort((a, b) => b.ms - a.ms).slice(0, 5);
  if (slowest.length) {
    lines.push('', '## Slowest');
    for (const txn of slowest) lines.push(`${fmtMs(txn.ms).padStart(7)}  ${txn.id}  ${txn.method} ${txn.path}`);
  }

  const groups = waterfallGroups();
  if (groups.length) {
    lines.push('', '## By screen');
    for (const group of groups) {
      lines.push(
        `${group.segment.name || 'before the first screen'} — ${group.rows.length} calls, ` +
        `${fmtMs(group.wall)}, ${fmtBytes(group.bytes)}${group.failed ? `, ${group.failed} failed` : ''}`,
      );
    }
  }

  const current = state.current.filter((s) => tagOf(s) !== 'state').slice(0, 12);
  if (current.length) {
    lines.push('', '## What the app holds now');
    for (const signal of current) {
      lines.push(`${signal.tag} ${signal.name} · ${signal.trigger === 'request' ? 'pulled' : 'pushed'} ${fmtAge(ageOf(signal.ts))}`);
    }
  }

  lines.push(
    '',
    '## Read more (MCP server "inspector")',
    `session_summary(session: "${state.sessionId}")`,
    `list_transactions(session: "${state.sessionId}", filter: "has:error")`,
    `timeline(session: "${state.sessionId}", limit: 60)`,
  );
  return lines.join('\n');
}

/**
 * Copy, and say so on the button itself.
 *
 * `navigator.clipboard` is unavailable on a page served over plain http from anything but
 * localhost, and it rejects rather than throwing — a silent failure that looks exactly like a
 * successful copy. The button reports it.
 */
/**
 * A copy button for one value.
 *
 * The UI could copy a whole cURL command and a whole AI bundle, and could not copy one header
 * value — so getting a bearer token into another terminal meant selecting it by hand out of a
 * monospace block that wraps, which is where the ends get clipped and nobody notices until the
 * request 401s. The overlay has had per-field copy since the first device outing; this is the
 * same affordance.
 *
 * Always in the DOM rather than created on hover, so it is reachable by keyboard and by a
 * screen reader. It is CSS that keeps it quiet until the row is hovered or the button focused.
 *
 * @param what names the value in the confirmation, because several of these sit close together
 *   and a bare "copied" does not say which one took.
 */
function copyButton(text, what) {
  const button = el('button', 'copy-field', '⧉');
  button.type = 'button';
  button.title = `Copy ${what}`;
  button.setAttribute('aria-label', `Copy ${what}`);
  button.addEventListener('click', (event) => {
    event.stopPropagation();
    navigator.clipboard.writeText(text).then(
      () => {
        button.classList.add('done');
        button.textContent = '✓';
        setTimeout(() => { button.classList.remove('done'); button.textContent = '⧉'; }, 1200);
      },
      () => {
        button.classList.add('bad');
        button.title = 'The clipboard is unavailable here. Open the UI on 127.0.0.1 rather than a LAN address.';
      },
    );
  });
  return button;
}

/** The value, with its copy button beside it. */
function copyableRow(cls, text, what) {
  const line = el('div', `copyable ${cls}`);
  line.appendChild(el('span', 'copyable-value', text));
  line.appendChild(copyButton(text, what));
  return line;
}

function copyBundle(button, build, label) {
  button.disabled = true;
  Promise.resolve()
    .then(build)
    .then((text) => navigator.clipboard.writeText(text).then(() => text))
    .then((text) => {
      button.textContent = `copied ${fmtBytes(text.length)}`;
    })
    .catch(() => {
      button.textContent = 'copy failed';
      button.title = 'The clipboard is unavailable here. Open the UI on 127.0.0.1 rather than a LAN address.';
    })
    .finally(() => {
      setTimeout(() => { button.textContent = label; button.disabled = false; }, 1600);
    });
}

/**
 * A button that opens a small menu under itself.
 *
 * Shared by the detail pane's replay menu and the top bar's export menu, so both close the same
 * ways: on a second click, a click outside, choosing an item, or Escape. Escape stops there —
 * the page's own Escape clears the selection, and a bubbling one closed the menu *and* the
 * whole detail pane.
 *
 * @param onOpen runs before the menu shows, for items whose targets depend on the moment.
 */
function menuButton({ label, className, title, items, onOpen, alignLeft = false }) {
  const button = el('button', className, label);
  button.type = 'button';
  button.title = title;
  button.setAttribute('aria-haspopup', 'menu');
  button.setAttribute('aria-expanded', 'false');
  const menu = el('div', alignLeft ? 'menu menu-left' : 'menu');
  menu.setAttribute('role', 'menu');
  menu.hidden = true;
  const close = () => {
    menu.hidden = true;
    button.setAttribute('aria-expanded', 'false');
    document.removeEventListener('click', onOutside, true);
  };
  const onOutside = (event) => {
    if (!menu.contains(event.target) && event.target !== button) close();
  };
  for (const item of items) {
    item.addEventListener('click', close);
    menu.appendChild(item);
  }
  menu.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') { event.stopPropagation(); close(); button.focus(); }
  });
  button.onclick = () => {
    if (!menu.hidden) { close(); return; }
    if (onOpen) onOpen();
    menu.hidden = false;
    button.setAttribute('aria-expanded', 'true');
    // The first item drawn: `⋮` hides its exports in an export and with no session.
    const items = [...menu.querySelectorAll('.menu-item')];
    (items.find((i) => !i.hidden && getComputedStyle(i).display !== 'none') || items[0]).focus();
    document.addEventListener('click', onOutside, true);
  };
  const wrap = el('span', 'menu-wrap');
  wrap.appendChild(button);
  wrap.appendChild(menu);
  return { wrap, button, menu, close };
}

function menuItem(label, title, run) {
  const node = el('button', 'menu-item', label);
  node.type = 'button';
  node.title = title;
  node.setAttribute('role', 'menuitem');
  node.addEventListener('click', run);
  return node;
}

/**
 * The session, out of the daemon as one file.
 *
 * Links rather than buttons that fetch: the daemon names each file in `Content-Disposition`, so
 * the browser saves it under the session's name with nothing built in the page. Hrefs are set
 * as the menu opens, because the selected session changes under a menu built once.
 */
const EXPORTS = [
  { label: 'HTML — this page, offline', ext: 'html', path: 'export.html',
    title: 'One file that opens in any browser with no daemon: this UI, with the whole session inside it' },
  { label: 'Markdown — readable', ext: 'md', path: 'export.md',
    title: 'The timeline, then every call with its request and response in full — for a ticket, a chat or an agent' },
  { label: 'JSON — complete', ext: 'json', path: 'export.json',
    title: 'Every row, body and signal payload in one document' },
  { label: 'HAR — for DevTools', ext: 'har', path: 'har',
    title: 'Calls only, in the format DevTools, Charles and Postman import. Fields capture never measured are -1' },
];

/**
 * `⋮`, the toolbar's last control: the four exports, the shortcut legend and compact rows — what
 * is used rarely enough to sit behind one button, so the toolbar fits one row at 1000px. The
 * exports moved here from the top bar. An export of nothing would be a 404 with a filename, so they
 * are offered only with a session; in an export they are built and hidden by `body.archive`, as
 * every control that would send something to a daemon is.
 */
function wireMoreMenu() {
  const links = EXPORTS.map((spec) => {
    const link = el('a', 'menu-item export-item', spec.label);
    link.title = spec.title;
    link.dataset.export = spec.ext;
    link.setAttribute('role', 'menuitem');
    link.setAttribute('download', '');
    return link;
  });
  const keys = menuItem('keyboard shortcuts', 'The keys that move through and act on the list', showKeys);
  keys.id = 'keys-item';
  const density = menuItem('compact rows', 'Fit more calls on screen; the text stays the same size',
    () => setDensity(state.density === 'compact' ? 'comfortable' : 'compact'));
  density.id = 'density-item';
  density.setAttribute('role', 'menuitemcheckbox');
  const more = menuButton({
    label: '⋮',
    className: 'btn btn-sm btn-quiet more-button',
    title: 'Export this session, keyboard shortcuts, row density',
    items: [...links, keys, density],
    onOpen: () => {
      const id = encodeURIComponent(state.sessionId || '');
      EXPORTS.forEach((spec, i) => {
        links[i].href = `/api/sessions/${id}/${spec.path}`;
        links[i].hidden = !state.sessionId;
      });
    },
  });
  more.button.id = 'more-button';
  more.button.setAttribute('aria-label', 'More: exports, keyboard shortcuts, row density');
  more.wrap.id = 'more-menu';
  $('more-host').replaceChildren(more.wrap);
  paintDensity();
}

function bundleButton(label, build, title) {
  const button = el('button', 'btn btn-sm', label);
  button.title = title;
  button.addEventListener('click', (event) => {
    event.stopPropagation();
    copyBundle(button, build, label);
  });
  return button;
}
