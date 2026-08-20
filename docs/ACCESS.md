# Getting Inspector — access, and what to do without it

Inspector lives in a **private** GitHub repository, `Shafichariri/kinspector`. This page answers one
question: what do *you* need in order to use it, and what happens if you have nothing.

Integrating it into an app is [`INTEGRATION.md`](INTEGRATION.md); running the daemon is
[`DAEMON.md`](DAEMON.md). Come here only for the "how do I get it" part.

---

## Two halves, two ways of travelling

| Half | What it is | How it reaches you |
|---|---|---|
| **Library** (`:inspector-core`, `:inspector-ui`, `:inspector-stream`) | Compiles into your app. Captures the traffic and draws the overlay. | A normal Gradle dependency, from GitHub Packages. |
| **Daemon** (`:inspector-daemon`) | A program on your own machine. Web UI, session archive, CLI, MCP server. | A zip from the [Releases page](https://github.com/Shafichariri/kinspector/releases). |

Neither one requires a checkout of this repository. You need a checkout only if you are changing
Inspector itself.

Both are gated on the same thing: **GitHub Packages and release assets on a private repository are
private too.** There is no anonymous path to either. So the question below is not "which download
link" — it is whether you have been given access to the repository at all.

---

## If you have repo access

### 1. Add the repository and your credentials

GitHub Packages requires a token to **download**, not only to publish — this is true even for
public packages, so there is no configuration that avoids it. In your app's
`settings.gradle.kts`, inside `dependencyResolutionManagement { repositories { … } }`:

```kotlin
maven {
    url = uri("https://maven.pkg.github.com/Shafichariri/kinspector")
    credentials {
        username = providers.gradleProperty("gpr.user").orNull
        password = providers.gradleProperty("gpr.key").orNull
    }
}
```

Then, once per machine, in `~/.gradle/gradle.properties` — **not** in the repository:

```properties
gpr.user=your-github-username
gpr.key=ghp_yourClassicTokenWithReadPackages
```

The token needs the `read:packages` scope, and nothing else. Create it at
**Settings → Developer settings → Personal access tokens**.

This is the only per-developer setup, it is one file outside the repo, and the file you commit is
identical for everyone.

### 2. Depend on it

```kotlin
implementation("dev.inspector:inspector-core:0.2.0")
implementation("dev.inspector:inspector-ui:0.2.0")
```

Then follow [`INTEGRATION.md`](INTEGRATION.md) from §1. Do §3 — the debug-only swap — before you
write any app code, not after.

### 3. Get the daemon, if you want the web UI

```bash
gh release download --repo Shafichariri/kinspector --pattern '*.zip'
unzip inspector-*.zip
```

A JDK 21 is the only requirement. You do not need the daemon at all for the in-app overlay; it is
only for the web UI, the on-disk archive, the CLI and the MCP server.

### Only if you are changing Inspector

Clone it and wire it in as a composite build, which compiles the source in place so your edits
appear immediately:

```bash
git clone https://github.com/Shafichariri/kinspector.git
```

`INTEGRATION.md` §2 covers the two lines that switch a consuming app from the published artifacts
to a local checkout, and how to keep the path out of the committed build file. Consuming apps
should not do this by default — it makes every developer responsible for a second repository.

---

## If you do not have repo access

**You are blocked, and it is a permissions problem rather than a technical one.**

Being precise about the kinds of "no", because they differ:

- **The library — blocked.** The packages exist, but they inherit the repository's visibility.
  Without access your token cannot read them and Gradle fails to resolve, exactly as it would for
  any private dependency.
- **The daemon — runs, but has nothing to show.** The zip is self-contained, and someone could
  simply hand you the file. It would start, serve the web UI, and display an empty archive forever,
  because every row comes from the library running inside an app. You also cannot fetch it
  yourself: release assets on a private repo need a login with access.
- **The licence — unresolved.** This repository has not chosen one (see the README). Absent a
  licence, nobody outside has a grant to use, copy or redistribute it, whatever files they end up
  holding. If you are outside the organisation, settle this before the mechanics.

### What would unblock you

1. **Be added to the repository** — a collaborator invite, or the repo moving into the
   organisation so access follows team membership. Nothing else changes: you take the section
   above unmodified, because package access follows repository access.
2. **Have the packages republished somewhere you can read** — a company Nexus or Artifactory, for
   instance. Worth it if a whole team needs Inspector and managing individual GitHub tokens
   becomes the annoying part; not worth it for one person.
3. **Be handed a source copy directly.** Technically sufficient and licence permitting, but you
   inherit a fork that stops receiving fixes. Prefer either option above.

---

## What each person actually needs

| You want | Repo access | JDK 21 | Anything else |
|---|---|---|---|
| The in-app overlay only | yes | yes | a `read:packages` token, and Kotlin/CMP versions matching [`INTEGRATION.md`](INTEGRATION.md) §1 |
| Overlay + web UI + archive | yes | yes | the same, plus the daemon running on your machine |
| To read sessions from an AI agent | yes | yes | the same, plus the MCP registration in [`INTEGRATION.md`](INTEGRATION.md) §10 |
| To change Inspector itself | yes | yes | a clone, and the composite-build wiring in §2 |
| To run the daemon against someone else's archive | no | yes | the release zip *handed to you*, and a copy of their `~/.inspector/sessions/` |

That last row is the only useful thing available without repo access, and it is a forensic case —
reading an archive somebody else recorded, not recording your own.

> Session archives hold **unredacted** credentials by default: bearer tokens, login bodies, the
> lot. That is deliberate, because a debugger that hides the auth header is useless when the bug
> *is* the auth header. It also means an archive is not a file to pass around casually, and the
> folder names carry the app's identity.
