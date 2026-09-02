package com.idanplusil.resolver.technique

import com.idanplusil.resolver.FakeHttpFacade
import com.idanplusil.resolver.config.ConfigLoader
import com.idanplusil.resolver.config.ResolverSpec
import com.idanplusil.resolver.model.Channel
import com.idanplusil.resolver.model.ResolveOutcome
import com.idanplusil.resolver.model.Stage
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class HtmlJsonResolverTest {

    private val channel = Channel(id = "11", title = "Kan 11")
    private val pageUrl = "https://www.example.org/live/tv.aspx?stationid=2"

    private fun spec(json: String) =
        ResolverSpec(ConfigLoader.DefaultJson.parseToJsonElement(json) as JsonObject)

    private val goodSpec = spec(
        """
        {"type":"html_json",
         "pages":[{"url":"$pageUrl","label":"Kan 11","priority":100}],
         "jsonSelector":"script#kan_app_search_data[type=application/json]",
         "jsonPointer":"/content/src",
         "iframeSelector":"iframe[src]",
         "iframePriorityDelta":-10}
        """
    )

    @Test
    fun `extracts the embedded json source and the companion iframe`() = runTest {
        val http = FakeHttpFacade.ok(pageUrl, FakeHttpFacade.fixture("kan/live_station_2.html"))
        val outcome = HtmlJsonResolver().resolve(channel, goodSpec, http)

        assertIs<ResolveOutcome.Ok>(outcome, "expected Ok, got $outcome")
        val urls = outcome.options.map { it.url }
        assertEquals("https://cdn.example/kan11/master.m3u8", urls[0])
        // Relative iframe src must resolve against the page URL. The reference
        // app ran a URL pattern over raw tag text and silently dropped these.
        assertEquals("https://www.example.org/embed/player?id=2", urls[1])
    }

    @Test
    fun `priorities are strictly descending so auto-select is deterministic`() = runTest {
        val http = FakeHttpFacade.ok(pageUrl, FakeHttpFacade.fixture("kan/live_station_2.html"))
        val outcome = HtmlJsonResolver().resolve(channel, goodSpec, http) as ResolveOutcome.Ok
        val priorities = outcome.options.map { it.priority }
        assertEquals(priorities.sortedDescending(), priorities)
        assertEquals(priorities.distinct().size, priorities.size, "duplicate priorities: $priorities")
    }

    @Test
    fun `a missing json block fails at EXTRACT rather than throwing`() = runTest {
        val http = FakeHttpFacade.ok(pageUrl, FakeHttpFacade.fixture("kan/live_no_block.html"))
        val outcome = HtmlJsonResolver().resolve(channel, goodSpec, http)
        assertIs<ResolveOutcome.Failed>(outcome)
        assertEquals(Stage.EXTRACT, outcome.stage)
    }
}
