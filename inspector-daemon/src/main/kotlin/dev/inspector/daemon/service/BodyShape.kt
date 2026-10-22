package dev.inspector.daemon.service

import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.model.NetworkTransaction
import java.nio.file.Path

/**
 * One direction of a call. [key] is the string `SessionRepository.readBody` takes and the `side` the
 * Redactor writes into `redacted[]` (`body:req:$.pin`).
 */
enum class BodySide(val key: String) {
    REQ("req"),
    RES("res"),
}

/**
 * How much of one body the archive holds: the one answer to "did capture keep this body whole".
 *
 * Findings, the session diff and sensitive-data detection each need it, and answered three times it
 * would disagree: a row one reads as complete and another as unknown is the false claim
 * `BodyOmission` exists to prevent. [key] is the word every surface prints, so "body unknown:
 * truncated" reads the same in a finding, the diff and an export.
 */
enum class BodyState(val key: String) {
    /** Kept whole, declared JSON, and (once a consumer parses it) strict UTF-8 and strict JSON. */
    COMPLETE("complete"),

    /** There was no body: nothing to keep. */
    EMPTY("empty"),

    /** Capture kept a prefix (`bodyCaptureMaxBytes`). Never opened: a prefix is not the body. */
    TRUNCATED("truncated"),

    /** Capture kept nothing on purpose: content type, streaming, discarded. */
    OMITTED("omitted"),

    /** The row counts bytes the archive does not hold. */
    MISSING("missing"),

    /** Kept whole, not declared JSON. */
    NOT_JSON("notJson"),

    /** Declared JSON and kept whole, but not strict UTF-8 or not strict JSON. */
    UNPARSED("unparsed"),
}

/**
 * [BodyState] for a row, and its bytes when the archive holds them whole.
 *
 * [state] reads the row and never the disk, so a truncated or omitted body is classified without
 * its file being opened. Two states only a read can reach are left to the reader: a ref whose file
 * is gone reads as [BodyState.MISSING] (here, [read] returns null), and a JSON body that does not
 * parse strictly is [BodyState.UNPARSED], decided by whichever consumer parses (the diff does;
 * findings compare bytes and never parse). What counts as known is the consumer's: the diff needs a
 * shape, so only [BodyState.COMPLETE] and [BodyState.EMPTY] are, while a byte comparison can use
 * every body [read] returns.
 */
object BodyEvidence {

    /** The state of [side] of [txn], from the row alone. Never [BodyState.UNPARSED]. */
    fun state(txn: NetworkTransaction, side: BodySide): BodyState {
        val omitted = if (side == BodySide.REQ) txn.reqBodyOmitted else txn.resBodyOmitted
        val truncated = if (side == BodySide.REQ) txn.reqBodyTruncated else txn.resBodyTruncated
        val ref = if (side == BodySide.REQ) txn.reqBodyRef else txn.resBodyRef
        val bytes = if (side == BodySide.REQ) txn.reqBytes else txn.resBytes
        val type = if (side == BodySide.REQ) txn.reqContentType else txn.resContentType
        return when {
            omitted != null -> BodyState.OMITTED
            truncated -> BodyState.TRUNCATED
            bytes == 0L -> BodyState.EMPTY
            ref == null -> BodyState.MISSING
            // The test `Redactor.body` uses, so redaction and every reader agree on which bodies are JSON.
            type?.contains("json", ignoreCase = true) != true -> BodyState.NOT_JSON
            else -> BodyState.COMPLETE
        }
    }

    /**
     * The bytes of [side] of [txn] when the archive holds the body whole, of any content type, and
     * null otherwise: for every unknown state, and for a ref whose file is gone. An empty body is an
     * empty array, read from nothing. A truncated body's prefix is never returned, so no consumer can
     * mistake it for the body.
     */
    fun read(repository: SessionRepository, dir: Path, txn: NetworkTransaction, side: BodySide): ByteArray? =
        read(state(txn, side)) { repository.readBody(dir, txn.id, side.key) }

    /**
     * [read] for a caller that kept only the row's [state], as a fold does: the bytes from [file]
     * when the state is whole, an empty array when there was no body, otherwise null.
     */
    fun read(state: BodyState, file: () -> ByteArray?): ByteArray? = when (state) {
        BodyState.EMPTY -> ByteArray(0)
        BodyState.COMPLETE, BodyState.NOT_JSON -> file()
        else -> null
    }
}

/** JSON paths in the Redactor's own syntax (`$`, `$.key`, `$.items[2]`). */
object JsonPaths {

    /**
     * The paths of [side] of [txn] that `redacted[]` says capture replaced, as the Redactor wrote them
     * after `body:<side>:`. The older form, `body:$.x`, named no side and counts for both, because
     * `docs/schema.md` still shows it and an archive written that way must not read as unredacted.
     */
    fun redactedPaths(txn: NetworkTransaction, side: BodySide): Set<String> {
        if (txn.redacted.isEmpty()) return emptySet()
        val prefix = "body:${side.key}:"
        val paths = LinkedHashSet<String>()
        for (entry in txn.redacted) {
            when {
                entry.startsWith(prefix) -> paths += entry.substring(prefix.length)
                entry.startsWith(OLDER_PREFIX) -> paths += entry.substring(OLDER_PREFIX.length - 1)
            }
        }
        return paths
    }

    private const val OLDER_PREFIX = "body:$"
}
