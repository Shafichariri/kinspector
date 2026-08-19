# Getting Inspector — access, and what to do without it

Inspector lives in a **private** GitHub repository, `Shafichariri/kinspector`. This page answers one
question: what do *you* need in order to use it, and what happens if you have nothing.

Integrating it into an app is [`INTEGRATION.md`](INTEGRATION.md); running the daemon is
[`DAEMON.md`](DAEMON.md). Come here only for the "how do I get it" part.

---

## Two halves, two ways of travelling

They are distributed differently, and the difference is not arbitrary — it is what each one has to
do.

| Half | What it is | How it reaches you |
|---|---|---|
| **Library** (`:inspector-core`, `:inspector-ui`, `:inspector-stream`) | Compiles into your app. Captures the traffic and draws the overlay. | **A source checkout. Nothing else works.** |
| **Daemon** (`:inspector-daemon`) | A program on your own machine. Web UI, session archive, CLI, MCP server. | A zip from the [Releases page](https://github.com/Shafichariri/kinspector/releases), or built from the same checkout. |

The library is not downloadable as a file because Gradle does not consume it as a file. It is wired
in as a **composite build** — your build compiles Inspector's source with *your* Kotlin version —
and `includeBuild` needs a directory on disk. There is no Maven publication, so there is no artifact
anyone could send you that Gradle would know how to resolve.

The daemon has no such constraint. It is an ordinary JVM program that a person downloads and runs,
which is why it can travel as a zip.

---

## If you have repo access

### 1. Clone it, once, anywhere

```bash
git clone https://github.com/Shafichariri/kinspector.git ~/development/tools/inspector
```

The location is yours to choose; nothing depends on it except the path you point your app at.

### 2. Point your app's build at that directory

In your app's `settings.gradle.kts`:

```kotlin
includeBuild("/absolute/path/to/inspector")
```

Better, if more than one person on your team will do this: read the path from a per-developer Gradle
property instead of hardcoding one person's home directory, and gate the whole thing behind a flag
so release builds never see it. [`INTEGRATION.md`](INTEGRATION.md) §3 covers that properly — do it
before writing app code, not after.

Then follow [`INTEGRATION.md`](INTEGRATION.md) from §1. Check the versions in §1 first: Inspector
compiles inside your build, so a Kotlin mismatch fails in ways that do not point at the real cause.

### 3. Get the daemon, if you want the web UI

You already have the source, so either works:

```bash
./gradlew :inspector-daemon:installDist   # from your checkout
```

```bash
gh release download --repo Shafichariri/kinspector --pattern '*.zip' && unzip inspector-*.zip
```

The release zip is the better choice if you are not changing Inspector itself — it needs only a
JDK 21, and it will not be invalidated every time you rebuild. The build-from-source path is the
right one if you are editing the daemon or the web UI, because the launcher runs the *installed*
copy, not your source tree.

You do not need the daemon at all for the in-app overlay. It is only for the web UI, the on-disk
archive, the CLI and the MCP server.

---

## If you do not have repo access

**You are blocked on the library, and that is the half that matters.**

Being precise about what is and is not blocked, because they are different kinds of "no":

- **The library — blocked.** No checkout, no `includeBuild`, no capture. There is no published
  artifact to fall back on. This is a permission problem, not a technical one: the code compiles
  fine from any copy of the source, but you have not been given one.
- **The daemon — runs, but has nothing to show.** The zip is self-contained and someone could
  simply hand you the file. It would start, serve the web UI, and display an empty archive forever,
  because the rows come from the library inside a running app. Release assets on a private repo
  also need a GitHub login with access, so you cannot fetch it yourself either.
- **The licence — unresolved.** This repository has not chosen one (see the README). Absent a
  licence, nobody outside has a grant to use, copy, or redistribute it, whatever files they end up
  holding. If you are outside the organisation, settle this first; it outranks the mechanics.

### What would unblock you

In rough order of how little work each one is:

1. **Be added to the repository** — a collaborator invite, or the repo moving into the organisation
   so access follows team membership. Nothing in this document changes; you simply take the section
   above.
2. **Ask for the library to be published to a Maven repository.** This is real work in Inspector,
   not a setting: the seven library modules need `maven-publish`, and the publishing job has to run
   on macOS because the iOS artifacts cannot be built anywhere else. It also gives up a safety
   property — a composite build compiles against *your* Kotlin, whereas published artifacts are
   pinned to whichever Kotlin built them, so consumers on a different version get klib errors.
   Worth doing when several teams need it; not worth doing for one person.
3. **Be handed a source copy directly.** Technically sufficient and licence-permitting, but you
   inherit a fork that no longer receives fixes. Prefer either option above.

---

## What each person actually needs

| You want | Repo access | JDK 21 | Anything else |
|---|---|---|---|
| The in-app overlay only | yes | yes | Kotlin/CMP versions matching [`INTEGRATION.md`](INTEGRATION.md) §1 |
| Overlay + web UI + archive | yes | yes | the daemon, running on your own machine |
| To read sessions from an AI agent | yes | yes | the daemon, plus the MCP registration in [`INTEGRATION.md`](INTEGRATION.md) §10 |
| To run the daemon against someone else's archive | no | yes | the release zip *handed to you*, and a copy of their `~/.inspector/sessions/` |

That last row is the only useful thing available without repo access, and it is a forensic case —
reading an archive somebody else recorded, not recording your own.

> Session archives hold **unredacted** credentials by default: bearer tokens, login bodies, the
> lot. That is deliberate, because a debugger that hides the auth header is useless when the bug
> *is* the auth header. It also means an archive is not a file to pass around casually, and the
> folder names carry the app's identity.
