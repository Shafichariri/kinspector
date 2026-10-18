# Status — what has been checked, and what has not
Moved here from the root `AGENTS.md` so the root stays an index; the delivery tracker keeps it current.

**Last full count: 886 tests, 0 failures** across JVM, iOS simulator, Android host and the daemon.
Phases A and E (below) added tests since, and **no full run has been counted since**: `:inspector-model:jvmTest`
ran 159 tests and `:inspector-daemon:test` ran 224, 0 failures each (2026-10-18), and nothing else
was run. A branch-wide total of 897 was computed by arithmetic, never measured, and is not claimed.

**Phase A of the structure pass (web checks and mirror cases in CI) is done**: its first CI run, on
PR #81, went green on the new `web` job (ubuntu) and on `build` and `release-swap` (macOS: full
`./gradlew build`, Android host tests, `-Pinspector=off`). `docs/ROADMAP.md` §5 has the detail.

**Phase E (MCP `timeline` and `explain` order through the model's `timeline()`) is done**: CI on
PR #82 went green on `build`, `release-swap` and `web`. `docs/ROADMAP.md` §5 has the detail.

### First real-app findings (2026-08-16, a consuming app on an Android emulator)

The first session recorded from a real app surfaced four defects. All are fixed; they are listed
because each was invisible to the suite at the time, and the shape of each gap matters more than
the fix.

1. **Live viewers saw every body as missing.** `SessionWriter.append` returned Unit and
   `SessionManager` broadcast the row *as received*, where `*BodyRef` is null by wire contract.
   The bodies were on disk the whole time. Nothing tested the live payload — `LiveTest` now does.
2. **The UI invented a reason.** Any null body rendered as "content type outside the capture
   allowlist", which was flatly wrong for the case above. Capture now records
   `reqBodyOmitted`/`resBodyOmitted` and both UIs render what was recorded, never a guess.
3. **`reqContentType` was null on every request.** It was read from the builder's headers, but
   Ktor keeps it on the `OutgoingContent`. This also silently disabled request-body redaction,
   which bails on anything not declared JSON — so `Redaction.On` was not redacting request bodies
   at all.
4. **Two dead daemons.** A failed bind left the JVM alive still printing "listening on …". The
   port is now probed before anything is printed.

The lesson worth keeping: **the live path and the REST path are two renderings of one row, and
only the REST one was tested.** Any new field on `NetworkTransaction` needs a check on both.

### Verified

- Redirect and retry each produce one row per attempt, sharing a `callId`.
- App receives byte-identical bodies with the inspector installed: 10 MB, gzip, chunked, empty,
  binary.
- `/secret` shows credentials verbatim by default, redacted under `Redaction.On()`.
- Ring buffer evicts by byte budget; a single oversized entry is still kept.
- `-Pinspector=off` yields a runtime classpath containing only noop modules.
- Canary guard fails when capture code is present and passes when it is not — **both directions
  proven**.
- `./gradlew :sample:desktop:run` launches and serves traffic.
- End-to-end: sample streams to the daemon, 40 transactions archived to
  `~/.inspector/sessions/<ts>_<app>_<device>_<build>/`, `latest` symlink correct, bodies written
  to `bodies/`, `index.jsonl` greppable from a terminal.
- REST filters evaluate server-side with the shared grammar; a bad filter returns 400 carrying
  the parser's own message.
- Killing the daemon mid-session leaves the app unaffected; reconnecting resumes the same
  session folder; a relaunch gets a new one.
- Retention prunes by count and by bytes, never the active session.
- **The web UI renders.** `scripts/render-web-ui.js` runs the real `app.js` against the real
  `index.html` with fetch proxied to a live daemon and reports what actually rendered: rows,
  marker dividers, session picker, detail pane, attempt chain, method classes, console errors. It
  drives the order control and asserts the rendered order reverses, restores, that exactly one half
  reads as selected, and that the row/divider sequence is an exact mirror. It clicks an endpoint
  chip and asserts three things: the row count narrows, the chip list *survives* (proving chips
  come from the unfiltered session), and exactly one chip goes active. It then drives the settings
  input to prove the cap applies and `0` hides them. It walks the tab bar, and for each tag browser
  asserts keys, facets, a pretty-printed value, history switching, and that the key filter and
  facet chips each change the list and toggle back off. It jumps the *window's* clock to prove ages
  tick — `window.Date` is not Node's `Date`, and overriding the wrong one silently proves nothing.
  It audits the stylesheet text for panes that set `display` without a `[hidden]` override, because
  jsdom resolves a hidden element to `display: none` whatever the CSS says, so the computed-style
  version of that check passes with the guard deleted. `INSPECTOR_UI_SESSION=<id or substring>`
  targets a session other than the newest, which is otherwise whatever ran last on the machine.
- **Daemon stop and restart, against a live daemon** — not only the unit tests. An unheadered POST
  is refused 403 and the daemon survives; restart swapped one pid for another in about a second
  with the whole archive still served; stop released the port and left no `serve` process.
- **A live viewer receives body refs**, so bodies are fetchable without a page reload
  (`LiveTest`). This is the defect the first real user hit.
- **OkHttp capture, end to end.** The sample makes one call through a plain `OkHttpClient` with no
  Ktor anywhere in its path; it reaches the archive as
  `GET /json?via=okhttp → 200` with its body on disk and header case preserved. The app receives
  byte-identical bodies with and without the interceptor across json, gzip, binary, 4 MB chunked
  and empty.
- **The MCP server answers the acceptance question in three calls**, verified both as a unit test
  and by piping JSON-RPC frames into the built binary against the real recorded session.
- **`adb reverse` reaches the daemon from inside a device.** Against a live daemon on a booted
  emulator, which speaks the same adb protocol as hardware: `127.0.0.1:8099` from inside the
  device was refused before `adb reverse tcp:8099 tcp:8099`, answered `HTTP/1.1 200 OK` with the
  daemon's own body after it, and was refused again once the forward was removed. `10.0.2.2`
  answered throughout, and `lsof` showed the daemon still bound to `127.0.0.1` alone — so the
  loopback boundary is genuinely untouched rather than assumed to be.
- **A physical iPhone streams to the daemon over USB** (iPhone 12 Pro, iOS 26.5, 2026-09-24). The
  real iOS sample, built for `iosArm64` and signed for the device, listened on the phone's
  `127.0.0.1:8099`; `inspector serve`'s bridge dialled it through usbmuxd and 188 transactions
  reached the archive as `platform: ios-device`, bodies on disk, retries and redirects intact, while
  the daemon stayed bound to `127.0.0.1` alone. The other direction too: a replay from the host came
  back with `resignedHeaders` that only the app's `ReplaySigner` on the phone produces, so a
  `SignRequest` crossed the cable and its answer returned. Killing a linked app and relaunching it
  reconnects in about a second — after the TIME_WAIT fix below; before it, 25 seconds of
  `EADDRINUSE`.

- **Phase A's web checks, run locally (2026-10-18, three independent QA passes).**
  `scripts/check-web-static.js` passes 20/20. The live probe against a daemon on 8231 holding the
  generated probe session: 164 passed, 0 unclassified, exit 0. The archive probe: 21 passed. Each guard
  category was re-broken by QA and went red: a second `fetch`, `[hidden]`, 11px, the JS mirrors, the
  Kotlin mirrors, the `inputs.dir` declaration, a missing anchor, a reverted drift, the archive
  data-versus-code check, the item-2 checks, `esc restores glance` (all three breaks) and the hidden
  scrim. `writeProbeFixture` is idempotent, and a JSON-only change re-runs the tests. The four `app.js`
  drifts and the empty-query `?` case are fixed, with `app.js` the only product file changed.

- **Phase E's MCP ordering, run locally (2026-10-18, two independent QA passes).** Daemon tests 224 and
  `:inspector-model:jvmTest` 159, 0 failures. Five breaks went red: the old sort, the daemon's
  `thenBy seq`, the old `mono` split in `explain`, a clamped `explain`, and `splitAround` returning
  empty instead of null. The built `inspector mcp` binary, driven over JSON-RPC against a hand-built
  tie archive, ordered entries as the model does. The 2,000 clamp is kept. `DAEMON.md` §8 "1.3.0 and
  earlier" was checked against the `v1.3.0` tag.

### Not verified

- **The Gradle cache carrying over between checkout paths** for the mirror-case test inputs is
  unproven.
- **Signal ties still ignore `seq`**, in the model and so in MCP (recorded under ROADMAP §3).
- **The Auth0 adapter in `docs/INTEGRATION.md` §11 compiles but has never run.** It is now
  written against the real `auth0-android` 3.12.0 sources (read out of the Gradle cache) and
  compile-checked against that jar plus OkHttp 4.12.0 and Gson, so the types and the
  body-construction logic are right. What is unproven is behaviour against a live tenant.

- **The overlay has now been seen on a phone once**, and every one of the four things that came
  back was a real defect: it drew under the status bar and camera cutout, there was no way back to
  the app, and nothing but cURL could be copied. All four are fixed; **the fixes themselves have
  only been compiled and exercised on desktop**, where insets are zero and there is no back
  gesture, so the parts that matter most are still unverified on a device.
  The list layout that followed was checked the same way with one addition — `ListRenderTest`
  renders it off-screen at 360dp, which catches what a desktop window cannot, because a desktop
  window is never 360dp wide. It is still not a device.
- **There are Android and iOS sample apps now, and the overlay has been seen running on both** —
  an emulator and an iOS simulator. The list, the clock column, the control strip, marker dividers
  and chips, signals and a collapsed run, on a real screen with a real status bar and a Dynamic
  Island. What that does *not*
  cover is hardware: a physical cutout, a vendor skin, or `adb reverse` with a cable in it. The one
  physical phone so far is an iPhone, and it was driven for the USB route above — the overlay was
  running on it the whole time, but nobody *looked* at it there, so the Dynamic Island and inset
  fixes are still unverified on hardware. No Android build has run on a physical phone.
- **The iPhone route's edges.** Verified: kill and relaunch. Not verified: pulling the cable
  mid-session (expected to look identical — the link sees EOF either way — but not tried); an app
  put in the background after an ordinary home-screen launch (every run was launched by
  `devicectl`, which may keep it from being suspended, and in the spike a backgrounded app still
  answered after 30 s for possibly that reason); two apps on one phone; Wi-Fi-paired devices,
  which the bridge skips by design.
- **Nobody has judged how the web UI *looks*.** It provably renders the right elements (see
  above), but no human has assessed spacing, colour or density. The browser pane is blocked from
  localhost by policy in this environment, so only a static snapshot has ever been produced.
- Redirect behaviour against engines other than CIO. Some engines follow redirects internally,
  which would collapse a chain into a single row. Unresolved for the target app's engine.

### Also outstanding

- **Someone should look at the web UI** and say what is ugly. It renders correctly; nobody has
  judged it.
- **Android + iOS sample shells**, to finally see the overlay on a device. Needs an Android app
  module and an Xcode project; the UI module already compiles for both.
- ~~**Stretch:** HAR export.~~ Built — `GET /api/sessions/{id}/har`, see the rule below.
