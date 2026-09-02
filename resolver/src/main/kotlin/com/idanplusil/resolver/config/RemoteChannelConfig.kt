package com.idanplusil.resolver.config

import kotlinx.serialization.Serializable

/**
 * Per-channel remote configuration.
 *
 * [show] controls catalog visibility, [force] bypasses the resolver entirely,
 * and [stream] is both the forced URL and the fallback. Three fields is the
 * right minimum, and [force] is the kill switch: when a broadcaster changes
 * their site and a resolver breaks, flipping this in the published JSON makes
 * every installed client recover with no app release.
 */
@Serializable
data class ChannelConfig(
    val show: Boolean = true,
    val force: Boolean = false,
    val stream: String? = null,
    val title: String? = null,
    val logo: String? = null,
    val epgId: String? = null,
    val sort: Int = 0,
    val resolver: ResolverSpec? = null,
)

@Serializable
data class RemoteChannelConfig(
    val schema: Int = 1,
    val updatedAt: Long? = null,
    val live: Map<String, ChannelConfig> = emptyMap(),
    /**
     * Named header sets, referenced by `headersRef`. Kept out of code because
     * they carry browser version strings that age out - sites reject default
     * OkHttp fingerprints, so these need updating as Chrome moves.
     */
    val headerSets: Map<String, Map<String, String>> = emptyMap(),
) {
    fun headersFor(spec: ResolverSpec?): Map<String, String> =
        spec?.headersRef?.let { headerSets[it] }.orEmpty()

    /** Visible channels, in authoring order. */
    fun visible(): Map<String, ChannelConfig> = live.filterValues { it.show }
}
