package com.idanplusil.tv.data.config

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface ConfigFetch {
    data class Fresh(val body: String, val etag: String?) : ConfigFetch
    data object NotModified : ConfigFetch
    data class Failed(val reason: ConfigError) : ConfigFetch
}

enum class ConfigError { NoNetwork, Timeout, BadResponse, Malformed }

/**
 * Fetches the published channel configuration.
 *
 * Conditional on the cached ETag, so the common case is a 304 and no body.
 */
class RemoteConfigSource(
    private val url: String,
    client: OkHttpClient,
) {
    private val client = client.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun fetch(etag: String?): ConfigFetch = try {
        val request = Request.Builder().url(url)
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
            .build()

        client.newCall(request).execute().use { response ->
            when {
                response.code == 304 -> ConfigFetch.NotModified
                !response.isSuccessful -> ConfigFetch.Failed(ConfigError.BadResponse)
                else -> {
                    val body = response.body?.string()
                    if (body.isNullOrBlank()) ConfigFetch.Failed(ConfigError.Malformed)
                    else ConfigFetch.Fresh(body, response.header("ETag"))
                }
            }
        }
    } catch (e: java.net.SocketTimeoutException) {
        ConfigFetch.Failed(ConfigError.Timeout)
    } catch (e: java.net.UnknownHostException) {
        ConfigFetch.Failed(ConfigError.NoNetwork)
    } catch (e: Exception) {
        ConfigFetch.Failed(ConfigError.NoNetwork)
    }
}
