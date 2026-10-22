package dev.inspector.repochecks

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * "Every real module needs a noop twin", in the meaning it was recorded with: every *published*
 * module a consuming app calls into. Not every module -- `:inspector-daemon`, `:repo-checks` and
 * the samples are never on a consumer's classpath, so a check reading the rule literally would
 * fail on day one or grow an allowlist nobody could explain.
 *
 * So the published set must be exactly the pairs in `structure/twins.txt` plus its exemptions, and
 * each exemption has to say why. A module that starts publishing without a twin fails here, which
 * is the `-Pinspector=off` break the twins exist to prevent.
 *
 * A twin is only as good as the golden file holding it to its pair, so each `pair` line also names
 * that file and it must exist. Before this, nothing failed a pair that had none, and the ui pair had
 * none for eleven releases while its real module grew public theme types the noop lacked. The fourth
 * field is the pair's iOS golden file, which the root build's `iosApiTest` asserts from the klibs;
 * it is held to the same rule, or a pair could drop its iOS guard by dropping a column.
 */
class NoopTwinTest {

    private sealed interface Entry
    /** A golden file is null when the line names none, so the test below can say which pair lacks one. */
    private data class Twin(val real: String, val noop: String, val golden: String?, val iosGolden: String?) : Entry {
        val goldens get() = listOf(golden, iosGolden)
    }
    private data class Exempt(val module: String, val reason: String) : Entry

    private fun entries(): List<Entry> = RepoFiles.golden("twins.txt").map { line ->
        val parts = line.split(Regex("\\s+"), limit = 3)
        when (parts[0]) {
            "pair" -> {
                val fields = line.split(Regex("\\s+")).drop(1)
                if (fields.size !in 2..4) fail("twins.txt: a pair is `pair <real> <noop> <golden file> <iOS golden file>`: $line")
                Twin(fields[0], fields[1], fields.getOrNull(2), fields.getOrNull(3))
            }
            "exempt" -> Exempt(parts.getOrElse(1) { fail("twins.txt: exempt names no module: $line") }, parts.getOrElse(2) { "" }.trim())
            else -> fail("twins.txt: unknown entry: $line")
        }
    }

    private fun published(): Set<String> = ModuleGraphTest.structure()
        .filter { it.startsWith("module ") && it.endsWith(" published") }
        .map { it.split(' ')[1] }
        .toSet()

    @Test
    fun every_exemption_gives_its_reason() {
        val blank = entries().filterIsInstance<Exempt>().filter { it.reason.isBlank() }
        if (blank.isNotEmpty()) {
            fail("An exemption from the twin rule must say why. No reason given for: ${blank.joinToString { it.module }}")
        }
    }

    @Test
    fun every_pair_names_a_golden_file_that_exists() {
        val pairs = entries().filterIsInstance<Twin>()
        // An empty loop passes; a twins.txt that lost its pairs must not read as all of them named.
        if (pairs.isEmpty()) fail("structure/twins.txt lists no pairs; refusing to check nothing")
        val unnamed = pairs.filter { null in it.goldens }
        val missing = pairs.filter { pair -> pair.goldens.any { it != null && !File(RepoFiles.root, it).isFile } }
        if (unnamed.isEmpty() && missing.isEmpty()) return
        fail(
            buildString {
                if (unnamed.isNotEmpty()) {
                    appendLine("These pairs do not name both golden files, JVM and iOS, in structure/twins.txt:")
                    unnamed.forEach { appendLine("  ${it.real} ${it.noop}") }
                }
                if (missing.isNotEmpty()) {
                    appendLine("These pairs name a golden file that does not exist:")
                    missing.forEach { appendLine("  ${it.real} ${it.noop} ${it.golden} ${it.iosGolden}") }
                }
                appendLine("Without a golden file both modules assert, a twin drifts unseen and -Pinspector=off changes what")
                appendLine("compiles. Give the pair a parity test in api-parity/ (api-parity/AGENTS.md), generate its files from")
                append("the real module (jvmTest and iosApiTest), and name both on the pair's line.")
            }
        )
    }

    @Test
    fun both_members_of_every_pair_are_published() {
        val published = published()
        val unpublished = entries().filterIsInstance<Twin>().flatMap { listOf(it.real, it.noop) }.filter { it !in published }
        if (unpublished.isNotEmpty()) {
            fail(
                "These twins are not published, so a release build cannot resolve the swap: " +
                    unpublished.joinToString() + ". Each needs `id(\"maven-publish\")`."
            )
        }
    }

    @Test
    fun the_published_set_is_the_pairs_plus_the_exemptions() {
        val entries = entries()
        val allowed = entries.flatMap {
            when (it) {
                is Twin -> listOf(it.real, it.noop)
                is Exempt -> listOf(it.module)
            }
        }.toSet()
        val published = published()
        // An exemption for a module that no longer publishes is a stale reason nobody re-read.
        val stale = allowed - published
        if (stale.isNotEmpty()) fail("Named in structure/twins.txt but not published: ${stale.joinToString()}.")
        val extra = published - allowed
        if (extra.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("Published with no noop twin: ${extra.joinToString()}.")
                    appendLine("A consumer's release build swaps every real module for its twin; one without a twin")
                    appendLine("either breaks -Pinspector=off or ships to production. Add the twin and a `pair` line")
                    append("to structure/twins.txt, or an `exempt` line saying why it needs none.")
                }
            )
        }
    }
}
