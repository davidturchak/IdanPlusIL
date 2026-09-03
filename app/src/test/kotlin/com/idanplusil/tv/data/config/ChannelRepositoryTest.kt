package com.idanplusil.tv.data.config

import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChannelRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var cache: ConfigCache
    private lateinit var repository: ChannelRepository

    private val good = """{"live":{"11":{"show":true,"title":"Eleven","stream":"https://a/x.m3u8"}}}"""

    @Before fun start() {
        server = MockWebServer().apply { start() }
        cache = ConfigCache(File(tmp.root, "config"))
        repository = ChannelRepository(cache, RemoteConfigSource(server.url("/channels.json").toString(), OkHttpClient()))
    }

    @After fun stop() { server.shutdown() }

    @Test
    fun `a remote that parses to zero channels is reported broken and never cached`() = runTest {
        server.enqueue(MockResponse().setBody(good).addHeader("ETag", "\"good\""))
        val fresh = repository.refresh()
        assertEquals(listOf("Eleven"), fresh.channels.map { it.title })
        assertEquals("\"good\"", cache.etag())

        server.enqueue(MockResponse().setBody("""{"live": []}""").addHeader("ETag", "\"poison\""))
        val broken = repository.refresh()
        assertEquals(ConfigError.Malformed, broken.error)
        assertEquals("the last good lineup stays", listOf("Eleven"), broken.channels.map { it.title })
        assertEquals("the poisoned ETag must not be pinned", "\"good\"", cache.etag())
        assertTrue(cache.read()!!.contains("Eleven"))
    }

    @Test
    fun `a not-modified answer carries no error and reuses the cached ETag`() = runTest {
        server.enqueue(MockResponse().setBody(good).addHeader("ETag", "\"good\""))
        repository.refresh()
        server.takeRequest()
        server.enqueue(MockResponse().setResponseCode(304))
        assertNull(repository.refresh().error)
        assertEquals("\"good\"", server.takeRequest().getHeader("If-None-Match"))
    }
}
