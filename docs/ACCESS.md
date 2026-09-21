# Getting Inspector

Inspector is a **public** repository, `Shafichariri/kinspector`, licensed
[Apache-2.0](../LICENSE). Nobody needs to be invited to anything.

From **1.0.1** there is no setup step at all: the library is on Maven Central and resolves
anonymously. Earlier versions need a token, and that is further down.

Integrating into an app is [`INTEGRATION.md`](INTEGRATION.md); running the daemon is
[`DAEMON.md`](DAEMON.md). Come here only for "how do I get it".

---

## From 1.0.1: nothing to set up

```kotlin
repositories { mavenCentral() }
```

```kotlin
implementation("io.github.shafichariri:inspector-core:1.0.2")
implementation("io.github.shafichariri:inspector-ui:1.0.2")
```

No token, no account, no repository block. Maven Central serves anonymously, and `mavenCentral()`
is already in most builds.

**If you were already on GitHub Packages, moving to Central does not change your version.** Delete the
`maven { url = "https://maven.pkg.github.com/…" }` block and the credentials with it: the same
coordinate now resolves from Central. Nothing else about the dependency moves.

**1.0.0 and earlier are not on Central and never will be.** They were published before the
signing and metadata Central requires, and a coordinate cannot be backfilled there. Those versions
stay on GitHub Packages, which means a token — see *The older path* below.

---

## Two halves, two ways of travelling

| Half | What it is | How it reaches you |
|---|---|---|
| **Library** (`:inspector-core`, `:inspector-ui`, `:inspector-stream`) | Compiles into your app. Captures the traffic and draws the overlay. | A Gradle dependency from Maven Central, anonymously, from 1.0.1. Also still on GitHub Packages, which needs a token. |
| **Daemon** (`:inspector-daemon`) | A program on your machine. Web UI, session archive, CLI, MCP server. | A zip from the [Releases page](https://github.com/Shafichariri/kinspector/releases). No token, no account. |

Neither needs a checkout. Clone only if you are changing Inspector itself.

**Both halves carry the same version**, because one tag publishes both — there is no separate
daemon version and library version to keep straight. The current one is on the
[Releases page](https://github.com/Shafichariri/kinspector/releases/latest), and the README shows
it as a badge. Whether a given release actually *changed* your half is a different question, and
[`INTEGRATION.md` §14](INTEGRATION.md) answers it per version.

---

Once you can resolve the dependency, follow [`INTEGRATION.md`](INTEGRATION.md) from §1, and do
§3 — the debug-only swap — before writing app code rather than after.

---

## The older path: GitHub Packages and a token

**Skip this entirely on 1.0.1 or later.** It is here for 1.0.0 and earlier, and for anyone who
would rather keep resolving from GitHub Packages — both halves are still published there, and
that is not changing.

GitHub Packages **requires an authenticated token for downloads even when the package is
public**. GitHub's own documentation puts it plainly: you need an access token "to publish,
install, and delete private, internal, and public packages" — where *install* means what Gradle
does when it resolves a dependency. No visibility setting turns it off. It is a property of that
registry, not a decision made here, and it is the single thing every new consumer used to trip
over.

**It has to be a *classic* token.** GitHub Packages does not accept fine-grained tokens; one will
return 401 however you scope it, which looks like a permissions mistake and is not one.

At **Settings → Developer settings → Personal access tokens (classic)**, create one with the
`read:packages` scope and nothing else — not `repo`, not `write:packages`. Give it an expiry.

Then, once per machine, in `~/.gradle/gradle.properties`, which lives outside any repository:

```properties
gpr.user=your-github-username
gpr.key=ghp_yourClassicToken
```

Never commit it. The file you check in stays identical for everyone; only this one does not.

In your app's `settings.gradle.kts`, inside
`dependencyResolutionManagement { repositories { … } }`:

```kotlin
maven {
    url = uri("https://maven.pkg.github.com/Shafichariri/kinspector")
    credentials {
        username = providers.gradleProperty("gpr.user").orNull
        password = providers.gradleProperty("gpr.key").orNull
    }
}
```

**Your release builds need the token too, and that surprises people.** The `-Pinspector=off` swap
in [`INTEGRATION.md`](INTEGRATION.md) §3 resolves `inspector-noop`, `inspector-noop-ui` and
`inspector-noop-stream` from the same registry, so a CI runner that has the token for debug jobs
and not for release ones fails on the *no-op* modules — the ones whose whole purpose is that
release builds carry nothing. The error names a no-op artifact and says 401, which points at the
wrong thing entirely. Resolving from Central removes this, because nothing there needs a
credential.

## Get the daemon, if you want the web UI

```bash
gh release download --repo Shafichariri/kinspector --pattern '*.zip'
unzip inspector-*.zip
```

A JDK 21 is the only requirement, and the Releases page works in a browser with no account at all.
You do not need the daemon for the in-app overlay; it is only for the web UI, the on-disk archive,
the CLI and the MCP server.

## Only if you are changing Inspector

```bash
git clone https://github.com/Shafichariri/kinspector.git
```

`INTEGRATION.md` §2 covers wiring a consuming app to a local checkout as a composite build, so your
edits appear without publishing, and how to keep the path out of the committed build file.
Consuming apps should not do this by default — it makes every developer responsible for a second
repository.

`main` is protected: changes arrive by pull request, with review and green CI.

---

## What each person actually needs

On 1.0.1 or later, from Maven Central:

| You want | GitHub account | Token | Anything else |
|---|---|---|---|
| The in-app overlay | no | no | Kotlin/CMP versions matching [`INTEGRATION.md`](INTEGRATION.md) §1 |
| Overlay + web UI + archive | no | no | the daemon, running on your machine |
| To read sessions from an AI agent | no | no | the daemon, plus the MCP registration in [`INTEGRATION.md`](INTEGRATION.md) §10 |
| To run the daemon only | no | no | the release zip and a JDK 21 |
| To change Inspector itself | yes | no | a clone; `main` takes pull requests, not pushes |

Nothing in that table needs an account except changing Inspector itself, which needs one to open
a pull request. That is new in 1.0.1; every row above said `read:packages` before it.

On 1.0.0 or earlier, or resolving from GitHub Packages by choice, every library row needs a
GitHub account and a **classic** token with `read:packages` — including release builds, which
resolve the no-op modules from the same registry.

The daemon has never needed either, and still does not. It will show an empty archive until some
app with the library in it starts sending rows.

> Session archives hold **unredacted** credentials by default: bearer tokens, login bodies, the
> lot. That is deliberate, because a debugger that hides the auth header is useless when the bug
> *is* the auth header. It also means an archive is not a file to pass around casually, and the
> folder names carry the app's identity.
