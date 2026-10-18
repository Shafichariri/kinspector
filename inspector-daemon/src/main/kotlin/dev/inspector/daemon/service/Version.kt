package dev.inspector.daemon.service

/**
 * The daemon's own version, or `unknown`.
 *
 * Read from the jar manifest, which `:inspector-daemon`'s build stamps. A daemon run from a
 * classes directory — every test, and `gradlew run` — has no manifest to read, so this says so
 * rather than inventing a number that would then travel inside an exported HAR.
 */
internal fun daemonVersion(): String =
    Har::class.java.`package`?.implementationVersion ?: "unknown"
