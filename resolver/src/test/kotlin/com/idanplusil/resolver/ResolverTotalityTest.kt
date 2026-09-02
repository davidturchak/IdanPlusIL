package com.idanplusil.resolver

import com.idanplusil.resolver.config.ConfigLoader
import com.idanplusil.resolver.config.ResolverSpec
import com.idanplusil.resolver.model.Channel
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * The contract every resolver must honour: no exception escapes, and nothing
 * hangs, whatever the network does. Run against every registered technique so a
 * newly added one cannot quietly break it.
 */
class ResolverTotalityTest {

    private val registry = ResolverRegistry.default()
    private val channel = Channel(id = "test", title = "Test", bundledFallbackUrl = "https://example/f.m3u8")

    @Test
    fun `every resolver is total against a hostile network`() = runTest {
        val specs = registry.knownTypes.map { type ->
            type to ResolverSpec(
                ConfigLoader.DefaultJson.parseToJsonElement(specJsonFor(type)) as JsonObject
            )
        }

        for ((type, spec) in specs) {
            for (behaviour in FakeHttpFacade.Behaviour.entries) {
                val http = FakeHttpFacade(behaviour = behaviour)
                // A short budget so NeverResponds proves the timeout works.
                val outcome = registry.resolve(channel, spec, http, budgetMs = 200)
                assertNotNull(outcome, "$type/$behaviour returned null")
            }
        }
    }

    @Test
    fun `an unknown resolver type fails without throwing`() = runTest {
        val spec = ResolverSpec(
            ConfigLoader.DefaultJson.parseToJsonElement("""{"type":"not_a_technique"}""") as JsonObject
        )
        val outcome = registry.resolve(channel, spec, FakeHttpFacade())
        assertNotNull(outcome)
    }

    private fun specJsonFor(type: String): String = when (type) {
        "direct" -> """{"type":"direct","stream":"https://example/a.m3u8"}"""
        "html_json" -> """{"type":"html_json","pages":[{"url":"https://example/page"}]}"""
        "iframe_chase" -> """{"type":"iframe_chase","pageUrl":"https://example/page"}"""
        "kaltura" -> """{"type":"kaltura","partnerId":1,"widgetId":"_1","entryId":"1_a"}"""
        "entitlement" -> """{"type":"entitlement","entitlementUrl":"https://example/ent","stream":"https://example/s.m3u8"}"""
        else -> """{"type":"$type"}"""
    }
}
