package com.idanplusil.tv.di

import android.content.Context
import android.os.Build
import android.util.Log
import com.idanplusil.resolver.ChannelResolutionService
import com.idanplusil.resolver.ResolverLog
import com.idanplusil.resolver.ResolverRegistry
import com.idanplusil.resolver.http.HttpClientFactory
import com.idanplusil.resolver.technique.DirectResolver
import com.idanplusil.resolver.technique.EntitlementResolver
import com.idanplusil.resolver.technique.HtmlJsonResolver
import com.idanplusil.resolver.technique.IframeChaseResolver
import com.idanplusil.resolver.technique.KalturaResolver
import com.idanplusil.resolver.token.InMemoryTokenStore
import com.idanplusil.tv.BuildConfig
import com.idanplusil.tv.data.config.ChannelRepository
import com.idanplusil.tv.data.config.ConfigCache
import com.idanplusil.tv.data.config.RemoteConfigSource
import com.idanplusil.tv.data.update.ApkDownloader
import com.idanplusil.tv.data.update.ApkStore
import com.idanplusil.tv.data.update.UpdateChecker
import com.idanplusil.tv.data.update.UpdateManifest
import com.idanplusil.tv.update.UpdateInstaller
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Hand-rolled dependency graph.
 *
 * For two screens Hilt would buy an annotation processor and a KSP version axis
 * in exchange for nothing. ViewModels take dependencies through explicit
 * factories, so swapping this for Hilt later touches this file and the
 * factories - not a single call site.
 */
class AppContainer(private val appContext: Context) {

    /**
     * One base client for the whole process. Every per-source client is derived
     * from it with newBuilder(), so they share the connection pool and
     * dispatcher while staying individually immutable.
     */
    val baseHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    val httpClients: HttpClientFactory by lazy { HttpClientFactory(baseHttpClient) }

    private val tokenStore = InMemoryTokenStore()

    val resolverRegistry: ResolverRegistry by lazy {
        ResolverRegistry(
            listOf(
                DirectResolver(),
                HtmlJsonResolver(),
                IframeChaseResolver(),
                KalturaResolver(),
                // Entitlement tokens are cached with an expiry and refreshed
                // proactively; the player's 403 handling is a safety net.
                EntitlementResolver(tokenStore),
            )
        )
    }

    val resolution: ChannelResolutionService by lazy {
        ChannelResolutionService(resolverRegistry, httpClients, tokenStore = tokenStore)
    }

    val channelRepository: ChannelRepository by lazy {
        ChannelRepository(
            cache = ConfigCache(File(appContext.filesDir, "config")),
            remote = RemoteConfigSource(BuildConfig.CHANNELS_CONFIG_URL, baseHttpClient),
        )
    }

    // ---- Self-update -------------------------------------------------------

    val apkStore: ApkStore by lazy {
        // Pre-N the installer reads a file:// path, which must not be private storage.
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appContext.cacheDir
        } else {
            appContext.externalCacheDir ?: appContext.cacheDir
        }
        ApkStore(File(base, "updates"))
    }

    val updateChecker: UpdateChecker by lazy {
        UpdateChecker(BuildConfig.UPDATE_MANIFEST_URL, baseHttpClient, BuildConfig.VERSION_CODE)
    }

    val apkDownloader: ApkDownloader by lazy { ApkDownloader(baseHttpClient) }

    val updateInstaller: UpdateInstaller by lazy {
        UpdateInstaller("${BuildConfig.APPLICATION_ID}.updates")
    }

    /** Process-lifetime memory of "Later", so coming back from the player does not re-prompt. */
    val updateSession = UpdateSession()

    init {
        ResolverLog.sink = ResolverLog.Sink { channelId, type, stage, message ->
            Log.w("IdanPlusIL", "resolve break: channel=$channelId technique=$type stage=$stage ${message.orEmpty()}")
        }
    }
}

class UpdateSession {
    @Volatile
    var dismissed: UpdateManifest? = null
}
