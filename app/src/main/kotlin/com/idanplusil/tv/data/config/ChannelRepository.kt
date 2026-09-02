package com.idanplusil.tv.data.config

import com.idanplusil.resolver.config.BundledDefaults
import com.idanplusil.resolver.config.ConfigLoader
import com.idanplusil.resolver.config.RemoteChannelConfig
import com.idanplusil.resolver.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CatalogSnapshot(
    val channels: List<Channel>,
    val config: RemoteChannelConfig,
    /** True when the published config could not be refreshed and this is cached or bundled data. */
    val stale: Boolean,
    val error: ConfigError? = null,
)

/**
 * Three-source ladder for the configuration itself: memory, then the disk
 * cache, then the copy bundled in the APK.
 *
 * A cold start with no network still renders a full grid, and the network fetch
 * is never on the UI critical path.
 */
class ChannelRepository(
    private val cache: ConfigCache,
    private val remote: RemoteConfigSource,
    private val loader: ConfigLoader = ConfigLoader(),
) {
    @Volatile
    private var memory: RemoteChannelConfig? = null

    /** Immediate, never blocks on the network. */
    fun localSnapshot(): CatalogSnapshot {
        val bundled = BundledDefaults.load(loader)
        val cached = cache.read()?.let { runCatching { loader.parse(it) }.getOrNull() }
        val config = memory ?: cached?.let { loader.merge(bundled, it) } ?: bundled
        memory = config
        return CatalogSnapshot(config.toChannels(), config, stale = cached == null)
    }

    /** Refreshes from the network. Safe to call on every screen entry. */
    suspend fun refresh(): CatalogSnapshot = withContext(Dispatchers.IO) {
        val bundled = BundledDefaults.load(loader)
        when (val result = remote.fetch(cache.etag())) {
            is ConfigFetch.Fresh -> {
                val parsed = runCatching { loader.parse(result.body) }.getOrNull()
                if (parsed == null) {
                    localSnapshot().copy(stale = true, error = ConfigError.Malformed)
                } else {
                    cache.write(result.body, result.etag)
                    val merged = loader.merge(bundled, parsed)
                    memory = merged
                    CatalogSnapshot(merged.toChannels(), merged, stale = false)
                }
            }
            ConfigFetch.NotModified -> localSnapshot().copy(stale = false)
            is ConfigFetch.Failed -> localSnapshot().copy(stale = true, error = result.reason)
        }
    }
}

private fun RemoteChannelConfig.toChannels(): List<Channel> =
    visible().entries
        .sortedWith(compareBy({ it.value.sort }, { it.key }))
        .map { (id, cfg) ->
            Channel(
                id = id,
                title = cfg.title ?: "Channel $id",
                logoUrl = cfg.logo,
                epgId = cfg.epgId,
                sortOrder = cfg.sort,
                bundledFallbackUrl = cfg.stream,
            )
        }
