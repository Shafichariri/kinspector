package dev.inspector.daemon

import dev.inspector.model.InspectorJson
import dev.inspector.model.Marker
import dev.inspector.model.NetworkTransaction
import dev.inspector.model.SessionMeta
import dev.inspector.model.Signal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.Instant
import java.util.Base64

/**
 * A captured body or signal payload, carried inside an export.
 *
 * `utf8` when the bytes decode strictly as UTF-8, and then [text] is exactly those characters;
 * `base64` otherwise. The strict decoder is the one HAR uses, for the same reason: decoding a PNG
 * leniently would not fail, it would substitute U+FFFD and present a page of replacement
 * characters as the captured body.
 *
 * Only present when the archive holds the bytes. Why a body is absent — not captured, streamed,
 * or simply empty — is already on the row as `reqBodyOmitted`/`resBodyOmitted` and the byte
 * counts, and a reader must take it from there rather than from a second copy that could disagree.
 */
@Serializable
data class ExportedBody(val encoding: String, val text: String) {
    companion object {
        const val UTF8 = "utf8"
        const val BASE64 = "base64"

        fun of(bytes: ByteArray): ExportedBody =
            bytes.decodeUtf8OrNull()?.let { ExportedBody(UTF8, it) }
                ?: ExportedBody(BASE64, Base64.getEncoder().encodeToString(bytes))
    }
}

/**
 * One archived row with its bodies beside it.
 *
 * [txn] is the row exactly as `index.jsonl` holds it, rather than a flattened copy, so the export
 * is literally the archive plus bodies and cannot grow a second, drifting description of a call.
 */
@Serializable
data class ExportedTransaction(
    val txn: NetworkTransaction,
    val reqBody: ExportedBody? = null,
    val resBody: ExportedBody? = null,
)

@Serializable
data class Exporter(val name: String, val version: String)

/**
 * One whole session as a single document: the thing all three export formats are rendered from.
 *
 * Built once, by [SessionExport.build], so the JSON an agent reads, the Markdown a person reads
 * and the HTML archive cannot disagree about what "the whole session" contains.
 *
 * No defaults on the identifying fields: `InspectorJson` does not encode defaults, and a document
 * that silently dropped its own `format` would be unidentifiable once saved to disk.
 */
@Serializable
data class SessionExportDocument(
    /** Always [SessionExport.FORMAT]. Lets a reader recognise the file without its name. */
    val format: String,
    /** Bumped when a field is removed or repurposed, never for an addition. */
    val formatVersion: Int,
    /** Host wall clock when the export ran. Nothing to do with the device's `ts` or `mono`. */
    val exportedAt: String,
    val exporter: Exporter,
    /** Counts recomputed from the rows: `meta.json` is rewritten only every 30 s while recording. */
    val meta: SessionMeta? = null,
    val summary: SessionSummary? = null,
    /**
     * Every entry of all three streams on one clock, oldest first, one compact line each — the
     * same line the MCP `timeline` tool emits, with no cap. Markers are placed by the shared
     * [dev.inspector.model.timeline] rule, so a marker at a call's `mono` precedes it.
     */
    val timeline: List<TimelineEntry> = emptyList(),
    /** By `mono`, ascending. */
    val transactions: List<ExportedTransaction> = emptyList(),
    /** By `mono`, ascending. `data` is null, as in the archive; the payload is in [signalData]. */
    val signals: List<Signal> = emptyList(),
    /**
     * Signal payloads by signal id, as the captured bytes. Kept out of `Signal.data` deliberately:
     * filling that means parsing the payload, and a parse keeps only the last of a duplicated key —
     * the viewers need what the app actually sent.
     */
    val signalData: Map<String, ExportedBody> = emptyMap(),
    /** Latest observation per `(tag, name)`, newest first — what the app held when it stopped. */
    val current: List<Signal> = emptyList(),
    /** By `mono`, ascending. */
    val markers: List<Marker> = emptyList(),
)

/**
 * Exports one whole session — every row, body and payload, no filter and no page limit.
 *
 * Reads through the repository's raw readers, never `queryTransactions` or `timeline()`: both
 * serve paged views and clamp (1000 and 2000 rows), and an export missing rows because of a cap
 * nobody mentioned is a quietly incomplete export.
 */
object SessionExport {

    const val FORMAT = "inspector-session-export"
    const val FORMAT_VERSION = 1

    /** The rows look exactly like `index.jsonl` lines, only indented. */
    val json: Json = Json(InspectorJson) { prettyPrint = true }

    fun build(
        sessionDir: Path,
        repository: SessionRepository,
        exporterVersion: String,
        exportedAt: Instant = Instant.now(),
    ): SessionExportDocument {
        val transactions = repository.readTransactions(sessionDir).sortedBy { it.mono }
        val signals = repository.readSignals(sessionDir).sortedBy { it.mono }
        val markers = repository.readMarkers(sessionDir).sortedBy { it.mono }

        val meta = repository.readMeta(sessionDir)?.copy(
            txnCount = transactions.size,
            errorCount = transactions.count { it.isError },
        )

        val merged = dev.inspector.model.timeline(transactions, markers, signals).map { entry ->
            when (entry) {
                is dev.inspector.model.TimelineEntry.Call -> TimelineEntry.of(entry.txn)
                is dev.inspector.model.TimelineEntry.Observation -> TimelineEntry.of(entry.signal)
                is dev.inspector.model.TimelineEntry.Mark -> TimelineEntry.of(entry.marker)
            }
        }

        return SessionExportDocument(
            format = FORMAT,
            formatVersion = FORMAT_VERSION,
            exportedAt = exportedAt.toString(),
            exporter = Exporter("Inspector", exporterVersion),
            meta = meta,
            summary = repository.summarize(sessionDir),
            timeline = merged,
            transactions = transactions.map { txn ->
                ExportedTransaction(
                    txn = txn,
                    reqBody = repository.readBody(sessionDir, txn.id, "req")?.let(ExportedBody::of),
                    resBody = repository.readBody(sessionDir, txn.id, "res")?.let(ExportedBody::of),
                )
            },
            signals = signals,
            signalData = signals
                .mapNotNull { signal ->
                    repository.readSignalPayload(sessionDir, signal.id)?.let { signal.id to ExportedBody.of(it) }
                }
                .toMap(),
            current = repository.currentSignals(sessionDir),
            markers = markers,
        )
    }

    fun toJson(document: SessionExportDocument): String =
        json.encodeToString(SessionExportDocument.serializer(), document)

    /** `<sessionId>.<ext>`, falling back to the folder name for a session with no metadata. */
    fun fileName(document: SessionExportDocument, sessionDir: Path, extension: String): String =
        "${document.meta?.sessionId ?: sessionDir.fileName}.$extension"
}
