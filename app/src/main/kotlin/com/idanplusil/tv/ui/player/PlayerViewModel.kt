package com.idanplusil.tv.ui.player

import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.idanplusil.resolver.ChannelResolutionService
import com.idanplusil.resolver.model.Channel
import com.idanplusil.resolver.model.StreamOption
import com.idanplusil.tv.data.config.ChannelRepository
import com.idanplusil.tv.player.PlaybackFailure
import com.idanplusil.tv.player.PlaybackState
import com.idanplusil.tv.player.PlayerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class PlayerViewModel(
    private val repository: ChannelRepository,
    private val resolution: ChannelResolutionService,
    private val playerFactory: PlayerFactory,
) : ViewModel() {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Resolving)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _channel = MutableStateFlow<Channel?>(null)
    val channel: StateFlow<Channel?> = _channel.asStateFlow()

    val player: ExoPlayer by lazy {
        playerFactory.create().apply { addListener(listener) }
    }

    private var watchdog: Job? = null
    /** Absolute deadline for getting a first frame on the channel the viewer chose. */
    private var channelDeadlineMs: Long = 0L
    private var options: List<StreamOption> = emptyList()
    private var optionIndex = 0
    private var reresolved = false
    private var channels: List<Channel> = emptyList()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val option = options.getOrNull(optionIndex) ?: return
            _state.value = when (playbackState) {
                Player.STATE_BUFFERING -> PlaybackState.Buffering(option)
                Player.STATE_READY -> {
                    watchdog?.cancel()
                    if (player.playWhenReady) PlaybackState.Playing(option)
                    else PlaybackState.Paused(option)
                }
                else -> _state.value
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val option = options.getOrNull(optionIndex) ?: return
            if (player.playbackState == Player.STATE_READY) {
                _state.value = if (isPlaying) PlaybackState.Playing(option) else PlaybackState.Paused(option)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            advanceAfterError(error)
        }
    }

    fun start(channelId: String) {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { repository.localSnapshot() }
            channels = snapshot.channels
            val channel = snapshot.channels.firstOrNull { it.id == channelId }
                ?: run { _state.value = PlaybackState.Failed(PlaybackFailure.Unresolvable); return@launch }
            _channel.value = channel
            beginChannelAttempt()
            resolve(channel)
        }
    }

    private suspend fun resolve(channel: Channel) {
        watchdog?.cancel()
        _state.value = PlaybackState.Resolving
        reresolved = false
        val snapshot = withContext(Dispatchers.IO) { repository.localSnapshot() }
        options = withContext(Dispatchers.IO) { resolution.resolve(channel, snapshot.config) }
        optionIndex = 0
        if (options.isEmpty()) {
            // Never reached the player at all - show a dedicated message rather
            // than a codec error.
            _state.value = PlaybackState.Failed(PlaybackFailure.Unresolvable)
        } else {
            play(options[0])
        }
    }

    private fun play(option: StreamOption) {
        _state.value = PlaybackState.Buffering(option)
        player.setMediaSource(playerFactory.mediaSourceFor(option) { onNeedsReresolve() })
        player.prepare()
        player.playWhenReady = true
        armWatchdog()
    }

    /**
     * Bounds how long a dead channel is allowed to spin.
     *
     * ExoPlayer retries a failing load with backoff before it surfaces an
     * error, which on an unreachable host adds up to well over half a minute of
     * spinner. On a remote-driven UI that reads as a hang; a definite failure
     * the viewer can act on is better than an indefinite wait.
     */
    private fun armWatchdog() {
        watchdog?.cancel()
        val remaining = channelDeadlineMs - System.currentTimeMillis()
        watchdog = viewModelScope.launch {
            if (remaining > 0) delay(remaining)
            if (_state.value !is PlaybackState.Playing && _state.value !is PlaybackState.Paused) {
                player.stop()
                _state.value = PlaybackState.Failed(PlaybackFailure.NetworkUnreachable)
            }
        }
    }

    /**
     * Starts the clock for a newly chosen channel. The deadline is absolute
     * rather than per attempt, so stepping through options and the one
     * re-resolve cannot silently add up to half a minute of spinner.
     */
    private fun beginChannelAttempt() {
        channelDeadlineMs = System.currentTimeMillis() + FIRST_FRAME_TIMEOUT_MS
    }

    private fun advanceAfterError(error: PlaybackException) {
        // Step through the remaining options silently: the user sees a brief
        // buffer, not an error.
        if (optionIndex + 1 < options.size) {
            optionIndex++
            play(options[optionIndex])
            return
        }
        // Options exhausted. Re-resolve exactly once - tokens may have gone
        // stale between resolution and playback, and that usually fixes it.
        val channel = _channel.value
        if (!reresolved && channel != null) {
            reresolved = true
            viewModelScope.launch {
                _state.value = PlaybackState.Resolving
                val snapshot = withContext(Dispatchers.IO) { repository.localSnapshot() }
                options = withContext(Dispatchers.IO) { resolution.resolve(channel, snapshot.config) }
                optionIndex = 0
                if (options.isEmpty()) _state.value = PlaybackState.Failed(error.toFailure())
                else play(options[0])
            }
            return
        }
        _state.value = PlaybackState.Failed(error.toFailure())
    }

    private fun onNeedsReresolve() {
        val channel = _channel.value ?: return
        if (reresolved) return
        reresolved = true
        viewModelScope.launch { resolve(channel) }
    }

    fun retry() {
        val channel = _channel.value ?: return
        beginChannelAttempt()
        viewModelScope.launch { resolve(channel) }
    }

    fun togglePlayPause() {
        player.playWhenReady = !player.playWhenReady
    }

    /** D-pad up/down zapping - the affordance that makes this feel like a TV app. */
    fun zap(delta: Int): String? {
        val current = _channel.value ?: return null
        if (channels.isEmpty()) return null
        val index = channels.indexOfFirst { it.id == current.id }
        if (index < 0) return null
        val next = channels[((index + delta) % channels.size + channels.size) % channels.size]
        _channel.value = next
        beginChannelAttempt()
        viewModelScope.launch { resolve(next) }
        return next.id
    }

    override fun onCleared() {
        watchdog?.cancel()
        player.removeListener(listener)
        player.release()
        super.onCleared()
    }

    private companion object {
        /** Generous enough for a slow live manifest, short enough not to read as a hang. */
        const val FIRST_FRAME_TIMEOUT_MS = 12_000L
    }

    class Factory(
        private val repository: ChannelRepository,
        private val resolution: ChannelResolutionService,
        private val playerFactory: PlayerFactory,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(repository, resolution, playerFactory) as T
    }
}

private fun PlaybackException.toFailure(): PlaybackFailure = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackFailure.NetworkUnreachable

    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PlaybackFailure.Forbidden

    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> PlaybackFailure.UnsupportedFormat

    else -> PlaybackFailure.Unknown
}
