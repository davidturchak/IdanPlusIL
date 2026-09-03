package com.idanplusil.resolver

import com.idanplusil.resolver.config.ChannelConfig
import com.idanplusil.resolver.config.RemoteChannelConfig
import com.idanplusil.resolver.http.HttpClientFactory
import com.idanplusil.resolver.http.SourceSpec
import com.idanplusil.resolver.model.Channel
import com.idanplusil.resolver.model.StreamOption
import com.idanplusil.resolver.technique.EntitlementResolver
import com.idanplusil.resolver.token.TokenStore

/**
 * The three-tier fallback ladder, in exactly one place.
 *
 * The reference app repeats this in seventeen resolvers plus four copies of a
 * dispatch table, and gets three details wrong that are fixed here: resolved
 * options *accumulate with* the fallbacks rather than replacing them, `force`
 * requires a non-blank stream (the original will happily force-play null), and
 * a failure records the stage that broke.
 */
class ChannelResolutionService(
    private val registry: ResolverRegistry,
    private val clients: HttpClientFactory,
    private val budgetMs: Long = ResolverRegistry.DEFAULT_BUDGET_MS,
    private val tokenStore: TokenStore? = null,
) {

    /**
     * @param fresh Drop any cached entitlement for the channel first. The player
     *   passes this when a CDN has just answered 403: handing back the ticket
     *   it rejected would make the re-resolve a no-op.
     */
    suspend fun resolve(channel: Channel, config: RemoteChannelConfig, fresh: Boolean = false): List<StreamOption> {
        val cfg: ChannelConfig? = config.live[channel.id]
        if (fresh) tokenStore?.invalidatePrefix(EntitlementResolver.cacheKeyPrefix(channel.id))

        // Tier 1 - the kill switch. Flipping `force` in the published JSON makes
        // every installed client bypass a broken resolver with no app release.
        if (cfg?.force == true && !cfg.stream.isNullOrBlank()) {
            return listOf(
                StreamOption(cfg.stream, LABEL_CONFIG, PRIORITY_FORCED, config.headersFor(cfg.resolver).forMedia())
            )
        }

        // Tier 2 - live resolution. Total by contract; never throws.
        val live: List<StreamOption> = cfg?.resolver?.let { spec ->
            val headers = config.headersFor(spec)
            val source = SourceSpec(
                key = "${channel.id}:${spec.type}",
                headers = headers,
                useCookies = true,
            )
            val outcome = registry.resolve(channel, spec, clients.facadeFor(source), budgetMs)
            outcome.optionsOrEmpty.map { option ->
                // The player fetches every segment through its own client, so
                // whatever identified us during resolution - browser UA, the
                // referring page, session cookies - has to ride on the option.
                // The option's own headers win; the source set fills the gaps.
                val cookie = clients.cookieHeaderFor(source, option.url)
                val merged = buildMap {
                    putAll(headers.forMedia())
                    if (cookie != null) put("Cookie", cookie)
                    putAll(option.headers)
                }
                option.copy(headers = merged, cookies = option.cookies ?: cookie)
            }
        }.orEmpty()

        // Tier 3 - static fallbacks, always appended so the player's error
        // policy has somewhere to go when a resolved URL turns out to be dead.
        val fallbacks = listOfNotNull(
            cfg?.stream?.takeIf { it.isNotBlank() }
                ?.let { StreamOption(it, LABEL_CONFIG, PRIORITY_CONFIG, config.headersFor(cfg.resolver).forMedia()) },
            channel.bundledFallbackUrl?.takeIf { it.isNotBlank() }
                ?.let { StreamOption(it, LABEL_BUNDLED, PRIORITY_BUNDLED) },
        )

        return (live + fallbacks)
            .distinctBy { it.url }
            .sortedByDescending { it.priority }
    }

    /** True when resolution produced nothing of its own and we are on a fallback. */
    fun isDegraded(options: List<StreamOption>): Boolean =
        options.firstOrNull()?.label in setOf(LABEL_CONFIG, LABEL_BUNDLED)

    companion object {
        const val LABEL_CONFIG = "config"
        const val LABEL_BUNDLED = "bundled"
        const val PRIORITY_FORCED = 1000
        const val PRIORITY_CONFIG = 50
        const val PRIORITY_BUNDLED = 10

        /**
         * The part of a browser header set that belongs on a media request. A
         * header set describes a page navigation (`Accept: text/html`,
         * `sec-fetch-dest: document`, `Upgrade-Insecure-Requests`); a real
         * browser sends none of that when its player fetches a playlist or a
         * segment, and a CDN that inspects them sees a fingerprint no browser
         * produces. What the CDN does check is who is asking and from where.
         */
        val MEDIA_HEADERS = setOf("user-agent", "referer", "origin", "cookie", "accept-language")

        fun Map<String, String>.forMedia(): Map<String, String> =
            filterKeys { it.lowercase() in MEDIA_HEADERS }
    }
}
