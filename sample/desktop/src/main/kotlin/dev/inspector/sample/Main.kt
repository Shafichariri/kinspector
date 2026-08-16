package dev.inspector.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.inspector.Inspector
import dev.inspector.InspectorConfig
import dev.inspector.Redaction
import dev.inspector.ui.InspectorOverlay
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reference wiring for a consuming app, and the manual-verification vehicle for the overlay.
 *
 * Three lines is the whole integration: configure, install on the client, wrap the root.
 */
fun main() {
    DemoServer.start()

    // Redaction defaults to Off — full fidelity, credentials included. Pass Redaction.On() here
    // to see the other behaviour.
    Inspector.init(InspectorConfig())

    val client = HttpClient(CIO) {
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 3)
            constantDelay(millis = 50, randomizationMs = 0)
        }
        Inspector.install(this)
    }

    application {
        Window(onCloseRequest = ::exitApplication, title = "Inspector sample") {
            InspectorOverlay {
                MaterialTheme { SampleScreen(client) }
            }
        }
    }
}

@Composable
private fun SampleScreen(client: HttpClient) {
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                // Burst: the queue is DROP_OLDEST, so this must not stutter the UI.
                scope.launch {
                    repeat(50) { client.runCatching { get(DemoServer.url("/json")).bodyAsBytes() } }
                }
            }) { Text("burst 50") }

            Button(onClick = { Inspector.mark("tapped checkout") }) { Text("mark") }

            Button(onClick = {
                scope.launch {
                    runCatching { client.get("http://127.0.0.1:1/dead").bodyAsBytes() }
                }
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
