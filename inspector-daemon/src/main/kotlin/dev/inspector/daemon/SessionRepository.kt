package dev.inspector.daemon

import dev.inspector.model.Filter
import dev.inspector.model.FilterContext
import dev.inspector.model.FilterParser
import dev.inspector.model.InspectorJson
import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.SessionMeta
import dev.inspector.model.Signal
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.useLines

/** Page of matching transactions, with enough counts for a UI to show "12 of 412". */
@Serializable
data class TransactionPage(
    val total: Int,
    val matched: Int,
    val items: List<NetworkTransaction>,
)

/** Compact session digest. Deliberately ~1 KB so an agent can ask this instead of reading rows. */
@Serializable
data class SessionSummary(
    val sessionId: String,
    val app: String,
    val device: String,
    val startedAt: String,
    val endedAt: String? = null,
    val txnCount: Int,
    val byStatusClass: Map<String, Int>,
    val byHost: Map<String, Int>,
    val slowest: List<SummaryRow>,
    val errors: List<SummaryRow>,
    val moreErrors: Int = 0,
    val markers: List<String>,
)

@Serializable
data class SummaryRow(
    val id: String,
    val method: String,
    val path: String,
    val status: Int? = null,
    val ms: Long? = null,
)

/**
 * All reads of the archive go through here.
 *
 * One implementation serves the REST API, the CLI's direct-file mode and the MCP tools, so a
 * filter evaluates identically no matter which asked — the alternative is three subtly different
 * behaviours discovered at the worst moment.
 */
class SessionRepository(private val config: DaemonConfig) {

    /**
     * Newest first.
     *
     * A session still recording has stale counts in `meta.json`, which is only rewritten every
     * 30 seconds — so for those the rows are counted from `index.jsonl` instead. Otherwise the
     * session picker would show a live session sitting at zero.
     */
    fun listSessions(): List<SessionMeta> = sessionDirs()
        .mapNotNull { dir ->
            val meta = readMeta(dir) ?: return@mapNotNull null
            if (meta.endedAt != null) meta else meta.withLiveCounts(dir)
        }
        .sortedByDescending { it.startedAt }

    private fun SessionMeta.withLiveCounts(dir: Path): SessionMeta {
        val txns = readTransactions(dir)
        return copy(txnCount = txns.size, errorCount = txns.count { it.isError })
    }

    /**
     * Resolves a session id to a directory, accepting the literal `latest`.
     *
     * Returns null for unknown or unsafe ids; [SessionLayout.isValidSessionId] is what stops a
     * crafted id from walking out of the archive.
     */
    fun resolve(id: String): Path? {
        if (id == "latest") {
            val link = config.latestLink
            if (link.exists()) return link.toRealPath()
            return sessionDirs().maxByOrNull { readMeta(it)?.startedAt ?: "" }
        }
        if (!SessionLayout.isValidSessionId(id)) return null
        val dir = config.sessionsDir.resolve(id)
        if (!dir.isDirectory()) return null
        // Belt and braces: confirm the resolved path really is inside the archive.
        val real = dir.toRealPath()
        if (!real.startsWith(config.sessionsDir.toRealPath())) return null
        return real
    }

    fun readMeta(sessionDir: Path): SessionMeta? {
        val file = SessionLayout.metaFile(sessionDir)
        if (!file.isRegularFile()) return null
        return runCatching { InspectorJson.decodeFromString<SessionMeta>(file.readText()) }.getOrNull()
    }

    fun readTransactions(sessionDir: Path): List<NetworkTransaction> {
        val file = SessionLayout.indexFile(sessionDir)
        if (!file.isRegularFile()) return emptyList()
        return file.useLines { lines ->
            lines.filter { it.isNotBlank() }
                .mapNotNull {
                    runCatching { InspectorJson.decodeFromString<NetworkTransaction>(it) }.getOrNull()
                }
                .toList()
        }
    }

    fun readMarkers(sessionDir: Path): List<Marker> {
        val file = SessionLayout.markersFile(sessionDir)
        if (!file.isRegularFile()) return emptyList()
        return file.useLines { lines ->
            lines.filter { it.isNotBlank() }
                .mapNotNull { runCatching { InspectorJson.decodeFromString<Marker>(it) }.getOrNull() }
                .toList()
        }
    }

    /**
     * Filtered, paginated read. Throws [dev.inspector.model.FilterParseException] on a bad
     * filter so callers can surface the parser's own message verbatim.
     */
    fun queryTransactions(
        sessionDir: Path,
        filterText: String = "",
        offset: Int = 0,
        limit: Int = 50,
    ): TransactionPage {
        val filter = if (filterText.isBlank()) Filter.MatchAll else FilterParser.parseOrThrow(filterText)
        val all = readTransactions(sessionDir)
        val context = FilterContext(readMarkers(sessionDir))
        val matched = all.filter { filter.matches(it, context) }
        // Newest first, ordered by the device's monotonic clock — never by wall clock.
        val ordered = matched.sortedByDescending { it.mono }
        return TransactionPage(
            total = all.size,
            matched = ordered.size,
            items = ordered.drop(offset).take(limit.coerceIn(1, 1000)),
        )
    }

    fun readTransaction(sessionDir: Path, txnId: String): NetworkTransaction? =
        readTransactions(sessionDir).firstOrNull { it.id == txnId }

    fun readSignals(sessionDir: Path): List<Signal> {
        val file = SessionLayout.signalsFile(sessionDir)
        if (!file.isRegularFile()) return emptyList()
        return file.useLines { lines ->
            lines.filter { it.isNotBlank() }
                .mapNotNull { runCatching { InspectorJson.decodeFromString<Signal>(it) }.getOrNull() }
                .toList()
        }
    }

    fun readSignal(sessionDir: Path, signalId: String): Signal? =
        readSignals(sessionDir).firstOrNull { it.id == signalId }

    fun readSignalPayload(sessionDir: Path, signalId: String): ByteArray? {
        if (!signalId.all { it.isLetterOrDigit() }) return null
        val file = SessionLayout.signalFile(sessionDir, signalId)
        if (!file.isRegularFile()) return null
        return file.readBytes()
    }

    /**
     * Latest observation per `(tag, name)`, newest first.
     *
     * A `cache` snapshot claims to be true from its `mono` until the next observation of the same
     * key, so the last row for a key is the current one. That rule is derivable from the stream
     * rather than stored, which is why it lives here and not in the schema.
     */
    fun currentSignals(sessionDir: Path, tag: String? = null): List<Signal> =
        readSignals(sessionDir)
            .filter { tag == null || it.tag.equals(tag, ignoreCase = true) }
            .groupBy { it.tag to it.name }
            .values
            .mapNotNull { rows -> rows.maxByOrNull { it.mono } }
            .sortedByDescending { it.mono }

    fun readBody(sessionDir: Path, txnId: String, side: String): ByteArray? {
        if (side != "req" && side != "res") return null
        if (!txnId.all { it.isLetterOrDigit() }) return null
        val file = SessionLayout.bodyFile(sessionDir, txnId, side)
        if (!file.isRegularFile()) return null
        return file.readBytes()
    }

    /**
     * Aggregates that answer most questions without reading any rows.
     *
     * This is the main affordance for agents: a session of 400 calls costs a kilobyte here
     * versus a hundred of them in raw index lines.
     */
    fun summarize(sessionDir: Path, maxErrors: Int = 20): SessionSummary? {
        val meta = readMeta(sessionDir) ?: return null
        val txns = readTransactions(sessionDir)
        val errors = txns.filter { it.isError }

        return SessionSummary(
            sessionId = meta.sessionId,
            app = meta.appId,
            device = meta.device,
            startedAt = meta.startedAt,
            endedAt = meta.endedAt,
            txnCount = txns.size,
            byStatusClass = txns.groupingBy { it.statusClass }.eachCount().toSortedMap().toMap(),
            byHost = txns.groupingBy { it.host }.eachCount()
                .entries.sortedByDescending { it.value }.take(10)
                .associate { it.key to it.value },
            slowest = txns.filter { it.ms != null }
                .sortedByDescending { it.ms }
                .take(5)
                .map { it.toRow() },
            errors = errors.take(maxErrors).map { it.toRow() },
            moreErrors = (errors.size - maxErrors).coerceAtLeast(0),
            markers = readMarkers(sessionDir).map { it.label }.distinct(),
        )
    }

    fun sessionDirs(): List<Path> {
        val root = config.sessionsDir
        if (!root.isDirectory()) return emptyList()
        return root.listDirectoryEntries().filter { it.isDirectory() }
    }

    fun sessionBytes(sessionDir: Path): Long =
        runCatching {
            java.nio.file.Files.walk(sessionDir).use { stream ->
                stream.filter { it.isRegularFile() }.mapToLong { it.fileSize() }.sum()
            }
        }.getOrDefault(0L)

    private fun NetworkTransaction.toRow() = SummaryRow(
        id = id,
        method = method,
        path = path,
        status = status,
        ms = ms,
    )
}
