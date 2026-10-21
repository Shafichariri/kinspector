/* navigation: tabs, views, the drawer and pop-overs */
'use strict';

// --- tabs ---------------------------------------------------------------

/**
 * Tags Inspector has an opinion about, in the order they are worth reading.
 *
 * The list is a *preference*, not a filter. A tag this build has never heard of still gets a
 * tab — the schema says tags are app-defined, and a view that silently drops one would make
 * Inspector lie about what the app recorded.
 */
const TAB_ORDER = ['cache', 'screen', 'state'];
const TAG_LABELS = { screen: 'screens' };

// @mirror-begin tagOf
const tagOf = (signal) => String(signal.tag).toLowerCase();
// @mirror-end tagOf
const signalsForTag = (tag) => state.allSignals.filter((signal) => tagOf(signal) === tag);

function tagsInSession() {
  const tags = [...new Set(state.allSignals.map(tagOf))];
  const rank = (tag) => {
    const at = TAB_ORDER.indexOf(tag);
    return at === -1 ? TAB_ORDER.length : at;
  };
  return tags.sort((a, b) => rank(a) - rank(b) || a.localeCompare(b));
}

/** Every view this session can offer, in tab order. */
function availableViews() {
  const views = [];
  // Traffic first, and it is where a session opens. This is a network debugger: in a real
  // session the merged timeline ran 126 rows of which 13 were calls, so landing there puts
  // what you came for at one row in ten. The merge is a correlation tool you reach for once
  // you know which call you care about, which makes it the second tab, not the first.
  views.push({ id: 'network', label: 'traffic', title: 'HTTP calls', count: state.transactions.length });
  // Always offered, even with no screen signals: a tab that came and went between sessions moved
  // everything after it, and a session with no screens still has a path — one honest column
  // saying so, which is also the only place that tells you how to get more.
  views.push({
    id: 'path',
    label: 'path',
    title: 'The screens the user moved through, and what happened on each',
  });
  // Merging is only worth a tab when there is something to merge with the traffic.
  if (state.allSignals.length) {
    views.push({
      id: 'all',
      label: 'timeline',
      title: 'Traffic, signals and markers on one timeline',
    });
  }
  // Grouping by screen needs both halves. With traffic but no screen signals it would be one
  // unnamed block, which the traffic tab already draws better. Asked of the whole session, not
  // the filtered calls: once the route honoured `filter`, `tag:screen` matched no calls and took
  // the tab away mid-filter. The waterfall says itself that no calls match the filter.
  if (state.allTransactions.length && state.screens.length) {
    views.push({
      id: 'waterfall',
      label: 'waterfall',
      title: 'Calls grouped by the screen that was showing when they started',
    });
  }
  for (const tag of tagsInSession()) {
    views.push({
      id: tag,
      label: TAG_LABELS[tag] || tag,
      title: `${tag} signals recorded by the app`,
      count: signalsForTag(tag).length,
    });
  }
  return views;
}

/** The views that arrange the traffic, as opposed to browsing one signal tag. */
const TRAFFIC_VIEWS = ['network', 'path', 'all', 'waterfall'];

function renderTabs() {
  const bar = $('tabs');
  const views = availableViews();
  // Always drawn, even with one tab. Hiding it when a session had no signals made the bar appear
  // and disappear between sessions — and moved everything under it — for no change in what the
  // page could do. A lone `traffic` tab also says, correctly, that nothing else was recorded.
  bar.hidden = false;
  bar.innerHTML = '';
  // Signal tabs are set apart from the traffic views, because they read differently: the
  // traffic views are three arrangements of one session, the rest are one tag each.
  const firstSignal = views.find((v) => !TRAFFIC_VIEWS.includes(v.id));
  for (const view of views) {
    const tab = el('button', view === firstSignal ? 'tab tab-signal-first' : 'tab');
    tab.dataset.view = view.id;
    tab.title = view.title;
    tab.appendChild(el('span', 'tab-label', view.label));
    if (view.count !== undefined) tab.appendChild(el('span', 'tab-count muted mono', String(view.count)));
    tab.classList.toggle('active', view.id === state.view);
    // Through the address, so a tab is a place Back returns to.
    tab.addEventListener('click', () => navigate({ page: 'traffic', view: view.id }));
    bar.appendChild(tab);
  }
  bar.appendChild(viewsMenu(views));
}

/**
 * `views ▾`: every view and its count, one control, for a bar too narrow to draw every tab. The
 * stylesheet shows it, and only the active tab beside it, below 700px; above, it is not drawn. A
 * rule rather than a measured overflow, because a rule can be read and jsdom cannot measure.
 * Built from the same `availableViews()` as the tabs, so a tag's view is never out of reach.
 */
function viewsMenu(views) {
  const items = views.map((view) => {
    const item = menuItem(view.count === undefined ? view.label : `${view.label}  ${view.count}`, view.title,
      () => navigate({ page: 'traffic', view: view.id }));
    item.dataset.view = view.id;
    if (view.id === state.view) item.setAttribute('aria-current', 'true');
    return item;
  });
  const menu = menuButton({
    label: 'views ▾',
    className: 'tabs-more-btn',
    title: 'Every view this session offers',
    items,
    alignLeft: true,
  });
  menu.wrap.id = 'tabs-more';
  menu.wrap.classList.add('tabs-more');
  return menu.wrap;
}

async function selectView(id) {
  if (state.view !== id) {
    state.browserKey = null;
    state.browserObservation = null;
    state.browserFilter = '';
    state.browserFacets = {};
    const input = $('browser-filter');
    if (input) input.value = '';
  }
  state.view = id;
  // A cache key only exists inside a payload, so the browser cannot draw its list without
  // them. Fetched on the way in rather than for every session that happens to hold cache rows.
  if (id === 'cache') await loadPayloadsFor('cache');
  if (id === 'path') await loadScreenPayloads();
  renderTabs();
  applyView();
  // The address says which view is shown, whoever chose it: a tab goes through `navigate`, which
  // pushed it already, and a direct call such as a measuring case is written in place.
  if (currentPage() === 'traffic' && parseRoute(location.hash).view !== id) {
    replaceRoute({ ...parseRoute(location.hash), page: 'traffic', view: id });
  }
}


// --- timeline -----------------------------------------------------------

/** Tags with a lane of their own. Anything else shares the generic one. */
const KNOWN_TAGS = ['screen', 'state', 'cache', 'session'];
const laneFor = (tag) =>
  KNOWN_TAGS.includes(String(tag).toLowerCase()) ? String(tag).toLowerCase() : 'other';

function applyView() {
  const all = state.view === 'all';
  const network = state.view === 'network';
  const waterfall = state.view === 'waterfall';
  const path = state.view === 'path';
  const browsing = !all && !network && !waterfall && !path;

  $('list').hidden = !network;
  $('list-empty').hidden = !network || state.transactions.length > 0;
  renderListNote();
  if ($('timeline').hidden && all) state.timelineFresh = true;
  $('timeline').hidden = !all;
  $('timeline-empty').hidden = true;
  $('waterfall').hidden = !waterfall;
  $('waterfall-empty').hidden = true;
  if ($('path').hidden && path) state.pathFresh = true;
  $('path').hidden = !path;
  if (!path) $('path-new').hidden = true;
  // A path has one true order. Newest-first would read "the user went from C to B to A", so the
  // control is hidden here rather than left on screen doing nothing.
  $('order-seg').hidden = path;
  $('browser').hidden = !browsing;
  for (const tab of $('tabs').querySelectorAll('.tab')) {
    tab.classList.toggle('active', tab.dataset.view === state.view);
  }
  // The browser carries its own detail panel, so the transaction one would be a second, empty
  // detail column. A class rather than `hidden`, because `hidden` loses to any author rule that
  // sets `display` — the bug this pane already shipped once.
  $('panes').classList.toggle('panes-wide', browsing);

  // The toolbar filters transactions, which is what both of these views are built from. The tag
  // browser filters payload keys instead and carries its own toolbar for it, so showing this one
  // there would put two filter boxes on screen that mean different things.
  // The waterfall is built from transactions, so the call filter applies to it as much as to
  // the list. Only the tag browsers, which filter payload keys, get their own toolbar instead.
  $('toolbar').hidden = browsing;
  if (browsing) closePops();
  // The drawer covers the list it was opened from. Carrying it across a tab switch would leave
  // it sitting over a list that never produced it.
  closeDrawer();
  // Nothing selected means the pane is free to say something useful about the session instead.
  if (!browsing && $('detail').hidden) renderSessionGlance();
  // `Now` is the app's current state, which is only ever read beside the merged timeline.
  $('now-strip').hidden = !all || state.current.length === 0;
  renderAxis();

  if (all) renderTimeline();
  if (waterfall) renderWaterfall();
  if (path) renderPath();
  if (browsing) renderBrowser();
}

/**
 * The detail pane, when it is a drawer rather than a column.
 *
 * The class is set at every width and only means anything under the media query, so there is no
 * breakpoint to track in JS and nothing to re-synchronise on resize: drag the window wider and
 * the drawer is simply a column again, still showing what it was showing.
 */
function openDrawer() {
  $('panes').classList.add('drawer-open');
  $('drawer-scrim').hidden = false;
}

function closeDrawer() {
  $('panes').classList.remove('drawer-open');
  $('drawer-scrim').hidden = true;
}

/**
 * Markers, the endpoint chips, the now list and the shortcut legend, one click away.
 *
 * Markers and shortcuts were permanent rail sections and both sat below the fold, which is the
 * worst of both: space spent, and not readable anyway. A popover costs a click and is the first
 * time the shortcut list has been visible at all. The legend opens from the `⋮` menu, not a button
 * of its own, so it is closed with the rest and opened by `showKeys`.
 */
function closePops() {
  for (const [button, pop] of POPS) {
    $(pop).hidden = true;
    $(button).setAttribute('aria-expanded', 'false');
  }
  $('keys-pop').hidden = true;
}

/** The shortcut legend, under the `⋮` button it was chosen from. */
function showKeys(event) {
  // The menu item's click would reach the document and close the legend it just opened.
  event.stopPropagation();
  closePops();
  $('keys-pop').style.top = `${$('more-button').getBoundingClientRect().bottom + 4}px`;
  $('keys-pop').hidden = false;
}

// `[button, pop, anchored]`: an anchored pop is placed under its button by the stylesheet, inside a
// positioned parent, so it takes no offset from script.
const POPS = [
  ['markers-button', 'markers-pop'],
  ['endpoints-button', 'endpoint-chips', true],
  ['now-toggle', 'current', true],
];

function wirePops() {
  for (const [button, pop, anchored] of POPS) {
    $(button).addEventListener('click', (event) => {
      event.stopPropagation();
      const open = $(pop).hidden;
      closePops();
      $(pop).hidden = !open;
      $(button).setAttribute('aria-expanded', String(open));
      if (open && !anchored) {
        // Anchored under its own button rather than at a fixed offset, so it stays put when
        // the chips wrap and move the button to a second row.
        const rect = $(button).getBoundingClientRect();
        $(pop).style.top = `${rect.bottom + 4}px`;
      }
    });
  }
  document.addEventListener('click', closePops);
  for (const pop of [...POPS.map(([, id]) => id), 'keys-pop']) {
    $(pop).addEventListener('click', (event) => event.stopPropagation());
  }
}

// --- the divider and the detail column --------------------------------------

/**
 * The list's share of `#panes` beside the detail column, in percent. The default is the
 * `1fr : 1.1fr` split the grid had before there was a divider.
 */
const LIST_SHARE = { min: 25, max: 75, step: 2, initial: 47.6 };

/**
 * Sets the list's share, clamped, and keeps it in this browser; `null` resets it and forgets the
 * choice. A share rather than pixels, so a split chosen at 1440 still splits a window resized to
 * 1280 the same way. Only two-column mode reads it: below 1200px the stylesheet gives the list the
 * whole width and does not draw the divider.
 */
function setListShare(share, { save = true } = {}) {
  const panes = $('panes');
  const divider = $('pane-divider');
  if (share === null || !Number.isFinite(share)) {
    if (save) prefs.remove('inspector.listShare');
    panes.style.removeProperty('--list-share');
    divider.setAttribute('aria-valuenow', String(LIST_SHARE.initial));
    return;
  }
  const clamped = Math.round(Math.min(LIST_SHARE.max, Math.max(LIST_SHARE.min, share)) * 10) / 10;
  panes.style.setProperty('--list-share', `${clamped}%`);
  divider.setAttribute('aria-valuenow', String(clamped));
  if (save) prefs.set('inspector.listShare', String(clamped));
}

const listShare = () => Number($('pane-divider').getAttribute('aria-valuenow')) || LIST_SHARE.initial;

/**
 * Drag, arrows and Home on the divider. A drag moves the share by the pointer's travel from where
 * it was pressed, not to the pointer's position, so pressing it does not make the split jump by
 * half the divider's width; it is saved once, when a drag that moved ends, rather than on every move.
 */
function wireDivider() {
  const divider = $('pane-divider');
  const saved = prefs.get('inspector.listShare');
  setListShare(saved === null ? null : Number(saved), { save: false });
  divider.addEventListener('pointerdown', (event) => {
    if (event.button !== 0) return;
    event.preventDefault();
    const width = $('panes').getBoundingClientRect().width;
    const startX = event.clientX;
    const startShare = listShare();
    divider.classList.add('dragging');
    let moved = false;
    const move = (e) => {
      moved = true;
      setListShare(startShare + ((e.clientX - startX) / width) * 100, { save: false });
    };
    // A press with no travel saves nothing: each half of a double-click is one, and saving there
    // would store the default as a choice.
    const end = () => {
      divider.classList.remove('dragging');
      document.removeEventListener('pointermove', move);
      document.removeEventListener('pointerup', end);
      document.removeEventListener('pointercancel', end);
      if (moved) setListShare(listShare());
    };
    document.addEventListener('pointermove', move);
    document.addEventListener('pointerup', end);
    document.addEventListener('pointercancel', end);
  });
  divider.addEventListener('dblclick', () => setListShare(null));
  divider.addEventListener('keydown', (event) => {
    const step = { ArrowLeft: -LIST_SHARE.step, ArrowRight: LIST_SHARE.step }[event.key];
    if (step) setListShare(listShare() + step);
    else if (event.key === 'Home') setListShare(null);
    else return;
    event.preventDefault();
  });
}

/**
 * The detail column put away, and the tab at the right edge that brings it back, naming the call it
 * would show. Selecting another row keeps it put away: hiding is a choice about the layout, and a
 * click on a row is not a request to undo it. Not kept across reloads. Column mode only; below
 * 1200px the stylesheet ignores the class, so the drawer still opens on a row.
 */
function hideDetail() {
  $('panes').classList.add('detail-hidden');
  paintDetailTools();
}

function restoreDetail() {
  $('panes').classList.remove('detail-hidden');
  paintDetailTools();
  $('detail-hide').focus();
}

/**
 * What the tools row and the restore tab say about the call the pane shows: `⤢` links to its own
 * page and is offered only while a call is shown, and the restore tab, shown while the column is
 * put away, names it.
 */
function paintDetailTools() {
  const hidden = $('panes').classList.contains('detail-hidden');
  const id = $('detail').hidden ? null : state.selectedId;
  const txn = id && (state.transactions.find((t) => t.id === id) || state.allTransactions.find((t) => t.id === id));
  const label = txn ? `${txn.method} ${txn.path}` : 'detail';
  $('detail-restore').hidden = !hidden;
  $('detail-restore-label').textContent = label;
  $('detail-restore').title = `Show the detail again: ${label}`;
  const full = $('detail-full');
  full.hidden = !id;
  if (id) full.href = routeHash({ page: 'txn', txn: id, session: state.sessionId });
}
