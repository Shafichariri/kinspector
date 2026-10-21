# Decisions — shared rules
Owned by `:inspector-model`: rules both surfaces draw, their mirrors in the web UI, the filter grammar and the palette. Imported by the `inspector-model`, `inspector-ui` and web guides.

**Mirror cases live once, at the repository root, and both sides run every one.** Eight rules in
the web UI copy a Kotlin authority (the eighth, `urlOf`, was a helper with no cases of its own until
IPv6 bracketing), and for five of the first seven "keep them in step" was the only thing
holding them. When the cases were first written, four drifts and a minor fifth had already opened:
three inside mirrored functions (the repeat count, a run's id, the chip list's final tie), a
`?` dropped from an empty query by the `urlOf` the duplicate key is built from, and one call site —
the timeline tab — that bypassed a mirror which was itself correct. So each rule's cases are
one JSON file in `mirror-cases/`, read by `MirrorCasesTest` in `:inspector-model` (or
`HopByHopMirrorTest` in the daemon, for `Replay.kt`'s list) and by `scripts/check-web-static.js`
against the function lifted out of the real web UI. A case added on either side is run on the
other; that is the point, and a case that only one side passes is a drift with the Kotlin answer
right. Rows are partial and merged over `_defaults.json`, then decoded through the **real
serializer with unknown keys refused** — the archive's reader ignores unknown keys so a newer device
can talk to an older daemon, but a fixture has no such excuse, and a misspelt `calId` silently
falling back to the default would make a case assert something other than what it says. The Kotlin
runner is `jvmTest` because it reads files from the repository; the commonTest suites stay, since
they are what runs on iOS and Android. Both Gradle test tasks receive the folder through a
`jvmArgumentProviders` entry that declares it an `@InputDirectory` with
`PathSensitivity.RELATIVE`, and that is load-bearing twice over: without the input declaration,
adding a bad case file left `jvmTest` up to date and green — measured, with the property marked
`@Internal` — and with an absolute `systemProperty` instead, the cache entry depended on where the
checkout lived.

**A lifted block is bounded by `// @mirror-begin <name>` and `// @mirror-end <name>`, never by a
neighbouring comment.** The `visits` probe used to lift from one section header to the next
function's doc comment, a boundary that moves whenever somebody adds a function and that lifts too
much or too little without saying so. Anchors are explicit, each name must appear exactly once as a
begin and once as an end in the same file, and anything else is exit 2 naming the block — a harness
that cannot run must not read as one that found nothing. An anchored block no rule uses, or a known
rule with no case file, is exit 2 for the same reason; a case file that names an unknown rule, holds
no cases or is misnamed is a failed check (exit 1) in `check-web-static.js` and fails
`MirrorCasesTest` too, so it is red on both sides either way. `computeDuplicates` moved beside
`duplicateKey` so the duplicates block could be one contiguous range.

**Repeated-request detection lives in `:inspector-model`, and the web UI mirrors it.** Both rules
in `duplicateGroups` are load-bearing. A **different `callId` is required**, because redirect hops
and retry attempts share one and are a single logical call already shown as an attempt chain —
without that rule every retry lights up, which is noise *and* a false description. And **headers are
excluded from the key**, because a signed app puts a fresh nonce and signature on every request, so
a key including them would find nothing on exactly the apps this helps most. Timing uses `mono`,
never `ts`, per `docs/schema.md`. The key is method + URL + status + both byte counts, which
identifies rows that are *indistinguishable at row level* — a weaker claim than byte-identical
bodies, and the doc says so rather than overselling it. The web UI's `duplicates` block mirrors the same
algorithm, and `mirror-cases/duplicates.json` holds it there: both sides run every case, so the two
cannot drift without `check-web-static.js` or `MirrorCasesTest` going red. Including the count —
`computeDuplicates` said `run.length` in every release from v0.1.0, where both copies first shipped,
through v1.3.0, so a retry inside a repeat read "sent 3x" for a call the app made twice.

**The scope bar's rule lives in `:inspector-model`, and the web UI's `pathScope` block mirrors it.** `pathScope` began
in `:inspector-ui`'s `Formatting.kt` when the overlay was the only surface that lifted a shared
prefix out of its rows. The web UI now draws the same bar, so the rule moved beside `timeline` and
`endpointShortcuts` for the same reason those did — three thresholds and a segment walk are exactly
what drifts invisibly, and a bar that appears on one surface and not the other, for a session both
are showing, reads as a bug in whichever one you are looking at. `PathScopeTest` in that module is
the authority; the copy in the web UI's `pathScope` block follows it, and `mirror-cases/pathScope.json` — one case per
rule and per threshold — is what makes it follow.

The bar is **sticky on both surfaces, and that is load-bearing.** It is a standing claim that every
path below it is missing the same front, so a stripped path read after the bar scrolled away is not
merely unhelpful — it names an endpoint nobody called. The host appears on a row only when it is
not the one the bar claims: repeating it everywhere is noise when it never changes, and
load-bearing on the one row where it does.

**Endpoint chips filter with an anchored glob, not a substring.** A chip labelled `profile` emits
`path:` + `*/profile`, because a bare `path:profile` term is a substring match and would also match
`/v3/accounts/profile/status` — a chip that does not mean what its label says. `globMatches`
anchors the trailing literal with `endsWith`, so the term matches only paths ending in `/profile`.
Chips are ordered by call count rather than recency on purpose: recency reshuffles the whole row on
every
request during live tail. Ties break on the most recent call, then on the segment by **char code**,
so the order is deterministic — `mirror-cases/endpointShortcuts.json` holds both sides to it and
`render-web-ui.js` asserts it on the rendered chips. Char code, not `localeCompare`: the web UI used
the latter and put `accounts` before `Balance` where the model and the overlay put `Balance` first.
That order reads a little oddly and is kept anyway, because it only applies after a full tie and
changing it is a change to the model rule.

**Method colours avoid the status hue family.** Green/amber/red belong to 2xx/4xx/5xx. An amber
PUT next to an amber 404 reads as "this row failed", which is the one thing a traffic list must
not get wrong. So GET is neutral grey, POST green (it is not a status), PUT purple, PATCH teal,
DELETE red because destructive is what red should mean.

GET was the accent blue until the palette pass. It is most of the rows, so blue meant "a read"
nearly everywhere and could no longer mean selection, a link or the active tab. PATCH was amber
"only because it is rare", and it moved anyway so that amber means 4xx and stale and nothing else;
the duplicate-row tint left amber for the same reason and now takes the PUT hue. Light-theme PATCH
is `#0f766e`, darker than the design proposal's `#0d9488`, which measured 2.9:1 on its own badge
tint.

**A transport failure is red, not grey.** 3xx moved to grey in the same pass, and grey was what
`.sx` and `transportError` used — so a call that never got a response would have looked exactly
like a routine redirect. Both surfaces draw it in the error red now. The overlay pill therefore
colours its dot muted when there is no call yet rather than asking `forStatus(null)`, or an app
that has made no request would show a failure.

The web UI's tokens and `InspectorColors` are one palette, and **`PaletteParityTest` enforces it**:
it reads `web/css/00-tokens.css`, with the folder declared as a test input so a CSS-only change
re-runs it, and fails if a token it checks is declared in any other stylesheet in `web/css/` — the
folder rather than `web/styles.txt`, because `WebManifestTest` holds the two equal and `WebPage` is
the one Kotlin reader of a manifest; it fails naming
the token when either side drifts, proven in both directions. It replaced a "keep them in step"
comment that held only as long as every change remembered both files.

The method renders as a **tinted badge**, not coloured text: `MethodBadge` in the overlay, `.method`
plus `.m-*` on the web. Both tint at 0.18 alpha of the text colour rather than filling solid with
white text — solid is fine for GET's blue and unreadable for PATCH's amber in the light theme, and
one rule that holds for all five beats five exceptions. The web tokens are therefore bare RGB
components (`106 169 255`), so `rgb(var(--m-get) / 0.18)` yields the tint from the same token;
`color-mix(… currentColor …)` was the first attempt and was dropped because a silent failure on an
older engine drops the whole `background` declaration and the badge with it.

**The path, the waterfall and the axis band are one rule: `visits()` in `:inspector-model`, mirrored
in the web UI's `visits` block.** The waterfall had its own segmentation in the web UI before the path view existed, and
it was wrong in the way a second copy usually is: it read the *filtered* signals, so typing `path:`
took every screen away, and it dropped any screen with no matching calls, which draws a route that
skips a screen the user was on — a navigation nobody made. Three surfaces now take one skeleton.
`VisitsTest` is the authority; its cases live in `mirror-cases/visits.json`, which `MirrorCasesTest`
runs against the model and `check-web-static.js` against the mirror lifted out of the real file, so
the copy cannot drift without one of them saying so.

Four rules are decisions, and each was argued before it was built:

- **Strictly by start, no grace window.** A visit owns `[from, to)`. A `screen` signal that lands a
  few ms after the calls its view-model fired puts them on the previous screen, and that is left
  alone: any window would also move the `POST` that *caused* the navigation onto the screen it
  navigated to. The fix is in the app — emit at navigation commit — and `INTEGRATION.md` §12c says
  so. A call that outlives its visit stays put and says where it finished.
- **A same-millisecond tie goes by capture `seq`.** `onClick { tap; navigate() }` lands both in one
  millisecond, and a tie handed to the arriving screen put the tap on the screen it led to — seen
  in the sample's own route on the first look at the map. `seq` is one counter in `Clocks.kt`,
  taken right after `monoMs()` at every capture site; when either side lacks it the tie still goes
  to the arrival, so older archives read exactly as before.
- **The skeleton ignores the filter.** `state.screens` is taken from the whole session
  (`state.allSignals`, see `web-ui.md`) whatever the filter is, and the leading "before first screen" visit is skeleton too, so `visits()` takes
  `sessionStart`. Without that, a filter matching nothing at launch removed the launch column —
  found by the probe on its first run, which is why it is a parameter rather than a comment.
- **Consecutive same-name screens are one visit.** Later ones are `repeats`, drawn in place as a
  step with what changed in the payload — a double-firing effect is sometimes the bug.
- **The path ignores the order toggle and hides it**, because reversed it reads as the user walking
  backwards; and **a failed call is never hidden** — a collapsed log line names every failure as a
  chip, and an expanded one floats failures above its `+N more`.

The layout is a sticky map of stations over a log with one line per visit. The first build was a
row of tall side-by-side columns; the user judged it, four alternatives were mocked up, and this
was the one chosen. The map answers "where did they go" without scrolling, the log reads like the
story, and live growth is a station appended and a line added rather than another column offscreen.

The order control is hidden on the path with `hidden`, and `.seg2` sets `display`, so it rendered
anyway — the `[hidden]` trap again, invisible to jsdom and found only by looking in a real browser.
`.seg2` is now in `auditHiddenPanes`. Live follow and the no-yank pill need layout and so were
verified in a browser, not by the probe: driven by a script on `WS /ingest`, the first (column)
layout followed new visits at its end, held still when scrolled back, and announced the new visit
instead. The map-and-log layout was seen following live; its scrolled-back case was not exercised,
because the test route was too short to overflow the pane.

`auditDrawerCss` used to read the first `max-width` block in the stylesheet; the path's narrow rule
landed earlier in the file and the audit failed on CSS that had not changed. It now finds the block
that lays out `#panes`.

**Markers interleave through `timeline()` in `:inspector-model`, and newest-first is a reverse,
never a descending sort.** Both surfaces draw markers between the rows they introduce, and the only
arrangement that makes causal sense — "a marker, then what happened after it" — is expressible
only while ascending. Reversing the finished sequence keeps each divider attached to the same
rows; sorting descending attaches it to the rows *before* it, which is the same pixels claiming
the opposite. The two agree on every session where no marker shares a `mono` with a call, so this
is a bug that passes casual testing: `TimelineTest` pins it with a tie, because a marker dropped
immediately before a request is exactly what a tie looks like on a millisecond clock.

A marker at a call's `mono` is placed **before** it — `<=`, not `<` — because a marker is
dropped just before the thing it marks. Markers after the last call are still emitted: "I marked
it and then nothing happened" is an answer, and a divider that appeared only once the next
request arrived would hide it. The web UI carries a mirror, `timelineEntries` in its `visits` block, held to the model by
`mirror-cases/timeline.json`, and both the list and the timeline tab draw through it. The timeline
tab did not until phase A: it sorted all three streams in one stable sort with markers added last,
so a tied marker landed after its call there and before it in the list beside it. A mirror case
could not see that, because the mirror was right and the tab did not call it — so
`render-web-ui.js` checks the tie on the rendered tab as well. The MCP `timeline` and `explain`
tools order through it too, never by a sort of their own, and `explain` splits before/after by
position, not by `mono`. `SignalToolsTest` compares the `timeline` tool with the live model entry
by entry, and `TransactionToolsTest` checks that a marker at the call's own millisecond lands in
`explain`'s `before`.

**A marker chip's filter term is asked of the parser, not assumed.** `since:marker("…")` quotes
the label and the grammar has **no escape inside the quotes**, so whether a label is expressible
turns on the *parity* of its quote count, not on whether it contains one: the tokenizer toggles on
every `"`, and `parseMarkerRef` recovers the label by prefix/suffix match rather than by parsing
quotes. So `say "hello"` round-trips intact and `it"s` does not — which nobody would guess from
reading either. The chip list therefore filters labels by running `FilterParser.parse` over the
term it would emit, so it follows the grammar rather than a snapshot of it. The *divider* is drawn
for every marker regardless; it is only the chip that cannot work.

**`path:` filters are dual-mode** — glob when the pattern contains `*`, substring otherwise. A
bare `path:/v2/users` typed in a hurry should find `/v2/users/me`.

**`since:marker("…")` with an unknown label matches nothing**, not everything. A typo silently
becoming "no filter" is the more dangerous failure while debugging.
