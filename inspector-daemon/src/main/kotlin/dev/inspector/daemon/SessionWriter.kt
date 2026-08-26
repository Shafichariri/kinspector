package dev.inspector.daemon

import dev.inspector.model.InspectorJson
import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.SessionMeta
import dev.inspector.model.Signal
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.moveTo

/**
 * Owns one session folder on disk.
 *
 * Append-and-flush per line, no fsync: losing the last few rows of a debug session to a hard
 * kill is an acceptable trade for not paying a disk sync on every captured request. `meta.json`
 * is written atomically (temp file plus rename) because a torn meta file would make a session
 * unreadable, while a truncated final `index.jsonl` line costs only that line.
 *
 * Not thread-safe; the daemon gives each connection its own writer and serialises writes.
 */
class SessionWriter(
    val sessionDir: Path,
    private var meta: SessionMeta,
) {
    private val indexWriter: BufferedWriter
    private val markersWriter: BufferedWriter
    private val signalsWriter: BufferedWriter

    private var txnCount = meta.txnCount
    private var errorCount = meta.errorCount
    private var dirtySinceFlush = false

    init {
        sessionDir.createDirectories()
        SessionLayout.bodiesDir(sessionDir).createDirectories()
        SessionLayout.signalsDir(sessionDir).createDirectories()
        indexWriter = openAppend(SessionLayout.indexFile(sessionDir))
        markersWriter = openAppend(SessionLayout.markersFile(sessionDir))
        signalsWriter = openAppend(SessionLayout.signalsFile(sessionDir))
        writeMeta()
    }

    val sessionId: String get() = meta.sessionId

    /**
     * Persists one attempt, writing its bodies to `bodies/` and rewriting the `*BodyRef` fields
     * so the stored row points at them. Bodies arrive inline over the wire specifically so the
     * device never has to touch a filesystem.
     *
     * Returns the row **as stored**, refs included. Callers must broadcast this rather than the
     * incoming [txn]: the wire contract sends `*BodyRef` as null, so a live viewer handed the
     * incoming row sees every body as absent even though it is already on disk.
     */
    fun append(txn: NetworkTransaction, reqBody: ByteArray?, resBody: ByteArray?): NetworkTransaction {
        var stored = txn

        if (reqBody != null) {
            Files.write(SessionLayout.bodyFile(sessionDir, txn.id, "req"), reqBody)
            stored = stored.copy(reqBodyRef = SessionLayout.bodyRef(txn.id, "req"))
        }
        if (resBody != null) {
            Files.write(SessionLayout.bodyFile(sessionDir, txn.id, "res"), resBody)
            stored = stored.copy(resBodyRef = SessionLayout.bodyRef(txn.id, "res"))
        }

        indexWriter.write(InspectorJson.encodeToString(stored))
        indexWriter.newLine()
        indexWriter.flush()

        txnCount++
        if (stored.isError) errorCount++
        dirtySinceFlush = true

        return stored
    }

    /**
     * Persists one observation, writing its payload to `signals/` and rewriting [Signal.dataRef]
     * so the stored row points at it. The payload arrives beside the row over the wire, exactly as
     * bodies do, so the device never has to touch a filesystem.
     *
     * Returns the row **as stored**, ref included, for the same reason the transaction overload
     * does: the wire contract sends `dataRef` as null, so a live viewer handed the incoming row
     * sees every payload as absent while it sits on disk. That is defect #1, and this path has the
     * identical shape.
     */
    fun append(signal: Signal, data: ByteArray?): Signal {
        var stored = signal.copy(data = null)

        if (data != null) {
            Files.write(SessionLayout.signalFile(sessionDir, signal.id), data)
            stored = stored.copy(dataRef = SessionLayout.signalRef(signal.id))
        }

        signalsWriter.write(InspectorJson.encodeToString(stored))
        signalsWriter.newLine()
        signalsWriter.flush()
        dirtySinceFlush = true

        return stored
    }

    fun append(marker: Marker) {
        markersWriter.write(InspectorJson.encodeToString(marker))
        markersWriter.newLine()
        markersWriter.flush()
        dirtySinceFlush = true
    }

    /** Periodic meta refresh so counts stay roughly current for a session still in progress. */
    fun flushMetaIfDirty() {
        if (!dirtySinceFlush) return
        writeMeta()
        dirtySinceFlush = false
    }

    fun close(endedAt: Instant = Instant.now()) {
        meta = meta.copy(endedAt = endedAt.toString())
        writeMeta()
        runCatching { indexWriter.close() }
        runCatching { markersWriter.close() }
        runCatching { signalsWriter.close() }
    }

    private fun writeMeta() {
        meta = meta.copy(txnCount = txnCount, errorCount = errorCount)
        val target = SessionLayout.metaFile(sessionDir)
        val temp = sessionDir.resolve("meta.json.tmp")
        Files.writeString(temp, InspectorJson.encodeToString(meta))
        temp.moveTo(target, overwrite = true)
    }

    private fun openAppend(path: Path): BufferedWriter =
        Files.newBufferedWriter(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        )

    companion object {
        /**
         * Points `~/.inspector/latest` at [sessionDir].
         *
         * Best-effort: filesystems without symlink support simply do not get the convenience.
         * The link is what lets a human or an agent say "the session I just ran" without
         * looking up a name.
         */
        fun updateLatestLink(dataDir: Path, sessionDir: Path) {
            val link = dataDir.resolve("latest")
            runCatching {
                if (link.exists(java.nio.file.LinkOption.NOFOLLOW_LINKS)) link.deleteIfExists()
                Files.createSymbolicLink(link, dataDir.relativize(sessionDir))
            }.onFailure {
                System.err.println("inspector: could not update 'latest' link: ${it.message}")
            }
        }

        /** Re-opens an existing session for appending, used by the resume path. */
        fun reopen(sessionDir: Path, meta: SessionMeta): SessionWriter =
            SessionWriter(sessionDir, meta)

        fun copyFileAtomically(from: Path, to: Path) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
