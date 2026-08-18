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
import kotlin.random.Random
import dev.inspector.InspectorConfig
import dev.inspector.Redaction
import dev.inspector.okHttpInterceptor
import dev.inspector.model.ClientInfo
import dev.inspector.model.Platforms
import dev.inspector.stream.StreamSink
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

    // External mode: also stream to the host daemon, if one is listening. Nothing here depends
    // on the daemon existing — with no daemon the sink simply stays disconnected and drops.
    val stream = StreamSink(
        client = ClientInfo(
            appId = "dev.inspector.sample",
            appVersion = "0.1.0",
            platform = Platforms.DESKTOP,
            device = System.getProperty("os.name") ?: "desktop",
            osVersion = System.getProperty("os.version") ?: "?",
            buildType = "debug",
        ),
        // The reference implementation of a replay signer.
        //
        // A real app would call into its own request-signing code here — the whole reason this
        // hook exists is that a device key cannot leave the device, so the host has to ask. This
        // one just proves the round trip and shows the shape: return whatever must be fresh for
        // *this* request, and the host merges it over the captured headers.
        //
        // Also a compile-time parity check. This call site has to build under `-Pinspector=off`
        // too, which is what keeps `:inspector-noop-stream` honest about carrying the same surface.
        signer = { method, url ->
            mapOf(
                "X-Sample-Timestamp" to (System.currentTimeMillis() / 1000).toString(),
                "X-Sample-Nonce" to Random.nextLong().toString(16),
                "X-Sample-Signed" to "$method $url",
            )
        },
    )
    stream.start()
    Inspector.addSink(stream)

    val client = HttpClient(CIO) {
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 3)
            constantDelay(millis = 50, randomizationMs = 0)
        }
        Inspector.install(this)
    }

    // A client that is not Ktor at all, wired the way a consuming app wires Auth0 or Retrofit.
    // Its calls must land in the same list, archive and web UI as everything above.
    val okHttp = okhttp3.OkHttpClient.Builder()
        .addInterceptor(Inspector.okHttpInterceptor())
        .build()

    // Scripted traffic for verification and demos: covers every endpoint plus a retry chain,
    // a marker, a burst and a transport failure, without anyone clicking a button.
    if (System.getProperty("inspector.sample.autofire") == "true") {
        kotlinx.coroutines.GlobalScope.launch {
            kotlinx.coroutines.delay(1500)
            suspend fun hit(path: String) = runCatching {
                client.get(DemoServer.url(path)).bodyAsBytes()
            }
            hit("/json"); hit("/status/404"); hit("/status/500")
            hit("/redirect"); hit("/flaky"); hit("/secret")
            hit("/binary"); hit("/large?kb=512"); hit("/slow?ms=700")
            runCatching {
                client.post(DemoServer.url("/echo")) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"hello":"world"}""")
                }.bodyAsBytes()
            }
            Inspector.mark("tapped checkout")
            repeat(20) { hit("/json") }
            runCatching { client.get("http://127.0.0.1:1/dead").bodyAsBytes() }

            // The non-Ktor path, exercised for real. The query marks it so the row is easy to
            // pick out of the list next to the Ktor ones.
            runCatching {
                okHttp.newCall(
                    okhttp3.Request.Builder().url(DemoServer.url("/json?via=okhttp")).build()
                ).execute().use { it.body?.bytes() }
            }
            println("inspector-sample: autofire complete")
        }
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
