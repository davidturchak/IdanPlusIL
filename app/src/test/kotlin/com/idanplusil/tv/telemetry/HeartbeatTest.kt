package com.idanplusil.tv.telemetry

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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

    private val contractKeys = setOf(
        "device_id", "app_id", "version", "version_code",
        "sdk_int", "device", "installer", "locale", "abi",
    )

    private fun heartbeat(
        endpoint: String = server.url("/heartbeat").toString(),
        deviceContext: DeviceContext = DeviceContext.NONE,
    ) = Heartbeat(
        endpoint = endpoint,
        client = OkHttpClient(),
        deviceId = { "0f2a4c6e-1111-2222-3333-444455556666" },
        appId = "com.idanplusil.tv",
        version = "1.4.4",
        versionCode = 10,
        deviceContext = deviceContext,
    )

    @Test
    fun `posts the contract fields as JSON and treats 204 as success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val sent = heartbeat(
            deviceContext = DeviceContext(
                sdkInt = 34,
                device = "Xiaomi MIBOX4",
                installer = "com.android.packageinstaller",
                locale = "he-IL",
                abi = "arm64-v8a",
            )
        ).send()
        assertTrue(sent)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/heartbeat", recorded.path)
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        // The server rejects unknown keys, so the key set is the contract.
        assertEquals(contractKeys, body.keys)
        assertEquals("0f2a4c6e-1111-2222-3333-444455556666", body["device_id"]!!.jsonPrimitive.content)
        assertEquals("com.idanplusil.tv", body["app_id"]!!.jsonPrimitive.content)
        assertEquals("1.4.4", body["version"]!!.jsonPrimitive.content)
        assertEquals(10, body["version_code"]!!.jsonPrimitive.int)
        assertEquals(34, body["sdk_int"]!!.jsonPrimitive.int)
        assertEquals("Xiaomi MIBOX4", body["device"]!!.jsonPrimitive.content)
        assertEquals("com.android.packageinstaller", body["installer"]!!.jsonPrimitive.content)
        assertEquals("he-IL", body["locale"]!!.jsonPrimitive.content)
        assertEquals("arm64-v8a", body["abi"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unavailable device context is sent as JSON null, never omitted or invented`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        assertTrue(heartbeat(deviceContext = DeviceContext.NONE).send())

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(contractKeys, body.keys)
        for (key in listOf("sdk_int", "device", "installer", "locale", "abi")) {
            assertEquals(key, JsonNull, body[key])
        }
    }

    @Test
    fun `device label is the real manufacturer and model, not a template`() {
        assertEquals("Xiaomi MIBOX4", DeviceContext.deviceLabel("Xiaomi", "MIBOX4"))
        assertEquals("MIBOX4", DeviceContext.deviceLabel(null, "MIBOX4"))
        assertEquals(null, DeviceContext.deviceLabel("", " "))
        assertEquals(128, DeviceContext.deviceLabel("x".repeat(100), "y".repeat(100))!!.length)
        assertFalse(DeviceContext.deviceLabel("Xiaomi", "MIBOX4")!!.contains("\${"))
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
