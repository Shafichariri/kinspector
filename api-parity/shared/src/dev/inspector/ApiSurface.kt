package dev.inspector

import java.io.File
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
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
 * This source directory is compiled into the `jvmTest` source set of four modules — the
 * `:inspector-core`/`:inspector-noop` pair and the `:inspector-stream`/`:inspector-noop-stream`
 * pair. Each module asserts its own surface against the golden file its pair shares, which is
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
                    "name is part of the ABI, so pin it with @file:JvmName rather than updating " +
                    "this list — a twin pair must compile top-level declarations into the same " +
                    "JVM class or a release swap breaks at runtime."
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
            .map { fn ->
                val params = fn.parameters
                    // Only a real receiver. A static function has none, and dropping its first
                    // parameter regardless rendered `valueOf()` as taking nothing — a signature
                    // that would go on matching after the parameter it hides had changed.
                    .filterNot { it.kind == KParameter.Kind.INSTANCE || it.kind == KParameter.Kind.EXTENSION_RECEIVER }
                    .joinToString(", ") { p ->
                        val optional = if (p.isOptional) " = ..." else ""
                        "${p.name}: ${p.type}$optional"
                    }
                "fun ${fn.name}($params): ${fn.returnType}"
            }

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

        return (functions + properties + constants + staticJavaSignatures(klass))
            // Applied to the *raw* signature, before [render] strips package prefixes and takes
            // `io.ktor.client.HttpClient` down to `HttpClient`. Filtering after rendering would
            // silently match nothing, which is the passing-guard-that-checks-nothing failure.
            .filterNot { skipKtorTyped && it.contains(KTOR_PACKAGE) }
            .map(::render)
            .distinct()
            .sorted()
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
            .filterNot { method ->
                val asKotlin = runCatching { method.kotlinFunction }.getOrNull()
                asKotlin != null && asKotlin.visibility != KVisibility.PUBLIC
            }
            .filterNot { klass.java.isEnum && it.name in ENUM_SYNTHETICS }
            .map { method ->
                val params = method.parameterTypes.joinToString(", ") { it.name }
                "fun ${method.name}($params): ${method.returnType.name}"
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
