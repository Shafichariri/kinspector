package dev.inspector.daemon.http

import dev.inspector.daemon.service.WebPage
import dev.inspector.daemon.service.webResource
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/*
 * The page and exactly the files its manifests list, as `GET /js/…` and `GET /css/…`.
 *
 * One constant route per listed file, registered from the manifests when the server starts, rather
 * than one `/js/{f}` route that checks membership. Two reasons. A file under `web/js` that no
 * manifest names then has no route at all, and an encoded `..` names nothing because no path is
 * ever built from the request. And a wrong method on a served file stays 405: in Ktor 3.5.0 a
 * method failure behind a *parameter* segment loses to the 404 recorded elsewhere in the tree, so
 * `PUT /js/{f}` answered 404 — the KIN-9 shape — where `PUT /app.js`, a constant, answered 405.
 */
internal fun Route.webUiRoutes() {
    get("/") {
        // Bytes rather than text, so the content type stays the bare `text/html` it always was.
        call.respondBytes(WebPage.servedIndex().encodeToByteArray(), ContentType.Text.Html)
    }
    for (file in WebPage.scripts()) get("/$file") { call.respondBundled(file, ContentType.Text.JavaScript) }
    for (file in WebPage.styles()) get("/$file") { call.respondBundled(file, ContentType.Text.CSS) }
}

private suspend fun ApplicationCall.respondBundled(
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
