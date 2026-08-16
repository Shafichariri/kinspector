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
2. **Host daemon** *(not built yet)* — archives every session to `~/.inspector/sessions/…` as
   readable folders, serves a web UI.
3. **Agents** *(not built yet)* — read `index.jsonl` directly, or query via CLI/MCP.

## What it is not

- Not a process-wide inspector. It sees **Ktor traffic only** — no third-party SDK traffic, no
  WebView, no native iOS `NSURLSession` calls. This was a deliberate v1 scope decision.
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
| **2** | Host daemon, session archive, stream sink, web UI | ⬜ next |
| **3** | CLI + MCP server, session summaries, polish | ⬜ |

**97 tests, 0 failures** across JVM, iOS simulator and Android host.

### Verified

- Redirect and retry each produce one row per attempt, sharing a `callId`.
- App receives byte-identical bodies with the inspector installed: 10 MB, gzip, chunked, empty,
  binary.
- `/secret` shows credentials verbatim by default, redacted under `Redaction.On()`.
- Ring buffer evicts by byte budget; a single oversized entry is still kept.
- `-Pinspector=off` yields a runtime classpath containing only noop modules.
- Canary guard fails when capture code is present and passes when it is not — **both directions
  proven**.
- `./gradlew :sample:desktop:run` launches and serves traffic.

### Not verified

- **The overlay has never been seen on a phone.** Verification so far is desktop-only. There is
  no Android app module and no Xcode project. UI code compiles for all targets.
- Redirect behaviour against engines other than CIO. Some engines follow redirects internally,
  which would collapse a chain into a single row. Unresolved for the target app's engine.

---

## What is next (Phase 2)

In order, from `docs/implementation-plan.md` §10:

1. Daemon skeleton — `serve`, config, data dir, session folders, `latest` symlink.
2. `WS /ingest` + disk writer + meta + retention pruning (100 sessions / 300 MB, configurable,
   never prune the active session).
3. `:inspector-stream` sink with reconnect and session resume (5 min grace).
4. REST API + `SessionRepository` + server-side filter evaluation.
5. Web UI — three-pane, dark, keyboard-driven.

`:inspector-stream/StreamSink.kt` and `:inspector-daemon/Daemon.kt` are placeholder files whose
KDoc already records the contract each must satisfy. Read them before starting.

---

## Architecture

```
:inspector-model     KMP  Schema, wire protocol, filter grammar. Only kotlinx-serialization.
:inspector-core      KMP  Ktor plugin, recorder, ring buffer, redaction, sinks.
:inspector-noop      KMP  Identical public API, does nothing. Release builds.
:inspector-ui        KMP  Compose overlay pill + inspector screens.
:inspector-noop-ui   KMP  Passthrough overlay. Release builds.
:inspector-stream    KMP  WebSocket sink to the daemon.          [Phase 2]
:inspector-daemon    JVM  Archive, REST, web UI, CLI, MCP.       [Phase 2/3]
sample/desktop       JVM  Runnable reference integration.
```

`:inspector-model` is shared between device and daemon **on purpose**: schema types and the
filter parser are then literally the same code on device, in the web UI backend, the CLI and the
MCP tools. A filter string typed in the app is a string you can hand an agent.

Targets: `android`, `iosArm64`, `iosSimulatorArm64`, `jvm`. **No `iosX64`** — Compose
Multiplatform 1.11+ dropped the Intel simulator, so no CMP app can target it.

---

## Non-obvious decisions — do not "fix" these

**Per-attempt rows, not per-call.** Redirects and retries each get a row. The bugs worth catching
hide in the attempts a "one row per call" view would collapse.

**`CallState` is shared by reference.** `HttpRequestRetry` rebuilds every attempt from the
*original* builder via `takeFrom`, which does `attributes.putAll` — entries copied, values shared
by reference. A plain `Int` attempt attribute resets to 1 on every retry. A mutable holder seeded
once in `SetupRequest` is the only thing that survives. `PerAttemptTest` caught this; keep it.

**Hand-rolled body tee.** Ktor's `split` is internal API. The tee forwards every byte unchanged
and copies a capped prefix aside; **past the cap it keeps reading and only counts**, because
stopping early would stall the app's side of the channel. `onComplete` fires in a `finally` so a
body the app abandons still produces a row.

**`rawContent` is `@InternalAPI`.** There is no public accessor for the undecoded body channel;
Ktor's own Logging plugin reads it the same way. Opted in explicitly, pinned to Ktor 3.5.0.
Revisit on upgrade.

**Redaction is OFF by default.** This is a debugging tool for debuggable builds whose capture
code cannot reach production. A debugger that hides the auth header is useless when the bug *is*
the auth header. Do not flip the default. `redacted[]` records what was stripped so consumers can
say "redacted" rather than "absent".

**Streaming request bodies are not buffered.** `ByteArrayContent`/`TextContent` are captured;
channel-backed uploads report `contentLength` only. Buffering a file upload to inspect it is the
exact cost the efficiency contract forbids.

**`path:` filters are dual-mode** — glob when the pattern contains `*`, substring otherwise. A
bare `path:/v2/users` typed in a hurry should find `/v2/users/me`.

**`since:marker("…")` with an unknown label matches nothing**, not everything. A typo silently
becoming "no filter" is the more dangerous failure while debugging.

**No eager `Redaction.Default`.** `On`'s default args read `DEFAULT_HEADERS` off the companion,
so constructing one during the companion's own init deadlocks `<clinit>`. Use `Redaction.On()`.

**Kotlin block comments nest.** A `/*` inside KDoc (e.g. the example `path:/v2/users/*`) swallows
the rest of the file. Write `&#42;` or avoid the sequence.

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

1. **Artifact swap.** `-Pinspector=off` substitutes `:inspector-noop` / `:inspector-noop-ui`.
   Both expose an identical public surface, verified against `api/inspector-public-api.txt` by
   `ApiParityTest` running in *both* modules, so neither can drift silently.
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
./gradlew :sample:desktop:run                     # runnable reference app
./gradlew :inspector-core:jvmTest                 # capture integration tests
./gradlew :inspector-model:iosSimulatorArm64Test  # iOS
./gradlew :inspector-model:testAndroidHostTest    # Android host
./gradlew build -Pinspector=off                   # release swap
```

Changing the public API is deliberately high-friction, because it is a contract:

```bash
# 1. change BOTH :inspector-core and :inspector-noop
# 2. regenerate:
./gradlew :inspector-core:jvmTest -Dinspector.api.regenerate=true
# 3. confirm both modules pass:
./gradlew :inspector-core:jvmTest :inspector-noop:jvmTest
```

---

## Conventions

- **Pinned versions.** Kotlin 2.3.21, Ktor 3.5.0, Compose MP 1.11.1, AGP 9.3.1, Gradle 9.5.1,
  JDK 21. All were verified present in the local Gradle cache before pinning. Do not bump
  casually.
- **Trust the pinned source over blog posts.** Ktor's plugin surface drifted across 2.x/3.x.
  Sources are in the Gradle cache; read them.
- **Tests define correctness, not APIs.** Where Ktor behaviour is uncertain, write the assertion
  first and pick whichever hook makes it pass.
- **Prove guards in both directions.** A guard only ever seen passing is not a guard.
- Comments explain *why*, especially where the obvious approach is wrong.
- `:inspector-model` may depend only on kotlinx-serialization. Keep it that way.

---

## Key files

| Path | Why it matters |
|---|---|
| `docs/schema.md` | The data contract. Read before touching `:inspector-model`. |
| `docs/INTEGRATION.md` | Self-contained guide for integrating into a consuming CMP app. |
| `docs/implementation-plan.md` | Full build order, phases, acceptance criteria. |
| `api/inspector-public-api.txt` | Golden public API surface, asserted by both modules. |
| `inspector-core/.../InspectorPlugin.kt` | Capture hooks, `CallState`, per-attempt logic. |
| `inspector-core/.../BodyCapture.kt` | The tee. The byte-identical guarantee lives here. |
| `inspector-model/.../Filter.kt` | Filter grammar, frozen for v1. |
| `scripts/check-release-clean.sh` | Production-safety enforcement. |
