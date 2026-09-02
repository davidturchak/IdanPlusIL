package com.idanplusil.resolver.model

/**
 * Static channel metadata.
 *
 * [epgId] is deliberately separate from [id] from day one: a guide provider's
 * identifier rarely matches an internal channel id, and merging them is
 * expensive to undo later. It is unused in v1 and that is fine.
 */
data class Channel(
    val id: String,
    val title: String,
    /**
     * Card art: an `http(s)` URL, or the bare name of a drawable bundled with
     * the app (`logo_11`). Bundled is the norm - logos rarely change and must
     * render offline on first paint; the URL form lets the published config
     * override one without a release. Resolution is the app's job.
     */
    val logo: String? = null,
    val epgId: String? = null,
    val categoryIds: List<String> = emptyList(),
    val sortOrder: Int = 0,
    /** Last-ditch URL compiled into the app, below even the remote config. */
    val bundledFallbackUrl: String? = null,
)
