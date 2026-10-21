# Decisions — twins and API
Owned by `api-parity/`: the noop twins, the golden files and what `ApiSurface` can see. Imported by the `inspector-core`, `inspector-stream`, `api-parity` and three noop guides.

**Every real module needs a noop twin.** `core`→`noop`, `ui`→`noop-ui`, `stream`→`noop-stream`.
Adding a module the consuming app calls into, without its twin, breaks `-Pinspector=off`
compilation and pushes users into `if (BuildConfig.DEBUG)` guards around their own wiring —
which is the exact thing the noop artifacts exist to prevent.

"Every real module" means every *published* module a consumer calls into, and `NoopTwinTest` in
`:repo-checks` holds it in that meaning: the published set must equal the pairs in
`structure/twins.txt` plus its exemptions, each exemption with its reason. There is one.
`:inspector-model` needs no twin because both members of the core pair `api` it, so it ships in
either build and there is nothing to swap, and it holds no capture code. `:inspector-daemon`,
`:repo-checks` and the samples never publish, so the rule does not reach them. A check that read
"every module" literally would have failed on day one.

**A twin without a golden file drifts, and `stream` did for eleven releases.**
`StreamSink.lastError` existed in `:inspector-stream` from before v0.1.0 and never in
`:inspector-noop-stream`, so an app that surfaced the reason its daemon connection failed compiled
in debug and failed to compile under `-Pinspector=off` — the one failure the twins exist to
prevent, shipped by the module that prevents it. Nothing caught it because the golden file
covered the `core` pair only. There are now three:
`api/inspector-public-api.txt`, `api/inspector-stream-public-api.txt` and
`api/inspector-ui-public-api.txt`.

The stream pair needs a rule the core pair does not, because the two are **not** identical: the
real module exposes Ktor types the noop must never name, or a release build would carry a Ktor
dependency. So the contract is every public member whose signature names no Ktor type, and both
modules assert **equality** against it — the real one with Ktor-typed members filtered out. Equality
is the load-bearing word. A subset check on the real module would let it grow a member the noop
lacks and say nothing, which is the hole that was already open. `StreamApiParityTest` carries the
reasoning. The `ui` pair, the last gap of this shape, has had its own since phase I; see below.

**A matching public API is not a matching ABI: the JVM file facade is named for the *file*.**
A twin pair can declare byte-identical Kotlin signatures and still be binary-incompatible.
`defaultDaemonHost` and `defaultClientInfo` are `expect`/`actual` in `:inspector-stream`, so the
actuals landed in `Platform_jvmKt` and `Platform_androidKt`, named for `Platform.jvm.kt` and
`Platform.android.kt`. The noop has no platform split and declared them in `StreamSink.kt`,
giving `StreamSinkKt`. Same package, same signatures, same Kotlin call site, **different JVM
class** — so anything compiled against one and linked against the other dies at runtime on a
class that is not in the artifact. Source-compatible, binary-incompatible, and therefore invisible
to every check that reads source or public API. Kotlin/Native has no file facades, which is why
iOS never saw it and why it survived eleven releases. Reported from a consuming app.

The real module now pins `@file:JvmName("StreamSinkKt")` on both actuals. Pinning *to the noop's*
name rather than the reverse is deliberate: it leaves the published noop ABI untouched, and the
noop is what a release build links against. The noop cannot pin the same way — `kotlin.jvm.JvmName`
does not resolve in a common source set shared with Native, so `@file:JvmName` there fails
`compileKotlinIosArm64` with "Unresolved reference" — so **that file's name is load-bearing and
renaming `StreamSink.kt` is a breaking change.** Both files say so.

The guard is what holds it, and the previous guard *structurally could not have*: `ApiSurface`
dumped both facades under one shared label, `dev.inspector.stream (top-level)`, specifically so
the differing names would stop differing. It erased the only fact that mattered. `StreamSinkKt` is
now named in `STREAM_CONTRACT_CLASSES` like any other type, the golden file records it, and an
unresolvable contract class fails with the reason rather than a bare `ClassNotFoundException`.
Proven in both directions and on both modules: removing the pin fails `:inspector-stream:jvmTest`,
renaming the noop's file fails `:inspector-noop-stream:jvmTest`.

**A matching Kotlin signature is not a matching ABI either: the constructor descriptor is the
contract.** The facade fix above was the first half of this lesson; the constructor was the second,
and it had been shipping since well before either. `StreamSink`'s real constructor takes
`engineFactory: () -> HttpClient` **fourth**; the noop's took no such parameter. Same Kotlin call
site, different JVM descriptor, so a consumer compiling against the noop and linking the real
module died on `NoSuchMethodError <init>` at their first `StreamSink(...)`. Reproduced exactly:
compiling that call against the published `inspector-noop-stream:1.0.2` and running it against
`inspector-stream:1.0.2` throws; against the fix it constructs.

**It was not introduced in 1.0.2, and the report that arrived said it was.** 1.0.1's artifacts
carry the identical divergence — checked by `javap` on both published aars. What changed is that
the facade bug was crashing *one line earlier in the same function*, on `defaultClientInfo`, so
fixing the facade in 1.0.2 moved the crash from the call to the constructor and made a
long-standing break newly reachable. Worth remembering when a report blames the release it first
became visible in.

**The noop's parameter is `() -> Any?`, and that is the whole trick.** It must never name
`HttpClient`, or a release build carries Ktor — but it does not have to, because Kotlin erases
function-type arguments on the JVM: `() -> HttpClient` and `() -> Any?` are both
`Lkotlin/jvm/functions/Function0;`. Identical descriptor, no Ktor named, and source compatibility
comes free in the same direction because a return type is covariant. **Its position is the
contract, not its name** — keep it fourth.

The guard's own documentation was the bug's cover: `StreamApiParityTest` said in as many words that
"constructors are outside this guard", so nothing looked. `ApiSurface.jvmDescriptors` now dumps
public constructor and method descriptors beside the Kotlin signatures. Two things it must keep
doing. It asks `Method.kotlinFunction` for visibility, because a top-level `internal` function is
**not** name-mangled and `connectionHelp`, `base64Encode` and `isUtf8` otherwise arrive looking
exactly like public API — found by reading the generated golden file, not from a failing test. And
it skips the synthetic default-args constructor, which is the descriptor the crash actually named,
because it is derived mechanically from the primary: two matching primaries cannot yield differing
synthetics, and dumping synthetics would drag every `$default` bridge into the golden file.

**A constructor-only descriptor diff is the low-noise form of the rejected class-set diff, and it
is worth running by hand after a release.** Restricting the comparison to classes present in *both*
artifacts is what removes the noise measured below — every `dev.inspector.internal` class exists in
one module only, so it never enters the comparison. Run across all three pairs it answers "did this
diverge anywhere else" in one line each; it confirmed `StreamSink` was the only constructor
divergence in the tree.

**A raw class-set diff between twins was measured and rejected — it is noise, not a guard.**
The obvious generalisation is to diff every public class of each pair and fail on asymmetry. It
was built and run: **19** entries for the `core` pair, **19** for `ui`, **4** for `stream`, and
every one legitimate. Kotlin `internal` is JVM-public, so `Redactor`, `RingBuffer`, `CallState`
and the whole `dev.inspector.internal` package read as public API; `InspectorConfigKt` is
real-only *by design*, because the canary must be absent from the noop for
`check-release-clean.sh` to mean anything; `EmulatorDetectionKt` is genuinely real-only. The
facade mismatch this exists to catch would have been two lines inside nineteen, indistinguishable
from the rest without already knowing the answer — and an allowlist covering the rest needs
editing on every internal refactor, which is how a guard gets switched off. The check that works
asks Kotlin what is public rather than asking the JVM, which is what `ApiSurface` already does.
Do not rebuild the class-set diff without reading this paragraph.

**`PassThroughInterceptor` in `:inspector-noop` is not an asymmetry — it is package-private.**
It was reported as one, on the reasoning that it exists in the noop and not in `:inspector-core`
and so is the same bug waiting for a consumer to reference it. No consumer can: it is a
`private object` backing `okHttpInterceptor()`, and `javap` reports `final class` with no
`public` — package-private in bytecode and `private` in Kotlin metadata, unreachable from Kotlin
and from Java outside `dev.inspector`. The public surface is `okHttpInterceptor()` on
`OkHttpCaptureKt`, which both modules have and the golden file covers. Nothing to fix; check the
access flags before treating a class-name difference as a contract difference.

**`ApiSurface` only sees classes it is named.** It reflects over an explicit contract list, so a
new public declaration is unguarded until it is added there. Top-level *extension* functions need
the Java-reflection fallback as well — Kotlin's `declaredMemberFunctions` and `staticFunctions`
both miss them, and the guard reports a file facade as having no API at all. Prove any change to
this file catches divergence in both directions before trusting it.

Extending it to the stream pair turned up four more ways it could look right and check nothing,
all of them found by reading the generated golden file rather than by a failing test:

- **Java reflection reports `internal` as public.** Members of a class are name-mangled
  (`f$module`) and so are droppable by name, but **top-level `internal` functions are not
  mangled** — `connectionHelp` and `base64Encode` arrived looking exactly like public API. The
  name cannot tell; `Method.kotlinFunction` is asked instead, falling back to *keeping* the member
  when Kotlin reflection cannot answer, which is the extension-function case above.
- **The two modules compile top-level functions into different facades.** `defaultDaemonHost` is
  `expect`/`actual` in the real module, so its JVM actual lands in `Platform_jvmKt`; the noop has
  no platform split and gets `StreamSinkKt`. Under their own class names every top-level function
  reads as both missing and extra, so they are dumped under one label. At least one named facade
  must resolve, or the guard would compare an empty set and pass.
- **An enum's constants were invisible.** They are static fields, so no function or property list
  reported them and a twin could have dropped a case unnoticed. They are dumped explicitly; the
  generated `values`/`valueOf`/`getEntries` around them are not, carrying no contract.
- **`parameters.drop(1)` assumed an instance receiver.** A static function has none, so its first
  real parameter was being eaten — `valueOf(String)` rendered as `valueOf()`, a signature that
  would go on matching after the parameter it hid had changed. It now drops by `KParameter.Kind`.

None of these changed the `core` golden file by a byte, which is how they were confirmed to be
fixes to the reflection rather than to the surface.

**`:inspector-ui`'s public API is `InspectorOverlay` and nothing else; the palette and the theme
are `internal`.** `InspectorTheme`, `InspectorColors` and `LocalInspectorColors` were public in
every release up to 1.3.0 and never existed in `:inspector-noop-ui`, so an app theming against them
compiled in debug and failed under `-Pinspector=off` — the `lastError` failure again, latent only
because no document had told anyone to use them. Mirroring them was rejected: a release artifact
would carry the inspector's palette as dead data. They became `internal` by the maintainer's
decision of 2026-10-18, allowed in a minor release, with `INTEGRATION.md` v59 telling consumers to
copy the colours they need. Do not make any of them public again so an app can theme against the
overlay: that reopens the divergence, and `UiApiParityTest` fails if it happens.

**A `@Composable` function's golden entry is its declaration plus its full JVM descriptor, with
Compose's parameters left in the descriptor.** The Compose compiler appends `$composer`,
`$changed` and, when there are defaults, `$default` to the JVM method, so `InspectorOverlay` is
`(ZLkotlin/jvm/functions/Function2;Landroidx/compose/runtime/Composer;II)V`. That descriptor is
what a consumer's compiled call binds to, so stripping the added parameters would hide real breaks:
a twin that lost the default on `enabled` loses the trailing `I`. Both twins are built by the same
Compose compiler, so equal declarations give equal text, which is all a parity check needs. The
`fun` line comes from the Kotlin declaration instead, by one rule with no names in it: a facade
method whose JVM parameter list is longer than its declaration's had parameters added by the
compiler, so it is rendered from the declaration, with the names and defaults a named-argument call
site depends on and the Java form cannot show. Methods whose lists agree keep the Java form, which
is why the `core` and `stream` golden files did not change by a byte. A suspend function on a
facade takes the same path, for its `Continuation`. Proven by renaming `enabled` in the noop: only
the `fun` line failed.

**The `ui` pair also checks that every public declaration is in its contract, and that is not the
rejected class-set diff.** `ApiSurface` only sees classes it is named, so the theme types could go
public again, or a public composable land in a new file, with the golden test green.
`UiApiParityTest` therefore lists every class in the module's own JVM output that holds a
declaration Kotlin calls public — a class when it and every enclosing class are public, a file
facade when one of its package members is — and requires that set to equal `UI_CONTRACT_CLASSES`.
The class-set diff asked the JVM, where `internal` is public, and compared twin with twin; this asks
Kotlin metadata and compares one module with its own list. Measured on the ui pair before the theme
types went internal, it named exactly them and `InspectorOverlayKt`: no spurious entries, the
generated `Res` classes included, which are public classes nested in an `internal` one.
`Method.kotlinFunction` cannot do this job. One `internal` composable with a value-class parameter,
`inspectorScreen`, makes it throw for every method of its facade, so the facade's members are read
through `getOrCreateKotlinPackage` instead. The check is not yet applied to the `core` and `stream`
pairs.

**Both `InspectorOverlay.kt` files are load-bearing, and only their file name holds the facade.**
Consumers' compiled code calls `dev.inspector.ui.InspectorOverlayKt`. Neither module can pin it:
`InspectorOverlay` is in `commonMain` in both, where `@file:JvmName` fails `compileKotlinIosArm64`
as it does for `StreamSink.kt`. So renaming either file is a breaking change. `UI_CONTRACT_CLASSES`
names the facade and the golden file records it, and renaming either file fails that module's
`UiApiParityTest`. Proven by renaming each in turn.
