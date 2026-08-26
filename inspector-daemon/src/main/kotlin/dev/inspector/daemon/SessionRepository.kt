package dev.inspector.daemon

import dev.inspector.model.Filter
import dev.inspector.model.FilterContext
import dev.inspector.model.FilterParser
import dev.inspector.model.InspectorJson
// Extension: `matches` on the interface takes a Row; this is the transaction overload.
import dev.inspector.model.matches
import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.SessionMeta
import dev.inspector.model.Signal
import dev.inspector.model.SignalTags
import dev.inspector.model.SignalTrigger
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.useLines

/** One page of signal rows, shaped like [TransactionPage] so consumers read them the same way. */
@Serializable
data class SignalPage(
    val total: Int,
    val matched: Int,
    val items: List<Signal>,
)

/**
 * One line of a merged timeline.
 *
 * Deliberately flat and small: this is what an agent reads to orient itself, and a compact line
 * per event is what keeps a whole session inside a context window. Follow an `id` into
 * `get_signal` or `get_body` for the detail.
 */
@Serializable
data class TimelineEntry(
    val kind: String,
    val mono: Long,
    val ts: String,
    val id: String? = null,
    val label: String,
    val detail: String? = null,
    val trigger: String? = null,
) {
    companion object {
        fun of(txn: NetworkTransaction): TimelineEntry = TimelineEntry(
            kind = "txn",
            mono = txn.mono,
            ts = txn.ts,
            id = txn.id,
            label = "${txn.method} ${txn.host}${txn.path}",
            detail = txn.status?.toString() ?: txn.error ?: "in flight",
        )

        fun of(signal: Signal): TimelineEntry = TimelineEntry(
            kind = "signal",
            mono = signal.mono,
            ts = signal.ts,
            id = signal.id,
            label = "${signal.tag}/${signal.name}",
            detail = if (signal.dataRef != null) "payload ${signal.bytes}B" else null,
            // Provenance travels with the row. An agent that cannot tell an app-start snapshot
            // from a fresh read will report stale state as live.
            trigger = if (signal.trigger == SignalTrigger.Request) "request" else "app",
        )

        fun of(marker: Marker): TimelineEntry = TimelineEntry(
            kind = "marker",
            mono = marker.mono,
            ts = marker.ts,
            label = marker.label,
            detail = marker.source,
        )
    }
}

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
    /** Signal rows per tag. Absent when the app records no signals, keeping the digest small. */
    val signalsByTag: Map<String, Int> = emptyMap(),
    /** The last `screen` observation, which is the one orienting question worth answering here. */
    val currentScreen: String? = null,
    /** `tag/name` pairs a pull has been answered for this session. */
    val providersSeen: List<String> = emptyList(),
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

    /**
     * Filtered, paginated read of the signal stream. Same grammar, same errors as
     * [queryTransactions] — a `status:` term simply matches nothing here, by the exclusion rule.
     */
    fun querySignals(
        sessionDir: Path,
        filterText: String = "",
        offset: Int = 0,
        limit: Int = 50,
    ): SignalPage {
        val filter = if (filterText.isBlank()) Filter.MatchAll else FilterParser.parseOrThrow(filterText)
        val all = readSignals(sessionDir)
        val context = FilterContext(readMarkers(sessionDir))
        val matched = all.filter { filter.matches(it, context) }
        val ordered = matched.sortedByDescending { it.mono }
        return SignalPage(
            total = all.size,
            matched = ordered.size,
            items = ordered.drop(offset).take(limit.coerceIn(1, 1000)),
        )
    }

    /**
     * Transactions, signals and markers merged by `mono` — the tool this feature exists for.
     *
     * Merged on read rather than stored merged, so every existing grep and route keeps working.
     * Ordered **oldest first**: a timeline is read forwards, unlike the row listings, which put
     * the newest first because that is what a live tail wants.
     */
    fun timeline(
        sessionDir: Path,
        filterText: String = "",
        since: Long? = null,
        until: Long? = null,
        limit: Int = 200,
    ): List<TimelineEntry> {
        val filter = if (filterText.isBlank()) Filter.MatchAll else FilterParser.parseOrThrow(filterText)
        val markers = readMarkers(sessionDir)
        val context = FilterContext(markers)

        val entries = buildList {
            readTransactions(sessionDir)
                .filter { filter.matches(it, context) }
                .forEach { add(TimelineEntry.of(it)) }
            readSignals(sessionDir)
                .filter { filter.matches(it, context) }
                .forEach { add(TimelineEntry.of(it)) }
            // Markers go through the same filter. They carry only `mono` and a label, so every
            // typed term drops them while since: and text: still reach them — which is what keeps
            // a timeline cut at a marker from losing the marker it was cut at.
            markers.filter { filter.matches(it, context) }
                .forEach { add(TimelineEntry.of(it)) }
        }

        return entries
            .filter { (since == null || it.mono >= since) && (until == null || it.mono <= until) }
            .sortedBy { it.mono }
            .take(limit.coerceIn(1, 2000))
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
        val signals = readSignals(sessionDir)
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
            signalsByTag = signals.groupingBy { it.tag }.eachCount().toSortedMap().toMap(),
            currentScreen = signals.filter { it.tag == SignalTags.SCREEN }
                .maxByOrNull { it.mono }?.name,
            // Only pulls prove a provider exists; a pushed row says nothing about one.
            providersSeen = signals.filter { it.trigger == SignalTrigger.Request }
                .map { "${it.tag}/${it.name}" }
                .distinct()
                .sorted(),
        )
    }

    fun sessionDirs(): List<Path> {
        val root = config.sessionsDir
        if (!root.isDirectory()) return emptyList()
        return root.listDirectoryEntries().filter { it.isDirectory() }
    }

    /**
     * Removes a session folder and everything under it. Returns false if anything survived.
     *
     * Repairs the `latest` link rather than leaving it dangling. Every agent and every CLI
     * subcommand accepts the literal `latest`, so a link pointing at a folder that no longer
     * exists does not fail one session — it fails "the session I just ran" for the whole archive.
     *
     * Refusing to delete a session that is still being written is the caller's job. [Retention]
     * and the REST layer each learn the active id a different way, and neither can be derived
     * from the folder alone.
     */
    fun deleteSession(sessionDir: Path): Boolean {
        // Resolved before the delete, while the link still has something to resolve to.
        val wasLatest = runCatching {
            config.latestLink.exists(LinkOption.NOFOLLOW_LINKS) &&
                config.latestLink.toRealPath() == sessionDir.toRealPath()
        }.getOrDefault(false)

        val gone = deleteRecursively(sessionDir)
        if (gone && wasLatest) repointLatestLink()
        return gone
    }

    /** Points `latest` at the newest surviving session, or removes it when none is left. */
    private fun repointLatestLink() {
        val newest = sessionDirs().maxByOrNull { readMeta(it)?.startedAt ?: "" }
        if (newest == null) {
            runCatching { config.latestLink.deleteIfExists() }
            return
        }
        SessionWriter.updateLatestLink(config.dataDir, newest)
    }

    /** Depth-first delete. Shared with [Retention], which prunes for a different reason. */
    fun deleteRecursively(dir: Path): Boolean = runCatching {
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
        !Files.exists(dir)
    }.getOrElse {
        System.err.println("inspector: could not delete $dir: ${it.message}")
        false
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
