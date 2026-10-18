package dev.inspector.daemon.http

import dev.inspector.daemon.archive.DaemonConfig
import dev.inspector.daemon.archive.DeletionCause
import dev.inspector.daemon.archive.SessionRepository
import dev.inspector.daemon.archive.SessionSummary
import dev.inspector.daemon.archive.TransactionPage
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

internal fun Route.sessionRoutes(config: DaemonConfig, repository: SessionRepository, manager: SessionManager) {
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
         * Deletes one session and everything in it. Permanent — there is no trash.
         *
         * The `latest` alias is refused rather than resolved. It names a different folder
         * depending on when you call it, which is tolerable for a read and is not tolerable for
         * a delete: the archive it points at is the one you are most likely to still want.
         */
        delete("/api/sessions/{id}") {
            val id = call.parameters["id"].orEmpty()
            if (id == "latest") {
                return@delete call.respondError(
                    HttpStatusCode.BadRequest,
                    "refusing to delete through the 'latest' alias — name the session explicitly",
                )
            }
            val dir = call.resolveSession(repository) ?: return@delete
            val sessionId = dir.fileName.toString()
            if (sessionId in manager.activeSessionIds()) {
                return@delete call.respondError(
                    HttpStatusCode.Conflict,
                    "session $sessionId is still being written; disconnect the app first",
                )
            }

            val bytes = repository.sessionBytes(dir)
            if (!repository.deleteSession(dir, DeletionCause.REQUESTED)) {
                return@delete call.respondError(
                    HttpStatusCode.InternalServerError,
                    "could not delete $sessionId; check permissions on ${config.sessionsDir}",
                )
            }
            call.respondJson(
                InspectorJson.encodeToString(
                    SessionDeletion.serializer(),
                    SessionDeletion(
                        deleted = listOf(sessionId),
                        remaining = repository.sessionDirs().size,
                        freedBytes = bytes,
                    ),
                )
            )
        }

        /**
         * Deletes every session except the ones still being written.
         *
         * Skipping the live session is not a convenience. Removing the folder underneath an open
         * writer corrupts the append stream and throws away the traffic on screen — the same rule
         * [Retention] enforces, for the same reason.
         */
        post("/api/sessions/clear") {
            val active = manager.activeSessionIds()
            val deleted = mutableListOf<String>()
            val kept = mutableListOf<String>()
            var freed = 0L

            for (dir in repository.sessionDirs()) {
                val sessionId = dir.fileName.toString()
                if (sessionId in active) {
                    kept += sessionId
                    continue
                }
                val bytes = repository.sessionBytes(dir)
                if (repository.deleteSession(dir, DeletionCause.CLEARED)) {
                    deleted += sessionId
                    freed += bytes
                } else {
                    kept += sessionId
                }
            }

            // A total beside the per-session lines. This is the call that removes many at once,
            // so it is the one whose shape you want to recognise in a scrollback without counting.
            println(
                "inspector: cleared ${deleted.size} session(s), kept ${kept.size}, " +
                    "freed ${freed / 1024} KB"
            )

            call.respondJson(
                InspectorJson.encodeToString(
                    SessionDeletion.serializer(),
                    SessionDeletion(
                        deleted = deleted.sorted(),
                        kept = kept.sorted(),
                        remaining = repository.sessionDirs().size,
                        freedBytes = freed,
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
