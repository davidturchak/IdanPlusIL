package com.idanplusil.tv.ui.channels

import androidx.compose.runtime.Immutable
import com.idanplusil.resolver.model.Channel
import com.idanplusil.tv.data.config.ConfigError

@Immutable
sealed interface ChannelsUiState {
    data object Loading : ChannelsUiState

    @Immutable
    data class Content(
        val channels: List<Channel>,
        /** The published list could not be refreshed; this is cached or bundled data. */
        val stale: Boolean = false,
    ) : ChannelsUiState

    data object Empty : ChannelsUiState

    data class Error(val reason: ConfigError) : ChannelsUiState
}
