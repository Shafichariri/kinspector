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

- ~~**The signals sort and clamp exist twice, which breaks the layering rule phase D wrote down.**~~
  Done 2026-10-19, see `ROADMAP-DONE.md` → §3 → Guards. *Ticket label:* `item:signals-query-one-rule`.
  **Open, deferred by the maintainer on 2026-10-19: KIN-42.** On a session of about 160,000 calls, the
  web UI shows a false empty state ("waiting for an app" / "nothing recorded") for about 25 seconds
  before the first rows appear, and the live socket connects only after. KIN-34 introduced it, when the
  filter-note counts began reading the whole session. The fix is to draw the first page at once and
  read the whole-session copy in the background (ACs in the ticket); it is not scheduled. The item is
  Done without it by that decision, not because the defect is closed.
  **For the product-owner, unplaced:** a live call never redraws the timeline. The `txn` branch of the
  socket's `onmessage` never calls `renderTimeline` (found by the engineer building KIN-41, true at `e1711a3` and after), so a call
  appears on the timeline only on the next redraw; with the pane at the end it is "followed" only in the
  sense that nothing moves. It predates this item and is not in KIN-42; it needs placing in the order.

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

- **Live rows ignore the filter until the next reload.** *Ticket label:*
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

- **Row labels drop a non-default port.** *Ticket label:* `item:row-label-port`. **Ordered
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
| KIN-33's sticky list-pane layout has no committed guard | 1b, `item:web-foundation-list-room` | 1b rebuilds the bands above the list, so the guard must exist before 1b moves them |
| List, timeline, waterfall and path share one scroll position | 1b, `item:web-foundation-list-room` | 1b rebuilds the list pane, and 1a's hash routes give each view an address to remember a position by |
| The export's transactions route ignores `offset`/`limit` | `item:web-transactions-paged` | that item's export criterion cannot be checked without it |
| The "Lost the daemon" banner shows only on the first close after an unplanned drop | 1a, `item:web-foundation-shell` | 1a rebuilds the daemon controls and the top bar the banner belongs to |

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
| 5. The repeat mark is cut to `twic…` at 400px | 1b, `item:web-foundation-list-room`, under (b) | (b) already owns what a row keeps at 400px |
| 6. The count is off-screen below about 600px | 1a, `item:web-foundation-shell`, under (e) | (e) covered the controls, not the count; now it covers the count too |
| 7. Four numbers for one session on one screen | 1b, under (f3) | (f3) already owns counts that read as disagreeing; the boundary-row idea goes to the new item |
| 8. The export answers `limit=0` with 0 rows | not worth doing, below | nothing asks it |

- **The traffic list does not say when it holds only part of what it counts.** *Ticket label:*
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

- **The now strip and the signal panes can show what is no longer true.** *Ticket label:*
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

  **Why not only a criterion on 1a.** 1a rebuilds the session switch, but it is the fifth item
  after this one and sized L, and folding this in would leave a false fact on screen until then,
  the same argument that made KIN-51 its own item. 1a carries one criterion instead: the probes
  this item adds stay green through its rebuild.

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
| Phase I — `noop-ui` golden file | M | after decision 2, now decided; does not gate §6 *(superseded 2026-10-18: scheduled in "Order from here", before §6)* |
| Phase H — split the two large overlay files | S | later *(superseded 2026-10-18: scheduled in "Order from here", before §6)* |
| Later — split `McpTest.kt` | S | later; added 2026-10-18, left over from phase D *(superseded 2026-10-18: scheduled in "Order from here", before §6)* |

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
here. Phases A to G are merged (see `ROADMAP-DONE.md` → §5). They are not in this table.

| Order | Item | Where | Ticket label | Size |
|---|---|---|---|---|
| 1 | Live rows ignore the filter *(added 2026-10-18; moves after phase I if sized above S; both 2026-10-19 criteria kept, maintainer 2026-10-19)* | §3 → Bugs found | `item:web-live-rows-filtered` | S, to be confirmed *(if above S, moves after phase I; maintainer accepted 2026-10-19)* |
| 3 | The traffic list does not say when it holds only part of what it counts *(added 2026-10-19; placed by the maintainer)* | §3 → Bugs found | `item:web-list-says-what-it-holds` | S, to be confirmed |
| 4 | The now strip and the signal panes can show what is no longer true *(added 2026-10-19)* | §3 → Bugs found | `item:web-now-strip-follows-session` | S, to be confirmed |
| 5 | Split `McpTest.kt` | §5 | `item:structure-split-mcp-test` | S |
| 6 | Row labels drop a non-default port *(added 2026-10-18)* | §3 → Bugs found | `item:row-label-port` | S |
| 7 | Phase H — split the two large overlay files | §5 | `item:structure-phase-h` | S |
| 8 | Phase I — `noop-ui` golden file | §5 | `item:structure-phase-i` | M |
| 9 | 1a — Web UI foundation: the shell | §6 | `item:web-foundation-shell` | L |
| 10 | 1b — Web UI foundation: room for the list | §6 | `item:web-foundation-list-room` | M |
| 11 | 2 — Endpoint latency stats | §6 | `item:endpoint-latency-stats` | M |
| 12 | The private security advisory's fix | §5 → Open questions | `item:control-boundary-advisory` | not sized |

§6 items 3 to 6 are **not scheduled** by this decision. They keep their place in §6's table and
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

One condition on (a). It is the only one of the six that traps a keyboard user, and §6 lets 1b slip
to 1.5. **If 1b slips, (a) comes out of 1b as its own item and goes ahead of 2.** It is small, and
a keyboard trap should not wait a release for a redesign.

### ~~Phase E — one `timeline()`, so MCP agrees with the model~~ — done 2026-10-18, see `ROADMAP-DONE.md` → §5

### ~~Phase C — every control route refuses without the header, by test~~ — done 2026-10-18, see `ROADMAP-DONE.md` → §5

### ~~Phase A — web checks and shared mirror cases in CI~~ — done 2026-10-18, see `ROADMAP-DONE.md` → §5; its release-notes lines are in `UNRELEASED.md`

### ~~Phase B — module graph, dependency goldens, budgets, TODO, identity and name checks~~ — done 2026-10-18, see `ROADMAP-DONE.md` → §5; `repo` is still not a required check on `main`, a maintainer action

### ~~Phase G — `AGENTS.md` as an index, `docs/decisions/`, per-module guides~~ — done 2026-10-18, see `ROADMAP-DONE.md` → §5

### ~~Phase D — daemon packages; split `Server.kt` and `McpTools.kt`; layering check~~ — done 2026-10-18, see `ROADMAP-DONE.md` → §5

### ~~Phase F — `app.js` and CSS split behind the manifest~~ — done 2026-10-18, see `ROADMAP-DONE.md` → §5

### Phase I — `noop-ui` golden file

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

### Phase H — split the two large overlay files

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

### Later — split `McpTest.kt`

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
| 1a | Web UI foundation: the shell — rail, hash routes, Sessions and Processes pages, bulk endpoints, writable config | L *(includes its share of the probe rewrite)* | 1.4 | yes |
| 1b | Web UI foundation: room for the list — divider, overview line, one-row toolbar, density, `#/txn/{id}` | M *(includes its share of the probe rewrite)* | 1.4 | yes |
| 2 | Endpoint latency stats | M | 1.5 | yes |
| — | The private security advisory's fix | not sized | not set | yes, after 2 |
| 3 | Session diff | L | 1.5 | no |
| 4 | Findings framework, and the payment-safety rules | L | 1.6 | no |
| 5 | Sensitive-data detection — detect, never redact | M | 1.6 | no |
| 6 | Web UI polish, against the finished screens | M | 1.7 | no |
| — | Mocking, fault injection, breakpoints | — | Deferred: needs the SDK | — |

*Last column added 2026-10-18.* The maintainer's decision that day ordered work across §3, §5 and
§6 together. Items 3 to 6 keep their §6 order but are not scheduled by it. The advisory row is not
a §6 item; it sits here because it now comes after 2.

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

**1a must leave room for the security advisory's fix** *(added 2026-10-18)*. The maintainer
scheduled that fix after 1a, 1b and 2 (§5 → "Order from here"), so the bulk endpoints are built
before it is chosen. Acceptance criterion: the architect's design for `POST /api/peers/kill` and
`POST /api/sessions/delete` states how the advisory's fix, under any of the options the advisory
lists, lands without rebuilding either endpoint, and the architect reads the advisory to write it.
The design note in this public repository names the advisory and does not describe the gaps.

**The web UI layout defects found at base `f574bda`** *(placed 2026-10-18; where each went and why
is in §5 → "Order from here")*. These are acceptance criteria, stated as what a render shows. Each
check that can be automated goes into the probes and is proven to fail when broken.

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
  moves with it into 1b.
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
