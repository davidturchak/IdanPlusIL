package com.idanplusil.tv.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.idanplusil.tv.IdanPlusApplication
import com.idanplusil.tv.player.PlaybackState
import com.idanplusil.tv.player.PlayerFactory
import com.idanplusil.tv.ui.theme.IdanPlusTheme
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var viewModel: PlayerViewModel? = null
    private var playbackState: () -> PlaybackState = { PlaybackState.Resolving }
    private var showOverlay: (() -> Unit)? = null
    private var overlayIsVisible: () -> Boolean = { false }
    private var hideOverlay: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as IdanPlusApplication).container
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
            ?: run { finish(); return }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            IdanPlusTheme {
                val vm: PlayerViewModel = viewModel(
                    factory = PlayerViewModel.Factory(
                        container.channelRepository,
                        container.resolution,
                        PlayerFactory(this, container.baseHttpClient),
                    )
                )
                viewModel = vm

                val state by vm.state.collectAsStateWithLifecycle()
                val channel by vm.channel.collectAsStateWithLifecycle()
                playbackState = { state }

                var overlayVisible by remember { mutableStateOf(true) }
                var overlayTick by remember { mutableStateOf(0) }
                showOverlay = { overlayVisible = true; overlayTick++ }
                hideOverlay = { overlayVisible = false }
                overlayIsVisible = { overlayVisible }

                LaunchedEffect(overlayTick, overlayVisible) {
                    if (overlayVisible) {
                        delay(OVERLAY_TIMEOUT_MS)
                        overlayVisible = false
                    }
                }

                LaunchedEffect(channelId) { vm.start(channelId) }

                PlayerScreen(
                    player = vm.player,
                    state = state,
                    channel = channel,
                    overlayVisible = overlayVisible,
                    onRetry = vm::retry,
                    onBack = { finish() },
                )
            }
        }
    }

    /**
     * Key handling lives here rather than in a Compose modifier so it works
     * regardless of what currently holds focus - on a player screen, most of
     * the time nothing does.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val vm = viewModel ?: return super.onKeyDown(keyCode, event)

        // When the failure pane is up, the screen belongs to its buttons.
        // Swallowing D-pad here would leave Retry and Back unreachable and make
        // the centre key toggle playback on a stream that is not playing.
        if (playbackState() is PlaybackState.Failed) return super.onKeyDown(keyCode, event)

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (overlayIsVisible()) vm.togglePlayPause() else showOverlay?.invoke()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                vm.togglePlayPause(); showOverlay?.invoke(); true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> { finish(); true }
            // Channel zapping. This is what separates a TV app from a phone app
            // running on a TV.
            KeyEvent.KEYCODE_DPAD_UP -> { vm.zap(-1); showOverlay?.invoke(); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { vm.zap(+1); showOverlay?.invoke(); true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showOverlay?.invoke(); true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (overlayIsVisible()) { hideOverlay?.invoke(); true } else super.onKeyDown(keyCode, event)
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel?.player?.playWhenReady = false
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "channel_id"
        private const val OVERLAY_TIMEOUT_MS = 4_000L

        fun intent(context: Context, channelId: String): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_CHANNEL_ID, channelId)
    }
}
