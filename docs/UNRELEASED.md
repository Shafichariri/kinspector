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
glance no longer show the previous session's values while the new one loads; the glance says it is
loading. Of two overlapping reads of the current state the newer one wins, and a failed read no
longer empties the strip of the session you are on: it keeps its values, each with its age."*
The timeline's call rows and the endpoint chips still show the previous session for the same
window; the line says nothing about calls.
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
`NetworkTransaction.hostPort`."* `url` is unchanged. The session summary's `byHost` still groups by
bare host, so the line does not say hosts are split by port everywhere.
`item:structure-phase-i` (done 2026-10-21) owes an eleventh, and it is not daemon-only: *"Overlay
(`inspector-ui`): `InspectorTheme`, `InspectorColors` and `LocalInspectorColors` are no longer
public. An app that named them replaces those uses with its own code; `INTEGRATION.md` v59 says
how."* **This release must be a minor bump, not a patch** (decided 2026-10-18), and the
`docs/INTEGRATION.md` v59 text already says "from the next release after 1.3.0". The
release-steward's changelog entry for the release points back to v59 and names the version that v59
calls "the next release after 1.3.0", and re-checks that against `git tag`.
`item:structure-split-mcp-test` and `item:structure-phase-h` owe nothing: tests and file moves only.
