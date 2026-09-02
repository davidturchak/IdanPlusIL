package com.idanplusil.tv.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import com.idanplusil.resolver.model.Container
import com.idanplusil.resolver.model.StreamOption
import okhttp3.OkHttpClient

@UnstableApi
class PlayerFactory(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {

    fun create(): ExoPlayer =
        ExoPlayer.Builder(
            context,
            DefaultRenderersFactory(context)
                // TV SoC decoders routinely advertise capabilities they do not
                // honour; without this a codec mismatch is fatal instead of
                // falling back to another decoder.
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF),
        )
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // 30s ceiling rather than the 50s default: a live stream
                    // gains nothing from a deep forward buffer it can never seek
                    // into, and on a 2.4 GB device the allocation is real.
                    .setBufferDurationsMs(5_000, 30_000, 1_500, 3_000)
                    .setBackBuffer(0, false)
                    .build()
            )
            .build()
            .apply { setForegroundMode(true) }

    /**
     * Builds a media source over the *same* OkHttp client that resolved the
     * stream, so cookies, User-Agent and entitlement tickets carry into every
     * segment request - tokenised CDNs re-check them per segment, not just on
     * the manifest.
     */
    fun mediaSourceFor(option: StreamOption, onNeedsReresolve: () -> Unit): MediaSource {
        val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
            .setDefaultRequestProperties(option.headers)

        val mediaItem = MediaItem.Builder()
            .setUri(option.url)
            .apply {
                when (option.container) {
                    Container.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    Container.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                    Container.AUTO -> Unit
                }
            }
            .build()

        val policy = LiveLoadErrorPolicy(onNeedsReresolve)

        return when (inferType(option)) {
            C_TYPE_DASH -> DashMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(policy)
                .createMediaSource(mediaItem)
            else -> HlsMediaSource.Factory(dataSourceFactory)
                // Measurably cuts channel zap time.
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(policy)
                .createMediaSource(mediaItem)
        }
    }

    /**
     * Live URLs frequently carry no useful extension, so the inferred type gets
     * a substring fallback. HLS is the default because it dominates this space.
     */
    private fun inferType(option: StreamOption): Int {
        if (option.container == Container.DASH) return C_TYPE_DASH
        if (option.container == Container.HLS) return C_TYPE_HLS
        val uri = android.net.Uri.parse(option.url)
        return when (Util.inferContentType(uri)) {
            androidx.media3.common.C.CONTENT_TYPE_DASH -> C_TYPE_DASH
            androidx.media3.common.C.CONTENT_TYPE_HLS -> C_TYPE_HLS
            else -> {
                val lower = option.url.lowercase()
                when {
                    lower.contains("mpd") || lower.contains("dash") || lower.contains(".livx") -> C_TYPE_DASH
                    else -> C_TYPE_HLS
                }
            }
        }
    }

    private companion object {
        const val C_TYPE_HLS = 2
        const val C_TYPE_DASH = 0
    }
}
