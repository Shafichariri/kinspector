/*
 * Structural checks over the repository itself: the module graph, the noop twins, the dependency
 * locks, deferred-work markers and the file-size ratchet. Tests only, no main source set.
 *
 * Never published, and that is checked rather than assumed: there is no `maven-publish` here, and
 * `NoopTwinTest` fails if one appears, because a published module without a twin breaks
 * `-Pinspector=off` for anyone who depends on it.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
}

// Samples are left out of the graph on purpose: their edges flip under `-Pinspector=off`, which is
// the release-swap job's business. Everything else is evaluated first so its declared dependencies
// and applied plugins are final by the time they are read.
private val checkedProjects = rootProject.subprojects.filter { !it.path.startsWith(":sample") }
checkedProjects.filter { it != project }.forEach { evaluationDependsOn(it.path) }

/*
 * The module graph as the build declares it, one line per fact, computed here because only Gradle
 * knows it. Declared dependencies only (`dependencies`, not `allDependencies`): an inherited edge
 * would repeat once for every configuration extending the one it was written in. Self-edges are
 * dropped: AGP adds one per host-test classpath, which is plugin mechanics, not a module decision.
 */
private val structureLines: Provider<List<String>> = provider {
    checkedProjects.flatMap { p ->
        val published = if (p.pluginManager.hasPlugin("maven-publish")) "published" else "unpublished"
        val edges = p.configurations.flatMap { c ->
            c.dependencies.withType<ProjectDependency>().filter { it.path != p.path }.map { "edge ${p.path} -> ${it.path} ${c.name}" }
        }
        listOf("module ${p.path} $published") + edges
    }.sorted()
}

/*
 * Hands the test JVM the repository root and the module graph. The root is `@Internal`: the real
 * input is the whole git tree, which no input declaration can express cheaply — hence the test
 * task below never being up to date.
 */
class StructureArguments(
    @get:Internal val repoRoot: File,
    @get:Input val structure: Provider<List<String>>,
    @get:Input val regenerate: Provider<String>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf(
        "-Dinspector.repoRoot=${repoRoot.absolutePath}",
        // `|` cannot occur in a Gradle path or configuration name, so it separates safely.
        "-Dinspector.structure=${structure.get().joinToString("|")}",
        "-Dinspector.structure.regenerate=${regenerate.get()}",
    )
}

// Gradle's own -D lands on the daemon, not the forked test JVM, so forward it explicitly.
private val regenerateStructure = providers.systemProperty("inspector.structure.regenerate").orElse("false")

tasks.test {
    useJUnitPlatform()
    jvmArgumentProviders += StructureArguments(rootProject.projectDir, structureLines, regenerateStructure)
    /*
     * Always runs, never from cache. These tests read every tracked file and every lockfile, and
     * declaring that as an input means declaring the repository; without this, adding a marker or
     * growing a file past its budget would leave the task up to date and green.
     */
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
