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
