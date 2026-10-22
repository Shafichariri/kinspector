package dev.inspector.repochecks

import java.io.File

/**
 * The `gradle.lockfile`s, read once here so `DependencyLockTest` and `PublishedPomTest` agree on
 * what a line means.
 */
object Lockfiles {

    /** One `group:module:version=configurations` line. */
    class Entry(val group: String, val module: String, val version: String, val configurations: Set<String>) {
        val coordinate: String get() = "$group:$module"
    }

    fun of(projectPath: String) = File(RepoFiles.root, projectPath.removePrefix(":").replace(':', '/') + "/gradle.lockfile")

    /** Every dependency line. Comments and the `empty=` line, which names configurations that lock nothing, are skipped. */
    fun entries(lockfile: File): List<Entry> = lockfile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("empty=") }
        .map { line ->
            val (gav, configurations) = line.split('=', limit = 2)
            val (group, module, version) = gav.split(':')
            Entry(group, module, version, configurations.split(',').filter { it.isNotEmpty() }.toSet())
        }
}
