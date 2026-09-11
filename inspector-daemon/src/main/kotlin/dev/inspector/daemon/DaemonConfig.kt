package dev.inspector.daemon

import dev.inspector.model.SignalTags
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Daemon settings, resolved from flags over `config.json` over defaults.
 *
 * Retention defaults are the agreed 100 sessions / 300 MB. Both are ceilings applied together:
 * pruning continues until the archive is under *both*.
 */
data class DaemonConfig(
    val port: Int = DEFAULT_PORT,
    val dataDir: Path = defaultDataDir(),
    val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    val maxTotalBytes: Long = DEFAULT_MAX_MB * 1024L * 1024L,
    /**
     * Rows kept per signal tag within one session. A tag with no entry is kept in full.
     *
     * Per tag rather than one number, because what a row costs varies by orders of magnitude: a
     * `screen` row is a route name and a few arguments, while `cache` and `state` rows carry the
     * value they observed.
     *
     * Overridable per tag from `config.json`, merging over [DEFAULT_SIGNAL_CAPS] — see
     * [mergeSignalCaps] for what an override may say.
     */
    val signalCaps: Map<String, Int> = DEFAULT_SIGNAL_CAPS,
) {
    val sessionsDir: Path get() = dataDir.resolve("sessions")
    val latestLink: Path get() = dataDir.resolve("latest")

    companion object {
        const val DEFAULT_PORT = 8099
        const val DEFAULT_MAX_SESSIONS = 100
        const val DEFAULT_MAX_MB = 300L

        /**
         * `screen` — and any tag this build has never heard of — stays uncapped: `screen` rows are
         * tiny and are the backbone of the merged timeline, so losing old ones would leave gaps in
         * the very thing signals exist to build.
         *
         * `cache` and `state` share a number because they cost the same thing: both carry an
         * observed value. Treat it as a runaway guard for one unusually chatty session rather than
         * a size budget — the 100-session and 300 MB archive ceilings are what actually bound the
         * disk, and they apply across sessions where this cannot.
         *
         * `cache` was 20 while a cache signal meant one whole-cache snapshot, where 20 rows bought
         * 20 points in time. Since `INTEGRATION.md` v18 the documented recipe emits a row per entry
         * write, removal and scope invalidation, so 20 rows no longer spans 20 moments — it does
         * not reliably span 20 distinct *keys*, and past that the browser's latest-per-key view
         * starts dropping keys outright rather than shortening their history. Sessions from a
         * consumer emitting per-entry rows were observed sitting at exactly the old cap while
         * their `state` counts ran into the hundreds: it bound routinely, not at an extreme.
         * `state`'s 500 has not been hit.
         */
        val DEFAULT_SIGNAL_CAPS: Map<String, Int> = mapOf(
            SignalTags.CACHE to 500,
            SignalTags.STATE to 500,
        )

        fun defaultDataDir(): Path = Path(System.getProperty("user.home")).resolve(".inspector")

        /**
         * Reads `config.json` from [dataDir] if present, then applies any explicit overrides.
         * A malformed config file is reported and ignored rather than preventing startup — a
         * debugging tool that will not start because of its own settings file is worse than one
         * running on defaults.
         */
        fun load(
            dataDir: Path = defaultDataDir(),
            port: Int? = null,
            maxSessions: Int? = null,
            maxMb: Long? = null,
            signalCaps: Map<String, Int?>? = null,
        ): DaemonConfig {
            val file = dataDir.resolve("config.json")
            val onDisk = if (file.exists()) {
                runCatching { InspectorDaemonJson.decodeFromString<FileConfig>(file.readText()) }
                    .onFailure { System.err.println("inspector: ignoring malformed ${file}: ${it.message}") }
                    .getOrNull()
            } else null

            return DaemonConfig(
                port = port ?: onDisk?.port ?: DEFAULT_PORT,
                dataDir = dataDir,
                maxSessions = maxSessions ?: onDisk?.maxSessions ?: DEFAULT_MAX_SESSIONS,
                maxTotalBytes = (maxMb ?: onDisk?.maxTotalMb ?: DEFAULT_MAX_MB) * 1024L * 1024L,
                signalCaps = mergeSignalCaps(signalCaps ?: onDisk?.signalCaps),
            )
        }

        /**
         * Folds `signalCaps` overrides into [DEFAULT_SIGNAL_CAPS].
         *
         * Overrides **merge** rather than replace, so raising the cap on one tag does not silently
         * uncap every other one. Replacing would be the simpler rule, but its failure mode is
         * quiet: the archive keeps more than you asked for and nothing says so until the disk
         * fills. Merging's failure mode is loud — you set a cap and it is still there.
         *
         * What an override may say, per tag:
         *
         * - a number — cap that tag at it. `0` is honoured: "do not archive this tag" is a real
         *   thing to want, and it is not the same as removing the entry.
         * - `null` — uncap it. Once merging is the rule this is the only way to say it, so
         *   `{"cache": null}` is the documented spelling for "keep every cache row".
         * - a negative number — reported on stderr and ignored, like a malformed file. Nothing
         *   sensible is meant by it, and [Retention.pruneSignals] runs on session close where a
         *   thrown exception would cost the user the session they just recorded.
         *
         * Tags match case-insensitively, matching how [Retention.pruneSignals] reads them, so
         * `"CACHE"` replaces the `cache` default instead of sitting beside it.
         */
        internal fun mergeSignalCaps(overrides: Map<String, Int?>?): Map<String, Int> {
            if (overrides.isNullOrEmpty()) return DEFAULT_SIGNAL_CAPS

            val merged = DEFAULT_SIGNAL_CAPS.mapKeys { it.key.lowercase() }.toMutableMap()
            for ((tag, cap) in overrides) {
                val key = tag.lowercase()
                when {
                    cap == null -> merged -= key
                    cap < 0 -> System.err.println(
                        "inspector: ignoring negative signalCaps entry for '$tag' ($cap)"
                    )
                    else -> merged[key] = cap
                }
            }
            return merged
        }
    }

    @Serializable
    data class FileConfig(
        val port: Int? = null,
        val maxSessions: Int? = null,
        val maxTotalMb: Long? = null,
        /** Nullable values on purpose — an explicit `null` uncaps a tag. See [mergeSignalCaps]. */
        val signalCaps: Map<String, Int?>? = null,
    )
}

internal val InspectorDaemonJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}
