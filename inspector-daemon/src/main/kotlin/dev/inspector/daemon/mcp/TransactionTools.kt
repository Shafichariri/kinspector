package dev.inspector.daemon.mcp

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.archive.TransactionPage
import dev.inspector.model.InspectorJsonPretty
import dev.inspector.model.NetworkTransaction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Calls: a filtered page, one in full, one explained, and its bodies. The body of `explain` is in
 * `ExplainTool.kt`, beside the shapes it returns.
 */
internal class TransactionTools(
    config: DaemonConfig,
    repository: SessionRepository,
) : ToolContext(config, repository), ToolGroup {

    override val tools: List<Tool> = listOf(
        Tool(
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
        ) { listTransactions(it) },
        Tool(
            tool(
                name = "get_transaction",
                description = "One transaction in full, including every header. " +
                    "Bodies are referenced but not inlined; call get_body for those.",
                required = listOf("id"),
            ) {
                put("session", sessionProperty())
                put("id", stringProperty("Transaction id, as returned by list_transactions."))
            }
        ) { getTransaction(it) },
        Tool(
            tool(
                name = "explain",
                description = "Everything the archive knows about one call, joined: the screen " +
                    "the app was on and how long it had been there, the other attempts of the " +
                    "same call, the calls that were in flight at the same moment, how often the " +
                    "same endpoint was hit, and the signals either side of it. Use this instead " +
                    "of get_transaction when the question is 'why' rather than 'what' — it is " +
                    "one call where the alternative is four and the joins done by hand.",
                required = listOf("id"),
            ) {
                put("session", sessionProperty())
                put("id", stringProperty("Transaction id, as returned by list_transactions."))
                put("windowMs", intProperty(
                    "How far either side to look for context, in device ms. Default 5000, " +
                        "maximum 60000."
                ))
            }
        ) { explain(it) },
        Tool(
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
        ) { getBody(it) },
    )

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

    /** States why a body is missing, using what capture recorded rather than guessing. */
    internal fun bodyNote(txn: NetworkTransaction, side: String): String? {
        val omitted = if (side == "req") txn.reqBodyOmitted else txn.resBodyOmitted
        val truncated = if (side == "req") txn.reqBodyTruncated else txn.resBodyTruncated
        val label = if (side == "req") "request" else "response"
        return when {
            omitted != null -> "$label body not captured: $omitted"
            truncated -> "$label body was truncated at capture; get_body reports the full size"
            else -> null
        }
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
}
