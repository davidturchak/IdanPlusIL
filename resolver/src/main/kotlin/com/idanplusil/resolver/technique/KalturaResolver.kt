package com.idanplusil.resolver.technique

import com.idanplusil.resolver.StreamResolver
import com.idanplusil.resolver.config.ConfigLoader
import com.idanplusil.resolver.config.ResolverSpec
import com.idanplusil.resolver.dto.KalturaSource
import com.idanplusil.resolver.http.HttpFacade
import com.idanplusil.resolver.http.normalizeProtocolRelative
import com.idanplusil.resolver.model.Channel
import com.idanplusil.resolver.model.ResolveOutcome
import com.idanplusil.resolver.model.Stage
import com.idanplusil.resolver.model.StreamOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Kaltura `multirequest`: one POST batching a widget session, an entry lookup
 * and a playback-context call, using Kaltura's `{n:result:field}` back-reference
 * syntax so the session key from call 1 feeds calls 2 and 3.
 *
 * This is the most durable non-direct technique - OVP APIs are versioned and
 * change far less often than page markup. Whenever a channel's page embeds a
 * known platform's player, prefer the API over scraping the page.
 *
 * The partner/widget/entry ids are configuration, not constants: they change
 * when the broadcaster reprovisions.
 */
class KalturaResolver : StreamResolver {

    override val type: String = "kaltura"

    @Serializable
    data class ExtraOption(val url: String, val label: String, val priority: Int = 50)

    @Serializable
    data class Config(
        val serviceUrl: String = "https://cdnapisec.kaltura.com/api_v3/service/multirequest",
        val partnerId: Int = 0,
        val widgetId: String = "",
        val entryId: String = "",
        val referer: String? = null,
        val userAgent: String? = null,
        val extraOptions: List<ExtraOption> = emptyList(),
    )

    override suspend fun resolve(
        channel: Channel,
        spec: ResolverSpec,
        http: HttpFacade,
    ): ResolveOutcome {
        val cfg = runCatching { spec.decode<Config>(ConfigLoader.DefaultJson) }
            .getOrElse { return ResolveOutcome.Failed(Stage.PARSE, "bad kaltura config") }

        if (cfg.partnerId == 0 || cfg.widgetId.isBlank() || cfg.entryId.isBlank()) {
            return ResolveOutcome.Failed(Stage.PARSE, "kaltura ids not configured")
        }

        val headers = buildMap {
            put("Content-Type", "application/json")
            cfg.referer?.let { put("Referer", it) }
            cfg.userAgent?.let { put("User-Agent", it) }
        }

        val response = runCatching { http.postJson(cfg.serviceUrl, body(cfg), headers) }
            .getOrElse { return ResolveOutcome.Failed(Stage.FETCH, it.message) }
        if (!response.isSuccessful) {
            return ResolveOutcome.Failed(Stage.FETCH, "HTTP ${response.code}")
        }

        val root = runCatching { ConfigLoader.DefaultJson.parseToJsonElement(response.body) }
            .getOrElse {
                return ResolveOutcome.Failed(
                    Stage.PARSE,
                    "unparseable response: ${response.body.take(120).replace('\n', ' ')}",
                )
            }

        // The response is an array, one element per sub-request. Find the one
        // carrying `sources` rather than assuming an index.
        val sourcesNode = (root as? JsonArray)
            ?.firstOrNull { (it as? JsonObject)?.containsKey("sources") == true }
            ?.jsonObject?.get("sources")?.jsonArray

        val sources: List<KalturaSource> = sourcesNode?.mapNotNull { element ->
            runCatching {
                ConfigLoader.DefaultJson.decodeFromJsonElement(
                    KalturaSource.serializer(), element
                )
            }.getOrNull()
        }.orEmpty()

        val resolved = sources
            // A DRM-protected source is not playable here; skip rather than fail.
            .filter { it.drm.isNullOrEmpty() }
            .filter { it.url.isNotBlank() }
            .sortedByDescending { it.isHls }
            .mapIndexed { i, s ->
                StreamOption(
                    url = s.url.normalizeProtocolRelative(),
                    label = s.format ?: "source ${i + 1}",
                    priority = 100 - i,
                    headers = headers.filterKeys { it == "Referer" || it == "User-Agent" },
                )
            }

        val extras = cfg.extraOptions.map {
            StreamOption(it.url, it.label, it.priority)
        }

        val all = (resolved + extras).distinctBy { it.url }
        return if (all.isEmpty()) ResolveOutcome.Failed(
            Stage.EXTRACT,
            "no playable sources (sources=${sources.size}, body=${response.body.take(100).replace('\n', ' ')})",
        )
        else ResolveOutcome.Ok(all)
    }

    /**
     * Kept as a string template rather than built from DTOs: the back-reference
     * syntax (`{1:result:ks}`) is not JSON-encodable data, it is Kaltura's own
     * mini-language, and a golden-file test pins this exact shape.
     */
    internal fun body(cfg: Config): String = """
        {
          "1": {"service":"session","action":"startWidgetSession","widgetId":"${cfg.widgetId}"},
          "2": {"service":"baseEntry","action":"list","ks":"{1:result:ks}",
                "filter":{"redirectFromEntryId":"${cfg.entryId}"},
                "responseProfile":{"type":1,"fields":"id,referenceId,name,dataUrl,duration,mediaType,type,dvrStatus,status"}},
          "3": {"service":"baseEntry","action":"getPlaybackContext","entryId":"{2:result:objects:0:id}",
                "ks":"{1:result:ks}",
                "contextDataParams":{"objectType":"KalturaContextDataParams","flavorTags":"all"}},
          "apiVersion":"3.3.0","format":1,"ks":"","clientTag":"html5:v1.0.5","partnerId":${cfg.partnerId}
        }
    """.trimIndent()
}
