package com.idanplusil.resolver.technique

import com.idanplusil.resolver.FakeHttpFacade
import com.idanplusil.resolver.config.ConfigLoader
import com.idanplusil.resolver.config.ResolverSpec
import com.idanplusil.resolver.model.Channel
import com.idanplusil.resolver.model.Container
import com.idanplusil.resolver.model.ResolveOutcome
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class DirectResolverTest {

    private val channel = Channel(id = "11", title = "Ch 11", bundledFallbackUrl = "https://bundled/f.m3u8")

    private fun spec(json: String) =
        ResolverSpec(ConfigLoader.DefaultJson.parseToJsonElement(json) as JsonObject)

    @Test
    fun `options list replaces the synthetic playlist string, in priority order`() = runTest {
        val outcome = DirectResolver().resolve(
            channel,
            spec(
                """{"type":"direct","options":[
                     {"url":"https://a/main.m3u8","label":"HD","priority":90},
                     {"url":"https://b/backup.m3u8","label":"Backup","priority":90},
                     {"url":"https://c/subs.m3u8","label":"Subtitles","priority":50}]}"""
            ),
            FakeHttpFacade(),
        )
        assertIs<ResolveOutcome.Ok>(outcome)
        assertEquals(listOf("https://a/main.m3u8", "https://b/backup.m3u8", "https://c/subs.m3u8"),
            outcome.options.map { it.url })
        // Two options declared the same priority; the resolver must still
        // produce a strict order or auto-select is nondeterministic.
        val p = outcome.options.map { it.priority }
        assertEquals(p.sortedDescending(), p)
        assertEquals(p.distinct().size, p.size, "priorities not distinct: $p")
    }

    @Test
    fun `single stream keeps the simple shape and honours the container hint`() = runTest {
        val outcome = DirectResolver().resolve(
            channel, spec("""{"type":"direct","stream":"https://a/live.livx","container":"dash"}"""), FakeHttpFacade(),
        )
        assertIs<ResolveOutcome.Ok>(outcome)
        assertEquals(Container.DASH, outcome.options.single().container)
    }

    @Test
    fun `no url at all falls to the bundled fallback`() = runTest {
        val outcome = DirectResolver().resolve(channel, spec("""{"type":"direct"}"""), FakeHttpFacade())
        assertIs<ResolveOutcome.Ok>(outcome)
        assertEquals("https://bundled/f.m3u8", outcome.options.single().url)
    }
}
