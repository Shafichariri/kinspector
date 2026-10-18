package dev.inspector.daemon.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.getAllRoutes
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The refusal, worded once. It names the header because `ControlHeaderCoverageTest` counts only a
 * 403 that does: a 403 from anything else is not this guard.
 */
private const val CONTROL_REFUSAL = "control endpoints require the X-Inspector-Control: 1 header"

/**
 * Requires a header no cross-origin request can set without a preflight.
 *
 * The daemon listens on loopback with no authentication, which means *any* page the developer
 * happens to have open can POST to `127.0.0.1:8099`. A simple form or `no-cors` fetch could
 * therefore kill the daemon. A custom header forces the browser to preflight, and this server
 * answers no CORS preflight, so only its own page gets through. The read-only endpoints are
 * left alone — this guard is about not handing strangers an off switch.
 *
 * Responding here is enough to stop the handler, and that rests on Ktor internals: in 3.5.0 the
 * wrapper `RoutingNode.buildPipeline` puts around the handlers checks `isHandled` before invoking
 * each one. Revisit on a Ktor upgrade; `ControlGateTest` counts handler runs, so it fails if that
 * stops holding.
 */
private val ControlGate = createRouteScopedPlugin("ControlGate") {
    onCall { call ->
        if (call.request.headers["X-Inspector-Control"] != "1") {
            call.respondError(HttpStatusCode.Forbidden, CONTROL_REFUSAL)
        }
    }
}

/**
 * Registers [build]'s routes behind the control gate, by installing it on each route [build] adds.
 *
 * On the routes themselves, and never on a node of its own above them. A gate node would move the
 * gated routes into a subtree beside their open siblings. When nothing matches, Ktor 3.5.0 keeps a
 * later failure over the one it holds if the later one is of higher quality *or* deeper, but only
 * while every selector on its trail is constant or transparent. The transparent gate node made the
 * gated subtree's path failure one level deeper than the open `GET /api/sessions` failing on its
 * method, so whenever that subtree was resolved second, `PUT /api/sessions` answered 404 where it had
 * always answered 405. Which subtree came second was registration order. With no node added, the
 * route tree is the one the daemon had before the gate.
 *
 * The routes are found by comparing the handler nodes under this one before and after [build]. A
 * route added to a node that already had a handler is not new and is not gated; nothing here can
 * see that (a node's handler list is internal to Ktor), which is one more reason
 * `ControlHeaderCoverageTest`, which walks every route Ktor registered, is what catches a control
 * route left outside the gate, not this file.
 *
 * The reverse goes wrong just as quietly. A node that gets its *first* handler inside [build] (a
 * bare `handle { }` on the receiver, say) is new, so it gets the plugin, and Ktor then gates every
 * route below it, open ones included. Register with `post`, `delete` and the like, which create a
 * fresh method node for each route.
 *
 * A route already gated, by a `controlGated` nested inside this one, keeps its one install: a
 * second would throw `DuplicatePluginException`, hence the check.
 *
 * It returns nothing on purpose. Returning the receiver let `controlGated { }.post("/x") { }`
 * compile, and that registers an open route that reads as a gated one.
 */
internal fun Route.controlGated(build: Route.() -> Unit) {
    val parent = this as RoutingNode
    val before: MutableSet<RoutingNode> = Collections.newSetFromMap(IdentityHashMap())
    before += parent.getAllRoutes()
    parent.build()
    val added = parent.getAllRoutes().filter { it !in before }
    for (route in added) {
        if (route.pluginOrNull(ControlGate) == null) route.install(ControlGate)
    }
}
