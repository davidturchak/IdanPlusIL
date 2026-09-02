package com.idanplusil.resolver.http

/** A fetched response, reduced to what resolvers actually need. */
data class HttpResponse(
    val code: Int,
    val body: String,
    /** Final URL after redirects - relative links must resolve against this, not the request URL. */
    val finalUrl: String,
    val headers: Map<String, String> = emptyMap(),
) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * The seam between resolvers and the network.
 *
 * Every resolver is a pure function from response bodies to stream options, so
 * tests drive them through a fake implementation of this interface against
 * saved fixtures - no sockets, no emulator.
 */
interface HttpFacade {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    suspend fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse
}
