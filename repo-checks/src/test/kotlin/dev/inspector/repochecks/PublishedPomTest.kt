package dev.inspector.repochecks

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.fail
import org.w3c.dom.Element

/**
 * The POMs a release publishes, held to the locks. A Maven-style consumer reads only the POM, so a
 * version in it that the lock does not resolve is what that consumer gets, by nearest-wins, under
 * a library built against another. The build stayed green while every Ktor module's POM said
 * kotlin-stdlib 2.3.20 and its lock 2.3.21, and so did the obvious fix; see `docs/decisions/release.md`.
 *
 * It reads the POMs `generatePomFileFor…Publication` writes, which `:repo-checks:test` depends on,
 * and compares them with the lock *text*, not with what the mapping resolved: with a lock edited by
 * hand, the mapping's own resolution fails quietly and the POM falls back to the declared version.
 */
class PublishedPomTest {

    private class Pom(val module: String, val publication: String, val file: File)

    private class Dependency(val coordinate: String, val version: String?)

    private fun publishedModules(): List<String> = ModuleGraphTest.structure()
        .filter { it.startsWith("module ") && it.endsWith(" published") }
        .map { it.split(' ')[1] }
        .also { if (it.isEmpty()) fail("no module is marked published; refusing to check nothing") }

    /** `jvmRuntimeClasspath` → `jvm`, `iosArm64CompileKlibraries` → `iosArm64`. */
    private fun publicationOf(configuration: String): String? =
        LOCKED_SUFFIXES.firstNotNullOfOrNull { suffix -> configuration.removeSuffix(suffix).takeIf { it != configuration } }

    /**
     * The POMs a module must have: the root publication, plus one per configuration its lock
     * covers. Derived from the lock rather than listed, so a target that gains a lock gains a POM
     * to check, and one whose POM was never generated fails instead of being skipped.
     */
    private fun expectedPoms(modulePath: String, problems: MutableList<String>): List<Pom> {
        val module = modulePath.removePrefix(":").replace(':', '/')
        val configurations = Lockfiles.entries(Lockfiles.of(modulePath)).flatMap { it.configurations }.toSortedSet()
        val publications = listOf(ROOT_PUBLICATION) + configurations.mapNotNull { configuration ->
            publicationOf(configuration).also { if (it == null) problems += "$modulePath locks $configuration, which maps to no publication" }
        }
        return publications.map { Pom(module, it, File(RepoFiles.root, "$module/build/publications/$it/pom-default.xml")) }
    }

    /** `<project><dependencies><dependency>` only, so a `<dependencyManagement>` block is not read as one. */
    private fun dependenciesOf(pom: File): List<Dependency> {
        val project = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom).documentElement
        return project.children("dependencies").flatMap { it.children("dependency") }.map { dependency ->
            val text = { tag: String -> dependency.children(tag).firstOrNull()?.textContent?.trim() }
            Dependency("${text("groupId")}:${text("artifactId")}", text("version"))
        }
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    @Test
    fun every_published_module_has_a_pom_for_each_locked_target() {
        val problems = mutableListOf<String>()
        publishedModules().flatMap { expectedPoms(it, problems) }.forEach { pom ->
            when {
                !pom.file.isFile -> problems += "no POM at ${pom.file.relativeTo(RepoFiles.root)}"
                dependenciesOf(pom.file).isEmpty() ->
                    problems += "${pom.module} ${pom.publication}: the POM lists no dependency; refusing to call that clean"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n") + "\n$HOW_TO_GENERATE")
    }

    /**
     * A target POM must state the version its own locked configuration resolves. The root POM is
     * mapped from an unlocked metadata configuration, so it is held to every locked configuration
     * that lists the coordinate, and at least one must.
     */
    @Test
    fun every_published_pom_states_the_versions_its_lock_resolves() {
        val problems = mutableListOf<String>()
        var compared = 0
        publishedModules().forEach { modulePath ->
            val lock = Lockfiles.entries(Lockfiles.of(modulePath))
            expectedPoms(modulePath, problems).forEach { pom ->
                if (!pom.file.isFile) {
                    problems += "no POM at ${pom.file.relativeTo(RepoFiles.root)}"
                    return@forEach
                }
                val configuration = lock.flatMap { it.configurations }.firstOrNull { publicationOf(it) == pom.publication }
                dependenciesOf(pom.file).filter { !it.coordinate.startsWith("$OWN_GROUP:") }.forEach { dependency ->
                    compared++
                    val locked = lock.filter { it.coordinate == dependency.coordinate }
                        .filter { configuration == null || configuration in it.configurations }
                        .map { it.version }.toSortedSet()
                    when {
                        locked.isEmpty() -> problems += "${pom.module} ${pom.publication}: ${dependency.coordinate} POM ${dependency.version}, absent from the lock"
                        locked.size > 1 || locked.single() != dependency.version ->
                            problems += "${pom.module} ${pom.publication}: ${dependency.coordinate} POM ${dependency.version}, lock ${locked.joinToString(" / ")}"
                    }
                }
            }
        }
        if (compared == 0 && problems.isEmpty()) fail("no POM dependency was compared with a lock; refusing to call that clean")
        if (problems.isNotEmpty()) {
            fail(
                problems.joinToString("\n") +
                    "\nEvery publication maps its versions from the configuration its lock covers; see " +
                    "\"Every publication maps its dependency versions\" in docs/decisions/release.md."
            )
        }
    }

    private companion object {
        const val ROOT_PUBLICATION = "kotlinMultiplatform"
        const val OWN_GROUP = "io.github.shafichariri"
        val LOCKED_SUFFIXES = listOf("RuntimeClasspath", "CompileKlibraries")
        const val HOW_TO_GENERATE = "The POMs are written by `generatePomFileFor…Publication`, which `:repo-checks:test` depends on; run it through Gradle."
    }
}
