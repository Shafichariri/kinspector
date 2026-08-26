package dev.inspector.daemon.mcp

import dev.inspector.daemon.DaemonConfig
import dev.inspector.daemon.SessionRepository
import dev.inspector.daemon.SignalPage
import dev.inspector.daemon.TimelineEntry
import dev.inspector.model.Signal
import dev.inspector.model.SignalTrigger
import dev.inspector.daemon.SessionSummary
import dev.inspector.daemon.TransactionPage
import dev.inspector.model.FilterParseException
import dev.inspector.model.InspectorJsonPretty
import dev.inspector.model.NetworkTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.builtins.ListSerializer

/** Lenient on input, strict on output — agents send approximate JSON. */
internal val McpJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

/** A tool either produced text for the agent, or a message explaining why it could not. */
sealed interface ToolOutcome {
    data class Ok(val text: String) : ToolOutcome
    data class Failed(val message: String) : ToolOutcome
}

/**
 * The archive as agent-callable tools.
 *
 * Reads go straight to disk through [SessionRepository], so an agent can inspect yesterday's
 * session with no daemon running. Only [addMarker] needs the daemon, because a marker has to
 * land in a session that is currently recording and the daemon owns those writers.
 *
 * Every tool is sized for a context window rather than a screen: summaries before rows, rows
 * before bodies, and bodies truncated unless asked otherwise.
 */
class McpTools(
    private val config: DaemonConfig,
    private val repository: SessionRepository = SessionRepository(config),
    private val markerPoster: MarkerPoster = HttpMarkerPoster(config),
    private val signalPuller: SignalPuller = HttpSignalPuller(config),
) {

    fun descriptors(): JsonArray = buildJsonArray {
        add(
            tool(
                name = "list_sessions",
                description = "List recorded sessions, newest first: id, app, device, " +
                    "transaction and error counts. Use this to find a session id; " +
                    "'latest' always works without calling it.",
            ) {
                put("limit", intProperty("Maximum sessions to return. Default 20."))
            }
        )
        add(
            tool(
                name = "session_summary",
                description = "Compact digest of one session: counts by status class and host, " +
                    "the slowest calls, every error, and the marker labels. About a kilobyte. " +
                    "Start here — it answers most questions without reading any transactions.",
            ) {
                put("session", sessionProperty())
            }
        )
        add(
            tool(
                name = "list_transactions",
                description = "Transactions matching a filter, newest first. Bodies are NOT " +
                    "included; call get_body for those. Filter grammar: " +
                    "status:404, status>=400, method:POST, host:api.example.com, path:/v2/users, " +
                    "slower:500ms, larger:10kb, has:error, text:refund, attempt>1, " +
                    "since:marker(\"label\"). Terms separated by spaces are ANDed; '|' ORs.",
            ) {
                put("session", sessionProperty())
                put("filter", stringProperty("Filter expression. Empty matches everything."))
                put("limit", intProperty("Maximum rows to return. Default 25, maximum 200."))
                put("offset", intProperty("Rows to skip, for paging. Default 0."))
            }
        )
        add(
            tool(
                name = "get_transaction",
                description = "One transaction in full, including every header. " +
                    "Bodies are referenced but not inlined; call get_body for those.",
                required = listOf("id"),
            ) {
                put("session", sessionProperty())
                put("id", stringProperty("Transaction id, as returned by list_transactions."))
            }
        )
        add(
            tool(
                name = "get_body",
                description = "The captured request or response body of one transaction. " +
                    "Truncated by default because bodies are large and context is not.",
                required = listOf("id", "side"),
            ) {
                put("session", sessionProperty())
                put("id", stringProperty("Transaction id."))
                put("side", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add(JsonPrimitive("req")); add(JsonPrimitive("res")) })
                    put("description", "Which body to read.")
                })
                put("maxBytes", intProperty("Truncate beyond this. Default 8192, maximum 262144."))
            }
        )
        add(
            tool(
                name = "timeline",
                description = "Transactions, signals and markers merged by the device clock, " +
                    "oldest first, one compact line each. This is the tool that answers 'what " +
                    "was the app doing when this failed' — start from a marker and read " +
                    "forwards. Follow an id into get_signal or get_body for detail. " +
                    "Same filter grammar as list_transactions plus tag: and name:.",
            ) {
                put("session", sessionProperty())
                put("filter", stringProperty("Filter expression. Empty matches everything."))
                put("since", intProperty("Only entries at or after this device mono (ms)."))
                put("until", intProperty("Only entries at or before this device mono (ms)."))
                put("limit", intProperty("Maximum entries. Default 200, maximum 2000."))
            }
        )
        add(
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
        )
        add(
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
        )
        add(
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
        )
        add(
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
        )
        add(
            tool(
                name = "add_marker",
                description = "Drop a labelled marker into the timeline of the session that is " +
                    "recording right now, so later calls can be filtered with " +
                    "since:marker(\"label\"). Requires a running daemon.",
                required = listOf("label"),
            ) {
                put("session", sessionProperty())
                put("label", stringProperty("Short human-readable label."))
            }
        )
    }

    /**
     * Argument names each tool declares, read back out of [descriptors].
     *
     * Derived from the descriptors rather than written out again, so a parameter added above
     * cannot drift out of this allowlist and start being rejected.
     */
    private val acceptedArguments: Map<String, Set<String>> by lazy {
        descriptors().associate { descriptor ->
            val obj = descriptor.jsonObject
            val properties = obj["inputSchema"]?.jsonObject?.get("properties")?.jsonObject
            obj.getValue("name").jsonPrimitive.content to (properties?.keys ?: emptySet())
        }
    }

    /**
     * Rejects arguments a tool does not declare, instead of ignoring them.
     *
     * Ignoring them produced confidently wrong answers. `list_sessions` returns the field as
     * `sessionId`, so calling another tool with `sessionId` — the obvious next step — left
     * `session` absent, defaulted it to `latest`, and returned a **different session's** data with
     * nothing to indicate the argument had been dropped. Reported from a consuming app.
     *
     * A tool that cannot honour what it was asked must say so; guessing is worse than failing.
     */
    private fun unknownArgumentError(name: String, args: JsonObject): ToolOutcome? {
        val accepted = acceptedArguments[name] ?: return null
        val unknown = args.keys.filterNot { it in accepted }
        if (unknown.isEmpty()) return null

        // The exact confusion that prompted this, named directly rather than left to be rediscovered.
        val hint = if ("sessionId" in unknown && "session" in accepted) {
            " Did you mean 'session'? list_sessions reports that field as 'sessionId'."
        } else {
            ""
        }

        return ToolOutcome.Failed(
            "unknown argument${if (unknown.size > 1) "s" else ""} for '$name': " +
                unknown.sorted().joinToString(", ") +
                ". Accepted: " + accepted.sorted().joinToString(", ") + "." + hint
        )
    }

    fun call(name: String, args: JsonObject): ToolOutcome = try {
        unknownArgumentError(name, args) ?: when (name) {
            "list_sessions" -> listSessions(args)
            "session_summary" -> sessionSummary(args)
            "list_transactions" -> listTransactions(args)
            "get_transaction" -> getTransaction(args)
            "get_body" -> getBody(args)
            "timeline" -> timeline(args)
            "current" -> current(args)
            "list_signals" -> listSignals(args)
            "get_signal" -> getSignal(args)
            "request_signal" -> requestSignal(args)
            "add_marker" -> addMarker(args)
            else -> ToolOutcome.Failed("unknown tool '$name'")
        }
    } catch (e: FilterParseException) {
        // The parser's message names the fix; an agent can correct itself from it.
        ToolOutcome.Failed(e.message ?: "invalid filter")
    } catch (e: Exception) {
        ToolOutcome.Failed("${e::class.simpleName}: ${e.message}")
    }

    // --- tools -----------------------------------------------------------------------------

    private fun listSessions(args: JsonObject): ToolOutcome {
        val limit = args.int("limit", default = 20, min = 1, max = 200)
        val sessions = repository.listSessions().take(limit)
        if (sessions.isEmpty()) {
            return ToolOutcome.Ok(
                "No sessions in ${config.sessionsDir}. An app records only while `inspector serve` " +
                    "is running and the app is built with the inspector wired in."
            )
        }
        return ToolOutcome.Ok(
            InspectorJsonPretty.encodeToString(
                ListSerializer(dev.inspector.model.SessionMeta.serializer()),
                sessions,
            )
        )
    }

    private fun sessionSummary(args: JsonObject): ToolOutcome {
        val dir = resolve(args) ?: return noSuchSession(args)
        val summary = repository.summarize(dir)
            ?: return ToolOutcome.Failed("session has no metadata; it may still be initialising")
        return ToolOutcome.Ok(InspectorJsonPretty.encodeToString(SessionSummary.serializer(), summary))
    }

    private fun listTransactions(args: JsonObject): ToolOutcome {
        val dir = resolve(args) ?: return noSuchSession(args)
        val page = repository.queryTransactions(
            sessionDir = dir,
            filterText = args.string("filter").orEmpty(),
            offset = args.int("offset", default = 0, min = 0, max = Int.MAX_VALUE),
            limit = args.int("limit", default = 25, min = 1, max = 200),
        )
        return ToolOutcome.Ok(InspectorJsonPretty.encodeToString(TransactionPage.serializer(), page))
    }

    private fun getTransaction(args: JsonObject): ToolOutcome {
        val dir = resolve(args) ?: return noSuchSession(args)
        val id = args.string("id") ?: return ToolOutcome.Failed("'id' is required")
        val txn = repository.readTransaction(dir, id)
            ?: return ToolOutcome.Failed("no transaction '$id' in this session")
        return ToolOutcome.Ok(InspectorJsonPretty.encodeToString(NetworkTransaction.serializer(), txn))
    }

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

    private fun getBody(args: JsonObject): ToolOutcome {
        val dir = resolve(args) ?: return noSuchSession(args)
        val id = args.string("id") ?: return ToolOutcome.Failed("'id' is required")
        val side = args.string("side") ?: return ToolOutcome.Failed("'side' must be 'req' or 'res'")
        if (side != "req" && side != "res") return ToolOutcome.Failed("'side' must be 'req' or 'res'")

        val bytes = repository.readBody(dir, id, side)
        if (bytes == null) {
            // Say why, rather than leaving the agent to conclude the request had no body.
            val txn = repository.readTransaction(dir, id)
                ?: return ToolOutcome.Failed("no transaction '$id' in this session")
            return ToolOutcome.Ok(explainMissingBody(txn, side))
        }

        val max = args.int("maxBytes", default = 8192, min = 1, max = 262_144)
        val text = bytes.decodeToString()
        return ToolOutcome.Ok(
            if (bytes.size <= max) text
            else text.take(max) + "\n\n[truncated: showing $max of ${bytes.size} bytes; " +
                "call get_body again with a larger maxBytes to see the rest]"
        )
    }

    private fun addMarker(args: JsonObject): ToolOutcome {
        val label = args.string("label") ?: return ToolOutcome.Failed("'label' is required")
        val session = args.string("session") ?: "latest"
        return markerPoster.post(session, label)
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun explainMissingBody(txn: NetworkTransaction, side: String): String {
        val omitted = if (side == "req") txn.reqBodyOmitted else txn.resBodyOmitted
        val bytes = if (side == "req") txn.reqBytes else txn.resBytes
        val contentType = if (side == "req") txn.reqContentType else txn.resContentType
        return when {
            bytes == 0L -> "This $side had no body."
            omitted == dev.inspector.model.BodyOmission.CONTENT_TYPE ->
                "The $side body was not captured: content type ${contentType ?: "was not declared"} " +
                    "is outside the capture allowlist. $bytes bytes were sent. " +
                    "Set captureAllBodies = true in InspectorConfig to capture it."
            omitted == dev.inspector.model.BodyOmission.STREAMING ->
                "The $side body ($bytes bytes) was streamed and never held in memory, so it was " +
                    "counted rather than captured."
            else -> "The $side body ($bytes bytes) is not in the archive."
        }
    }

    private fun resolve(args: JsonObject) = repository.resolve(args.string("session") ?: "latest")

    private fun noSuchSession(args: JsonObject): ToolOutcome {
        val requested = args.string("session") ?: "latest"
        val known = repository.listSessions().take(5).joinToString(", ") { it.sessionId }
        return ToolOutcome.Failed(
            if (known.isEmpty()) "no session '$requested'; the archive is empty"
            else "no session '$requested'. Known sessions: $known"
        )
    }
}

/** Posting a marker needs the daemon; split out so tests do not need one running. */
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

interface MarkerPoster {
    fun post(session: String, label: String): ToolOutcome
}

/**
 * Posts through the daemon's own HTTP API rather than writing the file directly, because the
 * daemon holds the open writer for a recording session and two writers would interleave.
 *
 * Uses the JDK client: adding an HTTP client dependency to reach a loopback port would be
 * disproportionate.
 */
class HttpMarkerPoster(private val config: DaemonConfig) : MarkerPoster {
    override fun post(session: String, label: String): ToolOutcome {
        val client = java.net.http.HttpClient.newHttpClient()
        val body = buildJsonObject { put("label", label) }
        val request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://127.0.0.1:${config.port}/api/sessions/$session/markers"))
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        return try {
            val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                ToolOutcome.Ok("Marker '$label' added to session '$session'.")
            } else {
                ToolOutcome.Failed("daemon refused the marker (${response.statusCode()}): ${response.body()}")
            }
        } catch (e: java.io.IOException) {
            ToolOutcome.Failed(
                "could not reach the daemon on 127.0.0.1:${config.port} — markers can only be " +
                    "added while `inspector serve` is running and the app is recording. (${e.message})"
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ToolOutcome.Failed("interrupted while posting the marker")
        }
    }
}

/** Seam for [McpTools.requestSignal], mirroring [MarkerPoster]. Faked in tests. */
interface SignalPuller {
    fun pull(session: String, tag: String, name: String): ToolOutcome
}

/**
 * Pulls through the daemon's HTTP API, for the same reason [HttpMarkerPoster] posts through it:
 * only the daemon holds the socket to the running app, and only it can address one.
 */
class HttpSignalPuller(private val config: DaemonConfig) : SignalPuller {
    override fun pull(session: String, tag: String, name: String): ToolOutcome {
        val client = java.net.http.HttpClient.newHttpClient()
        val body = buildJsonObject { put("tag", tag); put("name", name) }
        val request = java.net.http.HttpRequest.newBuilder()
            .uri(
                java.net.URI.create(
                    "http://127.0.0.1:${config.port}/api/sessions/$session/signals/request"
                )
            )
            .header("Content-Type", "application/json")
            .header("X-Inspector-Control", "1")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        return try {
            val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            when {
                response.statusCode() in 200..299 -> ToolOutcome.Ok(response.body())
                // The daemon's message already names what is registered, or that nothing is
                // attached. Passing it through beats replacing it with a vaguer one.
                else -> ToolOutcome.Failed("the pull failed (${response.statusCode()}): ${response.body()}")
            }
        } catch (e: java.io.IOException) {
            ToolOutcome.Failed(
                "could not reach the daemon on 127.0.0.1:${config.port} — a pull needs " +
                    "`inspector serve` running and the app still attached. (${e.message})"
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ToolOutcome.Failed("interrupted while pulling the signal")
        }
    }
}

// --- schema and argument plumbing ------------------------------------------------------------

private fun tool(
    name: String,
    description: String,
    required: List<String> = emptyList(),
    properties: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
): JsonObject = buildJsonObject {
    put("name", name)
    put("description", description)
    put(
        "inputSchema",
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject(properties))
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        },
    )
}

private fun sessionProperty() = stringProperty(
    "Session id, or 'latest' for the most recent. Defaults to 'latest'."
)

private fun stringProperty(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun intProperty(description: String) = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it !is kotlinx.serialization.json.JsonNull }
        ?.content?.takeIf { it.isNotBlank() && it != "null" }

/** Agents routinely send numbers as strings, so accept both rather than failing the call. */
private fun JsonObject.int(key: String, default: Int, min: Int, max: Int): Int {
    val primitive = this[key] as? JsonPrimitive ?: return default
    val value = primitive.intOrNull ?: primitive.content.toIntOrNull() ?: return default
    return value.coerceIn(min, max)
}

/** Absent means "no bound", so this is nullable rather than defaulted. Same string tolerance. */
private fun JsonObject.long(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.content.toLongOrNull()
}
