package com.idanplusil.resolver.livecheck

import com.idanplusil.resolver.model.Container
import com.idanplusil.resolver.model.StreamOption
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

data class ProbeResult(
    val option: StreamOption,
    val code: Int,
    val playable: Boolean,
    val note: String,
)

/**
 * Fetches the first couple of kilobytes of a manifest and checks it is what it
 * claims to be. Deliberately does not download segments - this answers "is the
 * manifest alive", not "does the whole stream work".
 */
object ManifestProbe {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun probe(option: StreamOption): ProbeResult {
        val request = Request.Builder()
            .url(option.url)
            .header("Range", "bytes=0-4095")
            .apply { option.headers.forEach { (k, v) -> header(k, v) } }
            .build()

        return try {
            client.newCall(request).execute().use { r ->
                val body = r.body?.string().orEmpty()
                val head = body.take(2048)
                val isHls = head.contains("#EXTM3U")
                val isDash = head.contains("<MPD") || head.contains("<mpd")
                val expectedDash = option.container == Container.DASH
                val ok = r.isSuccessful && (if (expectedDash) isDash else isHls || isDash)
                val note = when {
                    !r.isSuccessful -> "HTTP ${r.code}"
                    isHls -> if (head.contains("#EXT-X-STREAM-INF")) "master, ${head.split("#EXT-X-STREAM-INF").size - 1} variants" else "media playlist"
                    isDash -> "dash manifest"
                    else -> "not a manifest"
                }
                ProbeResult(option, r.code, ok, note)
            }
        } catch (e: Exception) {
            ProbeResult(option, -1, false, e.javaClass.simpleName + (e.message?.let { ": ${it.take(60)}" } ?: ""))
        }
    }
}
