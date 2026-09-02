package com.idanplusil.tv.player

import com.idanplusil.resolver.model.StreamOption

enum class PlaybackFailure { Unresolvable, NetworkUnreachable, Forbidden, UnsupportedFormat, Unknown }

sealed interface PlaybackState {
    data object Resolving : PlaybackState
    data class Buffering(val option: StreamOption) : PlaybackState
    data class Playing(val option: StreamOption) : PlaybackState
    data class Paused(val option: StreamOption) : PlaybackState
    data class Failed(val reason: PlaybackFailure) : PlaybackState

    val currentOption: StreamOption?
        get() = when (this) {
            is Buffering -> option
            is Playing -> option
            is Paused -> option
            else -> null
        }
}
