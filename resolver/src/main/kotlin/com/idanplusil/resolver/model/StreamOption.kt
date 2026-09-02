package com.idanplusil.resolver.model

/** Container hint. Live URLs frequently carry no useful extension. */
enum class Container { AUTO, HLS, DASH }

/**
 * One playable candidate for a channel.
 *
 * Resolvers accumulate these rather than picking one, so both the selection
 * layer and the player's error policy always have somewhere to fall back to.
 */
data class StreamOption(
    val url: String,
    val label: String,
    /** Higher wins. Must be distinct within a channel, or auto-select is nondeterministic. */
    val priority: Int,
    val headers: Map<String, String> = emptyMap(),
    val cookies: String? = null,
    /** Epoch millis. Null means "no known expiry", not "never expires". */
    val expiresAtMillis: Long? = null,
    val container: Container = Container.AUTO,
)
