package dev.inspector

import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile
import kotlin.jvm.internal.Reflection
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.staticFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.kotlinFunction
import kotlin.test.fail

/**
 * Reflects a module's public API into stable, sorted signature strings.
 *
 * This source directory is compiled into the `jvmTest` source set of six modules — the
 * `:inspector-core`/`:inspector-noop`, `:inspector-stream`/`:inspector-noop-stream` and
 * `:inspector-ui`/`:inspector-noop-ui` pairs. Each module asserts its own surface against the golden file its pair shares, which is
 * what keeps twins from drifting: they cannot be compared to each other directly, because they
 * declare the same fully-qualified names and so can never sit on one classpath together.
 */
object ApiSurface {

    /** Classes forming the `:inspector-core` contract. Keep in sync with its golden file. */
    val CONTRACT_CLASSES: List<String> = listOf(
        "dev.inspector.Inspector",
        "dev.inspector.InspectorConfig",
        "dev.inspector.InspectorSink",
        "dev.inspector.SignalPolicy",
        "dev.inspector.Redaction",
        "dev.inspector.Redaction\$Off",
        "dev.inspector.Redaction\$On",
        "dev.inspector.Redaction\$Companion",
        // The JVM/Android-only OkHttp entry point. Listed explicitly because this guard only
        // sees classes it is told about: a top-level function added to one module and forgotten
        // in the other would otherwise drift unnoticed, which is the exact failure this file
        // exists to prevent.
        "dev.inspector.OkHttpCaptureKt",
    )

    /**
     * Classes forming the `:inspector-stream` contract. Keep in sync with its golden file.
     *
     * `StreamSinkKt` is a **file facade**, and listing it by name is the point rather than an
     * implementation detail leaking in. Top-level functions compile into a class named for the
     * file that declares them, so `defaultDaemonHost` and `defaultClientInfo` landed in
     * `Platform_jvmKt`/`Platform_androidKt` in the real module and `StreamSinkKt` in the noop —
     * same Kotlin API, different JVM class, which is a runtime break for anything compiled
     * against one and linked against the other. Both modules now pin `@file:JvmName`, and naming
     * the facade here is what holds them to it: change either side and this list stops resolving.
     */
    val STREAM_CONTRACT_CLASSES: List<String> = listOf(
        "dev.inspector.stream.StreamSink",
        "dev.inspector.stream.StreamState",
        "dev.inspector.stream.ReplaySigner",
        "dev.inspector.stream.StreamSinkKt",
    )

    /**
     * Classes forming the `:inspector-ui` contract. Keep in sync with its golden file.
     *
     * One file facade and nothing else: `InspectorOverlay` is the module's whole public API, and
     * the palette and theme it draws with are `internal` (decided 2026-10-18; see
     * `docs/decisions/twins-and-api.md`). `InspectorOverlayKt` is named for the file in *both*
     * modules, and neither can pin it — `@file:JvmName` does not resolve in a common source set
     * shared with Native — so the file name is the ABI, and naming the facade here is what checks
     * it. `UiApiParityTest` also fails if any other class in the module holds public API.
     */
    val UI_CONTRACT_CLASSES: List<String> = listOf(
        "dev.inspector.ui.InspectorOverlayKt",
    )

    /**
     * @param classes types that must be present; a missing one fails loudly rather than shrinking
     *   the surface being compared. File facades belong in this list like anything else — dumping
     *   them under a shared label instead is what hid the `Platform_jvmKt`/`StreamSinkKt` split
     *   for eleven releases, because a label both modules agreed on erased the one fact that
     *   differed.
     */
    fun dump(
        classes: List<String> = CONTRACT_CLASSES,
        skipKtorTyped: Boolean = false,
    ): List<String> = classes.flatMap { name ->
        val klass = runCatching { Class.forName(name).kotlin }.getOrElse {
            fail(
                "Contract class $name is not on this module's classpath, so its members would " +
                    "not be compared at all.\nIf it is a file facade (a name ending Kt), the " +
                    "file declaring those top-level functions was renamed or moved: the facade " +
                    "name is part of the ABI, so rename the file back, or pin it with " +
                    "@file:JvmName where the source set allows it (a common source set shared " +
                    "with Native does not) — never update this list to follow it. A twin pair " +
                    "must compile top-level declarations into the same JVM class or a release " +
                    "swap breaks at runtime."
            )
        }
        signaturesOf(klass, skipKtorTyped).map { "$name: $it" }
    }.distinct().sorted()

    private fun signaturesOf(klass: KClass<*>, skipKtorTyped: Boolean): List<String> {
        // staticFunctions is what surfaces top-level declarations on a file facade class such as
        // OkHttpCaptureKt; declaredMemberFunctions alone reports a facade as having no API at
        // all, which would let the guard pass while seeing nothing.
        val functions = (klass.declaredMemberFunctions + klass.staticFunctions)
            .filter { it.visibility == KVisibility.PUBLIC }
            .filterNot { klass.java.isEnum && it.name in ENUM_SYNTHETICS }
            .map(::renderDeclared)

        val properties = klass.declaredMemberProperties
            .filter { it.visibility == KVisibility.PUBLIC }
            .map { prop ->
                prop.isAccessible = true
                val mutable = prop is kotlin.reflect.KMutableProperty<*>
                val setter = if (mutable) {
                    val setterVisibility = (prop as kotlin.reflect.KMutableProperty<*>)
                        .setter.visibility
                    if (setterVisibility == KVisibility.PUBLIC) " (public set)" else " (restricted set)"
                } else ""
                "${if (mutable) "var" else "val"} ${prop.name}: ${prop.returnType}$setter"
            }

        // An enum's constants are its whole contract, and nothing above reports them: they are
        // static fields, so neither declaredMemberProperties nor either function list sees one.
        // Without this a noop twin could drop a case and the guard would notice nothing.
        val constants = (klass.java.enumConstants ?: emptyArray<Any>())
            .map { "enum constant ${(it as Enum<*>).name}" }

        return (functions + properties + constants + staticJavaSignatures(klass) + jvmDescriptors(klass))
            // Applied to the *raw* signature, before [render] strips package prefixes and takes
            // `io.ktor.client.HttpClient` down to `HttpClient`. Filtering after rendering would
            // silently match nothing, which is the passing-guard-that-checks-nothing failure.
            .filterNot { skipKtorTyped && (it.contains(KTOR_PACKAGE) || it.contains(KTOR_PACKAGE_JVM)) }
            .map(::render)
            .distinct()
            .sorted()
    }

    private fun renderDeclared(fn: KFunction<*>): String {
        val params = fn.parameters
            // Only a real receiver. A static function has none, and dropping its first
            // parameter regardless rendered `valueOf()` as taking nothing — a signature
            // that would go on matching after the parameter it hides had changed.
            .filterNot { it.kind == KParameter.Kind.INSTANCE || it.kind == KParameter.Kind.EXTENSION_RECEIVER }
            .joinToString(", ") { p ->
                val optional = if (p.isOptional) " = ..." else ""
                "${p.name}: ${p.type}$optional"
            }
        return "fun ${fn.name}($params): ${fn.returnType}"
    }

    /**
     * Public static methods, read through Java reflection.
     *
     * Needed for top-level *extension* functions on a file facade — `fun Inspector.foo()` in
     * OkHttpCapture.kt appears in neither `declaredMemberFunctions` nor `staticFunctions`, so
     * Kotlin reflection alone reports the facade as having no API and the guard silently checks
     * nothing. Parameter names are unavailable here, which is fine: both modules render this
     * identically, and identical is the whole test.
     */
    private fun staticJavaSignatures(klass: KClass<*>): List<String> =
        klass.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) }
            .filterNot { it.isSynthetic }
            // Kotlin `internal` is JVM-public, so Java reflection reports it as API where Kotlin
            // reflection correctly does not. Members of a class are name-mangled (`f$module`);
            // top-level functions are not, so the name alone cannot tell — `connectionHelp` and
            // `base64Encode` both arrived here looking exactly like public API. Ask Kotlin what
            // it is, and fall back to keeping it when Kotlin reflection cannot say, which is the
            // case for extension functions on a file facade such as `okHttpInterceptor`.
            .filterNot { it.name.contains('$') }
            .filterNot { klass.java.isEnum && it.name in ENUM_SYNTHETICS }
            .mapNotNull { method ->
                val asKotlin = runCatching { method.kotlinFunction }.getOrNull()
                when {
                    asKotlin != null && asKotlin.visibility != KVisibility.PUBLIC -> null
                    // The JVM method has parameters its Kotlin declaration does not: the compiler
                    // added them. For a @Composable that is Compose's $composer, $changed and
                    // $default; for a suspend function, its Continuation. Render the declaration —
                    // names and defaults included, which a named-argument call site depends on and
                    // the Java form cannot show. The `jvm` line from [jvmDescriptors] keeps every
                    // parameter, because that is what a compiled call binds to. One rule, no names:
                    // methods whose lists agree keep the Java form, so the core and stream golden
                    // files did not change by a byte.
                    asKotlin != null && method.parameterCount > asKotlin.parameters.size ->
                        renderDeclared(asKotlin)
                    else -> {
                        val params = method.parameterTypes.joinToString(", ") { it.name }
                        "fun ${method.name}($params): ${method.returnType.name}"
                    }
                }
            }

    /**
     * Public **JVM descriptors** for constructors and methods — the bytes a call site binds to.
     *
     * Everything above this compares *Kotlin* signatures, and that is not the same contract. A
     * twin pair can agree on every Kotlin signature and still be binary-incompatible, because the
     * JVM descriptor carries things Kotlin's does not: parameter *position*, the synthetic
     * default-args overload and its `DefaultConstructorMarker`, and erasure.
     *
     * This exists because that gap shipped. `StreamSink`'s real constructor takes
     * `engineFactory: () -> HttpClient` fourth and the noop's did not take it at all, so a
     * consumer compiling against the noop and linking the real module died on
     * `NoSuchMethodError <init>` at their first `StreamSink(...)`. Constructors were explicitly
     * out of scope for this guard — `ApiSurface` reflected functions and properties only, and
     * `StreamApiParityTest` said so in as many words — so nothing looked. Reported from a
     * consuming app, twice: the facade split fixed in 1.0.2 was crashing one line earlier in the
     * same function and masking this.
     *
     * **Erasure is what makes the Ktor filter correct here rather than a hole.** `() ->
     * HttpClient` erases to `Lkotlin/jvm/functions/Function0;`, which names no Ktor type, so this
     * comparison sees the parameter and the twin must match it. A member that genuinely cannot be
     * mirrored — `defaultStreamClient()Lio/ktor/client/HttpClient;` — still carries Ktor in its
     * descriptor and is still filtered. The filter therefore drops exactly what a release build
     * cannot name and keeps exactly what it can.
     */
    private fun jvmDescriptors(klass: KClass<*>): List<String> {
        // The *primary* constructors only. Kotlin marks the default-args overload
        // `(params…, int, DefaultConstructorMarker)` synthetic, and that is the descriptor the
        // reported crash actually named — but it is derived mechanically from this one, so two
        // matching primaries cannot produce differing synthetics. Checking the primary is
        // therefore sufficient, and skipping synthetics keeps every `$default` bridge in the
        // codebase out of the golden file.
        val ctors = klass.java.declaredConstructors
            .filter { Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic }
            .map { "jvm <init>${descriptorOf(it.parameterTypes, "V")}" }

        val methods = klass.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic }
            // Kotlin mangles `internal` members of a class as `name$module`; those are not API.
            .filterNot { it.name.contains('$') }
            // ...but a top-level `internal` function is NOT mangled, so the name cannot tell.
            // `connectionHelp`, `base64Encode` and `isUtf8` arrived here looking exactly like
            // public API and would have failed the noop, which rightly does not have them. Ask
            // Kotlin, keeping the member when Kotlin reflection cannot answer — the same rule and
            // the same reason as [staticJavaSignatures].
            .filterNot { method ->
                val asKotlin = runCatching { method.kotlinFunction }.getOrNull()
                asKotlin != null && asKotlin.visibility != KVisibility.PUBLIC
            }
            .filterNot { klass.java.isEnum && it.name in ENUM_SYNTHETICS }
            .map { "jvm ${it.name}${descriptorOf(it.parameterTypes, jvmType(it.returnType))}" }

        return ctors + methods
    }

    /**
     * Every class in the module that declares [anchor] holding a declaration *Kotlin* calls public,
     * mapped to what it holds — the completeness half of a parity check.
     *
     * [dump] compares only the classes it is named, so public API anywhere else is invisible to the
     * golden file: the ui theme types could go public again, or a public composable land in a new
     * file, and the golden test would stay green. This finds them. It is not the class-set diff
     * rejected in `docs/decisions/twins-and-api.md`: that asked the JVM, where `internal` is public,
     * and compared twin against twin; this asks Kotlin metadata and compares one module with its
     * own list. A class counts when it and every class enclosing it are public or protected — the
     * generated `Res.string` is public inside an internal `Res` and must not count. A file facade
     * counts when one of its package members is public, read through `getOrCreateKotlinPackage`.
     * `Method.kotlinFunction` cannot do this: one internal composable with a value-class parameter
     * (`inspectorScreen`) makes it throw for every method of its facade.
     *
     * Reads only the directory or jar [anchor] was loaded from, so dependencies on the test
     * classpath are not reported. It cannot pass by scanning nothing: the caller compares the
     * result for equality with a non-empty contract.
     */
    fun publicHolders(anchor: String): Map<String, List<String>> {
        val anchorClass = runCatching { Class.forName(anchor) }.getOrElse {
            fail("Contract class $anchor is not on this module's classpath; the golden-file test's failure says why.")
        }
        val root = File(anchorClass.protectionDomain.codeSource.location.toURI())
        val holders = sortedMapOf<String, List<String>>()
        for (name in classNamesIn(root)) {
            val klass = Class.forName(name, false, anchorClass.classLoader)
            val metadata = klass.getAnnotation(Metadata::class.java) ?: continue
            when (metadata.kind) {
                KIND_CLASS -> if (effectivelyPublic(klass)) holders[name] = listOf("class")
                KIND_FILE_FACADE -> {
                    val members = runCatching { Reflection.getOrCreateKotlinPackage(klass).members }.getOrElse {
                        fail("Cannot list the declarations of file facade $name, so cannot tell whether it holds public API: $it")
                    }
                    val public = members.filter { it.visibility == KVisibility.PUBLIC }.map { it.name }.sorted()
                    if (public.isNotEmpty()) holders[name] = public
                }
                KIND_MULTIFILE_FACADE, KIND_MULTIFILE_PART -> fail(
                    "$name is a @JvmMultifileClass facade, which publicHolders does not read. Extend it before a twin module uses one."
                )
                // Kind 3 is a synthetic class — a lambda, WhenMappings, ComposableSingletons. Never API.
            }
        }
        return holders
    }

    private fun effectivelyPublic(klass: Class<*>): Boolean =
        generateSequence(klass) { it.enclosingClass }.all { c ->
            // Null for a local or anonymous class: not API. A throw is a class Kotlin cannot
            // describe, and a guard that cannot tell must say so rather than skip it.
            val visibility = runCatching { c.kotlin.visibility }.getOrElse {
                fail("Cannot ask Kotlin whether ${c.name} is public: $it")
            }
            visibility == KVisibility.PUBLIC || visibility == KVisibility.PROTECTED
        }

    private fun classNamesIn(root: File): List<String> =
        if (root.isDirectory) {
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
                .map { it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.') }
                .toList()
        } else {
            JarFile(root).use { jar ->
                jar.entries().toList().map { it.name }
                    .filter { it.endsWith(".class") && !it.startsWith("META-INF/") }
                    .map { it.removeSuffix(".class").replace('/', '.') }
            }
        }

    // kotlin.Metadata.kind values.
    private const val KIND_CLASS = 1
    private const val KIND_FILE_FACADE = 2
    private const val KIND_MULTIFILE_FACADE = 4
    private const val KIND_MULTIFILE_PART = 5

    private fun descriptorOf(params: Array<Class<*>>, ret: String): String =
        params.joinToString("", prefix = "(", postfix = ")$ret") { jvmType(it) }

    private fun jvmType(c: Class<*>): String = when {
        c == Void.TYPE -> "V"
        c == Integer.TYPE -> "I"
        c == java.lang.Long.TYPE -> "J"
        c == java.lang.Boolean.TYPE -> "Z"
        c == java.lang.Byte.TYPE -> "B"
        c == Character.TYPE -> "C"
        c == java.lang.Short.TYPE -> "S"
        c == java.lang.Float.TYPE -> "F"
        c == java.lang.Double.TYPE -> "D"
        c.isArray -> "[" + jvmType(c.componentType)
        else -> "L" + c.name.replace('.', '/') + ";"
    }

    /**
     * Normalises type strings so the two modules produce identical text. Kotlin renders some
     * types with platform-specific nullability markers that are noise for this comparison.
     */
    private fun render(type: String): String {
        var out = type.replace("!", "")
        // Longest-first, so kotlin.text.Regex does not get truncated to text.Regex.
        for (prefix in STRIPPED_PREFIXES) out = out.replace(prefix, "")
        return out
    }

    /** Generated by the compiler for every enum; carries no contract of its own. */
    private val ENUM_SYNTHETICS = setOf("values", "valueOf", "getEntries")

    private const val KTOR_PACKAGE = "io.ktor."

    /** The same package as a JVM descriptor spells it. See [jvmDescriptors] on why this matters. */
    private const val KTOR_PACKAGE_JVM = "io/ktor/"

    private val STRIPPED_PREFIXES = listOf(
        "kotlin.collections.",
        "kotlin.text.",
        "kotlinx.coroutines.flow.",
        "dev.inspector.model.",
        "io.ktor.client.",
        "kotlin.",
    )
}

/**
 * The golden-file half of a parity check: locate, compare, and regenerate on demand.
 *
 * Shared by both pairs so there is one implementation of "what does a drift look like", rather
 * than a second copy that can quietly stop agreeing with the first.
 */
object GoldenSurface {

    fun fileAt(relativePath: String): File {
        // Walk up from the module dir to the repo root, which holds api/.
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.exists() || File(dir, "settings.gradle.kts").exists()) return candidate
            dir = dir.parentFile
        }
        fail("could not locate repo root from ${System.getProperty("user.dir")}")
    }

    /**
     * @param regenerateWith the Gradle command that rewrites this golden file, quoted back to
     *   whoever the failure lands on — the fix is never guessable from the diff alone.
     */
    fun assertMatches(
        actual: List<String>,
        goldenPath: String,
        header: String,
        why: String,
        regenerateWith: String,
    ) {
        val goldenFile = fileAt(goldenPath)

        if (System.getProperty("inspector.api.regenerate") == "true") {
            goldenFile.parentFile.mkdirs()
            goldenFile.writeText(header + actual.joinToString("\n") + "\n")
            println("Regenerated ${goldenFile.path} with ${actual.size} entries")
            return
        }

        if (!goldenFile.exists()) {
            fail("Golden API file missing at ${goldenFile.path}. Generate it with:\n  $regenerateWith")
        }

        val expected = goldenFile.readLines().filterNot { it.startsWith("#") || it.isBlank() }

        val missing = expected - actual.toSet()
        val extra = actual - expected.toSet()
        if (missing.isEmpty() && extra.isEmpty()) return

        fail(
            buildString {
                appendLine("Public API differs from ${goldenFile.path}.")
                appendLine(why)
                if (missing.isNotEmpty()) {
                    appendLine("\nMissing from this module (${missing.size}):")
                    missing.forEach { appendLine("  - $it") }
                }
                if (extra.isNotEmpty()) {
                    appendLine("\nPresent here but not in the golden file (${extra.size}):")
                    extra.forEach { appendLine("  + $it") }
                }
                appendLine("\nIf this change is intended, mirror it in the twin module and run:\n  $regenerateWith")
            }
        )
    }
}
