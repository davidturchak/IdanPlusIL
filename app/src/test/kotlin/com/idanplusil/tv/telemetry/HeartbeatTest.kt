package com.idanplusil.tv.telemetry

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HeartbeatTest {

    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun heartbeat(endpoint: String = server.url("/heartbeat").toString()) = Heartbeat(
        endpoint = endpoint,
        client = OkHttpClient(),
        deviceId = { "0f2a4c6e-1111-2222-3333-444455556666" },
        appId = "com.idanplusil.tv",
        version = "1.4.4",
        versionCode = 10,
    )

    @Test
    fun `posts the four fields as JSON and treats 204 as success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        assertTrue(heartbeat().send())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/heartbeat", recorded.path)
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(setOf("device_id", "app_id", "version", "version_code"), body.keys)
        assertEquals("0f2a4c6e-1111-2222-3333-444455556666", body["device_id"]!!.jsonPrimitive.content)
        assertEquals("com.idanplusil.tv", body["app_id"]!!.jsonPrimitive.content)
        assertEquals("1.4.4", body["version"]!!.jsonPrimitive.content)
        assertEquals(10, body["version_code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `any other status is a quiet failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        assertFalse(heartbeat().send())
        server.enqueue(MockResponse().setResponseCode(500))
        assertFalse(heartbeat().send())
    }

    @Test
    fun `connection failure does not throw`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertFalse(heartbeat().send())
    }

    @Test
    fun `unresolvable host does not throw`() = runTest {
        assertFalse(heartbeat(endpoint = "https://telemetry.invalid/heartbeat").send())
    }
}
