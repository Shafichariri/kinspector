# Unreleased — lines owed to the next release's notes

Each line below is owed to the notes of the next release, and is emptied into that release when it
is cut. Moved here from `ROADMAP.md` → §5 → Phase A on 2026-10-19.

Consolidated on 2026-10-22 by `item:release-1-4-docs` (first pass): the twenty-three lines written
one item at a time are now one line per user-visible change, grouped as the notes will be. The
italic text is the notes line. Under it: the items it carries, whether QA has verified them
(`docs/STATUS.md` → Verified), and the caveats that keep the line true. **A line marked "QA owed"
does not go into the notes until STATUS supports it**; at the cut it is reworded to what QA saw, or
dropped. Whatever `docs/INTEGRATION.md` v59 to v64 call "the next release after 1.3.0" is 1.4.0;
the release-steward re-checks that, and every "1.3.0 and earlier" here and in `DAEMON.md` §8,
against `git tag` at the cut.

## Action needed for some apps

These change the library, or what a script or agent reads from the daemon. The release's own
`INTEGRATION.md` entry lists each with the entry that says what to do.

- *"Overlay (`inspector-ui`): `InspectorTheme`, `InspectorColors` and `LocalInspectorColors` are no
  longer public. An app that named them replaces those uses with its own code; `INTEGRATION.md` v59
  says how."*
  `item:structure-phase-i` (KIN-62, PR #106). QA verified. This is why the release is a minor, not a
  patch (decided 2026-10-18); v59 is the notice a major would have given.
- *"A call to an IPv6 address now has a URL that is one. `NetworkTransaction.url` and `hostPort`
  bracket an IPv6 host (`http://[::1]:8080/v6`, where an OkHttp capture read `http://::1:8080/v6`),
  so the overlay's Copy URL and cURL, the web UI's URL, cURL and replay, the Stats page, HAR and the
  exports' labels and routes table are valid for it; archives recorded earlier read the same way.
  Agents and CLI: the session summary's `byHost` (`GET …/summary`, MCP `session_summary`,
  `inspector summary`, the exports) now names a server by host and port, so `127.0.0.1:8080` and
  `127.0.0.1:9090` are two entries, where they were one `127.0.0.1`; a default port adds nothing to
  the key."*
  `item:host-port-everywhere` (KIN-92, PR #129). QA verified. A library change, because `url` is
  public in `:inspector-model`; `INTEGRATION.md` v63. No signature changed and no golden file moved.
  The `host:` filter term is unchanged and still matches the stored host (`::1` from OkHttp, `[::1]`
  from Ktor), so the line does not say filters see brackets.
- The route row's `host`, under "Daemon, MCP and exports" below, belongs in this list too, for
  scripts and agents; `INTEGRATION.md` v64 says what to do.

## New

- *"Findings. A Findings page on the web UI's rail, `GET /api/sessions/{id}/findings`, the MCP tool
  `list_findings` and `inspector findings` flag payment-safety problems in POST, PUT, PATCH and
  DELETE calls: duplicate mutations, an idempotency key reused with a changed body, automatic
  retries without a key, resubmits after an ambiguous failure, payment paths without a key, and
  retries with one (info). Rules and their settings live under `findings` in `config.json` and on the
  Settings page, and apply on the next read, with no restart; findings are computed on read and
  never stored; evidence never shows a captured value; every export carries the findings, the HAR in
  its entry comments."*
  `item:findings-payment-safety`: PR A (KIN-111 to KIN-115, PRs #151 to #153) and PR B (KIN-116 the
  page, KIN-117 the Settings fieldset, PR #160). QA verified KIN-111 to KIN-114 (STATUS → Verified,
  "Findings (payment safety), daemon side"); **QA owed** for KIN-115 (exports), KIN-116 and KIN-117.
  `INTEGRATION.md` v64. PR C (T7, the row badge and the detail section; T8, flagged rows only) is not
  merged; its words join this line in the final pass, from the design's own line.
- *"Sensitive-data findings — card numbers, IBANs, JWTs and secrets in URLs, credentials sent over
  plain `http`, and field names such as `password`, `cvv`, `date_of_birth` or `passport_number` — on
  the Findings page, in `list_findings` and in every export, always masked (`•••• 4242`), never the
  value. Severity rises for values in URLs and, once `findings.sensitive.firstPartyHosts` is set in
  `config.json`, for requests to other hosts. Email and phone rules ship off. A finding can be
  dismissed with a reason by listing it under `findingDismissals` in `config.json`, which applies on
  the next read. Teams add their own value rules as shapes with an optional checksum. Nothing changes
  on the device and nothing is redacted."*
  `item:sensitive-data-detection` T1 to T4 (KIN-120 to KIN-123, and KIN-127 carved out for it; PRs
  #156, #158, #161). **QA owed** for all of them. `INTEGRATION.md` v64. Written for `main` as it is:
  the page draws both families and the Settings page turns every rule on or off, but the page cannot
  dismiss yet and Settings does not edit `firstPartyHosts`. T5 (KIN-124: dismiss from the page, the
  dismissed fold, the no-hosts hint, first-party hosts in Settings) widens it in the final pass, back
  to the design's own line ("or Settings", and dismissal from the page).
- *"Daemon and web UI: a Stats page, `GET /api/sessions/{id}/stats`, the MCP tool `route_stats` and
  `inspector stats` show per-route calls, attempts, retries, 4xx, 5xx and transport-failure rates and
  p50/p90/p95/p99/max latency, with p95 and p99 reading `n<20` below 20 samples; paths group into
  routes by template, and `routeTemplates` in `config.json` corrects the grouping; a route's row
  opens the traffic list on a filter that may include paths the route keeps apart, and says so; the
  HTML and Markdown exports carry the table (a `## Routes` section in the Markdown, a `routeStats`
  key in the JSON export, whose `formatVersion` stays 1)."*
  `item:endpoint-latency-stats` (PRs #115 and #121). QA verified. `INTEGRATION.md` v62. "Retries" is
  load-bearing: a redirect is not counted as one, and latency is per attempt with transport failures
  counted and never averaged in. The HAR export is unchanged.
- *"Web UI: a left rail with Traffic, Sessions, Processes and Settings pages replaces the settings
  dialog (Stats and Findings, above, join it). Sessions and processes can be selected and deleted or
  killed in one step, with a result for each. Restart and stop moved to Processes. Settings can now
  edit the daemon's `config.json` (retention and signal caps), applied when the daemon restarts; the
  findings settings are the exception and apply on the next read. The top bar and tabs fit a 400px
  window, the closed session picker keeps the start time visible, and the 'Lost the daemon' banner
  now stays up through a restart that never comes back. Daemon: new `POST /api/peers/kill`,
  `POST /api/sessions/delete` and `GET`/`PUT /api/config`; all three need `X-Inspector-Control: 1`
  where they change something."*
  `item:web-foundation-shell` (1a, PRs #108, #112, #114). QA verified. `INTEGRATION.md` v60.
  `docs/DAEMON.md` documents the three routes and the config rules.
- *"Web UI: more room for rows. The toolbar is one row, endpoint shortcuts are behind `endpoints ▾`,
  exports and keyboard shortcuts are in a `⋮` menu, and the time axis and the now strip share one
  line. The list and detail can be resized by dragging the divider (double-click resets), the detail
  can be hidden, and a call opens on its own page at `#/txn/<id>`. A compact row density is in `⋮`
  and Settings. The traffic tab now counts the whole session, like the signal tabs, and every count
  says what it counts. Fixed: a closed detail drawer could be reached with Tab and widened the page
  below 1200px; at 400px rows lost their path and byte count; each traffic view now reopens where you
  left it while live is paused."*
  `item:web-foundation-list-room` (1b, PR #119). QA verified. `INTEGRATION.md` v61.
- *"Web UI: icon buttons are at least 24×24 with drawn icons, buttons on the pages at least 24px
  tall, notes at most 72 characters wide, and Sessions rows lead with the start time. Keyboard keys
  for every page: `g` then `t` Traffic, `s` Stats, `f` Findings, `a` Sessions, `p` Processes and `,`
  Settings, and `?` for the list of keys; the top of the list is now `g g`, not `g`. Typing in the
  replay editor no longer triggers shortcuts."*
  `item:web-ui-polish` PR A parts 1 and 2: A1 and A2 (KIN-102, KIN-103, PR #150), QA verified; A3
  (KIN-104, PR #155), **QA owed**. A7 (KIN-108, the contact sheet) owes no line. A4 to A6 and PR B
  come after 1.4.0. `g g` is load-bearing: it is the one change to an existing key. No
  `INTEGRATION.md` bump of its own.
- *"Web UI: the Sessions page names the folder this daemon keeps its sessions in, whatever `--data`
  is, where it always said `~/.inspector/sessions`. An exported file, which has no Sessions page,
  names none."*
  `item:docs-housekeeping-2026-10-21` (KIN-89, PR #140). QA verified. A relative `--data` shows a
  relative path, as the Processes page does.

## Web UI fixes

These change only the web files the daemon serves: no consumer code, coordinates or release build is
touched, so none has an `INTEGRATION.md` entry of its own.

- *"Web UI: the repeat count now counts calls, not rows; an expanded run of signals no longer
  collapses during live tail; a marker dropped at the same millisecond as a call is drawn before it,
  as the overlay always has."*
  Phase A, drifts 1 to 3. QA verified. Drift 4 and the `?` case get no line: they change only tie
  order and one edge of the duplicate key.
- *"Web UI, filters: a filter now applies to the signals on the timeline and path views, and a
  session with more than 1000 signals shows all of them. With a filter set, a call or signal arriving
  live that the filter does not match is no longer drawn in the list, the timeline or the path's
  visit lines (screens still mark the path, as on a load), and one that matches appears without a
  reload; the header count and the traffic tab follow live calls; a filter of only spaces counts as
  no filter."*
  `item:signals-query-one-rule` and `item:web-live-rows-filtered`. QA verified. "Follow" is a
  throttled re-read, not an instant check: a matching live row appears about a second after the
  first row of a burst, so the line does not say "at once".
- *"Web UI, switching sessions: nothing of the previous session shows while the new one loads — not
  its calls, endpoint chips, markers or tab counts, nor its now-strip values, timeline signal rows or
  session glance. Each pane says the session is being read until it arrives, and a call left open
  from the previous session closes. Of two overlapping reads of the app's current state the newer one
  wins, and a failed read no longer empties the now strip of the session you are on: it keeps its
  values, each with its age."*
  `item:web-current-session-guard`, `item:web-now-strip-follows-session` (KIN-57) and
  `item:web-switch-clears-call-panes` (KIN-94, KIN-95, PR #135). QA verified. The tab bar keeps its
  tabs through the window, without counts.
- *"Web UI, large sessions: the first rows and the live connection appear at once instead of waiting
  for the whole session to be read (on a 160,000-call session, in under half a second where it took
  about 24), and counts that need the whole session — the glance, the path, the endpoint shortcuts,
  the overview axis — say the session is still being read until it is. Past 1000 calls the live list
  says what it holds: `newest 1000 of N shown` with no filter, and `newest 1000 of 1200 matches
  shown` under one, also while a stretch is brushed, with a note of how many matches are loaded. The
  endpoint chips and repeat marks count the whole session, not the newest 1000. A brushed stretch
  older than the newest 1000 calls says those calls are there and how to reach them (under a filter,
  that none of the loaded matches is there and how many older ones are not loaded). An exported
  session draws every call, and a live call arriving while the page loads is no longer lost."*
  `item:web-transactions-paged`, `item:web-list-says-what-it-holds` (KIN-56, KIN-72, KIN-73) and
  KIN-42 of `item:signals-query-one-rule` (PR #136). QA verified. The timing is QA's, in headless
  Chrome (411 ms against about 24 s), and is not a promise for other sizes or machines; the
  whole-session read itself is unchanged and uncapped. "Live" is load-bearing in the list's wording:
  offline the export draws every call and reads `N of N calls`. A stretch holding some loaded rows
  and some older unloaded matches still says nothing about the older ones; the line does not claim
  it.
- *"Web UI, live updates: with live on, a new call appears on the timeline without any other action,
  and the session glance and the selected session's picker label count it as the header does, where
  they showed the count as of the last load. A burst of live signals or markers redraws the page a
  few times a second instead of once per message, and reads the app's current values once per redraw
  rather than once per signal."*
  `item:web-live-calls-reach-every-pane` (KIN-58) and `item:web-live-signal-burst-bounded` (KIN-101,
  PR #144). QA verified. The glance counts the whole session, not the filter, so under a filter it
  reads `calls 16` beside a header of `4 of 16 calls`; the line does not say the two are equal then.
  A lone signal or marker appears about 280 ms after it arrives (QA read 277 to 281 ms), so the line
  does not say "immediately". A live call still redraws the waterfall and the path once per call.
- *"Web UI: on a narrow window (400px) the Stats page breaks a route only between the parts of its
  address, at a `.`, a `:` or a `/`, so `127.0.0.1:8080` no longer reads as port 808 over a line that
  ends in `0`. A route too long to fit widens its column inside the table's own sideways scroll; the
  page itself does not scroll sideways. Wider windows are unchanged: a route is one line there."*
  `item:web-stats-route-breaks-whole` (KIN-93, PR #132). QA verified. The same holds in an exported
  file. "Only between the parts" is load-bearing: a long hostname label is held whole, so one very
  long route makes the route column wider and leaves fewer number columns beside it until the table
  is scrolled.

## Daemon, MCP and exports

These reach a consumer only through the daemon they download.

- *"MCP: `timeline` and `explain` place a marker dropped at the same millisecond as a call before
  that call, as both UIs do, and `explain` counts an earlier call at that millisecond as leading to
  it; in 1.3.0 and earlier both came after."*
  Phase E's MCP tie fix. QA verified. Announced by the agent team's decision of 2026-10-18 under the
  maintainer's delegation; the maintainer may overturn it (`ROADMAP-DONE.md` → §5 → "Open questions
  only the human can answer").
- *"Daemon: the Markdown export's URL line no longer repeats the query string, and MCP explain
  reports the full URL, port and query included."*
  `item:export-md-query-once` (KIN-23). QA verified.
- *"Daemon and MCP: call labels in `timeline`, `export.json` and the HTML and Markdown exports now
  include the port when it is not the scheme's default (`GET 127.0.0.1:9090/json`, where it was
  `GET 127.0.0.1/json`), so two local servers no longer read as one. Model: new
  `NetworkTransaction.hostPort`."*
  `item:row-label-port` (KIN-60, PR #105). QA verified. `url` is unchanged; the `byHost` split by
  port is the IPv6 line's, above.
- *"Daemon: an IPv6 server called through both OkHttp and Ktor is one route on the Stats page, in the
  Markdown export's Routes table and in MCP `route_stats`, not two rows with half the calls each; the
  row's `host` reads the bare literal (`::1`)."*
  `item:route-key-one-ipv6-host` (KIN-100, PR #142). QA verified. "The row's `host` reads the bare
  literal" is load-bearing: a script or agent that matched a route row's `host` against `[::1]`
  (Ktor's stored form) gets `::1` now, and the page and the Markdown still draw `[::1]:8080`. Decided
  in this consolidation: the sentence is owed to `INTEGRATION.md`, as v63 gave one for `byHost`, and
  v64 has it.
- *"Daemon: the MCP server's **test** on the Processes page now gives up after its timeout when
  `inspector mcp` is wedged and writes nothing, and stops that process, instead of never
  answering."*
  `item:mcp-probe-deadline-holds` (KIN-96, PR #138). QA verified. In the notes: the daemon ships in
  the zip, and a hang is user-visible. The probe's 15 s default and its route are unchanged.

## Sample app

The sample is not a published module, so nothing here changes what a consumer builds.

- *"The sample app has a demo flow that both findings families catch — a double submit with no
  idempotency key and a token in a query string — and a `sample/README.md` that walks a newcomer
  through it."*
  `item:sample-demo-flow` (KIN-128, PR #162). **QA owed.**

## Owe nothing

`item:structure-split-mcp-test` and `item:structure-phase-h` (tests and file moves only), polish A7
(the contact sheet), and the guard and script items merged on 2026-10-21 and 2026-10-22.
