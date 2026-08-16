package dev.inspector.daemon

import dev.inspector.model.Hello
import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.SESSION_RESUME_GRACE_MS
import dev.inspector.model.SessionMeta
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.time.Instant

/** Result of a `hello` handshake. */
data class OpenedSession(val sessionId: String, val resumed: Boolean)

/** Live events broadcast to connected web UIs. */
sealed interface LiveEvent {
    data class Transaction(val sessionId: String, val txn: NetworkTransaction) : LiveEvent
    data class MarkerAdded(val sessionId: String, val marker: Marker) : LiveEvent
    data class SessionStarted(val meta: SessionMeta) : LiveEvent
    data class SessionEnded(val sessionId: String) : LiveEvent
}

/**
 * Owns the writers for sessions currently being recorded, and decides whether a reconnecting
 * client continues an existing session or starts a new one.
 *
 * All mutation is guarded by one lock. Ingest is not the hot path — the device already absorbed
 * the burst behind its own queue — so a single lock is simpler than per-session striping and
 * cannot deadlock.
 */
class SessionManager(
    private val config: DaemonConfig,
    private val repository: SessionRepository,
    private val retention: Retention,
    private val now: () -> Instant = Instant::now,
) {
    private val lock = Any()
    private val open = mutableMapOf<String, SessionWriter>()

    private val _live = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 256)
    val live: SharedFlow<LiveEvent> = _live

    /** Session ids currently being written; never pruned. */
    fun activeSessionIds(): Set<String> = synchronized(lock) { open.keys.toSet() }

    /**
     * Handles `hello`.
     *
     * A [Hello.resumeSessionId] is honoured only if that session exists, is not already open, and
     * closed within [SESSION_RESUME_GRACE_MS]. The grace exists so a brief disconnect — a laptop
     * sleeping, the daemon restarting — continues one session rather than fragmenting a debugging
     * run into pieces; a genuine relaunch beyond it deserves its own folder.
     */
    fun openSession(hello: Hello): OpenedSession = synchronized(lock) {
        val resumeId = hello.resumeSessionId
        if (resumeId != null && resumeId !in open) {
            val dir = repository.resolve(resumeId)
            val meta = dir?.let { repository.readMeta(it) }
            if (dir != null && meta != null && isResumable(meta)) {
                val writer = SessionWriter.reopen(dir, meta.copy(endedAt = null))
                open[resumeId] = writer
                SessionWriter.updateLatestLink(config.dataDir, dir)
                return OpenedSession(resumeId, resumed = true)
            }
        }

        val startedAt = now()
        val sessionId = uniqueSessionId(hello, startedAt)
        val dir = config.sessionsDir.resolve(sessionId)
        val meta = hello.client.toSessionMeta(sessionId, startedAt.toString())
        val writer = SessionWriter(dir, meta)
        open[sessionId] = writer
        SessionWriter.updateLatestLink(config.dataDir, dir)
        _live.tryEmit(LiveEvent.SessionStarted(meta))
        OpenedSession(sessionId, resumed = false)
    }

    fun append(sessionId: String, txn: NetworkTransaction, reqBody: ByteArray?, resBody: ByteArray?) {
        val writer = synchronized(lock) { open[sessionId] } ?: return
        synchronized(writer) { writer.append(txn, reqBody, resBody) }
        _live.tryEmit(LiveEvent.Transaction(sessionId, txn))
    }

    fun append(sessionId: String, marker: Marker) {
        val writer = synchronized(lock) { open[sessionId] } ?: return
        synchronized(writer) { writer.append(marker) }
        _live.tryEmit(LiveEvent.MarkerAdded(sessionId, marker))
    }

    /** Periodic refresh so a long-running session's counts stay roughly current on disk. */
    fun flushMeta() {
        val writers = synchronized(lock) { open.values.toList() }
        writers.forEach { writer -> synchronized(writer) { writer.flushMetaIfDirty() } }
    }

    /** Closes the session and applies retention. Safe to call for an unknown id. */
    fun closeSession(sessionId: String) {
        val writer = synchronized(lock) { open.remove(sessionId) } ?: return
        synchronized(writer) { writer.close(now()) }
        _live.tryEmit(LiveEvent.SessionEnded(sessionId))
        retention.prune(activeSessionId = null)
    }

    fun closeAll() {
        val ids = synchronized(lock) { open.keys.toList() }
        ids.forEach { closeSession(it) }
    }

    /**
     * Whether a closed session may still be appended to.
     *
     * Decided from `endedAt` on disk rather than in-memory bookkeeping, because the daemon
     * restarting is one of the very cases the grace period exists to cover — an in-memory map
     * is empty in the new process and would silently refuse every resume.
     *
     * A null `endedAt` means the daemon did not shut down cleanly; that is treated as resumable.
     * The risk is bounded because a client can only ask for a session id it was handed during
     * this process lifetime — the id is never persisted on the device.
     */
    private fun isResumable(meta: SessionMeta): Boolean {
        val endedAt = meta.endedAt ?: return true
        val ended = runCatching { Instant.parse(endedAt) }.getOrNull() ?: return false
        return (now().toEpochMilli() - ended.toEpochMilli()) <= SESSION_RESUME_GRACE_MS
    }

    /**
     * Session ids embed a whole-second timestamp, so two launches inside the same second would
     * otherwise collide and append into one folder.
     */
    private fun uniqueSessionId(hello: Hello, startedAt: Instant): String {
        val base = SessionLayout.sessionId(hello.client, startedAt)
        if (!config.sessionsDir.resolve(base).toFile().exists() && base !in open) return base
        var suffix = 2
        while (true) {
            val candidate = "$base-$suffix"
            if (!config.sessionsDir.resolve(candidate).toFile().exists() && candidate !in open) {
                return candidate
            }
            suffix++
        }
    }
}
