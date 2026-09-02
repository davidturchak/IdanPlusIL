package com.idanplusil.resolver

import com.idanplusil.resolver.model.Stage

/**
 * Structured break-point logging.
 *
 * Recording *which stage* failed is what makes a dead resolver diagnosable from
 * a report rather than from a repro. The app installs a sink that forwards to
 * logcat; tests leave it at the default no-op.
 */
object ResolverLog {
    fun interface Sink {
        fun onBreak(channelId: String, type: String, stage: Stage, message: String?)
    }

    @Volatile
    var sink: Sink = Sink { _, _, _, _ -> }

    fun breakPoint(channelId: String, type: String, stage: Stage, message: String? = null) {
        runCatching { sink.onBreak(channelId, type, stage, message) }
    }
}
