package com.idanplusil.resolver.http

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test

class HttpClientFactoryTest {

    private lateinit var server: MockWebServer
    private val factory = HttpClientFactory(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build())
    private val spec = SourceSpec(key = "t:direct", useCookies = true, readTimeoutMs = 30_000)

    @Before fun start() { server = MockWebServer().apply { start() } }
    // A response still being stalled on purpose can make shutdown time out; that is not the test's concern.
    @After fun stop() { runCatching { server.shutdown() } }

    @Test
    fun `a stalled body is cut off by the caller's timeout instead of hanging`() = runBlocking<Unit> {
        // Headers arrive at once, then the body stalls: the exact shape that left the old
        // non-cancellable bridge suspended inside onResponse.
        server.enqueue(MockResponse().setBody("late").setBodyDelay(20, TimeUnit.SECONDS))
        val started = System.nanoTime()
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(300) { factory.facadeFor(spec).get(server.url("/slow").toString()) }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assert(elapsedMs < 5_000) { "took ${elapsedMs}ms: the suspension did not observe cancellation" }
    }

    @Test
    fun `a body that fails mid-read surfaces as an exception, not a hang`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody("x".repeat(50_000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        assertFailsWith<IOException> {
            withTimeout(5_000) { factory.facadeFor(spec).get(server.url("/broken").toString()) }
        }
    }

    @Test
    fun `a changed header set gets a new client and an identical spec reuses one`() {
        val a = factory.clientFor(SourceSpec("12:entitlement", mapOf("User-Agent" to "Chrome/140")))
        val same = factory.clientFor(SourceSpec("12:entitlement", mapOf("User-Agent" to "Chrome/140")))
        val bumped = factory.clientFor(SourceSpec("12:entitlement", mapOf("User-Agent" to "Chrome/141")))
        assertSame(a, same)
        assertNotSame(a, bumped)
    }

    @Test
    fun `cookies picked up during resolution are exposed for the player`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("ok").addHeader("Set-Cookie", "sid=42; Path=/"))
        val url = server.url("/page").toString()
        factory.facadeFor(spec).get(url)
        assertEquals("sid=42", factory.cookieHeaderFor(spec, server.url("/live/index.m3u8").toString()))
        assertEquals(null, factory.cookieHeaderFor(spec.copy(useCookies = false), url))
    }
}
