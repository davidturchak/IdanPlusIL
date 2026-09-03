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
    /** Set when the published config could not be refreshed and this is cached or bundled data. */
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
    private val bundled: RemoteChannelConfig by lazy { BundledDefaults.load(loader) }

    @Volatile
    private var memory: RemoteChannelConfig? = null

    /** Immediate, never blocks on the network. Reads the disk cache once per process. */
    fun localSnapshot(): CatalogSnapshot {
        val config = memory ?: loadLocal().also { memory = it }
        return CatalogSnapshot(config.toChannels(), config)
    }

    private fun loadLocal(): RemoteChannelConfig {
        val cached = cache.read()?.let { runCatching { loader.parse(it) }.getOrNull() }
        return cached?.let { loader.merge(bundled, it) } ?: bundled
    }

    /** Refreshes from the network. Safe to call on every screen entry. */
    suspend fun refresh(): CatalogSnapshot = withContext(Dispatchers.IO) {
        when (val result = remote.fetch(cache.etag())) {
            is ConfigFetch.Fresh -> {
                // A file that parses to zero channels is broken, not a lineup
                // change. It must not reach the disk cache: that would evict the
                // last known-good config and pin the poisoned ETag until the
                // remote is republished.
                val parsed = runCatching { loader.parse(result.body) }.getOrNull()
                    ?.takeIf { it.live.isNotEmpty() }
                if (parsed == null) {
                    localSnapshot().copy(error = ConfigError.Malformed)
                } else {
                    cache.write(result.body, result.etag)
                    val merged = loader.merge(bundled, parsed)
                    memory = merged
                    CatalogSnapshot(merged.toChannels(), merged)
                }
            }
            ConfigFetch.NotModified -> localSnapshot()
            is ConfigFetch.Failed -> localSnapshot().copy(error = result.reason)
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
                logo = cfg.logo,
                epgId = cfg.epgId,
                sortOrder = cfg.sort,
                bundledFallbackUrl = cfg.stream,
            )
        }
