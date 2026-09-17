# Getting Inspector

Inspector is a **public** repository, `Shafichariri/kinspector`, licensed
[Apache-2.0](../LICENSE). Nobody needs to be invited to anything.

There is still one setup step, and it surprises people, so it is the first thing on this page.

Integrating into an app is [`INTEGRATION.md`](INTEGRATION.md); running the daemon is
[`DAEMON.md`](DAEMON.md). Come here only for "how do I get it".

---

## The one catch: GitHub Packages always wants a token

The library is published to GitHub Packages, and **that registry requires an authenticated token
for downloads even when the package is public**. GitHub's own documentation puts it plainly: you
need an access token "to publish, install, and delete private, internal, and public packages" —
where *install* means what Gradle does when it resolves a dependency.

So there is no anonymous `implementation("dev.inspector:…")`. This is a property of GitHub
Packages, not a decision made here, and no visibility setting turns it off. Maven Central and
JitPack do not work this way, which is exactly why the expectation trips people up.

The token is free, takes a minute, and is the only thing standing between you and the library.

---

## Two halves, two ways of travelling

| Half | What it is | How it reaches you |
|---|---|---|
| **Library** (`:inspector-core`, `:inspector-ui`, `:inspector-stream`) | Compiles into your app. Captures the traffic and draws the overlay. | A Gradle dependency from GitHub Packages. Needs a token. |
| **Daemon** (`:inspector-daemon`) | A program on your machine. Web UI, session archive, CLI, MCP server. | A zip from the [Releases page](https://github.com/Shafichariri/kinspector/releases). No token, no account. |

Neither needs a checkout. Clone only if you are changing Inspector itself.

**Both halves carry the same version**, because one tag publishes both — there is no separate
daemon version and library version to keep straight. The current one is on the
[Releases page](https://github.com/Shafichariri/kinspector/releases/latest), and the README shows
it as a badge. Whether a given release actually *changed* your half is a different question, and
[`INTEGRATION.md` §14](INTEGRATION.md) answers it per version.

---

## 1. Create a token

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

## 2. Add the repository and depend on it

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

```kotlin
implementation("dev.inspector:inspector-core:0.8.0")
implementation("dev.inspector:inspector-ui:0.8.0")
```

Then follow [`INTEGRATION.md`](INTEGRATION.md) from §1, and do §3 — the debug-only swap — before
writing app code rather than after.

## 3. Get the daemon, if you want the web UI

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

| You want | GitHub account | Classic token | Anything else |
|---|---|---|---|
| The in-app overlay | yes | `read:packages` | Kotlin/CMP versions matching [`INTEGRATION.md`](INTEGRATION.md) §1 |
| Overlay + web UI + archive | yes | `read:packages` | the daemon, running on your machine |
| To read sessions from an AI agent | yes | `read:packages` | the daemon, plus the MCP registration in [`INTEGRATION.md`](INTEGRATION.md) §10 |
| To run the daemon only | no | no | the release zip and a JDK 21 |
| To change Inspector itself | yes | no | a clone; `main` takes pull requests, not pushes |

The fourth row is the only one that needs nothing: the daemon runs for anybody. It will show an
empty archive until some app with the library in it starts sending rows.

> Session archives hold **unredacted** credentials by default: bearer tokens, login bodies, the
> lot. That is deliberate, because a debugger that hides the auth header is useless when the bug
> *is* the auth header. It also means an archive is not a file to pass around casually, and the
> folder names carry the app's identity.
