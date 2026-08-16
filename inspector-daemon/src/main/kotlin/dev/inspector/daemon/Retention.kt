package dev.inspector.daemon

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** What a prune pass did. Returned rather than only logged so tests can assert on it. */
data class PruneResult(
    val prunedSessionIds: List<String>,
    val remainingSessions: Int,
    val remainingBytes: Long,
)

/**
 * Enforces the archive ceilings: at most `maxSessions` folders and `maxTotalBytes` on disk.
 *
 * Both limits apply together — pruning continues until the archive is under each. Runs on daemon
 * start and after every session closes.
 */
class Retention(
    private val config: DaemonConfig,
    private val repository: SessionRepository,
) {

    /**
     * Deletes oldest sessions until both ceilings are satisfied.
     *
     * [activeSessionId] is never deleted regardless of the limits: removing the session currently
     * being written would corrupt an open writer and lose the traffic the user is looking at
     * right now.
     */
    fun prune(activeSessionId: String? = null): PruneResult {
        val candidates = repository.sessionDirs()
            .mapNotNull { dir ->
                val meta = repository.readMeta(dir) ?: return@mapNotNull null
                Candidate(dir, meta.sessionId, meta.startedAt, repository.sessionBytes(dir))
            }
            .sortedBy { it.startedAt } // oldest first

        var sessions = candidates.size
        var bytes = candidates.sumOf { it.bytes }
        val pruned = mutableListOf<String>()

        for (candidate in candidates) {
            val withinLimits = sessions <= config.maxSessions && bytes <= config.maxTotalBytes
            if (withinLimits) break
            if (candidate.sessionId == activeSessionId) continue

            if (deleteRecursively(candidate.dir)) {
                pruned += candidate.sessionId
                sessions--
                bytes -= candidate.bytes
                println("inspector: pruned session ${candidate.sessionId} (${candidate.bytes / 1024} KB)")
            }
        }

        return PruneResult(pruned, sessions, bytes)
    }

    private fun deleteRecursively(dir: Path): Boolean = runCatching {
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { if (path.isRegularFile() || true) Files.deleteIfExists(path) }
            }
        }
        !Files.exists(dir)
    }.getOrElse {
        System.err.println("inspector: could not prune $dir: ${it.message}")
        false
    }

    private data class Candidate(
        val dir: Path,
        val sessionId: String,
        val startedAt: String,
        val bytes: Long,
    )
}
