package dev.inspector.daemon

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
) {
    val sessionsDir: Path get() = dataDir.resolve("sessions")
    val latestLink: Path get() = dataDir.resolve("latest")

    companion object {
        const val DEFAULT_PORT = 8099
        const val DEFAULT_MAX_SESSIONS = 100
        const val DEFAULT_MAX_MB = 300L

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
            )
        }
    }

    @Serializable
    data class FileConfig(
        val port: Int? = null,
        val maxSessions: Int? = null,
        val maxTotalMb: Long? = null,
    )
}

internal val InspectorDaemonJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}
