# Roadmap — done

Finished items moved out of `ROADMAP.md` word for word, grouped by the section they came from, each
with its done date. Section numbers and `§` references are `ROADMAP.md`'s; nothing here is live.

---

## 1. Bringing the overlay closer to the web UI

### Worth doing, cheapest first

Each of these is small and self-contained. They are listed in the order that buys the most per
hour of work, not in the order they were thought of.

- ~~**Render markers.**~~ **Done, shipped in 0.8.0.** Dividers in the list and a chip per distinct
  label under the filter field, applying and clearing `since:marker("…")` on tap. The interleave
  rule moved into `:inspector-model` as `timeline(…)`, so the overlay and `app.js` now describe
  the same arrangement in the same words — including the part that is easy to get wrong, which is
  that newest-first is the *reverse* of the ascending sequence and not a descending sort. One
  limit found on the way: the grammar has no escape inside its quotes, so a label with an odd
  number of `"` gets a divider but no chip.
- ~~**A wall-clock column in the row.**~~ **Done, shipped in 0.8.0.** `HH:MM:SS` in the device's own
  zone, on the metadata line before the method, which is the column order the web uses. Seconds
  and no milliseconds: the web has the width for `.mmm` and a phone does not. The zone conversion
  is an `expect`/`actual` for the UTC offset rather than a `kotlinx-datetime` dependency pushed
  onto consumers, and it uses the *current* offset — a session shorter than a DST transition is
  the only case, and it is the only case there is.
- ~~**Repeat count and span.**~~ **Done, shipped in 0.8.0.** `2× / 1.9s`, from `duplicatesById` in
  `:inspector-model`. The count is `callCount` and not `ids.size`, because a group that swept up a
  retry has more rows than calls and the question is how many times the app *asked*.
- ~~**Sort order toggle**~~, ~~**freeze the list**~~, ~~**quick-filter chips**~~ and
  ~~**endpoint chips**~~. **All done, shipped in 0.8.0**, and together, because they turned out to be
  one problem rather than four: a phone is 360dp wide and about 720 tall, the header, the filter
  field and the scope bar already spend four lines before any traffic, and a row each would have
  spent four more. They share **one horizontally scrollable strip** — the two view toggles lead
  and never move, then markers when there are any, then the four presets, then endpoints. Vertical
  space is the scarce one; horizontal is not.

  Freezing holds a *snapshot* of the rows and markers, not a flag. Capture keeps running and the
  ring keeps evicting, so a freeze that merely stopped redrawing would still lose rows out from
  under the reader — which is the thing they froze the list to prevent.

  `endpointShortcuts` and `endpointFilterTerm` went into `:inspector-model` beside `timeline`, so
  the count-not-recency ordering and the anchored-glob term are one rule rather than two. And
  `:inspector-ui` gained its first interaction tests: `compose.uiTest` in `jvmTest` only, because
  a toggle's behaviour is a transition and a render test can only photograph one state.

### The substantial piece: signals in the overlay

In dependency order, because each stage makes the next one cheap:

1. ~~**Collect `Inspector.signals`** and show a merged timeline~~ — **done, shipped in 0.8.0.** Traffic,
   signals and markers on one clock, in the list that was already there rather than behind a tab:
   a phone has no room for a second view of the same session, and the merged reading *is* the
   feature. On by default when the session has any, one tap off. *(Overturned: the overlay now
   opens newest first with signals hidden, and remembers whatever the reader last chose — see
   `ListViewState`. Opening it is almost always to see what the app just sent, and a reader who
   hid the signals had to hide them again on every visit.)*

   `timeline()` grew a `signals` parameter and a third `TimelineEntry` kind, which broke every
   exhaustive `when` in the repository — the sealed type doing its job. `timelineRuns()` collapses
   **adjacent** identical observations, which is what keeps a state holder firing on every
   keystroke from burying the traffic the merged view exists to correlate.

   One thing worth knowing before extending this: a run's members are in *draw* order, so in
   newest-first the head of a run is the latest, not the earliest. Anything that must mean the same
   in both orders reads `TimelineRun.earliest` instead — the id does, and has to, or an expanded
   run re-keys on every observation during live tail.

   Payloads are not drawn. A row cannot show a JSON object usefully at 360dp, and a truncated one
   would be worse than none; reading them is stage 2.
2. ~~**Per-tag browsers**~~ — **done, 2026-09-18.** Not a browser pane, which is a shape a 360dp
   screen has no room for: a **tag chip** narrows the list to one tag, and **tapping an observation
   opens it**. Payload, provenance, size, and the history of that key with the gap between each
   observation and the one before it.

   Three things came out of building it that the entry did not anticipate.

   **The overlay was not filtering signals at all.** It filtered the rows and handed `timeline()`
   every observation, so `path:/v2` thinned the calls and left the signals between them. The web UI
   has always passed its filter to the signal route. Fixing it is also what makes a tag chip work:
   `tag:` is a `SignalTerm`, so applying one drops every transaction — the exclusion rule turning a
   chip into a browser rather than a highlight.

   **A truncated payload is not the element it looks like.** `Recorder.emit` keeps the cut prefix
   as a `JsonPrimitive` *string*, so pretty-encoding it renders a whole cache snapshot as one
   quoted line with every quote escaped. `formatSignalPayload` unwraps it first.

   **Two layout defects and a misleading fixture were found by looking at the screenshot**, not by
   a failing test: a `Tag` field duplicating the header badge, two adjacent headings both reading
   "Payload", and tag chips sitting off the right-hand edge behind an unbounded list of marker
   chips. The render test now photographs the signal screen too.

   Left out deliberately: **faceting**. The web's facets are built from cache-payload fields —
   `storage`, `scope`, `expired`, `kind` — which is a browser-shaped control for a screen that is
   now one key at a time. A phone that has narrowed to `tag:cache` and opened an entry has already
   done what a facet chip is for.

   Not converged: `app.js`'s `browserGroups` does the same `(tag, name)` grouping as the model's
   new `signalGroups` *and* expands a cache snapshot's `items[]` into a row per entry. They agree
   on the grouping and differ in what they group, so this is not yet one rule with a mirror — see
   below.
3. ~~**The "now" strip**~~ — **done, 2026-09-19.** The latest observation of every `(tag, name)`,
   above the list, collapsed to one line by default: `now · 2 cache · 1 state — oldest 4m ago`.
   Open it for a row per key with its provenance and age; tapping one opens the observation, which
   is stage 2's screen doing the work stage 2 built it for.

   The sorting is the decision worth recording. `signalGroups` orders by recency and this does not:
   the strip is a **lookup** glanced at repeatedly, and a row that moves whenever the app mentions
   something else is one you have to find again each time. The list underneath is the feed. Two
   orderings of the same data, each wrong for the other's job.

   The age column is what makes the word "now" a claim rather than a label — most observations are
   pushed, so a panel headed "now" listing a value pushed at app start with nothing saying when is
   exactly the confidently-wrong reading `SignalTrigger` exists to prevent.

   Freezing holds the clock too, and `InspectorList` gained a defaulted `nowMsProvider` so that is
   *provable* rather than asserted in a comment: the ages come from the real clock and a test
   cannot move the real clock.
4. ~~**Pull a signal on demand.**~~ **Done, 2026-09-19.** No transport, as expected — but the
   internal API that needed surfacing was not the pull. `Inspector.answerSignalRequest` was already
   public, for `:inspector-stream`. What was missing was the **registry**: `signalProviders()`,
   which is the thing the host can never have.

   A daemon learns a provider's name only when one answers, so `app.js` infers the set from what a
   session holds and says in its own comment that it is guessing. In-process there is nothing to
   infer. Two things follow that the web cannot do: a pull is offered exactly where it will work,
   and a provider that has **never answered** is still findable — which is why the now strip lists
   unread providers. Without that, the pull button is unreachable for precisely the providers
   nobody has used yet.

   `pullSignal` is separate from `answerSignalRequest` rather than an overload, because a local
   pull correlates to no `SignalRequest`: the row carries a null `requestId` rather than an
   invented id pointing at a request that was never made.

   Found by looking at the screenshot again: the collapsed line counted four where the open panel
   drew five, and once the unread count was added the whole line ran off a 360dp strip — taking
   the staleness with it. The per-tag breakdown now gives way to a total past three tags, which is
   the first thing worth surrendering when the line is over budget.

### Going the other way

~~Three things the overlay has that the web UI does not.~~ **All three done, 2026-09-18.**

- ~~**The scope bar**~~ — `pathScope` moved from `:inspector-ui` into `:inspector-model`, beside
  `timeline` and `endpointShortcuts`, and `app.js` carries the mirror. Sticky rather than scrolled
  with the list on both surfaces, because it is a standing claim about what every path below it is
  missing: a stripped path read after the bar had gone names an endpoint nobody called.
- ~~**Creating a marker from the UI**~~ — and it turned up two things the item did not anticipate.
  `MarkerSource.USER` had existed with no caller, so every marker in every archive said `app` or
  `agent`; the endpoint now takes a `source`, defaulting to `agent` for the callers that came
  before. And gating the control on `endedAt` would have been wrong: a session whose daemon was
  killed has no `endedAt` and no open connection either, so the form would have been offered on
  exactly the sessions the daemon refuses. `GET /api/recording` answers the question that is
  actually being asked.
- ~~**Per-field copy buttons**~~ — one per header, per overview field and per body. Always in the
  DOM rather than conjured on hover, so they are reachable by keyboard; CSS keeps them quiet.

The smoke test grew probes for all three, each proved by deliberately breaking the feature. One of
those probes was worthless on the first attempt: it watched the marker list for a blank submission,
and the daemon refuses a marker on a session nothing is connected to, so the count stayed put
whether the client guarded or not. It watches `fetch` now — the claim is that the page does not
ask, so the thing to observe is the asking.

---

## 2. Real devices

### ~~First: the detection is wrong~~ — done, shipped in 0.8.0

The check matched `"generic"` in the fingerprint and `"Emulator"` or `"Android SDK built for"` in
the model, and a current AVD reports none of them, so every emulator session was archived as
`android-device`. It now reads the board and not the branding: `Build.HARDWARE`
(`goldfish`/`ranchu`) first, then `BOARD`, `DEVICE`, `PRODUCT`, and the model strings last.

`ro.kernel.qemu` was the suggestion here and it is still set on every AVD, but there is no public
accessor — `android.os.SystemProperties` is not in `android-36` — and `Build.HARDWARE` is its
public mirror, so nothing is gained by the hidden-API path.

The iOS side went further than "check it". `IS_IOS_SIMULATOR` is now `expect`/`actual` across
`iosArm64Main` and `iosSimulatorArm64Main`, so the answer is fixed at link time rather than
inferred from `SIMULATOR_DEVICE_NAME` being absent. That is what closes the gap this entry
described: a compile-time fact cannot be wrong on hardware nobody has tested on.

**Sessions already in the archive keep the wrong label.** Nothing rewrites them, so a session
folder older than this says `android-device` whatever it was.

### ~~Android over USB~~ — done, shipped in 0.8.0

`defaultDaemonHost()` returns `10.0.2.2` on an emulator and `127.0.0.1` on hardware, and
`connectionHelp(host, port)` replaced the single troubleshooting constant so the message names the
address that was actually tried. Recipe in `DAEMON.md` §2 and `INTEGRATION.md` §6f.

Verified on a booted emulator, which speaks the same adb protocol as hardware: `127.0.0.1:8099`
from inside the device was refused before the forward, answered `HTTP/1.1 200 OK` from the real
daemon after it, and was refused again once it was removed — with `10.0.2.2` still answering
throughout and the daemon still bound to `127.0.0.1` alone.

**Not verified on a phone**, because there is still no Android app module to install — see below.
The tunnel is proven; the app running inside it is not.

### ~~iOS hardware~~ — done over USB, 2026-09-24; released in 1.2.0

**Built through usbmuxd, and the authentication question below never had to be answered.** The
record of how it was decided is kept under the line, because it is still the reason a wider bind
remains the wrong fix.

**The spike came first**, because the design rested on one fact no documentation settles: whether a
usbmuxd connection reaches a listener bound only to the *phone's* loopback. Tested on an iPhone 12
Pro with a throwaway app that listened on `127.0.0.1:8100` and a 40-line usbmuxd client:

| Test | Result |
|---|---|
| Host talks to usbmuxd directly — no `iproxy`, no Homebrew | ✅ the phone is listed twice, `USB` and `Network` |
| USB route → the loopback-only listener | ✅ connects; on the phone the peer is `127.0.0.1` |
| 8 MB echo through the tunnel | ✅ byte-identical, about 33 MB/s both ways |
| Network route → the same listener | ❌ refused, though it reaches lockdownd — it dials the LAN address |
| A closed port | refused cleanly (`Number: 3`), so "app not running" is distinguishable |
| App backgrounded 30 s | ⚠️ still answered, but launched by `devicectl`, which may prevent suspension |

usbmuxd only dials **into** the phone, so the design inverts the connection: on a physical iPhone
`StreamSink` listens on its own loopback and `inspector serve` runs a bridge that dials it and
relays into the daemon's own `/ingest`. The daemon's bind did not move. Design notes and the
traps are in `docs/decisions/devices-and-stream.md`, under the `adb reverse` decision.

**Verified end to end on the same phone:** the real iOS sample streamed 188 transactions into the
archive as `platform: ios-device`; a replay was re-signed by the app across the cable; kill and
relaunch reconnects in about a second. That last one needed a fix found only on hardware — the
listener lacked `reuseAddress`, so a relaunch sat in `EADDRINUSE` while the killed link was in
TIME_WAIT — and only a Native test can guard it, because the JVM enables `SO_REUSEADDR` by default.

**Still open:**

- ~~**`INTEGRATION.md` is untouched on purpose.**~~ Done in 1.2.0: v56 rewrote §6f's closing
  paragraph and §9 item 7, and its changelog entry says *bump, download the daemon, plug in*.

*The two bullets still open stay in `ROADMAP.md` → §2 → "iOS hardware".*

---

*The record, as it stood before the spike:*

There is no `adb reverse` equivalent. That leaves widening the daemon's bind to a LAN interface,
and `Server.kt` is explicit about what that costs:

> Binds to 127.0.0.1 only. There is no authentication and the archive contains unredacted
> credentials by default, so the loopback bind is the security boundary — do not widen it without
> adding auth first.

So this is not a transport problem, it is an authentication problem, and the honest sequence is:

1. Decide whether the daemon gets authentication. Until then, iOS hardware is blocked.
2. If yes: a token the app must present, an opt-in flag to bind beyond loopback, and a refusal to
   do so in any build that is not explicitly a debug build.
3. Only then, the transport.

An alternative worth costing before taking that on: a **USB tunnel over `usbmuxd`**, which would
keep the loopback boundary intact the way `adb reverse` does on Android. It is more work and it
adds a host-side dependency, but it does not require putting an unauthenticated archive of
credentials on a network.

### And the thing that blocks actually trying any of it

~~**There is no Android app module**~~ — **added 2026-09-18.** `:sample:android` is a real APK
that wires Inspector the way `INTEGRATION.md` tells a consumer to, including the debug-only
cleartext config. The whole overlay has now been seen running on an emulator, and the emulator
detection verified itself end to end in the process: the session it wrote says
`platform: android-emulator`, where the same image used to say `android-device`.

Three things came out of running it that no test had caught: a main-thread `NetworkOnMainThread`
swallowed by `runCatching`, so the sample's OkHttp button silently did nothing; the release guard
reporting an APK full of capture code as clean, because D8 drops the canary from the dex pool; and
the same guard never unpacking archives inside a directory target. The first was the sample's, the
other two were real holes in production safety.

~~**The Xcode project is still missing**~~ — **added 2026-09-18.** `sample/ios/` is a KMP module
producing a static framework plus a hand-written `.xcodeproj` and about twenty lines of Swift; a
Compose Multiplatform sample that reimplemented its UI in Swift would be demonstrating the opposite
of the claim. The overlay has now been seen on iOS, and the compile-time `IS_IOS_SIMULATOR` from
0.8.0 verified itself: the session says `platform: ios-simulator`.

It found the last unfixed defect from the original device outing. `Modifier.inspectorScreen` gave
the *screens* insets; the **pill** never got them, and at 3x its 120-pixel default offset is 40pt —
inside the Dynamic Island, where the system eats the touch and the overlay cannot be opened at all.
Android only looked right because 120px clears a status bar at that density.

*The paragraph still open, "Neither sample has run on physical hardware", stays in `ROADMAP.md`.*

---

## 3. Carried over, unbuilt

### The table of carried-over work

| Item | Where it is specified | State |
|---|---|---|
| Replay step 3 — edit before replay | `REPLAY.md` §7 | ~~Specified~~ **Done, 2026-09-19.** Method, URL, headers and body, above the tabs of the request they describe. It also uncovered that the plain replay button had been throwing `NotFoundError` on ordinary rows since the tabbed detail pane arrived — `querySelector` finds a grandchild and `insertBefore` will not take one |
| HAR export | `implementation-plan.md` Phase 3 stretch | ~~Unbuilt~~ **Done, 2026-09-18.** `GET /api/sessions/{id}/har`, taking the same `filter` as the transaction list. Everything the format asks for and capture never saw is `-1`; attempts and redaction are disclosed per entry, because HAR has no field for either and silence reads as a claim |
| Whole-session export | Requested 2026-10-10 | **Done, 2026-10-10.** `export.html`, `export.md` and `export.json` under `/api/sessions/{id}/`, the web UI's export menu, and the `export_session` MCP tool. The HTML is the web UI itself in an archive mode, not a report written for the purpose. Building it found the HAR export silently stopping at 1000 rows |

### Guards

- ~~**The stream pair had no golden API file.**~~ **Done, 2026-09-18.** `StreamSink.lastError` was
  present in `:inspector-stream` from before v0.1.0 and absent from `:inspector-noop-stream`, so a
  call site reading it compiled in debug and failed under `-Pinspector=off` — the one failure the
  noop twins exist to prevent, in the module that prevents it, unnoticed for eleven releases
  because `ApiParityTest` only ever covered `core`/`noop`.

  The contract for this pair could not be equality, because the real module must expose Ktor types
  the noop must never name. It is instead *every public member whose signature names no Ktor type*,
  with both modules asserting equality against that. Extending the reflection to a second pair
  turned up four further ways it could have looked right while checking nothing — unmangled
  top-level `internal` functions, facades with different names in the two modules, enum constants
  visible to no reflection list, and a receiver-drop that ate a real parameter. All four are
  recorded in `docs/decisions/twins-and-api.md`; none of them changed the `core` golden file by a byte, which is how they
  were confirmed to be fixes to the guard rather than to the surface.

- ~~**The twin pair's JVM file facades had different names.**~~ **Done, 2026-09-22.** The bullet
  above records "facades with different names in the two modules" as one of four ways the
  *reflection* could look right while checking nothing. That reading was wrong, and the guard was
  built on it: the differing names were not a nuisance to normalise away, they were an **ABI
  break**. `defaultDaemonHost`/`defaultClientInfo` compiled into `Platform_jvmKt` and
  `Platform_androidKt` in the real module and `StreamSinkKt` in the noop, so a consumer compiled
  against one and linked against the other got a `NoSuchMethodError` on a class not in the
  artifact — identical public API, incompatible bytecode. Reported from a consuming app; Android
  and JVM only, because Kotlin/Native has no facades.

  The guard could not have caught it, and not by accident: it dumped both facades under the shared
  label `dev.inspector.stream (top-level)` *so that* the names would stop differing. A guard built
  to normalise away the symptom of a bug reports clean forever. Both actuals now pin
  `@file:JvmName("StreamSinkKt")`, `StreamSinkKt` is named in `STREAM_CONTRACT_CLASSES` like any
  other type, and the golden file records it. Proven both ways on both modules.

  The noop side cannot pin: `kotlin.jvm.JvmName` does not resolve in a common source set shared
  with Native. So `inspector-noop-stream/.../StreamSink.kt`'s **file name is load-bearing** and the
  guard is the only thing holding it. Recorded in the file itself and in `docs/decisions/twins-and-api.md`.

  A raw public-class-set diff across each pair — the obvious generalisation, and what the report
  suggested — was built, measured and rejected: 19 spurious entries for `core`, 19 for `ui`, 4 for
  `stream`, because Kotlin `internal` is JVM-public and the canary is real-only by design. The
  reasoning is in `docs/decisions/twins-and-api.md`; do not rebuild it without reading that.

- ~~**The twin pair's constructor descriptors differed.**~~ **Done, 2026-09-22.** The entry above
  fixed the *facade* half of "matching API is not matching ABI" and stopped there. `StreamSink`'s
  real constructor takes `engineFactory: () -> HttpClient` fourth; the noop's took no such
  parameter — so a consumer compiling against the noop and linking the real module crashed on
  `NoSuchMethodError <init>`, one line past the crash the facade fix had just removed.

  Two things worth keeping. It was **not** introduced by the facade release, though the report said
  it was: 1.0.1's aars carry the identical divergence, and fixing the earlier crash is what made
  this one reachable. And the guard's own documentation was the cover — `StreamApiParityTest` said
  constructors were out of scope, so nothing looked.

  `ApiSurface.jvmDescriptors` now compares public constructor and method **JVM descriptors**. The
  noop's parameter is `() -> Any?`: erasure makes it the same descriptor as `() -> HttpClient`
  without naming Ktor, which is also what keeps the Ktor filter honest — the erased parameter is
  compared, while `defaultStreamClient()Lio/ktor/client/HttpClient;` still carries Ktor and is
  still skipped. Proven both ways; a constructor-only descriptor audit across all three pairs
  confirms `StreamSink` was the only divergence.

- ~~**The signals sort and clamp exist twice, which breaks the layering rule phase D wrote down.**~~
  **Done, 2026-10-19, with one deferred bug, KIN-42.** *Ticket label:* `item:signals-query-one-rule`.
  Tickets KIN-24 to KIN-41 and KIN-26 are Done on QA evidence; KIN-42 stays open and deferred (below).
  Four verify rounds found and closed the defects (heads `5f6b610`, `e1711a3`, `a86dde2`, `8082e02`),
  and a fresh QA at `8f64058` found no regression: static checks 71/0, live probe 182, archive probe 26,
  `:inspector-daemon:test` 267/0, `:repo-checks:test` 35/0. Each round's findings were filed back under
  the item (KIN-27 to KIN-41), which is why 18 tickets cover what was sized S. KIN-26 was the follow-up
  to KIN-23: its test pushed `McpTest.kt` past its budget, and `:repo-checks` caught what a daemon-only
  run had not. What was learned: the route and `list_signals` now share `SessionRepository.querySignals`
  (pinned by `SignalQueryTest`); the web UI keeps a whole-session copy of signals beside the filtered one,
  as it already did for calls, and every round found another way that copy went stale (pause and resume,
  an unplanned reconnect, a slow older load, a clamp on counts), so the rule is written down in
  `docs/decisions/web-ui.md` and each way has a check. The Chrome suite `scripts/measure-web-ui.js` showed
  22 failures at the base of KIN-41 and 2 at head; the 20 that moved are all "scrolled back: moves ->
  stays", and the 2 left are KIN-42's. The release-notes line owed (in `UNRELEASED.md`, the fourth) still stands
  and is unchanged.
  *KIN-42 was closed on 2026-10-22; see the paragraph below. The unplaced live-timeline defect was placed and done in `item:web-live-calls-reach-every-pane`.*

  **KIN-42 Done, 2026-10-22.** PR #136 merged as `1888380` (two commits: `14be66e`, the pure relocation of
  `loadTransactions` and `syncAllTransactions` into `web/js/16-session-load.js`, then `293e012`); `build`,
  `release-swap`, `repo` and `web` passed on the PR and the push run on `main` succeeded. Undeferred by the maintainer
  on 2026-10-21; Done on QA's pass over detached copies, one round, no blocking findings.

  A load draws its first page at once, the live socket opens before it, and the whole-session copy is read behind
  it; the panes that need the copy (the glance, the path, the endpoint chips, the repeat marks, the overview line)
  say `this session's N calls are being read…` until it lands, and the copy lands in place, with the scroll kept.
  On the 160,016-call session in headless Chrome 131, first rows at 411 to 427 ms and connected at 414 to 430 ms
  (bar 3000), against about 24 s at `4645c43` and 26,378 and 26,482 ms at `623c571`. QA sampled the whole read every
  400 ms at three widths and found no `waiting for an app` or `nothing recorded` at any of them. The copy landed
  with 10 chips and `calls in session 160016`, the first row moved 33 px. Counts, base to head: static checks 112 to
  116, live probe 260 to 262, archive probe 48; Chrome `--only first,traffic,layout,run,live,room`, 220 readings, 0
  failed on head and 4 on base (only `first rows huge`, `connected huge`, `being read huge` and `copy lands huge`
  moved). Red by QA: `connectLive()` moved back after the load (`connected huge: over 3000 ms (23748 ms)`), and the
  overview band not reserved (`the first visible row moved 66 px`).

  **What was learned.** With the axis hidden during the read, the whole overview line was hidden on a huge session,
  because the axis is the only part of that line drawn there, so when the copy landed two 33 px bands appeared (the
  scope bar, which the design note allowed for, and the overview line, which it did not) and the first row moved
  66 px, past the 48 px bound. The coordinator chose option (a) on 2026-10-22: the overview line keeps its band while
  the copy is read and holds `beingRead(…)` text, never an empty axis, at the axis's height through the reading-only
  class `.axis-hint.axis-reading { min-height: 24px }`, and `syncOverview` counts the hint. QA found that rule
  unguarded: with the `min-height` removed, `copy lands huge` still passes at 44 px, under its bound, so the
  "rows move only by the scope bar" promise is held by the bound and not by an exact reading. The builder's first
  `copy lands huge` also passed vacuously when `connectLive` was moved back late, so it gained a "found nothing"
  guard. The read stays uncapped, by KIN-42's decision. A `git commit` with `-c` options failed in the builder's zsh
  (`$C` was not split) and a chained `git reset --hard` lost the working tree; every KIN-42 file was restored from
  the blobs `git add -A` had just staged, other worktrees' blobs excluded, and QA re-measured every Chrome reading
  on the merged tree, which is why those readings are QA's and not the builder's.

  **Left open, unplaced, for the product owner.** A filter typed during the read restarts it (one read per session
  that later loads wait on would not); a row arriving during the first page's own request can miss the list until the
  next load; an address naming a view other than traffic or path still waits for the whole load; the waterfall's
  stub groups for screens older than the list's page say `no calls` (predates KIN-42); the band's `min-height` is
  unguarded. Safari and Firefox on the huge session, and Chrome `topbar` and `shell` in this round, were not run.

  cost: not recorded — built in the 2026-10-22 coordinated run, one engineer per item, no QA before merge; QA after merge, 1 round

  *The original text of the open entry, kept as the record:*
  **Open: KIN-42. Deferred by the maintainer on 2026-10-19; undeferred on 2026-10-21 (order row 21).** On a session of about 160,000 calls, the
  web UI shows a false empty state ("waiting for an app" / "nothing recorded") for about 25 seconds
  before the first rows appear, and the live socket connects only after. KIN-34 introduced it, when the
  filter-note counts began reading the whole session. The fix is to draw the first page at once and
  read the whole-session copy in the background (ACs in the ticket); it is not scheduled. The item is
  Done without it by that decision, not because the defect is closed.
  *Original statement of the defect, kept as the record:*
  **Ordered 2026-10-18** (§5 → "Order from here"), right after the
  Markdown export bug. It is a live web UI defect: a filter a user types does not reach the
  signals, and a long session loses its oldest screens without saying so.
  `docs/decisions/daemon-and-mcp.md` now says "a rule both adapters serve lives in `service/` or
  `archive/`, never in a route or tool body". The signal list is served by both adapters, and it
  is not built that way:

  - `GET /api/sessions/{id}/signals` (`inspector-daemon/.../http/SignalRoutes.kt`) reads the raw
    signals and does its own work inside the route. It filters on a `tag` parameter with
    `equals(ignoreCase = true)`, sorts by `mono` descending, and clamps `limit` to 1..1000.
  - MCP `list_signals` goes through `SessionRepository.querySignals`, which applies the same sort
    and the same clamp separately, and filters with the shared grammar.

  The sort and the clamp agree today only because two copies happen to match. The layering scan
  from phase D cannot see this: the route imports nothing it should not. What it breaks is the
  rule that a route does not hold a rule.

  **It is also a live defect on the web UI, not only a layering one.** `app.js`'s `loadSignals`
  asks this route for `filter=<the current filter>&limit=2000`. The route reads neither `filter`
  nor anything above 1000. So on the web:

  - **A filter never reaches the signals.** `path:/v2` thins the calls and leaves every signal in
    place between them. That is the defect fixed in the overlay in stage 2 of §1. The recorded
    decision says "the web UI has always sent its filter to the signal route", which is true: it
    sends it, and the route ignores it. The path view's skeleton fetch (`filter=tag:screen`) only
    works because `app.js` filters the reply for screens on the client.
  - **A session with more than 1000 signals loses its oldest**, with nothing on screen to say so.
    That includes the screens the path view draws its route from.

  Both predate phase D: the route on `main` is the same code, moved and not rewritten.

  **The user and the problem.** A web UI user on a Mac who filters a session holding signals, and
  an agent over MCP, who is promised the same answer the page shows. Today the two adapters run
  different filters over one stream.

  **Acceptance criteria, when it is fixed.**

  - The route gets its rows from the same function `list_signals` uses, so the sort, the clamp and
    the filter exist in one place, in `archive/` or `service/`.
  - `tag=` keeps working. The grammar's `tag:` term is also exact and case-insensitive, so the
    parameter can become that term without changing what it matches.
  - The route still answers with a bare array, newest first. The export's `archiveSource` mirrors
    that shape, and `render-web-ui-archive.js` holds it.
  - A daemon test asks the route and `list_signals` for the same session and filter, and asserts
    the same ids in the same order. It fails with the route's own sort put back.
  - A filter typed in the web UI drops signals by the grammar's exclusion rule, checked by
    `render-web-ui.js`.
  - The limit is either raised to cover what `app.js` asks for, or `app.js` asks for what it can
    get and pages. The item says which, and a session with more than 1000 signals is tested either
    way, for the reason `HarTest` pins 1001 rows.

  **Out of scope.** The filter grammar, which is frozen. MCP's argument names or its page shape.

  Surfaces: daemon, web UI. No public API. It changes what a web UI user sees under a filter, so it
  gets a release-notes line when it ships; the line is owed with phase A's, in `UNRELEASED.md`.

- ~~**`:inspector-noop-ui` still has no golden file, and already diverges.**~~ **Done, 2026-10-21**, as §5 → Phase I (PR #106, merge `1479c30`).
  `api/inspector-ui-public-api.txt` is asserted by both modules of the pair, `InspectorOverlayKt` is
  the recorded facade, and the three theme types are `internal`. The detail and the lessons are
  under Phase I in §5 below.

  cost: see Phase I in §5 below; not recorded — built in the 2026-10-20/21 coordinated run, no QA before merge, QA after merge; 1 round

  Found while writing the
  stream guard, not fixed with it. `:inspector-ui` publicly exposes `InspectorTheme`,
  `InspectorColors` and `LocalInspectorColors`; the noop exposes `InspectorOverlay` alone, so a
  consumer theming anything against those would hit the `lastError` failure exactly. It is latent
  rather than live — no document has ever told a consumer to use them, which is the only reason
  nobody has been bitten.

  It is deliberately not folded into the stream fix, because the answer is a design decision and
  not a stub: mirroring `InspectorColors` means a release artifact carrying the inspector's palette
  as dead data. Either that cost is accepted, or the three are made `internal` and the divergence
  disappears — the second looks right, and it is a public-API removal, so it is a decision rather
  than a tidy-up. The guard itself is also harder here than for stream: `@Composable` functions
  carry synthetic `Composer` and `changed` parameters into their JVM signatures, which reflection
  reports and nobody wants in a golden file.

  The facade fix above adds a second thing that guard will have to assert. `InspectorOverlay` is
  top-level, so its JVM class is named for whichever file declares it in each module — the same
  defect the `stream` pair shipped. **Measured, and this pair is currently fine:** both modules
  put it in `InspectorOverlayKt`, on JVM and on Android. But they agree because both files happen
  to be called `InspectorOverlay.kt`, not because anything checks, and renaming either one is a
  silent runtime break with nothing to catch it.

  **Decided 2026-10-18: the three become `internal`.** The guard is now §5 → Phase I.

- ~~**A check that finds nothing to check passes.**~~ **Done, 2026-10-22.** KIN-87 is Done on QA's second
  round. PR #128 merged as `b1bbcac` and its fix round, PR #133, as `5b21593`; `build`, `release-swap`,
  `repo` and `web` passed on both PRs, and the push runs on `main` for `b1bbcac` and `5b21593` were in
  progress when QA looked and were not seen finishing.

  The rule now exists once, in `scripts/lib/scan-verdicts.js`: a scan that finds something passes and prints
  how many it checked; a scan that finds nothing where something belongs fails with `NO - found no <shape>,
  so this checked nothing`; a shape that is legitimately absent reads `n/a (why)`, and only when the reason
  comes from something the empty scan cannot have caused (the daemon's rows, `/current`, the page's own
  whole-session state). `failures unfolded` and `expanded unfolded` print their counts on the CI fixture (`1
  summarised line, 6 lines in all`; `1 expanded line, 0 failures drawn`). The rule was applied to the
  shape-scanning probes in `render-web-ui.js`, `render-web-ui-archive.js` and `scripts/lib/`, and recorded
  as one decision in `docs/decisions/web-ui.md`. No fixture check went from `yes` to `n/a`: the live probe
  stayed at 251 passed with 1 n/a, the archive probe at 48, and `ci.yml` is unchanged. `render-web-ui.js`'s
  line budget was tightened from 2395 to 2354.

  QA put KIN-55's leak back (the two lines in `lib/live-filter-probes.js` that restore the open visits). At
  the base, `failures unfolded : yes (0 summarised lines in this session)` with 251 passed. At the head,
  `failures unfolded : NO - found no summarised line (1 already open: Checkout), so this checked nothing`,
  250 passed and 1 failed, exit 1. With the leak removed, 251 passed.

  **What was learned.** QA's first round failed criterion 2. The builder inventoried `render-web-ui.js` and
  the `lib/*-probes.js` files and not the Chrome cases in `scripts/lib/`, though the criterion named
  `scripts/lib/`. QA renamed every `class="rail-item"` in the served page and `shell <w> rail` still read
  `ok … inside` at all four widths, over no rail item at all. The inventory listed none of `shell-cases.js`,
  `topbar-cases.js`, `list-room-cases.js` or `live-traffic-cases.js` (the last landed after the inventory
  was taken). The fix round converted what was vacuous (`shell <w> rail`; `room 1000 tab never enters
  closed`; the repeat-mark part of `room 400 rows`; `room <w> toolbar fits` and `one row`; `traffic
  reaches`), put the empty `topbar` count into words, and recorded a verdict for every check in the four
  files. In round 2 QA found one more by hand: the repeat-mark scan in `room 400 rows` took its count from
  `state.duplicates`, not from the marks drawn, so with the mark class renamed it read `ok … 0 repeat
  marks`; it fails now (`NO - found no repeat mark (3 rows held are repeats)`). QA also confirmed the rail
  fix is what turns the old reading red, by reversing only #133's hunk in `shell-cases.js`. The lesson is
  the one the item was filed for, one level up: an inventory of scans is itself a scan, and it needs the
  same count.

  **Left open, not placed (the product-owner's).** On the CI fixture `expanded unfolded` opens Home, which
  draws no failure, so the expanded half of the rule is exercised weakly; making it open the visit that
  holds a failure changes what the check judges, which this item ruled out. `docs/decisions/web-ui.md` names
  the render-probe checks that follow the rule and not yet the Chrome cases fixed in #133. Read from the
  code and not run red: `rail is navigation`, `route pages`, `exportMenu`, `sort flip restores`, `map
  matches log`, `start times shown`, `duplicate badges`, `unanchored chips`, `hop-by-hop stripped`,
  `onTop`'s `n/a` branch, and the socket half of `traffic reaches` (the case starts only once the socket is
  open).

  cost: not recorded — built in the 2026-10-21/22 coordinated run, batch 1, one engineer per item, no QA before merge; QA after merge, 2 rounds

  *Ticket label:* `item:guard-probe-finds-nothing-fails`.
  Found in the retrospective of `item:web-live-rows-filtered` (PR #98, 2026-10-19), from KIN-55. The
  roadmap-cycle skill now tells an engineer who adds a probe to the shared page to break one
  neighbouring guard and watch it fail. This item is the mechanical version of that rule, so the
  rule still holds on a day when nobody remembers it.

  **For whom.** Whoever changes the web UI and trusts a green `render-web-ui.js`, and the maintainer
  reading CI. Behind them, the web UI user on a Mac: a guard that checked nothing is how a 500 hidden
  behind "+N more" comes back.

  **Why it is unguarded.** The probes share one page. The new live probe in
  `scripts/lib/live-filter-probes.js` left a path visit open, so the path probe that runs after it
  found no collapsed visit. `failures unfolded` then printed `yes (0 summarised lines in this
  session)`, and `expanded unfolded` passed with nothing to check as well. Nothing counted either as
  wrong. `INSPECTOR_UI_EXPECT_CHECKS` pins how many checks pass, and a check that passes over nothing
  still counts as a pass. KIN-55 fixed the leak, not the guard: the live probe now puts the page state
  back, and the comment at the top of that file says why. The next probe that leaks state will
  reproduce it. QA found this one only by breaking a neighbouring guard by hand.

  **What it would take.** Acceptance criteria:

  - On the CI fixture, `failures unfolded` fails when it sees 0 summarised lines, and
    `expanded unfolded` fails when it sees no expanded line. When either passes, it prints how many it
    checked.
  - The same rule, generalised. A probe in `scripts/render-web-ui.js` or `scripts/lib/` that scans for
    a shape (rows, lines, bars, elements matching a selector) and passes when none breaks the rule
    also asserts that it found at least one, and prints the count. Among them, as a heuristic and not
    a complete list: `currentHasAge` (`#current .current-item`), `barsVisible` (`.wf-bar`), the lane
    list behind `unknownTagRendered`, and `noRepeatedHost` and `uncoveredNameTheirHost` in
    `render-web-ui.js`, plus `onTop` in `lib/measure-cases.js`. The tree already does this in places:
    `everyHeaderHasOne` and `labelled` require `length > 0`, and the CSS brace audit in
    `lib/web-audits.js` says "found no rule blocks, so this checked nothing". The architect finds the
    rest.
  - When a probe's shape is legitimately absent from a fixture, the probe says so in an `n/a (…)` or
    a `note()`, never in a `yes`.
  - Proven both ways. Reintroduce a leak like KIN-55's by leaving a path visit open before the path
    probe runs: the run goes red, and the failure names the empty scan. Remove the leak and the run
    is green again.

  **Out of scope.** Repairing whatever a leaking probe leaves behind (KIN-55 already did that for the
  live probe). Giving each probe its own page. Changing what any check judges beyond the empty case.
  If a check on the fixture turns from `yes` to `n/a`, the pinned `INSPECTOR_UI_EXPECT_CHECKS` count
  moves, and the change says which check and why. Surfaces: none a user sees. This touches only the
  web UI's check scripts, so there is no public API, no noop twin and no golden file.

- ~~**QA's live-traffic Chrome driver is still in a scratch directory.**~~ **Done, 2026-10-22.** PR #127
  merged as `9fb0470`; `build`, `release-swap`, `repo` and `web` passed on the PR, and the push run on
  `main` was in progress when QA looked and was not seen finishing. KIN-88 is Done on QA's pass over a
  detached copy, one round, no blocking findings.

  `scripts/lib/ingest-client.js` is an app-side `/ingest` client, and `scripts/lib/live-traffic-cases.js` is
  the case. `node scripts/measure-web-ui.js --only traffic` ran 9 readings, 0 failed. It judges the burst
  bound (3 list reads against at most 6, for 100 rows over 850 ms), the burst's counts against the daemon's
  (7 of 56 calls and 26 signals), the filter-edit race and the session-switch race, each in both landing
  orders, and that the page reached only 127.0.0.1 and every streamed host ended in `.invalid`.
  `measure-cdp.js` gained only `page.onNewDocument`, so there is still one Chrome launcher. The client
  refuses any origin but `http://127.0.0.1:<port>` and any host not ending in `.invalid`; QA called it
  directly with five bad origins and three bad hosts and each was refused. `scripts/AGENTS.md` and the
  roadmap-cycle skill name both files among the harnesses a brief tells a reviewer to reuse.

  QA re-ran three red proofs, each turning exactly the reading it guards red and nothing else: a re-read per
  live row (`over bound (149 list reads, at most 6 …)`), `stands()` without its filter clause (`old landed`
  on the filter-edit race), and `stands()` without its session clause (the same on the session-switch race).
  The builder's fourth, the write check made `true`, turns all four race readings red; QA did not repeat it.

  **What was learned.** Two first drafts were green when they should not have been, and both were caught by
  breaking the rule on purpose. A race judged in one landing order only stayed green with `stands()`'s
  filter clause removed, because the ticket (`readSent`/`readWritten`) also drops an old re-read that lands
  after the edit's own load; so each race runs in both orders, and `web-ui.md` says why. `traffic reaches`
  first read its origins from `__asked`, which the races clear, so it missed the load-time request; it now
  reads `__origins`, which nothing clears.

  **Not done, and said so.** The scratch driver's pause and resume, reconnect, 400px and spaces-only
  scenarios were not ported (the item allowed it), so no committed check guards them; QA verified pause and
  resume and reconnect by hand on KIN-58. `measure-web-ui.js` is not in CI. One full-case attempt hung once
  when Chrome exited mid-run, because `measure-cdp.js` does not notice a closed DevTools socket; a rerun was
  clean, and it is not new to this item. The burst-bound formula for a burst longer than a second was never
  exercised; every burst lasted about 850 ms.

  cost: not recorded — built in the 2026-10-21/22 coordinated run, batch 1, one engineer per item, no QA before merge; QA after merge, 1 round

  *Ticket label:*
  `item:guard-live-traffic-driver`. From the same retrospective. The roadmap-cycle rule "Promote a
  harness the first time it earns it" says this driver should have moved into `scripts/lib/` within
  `item:web-live-rows-filtered`. It did not.

  **For whom.** The web UI user on a Mac who watches a session live, under a filter. Also whoever next
  changes the live socket or the re-read, as `item:web-live-calls-reach-every-pane` did (done 2026-10-21).

  **Why it is unguarded.** `docs/STATUS.md` lists the following as verified in headless Chrome: the
  live re-read burst bound, a filter edit, a session switch and a dropped socket during a re-read.
  They were verified by a driver QA wrote in a temporary scratch directory. It streams real rows into
  a real daemon's `/ingest` and drives the served web UI over CDP. QA also used it to prove pause and
  resume. No committed check does any of this. The committed checks each cover a piece:

  - `lib/live-reread-checks.js` runs the races and `reread burst of 100` against stubbed routes,
    with no browser and no daemon.
  - `lib/measure-cases.js`'s `liveArrival` hands messages to the page's own `onmessage`, so nothing
    goes through `/ingest`.
  - `lib/live-filter-probes.js` fakes a row's arrival by wrapping `fetch`.

  Nothing runs the whole path (ingest, broadcast, page, re-read) in a browser, and nothing exercises
  pause and resume or reconnect there.

  **Where the driver is.** Copies are kept outside the repository, in the maintainer's notes. They
  are also posted as a comment on the pull request that placed this item, which
  `git log -S'item:guard-live-traffic-driver' -- docs/ROADMAP.md` finds. If both copies are gone,
  this is enough to rewrite them:

  - `ingest.js` (about 30 lines) is a minimal app-side stream client. It opens a WebSocket to
    `<origin>/ingest` and sends `{type:'hello', client:{v:1, appId, appVersion, platform:'desktop',
    device, osVersion, buildType:'debug'}}`, then waits for `{type:'helloAck', sessionId}`. After
    that it exposes two calls. `call(path, status, tag)` sends `{type:'txn', txn:{v:1, id, ts, mono,
    method:'GET', scheme, host:'api.qa.invalid', path, query, status, ms, callId, resContentType},
    resBody}`. `signal(tag, name, text)` sends `{type:'signal', signal:{v:1, id, ts, mono, tag, name},
    data}`.
  - `live-ui.js` (about 215 lines) launches Chrome with `launchChrome` and `sleep` from
    `lib/measure-cdp.js` and opens a CDP page target. Through `Page.addScriptToEvaluateOnNewDocument`
    it installs two wrappers. A fetch wrapper records every request in `window.__asked` (path,
    filter, offset, time) and can hold a URL through `window.__delay`. A WebSocket wrapper exposes
    `window.__sockets`, so the driver can drop the page's socket. A snapshot reads the counts, the tab
    counts, the view, the filter and the list rows. It also reads the lengths of
    `state.transactions`, `allTransactions`, `signals` and `allSignals`, which panes (list,
    timeline, path, waterfall) hold given ids, and how many times the list was read. The scenarios:
    - Setup: two sessions, an older B and the newest A.
    - Live rows under a filter on the list, timeline and path, both on arrival and once settled, also
      at 400 px wide.
    - A burst of 50 calls and 50 signals in one second, counting list re-reads against the daemon's
      own matched and total.
    - No filter, and a filter of spaces only: a live row costs no list read.
    - A filter edit mid re-read, with the call read held for 2 s.
    - A session switch mid re-read.
    - Pause with a re-read in flight, then resume.
    - Reconnect after dropping the socket with a re-read armed.

    It records page exceptions throughout.
  - `live-ui-2.js` (about 70 lines) adds three cases that reuse `live-ui.js`'s page:
    - Spaces versus nothing on the timeline: the header, the notes and the chips.
    - A live screen signal on the path tab, under a filter that refuses it: the path skeleton still
      gains the visit.
    - A burst that straddles the re-read timer, 100 rows over 1.4 s against a daemon delayed 1.5 s,
      counting reads.

  **What it would take.** Acceptance criteria:

  - The `/ingest` client and the CDP page helpers live in `scripts/lib/`. Chrome is launched through
    `lib/measure-cdp.js`, which already wraps `WebSocket` as `window.__sockets` and collects page
    exceptions. The fetch recorder and the delay hook join it or sit beside it, and nothing adds a
    second Chrome launcher.
  - It is wired into `scripts/measure-web-ui.js` as a case `--only` can select. Three readings are
    judged and exit 1 when they fail: the burst bound (list reads for a burst, against the daemon's
    own matched and total), the filter-edit race and the session-switch race. They are asserted, not
    logged.
  - Each judged reading is proven red by breaking the rule it guards, for example by re-reading on
    every live row, or by letting a re-read land that answers for an old filter or session. Each goes
    green again when the rule is restored.
  - It reaches nothing beyond 127.0.0.1, and every host in a streamed row ends in `.invalid`.
  - Future QA briefs name it among the existing `scripts/lib/` harnesses, so the next reviewer reuses
    it rather than writing another.

  **Out of scope.** Running `measure-web-ui.js` in CI. Today it runs once per web fix round and needs
  Chrome, JDK 21 and a built daemon, so putting it in CI is a separate decision. Porting every
  scratch scenario: pause and resume, reconnect, 400 px and the spaces-only cases are welcome but not
  required. Changing any live behaviour. Surfaces: the web UI's check scripts, plus the daemon's
  `/ingest` used as a client. There is no public API, no noop twin and no golden file.

- ~~**Phase I gave the ui pair three guards that the core and stream pairs lack.**~~ **Done, 2026-10-22.**
  PR #125 merged as `623c571`; `build`, `release-swap`, `repo` and `web` passed on the PR, and the push run
  on `main` was in progress when QA looked and was not seen finishing. KIN-90 is Done on QA's pass over a
  detached copy, one round, no blocking findings.

  Measured first, as the item's stop rule required: `publicHolders` equals the contract list in all four
  modules, so no public declaration sits outside either contract, nothing was made `internal` and no golden
  file changed (`git diff b1bbcac 623c571 -- api/` is empty). The completeness test
  (`every_public_declaration_is_in_the_contract`) now runs in `:inspector-core`, `:inspector-noop`,
  `:inspector-stream` and `:inspector-noop-stream` beside the golden test, through one shared helper in
  `api-parity/shared`, with the ui pair moved onto it. Each of those four modules declares its golden file
  as a `jvmTest` input. `structure/twins.txt` names each pair's golden file, and
  `NoopTwinTest.every_pair_names_a_golden_file_that_exists` fails a pair with none or a misspelt one.

  QA re-ran, each red and then green: a stray `class StrayProbe` in `:inspector-core` (completeness fails
  with `+ dev.inspector.StrayProbe [class]` while the golden test passes); a stray top-level function in
  `:inspector-noop-stream`; a line appended to each of the two golden files, run with `--build-cache` and no
  `--rerun`, which failed all four `jvmTest` tasks that had just come back `UP-TO-DATE` or `FROM-CACHE`; the
  stream pair's file misspelt and the core line stripped of its file in `twins.txt`. Locally the
  release-clean script's self-test found the canary and the plain run found nothing, on the JVM jars only.

  **What was learned.** The completeness check is class-level, as the ui pair's is, so the stream
  criterion's Ktor filter holds through the golden test and the check has no Ktor rule of its own. A new
  class or file in the real stream module that holds only Ktor-typed API fails the completeness test and
  cannot be listed in the contract either, because the noop's dump would then fail; such an API has to go on
  `StreamSinkKt`, as `defaultStreamClient` does. `twins-and-api.md` says so. The builder removed
  `inputs.files` from two modules to confirm the guard is what does the work: with it gone the same
  golden-only edit left those two `jvmTest` tasks `UP-TO-DATE` and green. The previous `twins.txt` fails all
  three pairs under the new test. The hand-over's report that the root `AGENTS.md` still said the ui pair
  has no golden file was false at `623c571`; PR #106 had corrected both lines.

  **Still open.** The guard reads JVM output only. A declaration that exists only in `iosMain` or
  `androidMain` of one module of a pair still passes; that is `item:guard-twin-native-surface`, not started.
  This machine has no Android SDK and no working `xcrun`, so the Android targets, the iOS test links and the
  klib half of the release scan rest on CI.

  cost: not recorded — built in the 2026-10-21/22 coordinated run, batch 1, one engineer per item, no QA before merge; QA after merge, 1 round

  *Ticket label:*
  `item:guard-twin-pairs-alike`. From the phase I hand-over (`item:structure-phase-i`), placed by the
  product owner on 2026-10-21 with the run's other follow-ups (§3 → Bugs found, not fixed).

  **For whom.** A consuming team building with `-Pinspector=off`. A public declaration in
  `:inspector-core` or `:inspector-stream` that the noop lacks compiles in debug and fails in
  release, the `lastError` and theme-type failure again. Behind them, whoever trusts a green
  `jvmTest` after editing a golden file.

  **Why it is unguarded.** Phase I gave the ui pair three guards. The other two pairs have none of
  them:
  - The completeness check (`publicHolders`) in `UiApiParityTest`. Without it, a public class or
    facade outside `ApiSurface`'s named classes passes, which is how the theme types went unseen.
  - Golden files declared as test inputs. Only the ui pair's builds declare theirs, so an edit to
    `api/inspector-public-api.txt` or `api/inspector-stream-public-api.txt` alone can be a build-cache
    hit and stay green.
  - Golden files named in `structure/twins.txt`. It lists pairs but not their golden files, so
    nothing fails a pair that has none, as the ui pair had none for eleven releases.

  **Acceptance criteria.**
  - The core and stream pairs fail on a public declaration outside their contract, the way the ui
    pair does. For stream, the contract is the one with Ktor-typed members filtered out.
  - Each module of the core and stream pairs declares its golden file as a `jvmTest` input, with
    `inputs.files`. An edit to the golden file alone re-runs the test and fails it, with the build
    cache on.
  - `structure/twins.txt` names each pair's golden file, and `:repo-checks` fails a pair whose file is
    missing or unnamed.
  - Each guard is proven red: a stray public class in each module, a golden-only edit, a pair line
    without its file.

  **Out of scope.** Declarations that exist only in `iosMain` or `androidMain`, which are
  `item:guard-twin-native-surface`. Rebuilding the class-set diff between twins, which §5 → "Deliberately not doing" rules out.
  The completeness check is not that diff, as `twins-and-api.md` explains. **If the check finds a
  public declaration outside the contract in core or stream today, that is a public API question for
  the maintainer**, as the theme types were, and the item stops there. Surfaces: library API guards
  only, `api-parity/`, the published modules' build files and `:repo-checks`. No golden file
  changes. **Size:** S.

- ~~**"A probe leaves no process behind" is a claim nothing tests.**~~ **Done, 2026-10-22.** PR #126 merged
  as `74f5ca0`; `build`, `release-swap`, `repo` and `web` passed on the PR, and the push run on `main` was
  queued when QA looked and was not seen finishing. KIN-91 is Done on QA's pass over detached copies, one
  round, no blocking findings.

  `McpProbeCleanupTest` has three tests, one each for the success path, the timeout path and a write to the
  child that throws; the child is dead (exited within 5 s) when `probe` returns. The stub child,
  `McpProbeStubChild`, ends every mode in `Thread.sleep(Long.MAX_VALUE)`, so it ignores EOF. QA replaced
  `process.destroyForcibly()` with `Unit` and all 3 failed, each naming a surviving pid (`the probe returned
  and left its child running: pid 97836 is still alive`); restored, 3 of 3 pass. `PeerRegistryTest.kt` is
  unchanged, and `the real control either answers or explains why it cannot` stays. `:inspector-daemon:test
  --rerun` went from 357 to 360 tests, 0 failed, and `pgrep -f McpProbeStubChild` found nothing before the
  run, after it and after the red run.

  **What was learned.** The seam is a launch function and not a command: `ProcessMcpControl` has an
  `internal` primary constructor `(config, launch)` and the public one-argument constructor passes `launch =
  null`, so `InspectorDaemon`'s constructor, and phase C's parameter-count tripwire, are untouched. A bare
  command would not do, because the failed-write path needs the child to have closed its stdin before the
  probe writes; otherwise the write lands in the pipe buffer and nothing throws. The test's launch waits for
  the child to say so. QA confirmed why the stub must ignore EOF: with `answer` returning at EOF and
  `destroyForcibly()` still deleted, the success test passes. The failed-write test asserts an `IOException`
  in the error, which proves it took the catch path and not the timeout's.

  **Found, not fixed (the product-owner's to place).** `ProcessMcpControl.probe` checks `timeoutMs` only
  between lines, so a child that keeps stdout open and writes nothing blocks `readLine` for good, and `POST
  /api/mcp/probe` and the web UI's probe button hang with it. The builder met it when a launch that did not
  wait for the stub hung a test for ten minutes. QA read the code and agrees, and did not reproduce it. The
  fix belongs to the timeout, which this item ruled out of scope. Not verified: Linux, except through CI;
  the failed-write test depends on a broken pipe, and it was not checked which operating system each CI job
  used.

  cost: not recorded — built in the 2026-10-21/22 coordinated run, batch 1, one engineer per item, no QA before merge; QA after merge, 1 round

  *Ticket label:*
  `item:guard-mcp-probe-cleanup`. Found during §5 → Phase D, and left alone because phase D allowed
  no behaviour change. `docs/decisions/daemon-and-mcp.md` ("There is no 'start the MCP server'
  endpoint") points here for it.

  **For whom.** A web UI user on a Mac who presses the MCP probe in settings. Every press spawns a
  real `inspector mcp`. If one outlived its probe, it would show up in the peer list as an `mcp`
  process, which is exactly the kind of process that list warns people not to kill, because it
  might be the editor's. A probe that leaks makes the daemon's own control panel lie about what is
  connected.

  **Why it is unguarded.** The whole property rests on one line in `ProcessMcpControl.probe`,
  `finally { process.destroyForcibly() }`. `McpControlTest` (in `PeerRegistryTest.kt`) does test the
  real control, but it runs in a Gradle worker. That worker's argv carries no `MainKt`, so
  `commandLine()` returns null, the probe reports "this process cannot report its own command line",
  and **nothing is ever spawned**. The test's own comment says either outcome is a pass. So the test
  passes with the `finally` deleted, and it would pass with it deleted on every machine the suite
  runs on.

  **What adding it would take.** A production seam in `ProcessMcpControl`, so a test can hand the
  probe a command to run in place of the one it derives from its own argv. The architect chooses
  its shape. It has to stay off `InspectorDaemon`'s constructor, or phase C's parameter-count
  tripwire goes red for a reason unrelated to side effects. That seam is a change to production code,
  and it is why this could not ride along with phase D. Acceptance criteria:

  - The child the probe started is not alive once `probe` returns, on the success path, on the
    timeout path (the child never answers `tools/list`), and when writing to the child throws.
  - **The stub child must ignore EOF.** The real MCP server stops when its stdin closes, and the
    probe closes stdin before it reads anything. A test driving the real server would pass with the
    `finally` deleted. Only a child that keeps running after EOF can tell a probe that kills it from
    one that is lucky.
  - Proven to fail when broken: delete the `destroyForcibly()` and the test goes red, naming the
    surviving pid.
  - The existing "either answers or explains why it cannot" test stays. It covers the case where the
    command line cannot be built, which this one does not.

  **Out of scope.** Changing what the probe reports, its timeout, or the `POST /api/mcp/probe`
  route. Surfaces: daemon only. `ProcessMcpControl` is public in `:inspector-daemon`, which is not
  published, so there is no noop twin or golden file to carry.

- ~~**A measure run hangs when Chrome dies mid-case.**~~ **Done, 2026-10-22.** PR #141 merged as `85e9552`; `build`, `release-swap`, `repo` and `web` passed on the PR, and the push run on `main`
  was in progress when QA looked. KIN-99 is Done on QA's pass over detached copies, one round, no blocking findings.

  `scripts/lib/measure-cdp.js` now fails every pending command with the case's name and the cause when Chrome
  exits after start-up (`Chrome exited (signal SIGKILL)`) or a page's DevTools socket closes, and rejects any later
  send at once. `measure-web-ui.js`'s `onPage` names each case (`head traffic`, `head layout big`) and throws if
  `page.lost` is set after the case returns, so a case that swallowed the error cannot print a reading as passed.
  The existing `finally` then stops Chrome and every daemon. QA killed Chrome 8 s into `--only traffic`: `run exited
  with 2 after 0s`, `head traffic: Chrome exited (signal SIGKILL) before Runtime.evaluate was sent`, nothing left
  running. At `95b4de4` the same kill left the run alive 60 s later and five daemons running. A socket closed under
  a pending command was rejected after 258 ms; with the `ws.onclose` handler removed it waited out the deadline.
  With the `if (page.lost) throw` removed, a case that swallows the error printed `ok SWALLOWED: 1` and exited 0.
  `--only traffic` (10 readings), `--only layout` (124) and `--only first` on the 160,016-call session (9) stay green.

  **What was learned.** The roadmap allowed a deadline on commands while Chrome is alive only "with a number the
  slowest case is shown to meet". The builder timed every send in a full run (287 readings, `first` included): the
  slowest was 718 ms, so 30 s (`SEND_DEADLINE_MS`) is about 40x, and `docs/decisions/web-ui.md` records it, decided
  2026-10-22 on the architect's recommendation. QA's judgement was that the events carry the item's criteria and the
  deadline covers the one failure they cannot see, a Chrome that neither exits nor closes its socket. The deadline
  is also a backstop that hides its own cause: with `onclose` removed the run failed at 30 s saying "no answer"
  instead of "socket closed". A socket's close can arrive before Chrome's exit is reaped, so the socket handler
  waits 250 ms and names the exit if there was one.

  **Left open.** Linux (only macOS Chrome was killed), a wedged renderer inside a real case (only the scratch
  driver's never-resolving command covered the deadline), a kill during a `--base` run, and Ctrl-C or SIGTERM sent to
  `measure-web-ui.js` itself, which still skips its `finally` so the daemons outlive it. That last one is a
  candidate guard of its own and is not placed.

  cost: not recorded — built in the 2026-10-22 coordinated run, one engineer per item, no QA before merge; QA after merge, 1 round

  *Ticket label:*
  `item:guard-measure-chrome-exit-fails`.

  **For whom.** The engineer or QA running `measure-web-ui.js` once per web fix round, as the
  roadmap-cycle skill asks. A hang reads as a slow case, so the run is waited on, then killed and
  rerun, and its readings are lost.

  **Why.** `scripts/lib/measure-cdp.js` resolves each DevTools command from `ws.onmessage` alone.
  Nothing listens for the socket closing or for Chrome's process exiting after start-up, and no
  command has a deadline, so every pending `send` waits for ever. It happened once during
  `item:guard-live-traffic-driver`, and a rerun was clean.

  **Acceptance criteria.**
  - When Chrome exits or its DevTools socket closes during a case, every pending command fails
    with an error naming the case and the cause, and the run ends with a non-zero exit within
    seconds. It never prints a reading as passed.
  - The runner stops the daemons and the Chrome it started on that path, as it does on success.
  - Proven by killing Chrome during a case: before the fix the run hangs, after it the run exits.

  **Out of scope.** Retrying a case. Putting `measure-web-ui.js` in CI. A deadline on commands
  while Chrome is alive: if the architect wants one, it comes with a number the slowest case
  (first rows on 160,000 calls) is shown to meet. Surfaces: none a user sees; scripts only.
  **Size:** S.

- ~~**`expanded unfolded` judges a line with no failure on it.**~~ **Done, 2026-10-22.** PR #143 merged as `c608ec8`, in one PR with `item:docs-housekeeping-2026-10-22`, as the order row allowed; `build`, `release-swap`, `repo` and `web` passed on the PR and the push run on `main` succeeded. KIN-98 is Done on QA's pass over detached copies, one round, no blocking findings.

  `scripts/lib/scan-verdicts.js` gained `failingVisit(screens, txns, leading)`, which splits the daemon's own rows into visits without the page's `visits()` (for the reason `route matches` does the same), and `stationToOpen`, which picks the first visit holding a failure, or the second station when none does, so `station opens line` still runs. `expandedUnfolded(lines, held)` gained an n/a branch and a NO when the opened line draws fewer failures than the daemon holds. `scripts/render-web-ui.js` changed by +2/-2 lines in `probePath` and stays at its 2354-line budget; `INSPECTOR_UI_EXPECT_CHECKS` did not move, because the line was `yes` and is still `yes`. On the CI fixture the probe now reads `station opens line : yes (Checkout)` and `expanded unfolded : yes (1 expanded line, 4 failures drawn in Checkout)`, 263 passed, 0 failed, 1 n/a; at `85e9552` the same fixture read `yes (Home)` and `yes (1 expanded line, 0 failures drawn)`. QA's red: `pathItem`'s status span stripped of its failure class gives `expanded unfolded : NO - an expanded line hides a failure (Checkout)`, 262 passed, 1 failed, exit 1, where base with the same mutation stayed green. A fixture copy with every 5xx set to 200 reads `n/a (no visit holds a failure)` and the station still opens. KIN-55's leak still turns `failures unfolded` red.

  **What was learned.** The old probe compared 0 with 0 and could not see the mutation the item is about. The hand-over's recipe for the daemon-count branch was wrong and the code was right: `const isFailure = () => false` removes the line's `· N failed` header but the spans keep their failure class, so the hiding branch wins. QA reached the count branch (`NO - Checkout holds 4 failures, its open line draws 0`) by keeping the hide mutation and removing the `pv-failed` header line.

  **Left open.** The same-millisecond tie branch of `failingVisit` was not exercised; the fixture has no tie.

  cost: not recorded — built in the 2026-10-22 coordinated run, one engineer per item, no QA before merge; QA after merge, 1 round

  *Ticket label:*
  `item:guard-expanded-unfolded-has-failures`.

  **For whom.** Whoever changes the path view and trusts a green `render-web-ui.js` in CI. Behind
  them, the web UI user on a Mac who opens a visit's line to find the 500 in it.

  **Why.** The probe opens the second station (`stops()[1]` in `scripts/render-web-ui.js`), which on
  the CI fixture is Home, and Home has no failed call. So the line reads `yes (1 expanded line, 0
  failures drawn)`: it passes, prints its count, and compares 0 with 0. The empty-scan rule cannot
  see this, because the scan found a line. QA saw the rule work (`4 failures drawn`) only with
  KIN-55's leak put back.

  **Acceptance criteria.**
  - The probe opens a station whose visit holds at least one failed call, chosen from the
    daemon's rows rather than from what the page drew. On the fixture it prints a count of at least
    one failure drawn.
  - With no visit holding a failure in the session, it reads `n/a (no visit holds a failure)`,
    never `yes`.
  - Proven red: with an expanded line made to drop its failed calls' status, the line fails.
  - If the pinned `INSPECTOR_UI_EXPECT_CHECKS` count moves, the change says why.

  **Out of scope.** `failures unfolded`, which is read before anything opens. The fixture's content,
  unless no visit in it holds a failure. Surfaces: none a user sees; scripts only. **Size:** XS.

- ~~**Interrupting a measure run leaves its daemons running.**~~ **Done, 2026-10-22.** PR #148 merged as `1796b96`; `build`, `release-swap`, `repo` and `web` passed on the PR and the push run on `main` succeeded. KIN-109 is Done on QA's pass over detached copies, one round, no blocking findings.

  `scripts/measure-web-ui.js` runs one memoised `cleanUp()` from the end of `main` and from a SIGINT and SIGTERM handler. It closes Chrome, asks each process it spawned to stop and waits for its exit (`STOP_MS`, 5 s, then SIGKILL), and removes the temp directory unless `--keep`. Interrupted, it prints one line, `measure-web-ui: interrupted by SIG…; stopping the N processes it started. Nothing measured is a result.`, prints no readings and no stack trace, and exits 130 (SIGINT) or 143 (SIGTERM). `scripts/lib/measure-cdp.js`'s `launchChrome(profileDir, onSpawn)` hands over the Chrome process as soon as it is spawned, so a signal while Chrome starts still reaches it. QA's own harness signalled the node process alone: SIGTERM 4 s into `layout,run,live` gave exit 143 after 2 s with 0 processes naming the temp directory left and the directory gone; SIGINT the same with exit 130; SIGINT with `--base 85e9552` (9 processes started) left 0; `--keep` kept only the directory. At `85e9552` the same signals left 18 processes (4 daemons, Chrome and 13 helpers) and a 45M directory, and 26 processes and 83M with `--base`. An uninterrupted `--only layout,traffic` gave 140 readings, 0 failed, exit 0, directory removed.

  **What was learned.** Signalling the node process alone is the harder case for the cleanup, which is why the harness does it: a terminal Ctrl-C signals the whole foreground group, Chrome and the JVMs included, and the cleanup then waits on exits that already happened. The `if (run.interrupted) return 2` guard before the readings print is defensive and was never reached: every interrupted run ended through the handler first.

  **Left open.** A real terminal Ctrl-C was not watched; Linux; a signal during the probe fixture's gradle write (every run used `--probe`); the small `stats-export-*` and `room-export-*` directories that `shell` and `room` make outside the run's temp directory.

  cost: not recorded — built in the 2026-10-22 coordinated run, one engineer per item, no QA before merge; QA after merge, 1 round

  *Ticket label:*
  `item:guard-measure-interrupt-cleans-up`.

  **For whom.** Every engineer and QA agent who runs `scripts/measure-web-ui.js`, which every W-lane
  fix round does, and who stops it with Ctrl-C because a case looks wrong or a run takes too long.

  **Why.** The run cleans up in one `finally`: it closes Chrome, kills each daemon it spawned and
  deletes its temp directory. SIGINT and SIGTERM end Node without running it. Each interrupted run
  leaves one or two JVM daemons behind, each serving a copy of the fixtures from a temp directory that
  is never deleted, and the 160,016-call session is one of those fixtures. Ports are picked free, so
  nothing collides; the cost is that leaked processes and disk pile up unnoticed. KIN-99 fixed the
  case where Chrome dies on its own, and named this case as out of its scope.

  **Acceptance criteria.**
  - SIGINT or SIGTERM sent to a measure run mid-case ends it within a few seconds. Afterwards no
    process whose command line names the run's temp directory is left, and the directory is gone
    unless `--keep` was given.
  - The run exits non-zero and says it was interrupted. It prints no readings as if they were a
    result.
  - This is proven both ways with KIN-99's harness, signalling the run instead of killing Chrome.
    At base, the daemons survive. On the change, nothing survives. A `--base` run is included.

  **Out of scope.** SIGKILL, which no handler can catch. `render-web-ui.js` and the other scripts,
  unless the architect finds they share the cleanup. If one has the same gap, that is a new entry,
  not scope creep. Windows signals. Surfaces: scripts only. No public API. **Size:** XS.

- ~~**The overview band's reserved height is a promise nothing holds.**~~ **Done, 2026-10-22.** PR #149 merged as `6aff923`; `build`, `release-swap`, `repo` and `web` passed on the PR. KIN-110 is Done on QA's pass over detached copies, one round, no blocking findings.

  `scripts/lib/layout-audits.js` gains `auditOverviewBand`, which `check-web-static.js` reports as `overview band css`: `.axis-hint.axis-reading` in `css/25-axis.css` sets a `min-height` in px equal to `.axis-svg`'s height, so CI now goes red when the rule is removed. `scripts/lib/measure-cases.js`'s `copyLandsRow` bounds the first visible row's move at `#scope-bar`'s measured height plus 1 px in place of the fixed 48 px, and its detail names the bar's height. `web-ui.md` gains one sentence naming both checks. No `web/` file changed.

  **What was learned.** The bound was chosen over comparing the band during and after the read because the decision says rows move only by the scope bar's height, so a bound that follows the bar keeps holding if polish restyles it. QA re-broke the rule and saw both checks red (`overview band css : NO`, and `FAIL copy lands huge … moved 44 px, over the scope bar's 33 px`), and saw base's 48 px bound pass the same 44 px, which is the gap KIN-42's QA found. Polish part 1 merged first and restyled nothing the check reads, so the order the item asked for (guard before polish) did not hold and nothing broke. `check-web-static.js` read 120 to 121 at the first push and 125 once polish's four landed under it; `ci.yml` does not pin it, so it did not move.

  **Left open.** The main push run on `6aff923` was still running when QA looked.

  cost: not recorded — built in the 2026-10-22 coordinated run, one engineer session, no QA before merge; QA after merge, 1 round

  *Ticket label:*
  `item:guard-overview-band-reserved`. This is the fifth of KIN-42's follow-ups, listed under the
  signals guard above. The other four are behaviour, not guards, so it does not fold into them.

  **For whom.** Whoever next changes the bands above the list, and trusts the checks to tell them
  they broke the read. Behind them, the web UI user on a Mac reading a large session. They have
  scrolled to a row while the copy is read, and the row should not jump when the copy lands.

  **Why.** `web-ui.md` says the overview line keeps its band through the read "as tall as the axis",
  so rows move only by the scope bar's 33 px when the copy lands. One CSS rule holds that:
  `.axis-hint.axis-reading { min-height: 24px }`. QA removed only that rule and `copy lands huge`
  stayed green, with rows moving 44 px, under its 48 px bound. That reading lives in
  `measure-web-ui.js`, which CI does not run, so in CI nothing goes red at all. Web UI polish restyles
  these bands next, and KIN-33's list-pane guard set the precedent: the guard comes before the work
  that moves what it guards.

  **Acceptance criteria.**
  - With only the `min-height` rule removed, `copy lands huge` fails. It does so by comparing the
    band's height during the read with its height after landing, or by bounding the move at the
    scope bar's measured height rather than a fixed 48 px. The architect chooses which.
  - A check that CI runs also fails with that rule removed, for example a static reading of the rule
    in `check-web-static.js`, as `closed drawer css` already does for the drawer. If no CI check can
    see it without a browser, the item says so, and `web-ui.md` says the rule is held only by the
    measure run.
  - Each check is proven red with the rule removed and green with it restored. If
    `INSPECTOR_UI_EXPECT_CHECKS` or the static count moves, the change says why.

  **Out of scope.** Changing the band, the rule or the 33 px. KIN-42's other four follow-ups. Running
  `measure-web-ui.js` in CI. Surfaces: none a user sees; scripts, plus one sentence in
  `web-ui.md` if needed. No public API. **Size:** XS.

### Bugs found, not fixed

- ~~**`ExportMarkdown.kt:158` prints the query twice.**~~ **Done, 2026-10-18.** The line is
  `inlineCode(txn.url + (txn.query?.let { "?$it" } ?: ""))`, but `NetworkTransaction.url` already
  includes the query (`docs/decisions/shared-rules.md` records that web's `urlOf` mirrors that, empty `?` included). Every
  call with a query string shows `…/path?a=1?a=1` in the Markdown export's **URL** line. Found while
  reconciling phase A; **out of phase A's scope** (daemon, not `app.js`) and not fixed. Fixing it needs a
  `ExportMarkdown` test with a query-bearing row, which asserts the URL appears once, and it is a visible
  change to the export, so it gets a release-notes line when it is fixed. Product owner to order it.

  *Ticket label:* `item:export-md-query-once`.

  **Done, 2026-10-18** (KIN-23, QA at `2b1b650`). The Markdown URL line is `inlineCode(txn.url)` and
  nothing else. The same cause was in MCP `explain`: `ExplainTool.kt` hand-built `CallLine.url` as
  scheme, host and path, which dropped the query and a non-default port, the defect
  `docs/decisions/capture.md` already records. It reads `txn.url` now. Red proofs against the old
  lines: `…?a=1&b=2?a=1&b=2`, `…me??`, and an `explain` url without port or query. 263 daemon tests,
  0 failures. Exporting a real session changed exactly 8 URL lines. HAR, Replayer, the overlay and
  the web `urlOf` already read the shared rule. **Release-notes line still owed**, listed in
  `UNRELEASED.md`, in phase A's pending daemon-only `INTEGRATION.md` entry.

  **Ordered 2026-10-18** (§5 → "Order from here"). The maintainer's
  call. It is a wrong fact in a file people hand to others, and the fix is one line and one test.

  **The user and the problem.** Anyone who exports a session as Markdown and pastes a URL out of it,
  often into a ticket or an agent's prompt. The URL they copy was never called.

  **Acceptance criteria.** An `ExportMarkdown` test with a query-bearing row asserts the query
  appears once on the **URL** line. It fails with the old line put back. A row with an empty query
  (`?` and nothing after) renders as the web's `urlOf` does, per `docs/decisions/shared-rules.md`.
  The release notes carry one line.

  **Out of scope.** The HTML and JSON exports, which read `url` and are not affected. Any other
  change to the Markdown layout. Surfaces: daemon only. No public API.

- ~~**The web traffic list stops at 1000 calls and does not say so.**~~ **Done, 2026-10-19.**
  *Ticket label:* `item:web-transactions-paged`. **Ordered 2026-10-18**,
  straight after the signals item whose pager fixes it.

  **Done, 2026-10-19 (KIN-43 to KIN-50, QA at `68315b4`, base `c21aaf0`; architect and UX said yes).**
  The panel ran three rounds. Round 1 found three blockers (KIN-47, KIN-48, KIN-49); round 2 found one
  false doc sentence (KIN-50); round 3 closed. Evidence at `68315b4`: web static checks 79/0,
  `:inspector-daemon:test` 268/0, `:repo-checks:test` 35/0 (`DecisionsTest` 5/0), live probe 185
  passed 0 failed, archive probe with the long export 37/0, and every red proof for KIN-43 to KIN-49
  re-run. What was learned: the list now asks for its own 1000 (`LIST_CEILING`) and says `newest 1000
  of N shown` with no filter, instead of the filter-shaped `1000 of N calls`. The export's
  transactions route slices by `offset` and `limit` like its signals route (KIN-43), and the
  maintainer decided on 2026-10-19 that **an export draws every embedded call**: at 1000 its oldest
  calls could not be opened while a brushed stretch over them said they did not exist (KIN-48). That
  retired the archive half of KIN-44's red proof (offline the copy is the list, so the pager is never
  called) and amended KIN-45's "same text live and offline": offline reads `1203 of 1203 calls`, live
  `newest 1000 of 1203 shown`. A live row arriving during the whole-session read is buffered and merged
  by id (KIN-46), including when the copy is marked but empty (KIN-47). The count had to stop wrapping
  in the 44px bar (KIN-49), which in turn let the closed session picker shrink. **KIN-42 stays
  deferred and owns whether the whole-session read is capped.** Not verified: CI (not pushed),
  `-Pinspector=off` (no consumer signature changed), and layout in anything but Chrome 131 headless.

  cost (approximate; resumed agents' tokens include carried context): 34 calls (architect 3, engineer 3, QA 6, architect-panel 4, UX 5, delivery-tracker 6, product-owner 4, release-steward 1, plus this one), ~4.0M tokens, about 6 h from first design call to close, Standard track in today's terms though run as a full panel, 3 verify rounds

  `loadTransactions` and `syncAllTransactions` (`web/js/15-session-data.js`) ask for `limit=2000`.
  `SessionRepository.queryTransactions` clamps every page to 1000. So on any session over 1000 calls:

  - **The count reads `1000 of 4210 calls`** with no filter set. That is exactly the shape a filter
    produces, so the reader concludes something is filtering and goes looking for it.
  - **Endpoint chips and repeat marks miss every call older than the newest 1000.** Both read
    `state.allTransactions`, and `docs/decisions/web-ui.md` ("The web UI keeps an unfiltered copy of
    the session") records that copy as *the whole session*. Past 1000 calls it is not, so the
    decision is false on the sessions long enough to need it.

  It is the same class as the signals item, and as the HAR export that stopped at 1000 rows
  (`docs/decisions/exports.md`, "All three exports read the raw readers"). Three times now a reader
  asked for more than the clamp and trusted what came back.

  **Why its own item and not folded into `item:signals-query-one-rule`.** That item is mid-build:
  KIN-24 is in progress and KIN-25 is cut. Adding transactions to it would reopen its design gate
  and hold a signals fix that is nearly done behind a new one. But the fix is the same, so it is
  ordered straight after and must reuse what KIN-24 builds rather than write a second pager.

  **The user and the problem.** A web UI user on a Mac with a long session: an app left running
  through a test pass, or a live tail of an hour. The list, the count, the chips and the repeat marks
  all describe the newest 1000 calls while claiming to describe the session.

  **Acceptance criteria.**

  - In a session of 1001 or more calls, `state.allTransactions` holds every call. The endpoint chips
    count the oldest call and a repeat group that includes it is marked. A probe proves it on a
    1001-call session and fails when the read is put back to one page.
  - **One pager, not two.** Transactions are read through the function KIN-24 adds for signals
    (`signalPages`, generalised or renamed as the architect chooses), and `check-web-static.js` runs
    the same cases against it: a server clamping at 500, a source that ignores `offset`, a row
    repeated across a page seam.
  - **A count never reads as a filter that is not there.** Either the list draws every matching
    call, or the architect sets a render ceiling and the count says so in words that cannot be taken
    for a filter, such as `newest 1000 of 4210 shown`. The item's design says which, and why. With
    no filter set, `1000 of 4210 calls` does not appear.
  - An exported session of 1001 or more calls behaves the same offline, checked by
    `render-web-ui-archive.js`.
  - The daemon's clamp is unchanged. It protects the server; the page reads past it by paging.

  **Out of scope.** The clamp itself. MCP's page shape. Virtualising the list, unless the ceiling
  option needs it. Surfaces: web UI only. No public API. It changes what a user sees on a long
  session, so it gets a release-notes line.

  **Where it stands, 2026-10-19 (the architect's note, placed by the product owner).** Part of this
  item has already been built, under another label. The "one pager" criterion is **met** by KIN-34's
  `routePages` (`web/js/15-session-data.js`). KIN-34 was filed under `item:signals-query-one-rule`
  and reads both whole-session copies through that one function. What is left:

  - **The count with no filter.** A 3016-call session still reads `1000 of 3016 calls` with nothing
    typed. The count criterion above is unmet.
  - **A live probe at 1001 or more calls** for the endpoint chips and the repeat marks. The first
    criterion above needs it, and nothing runs it yet.
  - **The export honouring `offset` and `limit`** (below).
  - **A decision on capping the full read.** The read has no ceiling now. A session of about
    160,000 calls hit the argument-spread limit and drew a blank page. KIN-37 fixes that crash
    by replacing the spreads with loops. It does not answer whether the page should hold 160,000
    rows in memory. The design says whether the whole-session read is capped, and if so, how the
    page says what it left out without reading as a filter. Whether a *labelled* 1000-call ceiling
    on what the list draws is acceptable at all is the maintainer's call. It is under §5 → "Open
    questions only the human can answer".

    *Answered and moved, 2026-10-19.* The maintainer said **yes** to a labelled 1000-call ceiling
    on what the list draws (§5 → "Open questions only the human can answer"). Whether the
    *whole-session read* is capped no longer belongs to this item: it belongs to **KIN-42**, which
    the maintainer kept on the side, deferred, and did **not** fold in here. This item's design does
    not decide it, and its verify panels do not hold it to an answer.

  **Two criteria folded in, 2026-10-19,** from defects the signals verify panels found outside any
  item. They go here because this item now owns the whole-session read, and the signals item is
  closing and takes no more:

  - **A live row that arrives during the whole-session read is in the copy when the read lands,
    and is in it once.** Today a live row joins the copy only once the copy is marked as the
    session's, and the mark is set when the read lands. So a row that arrives between the first
    page and the last is in neither the read nor the copy. KIN-34's paging widened that window from
    one request to every page. The criterion covers both copies, `state.allTransactions` and
    `state.allSignals`, because `routePages` reads both. A committed check delivers a live row
    between two pages of the read and fails with the mark-on-land rule put back. The panel's
    harness lived in a scratch directory and is not in the repository, so it does not count as
    the check.
  - **The export's transactions route slices by `offset` and `limit`**, as its signals route already
    does. Today `archiveSource` (`web/js/05-network.js:63-66`) returns every row whatever it is asked
    for, so the archive half of the 1001-call criterion above cannot be checked against a real page
    walk. The signals route's own comment says why it matters: an export that ignores `offset` is
    answering a different question from the daemon. `render-web-ui-archive.js` asks the export for
    a second page and gets the second page.

  **Size.** It was ordered as S before these were added. The architect re-sizes it. Its place stays
  where it was, because it still shows a user something false today: a filter that is not there.

  **Re-sized to M, 2026-10-19, with tickets KIN-43 to KIN-46, approved by the maintainer the same
  day.** KIN-43 is the export's `offset` and `limit`, KIN-44 the 1203-call live probe for the chips
  and repeat marks, KIN-45 the no-filter count, KIN-46 the live row during the whole-session read.
  The place is unchanged. KIN-42 is not among them (see "Where it stands" above).

  **Defects its first verify panel found that are not its own** (2026-10-19). Eight, each older than
  its diff (base `c21aaf0`) or outside its criteria, so none joins its fix round. They are placed
  below `item:web-current-session-guard`, with a table of where each went.

- ~~**One session's "now" values can land under another.**~~ **Done, 2026-10-19.** *Ticket label:*
  `item:web-current-session-guard`. **Ordered 2026-10-19: straight after
  `item:web-live-rows-filtered`** (§5 → "Order from here").

  `refreshCurrent` (`web/js/70-timeline.js:347-352`) awaits `GET /api/sessions/{id}/current` and
  writes `state.current` with no check that the session it asked about is still the one on screen.
  It is the one async writer of session state without a load or session guard. KIN-29 gave the
  others one. It runs on every session load and on every live signal, so with live traffic a read
  is often in flight. Switch sessions while one is, and the old session's values are drawn in the
  new session's now strip. There is a second way in: on a failed read it keeps `state.current` as
  it was, and right after a switch that is the other session's.

  **The user and the problem.** A web UI user on a Mac who switches sessions, most often to compare
  a run with the previous one. The now strip claims to show what this app holds now. Showing
  another session's values there is the confidently wrong reading that the strip's age and
  provenance exist to prevent.

  **Acceptance criteria.**

  - A `/current` reply for a session that is no longer selected is dropped. The strip shows the
    selected session's values or none, never another session's.
  - A failed `/current` read after a switch leaves the strip empty for the new session. It does not
    keep the previous session's values.
  - A `render-web-ui.js` probe holds a `/current` reply, switches session, then releases it, and
    asserts what the strip shows. It fails with the guard removed, and with the `catch` fallback
    put back.
  - No other state writer changes.

  **Out of scope.** What the daemon derives for `/current`. The overlay, which holds one session.
  Surfaces: web UI only. No public API. Size S. It gets a release-notes line with its neighbours,
  because a user could have seen it.

  **Why its own item.** `item:web-live-rows-filtered` changes which live rows join the lists, not what
  a session switch leaves behind. The signals item is closing. Folding it into 1a would leave a
  false fact on screen until 1a lands, to save one small ticket.

  **Done, 2026-10-19** (KIN-51, commit `eaaadce`, one fresh QA pass, one round). The fix is one hunk in
  `refreshCurrent`: it takes `state.sessionId` before the await, as `syncAllSignals` and
  `syncAllTransactions` do (KIN-29), returns without writing or rendering when the selection has
  changed, and a failed read now gives `[]`. Learned: `state.current` does not record which session
  it belongs to, so `refreshCurrent` cannot tell "the previous values are this session's" from
  "they are the one just left". Tagging it would have meant changing `loadSignals` and
  `afterSessionChange`, which criterion 4 rules out, so **a failed read empties the strip always,
  also on a session that did not change, until the next signal or load.** Accepted trade-off; the
  product owner may revisit. The guard is session-only, not a load counter: A to B to A with a reply
  for A held lands A's own values, which criterion 1 allows. Proof in both directions: guard
  deleted, 186 passed 1 failed (`now late reply dropped`); `.catch(() => state.current)` put back,
  `now failed read empty` fails; restored, 187 passed. Chrome render over a two-session archive: at
  base the old session's values show after the late reply, at head the new session's; a failed
  read hides the strip with items `[]`. Counts: web static checks 79/0, live probe 187/0, archive
  probe 37/0, `:inspector-daemon:test` 268/0, `:repo-checks:test` 35/0; commit identity and
  client-name scans pass.

  cost: 5 calls (delivery-tracker 2, engineer 1, QA 1, release-steward 1), ~0.4M tokens, ~45 min, Small, 1 round

- ~~**Live rows ignore the filter until the next reload.**~~ **Done, 2026-10-19.** *Ticket label:*
  `item:web-live-rows-filtered`. **Ordered 2026-10-18, scheduled in Order from here**, conditional
  on its size (see the end of this entry).

  `ws.onmessage` in `web/js/05-network.js` pushes every live call into `state.transactions` and
  every live signal into `state.signals`, whatever the filter. Type `status:5xx` with live on, and
  every 200 that arrives is drawn under that filter, and the count goes up with it, until the filter
  is edited or the page reloads. KIN-24 keeps signals consistent with calls here, so both stay wrong
  together. Once `item:signals-query-one-rule` makes the filter reach signals on a reload, the live
  socket is the only path by which anything escapes the filter.

  **The constraint that shapes the fix.** The page must not decide whether a row matches. The
  grammar is frozen and has one parser, in `:inspector-model`, and the export already records that
  its filter is "disabled, not reimplemented" (`docs/decisions/exports.md`) because a JavaScript
  evaluator would be a second parser, the drift this repository refuses. So the daemon answers:
  the live message carries whether the row matches the page's filter, or a live row under a filter
  triggers a throttled re-read of the filtered set. The architect chooses.

  **The user and the problem.** A web UI user on a Mac who filters while watching live traffic,
  which is the commonest way to watch for one failure. The list shows rows the filter excludes, so
  the filter appears not to work at the moment it is most needed.

  **Acceptance criteria.**

  - With a filter set, a live call or signal the filter does not match does not appear in the
    list, the timeline or a path visit's contents. One it does match appears without a reload.
    `render-web-ui.js` drives one live row of each kind and fails with the push-everything line put
    back.
  - `state.allTransactions` and `state.allSignals` still receive every live row, so chips, repeat
    marks, tab counts and the path skeleton still describe the whole session.
  - No filter is evaluated in `web/js`. The match comes from the daemon.
  - With no filter set, a live row costs no request.
  - If the re-read option is chosen, it is throttled, and the design states the most requests a
    burst of 100 live rows can cause.
  - Pausing is unchanged: it still drops rows and re-reads on resume (`docs/decisions/web-ui.md`).

  - **The header and the tab count follow live rows.** *(Added 2026-10-19, from the first verify
    panel on `item:web-transactions-paged`.)* Today `renderCounts` runs on a load and from
    `setTimeRange`, never when a live row arrives, so `16 of 16 calls` stays while 20 rows are drawn,
    and the tab reads `traffic 1000` while the list grows. After this item, once a live row has been
    admitted or refused, the header and the tab count say what the list and the session now hold,
    under a filter and without one. A live-row probe asserts the header after two live rows and
    fails with the counts left to the next load. *Why here:* this item decides what a live row does
    to the lists, and a count that ignores the decision is the same defect one line lower.
  - **A whitespace-only filter is no filter on the page, as it is on the daemon.** *(Added
    2026-10-19, from the same panel.)* The daemon treats a blank filter as none
    (`SessionRepository`, `filterText.isBlank()`), so `filter=%20` returns the whole session. The
    page reportedly treats it as a filter and draws the filtered `N of M calls` shape. This was read
    from the code and a curl, not driven in the page, so the architect confirms it first. The
    criterion holds either way: with only spaces typed, the header, the notes and the live path
    behave as with nothing typed, and a live row costs no request. The page decides "is there a
    filter" in one place, and a check fails with a space typed if it disagrees with the daemon.
    *Why here:* the "no filter, no request" criterion above needs exactly that test, and getting it
    wrong costs a re-read on every live row for a user who typed a space.

  **Out of scope.** The filter grammar. The overlay, which filters in process on every change.
  Surfaces: web UI, and the daemon's live route if the architect puts the match there. No public
  API. Release-notes line when it ships.

  **The two criteria added 2026-10-19 may push this item above S**, which under the condition below
  moves it to after phase I. The product owner judges both belong here anyway: the stale count is
  the same live-row decision, and the blank-filter test is one the item must write. The architect
  sizes it with them in. **Decided 2026-10-19 by the maintainer: both criteria stay.** If that
  sizes the item above S, it moves after phase I, and the maintainer accepted that. Size: S, to be
  confirmed.

  **The condition on its place.** It is ahead of the structure moves because it shows a user
  something false today, the same reason the maintainer put the two defects ahead of them. If the
  architect sizes it above S, it moves to after phase I and stays ahead of 1a. A live bug is worth more than a structure move only while
  it costs about the same.

  **Done, 2026-10-19** (KIN-52 to KIN-55, one QA pass and one fix round, size S as sized). **Design
  chosen: a throttled re-read under a filter.** With a filter set, a live row goes only into
  `state.allTransactions` / `state.allSignals` and arms one re-read, which fires 1 s after the first
  row that needs one and never while another is in flight, through the same routes a load uses. With
  no filter, rows are pushed as before and cost no request. The daemon and the wire are unchanged,
  and so is every `:inspector-model` type. **Burst bound:** 100 rows in a second cause at most 2
  re-reads, which is 6 requests with signals under 1000 matches. The bound counts re-read requests
  only. Each live signal also reads `/current`, with or without a filter, which predates this item.
  The header and the traffic tab redraw on every live call and when a re-read lands. A
  whitespace-only filter is stored as empty in `applyFilter`, the one place `state.filter` is set, so
  the page and the daemon agree that spaces are no filter.

  Learned: the first QA pass found a regression in the harness, not the page. The new live probe
  opened and unfolded the newest path visit and never closed it, so `probePath`, which runs later on
  the same page, found no collapsed visit and `failures unfolded` and `expanded unfolded` passed with
  nothing to test (`0 summarised lines`; `marker dividers` 5 instead of 4; the `lastCapture` check
  read the other text). That became KIN-55. The fix puts back `state.lastCaptureTs` and
  `state.expandedVisits` after the probe. The same pass found two rules no check guarded, the
  signals-ticket test inside `rereadLive` and the session clause of `stands()`; each now has a check
  (`older reread signals`, `reread session changed`). Proof in both directions: push-everything put
  back in `ws.onmessage`, 191 passed 6 failed; the redraw removed from the handler, from the
  re-read, and from both; the busy guard removed (`2 out at once`); `liveTail` check removed from
  `rereadLive` (`live paused no read` fails); `state.filter = typed` put back (`blank filter
  header` and `blank filter free` fail); `chip.dataset.failed = "0"` in `50-path.js` (`failures
  unfolded` and `expanded unfolded` fail, 195/2). Headless Chrome 1440, 1000 and 400 px, filter
  `status>=500 | tag:state`: at arrival nothing unmatched is drawn and the header reads `2 of 27
  calls`; a filter edit, a session switch and a dropped socket during a re-read all end on the
  daemon's own rows. Counts: `render-web-ui.js` 197 passed 0 failed, 1 n/a (base 187),
  `check-web-static.js` 87/0 (base 79), archive probe 37/0, `:repo-checks:test` 35/0,
  `:inspector-daemon:test` 268/0; client-name scan clean.

  Left as found, not fixed here: the page and the daemon disagree on two invisible characters, U+FEFF
  (the page sends no filter where the daemon would answer 400) and U+001C (the daemon reads it as no
  filter, the page as a filter). Nothing resets `state.reread.busy` except the read finishing, so a
  request that never settles would stop re-reads until a reload; not observed. The probe's restore
  has no check of its own. Older than this item and for the product owner: the session glance and the
  picker label do not follow live rows, a live call with no filter does not redraw the timeline, and
  the header count is clipped at 400 px.

  cost: 9 calls (delivery-tracker 3, engineer 2, QA 2, product-owner 1, release-steward 1), ~1.0M
  tokens, 2 h 07 min, Small, 1 fix round

- ~~**Row labels drop a non-default port.**~~ **Done, 2026-10-21.** PR #105 merged as `1da0d75`; `build`, `release-swap`, `repo` and `web` green
  on the PR (the push run on `main` was still queued when QA wrote). KIN-60 is Done on QA's pass,
  with real daemons built from base and head. New `NetworkTransaction.hostPort` in
  `:inspector-model` is `host`, plus `:port` unless the port is null or `defaultPortFor(scheme)`.
  `url` is built on it too, so the rule exists once, and `SessionRepository.kt` (MCP `timeline`,
  `export.json`, the HTML export) and `ExportMarkdown.kt` (the Markdown timeline table) read it.
  Tests are in `SignalToolsTest`, `ExportTest` and the model's `PortTest`. Each old line put back
  alone fails exactly its surface's test, and a `hostPort` that keeps every port fails 3 model and 2
  daemon tests. Counts: model 159 to 160, daemon 268 to 270.

  **What was learned.** `hostPort` is a new public declaration in a published module. It is
  getter-only like `url`, so it is absent from REST and `export.json`, and no golden file applies
  (`:inspector-model` has no twin; the exemption is recorded in `structure/twins.txt`). `url` is
  byte-identical to base for all nine call shapes QA sent, empty `?` included. Port 443 over http
  and 80 over https are not that scheme's default, so they keep their port. Two older defects
  surfaced and were not filed: an IPv6 host is not bracketed, so `url` reads `http://::1:8080/v6` at
  base and head and the label is ambiguous the same way (not confirmed against a real captured IPv6
  call); and the session summary's `byHost` groups by bare host, so `:8080` and `:9090` still merge
  there (`SessionRepository.kt`, outside this item). The agent-visible change is in `UNRELEASED.md`.
  Not verified locally: the full `./gradlew build` (no Android SDK).

  cost: not recorded — built in the 2026-10-20/21 coordinated run, one builder session, no QA before merge, QA after merge; 1 round

  *Ticket label:* `item:row-label-port`. **Ordered
  2026-10-18, scheduled in Order from here**, after the `McpTest.kt` split.

  Two places build a call's label from host and path by hand:

  - `SessionRepository.kt:62`, `label = "${txn.method} ${txn.host}${txn.path}"`. This is the label
    on every MCP `timeline` entry, and `export.json` and the HTML export carry the same
    `TimelineEntry`.
  - `ExportMarkdown.kt:122`, the Markdown export's timeline table.

  Neither has the port. Two local servers on `127.0.0.1:8080` and `127.0.0.1:9090` read as one
  endpoint, and on a real session a call to `:9090` was labelled `GET 127.0.0.1/json`.
  `docs/decisions/capture.md` ("Capture records the port, and `url` omits it only when it is the
  default") records this exact defect for URLs. KIN-23 fixed the same cause in MCP `explain`.
  Leaving the query out of a label is reasonable, because a label names an endpoint. Leaving the
  port out is not, because the port is part of which server answered.

  **The user and the problem.** An agent over MCP reading a timeline, and anyone handed a Markdown
  export. Both are told that calls to two different servers went to one. On a phone, the common case
  is a local mock server beside the real backend, which is exactly when the difference matters.

  **Acceptance criteria.**

  - Both labels show `host:port` when the port is not the scheme's default, and the bare host when
    it is or when `port` is null, so archives written before the port existed read as before.
  - **No third copy of the rule.** The labels get the default-port decision from `defaultPortFor`
    in `:inspector-model`, the function `url` already uses, through one helper that both call sites
    share. If the architect puts that helper in `:inspector-model`, it is a new public declaration
    in a published module. That module has no twin and no golden file, but the item says so.
  - A daemon test with calls on `:8080` and `:9090` asserts two different labels in the MCP
    `timeline` output and in the Markdown table, and a call on `:443` over https has no port. It
    fails with either old line put back. Its test goes in the split test files, not in
    `McpTest.kt`.
  - The label still has no query.

  **Why after the split.** Its test belongs with the timeline tests, which the split moves. Landing
  it first would add to the file being split, which is how KIN-26 happened.

  **Out of scope.** The web UI and overlay rows, which draw the host separately and are not this
  defect. Any other change to the label's shape. Surfaces: daemon, MCP, exports. An agent sees a
  different label, so it gets a release-notes line.

- ~~**The traffic list does not say when it holds only part of what it counts.**~~ **Done, 2026-10-21.** PR #104 merged as `fb8e54e` and its fix round, PR #110, as `ff4f9de`;
  `build`, `release-swap`, `repo` and `web` green on both, and on the push run for `ff4f9de`. KIN-56
  and the bugs KIN-72 and KIN-73 are Done on QA's second round. Under a filter past the ceiling the
  header reads `newest N of M matches shown`, with M from the route's `matched`, and `400 of the
  newest 1000 of 1200 matches shown` when a stretch is brushed. The list draws `filterNote`'s
  `newest N of M matches loaded` in a sticky `#list-note` outside the listbox. A stretch older than
  the loaded rows says so and how to reach them: `151 calls in this stretch are older than the
  newest 1000 the list loaded — filter to reach them`, and under a filter `none of the newest N of M
  matches the list loaded is in this stretch, and the K older ones are not loaded — narrow the
  filter to reach any here`. The 'at least' rule holds. No boundary row was built; the note and the
  stretch text meet the criteria. The export shows no list note. No Kotlin changed and
  `LIST_CEILING` is untouched. Counts at `ff4f9de`: static checks 90, live probe 204 passed, 0
  failed, 1 n/a (`facet filter`), archive probe 38.

  **What was learned.** QA's first round failed two criteria and the fix round passed both.
  `olderThanLoaded` counted the unfiltered whole-session copy, so under a filter a stretch where
  nothing matched read '500 calls … narrow the filter to reach them' beside an axis that said `0
  calls`; at base that screen read `no calls in the selected stretch`, which was true, so this was a
  regression the item introduced. The page evaluates no filter and cannot count matches in a
  stretch, so under a filter it now claims no count. The new header branch was guarded by
  `!state.timeRange`, so a brushed stretch under a filter still read `1000 of 3016 calls`, the
  string the criterion rules out; the builder had deferred that to 1b, but the criterion made no
  exception. The static check for the first case asserted the wrong number (151 where 76 match), a
  guard that codified the defect; it now covers five stretches. A stub was not enough: the builder
  had not run the failing case in Chrome. Left open, not blockers: a stretch holding some loaded
  rows and some older unloaded matches says `at least 10 calls hidden` and nothing that more matches
  are older; at 400 px the header count is off-screen and at 1000 px the closed picker drops the
  start time (1a (e) and 1b (a)).

  cost: not recorded — built in the 2026-10-20/21 coordinated run, one builder session, no QA before merge, QA after merge; 2 rounds

  *Ticket label:*
  `item:web-list-says-what-it-holds`. **Ordered 2026-10-19 by the maintainer: straight after
  `item:web-current-session-guard`**, with the other web defects (§5 → "Order from here").
  The product owner had proposed straight after `item:web-transactions-paged`.

  Two defects, one cause. The list draws the newest 1000 matching calls (`LIST_CEILING`), which the
  maintainer accepted on 2026-10-19 on the condition that the page says so. With no filter it now
  does (KIN-45). In two other cases it still does not:

  - **Under a filter.** `path:/v3/shop/bulk` on a 3016-call session matches 1200. The header reads
    `1000 of 3016 calls` and the list pane has no note. The path, the waterfall and the timeline
    all draw `newest N of M matches loaded` (`filterNote`), and the list, which is the default
    view, does not. So a reader is told 1000 calls match when 1200 do. The panel called it the most
    misleading thing left on these screens.
  - **Under a brushed stretch older than the newest 1000.** The list says `no calls in the selected
    stretch` while the THIS STRETCH figure counts calls in it. Live, and at base. A filter that
    narrows the matches under 1000 is the only way out, and nothing tells the reader that.

  **The user and the problem.** A web UI user on a Mac with a long session who narrows it, by a
  filter or by brushing the axis, which is how anyone looks for one call in a long session. The
  list states a count, or an absence, that is false, and the panes beside it state the truth.

  **Why now.** The ceiling was accepted on the condition that it is labelled. These are the two
  places it is not, so until they are fixed the condition is not met. It is also cheap: the note,
  the route's `matched` number and the stretch rule already exist for the other three panes.

  **Acceptance criteria.**

  - Under a filter that matches more calls than the list draws, the header numbers the matches and
    says how many are drawn, in words that cannot be read as the match count: for example
    `newest 1000 of 1200 matches shown`. `1000 of 3016 calls` does not appear. The match count
    comes from the route's `matched`, never from the rows held.
  - The list pane draws the same `newest N of M matches loaded` note the path, waterfall and
    timeline draw, through `filterNote` or whatever the architect makes it, not a fourth wording.
  - A brushed stretch the list holds no calls for, while the stretch holds calls, never reads `no
    calls in the selected stretch`. It says the calls there are older than what the list loaded and
    how to reach them. The rule `docs/decisions/web-ui.md` records for a stretch ("at least" when
    the stretch reaches below the oldest mono at which both lists are whole) holds here too.
  - The panel suggested a boundary row at the oldest end of a capped list: `203 older calls not
    drawn — filter to reach them`. That is one way to meet the last two criteria. The architect
    chooses.
  - Checks over a 3016-call stub clamped as the daemon clamps, in `check-web-static.js` and in both
    probes where a probe can see it: one for the filtered header, one for the list note, one for
    the stretch. Each is proven red with the old wording put back. An exported session behaves the
    same offline (`render-web-ui-archive.js`).
  - The list still draws at most 1000 calls, and the daemon's clamp is unchanged.

  **Out of scope.** Whether the whole-session read is capped (KIN-42). The four-numbers polish,
  which is 1b's (f3). Virtualising the list. The filter grammar; no filter is evaluated in
  `web/js`. Surfaces: web UI only. No public API, no `:inspector-model` change. Size S, to be
  confirmed by the architect. Release-notes line, because a user could have been misled by it.

  **Not a decision overturned.** It extends `docs/decisions/web-ui.md`'s rule that a pane a filter
  thinned says so to the one pane that does not.

- ~~**The now strip and the signal panes can show what is no longer true.**~~ **Done, 2026-10-21.** PR #107 merged as `6804c94`; `build`, `release-swap`, `repo` and `web` green
  on the PR, and the push run on `main` succeeded. KIN-57 is Done on QA's pass. A new
  `followSession` clears the signal side at once on a switch (the picker's path, and
  `afterSessionChange` after a delete or clear), and the glance reads `loading this session…` until
  `loadSignals` has written the selected session. One reader, `readCurrent`, serves `loadSignals`
  and `refreshCurrent` with a sent/written ticket, so the later-started `/current` read decides.
  `state.currentSessionId` records whose values the strip holds: a failed read keeps them on the
  same session and empties them after a switch. The fixture's long session has two `state` signals
  of its own, so `now late reply dropped` asserts them. Four new live-probe lines (`now switch shows
  none`, `now newest read decides`, `now load read overtaken`, `now failed read kept`); live probe
  200 to 204 passed, 1 n/a; archive 38; static 90. The only Kotlin change is the test fixture.

  **What was learned.** QA ran four mutated copies of the page scripts in parallel against one
  daemon, since the probe loads scripts from its own tree and asks the daemon only for data; each
  mutant failed only its own lines (the ticket check also fails `failed read kept`, which depends on
  it). The delete path, which the builder said no probe drives, was covered in Chrome. The product
  owner's ruling reversed KIN-51's accepted trade-off: a failed `/current` read no longer empties
  the strip on the session you are on, only across a switch, so the KIN-51 wording in
  `UNRELEASED.md` and `STATUS.md` was amended. Still false in the switch window and not fixed here:
  the timeline's call rows and the endpoint chips show the session just left until the load lands
  (true at base; calls are out of this item's scope, and 1a rebuilds the switch); the path, the
  waterfall and the tag browsers are not redrawn by `followSession` (by inspection only; QA did not
  render them). Not verified locally: the full Gradle build (no Android SDK).

  cost: not recorded — built in the 2026-10-20/21 coordinated run, one builder session, no QA before merge, QA after merge; 1 round

  *Ticket label:*
  `item:web-now-strip-follows-session`. **Placed 2026-10-19 by the product owner: straight after
  `item:web-list-says-what-it-holds`** (§5 → "Order from here"), with the other web defects.

  Three ways the signal side of the page shows something false, all left after KIN-51:

  - **The switch window.** The picker's `change` handler (`web/js/99-init.js`) and
    `afterSessionChange` (`web/js/90-sessions.js`, after a delete or clear) both reach
    `loadTransactions`, which reads a page of calls, the markers, the whole-session copy of the
    calls (`syncAllTransactions`) and only then `loadSignals`. Until that lands, `state.current`, `state.signals`, `state.allSignals` and
    `state.screens` are still the previous session's, so the now strip, the timeline's signal rows
    and the glance describe the session just left, under the new one's name. True at base, before
    KIN-51. On a large session the window is as long as the whole-session read, which is the
    25-second wait recorded under KIN-42 in §3 → Guards, so the reader has time to believe it.
    `afterSessionChange` clears all of these only when no session is left, and the picker never.
  - **Out-of-order replies.** `refreshCurrent` runs on every live signal. KIN-51 drops a reply for a
    session no longer selected, but two reads for the same session have no load counter, so an
    older reply landing last overwrites a newer one. `loadSignals` has `signalLoad` for exactly
    this; `refreshCurrent` does not share it.
  - **A probe that cannot fail on one mutation.** In KIN-51's `now late reply dropped`
    (`scripts/lib/current-guard-probes.js`), the fixture's second session has no current values,
    so "the strip lists the new session's own values" and "the strip is empty" are the same
    assertion. A mutation that writes `[]` on a mismatched session, instead of returning, passes
    CI. That is a guard only ever seen passing, against "prove guards in both directions".

  **Ruling on KIN-51's trade-off: on an unchanged session, keep the values; blank only across a
  switch.** Since KIN-51 a failed `/current` read always empties the strip, until the next signal
  or load. Blank is right after a switch, because the values held belong to another session and
  nothing says so. On the same session it is the worse of the two. Every row the strip keeps is
  this session's and carries its own age, so it is a dated fact: `docs/decisions/signals.md`
  ("The collapsed line says how stale the oldest of it is") is what makes a kept row honest. An
  empty strip is undated and reads as "this app holds nothing now", which is the confidently wrong
  reading the age exists to prevent. The cost of keeping is one value that may be one signal old,
  and the failed read is rare: the usual cause is the daemon going away, which already raises the
  banner and re-reads everything on reconnect. The fix is to record whose values `state.current`
  holds, so a failure keeps them only when they are the selected session's. **This touches no
  recorded decision**: KIN-51's rule ("a failed read after a switch leaves the strip empty")
  still holds, and the accepted trade-off lives only in `docs/STATUS.md` and the `UNRELEASED.md`
  line, which the delivery tracker amends when this ships.

  **The user and the problem.** A web UI user on a Mac who switches sessions to compare two runs,
  or watches the strip under live traffic. The strip and the timeline claim to describe this
  session now, and for a window, or after one slow reply, they describe another session or an
  older moment, without a mark that says so.

  **Why now.** KIN-51 just made the strip's session promise. These are the holes left in the same
  two functions, while the code and the probe are fresh. It is small, and it shows a user
  something false today, which is the maintainer's rule for going ahead of the structure moves.

  **Acceptance criteria.**

  - From the moment another session is selected until its own signals land, the now strip, the
    timeline's signal rows and the glance show none of the previous session's values: either
    nothing, or a loading state that names no values. A probe holds the new session's signal read,
    asserts all three, and fails with the clear removed.
  - Of two `/current` reads for one session, the later-started one decides the strip, whatever
    order they land in. A probe releases them newest first and fails with the counter removed.
    A `/current` read started by `loadSignals` and one started by a live signal obey the same
    rule; the architect chooses whether they share `signalLoad` or a counter of their own.
  - A failed `/current` read on an unchanged session leaves the strip as it was. A failed read
    after a switch still leaves it empty: KIN-51's `now failed read empty` stays green. A probe
    for the unchanged case fails with KIN-51's `catch(() => [])` behaviour kept for it.
  - The live probe's fixture has a second session with current values of its own, different from
    the first's, so `now late reply dropped` asserts the new session's values and not an empty
    strip. It is proven red with a mismatched reply written as `[]`, and the `n/a` branches that
    the old fixture took no longer fire.
  - No filter is evaluated in `web/js`, and no new route is added.

  **Out of scope.** The call list and the counts, which the first page of the switch already
  replaces. Making the whole-session read shorter, which is KIN-42. Marking a kept value as "not
  refreshed", which can be a later polish if the kept-value case is ever seen in use. The overlay,
  which holds one session. Surfaces: web UI only. No public API, no `:inspector-model` change.
  Size S, to be confirmed by the architect. Release-notes line, because a user could have seen the
  switch window.

  **Why not only a criterion on 1a.** 1a rebuilds the session switch, but it comes well after
  this one in "Order from here" and is sized L, and folding this in would leave a false fact on screen until then,
  the same argument that made KIN-51 its own item. 1a carries one criterion instead: the probes
  this item adds stay green through its rebuild.

- ~~**A live call reaches the list but not the timeline, the glance or the picker.**~~ *Ticket label:*
  `item:web-live-calls-reach-every-pane`. **Proposed 2026-10-19 by the product owner: straight after
  `item:web-now-strip-follows-session`**, with the other web defects. **Placed 2026-10-21 by the
  maintainer after 1a and before 1b**, unsplit, because 1a reworks the session picker in the same
  files (§5 → "Order from here").

  **Done, 2026-10-21.** PR #113 merged as `952b6c6` and its fix round, PR #117, as `433f30d`; KIN-58 is Done
  on QA's pass over detached copies, in two rounds. A live call arms one trailing redraw of the timeline and
  the glance per 250 ms (`LIVE_DRAW_MS`, `drawLive` in `web/js/05-network.js`): a burst of 100 calls over about
  1.2 s cost 5 timeline redraws and no `/api/sessions` read, against a stated bound of ceil(T/250)+1. The
  selected picker option and its face follow the header's own total through `followLiveCount`, which ends in
  `paintPickerFace`, with no request; paused, or for another session, nothing moves. Static checks 96 to 99, live
  probe 219 to 225, archive probe 40; no Kotlin change.

  **What was learned.**
  - **Round 1 failed one criterion, the picker.** Pause, two calls while paused, resume: the header and the glance read
    120 and the option and face stayed at 118 until the next live call. The count was written only in the
    live-call branch, and the resume's load set `sessionTotal` without telling the picker. The fix is in
    `setLiveTail` alone: once its load lands, for the same session and with `sessionTotal` known, it calls
    `followLiveCount()`. The existing `live paused still` line was strengthened rather than a new one added, and QA
    made it red against `952b6c6`'s `setLiveTail` (`header total 18 (was 16), option "... 23 calls"`; the stale
    option read 23, not the pre-pause 16, because earlier probe steps had pushed it up through the live branch).
  - **The picker face's single writer paid off.** 1a's `paintPickerFace` is the one place the face is written, so
    this item called it instead of adding a second writer.
  - **A red proof can guard the wrong thing.** With the redraw removed, `socket run stays open` stays green, because
    with no redraw a run trivially stays open: it guards the follow rule, not the redraw. An open run staying open
    was checked in jsdom only.
  - **The glance counts the session, not the filter**, as `web-ui.md` records: under `status>=500` the header read
    `4 of 16 calls` and the glance `calls 16`.
  - **Not verified.** Safari, Firefox and a phone-width browser (headless Chrome 131 only, at 1440, 1000 and 400 px).
    The full `./gradlew build` on the QA and builder machines (no Android SDK; CI ran it on the PRs).
  - **Left open, outside this item.** A live signal or marker still redraws the timeline once per message, so a
    signal burst is unbounded the way calls were; the builder proposed routing both through `drawLive`, unplaced.
    At 400 px the timeline's path column is cut, which is 1b's (b).

  cost: not recorded — built in the 2026-10-20/21 coordinated run, one builder session plus a fix-round session, no QA before merge; QA after merge, 2 rounds

  Since KIN-54 a live call moves the header and the traffic tab. Three other things on the page still
  describe the session as of the last load:

  - **The timeline, with no filter set.** The `txn` branch of `ws.onmessage` pushes the call and
    redraws the list, the waterfall and the path, and never calls `renderTimeline`. A marker and a
    signal both do. So a call appears on the timeline only at the next redraw for some other reason.
    Under a filter the re-read redraws it, which is why only the no-filter case shows.
  - **The session glance** (the detail pane with no call selected) reads `calls 27` while the
    header says 29, and its hosts and slowest calls are as stale.
  - **The session picker's label**, `6 calls`, is the daemon's `txnCount` from the last
    `loadSessions`, and nothing re-reads it while the session records.

  **The user and the problem.** A web UI user on a Mac watching a session record, which is what live
  tail is for. One screen states two different call counts for one session, and the timeline, the
  view that exists to show calls beside screens and signals, leaves out the newest ones. Each is a
  false fact, and each corrects itself only when the reader does something unrelated.

  **Why now.** It is the last of what a live call owes the page, in the handler KIN-52 to KIN-55 just
  rewrote, while its probes (`live header plain`, `live header filter`) are fresh and can be extended
  rather than rebuilt. Small, and it shows a user something false today.

  **Acceptance criteria.**

  - With no filter and live on, a call arriving while the timeline is shown is drawn on the timeline
    without any other action. The follow rule in `docs/decisions/web-ui.md` still holds: a timeline
    at its end follows the new call, a timeline scrolled back does not move, and an open run stays
    open. The existing `socket …` follow checks stay green, and a new check fails with the redraw
    removed.
  - A burst does not redraw the timeline once per call without bound. The architect chooses the
    shape (throttle, or the frame the list already draws in); the bound is measured the way
    `reread burst of 100` measured the re-read, and stated in the change.
  - With live on, the glance's `calls` row equals the session count the header states, after any
    number of live calls, with or without a filter, and its hosts and slowest calls include those
    calls. A probe sends calls with the glance shown and fails with the redraw removed.
  - The selected session's picker label states the same call count as the header after live calls,
    without a reload and without opening the picker. A probe fails with the old behaviour put back.
  - With live paused, none of the three moves, as the header does not. A live call for another session
    changes none of them.
  - No filter is evaluated in `web/js`, and no new route is added. If the picker label is kept
    current by re-reading `/api/sessions`, the re-read is throttled as the filtered re-read is.

  **Out of scope.** The labels of sessions other than the selected one; 1a moves the session list to
  a Sessions page, which is where every session's count being live belongs, if it does. The glance's
  signal side after a switch, which is `item:web-now-strip-follows-session`. The count clipped at
  400px, which is 1a's (e). The overlay, which redraws from one `StateFlow`. Surfaces: web UI only.
  No public API, no `:inspector-model` change, no daemon change. Size S, to be confirmed by the
  architect. Release-notes line, because a user could have read the stale count.

  **Not a decision overturned.** `docs/decisions/web-ui.md` records that the glance reads the
  whole-session copy so a filter never shrinks it; this keeps that copy's readers current, which is
  what that rule assumes.


- ~~**A server is its host and a non-default port, but not yet everywhere.**~~ **Done, 2026-10-22.** PR #129
  merged as `66c1bad`; `build`, `release-swap`, `repo` and `web` passed on the PR, and the push run on
  `main` was queued when QA looked and was not seen finishing. KIN-92 is Done on QA's pass over detached
  copies of base and head and a live archive, one round, no blocking findings.

  `NetworkTransaction.hostPort` brackets a host that holds a `:` unless it already starts with `[`, and
  `url` is built on it, so a call to `::1` on `:8080` has the url `http://[::1]:8080/v6` and the label `GET
  [::1]:8080/v6`. The brackets are added when `url` is read, so archives recorded earlier read the same way.
  `mirror-cases/urlOf.json` (6 cases) is run by `MirrorCasesTest` and by `check-web-static.js`; the web
  `urlOf` gained `hostPortOf` and `statsOrigin` uses it. `byHost` in the session summary keys on `hostPort`:
  QA's archive gave `{'[::1]:8080': 2, 'api.qa.invalid': 2, '[::1]': 1, '[fe80::1]:8443': 1,
  '127.0.0.1:8080': 1, '127.0.0.1:9090': 1, 'odd.qa.invalid:443': 1}`, where base gave `{'::1': 2,
  '127.0.0.1': 2, …}`. Every non-IPv6 `url` is byte-identical to base. No signature or golden file changed.
  Counts, base to head: `:inspector-model` 160 to 162, `:inspector-core` 74 to 76 with 0 skipped (the
  hand-over says 73 to 75), `:inspector-daemon` 360 to 362, static checks 107 to 109; live probe 251 and
  archive probe 48 unchanged. `INTEGRATION.md` is at v63 and the `UNRELEASED.md` line came in with the PR.

  Red by QA, each restored: the web `hostPortOf` without brackets (`urlOf: NO - 3 of 6 cases failed` and
  `stats origin: NO - http://::1:8080 for http://[::1]:8080`); the Kotlin `hostPort` without brackets
  (`MirrorCasesTest.urlOf_cases`, `PortTest` and the OkHttp capture test fail, the Ktor one passes). With
  the bind forced to an address this machine lacks, both IPv6 capture tests report `skipped` with the reason
  in the JUnit XML and `SKIPPED` in the Gradle console.

  **What was learned.** Ktor 3.5.0 already stores `[::1]` and OkHttp stores `::1`, which the builder
  measured by writing the test first; so "not bracketed twice" is what keeps the Ktor capture right, and the
  bug was OkHttp's alone. A third copy of the rule, which neither the item nor the architect named, sat in
  `ExportMarkdown.kt`'s routes table (`"${row.host}:$it"`); it now reads the model's `hostPort`. Gradle does
  not print an assumption's message, so the root `build.gradle.kts` now logs `SKIPPED` test events, which is
  how a skip becomes visible in CI. The `host:` filter term is unchanged and matches the stored host, so it
  still sees `::1` or `[::1]` by capture path (the grammar is frozen).

  **Left open, not placed (the product-owner's).** One IPv6 server reached by both clients (`::1` from
  OkHttp, `[::1]` from Ktor) draws as two identical-looking route rows on the Stats page and in the Markdown
  routes table, because the route key and its `host:` term use the stored host; `byHost` merges them. It
  needs one app using both clients against one IPv6 server. QA reproduced it again on
  `item:web-stats-route-breaks-whole`. Zone ids (`fe80::1%en0`) are out of scope. Not run locally: the full
  build, `-Pinspector=off`, Android and the iOS links (no Android SDK, no working `xcrun`); CI ran them.

  cost: not recorded — built in the 2026-10-21/22 coordinated run, batch 1, one engineer per item, no QA before merge; QA after merge, 1 round

  *Ticket label:*
  `item:host-port-everywhere`. From QA and the hand-over on `item:row-label-port`, and
  `docs/STATUS.md` → Not verified.

  **For whom.** Anyone who copies a URL for an IPv6 call: a cURL or a replay from the web UI, the
  overlay's Copy URL, an agent reading an export. Also an agent or a CLI user reading the session
  summary. `NetworkTransaction.url` writes `http://::1:8080/v6`, which is not a URL, and the label
  `GET ::1:8080/v6` cannot say where the address ends. OkHttp's `HttpUrl.host` gives an IPv6 literal
  with no brackets, and the OkHttp interceptor stores it as it comes. Whether Ktor's does too is
  unchecked. Separately, `SessionRepository.summarize` groups `byHost` by bare `host`, so two
  servers on one machine are one entry in `GET …/summary`, MCP `session_summary`,
  `inspector summary` and the exports' summary.

  **Acceptance criteria.**
  - `hostPort` brackets a host that holds a `:`, so the `url` of a call to `::1` on `:8080` is
    `http://[::1]:8080/v6` and its label reads `GET [::1]:8080/v6`. A host already in brackets is
    not bracketed twice.
  - It is decided in `:inspector-model` and in the web UI's `urlOf` mirror, with a mirror case run by
    both sides, so an archive recorded before the fix reads the same way.
  - A real call to the IPv6 loopback, captured through Ktor and through OkHttp, gives that `url`. If
    a CI runner has no IPv6 loopback, the test says it was skipped and why, not that it passed.
  - `byHost` keys on `hostPort`: `127.0.0.1:8080` and `127.0.0.1:9090` are two entries, and a default
    port adds nothing to its key.
  - Proven red with each rule removed.

  **Out of scope.** The filter grammar, which is frozen and has no port term, so two ports of one
  host still share a `host:` term (a documented superset). The route key, which already keeps ports
  apart. Zone ids (`%en0`). Surfaces: `:inspector-model` (overlay, web UI, daemon, MCP and exports
  through it), the web UI mirror and the daemon summary. **Public API:** no signature changes, but
  `url` is public in the published `:inspector-model` and its value changes for IPv6 hosts, and
  `byHost`'s keys change for agents. Both need an `UNRELEASED.md` line. Whether INTEGRATION.md
  needs a version is the release-steward's call. No noop twin applies (the model is exempt), and no
  golden file changes. **Size:** S, or M if the capture tests need a fallback for CI.

- ~~**At 400px the Stats page breaks a route's host mid-word.**~~ **Done, 2026-10-22.** PR #132 merged as
  `7b37d7a`; `build`, `release-swap`, `repo` and `web` passed on the PR, and the push run on `main` was in
  progress when QA looked and was not seen finishing. KIN-93 is Done on QA's pass over detached copies, one
  round, no blocking findings.

  At 400px, served and exported, a route cell now ends a line only at a `.`, a `:` or a `/`:
  `statsBreakable` in `web/js/95-stats.js` puts a `<wbr>` after each of those in the server and after each
  `/` of the path, never inside `[::1]`, inside `://` or after a path's leading `/`, and
  `web/css/85-stats.css` holds each piece `nowrap` below 700px. QA's own Chrome session at 400px (3 line
  breaks, `bad=[]`, `cut=[]`, document 400 of 400) kept `api.qa-long-name.invalid` whole and
  `127.0.0.1:8080` and `[::1]:8080` unbroken. At 1000 and 1440 a route is one line. A route too long to
  break that way widens its column inside the table's own scroll and the page never scrolls sideways (`shell
  <w> page stats fits` green at all four widths). The cell's text is unchanged. New: `stats 400 route breaks
  served` and `… export` (17 route cells, 23 line breaks), `stats 400 routes whole`, `stats 1440 routes one
  line served` in `measure-web-ui.js --only shell` (42 readings, 0 failed), a `routes` measure session, and
  the static check `stats route pieces` (109 to 110 checks; not pinned in `ci.yml`). The live probe (251)
  and archive probe (48) did not move.

  Red by QA: with the base's `85-stats.css` alone, both break readings read `mid-word` (33 breaks, including
  `averyveryverylong-|unbroken-hostlabel`); with the base's stylesheet and script together, 30 breaks
  including `http://127.0.0.1:808|0` and `https://api.qa.exam|ple.invalid:8443`; with the `nowrap` hold
  removed, both `mid-word`; with the static check's bracket rule ignored, `stats route pieces: NO -
  http://|[::|1]:|8080 …`.

  **What was learned.** The criterion's literal red proof cannot be made: `overflow-wrap: anywhere` put back
  on its own leaves the reading green, because the pieces are `nowrap` and the property cannot break inside
  them. The reading is red with the base's whole stylesheet put back, or with the hold removed, and
  `web-ui.md` says so (QA confirmed it). `<wbr>` alone was not enough, since Chrome also breaks after a `-`
  inside a host label; and Chrome breaks at a `<wbr>` even under `nowrap`, so above 700px the `<wbr>`s are
  `display: none`, which the builder found from the 1440 screenshot. The count of base breaks depends on
  fonts (the builder saw 29, QA 30); the named break `127.0.0.1:808|0` reproduces.

  **Left open.** The trade-off is accepted and stated: one over-long route widens the whole route column
  (228px in the test session), so fewer number columns fit beside it at 400px, and they are reached by
  scrolling the table. The mixed-client IPv6 double row (see `item:host-port-everywhere`) is still there.
  Headless Chrome only; `measure-web-ui.js` is not in CI.

  cost: not recorded — built in the 2026-10-21/22 coordinated run, batch 1, one engineer per item, no QA before merge; QA after merge, 1 round

  *Ticket label:*
  `item:web-stats-route-breaks-whole`. From QA on `item:endpoint-latency-stats`, and
  `docs/STATUS.md` → Not verified.

  **For whom.** A web UI user on a narrow window reading the Stats page. `.stats-host` is
  `overflow-wrap: anywhere`, so the cell reads `api.qa.invali` / `d`, and `http://127.0.0.1:808` /
  `0`, which a reader takes for port 808.

  **Acceptance criteria.**
  - At 400px, served and exported, no line of a route cell ends inside a host label, a port or a path
    segment. A host breaks only at a `.`, a `:` or a `/`.
  - A route too long to break that way is still on screen in full within the table's own sideways
    scroll, and the page itself never scrolls sideways (`shell <w> page stats fits` stays green).
  - A Chrome reading in `measure-web-ui.js` at 400px is proven red with `overflow-wrap: anywhere`
    put back.

  **Out of scope.** The table's columns and order, and the term a row links with. Offering
  `sort=errors` on the page. Surfaces: web UI only. **Size:** S.

- ~~**Housekeeping after the 2026-10-20/21 run.**~~ **Done, 2026-10-22.** Three PRs: #124 (merge `6c1cb32`: the `INTEGRATION.md` headings and the `AGENTS.md` table), #130 (`e071594`: the
  `architect.md` line, approved by the maintainer on 2026-10-21) and #140 (`95b4de4`: the Sessions page). KIN-89 is
  Done on QA's two passes over detached copies (part 1, then the whole item), no blocking findings.

  Only v62's heading says `(this document)`; no version bump, because nothing a consumer has to do changed. The
  four structure-pass rows became one `S` row (phases A to I), and `AGENTS.md` went from 14,990 to 14,606 bytes of
  its 15,000. `AgentGuideTest.the_root_guide_stays_under_its_budget` passed at 14,999 bytes and failed at 15,000 and
  15,007 (`AGENTS.md is 15000 bytes; the budget is under 15000`). `architect.md` line 31 now names
  `api/inspector-ui-public-api.txt`. With `--data` elsewhere the Sessions page names the real folder: QA read
  `<scratch>/custom data/sessions` (a path with a space) at 1440 and 400, `<scratch>/home/.inspector/sessions` by
  default (an expanded path, not a `~`), and nothing in an export, where the page is hidden. The probe line
  `sessions folder` reads `yes` on head (263 passed, 0 failed, 1 n/a) and `NO - the note says "Every run of the app
  is a folder under ~/.inspector/sessions. …"` against `84a1747`'s page (262 passed, 1 failed).

  **What was learned.** The hand-over of part 1 said the root `AGENTS.md` still told readers the ui pair has no
  golden file, in two places. QA found that false at `6c1cb32`: phase I (PR #106) had already changed both lines, and
  the only stale wording left was in `architect.md`, which this item already held. The page reads the folder from
  `GET /api/mcp`'s `dataDir` through `api()`, so no daemon change and no new route; the probe checks it against a
  different route, `/api/config`'s `path`, so it does not grade the page against its own source. The pace rule's
  `deletes disabled` reads 0 of 0 on the fixture and so cannot go red there. The builder was not meant to edit the
  "Where we are" table (run rule 7), and did, because the item's own criterion is that edit.

  **Left open.** A relative `--data` shows a relative path, which is the daemon's own value and what Processes shows.
  The push runs on `main` for the three merges were not seen finishing.

  cost: not recorded — built in the 2026-10-22 coordinated run, one engineer per item, no QA before merge; QA after merge, 2 rounds (part 1 on PR #124, then the whole item with PRs #130 and #140), none failed

  *Ticket label:* `item:docs-housekeeping-2026-10-21`.
  Four small edits that each leave someone reading something false or something about to break. None
  is worth an item of its own.

  **For whom.** The agents who read these files, the consuming team reading `INTEGRATION.md`, and a
  web UI user whose daemon runs with `--data`.

  **Acceptance criteria.**
  - `.claude/agents/architect.md` no longer says `:inspector-noop-ui` has no golden file. It names
    `api/inspector-ui-public-api.txt`. That file is the maintainer's to change, so the edit goes to
    the maintainer for approval.
  - In `INTEGRATION.md` only the current version's heading says `(this document)`. v49 and v50 lose
    the label. This changes nothing a consumer has to do. Whether it takes a version is the
    release-steward's call.
  - The root `AGENTS.md`'s "Where we are" table has room for the next row. The four structure-pass
    rows (S-D, S-F, S-H, S-I) become one row naming phases A to I as done, and pointing to
    `docs/STATUS.md` for the red `repo` pushes. The file is 14,990 bytes of its 15,000 budget, which
    is why S-D and S-F were cut short, and the next status row would fail `DecisionsTest`.
  - With `--data` elsewhere, the Sessions page names the real sessions folder, as the Processes page
    already shows the real data directory. In an export it names none. The page now says
    `~/.inspector/sessions` whatever the folder is. A probe line is proven red with the fixed path
    put back.

  **Out of scope.** Any other rewording of these files. Raising the root's byte budget. Surfaces:
  docs, plus one web UI string. **Size:** S.

- ~~**A silent MCP child hangs the probe, and is left running.**~~ **Done, 2026-10-22.** PR #138 merged as `ffc9278`; `build`, `release-swap`, `repo` and `web` passed on the PR, and the push run on `main`
  was in progress when QA looked. KIN-96 is Done on QA's pass over a detached copy, one round, no blocking findings.

  `ProcessMcpControl.probe` now reads the child's stdout on a daemon thread (`mcp-probe-reader-<pid>`) into a queue
  and polls it against the time left until the deadline, so the `finally { destroyForcibly() }` runs on time and
  killing the child ends the reader. EOF is an empty `Optional`, so EOF still ends the loop early; the 15 s default,
  the timeout error text and the route are unchanged. `McpProbeStubChild` gained a `silent` mode (stdout held open,
  nothing written, EOF ignored). `a probe whose child holds its output open and never writes returns within its
  timeout` passes in 3.514 s (the 1.5 s timeout plus the probe's 2 s `waitFor`), asserts the error `did not answer a
  tools/list within 1500ms` and, through `assertStopped`, that the child is dead. Red by QA: the blocking `readLine`
  back (`had not returned after 6500ms: its deadline waits on a read the silent child never ends`, the only failure of
  9, 14 s, no hang); `poll` replaced by `take`, the same; `destroyForcibly()` replaced by `Unit`, 4 of 4 fail naming
  a pid. `:inspector-daemon:test --rerun` 362 to 363, 0 failed, and no stub process was left at t+0 to t+3 s.

  **What was learned.** The test runs the probe on a thread of its own and waits with `Future.get(timeout + 5000 ms)`,
  so a broken probe fails on the test's bound instead of hanging the suite for ten minutes, which is what happened
  to the builder of `item:guard-mcp-probe-cleanup`. QA's first `pgrep` read "1" after every run; that was QA's own
  polling shell, whose command line named the class, not a stub. `docs/decisions/daemon-and-mcp.md` named only three
  paths; the tracker added the silent-child path and why the read has a thread of its own.

  **Left open.** A write to a child that never reads is still unbounded; the probe writes two short lines, far
  under a pipe buffer, so it cannot happen with today's requests. The 5 s margin covers a JVM start and may need
  watching on a slow CI runner. Linux and Windows only through CI, and a real wedged `inspector mcp` behind the web
  UI's button was not tried.

  cost: not recorded — built in the 2026-10-22 coordinated run, one engineer per item, no QA before merge; QA after merge, 1 round

  *Ticket label:*
  `item:mcp-probe-deadline-holds`. From the hand-over of `item:guard-mcp-probe-cleanup`, which hit it
  while proving red: a test hung for ten minutes.

  **For whom.** A web UI user on a Mac who presses the MCP probe in settings while an
  `inspector mcp` is wedged. `ProcessMcpControl.probe` checks `timeoutMs` only between lines, so a
  child that keeps stdout open and writes nothing leaves `readLine` blocked for good:
  `POST /api/mcp/probe` never answers and the button never reports. The `finally` that stops the
  child runs only after the read returns, so the child also outlives the probe and shows in the peer
  list as an `mcp` process. That is the leak `item:guard-mcp-probe-cleanup` closed on the success,
  timeout and failed-write paths, still open on this one.

  **Acceptance criteria.**
  - With a stub child that keeps stdout open and never writes, `probe` returns within its timeout
    plus a margin the test states, with an error that says the child did not answer in time.
  - The child is not alive once `probe` returns, read as `McpProbeCleanupTest` reads it.
  - A `silent` mode beside the stub's `answer`, `chatter` and `close-stdin`, proven red with the fix
    removed. The red run fails on the test's own bound and does not hang the suite.
  - The existing three cleanup tests and "the real control either answers or explains why it
    cannot" stay green.

  **Out of scope.** The 15 s default, what the probe reports on success, and the route. Surfaces:
  daemon only. `ProcessMcpControl` is in `:inspector-daemon`, which is not published, so there is
  no noop twin or golden file. **Size:** S.

- ~~**During a session switch the timeline's calls and the endpoint chips are the session just left.**~~ **Done, 2026-10-22.** PR #135 merged as `4645c43`; `build`, `release-swap`, `repo` and `web` passed on the PR and the push run on `main`
  succeeded. KIN-94 and KIN-95 are Done on QA's pass over detached copies, one round, no blocking findings.

  A switch now clears the call side along with the signal side: `followSession` (moved to the new
  `web/js/16-session-load.js`) bumps `callLoad`, empties the whole-session copy and the open call, and every pane
  that reads them says `this session is being read…` from `beingRead`, the one place that wording lives. This
  **overturned** the `web-ui.md` sentence "The calls themselves are left to the switch's first page", which the
  maintainer approved on 2026-10-21; QA compared the replacement paragraph with the design note's block word by word
  (629 words each, no difference). With the new session's first page held, the nine `switch …` lines of the live
  probe read `yes` and QA's own Chrome case (two sessions on `/alpha/*` and `/beta/*`) was 15 of 15 clean at 1440,
  1000 and 400 px, against 0 of 15 on `117aeee`. Counts, base to head: static checks 110 to 112, live probe 251 to
  260, archive probe 48, and `measure-web-ui.js --only traffic` gained `traffic session switch window`.

  **What was learned.** The premise was a fact about one pane, recorded as if it held for every pane: the list is
  replaced by the first page, the timeline is not. The design note's second break for `switch path`, removing
  `|| sessionPending()` from `50-path.js`, stays green: during the window the path is already empty, so its empty
  branch says `being read` either way, and the clause matters only when a live call arrives in the window. The
  builder proved the wording by unwrapping `saysBeingRead` instead, and the clause stays unproven. The note's
  estimates were low: the replacement text is 42 lines for 21 (+21, not about +12), `live-traffic-cases.js` grew
  by 48 against 40, and the static count had moved from the note's 107 to 110 with #133. The tag browser keeps its
  old empty-state line as a fallback behind an inline guard, so the wording "no `<tag>` signals" still exists in
  `60-browser.js`.

  **Left open.** The delete and clear path (`afterSessionChange`) goes through the same `followSession` and has no
  probe of its own; the `|| sessionPending()` clause is unproven; Safari and Firefox (only headless Chrome and jsdom
  ran it); Chrome's `topbar`, `room`, `shell` and `first` sections were not run for this item.

  cost: not recorded — built in the 2026-10-22 coordinated run, one engineer per item, no QA before merge; QA after merge, 1 round

  *Ticket label:* `item:web-switch-clears-call-panes`. From QA on
  `item:web-now-strip-follows-session` (screenshots at base and head) and its hand-over.

  **This overturns a recorded decision.** `docs/decisions/web-ui.md`, "The signal side follows the
  session the moment it changes", says:

  > The calls themselves are left to the switch's first page.

  `followSession` says the same: "The calls are not cleared: the switch's first page replaces them."
  On the list that holds. On the timeline it does not: the switch's reads take as long as a
  whole-session read, 25 s on a large session, and until they land the timeline draws the old
  session's call rows and the endpoints menu counts its chips, under the new session's name. QA saw
  `GET api.qa.invalid /b` and the chip `b 1` from Beta while Alpha was selected. The premise was a
  fact about one pane, and it was recorded as if it held for every pane.

  **For whom.** A web UI user on a Mac who switches sessions, or deletes the one on screen. For the
  length of the switch, the timeline shows calls the selected session never made.

  **Acceptance criteria.**
  - With the new session's calls held back, the timeline draws none of the left session's calls,
    and the endpoints button and its popover show none of its chips.
  - The path, the waterfall and the tag browsers, which read what `followSession` clears but are not
    redrawn by it, show none of the left session's rows in that window.
  - The tab bar keeps its tabs through the window, and so does the timeline tab. A count on a tab
    either describes the selected session or is not shown.
  - A live probe line for each pane, holding the switch's reads, proven red with that pane's redraw
    removed. `now switch shows none` stays green.

  **Out of scope.** Making the switch faster, and KIN-42's whole-session read. The list, which the
  first page already replaces. Surfaces: web UI only. **Size:** S.

- ~~**A burst of live signals or markers redraws every pane once per message.**~~ **Done, 2026-10-22.** PR #144 merged as `e34542e`; `build`, `release-swap`, `repo` and `web` passed on the PR and the push run on `main` was in progress when QA looked. KIN-101 is Done on QA's pass over detached copies, one round, no blocking findings.

  `web/js/05-network.js`'s `drawLive({ path, signal })` records what the one armed redraw owes and `drawLiveNow` is the tick, so the signal and marker branches go through the existing 250 ms timer (`LIVE_DRAW_MS` unchanged). Chrome `measure-web-ui.js --only traffic --base 907b708`: `ok traffic signal burst: within bound (100 signals over 1190 ms: 5 renderTimeline, 5 renderWaterfall, 5 renderPath, 5 current, at most 6 each)` and `ok traffic marker burst: within bound (50 markers over 1075 ms: 5 renderTimeline, 5 renderPath)`, where base read 100 and 50. With base's `05-network.js` put back both Chrome readings fail (2 of 16) and four static lines fail (4 of 120: `live signal burst timeline`, `live signal burst path`, `live marker burst`, `live signal still`). Paused, or for another session, nothing moves: 0 redraws and 0 `/current` reads. Counts: static 116 to 120, live probe 263 and archive probe 48 unchanged.

  **What was learned.** The architect's call on the two payload reads was that they ride the same timer. Each payload is its own request, so the timer saves no request; what it removes is the redraw each answer triggered (the path redrew twice per screen, the tag browser once per signal) and `loadScreenPayloads` re-asking for payloads in flight. At each tick the pane waits for its payloads and is drawn once. QA checked the two views the committed case does not cover, in Chrome: 30 screen signals on the path view cost `renderPath` 2 (base 60), 30 payload reads and `/current` 2; 40 state signals in the state browser cost `renderBrowser` 2 (base 40). No payload was read twice. The reads happen only when the page is on the session the signals came from, that session is loaded and live is on, so a switch inside the 250 ms window never puts the new session's values in the strip while the other panes say they are being read. The item's sentence "the bound calls already meet" is true only for the timeline and the glance: the call branch still calls `renderWaterfall` and `renderPath` once per call.

  **Left open.** A lone signal or marker reaches the timeline 277 to 281 ms after it arrives (the 250 ms timer plus the render), strictly over the criterion's "within 250 ms", as calls already are; the reading allows 50 ms. The call branch's per-call waterfall and path redraws are unplaced. The marker branch keeps `renderMarkers` and `renderList` per marker and the signal branch `renderTabs` per signal, by design.

  cost: not recorded — built in the 2026-10-22 coordinated run, one engineer per item, no QA before merge; QA after merge, 1 round

  *Ticket label:*
  `item:web-live-signal-burst-bounded`. From the hand-over and QA on
  `item:web-live-calls-reach-every-pane`, and from `docs/STATUS.md` → Not verified.

  **For whom.** A web UI user on a Mac watching an app live. An app that snapshots its caches at
  launch, or a view-model that emits on every keystroke, sends signals in bursts, and the page does a
  full redraw for each one.

  **Why now.** It is the bug `item:web-live-calls-reach-every-pane` fixed for calls, in the branch
  beside it, and the fix is already built. The `signal` branch of `ws.onmessage`
  (`web/js/05-network.js`) calls `renderTimeline`, `renderWaterfall`, `renderPath` and
  `refreshCurrent` for every signal, so each one costs three whole-session redraws and a `/current`
  read. The `marker` branch calls `renderTimeline` and `renderPath` for every marker. Calls go
  through `drawLive` at one redraw per 250 ms.

  **Acceptance criteria.**
  - A burst of 100 signals over T ms costs at most ⌈T/250⌉+1 redraws of each of the timeline,
    the waterfall and the path, and at most that many `/current` reads. A burst of markers costs at
    most that many redraws of the timeline and the path. These are the bound calls already meet.
  - A single signal or marker is still drawn within 250 ms, and the timeline keeps its follow rule.
  - The existing `socket at end timeline` lines for markers and signals wait for the throttled draw
    and still pass. A new burst line is proven red with the per-message redraws put back.
  - When paused, or for another session, nothing moves, as for calls.

  **Out of scope.** Changing `LIVE_DRAW_MS`. The two payload reads this branch also makes, when the
  path view or the signal's own tag is on screen: the architect says whether they ride the same timer,
  and if not, the item says why. Surfaces: web UI only. No public API, no noop twin, no golden file.
  **Size:** S.

- ~~**One IPv6 server reached by both clients is two route rows.**~~ **Done, 2026-10-22.** PR #142 merged as `1ed50c1`; `build`, `release-swap`, `repo` and `web` passed on the PR (rebased eight times over a moving `main`, all four checks green on every head) and the push run on `main` was queued when QA looked. KIN-100 is Done on QA's pass over detached copies, one round, no blocking findings.

  `RouteTemplates.keyOf`, still the only place a key is built, now keys the host through `RouteTemplates.serverHost`: it lowercases the host and strips the brackets only from a host that starts with `[`, ends with `]` and holds a `:`. Bare `::1` is the canonical form because the row's `host:` term is a substring match and only the bare literal finds both stored forms. Display is unchanged: the page (`hostPortOf` in `statsOrigin`) and the Markdown export (`hostPort` in `serverOf`) bracket the host again on read. Capture, the filter grammar, the web UI, the MCP tool description (golden) and the public API did not change. QA seeded one session through `/ingest` (3 × `::1` and 3 × `[::1]` on port 8080, one `::1` on 9090, two bracketed non-IPv6 hosts): `GET …/stats` reads `host='::1' port=8080 /v6 calls=6 attempts=6` (base two rows of 3), MCP `route_stats` through a real `inspector mcp` the same, the Markdown Routes table one line with the 500 now one in six, and the Stats page in Chrome at 1440 and 400 four rows with exactly one naming `[::1]:8080`. The row's term `method:GET host:::1 path:*/v6` opens 7 calls (the six plus the port-9090 superset) in REST and in Chrome. Red by QA: `keyOf` on the stored host fails 5 of 61, one test per surface; dropping the `':' in lower` condition fails `RouteTemplatesTest` with `expected: <[example.invalid]> but was: <example.invalid>`. `:inspector-daemon:test` and `:repo-checks:test` with `--rerun`: 368 tests, 0 failed.

  **What was learned.** `web/` did not change: `95-stats.js` does no grouping of its own and counts origins through `hostPortOf`, which already treats `::1` and `[::1]` as one origin, so the item was daemon-only and needed no mirror case. The row's `host` field now reads bare `::1`, where the page and the Markdown still draw `[::1]:8080`; that split between key and display is deliberate. The item sits ahead of `item:findings-payment-safety` and `item:session-diff` because both reuse the key, and QA's findings run on `::1:8080` and `[::1]:8080` reads as one route for rule R1.

  **Left open.** No real dual-client capture against `[::1]` was run (the session holds archived rows in both stored forms, which is what `Ipv6CaptureTest` shows each client stores); zone ids (`fe80::1%en0`) are out of scope and a bracketed `[fe80::1%25en0]` from Ktor would be stripped without a test; the `host:` filter itself still matches the stored host, by its frozen grammar.

  cost: not recorded — built in the 2026-10-22 coordinated run, one engineer per item, no QA before merge; QA after merge, 1 round

  *Ticket label:*
  `item:route-key-one-ipv6-host`. From QA on `item:host-port-everywhere`, seen again by QA on
  `item:web-stats-route-breaks-whole`.

  **For whom.** A web UI user on a Mac reading the Stats page, a consuming team reading the
  Markdown export, and an agent reading the MCP route stats, for an app that calls one IPv6 server
  through both an OkHttp client and Ktor. OkHttp stores the host as `::1` and Ktor as `[::1]`
  (`docs/decisions/capture.md`), and `RouteTemplates.keyOf` keys on the stored host. So the same
  route on the same server is two identical rows reading `http://[::1]:8080`, each with half the
  calls and its own percentiles, while the session summary's `byHost` already counts it once.

  **Why now.** The route key is reused by `item:session-diff` and `item:findings-payment-safety`,
  which are not built. Fixed after them, it changes three surfaces' answers instead of one.

  **Acceptance criteria.**
  - A session with three OkHttp calls to `::1` port 8080 and three Ktor calls to `[::1]` port 8080 on
    one path draws one route row on the Stats page with six calls, at every width. The Markdown
    routes table and the MCP route stats agree.
  - That row's filter term opens all six calls. The `host:` term is a substring match, so the bare
    literal finds both stored forms; the grammar is unchanged.
  - Two ports, or a host that is not an IPv6 literal, key exactly as today.
  - A daemon test with both stored forms, proven red with the key's host left as stored.

  **Out of scope.** Changing what capture stores: `capture.md` decided brackets on read, so archives
  recorded before read the same. Making the `host:` filter term bracket-aware. Zone ids
  (`fe80::1%en0`). This does not overturn a recorded decision: `capture.md` notes that `host:`
  matches the stored host, and this item keeps that. Surfaces: daemon (Stats page, Markdown export,
  MCP read what it computes). No public API. **Size:** S.

- ~~**Housekeeping after the 2026-10-22 batch.**~~ **Done, 2026-10-22.** PR #143 merged as `c608ec8`, in one PR with `item:guard-expanded-unfolded-has-failures`; `build`, `release-swap`, `repo` and `web` passed on the PR and the push run on `main` succeeded. KIN-97 is Done on QA's pass over detached copies, one round, no blocking findings.

  `docs/decisions/web-ui.md`'s "A scan that finds nothing to check fails, and says so" now names PR #133's Chrome readings, each with its file: `shell <w> rail` (`lib/shell-cases.js`), `room 400 rows`' repeat marks, `room <w> toolbar fits` and `one row`, and `room 1000 tab never enters closed` (`lib/list-room-cases.js`), `traffic reaches` (`lib/live-traffic-cases.js`) and the top bar's `count` wording (`lib/topbar-cases.js`). Each is marked shown red, except the socket half of `traffic reaches`, which rests on review. The Stats route-break paragraph no longer says "29 breaks": it says how many breaks main makes depends on the fonts Chrome finds (29 on one machine, 30 on another) and names the break that reproduces, `http://127.0.0.1:808|0`. QA: `grep -c "29 breaks"` gives 0, and the added sentence matches KIN-87's fix-round notes and QA's own KIN-87 round 2.

  **What was learned.** "Shown red" in a decision file restates a record kept in a ticket; nobody re-ran those mutants for this item, and QA relied on having re-run the rail and repeat-mark cases itself in KIN-87's second round. A count of breaks is a fact about one machine's fonts, so only the break that reproduces belongs in the text. Docs only, so there was no guard to prove.

  **Left open.** Nothing.

  cost: not recorded — built in the 2026-10-22 coordinated run, one engineer per item, no QA before merge; QA after merge, 1 round

  *Ticket label:* `item:docs-housekeeping-2026-10-22`.
  Two lines in one decision file, neither worth an item of its own.

  **For whom.** The agents and engineers who read `docs/decisions/web-ui.md` before changing a probe.

  **Acceptance criteria.**
  - "A scan that finds nothing to check fails, and says so" names the Chrome readings PR #133 put
    under the rule: `shell <w> rail`, `room 400 rows`, `toolbar fits` and `one row`,
    `tab never enters closed`, `traffic reaches` and the top bar's count wording. Each is
    marked shown red or resting on review, as KIN-87's notes record.
  - The Stats route-break paragraph no longer gives main's break count as a fact ("29 breaks"; QA
    saw 30 on another machine). It names the break that reproduces, `http://127.0.0.1:808|0`, and
    says the count depends on fonts.

  **Out of scope.** Any other rewording of `web-ui.md`. Surfaces: docs only. **Size:** XS.

- ~~**The README and the landing page say things that are not true.**~~ **Done, 2026-10-22.** PR #159 merged as `82887c1` (with row 13d); `build`, `release-swap`, `repo` and `web` passed on the PR and the push run on `main` succeeded. KIN-125 is Done on QA's pass, one round, no blocking findings. The entry below is moved verbatim.

  **What was built and learned.** The README's count became 886 at the last full count with STATUS's caveat that it is old; "used daily against a real app, on an Android emulator and the iOS simulator" was removed because no record supports "daily" or a real app on the simulator, and replaced by the one thing the record does show (the first real-app session found four defects, all fixed). The phone sentences now say the overlay ran once on an Android phone on 2026-08-17 in a build that predates the Android sample, that the sample has not run on a physical phone, and that one iPhone ran the iOS sample over USB with nobody looking at the overlay. The sponsor box says it pays for maintenance and the roadmap, and links it; `grep -i proxy site/index.html` finds nothing. The Android sentences hold whichever answer the maintainer gives to the open question below, so the item closed without waiting for it; **the question itself stays open** (whose build ran on that phone on 2026-08-17). QA checked each changed claim against STATUS, `git log` and Maven Central's own metadata (1.0.1 to 1.3.0), and the landing page in Chrome at 1440 and 400.

  **Left open.** At 400 px the landing page is 605 px wide (the architecture SVG) and a feature card's last bullet is cut; the base commit gives the identical overflow, so it predates this item. Commit identity and the name scan were not re-run by QA for this copy-only PR.

  cost: not recorded — built in the 2026-10-22 coordinated run, one engineer session, no QA before merge; QA after merge, 1 round

  *The entry below was found on 2026-10-22 outside any item, while the maintainer's proxy reviewed
  what the repository says about itself in public. Placed by the product owner the same day and
  checked against `docs/STATUS.md` and `ROADMAP-DONE.md` at `e34542e`. **Ordered 2026-10-22 by the
  maintainer's proxy**, first in lane S: see "Order from here".*

  - **The README and the landing page say things that are not true.** *Ticket label:*
  `item:public-claims-true`.

  **For whom.** A developer on a Mac deciding whether to try Inspector, who reads the README or
  `site/index.html` before the code, and a consuming team that quotes either to justify adopting it.
  Each of them takes the page's word for what has been tested and what is coming. A claim the record
  contradicts costs more than the gap it hides.

  **Why now.** Findings and detection are next, and they will draw readers to these two pages. The
  three claims below are each false today, each is a sentence or two, and none needs code.

  1. **The README's "Honest gaps" paragraph** says the overlay "has been run on one Android device,
     the iOS simulator and desktop, never on iOS hardware". `docs/STATUS.md` → Verified records a
     physical iPhone streaming to the daemon over USB on 2026-09-24, with the overlay running the
     whole time. `docs/STATUS.md` → Not verified says nobody *looked* at the overlay there, and that
     no Android build has run on a physical phone. So "never on iOS hardware" is false, and the
     Android half needs the record settled first (see the open question below).
  2. **The README's test count** reads `779 tests`. `docs/STATUS.md` gives the last full count as
     886, 0 failures, and says it is old and that no full run has been counted since.
  3. **The landing page's sponsor box** says sponsoring "pays for what's next: proxy capture for
     WebViews and native iOS networking". Proxy capture (4c) is not started and starts only if the
     maintainer explicitly chooses it (§3's carried-over table, and "What this document does not
     overturn"). A page that sells it as next promises something nobody has chosen.

  **Acceptance criteria.**
  - The README's phone sentences say what `docs/STATUS.md` says, no more: the overlay ran on one
    iPhone over USB and nobody looked at it there, and the Android claim says whatever the record
    settles (below). The rest of the paragraph (the signals work never touched by a thumb, the Auth0
    adapter, nothing non-Ktor on iOS) is re-read against `docs/STATUS.md` and kept only where it
    still holds.
  - The README's count is `docs/STATUS.md`'s last full count, re-read on the day of the change, with
    STATUS's own caveat that it is old. If no full run is counted by then, the README says "886 at the
    last full count" or links to `docs/STATUS.md`. It never states a total made by adding per-module
    counts.
  - The sponsor box says sponsoring pays for maintenance and what is on the roadmap, and links the
    roadmap. It names no unstarted item, and no phrase on the page reads as a promise of proxy capture.
  - Every other place the README or `site/index.html` claims hardware, a count or a future feature is
    read against `docs/STATUS.md` and this roadmap, and the change lists each one it checked. The
    landing page's Android row ("no Android build has run on a physical phone yet") is one of them.

  **Open question for the maintainer, which the first criterion waits on.** The record disagrees
  with itself about Android. Commit `ef9058e`, "Fix four overlay defects found on a real phone",
  says "First run on an Android device", and `docs/STATUS.md` → Not verified opens with "The overlay
  has now been seen on a phone once". The same section ends "No Android build has run on a physical
  phone", and the proxy's note says no Android phone has run the overlay. Both can be true if that
  first outing was a consuming app's build and not the sample's. The maintainer, who held that phone,
  says which. The README then says "once, on one Android phone, before 0.7.0" or "never on an Android
  phone", and `docs/STATUS.md` is corrected by the delivery tracker to match.

  **Out of scope.** The README's hero and any rewrite of the pitch, which wait until findings ship in
  a release: a claim about something unbuilt is a false fact. The sponsor link's target, which is
  still the carried-over row in §3. `docs/INTEGRATION.md`, whose version line this does not touch.
  Surfaces: docs and the landing page only. No public API. **Size:** XS.

---

## 4. ~~Maven Central~~ — done, 1.0.1 published 2026-09-21

**1.0.1 is on Central, published 2026-09-21.** It resolves anonymously, so the token that was
the single thing every new consumer tripped over is no longer needed for current versions.
GitHub Packages keeps running alongside it; 1.0.0 and earlier remain Packages-only, because they
predate the signing and metadata Central requires and a coordinate cannot be backfilled.

Split into one irreversible step and a pile of mechanical ones on purpose. A coordinate published
to Central can never be replaced or deleted, so the first push is the point of no return; a
coordinate published to GitHub Packages can be deleted, which is why the group rename went out as
1.0.0 **before** any of this rather than bundled with it.

| Step | State |
|---|---|
| Group changed to `io.github.shafichariri` | **Done, 1.0.0.** `dev.*` is verified by DNS on the matching domain and `inspector.dev` belongs to somebody else, so `dev.inspector` was never claimable. Artifact ids and the Kotlin package were left alone, so it cost consumers three dependency lines and no source edit |
| POM `name`, `description`, `url`, licence, `developers`, `scm` | **Done, 1.0.0.** The `<name>` had published a literal placeholder since 0.3.0 |
| Sources jar | **Done.** Kotlin MPP emits it already |
| Javadoc jar | **Done.** One stub file rather than empty, because empty is also what a silently failed Dokka run produces |
| GPG signing | **Done, wired to be inert without a key.** No `Sign` task exists when none is configured, so `build`, `publishToMavenLocal` and the GitHub Packages release job keep working on a machine that has never held a key |
| A published signing key | **Done, 2026-09-20.** `A5D94B7324C7B709`, RSA 4096, expires 2028-09-19, served by `keyserver.ubuntu.com`. Held as the `SIGNING_KEY`/`SIGNING_PASSWORD` repository secrets, and passed to the GitHub Packages publish although that repository does not ask for a signature — so the key is exercised on the low-stakes target before Central depends on it |
| Namespace verification on the Portal | **Done, 2026-09-21.** `io.github.<user>` is verified by GitHub account ownership, and signing in to the Portal *with* GitHub verified it outright — no repository to create. Note the Portal's own warning: namespaces are tied to the sign-in method, so a user token generated under a different sign-in with the same email belongs to a different account and does not own the namespace |
| Publishing to the Central Portal | **Built, never run.** Not a repository URL: the Portal replaced OSSRH's protocol and has no official Gradle plugin, so it takes a zipped Maven layout POSTed to a Publisher API. `centralBundle` produces the zip and `publishToCentralPortal` uploads it, defaulting to `USER_MANAGED` so the Portal stages and waits rather than releasing. Run from CI by dispatching `central.yml`, on macOS, because a bundle built anywhere else is missing the iOS klibs that only a Mac can produce. **First run 2026-09-21: uploaded, VALIDATED and published on the first attempt** |
| Keep publishing to GitHub Packages as well | **Decided: both.** Dropping it would strand anyone on 1.0.0 or earlier. They are separate `maven {}` entries and separate tasks, so a Central failure cannot take the Packages release with it |

**The namespace is now permanent.** Until 2026-09-21 it was not: nothing had reached the registry
that refuses deletions, and `io.github.shafichariri` could have been changed as an ordinary
breaking release. Publishing 1.0.1 spent that. `io.github.shafichariri:*:1.0.1` cannot be
replaced, deleted or re-pointed, and every future release lives under the same group.

### ~~What is left~~ — closed 2026-09-21

**A tag now stages on Central automatically**, and stops there. `release.yml` gained a third job
alongside `daemon` and `library`; it pins `USER_MANAGED`, so the Portal validates the bundle and
parks it, and a person still presses Publish.

That was the gap: publishing was a manual dispatch that was easy to forget, while the docs already
pointed consumers at Central by default — so a release could quietly never arrive for anyone
following them. Staging automatically removes the way to forget without making the irreversible
act reachable from a tag push, which is the property worth keeping.

`central.yml` stays for dispatching a tag by hand — re-staging after a dropped deployment, or a
tag cut before any of this existed.

---

## 5. Next: the structure and architecture pass — planned 2026-10-18

### Order from here — the rows finished

| Item | Where | Ticket label | Size |
|---|---|---|---|
| ~~`ExportMarkdown.kt` prints the query twice~~ **Done, 2026-10-18** | §3 → Bugs found | `item:export-md-query-once` | S |
| ~~The signals sort and clamp exist twice~~ **Done, 2026-10-19, with one deferred bug, KIN-42** (KIN-24 to KIN-41, KIN-26; four verify rounds; KIN-42 open, deferred: false empty state for ~25 s on a ~160k-call session) | §3 → Guards | `item:signals-query-one-rule` | S |
| ~~The web traffic list stops at 1000 calls~~ **Done, 2026-10-19** (KIN-43 to KIN-50, QA at `68315b4`; KIN-42 stays deferred and owns whether the whole-session read is capped) *(added 2026-10-18)* | §3 → Bugs found | `item:web-transactions-paged` | ~~S~~ M *(re-sized 2026-10-19; KIN-43 to KIN-46, approved; KIN-47 to KIN-50 from the verify panel)* |
| ~~One session's "now" values can land under another~~ **Done, 2026-10-19** (KIN-51, `eaaadce`; a failed `/current` read now always empties the strip) *(added 2026-10-19)* | §3 → Bugs found | `item:web-current-session-guard` | S |
| ~~Live rows ignore the filter until the next reload~~ **Done, 2026-10-19** (KIN-52 to KIN-55; a throttled re-read under a filter, no daemon or wire change; size S) *(added 2026-10-18)* | §3 → Bugs found | `item:web-live-rows-filtered` | S |
| ~~The traffic list does not say when it holds only part of what it counts~~ **Done, 2026-10-21** (KIN-56, KIN-72, KIN-73; PRs #104 and #110, merges `fb8e54e` and `ff4f9de`; two rounds of QA) | §3 → Bugs found | `item:web-list-says-what-it-holds` | S, to be confirmed |
| ~~The now strip and the signal panes can show what is no longer true~~ **Done, 2026-10-21** (KIN-57, PR #107, merge `6804c94`) | §3 → Bugs found | `item:web-now-strip-follows-session` | S, to be confirmed |
| ~~Split `McpTest.kt`~~ **Done, 2026-10-21** (KIN-59, PR #102, merge `49735be`) | §5 | `item:structure-split-mcp-test` | S |
| ~~Row labels drop a non-default port~~ **Done, 2026-10-21** (KIN-60, PR #105, merge `1da0d75`) | §3 → Bugs found | `item:row-label-port` | S |
| ~~Phase H — split the two large overlay files~~ **Done, 2026-10-21** (KIN-61, PR #103, merge `4dc21d2`) | §5 | `item:structure-phase-h` | S |
| ~~Phase I — `noop-ui` golden file~~ **Done, 2026-10-21** (KIN-62, PR #106, merge `1479c30`) | §5 | `item:structure-phase-i` | M |
| ~~1a — Web UI foundation: the shell~~ **Done, 2026-10-21** (KIN-63 to KIN-71; PRs #108, #112, #114, merges `e9f7131`, `a7e3946`, `cc9dbce`; two rounds of QA) *(was row 9; the later rows keep their numbers)* | §6 | `item:web-foundation-shell` | L |
| ~~1b — Web UI foundation: room for the list~~ **Done, 2026-10-21** (KIN-78 to KIN-86; PR #119, merge `4fe71dc`; one round of QA) *(was row 11; the later rows keep their numbers)* | §6 | `item:web-foundation-list-room` | M |
| ~~2 — Endpoint latency stats~~ **Done, 2026-10-21** (KIN-74 to KIN-77; PRs #115 and #121, merges `0a5a1c9` and `d256174`; one round of QA for each PR) *(was row 12)* | §6 | `item:endpoint-latency-stats` | M |
| ~~A live call reaches the list but not the timeline, the glance or the picker~~ **Done, 2026-10-21** (KIN-58; PRs #113 and #117, merges `952b6c6` and `433f30d`; two rounds of QA, round 1 failed the picker after a resume) *(added 2026-10-19; placed 2026-10-21 by the maintainer: after 1a, before 1b; was row 10, the later rows keep their numbers)* | §3 → Bugs found | `item:web-live-calls-reach-every-pane` | S |
| ~~A check that finds nothing to check passes~~ **Done, 2026-10-22** (KIN-87; PRs #128 and #133, merges `b1bbcac` and `5b21593`; two rounds of QA, round 1 failed criterion 2: `shell <w> rail` passed over no rail item and the inventory missed four Chrome case files, fixed in #133) *(was row 14; the later rows keep their numbers)* | §3 → Guards | `item:guard-probe-finds-nothing-fails` | not sized |
| ~~QA's live-traffic Chrome driver, committed~~ **Done, 2026-10-22** (KIN-88, PR #127, merge `9fb0470`; one round of QA) *(was row 15; the later rows keep their numbers)* | §3 → Guards | `item:guard-live-traffic-driver` | not sized |
| ~~The core and stream twin pairs get the ui pair's guards~~ **Done, 2026-10-22** (KIN-90, PR #125, merge `623c571`; one round of QA) *(was row 17; the later rows keep their numbers)* | §3 → Guards | `item:guard-twin-pairs-alike` | S |
| ~~"A probe leaves no process behind", tested~~ **Done, 2026-10-22** (KIN-91, PR #126, merge `74f5ca0`; one round of QA) *(was row 18; the later rows keep their numbers)* | §3 → Guards | `item:guard-mcp-probe-cleanup` | not sized |
| ~~A server is its host and a non-default port, everywhere~~ **Done, 2026-10-22** (KIN-92, PR #129, merge `66c1bad`; one round of QA) *(was row 19; the later rows keep their numbers)* | §3 → Bugs found | `item:host-port-everywhere` | S, or M |
| ~~At 400px the Stats page keeps a route's host whole~~ **Done, 2026-10-22** (KIN-93, PR #132, merge `7b37d7a`; one round of QA) *(was row 22; the later rows keep their numbers)* | §3 → Bugs found | `item:web-stats-route-breaks-whole` | S |
| ~~A silent MCP child hangs the probe, and is left running~~ **Done, 2026-10-22** (KIN-96, PR #138, merge `ffc9278`; one round of QA) *(was row 13a; the later rows keep their numbers)* | §3 → Bugs found | `item:mcp-probe-deadline-holds` | S |
| ~~Housekeeping after the 2026-10-20/21 run~~ **Done, 2026-10-22** (KIN-89; PRs #124, #130 and #140, merges `6c1cb32`, `e071594` and `95b4de4`; QA passed part 1 and then the whole item) *(was row 16; the later rows keep their numbers)* | §3 → Bugs found | `item:docs-housekeeping-2026-10-21` | S |
| ~~A measure run hangs when Chrome dies mid-case~~ **Done, 2026-10-22** (KIN-99, PR #141, merge `85e9552`; one round of QA) *(was row 16c; the later rows keep their numbers)* | §3 → Guards | `item:guard-measure-chrome-exit-fails` | S |
| ~~A session switch clears the timeline's calls and the endpoint chips~~ **Done, 2026-10-22** (KIN-94, KIN-95, PR #135, merge `4645c43`; one round of QA; overturned a `web-ui.md` sentence, approved by the maintainer 2026-10-21) *(was row 20; the later rows keep their numbers)* | §3 → Bugs found | `item:web-switch-clears-call-panes` | S |
| ~~KIN-42: draw the first page at once, read the whole-session copy in the background~~ **Done, 2026-10-22** (PR #136, merge `1888380`; one round of QA; undeferred by the maintainer 2026-10-21) *(was row 21; the later rows keep their numbers)* | §3 → Guards | `item:signals-query-one-rule` | one ticket |
| ~~Housekeeping after the 2026-10-22 batch~~ **Done, 2026-10-22** (KIN-97, PR #143, merge `c608ec8`; one round of QA, shared a PR with the next row) *(was row 16a; the later rows keep their numbers)* | §3 → Bugs found | `item:docs-housekeeping-2026-10-22` | XS |
| ~~`expanded unfolded` judges a line with no failure on it~~ **Done, 2026-10-22** (KIN-98, PR #143, merge `c608ec8`; one round of QA) *(was row 16b; the later rows keep their numbers)* | §3 → Guards | `item:guard-expanded-unfolded-has-failures` | XS |
| ~~Interrupting a measure run leaves its daemons running~~ **Done, 2026-10-22** (KIN-109, PR #148, merge `1796b96`; one round of QA) *(was row 16d; the later rows keep their numbers)* | §3 → Guards | `item:guard-measure-interrupt-cleans-up` | XS |
| ~~One IPv6 server reached by both clients is two route rows~~ **Done, 2026-10-22** (KIN-100, PR #142, merge `1ed50c1`; one round of QA) *(was row 21a; the later rows keep their numbers)* | §3 → Bugs found | `item:route-key-one-ipv6-host` | S |
| ~~A burst of live signals redraws once, not per message~~ **Done, 2026-10-22** (KIN-101, PR #144, merge `e34542e`; one round of QA) *(was row 23; the later rows keep their numbers)* | §3 → Bugs found | `item:web-live-signal-burst-bounded` | S |
| ~~The overview band's reserved height is a promise nothing holds~~ **Done, 2026-10-22** (KIN-110, PR #149, merge `6aff923`; one round of QA) *(was row 16e; the later rows keep their numbers)* | §3 → Guards | `item:guard-overview-band-reserved` | XS |
| ~~The README and the landing page say only what is true~~ **Done, 2026-10-22** (KIN-125, PR #159, merge `82887c1`; one round of QA; the maintainer's Android question stays open) *(was row 13b)* | §3 → Bugs found | `item:public-claims-true` | XS |
| ~~The release page and `ACCESS.md` name the same place to get the library~~ **Done, 2026-10-22** (KIN-126, PR #159, merge `82887c1`; one round of QA) *(was row 13d)* | §6 → Release 1.4.0 | `item:release-notes-boilerplate` | XS |
| ~~A sample demo flow that both rule families catch~~ **Done, 2026-10-22** (KIN-128, PR #162, merge `b34ea8b`; one round of QA) *(was row 30a)* | §6 | `item:sample-demo-flow` | S |

### Phase E — one `timeline()`, so MCP agrees with the model

**Audit finding 3. A behaviour-changing bug fix, approved as one (decision 6).**

**For whom.** An agent over MCP. It asks `timeline` what happened around a marker and gets the
marker placed *after* the call that was made at the same millisecond. Both UIs put it before. The
recorded rule in `docs/decisions/shared-rules.md` ("Markers interleave through `timeline()`") says before, because a
marker is dropped just before the thing it marks. A tie is exactly what a marker dropped right
before a request looks like on a millisecond clock. So the wrong answer shows up in the very case
the rule exists for. Signal ties ignore `seq` in the same way, which is the `onClick { tap;
navigate() }` case that `visits()` was fixed for.

**Acceptance criteria.**

- `SessionRepository.timeline()` no longer orders anything itself. Both MCP call sites, the
  `timeline` tool and the transaction tool's before/after context, get their order from the model's
  `timeline()`.
- A daemon test builds a session with a marker and a call at the same `mono`, and two signals at
  the same `mono` with different `seq`. It asserts that the MCP `timeline` output matches the
  model's `timeline()` for that session, entry by entry. It fails with the old sort put back.
- The recorded clamp is either kept or deliberately changed, and the item says which. Today
  `timeline()` clamps to 2,000 entries, and `docs/decisions/exports.md` ("All three exports read the raw readers")
  already records that clamp as a reason the exports do not use it.
- A note in `docs/DAEMON.md` or the MCP tool description says the tie order changed and in which
  release, so an agent comparing an old transcript with a new one has an explanation.

**Out of scope.** Changing the tool's arguments, its default limit or its oldest-first order. An MCP
`path` tool, which §3 already defers.

**Status, 2026-10-18: done.** CI on PR #82 (stacked on phase A) went green on `build`,
`release-swap` and `web`, which covers the full `./gradlew build`, iOS and Android. The 2,000 clamp is **kept**: `timeline()` is now
`timelineWindow()` plus the clamp, and `explain` reads the unclamped window and splits it by
position. Signal ties ship as "MCP equals the model", which still ignores `seq`; that is recorded
under §3 -> Bugs found, not fixed, rather than fixed here.

Evidence, from two independent QA passes run locally: `:inspector-daemon:test` 224 and
`:inspector-model:jvmTest` 159, 0 failures. Five breaks each went red: the old sort (timeline test),
the daemon's `thenBy seq` (timeline test), the old `mono` split (explain test), a clamped `explain`
(over-2000 test), and `splitAround` returning empty instead of null (refusal test). The built MCP
binary was driven over JSON-RPC against a hand-built tie archive and its order matched the model.
`DAEMON.md` §8 "1.3.0 and earlier" was checked against the `v1.3.0` tag.

Not shown: a CI run; a full `./gradlew build`; the iOS and Android targets, which were not run
because only daemon code changed. Phase E is done when CI is green on a pushed branch.

### Phase A — web checks and shared mirror cases in CI

**Status, 2026-10-18: done.** CI run 39082730859 on PR #81 went green on all three jobs: the new
`web` job on `ubuntu-latest` (so KMP and AGP configure there), and `build` and `release-swap` on macOS,
which ran the full `./gradlew build`, the Android host tests and `-Pinspector=off`.

*Evidenced locally (three independent QA passes, not the engineer):* the static check passes 20/20;
`:inspector-model:jvmTest` ran 159 tests and `:inspector-daemon:test` 219, 0 failures each; the live
probe on port 8231 reports 164 passed, 0 unclassified, exit 0; the archive probe 21 passed. Every guard
category was re-broken and went red: network, `[hidden]`, 11px, JS mirrors, Kotlin mirrors, `inputs.dir`,
a missing anchor, a reverted drift, the archive data-versus-code check, the item-2 checks, `esc restores
glance` (all three breaks) and the hidden scrim. The four drifts and the `?` case are fixed, with
`app.js` the only product file changed. `writeProbeFixture` is idempotent.

*Still not shown:* the Gradle cache carrying over across checkout paths. The "897 tests" arithmetic
is not a measurement, and `docs/STATUS.md` no longer claims it.

**Audit findings 1 and 2.**

**For whom.** A web UI user on a Mac, and everyone who edits `web/`. Every recorded web UI rule
(one `fetch`, the `[hidden]` trap, nothing under 11px, the mirrors) is held by a script that CI
never runs. Two of those rules were each broken for two releases before a person found them. And
§6 rewrites most of what those scripts anchor on.

**Acceptance criteria.**

- A zero-dependency `scripts/check-web-static.js` runs in CI. It covers the single-`fetch` count,
  the `[hidden]` override audit, the 11px audit, and the mirror cases. It needs no daemon, no
  network and no `npm install`.
- **Shared mirror cases for all seven mirrors.** Each case lives in one place that both sides read:
  the Kotlin test in `:inspector-model` and the JS check against the function lifted from the real
  `app.js`. The five that have no check today get one: `pathScope`, `endpointShortcuts`,
  `timelineRuns`, duplicates and `HOP_BY_HOP` (whose authority is `Replay.kt`, not the model). A
  case added on one side is run on the other.
- `render-web-ui.js` and `render-web-ui-archive.js` run in CI against a real daemon holding a
  known session. The architect chooses how the daemon gets that session. The run states how many
  checks it made, so a probe that quietly skipped half of them shows up as a smaller number.
- Proven to fail when broken, one break per check: a second `fetch`, a pane that sets `display`
  with no `[hidden]` override, a 10px rule, one changed threshold in each mirror, and a network call
  that bypasses `source` in an export.
- `jsdom` stays a CI and developer dependency of the probes. It never appears in `web/`.

**Out of scope.** Rewriting the probes for §6's layout, which belongs to 1a and 1b. Splitting
`render-web-ui.js`.

**Ruled 2026-10-18 (product owner, under the maintainer's delegation): the four drifts the mirror
cases found are fixed inside this item, as bug fixes.** Designing phase A, the architect found that
`app.js` already disagrees with `:inspector-model` in four places, and each new mirror check would
go red on day one. That runs into the rule above that only phase E changes behaviour, so it was
escalated rather than fixed quietly:

1. **Repeat count.** `computeDuplicates` sets `count: run.length`, so a retry pair plus one more
   call reads "sent 3x by separate calls". The recorded rule: "A repeat says `callCount`, never
   `ids.size`."
2. **Run id.** `runIdOf` takes the id from `run.entries[0]`, which in newest-first order is the
   latest member. The id re-keys during live tail, which is what the recorded `TimelineRun.earliest`
   rule exists to prevent.
3. **Marker at a tie.** `renderTimeline` puts a marker after a call at the same millisecond. The
   recorded `timeline()` rule puts it before (`<=`). This is the bug phase E fixes in MCP, which
   decision 6 already approved as a bug fix.
4. **Endpoint-chip tie order.** `app.js` breaks the final tie with `localeCompare`; the model
   compares char codes. The model is the stated authority, and the overlay already uses its order.

Also minor: `urlOf` drops a `?` with an empty query string where Kotlin keeps it. This only affects
the duplicate key.

**Why fix them now instead of pinning them.** None of these is a new behaviour decision. Each
one is the web breaking a rule that is already written down and that the overlay already follows,
so fixing it is the same kind of change as decision 6. A mirror case exists to say "the web
follows the model". A `knownDrift` field turns that around: a passing case would mean a failure.
It would be new runner machinery built only to be removed again, and the bugs would stay in front
of users longer. **Pinning with `knownDrift` is not worth doing.** The rule that phases change no
behaviour exists to stop scope creep inside moves and splits. It was not meant to protect behaviour
that the record already calls wrong.

**Conditions.**

- **Only `app.js` changes.** The Kotlin side is the authority for all five. If building a fix shows
  the model is the one that is wrong, stop and bring it back here. Do not change both sides inside
  phase A. That includes the chip order: char-code order puts `Balance` before `accounts`, which
  reads a little oddly, but it only applies after count and recency tie, and it is what the
  overlay already shows. Changing it would be a change to the model rule, as its own item.
- **One commit per drift**, each naming the rule it restores. That way any one of them can be
  reverted alone.
- **The old code is the proof that the check fails.** Each mirror case is shown failing against the
  `app.js` from before its fix, then passing after. This counts as that check's proven-to-fail run.
  The one-changed-threshold break listed above is still required as well.
- `render-web-ui.js` probes that read these values (the repeat label, the chip order) are updated
  to assert the model's answer. They must not be loosened to accept either answer.

**Release notes.** Moved to `UNRELEASED.md`, word for word.

### Phase F — `app.js` and CSS split behind the manifest

*Ticket label:* `item:structure-phase-f`.

**Audit finding 4. Must land before 1a**, because 1a adds a rail, hash routes and four pages. Adding
them to one 5,398-line function would be the "building it twice" that §6's ordering argues against.

**This touches recorded decisions** (decision 1). Several are worded around one file:

- "**The browser's `fetch` is called exactly once in `app.js`**." After this item that means once
  across every file in `web/scripts.txt`, and the count has to read the manifest. If it does not,
  a second `fetch` in a new file goes uncounted. The same goes for "`fetch` is still called once in
  `app.js`" in §6's foundation item, which should be read that way after this lands.
- The mirror checks and `parseJsonTree` checks "lift … out of the real `app.js` by their markers".
  After the split they lift from whichever file now holds each function.
- The HTML export "inlines the served `index.html`, `style.css` and `app.js`". After the split it
  inlines every file in manifest order. The archive probe proves that no file was left out.
- `source` stays the only network seam. A second one is on the "do not" list below.

**For whom.** A web UI user on a Mac sees nothing change. That is the acceptance bar. The people it
is for are whoever edits the web UI next, and the reviewer of 1a's diff.

**Acceptance criteria.**

- `web/scripts.txt` lists ordered classic scripts, `web/js/NN-*.js`. `index.html`, the export
  inliner and both probes all read the manifest, and none of them keeps its own list.
- The CSS is split into `tokens.css` plus per-area files. `PaletteParityTest` still reads the tokens
  and fails when one drifts, proven again after the move.
- There is no build step and no new dependency. The page works served by the daemon and from an
  exported file opened under `file:`.
- Both probes report the same check count before and after. The static checks from phase A pass
  unchanged.
- A manifest entry pointing at a missing file fails the build, and so does a `web/js/` file the
  manifest does not list.

**Out of scope.** Any visual or behavioural change. Splitting `render-web-ui.js`. A module system or
a bundler.

**Design agreed, 2026-10-18.** A design council agreed it and the maintainer approved it. It lands as
five commits, F1 to F5. The maintainer accepted the architect's call that the live probe keeps reading
the working tree; `RestApiTest` holds the served half. F1 also fixes a measured defect.
`PaletteParityTest` reported UP-TO-DATE on a CSS-only change, so palette drift passed an ordinary
build.

**Status, 2026-10-18: done.** PR #88 merged to `main` as `1913cf7`. CI run 39146369617 on that push
passed `build`, `release-swap` and `web`. **`repo` was red on that push, and only on the commit-identity
check**: GitHub's merge dialog authored the merge commit with the maintainer's work address, which
nothing in the branch set. PR #89 (merged) has since made the check accept the maintainer's personal
address; the setting that stops the dialog choosing the work address is the maintainer's to change, and is
not changed. PRs #90 and #91 (team tooling and names) also merged. Tickets KIN-10 to KIN-22 are Done on QA
evidence from fresh verifier runs, the last on the wording pass `b733aa4`. What landed, against base
`f574bda`:

- **F1 `8b20dcc`, manifest plumbing.** `web/scripts.txt` and `web/styles.txt`, with `index.html` holding
  two slot comments. `service/WebPage.kt` is the one Kotlin reader of the manifests. The export inliner,
  `web-sources.js` and `render-web-ui.js` read them too. F1 also fixed the pre-existing
  `PaletteParityTest` defect: Gradle reported it UP-TO-DATE on a CSS-only change, so palette drift passed
  an ordinary build.
- **F2 `266f7bd`, F3 `4a23f77`.** The IIFE is gone, static checks 1-3 were added, and the script became
  twenty ordered classic files in `web/js/`.
- **F4 `4eace25`.** The stylesheet became seventeen contiguous files in `web/css/`, and static check 4
  was added.
- **F5 `90618e2`.** Decisions, guides, comments and README for the manifest-split web UI.
- **Review fixes** `e046990` to `e065f11`, then `526dab7`, `f05603b` and `b733aa4`. These are KIN-16 to
  KIN-22: static check 5 for the cross-file `@media` order, a denylist that also refuses JavaScript
  globals, the blind spots of checks 2, 3 and 5 stated in `web-ui.md` (a column-0 line heuristic, not a
  parser), `PaletteParityTest` reading `web/css` directly, and four small nits. KIN-16 AC#1 was met as
  narrowed (same selector and overlapping property), which the maintainer accepted on 2026-10-18.

**Route design.** One fixed `GET` per listed file, not a wildcard, so a file the manifest does not list
cannot be served and an encoded traversal gives 404. It keeps KIN-9's 405: `PUT` on a served file is
refused with 405 rather than turned into a 404. `/app.js` and `/style.css` are gone.

**Evidence against base `f574bda`.** Live probe 164 passed, 1 n/a, 42 notes, per-label identical; archive
probe 21, the only label change being `app.js inlined` to `every file inlined`. The first 20 static lines
are byte-identical, and head reports 24 checks. The route matrix differs only on `/app.js`, `/style.css`,
`/js/*` and `/css/*`. Headless Chrome 131 over 16 configurations, served and exported under `file:`, gave
32/32 identical computed-style, text and PNG hashes and 0 page errors. QA re-ran the move proofs: the JS
block multiset with the `99-init` tail, and the CSS concatenation of 61736 bytes. Re-confirmed at
`e065f11`: daemon 259, ui 134, repo-checks 35, model 159. After `b733aa4` the static output was
byte-identical across `76b6c99`, `f05603b` and `b733aa4`, and repo-checks were 35/0. No `package.json` and
no dependency were added.

**Process note.** The agent-team roster roles gained `SendMessage` and the task tools (`a5a8661`).
Messaging between teammates then worked in the second panel (qa2 and arch2), while the task tools still
did not reach them.

**Not verified.** Safari and Firefox (Chrome only). Android host tests (no SDK on
this machine). The no-client-names check (term list kept out of the repository), so that DoD item is
unticked on all of KIN-10 to KIN-22. How the page looks to a human: the hash comparison proves "identical
to before", not "looks right".

**Six web UI layout defects UX found at base `f574bda`.** Phase F neither caused nor fixed them.
*Placed 2026-10-18:* they are acceptance criteria under §6 → Web UI foundation now, (e) and (f1) in
1a and the rest in 1b. The table of where each went, and why, is in §5 → "Order from here".

### Phase C — every control route refuses without the header, by test

**Done, 2026-10-18.** PR #83 merged 2026-10-18 07:25Z as `fbc2516`. CI on the PR head `01674dc` and on the merge push: `build`, `web` and `release-swap` green (no `repo` job existed yet). `ControlHeaderCoverageTest` is in `:inspector-daemon:test`, which `build` runs, and is on `main`. Every acceptance criterion is a test in that file; the ten red proofs below were run locally by the engineer and QA.


**Audit finding 5.**

**For whom.** Anyone with a browser open while the daemon runs. `docs/decisions/daemon-and-mcp.md` records why
`X-Inspector-Control` exists: without it, any web page can POST to `127.0.0.1:8099`. Today each route
opts in by hand, so a new route is unprotected unless its author remembers. §6 is about to add
`POST /api/peers/kill` and `POST /api/sessions/delete`, which are bulk kill and bulk delete.

**Acceptance criteria.**

- One test lists every route the daemon registers. It asserts that each one that is not `GET`
  answers **403** without the header. The list comes from the server itself, not from a list kept
  in the test, so a new route is covered when it is added.
- The allowlist is **markers**, and the reason is quoted from the recorded decision:
  `HttpMarkerPoster` and the MCP tools post markers without the header, and "a marker is not a
  weapon". Any other entry on the allowlist is a decision for this roadmap, not a line in a test.
- Proven to fail when broken: removing the check from one route fails the test and names the route.
- After phase D, the check is applied once by the control gate rather than at eight call sites.
  This item does not have to wait for D; the test is what holds the rule either way.

**Out of scope.** Authentication. The loopback bind is still the boundary. CORS behaviour.

**Status, 2026-10-18: built, pending CI. Not done.** It is on `shafic/structure-phase-c`, stacked on
phases A and E. The branch is not pushed, so CI has not run. `ControlHeaderCoverageTest` starts a real daemon
with inert fakes for every side effect: exit, peer kill, MCP probe and the replay transport. It walks
the route tree through one new `internal` accessor, `InspectorDaemon.routingRoot()`, and that
accessor is the only production change. It probes 8 routes:

- `DELETE /api/sessions/{id}`
- `POST /api/mcp/probe`
- `POST /api/peers/{pid}/kill`
- `POST /api/replay`
- `POST /api/server/restart`
- `POST /api/server/stop`
- `POST /api/sessions/clear`
- `POST /api/sessions/{id}/signals/request`

Each is probed with no header and with `X-Inspector-Control: 0`. The two WebSocket handshakes
(`GET /api/live`, `GET /ingest`) are printed as exempt, and markers is printed as allowlisted.

Two further guards came out of review. One is a tripwire on `InspectorDaemon`'s constructor
parameter count, because `control` and `peers` default to real implementations. The other asserts
that `POST /api/server/stop` is among the probed routes, so a partial walk cannot pass. A 403 whose
body does not name the header is reported with that body, and every side effect reached is
reported in one failure.

Evidence, run locally: `:inspector-daemon:test` ran 227 tests with 0 failures, and
`check-web-static.js` passed 20/20. Ten breaks went red, and each file was restored from a backup
copy:

1. The check removed from `signals/request`: named with 409.
2. The check removed from `server/stop`: 200, and the fake reported `ServerControl.exit`. The test
   JVM survived.
3. A new open `post(...)`: named with 200.
4. A method-less `route { handle {} }`: reported as `ANY` with 200.
5. The check added to markers: the allowlist assertion failed on 403.
6. `routingRoot()` returning null: "the route scan found no non-GET routes".
7. The guard weakened from `== "1"` to `!= null`: all eight routes were named on the
   `X-Inspector-Control: 0` probe only. The fakes reported exit, spawn and MCP probe together.
8. A seventh constructor parameter: "new dependency: fake it here if it has side effects".
9. The refusal message no longer naming the header: every route was named with the body
   `{"error":"forbidden"}`.
10. `server/stop` renamed: "the route scan missed POST /api/server/stop".

Not shown: a CI run, or a full `./gradlew build`. The iOS and Android targets did not run, because
only daemon code changed.

**What the allowlist accepts.** No preflight is needed for a cross-site `text/plain` POST, and the
markers route reads the body without checking `Content-Type`. So any web page can add a marker to a
live session, including one claiming `source:"user"`. The recorded decision accepts that: a marker
is not a weapon. It is written down here so nobody later mistakes `source` for proof of who
dropped a marker.

### Phase B — module graph, dependency goldens, budgets, TODO, identity and name checks

**Done, 2026-10-18.** PR #84 merged 2026-10-18 09:05Z as `01c6f5f`. CI on the PR head `b51b2c9` went green on all four jobs (`build`, `web`, `repo`, `release-swap`), which runs `:repo-checks:test`, `resolveLockedConfigurations`, the identity check and the name scan. On the merge push `repo` was red on commit identity only ("author email is not the maintainer's noreply address" for the merge commit itself, from the merge dialog; the other three jobs green). The `CLIENT_NAME_TERMS` secret exists (created 2026-10-19) and `ci.yml` passes `--require-terms` on push, so the "still to do once the secret exists" paragraph below is done. **Still open, a maintainer action:** `repo` is not a required check on `main`; branch protection requires `build` and `release-swap` only (checked 2026-10-19).


**Audit finding 8, and several rules that `docs/decisions/` states and nothing enforces.**

**This touches a recorded decision.** `docs/decisions/twins-and-api.md`: "**Every real module needs a noop twin.**" The
new `:repo-checks` module (decision 4) has no twin and must not have one. Neither does
`:inspector-daemon` or either sample. The rule as recorded is about modules *the consuming app
calls into*, so a check that says "every module" will either fail on day one or grow an unexplained
allowlist. The check has to encode the recorded meaning: published modules a consumer calls into.
Its allowlist must give that reason.

**For whom.** A consuming team, whose release build depends on the twins and the module graph
staying what the docs say. Also the maintainer, who today enforces every rule below from memory.

**Acceptance criteria**, each check proven to fail when broken:

- **Module edges** match an allowlist, and `:inspector-model` depends on kotlinx-serialization only.
- **Dependency golden files.** A changed resolved dependency fails until it is regenerated on
  purpose. This matches "Do not bump casually", which today is a sentence and nothing more.
- **Every published, consumer-facing module has a noop twin**, with the reason above in the
  allowlist.
- **No `TODO` markers in source.** This is already a convention, stated at the top of this file.
- **A file-size ratchet** in `structure/budgets.txt`. A file may shrink and may not grow past its
  recorded size, so findings 4, 6 and 10 cannot get worse while they wait their turn.
- **Commit identity and no trailers**, checked in CI over the commits a pull request adds: the
  maintainer's noreply address, and no `Co-Authored-By:` or `Claude-Session:` lines.
- **The client-name scan in CI**, reading its terms from a repository secret (decision 5). Two
  traps, both from the repository's own principles:
  - **CI logs on a public repository are public.** GitHub masks a secret only where its exact
    value appears. A single term matched out of a list would be printed in clear. The check must
    report file and line only, never the matched text.
  - **Pull requests from forks get no secrets.** "A guard that scans nothing must fail, not pass"
    is recorded for `check-release-clean.sh`, and it applies here too. When the secret is empty
    (and an unset secret arrives as empty, not absent; see the signing decision), the step must
    say "not run" and must not print a pass.

**Out of scope.** detekt and ktlint (decision 4). A build-wide formatter. Scanning history, which is
already public. As recorded, the check belongs before the commit.

**Status, 2026-10-18: built, and CI on PR #84 is green on all four jobs. Done once merged.** It is
on `shafic/structure-phase-b`. What landed:

- `:repo-checks`, a build-only JVM module with no `maven-publish`. Its 8 JUnit 5 tests run in
  `./gradlew build`: the module graph against `structure/modules.txt`; the twins against
  `structure/twins.txt`, with `:inspector-model` the one exemption and its reason; a non-empty
  lockfile for every published module and the daemon; the model's lockfile against a
  serialization-and-stdlib allowlist; no markers outside Markdown; and the file budgets in
  `structure/budgets.txt`.
- A `gradle.lockfile` in each of the seven published modules and the daemon, `LockMode.STRICT`,
  checked in `check` by `resolveLockedConfigurations`. The Android locks were generated here
  without an Android SDK, because resolving `androidRuntimeClasspath` does not need one.
- `scripts/check-commit-identity.sh` and `scripts/check-client-names.sh`, run by a new CI `repo`
  job on ubuntu with no JDK.
- The landing page's sponsor-link marker moved into §3's table. It was the one live marker, and it
  served as the marker check's first red run.

**Two deviations from the design, each smaller than the guarantee it keeps.**

1. **`resolveLockedConfigurations` does not use `resolutionResult.rootComponent`.** Measured: with
   it, a runtime dependency added to core and a stale lock entry both **passed**. Only the missing
   lockfile failed, because STRICT throws that before resolution. The graph result drops lock
   validation. The task resolves an artifact view that selects no component instead, which keeps
   the design's property of no downloads and no sibling builds, and adds the guarantee.
2. **Proof 3b's break was replaced.** Removing `ktor-network` from `:inspector-stream` leaves every
   locked graph byte-identical, because it also arrives transitively. That break could not go red
   whatever the guard did. The question it was asking, whether Gradle 9.5.1 fails on a stale entry,
   was answered by removing `ktor-client-okhttp` from stream's `androidMain` instead. It does fail
   ("Did not resolve … which is part of the dependency lock state").

`./gradlew dependencies --write-locks`, the design's regenerate command, runs only the root's
report in Gradle 9.5.1 and locks nothing. The command in `AGENTS.md` → Commands is the one that
works.

**Before merging phase B, the maintainer turns on GitHub → Settings → Emails → "Keep my email
addresses private".** A merge made on github.com takes its author email from the merge dialog, and
the dialog offers every verified address unless that setting is on. The dialog put a non-noreply
author on the merges of #72, #73, #79 and #81–83. The push-to-`main` identity check reads the merge
commit, so if phase B were merged the same way, its own merge would turn `main` red. Those six
merges stay as they are: history on a public remote is not rewritten, and the check never reads
them again.

**After the first green run, add `repo` as a required check on `main`,** beside `build` and
`release-swap`. Until it is required, a red identity or name check does not block a merge.

**The Android locks resolved on CI.** They were written on a Mac with no Android SDK, because
resolving `androidRuntimeClasspath` does not need one, and PR #84's CI, which has the SDK, resolved
them green.

**Still to do once the secret exists:** create `CLIENT_NAME_TERMS`, then add `--require-terms` to
the push-to-`main` run. Until then the scan prints NOT RUN with a warning on every run, which is
true, and never prints a pass.

**Found by the locks, not fixed here: the stdlib is not the pinned Kotlin.** Six published modules
(core, noop, ui, noop-ui, stream, noop-stream) and the daemon resolve `kotlin-stdlib:2.3.21`, while
the pinned Kotlin is 2.3.20. Only `:inspector-model` resolves 2.3.20. Ktor 3.5.0's own modules ask
for 2.3.21, and Gradle takes the higher version, so consumers of those modules get 2.3.21 whatever
the pin says. The locks record this as it is. Whether to move the pin to 2.3.21 or to constrain the
stdlib down is a decision for its own item, and it changes what consumers resolve.

Evidence, run locally on macOS with no Android SDK:

- `:repo-checks:test` ran 8 tests with 0 failures.
- Every non-Android `check` passed, plus `resolveLockedConfigurations`: 834 tests, 0 failures. That
  covers JVM and iOS-simulator tests for model, core, ui and stream, the noop JVM tests, the daemon's
  227 and `:repo-checks`. The four `testAndroidHostTest` tasks and `:sample:android` need the SDK
  and did not run.
- `check-web-static.js` passed 20/20.
- `:repo-checks:test` and `resolveLockedConfigurations` also pass under `-Pinspector=off`.

Every listed proof went red, and each file was restored from a backup copy:

- The module graph went red on an added edge and on a removed one.
- The twin check went red on `maven-publish` added to `:repo-checks`, on it removed from
  `:inspector-noop-ui`, and on a blank exemption reason.
- The locks went red on core's OkHttp moved to `implementation`, on a stale entry, on a deleted
  lockfile, and on a hand-edited version with the configuration cache warm. The lockfile is a
  cache input, so the cache was discarded.
- The model allowlist went red on kotlinx-datetime after its lock was regenerated and STRICT
  resolved it green.
- The marker check went red on the real `site/index.html:688` and on one planted in a `.kt`.
- The budgets went red on Server.kt growing, on it shrinking, on an untracked 601-line file, and on
  a deleted entry.
- Commit identity went red on a foreign author, a foreign committer, three trailer spellings, a
  GitHub-committed commit with a foreign author, and an empty range (exit 2). It passed a
  GitHub-committed merge authored by the maintainer.
- The name scan, with `Wormholy` as its only term, exited 1 listing `AGENTS.md:11` and printed the
  term nowhere. An empty term list printed NOT RUN and exited 0, or 1 under `--require-terms`.

Also red: the daemon's lock through `distZip`, a budget entry for a missing file, a regenerate run
that would have raised a budget, and a `git ls-files` failure.

Not shown: a CI run; the Android host tests; the `repo` job itself, including the `env:`-based
range expressions, which only GitHub evaluates.

### Phase G — `AGENTS.md` as an index, `docs/decisions/`, per-module guides

**Done, 2026-10-18.** PR #85 merged 2026-10-18 09:05Z as `b51b2c9`. CI on the PR head `fdf8d4c` green on all four jobs; `DecisionsTest` (the one-copy check and the root size budget) runs in `:repo-checks:test`. The root `AGENTS.md` is 14,555 bytes against the 15,000 budget. On the merge push `repo` was red on commit identity only (merge commit author address); `build`, `web` and `release-swap` green. No tickets existed for this phase.


**Audit finding 7.**

**For whom.** Every agent working here, including the team in `.claude/agents/`. `CLAUDE.md` is
loaded into every subagent, so 126 KB is paid on every run, and 92 KB of it is decisions about
modules the run is not touching. Also a human reading it for the first time, who has to work
through one flat list to find the rule for the file they are about to edit.

**This touches a recorded decision.** `AGENTS.md` opens by saying it is the one canonical context
file, with "**exactly one copy to keep current**". Per-module `AGENTS.md` files with `CLAUDE.md`
symlinks keep the *one copy of each decision* property only if the check that comes with them is
real. That check is what makes this item compatible with the decision rather than a reversal of it.

**Acceptance criteria.**

- The root `AGENTS.md` is under 15 KB, and a check holds that budget.
- The decisions move **word for word** into `docs/decisions/*.md`. The move is its own commit, and
  a diff shows nothing reworded. Editing comes after, in separate commits.
- A check fails when one decision appears in two places, proven by pasting a copy.
- Every reference to a moved heading is updated in the same change. That covers `.claude/agents/*`,
  `docs/TEAM.md`, the closing section of this roadmap ("Non-obvious decisions — do not 'fix'
  these"), and the `roadmap-cycle` skill. Otherwise the team's own instructions point at headings
  that no longer exist.
- The stale Architecture section is corrected against the real module list.

**Out of scope.** Rewriting, merging or dropping any decision during the move. Changing what any of
them says.

**Found while building it, and left for later because the move must not reword anything:**

- The `Formatting.kt` row, now in `inspector-ui/AGENTS.md`, still says it holds `pathScope`, which
  moved to `:inspector-model`.
- `docs/STATUS.md` → "Also outstanding" still lists the Android and iOS sample shells, which exist.
- The root came in at about 14.8 KB against a 13.5 KB target, because the sections the design kept
  add up to more than the target. Status churn has STATUS.md to go to, but a new root section will
  need something moved out first.

### Phase D — daemon packages; split `Server.kt` and `McpTools.kt`; layering check

**Done, 2026-10-18.** PR #87 merged 2026-10-18 13:02Z as `f574bda`. CI on the PR head `b919341` green on all four jobs (`build`, `web`, `repo`, `release-swap`), so `DaemonLayersTest` and the 247 daemon tests ran there. Tickets KIN-1 to KIN-9 are Done. On the merge push `repo` was red on commit identity only (merge commit author address); the other three green. The counts quoted below are the engineer's and QA's local ones; no full-repository total was taken.


*Ticket label:* `item:structure-phase-d`.

**Audit findings 5 and 6.**

**For whom.** Whoever builds §6. Stats, diff and findings are three services that the HTTP API and
MCP must both serve identically. §6's config item says outright that "an agent and the page
disagreeing about what counts as a finding is a worse bug than either alone". Services with no Ktor
in them are what make that structural rather than a matter of care. Decision 3 puts the rules there.

**Acceptance criteria.**

- The packages are as proposed above: `http/`, `mcp/`, `service/` and `archive/`. `Main.kt` does not
  move.
- An import scan fails when `service/` or `archive/` imports Ktor, or when `http/` and `mcp/`
  import each other. It is proven by adding one such import.
- `Server.kt` and `McpTools.kt` are split along those lines, with routes grouped and tools split by
  group. Every daemon test passes unchanged, and no MCP tool description or route changes.
- The peer, MCP-probe and server-control decisions in `docs/decisions/daemon-and-mcp.md`
  (identity re-checked at kill time, no "start MCP" endpoint, `ServerControl` as an interface) are
  still true, and their file references are updated.

**Out of scope.** Any new endpoint or tool. Changing the archive layout.

**Status, 2026-10-18: built; done once its PR is merged and CI is green.** It is on
`shafic/structure-phase-d`, which is not pushed, so CI has not run. Tickets KIN-1 to KIN-9 are Done on
QA evidence from two passes by a fresh verifier, the last at `753d9cf`; `c1f2a4a` only adds doc/KDoc text
and a `Unit` return type, and QA did not re-run it (the engineer reports 247/0 daemon tests and 35/0
repo-checks). What landed:

- **Guards first (C1).** `MainClassTest`, `SerialNameTest`, a byte-for-byte `tools/list` golden,
  `PeerKillIdentityTest` and `ReadRoutesOpenTest`, each proven red and green at base. `Daemon.kt` was
  deleted as unreferenced.
- **The moves (C2-C5).** `archive/`, `service/` and `http/` beside `mcp/` and `usb/`; `Main.kt` alone at
  the root. Renames are 93-99% similar, and the test diff is import lines only. The three decoded
  `@Serializable` types carry `@SerialName` pins, because kotlinx-serialization quotes the serial name in
  a 400 body and in stderr.
- **`DaemonLayersTest` (C6).** An import scan over rules L0-L6. It passes live with 0 violations, goes
  red on real-file breaks including a split-line FQN, and goes red when its own parser is blinded.
- **The splits (C7, C8).** `Server.kt` became `InspectorDaemon.kt` plus ten route-group files, with the
  `Replayer` and `McpControl` kept lazy so no `HttpClient` thread exists until the first replay.
  `McpTools.kt` became a `ToolGroup` plus seven files. The largest new file is 243 lines.
- **One control gate (C9).** `ControlGate` replaces the eight header checks; exactly 8 routes are gated.

**What the gate taught, KIN-9.** The first gate shape turned 405 into 404 on any path that also had a
gated sub-route; the base-against-head route matrix found it on 45 rows. The gate now installs on each
route, and all 36 affected rows are 405 again. The ticket's `createChild(ControlGateSelector)` design was
replaced by this.

**Evidence against base `1db168b`.** `tools/list` and an 88-frame transcript are identical. The
855-row route matrix has 0 differing rows. The jar version reads through to MCP and HAR. Daemon tests are
247 = 227 base + 20 new, with every base test present. The web probes pass with 164 checks (1 n/a) and 21.

**Not verified.** The no-client-names check on the tickets: the term list is kept out of the repository,
so QA cannot run it. The release steward scans what it can. `McpTest.kt` was not split, and is the
"Later" item below.

### Later — split `McpTest.kt`

**Done, 2026-10-21.** PR #102 merged 2026-10-20 as `49735be`; `build`, `release-swap`, `repo` and
`web` green on the PR, and the push run on `main` succeeded. KIN-59 is Done on QA's pass over
detached copies of the merge and its first parent. `McpTest.kt` (784 lines, 43 tests) is gone. Its
tests are in `inspector-daemon/src/test/kotlin/dev/inspector/daemon/mcp/`: `McpProtocolTest` (11),
`TransactionToolsTest` (17, `get_body` and `explain`, matching `TransactionTools`),
`SignalToolsTest` (7), `SessionToolsTest` (1), `MarkerToolsTest` (1), `ExportToolsTest` (3),
`McpAcceptanceTest` (3, questions that cross groups) and the shared `McpToolsFixture`. There were
268 daemon tests before and after, with identical sorted test-name lists; all 43 test bodies are
byte-identical at their new home; `inspector-daemon/src/main` has no diff; the deferred line in
`structure/budgets.txt` is removed and the largest new file is 286 lines.


*Ticket label:* `item:structure-split-mcp-test`.

**Left over from phase D. Later.** *(Scheduled 2026-10-18 in "Order from here", before
§6.)* `McpTools.kt` was split by tool group, and its test file was
not. `McpTest.kt` is 815 lines, and `structure/budgets.txt` lists it as deferred. Phase D could not
split it, because its acceptance criterion was that every daemon test passes unchanged, and phase
D added only five import lines to this file. Splitting the file would have moved every test in it.

**For whom.** Whoever next adds or changes an MCP tool. The tools now live one group per file, and
their tests are still in one file, so a change to one group means finding its tests in 815 lines.

**Acceptance criteria.** The tests split along the same groups as the tools in `mcp/`, one test
file per group, plus a shared file if the fixtures need one. The test count is the same before and
after, stated in the change. Each test moves word for word, and the diff shows nothing reworded.
The phase B ratchet records the new, smaller budgets, and the deferred line in
`structure/budgets.txt` is removed.

**Out of scope.** Changing what any test asserts. Adding tests, including the two §3 → Guards items
that came out of phase D. Those are separate items, so that a move and a new check never share one
diff.

**What was learned.** The roadmap said 815 lines; the file was 784 when it was split. A
word-for-word move still widens the fixture's members from `private` to `protected` and the `int()`
helper to `internal` (test source set only), and that is the whole of the permitted rewording; QA
found it with a line-level check that every other base line survives. The protocol and acceptance
tests belong to no one tool group, so they got files of their own. Landing the split first meant the
row-label test went straight into `SignalToolsTest`, the order the item argued for. The ratchet was
proved in both directions: padding a new file to 616 lines fails `:repo-checks:test`. Not verified
locally: the full `./gradlew build` (no Android SDK); CI ran it.

cost: not recorded — built in the 2026-10-20/21 coordinated run, one builder session, no QA before merge, QA after merge; 1 round

### Phase H — split the two large overlay files

**Done, 2026-10-21.** PR #103 merged as `4dc21d2`; `build`, `release-swap`, `repo` and `web` green
on the PR, and the push run on `main` succeeded. KIN-61 is Done on QA's pass. The builder named the
seams (no architect on the row): rows out of the list body, and the bars out of what opens over the
list. `InspectorList.kt` went from 758 to 351 lines and `ListChrome.kt` from 703 to 360; two files
are new, `ListRows.kt` (333) and `ListSheets.kt` (492: the menu, the filters sheet, their shared
scrim and the term edits). The only code change is `private` to `internal` on `SignalRow`,
`RunHeader`, `MarkerDivider` and `TransactionRow`. `InspectorOverlay.kt`, `InspectorTheme.kt` and
the jvmTest sources have no diff; `ListRenderTest` and `ListControlsTest` pass unchanged (134 tests
before and after, same names); all 12 360dp screenshots are byte-identical before and after, and two
were read by eye; `javap -public` shows `InspectorOverlayKt` identical. Both phase H budget lines
were removed, and `ListControlsTest.kt` keeps its 605-line budget with the reason rewritten.


*Ticket label:* `item:structure-phase-h`.

**Audit finding 10. Later.** `ListChrome.kt` (703 lines) and `InspectorList.kt` (758) are the
overlay's list and its chrome. Nothing in §6 touches the overlay, so the work can wait. The phase B
ratchet stops them growing in the meantime. *(Scheduled 2026-10-18 in "Order from here",
before §6. It still does not have to wait; the maintainer chose not to let it.)*

**For whom.** Whoever next changes the overlay's list.

**Acceptance criteria.** Each file is split along a seam the architect names. `ListRenderTest` and
`ListControlsTest` pass unchanged, and the 360dp screenshots are compared by eye before and after.
`InspectorOverlay.kt` keeps its name. The ratchet records the new, smaller budgets.

**Out of scope.** Any change to the list's behaviour or look.

**What was learned.** Compare screenshots only with `jvmTest --rerun`: the PNGs are not declared
task outputs, so a cached test task leaves a stale set that looks like a pass. Changing
`STRIPE_WIDTH` from 3dp to 4dp produced 5 differing PNGs, which is what shows the comparison can
see. Kotlin `internal` is public in bytecode, so the four list files' JVM facades differ from before
while nothing a consumer can call changed; the check that matters is that `InspectorOverlayKt` is
identical. Not verified locally: the full `./gradlew build`, the `-Pinspector=off` build on the
Android and iOS targets, and the klib half of `check-release-clean.sh` (no Android SDK and no
working `xcrun` on the machine); those rest on CI. `ListControlsTest.kt` (605 lines, budgeted) stays
unsplit because the criteria kept it unchanged; a possible follow-up, not filed.

cost: not recorded — built in the 2026-10-20/21 coordinated run, one builder session, no QA before merge, QA after merge; 1 round

### Phase I — `noop-ui` golden file

**Done, 2026-10-21.** PR #106 merged as `1479c30`; `build`, `release-swap`, `repo` and `web` green
on the PR (the push run on `main` was still in progress when QA wrote). KIN-62 is Done on QA's pass;
the `docs/INTEGRATION.md` v59 work was folded into the same PR. `api/inspector-ui-public-api.txt` (a
header and two lines) is asserted for equality by `UiApiParityTest` in both `:inspector-ui` and
`:inspector-noop-ui`. A second test in each names every public holder, so a public declaration in
any file fails and not only one in the facade. `InspectorOverlayKt` is the recorded facade, so
renaming either `InspectorOverlay.kt` fails both tests. `InspectorTheme`, `InspectorColors` and
`LocalInspectorColors` are `internal`. The core and stream golden files are byte-identical after
regeneration. `docs/INTEGRATION.md` v59 tells an affected app what to do before it upgrades past
1.3.0. There are 136 tests in `:inspector-ui:jvmTest` (134 + 2) and 2 in `:inspector-noop-ui`.


*Ticket label:* `item:structure-phase-i`.

**Audit finding 9. Unblocked by decision 2. Does not gate §6.** *(Scheduled 2026-10-18 in
"Order from here", so it lands before §6 starts, although nothing in §6 depends on it.)*

**This changes public API for `:inspector-ui`, and needs the noop twin and golden-file treatment
sized honestly.** `InspectorTheme`, `InspectorColors` and `LocalInspectorColors` become `internal`.
Nothing in this repository outside `:inspector-ui` uses them, the samples included, and no document
has ever told a consumer to. But they have been public in every published version, so a consuming
app that themed against them stops compiling. A removal can be undone only by a release, and §3 →
Guards called this "a decision rather than a tidy-up" for that reason. Two follow-ons:

- `docs/INTEGRATION.md` gets a version bump and a changelog entry that says what to *do*: "if you
  referenced these, copy the colours you need". The recorded rule is that any change affecting a
  consumer gets one.
- ~~**Open for the human:** whether a removal from public API ships in a minor release.~~
  **Decided 2026-10-18: yes, it may ship in a minor release.** The changelog entry above is what a
  consumer gets in place of a major version, so it must say plainly that three public types are
  gone and what to do instead.

**Acceptance criteria.**

- `api/inspector-ui-public-api.txt` (or whatever the architect names it) is asserted for
  **equality** by both `:inspector-ui` and `:inspector-noop-ui`, as the stream pair is.
- `InspectorOverlayKt` is recorded as the JVM facade in both. Renaming either `InspectorOverlay.kt`
  then fails the guard, which closes the gap §3 describes: "they agree because both files happen to
  be called `InspectorOverlay.kt`, not because anything checks".
- The constructor and method JVM descriptors are compared, as for the other pairs. Compose's
  synthetic `Composer` and `changed` parameters are handled by a stated rule and not by an
  allowlist, and the generated golden file is read by a person before it is trusted.
- Proven in both directions, on both modules: add a public member to one side, rename one file.
- `-Pinspector=off` still builds.

**Out of scope.** Mirroring the palette into the noop, which was rejected (decision 2). A class-set
diff, which is on the "do not" list.

**What was learned.** The builder proved thirteen breaks (R1 to R13), each seen failing and
restored; QA repeated all but R8. Gradle 9.5.1 rejects a missing file under
`inputs.file(...).optional()`, so the golden file is declared with `inputs.files(...)`, and R11
shows it is a real task input. R8 (a changed `content` parameter type) needed `OverlayStateTest.kt`
moved aside, because that test calls `InspectorOverlay { Text(...) }` and stops compiling first. The
composable rule needs no name list: a method with more JVM parameters than its Kotlin declaration is
rendered from the declaration, and otherwise keeps its Java form. The `enabled` rename (R7) is
caught by the `fun` line alone, not by the descriptor, which is why the golden file holds both
lines. A trap in the proofs: Gradle's `--rerun` applies only to the task named just before it, so
the design note's R12 command (`:inspector-core:jvmTest :inspector-stream:jvmTest
-Dinspector.api.regenerate=true --rerun`) re-ran stream only and left core's golden file untouched.
Written as `:inspector-core:jvmTest --rerun :inspector-stream:jvmTest --rerun
-Dinspector.api.regenerate=true`, it rewrote both and the diff stayed empty, so the result stands;
the faulty command is not quoted anywhere in the repository. The guard is JVM-only: a public
declaration that exists only in `iosMain` or `androidMain` is unguarded on every pair. Not verified
locally: the full `./gradlew build`, every Android target and the iOS links (no Android SDK, no
working `xcrun`); CI ran them. Follow-ups for the product owner, none part of this item: apply the
completeness check to the `core` and `stream` pairs; declare their golden files as `jvmTest` inputs,
since a golden-only edit can hit the build cache and stay green; a klib ABI dump for the native-only
surface; name each pair's golden file in `structure/twins.txt` so a pair without one fails
`:repo-checks`. `.claude/agents/architect.md` line 31 still says the noop-ui golden file is missing.

cost: not recorded — built in the 2026-10-20/21 coordinated run, one builder session, no QA before merge, QA after merge; 1 round

### Open questions only the human can answer

- ~~Whether making the three `noop-ui` theme types `internal` ships in a minor release, given that it
  removes public API from every published version up to now (phase I).~~ **Decided 2026-10-18 by the
  maintainer: yes, a minor release.** Recorded in phase I.
- ~~Whether the MCP `timeline` tie-order fix is announced as a behaviour change in the release notes,
  or only in the tool description (phase E).~~ **Announced in both the release notes and
  `DAEMON.md` §8** (decided by the agent team on 2026-10-18 under the maintainer's delegation; the maintainer may overturn). The tool description states the rule without a version.
- ~~**Is a labelled 1000-call ceiling acceptable?** *(Raised 2026-10-18, recorded 2026-10-19.)*
  `item:web-transactions-paged` lets the architect cap what the list draws, provided the count says
  so in words that cannot be read as a filter, such as `newest 1000 of 4210 shown`. A session of
  about 160,000 calls has since shown that drawing and holding everything has a real cost. If the
  answer is no, the list draws every call and the item likely needs virtualising, which it now
  leaves out of scope, and its size goes up. If yes, the remaining question, whether the
  *whole-session read* behind the chips and repeat marks is capped too, is the architect's.~~
  **Decided 2026-10-19 by the maintainer: yes.** The list draws at most 1000 calls and says so.
  Whether the whole-session read is capped is **not** the architect's under this item after all: it
  belongs to KIN-42, which the maintainer kept deferred and outside `item:web-transactions-paged`.
- ~~**Where does `item:web-list-says-what-it-holds` go?** *(Raised 2026-10-19.)* The first verify
  panel on `item:web-transactions-paged` found that the list still states a false count under a
  filter (`1000 of 3016 calls` where 1200 match) and a false absence under an old brushed stretch.
  The panel called the first the most misleading thing left on these screens, and the ceiling was
  accepted on the condition that it is labelled. The product owner proposes it **straight after
  `item:web-transactions-paged`**, ahead of `item:web-live-rows-filtered`: it is small, in the same
  files, and it completes the condition the maintainer set. The alternative is after
  `item:web-current-session-guard`, with the other web defects. It has no place until the maintainer
  says.~~ **Decided 2026-10-19 by the maintainer: with the web defects, straight after
  `item:web-current-session-guard`**, in "Order from here". The rows after it moved down
  one; the maintainer's rows keep their relative order.
- ~~**Does `item:web-live-rows-filtered` keep the two criteria added 2026-10-19**, the live-tail
  count refresh and the whitespace-only filter, given that they may size it above S and so move it
  after phase I?~~ **Decided 2026-10-19 by the maintainer: it keeps both.** If the architect sizes
  it above S, its own rule applies and it moves after phase I, still ahead of 1a. The maintainer
  accepted that.

## 6. Next: the daemon and the web UI — planned 2026-10-17

### Web UI foundation — 1a, the shell

**Done, 2026-10-21.** Three merges: PR #108 (daemon, merge `e9f7131`), PR #112 (web, merge `a7e3946`)
and PR #114, a fix round (merge `cc9dbce`). `build`, `release-swap`, `repo` and `web` were green on
each PR, and the push runs on `e9f7131`, `a7e3946` and `cc9dbce` succeeded. KIN-63 to KIN-71 are Done
on QA's evidence: PR A on its own, then PR B in two rounds, the first of which failed KIN-68. The
shared text of this section (the 1b half and the paragraphs both halves rest on) stays in
`ROADMAP.md`; what follows is the text that was only 1a's, moved verbatim, then what was built and
learned.

*Ticket label:* `item:web-foundation-shell`.

**1a must leave room for the security advisory's fix** *(added 2026-10-18)*. The maintainer
scheduled that fix after 1a, 1b and 2 (§5 → "Order from here"), so the bulk endpoints are built
before it is chosen. Acceptance criterion: the architect's design for `POST /api/peers/kill` and
`POST /api/sessions/delete` states how the advisory's fix, under any of the options the advisory
lists, lands without rebuilding either endpoint, and the architect reads the advisory to write it.
The design note in this public repository names the advisory and does not describe the gaps.

In **1a, the shell**:

- **(e) Nothing in the top bar is out of reach at a 400px window.** At base the bar measured
  scrollWidth 936 against 400: the session picker was clipped, and live, settings, restart, stop
  and delete could not be reached. After 1a, the top bar's scrollWidth is at most the window width
  at 400px, and every control it still holds can be reached by pointer and by keyboard. Restart and
  stop leave it for Processes anyway.
- **(e) The call count is on screen at 400px.** *(Added 2026-10-19, from the first verify panel on
  `item:web-transactions-paged`.)* Below about 600px the count sat past the right edge of the
  overflowing top bar, so it was off-screen entirely. That is the overflow above, but the criterion
  above covers controls and the count is not one, so it is stated here: at 400px the whole count
  text is inside the window. If 1b moves the count into its one-row toolbar (f2), the same check
  moves with it into 1b. *(Seen again 2026-10-19 by QA on `item:web-live-rows-filtered`, at base
  `4e5491c` too: at 400px under a filter the count read `5 of 31 call`, cut mid-word. The same
  criterion covers it; a count cut short is not the whole count text.)*
- **(e) Keep the session's start time visible when the picker truncates.** *(Added 2026-10-19, from
  the UX reviewer on `item:web-transactions-paged`.)* Since KIN-49 the closed picker can shrink to
  "com.example." at 600-700 px, so two sessions of one app look alike until it is opened. After 1a,
  the part of the label that tells sessions apart, the start time, is still on screen with the
  picker closed.
- **(e) Every tab is reachable at a 400px window.** At base the tab bar measured 704 against 400,
  and screens, state, analytics and tap were hidden. The bar need not *show* every tab at that
  width, but each must be reachable without resizing: visible, or behind one control that is. The
  architect chooses which. "The tab bar is always drawn" (`docs/decisions/web-ui.md`) still holds.
- **(f1) The session picker shows its whole label at 1440px**, or clips it with an ellipsis and the
  full label as a tooltip. It is not cut off mid-text.
- **The "Lost the daemon" banner is on screen for as long as the page has lost the daemon.**
  *(Added 2026-10-19, from the signals verify panels.)* As reported, `Lost the daemon — reconnecting…`
  (`web/js/05-network.js`, `ws.onclose`) shows only on the first close after an unplanned drop.
  The code that shows it runs only while `state.serverState` is `running`. The architect confirms
  the reproduction and its cause first. The criterion holds either way: after any
  unplanned drop the banner is visible until the page reconnects, and it clears then. That covers
  the first drop, a second drop after a reconnect, and a drop during a restart that never comes
  back. The stop probe's replayed `onclose`/`onopen` pair runs twice in a row and asserts the
  banner both times, and fails with the `running` check put back. *Why 1a:* 1a moves restart and stop
  into Processes and rebuilds the top bar the banner sits under, so the states this keys on
  (`stopped`, `restarting`, `away`) are rewritten there anyway. The recorded rule that a stopped
  daemon's banner offers no restart (`docs/decisions/web-ui.md`) still holds.
- **The session switch keeps its probes green.** *(Added 2026-10-19, from QA on
  `item:web-current-session-guard`.)* 1a rebuilds how a session is chosen, as Sessions leaves the
  dialog and hash routes give a session an address. The probes KIN-51 and
  `item:web-now-strip-follows-session` add (a late or failed `/current` read across a switch, the
  out-of-order pair, the switch window) still pass after 1a, driven through whichever control
  selects a session then, and are not deleted or reported `n/a` because the picker moved. *Why 1a:*
  it is the item that moves the switch; the defects themselves are fixed earlier, in their own item.

**The delete and clear loops move into `service/` with the bulk delete, and not before.** Today
they sit in the route bodies: `DELETE /api/sessions/{id}` and `POST /api/sessions/clear` in
`inspector-daemon/.../http/SessionRoutes.kt` each refuse the active session, call
`deleteSession` with their own `DeletionCause`, and build the deleted/kept reply. `POST
/api/sessions/delete {ids}` is the second caller of the same rules: refuse the active session,
refuse the `latest` alias, and answer per item. Copying them into a third route body would make
three copies of the rule about which sessions may be deleted. Phase D's layering rule is written
for rules both adapters serve ("a rule both adapters serve lives in `service/` or `archive/`, never
in a route or tool body"), and deletion is HTTP only, but the reason behind it holds here too: two
copies of a rule drift. So 1a extracts one session-deletion service, and all three routes call it. It was left in place during phase D on purpose: with one caller, an
extraction is a move with nothing to prove it right, and phase D allowed no behaviour change. Any
findings cache built later is removed through the same service, which is the "every deletion path"
condition recorded under Findings.

**What was built.** The daemon half: `service/SessionDeleter.kt`, which single delete, clear and the
new `POST /api/sessions/delete` all call; `POST /api/peers/kill`; `archive/ConfigFile.kt` and
`GET`/`PUT /api/config`, flags still winning, written by a temporary file and a rename. All three
routes sit inside `controlGated`. The web half: a left rail over hash routes (Traffic, Sessions,
Processes, Settings), the settings dialog gone, Sessions and Processes as pages with checkboxes,
presets and a two-click confirm that sends one request, restart and stop moved to Processes, a
Settings page with the config editor, `controlFetch` as the only writer of `X-Inspector-Control`, a top
bar and tab bar that fit a 400 px window with the count whole, and the lost-daemon banner on every
unplanned close. Counts, base to head: static checks 90 to 99, live probe 204 to 225, archive probe
38 to 40, daemon tests 271 to 299 at PR A.

**What was learned.**
- **QA's first round failed KIN-68 on something the probe could not see.** The Processes page drew
  `signalled N of M` and the per-pid list, and 300 ms later reloaded the table, which cleared the
  status; a refused kill left nothing on screen. `processes bulk` read the status before the reload.
  The fix (`loadPeers({ keepStatus })`) and a probe that waits 700 ms and answers one pid as
  `noSuchProcess` are in PR #114; the probe was red against the old code. The roadmap's reason for
  answering per item, that a batch with one status hides the failure, was exactly what the page did.
  Refresh had to become `() => loadPeers()` so the click event is not taken as the options.
- **The banner's cause was not the plain drop.** The design found a single unplanned drop never
  reproduced the bug; the `running` check mattered after a failed restart and for a restart that never
  returns. QA put the check back and `banner after failure` and `banner restart lost` went red while
  `banner every drop` stayed green. Verified with real process deaths: the banner reads `Restarting…`
  at +5 s and `Lost the daemon — reconnecting…` at +24 s, past the 20 s grace.
- **`config.json` is indented by hand.** kotlinx's pretty printer rewrites `2.50` as `2.5`, which
  breaks "unknown keys kept byte for byte". The bulk bodies refuse unknown keys, pids must be positive,
  and a symlinked `config.json` stays a symlink. `SessionDeleter` takes `sessionsDir` as a third
  parameter so the single route's 500 message keeps naming `config.sessionsDir` word for word.
- **The probes stub every destructive route.** One red run would otherwise have sent real deletes; it
  was stopped before the Sessions probe, and both fixture sessions survived.
- **The Chrome `shell` group runs after the parallel batch**: twelve windows at once dropped DevTools
  requests. `writeHash` falls back to `location` where jsdom refuses history writes on an export's `file:` URL.
- **A guard that cannot fail was adjusted, not kept.** `route default` needed every page made visible
  first, because the pages carry `hidden` in the HTML; one neighbour red proof used `tab bar shown`.
- **`railGone` was rewritten, not deleted**: `rail is navigation` keeps the reasoning that the old rail
  held the filter and the Now panel and was removed for it. QA proved it red with an input in `#rail`.
- **The picker face has one writer**, `paintPickerFace`. The next item, `item:web-live-calls-reach-every-pane`
  (KIN-58), must call it wherever it rewrites an option's text.
- **Left out, on purpose.** Stats, Findings and Diff have no rail entry until the item that builds each
  page. "Pick two → compare" waits for Diff. The closed detail drawer sits off-screen to the right at 1000
  and 400 px and widens the document (1646 px at 1000), which is 1b's (a). The Sessions page still says
  sessions live under `~/.inspector/sessions` even with `--data` elsewhere, word for word from the old
  dialog; the Processes page already shows the real data directory.
- **Not verified.** The full `./gradlew build` and `-Pinspector=off` (no Android SDK; CI ran them). The
  Chrome `first` section, a 160k-call session. A headless-Chrome reproduction of the fix round with a
  real peer exiting before the kill was QA's, in round 2, but the engineer's probe stubs the kill reply.
  The bulk routes and `PUT /api/config` sit behind the control gate only; the private advisory's fix is
  not built.
- **How it was built.** A Large item built without a design council or UX pass, by a developer session
  with engineer subagents, and QA ran after merge, not before. QA rendered the pages in headless Chrome
  131 at 1440, 1000, 600 and 400 px, live and in an export.

cost: not recorded — built in the 2026-10-20/21 coordinated run, developer session with engineer subagents, no design council, no QA before merge; QA after merge, 2 rounds

### Web UI foundation — 1b, room for the list

**Done, 2026-10-21.** PR #119, merge `4fe71dc`. `build`, `release-swap`, `repo` and `web` were green on
its head `2814977` and the item was merged with `--admin` once each read pass by name. KIN-78 to KIN-86
are Done on QA's evidence, one round, run after the merge. The shared text of this section stays in
`ROADMAP.md`; what follows is the text that was only 1b's, moved verbatim, then what was built and
learned.

*Ticket label:* `item:web-foundation-list-room`.

- **Room for the list:** a draggable divider between list and detail (double-click resets, width
  remembered, collapse and full-width buttons); the now strip and the axis fold into one
  `overview` line; the toolbar becomes one row with endpoint chips behind `endpoints ▾` past about
  six; export goes into a `⋮` menu; a density preference; and `#/txn/{id}` opens one call full
  screen. Measure the band heights before and after — the bar is about 120px more for rows.

In **1b, room for the list**:

- **(a) A closed drawer is not focusable and does not widen the page.** At base, below 1200px, the
  closed `#detail-pane` made the document 1646px wide at a 1000px window; Tab reached 13 controls
  inside it, and the first one (`✕`) scrolled the window 646px sideways, half-opening the drawer
  with no scrim. A trackpad swipe did the same. After 1b, at 1000px with the drawer closed, the
  document's scrollWidth equals the window width, and a probe tabbing through the whole page never
  lands focus inside the closed pane. That probe is new, and is proven to fail with the fix
  removed. UX suggested clipping `#panes`'s overflow in the narrow block and `inert` or delayed
  `visibility` on the closed pane; the architect chooses.
- **(b) The endpoint survives at 400px.** At base, paths were cut to about four characters while
  the clock, duration and byte columns kept full width, and the row
  `POST auth.example.invalid /oauth/token` lost its whole path and its byte count. After 1b, at
  400px, every row in the base session shows its path's last segment in full and its byte count.
  The host and the clock give way before the path does. UX suggested dropping or shortening the clock column below about
  520px.
  *(Added 2026-10-19, from the first verify panel on `item:web-transactions-paged`.)* **The repeat
  mark survives too.** At 400px it was cut to `twic…`, leaving only the tint, and a tint alone does
  not say how many times. After 1b, at 400px, every repeat mark in a repeat-bearing session reads in
  full, in its usual words or a shorter form such as `2×`, which the overlay already uses.
- **(c) The drawer's `✕` covers nothing.** At base, at 1000px, it sat over the header's last action
  (`replay ▾` served, `copy for agent` in an export), so a click on that corner closed the drawer.
  After 1b, the `✕` and every header action have disjoint boxes at 1000px, served and in an export.
- **(d) A closed drawer casts no shadow.** At base a dark strip showed at the right edge, mostly in
  light mode. After 1b the drawer's `box-shadow` applies only while it is open.
- **(f2) The call count does not wrap at 1000px.** At base `16 of 16 calls` broke over two lines.
- **(f3) Two counts never read as disagreeing.** At base the scope bar's `15 of 16` sat beside the
  top bar's `16 of 16`. They count different things, and the labels must say so. UX suggested
  `15 of 16 under this prefix`. The overlay's scope bar is out of scope here.
  *(Widened 2026-10-19, from the first verify panel on `item:web-transactions-paged`.)* On a
  1203-call session one screen showed four numbers for one session: the tab `traffic 1000`, the
  header `newest 1000 of 1203 shown`, the scope bar `1203 of 1203` and the axis `1203 calls`. The
  scope bar's sits directly over the rows and reads as "rows below", but it counts the session.
  After 1b, every count on the traffic page says in its own words what it counts: drawn rows, the
  session, or a prefix. Two that count the same thing show the same number. A probe on a capped
  session reads all of them and fails if any two disagree without different labels.
- **The sticky layout KIN-33 fixed is held by a committed check, and the check lands before 1b
  moves anything.** *(Added 2026-10-19, from the signals verify panels.)* KIN-33 made every sticky
  band in the list pane stop under the scope bar, through `--sticky-top`
  (`web/css/35-list.css`). It was verified only by headless-Chrome scripts in a scratch directory,
  so nothing in the repository fails if a new band goes back to `top: 0` and slides under the bar.
  1b is about to add such a band, the overview line. The criterion: a CI check fails when a sticky
  rule in the list pane, other than `.scope-bar` itself, takes its `top` from anything but
  `--sticky-top`. It is proven red by putting `top: 0` back on `#timeline > .filter-note`. The panel
  proposed a static audit in `check-web-static.js`, the shape of the 11px and `[hidden]` audits.
  jsdom has no layout engine, so a computed-position check there would pass whatever the CSS says.
  The architect chooses the shape. It is 1b's first commit, so that 1b's own changes are made under
  it.
- **Each traffic view opens where it was left, not where another view left off.** *(Added
  2026-10-19, from the signals verify panels.)* Since KIN-33 the list, the timeline, the waterfall
  and the path scroll one pane. With live off, a list scrolled to 5000 opens the timeline at 5000
  of 29,800, a place nobody chose. After 1b, with live off, going list → timeline → list returns the
  list to its own position. The timeline opens where it was last left, or at its start the first
  time. With live on, each view still follows new rows as it does now, and expanding a run still
  does not move the pane (KIN-38's rule). jsdom does not lay out, so this is a probe if jsdom can hold
  the positions, or a documented browser check if it cannot. The architect says which. Out of scope:
  remembering a position across a reload. *Why 1b:* 1b rebuilds the list pane, and 1a's hash routes
  give each view an address to keep a position under.

If 1b slips to 1.5, (a) leaves this list and becomes its own item ahead of Endpoint latency stats,
because it is a keyboard trap. See §5 → "Order from here".

**What was built.** Nine tickets, in two passes by two engineer subagents (KIN-78 to KIN-82, then KIN-83
to KIN-86), each diff reviewed in Chrome renders by the developer session.
- **KIN-78, the sticky guard first.** A static audit, `sticky top`, in `scripts/lib/layout-audits.js`:
  every sticky rule in the list pane other than `.scope-bar` takes its `top` from `--sticky-top`.
  `.pv-metro` was moved onto it, and no path station reading moved against base.
- **KIN-79, the closed drawer.** `visibility: hidden` with a delayed transition, `#panes` clipped in the
  narrow block, the shadow drawn only under `.drawer-open`, and a `#detail-tools` row holding the close
  button in flow above the header, which is what made it disjoint from the header actions.
- **KIN-80, rows at 400px.** Paths drawn as `.path-head` and `.path-tail`, the repeat mark its own
  `.dup-badge` with a long and a short form (`2×` below 520px).
- **KIN-81, one overview line.** The axis, the hint and the now strip in one row; the now list is a popover.
- **KIN-82, one-row toolbar.** `endpoints ▾ N` holds the chips; `⋮` holds the four exports, keyboard
  shortcuts and `compact rows`. `#keys-button` and `#export-host` are gone.
- **KIN-83, divider and hidden detail.** `#pane-divider` (pref `inspector.listShare`, 25% to 75%, arrow
  keys, Home and double-click reset); `#detail-hide` and `#detail-restore`, never remembered.
- **KIN-84, `#/txn/{id}?session=…`.** Looked up in the whole-session copy, then the list's rows, then the
  single-call route; in an export an unknown id reads "not in this export".
- **KIN-85, counts say what they count.** The traffic tab counts the session; the scope bar reads
  `N of M calls under this prefix`; the overview reads `N calls in this session`; the glance reads
  `calls in session`.
- **KIN-86, views keep place.** A view's scroll position is saved on the way out and restored on the
  way back, only while live is paused, keyed by session, filter, order and stretch.
Counts, base `1e9d720` to head: static checks 99 to 102, live probe 225 to 236, archive probe 40 to 43,
`render-web-ui.js` budget 2561 to 2398 (new probe bodies in `scripts/lib/list-room-probes.js` and
`list-room-cases.js`). `measure-web-ui.js --base`: 265 readings on each side, 18 failing at base, 2 at head
(`first rows huge` and `connected huge`, KIN-42's, on both sides).

**What was learned.**
- **Measured, the bar is 145px, not 120.** The list pane's top moved from 208px to 145px at 1440 on the list
  view and from 234px to 145px on the timeline, inside the 160px the criterion allows. The roadmap's "about
  120px" would need the tab bar folded into the toolbar, which nobody has asked for.
- **Pass 1 broke four readings by succeeding.** The freed height made the `big` path at 400px fit its pane,
  so the four `live big 400 … path … scrolled back` readings turned "too short", which is unjudged, not
  failed. `measure-cases.js` now unfolds the visits when the path pane does not scroll and those readings
  judge `stays` again.
- **A review finding in the counts.** The overview hint was cut at 1440. The axis now has a zero flex basis,
  so it gives way to the hint (`room <w> hint whole`).
- **A probe that read the wrong element.** QA's first read of the unknown-id case reported the previous
  call's text; it came from the hidden `#detail`, not from what was on screen. The check has to read what is on screen.
- **Two base probes were replaced, not deleted.** `rowLabels` asserted the row captions, which the design
  removes, and `toolbar one row` now asserts there are none; `shortcuts` asserted `#keys-button`, which moved
  into `⋮`, and `shortcuts in menu` checks it there.
- **An earlier item's `live header filter` probe was rewritten** to the session count, with a comment saying why: the tab
  now counts the session, which is what the criterion asked for.
- **Not in the diff, proposed.** `#/txn` has no visible way back (only browser Back and Esc, as the design
  says); a `← traffic` control is a UX call. The tools row's `⤢` and `⇥` glyphs are 13px; legible, and not
  judged by anyone. A live signal or marker still redraws the timeline once per message, carried from KIN-58.
- **Not verified.** The full `./gradlew build` (no Android SDK; CI ran it). The drawer's slide-in and its
  reduced-motion behaviour, which readings of state do not cover. `#/txn` exported at 400px. The divider
  dragged by keyboard in real Chrome (jsdom covers it). QA rendered in Chrome only.
- **How it was built.** An M item built from the architect's design note without a design review and
  without a UX pass, by the maintainer's choice, by a developer session with two engineer subagents. QA ran
  after the merge. QA's own red proofs: `top: 0` on `#timeline > .filter-note`, `visibility: hidden` removed
  from the closed drawer, an unlabelled scope count, and `openViewPlace` restoring nothing; each went red
  with the line named in the tickets.

cost: not recorded — built in the 2026-10-20/21 coordinated run, one developer session with engineer subagents, no design review, no QA before merge; QA after merge, 1 round

### Endpoint latency stats

**Done, 2026-10-21.** Two PRs: #115 (the daemon, KIN-74 to KIN-76), merge `0a5a1c9`, and #121 (the Stats page,
KIN-77), merge `d256174`. `build`, `release-swap`, `repo` and `web` were green on each head and each was merged
with `--admin` once they read pass by name; the main push run on `d256174` was a success. KIN-74 to KIN-77 are
Done on QA's evidence, one round per PR, run after each merge. The section's text is moved verbatim below; what
was built and learned follows it.

*Ticket label:* `item:endpoint-latency-stats`.

*Ticket label:* `item:endpoint-latency-stats`.

- **Endpoint templates**, built here and reused by the diff and the findings: numeric, UUID, long
  hex and long base64-ish segments become `{id}`, so `/users/123` and `/users/456` are one row.
  Overrides go in config for the paths the heuristic gets wrong.
- Per method + template: calls, attempts, 4xx / 5xx / transport-failure rates, p50/p90/p95/p99,
  max, mean bytes. **Below 20 samples p95 and p99 read `n<20`**, not a number — a percentile of
  six values is a confident-looking guess. Latency is per attempt, retried endpoints are marked,
  transport failures are counted and never averaged in.
- `GET /api/sessions/{id}/stats?filter=` with the shared grammar; MCP `route_stats`; a Stats page
  whose rows open Traffic with the filter term the daemon returned for that row — a superset of the
  row, and the opened view says so; live, throttled to once a second; precomputed into the exports
  so the offline file has them.
- **Naming:** a template is a **route** everywhere it appears — Stats column, API field, MCP tool,
  docs. "Endpoint" stays with the traffic page's chips, which already carry that label
  (`endpoints ▾`). The item keeps its title so the table and history still read.
- Must read the whole session: a test at 1001+ rows, for the reason `HarTest` exists.

**Decided 2026-10-17: keep both, named differently, and the superset link is acceptable.** No
`:inspector-model` change. Chips stay last-segment "endpoints"; daemon-side templates are "routes"
(see the naming bullet above). Each Stats row carries its route and the filter term it links with,
and the Traffic view opened from it says it may include paths the route keeps apart. This
unblocks the item, and through it the diff and the findings.

*Was an open decision — two notions of "endpoint".* *(Review, 2026-10-17. Blocked this item from
starting, and through it the diff and the findings, which reuse templates.)* The traffic page
already has one: endpoint chips come from `endpointShortcuts` in `:inspector-model`, keyed on the
**last path segment** and applied as `endpointFilterTerm` — `path:*/profile`, an anchored glob. The
overlay and `app.js` both use it. This item adds a second, daemon-side: `{id}` templates over the
whole path. So a chip `profile` (which counts `/users/123/profile` and `/orgs/9/profile` together)
and a Stats row `GET /users/{id}/profile` (which counts only the first) give different numbers for
what a reader will take to be the same endpoint.

There is a second problem the plan does not mention: **a Stats row cannot open Traffic filtered to
exactly that row.** The grammar is frozen and has no template term, and its `*` crosses `/` —
`globMatches` matches `/users/*/profile` against `/users/1/x/profile` and against
`/users/me/profile`, which the template would have kept apart. So "rows open Traffic filtered to
that endpoint" can only ever open a superset.

Options:

- **Converge the chips onto templates.** One notion everywhere, but it is a `:inspector-model`
  change, which the opening of this section rules out, plus an `app.js` mirror and an overlay
  change — and a template label does not fit a chip on a 360dp strip.
- **Converge the templates onto last segments.** Cheap, and wrong: last-segment grouping is a
  navigation shortcut, and latency per `profile` across unrelated resources is not a statistic
  anybody can act on.
- **Keep both and say why they differ.** Chips answer "take me there in one tap" and must be
  expressible as a filter; templates answer "how does this route behave" and need not be. The daemon
  owns the template rule and returns, on each Stats row, the template *and* the filter term it
  links with (`method:GET path:/users/*/profile`), so the page never derives either; the Traffic
  view opened from a Stats row says it may include paths the template keeps apart.

Recommended: the third. It keeps the section's no-model-change premise and puts the one honest
caveat where the superset is shown. What has to be decided before code is the naming — calling
both "endpoint" is what makes the disagreement read as a bug — and whether the superset link is
acceptable at all.

**What was built.** Four tickets, in two PRs, each by a fresh engineer subagent from the architect's note.
- **KIN-74, routes (PR A).** `archive/RouteTemplates.kt`: numeric, UUID and long hex segments become `{id}`, a
  `routeTemplates` list in `config.json` overrides the heuristic (read at start; `PUT /api/config` refuses bad
  entries whole, with a 400 naming them), and each route carries the filter term it links with.
- **KIN-75, stats (PR A).** `service/RouteStats.kt` and `RouteStatsService.kt`: an accumulator folded from the
  index's byte offset, an LRU of four, `GET /api/sessions/{id}/stats`, MCP `route_stats` and `inspector stats`.
  Nearest-rank percentiles; p95 and p99 withheld below 20 samples; transport failures counted and never a latency.
- **KIN-76, exports (PR A).** `routeStats` in `export.json` (`formatVersion` still 1), a `## Routes` table before
  `## Timeline` in the Markdown, the report embedded in the HTML. HAR byte-identical to base.
- **KIN-77, the Stats page (PR B).** `web/js/95-stats.js` and `web/css/85-stats.css`; a rail entry, one row per
  route, sort headers that send `sort=`, a row that opens Traffic on the daemon's term with a caveat, refresh at
  most once a second, and in an export the embedded report with rows inert.
Counts: daemon tests 299 to 357 (QA, PR A); web static checks 102 to 107, live probe 236 to 251 (1 n/a, as on
base), archive probe 43 to 48 (QA, PR B). `measure-web-ui.js --base`: 270 readings on each side, 2 failing at
head (`first rows huge`, `connected huge`, KIN-42's, on both sides), 6 at base.

**What was learned.**
- **The 160,016-row `huge` session, measured by QA at head.** Cold `GET …/stats` 0.331 s (budget 3 s), warm
  2.4 to 3.8 ms, 2.4 to 2.8 ms after 100 appended rows (budget 50 ms), `inspector stats` 0.36 s with the JVM
  start. The builder measured 0.238 s cold and 2.5 to 3.4 ms warm. Heap is about 17.4 MB per cache entry, about
  111 B per attempt against the note's 100: 11% over by QA, 14% by the builder. One contributor is the key memo
  the builder added, up to 50k entries per accumulator. Inside an order of magnitude, not blocking.
- **The page's first draw on that session was 26.6 s until the init order was fixed**, and is 413 ms now. This
  is why a first load straight to `#/stats` asks for the table before the whole-session read.
- **A route is a superset link, and some are very wide.** A route holding a character the grammar cannot write
  (`/p|q`) links with `path:/*`, and `*` crosses `/`, so it matches every path on the host. The two ports of one
  host (`127.0.0.1:8080` and `:9090`) share one term because the grammar has no port term. Both are the
  documented superset; the page's caveat is true for them, and QA checked that it reads so.
- **Reads go through the fold, not through `queryTransactions`.** That path clamps at 1000 rows, and the red proof
  was `expected <1203> but was <1000>` on REST and MCP, and `<2001>` against `<1000>` in the export, which is why
  the whole-session test exists.
- **Choices the note left open.** `sort=errors` orders by `errorRate` and no column offers it; the CLI's `--limit`
  defaults to 1000 as REST does; a bad entry in the file at start is named and skipped, and a `PUT` refuses a
  list holding any bad entry as a whole. The cache also rebuilds when the file's first 256 bytes change.
- **Row keys include the placeholder mask**, so a literal `{id}` path segment never shares a term with a templated
  one.
- **The HTML export's offline page moved to PR B**, because it needs `web/` and `scripts/`. PR A's `exports.md`
  describes only what PR A shipped, and PR B added the sentence.
- **A mutation QA could not make count.** Removing the export's `/stats` branch from QA's own copy only tripped
  `every file inlined: NO`, because the export embeds the daemon's own web files. The builder's proof, made in
  the daemon's tree, stands and was not repeated.
- **Not in the diff, proposed.** At 400px the route cell breaks a hostname mid-word (`api.qa.invali` / `d`, and
  `http://127.0.0.1:808` / `0`, which can read as port 808). The text is true; breaking at `.`, `:` or `/` would
  read better. A UX call.
- **Not verified.** The full `./gradlew build` and `-Pinspector=off` (no Android SDK; CI ran both). Safari,
  Firefox and a phone. A real session larger than the `huge` fixture. QA's hidden-tab case for refresh rests on
  the static check, not on a hidden Chrome tab.
- **How it was built.** An M item from the architect's note, without a design review, by a developer session with
  engineer subagents, in two PRs. QA ran after each merge. QA's own red proofs: floor rank for nearest rank,
  `TAIL_MIN_SAMPLES = 19`, a transport failure entering latency, the withheld cell drawn as a dash, and `oweStats`
  refreshing per row.

cost: not recorded — built in the 2026-10-20/21 coordinated run, architect note then a developer session with engineer subagents in two PRs, no QA before merge; QA after merge, 1 round each

### A sample demo flow that both rule families catch

**Done, 2026-10-22.** PR #162 merged as `b34ea8b`; `build`, `release-swap`, `repo` and `web` passed on the PR and the push run on `main` succeeded, including CI's Android and iOS compiles and the `-Pinspector=off` swap. KIN-128 is Done on QA's pass from a clean clone, one round, no blocking findings. The section's text is moved verbatim below; what was built and learned follows it.

*Ticket label:* `item:sample-demo-flow`. Added 2026-10-22 by the product owner, at the maintainer's
proxy's request; placed in "Order from here".

**The user and the problem.** A developer on a Mac trying Inspector from a clean clone, and the QA
agent verifying findings and detection. The sample's server (`sample/shared/kotlin`'s `DemoServer`)
answers GETs, one echo POST and a body with a token in it. Nothing in it sends a mutation twice or
puts a credential in a URL, so neither the payment-safety rules nor the query-string rule has
anything to find in a sample session. To watch them fire today you write your own app, which is what
a newcomer will not do and what QA should not have to.

**Why now.** Findings and detection come first in the order, and they are only worth as much as
someone can see them do. A demo that reproduces from a clean clone also gives QA the fresh session
its verification needs, without a hand-built fixture.

**Acceptance criteria.**
- The sample server gains a payment-shaped mutation route, and the sample's scripted run (the
  desktop sample's `-Dinspector.sample.autofire=true` today) sends one request body to it twice
  within the duplicate window with no idempotency key: a double submit. The same run makes one call
  with a token in its query string.
- On a fresh sample session, with the daemon's default config, `GET /api/sessions/{id}/findings`
  and MCP `list_findings` each return at least one Duplicate mutation finding at `high` that cites
  both calls, and at least one query-string token finding from detection. Its evidence is masked:
  the token's value appears nowhere in the response.
- The Findings page shows both on that session.
- A sample README (none exists; `sample/AGENTS.md` is the agent guide) says how to run the flow
  and what each finding should show, in steps a newcomer can follow from a clean clone.
- `-Pinspector=off` still builds the sample, and the release guard's check and self-test are unchanged.

**Out of scope.** Any change to the rules, their defaults or the daemon. The overlay, which has no
findings. The Android and iOS samples, unless the architect finds the scripted run is shared at no
cost. Whatever the demo route is called, Inspector learns nothing from it: the payment shape lives in
the sample app, as it would in a consuming one, and the rules match it through their generic
defaults.

**Surfaces.** The sample app and its docs. No library API, no noop twin, no golden file. **Size:** S.

**What was built and learned.** The sample server gains a payment-shaped `POST /checkout/payments` and a fake JWT in `/profile`'s query; the scripted run (`-Dinspector.sample.autofire=true`) and a "pay twice + token" button send the keyless double submit and the token call. QA followed `sample/README.md` verbatim on a clean clone with a second daemon on its own port and no `config.json`: one session of 50 calls, 7 findings (2 high, 2 medium, 3 info), exactly the README's table. Duplicate mutation (high, citing both calls) and JWT in a URL (high, masked `•••• HS256`) appear in REST, MCP, the CLI and the Findings page, and neither the token's segments nor the test card appear anywhere in the page, the replies or the exports (the archive's call rows hold them by design). Turning both token rules off through `PUT /api/config` makes the builder's check fail on the next read, with no restart; **with only `sensitive.jwt-in-url` off the check still passes**, because `sensitive.secret-in-url` reports the same `access_token` parameter, so a red proof has to turn off both. Nothing outside `sample/` changed: no rule, API, golden or `INTEGRATION.md` change.

**Left open.** The "pay twice + token" button pressed by hand on any platform; the Android and iOS samples running the flow (only desktop CIO ran it; CI compiled the others); and the flow has no automated guard in the repo (the builder's three check scripts would be the start of one).

cost: not recorded — built in the 2026-10-22 coordinated run, one engineer session, no QA before merge; QA after merge, 1 round

### Release 1.4.0

#### The release page and `ACCESS.md` name the same place to get the library

**Done, 2026-10-22.** PR #159 merged as `82887c1` (with row 13b). KIN-126 is Done on QA's pass, one round, no blocking findings. The text is moved verbatim below.

*Ticket label:* `item:release-notes-boilerplate`

**The user and the problem.** A consuming team reading a release page to add the dependency. The notes
`.github/workflows/release.yml` writes say the library comes "from GitHub Packages", that the registry
wants a token, and that `docs/ACCESS.md` leads with that. `docs/ACCESS.md` leads with Maven Central,
anonymous from 1.0.1, and keeps GitHub Packages as the older path. A reader who follows the release page
sets up a token they do not need.

**Why now.** Every release page from now on repeats it, and 1.4.0 is the next one. It is a few lines.

**Acceptance criteria.**
- The release page's library section names Maven Central first, with `mavenCentral()` and no token, and
  GitHub Packages as the alternative that needs one, in the same order and words as `docs/ACCESS.md`.
- It does not claim the version is on Central when the page is created. The Central job only stages the
  bundle and a person publishes it (`docs/decisions/release.md`), so the page says the version reaches
  Central once the maintainer publishes it, or the workflow's order makes the claim true. The architect
  picks which.
- No other sentence in the boilerplate contradicts `docs/ACCESS.md`, and the change lists each one checked.

**Out of scope.** Publishing automatically to Central, which `docs/decisions/release.md` rejects.
**Size:** XS.

**What was built and learned.** The decision was wording, not the workflow's order: the notes `release.yml` writes now open with "published to GitHub Packages and staged on Maven Central by the jobs beside this one", name Maven Central first with `repositories { mavenCentral() }` and no token, say that this version reaches Central once the maintainer publishes it (the tag only stages the bundle), and keep GitHub Packages as the older path with its token, pointing at `docs/ACCESS.md`. The daemon job creates the page without waiting for the library jobs, which is why the claim could not be made true by ordering. QA rendered the "Publish the release" step's heredoc at head and base with `VERSION=1.4.0` and compared the two workflow files as parsed YAML: every key, job and step is identical except that step's `run` text.

**Left open.** The hand-over's main push run and `pages` deploy were read by QA only as reported.

cost: not recorded — built in the 2026-10-22 coordinated run, one engineer session, no QA before merge; QA after merge, 1 round
