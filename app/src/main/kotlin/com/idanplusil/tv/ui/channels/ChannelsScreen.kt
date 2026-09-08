package com.idanplusil.tv.ui.channels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.idanplusil.resolver.model.Channel
import com.idanplusil.tv.BuildConfig
import com.idanplusil.tv.R
import com.idanplusil.tv.data.config.ConfigError
import com.idanplusil.tv.ui.common.LoadingPane
import com.idanplusil.tv.ui.common.MessagePane
import com.idanplusil.tv.ui.common.isCompactWidth
import com.idanplusil.tv.ui.common.isFocusDriven
import com.idanplusil.tv.ui.common.isTelevision
import com.idanplusil.tv.ui.common.screenMarginHorizontal
import com.idanplusil.tv.ui.common.screenMarginVertical
import com.idanplusil.tv.ui.common.touchClickable
import com.idanplusil.tv.ui.theme.BrandColors
import com.idanplusil.tv.ui.common.BrandLockup

@Composable
fun ChannelsScreen(
    state: ChannelsUiState,
    onChannelClick: (Channel) -> Unit,
    onRetry: () -> Unit,
    updateState: UpdateUiState = UpdateUiState.Hidden(),
    updateActions: UpdateActions? = null,
) {
    // Hoisted so the scroll position survives the grid being swapped for the
    // update pane and back.
    val gridState = rememberLazyGridState()

    val paneVisible = updateActions != null && updateState.showsPane
    // Without this, Back on the update pane finishes the Activity - it exits the app.
    BackHandler(enabled = paneVisible) {
        when (updateState) {
            is UpdateUiState.Downloading -> updateActions?.onCancel?.invoke()
            is UpdateUiState.Available,
            is UpdateUiState.ReadyToInstall,
            is UpdateUiState.NeedsPermission -> updateActions?.onLater?.invoke()
            else -> updateActions?.onDismiss?.invoke()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // One static brush, allocated once. No animated gradients, no blur -
            // it stops the screen reading as an empty black rectangle and costs
            // nothing per frame.
            .background(
                Brush.radialGradient(
                    colors = listOf(BrandColors.Orange.copy(alpha = 0.06f), MaterialTheme.colorScheme.background),
                    center = Offset(0f, 0f),
                    radius = 1400f,
                )
            )
            // Phones and tablets draw edge to edge; the backgrounds above reach
            // the bars, the content stays clear of the status bar, gesture bar
            // and any cutout. On a TV these insets are zero.
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // The pane replaces the grid outright: an update may be the fix for a
        // broken grid, and a scrim over focusable cards is a D-pad trap.
        if (paneVisible) {
            UpdatePane(updateState, updateActions!!)
        } else when (state) {
            ChannelsUiState.Loading ->
                LoadingPane(stringResource(R.string.loading_channels))

            ChannelsUiState.Empty -> MessagePane(
                title = stringResource(R.string.channels_empty_title),
                detail = stringResource(R.string.channels_empty_detail),
                actionLabel = stringResource(R.string.action_retry),
                onAction = onRetry,
            )

            is ChannelsUiState.Error -> MessagePane(
                title = stringResource(R.string.channels_error_title),
                detail = stringResource(state.reason.messageRes()),
                actionLabel = stringResource(R.string.action_retry),
                onAction = onRetry,
            )

            is ChannelsUiState.Content -> ChannelGrid(
                state = state,
                gridState = gridState,
                onChannelClick = onChannelClick,
                updateLabel = if (updateActions != null) updateState.headerLabel() else null,
                onCheckUpdates = updateActions?.onCheck,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelGrid(
    state: ChannelsUiState.Content,
    gridState: LazyGridState,
    onChannelClick: (Channel) -> Unit,
    updateLabel: String?,
    onCheckUpdates: (() -> Unit)?,
) {
    val firstItemFocus = remember { FocusRequester() }
    val tv = isTelevision()
    val focusDriven = isFocusDriven()
    val marginH = screenMarginHorizontal()
    val marginV = screenMarginVertical()
    val gap = if (tv) 20.dp else 12.dp

    Column(Modifier.fillMaxSize()) {
        Header(
            stale = state.stale,
            updateLabel = updateLabel,
            onCheckUpdates = onCheckUpdates,
        )

        LazyVerticalGrid(
            // Card width is pinned to 140-160dp and the column count follows
            // the window. On a 960dp TV layout minus the 48dp safe area each
            // side that is the same five ~157dp columns as before (a box that
            // reports a wider dp layout gets more, not wider, cards); a portrait
            // phone gets two, a landscape phone four, a 10" tablet seven.
            columns = GridCells.Adaptive(minSize = CARD_MIN_WIDTH),
            state = gridState,
            contentPadding = PaddingValues(
                start = marginH,
                end = marginH,
                top = 8.dp,
                bottom = marginV,
            ),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap + 4.dp),
            modifier = Modifier
                .fillMaxSize()
                // Send focus to the first card when focus enters the grid, and
                // restore the previously focused card on the way back from the
                // player.
                // runCatching: on a two-column phone with a keyboard attached
                // the first card may be scrolled out of composition.
                .focusProperties { onEnter = { runCatching { firstItemFocus.requestFocus() } } },
        ) {
            items(state.channels, key = { it.id }) { channel ->
                ChannelCard(
                    channel = channel,
                    onClick = { onChannelClick(channel) },
                    modifier = if (channel.id == state.channels.first().id) {
                        Modifier.focusRequester(firstItemFocus)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }

    // Only a D-pad viewer needs a starting point. In touch mode a programmatic
    // focus would leave the first card permanently ringed and scaled; when the
    // viewer later leaves touch mode the platform assigns focus itself, so the
    // mode is read once here rather than keyed on.
    LaunchedEffect(state.channels.isNotEmpty()) {
        if (focusDriven && state.channels.isNotEmpty()) runCatching { firstItemFocus.requestFocus() }
    }
}

@Composable
private fun Header(
    stale: Boolean,
    updateLabel: String?,
    onCheckUpdates: (() -> Unit)?,
) {
    val compact = isCompactWidth()
    val marginH = screenMarginHorizontal()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 56.dp else 72.dp)
            .padding(start = marginH, end = marginH, top = screenMarginVertical()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BrandLockup(height = if (compact) 30.dp else 40.dp)

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (stale) {
                Box(
                    Modifier
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        stringResource(if (compact) R.string.offline_short else R.string.offline_showing_cached),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.width(if (compact) 8.dp else 16.dp))
            }
            // The installed version doubles as the "check for updates" control:
            // no button chrome at rest, a faint ring and brighter text when the
            // D-pad lands on it (reached with UP from the first row). While a
            // check runs, or an update is pending, the label says so instead.
            VersionBadge(
                label = updateLabel ?: stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                onClick = onCheckUpdates,
            )
        }
    }
}

@Composable
private fun VersionBadge(label: String, onClick: (() -> Unit)?) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    if (onClick == null) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = dim)
        return
    }
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier.touchClickable(onClick, interaction),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = dim,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
            pressedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            pressedContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(1.dp, BrandColors.FocusRing), shape = RoundedCornerShape(6.dp)),
        ),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private val CARD_MIN_WIDTH = 140.dp

/** Text that replaces the version while a check is running or an update is pending; null = show the version. */
@Composable
private fun UpdateUiState.headerLabel(): String? = when (this) {
    UpdateUiState.Checking -> stringResource(R.string.update_checking)
    UpdateUiState.UpToDate -> stringResource(R.string.update_up_to_date)
    is UpdateUiState.Hidden -> pending?.let { stringResource(R.string.action_update_available, it.versionName) }
    else -> null
}

private fun ConfigError.messageRes(): Int = when (this) {
    ConfigError.NoNetwork -> R.string.error_no_network
    ConfigError.Timeout -> R.string.error_timeout
    ConfigError.BadResponse -> R.string.error_bad_response
    ConfigError.Malformed -> R.string.error_malformed
}
