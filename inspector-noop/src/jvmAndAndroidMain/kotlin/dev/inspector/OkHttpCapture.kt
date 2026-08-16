package dev.inspector

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Mirror of `:inspector-core`'s OkHttp entry point so a consuming app compiles unchanged under
 * `-Pinspector=off`.
 *
 * Returns an interceptor that does nothing but call the rest of the chain. It reads no headers,
 * touches no body and allocates nothing per call, so leaving `addInterceptor(...)` in release
 * code costs one virtual call per request.
 */
fun Inspector.okHttpInterceptor(): Interceptor = PassThroughInterceptor

private object PassThroughInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
