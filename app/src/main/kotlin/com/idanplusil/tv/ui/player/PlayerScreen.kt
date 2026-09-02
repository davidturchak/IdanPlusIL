package com.idanplusil.tv.ui.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.idanplusil.resolver.model.Channel
import com.idanplusil.tv.R
import com.idanplusil.tv.player.PlaybackFailure
import com.idanplusil.tv.player.PlaybackState
import com.idanplusil.tv.ui.common.BrandSpinner
import com.idanplusil.tv.ui.common.MessagePane
import com.idanplusil.tv.ui.common.TvSafeAreaHorizontal
import com.idanplusil.tv.ui.theme.BrandColors

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    player: ExoPlayer,
    state: PlaybackState,
    channel: Channel?,
    overlayVisible: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // SurfaceView, never TextureView: TV SoCs route it through a hardware
        // overlay plane and skip GPU composition entirely. On this class of
        // chip that is the difference between smooth 1080p and dropped frames.
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            modifier = Modifier.fillMaxSize(),
        )

        when (state) {
            is PlaybackState.Failed -> MessagePane(
                title = stringResource(R.string.playback_failed_title),
                detail = stringResource(state.reason.messageRes()),
                actionLabel = stringResource(R.string.action_retry),
                onAction = onRetry,
                secondaryLabel = stringResource(R.string.action_back_to_channels),
                onSecondary = onBack,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.85f)),
            )

            PlaybackState.Resolving, is PlaybackState.Buffering ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BrandSpinner()
                }

            else -> Unit
        }

        AnimatedVisibility(
            visible = overlayVisible && state !is PlaybackState.Failed,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerOverlay(channel = channel, playing = state is PlaybackState.Playing)
        }
    }
}

@Composable
private fun PlayerOverlay(channel: Channel?, playing: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))
            )
            .padding(horizontal = TvSafeAreaHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                channel?.title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(BrandColors.LiveRed, CircleShape))
                Spacer(Modifier.width(8.dp))
                // No seek bar: a live stream has nothing to seek to, and a
                // progress bar that never fills reads as broken.
                Text(
                    stringResource(R.string.live),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }

        Box(
            Modifier
                .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                stringResource(if (playing) R.string.cd_pause else R.string.cd_play),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
        }
    }
}

private fun PlaybackFailure.messageRes(): Int = when (this) {
    PlaybackFailure.Unresolvable -> R.string.playback_error_unresolvable
    PlaybackFailure.NetworkUnreachable -> R.string.playback_error_network
    PlaybackFailure.Forbidden -> R.string.playback_error_forbidden
    PlaybackFailure.UnsupportedFormat -> R.string.playback_error_unsupported
    PlaybackFailure.Unknown -> R.string.playback_error_unknown
}
