package dev.inspector.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import dev.inspector.Inspector
import dev.inspector.InspectorConfig
import dev.inspector.model.SignalTags
import dev.inspector.okHttpInterceptor
import dev.inspector.stream.StreamSink
import dev.inspector.stream.defaultClientInfo
import dev.inspector.ui.InspectorOverlay
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

/**
 * The overlay on a real Android app, which is the only way to see it on a phone.
 *
 * Everything here is the wiring `docs/INTEGRATION.md` gives a consumer, run as a consumer runs it
 * — the same three lines, the same debug-only cleartext config, the same `-Pinspector=off` swap in
 * the build file. If the guide is wrong, this is where it shows.
 *
 * The overlay had been seen on a phone exactly once before this module existed, and all four
 * things that came back from that were real defects. Everything added to it since has been checked
 * in an off-screen render at 360dp and by driving its controls in tests, which catches layout and
 * logic and cannot catch insets, gestures or a status bar.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge on purpose: it is the case the overlay's own inset handling exists for, and
        // the case a non-edge-to-edge host would never exercise. `Modifier.inspectorScreen` has to
        // keep its content clear of the status bar and the cutout while its background bleeds past
        // them — see AGENTS.md.
        enableEdgeToEdge()

        DemoServer.start()
        Inspector.init(InspectorConfig())

        // No host argument. `defaultDaemonHost()` answers 10.0.2.2 on an emulator and 127.0.0.1 on
        // hardware, where `adb reverse tcp:8099 tcp:8099` carries it to the host machine — so the
        // same build works in both places, which is the point of the 0.8.0 change.
        val stream = StreamSink(
            client = defaultClientInfo(appId = packageName, appVersion = "0.1.0"),
            // Reference signer, and a compile-time parity check: this call site has to build under
            // -Pinspector=off too, which is what keeps :inspector-noop-stream honest.
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

        val client = HttpClient(OkHttp) {
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                constantDelay(millis = 50, randomizationMs = 0)
            }
            Inspector.install(this)
        }

        // A client that is not Ktor at all, wired the way a consuming app wires Auth0 or Retrofit.
        val okHttp = okhttp3.OkHttpClient.Builder()
            .addInterceptor(Inspector.okHttpInterceptor())
            .build()

        setContent {
            InspectorOverlay {
                MaterialTheme { SampleScreen(client, okHttp) }
            }
        }
    }
}

@Composable
private fun SampleScreen(client: HttpClient, okHttp: okhttp3.OkHttpClient) {
    val scope = rememberCoroutineScope()

    Column(
        // The *app's* insets, not the inspector's. `enableEdgeToEdge` above means this content
        // draws behind the status bar unless it says otherwise, and a consuming app has to handle
        // that itself — the overlay insets its own screens and deliberately does not reach into
        // the host's layout to do it for them.
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Inspector sample", style = MaterialTheme.typography.titleLarge)
        Text(
            "Fire some traffic, then tap the pill. Drag it to move; long-press to collapse. " +
                "System back closes the inspector one screen at a time.",
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
                // Burst: the queue is DROP_OLDEST, so this must not stutter the UI. On a phone
                // that is a claim you can feel rather than measure, which is why it is a button.
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
                // 50 state changes as fast as they can be emitted. Conflation is the library's
                // job, and the overlay collapses what survives into one run.
                repeat(50) { i ->
                    Inspector.signal(SignalTags.STATE, "DemoViewModel", text = "tick=$i")
                }
            }) { Text("signal burst") }

            Button(onClick = {
                // `Dispatchers.IO`, and not by habit. `execute()` blocks, `rememberCoroutineScope`
                // launches on the main dispatcher, and Android throws NetworkOnMainThreadException
                // for exactly that — which `runCatching` then swallowed, so the button did nothing
                // at all and said nothing about it. The Ktor calls above are suspending and pick
                // their own dispatcher, which is why only this one was affected, and why desktop
                // never showed it.
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        okHttp.newCall(
                            okhttp3.Request.Builder().url(DemoServer.url("/json?via=okhttp")).build(),
                        ).execute().use { it.body?.bytes() }
                    }.onFailure { println("inspector-sample: okhttp call failed — $it") }
                }
            }) { Text("okhttp") }

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
