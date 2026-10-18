package dev.inspector.repochecks

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The daemon's packages are layers, and this scan is what holds them.
 *
 * Kotlin's `internal` is module-wide, so `dev.inspector.daemon.archive` and
 * `dev.inspector.daemon.http` are one visibility domain to the compiler: a repository class can
 * import a Ktor type and a tool can reach into a route file, and both compile. Packages alone
 * enforce nothing. So the rule is read off the source text instead:
 *
 * | Layer   | May import, beyond the base set and itself                       |
 * |---------|------------------------------------------------------------------|
 * | archive | nothing                                                          |
 * | service | archive                                                          |
 * | http    | archive, service, `io.ktor.`                                     |
 * | mcp     | archive, service                                                 |
 * | usb     | `javax.xml.`, `org.w3c.dom.`, `org.xml.sax.` -- no daemon package |
 * | root    | anything, but the root holds only `Main.kt`                      |
 *
 * The base set every layer may import is [BASE]. Adapters (http, mcp) read `archive/` directly:
 * holding them to `service/` alone would need a pass-through wrapper for every repository method,
 * which is a second copy of each. `usb/` imports nothing from the daemon because the bridge goes
 * through the daemon's own `WS /ingest`, the path every other test covers.
 *
 * Rules, each tagged in its message:
 *
 * - **L0** what is on disk under `src/main` is what git lists; no `.java`; no `.kt` outside the
 *   daemon's package directory. A file git cannot see is a file every scan here cannot see.
 * - **L1** the root package holds exactly `Main.kt`.
 * - **L2** a directory not in the table fails, and says where the table is.
 * - **L3** each `package` line matches its directory.
 * - **L4** each import, alias stripped, starts with an allowed prefix. A wildcard is just a prefix
 *   that names no allowed package, and nothing may import the root package.
 * - **L5** inline qualified names (`io.ktor...`, `dev.inspector.daemon.<layer>...`) obey the same
 *   table, matched over the whole text after joining names split across lines. Comments match
 *   too; a false positive in a comment is the accepted price of not parsing Kotlin.
 * - **L6** every mechanism above found something to check, so a parser or matcher that stopped
 *   matching fails here rather than reporting a clean tree.
 *
 * Files are read here rather than through [RepoFiles.text], which skips any file holding a NUL
 * byte, and `archive/SessionLayout.kt` holds one in a character literal.
 */
class DaemonLayersTest {

    /** The daemon's main sources as the scan sees them: plain data, so a synthetic tree can stand in. */
    class Tree(
        /** Every non-directory under `inspector-daemon/src/main/`, relative to the repository root. */
        val onDisk: List<String>,
        /** What git lists under the same directory. */
        val tracked: Set<String>,
        /** Text of every `.kt` file in [onDisk]. */
        val text: Map<String, String>,
    )

    @Test
    fun the_daemon_tree_has_no_layer_violations() {
        val tree = liveTree()
        val report = scan(tree)
        println("DaemonLayersTest: ${tree.onDisk.size} file(s) on disk under $MAIN; per layer ${report.filesPerLayer}")
        println("  io.ktor imports in http/: ${report.ktorImportsInHttp}; io.ktor names in http/: ${report.ktorNamesInHttp}")
        println("  archive/service imports in mcp/: ${report.adapterImportsInMcp}")
        if (report.violations.isNotEmpty()) {
            fail("The daemon's layers are crossed:\n  " + report.violations.joinToString("\n  "))
        }
    }

    // --- synthetic trees: each rule seen failing, permanently ----------------------------------

    @Test
    fun a_clean_tree_has_no_violations() {
        assertEquals(emptyList(), layerViolations(tree(clean())))
    }

    @Test
    fun service_importing_ktor_is_refused() =
        assertRule("L4", clean() + ("service/Svc.kt" to svc("import io.ktor.http.HttpStatusCode")))

    @Test
    fun archive_naming_a_ktor_route_inline_is_refused() =
        assertRule("L5", clean() + ("archive/Repo.kt" to "$ARCHIVE\nfun mount(route: io.ktor.server.routing.Route) = route\n"))

    @Test
    fun http_importing_mcp_is_refused() =
        assertRule("L4", clean() + ("http/Routes.kt" to routes("import dev.inspector.daemon.mcp.Tools")))

    @Test
    fun mcp_importing_http_is_refused() =
        assertRule("L4", clean() + ("mcp/Tools.kt" to tools("import dev.inspector.daemon.http.InspectorDaemon")))

    @Test
    fun archive_importing_service_is_refused() =
        assertRule("L4", clean() + ("archive/Repo.kt" to "$ARCHIVE\nimport dev.inspector.daemon.service.Svc\n"))

    @Test
    fun usb_importing_service_is_refused() =
        assertRule("L4", clean() + ("usb/Bridge.kt" to "$USB\nimport dev.inspector.daemon.service.Svc\n"))

    @Test
    fun a_ktor_wildcard_in_service_is_refused() =
        assertRule("L4", clean() + ("service/Svc.kt" to svc("import io.ktor.*")))

    @Test
    fun a_service_file_declaring_the_http_package_is_refused() =
        assertRule("L3", clean() + ("service/Svc.kt" to "package dev.inspector.daemon.http\n"))

    @Test
    fun a_second_file_in_the_root_is_refused() =
        assertRule("L1", clean() + ("Helpers.kt" to "package dev.inspector.daemon\n"))

    @Test
    fun an_unknown_layer_directory_is_refused() =
        assertRule("L2", clean() + ("web/Page.kt" to "package dev.inspector.daemon.web\n"))

    @Test
    fun a_qualified_name_split_across_lines_is_refused() =
        assertRule("L5", clean() + ("service/Svc.kt" to svc("val ok = io.ktor\n    .http\n    .HttpStatusCode.OK")))

    @Test
    fun a_java_file_is_refused() {
        val path = "${MAIN}java/dev/inspector/daemon/Legacy.java"
        val base = tree(clean())
        assertRule("L0", Tree(base.onDisk + path, base.tracked + path, base.text))
    }

    @Test
    fun a_file_on_disk_that_git_does_not_list_is_refused() {
        val path = "${KOTLIN}service/Ignored.kt"
        val base = tree(clean())
        assertRule("L0", Tree(base.onDisk + path, base.tracked, base.text + (path to "package dev.inspector.daemon.service\n")))
    }

    @Test
    fun an_empty_tree_fails_rather_than_passing() {
        assertTrue(layerViolations(Tree(emptyList(), emptySet(), emptyMap())).any { it.startsWith("L6") })
    }

    /**
     * Two imports on one line are legal Kotlin and are not something the import reader reads, so
     * http/ appears to import no Ktor at all. L6 is what notices: an import reader that silently
     * stopped matching would otherwise report every layer clean.
     */
    @Test
    fun imports_the_reader_cannot_read_trip_the_non_vacuity_check() {
        val unreadable = "$HTTP\nimport io.ktor.server.routing.Route; import dev.inspector.daemon.service.Svc\n"
        val violations = layerViolations(tree(clean() + ("http/Routes.kt" to unreadable)))
        assertTrue(violations.any { it.startsWith("L6") && "io.ktor" in it }, "no L6 violation in: $violations")
    }

    // --- the scan ----------------------------------------------------------------------------

    class Report(
        val violations: List<String>,
        val filesPerLayer: Map<String, Int>,
        val ktorImportsInHttp: Int,
        val ktorNamesInHttp: Int,
        val adapterImportsInMcp: Int,
    )

    companion object {
        const val MAIN = "inspector-daemon/src/main/"
        const val KOTLIN = "inspector-daemon/src/main/kotlin/dev/inspector/daemon/"
        private const val DAEMON = "dev.inspector.daemon"
        private const val ARCHIVE_PREFIX = "$DAEMON.archive."
        private const val SERVICE_PREFIX = "$DAEMON.service."

        /** What every layer may import. */
        val BASE = listOf("kotlin.", "kotlinx.serialization.", "kotlinx.coroutines.", "java.", "dev.inspector.model.")

        /** The table in the class doc. A layer's own package is always allowed as well. */
        val LAYERS: Map<String, List<String>> = linkedMapOf(
            "archive" to emptyList(),
            "service" to listOf(ARCHIVE_PREFIX),
            "http" to listOf(ARCHIVE_PREFIX, SERVICE_PREFIX, "io.ktor."),
            "mcp" to listOf(ARCHIVE_PREFIX, SERVICE_PREFIX),
            "usb" to listOf("javax.xml.", "org.w3c.dom.", "org.xml.sax."),
        )

        private const val ROOT = "root"
        private val IMPORT_LINE = Regex("""^\s*import\s""")
        private val IMPORT = Regex("""^import\s+([A-Za-z_]\w*(?:\.\w+)*(?:\.\*)?)(?:\s+as\s+\w+)?$""")
        private val PACKAGE = Regex("""(?m)^\s*package\s+([\w.`]+)""")
        private val QUALIFIED = Regex("""(?<![\w.])(io\.ktor|dev\.inspector\.daemon\.(archive|service|http|mcp|usb))(\.\w+)+""")
        private val SPLIT_DOT = Regex("""\s*\.\s*|`""")

        fun layerViolations(files: Tree): List<String> = scan(files).violations

        /** `dev.inspector.daemon.X` or `dev.inspector.daemon.*`: a name with no layer between the package and it. */
        private fun importsRoot(name: String): Boolean =
            name.startsWith("$DAEMON.") && '.' !in name.removePrefix("$DAEMON.")

        fun scan(files: Tree): Report {
            val violations = mutableListOf<String>()
            val perLayer = linkedMapOf<String, Int>()
            var ktorImportsInHttp = 0
            var ktorNamesInHttp = 0
            var adapterImportsInMcp = 0

            // L0: the disk against git, and nothing compiled from outside the package directory.
            for (path in files.onDisk) {
                if (path !in files.tracked) violations += "L0 $path is on disk but git does not list it; no scan here can see it"
                if (path.endsWith(".java")) violations += "L0 $path: the daemon is Kotlin; a .java file is outside every check"
                if (path.endsWith(".kt") && !path.startsWith(KOTLIN)) violations += "L0 $path is outside $KOTLIN"
            }

            val sources = files.onDisk.filter { it.startsWith(KOTLIN) && it.endsWith(".kt") }

            // L1: the composition root alone.
            val rootFiles = sources.map { it.removePrefix(KOTLIN) }.filter { '/' !in it }.toSet()
            if ("Main.kt" !in rootFiles) violations += "L1 ${KOTLIN}Main.kt is missing; it is the composition root"
            (rootFiles - "Main.kt").sorted().forEach {
                violations += "L1 $KOTLIN$it: the root package holds only Main.kt; give it a layer"
            }

            for (path in sources) {
                val relative = path.removePrefix(KOTLIN)
                val layer = if ('/' in relative) relative.substringBefore('/') else ROOT
                perLayer[layer] = (perLayer[layer] ?: 0) + 1
                val text = files.text[path]
                if (text == null) { violations += "L0 $path could not be read"; continue }

                // L2: a layer the table does not know.
                if (layer != ROOT && layer !in LAYERS) {
                    violations += "L2 $path: '$layer' is not a layer; the table in DaemonLayersTest names ${LAYERS.keys.joinToString()}"
                    continue
                }

                // L3: the package line agrees with the directory.
                val expected = (listOf(DAEMON) + relative.split('/').dropLast(1)).joinToString(".")
                val declared = PACKAGE.find(text)?.groupValues?.get(1)?.replace("`", "")
                if (declared != expected) violations += "L3 $path declares package ${declared ?: "(none)"}, expected $expected"

                val allowed = if (layer == ROOT) null else BASE + LAYERS.getValue(layer) + "$DAEMON.$layer."
                fun permitted(name: String) = allowed == null || allowed.any { name.startsWith(it) }

                // L4: imports, read line by line, a continuation line (one starting with a dot) joined on.
                val lines = text.lines()
                val parsedLines = mutableSetOf<Int>()
                var i = 0
                while (i < lines.size) {
                    if (!IMPORT_LINE.containsMatchIn(lines[i])) { i++; continue }
                    val first = i
                    var directive = lines[i]
                    while (i + 1 < lines.size && lines[i + 1].trimStart().startsWith(".")) directive += lines[++i]
                    val normalised = directive.replace(SPLIT_DOT) { if (it.value == "`") "" else "." }.trim().removeSuffix(";").trim()
                    val name = IMPORT.matchEntire(normalised)?.groupValues?.get(1)
                    if (name == null) {
                        violations += "L4 $path:${first + 1}: cannot read this import: ${lines[first].trim()}"
                    } else {
                        (first..i).forEach { parsedLines += it }
                        if (layer == "http" && name.startsWith("io.ktor.")) ktorImportsInHttp++
                        if (layer == "mcp" && (name.startsWith(ARCHIVE_PREFIX) || name.startsWith(SERVICE_PREFIX))) adapterImportsInMcp++
                        when {
                            layer != ROOT && importsRoot(name) ->
                                violations += "L4 $path:${first + 1}: imports the root package ($name); nothing depends on Main.kt"
                            !permitted(name) -> violations += "L4 $path:${first + 1}: the $layer layer may not import $name"
                        }
                    }
                    i++
                }

                // L5: inline qualified names, over the whole text with split names joined.
                val origin = IntArray(text.length + 1)
                val joined = StringBuilder()
                var last = 0
                for (m in SPLIT_DOT.findAll(text)) {
                    for (p in last until m.range.first) { origin[joined.length] = p; joined.append(text[p]) }
                    if (m.value != "`") { origin[joined.length] = m.range.first + m.value.indexOf('.'); joined.append('.') }
                    last = m.range.last + 1
                }
                for (p in last until text.length) { origin[joined.length] = p; joined.append(text[p]) }
                for (m in QUALIFIED.findAll(joined)) {
                    val line = text.substring(0, origin[m.range.first]).count { it == '\n' }
                    if (layer == "http" && m.groupValues[1] == "io.ktor") ktorNamesInHttp++
                    // A name on an import line L4 read is L4's to report; anything else is this rule's.
                    if (line !in parsedLines && !permitted(m.value)) {
                        violations += "L5 $path:${line + 1}: the $layer layer names ${m.value}"
                    }
                }
            }

            // L6: each mechanism found something, so none of them can pass by matching nothing.
            if (files.onDisk.isEmpty()) violations += "L6 the walk of $MAIN found no files"
            for (layer in listOf("archive", "service", "http", "mcp")) {
                if ((perLayer[layer] ?: 0) == 0) violations += "L6 $KOTLIN$layer/ holds no file; the scan checked nothing there"
            }
            if (ktorImportsInHttp == 0) violations += "L6 the import reader found no io.ktor import in http/; it is not reading imports"
            if (adapterImportsInMcp == 0) violations += "L6 the import reader found no archive or service import in mcp/"
            if (ktorNamesInHttp < ktorImportsInHttp) {
                violations += "L6 the name matcher found $ktorNamesInHttp io.ktor name(s) in http/, fewer than the " +
                    "$ktorImportsInHttp import line(s) naming one; it is not matching"
            }
            return Report(violations, perLayer, ktorImportsInHttp, ktorNamesInHttp, adapterImportsInMcp)
        }

        /** The repository as it is: the disk under `src/main`, git's list of it, and each Kotlin file's text. */
        fun liveTree(): Tree {
            val main = File(RepoFiles.root, MAIN).toPath()
            val onDisk = Files.walk(main).use { stream ->
                stream.filter { !Files.isDirectory(it) }
                    .map { RepoFiles.root.toPath().relativize(it).toString().replace(File.separatorChar, '/') }
                    .toList()
            }.sorted()
            val tracked = RepoFiles.paths.filter { it.startsWith(MAIN) }.toSet()
            val text = onDisk.filter { it.endsWith(".kt") }.associateWith { File(RepoFiles.root, it).readText(Charsets.UTF_8) }
            return Tree(onDisk, tracked, text)
        }

        // --- synthetic fixtures --------------------------------------------------------------

        private const val ARCHIVE = "package dev.inspector.daemon.archive\n\nimport java.nio.file.Path\n"
        private const val HTTP = "package dev.inspector.daemon.http\n"
        private const val USB = "package dev.inspector.daemon.usb\n\nimport javax.xml.parsers.DocumentBuilderFactory\n"

        private fun svc(extra: String) = "package dev.inspector.daemon.service\n\nimport dev.inspector.daemon.archive.Repo\n$extra\n"
        private fun routes(extra: String) =
            "$HTTP\nimport io.ktor.server.routing.Route\nimport dev.inspector.daemon.service.Svc\n$extra\n\n" +
                "fun Route.ok() = io.ktor.http.HttpStatusCode.OK\n"
        private fun tools(extra: String) = "package dev.inspector.daemon.mcp\n\nimport dev.inspector.daemon.archive.Repo\n$extra\n"

        /** One file per layer, each importing only what its row allows. Paths relative to [KOTLIN]. */
        private fun clean(): Map<String, String> = linkedMapOf(
            "Main.kt" to "package dev.inspector.daemon\n\nimport dev.inspector.daemon.http.InspectorDaemon\n",
            "archive/Repo.kt" to ARCHIVE,
            "service/Svc.kt" to svc(""),
            "http/Routes.kt" to routes(""),
            "mcp/Tools.kt" to tools(""),
            "usb/Bridge.kt" to USB,
        )

        private fun tree(files: Map<String, String>): Tree {
            val paths = files.keys.map { KOTLIN + it }
            return Tree(paths, paths.toSet(), files.mapKeys { KOTLIN + it.key })
        }

        private fun assertRule(rule: String, files: Map<String, String>) = assertRule(rule, tree(files))

        private fun assertRule(rule: String, tree: Tree) {
            val violations = layerViolations(tree)
            assertTrue(violations.any { it.startsWith("$rule ") }, "expected a $rule violation, got: $violations")
        }
    }
}
