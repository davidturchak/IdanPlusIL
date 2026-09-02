package com.idanplusil.resolver.technique

import kotlin.test.assertTrue
import org.junit.Test

/**
 * Pins the multirequest body. The `{1:result:ks}` / `{2:result:objects:0:id}`
 * back-references are Kaltura's own mini-language, not JSON data, so an
 * accidental edit would fail silently at runtime rather than at compile time.
 */
class KalturaBodyTest {

    @Test
    fun `body carries the session back-references and the json format flag`() {
        val body = KalturaResolver().body(
            KalturaResolver.Config(partnerId = 2748741, widgetId = "_2748741", entryId = "1_x3xriuot")
        )
        assertTrue(body.contains(""""widgetId":"_2748741""""))
        assertTrue(body.contains(""""redirectFromEntryId":"1_x3xriuot""""))
        assertTrue(body.contains("""{1:result:ks}"""), "session back-reference missing")
        assertTrue(body.contains("""{2:result:objects:0:id}"""), "entry back-reference missing")
        // format=1 selects JSON. Without it the API answers in XML.
        assertTrue(body.contains(""""format":1"""))
        assertTrue(body.contains(""""partnerId":2748741"""))
    }
}
