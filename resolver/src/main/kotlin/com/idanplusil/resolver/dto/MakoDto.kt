package com.idanplusil.resolver.dto

import kotlinx.serialization.Serializable

@Serializable
data class EntitlementTicket(
    val ticket: String = "",
    val url: String = "",
    val vendor: String? = null,
)

@Serializable
data class EntitlementResponse(
    val caseId: String? = null,
    val status: String? = null,
    val tickets: List<EntitlementTicket> = emptyList(),
) {
    /** A ticket object without a ticket string is a denial dressed as a grant. */
    val granted: Boolean get() = caseId == "1" && tickets.any { it.ticket.isNotBlank() }
}

@Serializable
data class MediaEntry(
    val format: String = "",
    val url: String = "",
    val cdn: String? = null,
)

@Serializable
data class MediaPlaylist(val media: List<MediaEntry> = emptyList())
