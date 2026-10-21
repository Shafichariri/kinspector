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
  *KIN-42, deferred, and the unplaced live-timeline defect stay open in `ROADMAP.md` → §3 → Guards.*
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
