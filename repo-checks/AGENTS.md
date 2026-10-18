# repo-checks — agent guide

JUnit 5 checks over the repository itself — module graph, noop twins, lockfiles, markers, file
budgets, and the decision files and agent guides — run by `./gradlew build`. Tests only, never
published. Its commands are in the root `AGENTS.md`.

Read before changing anything here: `docs/decisions/repository.md`.

Also: `docs/decisions/twins-and-api.md` for what `NoopTwinTest` holds the twins to.

@../docs/decisions/repository.md
