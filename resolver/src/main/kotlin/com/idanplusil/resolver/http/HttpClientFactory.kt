package com.idanplusil.resolver.http

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * One immutable client per source, all derived from a single base so they share
 * the connection pool and dispatcher.
 *
 * The reference app instead keeps one global mutable client that every resolver
 * reconfigures (headers, cookie jar) before use, from background threads. Two
 * channels resolving concurrently corrupt each other - almost certainly the
 * cause of its "works alone, fails when I zap fast" behaviour. Deriving with
 * newBuilder() makes that structurally impossible.
 */
class HttpClientFactory(
    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    private val cache = ConcurrentHashMap<String, OkHttpClient>()

    fun clientFor(spec: SourceSpec): OkHttpClient = cache.computeIfAbsent(spec.key) {
        base.newBuilder()
            .connectTimeout(spec.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(spec.readTimeoutMs, TimeUnit.MILLISECONDS)
            .apply { if (spec.useCookies) cookieJar(InMemoryCookieJar()) }
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                spec.headers.forEach { (k, v) -> builder.header(k, v) }
                chain.proceed(builder.build())
            }
            .build()
    }

    fun facadeFor(spec: SourceSpec): HttpFacade = OkHttpFacade(clientFor(spec))
}

/** Per-source cookie jar. Never shared across sources. */
private class InMemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store[url.host] = cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
}

internal class OkHttpFacade(private val client: OkHttpClient) : HttpFacade {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        return client.newCall(request).await()
    }

    override suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String>,
    ): HttpResponse {
        val request = Request.Builder()
            .url(url)
            // Bytes, not String: OkHttp's String.toRequestBody always appends
            // "; charset=utf-8" to the media type, and at least one upstream API
            // (Kaltura's multirequest) string-matches "application/json" exactly
            // and silently falls back to empty form-parsing when it sees the
            // charset parameter - returning a 200 with an empty XML result.
            .post(json.toByteArray(Charsets.UTF_8).toRequestBody(JSON))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return client.newCall(request).await()
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

private suspend fun Call.await(): HttpResponse = suspendCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

        override fun onResponse(call: Call, response: Response) {
            response.use { r ->
                cont.resumeWith(
                    Result.success(
                        HttpResponse(
                            code = r.code,
                            body = r.body?.string().orEmpty(),
                            finalUrl = r.request.url.toString(),
                            headers = r.headers.toMultimap().mapValues { it.value.joinToString(",") },
                        )
                    )
                )
            }
        }
    })
}
