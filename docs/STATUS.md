# Status — what has been checked, and what has not
Moved here from the root `AGENTS.md` so the root stays an index; the delivery tracker keeps it current.

**Last full count: 886 tests, 0 failures** across JVM, iOS simulator, Android host and the daemon. It is
old, and **no full run has been counted since**; a branch-wide total by arithmetic is not claimed.
Latest per-target counts from QA, each at the commit named and none a full-repository total:
`:inspector-model:jvmTest` 160/0 at `1da0d75`; `:inspector-ui:jvmTest`
136/0, `:inspector-noop-ui:jvmTest` 2/0, `:inspector-core:jvmTest` 73/0, `:inspector-noop:jvmTest` 3/0,
`:inspector-stream:jvmTest` 3/0, `:inspector-noop-stream:jvmTest` 1/0 and `:repo-checks:test` 35/0 at
`1479c30`; `:inspector-daemon:test` 299/0 at `e9f7131` (QA, PR #108; 271 on its base); web static checks 99/0,
live probe 225 passed 0 failed 1 n/a and archive probe 40 passed at `433f30d` (QA, KIN-58 round 2). The daemon figure is older than the web ones, and #115 and
later commits add daemon tests that nobody has counted.
Not included in any of these: the Android host tests and the iOS simulator tests, because the QA machine
has no Android SDK and no working `xcrun`; CI runs them.

**Phase A of the structure pass (web checks and mirror cases in CI) is done**: its first CI run, on
PR #81, went green on the new `web` job (ubuntu) and on `build` and `release-swap` (macOS: full
`./gradlew build`, Android host tests, `-Pinspector=off`). `docs/ROADMAP-DONE.md` §5 has the detail.

**Phase E (MCP `timeline` and `explain` order through the model's `timeline()`) is done**: CI on
PR #82 went green on `build`, `release-swap` and `web`. `docs/ROADMAP-DONE.md` §5 has the detail.

**Phase C (every control route refuses without the header, by test) is done**: PR #83 merged as `fbc2516`;
CI on its head and on the merge push passed `build`, `web` and `release-swap`. `ControlHeaderCoverageTest`
runs inside `:inspector-daemon:test`. No `repo` job existed yet.

**Phase B (module graph, dependency locks, budgets, identity and name checks) is done**: PR #84 merged as
`01c6f5f`; CI on its head `b51b2c9` passed all four jobs, including `repo`. On the merge push `repo` was
red on the commit-identity check only (the merge commit's author address). The `CLIENT_NAME_TERMS` secret
exists and `ci.yml` passes `--require-terms` on push. Not verified: that `repo` is a required check on
`main` (branch protection requires `build` and `release-swap` only, read 2026-10-19; a maintainer action).

**Phase G (`AGENTS.md` as an index, `docs/decisions/`, per-module guides) is done**: PR #85 merged as
`b51b2c9`; CI on its head `fdf8d4c` passed all four jobs, `repo` red on the merge push for author identity
only. The root is 14,605 bytes against the 15,000 budget held by `:repo-checks`.

**Phase D (daemon packages; `Server.kt` and `McpTools.kt` split; layering check) is done**: PR #87 merged as
`f574bda`; CI on its head `b919341` passed all four jobs, `repo` red on the merge push for author identity
only. Tickets KIN-1 to KIN-9 are Done on verifier evidence (last pass at `753d9cf`). The 247 daemon tests
and 35 repo-checks tests are the engineer's count at `c1f2a4a`, which the verifier did not re-run, and no
full-repository count has been taken. Not verified: the no-client-names check on the tickets, whose term
list is kept out of the repository. `docs/ROADMAP-DONE.md` §5 has the detail.

**Phase F (`app.js` and CSS split behind the manifest) is done**: PR #88 merged to `main` as `1913cf7`,
and CI run 39146369617 on that push passed `build`, `release-swap` and `web`. `repo` was red on that push,
only on the commit-identity check: the merge commit was authored with the maintainer's work address by
GitHub's merge dialog, not by anything in the branch. PR #89 has since made the check accept the
maintainer's personal address; the dialog setting is the maintainer's to change. Tickets KIN-10 to KIN-22
are Done on verifier evidence (last pass on `b733aa4`). Counts at `e065f11`, per module and not a
full-repository total: `:inspector-daemon:test` 259, `:inspector-ui:jvmTest` 134,
`:inspector-model:jvmTest` 159, repo-checks 35 (35/0 again after `b733aa4`). Web probes: live 164 passed,
1 n/a, 42 notes and archive 21, both per-label identical to base `f574bda`; static checks 24. Headless
Chrome 131: 16 configurations, served and exported under `file:`, computed-style, text and PNG hashes
identical to base, 0 page errors. Not verified: Safari and Firefox, Android host tests (no SDK), the
no-client-names check, and how the page looks to a human. Six layout defects that predate the phase are
listed for the product-owner in `docs/ROADMAP.md` §5. `docs/ROADMAP-DONE.md` §5 has the detail.

**`item:web-current-session-guard` is done** (2026-10-19, commit `eaaadce` on branch
`shafic/current-session-guard-445b65`, not pushed, so CI has not run). KIN-51 is Done on one fresh
qa-verifier pass. Counts at `eaaadce`, per target and not a full-repository total: web static checks
79/0, live probe 187 passed 0 failed, archive probe 37/0, `:inspector-daemon:test` 268/0,
`:repo-checks:test` 35/0. Guard proven red both ways (guard deleted: 186/1; `catch` fallback put
back: `now failed read empty` fails). Verified in headless Chrome only. Not run: `:inspector-model`
and the iOS, Android and overlay targets (nothing there changed). Accepted trade-off: a failed
`/current` read always empties the strip, also on an unchanged session. **Reversed 2026-10-21 by
`item:web-now-strip-follows-session`**: a failed read now keeps the strip on the session you are on and
empties it only across a switch.

**`item:web-live-rows-filtered` is done** (2026-10-19, uncommitted on branch
`shafic/web-live-rows-filtered`, base `4e5491c`, not pushed, so CI has not run). KIN-52 to KIN-55 are
Done on qa-verifier evidence over two passes (round 2 re-ran everything after the KIN-55 fix). Counts
on the working tree, per target and not a full-repository total: web static checks 87/0 (base 79),
live probe 197 passed 0 failed 1 n/a (base 187), archive probe 37/0, `:inspector-daemon:test` 268/0,
`:repo-checks:test` 35/0; client-name scan clean. Guards proven red both ways (push-everything put
back: 191/6; the two `rereadLive` and `stands()` rules: 87/1 each; the `50-path.js` break: 195/2).
Not run: `:inspector-model` and the iOS, Android and overlay targets (nothing there changed).

**`item:signals-query-one-rule` is done, with one deferred bug** (2026-10-19, on branch
`shafic/roadmap-batch-2026-10-18`, not pushed, so CI has not run). Tickets KIN-24 to KIN-41 and KIN-26
are Done on verifier evidence over four panels (`5f6b610`, `e1711a3`, `a86dde2`, `8082e02`) and a fresh
QA at `8f64058`. Counts at `8f64058`, per target and not a full-repository total:
`:inspector-daemon:test` 267/0, `:repo-checks:test` 35/0, web static checks 71/0, live probe 182
passed, archive probe 26 passed. KIN-42 is open and deferred by the maintainer. Not run for this item:
`:inspector-model` and the iOS, Android and overlay test targets (nothing there changed).
Not verified: the no-client-names check (term list not available to the tracker).

**`item:web-transactions-paged` is done** (2026-10-19, on branch `shafic/next-priorities-8bf5c2`, base
`c21aaf0`, not pushed, so CI has not run). Tickets KIN-43 to KIN-50 are Done on verifier evidence over
three rounds, last pass at `68315b4`. Counts at `68315b4`, per target and not a full-repository total:
web static checks 79/0, `:inspector-daemon:test` 268/0, `:repo-checks:test` 35/0 (`DecisionsTest`
5/0), live probe 185 passed 0 failed, archive probe with the long export 37/0. Not run: `:inspector-model`
and the iOS, Android and overlay targets (nothing there changed), and `-Pinspector=off` (no consumer
signature changed). Not verified: CI, the no-client-names check (term list not available to the
tracker), and the top-bar layout readings in anything but headless Chrome 131. Three criteria are
unticked on purpose: KIN-43 AC#4 ("passes ... in CI"), KIN-44 AC#4 (its archive half was retired) and
KIN-45 AC#4 ("same text live and offline", amended); the last two follow the maintainer's decision that
an export draws every embedded call. KIN-42 stays deferred.

**`item:web-foundation-shell` (1a) is done** (2026-10-21, on `main` at `cc9dbce`; PRs #108, #112 and #114, merges
`e9f7131`, `a7e3946` and `cc9dbce`). KIN-63 to KIN-71 are Done on QA's evidence: PR A on its own, PR B in two rounds, round 1
failing KIN-68 (the Processes page erased the kill results 300 ms after showing them; fixed in #114, round 2 verified it).
CI on each PR and on the three merge pushes: `build`, `release-swap`, `repo` and `web` green. Built without a design council or a
UX pass and with QA after merge, by the maintainer's choice for this run. Counts, base to head: static checks 90 to 99, live
probe 204 to 225, archive probe 38 to 40, `:inspector-daemon:test` 271 to 299 (28 new cases in `ConfigFileTest`,
`ConfigRoutesTest`, `PeerBulkKillTest`, `SessionBulkDeleteTest` and `WrongMethodStatusTest`). `docs/ROADMAP-DONE.md` §6 has the detail.

**Six items are done** (2026-10-21, on `main` at `ff4f9de`; each ticket Done on QA's pass over detached
copies, the items built without QA before merge by the maintainer's choice). CI on each PR and on the
merge pushes: `build`, `release-swap`, `repo` and `web` green; the push runs for `1da0d75` and `1479c30` were
queued or in progress when QA wrote and were not seen finishing.
- `item:structure-split-mcp-test` (KIN-59, PR #102, `49735be`): `McpTest.kt` split into seven test files and
  a fixture; 268 daemon tests before and after, 43 of 43 test bodies byte-identical.
- `item:structure-phase-h` (KIN-61, PR #103, `4dc21d2`): the two overlay list files split into four, all under
  600 lines; 134 jvmTest tests before and after, 12 of 12 360dp screenshots byte-identical.
- `item:row-label-port` (KIN-60, PR #105, `1da0d75`): labels keep a non-default port through the new
  `NetworkTransaction.hostPort`; daemon 268 to 270, model 159 to 160.
- `item:structure-phase-i` (KIN-62, PR #106, `1479c30`): `api/inspector-ui-public-api.txt` asserted by both
  modules of the ui pair, the three theme types `internal`, `docs/INTEGRATION.md` v59.
- `item:web-now-strip-follows-session` (KIN-57, PR #107, `6804c94`): the signal side follows the session; live
  probe 200 to 204.
- `item:web-list-says-what-it-holds` (KIN-56 with KIN-72 and KIN-73, PRs #104 and #110, `fb8e54e` and `ff4f9de`):
  QA's first round failed two criteria, the second passed. `docs/ROADMAP-DONE.md` has each item's detail.

**`item:web-live-calls-reach-every-pane` is done** (2026-10-21, on `main` at `433f30d`; KIN-58, PRs #113 and #117, merges `952b6c6`
and `433f30d`). Done on QA's evidence in two rounds: round 1 failed one criterion (after a pause, two calls and a resume, the
picker's option and face read 118 under a header of 120), fixed in #117 and verified in round 2. Built without an architect or QA
before merge, by the maintainer's choice for this run. CI on PR #117: `build`, `release-swap`, `repo` and `web` green; the push run
on `main` at `433f30d` was queued when QA looked and was not seen finishing. Counts, base to head: static checks 96 to 99, live
probe 219 to 225 (the fix round strengthened a line and added none), archive probe 40, `:repo-checks:test` green; no Kotlin change.
`docs/ROADMAP-DONE.md` §3 has the detail.

### First real-app findings (2026-08-16, a consuming app on an Android emulator)

The first session recorded from a real app surfaced four defects. All are fixed; they are listed
because each was invisible to the suite at the time, and the shape of each gap matters more than
the fix.

1. **Live viewers saw every body as missing.** `SessionWriter.append` returned Unit and
   `SessionManager` broadcast the row *as received*, where `*BodyRef` is null by wire contract.
   The bodies were on disk the whole time. Nothing tested the live payload — `LiveTest` now does.
2. **The UI invented a reason.** Any null body rendered as "content type outside the capture
   allowlist", which was flatly wrong for the case above. Capture now records
   `reqBodyOmitted`/`resBodyOmitted` and both UIs render what was recorded, never a guess.
3. **`reqContentType` was null on every request.** It was read from the builder's headers, but
   Ktor keeps it on the `OutgoingContent`. This also silently disabled request-body redaction,
   which bails on anything not declared JSON — so `Redaction.On` was not redacting request bodies
   at all.
4. **Two dead daemons.** A failed bind left the JVM alive still printing "listening on …". The
   port is now probed before anything is printed.

The lesson worth keeping: **the live path and the REST path are two renderings of one row, and
only the REST one was tested.** Any new field on `NetworkTransaction` needs a check on both.

### Verified

- **With live on, a call reaches the timeline, the glance and the selected picker option, without a reload, in headless Chrome 131
  at 1440, 1000 and 400 px, against a real daemon fed over `/ingest`.** At `433f30d` (QA, round 2): a live call is drawn by +500 ms
  with no other action; scrolled back, `scrollTop` is unchanged (1421 to 1421, 1456 at 400) and at the end the pane follows; 100
  calls over about 1.2 s cost 5 timeline redraws and 0 `/api/sessions` reads (bound ceil(T/250)+1); the glance's `calls` equals the
  header with no filter (14) and after the burst (116), and under `status>=500` it reads the session (16) beside `4 of 16 calls`;
  after a reconnect 122 equals the daemon's total; after a pause, two calls and a resume, the header, glance, option and face
  read 120 together, and paused or for another session nothing moved. Red by QA: `drawLive()` removed (`socket call drawn`,
  `live timeline plain`, `live glance plain`, `live glance filter`), `followLiveCount()` removed (`live picker count`), a redraw
  per call (`live draw burst of 100`), and `setLiveTail` put back to `952b6c6` (`live paused still`).
- **Web UI shell (1a), in headless Chrome 131 and the probes, at 1440, 1000, 600 and 400 px, live and in an export.** Rail and
  hash routes (Traffic, Sessions, Processes, Settings; an export keeps Traffic only and falls back); Sessions, Processes and
  Settings pages; bulk kill and bulk delete answering per item, each on a real daemon (a real peer killed, a live non-inspector
  process refused and left running, the recording session refused); `config.json` writable through `PUT /api/config` (the
  file on disk read back, unknown keys kept byte for byte, a malformed file left untouched with a 409). Top bar and tab bar fit
  at 400 px with the count whole, filtered too (`measure-web-ui.js --base`: 229 readings, 2 failed, both KIN-42's and failing at
  base). The lost-daemon banner shows on a first drop, a second drop and a restart that never returns, with real process deaths.
  Guards proven red by QA: a second control-header literal, the `running` check put back on the banner, an input in `#rail`, MCP
  in a preset, each route un-gated, the active-session check dropped, and (round 2) the old `85-processes.js` against the
  strengthened `processes bulk`. Daemon loopback: one listener, `127.0.0.1`; the LAN address and `::1` refused.
- **The ui pair's public API is asserted for equality, JVM only.** At `1479c30`, `UiApiParityTest` ran in both
  `:inspector-ui` (136 tests) and `:inspector-noop-ui` (2) with 0 failures; the golden file holds the
  `InspectorOverlayKt` facade and one composable. Twelve breaks went red and were restored: a public function
  added to either side, an `InspectorOverlay.kt` renamed in either module, the three theme types made public
  again, a contract class left empty, a dropped default, a renamed parameter, and a golden-only edit with no
  `--rerun`. The core and stream golden files are byte-identical after regeneration. The thirteenth, a changed
  `content` type, is the builder's record only. A public declaration that exists only in `iosMain` or
  `androidMain` is not guarded.
- **Phase H moved nothing anyone sees**, on the JVM and desktop only: 134 `:inspector-ui:jvmTest` tests before and
  after, the 12 360dp screenshots byte-identical under `--rerun` (two read by eye), `InspectorOverlayKt`
  identical under `javap -public`. Phase I's screenshots are byte-identical too.
- **Row labels keep a non-default port**, against real daemons built from base and head, nine call shapes
  through `/ingest`: REST, `export.md`, `export.json`, `export.html` and MCP `timeline` and `explain` over stdio.
  `url` is byte-identical to base for all nine. Three breaks went red and were restored.
- **The web now strip, timeline signal rows and glance show none of the previous session's values while a new
  session loads, in headless Chrome 131 and the web probes only.** `now switch shows none`, `now newest read
  decides`, `now load read overtaken`, `now failed read kept` and `now late reply dropped` pass in the 204 live
  checks; four mutants each failed only their own lines. Picker and delete paths driven in Chrome at 1440 and 400.
- **The web list says when it holds only part of what it counts, in headless Chrome 131 and the web probes only.**
  At `ff4f9de`, on a 3016-call session: `newest 1000 of 1200 matches shown` under a filter at 1440, 1000 and 400,
  also with a stretch brushed; the sticky list note; a stretch older than the loaded rows names how to reach it and,
  under a filter, claims no count. `LIST_CEILING` and the daemon clamp unchanged, 1000 rows drawn.
- **`SignalToolsTest` and `ExportTest` pin the labels**; the split test files hold 43 tests that moved word for word.

- **With a filter set, a live call or signal the filter does not match is not drawn in the list,
  timeline or path, and the header and traffic tab follow live calls, in headless Chrome 131 and the
  web probes only.** Working tree on `4e5491c`: `live rows list/timeline/path`, `live rows whole`,
  `live free no filter`, `live paused no read`, `live header plain` and `live header filter` pass in
  the 197; the burst bound (100 rows in 1003 ms: 3 list reads; with reads held 1.5 s: 6, one at a
  time) was measured in Chrome and is `reread burst of 100` in the static checks. Filter edit, session
  switch and a dropped socket during a re-read end on the daemon's own rows. A whitespace-only filter
  behaves as no filter (`blank filter header`, `blank filter free`, and curl `filter=%20%20%20` =
  16 of 16).
- **The web list draws the newest 1000 calls and says so, and an export draws every embedded call,
  in headless Chrome 131 and the static checks.** At `68315b4`: static 79/0, live probe 185/0
  (`long count no filter` reads `newest 1000 of 1203 shown`; `long chips count oldest` and `long repeat
  marked` read past the clamp), archive probe 37/0 (`1203 of 1203 calls`, `long stretch oldest`, `long
  offset 1000 sliced`), `:inspector-daemon:test` 268/0, `:repo-checks:test` 35/0. Live rows arriving
  during the whole-session read are kept once (`mid-read ... kept/once/empty`, static). Each red proof
  re-run in round 2.
- **The top-bar count stays on one line from 600 to 1100 px, in headless Chrome 131 only.** 21 widths
  against base `c21aaf0`: no control that base had inside the viewport is outside it at head.
  Numbers are in KIN-49's notes. Stop is still clipped at 800 px (off-screen at base).
- **The web UI follows live rows without moving a scrolled-back pane, in headless Chrome 131 only.**
  `scripts/measure-web-ui.js` against a snapshot of `8082e02`: 184 readings, 22 failed at base and 2 at
  `8f64058`. The 20 readings that moved are all "scrolled back: moves -> stays" (timeline and waterfall,
  live call and marker, both orders, 1440 and 400 wide; path at 400). The pane at the end still follows,
  and the order toggle, a filter load and a brushed range still follow. The 2 left are KIN-42's.
- **The web UI reads every signal, not the first 1000, and a filter reaches them.** `SignalQueryTest`
  (route and `list_signals` return the same ids in the same order; 1205-signal paging) and the web
  probes at `8f64058` (static 71, live 182, archive 26). The 500-signal page break is invisible in CI,
  whose fixture holds 19 signals; the engineer reproduced it on a 1219-signal session, and no QA panel
  re-ran that.
- Redirect and retry each produce one row per attempt, sharing a `callId`.
- App receives byte-identical bodies with the inspector installed: 10 MB, gzip, chunked, empty,
  binary.
- `/secret` shows credentials verbatim by default, redacted under `Redaction.On()`.
- Ring buffer evicts by byte budget; a single oversized entry is still kept.
- `-Pinspector=off` yields a runtime classpath containing only noop modules.
- Canary guard fails when capture code is present and passes when it is not — **both directions
  proven**.
- `./gradlew :sample:desktop:run` launches and serves traffic.
- End-to-end: sample streams to the daemon, 40 transactions archived to
  `~/.inspector/sessions/<ts>_<app>_<device>_<build>/`, `latest` symlink correct, bodies written
  to `bodies/`, `index.jsonl` greppable from a terminal.
- REST filters evaluate server-side with the shared grammar; a bad filter returns 400 carrying
  the parser's own message.
- Killing the daemon mid-session leaves the app unaffected; reconnecting resumes the same
  session folder; a relaunch gets a new one.
- Retention prunes by count and by bytes, never the active session.
- **The web UI renders.** `scripts/render-web-ui.js` runs the real `app.js` against the real
  `index.html` with fetch proxied to a live daemon and reports what actually rendered: rows,
  marker dividers, session picker, detail pane, attempt chain, method classes, console errors. It
  drives the order control and asserts the rendered order reverses, restores, that exactly one half
  reads as selected, and that the row/divider sequence is an exact mirror. It clicks an endpoint
  chip and asserts three things: the row count narrows, the chip list *survives* (proving chips
  come from the unfiltered session), and exactly one chip goes active. It then drives the settings
  input to prove the cap applies and `0` hides them. It walks the tab bar, and for each tag browser
  asserts keys, facets, a pretty-printed value, history switching, and that the key filter and
  facet chips each change the list and toggle back off. It jumps the *window's* clock to prove ages
  tick — `window.Date` is not Node's `Date`, and overriding the wrong one silently proves nothing.
  It audits the stylesheet text for panes that set `display` without a `[hidden]` override, because
  jsdom resolves a hidden element to `display: none` whatever the CSS says, so the computed-style
  version of that check passes with the guard deleted. `INSPECTOR_UI_SESSION=<id or substring>`
  targets a session other than the newest, which is otherwise whatever ran last on the machine.
- **Daemon stop and restart, against a live daemon** — not only the unit tests. An unheadered POST
  is refused 403 and the daemon survives; restart swapped one pid for another in about a second
  with the whole archive still served; stop released the port and left no `serve` process.
- **A live viewer receives body refs**, so bodies are fetchable without a page reload
  (`LiveTest`). This is the defect the first real user hit.
- **OkHttp capture, end to end.** The sample makes one call through a plain `OkHttpClient` with no
  Ktor anywhere in its path; it reaches the archive as
  `GET /json?via=okhttp → 200` with its body on disk and header case preserved. The app receives
  byte-identical bodies with and without the interceptor across json, gzip, binary, 4 MB chunked
  and empty.
- **The MCP server answers the acceptance question in three calls**, verified both as a unit test
  and by piping JSON-RPC frames into the built binary against the real recorded session.
- **`adb reverse` reaches the daemon from inside a device.** Against a live daemon on a booted
  emulator, which speaks the same adb protocol as hardware: `127.0.0.1:8099` from inside the
  device was refused before `adb reverse tcp:8099 tcp:8099`, answered `HTTP/1.1 200 OK` with the
  daemon's own body after it, and was refused again once the forward was removed. `10.0.2.2`
  answered throughout, and `lsof` showed the daemon still bound to `127.0.0.1` alone — so the
  loopback boundary is genuinely untouched rather than assumed to be.
- **A physical iPhone streams to the daemon over USB** (iPhone 12 Pro, iOS 26.5, 2026-09-24). The
  real iOS sample, built for `iosArm64` and signed for the device, listened on the phone's
  `127.0.0.1:8099`; `inspector serve`'s bridge dialled it through usbmuxd and 188 transactions
  reached the archive as `platform: ios-device`, bodies on disk, retries and redirects intact, while
  the daemon stayed bound to `127.0.0.1` alone. The other direction too: a replay from the host came
  back with `resignedHeaders` that only the app's `ReplaySigner` on the phone produces, so a
  `SignRequest` crossed the cable and its answer returned. Killing a linked app and relaunching it
  reconnects in about a second — after the TIME_WAIT fix in `docs/decisions/devices-and-stream.md`; before it, 25 seconds of
  `EADDRINUSE`.

- **Phase A's web checks, run locally (2026-10-18, three independent QA passes).**
  `scripts/check-web-static.js` passes 20/20. The live probe against a daemon on 8231 holding the
  generated probe session: 164 passed, 0 unclassified, exit 0. The archive probe: 21 passed. Each guard
  category was re-broken by QA and went red: a second `fetch`, `[hidden]`, 11px, the JS mirrors, the
  Kotlin mirrors, the `inputs.dir` declaration, a missing anchor, a reverted drift, the archive
  data-versus-code check, the item-2 checks, `esc restores glance` (all three breaks) and the hidden
  scrim. `writeProbeFixture` is idempotent, and a JSON-only change re-runs the tests. The four `app.js`
  drifts and the empty-query `?` case are fixed, with `app.js` the only product file changed.

- **Phase E's MCP ordering, run locally (2026-10-18, two independent QA passes).** Daemon tests 224 and
  `:inspector-model:jvmTest` 159, 0 failures. Five breaks went red: the old sort, the daemon's
  `thenBy seq`, the old `mono` split in `explain`, a clamped `explain`, and `splitAround` returning
  empty instead of null. The built `inspector mcp` binary, driven over JSON-RPC against a hand-built
  tie archive, ordered entries as the model does. The 2,000 clamp is kept. `DAEMON.md` §8 "1.3.0 and
  earlier" was checked against the `v1.3.0` tag.

- **Nested agent guides load in a subagent on Read (phase G, 2026-10-18, QA probe).** In a
  subagent, Reading a file under a module injected that module's guide and the guides of its parent
  directories, with their relative `@` imports expanded, and no approval prompt appeared. Bash
  commands did not load them. Still unverified: whether a fresh subagent that never Reads a file
  gets them, which is why the role prompts name the decision files to Read.

- **The manifest-split web UI is indistinguishable from the single-file one, in headless Chrome 131
  only.** 16 configurations (widths 1440, 1000, 1000 with the drawer open and 400; dark and light;
  reduced motion on and off), served by the daemon and exported under `file:`: computed-style, body text
  and PNG hashes identical to base `f574bda`, 0 page errors. Route matrix against base differs only on
  `/app.js`, `/style.css`, `/js/*` and `/css/*`; `PUT` on a served file is 405, an unlisted file and an
  encoded traversal are 404.

### Not verified

- **The bulk routes and `PUT /api/config` sit behind the control gate only.** The private advisory's fix is not built
  (`item:control-boundary-advisory`, last in the order). The new routes leave room for it, and the gaps are not described here.
- **1a's shell in Safari, Firefox and on a phone.** Only headless Chrome 131 was driven. Nobody has judged how the new rail and
  pages *look*: the builder and QA looked at screenshots, no UX review ran, and no design council sat on this item.
- **For 1a, the full `./gradlew build` and `-Pinspector=off`** were not run on the QA or builder machine (no Android SDK); CI ran
  them. The `measure-web-ui.js` `first` section (the 160k-call session) was not run. The engineer's `processes bulk` probe stubs the
  kill reply; the real case of a peer exiting before the kill was seen only by QA, in round 2.
- **The "recovers" probe's red proof** is the engineer's record in PR #112's body; QA did not repeat it.
- **Known and left for 1b:** the closed detail drawer sits off-screen right at 1000 and 400 px and widens the page (1646 px at 1000).
  **Older, not 1a's:** the Sessions page says sessions live under `~/.inspector/sessions` even with `--data` elsewhere.
- **The full `./gradlew build` for the six items of 2026-10-21 was not run on the QA machine** (no Android SDK).
  It rests on CI, which was green on each PR. For phase H and phase I the `-Pinspector=off` build on the Android
  and iOS targets, the iOS framework and test links (no working `xcrun`) and the klib half of
  `check-release-clean.sh` also rest on CI alone; locally only the JVM artifacts were scanned (self-test found the
  canary, the plain run found nothing). Phase I's Android and iOS targets were not run locally.
- **The push runs for `1da0d75` (row labels) and `1479c30` (phase I) were not seen finishing** by QA.
- **Nothing in phase H or phase I was looked at on a device**; the 360dp screenshots are the evidence.
- **The ui pair's guard covers the JVM only**: a public declaration in `iosMain` or `androidMain` alone passes on
  every pair. Applying the completeness check to `core` and `stream`, and declaring their golden files as test inputs
  (a golden-only edit can hit the build cache and stay green), are follow-ups for the product-owner.
- **The R12 proof command in the phase I design note** (`:inspector-core:jvmTest :inspector-stream:jvmTest
  -Dinspector.api.regenerate=true --rerun`) re-ran stream only. The result holds when run with `--rerun` after each
  task; the note is not in the repository.
- **IPv6 call labels and URLs are unbracketed** (`http://::1:8080/v6`), at base and head, from a synthetic call; a
  real captured IPv6 call was not checked. The session summary's `byHost` still merges `:8080` and `:9090`.
- **In the window after a session switch the timeline's call rows and endpoint chips still show the session just
  left**, true at base. The path, waterfall and tag browsers are not redrawn by the switch either (by inspection;
  not rendered).
- **A stretch under a filter holding some loaded rows and some older unloaded matches** says `at least 10 calls
  hidden` and not that more matches are older. At 400 px the header count is off-screen; at 1000 px the closed
  picker drops the start time (ROADMAP 1a (e), 1b).
- **The two items' web changes in Safari, Firefox and on a phone**: only headless Chrome 131 ran them.

- **`item:web-live-rows-filtered` in Safari, Firefox and on a phone**; only headless Chrome 131 ran it.
  `-Pinspector=off` was not run (no consumer signature changed). Not on CI: the branch is not pushed.
- **The burst bound counts re-read requests only.** Each live signal also reads `/current`, with or
  without a filter (50 signals gave 53 requests in total); that predates the item.
- **The probe's restore has no guard of its own.** Deleting the restore of `state.lastCaptureTs` and
  `state.expandedVisits` alone leaves 197/0; a later probe that opens a path visit could empty
  `failures unfolded` again without anything failing.
- **The page and the daemon disagree on two invisible characters**: U+FEFF (the page sends no filter,
  the daemon would answer 400) and U+001C (the daemon reads it as no filter, the page as a filter).
  Spaces and the usual whitespace agree.
- **`state.reread.busy` is reset only when the read finishes**, so a request that never settles would
  stop re-reads until a reload. Not observed.
- **`item:web-transactions-paged` on CI**: the branch is not pushed. KIN-43 AC#4 ("passes with it in
  CI") is the one criterion left unticked for that reason. `-Pinspector=off` was not run (no consumer
  signature changed).
- **The archive half of KIN-44's red proof** is retired, not met: offline the copy is the list, so the
  pager is never called. Offline paging is guarded instead by `long offset 1000 sliced`.
- **Whether the whole-session read should be capped** is KIN-42's, deferred; the read is still awaited
  and uncapped.
- **Closed session picker at 600-700 px** can read `com.example.`, so two sessions of one app look
  alike (ROADMAP 1a (e)).
- **A ~160,000-call session shows a false empty state for about 25 s before the first rows**, in
  headless Chrome (rows at 26 to 27 s, connected at 26 to 29 s, over the 3000 ms bar). KIN-42, introduced
  by KIN-34, deferred by the maintainer on 2026-10-19.
- **`item:web-live-calls-reach-every-pane` in Safari, Firefox and on a phone**; only headless Chrome 131 ran it. **An open run staying
  open under a live call** was checked in jsdom only (`socket run stays open`), and that line stays green with the redraw removed, so
  it guards the follow rule, not the redraw. **A live signal or marker** still redraws the timeline once per message, so a signal
  burst is unbounded the way calls were; the builder proposed routing both through `drawLive`, and it is unplaced. **The full
  `./gradlew build`** was not run on the builder or QA machine (no Android SDK); CI ran it on both PRs.
- **Follow behaviour on a live socket in Safari, Firefox, or on a real phone-width browser.** Only
  headless Chrome 131 was driven, at 1440 and 400.

- **The merge commits for PRs #84 to #91 carry a work address on `main`**, so the `repo` job was red on
  each of those pushes. The cause is the merge dialog's author setting, not the branches. #89 relaxed the
  check; it did not rewrite those commits or change the setting.
- **Phase F in Safari and Firefox.** Only Chrome 131 was driven, the Android host tests need an SDK this machine lacks, and the no-client-names term list is not here.
  Nobody has looked at the split page: hash equality proves "identical to before", not "looks right".
- **Six web UI layout defects found at base by UX, unfixed** (closed drawer focusable at 1000px and
  widening the page; endpoint cut at 400px; drawer close button over the header's last action at
  1000px; the closed drawer's shadow strip; top bar and tab bar overflow at 400px; small items). They
  predate phase F; `docs/ROADMAP.md` §5 hands them to the product-owner.
- **The Gradle cache carrying over between checkout paths** for the mirror-case test inputs is
  unproven.
- **Signal ties still ignore `seq`**, in the model and so in MCP (recorded under ROADMAP §3).
- **The Auth0 adapter in `docs/INTEGRATION.md` §11 compiles but has never run.** It is now
  written against the real `auth0-android` 3.12.0 sources (read out of the Gradle cache) and
  compile-checked against that jar plus OkHttp 4.12.0 and Gson, so the types and the
  body-construction logic are right. What is unproven is behaviour against a live tenant.

- **The overlay has now been seen on a phone once**, and every one of the four things that came
  back was a real defect: it drew under the status bar and camera cutout, there was no way back to
  the app, and nothing but cURL could be copied. All four are fixed; **the fixes themselves have
  only been compiled and exercised on desktop**, where insets are zero and there is no back
  gesture, so the parts that matter most are still unverified on a device.
  The list layout that followed was checked the same way with one addition — `ListRenderTest`
  renders it off-screen at 360dp, which catches what a desktop window cannot, because a desktop
  window is never 360dp wide. It is still not a device.
- **There are Android and iOS sample apps now, and the overlay has been seen running on both** —
  an emulator and an iOS simulator. The list, the clock column, the control strip, marker dividers
  and chips, signals and a collapsed run, on a real screen with a real status bar and a Dynamic
  Island. What that does *not*
  cover is hardware: a physical cutout, a vendor skin, or `adb reverse` with a cable in it. The one
  physical phone so far is an iPhone, and it was driven for the USB route above — the overlay was
  running on it the whole time, but nobody *looked* at it there, so the Dynamic Island and inset
  fixes are still unverified on hardware. No Android build has run on a physical phone.
- **The iPhone route's edges.** Verified: kill and relaunch. Not verified: pulling the cable
  mid-session (expected to look identical — the link sees EOF either way — but not tried); an app
  put in the background after an ordinary home-screen launch (every run was launched by
  `devicectl`, which may keep it from being suspended, and in the spike a backgrounded app still
  answered after 30 s for possibly that reason); two apps on one phone; Wi-Fi-paired devices,
  which the bridge skips by design.
- **Nobody has judged how the web UI *looks*.** It provably renders the right elements (see
  above), but no human has assessed spacing, colour or density. The browser pane is blocked from
  localhost by policy in this environment, so only a static snapshot has ever been produced.
- Redirect behaviour against engines other than CIO. Some engines follow redirects internally,
  which would collapse a chain into a single row. Unresolved for the target app's engine.

### Also outstanding

- **Someone should look at the web UI** and say what is ugly. It renders correctly; nobody has
  judged it.
- **Android + iOS sample shells**, to finally see the overlay on a device. Needs an Android app
  module and an Xcode project; the UI module already compiles for both.
- ~~**Stretch:** HAR export.~~ Built — `GET /api/sessions/{id}/har`, see the rule in `docs/decisions/exports.md`.
