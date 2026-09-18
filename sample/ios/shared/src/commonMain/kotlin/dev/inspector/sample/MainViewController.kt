package dev.inspector.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import dev.inspector.Inspector
import dev.inspector.InspectorConfig
import dev.inspector.model.SignalTags
import dev.inspector.stream.StreamSink
import dev.inspector.stream.defaultClientInfo
import dev.inspector.ui.InspectorOverlay
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.UIKit.UIViewController
import kotlin.random.Random

/**
 * Everything the Xcode project needs, in one function.
 *
 * The Swift side is deliberately a handful of lines that hosts this: the whole point of a Compose
 * Multiplatform overlay is that iOS needs no second implementation, and a sample that quietly wrote
 * one in Swift would be proving the opposite of the claim.
 *
 * Wiring is identical to the Android sample's, which is the claim being demonstrated — the same
 * three lines, no platform branches.
 */
fun MainViewController(): UIViewController {
    DemoServer.start()
    Inspector.init(InspectorConfig())

    // No host argument, same as Android. On the simulator `defaultDaemonHost()` is 127.0.0.1, and
    // the simulator shares the Mac's loopback, so the daemon is simply there. On a physical iPhone
    // it is the phone's own loopback and nothing is listening — which is the unsupported case, and
    // `connectionHelp` says so rather than offering an Android remedy.
    val stream = StreamSink(
        client = defaultClientInfo(appId = "dev.inspector.sample", appVersion = "0.1.0"),
        signer = { method, url ->
            mapOf(
                "X-Sample-Nonce" to Random.nextLong().toString(16),
                "X-Sample-Signed" to "$method $url",
            )
        },
    )
    stream.start()
    Inspector.addSink(stream)

    val client = HttpClient(Darwin) {
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 3)
            constantDelay(millis = 50, randomizationMs = 0)
        }
        Inspector.install(this)
    }

    return ComposeUIViewController {
        InspectorOverlay {
            MaterialTheme { SampleScreen(client) }
        }
    }
}

@Composable
private fun SampleScreen(client: HttpClient) {
    val scope = rememberCoroutineScope()

    Column(
        // The app's own insets, not the inspector's. The overlay insets its own screens and
        // deliberately does not reach into the host's layout to do it for them.
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Inspector sample", style = MaterialTheme.typography.titleLarge)
        Text(
            "Fire some traffic, then tap the pill. Drag it to move; long-press to collapse.",
            style = MaterialTheme.typography.bodyMedium,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Call(scope, client, "json", "/json")
            Call(scope, client, "404", "/status/404")
            Call(scope, client, "500", "/status/500")
            Call(scope, client, "slow", "/slow?ms=900")
            Call(scope, client, "redirect", "/redirect")
            Call(scope, client, "retry", "/flaky")
            Call(scope, client, "2 MB", "/large?kb=2048")
            Call(scope, client, "binary", "/binary")
            Call(scope, client, "secret", "/secret")

            Button(onClick = {
                scope.launch {
                    runCatching {
                        client.post(DemoServer.url("/echo")) {
                            contentType(ContentType.Application.Json)
                            setBody("""{"hello":"world"}""")
                        }.bodyAsBytes()
                    }
                }
            }) { Text("POST echo") }

            Button(onClick = {
                scope.launch {
                    repeat(50) { runCatching { client.get(DemoServer.url("/json")).bodyAsBytes() } }
                }
            }) { Text("burst 50") }

            Button(onClick = { Inspector.mark("tapped checkout") }) { Text("mark") }

            Button(onClick = {
                Inspector.signal(
                    tag = SignalTags.SCREEN,
                    name = "Checkout",
                    data = buildJsonObject { put("step", "review"); put("items", 3) },
                )
            }) { Text("screen signal") }

            Button(onClick = {
                repeat(50) { i ->
                    Inspector.signal(SignalTags.STATE, "DemoViewModel", text = "tick=$i")
                }
            }) { Text("signal burst") }

            Button(onClick = {
                scope.launch { runCatching { client.get("http://127.0.0.1:1/dead").bodyAsBytes() } }
            }) { Text("fail") }
        }
    }
}

@Composable
private fun Call(scope: CoroutineScope, client: HttpClient, label: String, path: String) {
    Button(onClick = {
        scope.launch { runCatching { client.get(DemoServer.url(path)).bodyAsBytes() } }
    }) { Text(label) }
}
