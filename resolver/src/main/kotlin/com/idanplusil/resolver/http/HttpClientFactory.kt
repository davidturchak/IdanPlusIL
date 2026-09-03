package com.idanplusil.resolver.http

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

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
    // Keyed by the whole spec, not just its name: header sets arrive in the
    // published channels.json precisely so they can change without a release,
    // and a client built for the old set must not outlive it.
    private val cache = ConcurrentHashMap<SourceSpec, OkHttpClient>()

    fun clientFor(spec: SourceSpec): OkHttpClient = cache.computeIfAbsent(spec) {
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

    /**
     * The `Cookie` header the source's jar would send to [url], or null when it
     * holds nothing for that host. The player fetches through its own client, so
     * session cookies picked up during resolution have to travel on the option.
     */
    fun cookieHeaderFor(spec: SourceSpec, url: String): String? {
        if (!spec.useCookies) return null
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val cookies = clientFor(spec).cookieJar.loadForRequest(httpUrl)
        if (cookies.isEmpty()) return null
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }
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

/**
 * Cancellable bridge from an OkHttp call to a coroutine.
 *
 * Two details matter for the resolver budget to mean anything. The suspension
 * must observe cancellation, so `withTimeout` in the registry actually aborts
 * the call rather than waiting on it forever. And the body read inside
 * `onResponse` must resume the continuation on failure: OkHttp swallows an
 * exception thrown from a callback (it logs and moves on, never calling
 * `onFailure`), which would otherwise leave the caller suspended for good.
 */
internal suspend fun Call.await(): HttpResponse = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching {
                response.use { r ->
                    HttpResponse(
                        code = r.code,
                        body = r.body?.string().orEmpty(),
                        finalUrl = r.request.url.toString(),
                        headers = r.headers.toMultimap().mapValues { it.value.joinToString(",") },
                    )
                }
            }
            if (!cont.isActive) return
            result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
        }
    })
}
