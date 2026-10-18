# Decisions — overlay
Owned by `:inspector-ui`. Imported by the `inspector-ui` guide.

**`InspectorBackHandler` is `expect`/`actual`, not Compose Multiplatform's `BackHandler`.** Do not
"simplify" it back. CMP's common `BackHandler` resolves a dispatcher owner from the composition
and calls `error(...)` when there is none — so on any host that does not provide one, installing
the overlay crashes the app at startup. That is an unacceptable failure mode for a debugging aid
that wraps somebody's entire root composable. It is also `@Deprecated` in 1.11.1, pointing at
`NavigationEventHandler` in navigationevent-compose, which is at `1.1.0-beta01` — this module does
not push a beta transitive dependency onto consumers. So: Android uses `androidx.activity`'s
handler (guaranteed by `ComponentActivity`), desktop and iOS no-op, because neither has a system
back gesture for a full-screen overlay.

**The overlay's controls are one scrollable strip, not a row each.** *(Overturned by the design
pass: the list's chrome is now three bars — header, filter row, scope-and-now line — and everything
the strip held moved into the filter row or a filters sheet. The reasoning below is kept because it
is still why nothing got a row of its own.)* A phone is 360dp by about
720; the header, the filter field and the scope bar already spend four lines before any traffic
appears. The order toggle, the freeze control, the quick filters, the marker chips and the endpoint
chips each getting a row would have spent four more. One horizontally scrollable strip trades
horizontal space, which is not scarce, for vertical space, which is. Order is deliberate: the two
view toggles lead because they never move and never grow, then markers when there are any — a
marker exists only because somebody dropped one, so it has earned the forward position — then the
four presets, then endpoints. The toggles are muted and the filter chips are accent-tinted, because
one changes how the list is *arranged* and the other changes what it *contains*, and a strip that
made them look alike would invite tapping `newest first` expecting fewer rows.

**The list's chrome is three bars, and the filters sheet types into the field.** Header (title,
count, `Mark`, `⋮`, a filled `✕`), filter row (field, `filters`, an order arrow, `live`/`frozen`),
and one line carrying the scope and the now glance. The first row moved from about 230dp down to
about 130dp. Freeze stayed in the filter row rather than the sheet: it is reached for while rows
arrive, and a control behind a sheet is one the rows you meant to hold scroll past.

The sheet edits the *same* filter text, one term at a time (`hasTerm`, `replaceTerm`,
`removeTerm`), and leaves anything typed by hand alone. **One choice per section, sections ANDed** —
that is the grammar's own shape: whitespace ANDs, `|` binds loosest, and there are no parentheses,
so an OR inside a section would need a rewrite into disjunctive form that no reader could check
in the field. The menu and the sheet are drawn in the overlay's own tree, not as `Popup` or
`ModalBottomSheet` windows, for the same reason `InspectorBackHandler` is hand-rolled; they sit
outside the inset padding so the scrim covers the status bar and the sheet reaches the bottom edge.

**The overlay's detail screen opens on the response, and remembers the tab across rows.** Tabs
read Response · Request · Overview; the choice is held by `InspectorOverlay`, like the list's
arrangement, because comparing one field down a list means picking the same tab on every row
otherwise — the web does the same. Within a tab the body comes before the headers, as on the web.
A header named in `redacted` reads `redacted at capture` in the warning colour rather than showing
the placeholder as though it were the value sent. Copy cURL and Copy URL moved into the header's
`⋮` menu, and each says `Copied` before closing, because iOS and desktop show nothing on a copy.

**The empty list says which of three things is true.** Nothing has arrived (instructions, naming
both the Ktor plugin and the OkHttp interceptor, and `Pull signals now` only where a provider
exists); a filter hid every call (`Clear filter`); or there are no calls but signals are hidden —
which used to read as nothing at all, and now offers to show them.

**The overlay's JSON tree is a `Column`, and copy never reads it.** It sits inside the detail
screen's own vertical scroll, where a `LazyColumn` gets unbounded height and throws — and a
fixed-height one would be a scroll inside a scroll, the gesture nobody can aim on a phone. So every
row is composed, and a body over 400 rows opens folded below depth 2 (the web's rule, at a lower
threshold). Copy and raw are handed the text the screen already showed, from the hand-rolled
`prettyJson` that keeps the body byte for byte; the tree is parsed with kotlinx.serialization,
which keeps number literals and key order but only the last of a duplicated key, which is why it
is never the source of a copy. Collapse and raw only — the web's focus and hide need a hover.
Folding is held per body by the overlay (`JsonTreeStates`), and a truncated body is never a tree.

**`TextOverflow.StartEllipsis` does not work on iOS, and `StartEllipsisText` exists because of it.**
On the skiko renderer — desktop *and iOS* — Start and Middle ellipsis fall back to an end
ellipsis. Measured: `/v3/some-service/accounts/balance` at 150dp renders `/v3/some-service/ac…`
under all three modes. Every path, signal name and scope in the overlay was written against
StartEllipsis because the tail names the endpoint, so on an iPhone every one of them had been
cutting off exactly that. Android's platform text honours it, which is why nobody saw it.
`StartEllipsisText` fits the longest tail by measurement (`fitTail`), so the string drawn is the
string in the semantics tree; `a long path keeps the endpoint and loses the front` runs on the
renderer that got it wrong and fails with the built-in overflow put back. Do not "simplify" a call
site back to `overflow = StartEllipsis`.

**Freezing holds a snapshot, not a flag.** Capture keeps running while the list is held and the
ring keeps evicting, so a freeze that only stopped redrawing would let the reader's rows be
replaced underneath them — exactly what they froze the list to prevent. Everything downstream
reads the frozen rows and markers, including the header count, the scope bar and the context: a
list frozen in some places and live in others is frozen in the one way nobody wants. Rows held past
their eviction can no longer fetch a body, which is honest and visible rather than silent.

**`:inspector-ui` has interaction tests, and `compose.uiTest` is jvmTest-only.** A toggle's whole
behaviour is the transition between two states: `ListRenderTest` photographs one and can never
show the step, and the state lives inside the composable where no unit test reaches it.
`ListControlsTest` taps the chips instead. Two rules learned writing it. Select a sheet chip with
`sheetChip(label)` — `hasText(…) and isToggleable()` — never by text alone, and not by
`hasClickAction()` either: a marker named `checkout` is on screen twice by design, and a tag chip
named `cache` shares its text with every cache signal row, which is clickable too. The chips are
`toggleable` with a checkbox role, which is also what makes a screen reader say whether one is on.
The order control is an arrow, found by `orderControl(newestFirst)` through its description. And
assert the *rows move*, not that the label flipped: a toggle wired to nothing would pass an
assertion about its own text.
Like `compose.desktop.currentOs`, this dependency must never reach a main source set.

**The overlay's wall clock converts to local time through an `expect`/`actual` offset, not a
datetime library.** `ts` is ISO-8601 UTC and the entire point of the column is lining a row up
against logcat or a backend log, which are in the reader's zone — a UTC column would be correct
and useless. `kotlin.time` has no zone support and `kotlinx-datetime` would be a transitive
dependency on every consumer of `:inspector-ui` to render eight characters, so
`localUtcOffsetSeconds()` is
three one-line actuals instead. On iOS it needs `import platform.Foundation.secondsFromGMT`: that
property comes from the `NSExtendedTimeZone` category, which Kotlin/Native surfaces as a
package-level extension rather than a member, so it does not resolve without the import.

`formatClock` takes the offset as a defaulted parameter so the conversion is testable at a fixed
offset rather than at whatever zone the build machine is in. Two things it must keep doing: `mod`
and not `%`, or an offset west of Greenwich produces a negative hour; and rejecting a 60th second
rather than allowing it, because the arithmetic wraps `23:59:60` silently to `00:00:00` — a wrong
time shown confidently instead of `--:--:--` admitting it could not read one. Unix time has no leap
second, so this only ever means malformed input. `LocalOffsetJvmTest` cross-checks the JVM actual
against `java.time`, because the common test can only ask whether the number is plausible and a
stub returning `0` is plausible in London.

**A repeat says `callCount`, never `ids.size`.** A duplicate group that swept up a retry attempt
holds more rows than calls, and the reader is asking how many times the app asked — not how many
lines the list drew. `repeatLabel` is `internal` purely so a test can pin that; the two read as
interchangeable until a retry lands in a group.

**The overlay pill insets itself too, and that was missed for a year.** `Modifier.inspectorScreen`
fixed the *screens* after the first device outing; the pill kept a raw `offset` starting at 120
**pixels** with drag bounds of `coerceIn(0f, …)`. On Android at that density 120px clears a status
bar, so it looked right. At 3x on an iPhone it is 40pt — inside the Dynamic Island, where the
system takes the touch and the overlay cannot be opened at all. The pill's frame is now
`windowInsetsPadding(safeDrawing)`, so both the default position and the drag bounds mean the safe
edge. Verified by tapping the default position before the fix (nothing) and after (opens).

**Overlay screens inset themselves; the host is not asked to.** `Modifier.inspectorScreen` applies
`background` *before* `windowInsetsPadding(safeDrawing)`, so colour bleeds edge to edge while
content stays clear of the status bar, cutout, nav bar and keyboard. Reversing that order leaves a
strip of the host app visible behind the status bar, which reads as a rendering bug. Non
edge-to-edge hosts report zero insets, so this is a no-op there rather than a double margin.

**`:inspector-ui`'s render test is JVM-only, and needs a dependency that must never ship.**
`ListRenderTest` draws the real list through `ImageComposeScene`, which needs skiko's native
runtime — `compose.ui` carries the API but not the binary. It is declared as
`jvmTest.dependencies { implementation(compose.desktop.currentOs) }`, test-only and never
published. Do not promote it to a main source set to "fix" a missing class, and do not add it to
`commonTest`: the iOS and Android test compilations have no use for it.
