# Roadmap — what is next, and what is deliberately not

The live backlog. Everything else that looks like a plan in this repo is a **record**, not a list:
`implementation-plan.md` stops at Phase 4 and every phase in it shipped, `SIGNALS.md` documents
work released in 0.3.0, and `REPLAY.md` describes two built steps and two unbuilt ones. Nothing
has tracked what is next since 0.3.0, which is why several releases' worth of work happened with
no plan document at all.

There are **no `TODO` markers in the source**, by convention. If it is worth doing, it belongs
here with a reason; if it is not, it should not be a comment someone trips over later.

Numbering: this document uses **none**. Three schemes already exist — phases (0–4,
`implementation-plan.md`), capture letters (4a/4b/4c, `AGENTS.md`) and replay steps (1–4,
`REPLAY.md`) — and a fourth would only collide. Items are referred to by name.

---

## 1. Bringing the overlay closer to the web UI

**This overturns a recorded decision.** `SIGNALS.md` → "Out, deliberately" says:

> **No overlay changes.** v1 is host-side only… The in-app pill and inspector screen stay
> traffic-only.

That was right when it was written: signals were a host-side feature and the overlay had no part
in them. It is worth reopening now because of one fact that the decision does not mention —
**the overlay already holds the data.**

`Inspector.signals` is a public `StateFlow` sitting beside `transactions`, `markers` and `latest`
(`Inspector.kt:48-57`), and `Signal.data` lives in device memory. The overlay module references
none of it; `inspector-ui` depends on `:inspector-core` alone, with no HTTP client. So the entire
signals story on mobile is **unimplemented UI, not a missing capability**.

### The line that actually divides the two surfaces

The overlay sees the current process's ring buffer. The web UI reads the daemon's archive. That
difference — and not the size of the screen — decides what can be ported:

| Cannot be ported without giving the overlay daemon access | Why |
|---|---|
| Session picker, session history, delete, clear-all | There is one session in memory, and no session concept in the module |
| Replay | Needs the daemon's replay endpoint and signer |
| Peers, daemon stop/restart, MCP probe | The daemon is the subject |
| "recorded on device, but no body reached the archive" | An archive-specific diagnosis |
| Anything older than the ring buffer | It is gone |

Everything below this line is data the overlay already has.

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
   feature. On by default when the session has any, one tap off.

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

### An age from `mono`, if it is ever worth a public API

The overlay's ages are wall clock, which is wrong across a clock change mid-session. `mono` cannot
jump and the overlay runs in the very process that recorded it — but `mono` is measured from an
origin private to `:inspector-core`, so `:inspector-ui` cannot convert one to an age at all. A
`TimeSource.Monotonic.markNow()` taken in the UI module is a different origin and produces ages
wrong by however long the process had been running.

Fixing it means exposing the origin, or a "mono now" reading, as public API on `Inspector` — both
modules plus the golden file, to carry an age column. Not obviously worth it; recorded so the next
person does not re-derive why the obvious clock is not being used.

### Converging the two signal groupings

`signalGroups` in `:inspector-model` and `browserGroups` in `app.js` both group observations by
`(tag, name)`; the web one additionally expands a cache snapshot's `items[]` into one row per
entry, so it groups *entries* where the model groups *observations*. Lifting the expansion into the
model would make them one rule with a mirror, the way `timeline` and `endpointShortcuts` are.

It is not obviously worth it. The expansion reads `items[]`, `id`, `storage`, `scope`, `expired`
and `kind` out of a payload — all app-defined conventions rather than schema — and
`:inspector-model` is the module that is supposed to know nothing about what a payload means.
Doing it there would be the first place Inspector encoded a convention about payload *contents*.
Worth deciding deliberately rather than drifting into.

### Bigger, and worth doing after the above

- **Time axis with drag-to-narrow.** Derived purely from `mono` on rows the overlay already has.
  On a phone this is a gesture surface rather than a pointer one, so it is a design question as
  much as an implementation one.
- **Per-screen waterfall.** Needs signals (stage 1 above) first.
- **"For AI" bundles.** `transactionBundle` and `sessionBundle` are pure functions over data the
  overlay holds. The only part that does not travel is the footer naming MCP calls, which
  references a session id the overlay would have to learn from the sink. Worth it — handing a
  finding to an agent from the device is arguably more useful than from the browser, because the
  device is where you noticed it.

### Not worth porting

- **Keyboard shortcuts.** There is no keyboard.
- **A JSON tree viewer.** Neither surface has one, and mobile's pretty-printer is already the
  better of the two: it formats truncated and malformed bodies, which are exactly the ones worth
  reading (`Formatting.kt`). Web parses or gives up.

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

**This overturns two recorded decisions.** `implementation-plan.md` §1, under "do not
re-litigate", fixes **"Simulator / emulator only for v1"**, and its non-goals list names
**"physical devices"** outright.

Both are worth reopening, and the same document is the reason why: it also lists "charts/timelines
in web UI" and "request rewriting/replay" as v1 non-goals, and **both were built anyway**. These
are not permanent positions, they are positions nobody has revisited on the record.

The code was written in anticipation. `StreamSink` already takes `host` as a constructor
parameter, and both platforms recorded device-versus-emulator on the grounds that "recording the
truth means the archive is still accurate the day physical devices are added" — which turned out
to be the right instinct and the wrong implementation, since the detection under that comment was
answering "physical device" for every emulator. Both of the first two items below are now done;
the comment is gone with them.

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

### iOS hardware — the one that needs a decision

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

**Neither sample has run on physical hardware.** A cutout, a vendor skin, a gesture bar and a cable
running `adb reverse` are all still unexercised, and no Inspector build has ever been on a real
phone of either kind.

---

## 3. Carried over, unbuilt

Not new, and not forgotten. Collected here because they were spread across nine documents.

| Item | Where it is specified | State |
|---|---|---|
| Replay step 3 — edit before replay | `REPLAY.md` §7 | ~~Specified~~ **Done, 2026-09-19.** Method, URL, headers and body, above the tabs of the request they describe. It also uncovered that the plain replay button had been throwing `NotFoundError` on ordinary rows since the tabbed detail pane arrived — `querySelector` finds a grandchild and `insertBefore` will not take one |
| Replay step 4 — template generators | `REPLAY.md` §7 | Specified, including the generator set. Blocked on two of `REPLAY.md` §8's questions for the backend team: the clock-skew window decides whether a generated curl needs a validity label, and nonce reuse decides whether Run is idempotent |
| 4c — proxy capture | `AGENTS.md` | Not started. **Requires an explicit decision to start**: it turns the tool from a library into network infrastructure |
| 4b — report what we cannot see | `AGENTS.md` | Not designed; flagged there as possibly not worth it |
| HAR export | `implementation-plan.md` Phase 3 stretch | ~~Unbuilt~~ **Done, 2026-09-18.** `GET /api/sessions/{id}/har`, taking the same `filter` as the transaction list. Everything the format asks for and capture never saw is `-1`; attempts and redaction are disclosed per entry, because HAR has no field for either and silence reads as a claim |
| Signal payload redaction | `SIGNALS.md`, `schema.md` | `Signal.redacted` is reserved and always empty; the schema field is already there |
| Disconnected-transaction backfill | `schema.md` | Explicitly a v2 candidate |

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
  recorded in `AGENTS.md`; none of them changed the `core` golden file by a byte, which is how they
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
  guard is the only thing holding it. Recorded in the file itself and in `AGENTS.md`.

  A raw public-class-set diff across each pair — the obvious generalisation, and what the report
  suggested — was built, measured and rejected: 19 spurious entries for `core`, 19 for `ui`, 4 for
  `stream`, because Kotlin `internal` is JVM-public and the canary is real-only by design. The
  reasoning is in `AGENTS.md`; do not rebuild it without reading that.

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

- **`:inspector-noop-ui` still has no golden file, and already diverges.** Found while writing the
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

### Open questions

- **The 150 ms conflation window** and **the 2 MB signal ring budget** are guesses, and
  `SIGNALS-CHECKLIST.md` asks that they be measured against one real session before
  `INTEGRATION.md` presents them as defaults worth keeping. Per-tag retention caps have already
  left this list; these two have not.
- **Redirect collapsing** on engines other than CIO is unresolved for the target app's engine.
- **The Auth0 adapter** compiles against the real SDK and has never run against a live tenant.
- **Nobody has judged how the web UI looks.** It renders correctly; that is a different claim.

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

## What this document does not overturn

The constraints in `AGENTS.md` → "Non-obvious decisions — do not 'fix' these", and
`implementation-plan.md` §1, stand except where this document says otherwise. In particular:
capture stays Ktor-client-only until 4c is deliberately chosen, the filter grammar is frozen for
v1, production safety remains two layers and non-negotiable, Inspector learns nothing about app
domain concepts, and no adapter modules ship.
