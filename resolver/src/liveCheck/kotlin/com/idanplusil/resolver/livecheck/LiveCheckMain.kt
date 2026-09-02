package com.idanplusil.resolver.livecheck

import com.idanplusil.resolver.ChannelResolutionService
import com.idanplusil.resolver.ResolverLog
import com.idanplusil.resolver.ResolverRegistry
import com.idanplusil.resolver.config.BundledDefaults
import com.idanplusil.resolver.config.ConfigLoader
import com.idanplusil.resolver.http.HttpClientFactory
import com.idanplusil.resolver.model.Channel
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

private enum class Status { OK, DEGRADED, FAIL }

private data class ChannelResult(
    val id: String,
    val title: String,
    val tier: String,
    val resolveMs: Long,
    val probes: List<ProbeResult>,
    val status: Status,
    val breaks: List<String>,
)

/**
 * Resolves every visible channel against the live network and reports which are
 * genuinely working.
 *
 * DEGRADED is the state this exists to surface: a channel that plays off its
 * static fallback looks fine to a user and to any naive "does it play" check,
 * while its resolver has been dead for months.
 *
 * Runs every channel concurrently, which also demonstrates that no HTTP state
 * is shared between resolvers.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val configText = args.firstOrNull { it.startsWith("--config=") }
        ?.removePrefix("--config=")
        ?.let { File(it).takeIf(File::exists)?.readText() }
        ?: BundledDefaults.readText()
        ?: error("no channel config available")

    val breaks = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
    ResolverLog.sink = ResolverLog.Sink { channelId, type, stage, message ->
        breaks.getOrPut(channelId) { java.util.Collections.synchronizedList(mutableListOf()) }
            .add("$type: $stage${message?.let { " - $it" } ?: ""}")
    }

    val config = ConfigLoader { id, e -> println("  ! bad config entry '$id': ${e.message}") }.parse(configText)
    val service = ChannelResolutionService(ResolverRegistry.default(), HttpClientFactory())

    val visible = config.visible().entries.sortedBy { it.value.sort }
    println("Checking ${visible.size} channels…\n")

    val results = visible.map { (id, cfg) ->
        async(Dispatchers.IO) {
            val channel = Channel(
                id = id,
                title = cfg.title ?: "Channel $id",
                epgId = cfg.epgId,
                bundledFallbackUrl = cfg.stream,
            )
            val started = System.nanoTime()
            val options = service.resolve(channel, config)
            val elapsed = (System.nanoTime() - started) / 1_000_000

            val probes = options.take(4).map { ManifestProbe.probe(it) }
            val anyPlayable = probes.any { it.playable }
            val onFallback = service.isDegraded(options)

            ChannelResult(
                id = id,
                title = channel.title,
                tier = options.firstOrNull()?.label ?: "-",
                resolveMs = elapsed,
                probes = probes,
                status = when {
                    anyPlayable && !onFallback -> Status.OK
                    anyPlayable -> Status.DEGRADED
                    else -> Status.FAIL
                },
                breaks = breaks[id].orEmpty().toList(),
            )
        }
    }.awaitAll()

    println("%-5s %-22s %-10s %8s %8s %s".format("id", "title", "tier", "resolve", "options", "status"))
    println("-".repeat(96))
    results.forEach { r ->
        val playable = r.probes.count { it.playable }
        println(
            "%-5s %-22s %-10s %7dms %5d/%-2d %s".format(
                r.id, r.title.take(22), r.tier.take(10), r.resolveMs, playable, r.probes.size, r.status
            )
        )
        r.probes.forEach { p ->
            val mark = if (p.playable) "ok  " else "FAIL"
            println("        $mark ${p.note.take(46).padEnd(46)} ${p.option.url.take(78)}")
        }
        r.breaks.forEach { println("        break  $it") }
    }

    val ok = results.count { it.status == Status.OK }
    val deg = results.count { it.status == Status.DEGRADED }
    val fail = results.count { it.status == Status.FAIL }
    println("\n$ok OK, $deg DEGRADED, $fail FAIL  (of ${results.size})")

    exitProcess(if (fail > 0) 1 else 0)
}
