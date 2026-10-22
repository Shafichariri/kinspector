package dev.inspector.sample

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay

/**
 * Two mistakes a real app makes, so the daemon's findings have something to find in a sample
 * session: a payment submitted twice with no idempotency key, and a token in a query string.
 *
 * Without this, watching either rule family fire meant writing an app of your own. Inspector learns
 * nothing from it: the payment shape lives here, as it would in a consuming app, and the daemon
 * matches it through its generic defaults (`checkout` and `payment` are both default payment-path
 * words, and `access_token` a default secret name). `sample/README.md` says what each finding shows.
 *
 * Common Ktor client API only, so the desktop, Android and iOS samples share one copy.
 */
object DemoFlow {
    /**
     * A JWT that decodes to `{"alg":"HS256","typ":"JWT"}`, a payload naming `sample.invalid`, and a
     * signature that is the words "not-a-real-signature". It has to be a well-formed JWT, because
     * the rule only counts a token whose header decodes to JSON with an `alg`; it must never be one
     * anybody could mistake for a credential, because this repository is public.
     */
    const val FAKE_JWT =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
            ".eyJzdWIiOiJkZW1vLXVzZXIiLCJpc3MiOiJodHRwczovL3NhbXBsZS5pbnZhbGlkIiwibm90ZSI6Im5vdCBhIHJlYWwgdG9rZW4ifQ" +
            ".bm90LWEtcmVhbC1zaWduYXR1cmU"

    /** A published test card number, Luhn-valid and nobody's card; the card rule reports it at info. */
    private const val PAYMENT_BODY = """{"amount":1999,"currency":"EUR","cardNumber":"4242424242424242"}"""

    suspend fun run(client: HttpClient) {
        // The double tap: the same body twice, as two separate calls, with no Idempotency-Key. The
        // gap is a thumb's, well inside the daemon's 3 s duplicate window. Two calls and not a
        // retry on purpose: a retry is one call with two attempts, and a different rule.
        repeat(2) { tap ->
            if (tap > 0) delay(250)
            runCatching {
                client.post(DemoServer.url("/checkout/payments")) {
                    contentType(ContentType.Application.Json)
                    setBody(PAYMENT_BODY)
                }.bodyAsBytes()
            }
        }
        // A token where every proxy and server log on the way keeps it.
        runCatching { client.get(DemoServer.url("/profile?access_token=$FAKE_JWT")).bodyAsBytes() }
    }
}
