package com.idanplusil.resolver.technique

import com.idanplusil.resolver.StreamResolver
import com.idanplusil.resolver.config.ConfigLoader
import com.idanplusil.resolver.config.ResolverSpec
import com.idanplusil.resolver.http.HttpFacade
import com.idanplusil.resolver.http.normalizeProtocolRelative
import com.idanplusil.resolver.http.originOrNull
import com.idanplusil.resolver.model.Channel
import com.idanplusil.resolver.model.ResolveOutcome
import com.idanplusil.resolver.model.Stage
import com.idanplusil.resolver.model.StreamOption
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup

/**
 * The page does not contain the stream; it contains an `<iframe>` pointing at a
 * player host that does.
 *
 * Two corrections to the reference implementation:
 *  - a `Referer` (and `Origin`) is sent on every iframe hop. Player hosts
 *    commonly check it, and the original omits it - the likeliest cause of its
 *    intermittent 403s.
 *  - all manifest matches are returned as options rather than guessing that the
 *    last one wins.
 */
class IframeChaseResolver : StreamResolver {

    override val type: String = "iframe_chase"

    @Serializable
    data class Config(
        val pageUrl: String? = null,
        val iframeSelector: String = "iframe[src]",
        val maxHops: Int = 2,
        val sendReferer: Boolean = true,
        val manifestPattern: String = DEFAULT_MANIFEST_PATTERN,
        /** Optional structured field to try before falling back to the pattern. */
        val jsonFieldPattern: String? = null,
        val label: String? = null,
    )

    override suspend fun resolve(
        channel: Channel,
        spec: ResolverSpec,
        http: HttpFacade,
    ): ResolveOutcome {
        val cfg = runCatching { spec.decode<Config>(ConfigLoader.DefaultJson) }
            .getOrElse { return ResolveOutcome.Failed(Stage.PARSE, "bad iframe_chase config") }

        val start = cfg.pageUrl
            ?: return ResolveOutcome.Failed(Stage.EXTRACT, "no pageUrl configured")

        // Guard against being handed a manifest where a page is expected. Three
        // of the reference app's channels are configured exactly this way, so
        // their iframe match can never fire and they have been silently dead.
        if (MANIFEST_SUFFIX.containsMatchIn(start)) {
            return ResolveOutcome.Failed(Stage.EXTRACT, "pageUrl is a manifest, not a page")
        }

        val manifestRegex = runCatching { Regex(cfg.manifestPattern) }
            .getOrElse { return ResolveOutcome.Failed(Stage.PARSE, "bad manifestPattern") }
        val jsonFieldRegex = cfg.jsonFieldPattern?.let { runCatching { Regex(it) }.getOrNull() }

        var current = start
        var referer: String? = null

        repeat(cfg.maxHops.coerceIn(1, 5)) { hop ->
            val headers = buildMap {
                if (cfg.sendReferer && referer != null) {
                    put("Referer", referer!!)
                    referer!!.originOrNull()?.let { put("Origin", it) }
                }
            }

            val response = runCatching { http.get(current, headers) }.getOrNull()
                ?: return ResolveOutcome.Failed(Stage.FETCH, "hop $hop failed")
            if (!response.isSuccessful) {
                return ResolveOutcome.Failed(Stage.FETCH, "hop $hop HTTP ${response.code}")
            }

            // A structured field beats a regex over the whole body when present.
            jsonFieldRegex?.find(response.body)?.let { m ->
                val url = (m.groupValues.getOrNull(1) ?: m.value).normalizeProtocolRelative()
                return ResolveOutcome.Ok(
                    listOf(StreamOption(url, cfg.label ?: channel.title, 100))
                )
            }

            val manifests = manifestRegex.findAll(response.body)
                .map { it.groupValues.getOrNull(1)?.takeIf(String::isNotBlank) ?: it.value }
                .map { it.normalizeProtocolRelative() }
                .distinct()
                .toList()

            if (manifests.isNotEmpty()) {
                return ResolveOutcome.Ok(
                    manifests.mapIndexed { i, url ->
                        StreamOption(
                            url = url,
                            label = if (i == 0) (cfg.label ?: channel.title)
                            else "${cfg.label ?: channel.title} ${i + 1}",
                            priority = 100 - i,
                            headers = headers,
                        )
                    }
                )
            }

            val next = runCatching {
                Jsoup.parse(response.body, response.finalUrl)
                    .selectFirst(cfg.iframeSelector)
                    ?.absUrl("src")
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull() ?: return ResolveOutcome.Failed(Stage.EXTRACT, "no iframe at hop $hop")

            referer = response.finalUrl
            current = next
        }

        return ResolveOutcome.Failed(Stage.EXTRACT, "hop limit reached without a manifest")
    }

    companion object {
        const val DEFAULT_MANIFEST_PATTERN = """https?://[^"'\s\\<>]+\.m3u8[^"'\s\\<>]*"""
        private val MANIFEST_SUFFIX = Regex("""\.(m3u8|mpd)(\?|$)""")
    }
}
