package com.idanplusil.resolver.technique

import com.idanplusil.resolver.StreamResolver
import com.idanplusil.resolver.config.ConfigLoader
import com.idanplusil.resolver.config.ResolverSpec
import com.idanplusil.resolver.dto.EntitlementResponse
import com.idanplusil.resolver.http.HttpFacade
import com.idanplusil.resolver.http.appendQuery
import com.idanplusil.resolver.http.normalizeProtocolRelative
import com.idanplusil.resolver.model.Channel
import com.idanplusil.resolver.model.ResolveOutcome
import com.idanplusil.resolver.model.Stage
import com.idanplusil.resolver.model.StreamOption
import com.idanplusil.resolver.token.CachedToken
import com.idanplusil.resolver.token.TokenStore
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

/**
 * The manifest URL is public but rejects unauthenticated requests; a separate
 * entitlements service issues a short-lived ticket appended as a query string.
 *
 * Two things this deliberately does not copy from the reference app:
 *  - the ticket is applied only to the URL the entitlement response itself
 *    names. The original takes one ticket and reuses it across several other
 *    CDN paths the response never granted; that is a pattern to understand, not
 *    to reproduce.
 *  - the ticket is stored with an expiry and refreshed proactively, rather than
 *    cached bare and rediscovered via a 403.
 *
 * The ticket is also the credential for *every segment*, not just the manifest,
 * which is why it is carried on the option and the same HTTP client is handed
 * to the player.
 */
class EntitlementResolver(
    private val tokenStore: TokenStore? = null,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : StreamResolver {

    override val type: String = "entitlement"

    @Serializable
    data class Option(val url: String, val label: String? = null, val priority: Int? = null)

    @Serializable
    data class Config(
        val entitlementUrl: String = "",
        /**
         * Several manifests, each entitled separately. One ticket is requested
         * per URL - the provider's own flow - rather than minting one and
         * stamping it across paths the response never named.
         */
        val options: List<Option> = emptyList(),
        /** Query parameter carrying the manifest URL being entitled. */
        val lpParam: String = "lp",
        /** Query parameter carrying the CDN name. */
        val cdnParam: String = "rv",
        val etParam: String = "et",
        val etValue: String = "gt",
        val cdn: String = "AKAMAI",
        val stream: String? = null,
        val ticketTtlSeconds: Long = 600,
        val label: String? = null,
    )

    override suspend fun resolve(
        channel: Channel,
        spec: ResolverSpec,
        http: HttpFacade,
    ): ResolveOutcome {
        val cfg = runCatching { spec.decode<Config>(ConfigLoader.DefaultJson) }
            .getOrElse { return ResolveOutcome.Failed(Stage.PARSE, "bad entitlement config") }

        if (cfg.entitlementUrl.isBlank()) {
            return ResolveOutcome.Failed(Stage.PARSE, "no entitlementUrl configured")
        }
        val targets: List<Option> = when {
            cfg.options.isNotEmpty() -> cfg.options.filter { it.url.isNotBlank() }
            else -> listOfNotNull((cfg.stream ?: channel.bundledFallbackUrl)?.let { Option(it, cfg.label) })
        }
        if (targets.isEmpty()) return ResolveOutcome.Failed(Stage.EXTRACT, "no manifest URL to entitle")

        // Entitle every candidate concurrently; each is independent, and a
        // failure on one must not cost the others.
        val results: List<Result<StreamOption>> = coroutineScope {
            targets.mapIndexed { index, target ->
                async { entitle(channel, cfg, http, target, index) }
            }.map { it.await() }
        }

        val ok = results.mapNotNull { it.getOrNull() }
        if (ok.isNotEmpty()) return ResolveOutcome.Ok(ok)

        val first = results.firstOrNull()?.exceptionOrNull()
        return when (first) {
            is Denied -> ResolveOutcome.Failed(Stage.ENTITLEMENT, first.message)
            is Unparseable -> ResolveOutcome.Failed(Stage.PARSE, first.message)
            else -> ResolveOutcome.Failed(Stage.FETCH, first?.message)
        }
    }

    private class Denied(msg: String) : Exception(msg)
    private class Unparseable(msg: String) : Exception(msg)

    private suspend fun entitle(
        channel: Channel,
        cfg: Config,
        http: HttpFacade,
        target: Option,
        index: Int,
    ): Result<StreamOption> = runCatching {
        val manifest = target.url.normalizeProtocolRelative()
        val label = target.label ?: cfg.label ?: channel.title
        // Distinct by construction even when config priorities tie.
        val priority = (target.priority ?: (100 - index * 10)) * 10 - index
        val now = clockMillis()
        val cacheKey = "${cacheKeyPrefix(channel.id)}$manifest"
        val expiresAt = now + cfg.ticketTtlSeconds * 1000

        tokenStore?.get(cacheKey, now)?.let { cached ->
            // Replay exactly what was granted: the URL the response named and the
            // expiry it was issued under, not the configured URL and a fresh TTL.
            return@runCatching option(cached.grantedUrl ?: manifest, cached.token, label, priority, cached.expiresAtMillis)
        }

        val url = cfg.entitlementUrl
            .appendQuery("${cfg.etParam}=${cfg.etValue}")
            .appendQuery("${cfg.lpParam}=${manifest.urlEncoded()}")
            .appendQuery("${cfg.cdnParam}=${cfg.cdn}")

        val response = http.get(url)
        if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")

        val parsed = runCatching {
            ConfigLoader.DefaultJson.decodeFromString(EntitlementResponse.serializer(), response.body)
        }.getOrElse { throw Unparseable("unparseable entitlement") }

        // A denial is the answer, not something to retry against other paths.
        if (!parsed.granted) throw Denied("denied (caseId=${parsed.caseId})")

        val ticket = parsed.tickets.first { it.ticket.isNotBlank() }

        // Entitle exactly the URL the response named.
        val granted = ticket.url.takeIf { it.isNotBlank() }?.normalizeProtocolRelative() ?: manifest
        tokenStore?.put(cacheKey, CachedToken(ticket.ticket, expiresAt, grantedUrl = granted))
        option(granted, ticket.ticket, label, priority, expiresAt)
    }

    private fun option(
        manifest: String,
        ticket: String,
        label: String,
        priority: Int,
        expiresAt: Long,
    ) = StreamOption(
        url = manifest.appendQuery(ticket),
        label = label,
        priority = priority,
        expiresAtMillis = expiresAt,
    )

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8")

    companion object {
        /** Every cached ticket for a channel lives under this prefix. */
        fun cacheKeyPrefix(channelId: String): String = "entitlement:$channelId:"
    }
}
