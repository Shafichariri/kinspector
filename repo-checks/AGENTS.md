# repo-checks — agent guide

JUnit 5 checks over the repository itself — module graph, noop twins, lockfiles, markers, file
budgets, and the decision files and agent guides — run by `./gradlew build`. Tests only, never
published. Its commands are in the root `AGENTS.md`.

Read before changing anything here: `docs/decisions/repository.md`.

Also: `docs/decisions/twins-and-api.md` for what `NoopTwinTest` holds the twins to.

@../docs/decisions/repository.md

## Key files

| Path | Why it matters |
|---|---|
| `repo-checks/` | Build-only, never published. JUnit 5 checks over the repository itself — module graph, noop twins, lockfiles, markers, file budgets — run by `./gradlew build`. |
| `structure/` | The goldens `:repo-checks` reads: `modules.txt` (generated), `twins.txt` and `budgets.txt` (hand-edited, each exemption and budget with its reason, each pair with its golden file). |
