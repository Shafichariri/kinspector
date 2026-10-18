# inspector-model — agent guide

Schema, wire protocol, the filter grammar, USB framing, and every rule both surfaces draw
(`timeline`, `visits`, `pathScope`, duplicates, signal groups, `prettyJson`). Shared on purpose
between device and daemon, and may depend only on kotlinx-serialization.

Read before changing anything here: `docs/decisions/shared-rules.md`, `docs/decisions/signals.md`.

Also: `docs/schema.md` before changing any type here; `docs/decisions/repository.md` for how the
dependency rule is asked of the lockfile; and `docs/STATUS.md` on the live path and the REST path
being two renderings of one row — a new field on `NetworkTransaction` needs a check on both.

@../docs/decisions/shared-rules.md
@../docs/decisions/signals.md

## Commands

```bash
./gradlew :inspector-model:iosSimulatorArm64Test  # iOS
./gradlew :inspector-model:testAndroidHostTest    # Android host
```

## Key files

| Path | Why it matters |
|---|---|
| `inspector-model/.../Filter.kt` | Filter grammar, frozen for v1. |
