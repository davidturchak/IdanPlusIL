package com.idanplusil.resolver.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parses the published channel configuration.
 *
 * Deliberately defensive: each channel entry is decoded inside its own
 * `runCatching`, so one malformed entry cannot blank the whole lineup. This is
 * read on every cold start and must never be the reason the app fails to open.
 */
class ConfigLoader(
    private val json: Json = DefaultJson,
    private val onBadEntry: (String, Throwable) -> Unit = { _, _ -> },
) {

    fun parse(text: String): RemoteChannelConfig {
        val root = json.parseToJsonElement(text).jsonObject

        val headerSets: Map<String, Map<String, String>> =
            root["headerSets"]?.let {
                runCatching { json.decodeFromJsonElement<Map<String, Map<String, String>>>(it) }
                    .getOrNull()
            }.orEmpty()

        val live = LinkedHashMap<String, ChannelConfig>()
        (root["live"] as? JsonObject)?.forEach { (id, element) ->
            runCatching { json.decodeFromJsonElement<ChannelConfig>(element) }
                .onSuccess { live[id] = it }
                .onFailure { onBadEntry(id, it) }
        }

        return RemoteChannelConfig(
            schema = root["schema"]?.jsonPrimitive?.intOrNull ?: 1,
            updatedAt = root["updatedAt"]?.jsonPrimitive?.longOrNull,
            live = live,
            headerSets = headerSets,
        )
    }

    /**
     * Combine the bundled copy with a freshly fetched one.
     *
     * The published file is authoritative for *which channels exist*: a channel
     * removed from it disappears on the next launch, no reinstall. The bundled
     * copy is the cold-start floor when nothing has been fetched yet, and its
     * header sets stay available to a remote file that omits them. A remote
     * that parses to zero channels is treated as broken and ignored.
     */
    fun merge(bundled: RemoteChannelConfig, remote: RemoteChannelConfig): RemoteChannelConfig {
        if (remote.live.isEmpty()) return bundled
        return remote.copy(headerSets = bundled.headerSets + remote.headerSets)
    }

    companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
