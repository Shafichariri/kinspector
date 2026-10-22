# Decisions — web UI
Owned by `inspector-daemon/src/main/resources/web/` and the probes in `scripts/`. Imported by the web guide.

**The web UI is ordered classic scripts listed in `web/scripts.txt`, and the daemon is the only
thing that turns the list into served tags.** `index.html` keeps no list; it has two slots, and
`WebPage` expands them — into `<script src>` and `<link>` tags for `GET /`, and into one inlined
`<script>` or `<style>` per file for an export (see `exports.md`). Nothing serves or fetches the
manifests themselves. The files share top-level declarations rather than a namespace object or ES
modules, because classic scripts share one global lexical scope: a call into another file resolves
when it runs, as it did inside the one file they were cut from, so the split could be checked as
pure relocation. A namespace object would have rewritten every cross-file reference, and
destructuring it at load captures `undefined` from any file not loaded yet. The cost is the
load-time rule, because hoisting does not cross files: **only `99-init.js` runs anything at load.**
Every other file only declares, and a constant built at load reads nothing from a later file —
`source` and `archiveSource` share `05-network.js` for exactly that reason, and moving
`archiveSource` later turns the archive probe red while the live probe stays green, since only an
export takes that branch. `'use strict'` is per file now, and **jsdom does not enforce it** — an
implicit global does not throw there — so the static check is the guard, not the probes. Pinned by
`WebManifestTest` (each manifest and its folder agree both ways, entries numbered and ascending,
nothing else in `web/`), static checks 1–3 in `check-web-static.js` (`'use strict'` is the first
statement of every file, no top-level name is declared in two files, none is on the named list of
browser `Window` members or an own property of Node's `globalThis` — which is where `Map`, `atob` and
`encodeURIComponent` are refused; checks 2 and 3 read top-level names with a line heuristic over
column-0 lines, not a JavaScript parser, so some declarations escape them — among them one written
indented, one after a block comment on the same line, the later names of a declaration whose first
line ends inside brackets, and one after a regex literal, a leading statement or a leading `}` on
the same line — and a second declaration after a `;` or `}` on a column-0 line, block comments
between the two included, is refused as unreadable when the scan can see it), both probes loading each file as its own
script in manifest order so a redeclared name or an early call into a later file fails as it would in a browser, and the archive
probe's `every file inlined`.

**`web/styles.txt` order is the cascade.** The seventeen files are contiguous slices of the one
stylesheet they were cut from, in its original order, so loading them in manifest order is that
stylesheet — proven at the split by concatenating them without their one-line headers and comparing
byte for byte. The rule a new rule or a move must keep: **an `@media` block never loads before a
rule it overrides.** Keep it in the same file as those rules, after them, wherever that can be
done; every `prefers-reduced-motion` block is, and so are the `.pv-visit` and `--stop-bg` pairs.
The `max-width: 1199px` block in `35-list.css` is the one that spans files: it also overrides
`#panes` and `#panes.panes-wide` in `10-panes.css`, no contiguous cut under the 600-line budget puts
them in one file, and it holds only because `styles.txt` lists `35-list.css` after `10-panes.css`.
So reordering the manifest is a cascade change, never a tidy-up. Pinned by static check 5
(`media overrides load last`: across every file in `styles.txt` order, no selector inside an
`@media` block has a later plain rule with the same selector text that sets a property of the same
name or one name followed by `-` at the start of the other, as `border` and `border-right` (custom
properties only by equal name); it prints how many such pairs, earlier or later, it compared, and fails when that is none —
proven red by renaming `35-list.css` to `08-list.css` ahead of `10-panes.css`) and by static check 4 (`css files close`: each file closes its
own braces and comments, which a joined read cannot see — a file that swallowed the next would still
join to the same text, and break only once served as its own `<link>`). Check 5 matches by text and
by name, not by the cascade, and walks `@media` and no other at-rule. What it misses is not listed
in full; among the misses are a different selector competing on specificity, the same compound written in another order (`.panes-wide#panes`),
longhands of one shorthand where neither name followed by `-` starts the other (`border-style` and `border-right`, `inset`
and `top`), and anything inside `@supports`, `@layer` or `@container`; and among its false reports
are a pair that does not compete though one name followed by `-` is at the start of the other,
because the shorter is not its shorthand (`border` and `border-radius`), and an `@media` declaration marked `!important`. None of
those shapes is in the stylesheet today; the misses are still the author's to see.
**`@media` against `@media` order is not checked at all**: check 5 never compares two `@media`
rules, so one that loads before another `@media` rule overriding it passes. That case is live in
`35-list.css`, where the `prefers-reduced-motion` block's `#detail-pane { transition: none }` must
load after the `max-width: 1199px` block's `#detail-pane` transition. It is held by review and by
keeping both blocks in that one file, and a comment above the reduced-motion block says so.

**The web scope pill reads `host · prefix` and clips at the end.** It was `direction: rtl` under
a comment saying the front of a host identifies it — which is the end rtl clips. The two parts are
separated so a narrow bar loses the least specific part of the prefix, and the full label is the
tooltip.

**The web list's empty state says which of four things emptied it.** A time selection, a filter
(with a `clear filter` button, because clearing it is the whole fix), a session with no calls,
and no session at all. "Waiting for an app with inspector-stream to connect" is said only in the
last case: on an archived session it sent people to check the app when the app had come and gone.

**Nothing in the web UI is under 11px except a named list, and `render-web-ui.js` reads the
stylesheet to hold it.** The method badge, `kbd` key caps and the three fold glyphs (`.jt-tw`,
`.now-caret`, `.tl-twisty`) are the exceptions — shapes, or a badge sized to a fixed column —
and each is named in `auditMinTextSize` rather than matched by pattern, so a new 10px caption fails
the audit until someone decides it belongs on the list. The design pass named four selectors to
raise; nine more were at 10px, and fixing only the four would have left the rule false on day one.

**An icon-only control is an SVG in a box at least `--target-min` (24px) square, never a text
glyph.** A glyph is drawn by whichever font has it: `⤢` fell back to a symbol font and drew well
under its 13px em, a 23×20 target. `⋮`'s own 13px rule lost the cascade to `.btn-sm` declared
later and rendered at 11px, which nobody noticed because the rule was there. `.btn.btn-icon` is the
one rule that sizes them: two classes outrank `.btn-sm` and `.btn-quiet` wherever either loads, and
`icon target css` refuses any other rule that sizes an icon control by name, whichever of the two
would win. Its centring is set under `:where()`, at no specificity, because what decides whether each
control is drawn at all is a single class or `[hidden]` (`.drawer-close`, `.detail-hide`), and a
`display` with two classes would outrank them. 24px is WCAG 2.2's minimum target. A control whose
text changes, such as the Sessions row's `✕` that arms as `sure?`, keeps its text in the same box.
**Every `.btn` on a page document is at least that tall, and the traffic toolbar is exempt**,
because its height is the band budget 1b measured (`room 1440 bands list`). Prose on a page document
stops at `--prose-max` (72ch). At 1440 a Sessions note had run 1232px, about 190 characters a line.
`icon target css`, `icon controls svg`, `page targets css` and `prose width css` read the rules;
`measure-web-ui.js`'s `polish <w> …` readings are the behaviour (the icons at 1440, 1000 and 400,
served and exported, dark and light), each red with its rule removed.

**The tab bar is always drawn.** It used to hide when a session had only the traffic view, which
made the bar appear and disappear between sessions and moved everything under it. The order control
is a segmented pair rather than two more chips, because it arranges the list where the chips beside
it filter it; `aria-pressed` on each half is checked against the visible state.

**`querySelector` searches the whole subtree, and the replay panel anchored on that for two
releases.** `runReplay` inserted its result with
`pane.insertBefore(title, pane.querySelector('.section-title'))`. On a row with a redaction banner
or an attempt chain the first match is a direct child and it works; on an **ordinary** row the
first match is the one inside a `.dtab-panel`, a grandchild, and `insertBefore` throws
`NotFoundError` before the request is even built. So replay failed on most rows and worked on
exactly the interesting ones somebody reaches for when testing it by hand. It anchors on `.dtabs`
now, which is a direct child. Nothing caught it because `render-web-ui.js` never pressed the
button; it does now.

**Replay is behind one `replay ▾` menu, and its Escape handler stops propagation.** The detail
header ranks its actions: the two copies are quiet because they only read, and replay is the one
outlined control because it sends. Escape inside the menu must not bubble — the page's own Escape
clears the selection, so a bubbling Escape closed the menu *and* the whole detail pane. The
replay probes open the menu before pressing an item, which is also what tests that the items are
reachable at all.

**A replay's `headers` map replaces the captured set entirely.** `Replayer` uses a sent map
verbatim and skips `replayableHeaders`, so anything the UI leaves out is simply not sent — and its
own hop-by-hop filtering never runs either. The edit form therefore seeds from *filtered* captured
headers rather than the raw capture, or changing a body would quietly put the captured
`Content-Length` and `Host` back on the request. `HOP_BY_HOP` (the web UI's `hopByHop` block) mirrors
`HOP_BY_HOP_HEADERS` in `Replay.kt`, and `mirror-cases/hopByHop.json` holds the two lists equal:
`HopByHopMirrorTest` runs it against the daemon's, `check-web-static.js` against the page's.

**A probe that presses a button must stub `fetch` itself, not rely on the guard it is testing.**
The editor's malformed-header check presses **send**, because "the form refuses this" is only
observable by trying. The first version relied on the guard being present to stop the request — so
the one run where the guard was broken was the one run that sent something. Against an unreachable
host that surfaced as the whole script dying; against a reachable one it would have fired a real
edited request from a smoke test and reported a pass.

**`render-web-ui.js` takes its verdict from its own report lines, and checks an exact count.** It
exited 0 whatever it printed, so a run in which every line said NO was green to anything that read
only the exit code — which is CI. Threading a result through a hundred probes would have been a
rewrite; instead every line printed while reporting is classified as it goes: a value starting
`yes` or `true` passed, `NO` or `false` failed, `n/a` was skipped. A line that is information on
purpose goes through `note()`, with a comment where it is printed saying why; anything else is
*unclassified*, and an unclassified line fails any run with an expected count. That rule exists
because the first version let information be the default: seven checks printed their verdict on an
unclassified line or in the second half of a compound one, so QA set the detail pane's default tab
to the wrong one and the run stayed green. **One check per line**, and nothing reaches the report
without either a verdict or a declared reason not to have one. Any failure, any page error or a
list that is not an exact mirror exits 1. The count is the other half: a probe whose selector
stopped matching does not fail, it reports `n/a` or nothing, so `INSPECTOR_UI_EXPECT_CHECKS` pins
how many must pass and CI sets it from `.github/workflows/ci.yml`. A change that adds or removes a
check changes that number in the same commit. The archive probe does the same. Against a session
of your own, with no expected count, unclassified lines are reported and not fatal, because probes
fall into prose branches for data that session lacks. The probes that read a whole session page
through the routes, which clamp a reply to 1000 rows.

**A scan that finds nothing to check fails, and says so.** The probes share one page, so a probe
that leaves state behind can empty the next one's scan. KIN-55's live probe left a path visit open,
the path probe found no summarised line, and `failures unfolded` printed `yes (0 summarised lines in
this session)`; `expanded unfolded` passed over nothing as well. The pinned count could not see it,
because a pass over nothing is still a pass. So a probe in `render-web-ui.js` or `scripts/lib/`
that judges a rule over a set of rows, lines, bars or elements also asserts that it found at least
one, prints how many it checked when it passes, and otherwise prints `NO - found no <shape>, so this
checked nothing`. It says `n/a (why)` instead only where the shape cannot exist, and that is decided
from a source the scan's emptiness cannot have caused: the daemon's rows, its `/current`, or the
page's whole-session state, never the elements scanned. `failures unfolded` is the one decided from
the page: with no summarised line it is `n/a` only when no line is open either, because a line left
open is the only way a long visit stops being summarised. `expanded unfolded` opened the second
station, which on the CI fixture is Home with no failed call, so it compared 0 failures with 0. It
now opens the first visit holding a failure by the daemon's rows, split into visits by the probe
itself as `route matches` is, and also fails when that line draws fewer failures than the daemon
holds there; with no failure in the session it reads `n/a (no visit holds a failure)`. A count
compared with an independent truth (`route matches`, `signal tabs count`, the archive's `marker
dividers`) is not an empty scan: at
zero it still asserts that nothing spurious was drawn. The live probe's verdicts are in
`lib/scan-verdicts.js`; the stylesheet audits `min text size` and `hidden panes hide`, the shell's
`self has kill btn`, `rail is navigation` and `route pages`, the archive probe's `daemon controls
hidden`, `pages hidden in export` and `stats rail in export`, and `measure-web-ui.js`'s scope bar
readings keep the same rule; a hidden scope bar there was a reading silently not taken, and is now a
failed one unless the page holds no scope. With KIN-55's leak put back, `failures unfolded` read
`NO - found no summarised line (1 already open: Checkout), so this checked nothing` and the run
failed. `expanded unfolded`, `every bar visible`, `now rows show age`, `self has kill btn`, the two
audits, the archive's three lines and the scope bar readings were shown red with their shape taken
out of the page, the export or the stylesheet text, and the live probe's other verdicts on stub
pages (KIN-87's notes list which); the others rest on review. PR #133 put the Chrome cases under the
rule too: `shell <w> rail` (`lib/shell-cases.js`), `room 400 rows`' repeat marks, `room <w> toolbar
fits` and `one row`, and `room 1000 tab never enters closed` (`lib/list-room-cases.js`), `traffic
reaches` (`lib/live-traffic-cases.js`) and the top bar's `count` wording, `NO - found no word in
#counts` (`lib/topbar-cases.js`). Each was shown red with its shape taken out of the page, except
the socket half of `traffic reaches`, which rests on review: the case needs the live socket open
before it can start.

**The CI probe session is written by the daemon's own writer at CI time, and never committed.**
`ProbeFixture.kt` builds it on the daemon's test classpath and `writeProbeFixture` runs it, through
`writeSession` and `SessionWriter` like every daemon test. A committed archive would be a second
copy of the on-disk format that nothing keeps current — the day a field is added it quietly stops
exercising it while every probe goes on passing against the old shape. Its contents are chosen, not
sampled, because a probe with nothing to look at says `n/a`: a shared prefix and a second host, a
500, a retry chain, a redirect chain, a repeat that swept up a retry, a marker at exactly a call's
`mono`, a full tie on chip count and recency, both cache payload shapes and a run of five identical
observations. A second run replaces the probe session rather than appending to it — `SessionWriter`
appends, and doubled rows would make every count depend on how often the task had run — and leaves
anything else in the directory alone. `ProbeFixtureTest` reads it back through `SessionRepository` and asserts each of those
is there, so a fixture edit that loses one fails a unit test rather than turning a CI check into a
skip. Every host is under `example.invalid`, so a replay that escaped its stub reaches nothing. CI
serves it on port 8231, never 8099, and pins jsdom by exact version.

**The snapshot now writes field values into the markup.** `input.value` and `textarea.value` are
DOM *properties*; `outerHTML` serialises the `value` attribute and a textarea's text content. Every
snapshot this script ever produced therefore showed the filter box, the settings fields and the
replay editor empty — and the snapshot is the only thing anyone *looks* at.

**The web UI keeps an unfiltered copy of the session, and two features depend on it.**
`state.transactions` holds the rows the *daemon* matched against the current filter, so it is the
wrong source for anything describing the session as a whole. `state.allTransactions` is the whole
session; endpoint chips and duplicate detection both read it. Endpoint chips built from the
filtered view would collapse to the one chip you just clicked, making every other endpoint a dead
end, and duplicate highlighting would vanish under exactly the filter you applied to investigate
it. Under a filter it costs one paged read per *session*, not per keystroke. With no filter it costs
nothing only while the list's own page holds the whole session; past the route's clamp that page is
the newest 1000 calls, so the copy is the same paged read. Copying the page there instead was trusted
once a filter was typed, and the path counted every older visit from the clamp (`unfiltered then
filter` in `check-web-static.js`, red with the copy taken whenever no filter is set; the filter-first
checks cannot see it, because a filtered load always reads the copy whole). The stopped banner's
"newest row in this session" reads it too, with `allSignals`. It used to read the filtered calls, and under
a filter that kept only older calls it dated the session by the newest one the filter happened to
keep. `check-web-static.js`'s `last capture whole` holds it.

Signals are split the same way. `state.signals` is what the daemon matched against the filter, and
only three readers take it: the timeline tab's entries and the path view's `visits(...)` call, the
two places a filter is meant to thin signals, and `hiddenByFilter`, which counts what the filter
took out of them. `state.allSignals` is the whole session, and the tabs and
their counts, the tag browsers, the glance, the axis, tag colours, `sessionStartMono`, id lookups,
the agent bundle, "last capture" and `state.screens` all read it — a tab counted from the filtered
list would shrink or vanish under a filter and claim the app recorded less than it did. The
waterfall tab is offered from `allTransactions` for the same reason: once the route honoured
`filter`, `tag:screen` matched no calls and the tab vanished mid-filter. `allSignals` is cached per
session under a filter, as `allTransactions` is, and a signal pull clears that cache because the
rows it asked for are new. Both are read through `routePages` — as `signalPages`, and the
whole-session calls as `transactionPages` — which asks the route for one page at a time, newest first, de-duplicates by id, and **stops at an empty page or one that adds no new id —
never at a short page.** The route clamps a page to 1000 today; stopping when a page came back
shorter than asked would truncate again the day that clamp is lowered, and would never stop against
a source that ignores `offset`. A row arriving mid-read shifts every newest-first page by one, which
is why the seam is de-duplicated. A page that fails fails the whole read: a quietly partial list is
the defect the pager exists to fix. `check-web-static.js` lifts it by its `routePages` anchors and
runs both shapes against stub routes — 1001 rows by 1000, a 500 clamp, `offset` ignored, a repeat
across the seam — and was proven red with a `page.length < 1000` stop and with the de-duplication
removed. The whole-session calls are paged under a filter, and without one when the list's page is
short of the session total, because the path counts what each visit recorded from that copy: stopped
at the clamp it made every visit older than the newest 1000 calls read as having recorded less. The
list draws the newest 1000 calls by the page's own limit (`LIST_CEILING`), not by the route's clamp
happening to match what it asked, and with no filter and no brushed stretch the header then says
`newest 1000 of 4210 shown`, because `1000 of 4210 calls` is the shape a filter produces and sent
readers looking for one (`no-filter count` in `check-web-static.js`, `long count no filter` in the
live probe). An export asks for every embedded call instead, and says `4210 of 4210 calls`: it has
no filter to reach past a ceiling, and at 1000 its oldest calls could not be opened at all, while a
brushed stretch over them said they did not exist (`long count no filter`, `long stretch oldest` and
`long offset 1000 sliced` in the archive probe). Under a filter that matched more calls than the list holds, the header
numbers the matches, `newest 1000 of 1200 matches shown`, from the route's `matched` and never from
the rows held: `1000 of 3016 calls` read as "1000 calls match" where 1200 did. A brushed stretch is
no exception, and there the drawn rows are the ones in it: `400 of the newest 1000 of 1200 matches
shown`, where the plain count had read `400 of 3016 calls`. The list then draws
the same `filterNote` the other panes draw, in `#list-note`, sticky at `--sticky-top` and outside the
listbox; within the ceiling it draws none, because the header's `N of M calls` already counts what is
drawn. A brushed stretch the list draws nothing of, while the stretch holds calls below the oldest one
the list loaded, never reads "no calls in the selected stretch". With no filter it says how many
calls there are older than what the list loaded and that a filter reaches them, counted from the
whole-session copy and "at least" below the mono at which that copy is whole (`olderThanLoaded`). The
glance's THIS STRETCH counted those calls while the list said there were none. **Under a filter it
claims no count**, because nothing on the page can make one: the copy is unfiltered and the page
evaluates no filter. The first build counted the copy there and told a stretch where nothing matched
that its 500 calls could be reached by narrowing the filter. It now says that none of the matches
loaded is in the stretch and how many older ones are not loaded (`matched` less the rows held), with
"narrow the filter to reach any here". A stretch the copy shows no calls in still reads "no calls in
the selected stretch", which is true whatever the filter. `filtered count`,
`list note past the clamp` and `stretch older than loaded` in `check-web-static.js` hold it over
the 3016-call stub, and `long filtered count`, `long list note` and `long stretch older` in the live
probe over the 1203-call session; each was proven red with the old wording or no note put back, and
the filtered header and stretch cases against the first build's code. An
export draws every call it embeds, so it never shows the note (`long list note absent`).

The two cached copies have gone stale in the ways below, and all are closed; this is
what has been found, not every way there is. Resuming the list and the socket opening again after
the daemon was away clear both (`forgetWholeSession`): the live socket is the only thing that keeps either copy current, so rows that arrived while nothing listened are in
neither, and the tabs, the route and the endpoint chips went on without them until the filter was
cleared. "The daemon was away" covers a drop nobody asked for as well as a stop or a restart:
`ws.onclose` moves `serverState` from `running` to `away`, and it used to leave it at `running`, so
after a plain drop the next open took the page for one that had never been gone. The live probe's
`resume refreshes`, `reconnect refreshes` and `drop refreshes` play rows arriving in each gap under
`tag:screen`; the last closes the page's socket and lets its own redial open the next, because the
reconnect line reaches `onopen` through the stubbed stop, which marks the daemon away by itself and
so could never show whether a drop does. Forgetting also overtakes every load in flight (`callLoad`, `signalLoad`),
because a whole-session read sent before the gap answers from before it, and landing afterwards it
marked the copy again with the old rows (`forget drops calls read`, `forget drops signals read`);
deleting the session on screen clears through the same function (`session change forgets`). A
whole-session read that fails leaves the filtered rows standing in and does not mark them as the
session, for the calls as for the signals, or every later load would trust them — and the list,
reading the mark, said a session with calls had recorded none (`whole read failure`,
`whole calls read failure` in `check-web-static.js`). And each load reads the filter once and hands it down, and a load a newer
one has overtaken drops its answer rather than writing it (`callLoad`, `signalLoad`): the daemon may
answer two loads out of order, and a slow unfiltered read that landed last put every row back under
the filter just typed (`older call load dropped`, `older signal load dropped`). And a live row that
arrived while the whole-session read was between pages was in neither the read nor the copy, because
a live row joined a copy only once the copy was marked as the session's, and the mark is set when the
read lands; paging widened that window from one request to every page. Now, from the first page
request until the read lands, a live row for that session is buffered (`admitLive`, `state.arriving`),
and the landed copy is the read plus every buffered row whose id it lacks, for both copies. A copy
already marked is read again when it is empty, and the read replaces it, so a live row goes into an
open buffer whether or not its copy is marked (`mid-read calls empty`, `mid-read signals empty`). Opening
the buffer before the first request is enough because the daemon stores a row before it broadcasts it,
so a row broadcast earlier is already in the read; and the merge is by id because a row can be in a
page and in the buffer both (`mid-read calls kept`, `mid-read signals kept`, `mid-read calls once`,
`mid-read signals once`).

**A switch follows the session the moment it changes, calls and signals alike, and until the new
session lands every pane says it is being read.** A switch reads a page of calls, the markers and
the signals before it draws, and until KIN-42 it read the whole-session calls first too, so until
those landed the panes described the session just left under the new one's name: 25 s on a large
session. `followSession` clears both sides at once. It empties the signals, `allSignals`,
`screens` and `current`, and the calls, `allTransactions`, `markers`, `sessionTotal` and
`matched`, and leaves both copies unmarked. It overtakes the call load, the signal load and every
`/current` read in flight. It closes a call the session left had open, and redraws the header, the
tab bar, the markers, the list and the view shown. The picker reaches it through
`loadTransactions`, and `afterSessionChange` calls it before it waits for the session list, so a
deleted session is not shown meanwhile. The calls used to be left to the switch's first page, on
the premise that the first page replaces them. It replaced the list's rows and nothing else, and
only when the whole load ended. The timeline's call rows, the endpoint chips, the axis, the
markers and the tab counts read `transactions`, `allTransactions` and `markers`, which nothing
else wrote until then, so for the length of the switch the timeline drew calls the selected
session never made. The premise was a fact about one pane, recorded as if it held for every pane.
From the clear until `loadSignals` has drawn the selected session (`signalSessionId`, asked
through `sessionPending`), the list, the timeline, the waterfall, the path, a tag browser, the
glance and the markers popover draw none of the old session's rows. Each says `this session is
being read…`, from `beingRead`, the one function that words it, and never an empty state:
"nothing recorded" of a session that is only unread sends a reader to debug the app. The tab bar
keeps the tabs it had, without their counts (`state.tabViews`). Rebuilt from what the switch
cleared, it would drop the timeline tab, and a count would be the session left's. A call an
address opened on the selected session stays open, since it is that session's (`showTxnFull`).
`loadSignals` and `refreshCurrent` read `/current` through one function, `readCurrent`: of two
reads the later-started decides whatever order they land in (`currentRead`, as
`readSent`/`readWritten` for the filtered rows), and a failed read keeps the strip only when its
values are the selected session's (`currentSessionId`). Kept, each row is a dated fact under its
own age (`signals.md`); emptied, the strip reads as "this app holds nothing now". After a switch
the values are another session's, so a failure there still leaves it empty. The live probe's
`now switch shows none` holds the new session's `/signals` and reads the three signal panes.
`switch list`, `switch timeline`, `switch waterfall`, `switch path`, `switch browser`,
`switch endpoints`, `switch tabs`, `switch detail` and `switch counts` hold its first page of
calls and read one pane each. Each fails, rather than passing, when the session left drew nothing
there to leak. `measure-web-ui.js --only traffic`'s `traffic session switch window` reads the
list, the endpoints and the tab counts in Chrome while a real daemon's answer is held.
`check-web-static.js`'s `switch clears calls` and `being read wording` hold the clear and the one
wording. `now newest read decides` and `now load read overtaken` release two reads newest first;
`now failed read kept` fails one on the session shown, beside KIN-51's `now late reply dropped`
and `now failed read empty`. The fixture's long session has current values of its own, so a late
reply written as empty fails `now late reply dropped` rather than matching it. Each was proven red
with its rule removed.

**Under a filter a live row waits for the daemon to say whether it matches.** The socket handler
pushed every live call and signal into the filtered lists, so under `status>=500` every 200 that
arrived was drawn, and counted, until the filter was edited. The page cannot decide the match
itself: that would be a second parser of the shared grammar, which is why an export disables the
filter (`exports.md`). The daemon could have said instead, as a flag on each live message, but
`/api/live` is one broadcast with no per-page state. It would have had to learn each page's
filter over a socket that any web page can open, and a flag computed against a filter the page
has since changed needs a read to settle anyway. So a live row under a filter joins only the
whole-session copies on arrival, and owes a re-read of the filtered rows of its kind through the
routes a load reads (`oweReread`, `rereadLive`). With no filter it is pushed as before and costs no
request. Only spaces typed is no filter, decided once in `applyFilter`, as the daemon reads a blank
filter. A re-read fires a second after the first row that owes it, and never while another is out;
rows arriving meanwhile ride on the one armed when it lands. So a burst of 100 rows inside a second
costs at most two re-reads: two requests if all are calls, and `2 × (1 + ⌈S/1000⌉ + 1)` with
signals among them, where S is how many signals the filter matches. Of a load and a re-read
for the same rows, the one sent last is kept whichever lands last (`readSent`, `readWritten`), and a
re-read is dropped once the session, the filter or live tail has changed. Pausing still drops rows
first. The live probe's `blank filter …` and `live …` lines drive rows through the page's
socket. They were proven red with the push put back, with the whole-session push removed, with the
re-read run while paused, with the counts left to the next load, and with spaces kept as a filter.
`check-web-static.js`'s `reread …` and `older reread …` lines hold the ordering and the
burst, each proven red with its rule removed. `measure-web-ui.js --only traffic` holds the same
rules through the whole path, rows streamed into a real daemon's `/ingest`: `traffic burst reads`
and `traffic burst counts`, and each race in both orders, because each order has its own guard.
An old re-read landing after the edit's or switch's own read is dropped by the ticket and by the
session-and-filter check alike, so only removing both turns `… old lands last` red; one landing
before it is dropped by that check alone, and removing its filter or session clause turns
`traffic filter edit race, old lands first` or `traffic session switch race, old lands first` red.

**A live call reaches every pane that counts calls, and the two that redraw from the whole session
do it on a timer.** Since KIN-54 a live call moved the header and the traffic tab; the timeline, the
glance and the picker still described the last load. The socket's call branch never called
`renderTimeline`, so with no filter a call reached the timeline only when something else redrew it;
the glance read `calls 27` under a header of 29; and the picker's `txnCount` was the daemon's from the
last `loadSessions`, `6 calls` under 31. The timeline and the glance redraw from every row the session
holds, so a call arms one redraw of both (`drawLive`) and the calls arriving meanwhile ride on it:
`LIVE_DRAW_MS` is 250, and a burst of 100 calls over T ms costs at most ⌈T/250⌉+1 of each, five over
a second, against one per call before. The timeline keeps its follow rule, because the redraw is
`renderTimeline` itself. The selected option counts from the header's own `sessionTotal`
(`followLiveCount`) and repaints the face through `paintPickerFace`, its one writer, at no request;
re-reading `/api/sessions` would have cost one per call or a throttle of its own, for a number the
page already holds. Other options keep their loaded counts. Paused, or for another session, the
branch is never reached, so none of the three moves. `check-web-static.js`'s `socket call drawn`,
`socket run stays open` and `live draw burst of 100`, and the live probe's `live picker count`,
`live glance plain`, `live timeline plain`, `live glance filter`, `live paused still` and `live other
still` hold it, each proven red with its rule removed.

Live signals and markers ride the same timer. The signal branch redrew the timeline, the waterfall
and the path and read `/current` for every signal, and the marker branch redrew the timeline and the
path for every marker, so 100 signals cost 300 whole-session redraws and 100 reads. A marker now owes
the redraw the path, and a signal owes it the waterfall, the path, one `/current` read and the
payload reads of the pane that shows them; each runs at most once per redraw, so the bound is the
calls' ⌈T/250⌉+1. **The payload reads ride the timer too**, though each payload is its own request
and riding saves none of them. What it saves is the redraw each answer made: the path redrew per
screen on landing as well as per signal, and `loadScreenPayloads`, which reads every screen not yet
held, asked again for those still in flight. The path and a tag's browser wait for their payloads and
are drawn once. Drawn before them and again after, the path would cost two redraws per tick and break
the bound; on loopback the wait is milliseconds. The reads are made only for the session the signals
arrived on, while it is read and live tail is on: after a switch inside the window, the timer's
`/current` read put the new session's values in the strip while every other pane said the session
was being read. The tab bar still redraws per signal, because a tag's first signal is when its tab
becomes reachable, and the list and the markers popover per marker. `check-web-static.js`'s `live
signal burst timeline`, `live signal burst path`, `live marker burst` and `live signal still`, and
`socket at end timeline`, which now waits for the redraw for markers and signals, hold it in jsdom;
`measure-web-ui.js --only traffic`'s `traffic signal burst`, `traffic marker burst`, `traffic lone
signal drawn`, `traffic lone marker drawn`, `traffic paused still` and `traffic other still` hold it
through a real daemon. Each was proven red with its rule removed, the bursts with the per-message
branches put back.

**No row array is spread into an argument list.** Every argument is a stack slot, so
`Math.min(...monos)` throws `RangeError: Maximum call stack size exceeded` once the array is long
enough — past about 110,000 elements in Chrome and 120,000 in Node. Once the whole-session calls
were read past the clamp, a 160,000-call session threw in `sessionWindow` before anything was
drawn: no rows, no axis, and the dot stuck at "connecting…". Minimum and maximum are loops now, in
the axis, the waterfall, the timeline, a run's row, the path and the stretch note. Spreading into an
array literal, `[...a, ...b]`, is not a call and is fine. `check-web-static.js`'s long session
section holds it twice: `no argument spread` reads every script, with comments and strings blanked,
for a `...` whose innermost bracket is a call's `(`; and `axis window 200k` to `path 200k` run those
functions out of the real files over 200,000 rows. Each was proven red with each file's spreads put
back. What jsdom cannot show — rows and the axis drawn and the dot at "connected" on a real
160,016-call session in headless Chrome, red at `a86dde2` and green after — is recorded in KIN-37's
notes. The whole-session read is not capped, which KIN-42 decided: the path counts what each visit
recorded and the stretch notes count what lies below the list from that copy, and a cap re-opens
the defect paging closed, every visit older than the cap reading as having recorded less. Its
length is kept off the first draw instead (the next paragraph).

**A load draws its first page at once and reads the whole-session copy behind it.** KIN-34 made a
load read the whole session's calls before it drew anything, because the path, the stretch notes
and the endpoint chips count from that copy. On a 160,016-call session that is about 160 pages and
26 s, and for all of it the list said "waiting for an app with inspector-stream to connect" under
a header counting the session, with the dot at "connecting…", because the socket was opened after
the load. `loadTransactions` now starts `syncAllTransactions` once the first page and the markers
are in, reads the signals, and draws the list, the header, the tabs and the view shown. It awaits
the copy last, and when the copy lands `drawWhole` draws again what reads it. The read opens its
live-row buffer before its first page, as before (`admitLive`). Until it lands the copy is what the
switch left, empty and unmarked, and the panes that read it do not count a page as the session.
The glance and the path say `this session's N calls are being read…` with the header's total
(`beingRead`). The axis is hidden, because a map drawn from the newest page shows the session
starting where the page does. Its overview line keeps its band through the read, as tall as the
axis, with the same words in the axis's hint and never an empty axis: hidden too, the line came back
when the copy landed and pushed every row 33 px down under the reader, on top of the scope bar's
33 px. The endpoint chips, repeat marks and scope bar are absent. An empty
list, timeline or waterfall says the same where it would have said "no calls in the selected
stretch" (`saysBeingRead(node, whole)`, asked through `wholePending`: a read of this session's
calls is in flight). The waterfall tab is offered on the session's total, so it does not come and
go while the copy is read. `drawWhole` leaves each pane where it was: the list's scroll is put back
after `renderList`, which follows the newest row under live tail, and the timeline is drawn without
following. Each page of the read asks whether its load still stands, so a newer load, a switch or a
forget stops it at its next page rather than reading the session for nobody. A filter typed during
the read therefore starts it again. `loadTransactions` still resolves only once the copy has
landed, so a caller that awaits a load sees it landed. The socket opens as soon as the session list
is read, before the first page. A row is matched against the selected session as it arrives and
needs nothing a load reads, and opened after the load the socket left the dot at "connecting…" and
dropped every row that arrived in between. A row that arrives during the first page's own request
can still miss the list until the next load. `measure-web-ui.js`'s `first rows huge` and
`connected huge` hold the draw and the dot within 3000 ms on the 160,016-call session, and were red
at `623c571` at 26,378 and 26,482 ms. `being read huge` holds what the page says during the read,
and `copy lands huge` the redraw in place after it. `check-web-static.js`'s `first draw before
copy`, `copy lands redraws`, `overtaken read stops` and `reading notes`, and the live probe's
`long first draw` and `long copy lands` on the 1203-call session, hold the same rules without a
browser. Each was proven red with its rule removed. The band's reserved height,
`.axis-hint.axis-reading`'s `min-height`, is held in CI by `check-web-static.js`'s `overview band
css`, and in Chrome by `copy lands huge` bounding the move at the scope bar's measured height: its
fixed 48 px let the band's missing 11 px through, rows moving 44 px. Both were red with the rule
removed (KIN-110).

**A pane a filter emptied or thinned says so, names the filter and offers `clear filter`.** The
list always did; the waterfall, the path and the timeline did not. Under a filter that matched
nothing the waterfall said "no calls to lay out" beside a header counting sixteen, the path called
four visits in six "nothing recorded", and the timeline either said "no entries" or — with a marker
in range, which the filter does not touch — nothing at all. Each now draws `filterNote`: emptied,
"nothing matches the filter `…` — N calls and M signals hidden", or on the waterfall, which draws
only calls, "no calls match the filter `…`" — under `tag:screen` "nothing matches" was false of the
six screens it matched; thinned, "N calls and M signals
hidden by the filter `…`" above the rows, both with the list's own `clear filter` button. Hidden
calls are the route's own two numbers, `sessionTotal` less `matched` — never a difference of the rows
held, because the filtered list stops at the clamp: on a 3016-call session that read "1000 calls
hidden" where 3016 were, and drew no note for a filter matching 1200. A brushed stretch has no route
number, so there the held rows are counted from the oldest mono at which both lists are still whole,
and the note says "at least" when the stretch reaches below it (`hidden past the clamp`,
`stretch past the clamp`, `hidden when none match` and `path N past the clamp` in
`check-web-static.js`, over a 3016-call stub clamped as the daemon clamps). Hidden signals are the
whole session's rows less the filtered ones, within a brushed stretch where the pane brushes, and
without `screen` signals on the path, which draws every screen whatever the filter. A visit the
filter emptied reads "0 of N match", N counted from the same skeleton over the whole session, and
"nothing recorded" is said only where nothing was. Neither is said of a visit holding a call older
than the oldest the list holds while the list is short of what the route matched: the list stops at
the clamp, so whether those calls match is unknown, and on the 3016-call session under
`path:/v3/shop`, which matched 3015, five visits read "0 of N match" over calls that matched. Such a
visit says "calls not loaded", and one with some calls drawn adds "older calls not loaded" in the log;
the visits are asked of the whole-session skeleton, so a visit with no call below that point is not
marked. With no filter the same visits had read "nothing recorded". The note then says
"newest 1000 of 3015 matches loaded", which is the number the header counts — beside "1 call hidden"
it had left 2015 calls unaccounted for (`path clamp …` in `check-web-static.js`). The waterfall's stubs
do not take this rule yet: a visit there whose calls were not loaded still reads "no matching calls". The timeline decides it is empty on calls and
signals, never markers. A stub visit inside a waterfall that still has rows reads "no matching
calls"; the waterfall as a whole never did, which is what this paragraph used to claim. An emptied
waterfall asks in a fixed order: a filter that hid calls in range, then a brushed stretch in a session
with calls, and "no calls were recorded" only of a session with none — the stretch was asked of the
filtered calls, so a filter plus an empty stretch called a session with calls empty. The live
probe's lines from `waterfall emptied` to `filter panes tabs` press each pane's button and
check the tab bar never moves; `waterfall stretch`, `waterfall order` and `waterfall calls` hold
the order and the wording, the stretch set through `setTimeRange`, which the brush calls. Where the note sits is layout, which jsdom does not do, so it was checked in
headless Chrome at 1440 and 400 wide: the timeline's empty note is above the timeline, and an
empty timeline does not follow its foot under live tail, or the note scrolled out of sight; the
thinned note is sticky, and the path's rides in one sticky box with the map.

**Nothing sticky in the list pane stops above the scope bar.** The bar is sticky at the top of
`#list-pane` and is a standing claim about every path below it (`shared-rules.md`), and every view
in the pane sticks something of its own: the timeline's filter note, the path's map, the waterfall's
group heads, the new-visit pill. Each stuck at `top: 0`, so whichever came later in the document
drew over the bar — the timeline's note under any filter, the path's map at 400 wide. They stop at
`--sticky-top` now, which is the bar's height while it is drawn and 0 otherwise, set by
`#scope-bar:not([hidden]) ~ *`; the bar's height is fixed so the offset is a number, not a
measurement. `check-web-static.js`'s `sticky top` holds it from the stylesheet text, because jsdom
has no layout and a computed check would pass whatever the CSS says: every selector declared sticky,
other than `.scope-bar`, must take any `top` it declares, in any file and any `@media` block, from
`--sticky-top`. It was proven red by putting `top: 0` back on `#timeline > .filter-note`, and it
landed before the overview line, the first band added under it. A heuristic over selector text;
among what it misses is a `top` set for the same element through another selector. The timeline is no longer its own full-height scroller: beside the bar it made the pane
overflow by the bar's height, live follow scrolled the pane that far, and the bar sat on the first
row while the timeline's own foot was never followed. It flows in the pane as the list does. That
made its follow move the whole pane on every redraw, so expanding a run in the middle of a 3016-call
session threw the reader to the end — 24 to 29934 oldest-first, 29800 to 0 newest-first. It now
follows as the path's log does: on opening the view, and on a redraw only when the pane was already
at the end it follows; a run opened or closed never moves it (`renderTimeline({ follow: false })`).
The list's own follow moved the same pane, and it ran whatever view was shown: on the 3016-call
session one live call or marker moved a timeline scrolled to the middle from 14927 to its end
oldest-first and to 0 newest-first, and a path or a waterfall the same way. It follows only while
the list is shown now. The order toggle, a load and a brushed stretch had followed on the timeline
only because that follow moved the pane first, so each sets `state.timelineFresh`. The waterfall has
no follow of its own, so it no longer moves under live tail at all. `check-web-static.js`'s live follow
section drives the real file over a stub pane in both orders, red at `a86dde2` on the run and on a
row arriving while scrolled back; its `socket …` and `timeline asked follows` lines send calls,
markers and signals through the real `05-network.js` handler with the timeline, the path and the
waterfall shown, red at `8082e02` on all three views scrolled back. KIN-38's and KIN-41's notes record
the headless Chrome numbers. A
clicked station scrolls its visit to just under the sticky box by measuring the box at click time;
the fixed `scroll-margin-top: 96px` it replaced did not count the filter note, and landed the row 19
to 33px under the box. Layout again, so not a probe: KIN-33's notes record the headless Chrome
measurement — the element at the bar's centre, the first and newest timeline rows, and each
station's row against the box, at 1440 and 400, with and without a filter — red at e1711a3 and
green after.

**Each traffic view keeps its own place in the shared pane while live tail is paused.** Since KIN-33
the list, the timeline, the waterfall and the path scroll one pane, so a list left at row 5000 opened
the timeline at 5000 of 29,800, a place nobody chose. Leaving a view now saves the pane's position
under its view id, and opening it restores that, or the view's own first-open rule. Only while
paused: with live tail on each view follows as before, and KIN-38's rule that opening a run never
moves the pane is untouched. A new session, filter, order or stretch drops every saved place, since
each is a new list; the places are kept with the list they were saved on and dropped the first time
a view opens on another, so no loader has to remember to clear them. `views keep place` drives it in
jsdom, which holds an assigned `scrollTop`, and Chrome confirms it on the 3016-call session (`room
views keep place`); both were red with the restore removed.

**A closed drawer is hidden from the keyboard and from the page's width, not only moved off screen.**
Below 1200px the closed pane was translated past the right edge and nothing else, so the document
was 1646px wide at 1000, Tab reached thirteen controls inside it, and focusing its `✕` scrolled the
window 646px sideways, half-opening the drawer with no scrim. It is `visibility: hidden` once its
slide ends, which takes it out of the tab order with no breakpoint in script, and `#panes` is
`overflow: clip`, not `hidden`, because a `hidden` box can still be scrolled by focus or a swipe. Its
shadow applies only while open. `closed drawer css` reads the rules; `measure-web-ui.js`'s
`tab never enters closed` and `doc width closed` are the behaviour, red with each rule removed.
The `✕` sits in a tools row above the detail header, in flow, rather than absolutely placed over its
top right corner, where it covered `replay ▾` served and `copy for agent` in an export
(`room 1000 close disjoint served` and `… export`, red with it placed absolutely again).

**A narrow row gives up its clock and host before its path's last segment, its byte count or its
repeat mark.** At 400px paths were cut to four characters while the fixed columns kept full width.
The path is drawn as a head that ellipsizes and a last segment that never shrinks; the repeat mark is
its own item, reading `2×` below 520px, the overlay's form, because a tint alone does not say how
many times. `room 400 rows` measures every row of the probe session, red with the 519px row block
removed; `narrow row css` reads the rules, and the live probe's `row parts` holds that the segment is
the last one of the path the row shows, after the scope bar's prefix, and that both forms of the mark
count the calls.

**The now strip and the time axis are one overview line.** Two bands of the seven above the first
row said one thing each; the axis keeps its brush and its ids, and the now list opens as a popover
instead of pushing the rows down. The line is hidden when both of its parts are, except while the
whole-session copy is read, when the hint holds it and says so (KIN-42, above), and hiding the now
strip closes its list, so a popover left open never comes back over another view's rows. The hint
counts the session's calls, as the bars do: the live probe's `overview line` reads it under a filter
that thins the rows, red with the count taken from the rows held. `room 1440 bands list` bounds the
list pane's top at 160px. The hint's words are whole at 1440 and 1000, because the axis is the part
that gives: its bars stretch to any width, and a count cut to `16 calls in this session · 2.1s — drag
to …` no longer says what it counts. The axis has a zero flex basis for that; with `auto` it claimed
the svg's own width and the two shrank in step (`room <w> hint whole`, red with `auto` put back).

**Every count on the traffic page says what it counts, and every tab counts the session.** One
1203-call session once showed four numbers: the tab's `traffic 1000`, the header's `newest 1000 of
1203 shown`, the scope bar's `1203 of 1203` and the axis's `1203 calls`. The traffic tab counted the
rows held while the signal tabs counted the session; now it counts the session too, as `allSignals`
does for the signal tabs. The scope bar says `calls under this prefix` (`N/M under prefix` below
520px), the overview `calls in this session` and in a stretch `calls in this stretch`, counted from
the whole session as its bars are, the glance `calls in session`, and the header keeps its own
wording for drawn rows. `counts agree`, on the capped long session live and exported, fails when a
count matches no label or two counts of the session differ; its label table holds the header's and
the list note's match wordings too. It was red with the scope count bare and with the tab counting
rows. The live probe's `live header filter` asserts the tab's session count under a filter, where it
used to assert the filtered rows. `topbar <w> count` in `measure-web-ui.js` holds each header
wording to one line at 1000 as well as below, where the toolbar has no room for it.

**The toolbar is one row from 1000px up, with endpoint chips behind `endpoints ▾`.** It was two rows
because the chips are the only part whose length depends on the session, and sharing a row made
the order control move for every app. Behind a menu they can never push it, and the quick filters,
the order control, markers and `⋮` all fit one row at 1000. Below 1000 it wraps rather than hide a
control. The button counts the chips its popover holds, from the whole session as the chips are, so
the filter a chip sets does not shrink it (`endpoints menu`, red with the count taken from the
filtered rows). `⋮` holds the four exports, which left the top bar, the shortcut legend and compact
rows; in an export its exports are hidden by `body.archive` with every other control that would ask
a daemon for something (`export items hidden`). Row density has one writer, `setDensity`, behind the
`⋮` item and the Settings page's control, stored as `inspector.density`; compact takes padding off a
row and never makes text smaller (`density`, red with the Settings control writing a key of its own).
`room <w> toolbar one row` at 1440 and 1000 and `room <w> toolbar fits` at 600 and 400 measure it in
Chrome.

**`#/txn/{id}` is the detail pane given the page, not a second rendering of a call.** It reuses
`renderDetail`, so the evidence on it is the evidence everywhere else, and it reads a call past the
list's ceiling from the single-call route rather than saying it does not exist. The call is looked
up in the whole-session copy, then the rows the list holds, and only then read; an export has no
route and embeds every call, so there a missing id says `not in this export`. `route parse` holds
the address, the live probe's `txn route` and `txn route past ceiling` the page, Back and Escape,
and the archive probe's `txn route in export` the export, the last two red with the lookup made in
the list's rows alone and with the page's class not set.

**The web UI parses JSON itself, and `JSON.parse` is the thing it is avoiding.** `JSON.parse`
turns every number into a double, so a 64-bit id `1234567890123456789` came back as
`…800` — a different id, shown confidently in a debugger — and it also hoists integer-like keys to
the front of their object and keeps only the last of a duplicated key. `prettyJson` did exactly
that round trip for as long as it existed. `parseJsonTree` keeps each scalar's source text and each
object's entries in order, and `printJsonTree` reproduces `JSON.stringify(x, null, 2)`'s layout
from it, so the tree, raw and copy are the captured bytes re-indented and nothing else. It is
strict on purpose: anything `JSON.parse` rejects falls back to the text as it arrived.
`check-web-static.js` lifts both functions, by their `jsonParser` anchors, out of whichever manifest file holds them
and checks the ids, the order, the duplicates and the rejection cases, in CI, with nothing installed.

**The JSON tree's hidden fields are a view, never an edit.** Copy, raw and every export read the
body, not the rows — a field that silently went missing from a copied body would be the viewer
editing the evidence. Two more rules: the tree is never drawn for a **truncated** body, because a
prefix that happens to parse would be presented as the whole; and its key handler stops `f`, `h`
and the arrows from bubbling, because `f` also means "focus the filter box" to the page. The
view-only copy and the key handling each have a check in `render-web-ui.js` that was proven to
fail when the rule was broken; the truncation rule has none yet, because the script drives whatever
session is on the machine and a truncated JSON body is rare there.

**A stopped daemon's banner offers no restart, and the page keeps redialling.** The design pass
proposed a `restart daemon` button on the banner; the process that would receive it is the one that
just exited, so it could only ever fail. The banner says to run `inspector serve` instead, and that
is enough because `ws.onclose` still redials every 3 s while stopped — it used to return early,
which left a page that had stopped a daemon unable to notice it coming back without a reload.
`render-web-ui.js` stubs the stop request (never the real daemon; the probe is in
`lib/shell-probes.js`), checks one click only arms, and replays the page's own `onopen` to prove the
banner, the dimming and the disabled controls all clear. "Last capture" names a row this page watched arrive from any session; failing that it says
"newest row in this session", because the newest row of an old session is not the daemon's last
capture and must not be presented as one.

**The top bar has two dots and they mean different things.** `live` is whether the *list* follows
new rows; the dot in the daemon group is whether the *page is connected*. They were one checkbox
and one dot, which read as "the daemon is up" whichever it was describing. Pausing drops live rows
rather than queueing them, so resuming re-reads the session — without that, a list resumed after a
burst is missing the burst with nothing on screen to say so. The probe watches for the re-read.

**The page is a rail over hash-routed pages, and the rail holds navigation and nothing else.**
`#/traffic[/<view>]`, `#/sessions`, `#/processes` and `#/settings`, each optionally ending
`?session=<id>`, are read by `parseRoute` and written by `routeHash` in `js/08-routes.js`, and
nothing else reads `location.hash`. Hash routes keep the one `index.html` and no build step, make
Back walk the pages, and give a view and a session an address. A page or a tab pushes a history
entry; a session switch replaces it, because Back walks places, not data, and a pushed switch would
make Back re-run a whole-session read. The page had a left rail once, holding the filter and a Now
panel, and it was taken out because it spent a fifth of the window on controls that did nothing on
most views — the Now panel alone ran 1341px. This rail is navigation, which applies on every page,
so `render-web-ui.js`'s `rail is navigation` fails if `#rail` holds anything but its links and its
collapse button. A view control put back there is a red run, not a precedent. In an export the rail
offers Traffic and Stats, which read only the embedded session, and every other route lands on
Traffic, because the other pages send something or read the machine (`pages hidden in export`,
`routes fall back in export`, `stats rail in export`); `route parse` in
`check-web-static.js` holds the parser. An export is a file opened from disk, where some engines,
jsdom among them, refuse the history API, so there the address is written through `location`
instead (`writeHash`).

**Every request to a control route goes through `controlFetch`, the one place the page writes
`X-Inspector-Control`.** Six call sites wrote it by hand. The marker post is not one: its route is
open by decision (`daemon-and-mcp.md`) and it sends no header. The header is how the daemon tells its own
page from any other, and whatever the daemon later asks of a control request has to be added in one
place on the page, as it is added in one gate on the server. `control header once` in
`check-web-static.js` counts the literal across every file `web/scripts.txt` lists and fails on any
count but one.

**Every close that is not a stop paints the lost-daemon banner, and a restart that has not
reconnected in 20 s is a lost daemon.** `ws.onclose` painted the banner only on the way out of
`running`. A plain drop still showed it, and that was the case everyone tried; the defect was in the
others: a restart that never came back read `Restarting…` forever, and a stop or restart request that
failed while the daemon was gone replaced the banner with its own message, which no later close
repainted. `RESTART_GRACE_MS` is the time a relaunched daemon gets before the page says it is gone.
The stop probe's `banner every drop`, `banner after failure` and `banner restart lost` replay the
page's own handlers; the last two were proven red with the `running` check put back. A stopped
daemon's banner still offers no restart (above).

**The closed session picker leads with the start time, and the native `<select>` sits invisible over
a drawn face.** Since KIN-49 the picker gives way first when the bar runs short, and a closed
`<select>` clips its label from the end, mid-word, which is where the label kept the start time — the
one part that tells two sessions of one app apart. A `<select>` cannot ellipsize. So the select keeps
the focus, the keyboard, the native list and every probe's `change` event, and `.picker-face`
beneath it shows the time first and whole, the rest ellipsized, the full label as its tooltip
(`picker face`; `shell <width> picker time` in `measure-web-ui.js`). The Sessions page's row does
the same. Its label was the app then the time, and at 400px both of the probe fixture's rows read
`com.example.pr…` with no time at all (`session row time`; `polish <w> session time whole` in
Chrome).

**Below 700px the tab bar draws the active tab and a `views ▾` menu of every view.** "The tab bar is
always drawn" still holds: the band never comes and goes. Which tabs it draws inline is a width
decision, and the menu lists every view with its count, so none is out of reach at 400px. A CSS
rule rather than a measured overflow, because jsdom cannot measure and a rule can be read
(`narrow tabs css`, `tabs menu`, and `shell <width> tabs reachable` in Chrome).

**`endedAt` and "is anything connected" are different questions, and `GET /api/recording` is the
second one.** A session whose daemon was killed, or whose app vanished without a clean close, has
no `endedAt` and no open connection either — so anything gating on `endedAt` offers a control that
the daemon then refuses with a 409, on exactly the sessions where the app went away. Which is when
somebody most wants to mark where it happened. The web UI's add-marker form asks the endpoint.

**The list's display order is `orderedRows()`, and both rendering and `j`/`k` go through it.** If
navigation computes its own sort, `j` moves up the screen the moment newest-first is on. Marker
dividers are interleaved oldest-first and the whole sequence is then reversed, so a divider stays
attached to the same rows; `scripts/render-web-ui.js` asserts the sequence is an exact mirror.

**The Stats page computes nothing, and a row opens Traffic on a superset that says so.** Routes,
terms, the order, the rates and the `n<20` cell all come from the daemon, so the page has no
mirror block and no case file: there is nothing to keep in step. It has its own filter field,
because a row click replaces the Traffic filter with the row's term, and a shared field would
re-filter the Stats page to one route on return. A row sets Traffic's filter through
`applyFilter`, and `#filter-caveat` says the list "may include paths the route keeps apart" for
exactly as long as the filter is that term: any other filter clears it, so typing the term back by
hand is not a list a row opened, and so does a switch to another session. It sits under
`#filter-error`, outside the toolbar's one row. It is one row in `PAGES`, and its `enter` is an
arrow, because `PAGES` is built when `08-routes.js` loads, before the stats file has declared
anything. In an export its rail link stays, because it reads only the embedded report. Live, it
asks again at most once a second and never twice at once, the `oweReread` rule, and only while it
is shown and live tail is on; a switch through `switchSession`, a changed session list, resuming
and the socket opening after an absence read it again. In an export its rows are inert and its
headers do not sort, because both need the daemon. The table scrolls sideways in its own box with
the route held at the left, so the page never does (`shell <w> page stats fits` in Chrome, red at
600 and 400 with the box's `overflow-x` removed). `check-web-static.js`'s `stats refresh …` lines
hold the refresh rule, each shown red with its check removed; the live probe's `stats …` lines hold
the page and the caveat, `stats n<20` and `stats long session whole` red against a daemon with the
threshold at 0 and its fold cut at 1000 rows, and `stats restored` leaves the page as it found it,
proven by breaking the next probe's guard (`chip marks active`).

**Under 700px a route cell breaks only between whole pieces: a server after a `.`, `:` or `/`, a
path after a `/`.** The held column wraps inside a 150px cap, and `overflow-wrap: anywhere` broke
it anywhere: `api.qa.invali` / `d`, and `http://127.0.0.1:808` / `0`, which reads as port 808.
`statsBreakable` cuts each span into pieces with a `<wbr>` between them, never inside `[::1]` or a
run such as `://`, and never after a path's leading `/`. The narrow stylesheet holds each piece
`nowrap`, because a `<wbr>` alone still left Chrome breaking after a `-` inside a host label. So
`overflow-wrap: anywhere` put back on its own changes nothing now. Main's whole stylesheet put
back, or the `nowrap` hold removed, is what turns the reading red. The cap is on the label, where
`min-width: min-content` outranks it, so a piece wider than 150px widens the column inside
`.stats-scroll`. On the cell, Chrome held the column at 150px and the piece painted over the
numbers. Above 700px the `<wbr>`s are `display: none`, because Chrome breaks at one even under
`nowrap`, and the wide route went to three lines. `measure-web-ui.js` reads where Chrome put each
line, per character, on the `routes` session (a port, `[::1]`, a hyphenated label, a long segment):
`stats 400 route breaks served` and `… export`, `stats 400 routes whole …` (nothing cut, document
no wider than the window) and `stats 1440 routes one line served`. Each found-nothing branch fails
and was shown failing with the servers drawn under another class. The breaks were red on main,
with main's stylesheet and with the hold removed. How many breaks main makes depends on the fonts
Chrome finds (29 on one machine, 30 on another); the break that reproduces is
`http://127.0.0.1:808|0`. `whole` was red with the cap back on the cell; `one line` was red before the wide rule. `check-web-static.js`'s `stats
route pieces` holds the cut itself, red with brackets ignored and with the leading `/` cut.

**Every DevTools command in `scripts/lib/measure-cdp.js` fails after 30 s (`SEND_DEADLINE_MS`), about 40x the slowest single command measured in a full run (718 ms, `head run`, 287 readings, 160k-call cases included, macOS, 2026-10-22), so a wedged renderer fails the run instead of hanging it. Long loads wait in `waitFor`'s polls, never in one command.** (Decided 2026-10-22: kept on the architect's recommendation.)
