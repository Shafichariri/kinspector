# Decisions — exports
Owned by `:inspector-daemon`: the HAR, Markdown and HTML exports. Imported by the `inspector-daemon` guide.

**The HAR export writes `-1` for everything capture never measured, and that is the whole
design.** HAR was specified by wire-level tools and asks for a send/wait/receive breakdown, a
`headersSize` and an `httpVersion`; Inspector has one duration measured around the call, parsed
headers rather than the raw block, and no negotiated version at that layer. `-1` is what the spec
reserves for "no information available", and it is the only honest answer — a plausible breakdown
would draw a waterfall in DevTools out of numbers nobody measured, and a waterfall is read as
evidence. `bodySize` is deliberately *not* `-1`: `reqBytes`/`resBytes` are true sizes, counted even
when the body was not captured, so reporting them as unknown would throw away a real measurement.

Two things the export must keep disclosing, because HAR has no field for either and silence reads
as a claim: that a row is one **attempt** of a call that redirected or retried, and **what
redaction removed**. Both go in the entry `comment`. A redacted capture handed over as a complete
one is the export lying by omission.

A response body that is not valid UTF-8 is base64, which HAR supports. A **request** body that is
not is reported absent with the reason, because `postData.text` is a string with no encoding escape
— and `String(bytes)` would not fail, it would substitute U+FFFD and present a page of replacement
characters as the captured body. The strict decoder exists to reach that branch.

**The HTML export is the web UI, not a report, and one seam is what makes that hold.**
`export.html` inlines the served `index.html`, `style.css` and `app.js` with the session embedded as
`window.INSPECTOR_ARCHIVE`. A purpose-built report would be a second rendering of every row, and
every second rendering here has drifted from the first. What makes it work is that `app.js` talks to
the daemon through one object, `source`: live it is `fetch`; in an export it is `archiveSource`, a
GET router over the embedded data that mirrors the daemon's reply shapes — transactions as a page,
signals as a bare array, both newest first — and refuses anything else with a message. **The
browser's `fetch` is called exactly once in `app.js`**, and it is counted twice: statically on every
push by `check-web-static.js` across every loaded script — `window.fetch(`, `globalThis.fetch(` and
`self.fetch(` must be zero, so the bare count cannot be dodged — and in the built export by
`render-web-ui-archive.js`, with the same counter, so a new call site cannot reach for the network
from inside an export. The export's embedded session is not code and is not counted: a script is
skipped as data only when what follows `window.INSPECTOR_ARCHIVE = ` parses as JSON, which nothing
with a call appended to it does, so a captured body that mentions `fetch(` cannot fail a sound
export and a call smuggled after the data cannot pass one. The static count is text patterns —
`window['fetch'](` or `fetch (x)` escape it — and the runtime ban on the network is what covers
those. That probe opens the real file
under a `file:` URL with `fetch`, `WebSocket` and `XMLHttpRequest` all throwing; one network attempt
fails it. Each check was proven to fail by breaking a copy of the file.

What an export cannot do, it hides and says. The filter is **disabled, not reimplemented**: the chips
alone emit seven kinds of term, and a JavaScript evaluator would be a second parser of the shared
grammar, the drift this repository refuses. `applyFilter` is the single funnel for all six ways of
setting a filter, which is the only reason one guard in it covers them all; without it a marker click
empties the list behind a refusal. Controls that send something are hidden by `body.archive` with
`!important`, because two of them are re-shown by script on every render. `connectLive` is skipped
outright: from `file://` the socket URL is malformed and its constructor throws inside `init`.
`localStorage` goes through `prefs`, because storage can throw on an opaque origin before anything
renders.

Embedding is safe because every `<` in the JSON becomes `\u003c`. A `<` can only occur inside a
JSON string, where that escape means the same character to both parsers, so one rule disarms
`</script>` and `<!--`. The two tags the exporter replaces are the same literals
`render-web-ui.js` replaces, and both sides now refuse to proceed when a replacement misses.

**All three exports read the raw readers, never `queryTransactions` or `SessionRepository.timeline()`.**
The first clamps any limit to 1000 and the second to 2000. Until phase E the second also sorted
calls before a marker at the same `mono`; it now orders through the shared `timeline()` but still
clamps. The HAR route had asked for `Int.MAX_VALUE` rows from the first under a comment saying a
quietly incomplete export was the thing to avoid, so every HAR of a session over 1000 calls was
exactly that. It reads `filterTransactions` now, and `HarTest` pins it at 1001 rows.

**Export payloads are the captured text, never `Signal.data`.** Filling `data` means parsing the
payload, and a parse keeps only the last of a duplicated key. The JSON tree in the HTML needs the
bytes the app sent, so payloads travel in `signalData` with the same utf8/base64 rule bodies use.
The Markdown re-indents bodies with `prettyJson`, now in `:inspector-model` for the same reason
`pathScope` moved: the overlay and the daemon both render bodies for people.

**`export_session` writes beside `sessions/`, never inside a session folder.** Retention sizes a
session by everything under its folder, so an export there would inflate the session it describes
and be pruned with it.
