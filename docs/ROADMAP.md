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

- **Render markers.** The overlay already receives them and uses them for `since:marker(…)`
  filtering only (`InspectorList.kt:73`) — they are never drawn. So a marker you can create with
  the `mark` button is invisible the moment you create it, and there is no way to discover what
  labels exist to type into a filter. Dividers in the list, and a list of labels to tap.
- **A wall-clock column in the row.** The web row carries one; the mobile row does not, which
  makes correlating with anything outside the app harder than it should be.
- **Repeat count and span.** Mobile says `repeated`; the web says how many times and over how
  long, which is the part that tells you whether it is a retry storm or a double-fetch.
- **Sort order toggle**, oldest/newest first. The mobile list is fixed.
- **Freeze the list.** Mobile is always live by construction, which sounds better than it is:
  there is no way to hold still and read while traffic keeps arriving.
- **Quick-filter chips** — errors / 5xx / slow / retries. The grammar is identical on both sides
  already (both parse with `:inspector-model`'s `FilterParser`), so these are one-tap presets over
  machinery that exists.
- **Endpoint chips.** Same reasoning.

### The substantial piece: signals in the overlay

In dependency order, because each stage makes the next one cheap:

1. **Collect `Inspector.signals`** and show a merged timeline — traffic, signals and markers on
   one clock. This is the single biggest gap, and it is the thing the web UI opens on for a
   session that has signals.
2. **Per-tag browsers** — cache, state, screen, and any tag an app invents. Freshness, provenance
   (`pushed` vs `pulled`), faceting, per-key history.
3. **The "now" strip** — the last observation per `(tag, name)`, with age.
4. **Pull a signal on demand.** Providers are in-process (`Recorder.kt`), so this needs an
   internal API surfaced rather than any transport.

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

Three things the overlay has that the web UI does not, and at least two should cross over:

- **The scope bar** — the shared host and path prefix lifted out of the rows. The web repeats the
  full path on every row and has the same redundancy, with more width to waste it in.
- **Creating a marker from the UI.** The web can only read markers; only MCP can add one.
- **Per-field copy buttons.** The web has copy-cURL and the AI bundles, but cannot copy one header
  value.

---

## 2. Real devices

**This overturns two recorded decisions.** `implementation-plan.md` §1, under "do not
re-litigate", fixes **"Simulator / emulator only for v1"**, and its non-goals list names
**"physical devices"** outright.

Both are worth reopening, and the same document is the reason why: it also lists "charts/timelines
in web UI" and "request rewriting/replay" as v1 non-goals, and **both were built anyway**. These
are not permanent positions, they are positions nobody has revisited on the record.

The code was written in anticipation. `StreamSink` already takes `host` as a constructor
parameter, and both platforms already record device-versus-emulator honestly, with this comment:

> v1 only supports emulators, but recording the truth means the archive is still accurate the day
> physical devices are added.

### First: the detection is wrong

Before anything else, because it is the field that would tell you whether device support works.

An archive of real sessions contains **18 recorded as `android-device` from a device named
`Google sdk_gphone64_arm64`** — the standard emulator AVD, labelled as hardware. The check looks
for `"generic"` in the fingerprint and `"Emulator"` or `"Android SDK built for"` in the model
(`Platform.android.kt`), and a current emulator image reports none of them.

Fix the detection, and prefer something sturdier than a string match on a product name —
`ro.kernel.qemu` and the `goldfish`/`ranchu` device names outlast marketing strings. Then check
the iOS side too: it keys off `SIMULATOR_DEVICE_NAME`, which is sound, but has never been
confirmed against a physical iPhone because nobody has run it on one.

### Android over USB — nearly free, and no security decision

`adb reverse tcp:8099 tcp:8099` makes the phone's own loopback reach the host machine. The app
then connects to `127.0.0.1` instead of the `10.0.2.2` emulator alias, and **the daemon's loopback
bind is untouched.**

What this needs: the host default to stop being a hard-coded emulator alias, a documented recipe,
and a clear failure message when nothing is listening — "is `adb reverse` set up?" rather than a
silent disconnected sink.

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

**There is no Android app module and no Xcode project.** `AGENTS.md` has carried "Android + iOS
sample shells, to finally see the overlay on a device" for a while. Everything above is untestable
on hardware without them, so they come first in practice even though they are the least
interesting item here.

---

## 3. Carried over, unbuilt

Not new, and not forgotten. Collected here because they were spread across nine documents.

| Item | Where it is specified | State |
|---|---|---|
| Replay step 3 — edit before replay | `REPLAY.md` §7 | Specified; the API already accepts edits, so this is the form around it |
| Replay step 4 — template generators | `REPLAY.md` §7 | Specified, including the generator set |
| 4c — proxy capture | `AGENTS.md` | Not started. **Requires an explicit decision to start**: it turns the tool from a library into network infrastructure |
| 4b — report what we cannot see | `AGENTS.md` | Not designed; flagged there as possibly not worth it |
| HAR export | `implementation-plan.md` Phase 3 stretch | Unbuilt, named in two documents |
| Signal payload redaction | `SIGNALS.md`, `schema.md` | `Signal.redacted` is reserved and always empty; the schema field is already there |
| Disconnected-transaction backfill | `schema.md` | Explicitly a v2 candidate |

### Open questions

- **The 150 ms conflation window** and **the 2 MB signal ring budget** are guesses, and
  `SIGNALS-CHECKLIST.md` asks that they be measured against one real session before
  `INTEGRATION.md` presents them as defaults worth keeping. Per-tag retention caps have already
  left this list; these two have not.
- **Redirect collapsing** on engines other than CIO is unresolved for the target app's engine.
- **The Auth0 adapter** compiles against the real SDK and has never run against a live tenant.
- **Nobody has judged how the web UI looks.** It renders correctly; that is a different claim.

---

## What this document does not overturn

The constraints in `AGENTS.md` → "Non-obvious decisions — do not 'fix' these", and
`implementation-plan.md` §1, stand except where this document says otherwise. In particular:
capture stays Ktor-client-only until 4c is deliberately chosen, the filter grammar is frozen for
v1, production safety remains two layers and non-negotiable, Inspector learns nothing about app
domain concepts, and no adapter modules ship.
