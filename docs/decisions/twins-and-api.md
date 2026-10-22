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
`api/inspector-ui-public-api.txt`, one per pair, each named on its pair's line in `structure/twins.txt`.

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
through `getOrCreateKotlinPackage` instead.

**Every pair runs it, not only `ui`.** The `core` and `stream` pairs got the same test on
2026-10-21, through one shared `ApiSurface.assertEveryPublicDeclarationListed`, and measured
before it was written, both modules of each pair held public API in exactly their contract
classes, so nothing was made internal and no golden file changed. It asks which classes hold
public API, not what they hold, so the stream pair's Ktor rule does not enter it:
`defaultStreamClient` passes because it sits on the contract facade `StreamSinkKt`. A new class
or file holding only Ktor-typed API fails, and has to: it cannot be added to
`STREAM_CONTRACT_CLASSES`, because the noop would then fail on a class it does not have.

**A golden file is a declared `jvmTest` input of both modules that read it.** The test reads it
from outside the module at run time, so nothing else tells Gradle it matters, and a hand-edited
golden file left `jvmTest` up to date, or a cache hit, and green. Measured with the input removed
from `:inspector-noop` and `:inspector-stream`. The declaration is `inputs.files`: with Gradle
9.5.1, `inputs.file(...).optional()` still rejects a file that does not exist, and the first
regeneration runs before it does.

**`structure/twins.txt` names each pair's golden file, and `NoopTwinTest` fails a pair that names
none or one that does not exist.** Listing pairs without their files is how the ui pair went
eleven releases with no golden file and nothing failing. The test checks that the file exists,
not what is in it; the parity tests own the contents.

**Both `InspectorOverlay.kt` files are load-bearing, and only their file name holds the facade.**
Consumers' compiled code calls `dev.inspector.ui.InspectorOverlayKt`. Neither module can pin it:
`InspectorOverlay` is in `commonMain` in both, where `@file:JvmName` fails `compileKotlinIosArm64`
as it does for `StreamSink.kt`. So renaming either file is a breaking change. `UI_CONTRACT_CLASSES`
names the facade and the golden file records it, and renaming either file fails that module's
`UiApiParityTest`. Proven by renaming each in turn.

**The iOS surface is read from the klib by the toolchain's own `klib dump-abi`; Android asserts the
JVM golden file in its host test.** Every parity test above reads JVM output, so a public declaration in
one module's iOS or Android source alone passed every pair. Each pair's module now runs `iosApiTest`,
which runs `bin/klib dump-abi` from the Kotlin/Native distribution every iOS compile already provisions
in `~/.konan`, on both iOS targets' klibs, and asserts the result against the pair's iOS golden file
(`api/*.ios.txt`, the fourth column of `structure/twins.txt`). The distribution is found by the
compiler's version from `libs.versions.kotlin`, never `KotlinVersion.CURRENT`, which is the stdlib's
2.3.21 and names no distribution. KGP's `abiValidation {}` was rejected: it is experimental, it
resolves `abi-tools`, `kotlin-metadata-jvm`, `kotlin-klib-abi-reader` and the third-party
`java-diff-utils`, none of them in the Gradle cache, and it checks a module against its own dump, not a
twin against its twin. The maintainer's proxy decided on 2026-10-22 that the provisioned binary is no
new tool.

Three normalisation rules, each proven load-bearing by removing it. **Every member is flattened to
`<enclosing declarations>: <declaration>`,** because the golden file is compared as a set and an
unqualified `final fun stop()` moved from `StreamSink` into `StreamState` passed without it.
**`$stableprop` lines are dropped by that name:** the Compose compiler makes a `…$stableprop` and a
`…$stableprop_getter` public for each class it infers stability for, `internal` ones included, 26 lines
in `:inspector-ui` and none in the noop; a general `$` rule would swallow the next kind of compiler
noise instead of failing on it. **In the real stream module only, a Ktor type inside
`kotlin/FunctionN<…>` reads as `kotlin/Any?`, then every line naming Ktor is dropped.** Native does not
erase function-type arguments as the JVM does, so the real constructor's `Function0<HttpClient>` meets
the noop's `Function0<Any?>` only at source level: their IR signatures differ, and a klib compiled
against one twin and linked against the other is not covered. The proxy accepted that, since an iOS
release build recompiles from source. The dumps carry no parameter names, so a renamed parameter in
iOS-only code passes; common code keeps the JVM golden file's names. Both iOS targets must dump the same
surface, so a declaration in `iosArm64Main` or `iosSimulatorArm64Main` alone fails on its own message. Pinned by the six `iosApiTest` tasks.

**In `:inspector-core` and `:inspector-noop`, `src/iosMain` is not compiled.** Their hand-made
`jvmAndAndroidMain` turns the default hierarchy template off, so no `iosMain` source set exists and a
file there, even one that is not Kotlin, builds clean and ships nowhere. Their iOS-only code would go in
`iosArm64Main` and `iosSimulatorArm64Main`, which is where the red proof put its probe.

Android's output is JVM bytecode, so each pair's two tests also run in `testAndroidHostTest`, against
the pair's JVM golden file, read only: `inspector.api.regenerate` reaches `jvmTest` alone. Every platform
split that reaches Android also reaches the JVM (`jvmAndAndroidMain`, and `@file:JvmName("StreamSinkKt")`
on both `Platform.android.kt` and `Platform.jvm.kt`), so the two surfaces are equal by construction, and
this is the first guard on the Android facade pin. The first Android-only API added to both twins splits
the file then. Pinned by the six `testAndroidHostTest` tasks.
