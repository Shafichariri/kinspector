package dev.inspector

import dev.inspector.internal.InspectorOkHttpInterceptor
import okhttp3.Interceptor

/**
 * Capture for traffic that never touches a Ktor client.
 *
 * Add to any `OkHttpClient` and its calls land in the same ring buffer, the same overlay and the
 * same session archive as Ktor's, indistinguishable once recorded:
 *
 * ```
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(Inspector.okHttpInterceptor())
 *     .build()
 * ```
 *
 * This exists because a Ktor plugin can only see Ktor. SDKs that own their transport — Auth0 on
 * Android, Retrofit, Coil — are invisible to it, and their absence reads as "nothing happened"
 * rather than "not watched".
 *
 * **Add it with `addInterceptor`, not `addNetworkInterceptor`.** As an application interceptor it
 * sees bodies already decompressed, which is what you want to read. The cost is that OkHttp's
 * redirects and retries happen *below* it, so a call that redirects produces one row with
 * `attempt = 1`, where the Ktor path would produce a row per hop. Registering it as a network
 * interceptor instead gives you a row per hop, but the bodies arrive gzipped.
 */
fun Inspector.okHttpInterceptor(): Interceptor = InspectorOkHttpInterceptor(recorder)
