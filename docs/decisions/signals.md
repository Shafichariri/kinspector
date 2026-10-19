# Decisions — signals
Owned by `:inspector-ui` and the recording side of `:inspector-core`, with the shared rules in `:inspector-model`. Imported by the `inspector-model`, `inspector-core` and `inspector-ui` guides.

**Signals merge into the overlay's existing list, and a run's head is not its earliest member.**
`SIGNALS.md` → "Out, deliberately" said the overlay stays traffic-only; that is overturned, and
the bullet is annotated rather than deleted because the reasoning is still the record. The fact it
does not mention is that `Inspector.signals` is a public `StateFlow` and `Signal.data` is in device
memory, so this was always unwritten UI.

Merged into the traffic list rather than behind a tab: a phone has no room for a second view of one
session, and the merged reading *is* the feature. `timeline()` takes signals and returns a third
`TimelineEntry` kind, which broke every exhaustive `when` in the repo — the sealed type doing its
job.

**`timelineRuns()` collapses only adjacent identical observations.** A state holder firing on every
keystroke produced 48 adjacent rows in one real session, which on a phone is the whole screen. A
run interrupted by a call or a marker is two runs, because that interruption is information: the
state settled, something else happened, and it moved again.

**A run's members are in draw order, so `first` is the latest in newest-first.** Anything that must
mean the same in both orders reads `TimelineRun.earliest`. The id does, and has to: a run grows at
its newest end, so an id taken from the head would re-key on every observation during live tail and
collapse the run under the reader — in exactly the mode someone watching live traffic is in. It
would also change when the order toggle is tapped. Two tests pin this and neither is redundant.
`app.js`'s `runIdOf` took the head from v0.8.0, when the model's id moved to `earliest`, through
v1.3.0, so an expanded run on the web collapsed under the reader during live tail;
`mirror-cases/timelineRuns.json` now carries the newest-first cases.

**The overlay can enumerate providers and the host cannot, which is the whole of stage 4.** A
daemon learns a provider's name only when one answers, so the web UI infers the set from what a
session already holds and says in its own comment that it is guessing — conservatively, to avoid
firing fifty doomed requests at an app. In-process there is nothing to infer:
`Inspector.signalProviders()` **is** the registry. Two consequences the host can never have: a pull
is offered exactly where it will work, and a provider that has **never answered** is still
findable, where host-side it is invisible until the first time it does.

That last case is why the now strip lists unread providers. With no observation there is no row to
open, so the detail screen's pull button is unreachable for exactly the providers nobody has used
yet — they would exist nowhere in the UI at all.

**`pullSignal` is separate from `answerSignalRequest` because the `requestId` differs, and that is
not cosmetic.** A host pull correlates to a `SignalRequest`; a local one correlates to nothing, so
the recorded row carries a **null** `requestId` rather than an invented id pointing at a request
that was never made. The trigger stays `request` in both: what it distinguishes is pushed-by-the-app
versus read-on-demand, and an overlay pull is a read on demand.

**`Recorder.providerKeys()` exists beside `registeredProviders()` and neither replaces the other.**
The latter joins `tag` and `name` with `/` for a human to read in an error message and **cannot be
split back** — `cache/portfolio/holdings` has three parts and two of them are the name. Anything
that means to *call* a provider needs the pair exactly as registered, which is what the structured
one returns.

**`signalProviders()` is a snapshot and deliberately not a flow.** Providers come and go as caches
and repositories are built, so any answer is historical by the time it is read. Rather than invent
a flow to look otherwise, the controls built from it treat a failed pull as ordinary — which it is
— and `pullSignal` reports that as a message rather than throwing. The message is the app's own and
is shown verbatim: it is the only diagnosis there is.

**The "now" strip is a lookup and the list is the feed, which is why they sort differently.**
`signalGroups` orders by recency — right for a history, where the reader is looking for what
changed. `currentObservations` sorts alphabetically by tag then name, because the strip is glanced
at repeatedly and a row that moves whenever the app mentions something else is one you have to find
again each time. Two orderings of the same data, each wrong for the other's job.

**The collapsed line says how stale the oldest of it is, and that is the half that earns the line.**
A count says the panel has something in it; the age says whether opening it is worth doing, and it
is what makes the word "now" a claim rather than a label. Most observations are *pushed* — the app
said something once and has not been asked since — so a panel headed "now" listing a value pushed
at app start with nothing saying when is the confidently-wrong reading `SignalTrigger` exists to
prevent. An unreadable timestamp is skipped rather than counted as zero: reporting it as the
freshest thing on screen is that job done backwards.

**Freezing holds the clock as well as the rows.** The ticker stops while the list is frozen, so a
held snapshot is not described by a clock that has moved on from it — "oldest 6m ago" over rows
that stopped updating five minutes ago is a sentence about two different moments. `InspectorList`
takes a defaulted `nowMsProvider` **so this is provable**: the ages come from the real clock, a
test cannot move the real clock, and a rule about what happens to ages over time that nothing can
exercise is a paragraph of comment rather than a behaviour.

**An age is wall clock, and `mono` would be better if it were reachable.** The overlay runs in the
process that recorded the row, so `mono` — which cannot jump — is the right clock for an age. It is
unusable: `mono` is measured from an origin private to `:inspector-core`, and a
`TimeSource.Monotonic.markNow()` taken in `:inspector-ui` is a different origin and yields ages
wrong by however long the process had been running. Exposing the origin is a public API change to
carry an age column, which is not a trade worth making. So both surfaces use wall clock — the web
because it is a different machine, the overlay because the better clock is out of reach — and both
are wrong in the same single case, a clock change mid-session.

**Signals go through the same filter as the traffic, and did not until stage 2.** The overlay
filtered the rows and passed every observation to `timeline()` untouched, so `path:/v2` thinned the
calls and left the signals sitting between them. The web UI has always sent its filter to the
signal route, and the route ignored it until `item:signals-query-one-rule`: it filtered on `tag=`
alone, so the web timeline and the path view's visit lines disagreed exactly as the overlay had. It
now reads `SessionRepository.querySignals`, the function `list_signals` reads, and
`SignalQueryTest` holds the two to the same ids in the same order.

The exclusion rule then does the interesting half, and it is the feature rather than a side effect:
`tag:` is a `SignalTerm` so a tag chip **drops every transaction**, and `path:` is a
`TransactionTerm` so it drops every signal. That is what makes a tag chip a browser for one tag
rather than a highlight over an unchanged list. Anyone wanting both writes the `|` form by hand.

**Tag chips are built from the session, never from `SignalTags`.** The tag set is open by design,
so a chip list built from the constants would be a list of the tags *this build* knows about —
which is not a fact about the app being debugged. The same goes for every section of the filters
sheet: endpoints, methods and markers are whatever this session holds. *(The ordering argument that
used to follow — tags before markers on a 360dp strip — went with the strip; see the scrollable-strip paragraph in `overlay.md`.)*

**A signal's payload is rendered from `Signal.data`, and the truncated case is not the element.**
`Recorder.emit` cuts the *encoded* payload at `maxPayloadBytes` and keeps the prefix as a
`JsonPrimitive` **string**, because a cut JSON document is not JSON and the archive holds one
payload type rather than two. Pretty-encoding that element therefore renders a whole cache snapshot
as a single quoted line with every `"` escaped — the least readable form of exactly the payload
somebody opened the row to read. `formatSignalPayload` unwraps it first and runs `prettyJson`,
which formats malformed input rather than giving up on it. A `JsonPrimitive` string that is *not*
truncated is a `toString()` dump from the `signal(tag, name, text)` overload: it is not JSON and
must not be re-quoted either.

**A signal's history is keyed on `(tag, name)`, never on `name`.** `Signal.name` is documented as
identity *within* `tag`, and the daemon's last-wins `current` query uses the same pair. Grouping on
the name alone splices a cache entry into a view-model's history and shows a value changing into
something it never was. A test decoy for this has to differ in the tag **and share the name** — one
that differs in both is excluded by either rule and cannot tell the two apart, which is how the
first version of that test passed with the grouping broken.

`signalGroups` orders groups by recency, which is the opposite of `endpointShortcuts` and right for
the opposite reason: chips are aimed at while traffic arrives, and a history list is read to find
what changed. Within a group the observations are ascending by `mono` and do **not** take the
list's sort toggle — a history is a sequence of changes, so it has one true order.

**Provenance is drawn on every observation, not only the pulled ones.** `SignalTrigger`'s own
documentation is the argument: a snapshot with no provenance reads as the current state of the app,
and if it was pushed at app start and the session is now twenty minutes old that reading is
confidently wrong. Drawing it only for `request` rows would leave the common case — pushed,
possibly long ago — as the one with nothing said about it.

**The web UI does not share `signalGroups`, and that is not yet a mirror to keep in step.** The web's
`browserGroups` does the same `(tag, name)` grouping *and* expands a cache snapshot's `items[]` into
one row per entry, which is payload-shaped work the overlay does not do. They agree on the grouping
and differ in what they group; converging them is in `ROADMAP.md` rather than claimed here.

**Signal rows are deliberately not shaped like transaction rows.** A signal has no status, no
duration and no byte count, and giving it those columns would leave four gaps that read as missing
data. One line, a lane stripe, and the clock — the only column it shares with the traffic and the
only one that makes the two comparable. Payloads are not drawn: a row cannot show a JSON object
usefully at 360dp and a truncated one is worse than none.

`InspectorColors.forTag` borrows existing tokens for its lanes, in the same hues as the `.lane-*`
rules in the web stylesheets (which are literals, and not identical shades). Screen reads `accent` and
session reads `clientError`: they read GET and PATCH until those moved to grey and teal, and a grey
screen lane would be indistinguishable from an unknown tag. Borrowing hues does not contradict the
method-colour rule in `shared-rules.md`, which is about avoiding the *status* hues: a 3dp stripe down a row with
no status cannot be confused with a tinted badge in a fixed column.
