package com.idanplusil.resolver.http

import java.net.URI

/**
 * Normalise a protocol-relative URL. The reference app only handled the `http:`
 * case, so `//host/path` reached the player unusable.
 */
fun String.normalizeProtocolRelative(defaultScheme: String = "https"): String = when {
    startsWith("//") -> "$defaultScheme:$this"
    else -> this
}

/** Resolve a possibly-relative URL against the page it was found on. */
fun String.resolveAgainst(base: String): String = runCatching {
    URI(base).resolve(this.normalizeProtocolRelative()).toString()
}.getOrDefault(this)

/** Append a raw query string, respecting whether the URL already has one. */
fun String.appendQuery(query: String): String = when {
    query.isBlank() -> this
    contains('?') -> "$this&$query"
    else -> "$this?$query"
}

/** Origin (`scheme://host[:port]`) of a URL, for use as a Referer or Origin header. */
fun String.originOrNull(): String? = runCatching {
    val u = URI(this)
    val port = if (u.port == -1) "" else ":${u.port}"
    "${u.scheme}://${u.host}$port"
}.getOrNull()
