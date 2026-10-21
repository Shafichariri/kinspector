package dev.inspector.daemon.http

import dev.inspector.daemon.archive.InspectorDaemonJson
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.archive.SessionSummary
import dev.inspector.daemon.archive.TransactionPage
import dev.inspector.daemon.service.SessionDeleteOutcome
import dev.inspector.daemon.service.SessionDeleter
import dev.inspector.daemon.service.SessionManager
import dev.inspector.model.FilterParseException
import dev.inspector.model.InspectorJson
import dev.inspector.model.SessionMeta
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a delete or a clear actually did.
 *
 * [kept] is not noise: a clear that silently skips the session being written looks, from the
 * outside, exactly like a clear that failed on it. Naming the survivors is what tells the two
 * apart without reading the daemon's log.
 */
@Serializable
data class SessionDeletion(
    val deleted: List<String>,
    val kept: List<String> = emptyList(),
    val remaining: Int,
    val freedBytes: Long,
)

/** One id's answer in a bulk delete. [error] and [freedBytes] appear only where they mean something. */
@Serializable
data class BulkSessionResult(
    val id: String,
    /** `deleted`, `refused`, `notFound` or `failed`. */
    val outcome: String,
    val error: String? = null,
    val freedBytes: Long? = null,
)

@Serializable
data class BulkSessionDeletion(
    val results: List<BulkSessionResult>,
    val deleted: List<String>,
    val remaining: Int,
    val freedBytes: Long,
)

/** Enough for any archive a person would prune by hand, and a bound on one request's work. */
private const val MAX_BULK_DELETE = 1000

/** The ids from `{"ids":[…]}`, or null when the body is anything else. */
private fun parseBulkIds(body: JsonObject): List<String>? {
    if (body.keys != setOf("ids")) return null
    val ids = body["ids"] as? JsonArray ?: return null
    if (ids.isEmpty() || ids.size > MAX_BULK_DELETE) return null
    return ids.map { element ->
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) return null
        primitive.content
    }
}

private fun SessionDeleteOutcome.toBulkResult(): BulkSessionResult = when (this) {
    is SessionDeleteOutcome.Deleted -> BulkSessionResult(id, "deleted", freedBytes = freedBytes)
    is SessionDeleteOutcome.Refused -> BulkSessionResult(id, "refused", error = message)
    is SessionDeleteOutcome.NotFound -> BulkSessionResult(id, "notFound", error = message)
    is SessionDeleteOutcome.Failed -> BulkSessionResult(id, "failed", error = message)
}

internal fun Route.sessionRoutes(repository: SessionRepository, manager: SessionManager, deleter: SessionDeleter) {
    /**
     * The sessions an app is connected to right now.
     *
     * `endedAt` on a session is not the same question, and the difference is not academic: a
     * session whose daemon was killed, or whose app vanished without a clean close, has no
     * `endedAt` and is not open either. Anything deciding whether a marker can be added has to
     * ask this rather than infer it from the metadata, or it offers a control that is refused
     * on exactly the sessions where the app went away — which is when you most want to mark
     * where it happened.
     */
    get("/api/recording") {
        call.respondJson(
            InspectorJson.encodeToString(
                ListSerializer(String.serializer()),
                manager.activeSessionIds().toList().sorted(),
            )
        )
    }

    get("/api/sessions") {
        call.respondJson(
            InspectorJson.encodeToString(
                ListSerializer(SessionMeta.serializer()),
                repository.listSessions(),
            )
        )
    }

    controlGated {
        /**
         * Deletes one session and everything in it. Permanent — there is no trash. The rules, and
         * the `latest` alias refused rather than resolved, are [SessionDeleter]'s.
         */
        delete("/api/sessions/{id}") {
            when (val outcome = deleter.deleteOne(call.parameters["id"].orEmpty())) {
                is SessionDeleteOutcome.Deleted -> call.respondJson(
                    InspectorJson.encodeToString(
                        SessionDeletion.serializer(),
                        SessionDeletion(
                            deleted = listOf(outcome.id),
                            remaining = deleter.remaining(),
                            freedBytes = outcome.freedBytes,
                        ),
                    )
                )
                is SessionDeleteOutcome.Refused -> call.respondError(
                    when (outcome.reason) {
                        SessionDeleteOutcome.Refused.Reason.LATEST_ALIAS -> HttpStatusCode.BadRequest
                        SessionDeleteOutcome.Refused.Reason.ACTIVE -> HttpStatusCode.Conflict
                    },
                    outcome.message,
                )
                is SessionDeleteOutcome.NotFound -> call.respondError(HttpStatusCode.NotFound, outcome.message)
                is SessionDeleteOutcome.Failed -> call.respondError(HttpStatusCode.InternalServerError, outcome.message)
            }
        }

        /** Deletes every session except the ones still being written; see [SessionDeleter.clear]. */
        post("/api/sessions/clear") {
            val cleared = deleter.clear()
            call.respondJson(
                InspectorJson.encodeToString(
                    SessionDeletion.serializer(),
                    SessionDeletion(
                        deleted = cleared.deleted,
                        kept = cleared.kept,
                        remaining = deleter.remaining(),
                        freedBytes = cleared.freedBytes,
                    ),
                )
            )
        }

        /**
         * Deletes the sessions named in `{"ids":[…]}`, answering for each in request order.
         *
         * 200 whenever the body is well formed, whatever each id's outcome: one status for five
         * deletions would hide the one that was refused. Each id goes through the rule the single
         * route uses, so a refusal carries the same words.
         */
        post("/api/sessions/delete") {
            val body = call.receiveJsonObject() ?: return@post
            val ids = parseBulkIds(body)
                ?: return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    "send {\"ids\":[\"<session id>\", …]} with 1 to $MAX_BULK_DELETE session ids as strings",
                )
            val results = deleter.deleteMany(ids).map { it.toBulkResult() }
            call.respondJson(
                InspectorDaemonJson.encodeToString(
                    BulkSessionDeletion.serializer(),
                    BulkSessionDeletion(
                        results = results,
                        deleted = results.filter { it.outcome == "deleted" }.map { it.id },
                        remaining = deleter.remaining(),
                        freedBytes = results.sumOf { it.freedBytes ?: 0L },
                    ),
                )
            )
        }
    }

    get("/api/sessions/{id}/summary") {
        val dir = call.resolveSession(repository) ?: return@get
        val summary = repository.summarize(dir)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "session has no metadata")
        call.respondJson(InspectorJson.encodeToString(SessionSummary.serializer(), summary))
    }

    get("/api/sessions/{id}/transactions") {
        val dir = call.resolveSession(repository) ?: return@get
        val filter = call.request.queryParameters["filter"].orEmpty()
        val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
        try {
            val page = repository.queryTransactions(dir, filter, offset, limit)
            call.respondJson(InspectorJson.encodeToString(TransactionPage.serializer(), page))
        } catch (e: FilterParseException) {
            // The parser's message names the fix; pass it through untouched.
            call.respondError(HttpStatusCode.BadRequest, e.message ?: "invalid filter")
        }
    }

    get("/api/sessions/{id}/transactions/{txnId}") {
        val dir = call.resolveSession(repository) ?: return@get
        val txnId = call.parameters["txnId"].orEmpty()
        val txn = repository.readTransaction(dir, txnId)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "no transaction $txnId")
        call.respondJson(InspectorJson.encodeToString(txn))
    }

    get("/api/sessions/{id}/transactions/{txnId}/body/{side}") {
        val dir = call.resolveSession(repository) ?: return@get
        val txnId = call.parameters["txnId"].orEmpty()
        val side = call.parameters["side"].orEmpty()
        val bytes = repository.readBody(dir, txnId, side)
            ?: return@get call.respondError(HttpStatusCode.NotFound, "no $side body for $txnId")
        val contentType = repository.readTransaction(dir, txnId)?.let {
            if (side == "req") it.reqContentType else it.resContentType
        }
        call.respondBytes(
            bytes,
            runCatching { ContentType.parse(contentType ?: "") }.getOrNull()
                ?: ContentType.Application.OctetStream,
        )
    }
}
