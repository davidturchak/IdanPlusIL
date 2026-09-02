package com.idanplusil.resolver.technique

import com.idanplusil.resolver.StreamResolver
import com.idanplusil.resolver.config.ConfigLoader
import com.idanplusil.resolver.config.ResolverSpec
import com.idanplusil.resolver.http.HttpFacade
import com.idanplusil.resolver.model.Channel
import com.idanplusil.resolver.model.Container
import com.idanplusil.resolver.model.ResolveOutcome
import com.idanplusil.resolver.model.Stage
import com.idanplusil.resolver.model.StreamOption
import kotlinx.serialization.Serializable

/**
 * No resolution at all - the configured URL(s) go straight to the player.
 *
 * Prefer this whenever it works. Half the "scrapers" in the reference app exist
 * because nobody rechecked whether the direct URL still worked; today its own
 * published config force-plays every channel from here.
 *
 * [Config.options] replaces the reference app's synthetic `#EXT-X-STREAM-INF`
 * playlist string: several candidate manifests with an explicit priority, so a
 * dead primary falls through to a backup instead of failing the channel.
 */
class DirectResolver : StreamResolver {

    override val type: String = "direct"

    @Serializable
    data class Option(
        val url: String,
        val label: String? = null,
        val priority: Int? = null,
        val container: String? = null,
    )

    @Serializable
    data class Config(
        val stream: String? = null,
        val options: List<Option> = emptyList(),
        val container: String? = null,
        val label: String? = null,
    )

    override suspend fun resolve(
        channel: Channel,
        spec: ResolverSpec,
        http: HttpFacade,
    ): ResolveOutcome {
        val cfg = runCatching { spec.decode<Config>(ConfigLoader.DefaultJson) }
            .getOrDefault(Config())

        if (cfg.options.isNotEmpty()) {
            val options = cfg.options
                .filter { it.url.isNotBlank() }
                .mapIndexed { i, o ->
                    StreamOption(
                        url = o.url,
                        label = o.label ?: "${channel.title} ${i + 1}",
                        // Distinct by construction: config priorities may tie.
                        priority = (o.priority ?: (100 - i * 10)) * 10 - i,
                        container = containerOf(o.container ?: cfg.container),
                    )
                }
            return if (options.isEmpty()) ResolveOutcome.Failed(Stage.EXTRACT, "options list is empty")
            else ResolveOutcome.Ok(options)
        }

        val url = cfg.stream ?: channel.bundledFallbackUrl
            ?: return ResolveOutcome.Failed(Stage.EXTRACT, "no direct URL configured")

        return ResolveOutcome.Ok(
            listOf(
                StreamOption(
                    url = url,
                    label = cfg.label ?: "default",
                    priority = 100,
                    container = containerOf(cfg.container),
                )
            )
        )
    }
}

internal fun containerOf(name: String?): Container = when (name?.lowercase()) {
    "hls", "m3u8" -> Container.HLS
    "dash", "mpd" -> Container.DASH
    else -> Container.AUTO
}
