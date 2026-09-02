package com.idanplusil.tv.data.update

import java.io.File
import java.security.MessageDigest

/**
 * The directory downloaded APKs live in (`cacheDir/updates`).
 *
 * Files are named `update-<versionCode>.apk`; an in-progress download is
 * `update-<versionCode>.apk.part`. Plain java.io so it is unit-testable.
 */
class ApkStore(val dir: File) {

    fun fileFor(versionCode: Int): File = File(dir, "update-$versionCode.apk")

    fun partFor(versionCode: Int): File = File(dir, "update-$versionCode.apk.part")

    fun ensureDir(): Boolean = dir.isDirectory || dir.mkdirs()

    /** Two times the size: the .part and the final file may briefly coexist on rename. */
    fun hasSpaceFor(sizeBytes: Long): Boolean {
        if (!ensureDir()) return false
        val usable = dir.usableSpace
        // Some filesystems report 0 for usableSpace; do not refuse on that alone.
        return usable == 0L || usable >= sizeBytes * 2
    }

    /**
     * A previously downloaded copy of [manifest], re-verified by size and hash.
     * Size alone is not enough: the installer may have been cancelled midway
     * through an earlier attempt and the file could be from a rebuilt release.
     */
    fun existingVerified(manifest: UpdateManifest): File? {
        val file = fileFor(manifest.versionCode)
        if (!file.isFile || file.length() != manifest.sizeBytes) return null
        val hash = runCatching { sha256(file) }.getOrNull() ?: return null
        return file.takeIf { hash == manifest.sha256 }
    }

    /**
     * Delete leftovers. Every `.part` (no download is in flight at startup) and
     * every APK at or below the running version, which includes the one that
     * installed this build.
     */
    fun prune(currentVersionCode: Int): List<File> {
        val removed = mutableListOf<File>()
        val files = dir.listFiles() ?: return removed
        for (f in files) {
            val keep = PATTERN.matchEntire(f.name)?.let { m ->
                val vc = m.groupValues[1].toIntOrNull() ?: return@let false
                m.groupValues[2].isEmpty() && vc > currentVersionCode
            } ?: false
            if (!keep && f.delete()) removed += f
        }
        return removed
    }

    companion object {
        private val PATTERN = Regex("""update-(\d+)\.apk(\.part)?""")

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().toHex()
        }

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
