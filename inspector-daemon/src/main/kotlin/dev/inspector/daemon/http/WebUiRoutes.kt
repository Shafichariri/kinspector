package dev.inspector.daemon.http

import dev.inspector.daemon.service.webResource
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.webUiRoutes() {
    get("/") { call.respondResource("index.html", ContentType.Text.Html) }
    get("/app.js") { call.respondResource("app.js", ContentType.Text.JavaScript) }
    get("/style.css") { call.respondResource("style.css", ContentType.Text.CSS) }
}

private suspend fun ApplicationCall.respondResource(
    name: String,
    contentType: ContentType,
) {
    val bytes = webResource(name)
    if (bytes == null) {
        respondError(HttpStatusCode.NotFound, "missing bundled resource web/$name")
        return
    }
    respondBytes(bytes, contentType)
}
