package com.idanplusil.resolver

import com.idanplusil.resolver.config.ChannelConfig
import com.idanplusil.resolver.config.RemoteChannelConfig
import com.idanplusil.resolver.http.HttpClientFactory
import com.idanplusil.resolver.http.SourceSpec
import com.idanplusil.resolver.model.Channel
import com.idanplusil.resolver.model.ResolveOutcome
import com.idanplusil.resolver.model.StreamOption

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
) {

    suspend fun resolve(channel: Channel, config: RemoteChannelConfig): List<StreamOption> {
        val cfg: ChannelConfig? = config.live[channel.id]

        // Tier 1 - the kill switch. Flipping `force` in the published JSON makes
        // every installed client bypass a broken resolver with no app release.
        if (cfg?.force == true && !cfg.stream.isNullOrBlank()) {
            return listOf(
                StreamOption(cfg.stream, LABEL_CONFIG, PRIORITY_FORCED, config.headersFor(cfg.resolver))
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
                // Carry the source's header set onto the option: the player must
                // replay it on every segment request, not just the manifest.
                if (option.headers.isEmpty()) option.copy(headers = headers) else option
            }
        }.orEmpty()

        // Tier 3 - static fallbacks, always appended so the player's error
        // policy has somewhere to go when a resolved URL turns out to be dead.
        val fallbacks = listOfNotNull(
            cfg?.stream?.takeIf { it.isNotBlank() }
                ?.let { StreamOption(it, LABEL_CONFIG, PRIORITY_CONFIG, config.headersFor(cfg.resolver)) },
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
    }
}
