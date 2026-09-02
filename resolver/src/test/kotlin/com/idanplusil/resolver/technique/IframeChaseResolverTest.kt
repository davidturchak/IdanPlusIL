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

class IframeChaseResolverTest {

    private val channel = Channel(id = "97", title = "Hidabroot")
    private val page = "https://www.example.org/live"
    private val embed = "https://player.example/embed/97"

    private fun spec(json: String) =
        ResolverSpec(ConfigLoader.DefaultJson.parseToJsonElement(json) as JsonObject)

    private val chaseSpec = spec(
        """{"type":"iframe_chase","pageUrl":"$page",
            "iframeSelector":"iframe#live_iframe_player[src]","maxHops":2,"sendReferer":true}"""
    )

    @Test
    fun `chases the iframe and returns every manifest it finds`() = runTest {
        val http = FakeHttpFacade.of(
            page to FakeHttpFacade.fixture("hidabroot/live_page.html"),
            embed to FakeHttpFacade.fixture("hidabroot/iframe_player.html"),
        )
        val outcome = IframeChaseResolver().resolve(channel, chaseSpec, http)
        assertIs<ResolveOutcome.Ok>(outcome, "got $outcome")
        assertEquals(
            listOf(
                "https://cdn.example/hidabroot/playlist.m3u8",
                "https://backup.example/hidabroot/playlist.m3u8",
            ),
            outcome.options.map { it.url },
        )
    }

    @Test
    fun `sends a Referer on the iframe hop`() = runTest {
        val http = FakeHttpFacade.of(
            page to FakeHttpFacade.fixture("hidabroot/live_page.html"),
            embed to FakeHttpFacade.fixture("hidabroot/iframe_player.html"),
        )
        IframeChaseResolver().resolve(channel, chaseSpec, http)

        assertEquals(2, http.requests.size)
        // The first hop has no referer; the second must carry the page it came from.
        assertEquals(emptyMap(), http.requests[0].headers)
        assertEquals(page, http.requests[1].headers["Referer"])
        assertEquals("https://www.example.org", http.requests[1].headers["Origin"])
    }

    @Test
    fun `refuses a pageUrl that is actually a manifest`() = runTest {
        // Three of the reference app's channels are configured this way, so
        // their iframe match can never fire and they are silently dead. Fail
        // loudly at EXTRACT instead.
        val badSpec = spec(
            """{"type":"iframe_chase","pageUrl":"https://cdn.example/master.m3u8"}"""
        )
        val outcome = IframeChaseResolver().resolve(channel, badSpec, FakeHttpFacade())
        assertIs<ResolveOutcome.Failed>(outcome)
        assertEquals(Stage.EXTRACT, outcome.stage)
        assertTrue(outcome.message!!.contains("manifest"))
    }
}
