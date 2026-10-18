package dev.inspector.daemon.http

import dev.inspector.daemon.service.LiveApps
import dev.inspector.daemon.service.SessionManager
import dev.inspector.model.Bye
import dev.inspector.model.Hello
import dev.inspector.model.HelloAck
import dev.inspector.model.InspectorJson
import dev.inspector.model.MarkerMsg
import dev.inspector.model.SignRequest
import dev.inspector.model.SignResponse
import dev.inspector.model.SignalError
import dev.inspector.model.SignalMsg
import dev.inspector.model.SignalRequest
import dev.inspector.model.Txn
import dev.inspector.model.WireMsg
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.Base64

internal fun Route.ingestRoutes(manager: SessionManager, liveApps: LiveApps) = webSocket("/ingest") {
    var sessionId: String? = null
    var connection: LiveApps.Connection? = null
    try {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val message = runCatching {
                InspectorJson.decodeFromString<WireMsg>(frame.readText())
            }.getOrElse {
                System.err.println("inspector: dropping unparseable frame: ${it.message}")
                continue
            }

            when (message) {
                is Hello -> {
                    val opened = manager.openSession(message)
                    sessionId = opened.sessionId
                    // Registered so the host can address this app later. The send closure is
                    // handed over rather than the socket, keeping LiveApps free of Ktor.
                    connection?.let { liveApps.unregister(it.sessionId, it) }
                    connection = liveApps.register(opened.sessionId) { outbound ->
                        send(Frame.Text(InspectorJson.encodeToString(outbound)))
                    }
                    send(
                        Frame.Text(
                            InspectorJson.encodeToString<WireMsg>(
                                HelloAck(opened.sessionId, opened.resumed)
                            )
                        )
                    )
                    println(
                        "inspector: ${if (opened.resumed) "resumed" else "started"} " +
                            "session ${opened.sessionId}"
                    )
                }

                is Txn -> {
                    val id = sessionId ?: continue
                    manager.append(
                        id,
                        message.txn,
                        decodeBody(message.reqBody, message.reqBodyB64),
                        decodeBody(message.resBody, message.resBodyB64),
                    )
                }

                is MarkerMsg -> sessionId?.let { manager.append(it, message.marker) }

                is SignResponse -> sessionId?.let { liveApps.complete(it, message) }

                is SignalMsg -> {
                    val id = sessionId ?: continue
                    val stored = manager.append(
                        id,
                        message.signal,
                        decodeBody(message.data, message.dataB64),
                    )
                    // Archived first, then handed to any waiting pull, so the row a caller
                    // receives is the one on disk — `dataRef` included.
                    stored?.let { liveApps.completeSignal(id, it) }
                }

                is SignalError -> sessionId?.let { liveApps.failSignal(it, message) }

                Bye -> break

                // Daemon-to-client only; a client sending one back is simply ignored.
                is HelloAck -> Unit
                is SignRequest -> Unit
                is SignalRequest -> Unit
            }
        }
    } finally {
        connection?.let { liveApps.unregister(it.sessionId, it) }
        sessionId?.let {
            manager.closeSession(it)
            println("inspector: closed session $it")
        }
    }
}

private fun decodeBody(encoded: String?, base64: Boolean): ByteArray? {
    if (encoded == null) return null
    return if (base64) {
        runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
    } else {
        encoded.toByteArray()
    }
}
