package com.idanplusil.tv.ui.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
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
import com.idanplusil.tv.ui.common.isTelevision
import com.idanplusil.tv.ui.common.screenMarginHorizontal
import com.idanplusil.tv.ui.common.touchClickable
import com.idanplusil.tv.ui.theme.BrandColors

/**
 * The player.
 *
 * On a TV the remote drives everything through the Activity's key handling and
 * the overlay is informational. On a phone or tablet the same overlay carries
 * real controls - back, previous/next channel, play/pause - and a tap anywhere
 * on the picture shows or hides it. None of those controls is focusable, so the
 * D-pad path is untouched.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    player: ExoPlayer,
    state: PlaybackState,
    channel: Channel?,
    overlayVisible: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onTap: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onZap: (Int) -> Unit,
) {
    val touch = !isTelevision()
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

        // Tap layer above the SurfaceView. It sits below the failure pane and
        // the overlay controls, which take their own taps first.
        Box(Modifier.fillMaxSize().touchClickable(onTap))

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
            // The fade renders its content through an alpha layer, so keep that
            // layer to the bottom strip unless the overlay really spans the
            // screen (the touch Back button sits at the top).
            modifier = if (touch) Modifier else Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerOverlay(
                channel = channel,
                playing = state is PlaybackState.Playing,
                touch = touch,
                onBack = onBack,
                onTogglePlayPause = onTogglePlayPause,
                onZap = onZap,
            )
        }
    }
}

@Composable
private fun PlayerOverlay(
    channel: Channel?,
    playing: Boolean,
    touch: Boolean,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onZap: (Int) -> Unit,
) {
    val marginH = screenMarginHorizontal()
    // System bars are hidden here, so on a phone this is the display cutout.
    Box(
        Modifier
            .then(if (touch) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        if (touch) {
            TouchControl(
                glyph = Glyph.Back,
                contentDescription = stringResource(R.string.cd_back),
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = marginH, top = 12.dp),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(96.dp)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))
                )
                .padding(horizontal = marginH),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    channel?.title.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 1,
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

            if (touch) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TouchControl(
                        glyph = Glyph.Previous,
                        contentDescription = stringResource(R.string.cd_previous_channel),
                        onClick = { onZap(-1) },
                    )
                    TouchControl(
                        glyph = if (playing) Glyph.Pause else Glyph.Play,
                        contentDescription = stringResource(if (playing) R.string.cd_pause else R.string.cd_play),
                        onClick = onTogglePlayPause,
                        diameter = 56.dp,
                    )
                    TouchControl(
                        glyph = Glyph.Next,
                        contentDescription = stringResource(R.string.cd_next_channel),
                        onClick = { onZap(+1) },
                    )
                }
            } else {
                // A hint of what the centre key does right now, not a control.
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
    }
}

private enum class Glyph { Back, Previous, Next, Play, Pause }

/**
 * A round tap target with a hand-drawn glyph. Not focusable on purpose: on a
 * TV the remote already owns these actions through the Activity, and a
 * focusable control here would hijack the D-pad. Hand-drawn for the same
 * reason as [BrandSpinner]: no icon pack in a tv-material app.
 */
@Composable
private fun TouchControl(
    glyph: Glyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 48.dp,
) {
    Box(
        modifier = modifier
            .size(diameter)
            .background(Color.White.copy(alpha = 0.16f), CircleShape)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                onClick { onClick(); true }
            }
            .touchClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter * 0.42f)) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (glyph) {
                Glyph.Play -> drawPath(
                    Path().apply {
                        moveTo(w * 0.15f, 0f); lineTo(w, h / 2f); lineTo(w * 0.15f, h); close()
                    },
                    Color.White,
                )
                Glyph.Pause -> {
                    val bar = Size(w * 0.28f, h)
                    val r = CornerRadius(bar.width / 4)
                    drawRoundRect(Color.White, Offset(w * 0.12f, 0f), bar, r)
                    drawRoundRect(Color.White, Offset(w * 0.60f, 0f), bar, r)
                }
                Glyph.Previous -> drawPath(
                    Path().apply {
                        moveTo(w * 0.65f, h * 0.1f); lineTo(w * 0.3f, h / 2f); lineTo(w * 0.65f, h * 0.9f)
                    },
                    Color.White, style = stroke,
                )
                Glyph.Next -> drawPath(
                    Path().apply {
                        moveTo(w * 0.35f, h * 0.1f); lineTo(w * 0.7f, h / 2f); lineTo(w * 0.35f, h * 0.9f)
                    },
                    Color.White, style = stroke,
                )
                Glyph.Back -> drawPath(
                    Path().apply {
                        moveTo(w * 0.45f, h * 0.15f); lineTo(w * 0.1f, h / 2f); lineTo(w * 0.45f, h * 0.85f)
                        moveTo(w * 0.1f, h / 2f); lineTo(w * 0.95f, h / 2f)
                    },
                    Color.White, style = stroke,
                )
            }
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
