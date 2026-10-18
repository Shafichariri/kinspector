# inspector-core — agent guide

Capture: the Ktor client plugin, the OkHttp interceptor, the `Recorder` every capture path and
signal goes through, the ring buffer, redaction and the sinks. Debug builds only; release builds
swap in `:inspector-noop`, which must keep the identical public API.

Read before changing anything here: `docs/decisions/capture.md`, `docs/decisions/signals.md`, `docs/decisions/twins-and-api.md`.

Also: `docs/decisions/release.md` for the canary this module carries.

@../docs/decisions/capture.md
@../docs/decisions/signals.md
@../docs/decisions/twins-and-api.md

## Commands

```bash
./gradlew :inspector-core:jvmTest                 # capture integration tests
```

## Key files

| Path | Why it matters |
|---|---|
| `inspector-core/.../InspectorPlugin.kt` | Capture hooks, `CallState`, per-attempt logic. |
| `inspector-core/.../BodyCapture.kt` | The tee. The byte-identical guarantee lives here. |
