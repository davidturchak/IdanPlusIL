package com.idanplusil.tv.data.update

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck
    data class Available(val manifest: UpdateManifest) : UpdateCheck
    data class Failed(val reason: UpdateError) : UpdateCheck
}

/**
 * Fetches the update manifest and compares it with the installed build.
 *
 * No ETag or cache: the file is a few hundred bytes and there is nothing to
 * compare a cached copy against except the installed versionCode. Never throws.
 */
class UpdateChecker(
    private val manifestUrl: String,
    client: OkHttpClient,
    private val currentVersionCode: Int,
) {
    private val client = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(manifestUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext UpdateCheck.Failed(UpdateError.BadResponse)
                val manifest = UpdateManifest.parse(response.body?.string().orEmpty())
                    ?: return@withContext UpdateCheck.Failed(UpdateError.Malformed)
                if (manifest.versionCode > currentVersionCode) UpdateCheck.Available(manifest)
                else UpdateCheck.UpToDate
            }
        } catch (e: SocketTimeoutException) {
            UpdateCheck.Failed(UpdateError.Timeout)
        } catch (e: IOException) {
            UpdateCheck.Failed(UpdateError.NoNetwork)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateCheck.Failed(UpdateError.Malformed)
        }
    }
}
