package dev.inspector.daemon.mcp

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.archive.SignalPage
import dev.inspector.daemon.archive.TimelineEntry
import dev.inspector.model.InspectorJsonPretty
import dev.inspector.model.Signal
import dev.inspector.model.SignalTrigger
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** Signals: the merged timeline, the latest per key, a filtered page, one in full, and a live pull. */
internal class SignalTools(
    config: DaemonConfig,
    repository: SessionRepository,
    private val signalPuller: SignalPuller,
) : ToolContext(config, repository), ToolGroup {

    override val tools: List<Tool> = listOf(
        Tool(
            tool(
                name = "timeline",
                description = "Transactions, signals and markers merged by the device clock, " +
                    "oldest first, one compact line each. This is the tool that answers 'what " +
                    "was the app doing when this failed' — start from a marker and read " +
                    "forwards. Follow an id into get_signal or get_body for detail. " +
                    "Same filter grammar as list_transactions plus tag: and name:. " +
                    "Same-millisecond ties go by capture order (seq) where rows carry it; a " +
                    "marker without one comes before the call or signal at its instant.",
            ) {
                put("session", sessionProperty())
                put("filter", stringProperty("Filter expression. Empty matches everything."))
                put("since", intProperty("Only entries at or after this device mono (ms)."))
                put("until", intProperty("Only entries at or before this device mono (ms)."))
                put("limit", intProperty("Maximum entries. Default 200, maximum 2000."))
            }
        ) { timeline(it) },
        Tool(
            tool(
                name = "current",
                description = "The latest observation per (tag, name), with how old each one is. " +
                    "One call answers 'what screen was the app on, what was cached'. " +
                    "Read 'trigger' before reporting any of it as live: 'app' means the app " +
                    "pushed it at that moment and has not re-read it since — a snapshot from " +
                    "app start is not the current state. 'request' means it was pulled fresh. " +
                    "Use request_signal to get a genuinely current value.",
            ) {
                put("session", sessionProperty())
                put("tag", stringProperty("Restrict to one tag, e.g. 'cache'. Optional."))
            }
        ) { current(it) },
        Tool(
            tool(
                name = "list_signals",
                description = "Signals matching a filter, newest first. Payloads are NOT " +
                    "included; call get_signal for those. Filter terms for signals: tag:screen " +
                    "(exact), name:Checkout (substring), text:refund (tag and name, never " +
                    "payloads), since:marker(\"label\"). Transaction-only terms such as status: " +
                    "match no signals at all, so 'status:500 tag:screen' returns nothing — use " +
                    "'|' to span both kinds. Payloads are NOT redacted even when the session " +
                    "was recorded with redaction on.",
            ) {
                put("session", sessionProperty())
                put("filter", stringProperty("Filter expression. Empty matches everything."))
                put("limit", intProperty("Maximum rows to return. Default 25, maximum 200."))
                put("offset", intProperty("Rows to skip, for paging. Default 0."))
            }
        ) { listSignals(it) },
        Tool(
            tool(
                name = "get_signal",
                description = "One signal in full, with its payload, truncated by default. " +
                    "Check 'trigger': 'app' was pushed by the app at that moment, 'request' was " +
                    "pulled on demand. Signal payloads are app state, captured verbatim and " +
                    "NOT redacted — they may contain credentials or customer data even in a " +
                    "session recorded with redaction on.",
                required = listOf("id"),
            ) {
                put("session", sessionProperty())
                put("id", stringProperty("Signal id, as returned by list_signals or timeline."))
                put("maxBytes", intProperty("Truncate the payload beyond this. Default 8192."))
            }
        ) { getSignal(it) },
        Tool(
            tool(
                name = "request_signal",
                description = "Ask the running app what it holds right now, rather than reading " +
                    "what it pushed earlier. Requires a live session with an attached app and a " +
                    "provider registered for that tag and name; both failures are reported " +
                    "explicitly, and the error names which providers do exist.",
                required = listOf("tag", "name"),
            ) {
                put("session", sessionProperty())
                put("tag", stringProperty("Signal tag, e.g. 'cache'."))
                put("name", stringProperty("Provider name within the tag, e.g. 'response'."))
            }
        ) { requestSignal(it) },
    )

    private fun timeline(args: JsonObject): ToolOutcome {
        val dir = resolve(args) ?: return noSuchSession(args)
        val entries = repository.timeline(
            sessionDir = dir,
            filterText = args.string("filter").orEmpty(),
            since = args.long("since"),
            until = args.long("until"),
            limit = args.int("limit", default = 200, min = 1, max = 2000),
        )
        if (entries.isEmpty()) {
            return ToolOutcome.Ok(
                "No entries matched. Remember that a term excludes the row type it does not " +
                    "apply to: 'status:500 tag:screen' can never match, because status excludes " +
                    "signals and tag excludes transactions. Use '|' to span both."
            )
        }
        return ToolOutcome.Ok(
            InspectorJsonPretty.encodeToString(ListSerializer(TimelineEntry.serializer()), entries)
        )
    }

    private fun current(args: JsonObject): ToolOutcome {
        val dir = resolve(args) ?: return noSuchSession(args)
        val rows = repository.currentSignals(dir, args.string("tag"))
        if (rows.isEmpty()) {
            return ToolOutcome.Ok(
                "No signals in this session. The app records them only if it calls " +
                    "Inspector.signal(...); a session with traffic but no signals simply has no " +
                    "instrumentation for app state yet."
            )
        }
        // Age is reported against the newest row rather than a host clock: `mono` is the device's
        // monotonic clock and has no relationship to this machine's.
        val newest = rows.maxOf { it.mono }
        val observations = rows.map { signal ->
            CurrentObservation(
                tag = signal.tag,
                name = signal.name,
                id = signal.id,
                trigger = if (signal.trigger == SignalTrigger.Request) "request" else "app",
                observedMono = signal.mono,
                ageMsAtLastActivity = newest - signal.mono,
                bytes = signal.bytes,
            )
        }
        return ToolOutcome.Ok(
            InspectorJsonPretty.encodeToString(
                ListSerializer(CurrentObservation.serializer()),
                observations,
            )
        )
    }

    private fun listSignals(args: JsonObject): ToolOutcome {
        val dir = resolve(args) ?: return noSuchSession(args)
        val page = repository.querySignals(
            sessionDir = dir,
            filterText = args.string("filter").orEmpty(),
            offset = args.int("offset", default = 0, min = 0, max = Int.MAX_VALUE),
            limit = args.int("limit", default = 25, min = 1, max = 200),
        )
        return ToolOutcome.Ok(InspectorJsonPretty.encodeToString(SignalPage.serializer(), page))
    }

    private fun getSignal(args: JsonObject): ToolOutcome {
        val dir = resolve(args) ?: return noSuchSession(args)
        val id = args.string("id") ?: return ToolOutcome.Failed("'id' is required")
        val signal = repository.readSignal(dir, id)
            ?: return ToolOutcome.Failed("no signal '$id' in this session")

        val payload = repository.readSignalPayload(dir, id)
        val head = InspectorJsonPretty.encodeToString(Signal.serializer(), signal)
        if (payload == null) {
            return ToolOutcome.Ok("$head\n\n[no payload was captured for this signal]")
        }

        val max = args.int("maxBytes", default = 8192, min = 1, max = 262_144)
        val text = payload.decodeToString()
        val body = if (payload.size <= max) {
            text
        } else {
            text.take(max) + "\n\n[truncated: showing $max of ${payload.size} bytes; " +
                "raise maxBytes to see more]"
        }
        return ToolOutcome.Ok("$head\n\npayload:\n$body")
    }

    private fun requestSignal(args: JsonObject): ToolOutcome {
        val tag = args.string("tag") ?: return ToolOutcome.Failed("'tag' is required")
        val name = args.string("name") ?: return ToolOutcome.Failed("'name' is required")
        return signalPuller.pull(args.string("session") ?: "latest", tag, name)
    }
}

/**
 * One row of `current`: the latest observation for a `(tag, name)`, and how stale it is.
 *
 * [ageMsAtLastActivity] is measured against the newest signal in the session, not a host clock:
 * `mono` is the device's monotonic clock and has no relationship to this machine's.
 */
@kotlinx.serialization.Serializable
data class CurrentObservation(
    val tag: String,
    val name: String,
    val id: String,
    /** `app` — pushed by the app then; `request` — pulled on demand. Read before reporting. */
    val trigger: String,
    val observedMono: Long,
    val ageMsAtLastActivity: Long,
    val bytes: Long,
)
