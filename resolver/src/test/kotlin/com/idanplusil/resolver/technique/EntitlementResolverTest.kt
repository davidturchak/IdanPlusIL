package com.idanplusil.resolver.technique

import com.idanplusil.resolver.FakeHttpFacade
import com.idanplusil.resolver.config.ConfigLoader
import com.idanplusil.resolver.config.ResolverSpec
import com.idanplusil.resolver.http.HttpResponse
import com.idanplusil.resolver.model.Channel
import com.idanplusil.resolver.model.ResolveOutcome
import com.idanplusil.resolver.model.Stage
import com.idanplusil.resolver.token.InMemoryTokenStore
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class EntitlementResolverTest {

    private val channel = Channel(id = "12", title = "Keshet 12")
    private val entitlementUrl = "https://ent.example/api"
    private val configured = "https://cdn.example/live/index.m3u8"
    private val spec = ResolverSpec(
        ConfigLoader.DefaultJson.parseToJsonElement(
            """{"type":"entitlement","entitlementUrl":"$entitlementUrl","stream":"$configured","ticketTtlSeconds":600}"""
        ) as JsonObject
    )

    private fun http(body: String) = FakeHttpFacade(mapOf(entitlementUrl to HttpResponse(200, body, entitlementUrl)))

    @Test
    fun `a ticket object without a ticket string is a denial, not a grant`() = runTest {
        val store = InMemoryTokenStore()
        val outcome = EntitlementResolver(store) { 1_000L }.resolve(
            channel, spec, http("""{"caseId":"1","tickets":[{"url":"$configured"}]}""")
        )
        assertIs<ResolveOutcome.Failed>(outcome)
        assertEquals(Stage.ENTITLEMENT, outcome.stage)
        // Nothing blank was cached for the next ten minutes.
        assertEquals(null, store.get("${EntitlementResolver.cacheKeyPrefix("12")}$configured", 1_000L))
    }

    @Test
    fun `the cached path replays the granted URL and the original expiry`() = runTest {
        val store = InMemoryTokenStore()
        val granted = "https://edge7.example/live/index.m3u8"
        val body = """{"caseId":"1","tickets":[{"ticket":"hdnts=abc","url":"$granted"}]}"""
        var now = 1_000L
        val resolver = EntitlementResolver(store) { now }

        val first = resolver.resolve(channel, spec, http(body)) as ResolveOutcome.Ok
        assertEquals("$granted?hdnts=abc", first.options.single().url)
        assertEquals(1_000L + 600_000L, first.options.single().expiresAtMillis)

        now = 200_000L
        val second = resolver.resolve(channel, spec, http("""{"caseId":"0"}""")) as ResolveOutcome.Ok
        assertEquals("$granted?hdnts=abc", second.options.single().url, "cached path must use the URL that was granted")
        assertEquals(1_000L + 600_000L, second.options.single().expiresAtMillis, "cached path must report the stored expiry")
    }

    @Test
    fun `invalidating the channel prefix forces a fresh ticket`() = runTest {
        val store = InMemoryTokenStore()
        val resolver = EntitlementResolver(store) { 1_000L }
        val http = http("""{"caseId":"1","tickets":[{"ticket":"hdnts=one","url":"$configured"}]}""")
        resolver.resolve(channel, spec, http)
        store.invalidatePrefix(EntitlementResolver.cacheKeyPrefix("12"))
        val again = resolver.resolve(channel, spec, http("""{"caseId":"1","tickets":[{"ticket":"hdnts=two","url":"$configured"}]}"""))
        assertTrue((again as ResolveOutcome.Ok).options.single().url.endsWith("hdnts=two"))
    }
}
