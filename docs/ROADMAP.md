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

- ~~**Render markers.**~~ **Done, 2026-09-17.** Dividers in the list and a chip per distinct
  label under the filter field, applying and clearing `since:marker("…")` on tap. The interleave
  rule moved into `:inspector-model` as `timeline(…)`, so the overlay and `app.js` now describe
  the same arrangement in the same words — including the part that is easy to get wrong, which is
  that newest-first is the *reverse* of the ascending sequence and not a descending sort. One
  limit found on the way: the grammar has no escape inside its quotes, so a label with an odd
  number of `"` gets a divider but no chip.
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
parameter, and both platforms recorded device-versus-emulator on the grounds that "recording the
truth means the archive is still accurate the day physical devices are added" — which turned out
to be the right instinct and the wrong implementation, since the detection under that comment was
answering "physical device" for every emulator. Both of the first two items below are now done;
the comment is gone with them.

### ~~First: the detection is wrong~~ — done, 2026-09-17

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

### ~~Android over USB~~ — done, 2026-09-17

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
