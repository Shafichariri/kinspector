package dev.inspector

import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.staticFunctions
import kotlin.reflect.jvm.isAccessible

/**
 * Reflects a module's public API into stable, sorted signature strings.
 *
 * This source directory is compiled into the `jvmTest` source set of BOTH `:inspector-core` and
 * `:inspector-noop`. Each module asserts its own surface against the shared golden file at
 * `api/inspector-public-api.txt`, which is what keeps the two from drifting: they cannot be
 * compared to each other directly, because they declare the same fully-qualified names and so
 * can never sit on one classpath together.
 */
object ApiSurface {

    /** Classes forming the public contract. Keep in sync with the golden file's sections. */
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

    fun dump(): List<String> = CONTRACT_CLASSES.flatMap { name ->
        val klass = Class.forName(name).kotlin
        signaturesOf(klass).map { "$name: $it" }
    }.sorted()

    private fun signaturesOf(klass: KClass<*>): List<String> {
        // staticFunctions is what surfaces top-level declarations on a file facade class such as
        // OkHttpCaptureKt; declaredMemberFunctions alone reports a facade as having no API at
        // all, which would let the guard pass while seeing nothing.
        val functions = (klass.declaredMemberFunctions + klass.staticFunctions)
            .filter { it.visibility == KVisibility.PUBLIC }
            .map { fn ->
                val params = fn.parameters
                    .drop(1) // receiver
                    .joinToString(", ") { p ->
                        val optional = if (p.isOptional) " = ..." else ""
                        "${p.name}: ${render(p.type.toString())}$optional"
                    }
                "fun ${fn.name}($params): ${render(fn.returnType.toString())}"
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
                "${if (mutable) "var" else "val"} ${prop.name}: ${render(prop.returnType.toString())}$setter"
            }

        return (functions + properties + staticJavaSignatures(klass)).distinct().sorted()
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
            .map { method ->
                val params = method.parameterTypes.joinToString(", ") { render(it.name) }
                "fun ${method.name}($params): ${render(method.returnType.name)}"
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

    private val STRIPPED_PREFIXES = listOf(
        "kotlin.collections.",
        "kotlin.text.",
        "kotlinx.coroutines.flow.",
        "dev.inspector.model.",
        "io.ktor.client.",
        "kotlin.",
    )
}
