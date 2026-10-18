package dev.inspector.daemon.http

import dev.inspector.daemon.service.LiveEvent
import dev.inspector.daemon.service.SessionManager
import dev.inspector.model.InspectorJson
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame

internal fun Route.liveRoutes(manager: SessionManager) = webSocket("/api/live") {
    manager.live.collect { event ->
        val payload = when (event) {
            is LiveEvent.Transaction -> """{"type":"txn","sessionId":"${event.sessionId}","txn":${
                InspectorJson.encodeToString(event.txn)
            }}"""
            is LiveEvent.MarkerAdded -> """{"type":"marker","sessionId":"${event.sessionId}","marker":${
                InspectorJson.encodeToString(event.marker)
            }}"""
            // The signal here is the row SessionWriter stored, so `dataRef` is populated.
            // Broadcasting the row as received is defect #1; this path has its shape exactly.
            is LiveEvent.SignalRecorded -> """{"type":"signal","sessionId":"${event.sessionId}","signal":${
                InspectorJson.encodeToString(event.signal)
            }}"""
            is LiveEvent.SessionStarted -> """{"type":"sessionStarted","meta":${
                InspectorJson.encodeToString(event.meta)
            }}"""
            is LiveEvent.SessionEnded -> """{"type":"sessionEnded","sessionId":"${event.sessionId}"}"""
        }
        send(Frame.Text(payload))
    }
}
