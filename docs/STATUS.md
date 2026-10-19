# Status — what has been checked, and what has not
Moved here from the root `AGENTS.md` so the root stays an index; the delivery tracker keeps it current.

**Last full count: 886 tests, 0 failures** across JVM, iOS simulator, Android host and the daemon.
Phases A and E (below) added tests since, and **no full run has been counted since**: `:inspector-model:jvmTest`
ran 159 tests and `:inspector-daemon:test` ran 224, 0 failures each (2026-10-18), and nothing else
was run. A branch-wide total of 897 was computed by arithmetic, never measured, and is not claimed.

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
`/current` read always empties the strip, also on an unchanged session.

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
- **A live call never redraws the timeline**: the socket's `txn` branch does not call `renderTimeline`.
  Predates the signals item and is unplaced (ROADMAP §3).
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
