# Decisions — repository
Owned by `:repo-checks`: the repository's own rules, locks and test conventions. Imported by the `repo-checks` guide.

**Backtick test names in multiplatform `commonTest` cannot contain commas.** Kotlin/Native rejects
them with `Name contains illegal characters: ","` while the JVM accepts them, so a targeted
`:inspector-model:jvmTest` passes and the full build fails on iOS. `:inspector-daemon` is JVM-only,
so its tests are exempt — do not churn them.

**Dependency locks cover what a consumer receives, and nothing else.** Each published module
locks its `jvmRuntimeClasspath`, `androidRuntimeClasspath` and both `ios*CompileKlibraries` in a
`gradle.lockfile`. The daemon locks its `runtimeClasspath`, which is the release zip. All of them
are `LockMode.STRICT`, so a deleted lockfile fails instead of silently unlocking. Locking is not
`lockAllConfigurations()`, because the Kotlin/Native bundle and `compose.desktop.currentOs` resolve
per host, so a lock written on one machine fails on the next. Test classpaths are not locked
either, because no consumer sees them. Regenerate on macOS, the only host where the iOS graphs are
real, with the command under Commands.

**`resolveLockedConfigurations` is load-bearing, and the obvious way to write it checks nothing.**
Nothing else in a library build resolves a main runtime classpath: the jars, the publications and
the tests all read other configurations. Without this task, wired into `check`, the locks above
would be read by nobody. The first version resolved `incoming.resolutionResult.rootComponent`, as
"graph only" suggests. **It passed with a new runtime dependency and with a stale lock entry**:
the resolution result hands back the graph and drops lock validation. Only an artifact resolution
rethrows those failures. So the task resolves an artifact view whose `componentFilter` selects
nothing. That validates the whole graph against the lock without downloading one artifact or
building a sibling's klib. Gradle 9.5.1 does fail on a stale entry ("Did not resolve … which is
part of the dependency lock state"), once something asks it in a way that surfaces it.

With the configuration cache on, which it is by default here, that failure arrives headed
`Configuration cache state could not be cached: field __lockedGraphs__ …`, with the lock message
two lines further down. It reads like a cache incompatibility and is not one. The resolution runs
while the cache entry is written, so this is where the error surfaces. Marking the task
`notCompatibleWithConfigurationCache()` would only move the same failure to execution time.
Nothing automated proves this read still validates the lock, only the manual proofs recorded in
ROADMAP §5 phase B. Re-run the stale-entry proof after any Gradle upgrade.

**`:inspector-model`'s dependency rule is asked of its lockfile, because STRICT cannot hold it.**
A lock regenerated after adding a dependency is perfectly consistent. Measured: kotlinx-datetime
added to the model with its lock regenerated, and STRICT resolved green. So `DependencyLockTest`
holds every module in `inspector-model/gradle.lockfile` to an allowlist with a reason per entry.
The allowlist holds serialization-json, serialization-core and the serialization BOM, the stdlib,
and `org.jetbrains:annotations`, which the stdlib brings on the JVM and Android. The lockfile covers
only the locked graphs, the runtime classpaths and the iOS klibs, so a JVM-only `compileOnly`
dependency would not appear in it.

**Kotlin block comments nest.** A `/*` inside KDoc (e.g. the example `path:/v2/users/*`) swallows
the rest of the file. Write `&#42;` or avoid the sequence.

**The two module test frameworks differ.** `:inspector-core` and the KMP modules run **JUnit 4**
(`kotlin("test")` on JVM), where a test method must return `Unit` — `fun x() = runBlocking { … }`
fails at class-init with "should be void" if the block's last expression returns a value, and
`assertNotNull` returns its argument. `:inspector-daemon` runs **JUnit 5**
(`useJUnitPlatform()`), where a value-returning `@BeforeEach` is rejected instead.

`scripts/check-client-names.sh` is that check. Run it locally with `--terms-file` before
committing. CI's `repo` job runs it too, with the terms from the `CLIENT_NAME_TERMS` secret, and
that catches a match **before merge, not before publication**: by the time CI sees a branch, it is
on a public remote. The script reports file and line, a count of matching paths, the SHA of a
matching commit message, and the fact that the branch name matches. It never prints the matched
text, because GitHub masks only a secret's whole value, so one term out of a list would print in
clear in a public log.

**A scan with no terms says NOT RUN, and NOT RUN is never a pass.** A fork's pull request gets no
secrets, and an unset secret arrives empty. In both cases the script prints `NOT RUN` and a
`::warning::`, then exits 0. It never prints "clean" or "0 matches". `--require-terms` makes that
case exit 1. CI does not pass it yet, because the secret has not been created and `main` would go
red. Add it to the push-to-`main` run once the secret exists.

`scripts/check-commit-identity.sh <base> <head>` enforces this over every commit in a range, merges
included, and CI's `repo` job runs it over what a pull request adds and what a push to `main`
brings. It names the commit and the failing field, never the address. An empty or uncomputable
range exits 2, because a check over no commits must not pass.

A merge, squash or "Update branch" made on github.com takes its author email from the merging
account on GitHub, not from any local git config. The merge dialog offers every verified address
unless **Settings → Emails → "Keep my email addresses private"** is on, and then it offers only the
noreply address. History on `main` shows the dialog picking another address on some merges and
not on others. That setting is therefore part of this rule. If a web merge fails here, fix the
setting, not the check. Do not exempt commits whose committer is `noreply@github.com` from the
author check either: the push run is the only place a merge commit is checked at all, because a
pull request's checks finish before its merge commit exists. Do not rewrite merges already on
`main` to make them pass, per the paragraph on history in the root `AGENTS.md`. The check reads only the range a
push brings, so it never sees them again.

`gradle/wrapper/gradle-wrapper.jar` throws false positives on short alphabetic patterns: it is
compressed binary, stock Gradle, unmodified since the first commit. Ignore it; investigate anything
else a scan turns up.

Two conventions `:repo-checks` holds, moved out of the root so it stays under its budget:

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
