package com.idanplusil.resolver.http

/**
 * Everything that distinguishes one upstream source from another: header set,
 * cookie policy, timeouts.
 *
 * This is data, not code. Header sets in particular carry browser version
 * strings that age out, so they live in channels.json and are pushed, not
 * released.
 */
data class SourceSpec(
    val key: String,
    val headers: Map<String, String> = emptyMap(),
    val useCookies: Boolean = false,
    val connectTimeoutMs: Long = 5_000,
    val readTimeoutMs: Long = 8_000,
)
