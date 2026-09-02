package com.idanplusil.resolver.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * A channel's resolver configuration, kept as raw JSON.
 *
 * This is the whole point of the architecture: compiled code ships only the
 * *techniques*, while every parameter that actually breaks - CSS selectors,
 * JSON pointers, platform ids, entitlement parameter names, header sets, URLs -
 * lives here and is pushed as config rather than released as an app update.
 *
 * Holding it raw means an unrecognised `type` (say, a technique a newer app
 * version added) fails one channel at the registry, instead of failing to parse
 * the whole config file.
 */
@Serializable(with = ResolverSpecSerializer::class)
class ResolverSpec(val raw: JsonObject) {

    val type: String
        get() = raw["type"]?.jsonPrimitive?.contentOrNull ?: TYPE_DIRECT

    val headersRef: String?
        get() = raw["headersRef"]?.jsonPrimitive?.contentOrNull

    /** Decode the flat parameter block into a technique's own config type. */
    inline fun <reified T> decode(json: Json): T = json.decodeFromJsonElement(raw)

    override fun toString(): String = "ResolverSpec($type)"

    companion object {
        const val TYPE_DIRECT = "direct"

        fun direct(): ResolverSpec = ResolverSpec(
            JsonObject(mapOf("type" to kotlinx.serialization.json.JsonPrimitive(TYPE_DIRECT)))
        )
    }
}

object ResolverSpecSerializer : KSerializer<ResolverSpec> {
    private val delegate = JsonObject.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun deserialize(decoder: Decoder): ResolverSpec =
        ResolverSpec(delegate.deserialize(decoder))
    override fun serialize(encoder: Encoder, value: ResolverSpec) =
        delegate.serialize(encoder, value.raw)
}
