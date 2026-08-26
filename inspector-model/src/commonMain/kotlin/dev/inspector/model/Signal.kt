package dev.inspector.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * An app-defined observation on the same clock as [NetworkTransaction].
 *
 * One type covers a navigation event, a presentation-state observation and a cache snapshot,
 * because all three are *a named thing, under a category, at a moment, optionally with a payload*.
 *
 * Inspector deliberately knows nothing about what those categories mean: [tag] and [name] are free
 * strings the app chooses, for the same reason [SessionMeta.platform] is a string rather than an
 * enum. Conventional tags are listed in [SignalTags], but an unknown tag is ordinary — it must be
 * archived, filtered and served like any other, never dropped.
 *
 * See `docs/SIGNALS.md` for the design and `docs/SIGNALS-CHECKLIST.md` for what must be true of a
 * build.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Signal(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val v: Int = SCHEMA_VERSION,
    /** 8-char lowercase hex, unique within a session. Same generator as [NetworkTransaction.id]. */
    val id: String,
    /** ISO-8601 UTC with millis, device wall clock. Display only. */
    val ts: String,
    /**
     * Device monotonic milliseconds — the same clock [NetworkTransaction.mono] uses.
     *
     * This is what makes a merged timeline possible. It is not optional, and it must not be
     * sourced from a second clock: two monotonic clocks cannot be compared to each other.
     */
    val mono: Long,
    /** App-defined category, lowercase by convention. See [SignalTags]. */
    val tag: String,
    /**
     * App-defined identity within [tag]. `(tag, name)` is the grouping key for last-wins queries.
     */
    val name: String,
    /**
     * Payload, **in device memory only**.
     *
     * Null on the wire and null in the archive: the payload travels beside the row in
     * [SignalMsg.data] and the daemon writes it to disk, setting [dataRef]. This is the same split
     * [Txn] uses for bodies, and it exists so the payload is never carried twice.
     *
     * A non-JSON payload (typically a `toString()` dump) is held as a `JsonPrimitive` string, so
     * there is one payload type on disk rather than two.
     */
    val data: JsonElement? = null,
    /**
     * Host-relative path to the payload, e.g. `signals/7f3a.json`.
     *
     * Null on the wire; the daemon fills it in on ingest, **before** broadcasting to live viewers.
     * Broadcasting the row as received is defect #1 from the first real-app session and this path
     * has the identical shape — see `docs/SIGNALS.md`.
     */
    val dataRef: String? = null,
    /** Payload exceeded `SignalPolicy.maxPayloadBytes` and was cut. */
    val dataTruncated: Boolean = false,
    /** True payload size, counted even when the payload was truncated or dropped. */
    val bytes: Long = 0,
    /** Reserved. Always empty in v1 — signal payloads are never redacted; see `docs/SIGNALS.md`. */
    val redacted: List<String> = emptyList(),
    /** Who caused this row to exist. Consumers must surface it; see [SignalTrigger]. */
    val trigger: SignalTrigger = SignalTrigger.App,
    /** Correlates to the [SignalRequest] that caused this row. Set when [trigger] is request. */
    val requestId: String? = null,
)

/**
 * Provenance of a [Signal]. Load-bearing, for the same reason [NetworkTransaction.redacted] is.
 *
 * An agent handed a cache snapshot with no provenance reports it as the current state of the
 * cache. If that snapshot was pushed at app start and the session is now twenty minutes old, the
 * agent has just given a confidently wrong answer about live state. This field is what lets every
 * consumer say "recorded at app start, not re-read since" instead of guessing.
 */
@Serializable
enum class SignalTrigger {
    /** The app emitted this on its own. */
    @SerialName("app") App,

    /** The host asked for it, and the app's registered provider answered. */
    @SerialName("request") Request,
}

/**
 * Conventional [Signal.tag] values.
 *
 * Constants rather than an enum, deliberately: the set is open. An app emitting `featureflags` or
 * `bluetooth` gets a row that archives, filters, merges into the timeline and reads over MCP
 * exactly like these do — it simply renders in the generic lane rather than a bespoke one.
 *
 * Same reasoning as [MarkerSource] and [BodyOmission]: the archive outlives the binary that wrote
 * it, so a reader must cope with a value it has never heard of.
 */
object SignalTags {
    /** The active destination changed. `name` is the route; `data` its arguments. */
    const val SCREEN = "screen"

    /** A presentation-layer state observation. `name` is the state holder. */
    const val STATE = "state"

    /** A cache observation or mutation. `name` is the cache or entry. */
    const val CACHE = "cache"

    /** Auth/session lifecycle. `name` is the event. */
    const val SESSION = "session"
}
