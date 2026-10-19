# Inspector — agent guide

Canonical context file for AI coding agents, and an index. `CLAUDE.md` and `.cursorrules` link
here, as does `.cursor/rules/inspector.mdc`. There is **one copy of each decision**, in
`docs/decisions/`, never also here or in a guide; `DecisionsTest` fails on a second copy. Every
module has an `AGENTS.md` naming the decision files to read before changing it.

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

**There are four surfaces, and only two are for users.** The in-app overlay and the browser web
UI are the products. The daemon is a headless CLI, and `sample/desktop` is a demo, not a tool.
Nobody should be told to "open the desktop app".

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
| **S-D** | Structure pass (not a capture letter), phase D — daemon packages, layering check | 🟡 built, done once PR merged and CI green |
| **S-F** | Structure pass, phase F — web UI script and CSS split behind the manifest | ✅ done (PR #88; `repo` red on the merge push for author identity only, see `docs/STATUS.md`) |

The lettered rows are **capture mechanisms** and are lettered independently of Phase 4, which is
signals. Two numbering schemes met here and the collision is historical; `implementation-plan.md`
owns the phases, and the letters only ever appear in the capture roadmap in `docs/decisions/capture.md`.

Counts, Verified and Not verified are in `docs/STATUS.md`. It is not in context: read it before
claiming anything is verified.

---

## Architecture

```
:inspector-model       KMP  Schema, wire protocol, filter grammar, USB framing, the rules both
                            surfaces draw. Only kotlinx-serialization.
:inspector-core        KMP  Ktor plugin, OkHttp interceptor, recorder, ring buffer, redaction, signals.
:inspector-noop        KMP  Release twin of core. Golden: api/inspector-public-api.txt.
:inspector-ui          KMP  Compose overlay pill + inspector screens.
:inspector-noop-ui     KMP  Release twin of ui. Golden: none yet (phase I).
:inspector-stream      KMP  Sink to the daemon: dials /ingest, or on an iPhone listens for the bridge.
:inspector-noop-stream KMP  Release twin of stream. Golden: api/inspector-stream-public-api.txt.
:inspector-daemon      JVM  Archive, REST, live WS, web UI, CLI, MCP server, USB bridge.
:repo-checks           JVM  Build-only structural checks over the repo. Never published.
:sample:desktop        JVM  Runnable reference integration.
:sample:android        APK  The same sample on a device or emulator.
:sample:ios:shared     KMP  Framework for the Xcode project in sample/ios/iosApp.
```

Not modules: `sample/shared/kotlin` (the samples' common source), `api/`, `api-parity/`,
`mirror-cases/`, `structure/`, `scripts/` and `site/` (the landing page).

`:inspector-model` is shared between device and daemon **on purpose**: schema types and the
filter parser are then literally the same code on device, in the web UI backend, the CLI and the
MCP tools. A filter string typed in the app is a string you can hand an agent.

Targets: `android`, `iosArm64`, `iosSimulatorArm64`, `jvm`. **No `iosX64`** — Compose
Multiplatform 1.11+ dropped the Intel simulator, so no CMP app can target it.

---

## Non-obvious decisions — do not "fix" these

| File | Holds |
|---|---|
| `docs/decisions/capture.md` | Ktor and OkHttp capture, the tee, redaction, what Ktor cannot see. |
| `docs/decisions/signals.md` | Signals in the overlay: runs, providers, the now strip, ages, payloads. |
| `docs/decisions/shared-rules.md` | Model rules the web UI mirrors, the filter grammar, the palette. |
| `docs/decisions/overlay.md` | The Compose overlay: chrome, detail, JSON tree, ellipsis, insets, tests. |
| `docs/decisions/web-ui.md` | The browser UI and its probes. |
| `docs/decisions/exports.md` | HAR, Markdown and HTML exports. |
| `docs/decisions/daemon-and-mcp.md` | The daemon, its loopback boundary, replay signing, peers, MCP. |
| `docs/decisions/devices-and-stream.md` | How an app reaches the daemon from an emulator or a phone. |
| `docs/decisions/twins-and-api.md` | The noop twins, golden files, API versus ABI. |
| `docs/decisions/release.md` | The release guard, publishing, and cutting a release. |
| `docs/decisions/repository.md` | Locks, test traps, the name and identity scans. |

A new decision goes in its area's file; a new file joins this table. A lead naming two areas goes
to the first that applies: its parent's file if it continues one, `twins-and-api`, `release`,
`signals`, `shared-rules`, the module whose test pins it, `repository`.

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
node scripts/check-web-static.js                  # web audits + every mirror case; no daemon, no npm
./gradlew build -Pinspector=off                   # release swap
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

Module commands are in each guide; releases in `docs/decisions/release.md`; API regeneration in
`api-parity/AGENTS.md`.

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
work address. The maintainer's personal address, `shafichariri@gmail.com`, is also accepted as an
author or committer: by their choice it is what they pick in the web merge dialog. Otherwise the
committer email is the noreply address, or `noreply@github.com` when the commit was made on
github.com, as a pull request merge is. Agents still commit as the noreply address. Only emails are checked: GitHub attributes by
email, and a display name identifies nobody. No `Co-Authored-By:` or `Claude-Session:` lines in
commit messages, whatever a tool's default says. GitHub lists commit authors as repository
contributors, and four commits authored as `Claude <noreply@anthropic.com>` put an AI account on
that list. Check `git config user.email` before the first commit in a fresh clone or a cloud
session; a per-commit `-c user.email=…` or the `GIT_AUTHOR_EMAIL`/`GIT_COMMITTER_EMAIL` variables
work where the local config holds another address.

How it is enforced is in `docs/decisions/repository.md`.

---

## Key files

| Path | Why it matters |
|---|---|
| `docs/INTEGRATION.md` | Self-contained guide for integrating into a consuming CMP app. **Versioned** — it is handed to other teams as a file, so a reader cannot diff it against anything. Any change that affects a consumer bumps the version line at the top and adds a changelog entry saying what they must *do*, not just what changed. |
| `docs/ROADMAP.md` | **The live backlog.** What is next, what it overturns, and what is deliberately not being done. Every other plan-shaped document here is a record of finished work — this is the one to update when the plan changes. |
| `docs/README.md` | What every other document in `docs/` is for. |
| `*/gradle.lockfile` | Dependency goldens for every published module and the daemon. Regenerate on macOS; see Commands. |
| `scripts/check-commit-identity.sh`, `scripts/check-client-names.sh` | CI's `repo` job. The name scan is also the check to run locally before committing. |
| `.github/workflows/release.yml` | Tag-triggered. Publishes the daemon zip (smoke-tested first) and the library to GitHub Packages, and stages it on Maven Central. Three jobs — the two library ones need macOS for the iOS klibs. |
| `mirror-cases/` | One JSON file per mirrored rule, run by both the Kotlin tests and `check-web-static.js`. Add a case here, never to one side. Its README has the format. |
