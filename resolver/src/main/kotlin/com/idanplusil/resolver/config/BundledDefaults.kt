package com.idanplusil.resolver.config

/**
 * The channel configuration compiled into the app.
 *
 * This is the cold-start floor: a first launch with no network still shows a
 * full catalog rather than an empty screen. The published channels.json
 * overlays it; it never replaces it.
 */
object BundledDefaults {
    const val RESOURCE = "/channels.json"

    fun readText(): String? =
        BundledDefaults::class.java.getResourceAsStream(RESOURCE)?.bufferedReader()?.use { it.readText() }

    fun load(loader: ConfigLoader = ConfigLoader()): RemoteChannelConfig =
        readText()?.let { runCatching { loader.parse(it) }.getOrNull() } ?: RemoteChannelConfig()
}
