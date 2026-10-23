package dev.inspector.repochecks

import kotlin.test.Test
import kotlin.test.fail

/**
 * Every `src/<name>` directory of a published module is one of its source sets.
 *
 * A directory under `src/` that no source set names is never compiled, and Gradle says nothing:
 * the build stays green and the file ships nowhere. `:inspector-core` and `:inspector-noop` are
 * where that happens most easily. Their hand-written `jvmAndAndroidMain` links turn the default
 * hierarchy template off, so they have no `iosMain`, and `iosMain` is where KMP habit puts iOS code
 * (`docs/decisions/twins-and-api.md`). The iOS API guard reads the real klibs, so it stays honest,
 * but it cannot see code that was never in them.
 *
 * The source set names come from the build, as the module graph does; the directories from
 * [RepoFiles.paths], so a directory left empty by a branch switch, or holding only ignored files,
 * does not count, and a file written and not yet added does.
 */
class SourceSetDirsTest {

    @Test
    fun every_src_directory_of_a_published_module_is_a_source_set() {
        val modules = sourceSets()
        val stray = modules.flatMap { (dir, names) ->
            RepoFiles.paths
                .filter { it.startsWith("$dir/src/") }
                .map { it.removePrefix("$dir/src/").substringBefore('/') }
                .distinct()
                .filter { it !in names }
                .map { "$dir/src/$it is not a source set of $dir (it has ${names.sorted().joinToString()})" }
        }
        if (stray.isNotEmpty()) {
            fail(
                "Directories under src/ that nothing compiles; whatever is in them builds clean and ships nowhere:\n  " +
                    stray.sorted().joinToString("\n  ") +
                    "\nIn :inspector-core and :inspector-noop there is no iosMain: iOS-only code goes in iosArm64Main " +
                    "and iosSimulatorArm64Main (docs/decisions/twins-and-api.md). Elsewhere, move the files into a " +
                    "source set the module declares, or declare one."
            )
        }
    }

    companion object {
        /** What the build passed in: each published module's directory and its Kotlin source set names. */
        fun sourceSets(): Map<String, Set<String>> {
            val raw = System.getProperty("inspector.sourceSets")
                ?: fail("inspector.sourceSets is not set; run these through `./gradlew :repo-checks:test`.")
            val modules = raw.split('|').filter { it.isNotBlank() }.associate { line ->
                val parts = line.split(' ')
                if (parts.size != 2) fail("malformed source set line from the build: '$line'")
                parts[0] to parts[1].split(',').filter { it.isNotEmpty() }.toSet()
            }
            // No modules, or a module with no source sets, would flag nothing or everything for the
            // wrong reason; either means the build stopped reporting them.
            if (modules.isEmpty()) fail("the build reported no published modules' source sets; refusing to check nothing")
            modules.filterValues { it.isEmpty() }.keys.firstOrNull()?.let { fail("the build reported no source sets for $it") }
            val published = ModuleGraphTest.structure().count { it.startsWith("module ") && it.endsWith(" published") }
            if (modules.size != published) fail("the build reported source sets for ${modules.size} modules, and $published are published")
            return modules
        }
    }
}
