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
list itself still stops at the clamp; that is `item:web-transactions-paged`.

The two cached copies have gone stale in the ways below, and all but the last are closed; this is
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
the filter just typed (`older call load dropped`, `older signal load dropped`). Open: a live row that
arrives while the whole-session read is between pages is in neither the read nor the copy, because a
live row joins a copy only once the copy is marked as the session's, and the mark is set when the read
lands. Paging widened that window from one request to every page. `item:web-transactions-paged` owns
it.

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
notes. Holding that many rows at all is a cost this does not settle; `item:web-transactions-paged`
owns whether the whole-session read is capped.

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
measurement. The timeline is no longer its own full-height scroller: beside the bar it made the pane
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
`render-web-ui.js` stubs the stop request (never the real daemon), checks one click only arms,
and replays the page's own `onopen` to prove the banner, the dimming and the disabled controls all
clear. "Last capture" names a row this page watched arrive from any session; failing that it says
"newest row in this session", because the newest row of an old session is not the daemon's last
capture and must not be presented as one.

**The top bar has two dots and they mean different things.** `live` is whether the *list* follows
new rows; the dot in the daemon group is whether the *page is connected*. They were one checkbox
and one dot, which read as "the daemon is up" whichever it was describing. Pausing drops live rows
rather than queueing them, so resuming re-reads the session — without that, a list resumed after a
burst is missing the burst with nothing on screen to say so. The probe watches for the re-read.

**`endedAt` and "is anything connected" are different questions, and `GET /api/recording` is the
second one.** A session whose daemon was killed, or whose app vanished without a clean close, has
no `endedAt` and no open connection either — so anything gating on `endedAt` offers a control that
the daemon then refuses with a 409, on exactly the sessions where the app went away. Which is when
somebody most wants to mark where it happened. The web UI's add-marker form asks the endpoint.

**The list's display order is `orderedRows()`, and both rendering and `j`/`k` go through it.** If
navigation computes its own sort, `j` moves up the screen the moment newest-first is on. Marker
dividers are interleaved oldest-first and the whole sequence is then reversed, so a divider stays
attached to the same rows; `scripts/render-web-ui.js` asserts the sequence is an exact mirror.
