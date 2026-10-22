# Unreleased — lines owed to the next release's notes

Each line below is owed to the notes of the next release, and is emptied into that release when it
is cut. Moved here from `ROADMAP.md` → §5 → Phase A on 2026-10-19.

**Release notes.** One line, for drifts 1 to 3, because a person can see them: *"Web UI: the repeat
count now counts calls, not rows; an expanded run of signals no longer collapses during live tail;
a marker dropped at the same millisecond as a call is drawn before it, as the overlay always has."*
Drift 4 and the `?` case get no line: they change only tie order and one edge of the duplicate key.
**Pending — not yet in any release.** When phase A ships, the `release-steward` puts this line in
the release notes and in a daemon-only `INTEGRATION.md` entry ("download the new daemon; leave your
coordinates alone"), as v40, v48 and v56 did for web-UI-only changes. Phase E's MCP tie fix is
announced too (decided by the agent team on 2026-10-18 under the maintainer's delegation; the maintainer may overturn; see `ROADMAP-DONE.md` → §5 → "Open questions only the human can answer"), right after the web-drift line, since it is the
same rule on a third surface: *"MCP: `timeline` and `explain` place a marker dropped at the same
millisecond as a call before that call, as both UIs do, and `explain` counts an earlier call at
that millisecond as leading to it; in 1.3.0 and earlier both came after."*
The Markdown-export fix (`item:export-md-query-once`, done 2026-10-18) owes a third line in the same
entry: *"Daemon: the Markdown export's URL line no longer repeats the query string, and MCP explain
reports the full URL, port and query included."*
`item:signals-query-one-rule` owes a fourth: *"Web UI: a filter now applies to the signals loaded on
the timeline and path views (rows arriving live follow the filter once item:web-live-rows-filtered
lands), and a session with more than 1000 signals shows all of them."* "Loaded" is load-bearing: a
signal pushed over the live socket is drawn whatever the filter until that item lands, so a line
saying the filter applies to every signal would be false on a live session.
`item:web-transactions-paged` (done 2026-10-19) owes a fifth: *"Web UI, on a session of more than
1000 calls: with no filter, the live list says `newest 1000 of N shown` instead of a count shaped
like a filter; the endpoint chips and repeat marks count the whole session, not the newest 1000; an
exported session draws every call; and a live call arriving while the page loads is no longer lost."*
"Live" is load-bearing in the first clause: offline the export draws every call, so it reads
`N of N calls`, and a line promising the same words in both would be false. No `INTEGRATION.md`
entry of its own: it joins the same daemon-only entry as the lines above, because a consumer's
code, coordinates and release build are untouched and the change reaches them only through the
daemon they download.
"1.3.0 and earlier" is right only if this ships in the next release after 1.3.0; the
`release-steward` re-checks it against `git tag` at release, here and in `DAEMON.md` §8.
`item:web-current-session-guard` (done 2026-10-19) owes a sixth, joining the same daemon-only entry:
*"Web UI: switching sessions could show the previous session's \"now\" values in the now strip; it now
shows the selected session's own values, or none."* That item's first version also emptied the strip on a failed read of the now
values on an unchanged session; `item:web-now-strip-follows-session` (below) reversed it, so the
release-steward does not carry that sentence into the notes.
`item:web-live-rows-filtered` (done 2026-10-19) owes a seventh, in the same daemon-only entry:
*"Web UI: with a filter set, a call or signal arriving live that the filter does not match is no
longer drawn in the list, the timeline or the path's visit lines (screens still mark the path,
as on a load), and one that matches appears without a reload; the header count and the traffic tab
follow live calls; a filter of only spaces counts as no filter."*
It also settles the fourth line above: "rows arriving live follow the filter once
item:web-live-rows-filtered lands" is now true, and the "Loaded is load-bearing" caveat no longer
applies, so the release-steward drops it when the lines are merged. "Follow" is a throttled re-read,
not an instant check: a matching live row appears about a second after the first row of a burst, and
a burst of 100 rows in a second costs at most 2 re-reads. Daemon and wire unchanged.
`item:web-now-strip-follows-session` (done 2026-10-21) owes an eighth, in the same daemon-only entry:
*"Web UI: after switching sessions, the now strip, the timeline's signal rows and the session
glance no longer show the previous session's values while the new one loads; the glance says the
session is being read. Of two overlapping reads of the current state the newer one wins, and a failed read no
longer empties the strip of the session you are on: it keeps its values, each with its age."*
`item:web-list-says-what-it-holds` (done 2026-10-21, with its fix round) owes a ninth, in the same
daemon-only entry: *"Web UI: under a filter matching more than 1000 calls, the header no longer
reads as if only 1000 matched. It says `newest 1000 of 1200 matches shown`, also while a stretch is
brushed, and the list notes how many matches are loaded. A brushed stretch older than the newest
1000 calls says those calls are there and how to reach them (under a filter, that none of the loaded
matches is there and how many older ones are not loaded), instead of `no calls in the selected
stretch`."* A stretch holding some loaded rows and some older unloaded matches still says nothing
about the older ones; the line does not claim it.
`item:row-label-port` (done 2026-10-21) owes a tenth, for agents and anyone handed an export:
*"Daemon and MCP: call labels in `timeline`, `export.json` and the HTML and Markdown exports now
include the port when it is not the scheme's default (`GET 127.0.0.1:9090/json`, where it was
`GET 127.0.0.1/json`), so two local servers no longer read as one. Model: new
`NetworkTransaction.hostPort`."* `url` is unchanged. The session summary's `byHost` grouped by bare host
when this line was written, so it does not say hosts are split by port; the sixteenth line below does.
`item:structure-phase-i` (done 2026-10-21) owes an eleventh, and it is not daemon-only: *"Overlay
(`inspector-ui`): `InspectorTheme`, `InspectorColors` and `LocalInspectorColors` are no longer
public. An app that named them replaces those uses with its own code; `INTEGRATION.md` v59 says
how."* **This release must be a minor bump, not a patch** (decided 2026-10-18), and the
`docs/INTEGRATION.md` v59 text already says "from the next release after 1.3.0". The
release-steward's changelog entry for the release points back to v59 and names the version that v59
calls "the next release after 1.3.0", and re-checks that against `git tag`.
`item:structure-split-mcp-test` and `item:structure-phase-h` owe nothing: tests and file moves only.
`item:web-foundation-shell` (1a, done 2026-10-21) owes a twelfth, in the same daemon-only entry, and it is consumer-visible: the
web UI and the daemon's HTTP API both change. Web UI: *"a left rail with Traffic, Sessions, Processes and Settings pages replaces
the settings dialog. Sessions and processes can be selected and deleted or killed in one step, with a result for each. Restart and
stop moved to Processes. Settings can now edit the daemon's `config.json` (retention and signal caps); changes apply when the
daemon restarts. The top bar and tabs fit a 400px window, the closed session picker keeps the start time visible, and the 'Lost the
daemon' banner now stays up through a restart that never comes back."* Daemon: *"new `POST /api/peers/kill`,
`POST /api/sessions/delete` and `GET`/`PUT /api/config`; all three need `X-Inspector-Control: 1` where they change something."*
`docs/DAEMON.md` already documents the three routes, a `curl` for each and the config rules (checked at the close: the Processes
section, "Deleting sessions", the config section and the control-header list), so no docs gap there. `docs/INTEGRATION.md`
v60 (2026-10-21) carries the web UI change: §6c (the rail, restart and stop on Processes), §6e (the Sessions page), §12h (the
Settings editor) and a changelog entry, "Nothing to do. The web UI's session list moved from the settings dialog to a Sessions
page." The release's own entry points back to v60 as it does to v59, and names the version v60 calls "the next daemon release
after 1.3.0".
`item:web-live-calls-reach-every-pane` (done 2026-10-21, with its fix round) owes a thirteenth, in the same daemon-only entry, and
a user could have read the stale count: *"Web UI: with live on, a new call now appears on the timeline without any other action,
and the session glance and the selected session's picker label count it as the header does. They used to show the count as of
the last load (`calls 27` under a header of 29, `6 calls` in the picker)."* The glance counts the whole session, not the filter,
so under a filter it reads `calls 16` beside a header of `4 of 16 calls`; the line does not say the two are equal then. No
`INTEGRATION.md` bump of its own: the change is in the web files the daemon serves, and no consumer code, coordinates or
release build is touched.
`item:web-foundation-list-room` (1b, done 2026-10-21) owes a fourteenth, in the same daemon-only entry, and it is consumer-visible: *"Web UI: more room for rows. The toolbar is one row, endpoint shortcuts are behind `endpoints ▾`, exports and keyboard shortcuts are in a `⋮` menu, and the time axis and the now strip share one line. The list and detail can be resized by dragging the divider (double-click resets), the detail can be hidden, and a call opens on its own page at `#/txn/<id>`. A compact row density is in `⋮` and Settings. The traffic tab now counts the whole session, like the signal tabs, and every count says what it counts. Fixed: a closed detail drawer could be reached with Tab and widened the page below 1200px; at 400px rows lost their path and byte count; each traffic view now reopens where you left it while live is paused."* `docs/INTEGRATION.md` v61 (2026-10-21) covers it: §6e (the tab names, the now strip on the axis line, the one-row toolbar with `endpoints ▾` and `⋮`, the divider, hiding the detail, `#/txn/<id>`, and exports from `⋮`), a forward pointer in v56's export bullet, and a changelog entry, *"Nothing to do. The web UI's exports moved from `export ▾` beside the session picker to the `⋮` menu at the end of the traffic toolbar; nothing else about exporting changed."* The release's own entry points back to v61 as it does to v59 and v60, and names the version v61 calls "the next daemon release after 1.3.0".
`item:endpoint-latency-stats` (done 2026-10-21, PRs #115 and #121) owes a fifteenth, in the same daemon-only entry, and it is consumer-visible: *"Daemon and web UI: a Stats page, `GET /api/sessions/{id}/stats`, the MCP tool `route_stats` and `inspector stats` show per-route calls, attempts, retries, 4xx, 5xx and transport-failure rates and p50/p90/p95/p99/max latency, with p95 and p99 reading `n<20` below 20 samples; paths group into routes by template, and `routeTemplates` in `config.json` corrects the grouping; a route's row opens the traffic list on a filter that may include paths the route keeps apart, and says so; the HTML and Markdown exports carry the table (a `## Routes` section in the Markdown, a `routeStats` key in the JSON export, whose `formatVersion` stays 1)."* "Retries" is load-bearing: a redirect is not counted as one, and latency is per attempt with transport failures counted and never averaged in. The HAR export is unchanged. `docs/INTEGRATION.md` v62 (2026-10-21) covers it: §6c (Stats on the rail), §6e (the Stats page, `routeTemplates` in `config.json`, and the route table in the HTML, Markdown and JSON exports), §10 (`route_stats(filter, sort, limit)`), and a changelog entry, *"Nothing to do. Optionally, if the Stats page groups two of your paths wrongly, list templates under `routeTemplates` in `~/.inspector/config.json` on your Mac (§6e) and restart the daemon."* The release's own entry points back to v62 as it does to v59 to v61, and names the version v62 calls "the next daemon release after 1.3.0". `docs/DAEMON.md` already documents the route, the config key and the CLI line (§6, §7).
`item:host-port-everywhere` (KIN-92, done 2026-10-22, PR #129; the line came in with the PR) owes a sixteenth, and it is not daemon-only, because `url` is public in the published
`:inspector-model`: *"A call to an IPv6 address now has a URL that is one. `NetworkTransaction.url` and `hostPort` bracket
an IPv6 host (`http://[::1]:8080/v6`, where an OkHttp capture read `http://::1:8080/v6`), so the overlay's Copy URL and
cURL, the web UI's URL, cURL and replay, the Stats page, HAR and the exports' labels and routes table are valid for it; archives recorded earlier read the
same way. Agents and CLI: the session summary's `byHost` (`GET …/summary`, MCP `session_summary`, `inspector summary`,
the exports) now names a server by host and port, so `127.0.0.1:8080` and `127.0.0.1:9090` are two entries, where they
were one `127.0.0.1`; a default port adds nothing to the key."* No signature changes and no golden file moved. The
`host:` filter term is unchanged and still matches the stored host, which is `::1` from OkHttp and `[::1]` from Ktor, so
the line does not say filters see brackets. `docs/INTEGRATION.md` v63 (2026-10-21) covers it, and because `url` is in the
library, the release's notes point at it as a library change, not inside the daemon-only entry: *"Nothing to do, unless your
code parses `NetworkTransaction.url` or `hostPort` for calls to an IPv6 address, or a script or agent of yours reads the
session summary's `byHost` keys as bare host names."*
`item:web-stats-route-breaks-whole` (KIN-93, done 2026-10-22, PR #132) owes a seventeenth, in the same daemon-only entry, and it is
consumer-visible: *"Web UI: on a narrow window (400px) the Stats page breaks a route only between the parts of its address, at a
`.`, a `:` or a `/`, so `127.0.0.1:8080` no longer reads as port 808 over a line that ends in `0`. A route too long to fit
widens its column inside the table's own sideways scroll; the page itself does not scroll sideways. Wider windows are
unchanged: a route is one line there."* The same holds in an exported file. "Only between the parts" is load-bearing: a long
hostname label such as `api.qa-long-name` is held whole, so one very long route makes the route column wider than before and
leaves fewer number columns beside it until the table is scrolled. No `INTEGRATION.md` bump of its own: the change is in the web
files the daemon serves, v62's description of the Stats page does not mention wrapping, and no consumer code, coordinates or
release build is touched. The `release-steward` may overrule that.
`item:web-switch-clears-call-panes` (KIN-94 and KIN-95, done 2026-10-22, PR #135) owes an eighteenth, in the same daemon-only entry, and it is consumer-visible: *"Web UI: switching sessions no longer shows the previous session's calls, endpoint chips, markers or tab counts while the new one loads; each pane says the session is being read until it arrives, and a call left open from the previous session closes."* It finishes what the eighth line started, which covered only the now strip, the signal rows and the glance. The tab bar keeps its tabs through the window, without counts. No `INTEGRATION.md` bump of its own, as for the lines above: the change is in the web files the daemon serves. The `release-steward` may overrule that.
`item:signals-query-one-rule`'s KIN-42 (done 2026-10-22, PR #136) owes a nineteenth, in the same daemon-only entry, and it is consumer-visible: *"Web UI: on a very large session the first rows and the live connection now appear at once, instead of after about 25 seconds of 'waiting for an app'. Counts that need the whole session (the glance, the path, the endpoint shortcuts, the overview axis) say the session is still being read until it is."* "About 25 seconds" was measured on a 160,000-call session in headless Chrome and is not a promise for other sizes or machines; the whole-session read itself is unchanged and uncapped. No `INTEGRATION.md` bump of its own.
`item:docs-housekeeping-2026-10-21` (KIN-89, done 2026-10-22, PR #140) owes a twentieth, in the same daemon-only entry, and it is consumer-visible: *"Web UI: the Sessions page names the folder this daemon keeps its sessions in, whatever `--data` is, where it always said `~/.inspector/sessions`. An exported file, which has no Sessions page, names none."* A relative `--data` shows a relative path, as the Processes page does. No `INTEGRATION.md` bump of its own; the rest of that item (the `INTEGRATION.md` headings, the `AGENTS.md` table, `architect.md`) changes nothing a consumer does.
`item:mcp-probe-deadline-holds` (KIN-96, done 2026-10-22, PR #138) owes a twenty-first, in the same daemon-only entry. The daemon ships in the release zip, so this is user-visible: *"Daemon: the MCP probe in settings now gives up after its timeout when `inspector mcp` is wedged and writes nothing, and stops that process, instead of never answering."* The `release-steward` decides whether it belongs in the notes; the probe's 15 s default and its route are unchanged. No `INTEGRATION.md` bump of its own.
`item:web-ui-polish`'s KIN-104 (A3, not merged yet) owes a line, in the same daemon-only entry, and it is consumer-visible: *"Web UI: keyboard keys for every page: `g` then `t` Traffic, `s` Stats, `a` Sessions, `p` Processes and `,` Settings (and `f` Findings, `d` Diff where those pages exist), `?` for the list of keys; the top of the list is now `g g`, not `g`. Typing in the replay editor no longer triggers shortcuts."* `g g` is load-bearing: it is the one change to an existing key, and a reader with the old habit needs to be told. The rest of the design note's line (icon buttons, page buttons, note width, the route templates editor) belongs to the polish tickets that build each, and is added with them.
