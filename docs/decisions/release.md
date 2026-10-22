# Decisions — release
Owned by the root build, `.github/workflows/release.yml` and `scripts/check-release-clean.sh`. No guide imports it; the `inspector-core` and `inspector-daemon` guides point here.

**A guard that scans nothing must fail, not pass.** `check-release-clean.sh` used to treat a
missing `--paths` target as `skip (missing)` and still print `PASSED: no capture code found in 1
artifact(s)` with exit 0. It also `cd`s to its own repo root, so a *relative* path from a consumer's
build directory resolved against Inspector and missed every time — the two combined into a release
guard that green-lit a build it had never looked at. Relative `--paths` now resolve against the
caller's directory, and any missing target is a hard exit 2. Reported from a consuming app. This
is the same principle the `--self-test` mode exists for: a broken detector reports everything
clean.

**The canary is findable in a klib and invisible in a linked iOS binary, and both are correct.**
`check-release-clean.sh` greps ASCII, and that is right for what it scans: klibs, jars and aars
keep string literals as ASCII, which is why `--self-test` finds the canary in
`iosSimulatorArm64/main/klib`. It stops being right one step later. Kotlin/Native stores literals
as **UTF-16** in the linked product, so in a built `.app` the canary appears zero times as ASCII
and twice as UTF-16 — measured, not assumed — while `dev.inspector` appears 2681 times. A consumer
who greps their `.app` for the canary therefore gets a guard that passes forever, including on a
build full of capture code. That is why `INTEGRATION.md` §3 tells them to grep `dev/inspector/`
and `dev.inspector.` instead, and why a consumer-side guard is not the same script as this one.
Do not "fix" this by making the script search UTF-16: it never sees a linked binary.

**The group is `io.github.shafichariri`, and `dev.inspector` is the package, not the group.** The
two differ on purpose and the difference is load-bearing: Maven Central verifies a `dev.*`
namespace against the matching domain and `inspector.dev` belongs to somebody else, so that group
was never claimable there. Artifact ids and the Kotlin package were left alone in the move —
`import dev.inspector.Inspector` still compiles, and §3's release-guard advice still greps
`dev/inspector/` for exactly that reason.

**A POM value that reads a receiver is the easiest thing here to get silently wrong.** Three
attempts at one line, none of which failed a build:

- `"Inspector ${'$'}{this@subprojects.name}"` inside `pom {}` — the escape stopped the Kotlin
  string interpolating at all, so **every POM from 0.3.0 to 0.9.1 published the placeholder text
  verbatim** as its `<name>`.
- The same expression moved into `pluginManager.withPlugin("maven-publish") {}`, where the
  receiver is the *applied plugin*: `<name>Inspector maven-publish</name>`.
- Interpolating correctly but reading the raw project name: `<name>Inspector inspector-core</name>`
  beside an artifact id that already says `inspector-core`.

It is computed once in the `subprojects` body now, where `this` is the Project. The rule worth
keeping: after changing anything in the `pom {}` block, run `publishToMavenLocal` and **read the
generated POM**. None of the three was visible any other way.

**Every publication maps its dependency versions from the configuration its lock covers, named
per publication; `fromResolutionResult()` alone is a no-op on a KMP publication.** Without a
mapping a POM states the declared version, so every Ktor module's POM said kotlin-stdlib 2.3.20
while its lock resolves 2.3.21, raised by Ktor, and a Maven-style consumer got the older one.
`versionMapping { allVariants { fromResolutionResult() } }` changed nothing and the build stayed
green. Gradle 9.5.1 maps versions only from a configuration that is named, carried by a
`ResolutionBackedVariant`, or registered by the `java` plugin; KGP 2.3.20 provides none, so
Gradle silently keeps the declared version. The root build names each target's locked
configuration, and `commonMainResolvableDependenciesMetadata` for the root POM, which is unlocked.
A publication no locked configuration covers fails configuration. `PublishedPomTest` holds all 35
POMs to the lock *text*, because with a hand-edited lock the mapping's own resolution fails
quietly and the POM falls back to the declared version. The Gradle module metadata moves with it
(`requires 2.3.21`), a floor Ktor's own metadata already imposes. Found by the 1.4.0 release
rehearsal.

**Signing is wired to create no `Sign` task at all when no key is configured, rather than to
create one that signs nothing.** Maven Central wants a detached `.asc` beside every artifact;
GitHub Packages, which is where everything through 1.0.0 went, does not. So the build has to keep
working untouched on a machine that has never held a GPG key — `build`, `publishToMavenLocal` and
the release job all run that way today. The rejected alternative is `isRequired = false`, which
leaves the tasks in place and quietly produces an unsigned publication: that failure surfaces at
the far end of a ten-minute tagged job, as a rejection from Central, which is the worst place to
learn it.

The key is read in memory (`useInMemoryPgpKeys`) rather than from a keyring file, because CI has
no keyring. **Presence is not the test — blankness is.** An unset repository secret arrives as an
*empty* environment variable, not an absent one, so an `isPresent` check flips to "sign with the
empty string" the moment a workflow references `${{ secrets.SIGNING_KEY }}` before the secret
exists. The provider is `filter { it.isNotBlank() }` for that reason. Three states are proven:
no key → no signatures and exit 0; a key → a valid `.asc` beside every artifact including
the iOS klibs; an *empty* key → identical to no key.

**The javadoc jar carries one file, and empty was rejected deliberately.** Kotlin has no javadoc
and Central only checks the artifact exists, so an empty jar satisfies it and is what most Kotlin
libraries ship. But an empty jar is also exactly what a Dokka run that failed silently produces,
so the two are indistinguishable to whoever opens it next. It holds a README pointing at the
repository instead. Dokka is the upgrade if real API docs are ever wanted; it is a new toolchain
across seven KMP modules, which is more than "the jar must exist" is asking for.

**Maven Central takes an archive, not a deploy, and `maven { url = … }` cannot reach it.** The
Central Portal replaced OSSRH's protocol; Sonatype's own Gradle page says there is no official
Gradle plugin for it. So the shape that looks obvious — swap the repository URL — does not exist,
and "switch the publishing repository to Central" was a wrong description of this work for several
days before anyone checked. What the Portal wants is a zipped **Maven repository layout** POSTed to
`/api/v1/publisher/upload`, which is why `centralBundle` stages every module into one local
`file://` repository and zips that. GitHub Packages is a second `maven {}` entry and a separate task
on purpose: both run, and a Central failure cannot take the Packages release with it.

**The bundle carries only `.md5` and `.sha1` checksums, and none on a signature.** Central caps each
organization's files per month, and the cap counts signatures and checksums. Gradle's file
repository writes `.md5`, `.sha1`, `.sha256` and `.sha512` beside every file it publishes, `.asc`
signatures included. That made 1.3.0 a 2,000-file release against a 1,000-file monthly limit:
Central sent a grace-period warning after two releases. Sonatype's requirements page says `.md5` and
`.sha1` are required, `.sha256` and `.sha512` are optional, and `.asc` files need no checksums. So
`centralBundle` excludes `*.asc.*`, `*.sha256` and `*.sha512` when it zips, which brings the same
release to 800 files. It filters at the zip, not in the staging repository, because Gradle has no
public switch for either kind of checksum, and the GitHub Packages publication is not affected. Do
not "restore" these files for completeness. The one cost is that each release still uses 80% of a
month's allowance, so two Central releases in one month need Sonatype's raised limit, which open
source projects request through central-support@sonatype.com.

`publishToCentralPortal` defaults to `USER_MANAGED` rather than `AUTOMATIC`, so the upload validates
and stages and then waits for a person to press publish. Releasing to Central is the one
irreversible act in the whole sequence — a coordinate there can never be replaced or deleted — and
that does not belong behind a Gradle task a tag could trigger by accident. The task also polls the
status endpoint rather than trusting the 201: the Portal validates asynchronously, so a 201 means
"received", never "accepted".

**A guard on the `publishAllPublications…` aggregate guards nothing.** `requireSigningKey` first
hung off the aggregate task, and a keyless run failed with the right message and a non-zero exit
**after writing 180 files** — the aggregate waits for both the guard and the individual publications
but imposes no order *between* them, so Gradle ran the publications first. It hangs off every
`PublishToMavenRepository` whose name ends `ToCentralBundleRepository` now. The general rule:
depending on an aggregate orders you against the aggregate, not against what it aggregates.

**Publishing is opt-in per module, and the list lives in the modules.** The root build configures
whichever subproject applied `maven-publish`; it does not name them. An allowlist in the root would
be a second place to keep current, and the failure mode is silent in the wrong direction —
`:inspector-daemon` is a tool and `:sample:desktop` is a demo, and a module that quietly starts
publishing is not something a build failure would tell you about. The `:inspector-noop*` modules
*are* published on purpose: a consumer's release build resolves them by coordinate like any other
dependency.

**The canary is invisible in an Android APK, and the guard refuses rather than passes.** D8 does
not carry the constant's string into the dex pool. Measured on a debug APK of `:sample:android`,
which contains capture code by construction: the canary appears **0** times while
`dev/inspector/Inspector` appears 11 and `okHttpInterceptor` 3. So a canary miss on an Android
artifact means the detector could not see, which is not the same answer as clean — and
`check-release-clean.sh` said `PASSED` on that APK until this was found. It now exits 2 with an
explanation when it finds dex and no canary. Same shape as the iOS case above; the difference is
only the mechanism.

**A directory target must unpack the archives inside it.** `--paths some/build/outputs/apk/debug`
went down the plain-grep branch, and grepping a compressed archive for a plaintext canary finds
nothing however much capture code is in it — so pointing the guard at any directory of jars, aars
or APKs was a guaranteed false pass. Directories now expand to the archives they hold and scan each
one, *and* still grep the directory itself, because klib output is an unpacked tree rather than an
archive. Proved both ways: a raw grep of `inspector-core/build/libs` finds nothing, and the script
now exits 1 on it.

**A published klib is a zip, and a script error is never exit 1.** The build output scanned by
default and by `--self-test` is the *unpacked* klib tree. A published or downloaded `.klib` is a
deflate zip, where the canary sits in `default/ir/strings.knt` and a raw grep counts 0, so
`--paths` over core's own iOS klib said `PASSED` until `*.klib` joined the archive list. A folder
is scanned as a folder whatever its name, because `unzip` on a folder fails quietly and reads as
clean. Exit 1 means "canary found", but under `set -e` a failing command exits with its own
status, usually 1, and a temp folder named after a deep absolute path hit NAME_MAX, so a clean
artifact reported capture code. Unpack folders are numbered now, and an `ERR` trap turns every
failure the script did not decide on into exit 2. Found by the 1.4.0 release rehearsal; CI's
build job pins each case's exit code against freshly packed klibs.

**An archive the guard cannot unpack proves nothing, and exits 2.** `unzip` ran with `|| true`,
so a truncated jar or a text file named `.klib` unpacked to an empty folder, which greps clean:
half of core's own jar said `PASSED`. `unzip` exits 1 for a warning with everything extracted and
2 or more when it could not read the archive (9 for both of those), so 2 and above, on the target
or on a jar nested in it, is now `UNREADABLE`, and the run ends with exit 2 and the file named,
the same answer as a missing target. The status is tested inline: under `set -E` a function that
returns non-zero inside `$(...)` runs the `ERR` trap and prints a false internal error beside the
real one. CI's packed-klib step pins both cases.

Publishing the library locally, to try a build from a consuming app:

```bash
./gradlew publishToMavenLocal -Pinspector.version=0.2.0-local   # publish the library locally
```

Cutting a daemon release — the tag is the trigger, and the only thing that ships this way:

```bash
git tag v0.2.0 && git push origin v0.2.0
```

**The docs half of a release goes through a pull request like anything else.** The coordinate bump
and the `INTEGRATION.md` entry are a normal change to `main`, not part of the tag push, so they get
a branch and a PR. `main` requires a review and both checks; `enforce_admins` is off, so an admin
push *succeeds* and GitHub reports `Bypassed rule violations for refs/heads/main` after the fact.
That a bypass is available is not a reason to take it, and release-docs commits being mechanical is
not either — it is the mechanical-looking commits that get the least scrutiny. Waiting costs
nothing: the tag is a separate step that comes after the docs land, and tags are not protected.

`.github/workflows/release.yml` does three independent things on that tag. The **daemon** job
builds the zip, unzips it, starts it and checks the web UI and the MCP server both answer, and only
then publishes the release. The **library** job publishes all seven modules to GitHub Packages. The
**central** job stages the same artifacts on Maven Central. Both of the latter run on macOS because
the iOS klibs cannot be produced anywhere else — a publication missing its iOS variants resolves
fine on JVM and Android and then fails on device, furthest from the cause.

Three jobs rather than one with three steps, because the registries fail independently: a Central
rejection must not take the Packages release with it, and neither must stop the daemon zip.

**The central job stages and never publishes.** It pins `USER_MANAGED`, so the Portal validates
the bundle and parks it for a person to press Publish. Both halves of that are load-bearing. A
coordinate on Central can never be replaced or deleted, so the irreversible act must not be
reachable by pushing a tag — and equally, forgetting to upload at all should not be possible,
which is what the job fixes: Central was a manual dispatch for one release and the docs already
pointed consumers there by default. A re-pushed tag stages coordinates that exist and the Portal
rejects it per component; that red job does not mean the release failed.

The repository is public, so release assets are an anonymous download. From 1.0.1 the library is
too, through Maven Central. GitHub Packages still carries it and still demands an authenticated
token even for public packages — which is why it is no longer the path `docs/ACCESS.md` leads
with.
