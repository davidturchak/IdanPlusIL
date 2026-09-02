package com.idanplusil.resolver.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

@Serializable
data class KalturaSource(
    val url: String = "",
    val format: String? = null,
    val protocols: String? = null,
    val flavorIds: String? = null,
    val deliveryProfileId: Int? = null,
    val drm: JsonArray? = null,
) {
    val isHls: Boolean
        get() = format?.contains("applehttp", ignoreCase = true) == true ||
            url.contains(".m3u8", ignoreCase = true)
}

@Serializable
data class KalturaMessage(val code: String? = null, val message: String? = null)
