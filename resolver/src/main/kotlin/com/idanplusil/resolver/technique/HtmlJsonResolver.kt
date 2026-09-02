package com.idanplusil.resolver.technique

import com.idanplusil.resolver.StreamResolver
import com.idanplusil.resolver.config.ConfigLoader
import com.idanplusil.resolver.config.ResolverSpec
import com.idanplusil.resolver.http.HttpFacade
import com.idanplusil.resolver.model.Channel
import com.idanplusil.resolver.model.ResolveOutcome
import com.idanplusil.resolver.model.Stage
import com.idanplusil.resolver.model.StreamOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

/**
 * The broadcaster's web player ships its configuration in a
 * `<script type="application/json">` block. Fetch the page, read the block,
 * walk to the stream URL.
 *
 * Extraction uses a jsoup selector rather than the reference app's lookbehind
 * regex over raw HTML, and `absUrl` for the companion iframe - the original ran
 * a URL pattern over the raw tag text and silently dropped relative `src`
 * values.
 */
class HtmlJsonResolver : StreamResolver {

    override val type: String = "html_json"

    @Serializable
    data class Page(val url: String, val label: String? = null, val priority: Int? = null)

    @Serializable
    data class Config(
        val pages: List<Page> = emptyList(),
        /** CSS selector for the JSON block, e.g. `script#player_data[type=application/json]`. */
        val jsonSelector: String = "script[type=application/json]",
        /** JSON pointer to the stream URL inside that block, e.g. `/content/src`. */
        val jsonPointer: String = "/content/src",
        val iframeSelector: String? = null,
        val iframePriorityDelta: Int = -10,
    )

    override suspend fun resolve(
        channel: Channel,
        spec: ResolverSpec,
        http: HttpFacade,
    ): ResolveOutcome {
        val cfg = runCatching { spec.decode<Config>(ConfigLoader.DefaultJson) }
            .getOrElse { return ResolveOutcome.Failed(Stage.PARSE, "bad html_json config") }

        if (cfg.pages.isEmpty()) return ResolveOutcome.Failed(Stage.EXTRACT, "no pages configured")

        val options = mutableListOf<StreamOption>()
        var lastStage: Stage = Stage.FETCH

        // Priorities must stay distinct across pages: the reference app assigns
        // duplicate ranks and then selects by substring match, which makes
        // auto-selection between the main and closed-captions feeds random.
        cfg.pages.forEachIndexed { index, page ->
            val basePriority = page.priority ?: (100 - index * 20)
            val response = runCatching { http.get(page.url) }.getOrNull()
            if (response == null || !response.isSuccessful) {
                lastStage = Stage.FETCH
                return@forEachIndexed
            }

            val doc = runCatching { Jsoup.parse(response.body, response.finalUrl) }
                .getOrElse { lastStage = Stage.PARSE; return@forEachIndexed }

            val block = doc.selectFirst(cfg.jsonSelector)?.data()
            if (block.isNullOrBlank()) {
                lastStage = Stage.EXTRACT
            } else {
                val parsed = runCatching {
                    ConfigLoader.DefaultJson.parseToJsonElement(block)
                }.getOrNull()
                if (parsed == null) {
                    lastStage = Stage.PARSE
                } else {
                    srcsFrom(parsed, cfg.jsonPointer).forEach { src ->
                        options += StreamOption(
                            url = src,
                            label = page.label ?: channel.title,
                            priority = basePriority - options.size,
                        )
                    }
                }
            }

            // Companion source: the embedded player iframe. More options means
            // more chances one of them works.
            cfg.iframeSelector?.let { sel ->
                doc.selectFirst(sel)?.absUrl("src")?.takeIf { it.isNotBlank() }?.let { iframe ->
                    options += StreamOption(
                        url = iframe,
                        label = "${page.label ?: channel.title} (embed)",
                        priority = basePriority + cfg.iframePriorityDelta,
                    )
                }
            }
        }

        return if (options.isEmpty()) ResolveOutcome.Failed(lastStage)
        else ResolveOutcome.Ok(options.distinctBy { it.url })
    }

    /** Reads the pointer target, tolerating both an object and an array of objects. */
    private fun srcsFrom(root: JsonElement, pointer: String): List<String> {
        val path = pointer.trim('/').split('/').filter { it.isNotEmpty() }
        fun walk(node: JsonElement): String? {
            var cur: JsonElement = node
            for (key in path) {
                cur = (cur as? JsonObject)?.get(key) ?: return null
            }
            return cur.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
        }
        return when (root) {
            is JsonArray -> root.mapNotNull { runCatching { walk(it) }.getOrNull() }
            else -> listOfNotNull(runCatching { walk(root) }.getOrNull())
        }
    }
}
