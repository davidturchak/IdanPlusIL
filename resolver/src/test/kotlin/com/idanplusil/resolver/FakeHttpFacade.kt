package com.idanplusil.resolver

import com.idanplusil.resolver.http.HttpFacade
import com.idanplusil.resolver.http.HttpResponse
import java.io.IOException
import kotlinx.coroutines.delay

/**
 * Records every request so tests can assert on headers and hop order, not just
 * on the final result. That is what lets us pin behaviours like "a Referer is
 * sent on the iframe hop" - the omission of which is the likeliest cause of the
 * reference app's intermittent 403s.
 */
class FakeHttpFacade(
    private val responses: Map<String, HttpResponse> = emptyMap(),
    private val behaviour: Behaviour = Behaviour.Map,
) : HttpFacade {

    enum class Behaviour { Map, Throws, ServerError, Empty, Garbage, NeverResponds }

    data class Recorded(val method: String, val url: String, val headers: Map<String, String>, val body: String? = null)

    val requests = mutableListOf<Recorded>()

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        requests += Recorded("GET", url, headers)
        return respond(url)
    }

    override suspend fun postJson(url: String, json: String, headers: Map<String, String>): HttpResponse {
        requests += Recorded("POST", url, headers, json)
        return respond(url)
    }

    private suspend fun respond(url: String): HttpResponse = when (behaviour) {
        Behaviour.Map -> responses[url]
            ?: responses.entries.firstOrNull { url.startsWith(it.key) }?.value
            ?: HttpResponse(404, "", url)
        Behaviour.Throws -> throw IOException("boom")
        Behaviour.ServerError -> HttpResponse(500, "internal error", url)
        Behaviour.Empty -> HttpResponse(200, "", url)
        Behaviour.Garbage -> HttpResponse(200, "{not json at all <<<", url)
        Behaviour.NeverResponds -> { delay(Long.MAX_VALUE); error("unreachable") }
    }

    companion object {
        fun ok(url: String, body: String) = FakeHttpFacade(mapOf(url to HttpResponse(200, body, url)))
        fun of(vararg pairs: Pair<String, String>) =
            FakeHttpFacade(pairs.associate { (u, b) -> u to HttpResponse(200, b, u) })
        fun fixture(path: String): String =
            FakeHttpFacade::class.java.getResource("/fixtures/$path")!!.readText()
    }
}
