package com.idanplusil.tv.data.update

import com.idanplusil.tv.data.update.ApkStore.Companion.toHex
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface DownloadEvent {
    data class Progress(val percent: Int, val bytes: Long) : DownloadEvent
    data class Done(val file: File) : DownloadEvent
    data class Failed(val reason: UpdateError) : DownloadEvent
}

/**
 * Streams the APK to disk, hashing as it goes.
 *
 * The flow ends with exactly one [DownloadEvent.Done] or [DownloadEvent.Failed].
 * Progress uses the manifest's size as the denominator so it works even when the
 * CDN omits Content-Length. Cancelling the collector aborts the download and
 * removes the partial file.
 */
class ApkDownloader(client: OkHttpClient) {

    private val client = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Per-read, so slow TV Wi-Fi is fine; callTimeout is the absolute ceiling.
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        // followRedirects is inherited: GitHub release assets 302 to objects.githubusercontent.com.
        .build()

    fun download(manifest: UpdateManifest, store: ApkStore): Flow<DownloadEvent> = flow {
        store.existingVerified(manifest)?.let {
            emit(DownloadEvent.Progress(100, manifest.sizeBytes))
            emit(DownloadEvent.Done(it))
            return@flow
        }
        if (!store.hasSpaceFor(manifest.sizeBytes)) {
            emit(DownloadEvent.Failed(UpdateError.Storage))
            return@flow
        }

        val part = store.partFor(manifest.versionCode)
        val target = store.fileFor(manifest.versionCode)
        try {
            client.newCall(Request.Builder().url(manifest.apkUrl).build()).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    emit(DownloadEvent.Failed(UpdateError.BadResponse))
                    return@flow
                }
                val declared = body.contentLength()
                if (declared >= 0 && declared != manifest.sizeBytes) {
                    emit(DownloadEvent.Failed(UpdateError.SizeMismatch))
                    return@flow
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                var lastPercent = -1
                part.outputStream().buffered().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            total += n
                            if (total > manifest.sizeBytes) {
                                emit(DownloadEvent.Failed(UpdateError.SizeMismatch))
                                return@flow
                            }
                            val percent = (total * 100 / manifest.sizeBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                // emit() is also the cancellation point.
                                emit(DownloadEvent.Progress(percent, total))
                            }
                        }
                    }
                }
                if (total != manifest.sizeBytes) {
                    emit(DownloadEvent.Failed(UpdateError.SizeMismatch))
                    return@flow
                }
                if (digest.digest().toHex() != manifest.sha256) {
                    emit(DownloadEvent.Failed(UpdateError.ChecksumMismatch))
                    return@flow
                }
                target.delete()
                if (!part.renameTo(target)) {
                    emit(DownloadEvent.Failed(UpdateError.Storage))
                    return@flow
                }
                emit(DownloadEvent.Done(target))
            }
        } catch (e: SocketTimeoutException) {
            emit(DownloadEvent.Failed(UpdateError.Timeout))
        } catch (e: IOException) {
            emit(DownloadEvent.Failed(UpdateError.NoNetwork))
        } finally {
            // No-op after a successful rename; cleans up on failure and cancellation.
            part.delete()
        }
    }.flowOn(Dispatchers.IO)
}
