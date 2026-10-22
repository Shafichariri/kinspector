package dev.inspector

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/**
 * A module's public **iOS** surface, read from its klibs by the Kotlin/Native toolchain's own
 * `klib dump-abi`, normalised into stable `<enclosing declaration>: <declaration>` lines.
 *
 * [ApiSurface] reads JVM bytecode, so a public declaration in one module's `iosMain` and not in its
 * twin passed every parity test, and the consuming team found out when its release build failed to
 * compile on iOS alone. This is the iOS half. It runs only in each module's `iosApiTest` task, which
 * passes the system properties read below; every other test task excludes the `*IosApiParityTest`
 * classes. See `docs/decisions/twins-and-api.md` on why the tool is `klib dump-abi` and not KGP's
 * `abiValidation`.
 *
 * Everything that could make it compare nothing fails instead: no tool, a missing klib, the wrong
 * library, two targets that disagree, an empty result.
 */
object KlibSurface {

    /**
     * @param ktorRule the stream pair's rule, as [ApiSurface.dump]'s `skipKtorTyped` is on the JVM. A
     *   Ktor type inside a `kotlin/FunctionN<…>` reads as `kotlin/Any?`, and then every line still
     *   naming Ktor is dropped. Native does not erase function-type arguments as the JVM does, so the
     *   real constructor's `Function0<io.ktor.client/HttpClient>` would otherwise never equal the
     *   noop's `Function0<kotlin/Any?>`. That is a source-level match, not an ABI one: the IR
     *   signatures still differ, and binary compatibility between twins is out of scope on iOS.
     */
    fun dump(ktorRule: Boolean): List<String> {
        val module = property("inspector.klib.module")
        val tool = tool()
        val dirs = property("inspector.klib.dirs").split(',').map {
            it.substringBefore('=') to File(it.substringAfter('='))
        }
        val byTarget = dirs.associate { (target, dir) -> target to normalise(abiOf(tool, target, dir, module), ktorRule) }
        val (firstTarget, first) = byTarget.entries.first()
        // A public declaration in iosArm64Main or iosSimulatorArm64Main alone is a split no single
        // golden file can describe, so it is a failure of its own rather than a line in one diff.
        for ((target, lines) in byTarget) {
            if (lines == first) continue
            fail(
                buildString {
                    appendLine("$module's public iOS surface differs between targets, so one golden file cannot describe it.")
                    (first - lines.toSet()).forEach { appendLine("  only in $firstTarget: $it") }
                    (lines - first.toSet()).forEach { appendLine("  only in $target: $it") }
                    append("Declare it for every iOS target, or make it internal.")
                }
            )
        }
        if (first.isEmpty()) fail("klib dump-abi read no public declaration from $module; refusing to compare nothing.")
        return first
    }

    /**
     * The `klib` launcher of the Kotlin/Native distribution matching the **compiler's** version.
     *
     * Never `KotlinVersion.CURRENT`: that is the stdlib on the test classpath, which the locks raise
     * to 2.3.21 while the compiler is 2.3.20, and no 2.3.21 distribution exists. The build passes
     * `libs.versions.kotlin` instead. The `~/.konan` layout is KGP's convention, not an API, so a
     * Kotlin bump may move it; this then fails naming what it looked for, never passes.
     */
    private fun tool(): File {
        System.getProperty("inspector.konan.home")?.takeIf { it.isNotBlank() }?.let { home ->
            val klib = File(home, "bin/klib")
            if (!klib.canExecute()) fail("No klib tool at $klib (from inspector.konan.home, the kotlin.native.home Gradle property).")
            return klib
        }
        val version = property("inspector.kotlin.version")
        val data = System.getenv("KONAN_DATA_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".konan")
        val dists = data.listFiles { f -> f.name.startsWith("kotlin-native-prebuilt-") && f.name.endsWith("-$version") }
            .orEmpty().sortedBy { it.name }
        val tools = dists.map { File(it, "bin/klib") }.filter { it.canExecute() }
        if (tools.size == 1) return tools.single()
        fail(
            buildString {
                appendLine("Expected exactly one Kotlin/Native $version klib tool, found ${tools.size}. Tried:")
                appendLine("  $data/kotlin-native-prebuilt-*-$version/bin/klib")
                dists.forEach { appendLine("  ${File(it, "bin/klib")}") }
                append("Any iOS compile downloads the distribution; or set the kotlin.native.home Gradle property.")
            }
        )
    }

    private fun abiOf(tool: File, target: String, dir: File, module: String): List<String> {
        if (!dir.isDirectory) {
            fail(
                "No $target klib for $module at $dir. iosApiTest depends on the iOS compiles, so this host did not run " +
                    "them, or KGP moved its output. A guard that scans nothing must fail, not pass."
            )
        }
        val stderr = File.createTempFile("klib-dump-abi", ".err")
        try {
            val process = ProcessBuilder(tool.path, "dump-abi", dir.path)
                .redirectError(stderr)
                .also { it.environment()["JAVACMD"] = File(System.getProperty("java.home"), "bin/java").path }
                .start()
            val out = process.inputStream.bufferedReader().readLines()
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                fail("klib dump-abi timed out on $dir")
            }
            if (process.exitValue() != 0) fail("klib dump-abi exited ${process.exitValue()} on $dir:\n${stderr.readText()}")
            // The library's own name, so a path that points at the twin, or at a dependency, cannot pass.
            val unique = out.firstOrNull { it.startsWith(UNIQUE_NAME) }
            if (unique == null || !unique.trimEnd().endsWith(":$module>")) {
                fail("$dir is not $module's klib: dump-abi reports ${unique ?: "no unique name"}.")
            }
            return out
        } finally {
            stderr.delete()
        }
    }

    /**
     * Three rules, each measured on all three pairs (`docs/decisions/twins-and-api.md`).
     *
     * **Flattened.** dump-abi nests members under their class by indentation, and [GoldenSurface]
     * compares sets, so an unqualified `final fun stop()` could move from `StreamSink` to
     * `StreamState` and the set would not change. Every line carries its enclosing declarations.
     *
     * **`$stableprop` dropped by name.** The Compose compiler emits a public `…$stableprop` and
     * `…$stableprop_getter` for each class it infers stability for, `internal` ones included: 26
     * lines in `:inspector-ui`, none in the noop. Not a general `$` rule, so a new kind of compiler
     * noise fails here instead of being swallowed.
     *
     * **The Ktor rule**, for the stream pair only; see [dump].
     */
    internal fun normalise(dump: List<String>, ktorRule: Boolean): List<String> {
        val enclosing = mutableListOf<String>()
        val lines = mutableListOf<String>()
        for (raw in dump) {
            if (raw.isBlank() || raw.trimStart().startsWith("//")) continue
            val depth = (raw.length - raw.trimStart().length) / INDENT
            // The IR signature, which names the same declaration again in another syntax.
            val text = raw.trim().substringBefore(" // ")
            if (text == "}") continue
            while (enclosing.size > depth) enclosing.removeAt(enclosing.lastIndex)
            val declaration = text.removeSuffix(" {")
            lines += if (enclosing.isEmpty()) declaration else "${enclosing.joinToString(".")}: $declaration"
            enclosing += nameOf(declaration)
        }
        return lines
            .filterNot { STABLEPROP in it }
            .map { if (ktorRule) eraseKtorInFunctionTypes(it) else it }
            .filterNot { ktorRule && ApiSurface.KTOR_PACKAGE in it }
            .distinct()
            .sorted()
    }

    /** `final class dev.inspector/Redaction : …` → `dev.inspector/Redaction`; `final val dropped` → `dropped`. */
    private fun nameOf(declaration: String): String =
        declaration.substringBefore(" : ").substringBefore('(').substringBefore(" =").trim().substringAfterLast(' ')

    private fun eraseKtorInFunctionTypes(line: String): String {
        val out = StringBuilder()
        var i = 0
        while (true) {
            val start = FUNCTION_TYPE.find(line, i) ?: break
            out.append(line, i, start.range.last + 1)
            // The matching `>`, so a nested generic does not end the function type early.
            var depth = 1
            var end = start.range.last + 1
            while (end < line.length && depth > 0) {
                when (line[end]) { '<' -> depth++; '>' -> depth-- }
                end++
            }
            out.append(KTOR_TYPE.replace(line.substring(start.range.last + 1, end), "kotlin/Any?"))
            i = end
        }
        return out.append(line, i, line.length).toString()
    }

    private fun property(name: String): String =
        System.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: fail("System property $name is not set. Run this test through the module's iosApiTest task, which sets it.")

    private const val UNIQUE_NAME = "// Library unique name:"
    private const val STABLEPROP = "\$stableprop"
    private const val INDENT = 4
    private val FUNCTION_TYPE = Regex("""kotlin/Function\d+<""")
    private val KTOR_TYPE = Regex(Regex.escape(ApiSurface.KTOR_PACKAGE) + """[\w.]*/[\w.]+(<[^<>]*>)?\??""")
}
