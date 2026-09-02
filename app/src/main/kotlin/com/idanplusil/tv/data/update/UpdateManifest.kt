package com.idanplusil.tv.data.update

import com.idanplusil.resolver.config.ConfigLoader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The published `config/update.json`.
 *
 * Written by tools/release.sh, never by hand. Only [versionCode] is compared
 * against the installed build; [sha256] and [sizeBytes] are verified before the
 * downloaded file is handed to the system installer.
 */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String? = null,
) {
    companion object {
        /** Returns null for anything the app should not act on. */
        fun parse(text: String, json: Json = ConfigLoader.DefaultJson): UpdateManifest? =
            runCatching { json.decodeFromString<UpdateManifest>(text) }.getOrNull()
                ?.takeIf { it.isSane() }
                ?.let { it.copy(sha256 = it.sha256.lowercase()) }

        private fun UpdateManifest.isSane(): Boolean =
            versionCode > 0 &&
                sizeBytes > 0 &&
                versionName.isNotBlank() &&
                apkUrl.startsWith("https://") &&
                sha256.length == 64 &&
                sha256.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }
}
