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

/**
 * Drives one channel at a time through resolve -> play -> recover.
 *
 * Everything here runs on the main thread. The only callback that arrives from
 * elsewhere is the load-error policy's re-resolve request, which is hopped onto
 * the main dispatcher before it touches any state.
 *
 * Recovery is organised in *episodes*. An episode starts when the viewer picks
 * a channel (start, zap, retry) or when a stream that had been showing a
 * picture fails. Within an episode the options are stepped through, at most one
 * re-resolve is allowed, and a single absolute deadline bounds the whole thing
 * so a dead channel cannot spin for half a minute. A failure after playback was
 * up opens a new episode with a fresh deadline: the old one expired minutes ago
 * and would otherwise fail every fallback the instant it was tried.
 */
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
    private var resolveJob: Job? = null
    /** Bumped on every resolve so a slow earlier resolution cannot apply its result over a later choice. */
    private var generation = 0
    /** Absolute deadline for getting a picture up in the current episode. */
    private var channelDeadlineMs: Long = 0L
    /** True once the current episode has shown a frame; a failure after that starts a new episode. */
    private var playedThisEpisode = false
    private var options: List<StreamOption> = emptyList()
    private var optionIndex = 0
    private var reresolved = false
    private var channels: List<Channel> = emptyList()

    private val resolving: Boolean get() = resolveJob?.isActive == true

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // Events from the source being replaced are noise while a new one resolves.
            if (resolving) return
            val option = options.getOrNull(optionIndex) ?: return
            _state.value = when (playbackState) {
                Player.STATE_BUFFERING -> PlaybackState.Buffering(option)
                Player.STATE_READY -> {
                    watchdog?.cancel()
                    playedThisEpisode = true
                    if (player.playWhenReady) PlaybackState.Playing(option)
                    else PlaybackState.Paused(option)
                }
                else -> _state.value
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (resolving) return
            val option = options.getOrNull(optionIndex) ?: return
            if (player.playbackState == Player.STATE_READY) {
                _state.value = if (isPlaying) PlaybackState.Playing(option) else PlaybackState.Paused(option)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (resolving) return
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
            beginEpisode(channel)
        }
    }

    fun retry() {
        val channel = _channel.value ?: return
        // The viewer is asking for another go, so do not hand back a cached ticket.
        beginEpisode(channel, fresh = true)
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
        beginEpisode(next)
        return next.id
    }

    /** A new episode: fresh deadline, one re-resolve available, and a resolution from scratch. */
    private fun beginEpisode(channel: Channel, fresh: Boolean = false) {
        resetEpisode()
        armWatchdog()
        launchResolve(channel, fresh = fresh)
    }

    private fun resetEpisode() {
        channelDeadlineMs = System.currentTimeMillis() + EPISODE_TIMEOUT_MS
        playedThisEpisode = false
        reresolved = false
    }

    /**
     * Resolves [channel] and, if this is still the resolution the viewer is
     * waiting on, plays its first option. Any resolution already in flight is
     * cancelled; its result is discarded even if it completes first.
     */
    private fun launchResolve(
        channel: Channel,
        fresh: Boolean,
        failureIfEmpty: PlaybackFailure = PlaybackFailure.Unresolvable,
    ) {
        resolveJob?.cancel()
        val myGeneration = ++generation
        resolveJob = viewModelScope.launch {
            _state.value = PlaybackState.Resolving
            val snapshot = withContext(Dispatchers.IO) { repository.localSnapshot() }
            val resolved = withContext(Dispatchers.IO) { resolution.resolve(channel, snapshot.config, fresh) }
            if (myGeneration != generation || _channel.value?.id != channel.id) return@launch
            options = resolved
            optionIndex = 0
            if (resolved.isEmpty()) {
                // Never reached the player at all - show a dedicated message rather
                // than a codec error.
                _state.value = PlaybackState.Failed(failureIfEmpty)
            } else {
                play(resolved[0])
            }
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
     * the viewer can act on is better than an indefinite wait. The deadline is
     * the episode's, so stepping through options and the one re-resolve share
     * it rather than each getting their own.
     */
    private fun armWatchdog() {
        watchdog?.cancel()
        val remaining = channelDeadlineMs - System.currentTimeMillis()
        watchdog = viewModelScope.launch {
            if (remaining > 0) delay(remaining)
            if (_state.value !is PlaybackState.Playing && _state.value !is PlaybackState.Paused) {
                resolveJob?.cancel()
                player.stop()
                _state.value = PlaybackState.Failed(PlaybackFailure.NetworkUnreachable)
            }
        }
    }

    /** A stream that was showing a picture has failed: give its recovery a full budget of its own. */
    private fun beginRecoveryIfPlayed() {
        if (playedThisEpisode) resetEpisode()
    }

    private fun advanceAfterError(error: PlaybackException) {
        beginRecoveryIfPlayed()
        // Step through the remaining options silently: the user sees a brief
        // buffer, not an error.
        if (optionIndex + 1 < options.size) {
            optionIndex++
            play(options[optionIndex])
            return
        }
        // Options exhausted. Re-resolve exactly once per episode - tokens may
        // have gone stale between resolution and playback, and that usually
        // fixes it.
        val channel = _channel.value
        if (!reresolved && channel != null) {
            reresolved = true
            armWatchdog()
            launchResolve(channel, fresh = true, failureIfEmpty = error.toFailure())
            return
        }
        _state.value = PlaybackState.Failed(error.toFailure())
    }

    /**
     * Called by the load-error policy from ExoPlayer's loader thread on every
     * 403/410, including each retry, so the once-per-episode guard has to be
     * evaluated on the main thread where the rest of the state lives.
     */
    private fun onNeedsReresolve() {
        viewModelScope.launch {
            val channel = _channel.value ?: return@launch
            if (resolving) return@launch
            beginRecoveryIfPlayed()
            if (reresolved) return@launch
            reresolved = true
            armWatchdog()
            launchResolve(channel, fresh = true)
        }
    }

    override fun onCleared() {
        watchdog?.cancel()
        resolveJob?.cancel()
        player.removeListener(listener)
        player.release()
        super.onCleared()
    }

    private companion object {
        /**
         * Resolution plus first frame. Resolution normally takes a second or two
         * (its own hard budget is ResolverRegistry.DEFAULT_BUDGET_MS); the rest
         * is generous enough for a slow live manifest and short enough not to
         * read as a hang.
         */
        const val EPISODE_TIMEOUT_MS = 15_000L
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
