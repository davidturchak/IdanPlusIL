package com.idanplusil.resolver

import com.idanplusil.resolver.config.ResolverSpec
import com.idanplusil.resolver.http.HttpFacade
import com.idanplusil.resolver.model.Channel
import com.idanplusil.resolver.model.ResolveOutcome
import com.idanplusil.resolver.model.Stage
import com.idanplusil.resolver.technique.DirectResolver
import com.idanplusil.resolver.technique.EntitlementResolver
import com.idanplusil.resolver.technique.HtmlJsonResolver
import com.idanplusil.resolver.technique.IframeChaseResolver
import com.idanplusil.resolver.technique.KalturaResolver
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The single dispatch point.
 *
 * The reference app spreads this decision across four copies of an
 * `if (video.id == ...)` ladder. Here a channel names its technique in config
 * and the registry looks it up - adding a technique is a new file plus one line.
 */
class ResolverRegistry(resolvers: List<StreamResolver>) {

    private val byType: Map<String, StreamResolver> = resolvers.associateBy { it.type }

    val knownTypes: Set<String> get() = byType.keys

    fun has(type: String): Boolean = byType.containsKey(type)

    /**
     * Runs a resolver with the totality contract enforced: no exception escapes,
     * and nothing runs past [budgetMs].
     */
    suspend fun resolve(
        channel: Channel,
        spec: ResolverSpec,
        http: HttpFacade,
        budgetMs: Long = DEFAULT_BUDGET_MS,
    ): ResolveOutcome {
        val resolver = byType[spec.type]
            ?: return ResolveOutcome.Failed(Stage.PARSE, "unknown resolver type '${spec.type}'")

        return try {
            withTimeout(budgetMs) { resolver.resolve(channel, spec, http) }
        } catch (e: TimeoutCancellationException) {
            ResolveOutcome.Failed(Stage.TIMEOUT, "exceeded ${budgetMs}ms")
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ResolveOutcome.Failed(Stage.FETCH, e.message ?: e::class.java.simpleName)
        }.also { outcome ->
            if (outcome is ResolveOutcome.Failed) {
                ResolverLog.breakPoint(channel.id, spec.type, outcome.stage, outcome.message)
            }
        }
    }

    companion object {
        const val DEFAULT_BUDGET_MS = 12_000L

        /** Every technique v1 ships. Channel 9's JS-rendered page is deliberately absent. */
        fun default(): ResolverRegistry = ResolverRegistry(
            listOf(
                DirectResolver(),
                HtmlJsonResolver(),
                IframeChaseResolver(),
                KalturaResolver(),
                EntitlementResolver(),
            )
        )
    }
}
