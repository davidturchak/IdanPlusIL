package com.idanplusil.resolver.config

import com.idanplusil.resolver.ResolverRegistry
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class ConfigLoaderTest {

    private val loader = ConfigLoader()

    @Test
    fun `the bundled channels file parses and every resolver type is known`() {
        val config = BundledDefaults.load(loader)
        assertTrue(config.live.isNotEmpty(), "bundled config is empty")

        val registry = ResolverRegistry.default()
        val unknown = config.live.values
            .mapNotNull { it.resolver?.type }
            .distinct()
            .filterNot(registry::has)
        assertEquals(emptyList(), unknown, "bundled config names resolver types the registry does not implement")
    }

    @Test
    fun `one malformed entry does not blank the lineup`() {
        val bad = mutableListOf<String>()
        val text = """
            {"schema":1,
             "live":{
               "11":{"show":true,"stream":"https://a/x.m3u8"},
               "12":{"show":"not a boolean at all","force":[1,2,3],"stream":{"nested":"object"}},
               "13":{"show":true,"stream":"https://c/z.m3u8"}
             }}
        """.trimIndent()

        val config = ConfigLoader(onBadEntry = { id, _ -> bad += id }).parse(text)

        assertTrue("11" in config.live)
        assertTrue("13" in config.live)
        assertEquals(listOf("12"), bad)
    }

    @Test
    fun `unknown fields are ignored so a newer config never breaks an older app`() {
        val text = """
            {"schema":9,"somethingNew":{"a":1},
             "live":{"11":{"show":true,"stream":"https://a/x.m3u8","futureField":42}}}
        """.trimIndent()
        val config = loader.parse(text)
        assertEquals("https://a/x.m3u8", config.live["11"]?.stream)
    }

    @Test
    fun `remote config overlays bundled defaults rather than replacing them`() {
        val bundled = loader.parse(
            """{"live":{"11":{"show":true,"stream":"https://bundled/11.m3u8"},
                        "12":{"show":true,"stream":"https://bundled/12.m3u8"}}}"""
        )
        val remote = loader.parse("""{"live":{"11":{"show":true,"stream":"https://remote/11.m3u8"}}}""")

        val merged = loader.merge(bundled, remote)

        assertEquals("https://remote/11.m3u8", merged.live["11"]?.stream)
        // A trimmed remote file must not silently remove a channel the app can play.
        assertEquals("https://bundled/12.m3u8", merged.live["12"]?.stream)
    }

    @Test
    fun `header sets resolve by reference`() {
        val config = loader.parse(
            """{"headerSets":{"b":{"User-Agent":"UA"}},
                "live":{"11":{"show":true,"resolver":{"type":"direct","headersRef":"b"}}}}"""
        )
        val spec = config.live["11"]?.resolver
        assertNotNull(spec)
        assertEquals(mapOf("User-Agent" to "UA"), config.headersFor(spec))
    }
}
