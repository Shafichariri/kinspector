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
Items are placed by position in Order from here only; nowhere else names a place.

Finished items are in `ROADMAP-DONE.md`, moved there word for word with their dates; §4 went
whole, which is why the sections below run from §3 to §5. The release-notes lines owed to the
next release are in `UNRELEASED.md`.

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

### ~~The substantial piece: signals in the overlay~~ — all four stages done by 2026-09-19, see `ROADMAP-DONE.md` → §1

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

### ~~First: the detection is wrong~~ — done, shipped in 0.8.0; see `ROADMAP-DONE.md` → §2

### ~~Android over USB~~ — done, shipped in 0.8.0; see `ROADMAP-DONE.md` → §2

### ~~iOS hardware~~ — done over USB, 2026-09-24; released in 1.2.0

Done 2026-09-24, see `ROADMAP-DONE.md` → §2 → "iOS hardware". What is still open:

- **Edges not tried on hardware:** a cable pulled mid-session, an app backgrounded after an ordinary
  home-screen launch, two Inspector apps on one phone. Two apps would contend for one port; the
  second reports it, and each can be given its own `port`, but the bridge dials one port per
  daemon.
- **The phone's loopback is now a boundary.** Any process on the phone can reach the listener, the
  way any process on the Mac reaches the daemon — which includes asking a registered
  `ReplaySigner` to sign. Same class of exposure as today, debug-only, but the signer is the
  sharpest thing in it. Worth a paired-secret handshake if this ever leaves debug builds, which it
  must not.

### And the thing that blocks actually trying any of it

The Android app module and the Xcode project were added 2026-09-18, see `ROADMAP-DONE.md` → §2.

**Neither sample has run on physical hardware.** A cutout, a vendor skin, a gesture bar and a cable
running `adb reverse` are all still unexercised, and no Inspector build has ever been on a real
phone of either kind.

---

## 3. Carried over, unbuilt

Not new, and not forgotten. Collected here because they were spread across nine documents.

| Item | Where it is specified | State |
|---|---|---|
| Replay step 4 — template generators | `REPLAY.md` §7 | Specified, including the generator set. Blocked on two of `REPLAY.md` §8's questions for the backend team: the clock-skew window decides whether a generated curl needs a validity label, and nonce reuse decides whether Run is idempotent |
| 4c — proxy capture | `docs/decisions/capture.md` | Not started. **Requires an explicit decision to start**: it turns the tool from a library into network infrastructure |
| 4b — report what we cannot see | `docs/decisions/capture.md` | Not designed; flagged there as possibly not worth it |
| Signal payload redaction | `SIGNALS.md`, `schema.md` | `Signal.redacted` is reserved and always empty; the schema field is already there |
| Disconnected-transaction backfill | `schema.md` | Explicitly a v2 candidate |
| Landing-page sponsor link | Moved here 2026-10-18 from a marker comment in `site/index.html`, when phase B made such markers fail the build | The button links `https://github.com/sponsors/Shafichariri`, which shows a real page only once GitHub Sponsors (or Ko-fi) is set up. Point it at whichever page exists then |
| The path view — what was left out | `docs/decisions/shared-rules.md`, "the path, the waterfall and the axis band are one rule" | Web `path` tab **done, 2026-10-11; released in 1.3.0**. Deliberately deferred: the overlay (a phone has no width for a route, and its merged list is already the vertical version); an MCP `path` tool (`timeline` carries the same stream — build it when an agent visibly struggles); concurrency hints inside a column; a map view with each screen once and transitions counted; making `path` the landing tab, which stays `network` until the view has earned it |

### Guards

- ~~**The stream pair had no golden API file.**~~ Done 2026-09-18, see `ROADMAP-DONE.md` → §3 → Guards.

- ~~**The twin pair's JVM file facades had different names.**~~ Done 2026-09-22, see `ROADMAP-DONE.md` → §3 → Guards.

- ~~**`:inspector-noop-ui` still has no golden file, and already diverges.**~~ Done 2026-10-21, see `ROADMAP-DONE.md` → §3 → Guards. *Ticket label:* `item:structure-phase-i`.

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

- ~~**The signals sort and clamp exist twice, which breaks the layering rule phase D wrote down.**~~
  Done 2026-10-19, see `ROADMAP-DONE.md` → §3 → Guards. *Ticket label:* `item:signals-query-one-rule`.
  **Open: KIN-42. Deferred by the maintainer on 2026-10-19; undeferred on 2026-10-21 (order row 21).** On a session of about 160,000 calls, the
  web UI shows a false empty state ("waiting for an app" / "nothing recorded") for about 25 seconds
  before the first rows appear, and the live socket connects only after. KIN-34 introduced it, when the
  filter-note counts began reading the whole session. The fix is to draw the first page at once and
  read the whole-session copy in the background (ACs in the ticket); it is not scheduled. The item is
  Done without it by that decision, not because the defect is closed.
  **For the product-owner, unplaced:** a live call never redraws the timeline. The `txn` branch of the
  socket's `onmessage` never calls `renderTimeline` (found by the engineer building KIN-41, true at `e1711a3` and after), so a call
  appears on the timeline only on the next redraw; with the pane at the end it is "followed" only in the
  sense that nothing moves. It predates this item and is not in KIN-42; it needs placing in the order.
  **Placed 2026-10-19 by the product owner** in `item:web-live-calls-reach-every-pane`, §3 → Bugs
  found, not fixed, after QA on `item:web-live-rows-filtered` found it again with two siblings.
  **Done 2026-10-21** in that item.

- **A check that finds nothing to check passes.** *Ticket label:* `item:guard-probe-finds-nothing-fails`.
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

- **QA's live-traffic Chrome driver is still in a scratch directory.** *Ticket label:*
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

- **Phase I gave the ui pair three guards that the core and stream pairs lack.** *Ticket label:*
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

- **No pair guards a public declaration that exists only on iOS or Android.** *Ticket label:*
  `item:guard-twin-native-surface`. Phase I's design named it as its first risk, and its QA and
  `docs/STATUS.md` → Not verified carry it.

  **For whom.** A consuming team shipping iOS or Android. Every parity test reads JVM output. A
  public function added to `iosMain` or `androidMain` of a real module, and not to its twin, passes
  every pair's test. The team finds out when their release build fails to compile on that platform
  alone, which is how the facade-name break reached iOS (`twins-and-api.md`).

  **Acceptance criteria.**
  - On every pair, a public declaration present in one module's `iosMain` or `androidMain` and absent
    from its twin fails a check that CI runs. The iOS half runs on the macOS job.
  - Proven red on each platform with such a declaration added, and green again once it is removed.
  - The architect chooses the mechanism, for example the Kotlin Gradle plugin's own klib ABI dump or
    a scan of each platform's compiled output. A choice that adds a dependency or changes a
    `gradle.lockfile` says so, and §5's "no new tools" is the bar it argues against.

  **Out of scope.** Binary compatibility between released versions. The JVM half, which phase I and
  `item:guard-twin-pairs-alike` cover. Surfaces: library API guards only, with no consumer-visible
  change. **Size:** M, until the architect sizes the mechanism.

### Bugs found, not fixed

- **The model's `timeline()` ignores `seq` on same-millisecond signal ties.** Calls and signals are
  stable-sorted by `mono` alone, so two signals in one millisecond keep file order, and a signal and
  a call in one millisecond put the call first — whatever capture order `seq` recorded. That is the
  `onClick { tap; navigate() }` case `visits()` was fixed for. Fixing it changes every surface at
  once: the overlay, the `app.js` mirror, the exports and the shared mirror cases. It needs a total
  comparator (as `visits()` does with `seq ?: Long.MIN_VALUE`) and a decision about markers that
  carry `seq`, which today are interleaved by `mono` with `<=` only. **Phase E ships as "MCP
  equals the model"** (decided by the agent team on 2026-10-18 under the maintainer's delegation; the maintainer may overturn), so MCP inherits this rather than diverging from it. Not scheduled.

*The four entries below were found on 2026-10-18, outside any item: the first by the architect and
QA while verifying `item:export-md-query-once`, the other three by the architect while designing
`item:signals-query-one-rule`. Placed by the product owner the same day. Three are items and one is
not worth doing. Where each sits in the order is in §5 → "Order from here".*

- ~~**The web traffic list stops at 1000 calls and does not say so.**~~ Done 2026-10-19, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:web-transactions-paged`.

- ~~**Live rows ignore the filter until the next reload.**~~ Done 2026-10-19, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:web-live-rows-filtered`.

- ~~**Row labels drop a non-default port.**~~ Done 2026-10-21, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:row-label-port`.

- **Two path-view tooltips drop an empty query's `?`.** **Not worth doing, 2026-10-18.**
  `web/js/50-path.js:338` and `:458` test `txn.query ?` for truth, so a call to `/a?` shows `/a` in
  the tooltip. `docs/decisions/shared-rules.md` records that `urlOf` keeps an empty `?`, but that
  rule is about **URLs**, which are evidence: what was sent, copied and replayed. These two are
  labels for a path chip, and a label may drop the query entirely, as `item:row-label-port` says.
  Clicking either one selects the row, and the detail pane, the copies and every export show the URL
  with its `?`. Making the tooltips agree would spread the empty-`?` rule to two more call sites
  that carry no evidence. If someone edits those lines for another reason, `txn.query != null` is
  the one-word change.

*The six defects below were found by the signals verify panels on 2026-10-18, while verifying
`item:signals-query-one-rule`. They are should-fixes, not blockers of that item; two were widened
or brought into view by its tickets, and the entries say which. Placed
by the product owner on 2026-10-19. None joins the signals item, which is closing. One is its own
item, here. The other five became acceptance criteria on items that already rebuild the code
involved:*

| Defect | Goes to | Why there |
|---|---|---|
| A live row arriving during the whole-session read is lost from the copy | `item:web-transactions-paged` | that item owns the whole-session read; KIN-34's paging widened the window |
| `refreshCurrent` has no session guard | **its own item**, below | no open item touches `/current` |
| ~~KIN-33's sticky list-pane layout has no committed guard~~ **Done 2026-10-21** (KIN-78; `ROADMAP-DONE.md` → §6 → "Web UI foundation — 1b, room for the list") | 1b, `item:web-foundation-list-room` | 1b rebuilds the bands above the list, so the guard must exist before 1b moves them |
| ~~List, timeline, waterfall and path share one scroll position~~ **Done 2026-10-21** (KIN-86; `ROADMAP-DONE.md` → §6 → "Web UI foundation — 1b, room for the list") | 1b, `item:web-foundation-list-room` | 1b rebuilds the list pane, and 1a's hash routes give each view an address to remember a position by |
| The export's transactions route ignores `offset`/`limit` | `item:web-transactions-paged` | that item's export criterion cannot be checked without it |
| ~~The "Lost the daemon" banner shows only on the first close after an unplanned drop~~ **Done 2026-10-21** (KIN-71; `ROADMAP-DONE.md` → §6 → "Web UI foundation — 1a, the shell") | 1a, `item:web-foundation-shell` | 1a rebuilds the daemon controls and the top bar the banner belongs to |

- ~~**One session's "now" values can land under another.**~~ Done 2026-10-19, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:web-current-session-guard`.

*The eight defects below were found on 2026-10-19 by the first verify panel on
`item:web-transactions-paged`. Each is older than that item's diff (base `c21aaf0`) or outside its
criteria, so none joins its fix round. Placed by the product owner the same day. One is a new item,
which takes two of them. Five became acceptance criteria on items that already rebuild the code
involved. One is not worth doing. The maintainer placed the new item on 2026-10-19, straight after
`item:web-current-session-guard` (§5 → "Order from here").*

| Defect | Goes to | Why there |
|---|---|---|
| 1. Under a filter, the list never says it holds only some of the matches | **new item**, `item:web-list-says-what-it-holds` | the list is the one pane without the note; no open item draws it |
| 2. The header and the tab count go stale under live tail | `item:web-live-rows-filtered` | that item rewrites what a live row does to the lists; the count is the same decision |
| 3. A brushed stretch below the newest 1000 says "no calls in the selected stretch" | **new item**, with 1 | same cause: the list holds part of what it counts and says nothing |
| 4. A whitespace-only filter is a filter on the page and not on the daemon | `item:web-live-rows-filtered` | that item adds a page-side "is there a filter" test, which must agree with the daemon |
| ~~5. The repeat mark is cut to `twic…` at 400px~~ **Done 2026-10-21** (KIN-80; `ROADMAP-DONE.md` → §6 → "Web UI foundation — 1b, room for the list") | 1b, `item:web-foundation-list-room`, under (b) | (b) already owns what a row keeps at 400px |
| ~~6. The count is off-screen below about 600px~~ **Done 2026-10-21** (KIN-70; `ROADMAP-DONE.md` → §6 → "Web UI foundation — 1a, the shell") | 1a, `item:web-foundation-shell`, under (e) | (e) covered the controls, not the count; now it covers the count too |
| ~~7. Four numbers for one session on one screen~~ **Done 2026-10-21** (KIN-85; `ROADMAP-DONE.md` → §6 → "Web UI foundation — 1b, room for the list") | 1b, under (f3) | (f3) already owns counts that read as disagreeing; the boundary-row idea goes to the new item |
| 8. The export answers `limit=0` with 0 rows | not worth doing, below | nothing asks it |

- ~~**The traffic list does not say when it holds only part of what it counts.**~~ Done 2026-10-21, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:web-list-says-what-it-holds`.

- **The export answers `limit=0` with no rows, where the daemon coerces it to 1.** **Not worth doing,
  2026-10-19.** Both the transactions and the signals branch of `archiveSource` do it. The export
  is read by one caller, the page, and the page never sends `limit=0`: its pager asks for whole
  pages. So no reader of any export can see the difference. Making the export agree would add a
  copy of the daemon's coercion rule for a request nobody makes, which is a mirror with nothing to
  mirror. Reopen it if anything other than the page ever reads an export's routes, or the page
  starts sending a computed limit that can reach 0.

*The three defects below were found on 2026-10-19 by QA on `item:web-current-session-guard`
(KIN-51, fix at `eaaadce`). Each is older than that item's diff or outside its criteria, so none
joins its fix round. Placed by the product owner the same day, with a ruling on the trade-off that
item accepted. All three, and the ruling, make one new item: they are the same state, the same two
functions and the same probe file, and splitting them would mean three tickets each rebuilding the
probe's fixture.*

| Defect | Goes to | Why there |
|---|---|---|
| 1. After a session switch, the now strip, the timeline's signal rows and the glance show the previous session's values until `loadSignals` lands | **new item**, `item:web-now-strip-follows-session` | a false fact on screen, true at base; 1a rebuilds the switch path but is far off, and gets a criterion that keeps the new probe green |
| 2. Two `refreshCurrent` reads for the same session can land out of order | **new item**, with 1 | the same writer; KIN-51 guarded the session, not the order |
| 3. The probe `now late reply dropped` cannot tell "dropped" from "blanked" | **new item**, with 1 | a guard that cannot detect; the new item's probes need the same second fixture session |
| KIN-51's trade-off: a failed `/current` read empties the strip on an unchanged session too | **new item**, ruled below: keep the session's own values | the fix is one field beside the counter in 2 |

- ~~**The now strip and the signal panes can show what is no longer true.**~~ Done 2026-10-21, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:web-now-strip-follows-session`.

*The three defects below were found on 2026-10-19 by QA on `item:web-live-rows-filtered` (KIN-52 to
KIN-55). Each is true at base `4e5491c`, before that item's diff, so none joins its fix round.
Placed by the product owner the same day. Two make one new item; the third was already owned.*

| Defect | Goes to | Why there |
|---|---|---|
| 1. The session glance (`calls 27` while the session had 29) and the picker label (`6 calls`) follow only a load, not live calls | **new item**, `item:web-live-calls-reach-every-pane` | KIN-54 made the header and the tab follow live calls and stopped there; the same handler owes the rest. Not `item:web-now-strip-follows-session`, whose scope is the switch window and which puts the counts out of scope; not 1b's (f3), which is about labels and is far off |
| 2. A live call with no filter set does not redraw the timeline | **new item**, with 1 | the same `txn` branch of `ws.onmessage` (`web/js/05-network.js`), and the same probe. It was recorded in §3 → Guards as unplaced since KIN-41, and in `docs/STATUS.md` → Not verified; this places it |
| ~~3. At 400 px the header count is clipped, `5 of 31 call`~~ **Done 2026-10-21** (KIN-70; `ROADMAP-DONE.md` → §6 → "Web UI foundation — 1a, the shell") | 1a, `item:web-foundation-shell`, under (e), "The call count is on screen at 400px" | already owned: that criterion asks for the whole count text inside the window at 400px. Noted there as seen again, under a filter |

- ~~**A live call reaches the list but not the timeline, the glance or the picker.**~~ Done 2026-10-21, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:web-live-calls-reach-every-pane`.

*The follow-ups below were left open by the coordinated run of 2026-10-20/21. Its builders and QA
recorded them in hand-overs, in QA's "older defects" and in `docs/STATUS.md` → Not verified. Each is
true at the base of the item that found it, or outside that item's criteria. Placed by the product
owner on 2026-10-21 and checked against the code at `c79e902`. Seven are items here, two are guards
under §3 → Guards, one is a line on Web UI polish (§6) and three are not items. **None is in "Order
from here" yet**: the maintainer reviews the list first.*

| Follow-up | Goes to | Why there |
|---|---|---|
| A live signal or marker redraws the timeline once per message | **new item**, `item:web-live-signal-burst-bounded` | the `drawLive` timer from `item:web-live-calls-reach-every-pane` already exists, and the same branch reads `/current` once per signal, which `docs/STATUS.md` records (50 signals, 53 requests). One item takes both |
| `#/txn/<id>` has no visible way back | **new item**, `item:web-txn-page-way-back` | a missing control, not a look. Web UI polish is far off |
| The `⤢` and `⇥` glyphs are 13px and nobody has judged them | §6 → Web UI polish | a judgement with no defect in it, and that item is the first human design pass |
| At 400px the Stats page's route cell breaks hostnames mid-word | **new item**, `item:web-stats-route-breaks-whole` | `http://127.0.0.1:808` / `0` reads as port 808, so this is a misreading and not only a look |
| IPv6 hosts are not bracketed in `url` or the row label | **new item**, `item:host-port-everywhere` | both this and the next row are places where the rule `item:row-label-port` set (a server is its host plus a non-default port) has not reached |
| The session summary's `byHost` merges `:8080` and `:9090` | **new item**, with the IPv6 row | the key becomes `hostPort`, and once `hostPort` brackets IPv6 the key is right for IPv6 as well |
| During a session switch the timeline's call rows and the endpoint chips show the session just left | **new item**, `item:web-switch-clears-call-panes` | the same class of false fact as `item:web-now-strip-follows-session`, which put calls out of scope. 1a, where QA suggested it go, has shipped without it |
| A brushed stretch with some loaded rows says nothing of older unloaded matches under a filter | **new item**, `item:web-stretch-says-older-matches` | `item:web-list-says-what-it-holds` covered only a stretch the list draws nothing of |
| Small doc fixes: `architect.md`, two INTEGRATION.md headings, the root `AGENTS.md` table, the Sessions page's path | **new item**, `item:docs-housekeeping-2026-10-21` | four edits, none of them worth an item on its own |
| Phase I's R12 proof command | not an item | `ROADMAP-DONE.md` → §5 → Phase I records that it is quoted nowhere in the repository, and the per-module regenerate commands in `api-parity/AGENTS.md` are correct |
| `twins-and-api.md` says v59 tells consumers to "copy the colours they need" | not an item | QA called it looser wording, not a false claim. Copying the colours is one of v59's three cases |
| No item in the run recorded what it cost | not an item | a team rule, in the roadmap-cycle skill ("Record what each item cost"), not a product change. Whether a coordinated run is held to it is a question for the maintainer |

- **A burst of live signals or markers redraws every pane once per message.** *Ticket label:*
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

- **`#/txn/<id>` has no visible way back.** *Ticket label:* `item:web-txn-page-way-back`. From the
  hand-over and QA on `item:web-foundation-list-room` (1b).

  **For whom.** A web UI user on a Mac who opened a call on its own page, from `⤢` or from an address
  someone pasted. Only the browser's Back and `Esc` leave the page, and nothing on it says so. A
  call opened in a new tab has no Back to press.

  **Acceptance criteria.**
  - The `#/txn` page shows a control that leaves it, keyboard-reachable and named for where it goes.
    It does what `Esc` does (`leaveTxnFull`): Back when `⤢` pushed the page, otherwise the session's
    list.
  - At 400px it is on screen, and in an export it works from the embedded data.
  - A probe line presses it in both cases. It is proven red with the control removed.

  **Out of scope.** Changing what Back and `Esc` do. The size of the `⤢` and `⇥` glyphs, which is
  Web UI polish's. This does not overturn a recorded decision: `web-ui.md` records that Back and
  Escape leave the page, not that nothing else may. Surfaces: web UI only. **Size:** S.

- **At 400px the Stats page breaks a route's host mid-word.** *Ticket label:*
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

- **A server is its host and a non-default port, but not yet everywhere.** *Ticket label:*
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

- **During a session switch the timeline's calls and the endpoint chips are the session just left.**
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

- **A brushed stretch under a filter does not say that older matches may be in it.** *Ticket label:*
  `item:web-stretch-says-older-matches`. From QA on `item:web-list-says-what-it-holds` (its "mixed
  stretch" note) and `docs/STATUS.md` → Not verified.

  **For whom.** A web UI user on a long session, with a filter that matches more calls than the list
  loads, who brushes a stretch that reaches below the oldest match loaded. The list draws the loaded
  matches in the stretch, and the note says `at least 10 calls hidden by the filter`. Nothing says
  that the stretch may also hold matches older than the ones loaded, so the list reads as all the
  matches in the stretch.

  **Acceptance criteria.**
  - In that case the list's note also says that the stretch reaches below the oldest of the `N`
    matches loaded, so older matches in it are not drawn, and that narrowing the filter reaches them.
  - It claims no count of those matches. The page evaluates no filter (`web-ui.md`, "Under a filter
    it claims no count"), so it has none.
  - A stretch wholly above the oldest match loaded reads as it does today.
  - A line in `check-web-static.js` over the 3016-call stub and one in the live probe over the long
    session, each proven red with the sentence removed.

  **Out of scope.** A daemon route that counts the matches in a range. If the architect finds that
  the sentence cannot be true without one, the item comes back to the product owner. The header's
  wording. Surfaces: web UI only. **Size:** S.

- **Housekeeping after the 2026-10-20/21 run.** *Ticket label:* `item:docs-housekeeping-2026-10-21`.
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

### Open questions

- **The 150 ms conflation window** and **the 2 MB signal ring budget** are guesses, and
  `SIGNALS-CHECKLIST.md` asks that they be measured against one real session before
  `INTEGRATION.md` presents them as defaults worth keeping. Per-tag retention caps have already
  left this list; these two have not.
- **Redirect collapsing** on engines other than CIO is unresolved for the target app's engine.
- **The Auth0 adapter** compiles against the real SDK and has never run against a live tenant.
- **Nobody has judged how the web UI looks.** It renders correctly; that is a different claim.


---

## 5. Next: the structure and architecture pass — planned 2026-10-18

**This comes first. Nothing in §6 starts until the items below marked "before §6" have landed.**
*(Extended 2026-10-18: phases H and I and the `McpTest.kt` split now also come before §6, as do the
two §3 defects. See "Order from here", below.)*
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
   *Decided 2026-10-18: it may ship in a minor release.*
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

| Item | Size | Must land |
|---|---|---|
| Phase E — one `timeline()`, so MCP agrees with the model | S | before §6 — a live bug |
| Phase C — every control route refuses without the header, by test **Done, 2026-10-18** (PR #83, merge `fbc2516`; `build`, `web`, `release-swap` green) | S | before §6 |
| Phase A — web checks and shared mirror cases in CI | M | before §6 |
| Phase B — module graph, dependency goldens, budgets, TODO, identity and name checks **Done, 2026-10-18** (PR #84, merge `01c6f5f`; all four jobs green on the head, `repo` red on the merge commit's author address only) | S | before §6 |
| Phase G — `AGENTS.md` as an index, `docs/decisions/`, per-module guides **Done, 2026-10-18** (PR #85, merge `b51b2c9`; all four jobs green on the head, `repo` red on the merge commit's author address only) | M | before §6 |
| Phase D — daemon packages; split `Server.kt` and `McpTools.kt`; layering check **Done, 2026-10-18** (PR #87, merge `f574bda`; all four jobs green on the head, `repo` red on the merge commit's author address only) | M | before §6 |
| Phase F — `app.js` and CSS split behind the manifest **Done, 2026-10-18** (PR #88, merge `1913cf7`; `build`, `release-swap` and `web` green, `repo` red on the merge commit's author address only) | M | before 1a |
| Phase I — `noop-ui` golden file **Done, 2026-10-21** (PR #106, merge `1479c30`) | M | after decision 2, now decided; does not gate §6 *(superseded 2026-10-18: scheduled in "Order from here", before §6)* |
| Phase H — split the two large overlay files **Done, 2026-10-21** (PR #103, merge `4dc21d2`) | S | later *(superseded 2026-10-18: scheduled in "Order from here", before §6)* |
| Later — split `McpTest.kt` **Done, 2026-10-21** (PR #102, merge `49735be`) | S | later; added 2026-10-18, left over from phase D *(superseded 2026-10-18: scheduled in "Order from here", before §6)* |

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

*(The reasoning in the last three sentences is overturned for order, not for content, on
2026-10-18. Nothing in §6 touches the overlay still holds. The maintainer chose to finish the
structure pass before §6 rather than leave it trailing. See below.)*

### Order from here — decided 2026-10-18

The maintainer set this order on 2026-10-18. It runs across §3, §5 and §6, so it is recorded once,
here. Phases A to I are merged, and so are the rows listed in `ROADMAP-DONE.md` → §5 → "Order from here — the rows finished". They are not in this table.

| Order | Item | Where | Ticket label | Size | Lane |
|---|---|---|---|---|---|
| 13 | The private security advisory's fix | §5 → Open questions | `item:control-boundary-advisory` | not sized | D |
| 14 | A check that finds nothing to check passes | §3 → Guards | `item:guard-probe-finds-nothing-fails` | not sized | W, alone |
| 15 | QA's live-traffic Chrome driver, committed | §3 → Guards | `item:guard-live-traffic-driver` | not sized | S; its case in its own `scripts/lib/` file |
| 16 | Housekeeping after the 2026-10-20/21 run (waits: maintainer approval of the `architect.md` edit, for that edit only) | §3 → Bugs found, not fixed | `item:docs-housekeeping-2026-10-21` | S | S for the docs, then W for the Sessions page line; the `AGENTS.md` edit merges before any status-row close |
| 17 | The core and stream twin pairs get the ui pair's guards | §3 → Guards | `item:guard-twin-pairs-alike` | S | L |
| 18 | "A probe leaves no process behind", tested | §3 → Guards | `item:guard-mcp-probe-cleanup` | not sized | D, can run beside the advisory |
| 19 | A server is its host and a non-default port, everywhere | §3 → Bugs found, not fixed | `item:host-port-everywhere` | S, or M | W; one PR, its `urlOf` mirror case runs in Kotlin and the web checks |
| 20 | A session switch clears the timeline's calls and the endpoint chips *(approved 2026-10-21 by the maintainer: overturns the `web-ui.md` sentence; replacement text comes with the item)* | §3 → Bugs found, not fixed | `item:web-switch-clears-call-panes` | S | W; cleared panes say "being read" |
| 21 | KIN-42: draw the first page at once, read the whole-session copy in the background *(undeferred 2026-10-21 by the maintainer; builds from a fresh design note)* | §3 → Guards, under the signals sort and clamp | `item:signals-query-one-rule` | one ticket | W, the same engineer as `item:web-switch-clears-call-panes` |
| 22 | At 400px the Stats page keeps a route's host whole | §3 → Bugs found, not fixed | `item:web-stats-route-breaks-whole` | S | beside W, only after `item:host-port-everywhere` merges |
| 23 | A burst of live signals redraws once, not per message | §3 → Bugs found, not fixed | `item:web-live-signal-burst-bounded` | S | W |
| 24 | Session diff (waits: design Q2 and Q3 confirmations, non-blocking) | §6 → Session diff | `item:session-diff` | L | PR A in D, after the advisory's batch; PR B in W |
| 25 | A pair guards a public declaration that exists only on iOS or Android | §3 → Guards | `item:guard-twin-native-surface` | M | L; the note first, then the build |
| 26 | Findings, and the payment-safety rules (waits: the shared STOP before T2, Q1 before T7) | §6 → Findings | `item:findings-payment-safety` | L | PR A in D, after diff PR A merges; PR B and C in W |
| 27 | Web UI polish (waits: Q2 to Q5) | §6 → Web UI polish | `item:web-ui-polish` | M | W; PR A before diff PR B, PR B last of all |
| 28 | `#/txn/<id>` has a visible way back | §3 → Bugs found, not fixed | `item:web-txn-page-way-back` | S | W, after polish A1 |
| 29 | A brushed stretch under a filter says older matches may be in it | §3 → Bugs found, not fixed | `item:web-stretch-says-older-matches` | S | W, after polish PR A |
| 30 | Sensitive-data detection — detect, never redact (waits: the shared STOP, Q2, Q3) | §6 → Sensitive-data detection | `item:sensitive-data-detection` | M | T1 to T4 in D; T5 in W after findings PR B |

**Rows after the advisory decided 2026-10-21 by the maintainer**, on the architect's and the
product owner's joint proposal; the order is approved, and each "(waits: …)" is a decision still
open. This schedules §6 items 3 to 6. Work runs in batches with one writer per lane: W web UI, D
daemon, L library twins, S scripts and docs. W is strictly serial. `25-traffic-list.js` has a line
budget to share: KIN-42 takes it to at most 592, polish A4 to 589, the stretch to 591, findings
T7/T8 to 600. `15-session-data.js` has about 52 lines left, so KIN-42's note states its line count;
past that a new numbered script, never a raised budget.

§6 items 3 to 6 were **not scheduled** by the 2026-10-18 decision. They keep their place in §6's table and
wait for the next one.

**Three rows added 2026-10-18 by the product owner**, from defects found that day outside any item.
The maintainer's order is unchanged: every one of the maintainer's rows keeps its place relative to
the others, and the new rows only sit between them. The tooltip defect found at the same time is in §3 as not worth doing.

The reason for each place is the maintainer's own rule above: a defect that shows someone a false
fact comes before a move that changes nothing anyone sees. The two web defects go straight after
the signals item because they are in the same files and the same class, and the first reuses its
pager. The port label is the exception and goes one step later, after the `McpTest.kt` split,
because its test belongs in the files the split creates.

**One row added 2026-10-19 by the product owner**, `item:web-current-session-guard`, from the
signals verify panels' should-fixes. Five more from the same batch were folded into rows already
here, as acceptance criteria: two into `item:web-transactions-paged`, one into 1a and two into 1b.
The table of where each went is in §3, above that item. The new row goes with the other web defects
for the same reason they go first: it shows a user something false, and it is small. Whether the web
defects should go ahead of the `McpTest.kt` split and phases H and I at all is still open for the
maintainer (§5 → "Open questions only the human can answer"). If the answer is no, this row moves
with them. The maintainer's rows keep their order relative to each other.

**One row added 2026-10-19 for the first `item:web-transactions-paged` verify panel's eight
defects.** Five were folded into `item:web-live-rows-filtered`, 1a and 1b as acceptance criteria,
one is not worth doing, and two make a new item, `item:web-list-says-what-it-holds`. The product
owner proposed it straight after `item:web-transactions-paged`. **The maintainer placed it on 2026-10-19 with the web
defects instead, straight after `item:web-current-session-guard`**; the rows after it
moved down one, and the maintainer's rows keep their relative order. The table of where each
defect went is in §3, below `item:web-current-session-guard`.

**Decided 2026-10-19 by the maintainer: `item:web-live-rows-filtered` keeps both criteria added
that day** (the header and tab count under live tail, and the whitespace-only filter). If the
architect sizes it above S with them in, its own condition applies and `item:web-live-rows-filtered` moves to after
phase I, still ahead of 1a. The maintainer accepted that consequence.

**One row added 2026-10-19 by the product owner**, `item:web-now-strip-follows-session`, from QA on
`item:web-current-session-guard`: three defects that item left in the same two functions and the
same probe, and a ruling on the trade-off it accepted. It goes straight after
`item:web-list-says-what-it-holds`, with the other web defects, for their reason: it is small and
shows a user something false today. The maintainer's rows keep their relative order and the rows
after it move down one. If the maintainer takes the web defects after phase I instead (§5 → "Open
questions only the human can answer"), this row moves with them. Where each defect went is in §3,
above the item.

**One row proposed 2026-10-19 by the product owner**, `item:web-live-calls-reach-every-pane`, from QA
on `item:web-live-rows-filtered`: the timeline, the glance and the picker label do not follow a live
call. The product owner proposed it straight after `item:web-now-strip-follows-session`, with the
other web defects. **The maintainer placed it on 2026-10-21 after 1a and before 1b, unsplit and with
its scope unchanged.** 1a's remaining pages (KIN-66 to KIN-69) rework the session picker and edit the
same web files, so building this item's picker criterion first would mean doing it twice, or two
writers on the same files. It no longer moves with the other web defects. Where each defect went is
in §3, above the item.

**Why this order.** By value per hour. The two defects come ahead of the structure moves: each is small, and each shows a
user something false today. The Markdown export prints a URL that was never called. The web UI
shows signals a filter should have hidden, and silently drops the oldest past 1000. The `McpTest.kt`
split, H and I come next, while the structure pass is still fresh. They are cheap now, and each one
is a move that is easier to review on a quiet tree than under §6's diffs. I comes after the other two
because it is the only one that changes something a consumer sees (see its item). Then §6 in its own
dependency order.

**The security advisory comes after 1a, 1b and 2, and this overturns a recorded decision.** §5 → "Open questions
only the human can answer" said: "Decide both before §6's bulk endpoints are built." The maintainer
moved it after 1a, 1b and 2 on 2026-10-18. The consequence, stated plainly: **1a's bulk delete and
bulk kill endpoints will be built before the advisory's fix is chosen.** What keeps that from
becoming rework is an acceptance criterion on 1a: the architect designs those endpoints so the fix
can land without rebuilding them. Phase C's header test still covers both endpoints from the day
they exist, because it reads the route list from the server.

The advisory item carries a ticket label so its progress is not held only in conversation. Ticket
files are public. A ticket under it names the advisory and never describes the gaps, until the
advisory itself is published.

**The six web UI layout defects UX found at base `f574bda`** are folded into 1a and 1b as
acceptance criteria, because those items rebuild the code each one lives in. None is a standalone
item. Where each went:

| Defect | Goes to | Why there |
|---|---|---|
| (a) Closed drawer focusable and widening the page | 1b | 1b rebuilds the list/detail split |
| (b) Endpoint cut away at 400px | 1b | 1b reworks the row and density |
| (c) Drawer `✕` overlaps the header's last action | 1b | the detail header sits in the same split |
| (d) Closed drawer's shadow shows as a strip | 1b | same drawer |
| (e) Top bar and tab bar overflow at 400px | 1a | 1a rebuilds the top bar and moves its controls |
| (f1) Session picker clipped at 1440px | 1a | the picker is top-bar chrome that 1a rebuilds |
| (f2) `16 of 16 calls` wraps at 1000px | 1b | the count sits in the toolbar 1b makes one row |
| (f3) Two counts that read as disagreeing | 1b | same toolbar and overview line |

*Rows marked 1b are done 2026-10-21 (PR #119); their criteria are in `ROADMAP-DONE.md` → §6 → "Web UI foundation — 1b, room for the list".*

One condition on (a), moot now that 1b has shipped. It was the only one of the six that traps a keyboard user, and §6 lets 1b slip
to 1.5. **If 1b slips, (a) comes out of 1b as its own item and goes ahead of 2.** It is small, and
a keyboard trap should not wait a release for a redesign.

### ~~Phase E — one `timeline()`, so MCP agrees with the model~~ — done 2026-10-18, see `ROADMAP-DONE.md` → §5

### ~~Phase C — every control route refuses without the header, by test~~ — done 2026-10-18, see `ROADMAP-DONE.md` → §5

### ~~Phase A — web checks and shared mirror cases in CI~~ — done 2026-10-18, see `ROADMAP-DONE.md` → §5; its release-notes lines are in `UNRELEASED.md`

### ~~Phase B — module graph, dependency goldens, budgets, TODO, identity and name checks~~ — done 2026-10-18, see `ROADMAP-DONE.md` → §5; `repo` is still not a required check on `main`, a maintainer action

### ~~Phase G — `AGENTS.md` as an index, `docs/decisions/`, per-module guides~~ — done 2026-10-18, see `ROADMAP-DONE.md` → §5

### ~~Phase D — daemon packages; split `Server.kt` and `McpTools.kt`; layering check~~ — done 2026-10-18, see `ROADMAP-DONE.md` → §5

### ~~Phase F — `app.js` and CSS split behind the manifest~~ — done 2026-10-18, see `ROADMAP-DONE.md` → §5

### ~~Phase I — `noop-ui` golden file~~ — done 2026-10-21, see `ROADMAP-DONE.md` → §5

### ~~Phase H — split the two large overlay files~~ — done 2026-10-21, see `ROADMAP-DONE.md` → §5

### ~~Later — split `McpTest.kt`~~ — done 2026-10-21, see `ROADMAP-DONE.md` → §5

### Deliberately not doing, in this pass

Each of these would look like a tidy-up while the code is being moved, and each is a recorded
decision or a known trap:

- **Moving `Main.kt`.**
- **Renaming `inspector-noop-stream/.../StreamSink.kt`**, which is load-bearing for the JVM facade
  name, **or either `InspectorOverlay.kt`**, which holds the same property and has been checked since
  phase I.
- **Rebuilding a class-set diff between twins.** It was measured at 19, 19 and 4 spurious entries
  and rejected. The reasoning is in `docs/decisions/twins-and-api.md`.
- **Adding a JavaScript filter evaluator to the export.** That would be a second parser of the frozen
  grammar.
- **Adding a second network seam beside `source`.**
- **Splitting out `:inspector-okhttp`.** A separate module cannot see `Recorder` or `Redactor`.
- **Moving `UsbListenerRebindTest` to the JVM**, where it passes with the fix deleted.

### Open questions only the human can answer

- **Needs the maintainer: two security-boundary gaps, tracked privately.** Found while building
  phase C. The detail, the options and their breakage risks are in a private security advisory.
  Neither has a chosen fix, and neither belongs inside §5, which allows no behaviour change. ~~Decide
  both before §6's bulk endpoints are built.~~ **Overturned 2026-10-18 by the maintainer: the fix is
  chosen and built last, after 1a, 1b and 2.** *Ticket label:* `item:control-boundary-advisory`.
  The consequence is that 1a's bulk delete and bulk kill endpoints are built before the fix is
  chosen. 1a carries an acceptance criterion so the fix lands without rebuilding them. The details
  stay in the advisory; tickets under this label name the advisory and never describe the gaps.
  See "Order from here".
- **Do the web defects go ahead of the `McpTest.kt` split and phases H and I?** *(Raised 2026-10-18,
  recorded 2026-10-19.)* The product owner placed `item:web-transactions-paged`,
  `item:web-live-rows-filtered` and now `item:web-current-session-guard` between the maintainer's
  rows, on the maintainer's own rule that a false fact comes before a move. The maintainer has not
  confirmed it. The alternative is to finish the structure pass first, while it is fresh, and take the
  web defects straight after phase I. Either way they stay ahead of 1a.
  `item:web-list-says-what-it-holds`, placed with them on 2026-10-19, moves with them. So does
  `item:web-now-strip-follows-session`, placed with them later the same day.
  `item:web-live-calls-reach-every-pane` does not: the maintainer placed it after 1a on 2026-10-21
  (see "Order from here").

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

| Order | Item | Size | Ships in | Scheduled in "Order from here" |
|---|---|---|---|---|
| ~~1a~~ | ~~Web UI foundation: the shell — rail, hash routes, Sessions and Processes pages, bulk endpoints, writable config~~ **Done, 2026-10-21** (KIN-63 to KIN-71; PRs #108, #112, #114) | L *(includes its share of the probe rewrite)* | 1.4 | ~~yes~~ done |
| ~~1b~~ | ~~Web UI foundation: room for the list — divider, overview line, one-row toolbar, density, `#/txn/{id}`~~ **Done, 2026-10-21** (KIN-78 to KIN-86; PR #119) | M *(includes its share of the probe rewrite)* | 1.4 | ~~yes~~ done |
| ~~2~~ | ~~Endpoint latency stats~~ **Done, 2026-10-21** (KIN-74 to KIN-77; PRs #115, #121) | M | 1.5 | ~~yes~~ done |
| — | The private security advisory's fix | not sized | not set | yes, after 2 (done) |
| 3 | Session diff | L | 1.5 | yes, decided 2026-10-21 |
| 4 | Findings framework, and the payment-safety rules | L | 1.6 | yes, decided 2026-10-21 |
| 5 | Sensitive-data detection — detect, never redact | M | 1.6 | yes, decided 2026-10-21 |
| 6 | Web UI polish, against the finished screens | M | 1.7 | yes, decided 2026-10-21 |
| — | Mocking, fault injection, breakpoints | — | Deferred: needs the SDK | — |

*Last column added 2026-10-18.* The maintainer's decision that day ordered work across §3, §5 and
§6 together. Items 3 to 6 keep their §6 order but are not scheduled by it. The advisory row is not
a §6 item; it sat here because it came after 2, which is done, so it is the only row left in "Order from here".

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

*Ticket label:* `item:web-foundation-shell` for 1a, the shell.
*Ticket label:* `item:web-foundation-list-room` for 1b, room for the list.

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

**1a done 2026-10-21** (PR #108, merge `e9f7131`; PR #112, merge `a7e3946`; fix PR #114, merge `cc9dbce`). Stats, Findings and Diff get their rail entry in the item that builds each page. The Sessions page's "pick two → compare" is the Diff item's: 1a has no Diff page to send it to. The 1a text that was only 1a's moved to `ROADMAP-DONE.md` → §6; 1b is done too, below.

**1b done 2026-10-21** (PR #119, merge `4fe71dc`; KIN-78 to KIN-86). Measured: the list pane's top moved from 208px to 145px at 1440 on the list view and 234 → 145px on the timeline; the "about 120px" estimate needs the tab bar folded into the toolbar, which nobody has asked for. The text that was only 1b's moved to `ROADMAP-DONE.md` → §6 → "Web UI foundation — 1b, room for the list"; what is left in this section is shared with the pages still ahead.

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
- **Room for the list:** *done 2026-10-21*; the bullet moved verbatim to `ROADMAP-DONE.md` → §6 → "Web UI foundation — 1b, room for the list".
- **The daemon's `config.json` becomes writable**, behind `GET/PUT /api/config`. It exists today
  (`DaemonConfig` reads it, flags over file over defaults) but nothing writes or validates it;
  this adds validation and an atomic write, and keeps flags winning. Findings
  rules have to live where MCP can read them, and an agent and the page disagreeing about what
  counts as a finding is a worse bug than either alone. View preferences stay in `prefs`.

What must still hold: the export still works offline (new pages either read the embedded archive
or hide under `body.archive`); `fetch` is still called once across every file `web/scripts.txt`
lists; every new pane that sets `display` joins `auditHiddenPanes`; nothing new under 11px.

**1a's advisory criterion is met** *(done 2026-10-21)*. The paragraph that stated it moved verbatim to `ROADMAP-DONE.md` → §6 → "Web UI foundation — 1a, the shell"; QA read the new routes against it on PR #108.

**The web UI layout defects found at base `f574bda`** *(placed 2026-10-18; where each went and why
is in §5 → "Order from here")*. These are acceptance criteria, stated as what a render shows. Each
check that can be automated goes into the probes and is proven to fail when broken.

In **1a, the shell**: *done 2026-10-21.* Its criteria moved verbatim to `ROADMAP-DONE.md` → §6 → "Web UI foundation — 1a, the shell".

In **1b, room for the list**: *done 2026-10-21.* Its criteria moved verbatim to `ROADMAP-DONE.md` → §6 → "Web UI foundation — 1b, room for the list".

**The delete and clear loops** moved into `service/SessionDeleter.kt` with the bulk delete *(done 2026-10-21)*; the paragraph is in `ROADMAP-DONE.md` → §6 → "Web UI foundation — 1a, the shell".

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

**Done, 2026-10-21** (KIN-74 to KIN-77; PRs #115 and #121, merges `0a5a1c9` and `d256174`). The text, the decision on the two notions of "endpoint" and what was built are in `ROADMAP-DONE.md` → §6 → "Endpoint latency stats".

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

The route is `archive/RouteTemplates.kt`'s, built in `item:endpoint-latency-stats`, including the `routeTemplates` overrides; the diff calls it and never templates a path itself.

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

"Template" in the table is the route from `archive/RouteTemplates.kt` (method, host and path template), built in `item:endpoint-latency-stats`.

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

The `⤢` and `⇥` glyphs in the detail pane's tools row, drawn at 13px since 1b, which nobody has judged
(placed 2026-10-21 from the coordinated run's follow-ups; §3 → Bugs found, not fixed has the table).

An editor for `routeTemplates` on the Settings page. 1a's editor covers retention and signal caps, and `item:endpoint-latency-stats` left templates to `config.json` and `PUT /api/config`, which validates them.

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
