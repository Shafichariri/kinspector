# Roadmap — what is next, and what is deliberately not

The live backlog. Everything else that looks like a plan in this repo is a **record**, not a list:
`implementation-plan.md` stops at Phase 4 and every phase in it shipped, `SIGNALS.md` documents
work released in 0.3.0, and `REPLAY.md` describes two built steps and two unbuilt ones. Nothing
has tracked what is next since 0.3.0, which is why several releases' worth of work happened with
no plan document at all.

There are **no `TODO` markers in the source**, by convention. If it is worth doing, it belongs
here with a reason; if it is not, it should not be a comment someone trips over later.

Numbering: this document uses **none**. Three schemes already exist — phases (0–4,
`implementation-plan.md`), capture letters (4a/4b/4c, `AGENTS.md` and `docs/decisions/capture.md`) and replay steps (1–4,
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
- **Edges not tried on hardware:** a cable pulled mid-session, an app backgrounded after an ordinary
  home-screen launch, two Inspector apps on one phone. Two apps would contend for one port; the
  second reports it, and each can be given its own `port`, but the bridge dials one port per
  daemon.
- **The phone's loopback is now a boundary.** Any process on the phone can reach the listener, the
  way any process on the Mac reaches the daemon — which includes asking a registered
  `ReplaySigner` to sign. Same class of exposure as today, debug-only, but the signer is the
  sharpest thing in it. Worth a paired-secret handshake if this ever leaves debug builds, which it
  must not.

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
| 4c — proxy capture | `docs/decisions/capture.md` | Not started. **Requires an explicit decision to start**: it turns the tool from a library into network infrastructure |
| 4b — report what we cannot see | `docs/decisions/capture.md` | Not designed; flagged there as possibly not worth it |
| HAR export | `implementation-plan.md` Phase 3 stretch | ~~Unbuilt~~ **Done, 2026-09-18.** `GET /api/sessions/{id}/har`, taking the same `filter` as the transaction list. Everything the format asks for and capture never saw is `-1`; attempts and redaction are disclosed per entry, because HAR has no field for either and silence reads as a claim |
| Whole-session export | Requested 2026-10-10 | **Done, 2026-10-10.** `export.html`, `export.md` and `export.json` under `/api/sessions/{id}/`, the web UI's export menu, and the `export_session` MCP tool. The HTML is the web UI itself in an archive mode, not a report written for the purpose. Building it found the HAR export silently stopping at 1000 rows |
| Signal payload redaction | `SIGNALS.md`, `schema.md` | `Signal.redacted` is reserved and always empty; the schema field is already there |
| Disconnected-transaction backfill | `schema.md` | Explicitly a v2 candidate |
| Landing-page sponsor link | Moved here 2026-10-18 from a marker comment in `site/index.html`, when phase B made such markers fail the build | The button links `https://github.com/sponsors/Shafichariri`, which shows a real page only once GitHub Sponsors (or Ko-fi) is set up. Point it at whichever page exists then |
| The path view — what was left out | `docs/decisions/shared-rules.md`, "the path, the waterfall and the axis band are one rule" | Web `path` tab **done, 2026-10-11; released in 1.3.0**. Deliberately deferred: the overlay (a phone has no width for a route, and its merged list is already the vertical version); an MCP `path` tool (`timeline` carries the same stream — build it when an agent visibly struggles); concurrency hints inside a column; a map view with each screen once and transitions counted; making `path` the landing tab, which stays `network` until the view has earned it |

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

  **Decided 2026-10-18: the three become `internal`.** The guard is now §5 → Phase I.

- **"A probe leaves no process behind" is a claim nothing tests.** *Ticket label:*
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

- **The signals sort and clamp exist twice, which breaks the layering rule phase D wrote down.**
  *Ticket label:* `item:signals-query-one-rule`. **Known violation, not yet fixed.**
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
  gets a release-notes line when it ships.

### Bugs found, not fixed

- **`ExportMarkdown.kt:158` prints the query twice.** The line is
  `inlineCode(txn.url + (txn.query?.let { "?$it" } ?: ""))`, but `NetworkTransaction.url` already
  includes the query (`docs/decisions/shared-rules.md` records that web's `urlOf` mirrors that, empty `?` included). Every
  call with a query string shows `…/path?a=1?a=1` in the Markdown export's **URL** line. Found while
  reconciling phase A; **out of phase A's scope** (daemon, not `app.js`) and not fixed. Fixing it needs a
  `ExportMarkdown` test with a query-bearing row, which asserts the URL appears once, and it is a visible
  change to the export, so it gets a release-notes line when it is fixed. Product owner to order it.
- **The model's `timeline()` ignores `seq` on same-millisecond signal ties.** Calls and signals are
  stable-sorted by `mono` alone, so two signals in one millisecond keep file order, and a signal and
  a call in one millisecond put the call first — whatever capture order `seq` recorded. That is the
  `onClick { tap; navigate() }` case `visits()` was fixed for. Fixing it changes every surface at
  once: the overlay, the `app.js` mirror, the exports and the shared mirror cases. It needs a total
  comparator (as `visits()` does with `seq ?: Long.MIN_VALUE`) and a decision about markers that
  carry `seq`, which today are interleaved by `mono` with `<=` only. **Phase E ships as "MCP
  equals the model"** (decided by the agent team on 2026-10-18 under the maintainer's delegation; the maintainer may overturn), so MCP inherits this rather than diverging from it. Not scheduled.

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

## 5. Next: the structure and architecture pass — planned 2026-10-18

**This comes first. Nothing in §6 starts until the items below marked "before §6" have landed.**
This section was inserted on 2026-10-18 ahead of what had been §5. The daemon and web UI plan is §6
now. Anything written before that date, including the review recorded inside that section, calls
it §5.

The reason is in one sentence of §6: "Order is by dependency, not by appeal." §6 adds six screens to
a page that is one 5,398-line function. It adds bulk control endpoints to an 800-line class where
the control header is checked by hand at eight call sites. It adds stats, diff and findings services
to a daemon with no layers. And all of it lands under web UI checks that CI has never run. Building
§6 on that means building it twice, or building it once and not being able to tell what broke.

**Source.** A read-only audit of the repository by the architect, measured at `b4e7319` on
2026-10-17. The ten findings are below in the audit's own order, because each item points back to
one of them:

1. CI never runs the web UI checks: `render-web-ui.js`, the archive probe, and the single-`fetch`,
   `[hidden]`, 11px and mirror audits. All of them run only when someone remembers to.
2. Five of the seven model↔`app.js` mirrors have no check: `pathScope`, `endpointShortcuts`,
   `timelineRuns`, duplicates and `HOP_BY_HOP`. `AGENTS.md` says "keep them in step" for each one,
   and for these five nothing enforces it.
3. **A live bug.** `SessionRepository.timeline()` is a second copy of `timeline()`. It sorts by
   `mono` alone, stably, after adding transactions, then signals, then markers. So at a tie a marker
   lands *after* the call it marks, where the model's rule puts it *before*, and signal ties ignore
   `seq`. The MCP `timeline` tool uses it, and so does the context half of the transaction tool.
4. `app.js` is 5,398 lines in one IIFE, `style.css` is 1,926 and `render-web-ui.js` is 2,530.
5. The control header is opt-in per route. `Server.kt` is one 800-line class with 30 routes, and
   eight call sites check the header by hand.
6. The daemon has no layers: about twenty files in one package, beside a lone `mcp/`. `McpTools.kt`
   is 938 lines.
7. `AGENTS.md` is 126 KB, and 92 KB of that is one flat list of decisions. Every agent session and
   every subagent loads all of it. Its Architecture section is stale.
8. The module graph is correct, and nothing checks it.
9. The `noop-ui` twin has no golden file. This is the gap already recorded under §3 → Guards.
10. Two overlay files are large: `ListChrome.kt` is 703 lines and `InspectorList.kt` is 758.

**The target shape**, as proposed by the architect. The items below carry it out. It is recorded
here as the goal, and the architect owns the details:

- **Daemon packages:** `http/` (routes and a control gate), `mcp/` (tools split by group),
  `service/` (stats, diff, findings, export, replay and process control, with no Ktor), and
  `archive/`, all over `:inspector-model`. Adapters (`http/`, `mcp/`) never import each other.
  `Main.kt` stays where it is.
- **Web UI:** ordered classic scripts, `web/js/NN-*.js`, listed in a manifest, `web/scripts.txt`.
  The export inliner, `index.html` and the probes all read that manifest. There is still no build
  step. The CSS splits into `tokens.css` plus one file per area.
- **Docs:** `AGENTS.md` becomes an index under 15 KB. The decisions move word for word into
  `docs/decisions/*.md`. Each module gets its own `AGENTS.md` with a `CLAUDE.md` symlink. A check
  enforces that each decision exists in exactly one place.

**Behaviour does not change, except in the phase E item** *(and, ruled 2026-10-18, the four web
drifts listed under phase A, which bring `app.js` back to rules already recorded)*. Every other item
is a move, a split or a new check. If one of them changes what a user, an agent or a consuming app sees, it has gone beyond
its scope. Stop and bring it back here.

**How every check here is accepted.** No new tools: no detekt, no ktlint, no npm dependency in the
shipped web UI. Each check is **proven to fail when broken**: break a copy of the thing it guards,
show the red run, then restore it. This is the repository's convention, and it matters most here
because this whole section is guards. A guard that has only ever been seen passing is not a guard.

### Decided 2026-10-18 — six questions from the handoff, all as recommended

The maintainer answered all six on 2026-10-18, each with the option the architect recommended.
They are recorded together here because several items depend on more than one of them.

1. **`app.js` becomes ordered classic script files**, `web/js/NN-*.js`, listed in `web/scripts.txt`,
   with no build step. The other option was one file with enforced sections. Files won because a
   diff to one area should be reviewable as that area. *Touches recorded decisions; see the phase F
   item.*
2. **`noop-ui`: `InspectorTheme`, `InspectorColors` and `LocalInspectorColors` become `internal`**
   in `:inspector-ui`. They are not mirrored into the noop. This closes the divergence recorded
   under §3 → Guards and unblocks the phase I item. **It is a public API removal**; see that item.
3. **Stats and findings rules live in the daemon's `service/` layer**, not in `:inspector-model`.
   This matches §6's premise of no model change and no new `app.js` mirror.
4. **A `:repo-checks` module holds the structural checks, with no detekt or ktlint.** It is a
   build-only module and is never published. *Touches a recorded decision; see the phase B item.*
5. **The client-name terms are held as a repository secret for CI**, and are never written into
   the repository. This keeps the recorded rule that the terms are not listed anywhere in the
   repo. CI catches a match **before merge, not before publication**: a branch CI can see is
   already on a public remote. So "the check belongs before the commit" stays the primary rule,
   with `scripts/check-client-names.sh --terms-file` run locally, and CI is the net under it.
   *Has a trap; see the phase B item.*
6. **The MCP `timeline` fix is approved as a bug fix.** An agent will see a different order at
   same-millisecond ties. That is the correction, not a regression.

### Order, and why

Ordered by value per hour of work, not by the audit's lettering. The letters are kept so the
handoff and the architect's notes still line up. They label items here and are not a numbering
scheme.

| Order | Item | Size | Must land |
|---|---|---|---|
| 1 | Phase E — one `timeline()`, so MCP agrees with the model | S | before §6 — a live bug |
| 2 | Phase C — every control route refuses without the header, by test | S | before §6 |
| 3 | Phase A — web checks and shared mirror cases in CI | M | before §6 |
| 4 | Phase B — module graph, dependency goldens, budgets, TODO, identity and name checks | S | before §6 |
| 5 | Phase G — `AGENTS.md` as an index, `docs/decisions/`, per-module guides | M | before §6 |
| 6 | Phase D — daemon packages; split `Server.kt` and `McpTools.kt`; layering check | M | before §6 |
| 7 | Phase F — `app.js` and CSS split behind the manifest | M | before 1a |
| 8 | Phase I — `noop-ui` golden file | M | after decision 2, now decided; does not gate §6 |
| 9 | Phase H — split the two large overlay files | S | later |
| 10 | Later — split `McpTest.kt` | S | later; added 2026-10-18, left over from phase D |

E goes first because it is small and it is the only item where something is wrong today: an agent
asking for a timeline gets an answer that disagrees with both UIs. C is next because it is small and
it is the security boundary. §6 adds two bulk control endpoints, and today they would be protected
only if somebody remembered to protect them. A comes before F on purpose. Splitting `app.js` is the
riskiest change to the web UI in this section, so it should happen with CI already watching
the mirrors and audits it could break. G comes before D and F because every agent run for every
later item pays for the 126 KB file, and because D and F each restate decisions. It is cheaper to
restate them in their new home than to move them twice. H is last because nothing in §6 touches the
overlay. The `McpTest.kt` split was added after it, out of phase D. It changes nothing anyone sees,
and the ratchet stops the file growing until then.

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

### Phase C — every control route refuses without the header, by test

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

**Release notes.** One line, for drifts 1 to 3, because a person can see them: *"Web UI: the repeat
count now counts calls, not rows; an expanded run of signals no longer collapses during live tail;
a marker dropped at the same millisecond as a call is drawn before it, as the overlay always has."*
Drift 4 and the `?` case get no line: they change only tie order and one edge of the duplicate key.
**Pending — not yet in any release.** When phase A ships, the `release-steward` puts this line in
the release notes and in a daemon-only `INTEGRATION.md` entry ("download the new daemon; leave your
coordinates alone"), as v40, v48 and v56 did for web-UI-only changes. Phase E's MCP tie fix is
announced too (decided by the agent team on 2026-10-18 under the maintainer's delegation; the maintainer may overturn; see the open questions below), right after the web-drift line, since it is the
same rule on a third surface: *"MCP: `timeline` and `explain` place a marker dropped at the same
millisecond as a call before that call, as both UIs do, and `explain` counts an earlier call at
that millisecond as leading to it; in 1.3.0 and earlier both came after."*
"1.3.0 and earlier" is right only if this ships in the next release after 1.3.0; the
`release-steward` re-checks it against `git tag` at release, here and in `DAEMON.md` §8.

### Phase B — module graph, dependency goldens, budgets, TODO, identity and name checks

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

### Phase I — `noop-ui` golden file

**Audit finding 9. Unblocked by decision 2. Does not gate §6.**

**This changes public API for `:inspector-ui`, and needs the noop twin and golden-file treatment
sized honestly.** `InspectorTheme`, `InspectorColors` and `LocalInspectorColors` become `internal`.
Nothing in this repository outside `:inspector-ui` uses them, the samples included, and no document
has ever told a consumer to. But they have been public in every published version, so a consuming
app that themed against them stops compiling. A removal can be undone only by a release, and §3 →
Guards called this "a decision rather than a tidy-up" for that reason. Two follow-ons:

- `docs/INTEGRATION.md` gets a version bump and a changelog entry that says what to *do*: "if you
  referenced these, copy the colours you need". The recorded rule is that any change affecting a
  consumer gets one.
- **Open for the human:** whether a removal from public API ships in a minor release.

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

### Phase H — split the two large overlay files

**Audit finding 10. Later.** `ListChrome.kt` (703 lines) and `InspectorList.kt` (758) are the
overlay's list and its chrome. Nothing in §6 touches the overlay, so the work can wait. The phase B
ratchet stops them growing in the meantime.

**For whom.** Whoever next changes the overlay's list.

**Acceptance criteria.** Each file is split along a seam the architect names. `ListRenderTest` and
`ListControlsTest` pass unchanged, and the 360dp screenshots are compared by eye before and after.
`InspectorOverlay.kt` keeps its name. The ratchet records the new, smaller budgets.

**Out of scope.** Any change to the list's behaviour or look.

### Later — split `McpTest.kt`

*Ticket label:* `item:structure-split-mcp-test`.

**Left over from phase D. Later.** `McpTools.kt` was split by tool group, and its test file was
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

### Deliberately not doing, in this pass

Each of these would look like a tidy-up while the code is being moved, and each is a recorded
decision or a known trap:

- **Moving `Main.kt`.**
- **Renaming `inspector-noop-stream/.../StreamSink.kt`**, which is load-bearing for the JVM facade
  name, **or either `InspectorOverlay.kt`**, which holds the same property by coincidence until
  phase I makes it checked.
- **Rebuilding a class-set diff between twins.** It was measured at 19, 19 and 4 spurious entries
  and rejected. The reasoning is in `docs/decisions/twins-and-api.md`.
- **Adding a JavaScript filter evaluator to the export.** That would be a second parser of the frozen
  grammar.
- **Adding a second network seam beside `source`.**
- **Splitting out `:inspector-okhttp`.** A separate module cannot see `Recorder` or `Redactor`.
- **Moving `UsbListenerRebindTest` to the JVM**, where it passes with the fix deleted.

### Open questions only the human can answer

- Whether making the three `noop-ui` theme types `internal` ships in a minor release, given that it
  removes public API from every published version up to now (phase I).
- ~~Whether the MCP `timeline` tie-order fix is announced as a behaviour change in the release notes,
  or only in the tool description (phase E).~~ **Announced in both the release notes and
  `DAEMON.md` §8** (decided by the agent team on 2026-10-18 under the maintainer's delegation; the maintainer may overturn). The tool description states the rule without a version.
- **Needs the maintainer: two security-boundary gaps, tracked privately.** Found while building
  phase C. The detail, the options and their breakage risks are in a private security advisory.
  Neither has a chosen fix, and neither belongs inside §5, which allows no behaviour change. Decide
  both before §6's bulk endpoints are built.

---

## 6. Next: the daemon and the web UI — planned 2026-10-17

*Numbered §5 until 2026-10-18, when the structure and architecture pass was inserted ahead of it;
the review below was written under that number.*

Six pieces of work, all host-side. **No SDK change and no `:inspector-model` change**: every new
rule runs in `:inspector-daemon` and reaches the page through the API, so none of it needs a
mirror in `app.js` to keep in step — the mirrors above are the drift this plan declines to add.
`INTEGRATION.md` is untouched, because nothing changes for a consuming app.

**None of §6 starts until the structure and architecture pass that precedes it has landed**; that
pass is §5, and phase F there must land before 1a in particular.

Order is by dependency, not by appeal: the page shell comes first because every later item adds a
screen, and building those into the current single page would mean building them twice.

| Order | Item | Size | Ships in |
|---|---|---|---|
| 1a | Web UI foundation: the shell — rail, hash routes, Sessions and Processes pages, bulk endpoints, writable config | L *(includes its share of the probe rewrite)* | 1.4 |
| 1b | Web UI foundation: room for the list — divider, overview line, one-row toolbar, density, `#/txn/{id}` | M *(includes its share of the probe rewrite)* | 1.4 |
| 2 | Endpoint latency stats | M | 1.5 |
| 3 | Session diff | L | 1.5 |
| 4 | Findings framework, and the payment-safety rules | L | 1.6 |
| 5 | Sensitive-data detection — detect, never redact | M | 1.6 |
| 6 | Web UI polish, against the finished screens | M | 1.7 |
| — | Mocking, fault injection, breakpoints | — | Deferred: needs the SDK |

Both halves of the foundation stay in 1.4: the shell is what every later page is built into, and
room for the list is the half a user notices on the screen they already use, so shipping the shell
alone would be a release whose visible change is a rail with pages that are mostly empty. The sizes
are the product owner's estimate; the architect re-sizes both before 1a starts. If 1b runs long it
can slip to 1.5 without blocking anything — nothing later depends on it.

**Review, 2026-10-17: six open decisions, each recorded under the item it affects. Four were decided
the same day.** Decided: *the probe rewrite* (split the foundation into 1a and 1b); *two notions of
endpoint* (keep both, named differently — this unblocks Endpoint latency stats, and with it the diff
and the findings); *where the cache lives* (a disk cache is acceptable under the stated conditions);
*regional defaults* (generic rules only). **Nothing in the table is blocked by an open decision
now**; all of it waits on the structure pass above. Still open, and neither stops an item from
starting: *truncated bodies* adds an acceptance criterion to the Session diff; *two notions of
duplicate* gates the Findings row badge.

### Web UI foundation

Two complaints from use drive it: actions on lists are one item at a time (killing five processes
is five confirms), and the main page stacks seven bands — top bar, tabs, two toolbar rows, filter
error, now strip, axis — before the first row.

**The rule for what moves:** anything about the session being watched stays on the traffic page;
anything about the machine, the archive or more than one session gets a page of its own.

**It lands as two deliveries** (decided 2026-10-17, see below). **The shell** is the rail and hash
routes, Sessions and Processes leaving the dialog, bulk selection and the bulk endpoints, and the
writable `config.json` — the last because Settings and the findings rules need it, and Settings is a
shell page. **Room for the list** is the divider, the overview line, the one-row toolbar, the `⋮`
export menu, density and `#/txn/{id}`. Each lands with the probes it moves.

- **A left rail**, collapsible to icons, over hash-routed pages: Traffic, Stats, Findings, Diff,
  Sessions, Processes, Settings. Hash routes keep the no-build-step, one-`index.html` shape and make
  the back button work. A rail rather than a second tab bar because it spends width, which is
  spare, rather than height, which is not.
- **Sessions and Processes leave the settings dialog**, and restart/stop leave the top bar for
  Processes. Settings keeps preferences only.
- **Bulk selection on every list.** Processes: checkboxes, select all, presets (`all servers`,
  `all except this one`, `stale`), `kill selected (n)` with the two-click confirm. MCP processes are
  **excluded from presets** and warned about — killing one cuts the editor off, the same trap the
  `pkill` note in the dialog already describes. Sessions: `delete selected`, `keep last N`,
  `older than`, and **pick two → compare**, which is the diff's entry point.
- **Bulk endpoints keep every single-item protection.** `POST /api/peers/kill {pids}` re-verifies
  each pid's identity at kill time and refuses the daemon's own; `POST /api/sessions/delete {ids}`
  refuses the active session. Both need `X-Inspector-Control`, both answer per item, because a
  batch that reports one status for five kills hides the one that failed.
- **Room for the list:** a draggable divider between list and detail (double-click resets, width
  remembered, collapse and full-width buttons); the now strip and the axis fold into one
  `overview` line; the toolbar becomes one row with endpoint chips behind `endpoints ▾` past about
  six; export goes into a `⋮` menu; a density preference; and `#/txn/{id}` opens one call full
  screen. Measure the band heights before and after — the bar is about 120px more for rows.
- **The daemon's `config.json` becomes writable**, behind `GET/PUT /api/config`. It exists today
  (`DaemonConfig` reads it, flags over file over defaults) but nothing writes or validates it;
  this adds validation and an atomic write, and keeps flags winning. Findings
  rules have to live where MCP can read them, and an agent and the page disagreeing about what
  counts as a finding is a worse bug than either alone. View preferences stay in `prefs`.

What must still hold: the export still works offline (new pages either read the embedded archive
or hide under `body.archive`); `fetch` is still called once across every file `web/scripts.txt`
lists; every new pane that sets `display` joins `auditHiddenPanes`; nothing new under 11px.

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

**Decided 2026-10-17: split into the shell and room for the list, each landing with its probes.**
The acceptance criteria at the end of this paragraph apply to both halves.

*Was an open decision — the probe rewrite is part of this item, and L understates it.* *(Review,
2026-10-17. Did not block the start; the architect should re-size before it begins.)*
`scripts/render-web-ui.js` is about 2,500 lines, and much of it is anchored on exactly the layout
this item moves. Its settings probe opens `#settings` through `#open-settings` and works through
`#peers` and the sessions list inside that dialog; the stop probe presses `#server-stop` in the top
bar and reads `#server-banner`; the toolbar probe reads `.row-label`s across the two rows of
`#toolbar`; the order probe drives `#order-newest`/`#order-oldest`; the now probe toggles
`#now-toggle`; the chip probe counts `#endpoint-chips .chip`; `#tabs .tab` is queried fifteen
times; and `auditDrawerCss` finds the `max-width` block that lays out `#panes`. The archive probe
carries its own list — `#session-delete`, `.daemon-group`, `#export-host` and others must be hidden
in an export — and every one of those may move or be renamed here.

One anchor is worth calling out because it encodes history, not just a selector. `probeToolbar`
asserts `railGone: !doc.getElementById('rail')`. The page **had** a left rail — the filter and a
Now panel — and it was taken out because it spent a fifth of the window on views it could not act
on (the Now panel alone was 1341px of it). The rail planned here is navigation, which applies on
every page, so it does not repeat that mistake. But the assertion must be rewritten with that
reasoning, not deleted to make the build pass, or the next person reads a removed guard as
permission to put view controls back in a sidebar.

Options: keep the item whole and accept that roughly a third of it is probe work; or split it into
**the shell** (rail, hash routes, Sessions and Processes leaving the dialog, bulk endpoints) and
**room for the list** (divider, overview line, one-row toolbar, density, `#/txn/{id}`), each landing
with its probes. Recommended: split, because each half then has a probe diff small enough to review.
Acceptance criteria for each half: every existing probe still runs, moved and not deleted,
with the before/after check count stated; every new page has a probe proven to fail when broken;
the archive probe opens each new page in an export and finds it either working from the embedded
data or hidden under `body.archive`.

### Endpoint latency stats

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

### Session diff

`GET /api/diff?a=&b=&filter=`, defaulting to the newest session against the previous one of the
same app; an optional `since:marker("…")` per side to compare one stretch of two runs.

- Endpoints added and removed; call count changed (`/transfer` 1× → 2×, loudest on mutations);
  status mix changed; latency regressed **only** when n ≥ 5 on both sides, the change is ≥ 25%
  **and** ≥ 50 ms — anything less is noise drawn as signal; request and response **shape** (JSON key
  paths and types) from bodies on disk.
- **A body capture did not keep is "unknown", never "field removed".** Same principle as
  `BodyOmission`: the diff must not assert what capture could not see.
- MCP `diff_sessions`, a Markdown form, and the Diff page.

Endpoint identity here is the **route** — the template decided under Endpoint latency stats — so
"endpoints added and removed" means routes, and the diff says so in its labels.

**Open decision — "unknown" must cover truncated bodies, not only omitted ones.** *(Review,
2026-10-17. Does not block the start; it is an acceptance criterion.)* The rule above is phrased
around bodies capture did not keep, which reads as `reqBodyOmitted`/`resBodyOmitted`. A body over
the cap is a different case: capture keeps a prefix, writes it to disk with a `*BodyRef`, sets
`reqBodyTruncated`/`resBodyTruncated`, and leaves the omission reason **null** — so a diff that only
checks omission treats it as captured. The cap is `bodyCaptureMaxBytes`, 256 KB by default and set
by the app, so two runs can even truncate at different points. A strict JSON parse of a cut prefix
fails, but a lenient or streaming key-path walk succeeds up to the cut and then reports every later
key as removed — the exact false claim the rule exists to prevent, on exactly the large responses
people diff.

Options: treat a truncated body as unknown; or walk its prefix for key paths that *are* present and
count them as "seen", never anything as removed. Recommended: the second is tempting and not worth
its subtlety — **shape comes only from bodies that are complete (not truncated, not omitted) and
parse strictly; everything else is unknown for that side**, and a key path is "removed" only when
the other side has at least one complete sample and no complete sample contains it. One adjacent
case belongs in the same criterion: under `Redaction.On()` a redacted value becomes the placeholder
string, so a number redacted on one side and not the other would read as a type change. Paths named
in `redacted[]` are not compared for type.

### Findings, and the payment-safety rules

**The framework**, shared with sensitive data: `{ruleId, category, severity, txnIds, callIds,
message, evidence}`, severities `high` / `medium` / `info`. **Computed on read, never written to the
archive**, so a rule edit applies to every session already recorded; cached per session against
row count and rule version. Rules are **data in the config file** — the defaults ship, and a user
can edit, disable or add rules from Settings without a release.

**This bends one stated constraint, and on purpose.** "Inspector learns nothing about app domain
concepts" stands for *capture*. These rules are user configuration over captured rows; the
defaults are generic conventions (`Idempotency-Key`), never a particular backend's.

Payment rules apply to mutations (POST/PUT/PATCH/DELETE):

| Rule | When | Severity |
|---|---|---|
| Duplicate mutation | same method + template + body hash, **different** `callId`, within a window (3 s), no idempotency key | high |
| Key reused, body changed | same idempotency key, different body | high |
| Automatic retry without a key | attempt > 1, no key | high |
| Ambiguous resubmit | a mutation ends in timeout or transport failure and is sent again as a new call | medium |
| Key missing on a payment path | path matches a configured pattern, no key | medium |
| Retry with a key | attempt > 1, key present | info — the safe case, shown for context |

Configurable: idempotency header names (default `Idempotency-Key`, `X-Idempotency-Key`), payment
path patterns in the existing `path:` syntax, the window. A request body that was not captured
makes the evidence read "body unknown"; a duplicate is never claimed without both bodies.

**The filter grammar stays frozen**, so there is no `has:finding` term — "flagged rows only" is a
view toggle. Changing the grammar is a separate decision.

`GET /api/sessions/{id}/findings?category=`, MCP `list_findings`, a Findings page grouped by rule,
a row badge, a section in the detail pane, and disclosure in every export (HAR's entry `comment`).

**Open decision — two notions of "duplicate" on one row.** *(Review, 2026-10-17. Does not block
the start; gates the row badge for this rule.)* The page already marks repeats: `duplicateGroups` in
`:inspector-model`, mirrored in `app.js`, keys on method + full URL + status + both byte counts,
requires a different `callId`, excludes headers, and chains members whose consecutive gap is within
a window — 3 s by default, editable as a *view preference* in `prefs`. It draws a `repeat` tag. The
Duplicate mutation rule keys on method + **template** + **body hash**, has no status in the key, and
its window lives in **config**. They will disagree in both directions: a resent POST whose first try
got a 500 and second a 200 is a finding and not a repeat; two GETs to different ids under one
template can never be a finding but two identical ones are a repeat. A row carrying a `repeat` tag
and a "duplicate" badge that mean different things, with two settings both called "window", will be
read as one feature that is broken.

Options: retire the `repeat` tag on mutations and let the finding speak for them; fold
`duplicateGroups` into the findings as an `info` rule; or keep both and make them unmistakably
different claims. Recommended: the last. The `repeat` tag stays what it is — a row-level
observation for every method, drawn as a tag, with its own name — and this rule is named **Duplicate
mutation** everywhere and drawn only as a severity badge. Two things to settle in the build: the
rule's window should chain consecutive gaps the way `duplicateGroups` does, or the two answer
"within 3 s" differently for a run of three; and the evidence may cite a coinciding repeat group by
id, which explains the overlap rather than hiding it. Retiring the tag would make the overlay and the
web disagree, since the overlay has no findings; folding it in means a model-side rule moving into
daemon config, which is a larger decision than this item.

**Decided 2026-10-17: a disk cache is acceptable, under the three conditions at the end of this
paragraph** — beside `sessions/`, never inside a session folder; masked evidence only; removed on
every deletion path; and retention's byte budget states whether it counts. Memory-only remains the
starting point until a measurement on a large real session says recomputing is slow; the disk option
is no longer ruled out, and building it needs no further decision.

*Was an open decision — where the findings cache lives.* *(Review, 2026-10-17. Did not block the
start; in-memory is safe by default. Gated any on-disk cache, here and in Sensitive-data detection.)*
"Cached per session against row count and rule version" says when the cache is valid, not where it
is. It must not be inside the session folder. The recorded rule is narrower than "nothing in a
session folder" — the daemon writes `index.jsonl`, `meta.json` and `bodies/` there by design — it is
`export_session`'s rule that **derived** files go beside `sessions/`, because retention sizes a
session by walking its whole folder (`sessionBytes`), so a derived file inside it inflates the
session it describes and is pruned with it. A findings cache is derived in exactly that sense.

Options: memory only, recomputed after a daemon restart; or a file per session beside `sessions/`
(for example under the data directory's own `cache/`). Recommended: **memory only** until a
measurement on a large real session says recomputing is slow, because the sensitive-data scan is
the one with a real cost (it reads bodies) and nobody has measured it. If a disk cache is ever
added, three conditions come with it: every deletion path — retention, `sessions/delete`, clear-all
— removes the entry with the session; it holds **masked** evidence only, or it becomes an unpruned
second copy of the credentials the detector found; and retention's byte budget states whether it
counts.

### Sensitive-data detection — detect, never redact

Redaction stays off by default; this is a smoke detector, not a sprinkler. Rules are editable and
addable like the payment rules, in two kinds:

- **Value rules**, validated rather than bare regex: card numbers (13–19 digits, **Luhn**; known
  test PANs drop to `info`), IBAN (length checked against the full **ISO 13616** country table,
  and **mod-97**), JWTs and `token=`/`password=`/API keys in query strings, and credentials over
  `http://`. Email and phone exist and default **off** — they are noise in almost every session.
  **No national-ID value rule ships by default**; a team whose app handles one adds its own rule in
  config, like any other.
- **Field-name rules**, matched on JSON key paths and query parameters whatever the value:
  `national_id`, `id_number`, `tax_id`, `first_name`, `last_name`, `full_name`, `date_of_birth`,
  `passport_number`, `iban`, `card_number`, `cvv`, `cvc`, `pin`, `otp`, `password`, and so on —
  case- and separator-insensitive (`firstName`, `first-name`). The identity names are a neutral
  spread, not one country's.

Severity rises when the destination is **not** in a configured first-party host list: a card
number going to an analytics host is the real alarm. Fields listed in `redacted[]` are skipped.

**The detector must not become the leak.** Evidence is stored and shown **masked** —
`•••• 4242`, `GB82 •••• 5432` (the IBAN registry's own example) — with the location and JSON path; never the value, in the UI, MCP
or any export. Bodies are scanned when findings are asked for, cached, never on ingest. A finding
can be dismissed per endpoint and path, with a reason, saved to config.

The cache for this scan follows *where the findings cache lives*, under Findings.

**Decided 2026-10-17: generic rules only, no region packs.** The IBAN rule reads the full ISO 13616
length table; no national-ID value rule ships by default, and a user who needs one adds it through
config; field-name rules use a neutral spread of identity names. The rule list and masking example
above have been rewritten to match.

*Was an open decision — regional defaults.* *(Review, 2026-10-17. Did not block the start of the
framework; gated the rule set that ships as default.)* The first draft of this item carried one
country's national-ID value rule, that country's IBAN length called out by name, its field name for
the ID, and a masking example in its IBAN format. Those are country rules, not a client's name, so
they did not break the no-client-names rule. But they would have been the **only** regional
defaults in a public repository, and a single country singled out in the shipped rules narrows who
the consuming app might be about as well as a name would. The constraint that matters — one real
consuming app handles a national identity number with a known structure — is worth carrying; the
country is not.

Options were: ship as drafted; ship **generic structure only** (Luhn cards, IBAN with mod-97 and the
full length table, field names for national identity numbers across many regions, no national-ID
value rules); or generic structure plus **optional region packs**, off by default, with more than
one region represented from the first release. The review recommended packs, with generic-only as
the fallback if no second region could be specified properly. The maintainer chose generic-only:
the IBAN rule needs no pack once it reads the whole table, and a national-ID rule is exactly the
kind of thing user-editable rules exist for, so a pack would be shipped maintenance for a case the
config already covers.

### Web UI polish

The notes from use, applied once the new screens exist; the first human design pass the web UI
has had (still listed under open questions); `g s` / `g f`-style keys for the new pages; and empty
states that say why they are empty, as the traffic list already does.

### Deferred: mocking, fault injection, breakpoints

All three need code on the device. Capture is a client plugin, so the daemon reads the transcript
after the call — it is never standing in the doorway. `MockEngine` is a Ktor *client* engine;
breakpoints pause the app's own request. They wait for a deliberate SDK phase, and carry two
decisions with them: mocking turns an observer into an actor (it would need a visible "mocks
active" state, mocked rows marked in the archive, and a noop twin), and breakpoints block the
app by design, which the efficiency contract forbids outside an explicit debug mode. The config
file, the endpoint templates and filter-grammar rule matching built above are the parts that
phase will reuse.

### Every phase

Daemon tests, `render-web-ui.js` and `render-web-ui-archive.js` before each push, and every new
guard proven to fail when broken. `DAEMON.md` for new endpoints and the config file; the right
`docs/decisions/` file for any decision that someone could later "fix". No client names in rule defaults, fixtures or
commits.

---

## What this document does not overturn

The constraints in `docs/decisions/`, indexed in `AGENTS.md` → "Non-obvious decisions — do not
'fix' these", and `implementation-plan.md` §1, stand except where this document says otherwise.
In particular: capture stays Ktor-client-only until 4c is deliberately chosen, the filter grammar
is frozen for v1, production safety remains two layers and non-negotiable, Inspector learns
nothing about app domain concepts, and no adapter modules ship.
