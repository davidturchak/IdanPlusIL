package com.idanplusil.tv.data.config

import java.io.File

/**
 * On-disk cache of the published channel configuration.
 *
 * Written temp-then-rename so a process killed mid-write cannot leave a
 * truncated file behind - this is read on every cold start and must never be
 * the reason the app fails to open.
 */
class ConfigCache(private val dir: File) {

    private val file get() = File(dir, "channels.json")
    private val etagFile get() = File(dir, "channels.etag")

    fun read(): String? = runCatching {
        file.takeIf { it.exists() && it.length() > 0 }?.readText()
    }.getOrNull()

    fun etag(): String? = runCatching {
        etagFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun write(text: String, etag: String?) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "channels.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
            if (etag.isNullOrBlank()) etagFile.delete() else etagFile.writeText(etag)
        }
    }
}
