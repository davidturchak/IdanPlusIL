package com.idanplusil.tv.data.update

import com.idanplusil.tv.data.update.ApkStore.Companion.toHex
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: ApkStore
    private val payload = ByteArray(300_000) { (it * 31).toByte() }
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload).toHex()

    @Before fun start() {
        server = MockWebServer().apply { start() }
        store = ApkStore(File(tmp.root, "updates"))
    }

    @After fun stop() { server.shutdown() }

    private fun manifest(
        size: Long = payload.size.toLong(),
        sha: String = payloadSha,
        path: String = "/releases/download/v1.1.0/IdanPlusIL-1.1.0.apk",
    ) = UpdateManifest(2, "1.1.0", server.url(path).toString(), sha, size)

    private fun body(bytes: ByteArray = payload) = MockResponse().setBody(Buffer().write(bytes))

    private fun leftovers() = store.dir.listFiles()?.map { it.name }.orEmpty()

    @Test
    fun `happy path through a redirect`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/objects/x.apk").toString()),
        )
        server.enqueue(body())

        val events = ApkDownloader(OkHttpClient()).download(manifest(), store).toList()

        val done = events.last() as DownloadEvent.Done
        assertTrue(done.file.isFile)
        assertEquals(payload.size.toLong(), done.file.length())
        val progress = events.filterIsInstance<DownloadEvent.Progress>().map { it.percent }
        assertEquals(progress.sorted(), progress)
        assertEquals(100, progress.last())
        assertEquals(listOf("update-2.apk"), leftovers())
    }

    @Test
    fun `checksum mismatch leaves nothing behind`() = runTest {
        server.enqueue(body())
        val events = ApkDownloader(OkHttpClient()).download(manifest(sha = "ff".repeat(32)), store).toList()
        assertEquals(DownloadEvent.Failed(UpdateError.ChecksumMismatch), events.last())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `short body is a size mismatch`() = runTest {
        server.enqueue(body(payload.copyOf(payload.size - 1000)))
        val events = ApkDownloader(OkHttpClient()).download(manifest(), store).toList()
        assertEquals(DownloadEvent.Failed(UpdateError.SizeMismatch), events.last())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `body longer than declared is a size mismatch`() = runTest {
        server.enqueue(body(payload + ByteArray(10)))
        val events = ApkDownloader(OkHttpClient()).download(manifest(), store).toList()
        assertEquals(DownloadEvent.Failed(UpdateError.SizeMismatch), events.last())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `disagreeing Content-Length fails before writing`() = runTest {
        server.enqueue(body())
        val events = ApkDownloader(OkHttpClient()).download(manifest(size = payload.size + 5L), store).toList()
        assertEquals(DownloadEvent.Failed(UpdateError.SizeMismatch), events.last())
        assertEquals(1, events.size)
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `http error is a bad response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val events = ApkDownloader(OkHttpClient()).download(manifest(), store).toList()
        assertEquals(DownloadEvent.Failed(UpdateError.BadResponse), events.last())
    }

    @Test
    fun `verified cached file skips the network`() = runTest {
        store.ensureDir()
        store.fileFor(2).writeBytes(payload)
        val events = ApkDownloader(OkHttpClient()).download(manifest(), store).toList()
        assertTrue(events.last() is DownloadEvent.Done)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cached file with the wrong hash is re-downloaded`() = runTest {
        store.ensureDir()
        store.fileFor(2).writeBytes(ByteArray(payload.size) { 7 })
        server.enqueue(body())
        val events = ApkDownloader(OkHttpClient()).download(manifest(), store).toList()
        assertTrue(events.last() is DownloadEvent.Done)
        assertEquals(1, server.requestCount)
        assertEquals(payloadSha, ApkStore.sha256(store.fileFor(2)))
    }

    @Test
    fun `prune removes parts and superseded builds`() {
        store.ensureDir()
        listOf("update-1.apk", "update-2.apk", "update-2.apk.part", "update-3.1234.apk.part", "update-3.apk", "junk.txt")
            .forEach { File(store.dir, it).writeText("x") }
        store.prune(currentVersionCode = 2)
        assertEquals(listOf("update-3.apk"), leftovers())
        assertFalse(store.newPartFor(2).exists())
    }
}
