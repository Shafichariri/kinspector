# Status — what has been checked, and what has not
Moved here from the root `AGENTS.md` so the root stays an index; the delivery tracker keeps it current.

**Last full count: 886 tests, 0 failures** across JVM, iOS simulator, Android host and the daemon. It is
old, and **no full run including iOS and Android has been counted since**; a branch-wide total by arithmetic is not claimed.
**Counted by the tracker on 2026-10-23 at `1b7a45b` plus the sixth close's doc and ticket edits** (JUnit XML after `./gradlew :inspector-model:jvmTest --rerun :inspector-core:jvmTest --rerun :inspector-ui:jvmTest --rerun :inspector-noop-ui:jvmTest --rerun :inspector-noop:jvmTest --rerun :inspector-stream:jvmTest --rerun :inspector-noop-stream:jvmTest --rerun :inspector-daemon:test --rerun :repo-checks:test --rerun`, every one of the nine test tasks executed, none from cache): **1,006 tests, 0 failed, 0 skipped** across those nine JVM targets: model 162, core 76, ui 136, noop-ui 2, noop 4, noop-stream 2, stream 4, daemon 582, repo-checks 38. `node scripts/check-web-static.js` read 139 checks, 0 failed. Not included: the Android host tests, the six `iosApiTest` tasks (1 test each; QA ran them green at `c62d39e`, the tracker did not), the iOS simulator tests and the full `./gradlew build` (CI runs them); neither live probe was run by the tracker (QA read the live probe 321 passed, 0 failed, 1 n/a and the archive probe 61 at `1b7a45b`). Against the count before it, the daemon is 50 tests up (the session diff's ten test classes, `ProbeFixtureTest` growing to eight) and repo-checks is unchanged at 38; the other seven modules are unchanged.

The count before it, **by the tracker on 2026-10-22 at `52a36ba` plus the fifth close's doc and ticket edits** (JUnit XML after the same nine `--rerun` test tasks as below, every one executed, none from cache): **956 tests, 0 failed, 0 skipped** across those nine JVM targets: model 162, core 76, ui 136, noop-ui 2, noop 4, noop-stream 2, stream 4, daemon 532, repo-checks 38. `node scripts/check-web-static.js` read 133 checks, 0 failed. Not included: the Android host tests, the iOS simulator tests and the full `./gradlew build` (CI runs them); neither live probe was run by the tracker (QA read `render-web-ui.js` 299 passed, 0 failed, 1 n/a and the archive probe 58 at `445408e`; `ci.yml` pins 299 and 58). Daemon is one more than before (T5's test) and repo-checks two more (`PublishedPomTest`).
The count before it, **by the tracker on 2026-10-22 at `da5c97d`** (JUnit XML after `./gradlew :inspector-model:jvmTest --rerun :inspector-core:jvmTest --rerun :inspector-ui:jvmTest --rerun :inspector-noop-ui:jvmTest --rerun :inspector-noop:jvmTest --rerun
:inspector-stream:jvmTest --rerun :inspector-noop-stream:jvmTest --rerun :inspector-daemon:test --rerun :repo-checks:test --rerun`, with this close's doc edits in the tree; every one of the nine test tasks executed, none from cache): **953 tests, 0 failed,
0 skipped** across those nine JVM targets: model 162, core 76, ui 136, noop-ui 2, noop 4, noop-stream 2, stream 4, daemon 531, repo-checks 36. Not included: the Android host tests, the iOS
simulator tests and the full `./gradlew build` (no Android SDK or working `xcrun` here; CI runs them). `node scripts/check-web-static.js` read 133 checks, 0 failed; neither
live probe was run by the tracker (QA read `render-web-ui.js` 289 passed, 0 failed, 1 n/a and the archive probe 56 at `f4bbfbf`; `ci.yml` pins 289 and 56).
Latest per-target counts from QA before that, each at the commit named and none a full-repository total:
`:inspector-model:jvmTest` 162/0 and `:inspector-core:jvmTest` 76/0, 0 skipped, at `66c1bad` (QA, `item:host-port-everywhere`; 160 and 74 on its
base `623c571`); `:inspector-daemon:test` 363/0 at `ffc9278` (QA, `item:mcp-probe-deadline-holds`) and again at `85e9552` (the tracker, JUnit XML, `:inspector-daemon:test --rerun`); 362/0 at `66c1bad` (360 at `74f5ca0`, QA, `item:guard-mcp-probe-cleanup`; 357 at `0a5a1c9`, QA, PR #115);
`:inspector-ui:jvmTest` 136/0, `:inspector-noop-ui:jvmTest` 2/0, `:inspector-noop:jvmTest` 3/0, `:inspector-stream:jvmTest` 3/0,
`:inspector-noop-stream:jvmTest` 1/0 and `:repo-checks:test` 35/0 at `1479c30`. At `623c571` (QA, `item:guard-twin-pairs-alike`) each of the four
core and stream modules ran two parity tests, 0 failed, so the noop and stream totals above are one higher there (`every_public_declaration_is_in_the_contract`);
their module totals were not recounted. `:repo-checks:test --rerun` was green at `623c571`, `66c1bad`, `7b37d7a` and `5b21593` (QA, count not recorded); the tracker
counted 36/0 from the JUnit XML at `5b21593` plus these doc edits, which is one more than the 35 above (`NoopTwinTest.every_pair_names_a_golden_file_that_exists`), and again 36/0 at `85e9552` with the doc edits of this close. Web static checks 116/0 at `85e9552` (the tracker ran `check-web-static.js`; QA read 116 at `1888380` and `95b4de4`; 112 at `4645c43`, 110 at `7b37d7a`);
live probe 262 passed 0 failed 1 n/a and archive probe 48 at `1888380` (QA, `item:signals-query-one-rule`'s KIN-42; 260 at `4645c43`, QA, `item:web-switch-clears-call-panes`) and live probe 263 passed 0 failed 1 n/a at `95b4de4` (QA, `item:docs-housekeeping-2026-10-21`, the Sessions page line; `ci.yml` pins 263). Web static checks 110/0 at `7b37d7a` (QA, `item:web-stats-route-breaks-whole`; 109 at `66c1bad`, 107 at `b1bbcac`);
live probe 251 passed 0 failed 1 n/a and archive probe 48 passed at `b1bbcac` (QA, `item:guard-probe-finds-nothing-fails`, round 1; the same counts at
`d256174`, QA, PR #121). PRs #127, #129, #132 and #133 left both probes' counts alone, and neither probe was recounted after #133, which changed only
Chrome-case scripts. At `5b21593` QA ran `check-web-static.js` (110 checks, 0 failed) and `measure-web-ui.js --only shell,room` on the fixture (61 readings,
0 failed). At `85e9552` only `:repo-checks:test` and `:inspector-daemon:test` were run for this count; neither probe was run by the tracker. Not included in any of these: the Android host tests and the iOS simulator tests, because the QA machine
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
passed, archive probe 26 passed. KIN-42 closed 2026-10-22, PR #136 (see below). Not run for this item:
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

**`item:web-foundation-list-room` (1b) is done** (2026-10-21, on `main` at `4fe71dc`; PR #119; KIN-78 to KIN-86). Done on QA's evidence, one round,
no blocking findings. CI on PR #119: `build`, `release-swap`, `repo` and `web` green; the push run on `main` at `4fe71dc` was in progress
when QA wrote and was not seen finishing. Built from the architect's design note without a design review and without a UX pass, and with QA
after the merge, by the maintainer's choice for this run. Counts, base `1e9d720` to head: static checks 99 to 102, live probe 225 to 236,
archive probe 40 to 43; no Kotlin change. `docs/ROADMAP-DONE.md` §6 has the detail.

**Six more items are done** (2026-10-22, on `main` at `5b21593`; each ticket Done on QA's pass over detached copies, the items built without QA
before merge, one engineer per item, by the maintainer's choice for this run). CI on each PR: `build`, `release-swap`, `repo` and `web` green. The push
runs on `main` for `b1bbcac`, `9fb0470`, `74f5ca0`, `623c571`, `66c1bad`, `7b37d7a` and `5b21593` were queued or in progress when QA looked and were not seen
finishing. `docs/ROADMAP-DONE.md` §3 has each item's detail and cost line.
- `item:guard-twin-pairs-alike` (KIN-90, PR #125, `623c571`), `item:guard-mcp-probe-cleanup` (KIN-91, PR #126, `74f5ca0`) and
  `item:guard-live-traffic-driver` (KIN-88, PR #127, `9fb0470`): one round of QA each, no blocking findings.
- `item:guard-probe-finds-nothing-fails` (KIN-87, PRs #128 and #133, `b1bbcac` and `5b21593`): QA's first round failed criterion 2 (`shell <w> rail` passed
  over no rail item, and the inventory had missed four Chrome case files); the fix round passed.
- `item:host-port-everywhere` (KIN-92, PR #129, `66c1bad`) and `item:web-stats-route-breaks-whole` (KIN-93, PR #132, `7b37d7a`): one round each.
  Counts, base to head, are in the paragraph above. KIN-89 (`item:docs-housekeeping-2026-10-21`) stayed open for its Sessions page line; it closed below.

**Five more items are done** (2026-10-22, on `main` at `85e9552`; each ticket Done on QA's pass over detached copies, built without QA before merge, one engineer
per item, by the maintainer's choice for this run). CI on each PR: `build`, `release-swap`, `repo` and `web` green. The push runs on `main` for `4645c43` (succeeded),
`1888380` (succeeded), `ffc9278`, `95b4de4` and `85e9552` (the last three in progress when QA looked) were not all seen finishing. `docs/ROADMAP-DONE.md` has each item's
detail and cost line. No QA round failed.
- `item:docs-housekeeping-2026-10-21` (KIN-89; PRs #124, #130, #140; `6c1cb32`, `e071594`, `95b4de4`): the `INTEGRATION.md` headings, the `AGENTS.md` S row (14,606 bytes), the
  `architect.md` golden line, and the Sessions page naming the daemon's own sessions folder. QA passed part 1, then the whole item.
- `item:mcp-probe-deadline-holds` (KIN-96, PR #138, `ffc9278`), `item:guard-measure-chrome-exit-fails` (KIN-99, PR #141, `85e9552`),
  `item:web-switch-clears-call-panes` (KIN-94 and KIN-95, PR #135, `4645c43`) and KIN-42 of `item:signals-query-one-rule` (PR #136, `1888380`): one round each.
  `item:control-boundary-advisory` is not among them and stays open.

**Five more items are done, and two items are part done** (2026-10-22, on `main` at `4343823`; each ticket Done on QA's pass over detached copies, built without QA
before merge, one engineer per item, by the maintainer's choice for this run). CI on each PR: `build`, `release-swap`, `repo` and `web` green. The push runs on `main`
for `c608ec8`, `1796b96` (both succeeded), `e34542e` (in progress), `1ed50c1` (queued), `428745a`, `66ab63c` and `4343823` (in progress) were not all seen finishing.
`docs/ROADMAP-DONE.md` has each finished item's detail and cost line. No QA round failed.
- `item:docs-housekeeping-2026-10-22` (KIN-97) and `item:guard-expanded-unfolded-has-failures` (KIN-98), PR #143, `c608ec8`; `item:guard-measure-interrupt-cleans-up` (KIN-109),
  PR #148, `1796b96`; `item:route-key-one-ipv6-host` (KIN-100), PR #142, `1ed50c1`; `item:web-live-signal-burst-bounded` (KIN-101), PR #144, `e34542e`: one round each.
- **Part done, not closed:** `item:findings-payment-safety` PR A so far (KIN-111, KIN-112 in PR #151, `428745a`; KIN-113, KIN-114 in PR #152, `66ab63c`), with T4 (KIN-115) and
  everything after it open; and `item:web-ui-polish` PR A part 1 (KIN-102, KIN-103, KIN-108 in PR #150, `4343823`), with A3 to A6 open. KIN-110 (PR #149) was not merged and
  `item:guard-overview-band-reserved` stays open.

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

- **The Central bundle ships only `.md5`, `.sha1` and `.asc` (row 13g, PR #179, `936dbbf`; no ticket).** At `936dbbf`, base `5253bf3` (QA): `centralBundle` adds `exclude("**/*.asc.*", "**/*.sha256", "**/*.sha512")`, and that is the only change outside `docs/` and `backlog/` (`release.yml` is unchanged). Counted on the 28 non-Android publications of the seven modules (165 published files), staged with a throwaway key and then the real `centralBundle` task: **1,650 files before, 660 after**; every published file keeps its `.md5`, `.sha1` and `.asc` (checked file by file, 0 missing), and there is no `.sha256`, `.sha512` or `.asc.*`. The zip went from 3,438,063 to 3,091,647 bytes. The staging repository still holds all ten kinds (the filter acts at the zip), and `./gradlew tasks --all` lists the same 28 GitHub Packages publish tasks on both trees, so that publication is unchanged. With the seven Android publications (35 more published files) the same per-file rule gives 2,000 before and 800 after, which matches `docs/decisions/release.md`; that figure is extrapolated, not built. `build`, `release-swap`, `repo` and `web` passed on the PR; identity and name scans clean.
- **Row 29: a brushed stretch under a filter that reaches below the oldest match loaded says any older matches in it are not drawn and that narrowing the filter reaches them, with no count (`item:web-stretch-says-older-matches`, KIN-142, PR #184, `2af3168`).** At `2af3168`, base `c62d39e` (QA): `check-web-static.js` 135 checks, 0 failed, with `stretch below loaded matches`; live probe 306 passed, 0 failed, 1 n/a, with `long stretch below matches` on the 1203-call session; archive probe 60. By hand the note read `... newest 1000 of 1201 matches loaded - this stretch reaches below the oldest of the 1000 matches loaded, so any older matches in it are not drawn - narrow the filter to reach them - clear filter`; a stretch wholly above the oldest match loaded reads as before. At 1440 the note is 61 px; at 400 it is five lines, whole, with no sideways scroll. Red, each failing exactly the two lines and nothing else: the sentence removed (`olderMatchesNote` returning null), the below-loaded rule ignored, and a count put in the clause (the planted count came from the unfiltered copy, which is why the text says "any"). Chrome against base moved nothing; its one failure, `run toggle big 1440 oldest-first`, is the known flake and failed the same on base.
- **Row 28: `#/txn/<id>` has a visible `← traffic` that does what Esc does, live and exported (`item:web-txn-page-way-back`, KIN-141, PR #183, `abb3622`).** At `abb3622`, base `7f49e14` (QA): static 134/0; live probe 305 passed, 0 failed, 1 n/a (`txn back`); archive probe 60 (`txn back in export`). By hand, in a fresh tab opened straight to `#/txn/a0000002` (history length 1), a real click replaced the page with the session's list and history length stayed 1; through the expand button, the control went Back and Forward reopened the call. It is a button reached by Tab, 24 px tall, and its click handler is `leaveTxnFull` itself. In Chrome it is drawn and hit at 1440 and 400 (`polish <w> txn back`, served and exported). Red: control and wiring removed (live and archive fail, nothing else), always opening the list (the live line fails on Back and Forward), and the show rule removed (all four Chrome readings fail). Chrome head 346 readings, 1 failed (`run toggle big 1440 oldest-first`, a known flake, 6 of 6 ok alone); base 4 failed more.
- **Polish PR A, part 3: empty states say why, Settings shows the session's counts on a first load, and `routeTemplates` is editable on Settings (`item:web-ui-polish`, A4 to A6, KIN-105 to KIN-107, PR #181, `7f49e14`).** With parts 1 and 2, polish PR A is complete. At `7f49e14`, base `f59f3d4` (QA): static 134/0; live probe 304 passed, 0 failed, 1 n/a; archive probe 59; Chrome full suite head 342 readings, 0 failed, base 3 failed (`polish settings first load counts`, `polish 1440 empty whole`, `polish 400 empty whole`). Empty states: Stats on an ended session reads `no calls were recorded in this session` and on a recording one `the app is connected - no calls yet`; with no session the list's `waiting for an app with inspector-stream to connect`; the detail pane reads `select a call`; each sentence is whole at 1440 and 400. Red: the old literal back in `statsEmptyWhy`, `'no sessions'` back, `statsEmptyWhy` saying the sentence itself, and `white-space: nowrap` on the empty cells (`cut ... clipped by #page-sessions`). Settings: opened straight to `#/settings` it reads `3 rows in this session` and `showing 10 of 13 endpoints in this session` at both widths; red with the repaint removed, in jsdom and Chrome; `row(s)` appears nowhere in `web/`. The editor, end to end on a real daemon: an unchanged save made 0 PUTs; a changed one sent exactly the trimmed non-blank lines through `controlFetch`; `noslash` gave the daemon's 400 verbatim in `#config-status`; after a restart Settings read `running: 2 templates`. Red with the key always sent (four lines fail). The Stats note links `#/settings` live and names `config.json` in an export (two reds). No sideways scroll on Settings at 1440, 1000, 600 and 400. QA did not repeat the builder's Pace reds.
- **Twin guard on iOS and Android (`item:guard-twin-native-surface`, KIN-143 and KIN-144, PR #182, `c62d39e`).** A public declaration in one twin's iOS or Android source alone fails CI. *iOS, run locally on macOS (QA, Kotlin/Native 2.3.20 from the existing konan home, nothing downloaded):* six `iosApiTest` tasks, 1 test each, 0 failed; `check` depends on them; each reads both iOS targets' klibs with `klib dump-abi` from the distribution matching `libs.versions.kotlin` (not the stdlib's 2.3.21) and asserts against `api/*.ios.txt`. Red: an iOS-only function in each of the six modules (`Present here but not in the golden file (1): + final fun dev.inspector.probe/iosOnlyProbe`); a simulator-only val (`public iOS surface differs between targets`); the klib tool not found, the klib dir missing, another library's klib and an empty golden each fail with their own message; each normalisation rule removed turns its module red (`$stableprop`, function-type rewrite, flattening); a hand-edited golden fails without `--rerun`, and with the `inputs.files` declaration removed the same edit stays UP-TO-DATE and green. `structure/twins.txt`'s fourth column: a missing file and a removed column both fail. *Android, from CI logs because there is no SDK here:* a throwaway branch (deleted, never merged) ran all six `testAndroidHostTest` red for an Android-only function (run 39852405138), and red on `inspector-stream` alone for a function on `StreamSinkKt` and for the `@file:JvmName` pin deleted (run 39852841927); the PR and main runs (39855355125, 39857603878) executed all six green. No lockfile, catalog, plugin or wrapper change: a fix, not a technology change. The only dependency lines added are test-only, on `androidHostTest`.
- **The Diff page (`item:session-diff`, PR B: KIN-139 and KIN-140, PR #185, `1b7a45b`), in jsdom and in headless Chrome.** With PR A, `item:session-diff` is complete. At `1b7a45b`, base `2af3168` (QA): static 139/0; live probe 321 passed, 0 failed, 1 n/a (fifteen `diff` lines); archive probe 61; Chrome head 350 readings, 0 failed, base 6 failed (`shell <w> page diff fits` at four widths and `polish page targets`, the intended movers). `#/diff`, `#/diff?a=`, `#/diff?a=&b=` parse and round-trip; the rail links Diff between Stats and Sessions; `#/diff` with no ids shows the latest run against its previous one and is not rewritten. The page draws the daemon's order, kinds, loud mark and verdicts and computes nothing: an unknown direction shows the daemon's `why` and no "removed", and `n<5` comes from the reply. Red: rows sorted by the page, the loud mark dropped, unsure keys under "removed", the page's own `n<5`, and the stale-answer ticket check dropped each fail their line; `live: false` fails the export lines; `overflow-x` removed from `.diff-scroll` fails at 1000, 600 and 400. In an export the link is hidden and `#/diff` lands on Traffic. `diff mutation loud` looks `POST /v3/shop/orders/submit` up by name and fails when the page sorts by route. `compare (2)` opens the older as A (red with them swapped); a row's `diff` opens `#/diff?b=<id>`; a route's `calls in B` opens Traffic on B with the filter equal to `b.filterTerm`, `From Diff`, and back returns to the diff address. Screenshots of the orders detail open at 1440 and 400 are in QA's run folder: at 400 the table scrolls inside its own 336 px box and the document does not scroll sideways.
- **Session diff, daemon half (`item:session-diff`, PR A: KIN-135 to KIN-138, PR #180, `f59f3d4`).** `GET /api/diff`, `GET /api/diff.md`, MCP `diff_sessions` and `inspector diff` compare two sessions route by route over every row (1201 of 1201 on the long fixture, on REST, MCP and the CLI). A body capture did not keep whole is never reported as a removed key. Latency is judged only at n >= 5 per side with a change of 50 ms and 25%. At `f59f3d4`, base `936dbbf` (QA): `:inspector-daemon:test --rerun` 582 tests, 0 failed; `:repo-checks:test --rerun` 38, 0 failed; static 133/0; live probe 299 passed, 0 failed, 1 n/a and archive probe 58 on the four-session fixture. Base against probe, live: 14 routes (7 added, 1 removed, 4 changed, 2 unchanged), 29 bodies read, 887 bytes, budget not exhausted; the truncated base feed reads `compared: false` with nothing removed; the mutation `DELETE .../cart/items` and `POST .../orders/submit` (2 calls to 1) sort first; no header value and no body value appears in the REST, Markdown, MCP or CLI output (searched for every value in both sessions). Fifteen red proofs re-run, each failing as the hand-over names: `>` for `>=`, `n >= 4`, `known(B)` dropped, the redacted exclusion dropped (`$.card` and `$.expires_in` gain type changes), rows read through `queryTransactions(..., 5000)` (`expected <1201> but was <999>` in four classes), the cache stamp check dropped, header values kept, `cell()` bypassed, unsure under "Removed", a digits-only `foldIdKeys`, the feed's `resBodyTruncated` dropped, `SessionStamp` without `fileKey`, NotFound as 400, `--since-b` ignored, and the tool description reworded. Ten error cases answer the same body and status on `/api/diff` and `/api/diff.md`; escaped pipes in routes keep every table row at its header's cell count. A second read is served from the cache (0.017 s cold, 0.0012 s warm); an appended row or marker recomputes. QA did not re-run five of the builder's reds (calls counted as attempts, a lenient prefix walk, observable dropped, unread counted as known, `since` pasted into the filter text).

- **The release rehearsal for 1.4.0 (`item:release-rehearsal`, KIN-130), round 2, run on `0b364f5`, 2026-10-22, macOS, locally, nothing uploaded.** It ran after round 1's faults were fixed by KIN-132 (PR #171), KIN-133 (PR #172) and KIN-134 (PR #173); its evidence came in through PR #174 (`52a36ba`). The seven modules were published at `1.4.0-rc` to a local repository, and their 35 POMs, the seven Android ones included, were compared with the locks: 154 dependencies, **0 mismatches**; `kotlin-stdlib` is now 2.3.21 wherever Ktor raises it, 2.3.20 in the five `inspector-model` POMs, each equal to its lock. The daemon zip `inspector-1.4.0-rc.zip` (11589662 bytes) passed `release.yml`'s own smoke test (`/api/sessions`, `/`, MCP `serverInfo.version` = `1.4.0-rc`; red with `VERSION=1.4.0`: `MCP serverInfo.version is '1.4.0-rc', expected '1.4.0'`). `check-release-clean.sh --self-test` found the canary in 5 core artifacts, and the real check passed on 8 noop artifacts. `--paths` over the published repository flags exactly core's jar, its JVM jar and both iOS klibs (4 folders exit 1), finds every other artifact clean (24 folders exit 0), and reports no unreadable archive. QA repeated it independently (KIN-130, Doug) from a detached copy of `52a36ba` with its own build, its own local repository and its own POM checker: `POMs: 35, dependencies: 154, lock faults: 0`; the dependency sets are identical to the published 1.3.0 POMs from Maven Central in all 28 non-Android publications (only versions moved); every artifact has sources and javadoc jars; the same smoke result; the same 4 and 24 exits. **No new fault.** Round 1's three faults each became an item and were fixed before the tag: F1 by KIN-133 (below), F2 and F3 by KIN-132, and the corrupt-archive gap QA found while verifying KIN-132 by KIN-134.
- **Published POMs state the versions the locks resolve (`item:pom-version-mapping`, KIN-133, PR #172, `9481339`).** At `0b364f5` (QA, base `f32734e`): every publication maps its dependency versions from the configuration its lock covers, and `PublishedPomTest` in `:repo-checks` holds all 35 POMs to the locks (`:repo-checks:test --rerun` 38 tests, 0 failed). Published by hand on both trees to a Maven folder of QA's own: `inspector-core`, `inspector-core-jvm` and `inspector-stream-jvm` state `kotlin-stdlib` 2.3.20 at base and 2.3.21, the lock's version, at head; `inspector-core-jvm`'s Gradle module metadata `requires` moved from 2.3.20 to 2.3.21; Ktor 3.5.0 and coroutines 1.11.0 are unchanged. Red by QA: the `versionMapping` line deleted gives 30 mismatches (`inspector-core kotlinMultiplatform: org.jetbrains.kotlin:kotlin-stdlib POM 2.3.20, lock 2.3.21`, and the Android, iOS and JVM lines); `inspector-core/gradle.lockfile`'s stdlib hand-edited to 2.3.22 gives 5. `libs.versions.toml`, the eight lockfiles and the wrapper are unchanged: a fix, not a version bump. The `-android` POMs are read by the test where the SDK exists, which is CI's `build` and `release-swap` (macOS), both passing on the PR.
- **The release guard unpacks published klibs, exits 2 on its own errors, and exits 2 on an archive it cannot unpack (`item:release-guard-klib`, KIN-132, PR #171, `f32734e`; `item:release-guard-unzip`, KIN-134, PR #173, `0b364f5`).** At `f32734e` and `0b364f5` (QA, old script against new on the same tree): `--paths` over the published `inspector-core` iosArm64 klib read `PASSED` with exit 0 at base and reads `CANARY FOUND`, exit 1, now (the simulator klib and the publication folder alike); the `inspector-noop` klib folders read `PASSED`, exit 0; a path past `NAME_MAX` gets its true verdict (the old script died with `File name too long`, exit 1, which a caller reads as "capture code present", even for noop) and a failing `mktemp` stub gives `ERROR: internal error at line 107 (exit 1); nothing was proven.`, exit 2. A truncated copy of core's own jvm jar, a text file named `.klib` and an `.aar` wrapping a truncated `classes.jar` each gave `PASSED`, exit 0 at base and now give `UNREADABLE (unzip exit 9): <file>` and `ERROR: 1 archive(s) could not be unpacked:`, exit 2. On real core and noop jars and klibs the output is byte-identical to before (`cmp`), and so are `--self-test` and the default check. `ci.yml`'s step, run verbatim by QA, passes all 8 pinned cases on the new script and fails 2 (exactly the unzip cases) on the old one, and 4 on the KIN-132 cases against the pre-KIN-132 script. The `build` job runs it on CI and main's push runs for `f32734e` and `0b364f5` succeeded.
- **The landing page fits a 400 px window (`item:landing-page-fits-400`, KIN-131, PR #168, `41b968c`).** At `41b968c` (QA, base `fda2cd8`): `node scripts/measure-web-ui.js --only site` reads `400 px in 400`, 0 elements past the edge and 0 of 12 card bullets cut; base read `605 px in 400`, 51 elements and 12 of 12. It was red at 605 px with both rules removed, 605 px with only `.step`'s and 558 px with only `.grid2`'s, so each rule is needed. The 1440 layout (`1440x10382, 455 boxes, digest 1469658b`) is identical on both trees. CI's `web` job runs the guard on the runner's preinstalled Chrome (no install step, action or package; the maintainer's proxy approved this as 13f's condition), and main's push run for `41b968c` read `385 px in 400` (Linux's 15 px classic scrollbar), 0 elements, 0 of 12; `site/index.html` is still 723 lines. **This corrects the earlier gaps note** (`item:public-claims-true`, the fourth close) that blamed the architecture SVG for the 605 px: the SVG scrolls inside `.diagram-box` and never widened the page. The cause was the `.step` and `.grid2` grid items keeping `min-width: auto`, so a 531 px `pre` and a 454 px `.term` set their columns' width.
- **Sensitive-data detection, T5, the page (`item:sensitive-data-detection`, KIN-124, PR #169, `445408e`), in headless Chrome with real typing against a real daemon and its real `config.json`.** At `445408e` (QA, base `41b968c`): at 1440, 1000, 600 and 400 px, served and exported, the sensitive findings read `masked whole` and the dismiss form fits (one line, stacked at 400: `input 314 of 314; stacked true`). A dismissal needs a reason (empty: the send button is disabled and `config.json` is unchanged); a typed one writes only the `findingDismissals` entry, `{"ruleId":"sensitive.iban","route":"GET https://api.example.invalid/v3/shop/wallet/Balance","location":"resBody","path":"$.accounts[*].iban","reason":"…"}`, with the grouped path rather than the call's `[0]`, and keeps `findings.sensitive.fieldNames` as the file held it. The finding moves to `dismissed (1)` only after the daemon's reply; restore removes the entry (`"findingDismissals": []` remains) and the fold goes. The form says how far a dismissal reaches ("every \"IBAN\" finding on <route> at resBody <path>, in every session, not only this one, until it is restored"), and that wording is guarded: red with the reach line removed and red with only the clause ", in every session, not only this one" removed. An export hides dismiss and restore, draws the fold and the reason. A grep of `#page-findings`'s `outerHTML`, live and exported, finds none of the planted raw values (`4000056655665556`, a key, `GB82WEST12345698765432`). Settings edits `findings.sensitive.firstPartyHosts` and carries `fieldNames`; with none set the coverage line reads `no first-party hosts set, so nothing is raised for where data was sent` and the hint links `#/settings`, both gone after a host is saved. The fixture still has 16 rows; live probe 289 to 299 and archive probe 56 to 58, 0 failed, matching `ci.yml`; `check-web-static.js` 133/0; `:inspector-daemon:test --rerun` 532/0 and `:repo-checks:test --rerun` 36/0; `measure-web-ui.js --only polish,shell,room,layout,first --base` 236 readings, 12 failed at base and 0 at head, only the 12 new ones moved; first rows on the 160k session 415 ms; `chip marks active` (the pace neighbour) still true. With T1 to T4 above, `item:sensitive-data-detection` is complete.
- **Reported by the maintainer on 2026-10-22, not verified by an agent: the overlay has run on a physical Android phone and works, and Inspector is used daily on a real Compose Multiplatform project.** Which build ran on the phone, and on what device, is not recorded, so this does not say the Android sample has run on one or that any particular fix was looked at on hardware. The consuming project is not named. Nothing here was re-run by QA or the tracker, and nothing in the repository proves it.

- **The Findings page, badges, flagged rows only and the Settings fieldset (`item:findings-payment-safety`, PR B and C), in headless Chrome at 1440, 1000, 600 and 400 px, live and in an export.** At `f661683` and `f4bbfbf` (QA, KIN-116 to KIN-119, base `82887c1` and `ce7012f`): the payments session reads "10 findings in 18 calls in this session · 3 high · 6 medium · 1 info" under Payment safety, links 12 flagged rows, and its coverage line is `13 mutation calls looked at; 6 request bodies compared, 2 unknown (2 truncated).` (REST agrees: 12 distinct rows); the probe session reads "No findings in 16 calls in this session" with six rules checked; a call link opens `#/txn/<id>` and Back returns, `?id=` scrolls to and marks one finding, and an unknown id says so; the page text holds none of 6 planted key values. Exactly the 12 flagged rows of 18 carry a badge at 1440, 1000 and 400 served and 1440 and 400 exported, each its first finding's severity in words with no hue and no "duplicate" or "repeat"; `room 400 payments rows` is whole (cut at base); a flagged row's detail shows "Findings (2)" with each rule's evidence and an unflagged row none. `⋮` → flagged rows only (clicked in the real menu) leaves 12 rows with its band, off gives 18, a real `location.reload()` gives 18 and writes no storage key, a no-findings session reads "No calls with findings among the calls loaded", and `25-traffic-list.js` is 591 lines. Settings, with real clicks, the real `PUT` and the real `config.json`: save untouched writes nothing, email on writes only `{"findings":{"rules":{"sensitive.email":{"enabled":true}}}}`, off again writes `{}`, a severity change writes only that entry, a refused value shows the daemon's 400 text, success reads "saved — findings use it now", and a save never writes `enabled:false` for a default-off rule. Counts: static 127 to 132 to 133, live probe 268 to 281 to 289, archive probe 49 to 54 to 56, all 0 failed, matching `ci.yml`; `measure-web-ui.js --only room,shell,polish,layout,first` 224 readings, 0 failed at `f4bbfbf` (the `first` group, which the builder did not run, passes on the 160k-call session). Red by QA: the 1 s timer bypassed (`findings refresh burst : NO - 100 owed calls over 1277 ms cost 100 requests`, and `one flight : NO - 31 out at once`); the whole effective object sent (`findings settings patch : NO`); a badge taking its call's last finding's severity (`findings badge : NO`); the flagged filter keeping every row (`flagged only : NO - on: 18 rows (6 unflagged)`).
- **Findings in every export, and the leak guard (`item:findings-payment-safety`, PR A's T4), against real daemons from base and head.** At `e751a25` (QA, KIN-115, base `4343823`): the JSON export's `findings` object holds only masked hints (`•••• 2345`), the Markdown `## Findings` sits between `## Routes` and `## Timeline` and a session with none reads `No findings: 6 rules checked.`, the HTML embeds the report, and 11 of 15 HAR entries carry `finding <severity> <ruleId>: <message>`. A session with no findings gives a HAR byte-identical to base (`cmp`, 4294 bytes) and `HarTest` is unedited. `FindingsLeakTest` is red with nothing planted, with a raw key kept (caught in the accumulators' fields), and with both error-message guards removed (`REST: https://api.example.invalid/v1/checkout?token=PLANTED-ERR-55aa`); with only the first guard removed it stays green, because `errorClassOf` drops a non-class name too. Daemon 460/0.
- **Sensitive-data detection, T1 to T4 (`item:sensitive-data-detection`), against a real daemon, a still-running `inspector mcp` and the exports.** At `5cce7ab` (QA, KIN-120 to KIN-123 and KIN-127; PRs #156 `f0c53a2`, #158 `d5554a9`, #161): a seeded session with a case per rule and near-misses gave 18 findings (8 high, 4 medium, 6 info) with `firstPartyHosts` empty: card numbers (`•••• 5676`; high in a query; the published test card `4242…` info), IBANs (`DE89 •••• 3000`; high in a URL path, with the value absent from the route), a JWT in a query and in a path segment (the route's segment written `•`), `api_key`, `apikey` and `X-Api-Key` in a URL, credentials over http (high to a public host, medium to a private one, info to `10.0.2.2`), and field names (`$.password` info, `$.date_of_birth` medium, names info); email and phone are off by default and raise nothing; a Luhn failure, `shipping`, `spinner` and a card-shaped JSON number under `order_id` raise nothing. The empty-`firstPartyHosts` line is in `coverage.sensitive.line`, and setting the key changes the next read with no restart (a name sent to another host goes info to medium, credentials over http to a non-first-party private host medium to high). Dismissals: after one hand-written entry, REST and the same running `inspector mcp` process both read `findingCount 9` to `7` with `dismissedCount 2` and no restart; `ruleVersion` is unchanged (`716d5de5`); `includeDismissed=true` returns both with their reason, `yes` is a 400; a bad entry is named in `configError` and nothing is dismissed; the live `tools/list` has 15 tools and none writes a dismissal. Exports: Markdown reads `1 dismissed, listed with their reasons`, the HAR note ends `(dismissed: qa: …)`, the JSON and HTML hold all 18 sensitive findings with the dismissed one marked. `grep` of every planted value over REST, the JSON export's `findings`, the Markdown section and the HAR notes: no match (the call rows carry them by design). `JsonPaths.walk`, `group` and `isAtOrUnder` match session-diff §10, with `[]` for `[N]` and a bare `startsWith` each red in `BodyShapeTest` and `SensitiveSourceTest`. `SensitiveLeakTest` red with `SensitiveMask.hint` returning the value (`accumulator: Zr5hQ1wE7uI3oP`) and with route masking removed (`REST: NL91ABNA0417164300`). Daemon 496/0 at `f0c53a2`, 504/0 at `d5554a9`, 531/0 at `5cce7ab`; the three PRs' `build`, `release-swap`, `repo` and `web` passed. The builder's cold scan: 2706 ms on 10,000 calls with ~4 KB bodies each side (budget 5 s), 41 ms after 100 appended rows, 316 ms on 160,000 rows shaped like `huge`; not re-measured by QA.
- **The sample's demo flow, from a clean clone, on desktop (`item:sample-demo-flow`).** At `b34ea8b` (QA, KIN-128, base `5cce7ab`): following `sample/README.md` step by step with JDK 21 (a second daemon on 8109, an empty data folder, no `config.json`), `-Dinspector.sample.autofire=true` archives one session of 50 calls and 7 findings (2 high, 2 medium, 3 info), as the README's table says. Duplicate mutation (high, citing 2 calls 0.3 s apart, `3 mutation calls looked at; 2 request bodies compared`) and JWT in a URL (high, `•••• HS256`) appear in REST, in a real `inspector mcp` (`list_findings`), in `inspector findings` and on the Findings page in Chrome ("7 findings in 50 calls in this session"). The token's three segments and `4242424242424242` occur 0 times in the page text and DOM, and nowhere in the REST reply, the CLI JSON, the JSON export's `findings`, the Markdown section or the HAR notes. `PUT /api/config` disabling `payment.duplicate-mutation` and `sensitive.jwt-in-url` makes `check_demo.py` and `mcp_findings.py` FAIL on the next read; restored by `PUT {"findings": null}`. With only `jwt-in-url` off the check still passes, because `sensitive.secret-in-url` reports the same `access_token`. The PR touches only `sample/`.
- **Page keys, in headless Chrome 131 with real key events at 1440 and 400 px (`item:web-ui-polish`, PR A part 2).** At `fc7b205` (QA, KIN-104, base `d5554a9`): 36 of 36 readings ok on head and 14 of 36 on base. `g s`, `g a`, `g p`, `g ,` and `g t` open Stats, Sessions, Processes, Settings and Traffic with focus on the page's title, `g g` selects the first of `orderedRows()` after `j j j`, and `?` opens the legend on Traffic, Stats, Sessions, Processes, Settings and `#/txn`, inside the window, and Escape closes it. Typing `gsgafo?` into the filter, a Settings number input, the density select and the replay body textarea fires nothing and keeps focus, which was the reported defect (base: `f` in the textarea jumped to the filter). Red: `'TEXTAREA'` removed from `typingTarget` (`keys skip textarea : NO`, 267/1) and `g g` routed to nothing (`page keys live : NO`, 267/1). Static 124 to 127, live probe 264 to 268, archive probe 48 to 49, all 0 failed; `measure-web-ui.js --only polish,shell,room,layout --base` 206 readings, 2 failed at base and 0 at head.
- **The overview band's reserved height is guarded in CI and in the measure run (`item:guard-overview-band-reserved`).** At `6aff923` (QA, KIN-110, base `13ae503`), with only `.axis-hint.axis-reading { min-height: 24px }` removed from the head copy: `check-web-static.js` reads `overview band css : NO - .axis-hint.axis-reading sets no min-height in px` (125 checks, 1 failed, exit 1), and `measure-web-ui.js --only first` reads `FAIL copy lands huge: moved or stale (the first visible row moved 44 px, over the scope bar's 33 px)`, the only failure; with the rule, `overview band css : yes` and `ok copy lands huge … moved 33 px, scope bar 33 px` (9 readings, 0 failed). Base's 48 px bound passed the same 44 px. The diff touches no `web/` file.
- **The README and the landing page agree with this file, and the release page agrees with `docs/ACCESS.md` (`item:public-claims-true`, `item:release-notes-boilerplate`).** At `82887c1` (QA, KIN-125 and KIN-126, base `fc7b205`): each changed README and `site/index.html` claim was read against this file, `git log` and Maven Central's public metadata (versions 1.0.1 to 1.3.0, latest 1.3.0): 886 tests at the last full count with the caveat, the real-app sentence, the Android sentence (ran once on a phone on 2026-08-17 in a build that predates the Android sample; the sample has not run on a phone; **superseded 2026-10-22 by the maintainer's report above that the overlay has run on a physical Android phone and works**), the iPhone sentence, the sponsor box (`grep -i proxy site/index.html` finds nothing), and Maven Central with no token from 1.0.1 once a person publishes the staged bundle. The landing page was read in Chrome at 1440 and 400. The release notes' heredoc, rendered at head and base with `VERSION=1.4.0`, names Maven Central first, says 1.4.0 reaches it once the maintainer publishes, and the two workflow files differ only in that step's `run` text.

- **Findings (payment safety), daemon side, PR A so far, against a real daemon, a real `inspector mcp` and the CLI.** At `66ab63c` (QA, `item:findings-payment-safety`: KIN-111 to
  KIN-114, base `1ed50c1`): one live session with a case per rule and two near-misses gave R1 1 (on `::1:8080` and `[::1]:8080`, one route), R2 1, R3 1, R4 1, R5 4, R6 1, none for the
  3001 ms near-miss and none for `UnknownHostException`; 9 findings in 15 rows (3 high, 5 medium, 1 info), `13 mutation calls looked at; 6 request bodies compared`. `GET …/findings`,
  MCP `list_findings` and `inspector findings` return the same ids in the same order; an unknown session is 404, `?category=nope` is 400, an unknown MCP category is `isError`, and
  `--category nope` exits 1. Edits to `config.json`'s `findings` key (R5 disabled, R1 severity `medium`) change the next read with no restart, while `routeTemplates` in the same file
  stays unapplied until a restart; a broken hand edit keeps the last good settings and sets `configError`. `grep` for the planted key values, the error text and the address in it
  over the REST reply: 0 matches. Daemon tests 453/0 and `:repo-checks:test` 36/0 (`--rerun`). Red by QA: the cursor past an incomplete line (`RouteStatsServiceTest`, expected 4 but
  was 3, the only failure of 52); `isRedirect` false (fails `RouteStatsTest`, `StatsRouteTest` and `PaymentRulesTest`: the rule is shared); the truncated state removed (fails
  `BodyShapeTest` and R2); R1's chain `>` to `>=`; `NEVER_SENT` emptied; retries read as `attempt > 1` (a 3xx predecessor then counts); settings cached after the first parse
  (`FindingsServiceTest`, `FindingSettingsSourceTest`); the engine's `isMasked` check bypassed (`FindingMaskGuardTest`: `expected: <••••> but was: <4111111111111111>`).
  **The payments fixture's counts:** the code asserts R1 to R6 = 1, 1, 1, 1, 5, 1, 13 mutation calls, 12 flagged rows and 2 truncated bodies, and QA counted the design's own table
  to the same 13 and 12; the design note's 15 and 14 (and KIN-114's sixth criterion) are arithmetic errors in the note, not in the code.
- **Web UI polish, PR A part 1, in headless Chrome and jsdom.** At `4343823` (QA, `item:web-ui-polish`: KIN-102, KIN-103, KIN-108, base `66ab63c`): at 1440, 1000 and 400 px, live and
  exported, dark and light, `#detail-full`, `#detail-hide`, `#drawer-close`, `#more-button` and the Sessions row `✕` are at least 24×24 with SVG at least 14 px (12 `polish <w> icon
  targets` readings; `26x24 svg 16x16`; base `small` on all 12) and red with `.btn.btn-icon`'s `min-width` and `min-height` removed; every `.btn` on Stats, Sessions, Processes and
  Settings is at least 24 px tall (27 buttons on 4 pages), `.settings-note` is at most 72ch (widest 544 px), the density select shows `comfortable` whole (112 px for 99 px; red with
  `width: 84px` back, `82px for 99px`), and Sessions rows lead with the start time (`session row time : yes`). `--only sheet --out <dir>` wrote 120 PNGs and an `index.html`
  referencing all 120. Counts, base to head: `check-web-static.js` 120 to 124/0, `render-web-ui.js` 263 to 264 passed, 0 failed, 1 n/a (`ci.yml` pins 264), archive probe 48,
  `measure-web-ui.js --only polish,room,shell --base` 80 readings with 19 failing at base and 0 at head, only the polish readings moved; `room 1440 bands list: top 145px`
  unchanged. `:repo-checks:test --rerun` green. Not the design: the contact sheet is with the maintainer.
- **An IPv6 server reached by both clients is one route, in the daemon's tests, a real daemon, `inspector mcp` and headless Chrome.** At `1ed50c1` (QA, `item:route-key-one-ipv6-host`):
  a session of 3 × `::1` and 3 × `[::1]` on port 8080, `/v6`, plus one `::1` on 9090 reads one row of 6 calls in `GET …/stats` (base two rows of 3), MCP `route_stats` and the Markdown
  Routes table, and one row naming `[::1]:8080` on the Stats page at 1440 and 400 (4 rows); the row's `host` is the bare `::1` and the page and Markdown still draw `[::1]:8080`.
  `method:GET host:::1 path:*/v6` opens 7 calls (the six and the port-9090 superset) in REST and in Chrome; a bracketed non-IPv6 host keys as before. Red by QA: `keyOf` on the stored
  host fails 5 of 61, one per surface; the `':' in lower` condition dropped fails `RouteTemplatesTest`. Daemon and `:repo-checks` tests 368/0 (`--rerun`).
- **A burst of live signals or markers redraws a bounded number of times, in headless Chrome 131 against a real daemon, and in jsdom.** At `e34542e` (QA,
  `item:web-live-signal-burst-bounded`): 100 signals over 1190 ms cost 5 redraws of each of the timeline, the waterfall and the path and 5 `/current` reads (bound 6; base 100 of
  each), 50 markers over 1075 ms cost 5 timeline and 5 path redraws (base 50); on the path view 30 screen signals cost `renderPath` 2 and 30 payload reads (base 60), in the state
  browser 40 state signals cost `renderBrowser` 2 (base 40); paused, or for another session, 0 redraws and 0 `/current` reads. Red with base's `05-network.js`: both Chrome readings
  and 4 of 120 static lines fail. Static 116 to 120/0; the live probe stays 263.
- **Interrupting a measure run leaves nothing behind, on macOS, with the signal sent to the node process alone.** At `1796b96` (QA, `item:guard-measure-interrupt-cleans-up`): SIGTERM and
  SIGINT mid-case (`layout,run,live`, `traffic`, and the moment Chrome spawned) exit 143 and 130 within 2 s with 0 processes naming the temp dir left and the dir gone, once with
  `--base 85e9552` (9 processes started); `--keep` keeps only the dir; each log holds one `interrupted by SIG…` line and no readings. At `85e9552` the same signals left 18 and 26
  processes and 45M and 83M of temp dir. An uninterrupted `--only layout,traffic` read 140 readings, 0 failed.
- **`expanded unfolded` judges a visit that holds failures, in jsdom against the CI fixture.** At `c608ec8` (QA, `item:guard-expanded-unfolded-has-failures`): the probe opens Checkout, chosen
  from the daemon's rows, and reads `yes (1 expanded line, 4 failures drawn in Checkout)`, 263 passed, 0 failed, 1 n/a (base `yes (1 expanded line, 0 failures drawn)`); with the status
  span's failure class stripped it reads `NO - an expanded line hides a failure (Checkout)` (base stays green), with the `pv-failed` header also removed `NO - Checkout holds 4 failures,
  its open line draws 0`; a fixture with every 5xx set to 200 reads `n/a (no visit holds a failure)`. `web-ui.md` names #133's Chrome cases and no longer states "29 breaks"
  (`item:docs-housekeeping-2026-10-22`, same PR).
- **A silent MCP child no longer hangs the probe and is left running, in the daemon's JVM tests.** At `ffc9278` (QA, `item:mcp-probe-deadline-holds`): `McpProbeCleanupTest`'s
  silent stub (stdout held open, nothing written, EOF ignored) makes `probe(timeoutMs = 1500)` return in 3.514 s with `did not answer a tools/list within 1500ms`, under
  the test's own 6.5 s bound, and the child is dead afterwards. Red by QA: the blocking `readLine` back (`had not returned after 6500ms: its deadline waits on a read the
  silent child never ends`, the only failure of 9, 14 s, no hang), `poll` replaced by `take` (the same), and `destroyForcibly()` replaced by `Unit` (4 of 4 fail naming a pid).
  `McpProbeCleanupTest` and `McpControlTest` 9 tests, 0 failed restored; daemon tests 362 to 363; no `McpProbeStubChild` left at t+0 to t+3 s.
- **A measure run fails within about a second when Chrome exits or a DevTools socket closes mid-case, on macOS.** At `85e9552` (QA, `item:guard-measure-chrome-exit-fails`): Chrome
  killed 8 s into `--only traffic` gives `run exited with 2 after 0s`, `head traffic: Chrome exited (signal SIGKILL) before Runtime.evaluate was sent`, no reading printed, and
  no Chrome or daemon naming the run's temp dir left; 4 s into `--only layout,run,live`, `head layout big: Chrome exited ... while Runtime.evaluate waited for its answer`. At
  `95b4de4` the same kill hung (`run still alive 60s after Chrome was killed`, five daemons left). A closed socket rejects in 258 ms; with `ws.onclose`'s `lose(...)` removed it
  waits 30003 ms for the deadline. A case that swallows the error exits 2 with `if (page.lost) throw` and exits 0 printing `ok SWALLOWED: 1` without it. Each command is
  bounded at 30 s, about 40x the slowest send the builder measured in a full run (718 ms, 287 readings, `first` included); `--only first` on the 160,016-call session
  (9 readings), `--only traffic` (10) and `--only layout` (124) stay green with 0 failed.
- **A session switch shows nothing of the session left, in jsdom and headless Chrome 131.** At `4645c43` (QA, `item:web-switch-clears-call-panes`): with the new session's first
  page held, the live probe's nine `switch ...` lines read the list, the timeline, the waterfall, the path, a tag browser, the endpoints, the tab bar, the detail and the counts,
  and each says `this session is being read…` or is cleared (260 passed, 0 failed, 1 n/a); QA's own Chrome case, two sessions, was 15 of 15 clean at 1440, 1000 and 400 and 0 of 15 on
  `117aeee`. Red by QA, each restored: `state.callLoad++` removed (`callLoad 5, so a load of A in flight still writes`), `state.allTransactions = []` removed (static and live), the
  timeline's `saysBeingRead` guard removed (`#timeline-empty reads "nothing was recorded in this session"`), the tag browser's guard removed, and `renderList()` removed from
  `followSession` in Chrome (`traffic session switch window: old rows`). The two session-switch races still read `old dropped`. Counts: static 110 to 112, live probe 251 to 260, archive 48.
- **The first rows and the live socket on a 160,016-call session no longer wait for the whole-session copy, in headless Chrome 131.** At `1888380` (QA, KIN-42 of
  `item:signals-query-one-rule`): `first rows huge` 411 ms and `connected huge` 414 ms (bar 3000; `over 3000 ms` at `4645c43`, about 24 s). While the copy is read the page shows no
  empty-state claim: QA sampled every 400 ms, 57 to 58 samples per width at 1440, 1000 and 400, and found no `waiting for an app`, `nothing recorded` or `no calls`; the glance and the
  reserved overview band say the session is being read. The copy lands in place: scroll kept, 10 chips, `calls in session 160016`, first row moved 33 px (`copy lands huge`). Red by
  QA: `connectLive()` moved back after the load (`connected huge: over 3000 ms (23748 ms)`) and the overview band not reserved (66 px). `measure-web-ui.js --only first,traffic,layout,run,live,room`
  220 readings, 0 failed on head, 4 failed on base. Counts: static 112 to 116, live probe 260 to 262, archive 48.
- **The Sessions page names the daemon's own sessions folder, in headless Chrome 131.** At `95b4de4` (QA, `item:docs-housekeeping-2026-10-21`): with `--data "<scratch>/custom data"`,
  `#sessions-dir` reads `<scratch>/custom data/sessions` at 1440 and 400 and the document does not widen; with no `--data` (and `-Duser.home` pointed at the scratchpad) it reads the
  expanded `<scratch>/home/.inspector/sessions`; in an export the page is hidden and the placeholder stays. The probe line `sessions folder` is green on head (263 passed, 0 failed,
  1 n/a) and red against `84a1747`'s page (`NO - the note says "Every run of the app is a folder under ~/.inspector/sessions. …"`, 262 passed, 1 failed). Also at `6c1cb32`
  (QA, part 1): the `AgentGuideTest` budget is green at 14,999 bytes and red at 15,000 and 15,007; only v62's `INTEGRATION.md` heading says `(this document)`; `CLAUDE.md` and
  `.cursorrules` are symlinks. At `e071594` `architect.md` names `api/inspector-ui-public-api.txt`.

- **Scan probes on the shared probe page fail on an empty scan and print what they checked, in headless Chrome and jsdom against the CI fixture.** At `b1bbcac`
  and `5b21593` (QA, `item:guard-probe-finds-nothing-fails`, two rounds): `scripts/lib/scan-verdicts.js` holds the rule; on the fixture
  `failures unfolded : yes (1 summarised line, 6 lines in all)` and `expanded unfolded : yes (1 expanded line, 0 failures drawn)`, 251 passed and 1 n/a in
  `render-web-ui.js`, 48 in the archive probe. With KIN-55's leak put back (the two lines in `lib/live-filter-probes.js` that restore the open visits) base
  stays green on `yes (0 summarised lines in this session)` and the head reads `failures unfolded : NO - found no summarised line (1 already open: Checkout), so
  this checked nothing`, 250 passed and 1 failed, exit 1; without the leak it is green. Round 1 found `shell <w> rail` passing over no rail item; round 2, with
  every `class="rail-item"` renamed, reads `FAIL shell <w> rail: NO - found no rail item, so this checked nothing` at 1440, 1000, 600 and 400, and with only #133's
  hunk reversed it passes again over none (the vacuous pass is what the fix turns red). Also red by QA: the repeat-mark scan in `room 400 rows` with the mark class
  renamed (`NO - found no repeat mark (3 rows held are repeats)`). `measure-web-ui.js --only shell,room` on the fixture, 61 readings, 0 failed.
- **The live re-read through the whole path, in headless Chrome 131 against a real daemon, on a fixture of two sessions.** At `9fb0470` (QA,
  `item:guard-live-traffic-driver`): `node scripts/measure-web-ui.js --only traffic` judged 9 readings, 0 failed, twice: the burst bound (3 list reads, at most 6,
  for 100 rows over 850 ms), the burst's counts against the daemon's (7 of 56 calls, 26 signals), the filter-edit race and the session-switch race in both landing
  orders, that every request and socket stayed on 127.0.0.1, and that every streamed host ends in `.invalid`. Red by QA, each turning only the reading it guards:
  a re-read per live row, `stands()` without its filter clause, `stands()` without its session clause. The client refuses `localhost`, `0.0.0.0`, an `.invalid`
  origin, a path-traversal origin and a look-alike host as origins, and `api.example.com` and two look-alikes as hosts. Pause and resume, reconnect, 400px and
  spaces-only are not in the committed case.
- **The core and stream twin pairs fail on a public declaration outside their contract, and on a golden-only edit with the build cache on, JVM only.** At `623c571`
  (QA, `item:guard-twin-pairs-alike`): `every_public_declaration_is_in_the_contract` and the golden test run green in `:inspector-core`, `:inspector-noop`,
  `:inspector-stream` and `:inspector-noop-stream`. Red by QA, each restored: a stray `class StrayProbe` in core (completeness fails with `+ dev.inspector.StrayProbe [class]`,
  the golden test stays green); a stray top-level function in `:inspector-noop-stream`; a line appended to each of `api/inspector-public-api.txt` and
  `api/inspector-stream-public-api.txt` with `--build-cache` and no `--rerun`, which failed all four `jvmTest` tasks that had been `UP-TO-DATE` or `FROM-CACHE`;
  `structure/twins.txt` with the stream pair's file misspelt, and with the core pair's file missing. No golden file changed. The klib half and the Android
  and iOS targets are CI's.
- **A probe leaves no process behind, in the daemon's JVM tests.** At `74f5ca0` (QA, `item:guard-mcp-probe-cleanup`): `McpProbeCleanupTest` runs a child JVM
  that ignores EOF and checks it is dead after `probe` on the success, timeout and failed-write paths. With `process.destroyForcibly()` replaced by `Unit`, 3 of 3
  fail, each naming a surviving pid; restored, 3 of 3 pass. `pgrep -f McpProbeStubChild` found nothing before, after and after the red run. Daemon tests 357 to 360.
- **A server is its host and a non-default port everywhere, against real daemons built from base and head and one seeded archive.** At `66c1bad` (QA,
  `item:host-port-everywhere`): `url` for `::1` (OkHttp's shape) and `[::1]` (Ktor's) both read `http://[::1]:8080/v6`, not doubled; HAR, the Markdown labels, MCP
  `timeline` and `explain`, the page's `urlOf` and the URL inside `curlFor` agree; `summary.byHost` names `127.0.0.1:8080` and `127.0.0.1:9090` separately; every
  non-IPv6 `url` is byte-identical to base. Real calls to `[::1]` through Ktor and OkHttp ran (0 skipped) and, with the bind forced to fail, report skipped with the
  reason. The mirror case `urlOf` is red on the web side (`urlOf: NO - 3 of 6 cases failed`) and on the Kotlin side (`MirrorCasesTest.urlOf_cases`). No golden file changed.
- **At 400px the Stats route cell breaks only at `.`, `:` or `/`, served and exported, in headless Chrome 131.** At `7b37d7a` (QA,
  `item:web-stats-route-breaks-whole`): `measure-web-ui.js --only shell`, 42 readings, 0 failed; `stats 400 route breaks served` and `… export` read `at . : /` (17 route
  cells, 23 line breaks), QA's own session kept `api.qa-long-name.invalid`, `127.0.0.1:8080` and `[::1]:8080` whole, a route too long to break stays whole inside the
  table's scroll with the document 400 of 400, and at 1000 and 1440 a route is one line. Red by QA: the base's `85-stats.css`, the base's CSS and script together
  (`http://127.0.0.1:808|0`), and the `nowrap` hold removed; `overflow-wrap: anywhere` put back on its own stays green, as the item records.
- **Route stats, daemon side (`item:endpoint-latency-stats`, PR A), against real daemons and a seeded session, JVM daemon only.** At `0a5a1c9` (QA, round 1):
  `GET /api/sessions/{id}/stats`, MCP `route_stats` and `inspector stats` return one row per method, host and route over the whole session
  (`total 1203, matched 1203` on the 1203-call fixture, not the 1000 clamp); `/users/{id}` with samples 1 to 20 ms reads p50 10, p90 18, p95 19,
  p99 20, max 20 by hand-computed nearest rank; a route with 19 samples reads `n<20` for p95 and p99 on REST, in the Markdown and in the page;
  a transport failure is counted (`attempts 21`, `latencySamples 20`) and never a latency; a 503, 503, 200 chain is one call with `retries 2` and a 302
  is a redirect, not a retry, also under `status:200`. Each row's `filterTerm` parses and matches its own rows over a fixed corpus and 5,000 seeded random
  paths, and is carried only for filters that stay a superset (`status:500 | has:error` carries the route alone). `routeTemplates` is read at start;
  `PUT /api/config` is control-gated (403 without the header), refuses a bad entry with a 400 naming it, and after a restart `/x/deadbeefcafe` reports
  as `/x/{name}`, `routeSource: config`. `export.json` carries `routeStats` (`formatVersion` 1), `export.md` a `## Routes` table before `## Timeline`,
  and the HAR is byte-identical to the base daemon's (64,818 bytes). Daemon tests 299 to 357, 0 failed. On the 160,016-row `huge` session: cold
  0.331 s, warm 2.4 to 3.8 ms, 2.4 to 2.8 ms after 100 appended rows, `inspector stats` 0.36 s, heap about 17.4 MB per entry (about 111 B per
  attempt). Red by QA: floor rank (`expected <[10, 18, 19, 20, 20]> but was <[10, 18, 19, 19, 20]>`), `TAIL_MIN_SAMPLES = 19`, and a transport
  failure entering latency. The engineers' other red proofs are in the tickets, not repeated.
- **The Stats page (`item:endpoint-latency-stats`, PR B), in headless Chrome at 1440, 1000, 600 and 400 px, live and in an export.** At `d256174` (QA, round 1):
  `Stats` sits between Traffic and Sessions in the rail; every row and cell equals `GET …/stats` and `inspector stats` at 1440, 1000 and 400, under no
  filter (10 routes, 50 calls) and under `status>=500` (`2 of 50 calls`); rows under 20 samples print `n<20` for p95 and p99; `/pay` is tagged `2 retries`
  and `/new`, reached by a 302, is not; clicking `p50` sends one request with `sort=p50` and the order equals REST's; a row opens Traffic on the daemon's
  term with the caveat, editing the filter clears it and `back to Stats` returns; 30 calls in about a second cost one `/stats` read and a paused page none;
  `/v3/long/bulk` reads 1201 attempts, past the 1000 clamp; the exported file draws all 10 rows offline with the filter disabled and no open or sort
  buttons; the document is as wide as the window at all four widths and the table scrolls inside `#stats-scroll` at 400. Counts: static 102 to 107,
  live probe 236 to 251, archive probe 43 to 48, `measure-web-ui.js --base` 270 readings with 2 failing on both sides (KIN-42's). Red by QA: the withheld
  cell drawn as a dash (`stats n<20`) and a refresh per owed row (`stats refresh burst`, `stats refresh one flight`). The engineers' other red proofs are not
  repeated.
- **With live on, a call reaches the timeline, the glance and the selected picker option, without a reload, in headless Chrome 131
  at 1440, 1000 and 400 px, against a real daemon fed over `/ingest`.** At `433f30d` (QA, round 2): a live call is drawn by +500 ms
  with no other action; scrolled back, `scrollTop` is unchanged (1421 to 1421, 1456 at 400) and at the end the pane follows; 100
  calls over about 1.2 s cost 5 timeline redraws and 0 `/api/sessions` reads (bound ceil(T/250)+1); the glance's `calls` equals the
  header with no filter (14) and after the burst (116), and under `status>=500` it reads the session (16) beside `4 of 16 calls`;
  after a reconnect 122 equals the daemon's total; after a pause, two calls and a resume, the header, glance, option and face
  read 120 together, and paused or for another session nothing moved. Red by QA: `drawLive()` removed (`socket call drawn`,
  `live timeline plain`, `live glance plain`, `live glance filter`), `followLiveCount()` removed (`live picker count`), a redraw
  per call (`live draw burst of 100`), and `setLiveTail` put back to `952b6c6` (`live paused still`).
- **Web UI room for the list (1b), in headless Chrome at 1440, 1000 and 400 px, live and in an export.** At `4fe71dc` (QA, round 1): the toolbar is
  one row from 1000 px (37 px; at 400 it wraps and fits) with `endpoints ▾` and a `⋮` menu holding the exports, keyboard shortcuts and compact
  rows, and an export offers no export item; the overview is one line (33 px) and the list pane's top is 145 px at 1440 on the list and the
  timeline views (base 208 and 234 px; the bar is 160 px); the divider resizes the list from 605 to 805 px, keeps it across a reload and a
  double-click restores 605, and `⇥` hides the detail column; `#/txn/{id}` opens the oldest call of a 1203-call session, which is not among the
  1000 rows the list holds, on its own page, Back and Esc return, an unknown id reads `no transaction …` served and `… is not in this export`
  in the export; a closed drawer at 1000 px leaves the document 1000 wide, draws no shadow and none of the 8 controls inside it can take focus,
  and the open drawer's close box overlaps none of the 7 header actions; across 1000 rows at 400 px, none has its path tail or byte count cut,
  and the repeat mark reads `2×`; on the 1203-call session the tab (`traffic 1203`), the header (`newest 1000 of 1203 shown`), the scope bar
  (`1203 of 1203 calls under this prefix`), the overview and the glance each say what they count, and the two that count the session agree,
  live and exported; with live paused, the list returns at 5000, the timeline opens at 0 and then where it was left (2000), and with live on
  it opens at its end. Counts: static 102/0, live probe 236 passed 0 failed 1 n/a, archive probe 43 passed; `measure-web-ui.js --base`
  265 readings, 18 failing at base and 2 at head (`first rows huge`, `connected huge`, KIN-42's, on both sides). Red by QA: `top: 0` on
  `#timeline > .filter-note` (`sticky top`), `visibility: hidden` removed from the closed drawer (`closed drawer css`), an unlabelled scope
  count (`counts agree`), and `openViewPlace` restoring nothing (`views keep place`). Two base probes were replaced by stronger ones, not
  deleted: `rowLabels` by `toolbar one row`, `shortcuts` by `shortcuts in menu`.
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

- **Gaps behind the items closed on 2026-10-23 (the sixth close: session diff, polish part 3, rows 25, 28 and 29, the Central trim).** *Local machine and CI:* no Android SDK on the QA or tracker machine, so the full `./gradlew build` and the Android targets rest on CI's jobs, which passed on every PR and on main's push runs. *`item:session-diff` daemon half:* the timings on large pairs (the 160k-call `huge` pair and the 20,000-call scratch pair are builder fixtures QA did not rebuild; the scratch pair, with 8 KB bodies, exhausts the 256 MiB body budget, so some removed keys read as "unsure"); the MCP `limit` cap of 200 live and a `startedAt` tie live (both unit-tested); the Chrome suite for the probe fixture's new baseline session (jsdom probes only); **by default `diff_sessions`, `inspector diff` and the Diff page hide a route whose only news is an unsure key** (it counts as unchanged, so the tool says "0 routes" where a removal may be hidden; the page shows it only under "show N unchanged routes"; a placed candidate in ROADMAP §6); the DAEMON.md budget is written "256 MB" where the code's is 256 MiB. *`item:session-diff` Diff page:* a recording B (the note and refresh), the `diff` button on a recording row, and a deleted session under a shown Diff page (code read only), because the fixture has no recording session; the row button's accessible name runs the method into the URL; the latency line wraps mid-value at 1440. *Polish part 3:* the `routeTemplates` box runs about 1000 px at 1440 and its `<label>` wraps the running count into the accessible name (both for the design pass); QA did not repeat the builder's Pace reds. *Row 28:* pressing `← traffic` a second time after Forward replaces the call's entry, as Esc does; the control scrolls away on a long call's page. *Row 29:* the clause is not shown for a stretch holding only older calls, whose empty text keeps its own sentence. *Row 25:* the empty-dump-result failure was read in code only; the half of the flattening red that shows a moved member passing without flattening; the per-module XML listing of both pair tests in all six Android host reports (read from the throwaway run's log, which names them on stream and counts them elsewhere); off-macOS behaviour (it fails by design with `No iosArm64 klib`, and no CI job runs `check` off macOS); parameter names (absent from klib dumps); stream's constructor beyond a source-level match; cross-twin partial linkage; in `:inspector-core` and `:inspector-noop` `src/iosMain` is never compiled, because the hand-written source-set links stop the default hierarchy (no files there today; a placed candidate). *The Central trim:* the Android part of the bundle was not built (2,000 to 800 is extrapolated from 1,650 to 660); the throwaway-key signatures were not checked against Central's validation and nothing was uploaded; whether the Portal accepts the trimmed bundle is checked when 1.4.0 is staged. Chrome's `run toggle big 1440 oldest-first` reading is flaky under load (failed on base and head in three of QA's two-tree runs, 6 of 6 ok alone).

- **Gaps behind the items closed on 2026-10-22 (the fifth close, the last before the 1.4.0 tag: detection T5, the three release fixes, the rehearsal, the landing page).** *Local machine and CI:* no Android SDK on the QA or tracker machine, so the full `./gradlew build` and the Android targets rest on CI's jobs, which passed on every PR and on main's push runs for `445408e`, `41b968c`, `f32734e`, `9481339`, `0b364f5` and `52a36ba`. *`item:release-rehearsal`:* the Android aars and `publishAndroidPublicationToMavenLocal` were never built or scanned locally, so `check-release-clean.sh` was not run over an aar in the rehearsal (the seven Android POMs were generated and checked against `androidRuntimeClasspath`; CI's `build` and `release-swap`, on macOS with the SDK, build the aars and run `PublishedPomTest`; a placed candidate in ROADMAP §6); signing and the Central bundle, out of scope by the ticket; nothing was uploaded. *`item:pom-version-mapping`:* `PublishedPomTest` has never been run on a Linux developer machine, because CI runs `:repo-checks:test` only inside the macOS `build` job (the Ubuntu `repo` job runs the two scripts and no JDK); QA did not re-run the builder's red proofs R2 (`fromResolutionResult`), R4 (the `dependsOn` removed) and R5 (an unlocked target). *`item:landing-page-fits-400`:* Safari, Firefox and real phones (only headless Chrome on macOS and CI's Linux Chrome ran it); between 821 and 885 px the page is still 886 px wide, because of the two-column `.grid2` above its 820 px breakpoint (QA swept every 5 px from 360 to 1440; main before the fix has the same band, and the 400 px criterion does not cover it; a placed candidate); the builder saw `run toggle big 1440 oldest-first` move once in a full parallel run, and QA did not re-run the full batch. *`item:sensitive-data-detection` (T5):* the no-hosts hint repeats the end of the coverage line just above it, which the product owner may want shortened (a nit); the false-positive rate on a real consuming app's sessions; Safari and Firefox. *`item:release-guard-klib`, `item:release-guard-unzip`:* the full `./gradlew build` and `-Pinspector=off` were not run by QA (CI ran them); an `unzip` exit of 1 (a warning with everything extracted) is still accepted as readable, by design. *KIN-130's own criterion 4 is this entry.*

- **Gaps behind the items closed on 2026-10-22 (the fourth close: findings, detection T1 to T4, polish part 2, the demo flow).** *Local machine and CI:* no Android SDK on the QA or tracker machine, so the full `./gradlew build` and Android rest on CI's jobs, which passed on every PR; the push runs on `main` for `e751a25`, `6aff923` and `d5554a9` were in progress when QA looked. Linux ran only in CI. *`item:findings-payment-safety`:* MCP `export_session`, called directly (it shares `SessionExport` with the REST export, and `FindingsLeakTest` reads it among its surfaces); a 2,001-row whole-session export (the builder's `ExportTest`); the builder's red proofs for a reply that changes a badge without refetching the detail's bodies were not re-run (the probe was); iOS transport-error class names against `NEVER_SENT`; timings on a session larger than `huge`; the findings timing budgets; the HAR's existing transport-error comment predates findings and carries the call's own error text, unchanged; at 1440 with the detail column open a row that is both repeated and flagged can draw no path (the builder's observation, no criterion, a follow-up for the product owner); iOS, and a real Settings save from the page against the daemon's file beyond QA's own clicks. *`item:sensitive-data-detection` (T1 to T4):* **T5, the page, was open at that close and is now Verified (KIN-124, the fifth close)**: until then a dismissal was made by editing `config.json`; the false-positive rate on a real consuming app's sessions; the cold scan on a real large body-heavy session beyond the builder's synthetic ones (not re-measured by QA); the IBAN table was transcribed from memory (89 countries) and has not been checked against a current SWIFT registry edition, though every entry's example passes mod-97 at its length and QA's comparison of the lengths found no disagreement; the builder's red proofs for PUT validation, dismissals filtered out of exports and the depth bound off by one were not re-run; a dismissal of a payment finding covers every finding of that rule on that route (it matches rule, route, location and path), where the design's invariant says group key, which is for the product owner. *`item:sample-demo-flow`:* the "pay twice + token" button pressed by hand on any platform; the Android and iOS samples running the flow (only desktop CIO ran it; CI compiled the others); the item has no automated guard in the repo. *`item:web-ui-polish` (part 2):* `g d` and `g f` (no Diff or Findings page when A3 merged; the static check covers them over stub rows, and `g f` now has its page); polish part 3 (A4 to A6) is parked and unjudged. *`item:public-claims-true`:* commit identity and the name scan were not re-run by QA for that copy-only PR; the landing page was 605 px wide at 400 px and a card's last bullet was cut, which predated it (**corrected 2026-10-22: the cause was the `.step` and `.grid2` grid items keeping `min-width: auto`, not the architecture SVG, which scrolls inside `.diagram-box`; fixed and Verified by `item:landing-page-fits-400` above**); whose build ran on the Android phone on 2026-08-17 is not recorded (the maintainer reported on 2026-10-22 that the overlay has run on a physical Android phone and works; see Verified).

- **Gaps behind the items closed and part done on 2026-10-22 (the third close).** *Local machine and CI:* no Android SDK on the QA machine, so the full `./gradlew build` and Android
  rest on CI's jobs, which passed on every PR; the push runs on `main` for `e34542e`, `1ed50c1`, `428745a`, `66ab63c` and `4343823` were not seen finishing. Linux ran only in CI.
  *`item:web-live-signal-burst-bounded`:* a lone signal or marker reaches the timeline 277 to 281 ms after arriving (the 250 ms timer plus the render), strictly over "within
  250 ms", as calls are; **the call branch still redraws the waterfall and the path once per call**, so the bound calls meet holds for the timeline and the glance only (a candidate in ROADMAP §6, unordered);
  the marker branch redraws `renderMarkers` and `renderList` per marker and the signal branch `renderTabs` per signal, by design. *`item:guard-measure-interrupt-cleans-up`:* a real
  terminal Ctrl-C (it signals the whole foreground group; the harness signalled the node process alone), Linux, a signal during the probe fixture's gradle write, the small
  `stats-export-*` and `room-export-*` directories that `shell` and `room` leave outside the run's temp dir, and the `if (run.interrupted) return 2` guard on its own (never reached).
  *`item:guard-expanded-unfolded-has-failures`:* the same-millisecond tie branch of `failingVisit`; `measure-web-ui.js` and `./gradlew build` were not run for it. *`item:route-key-one-ipv6-host`:*
  a real dual-client capture; zone ids (a bracketed `[fe80::1%25en0]` from Ktor would be stripped, untested). *`item:findings-payment-safety` (PR A so far):* there is no web, export or
  HAR surface yet (T4 to T8); the 200-item caps, the row-1203 fold, the PUT refusals, the custom rule id and the omitted-body red proof are builder-tested only, passing in the suite and
  not re-run red by QA; Linux; settings are re-read on the next findings read while every other `config.json` key still waits for a restart, and `daemon-and-mcp.md` line 72 words that
  ambiguously (a nit, a candidate in ROADMAP §6). *`item:web-ui-polish` (PR A part 1):* the design has not been looked at (the contact sheet is with the maintainer); `icon target css` and `icon controls svg`
  were shown red by the builder and not re-run by QA; the Settings page reads `0 row(s) in this session` on a direct load of `#/settings` (A5's, not fixed); an export offers six of the
  served side's nine views, by design.
- **Gaps behind the five items closed on 2026-10-22 (the second close).** *Local machine and CI:* no Android SDK on the QA or tracker machine, so the full `./gradlew build` and
  Android rest on CI's jobs, which passed on every PR; the push runs on `main` for `ffc9278`, `95b4de4` and `85e9552` were in progress when QA looked. Linux and Windows ran only through
  CI. *`item:web-switch-clears-call-panes`:* Safari and Firefox (only jsdom and headless Chrome ran it); the delete and clear path (`afterSessionChange`) has no probe of its own and
  goes through the same `followSession`; the `|| sessionPending()` clause in `50-path.js` matters only when a live call arrives during the switch window and no probe drives that, so it
  is unproven (removing it leaves `switch path` green); the tag browser keeps its old empty-state line as a fallback behind an inline guard; Chrome's `topbar`, `room`, `shell` and `first`
  sections were not run for it. *KIN-42:* Safari and Firefox on the huge session; Chrome `topbar` and `shell` in that round; a filter typed during the whole-session read restarts it, by
  design, and one read per session is unplaced; a row arriving during the first page's own request can miss the list until the next load, an address naming a view other than traffic or
  path still waits for the whole load, and the waterfall's stub groups for screens older than the list's page say `no calls` (predates KIN-42), all unplaced; **the overview band's
  `min-height` rule was unguarded (guarded since `item:guard-overview-band-reserved`; Verified above)**: with `.axis-hint.axis-reading { min-height: 24px }` removed, `measure-web-ui.js --only first` stays green (`copy lands huge`, row moved 44 px,
  under its 48 px bound), so "rows move only by the scope bar" is held by the bound and not by an exact reading; the Chrome readings were taken by QA on the merged tree, after the
  builder's working tree was lost and restored. *`item:guard-measure-chrome-exit-fails`:* Linux (only macOS Chrome was killed); a wedged renderer inside a real case (only a scratch
  driver's never-resolving command exercised the deadline); a kill during a `--base` run; Ctrl-C or SIGTERM sent to `measure-web-ui.js` itself left the
  daemons, which `item:guard-measure-interrupt-cleans-up` fixed (Verified above). *`item:mcp-probe-deadline-holds`:* a real wedged `inspector mcp` behind the web UI's button (only the stub ran); a write to a child that never reads is still
  unbounded, though the probe writes two short lines; the 5 s margin on the test's bound covers a JVM start and may need watching on a slow CI runner. *`item:docs-housekeeping-2026-10-21`:*
  a relative `--data` shows a relative path, which is the daemon's own value.
- **Gaps behind the six items closed on 2026-10-22.** *Local machine and CI:* no Android SDK and no working `xcrun` on the QA machine, so the full `./gradlew build`,
  `-Pinspector=off`, the Android targets, the iOS test links and the klib half of `check-release-clean.sh` for these items rest on CI's `build` and `release-swap`
  jobs, which passed on every PR; the release scan was run locally on the JVM jars only (self-test found the canary, the plain run found nothing). The push runs on
  `main` were not seen finishing. *`measure-web-ui.js` is not in CI*, so `--only traffic`, the shell, room and topbar readings and the `routes` fixture run when
  someone runs them; CI runs `check-web-static.js`, `render-web-ui.js` and the archive probe. *`item:guard-probe-finds-nothing-fails`:* `expanded unfolded` opened Home
  and drew no failure, which KIN-98 fixed (Verified above); these were read from the code and not run red: `rail is navigation`, `route pages`, `exportMenu`, `sort flip restores`, `map matches log`,
  `start times shown`, `duplicate badges`, `unanchored chips`, `hop-by-hop stripped`, `onTop`'s `n/a` branch and the socket half of `traffic reaches`; the decision text in
  `web-ui.md` names the Chrome cases fixed in #133 since KIN-97. *`item:guard-live-traffic-driver`:* pause and resume, reconnect, 400px and spaces-only have no committed
  check; `measure-cdp.js` fails a run within about a second when Chrome exits or a DevTools socket closes mid-case, naming the case, and bounds each command at 30 s (KIN-99); the burst bound for a burst longer than a second was
  never exercised. *`item:guard-mcp-probe-cleanup`:* an MCP probe whose child keeps stdout open and writes nothing now returns at its timeout (`item:mcp-probe-deadline-holds`, Verified); Linux ran only in CI, and which operating system each CI job used was not checked.
  *`item:web-stats-route-breaks-whole`:* headless Chrome only, and the base's break count depends on fonts (29 for the builder, 30 for QA); one over-long route widens the
  whole route column at 400px, so fewer number columns fit beside it.
- **`item:endpoint-latency-stats` was built without a design review and without a UX pass, and QA rendered the Stats page in headless Chrome only.** Nobody has
  judged how the table, its tags or the caveat *look* in Safari, Firefox or on a phone. (At 400 px the route cell used to break a hostname mid-word; it breaks only at `.`, `:` or `/` since
  `item:web-stats-route-breaks-whole`, see Verified.) The page does not offer `sort=errors`, which the daemon supports.
- **Route stats timings beyond the `huge` fixture.** Cold and warm times and heap per attempt were taken on the 160,016-row fixture only, not on a real
  recorded session of that size.
- **The refresh's hidden-tab case** rests on the static check `stats refresh hidden`; QA did not drive a hidden Chrome tab. QA's mutation of the export's
  `/stats` branch was not a valid proof (the export embeds the daemon's own files); the builder's proof in the daemon's tree stands and was not repeated.
- **For `item:endpoint-latency-stats`, the full `./gradlew build` and `-Pinspector=off`** were not run on the QA or builder machine (no Android SDK); CI
  ran both on each PR and the main push run on `d256174` succeeded. The main push run on `0a5a1c9` was still in progress when QA wrote.
- **A route holding a character the grammar cannot write links with a very wide term** (`/p|q` gives `path:/*`, and `*` crosses `/`, so it matches every path
  on the host), and two ports of one host share a term. Both are the documented superset and the page's caveat says so; no fix is planned.
- **The bulk routes and `PUT /api/config` sit behind the control gate only.** The private advisory's fix is not built
  (`item:control-boundary-advisory`, last in the order). The new routes leave room for it, and the gaps are not described here.
- **1a's shell in Safari, Firefox and on a phone.** Only headless Chrome 131 was driven. Nobody has judged how the new rail and
  pages *look*: the builder and QA looked at screenshots, no UX review ran, and no design council sat on this item.
- **1b's room for the list was built without a design review and without a UX pass, and QA rendered it in headless Chrome only.** Nobody has
  judged how the one-row toolbar, the `⋮` menu, the overview line, the divider or the `#/txn` page *look* or feel, in Safari, Firefox or on a
  phone. The tools row's `⤢` and `⇥` glyphs are 13 px and nobody has judged them. `#/txn` has no visible way back (Back and Esc only).
- **For 1b, the full `./gradlew build`** was not run on the QA machine (no Android SDK); CI ran it on the PR. The push run on `main` at `4fe71dc`
  was not seen finishing. Not looked at by anyone: the drawer's slide-in and its reduced-motion behaviour (readings cover state, not the
  animation), `#/txn` exported at 400 px, and the divider dragged by keyboard in real Chrome (jsdom covers it). QA ran no mutation on the
  `#/txn` route, the toolbar or the divider probes; for those, base-red and head-green stand in, and the engineers' red proofs are in their
  reports, not repeated.
- **For 1a, the full `./gradlew build` and `-Pinspector=off`** were not run on the QA or builder machine (no Android SDK); CI ran
  them. The `measure-web-ui.js` `first` section (the 160k-call session) was not run. The engineer's `processes bulk` probe stubs the
  kill reply; the real case of a peer exiting before the kill was seen only by QA, in round 2.
- **The "recovers" probe's red proof** is the engineer's record in PR #112's body; QA did not repeat it.
- **Fixed by 1b (2026-10-21):** the closed detail drawer that widened the page at 1000 and 400 px; see Verified.
- **Fixed after 1a (2026-10-22):** the Sessions page names the daemon's own sessions folder, `--data` or not; see Verified.
- **The full `./gradlew build` for the six items of 2026-10-21 was not run on the QA machine** (no Android SDK).
  It rests on CI, which was green on each PR. For phase H and phase I the `-Pinspector=off` build on the Android
  and iOS targets, the iOS framework and test links (no working `xcrun`) and the klib half of
  `check-release-clean.sh` also rest on CI alone; locally only the JVM artifacts were scanned (self-test found the
  canary, the plain run found nothing). Phase I's Android and iOS targets were not run locally.
- **The push runs for `1da0d75` (row labels) and `1479c30` (phase I) were not seen finishing** by QA.
- **Nothing in phase H or phase I was looked at on a device**; the 360dp screenshots are the evidence.
- **The twin pairs' iOS guard matches the stream constructor at source level only, sees no parameter names, and does not
  cover cross-twin linking** (`item:guard-twin-native-surface`). Every pair's iOS surface is read from both iOS targets'
  klibs by `klib dump-abi` and asserted against `api/*.ios.txt`, and its two JVM tests also run in `testAndroidHostTest`
  (Verified, KIN-143 locally and KIN-144 on CI, PR #182). The real `StreamSink` constructor's `Function0<HttpClient>` is read as the noop's
  `Function0<Any?>`, while their IR signatures differ. Klib dumps carry no parameter names, so a renamed parameter in
  iOS-only code passes. A klib compiled against one twin and linked against the other is not tested. The Android half was
  proven red on CI only; there is no Android SDK on the development machine. In `:inspector-core` and `:inspector-noop`,
  `src/iosMain` is not compiled (`twins-and-api.md`). The completeness check and the golden files as test inputs cover the core and stream pairs
  as well as the ui pair (Verified, `item:guard-twin-pairs-alike`). The check is class-level, so a new class or file in the real stream module that
  holds only Ktor-typed API fails it and the API has to go on `StreamSinkKt`.
- **The R12 proof command in the phase I design note** (`:inspector-core:jvmTest :inspector-stream:jvmTest
  -Dinspector.api.regenerate=true --rerun`) re-ran stream only. The result holds when run with `--rerun` after each
  task; the note is not in the repository.
- **IPv6 hosts are bracketed in `url`, labels and the Stats page, but the `host:` filter still uses the stored host**, which is `::1` from OkHttp
  and `[::1]` from Ktor. The route key no longer does: one IPv6 server reached by both clients is one route row (Verified above, `item:route-key-one-ipv6-host`, `1ed50c1`; QA
  had reproduced the two rows at `66c1bad` and `7b37d7a`). A real dual-client capture against `[::1]` was not run for it: the session held archived rows in both stored
  forms. Zone ids (`fe80::1%en0`) were not handled. The IPv6 capture tests ran on QA's Mac; the builder
  reports no skip line in CI's log, which QA did not check, and a runner without an IPv6 loopback reports them skipped.
- **A stretch under a filter holding some loaded rows and some older unloaded matches** says `at least 10 calls
  hidden` and not that more matches are older. At 400 px the header count is off-screen; at 1000 px the closed
  picker drops the start time (ROADMAP 1a (e), 1b).
- **The two items' web changes in Safari, Firefox and on a phone**: only headless Chrome 131 ran them.

- **`item:web-live-rows-filtered` in Safari, Firefox and on a phone**; only headless Chrome 131 ran it.
  `-Pinspector=off` was not run (no consumer signature changed). Not on CI: the branch is not pushed.
- **The burst bound counts re-read requests only.** Each live signal also read `/current`, with or
  without a filter (50 signals gave 53 requests in total); that predates the item, and `item:web-live-signal-burst-bounded` bounded the signal case (Verified above: 100 signals, 5 `/current` reads).
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
- **Closed session picker at 600-700 px** can read `com.example.`, so two sessions of one app look
  alike (ROADMAP 1a (e)).
- **`item:web-live-calls-reach-every-pane` in Safari, Firefox and on a phone**; only headless Chrome 131 ran it. **An open run staying
  open under a live call** was checked in jsdom only (`socket run stays open`), and that line stays green with the redraw removed, so
  it guards the follow rule, not the redraw. **A live signal or marker** redrew the timeline once per message, so a signal
  burst was unbounded the way calls were; `item:web-live-signal-burst-bounded` routed both through `drawLive` (Verified above). **The full
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
  fixes are still unverified on hardware. The overlay ran once on an Android phone (2026-08-17, commit `ef9058e`, a build that predates the Android sample), and **the maintainer reported on 2026-10-22 that it has run on a physical Android phone and works** (reported, not verified by an agent; which build, which device and what was looked at are not recorded). No agent has looked at the Android sample, or at anything added to the overlay since `ef9058e`, on a physical phone.
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
- ~~**Android + iOS sample shells**, to finally see the overlay on a device.~~ Built 2026-09-18; the overlay has run on an emulator, an iOS simulator, an iPhone and (maintainer-reported, 2026-10-22) a physical Android phone.
- ~~**Stretch:** HAR export.~~ Built — `GET /api/sessions/{id}/har`, see the rule in `docs/decisions/exports.md`.
