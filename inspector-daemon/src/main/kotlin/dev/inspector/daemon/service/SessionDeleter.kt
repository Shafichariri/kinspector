package dev.inspector.daemon.service

import dev.inspector.daemon.archive.DeletionCause
import dev.inspector.daemon.archive.SessionRepository
import java.nio.file.Path

/** What happened to one session a caller asked to delete. */
sealed interface SessionDeleteOutcome {
    /** The id as the caller named it, so a reply lines up with its request. */
    val id: String

    data class Deleted(override val id: String, val freedBytes: Long) : SessionDeleteOutcome

    /** A rule said no. [reason] is what the single route maps to its status. */
    data class Refused(override val id: String, val reason: Reason, val message: String) : SessionDeleteOutcome {
        enum class Reason { LATEST_ALIAS, ACTIVE }
    }

    /** Unknown, or unsafe: [SessionRepository.resolve] does not say which, and neither does this. */
    data class NotFound(override val id: String, val message: String) : SessionDeleteOutcome

    data class Failed(override val id: String, val message: String) : SessionDeleteOutcome
}

/** What a clear did: [kept] names the survivors, see `SessionDeletion` in `SessionRoutes.kt`. */
data class SessionClear(val deleted: List<String>, val kept: List<String>, val freedBytes: Long)

/**
 * Every deletion a person asks for: one session, a named batch, or the whole archive.
 *
 * The single route, the bulk route and `clear` all come here, so the rules that make deleting safe
 * exist once. A second copy of "refuse the session being written" is the one that would drift: it
 * is invisible until somebody deletes a folder from under an open writer and loses the traffic on
 * screen. [Retention] has its own pass because it chooses its victims; these callers are told.
 *
 * [activeIds] is read once per call, a batch included, so every item in one request is judged
 * against the same set.
 */
class SessionDeleter(
    private val repository: SessionRepository,
    /** Only for the failure message, which has always named the folder to check permissions on. */
    private val sessionsDir: Path,
    private val activeIds: () -> Set<String>,
) {

    fun deleteOne(id: String, cause: DeletionCause = DeletionCause.REQUESTED): SessionDeleteOutcome =
        deleteNamed(id, cause, activeIds())

    /**
     * Deletes each id in order, answering for each; a repeated id is answered once.
     *
     * Every one is [DeletionCause.REQUESTED], never [DeletionCause.CLEARED]: each id was named by
     * a person, and the log line is how a missing session is later attributed.
     */
    fun deleteMany(ids: List<String>): List<SessionDeleteOutcome> {
        val active = activeIds()
        return ids.distinct().map { deleteNamed(it, DeletionCause.REQUESTED, active) }
    }

    /**
     * Deletes every session except the ones still being written.
     *
     * Skipping the live session is not a convenience. Removing the folder underneath an open
     * writer corrupts the append stream and throws away the traffic on screen — the same rule
     * [Retention] enforces, for the same reason.
     */
    fun clear(): SessionClear {
        val active = activeIds()
        val deleted = mutableListOf<String>()
        val kept = mutableListOf<String>()
        var freed = 0L
        for (dir in repository.sessionDirs()) {
            when (val outcome = deleteDir(dir, DeletionCause.CLEARED, active)) {
                is SessionDeleteOutcome.Deleted -> {
                    deleted += outcome.id
                    freed += outcome.freedBytes
                }
                else -> kept += outcome.id
            }
        }

        // A total beside the per-session lines. This is the call that removes many at once, so it
        // is the one whose shape you want to recognise in a scrollback without counting.
        println(
            "inspector: cleared ${deleted.size} session(s), kept ${kept.size}, " +
                "freed ${freed / 1024} KB"
        )
        return SessionClear(deleted.sorted(), kept.sorted(), freed)
    }

    fun remaining(): Int = repository.sessionDirs().size

    /**
     * The `latest` alias is refused rather than resolved. It names a different folder depending on
     * when you call it, which is tolerable for a read and is not tolerable for a delete: the archive
     * it points at is the one you are most likely to still want.
     */
    private fun deleteNamed(id: String, cause: DeletionCause, active: Set<String>): SessionDeleteOutcome {
        if (id == LATEST) {
            return SessionDeleteOutcome.Refused(
                id,
                SessionDeleteOutcome.Refused.Reason.LATEST_ALIAS,
                "refusing to delete through the 'latest' alias — name the session explicitly",
            )
        }
        val dir = repository.resolve(id) ?: return SessionDeleteOutcome.NotFound(id, "no session '$id'")
        return deleteDir(dir, cause, active)
    }

    private fun deleteDir(dir: Path, cause: DeletionCause, active: Set<String>): SessionDeleteOutcome {
        val sessionId = dir.fileName.toString()
        if (sessionId in active) {
            return SessionDeleteOutcome.Refused(
                sessionId,
                SessionDeleteOutcome.Refused.Reason.ACTIVE,
                "session $sessionId is still being written; disconnect the app first",
            )
        }
        val bytes = repository.sessionBytes(dir)
        if (!repository.deleteSession(dir, cause)) {
            return SessionDeleteOutcome.Failed(
                sessionId,
                "could not delete $sessionId; check permissions on $sessionsDir",
            )
        }
        return SessionDeleteOutcome.Deleted(sessionId, bytes)
    }

    private companion object {
        const val LATEST = "latest"
    }
}
