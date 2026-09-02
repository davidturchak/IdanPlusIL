package com.idanplusil.tv.data.update

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateCheckerTest {

    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun checker(current: Int) =
        UpdateChecker(server.url("/config/update.json").toString(), OkHttpClient(), current)

    private fun manifest(versionCode: Int) = """
        {"versionCode": $versionCode, "versionName": "1.$versionCode.0",
         "apkUrl": "https://example.invalid/a.apk", "sha256": "${"00".repeat(32)}", "sizeBytes": 10}
    """.trimIndent()

    @Test
    fun `newer versionCode is available`() = runTest {
        server.enqueue(MockResponse().setBody(manifest(2)))
        val result = checker(current = 1).check()
        assertTrue(result is UpdateCheck.Available)
        assertEquals(2, (result as UpdateCheck.Available).manifest.versionCode)
    }

    @Test
    fun `equal or older versionCode is up to date`() = runTest {
        server.enqueue(MockResponse().setBody(manifest(2)))
        assertEquals(UpdateCheck.UpToDate, checker(current = 2).check())
        server.enqueue(MockResponse().setBody(manifest(1)))
        assertEquals(UpdateCheck.UpToDate, checker(current = 2).check())
    }

    @Test
    fun `404 is a bad response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(UpdateCheck.Failed(UpdateError.BadResponse), checker(1).check())
    }

    @Test
    fun `garbage body is malformed`() = runTest {
        server.enqueue(MockResponse().setBody("<html>nope</html>"))
        assertEquals(UpdateCheck.Failed(UpdateError.Malformed), checker(1).check())
    }

    @Test
    fun `connection failure is no network`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(UpdateCheck.Failed(UpdateError.NoNetwork), checker(1).check())
    }
}
