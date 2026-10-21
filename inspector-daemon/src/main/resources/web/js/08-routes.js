/* routes: the rail, its pages and the hash that addresses them */
'use strict';

/**
 * The pages the rail offers, in rail order. `live` is a page that sends something to the daemon or
 * reads the machine it runs on, so an export, which has neither, offers Traffic alone and every
 * other route lands there.
 *
 * Stats, Findings and Diff get their row in the item that builds each page: a rail entry leading to
 * an empty page is a dead end.
 */
const PAGES = [
  { id: 'traffic', label: 'Traffic', live: false },
  { id: 'sessions', label: 'Sessions', live: true },
  { id: 'processes', label: 'Processes', live: true },
  { id: 'settings', label: 'Settings', live: true },
];

/**
 * `#/traffic[/<view>]`, `#/sessions`, `#/processes` or `#/settings`, each optionally ending
 * `?session=<id>`, as `{ page, view, session, known }`. Pure, and the only reader of the hash's
 * shape: nothing else parses `location.hash`.
 *
 * A page this build does not have is Traffic with `known: false`, so the caller rewrites the address
 * rather than showing nothing. A view id is the tab's own `state.view` id, URL-decoded, so there is
 * no table mapping addresses to tabs that could drift from the tabs.
 *
 * `#/txn/<id>` is one call on the whole page, `{ page: 'txn', …, txn }`: the Traffic page with its
 * detail pane given the width (`showTxnFull`), so it is not a rail page and not in `PAGES`. Without
 * an id it names nothing, and is unknown.
 */
function parseRoute(hash) {
  const text = String(hash || '').replace(/^#/, '');
  const at = text.indexOf('?');
  const path = at === -1 ? text : text.slice(0, at);
  const query = new URLSearchParams(at === -1 ? '' : text.slice(at + 1));
  const parts = path.split('/').filter(Boolean).map((part) => {
    try { return decodeURIComponent(part); } catch { return part; }
  });
  const session = query.get('session') || null;
  const page = parts[0] || 'traffic';
  if (page === 'txn' && parts[1]) return { page: 'txn', view: null, session, known: true, txn: parts[1] };
  if (!PAGES.some((p) => p.id === page)) return { page: 'traffic', view: null, session, known: false };
  const view = page === 'traffic' && parts[1] ? parts[1] : null;
  return { page, view, session, known: true };
}

/** The address of [route]; `parseRoute(routeHash(r))` gives back `r`. Pure. */
function routeHash({ page = 'traffic', view = null, session = null, txn = null } = {}) {
  const path = page === 'txn' ? `#/txn/${encodeURIComponent(txn)}`
    : page === 'traffic' && view ? `#/traffic/${encodeURIComponent(view)}` : `#/${page}`;
  return session ? `${path}?session=${encodeURIComponent(session)}` : path;
}

/** The page on screen, which `applyRoute` set; Traffic before it has run, and under `#/txn/<id>`. */
const currentPage = () => document.body.dataset.page || 'traffic';

/** The rail page a route is drawn on: a call's own page is the Traffic page. */
const pageOf = (route) => (route.page === 'txn' ? 'traffic' : route.page);

/**
 * Goes to [route]. A page or a tab pushes a history entry, so Back walks the pages; `replace` is for
 * a session switch, because Back walks places, not data, and a pushed switch would make Back re-run
 * a whole-session read. A pushed entry never carries the session it was pushed from, for the same
 * reason: going Back to it must not switch sessions.
 */
async function navigate(route, { replace = false } = {}) {
  const hash = routeHash(route);
  const changesPage = pageOf(route) !== currentPage();
  if (hash !== location.hash) writeHash(hash, replace);
  await applyRoute();
  // A person who chose a page is taken to its heading, as a link to a new document would.
  if (changesPage && route.page !== 'traffic') $(`page-${route.page}`)?.querySelector('.page-title')?.focus();
}

/** Rewrites the address in place, with no history entry and nothing re-applied. */
function replaceRoute(route) {
  const hash = routeHash(route);
  if (hash !== location.hash) writeHash(hash, true);
}

/**
 * Writes [hash] to the address, pushed or in place. The history API refuses a `file:` page in some
 * engines, and an export is a file opened from disk, so there the address goes through `location`
 * instead. That fires `hashchange`, which applies the same route a second time, to no effect.
 */
function writeHash(hash, replace) {
  try {
    history[replace ? 'replaceState' : 'pushState'](null, '', hash);
  } catch {
    if (replace) location.replace(hash);
    else location.hash = hash;
  }
}

/**
 * Shows what the address names: the page, then on Traffic the session and the view.
 *
 * Unknown, or a live page in an export, is Traffic, and the address is rewritten to say so. A
 * session other than the one shown is switched to through `switchSession`, as the picker does, so
 * every clear a switch makes runs for an address too. Before the session list has loaded it only
 * chooses which session the first load reads. A view the session offers is shown; one it does not
 * rewrites the address to `#/traffic`, but only once its signals have loaded, since before then a
 * tag's view such as `cache` is not a tab yet.
 */
async function applyRoute() {
  let route = parseRoute(location.hash);
  const page = PAGES.find((p) => p.id === pageOf(route));
  if (!route.known || (ARCHIVE && page.live)) {
    route = { page: 'traffic', view: null, session: route.session };
    replaceRoute(route);
  }
  const entering = pageOf(route) !== currentPage() || !document.body.dataset.page;
  document.body.dataset.page = pageOf(route);
  for (const p of PAGES) $(`page-${p.id}`).hidden = p.id !== pageOf(route);
  renderRail();
  if (entering) enterPage(pageOf(route));

  if (route.session && route.session !== state.sessionId) {
    if (!state.sessionsLoaded) state.sessionId = route.session;
    else if (state.sessions.some((s) => s.sessionId === route.session)) await switchSession(route.session);
  }
  await showTxnFull(route.page === 'txn' ? route.txn : null);
  if (route.page !== 'traffic' || !route.view || route.view === state.view) return;
  if (availableViews().some((v) => v.id === route.view)) await selectView(route.view);
  else if (state.sessionId && state.signalSessionId === state.sessionId) {
    replaceRoute({ page: 'traffic', session: route.session });
  }
}

/** What a page reads when it is opened; Traffic is kept current by the live socket instead. */
function enterPage(page) {
  if (ARCHIVE) return;
  if (page === 'sessions') loadSessions().then(renderSessionRows).catch(() => renderSessionRows());
  if (page === 'processes') {
    loadPeers();
    loadMcp();
    loadServerInfo();
  }
  if (page === 'settings') {
    applyDuplicateWindow(state.duplicateWindowMs);   // refreshes the count for this session
    applyEndpointLimit(state.endpointLimit);
    loadConfig();
  }
}

/**
 * The rail's links, from `PAGES`: each one's address, its name for a reader when only the icon is
 * drawn, which one is current, and which an export hides (`data-live`, read by `body.archive`).
 */
function renderRail() {
  const page = currentPage();
  for (const p of PAGES) {
    const link = document.querySelector(`#rail .rail-item[data-page="${p.id}"]`);
    if (!link) continue;
    link.href = routeHash({ page: p.id });
    link.title = p.label;
    link.setAttribute('aria-label', p.label);
    if (p.live) link.dataset.live = '';
    if (p.id === page) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  }
}

/**
 * Labelled or icons only. With no choice saved the width decides (the stylesheet: labelled from
 * 1200px); a choice, `open` or `icons`, is kept in this browser and wins at every width.
 */
function setRailMode(mode) {
  const rail = $('rail');
  if (mode === 'open' || mode === 'icons') prefs.set('inspector.rail', mode);
  const saved = prefs.get('inspector.rail');
  rail.classList.toggle('rail-open', saved === 'open');
  rail.classList.toggle('rail-icons', saved === 'icons');
}

/** The toggle flips whatever the rail is drawing now, whether a choice or the width decided it. */
function toggleRail() {
  const labelled = $('rail').getBoundingClientRect().width > 100;
  setRailMode(labelled ? 'icons' : 'open');
  const toggle = $('rail-toggle');
  const label = labelled ? 'Show page names' : 'Hide page names';
  toggle.setAttribute('aria-label', label);
  toggle.title = label;
}
