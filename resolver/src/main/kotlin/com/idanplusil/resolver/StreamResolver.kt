package com.idanplusil.resolver

import com.idanplusil.resolver.config.ResolverSpec
import com.idanplusil.resolver.http.HttpFacade
import com.idanplusil.resolver.model.Channel
import com.idanplusil.resolver.model.ResolveOutcome

/**
 * One resolution technique.
 *
 * The contract is **total**: an implementation returns [ResolveOutcome.Failed]
 * rather than throwing. Totality is additionally enforced at the registry, so a
 * technique author cannot break the caller by accident. A channel playing a
 * stale stream beats a channel showing an error.
 */
interface StreamResolver {
    val type: String

    suspend fun resolve(
        channel: Channel,
        spec: ResolverSpec,
        http: HttpFacade,
    ): ResolveOutcome
}
