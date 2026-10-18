# Inspector — agent guide

Canonical context file for AI coding agents. `CLAUDE.md` is a symlink to this file and
`.cursor/rules/inspector.mdc` points here, so **there is exactly one copy to keep current** —
edit this file, never a duplicate.

---

## What this is

A network inspector for **Compose Multiplatform apps that use Ktor**. Think Wormholy (iOS), but
multiplatform, and with a host-side archive and first-class access for AI agents.

Three consumers of the same captured data:

1. **In-app** — a draggable overlay pill showing `GET /users · 200 · 143ms`, tapping into a full
   inspector. Works on Android, iOS and desktop from one Compose codebase.
2. **Host daemon** — archives every session to `~/.inspector/sessions/…` as readable folders and
   serves a web UI at `http://127.0.0.1:8099`. Operations are in `docs/DAEMON.md`.
3. **Agents** — read `index.jsonl` directly, or query via the CLI or the MCP server.

## What it is not

- Not a process-wide inspector. It sees **Ktor traffic, plus any OkHttp client the app hands the
  interceptor to** — so Auth0/Retrofit/Coil are reachable on Android and JVM with a line of app
  code, but WebViews and native iOS `NSURLSession` calls are not. Deliberate scope; the universal
  answer is proxy capture (4c), which is not started.
- Not wire-level. Bodies may already be decompressed; there is no DNS/TLS/TTFB breakdown.
- Not for WebSockets or SSE.
- Not shipped to production, ever. See **Production safety** below — this is enforced, not
  conventional.

---

## Where we are

| Phase | Scope | Status |
|---|---|---|
| **0** | Schema, filter grammar, API contract, release guard | ✅ done |
| **1** | Capture, ring buffer, redaction, overlay + inspector UI | ✅ done |
| **2** | Host daemon, session archive, stream sink, web UI | ✅ done |
| **3** | MCP server over the archive | ✅ done |
| **4** | Signals — screens, view-model state and caches on the traffic timeline | ✅ done, 0.3.0 |
| **4a** | OkHttp capture, for SDKs that own their transport | ✅ done |
| **4b** | Report what we know we cannot see | ⬜ not designed |
| **4c** | Proxy capture — iOS `URLSession`, WebViews, opaque SDKs | ⬜ not started |

The lettered rows are **capture mechanisms** and are lettered independently of Phase 4, which is
signals. Two numbering schemes met here and the collision is historical; `implementation-plan.md`
owns the phases, and the letters only ever appear in the capture roadmap below.

---

## Architecture

```
:inspector-model     KMP  Schema, wire protocol, filter grammar. Only kotlinx-serialization.
:inspector-core      KMP  Ktor plugin, recorder, ring buffer, redaction, sinks.
:inspector-noop      KMP  Identical public API, does nothing. Release builds.
:inspector-ui        KMP  Compose overlay pill + inspector screens.
:inspector-noop-ui   KMP  Passthrough overlay. Release builds.
:inspector-noop-stream KMP  No-op stream sink. Release builds.
:inspector-stream    KMP  WebSocket sink to the daemon.
:inspector-daemon    JVM  Archive, REST, live WS, web UI, CLI.   MCP is Phase 3.
:repo-checks         JVM  Build-only structural checks over the repo. Never published.
sample/desktop       JVM  Runnable reference integration.
```

`:inspector-model` is shared between device and daemon **on purpose**: schema types and the
filter parser are then literally the same code on device, in the web UI backend, the CLI and the
MCP tools. A filter string typed in the app is a string you can hand an agent.

Targets: `android`, `iosArm64`, `iosSimulatorArm64`, `jvm`. **No `iosX64`** — Compose
Multiplatform 1.11+ dropped the Intel simulator, so no CMP app can target it.

---

## Non-obvious decisions — do not "fix" these

The decisions moved, word for word, into `docs/decisions/`, one file per area; the state of the project — counts, Verified, Not verified — moved into `docs/STATUS.md`.

**There are four surfaces, and only two are for users.** The in-app overlay and the browser web
UI are the products. The daemon is a headless CLI, and `sample/desktop` is a demo, not a tool.
Nobody should be told to "open the desktop app".

---

## Efficiency contract

The app must never block on, wait for, or meaningfully allocate because of the inspector.

- Capture on the caller's coroutine does only a metadata snapshot plus `trySend` into a
  `Channel(256, DROP_OLDEST)`. Backpressure never reaches the app.
- One dedicated worker (`Dispatchers.Default.limitedParallelism(1)`) does ring accounting and
  sink fan-out. A sink that throws is caught and skipped.
- Body capture is the only real cost, gated by a 256 KB cap and a content-type allowlist.
- Release builds use the noop artifact: cost is *zero*, not *small*.

**DROP_OLDEST everywhere on the hot path. The app's traffic is sacred; the inspector's
completeness is not.**

---

## Production safety — two layers

1. **Artifact swap.** `-Pinspector=off` substitutes `:inspector-noop` / `:inspector-noop-ui` /
   `:inspector-noop-stream`. Each pair is verified against a golden file by a parity test running
   in *both* modules of the pair, so neither can drift silently: `api/inspector-public-api.txt`
   for `core`, and `api/inspector-stream-public-api.txt` for `stream`. The `ui` pair has no golden
   file yet.
2. **Canary guard.** `:inspector-core` carries `INSPECTOR_CANARY_…`;
   `scripts/check-release-clean.sh` fails if it appears in a release artifact. The script reads
   the canary from the Kotlin source rather than hardcoding it, and scans Kotlin/Native klib
   directories as well as jars/aars — an earlier version scanned only jars and so reported iOS
   builds clean purely because it never looked.

**Always run the self-test alongside the real check.** A detector that cannot detect would report
every release build clean:

```bash
./scripts/check-release-clean.sh --self-test   # must FIND the canary
./scripts/check-release-clean.sh               # must find NOTHING
```

---

## Commands

```bash
./gradlew build                                   # everything, all targets
./gradlew :inspector-daemon:installDist           # then: inspector-daemon/build/install/inspector/bin/inspector serve
./gradlew :sample:desktop:run -Dinspector.sample.autofire=true   # scripted traffic, no clicking
node scripts/check-web-static.js                  # web audits + every mirror case; no daemon, no npm
npm install --no-save --no-package-lock jsdom@29.1.1 # the version CI pins
node scripts/render-web-ui.js > /tmp/ui.html      # web UI smoke test + snapshot
./gradlew :inspector-daemon:writeProbeFixture -Pinspector.probeDir=/tmp/probe   # the session CI probes; re-running replaces it
inspector-daemon/build/install/inspector/bin/inspector serve --data /tmp/probe --port 8231 &   # ... served off 8099
INSPECTOR_UI_ORIGIN=http://127.0.0.1:8231 INSPECTOR_UI_EXPECT_CHECKS=<UI_EXPECT_CHECKS in ci.yml> \
  node scripts/render-web-ui.js > /tmp/ui.html    # ... judged as CI judges it; the count only holds on that session
INSPECTOR_UI_SESSION=<id> node scripts/render-web-ui.js           # ... against a chosen session
INSPECTOR_UI_ORIGIN=http://127.0.0.1:8231 node scripts/render-web-ui.js   # ... a daemon off 8099
curl -s http://127.0.0.1:8099/api/sessions/latest/export.html -o /tmp/s.html     # an exported session
INSPECTOR_UI_ARCHIVE=/tmp/s.html node scripts/render-web-ui-archive.js        # ... works with no network
./gradlew :sample:desktop:run                     # runnable reference app
./gradlew :sample:android:installDebug            # the same app on a device or emulator
adb shell am start -n dev.inspector.sample/.MainActivity        # ... and launch it
xcodebuild -project sample/ios/iosApp/iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' build
xcrun simctl install booted <path>/iosApp.app && xcrun simctl launch booted dev.inspector.sample
# ... on a physical iPhone over USB: sign it (the project ships unsigned), install, run `inspector serve`
xcodebuild -project sample/ios/iosApp/iosApp.xcodeproj -scheme iosApp -destination 'platform=iOS,id=<udid>' \
  -allowProvisioningUpdates CODE_SIGNING_ALLOWED=YES CODE_SIGN_STYLE=Automatic DEVELOPMENT_TEAM=<team> build
xcrun devicectl device install app --device <id> <path>/iosApp.app
inspector serve --port 8231 --usb-port 8099       # a second daemon beside your usual one; the app still listens on 8099
./gradlew :inspector-core:jvmTest                 # capture integration tests
./gradlew :inspector-model:iosSimulatorArm64Test  # iOS
./gradlew :inspector-model:testAndroidHostTest    # Android host
./gradlew :inspector-ui:jvmTest                   # overlay: prefix rules + a real render
open inspector-ui/build/screenshots/list-dark.png # ... what that render produced
./gradlew build -Pinspector=off                   # release swap
./gradlew :inspector-daemon:distZip -Pinspector.version=0.2.0   # the release zip, as CI builds it
./gradlew publishToMavenLocal -Pinspector.version=0.2.0-local   # publish the library locally
./gradlew :repo-checks:test                       # module graph, twins, locks, markers, file budgets
./gradlew :repo-checks:test -Dinspector.modules.regenerate=true     # ... rewrite structure/modules.txt after a deliberate edge change
./gradlew :repo-checks:test -Dinspector.budgets.tighten=true        # ... lower or remove budgets after a file shrank; touches nothing else
# regenerate every gradle.lockfile, on macOS, after a deliberate dependency change:
./gradlew resolveLockedConfigurations :inspector-daemon:dependencies --configuration runtimeClasspath --write-locks
./scripts/check-commit-identity.sh origin/main HEAD                 # your commits, before pushing
./scripts/check-client-names.sh --terms-file <your terms> --range origin/main..HEAD --branch "$(git branch --show-current)"
```

`./gradlew dependencies --write-locks` from the root looks like the regenerate command and is not.
In Gradle 9.5.1 it runs only the root project's report, which has no configurations, and writes
nothing a module locks.

Driving the MCP server by hand, which is the fastest way to check a tool change:

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"session_summary","arguments":{}}}' \
  | inspector-daemon/build/install/inspector/bin/inspector mcp
```

Changing the public API is deliberately high-friction, because it is a contract:

```bash
# 1. change BOTH :inspector-core and :inspector-noop
# 2. regenerate:
./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true
# 3. confirm both modules pass:
./gradlew :inspector-core:jvmTest :inspector-noop:jvmTest
```

The stream pair has its own golden file, and the same friction:

```bash
# 1. change BOTH :inspector-stream and :inspector-noop-stream
# 2. regenerate (from the REAL module — it is the one that knows what is Ktor-typed):
./gradlew :inspector-stream:jvmTest -Dinspector.api.regenerate=true
# 3. confirm both modules pass:
./gradlew :inspector-stream:jvmTest :inspector-noop-stream:jvmTest
```

---

## Conventions

- **Pinned versions.** Kotlin 2.3.20, Ktor 3.5.0, Compose MP 1.11.1, AGP 9.2.1, Gradle 9.5.1,
  JDK 21. All were verified present in the local Gradle cache before pinning. Do not bump
  casually. The `gradle.lockfile`s enforce it: any change to what resolves, direct or
  transitive, fails `check` until the locks are regenerated on purpose.
- **Trust the pinned source over blog posts.** Ktor's plugin surface drifted across 2.x/3.x.
  Sources are in the Gradle cache; read them.
- **Tests define correctness, not APIs.** Where Ktor behaviour is uncertain, write the assertion
  first and pick whichever hook makes it pass.
- **Prove guards in both directions.** A guard only ever seen passing is not a guard.
- Comments explain *why*, especially where the obvious approach is wrong.
- `:inspector-model` may depend only on kotlinx-serialization. Keep it that way. `:repo-checks`
  enforces it for what the model resolves at runtime and links on iOS.
- **No deferred-work markers in source.** Unfinished work goes in `docs/ROADMAP.md`. `NoTodoTest`
  fails on the uppercase markers in any file git tracks or would track, except Markdown.
- **A source file over 600 lines needs a budget, and budgets only tighten.** "Source" means `.kt`,
  `.kts`, `.js`, `.css`, `.html`, `.sh` and `.swift`, tracked or untracked-but-not-ignored, and
  `FileBudgetTest` holds it. `structure/budgets.txt` is a
  ratchet, not a cap: a budgeted file fails when it grows, and it also fails when it *shrinks*,
  until the budget is lowered to match. A budget left at the old size would let the lost lines
  back in unnoticed. `-Dinspector.budgets.tighten=true` only lowers or removes, and never touches
  `modules.txt`. Raising a budget or adding a file is a
  hand edit with the reason on the line.

---

## Reproducing the full stack locally

```bash
./gradlew :inspector-daemon:installDist
inspector-daemon/build/install/inspector/bin/inspector serve --data /tmp/demo &
./gradlew :sample:desktop:run -Dinspector.sample.autofire=true
open http://127.0.0.1:8099
```

Sessions land in `/tmp/demo/sessions/`; `/tmp/demo/latest` symlinks the newest.

---

## No client names in this repository

**This repository is public.** Nothing committed here may name a client, an employer, or one of
their products or domains. That includes prose, comments, test fixtures, sample data, commit
messages and branch names — history is as public as the working tree.

The rule exists because the design docs are strongest when argued from a real app, and the
temptation is to name it for credibility. Do not. Say "one real consuming app", describe the
constraint that mattered ("its navigation destinations are already `@Serializable`"), and drop the
identity — the constraint is what carries the argument, never the name.

History was scrubbed once already, with `filter-branch` across every commit, before the repository
went public. Doing that again after a push is not a cleanup — the old objects survive on the remote
and in every clone — so the check belongs before the commit, not after.

**The terms to grep for are deliberately not listed in this file.** Writing them here would publish
exactly the list of things that were removed, which defeats the removal. They live in the
maintainer's local notes; ask if you do not have them. Commit author identity is the GitHub noreply
address for the same reason — check it stays that way if you ever configure git in a fresh clone.

**Agents commit as the maintainer and add no attribution trailers.** The author email is
`3518471+Shafichariri@users.noreply.github.com`, never an agent's own identity and never a
personal or work address. The committer email is the same, or `noreply@github.com` when the commit
was made on github.com, as a pull request merge is. Only emails are checked: GitHub attributes by
email, and a display name identifies nobody. No `Co-Authored-By:` or `Claude-Session:` lines in
commit messages, whatever a tool's default says. GitHub lists commit authors as repository
contributors, and four commits authored as `Claude <noreply@anthropic.com>` put an AI account on
that list. Check `git config user.email` before the first commit in a fresh clone or a cloud
session; a per-commit `-c user.email=…` or the `GIT_AUTHOR_EMAIL`/`GIT_COMMITTER_EMAIL` variables
work where the local config holds another address.

---

## Key files

| Path | Why it matters |
|---|---|
| `docs/schema.md` | The data contract. Read before touching `:inspector-model`. |
| `docs/ACCESS.md` | Who can get Inspector and how, and the honest answer for someone who cannot. Update it if the distribution story changes — it is the only doc that answers "am I blocked". |
| `docs/DAEMON.md` | Running the daemon: start, stop, restart, kill, the CLI, archive layout, troubleshooting. Update it when a flag or command changes. |
| `docs/INTEGRATION.md` | Self-contained guide for integrating into a consuming CMP app. **Versioned** — it is handed to other teams as a file, so a reader cannot diff it against anything. Any change that affects a consumer bumps the version line at the top and adds a changelog entry saying what they must *do*, not just what changed. |
| `docs/TEAM.md` | The agent team in `.claude/agents/` — product owner, architect, engineer, QA, UX, tracker, release steward, business strategist — and the `roadmap-cycle` skill that runs them. It adds no tracking document of its own: the backlog stays here in `ROADMAP.md`, state stays in "Where we are". |
| `docs/ROADMAP.md` | **The live backlog.** What is next, what it overturns, and what is deliberately not being done. Every other plan-shaped document here is a record of finished work — this is the one to update when the plan changes. |
| `docs/implementation-plan.md` | Full build order, phases, acceptance criteria. Phases 0–4 shipped, signals in 0.3.0; of the capture mechanisms, 4a shipped and 4b/4c have not started. A record of the original build — do not rewrite it to match later work. |
| `docs/SIGNALS.md` | Why signals are shaped the way they are — app state on the traffic timeline. **Built and released in 0.3.0**; kept as the design record, so where it and the code disagree, the code won. Read it before changing anything signal-shaped: it carries the decisions, the traps, and what was deliberately left out. Its build order is numbered in *stages* so it does not collide with the plan's phases. |
| `docs/SIGNALS-CHECKLIST.md` | What must be true of a build that records signals — assertions only, no rationale. The live companion to the spec above; update this one when the bar moves. |
| `docs/REPLAY.md` | Replay, on-device re-signing and daemon control. Steps 1–3 are built; the template generators (step 4) are specified and not written. Read it before touching replay — the signing constraint decides the whole architecture. |
| `api/inspector-public-api.txt` | Golden public API surface for the `core` pair, asserted by both modules. |
| `api/inspector-stream-public-api.txt` | The same for the `stream` pair, minus the Ktor-typed members a release build cannot carry. |
| `api-parity/` | `shared/` is the reflection and golden-file machinery; `core/` and `stream/` hold one test each. Every directory here is compiled into the `jvmTest` of the two modules it guards. |
| `inspector-core/.../InspectorPlugin.kt` | Capture hooks, `CallState`, per-attempt logic. |
| `inspector-core/.../BodyCapture.kt` | The tee. The byte-identical guarantee lives here. |
| `inspector-model/.../Filter.kt` | Filter grammar, frozen for v1. |
| `scripts/check-release-clean.sh` | Production-safety enforcement. |
| `repo-checks/` | Build-only, never published. JUnit 5 checks over the repository itself — module graph, noop twins, lockfiles, markers, file budgets — run by `./gradlew build`. |
| `structure/` | The goldens `:repo-checks` reads: `modules.txt` (generated), `twins.txt` and `budgets.txt` (hand-edited, each exemption and budget with its reason). |
| `*/gradle.lockfile` | Dependency goldens for every published module and the daemon. Regenerate on macOS; see Commands. |
| `scripts/check-commit-identity.sh`, `scripts/check-client-names.sh` | CI's `repo` job. The name scan is also the check to run locally before committing. |
| `inspector-stream/.../Transport.kt` | How `StreamSink` reaches the host: dialling `/ingest`, or — on a physical iPhone — listening on the phone's loopback for the USB bridge. |
| `inspector-daemon/.../usb/UsbBridge.kt` | The iOS counterpart of `adb reverse`: polls usbmuxd, dials the app's port, relays into the daemon's own `/ingest`. `Usbmux.kt` and `Plist.kt` beside it are the protocol. |
| `.github/workflows/release.yml` | Tag-triggered. Publishes the daemon zip (smoke-tested first) and the library to GitHub Packages. Two jobs, two runners — the library half needs macOS for the iOS klibs. |
| `inspector-daemon/.../Har.kt` | HAR 1.2 export. Read the class doc before changing a field: most of it is about what the format asks for and capture cannot answer. |
| `scripts/check-web-static.js` | The web checks that need nothing running: network call sites, the stylesheet audits, the JSON parser and every mirror case. First step of the CI `web` job. |
| `mirror-cases/` | One JSON file per mirrored rule, run by both the Kotlin tests and `check-web-static.js`. Add a case here, never to one side. Its README has the format. |
| `scripts/render-web-ui.js` | The live web UI's check; run it after touching `web/`, along with the archive probe below. CI runs both against a fixture session and pins how many checks pass. |
| `scripts/render-web-ui-archive.js` | Opens an exported session's HTML with the network forbidden and checks it works offline. Run it after touching `web/`: any new request that bypasses `source` fails it. |
| `inspector-daemon/.../Export.kt` | `SessionExport`: the one document all three exports render from. `ExportMarkdown.kt` and `HtmlExport.kt` beside it are the other two forms. |
| `inspector-ui/.../InspectorList.kt` | The overlay's list and row. Two lines per call, and the bar that lifts the shared path prefix out of them. |
| `inspector-ui/.../Formatting.kt` | Row presentation, including `pathScope` — the shared-prefix rules, and the thresholds that decide whether the bar appears at all. |
| `inspector-ui/.../InspectorSignalDetail.kt` | One observation: payload, provenance and the history of its key. Where stage 1's undrawn payloads went. |
| `inspector-ui/.../NowStrip.kt` | What the app holds now — latest per `(tag, name)`, with age and provenance. Sorted stably, unlike `signalGroups`; read its doc before changing either. |
| `inspector-ui/src/jvmTest/.../ListRenderTest.kt` | The mobile equivalent of `render-web-ui.js`: renders the real list **and the signal screen** at 360dp and writes PNGs to `inspector-ui/build/screenshots/`. Run it after touching either, then *look at the output* — it is there to be read, not just to pass. Two layout defects and a misleading fixture clock in stage 2 were found only by looking. |
| `inspector-daemon/.../SessionRepository.kt` | Every archive read. The MCP tools wrap this. |
| `inspector-daemon/src/main/resources/web/` | The web UI. No build step, no dependencies. |
