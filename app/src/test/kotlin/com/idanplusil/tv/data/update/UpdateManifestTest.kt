package com.idanplusil.tv.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateManifestTest {

    private val sample = """
        {
          "versionCode": 2,
          "versionName": "1.1.0",
          "apkUrl": "https://github.com/davidturchak/IdanPlusIL/releases/download/v1.1.0/IdanPlusIL-1.1.0.apk",
          "sha256": "${"ab".repeat(32)}",
          "sizeBytes": 2237275,
          "notes": "Self-update"
        }
    """.trimIndent()

    @Test
    fun `parses the published shape`() {
        val m = UpdateManifest.parse(sample)
        assertNotNull(m)
        assertEquals(2, m!!.versionCode)
        assertEquals("1.1.0", m.versionName)
        assertEquals(2237275L, m.sizeBytes)
        assertEquals("Self-update", m.notes)
    }

    @Test
    fun `unknown keys are ignored and notes are optional`() {
        val m = UpdateManifest.parse(sample.replace("\"notes\": \"Self-update\"", "\"future\": {\"x\": 1}"))
        assertNotNull(m)
        assertNull(m!!.notes)
    }

    @Test
    fun `sha256 is normalised to lowercase`() {
        val m = UpdateManifest.parse(sample.replace("ab".repeat(32), "AB".repeat(32)))
        assertEquals("ab".repeat(32), m!!.sha256)
    }

    @Test
    fun `rejects unusable manifests`() {
        assertNull(UpdateManifest.parse("not json"))
        assertNull(UpdateManifest.parse(sample.replace("\"sha256\": \"${"ab".repeat(32)}\",", "")))
        assertNull(UpdateManifest.parse(sample.replace("ab".repeat(32), "ab".repeat(31))))
        assertNull(UpdateManifest.parse(sample.replace("https://", "http://")))
        assertNull(UpdateManifest.parse(sample.replace("2237275", "0")))
        assertNull(UpdateManifest.parse(sample.replace("\"versionCode\": 2", "\"versionCode\": 0")))
    }
}
