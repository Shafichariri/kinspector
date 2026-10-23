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

**Neither sample has been looked at on physical hardware by an agent.** A cutout, a vendor skin, a gesture bar and a cable
running `adb reverse` are all still unexercised by anyone who wrote it down, and the record has no
Inspector build looked at on a real phone of either kind. *(See the corrections below: the maintainer reported
on 2026-10-22 that the overlay has run on a physical Android phone and works.)*

*(Corrected 2026-10-22 by the product owner: the last clause is no longer true. An iPhone ran the iOS
sample over USB on 2026-09-24, with the overlay running and nobody looking at it, and an Android
device ran the overlay once on 2026-08-17, a month before the Android sample existed (commit
`ef9058e`, which fixed the four defects it found). The sample's Android
build has not been recorded as running on a physical phone. `item:public-claims-true` carried the same correction to the
README, done 2026-10-22; which build ran on the 2026-08-17 phone is still the maintainer's to say.)*

*(Added 2026-10-22, close-out 5: **the maintainer reported that the overlay has run on a physical Android phone and works, and that Inspector is used daily on a real Compose Multiplatform project.** Reported, not verified by an agent, and not tied to a build or a device; `docs/STATUS.md` records it that way. The first sentence above is now only true of what an agent has seen.)*

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
| Landing-page sponsor link | Moved here 2026-10-18 from a marker comment in `site/index.html`, when phase B made such markers fail the build | The button links `https://github.com/sponsors/Shafichariri`, which shows a real page only once GitHub Sponsors (or Ko-fi) is set up. Point it at whichever page exists then. *(2026-10-22: the box's copy, which promised proxy capture, is corrected by `item:public-claims-true`; the link stays here.)* |
| The path view — what was left out | `docs/decisions/shared-rules.md`, "the path, the waterfall and the axis band are one rule" | Web `path` tab **done, 2026-10-11; released in 1.3.0**. Deliberately deferred: the overlay (a phone has no width for a route, and its merged list is already the vertical version); an MCP `path` tool (`timeline` carries the same stream — build it when an agent visibly struggles); concurrency hints inside a column; a map view with each screen once and transitions counted; making `path` the landing tab, which stays `network` until the view has earned it |

### Guards

- ~~**The stream pair had no golden API file.**~~ Done 2026-09-18, see `ROADMAP-DONE.md` → §3 → Guards.

- ~~**The twin pair's JVM file facades had different names.**~~ Done 2026-09-22, see `ROADMAP-DONE.md` → §3 → Guards.

- ~~**`:inspector-noop-ui` still has no golden file, and already diverges.**~~ Done 2026-10-21, see `ROADMAP-DONE.md` → §3 → Guards. *Ticket label:* `item:structure-phase-i`.

- ~~**"A probe leaves no process behind" is a claim nothing tests.**~~ Done 2026-10-22, see `ROADMAP-DONE.md` → §3 → Guards. *Ticket label:* `item:guard-mcp-probe-cleanup`.

- ~~**The signals sort and clamp exist twice, which breaks the layering rule phase D wrote down.**~~
  Done 2026-10-19, see `ROADMAP-DONE.md` → §3 → Guards. *Ticket label:* `item:signals-query-one-rule`.
  **KIN-42 done 2026-10-22** (PR #136, merge `1888380`; QA one round, no blocking findings), see
  `ROADMAP-DONE.md` → §3 → Guards. A load draws its first page at once, reads the whole-session copy behind it,
  and opens the socket before the first page; the panes that read the copy say `this session's N calls are being
  read…` until it lands. On the 160,016-call session, first rows at 411 to 427 ms against about 24 s at `4645c43`.
  The read stays uncapped, by KIN-42's decision. **For the product owner, unplaced, from KIN-42's hand-over and QA:**
  (1) a filter typed during the read restarts it, where one read per session that later loads wait on would not;
  (2) a row arriving during the first page's own request can miss the list until the next load; (3) an address
  naming a view other than traffic or path (`#/traffic/all`) still waits for the whole load; (4) the waterfall's
  stub groups for screens older than the list's page say `no calls`, before and after the copy lands (predates
  KIN-42); (5) the overview band's `min-height` rule is unguarded: with `.axis-hint.axis-reading { min-height:
  24px }` removed, `copy lands huge` still passes at 44 px, under its 48 px bound. *(2026-10-22: (5) is
  placed by the product owner as `item:guard-overview-band-reserved`, §3 → Guards; (1) to (4) stay unplaced. **(5) is done**: guarded by KIN-110, `overview band css` in CI and `copy lands huge` bounded at the scope bar's height.)*
  **For the product-owner, unplaced:** a live call never redraws the timeline. The `txn` branch of the
  socket's `onmessage` never calls `renderTimeline` (found by the engineer building KIN-41, true at `e1711a3` and after), so a call
  appears on the timeline only on the next redraw; with the pane at the end it is "followed" only in the
  sense that nothing moves. It predates this item and is not in KIN-42; it needs placing in the order.
  **Placed 2026-10-19 by the product owner** in `item:web-live-calls-reach-every-pane`, §3 → Bugs
  found, not fixed, after QA on `item:web-live-rows-filtered` found it again with two siblings.
  **Done 2026-10-21** in that item.

- ~~**A check that finds nothing to check passes.**~~ Done 2026-10-22, see `ROADMAP-DONE.md` → §3 → Guards. *Ticket label:* `item:guard-probe-finds-nothing-fails`.

- ~~**QA's live-traffic Chrome driver is still in a scratch directory.**~~ Done 2026-10-22, see `ROADMAP-DONE.md` → §3 → Guards. *Ticket label:* `item:guard-live-traffic-driver`.

- ~~**Phase I gave the ui pair three guards that the core and stream pairs lack.**~~ Done 2026-10-22, see `ROADMAP-DONE.md` → §3 → Guards. *Ticket label:* `item:guard-twin-pairs-alike`.

- ~~**No pair guards a public declaration that exists only on iOS or Android.**~~ Done 2026-10-23, see `ROADMAP-DONE.md` → §3 → Guards. *Ticket label:* `item:guard-twin-native-surface`.

*The two guards below were left open by the batch closed on 2026-10-22, from QA on
`item:guard-probe-finds-nothing-fails` and the hand-over of `item:guard-live-traffic-driver`, and
recorded in `docs/STATUS.md` → Not verified. Placed by the product owner on 2026-10-22 and checked
against the code at `5b21593`. **Both ordered 2026-10-22 by the maintainer**: see "Order from here".*

- ~~**`expanded unfolded` judges a line with no failure on it.**~~ Done 2026-10-22, see `ROADMAP-DONE.md` → §3 → Guards. *Ticket label:* `item:guard-expanded-unfolded-has-failures`.

- ~~**A measure run hangs when Chrome dies mid-case.**~~ Done 2026-10-22, see `ROADMAP-DONE.md` → §3 → Guards. *Ticket label:* `item:guard-measure-chrome-exit-fails`.

*The two guards below were left open by the batch closed on 2026-10-22: the first by QA on KIN-42
(`item:signals-query-one-rule`), the second by the hand-over and QA of
`item:guard-measure-chrome-exit-fails`. Both are recorded in `docs/STATUS.md` → Not verified. The
product owner placed them here on 2026-10-22. **Both were ordered 2026-10-22 by the maintainer's
proxy**: see "Order from here".*

- ~~**The overview band's reserved height is a promise nothing holds.**~~ Done 2026-10-22, see `ROADMAP-DONE.md` → §3 → Guards. *Ticket label:* `item:guard-overview-band-reserved`.

- ~~**Interrupting a measure run leaves its daemons running.**~~ Done 2026-10-22, see `ROADMAP-DONE.md` → §3 → Guards. *Ticket label:* `item:guard-measure-interrupt-cleans-up`.

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
| ~~A live signal or marker redraws the timeline once per message~~ **Done 2026-10-22** (KIN-101; `ROADMAP-DONE.md` → §3 → Bugs found, not fixed) | **new item**, `item:web-live-signal-burst-bounded` | the `drawLive` timer from `item:web-live-calls-reach-every-pane` already exists, and the same branch reads `/current` once per signal, which `docs/STATUS.md` records (50 signals, 53 requests). One item takes both |
| ~~`#/txn/<id>` has no visible way back~~ **Done 2026-10-23** (KIN-141; `ROADMAP-DONE.md` → §3 → Bugs found, not fixed) | `item:web-txn-page-way-back` | a missing control, not a look. Web UI polish is far off |
| The `⤢` and `⇥` glyphs are 13px and nobody has judged them | §6 → Web UI polish | a judgement with no defect in it, and that item is the first human design pass |
| ~~At 400px the Stats page's route cell breaks hostnames mid-word~~ **Done 2026-10-22** (KIN-93; `ROADMAP-DONE.md` → §3 → Bugs found, not fixed) | `item:web-stats-route-breaks-whole` | `http://127.0.0.1:808` / `0` reads as port 808, so this is a misreading and not only a look |
| ~~IPv6 hosts are not bracketed in `url` or the row label~~ **Done 2026-10-22** (KIN-92; `ROADMAP-DONE.md` → §3 → Bugs found, not fixed) | `item:host-port-everywhere` | both this and the next row are places where the rule `item:row-label-port` set (a server is its host plus a non-default port) has not reached |
| ~~The session summary's `byHost` merges `:8080` and `:9090`~~ **Done 2026-10-22**, with the IPv6 row (KIN-92) | `item:host-port-everywhere` | the key becomes `hostPort`, and once `hostPort` brackets IPv6 the key is right for IPv6 as well |
| ~~During a session switch the timeline's call rows and the endpoint chips show the session just left~~ **Done 2026-10-22** (KIN-94, KIN-95; `ROADMAP-DONE.md` → §3 → Bugs found, not fixed) | **new item**, `item:web-switch-clears-call-panes` | the same class of false fact as `item:web-now-strip-follows-session`, which put calls out of scope. 1a, where QA suggested it go, has shipped without it |
| ~~A brushed stretch with some loaded rows says nothing of older unloaded matches under a filter~~ **Done 2026-10-23** (KIN-142; `ROADMAP-DONE.md` → §3 → Bugs found, not fixed) | `item:web-stretch-says-older-matches` | `item:web-list-says-what-it-holds` covered only a stretch the list draws nothing of |
| ~~Small doc fixes: `architect.md`, two INTEGRATION.md headings, the root `AGENTS.md` table, the Sessions page's path~~ **Done 2026-10-22** (KIN-89; `ROADMAP-DONE.md` → §3 → Bugs found, not fixed) | **new item**, `item:docs-housekeeping-2026-10-21` | four edits, none of them worth an item on its own |
| Phase I's R12 proof command | not an item | `ROADMAP-DONE.md` → §5 → Phase I records that it is quoted nowhere in the repository, and the per-module regenerate commands in `api-parity/AGENTS.md` are correct |
| `twins-and-api.md` says v59 tells consumers to "copy the colours they need" | not an item | QA called it looser wording, not a false claim. Copying the colours is one of v59's three cases |
| No item in the run recorded what it cost | not an item | a team rule, in the roadmap-cycle skill ("Record what each item cost"), not a product change. Whether a coordinated run is held to it is a question for the maintainer |

- ~~**A burst of live signals or markers redraws every pane once per message.**~~ Done 2026-10-22, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:web-live-signal-burst-bounded`.

- ~~**`#/txn/<id>` has no visible way back.**~~ Done 2026-10-23, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:web-txn-page-way-back`.

- ~~**At 400px the Stats page breaks a route's host mid-word.**~~ Done 2026-10-22, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:web-stats-route-breaks-whole`.

- ~~**A server is its host and a non-default port, but not yet everywhere.**~~ Done 2026-10-22, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:host-port-everywhere`.

- ~~**During a session switch the timeline's calls and the endpoint chips are the session just left.**~~
  Done 2026-10-22 (KIN-94, KIN-95, PR #135): a switch clears the call side with the signal side, and until the new session lands every call pane, the glance and the markers popover say `this session is being read…`; the tab bar keeps its tabs without counts, and a call left open from the previous session closes. Overturns the `web-ui.md` sentence "The calls themselves are left to the switch's first page", by the maintainer's approval of 2026-10-21. See `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:web-switch-clears-call-panes`.

- ~~**A brushed stretch under a filter does not say that older matches may be in it.**~~ Done 2026-10-23, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:web-stretch-says-older-matches`.

- ~~**Housekeeping after the 2026-10-20/21 run.**~~ Done 2026-10-22, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:docs-housekeeping-2026-10-21`.

*The follow-ups below were left open by the batch closed on 2026-10-22 (`item:guard-mcp-probe-cleanup`,
`item:guard-probe-finds-nothing-fails`, `item:host-port-everywhere`,
`item:web-stats-route-breaks-whole`), from their hand-overs and QA reports, and recorded in
`docs/STATUS.md` → Not verified. Each is true at the base of the item that found it, or outside
its criteria. Placed by the product owner on 2026-10-22 and checked against the code at `5b21593`.
Two are items here, one is a doc entry here, and two are guards under §3 → Guards. **All five
ordered 2026-10-22 by the maintainer**: see "Order from here".*

| Follow-up | Goes to | Why there |
|---|---|---|
| ~~A silent MCP child hangs the probe~~ **Done 2026-10-22** (KIN-96; `ROADMAP-DONE.md` → §3 → Bugs found, not fixed) | **new item**, `item:mcp-probe-deadline-holds` | `item:guard-mcp-probe-cleanup` put the timeout out of scope; its test file has the stub this needs |
| ~~One IPv6 server reached by OkHttp and Ktor is two route rows~~ **Done 2026-10-22** (KIN-100; `ROADMAP-DONE.md` → §3 → Bugs found, not fixed) | **new item**, `item:route-key-one-ipv6-host` | the route key, not the Stats page's drawing; neither IPv6 item owned the key |
| ~~`web-ui.md` does not name the cases #133 fixed; its "29 breaks" depends on fonts~~ **Done 2026-10-22** (KIN-97; `ROADMAP-DONE.md` → §3 → Bugs found, not fixed) | **new item**, `item:docs-housekeeping-2026-10-22` | `item:docs-housekeeping-2026-10-21` was open with only a web line left, and reopening its docs part would mix lanes |
| ~~`expanded unfolded` opens Home, which has no failure~~ **Done 2026-10-22** (KIN-98; `ROADMAP-DONE.md` → §3 → Guards) | §3 → Guards, `item:guard-expanded-unfolded-has-failures` | a guard that passes without judging its rule |
| ~~`measure-cdp.js` hangs when Chrome dies mid-case~~ **Done 2026-10-22** (KIN-99; `ROADMAP-DONE.md` → §3 → Guards) | §3 → Guards, `item:guard-measure-chrome-exit-fails` | a check that cannot fail when its browser goes away |

- ~~**A silent MCP child hangs the probe, and is left running.**~~ Done 2026-10-22, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:mcp-probe-deadline-holds`.

- ~~**One IPv6 server reached by both clients is two route rows.**~~ Done 2026-10-22, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:route-key-one-ipv6-host`.

- ~~**Housekeeping after the 2026-10-22 batch.**~~ Done 2026-10-22, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:docs-housekeeping-2026-10-22`.

- ~~**The README and the landing page say things that are not true.**~~ Done 2026-10-22, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:public-claims-true`.

- ~~**The landing page scrolls sideways at 400 px, and cuts a card's text.**~~ Done 2026-10-22, see `ROADMAP-DONE.md` → §3 → Bugs found, not fixed. *Ticket label:* `item:landing-page-fits-400`.

### Open questions

- **The 150 ms conflation window** and **the 2 MB signal ring budget** are guesses, and
  `SIGNALS-CHECKLIST.md` asks that they be measured against one real session before
  `INTEGRATION.md` presents them as defaults worth keeping. Per-tag retention caps have already
  left this list; these two have not.
- **Redirect collapsing** on engines other than CIO is unresolved for the target app's engine.
- **The Auth0 adapter** compiles against the real SDK and has never run against a live tenant.
- **Nobody has judged how the web UI looks.** It renders correctly; that is a different claim.
  *(2026-10-22: `item:web-ui-polish` no longer closes this. It hands the maintainer a contact sheet;
  this stays open until the maintainer says they have looked, or picks an outside designer.)*


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

**Release 1.4.0 — decided 2026-10-22 by the maintainer.** One release, and its part of the order
ends at findings and sensitive-data detection.
- **Scope:** everything merged on `main` when the tag is cut.
- **Required before the tag:** all of `item:findings-payment-safety` (its PR B and C included) and
  `item:sensitive-data-detection` T1 to T5, each merged and verified by QA; the private security
  advisory's fix, row 13; and `item:release-1-4-docs`, which lands last.
- **Central's monthly file limit, added 2026-10-22 at the maintainer's request.** Central counts every
  published file, checksums and signatures included, against 1,000 a month. 1.3.0 was a 2,000-file
  release. Two rules follow:
  1. **The 1.4.0 tag is cut no earlier than 2026-11-01**, so the release counts against November.
  2. **At most one Central release a month.** Even trimmed (row 13g, done: about 800 files), one
     release uses about 80% of the limit. A patch release in the same month goes to GitHub Packages
     only, or waits.
- **Fallback:** if those are not merged and verified by QA by **2026-12-03**, 1.4.0 is cut that day
  from what is merged, and anything required that missed it goes to the following release.
- **Progress, close-out 5, 2026-10-22:** findings and detection T1 to T5 are merged and Done on QA's evidence, the rehearsal ran twice and its three faults are fixed (`ROADMAP-DONE.md` → §6), and the landing page landed. Open before the tag: row 13c's final pass, and row 13 (the private advisory's fix).
- **After 1.4.0:** session diff, the rest of web UI polish, the two web defects that wait on polish, and the
  native-surface guard. They are in the "After 1.4.0" table below.

| Order | Item | Where | Ticket label | Size | Lane |
|---|---|---|---|---|---|
| 13 | The private security advisory's fix | §5 → Open questions | `item:control-boundary-advisory` | not sized | D |
| 13c | The 1.4.0 release notes and `INTEGRATION.md` say what 1.4.0 ships | §6 → Release 1.4.0 | `item:release-1-4-docs` | S | S, after 13b; starts now, and its last commit lands last before the tag, after findings and detection are merged and verified by QA. **Pass 1 done 2026-10-22** (PR #163): UNRELEASED consolidated, INTEGRATION v64 (findings, detection, IPv6 route host), v60/v61 → v57. Final pass waits on findings PR C, detection T5 and QA (PR C has since merged, #164). **Progress, close-out 5, 2026-10-22: the final pass is next and is the last commit before the tag.** Findings and detection are merged and Done on QA's evidence (KIN-124 included), the rehearsal passed (`ROADMAP-DONE.md` → §6 → Release 1.4.0), and KIN-129 stays open for it. **Release-day PR #177 is a draft (v66, 1.4.0 coordinates, the site's "New in 1.4.0") and will be refreshed when the maintainer sets a date; no tag until then.** |

**After 1.4.0**, decided 2026-10-22 by the maintainer. Row labels are kept. Why they wait: 1.4.0 ships the two
features that catch a defect in the app under test, and none of these is needed to show them.

| Order | Item | Where | Ticket label | Size | Lane |
|---|---|---|---|---|---|
| 27 | Web UI polish (questions answered 2026-10-22 by the maintainer's proxy; the first human design pass stays open for the maintainer, and the item closes without it) | §6 → Web UI polish | `item:web-ui-polish` | M | W; PR A before PR B, PR B last of all (diff PR B has since merged, so only polish PR B is left). **PR A is complete (2026-10-23).** Part 1 done 2026-10-22 and in 1.4.0: A1, A2 and A7 (PR #150, `4343823`). Part 2 done 2026-10-22: A3, page keys (KIN-104, PR #155, `fc7b205`). Part 3 done 2026-10-23: A4 to A6 (KIN-105 to KIN-107, PR #181, `7f49e14`; QA closed all three). **PR B is in progress** (KIN-145 and KIN-146, on the web developer's branch, not merged) |

**One row added 2026-10-22 by the product owner, at the coordinator's request:**
`item:landing-page-fits-400`, from QA on `item:public-claims-true`. It goes first in lane S because
lane S has nothing else it can start: `item:release-1-4-docs`' final pass and
`item:release-rehearsal` both wait on detection T5. It is above the 1.4.0 cut because the page is
public now and is published from `main`, not by the tag, but it is not one of the tag's required
items. Its label is out of sequence on purpose: 13a, 13b and 13d were used. The maintainer's proxy
approved this place on 2026-10-22 (decision `2026-10-22-landing-page-fits-400`: first in lane S, before the tag if it can, not gating it), and the row is done: `ROADMAP-DONE.md` → §3 → Bugs found, not fixed.

**Three rows added 2026-10-22 for the 1.4.0 cut, approved by the maintainer's proxy on the product
owner's proposal**, all in lane S and straight after `item:public-claims-true`: `item:release-1-4-docs`,
`item:release-notes-boilerplate` and `item:release-rehearsal`. Each is cheap, and each is what stands
between a merged feature and a release a consuming team can take on trust: notes that match the
evidence, a release page that agrees with `docs/ACCESS.md`, and a publish path exercised before the
tag rather than by it. The rows under "After 1.4.0" keep their order relative to each other; they only
moved below the cut. §6's "Ships in" column (1.5 to 1.7) is superseded by this split.

**Re-ordered 2026-10-22 by the maintainer's proxy: features that catch real defects and can be
demonstrated come first; the architect confirmed the findings/diff dependency is one shared piece.**
Row labels are kept, not renumbered, because tickets and notes already cite them; position is the
order, and the labels now run out of sequence on purpose. What changed:

- `item:public-claims-true` is new and first in lane S. A false fact comes before anything else in
  its lane, by the maintainer's own rule, and the three it fixes are on the two pages a newcomer
  reads first.
- `item:findings-payment-safety` PR A and `item:sensitive-data-detection` T1 to T4 move ahead of
  `item:session-diff`. Each catches a defect in the app under test (a payment sent twice, a token in
  a URL), where the diff reports a change that may or may not be one. The architect checked the
  dependency that had put findings after diff PR A and found it narrow: it starts at findings T3, and
  it is the one piece of diff T1 that both use. That piece is now its own ticket, Shared body
  evidence, under `item:findings-payment-safety`, built to the names and signatures
  `item:session-diff`'s design fixes in its §10. Diff T1 shrinks by it and depends on it.
  `item:route-key-one-ipv6-host` stays ahead of both, for its own reason.
- `item:sample-demo-flow` is new, after detection T5 and before the diff. Until the sample sends a
  double submit and a token in a query string, nobody can watch either rule family fire without
  writing their own app, and QA has no fresh session to verify them on.
- In lane W the order is findings PR B and C, detection T5, then the diff's PR B and polish as
  before. Polish PR A still lands before diff PR B.
- Two unordered candidates were added to §6 → "Candidates, not ordered": `item:findings-ci-check` and
  `item:mcp-registry-listing`.

**Rows after the advisory decided 2026-10-21 by the maintainer**, on the architect's and the
product owner's joint proposal; the order is approved, and each "(waits: …)" is a decision still
open. This schedules §6 items 3 to 6. Work runs in batches with one writer per lane: W web UI, D
daemon, L library twins, S scripts and docs. W is strictly serial. `25-traffic-list.js` has a line
budget to share: KIN-42 takes it to at most 592, polish A4 to 589, the stretch to 591, findings
T7/T8 to 600. `15-session-data.js` has about 52 lines left, so KIN-42's note states its line count;
past that a new numbered script, never a raised budget.

**Five rows added 2026-10-22: decided by the maintainer on the product owner's proposal**, from the
follow-ups the batch closed that day left open. Lettered rows sit between existing ones so no row
already here is renumbered; docs refer to those numbers. `item:mcp-probe-deadline-holds` is first in
lane D after the advisory: a probe that never answers and a child that outlives it are a user-visible
hang and a leak, and the fix is small. The three S-lane rows follow the 2026-10-20/21 housekeeping:
the doc entry, then the `expanded unfolded` guard (same lane and small enough to share its PR), then
the measure runner, which goes before the W lane's next fix round because every round runs it.
`item:route-key-one-ipv6-host` goes in lane D before `item:session-diff` and
`item:findings-payment-safety`, because both reuse the route key; fixed after them, it would change
three surfaces' answers instead of one. KIN-42 merged on 2026-10-22 as PR #136 and QA verified it the same day; its row, with the
rows for `item:mcp-probe-deadline-holds`, `item:docs-housekeeping-2026-10-21`,
`item:guard-measure-chrome-exit-fails` and `item:web-switch-clears-call-panes`, moved to `ROADMAP-DONE.md`.

On 2026-10-22, later the same day and after QA, the rows for `item:docs-housekeeping-2026-10-22` (16a),
`item:guard-expanded-unfolded-has-failures` (16b), `item:guard-measure-interrupt-cleans-up` (16d),
`item:route-key-one-ipv6-host` (21a) and `item:web-live-signal-burst-bounded` (23) moved to `ROADMAP-DONE.md` too.
`item:guard-overview-band-reserved` (16e) merged later the same day as PR #149 and moved to `ROADMAP-DONE.md` at the batch-4 close; its row's "lands before
`item:web-ui-polish` PR A" did not hold, because PR A part 1 (#150) merged first.

**Two rows added 2026-10-22, ordered by the maintainer's proxy on the product owner's proposal.**
16d is the measure run's interrupt cleanup: every W-lane fix round runs that script, and it is the same
kind of fix as KIN-99. 16e is the overview band guard, before `item:web-ui-polish` PR A, because the
guard must exist before the work that moves what it guards. 16c is skipped because KIN-99 used it
and docs may still name that number.

**The design questions on four rows were answered 2026-10-22 by the maintainer's proxy**, each on the
architect's recommended default, and are reversible if the maintainer reopens them. Findings and
sensitive-data detection share one answer to the shared STOP: `findings` and `findingDismissals`
are re-read live, every other `config.json` key stays read at start, and the narrowing is written
into `docs/decisions/daemon-and-mcp.md` by the item, not left implicit. Both ship on by default,
with `list_findings` open to agents and no MCP tool able to dismiss a finding. Web UI polish closes
on merge and QA like any item; the contact sheet its last PR produces goes to the maintainer, and
§3's open question "Nobody has judged how the web UI looks" stays open until they have looked or
picked a designer. Two questions became unordered candidates, in §6 → "Candidates, not ordered".

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

*"Ships in" superseded 2026-10-22 by the maintainer: findings and detection are required for 1.4.0, and
session diff and the rest of polish come after it. See "Order from here" → "Release 1.4.0".*

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
duplicate* gates the Findings row badge. *(Both decided 2026-10-22 by the maintainer's proxy;
see each item.)*

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
  The `findings` key is the one exception to "applies at the next start" (decided 2026-10-22, `item:findings-payment-safety`).

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

### ~~Session diff~~ — done 2026-10-23, see `ROADMAP-DONE.md` → §6

Open text still refers to it: the cross-origin question is the candidate "Match routes across origins in the diff" below, and the default-view gap is under "Follow-ups left by the batch-6 close".

### ~~Findings, and the payment-safety rules~~ — done 2026-10-22, see `ROADMAP-DONE.md` → §6

### ~~Sensitive-data detection — detect, never redact~~ — done 2026-10-22, see `ROADMAP-DONE.md` → §6

### ~~A sample demo flow that both rule families catch~~ — done 2026-10-22, see `ROADMAP-DONE.md` → §6

### Web UI polish

**Progress, 2026-10-23: PR A is complete.** Part 3 (A4 empty states, A5 Settings counts on a first load, A6 the `routeTemplates` editor; KIN-105 to KIN-107, PR #181, `7f49e14`) merged and QA closed all three, so A1 to A7 are done. PR B (KIN-145, KIN-146) is in progress on a branch and not merged; the item stays open for it.

**Progress, 2026-10-22: PR A parts 1 and 2 done.** Part 2: A3 (KIN-104, page keys) merged in PR #155 (`fc7b205`) and is Done on QA's pass with real keystrokes at 1440 and 400 px (`g` plus a letter, `g g`, `?` on every page, nothing fires while typing, which also fixes the replay textarea defect). Part 3 (A4 to A6, KIN-105 to KIN-107) is parked for after 1.4.0.

**Part 1:** A1 (KIN-102, icon controls), A2 (KIN-103, page
documents) and A7 (KIN-108, the contact sheet harness) merged in PR #150 (`4343823`), each Done on
QA's pass over detached copies (one round, no blocking findings; `check-web-static.js` 120 to 124,
the live probe 263 to 264, `measure-web-ui.js --only polish,room,shell` 19 failing readings at base
to 0 at head). **Open:** A3 to A6 (KIN-104 to KIN-107) and the diff's PR B. The item is not Done. QA
ran the contact sheet (120 PNGs and an `index.html`) and left it in the run folder for the
maintainer; whether the pages look right is still the maintainer's call, and that question stays
open. The Settings page reading `0 row(s) in this session` on a direct load of `#/settings` is in
the sheet and is A5's.

The notes from use, applied once the new screens exist; the first human design pass the web UI
has had (still listed under open questions; *decided 2026-10-22 by the maintainer's proxy: the item
does not wait for that pass. It closes on merge and QA, hands the maintainer the contact sheet, and
the pass and its notes become their own items*); `g s` / `g f`-style keys for the new pages; and empty
states that say why they are empty, as the traffic list already does.

The `⤢` and `⇥` glyphs in the detail pane's tools row, drawn at 13px since 1b, which nobody has judged
(placed 2026-10-21 from the coordinated run's follow-ups; §3 → Bugs found, not fixed has the table).

An editor for `routeTemplates` on the Settings page. 1a's editor covers retention and signal caps, and `item:endpoint-latency-stats` left templates to `config.json` and `PUT /api/config`, which validates them.

### Release 1.4.0

Added 2026-10-22 by the product owner and approved the same day by the maintainer's proxy. These three
items are the 1.4.0 cut's own work; its scope, what it requires and its fallback date are in "Order from
here" → "Release 1.4.0". None changes code a consumer compiles. Surfaces: docs, the release workflow's
text and `docs/STATUS.md`. No public API, no noop twin, no golden file.

#### The 1.4.0 release notes and `INTEGRATION.md` say what 1.4.0 ships

*Ticket label:* `item:release-1-4-docs`

**Pass 1 done 2026-10-22** (PR #163): UNRELEASED consolidated, INTEGRATION v64 (findings, detection, IPv6 route host), v60/v61 → v57. Final pass waits on findings PR C, detection T5 and QA (PR C has since merged, #164). Ticket KIN-129. **Progress, close-out 5, 2026-10-22: findings, detection T1 to T5 and the rehearsal are all done and verified, so the final pass is next, and it lands last before the tag.**

**The user and the problem.** A consuming team upgrading from 1.3.0, and the developer on a Mac who
downloads the 1.4.0 daemon. They read the release page and `INTEGRATION.md` §14, not the commits.
`docs/UNRELEASED.md` holds twenty-three owed lines written one item at a time. Several describe the same
screen twice, some sentences were true when written and have since been overtaken by a later item, and
the lines for findings, detection and the merged polish work are not written yet. `INTEGRATION.md` v60
and v61 tell a reader that the 1.3.0 daemon's web UI "is the one v59 described". v59 is about `inspector-ui`'s public API and describes no web
UI; the entry for 1.3.0 is v57.

**Why now.** It is the last thing before the tag, and the tag is required to wait for it. Starting the
consolidation now, on the lines that already exist, leaves only the findings and detection lines for
the end.

**Acceptance criteria.**
- `docs/UNRELEASED.md`'s owed lines that overlap are one line each: the sixth, eighth and eighteenth;
  the fifth, ninth and nineteenth; the thirteenth and twenty-third. A sentence a later item made false
  is dropped, and the change lists each one it dropped and which item overtook it.
- Lines are added for findings, sensitive-data detection and the merged parts of web UI polish, each
  once its work is merged and verified by QA, never before.
- Every claim in the consolidated notes matches `docs/STATUS.md` → Verified at the cut. A claim STATUS
  does not support is reworded or removed. Nothing is claimed for an item the cut left out (the
  fallback in "Release 1.4.0").
- `INTEGRATION.md` gains, with a version bump and a §14 entry that says what a consumer must *do*:
  `GET /api/sessions/{id}/findings`, the MCP tool `list_findings`, the CLI's `inspector findings`, the
  `findings` config key and that it is re-read live, and the same set for detection as built.
- v60 and v61 point to v57 for the 1.3.0 web UI. Older changelog entries are otherwise left as written.
- It lands last before the tag. A later merge that changes what 1.4.0 ships reopens it.

**Out of scope.** The README and the landing page, which are `item:public-claims-true`'s. The release
page's boilerplate, which is `item:release-notes-boilerplate`'s. Rewriting old changelog entries for
style. **Size:** S.

#### ~~The release page and `ACCESS.md` name the same place to get the library~~ — done 2026-10-22, see `ROADMAP-DONE.md` → §6 → Release 1.4.0

#### ~~Rehearse the release locally before the tag~~ — done 2026-10-22, see `ROADMAP-DONE.md` → §6 → Release 1.4.0

#### ~~The Central bundle ships only what Central requires~~ (row 13g) — done 2026-10-23, see `ROADMAP-DONE.md` → §6 → Release 1.4.0

### Candidates, not ordered

Added 2026-10-22 by the product owner, out of questions the maintainer's proxy answered that day
for `item:session-diff` and `item:findings-payment-safety`. Each is kept out of its parent item on
purpose. Neither is in "Order from here", and neither gets tickets until it is placed there. Each
names what would decide it. If that evidence says no, it moves to a "Not worth doing" entry with the
reason.

#### Follow-ups left by the batch-4 close, 2026-10-22

Unplaced, no tickets, each checked against the code at `f4bbfbf` before it was written. One line each; the product owner places or drops them.

- **The payment source's route memo holds raw URL paths.** `PaymentLedger.add` asks `FindingSession.routeOf`, whose memo is keyed by the raw method, scheme, host, port and path (`service/Findings.kt`), so a card number in a URL path sits in the cache; the sensitive source avoids the memo for that reason. Found by detection's leak test, outside its item.
- **The call branch redraws the waterfall and the path on every call.** `web/js/05-network.js`'s `txn` branch calls `renderWaterfall()` and `renderPath()` per message, while the timeline and the glance go through `drawLive` (250 ms); the burst bound `web-live-signal-burst-bounded` set holds for those two only.
- **A lone signal takes about 280 ms to reach the timeline.** `LIVE_DRAW_MS` is 250, and QA read 277 to 281 ms with the render, so "within 250 ms" is true of calls and not of a signal on its own.
- **`docs/decisions/daemon-and-mcp.md` line 72 can be read the wrong way round.** "Nothing but `findings` and `findingDismissals` applies until the next start" can be taken as "only these wait"; "Only `findings` and `findingDismissals` apply live; every other key waits for the next start" cannot. QA's nit from `item:findings-payment-safety`.
- **`shell`, `room`, `polish` and `sheet` leave their export temp directories behind on an interrupt.** Each makes `stats-export-`, `room-export-`, `polish-export-` or `sheet-export-` under the OS temp directory and removes it in a `finally`, which a signal skips; `measure-web-ui.js`'s `cleanUp` removes only the run's own directory (KIN-109 named `stats-export-` and `room-export-` as left open).

#### Follow-ups left by the batch-5 close, 2026-10-22

Unplaced, no tickets, each checked before it was written: against `site/index.html` and QA's width sweep for the first, and against `.github/workflows/ci.yml` and `scripts/check-release-clean.sh` for the other two. One line each; the product owner places or drops them.

- **Between 821 and 885 px the landing page is still 886 px wide.** QA measured `scrollWidth` every 5 px from 360 to 1440 (`41b968c`): only that band overflows; at 850 px the widest boxes are a card's `div.shot`, `h3`, `p` and `li`, reaching x = 861. `.grid2` is two columns above its 820 px query (`site/index.html:86-87`), where `.grid2 > * { min-width: 0 }` does not apply. It is true on main before `item:landing-page-fits-400` too, and the 400 px guard does not read it.
- **`PublishedPomTest` has never been run on a Linux developer machine.** CI runs `:repo-checks:test` only inside the macOS `build` job's `./gradlew build`; the Ubuntu `repo` job runs the identity and name scripts and no JDK. QA's and the builder's runs were on macOS, and the test resolves iOS configurations.
- **The release guard did not scan the Android aars during the local rehearsal.** There is no Android SDK on the rehearsal machine, so no aar was built; `check-release-clean.sh` reads `*.aar` (and unpacks the nested `classes.jar`), and CI's `build` job, on macOS with the SDK, runs it after `./gradlew build`. Only the seven Android POMs were checked locally.

#### Follow-ups left by the batch-6 close, 2026-10-23

Unplaced, no tickets, each checked against the code at `1b7a45b` before it was written. One line each; the product owner places or drops them.

- **`src/iosMain` is never compiled in `:inspector-core` and `:inspector-noop`.** Their hand-written `jvmAndAndroidMain` links (`dependsOn` in both `build.gradle.kts`) turn the default hierarchy template off, so no `iosMain` source set exists; neither module has a `src/iosMain` today, but a file added there would build clean and ship nowhere (`docs/decisions/twins-and-api.md`; the iOS guard reads the real klibs, so it stays honest). `iosArm64Main` and `iosSimulatorArm64Main` are where iOS-only code goes. Found by KIN-143's build and confirmed by QA.
- **Two 20,000-call sessions with 8 KB bodies use up the diff's 256 MiB body budget.** `DiffBudget` (`service/SessionDiffSide.kt`, `256L * 1024 * 1024`) ran out in the builder's scratch pair (33,378 bodies, 268,434,247 bytes), so the rest of each route's bodies read as unread, and a key B dropped can read "unsure" where it is in fact removed. The reply says so (`shapeBudget.exhausted`); nothing false is claimed. Whether to raise the budget, sample, or stream is open.
- **By default, MCP `diff_sessions`, `inspector diff` and the Diff page hide a route whose only news is an unsure key.** Design 6.4 says `unsure` never sets a change kind, so such a route counts as unchanged and the `unchanged=false` default drops it: QA's pair read `counts {changed: 0, unchanged: 7}` and 0 routes, while a removal may be hidden. Every criterion is met as written. An `unsure` tally in `counts` or the summary, or keeping such routes listed, would close it on all three surfaces together (QA, `session-diff-pr-a-report` note 1 and `session-diff-pr-b-report` note 1).
- **The Stats page's table box may have the Diff page's layout fault.** `css/85-stats.css:21` is `.stats-scroll { overflow-x: auto; margin: 0 0 8px; }`, where the Diff box needed `flex: none` (`css/88-diff.css:28`) to keep its width in a flex column. Unverified and untouched: nobody has measured the Stats box in Chrome. Polish PR B's hand-over is not written yet, so this is placed here and dropped if it shows the box was fixed or never faulted.

#### Match routes across origins in the diff

*Ticket label:* `item:diff-match-across-origins`

**The user and the problem.** A web UI user on a Mac, or an agent calling `diff_sessions`, compares
a run against a staging server with a run against production. A route keys on host, so every route
is different and the diff shows the whole app as removed and re-added. The answer is correct and
tells the user nothing.

**Why not now.** It would grow an L item. Nobody knows yet how often two runs of one app reach
different hosts. **Decide after `item:session-diff` is verified by QA**, from how often the
comparison comes up. One check for that QA, which belongs to the diff and not to this candidate: a
diff of two sessions with no host in common must say so, not show only a wall of added and removed
routes.

**Acceptance criteria, if placed.**
- With a host pairing set in `config.json` (for example `staging.example.test` →
  `api.example.test`), a diff of those two sessions pairs routes whose method, scheme, port and
  template match, and labels each paired row with both hosts.
- With no pairing set, nothing is paired. The diff never guesses that two hosts are "the same
  server".
- `diff_sessions` and the Markdown form report the same pairing as the page.

**Out of scope.** Changing the route key. `docs/decisions/daemon-and-mcp.md` records that "the
host is part of the key because one template on two servers is two routes, and the diff and the
findings reuse the key". A pairing is a diff-time view over two keys, not a new key. If the design
finds it has to change the key, **this overturns a recorded decision** and must say so. Findings and
stats are not affected.

**Surfaces.** Daemon, web UI, MCP. A new `config.json` key, read at start like every key except the
two findings keys. No library API. **Size:** M, the product owner's guess.

#### Same body sent twice with different idempotency keys

*Ticket label:* `item:finding-same-body-new-key`

**The user and the problem.** A web UI user or an agent checking a payment flow. The client mints a
fresh idempotency key on each tap, so a double tap sends the same body twice and the server treats
the two calls as two payments. None of the six rules sees it: Duplicate mutation needs no key, and
"Key reused, body changed" needs the same key.

**Why not now.** It is a seventh rule, and the six are not built. Two identical payments a few
seconds apart are sometimes intended, so the false-positive rate decides the rule's severity and
whether it ships on by default. **Decide after `item:findings-payment-safety` has run on one real
session.**

**Acceptance criteria, if placed.**
- Two mutations with the same method, route and body hash, different `callId`s and different
  idempotency keys, within the duplicate window, raise one finding that cites both calls.
- A body capture did not keep, or kept only in part, raises nothing. The rule never claims a
  duplicate without both bodies, the same as Duplicate mutation.
- Evidence is masked like every other finding's. The rule has a stable id and lives in the one rule
  registry, and the findings JSON only gains entries.

**Out of scope.** Matching across sessions, a filter-grammar term (the grammar is frozen for v1),
and any knowledge of a particular backend's payment API.

**Surfaces.** Daemon only. The page, MCP and exports get it through the findings framework. No
library API. **Size:** S.

*The two candidates below were added 2026-10-22 by the product owner, at the maintainer's proxy's
request, when findings moved ahead of the diff. They follow the rule above: unordered, no tickets,
and each names what would decide it.*

#### A findings check for CI

*Ticket label:* `item:findings-ci-check`

**The user and the problem.** A consuming team that has recorded a payment flow and wants a pull
request to fail when that flow starts double-submitting. Findings answer on a page, over MCP and from
the CLI, but nothing turns a finding into a failed build, so a regression is caught only when someone
opens the page.

**Why not now.** It is worth building only if two things are true, and the architect has not
confirmed either. **Decide when the architect confirms** that the findings engine runs as a pure
function over a session folder, with no running daemon, and that a team's CI can produce a session
at all: a test run that streams into a daemon, or a recorded folder checked in. If CI cannot produce a
session, this is "Not worth doing" with that reason.

**Acceptance criteria, if placed.**
- `inspector findings <session folder> --fail-on <severity>` exits non-zero when any undismissed
  finding is at or above that severity, and zero otherwise. It prints the findings it counted, with
  masked evidence.
- It reads the same rules and dismissals from `config.json` as the page and `list_findings`, so the
  three agree on the same session.
- Without `--fail-on`, the command behaves as the findings item built it.

**Out of scope.** A hosted service, or anything listening beyond loopback. A new filter-grammar
term. Comparing two sessions, which is the diff's.

**Surfaces.** Daemon CLI only. No library API. **Size:** S, if the engine is already pure.

#### MCP registry listing

*Ticket label:* `item:mcp-registry-listing`

**The user and the problem.** A developer whose editor's agent finds MCP servers through a
registry. Inspector's server is described only in this repository, so that developer never finds
it, and an agent that could read their archive does not.

**Why not now.** It is docs only, and the listing should describe findings once they ship in a
release, not before. **Decide when findings are in a release**, and when the maintainer has picked
the registries and holds the accounts, which only the maintainer can.

**Acceptance criteria, if placed.**
- Each listing names what the server reads (the local session archive), that it binds loopback
  only, and the command that starts it, and lists the tools in the MCP tools-list golden file.
- Nothing in a listing claims more than `docs/STATUS.md` → Verified. The same check as
  `item:public-claims-true`.

**Out of scope.** A remote or hosted transport, which would overturn the loopback boundary in
`docs/decisions/daemon-and-mcp.md`. Any change to the tools.

**Surfaces.** Docs only. **Size:** XS, plus the maintainer's time with each registry.

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
