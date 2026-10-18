package dev.inspector.daemon.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingResolveContext

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
 * Matches without consuming a path segment, so a route below it keeps its path; and `path` renders
 * only path components, so its template is unchanged too.
 *
 * An `object`, and that is load-bearing: `createChild` reuses a child whose selector is equal, so
 * every [controlGated] on one parent lands on the same node.
 */
private object ControlGateSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(X-Inspector-Control: 1)"
}

/**
 * Registers [build]'s routes behind the control gate.
 *
 * The gate is one node, so the second call on a parent finds the plugin already installed there,
 * and installing it again would throw `DuplicatePluginException`; hence the check.
 *
 * What this cannot do is notice a control route registered outside it. `ControlHeaderCoverageTest`
 * walks every route Ktor registered and is what catches that, not this file.
 */
internal fun Route.controlGated(build: Route.() -> Unit): Route {
    val gated = createChild(ControlGateSelector) as RoutingNode
    if (gated.pluginOrNull(ControlGate) == null) gated.install(ControlGate)
    gated.build()
    return gated
}
