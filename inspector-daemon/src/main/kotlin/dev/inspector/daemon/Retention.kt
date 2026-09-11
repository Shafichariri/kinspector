package dev.inspector.daemon

import dev.inspector.model.InspectorJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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

            // The line this used to print itself now comes from the primitive, which logs every
            // removal and names its cause -- one format, and no path that can delete in silence.
            if (repository.deleteRecursively(candidate.dir, DeletionCause.PRUNED, candidate.bytes)) {
                pruned += candidate.sessionId
                sessions--
                bytes -= candidate.bytes
            }
        }

        return PruneResult(pruned, sessions, bytes)
    }

    /**
     * Trims `signals.jsonl` to [DaemonConfig.signalCaps] rows per tag, deleting the payload files
     * of the rows it drops.
     *
     * Rewrites the file, so it must only run when nothing holds it open for append — that is, on
     * session close. The never-prune-the-active-session rule stands here too: this is called after
     * the writer is closed and the session has left the open map.
     *
     * A tag with no cap is kept in full, including one this build has never heard of. Dropping an
     * unknown tag would silently discard whatever an app chose to record.
     */
    fun pruneSignals(sessionDir: Path): Int {
        val caps = config.signalCaps
        if (caps.isEmpty()) return 0

        val all = repository.readSignals(sessionDir)
        if (all.isEmpty()) return 0

        val keep = all.groupBy { it.tag.lowercase() }
            .flatMap { (tag, rows) ->
                val cap = caps.entries.firstOrNull { it.key.equals(tag, ignoreCase = true) }?.value
                // coerced, not trusted: this runs on session close, where a thrown `take(-1)` would
                // cost the user the session they just recorded. `load` rejects negatives loudly.
                if (cap == null || rows.size <= cap) rows
                else rows.sortedByDescending { it.mono }.take(cap.coerceAtLeast(0))
            }
            .toSet()

        val dropped = all.filterNot { it in keep }
        if (dropped.isEmpty()) return 0

        // Preserve the original append order for what survives: the file is read as a timeline.
        val surviving = all.filter { it in keep }
        val rewritten = surviving.joinToString("") { InspectorJson.encodeToString(it) + "\n" }
        runCatching {
            val temp = sessionDir.resolve("signals.jsonl.tmp")
            Files.writeString(temp, rewritten)
            Files.move(temp, SessionLayout.signalsFile(sessionDir), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            System.err.println("inspector: could not prune signals: ${it.message}")
            return 0
        }

        for (row in dropped) {
            runCatching { Files.deleteIfExists(SessionLayout.signalFile(sessionDir, row.id)) }
        }
        return dropped.size
    }

    private data class Candidate(
        val dir: Path,
        val sessionId: String,
        val startedAt: String,
        val bytes: Long,
    )
}
