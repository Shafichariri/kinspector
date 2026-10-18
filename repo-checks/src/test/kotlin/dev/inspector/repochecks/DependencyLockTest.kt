package dev.inspector.repochecks

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The lockfiles themselves. Gradle's STRICT locking is what fails a build whose resolved graph has
 * drifted from its lockfile; these two checks cover what Gradle cannot know.
 */
class DependencyLockTest {

    /** One `group:module:version=configurations` line, version and configurations dropped. */
    private fun modulesIn(lockfile: File): List<String> = lockfile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("empty=") }
        .map { it.substringBefore('=').substringBeforeLast(':') }

    private fun lockfileOf(projectPath: String) = File(RepoFiles.root, projectPath.removePrefix(":").replace(':', '/') + "/gradle.lockfile")

    /**
     * A module that ships with no lockfile ships unlocked. STRICT catches a deleted lockfile only
     * for configurations still marked locked, so a module that stopped applying the lock setup --
     * by no longer publishing through the root's `maven-publish` hook, say -- would lose its lock
     * with nothing failing. The daemon is here because its runtime classpath is the release zip.
     */
    @Test
    fun every_shipped_module_has_a_lockfile() {
        val shipped = ModuleGraphTest.structure()
            .filter { it.startsWith("module ") && it.endsWith(" published") }
            .map { it.split(' ')[1] } + ":inspector-daemon"
        val problems = shipped.mapNotNull { path ->
            val file = lockfileOf(path)
            when {
                !file.isFile -> "$path has no ${file.relativeTo(RepoFiles.root)}"
                modulesIn(file).isEmpty() -> "$path has a lockfile that locks nothing"
                else -> null
            }
        }
        if (problems.isNotEmpty()) {
            fail(problems.joinToString("\n") + "\nRegenerate on macOS (the iOS klibs only resolve there):\n  $WRITE_LOCKS")
        }
    }

    /**
     * "`:inspector-model` may depend only on kotlinx-serialization." It is the module the device,
     * the daemon, the CLI and the MCP server all share, so anything it pulls in is pulled in
     * everywhere. A lock regenerated after adding a dependency is perfectly consistent, which is
     * why STRICT alone cannot hold this rule: it has to be asked of what the lock contains.
     */
    @Test
    fun the_model_resolves_serialization_and_the_stdlib_only() {
        val lockfile = lockfileOf(":inspector-model")
        if (!lockfile.isFile) fail("missing ${lockfile.path}")
        val modules = modulesIn(lockfile).distinct()
        if (modules.isEmpty()) fail("${lockfile.path} locks nothing; refusing to call that clean")
        val foreign = modules.filter { module -> MODEL_ALLOWED.keys.none { allowed -> matches(allowed, module) } }
        if (foreign.isNotEmpty()) {
            fail(
                ":inspector-model resolves dependencies outside its allowlist: ${foreign.joinToString()}.\n" +
                    "The model may depend only on kotlinx-serialization (and what that and the stdlib bring)."
            )
        }
    }

    private fun matches(pattern: String, module: String) =
        if (pattern.endsWith("*")) module.startsWith(pattern.dropLast(1)) else module == pattern

    private companion object {
        const val WRITE_LOCKS = "./gradlew resolveLockedConfigurations :inspector-daemon:dependencies --configuration runtimeClasspath --write-locks"

        /** Every module the model may resolve, each with why. Filled from the generated lockfile. */
        val MODEL_ALLOWED = mapOf(
            "org.jetbrains.kotlinx:kotlinx-serialization-json*" to "the one dependency the model declares",
            "org.jetbrains.kotlinx:kotlinx-serialization-core*" to "what serialization-json is built on",
            "org.jetbrains.kotlinx:kotlinx-serialization-bom" to "the platform serialization-json aligns its versions with",
            "org.jetbrains.kotlin:kotlin-stdlib" to "every Kotlin module resolves it",
            "org.jetbrains:annotations" to "the stdlib's own dependency on the JVM and Android",
        )
    }
}
