package com.idanplusil.resolver

import com.idanplusil.resolver.config.ConfigLoader
import com.idanplusil.resolver.http.HttpClientFactory
import com.idanplusil.resolver.model.Channel
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChannelResolutionServiceTest {

    private val loader = ConfigLoader()
    private val service = ChannelResolutionService(ResolverRegistry.default(), HttpClientFactory())
    private val channel = Channel(id = "11", title = "Ch11", bundledFallbackUrl = "https://bundled/f.m3u8")

    @Test
    fun `force short-circuits resolution entirely`() = runTest {
        val config = loader.parse(
            """{"live":{"11":{"show":true,"force":true,"stream":"https://forced/x.m3u8",
                 "resolver":{"type":"html_json","pages":[{"url":"https://never.called/"}]}}}}"""
        )
        val options = service.resolve(channel, config)
        assertEquals(1, options.size)
        assertEquals("https://forced/x.m3u8", options.first().url)
    }

    @Test
    fun `force with a blank stream falls through instead of playing nothing`() = runTest {
        // The reference app will happily "force" a null URL into the player.
        val config = loader.parse("""{"live":{"11":{"show":true,"force":true,"stream":""}}}""")
        val options = service.resolve(channel, config)
        assertEquals(listOf("https://bundled/f.m3u8"), options.map { it.url })
    }

    @Test
    fun `a failed resolver still yields the config and bundled fallbacks`() = runTest {
        val config = loader.parse(
            """{"live":{"11":{"show":true,"stream":"https://config/x.m3u8",
                 "resolver":{"type":"iframe_chase","pageUrl":"https://cdn/already.m3u8"}}}}"""
        )
        val options = service.resolve(channel, config)
        assertEquals(
            listOf("https://config/x.m3u8", "https://bundled/f.m3u8"),
            options.map { it.url },
        )
        assertTrue(service.isDegraded(options))
    }

    @Test
    fun `options come back sorted by priority and de-duplicated`() = runTest {
        val config = loader.parse(
            """{"live":{"11":{"show":true,"stream":"https://bundled/f.m3u8"}}}"""
        )
        // config stream and bundled fallback are the same URL - it must appear once.
        val options = service.resolve(channel, config)
        assertEquals(1, options.size)
        assertEquals(options.sortedByDescending { it.priority }, options)
    }
}
