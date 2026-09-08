package com.idanplusil.tv.ui.common

import android.view.ViewConfiguration
import androidx.compose.foundation.gestures.PressGestureScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tap support for tv-material components.
 *
 * tv-material's clickable Surface and Button react to D-pad/Enter only: their
 * click modifier is `handleDPadEnter` + `focusable` with no pointer handling at
 * all, so on a phone a tap on a channel card did nothing. This adds the missing
 * tap path. Press and release are mirrored into the same
 * [MutableInteractionSource] the component draws from, so a finger sees the same
 * pressed colours the remote does. On a D-pad device no pointer events arrive
 * and the modifier is inert.
 */
@Composable
fun Modifier.touchClickable(
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    val currentOnClick by rememberUpdatedState(onClick)
    return pointerInput(interactionSource) {
        detectTapGestures(
            onPress = { offset ->
                if (interactionSource != null) mirrorPress(interactionSource, offset)
            },
            onTap = { currentOnClick() },
        )
    }
}

/**
 * The pressed colours wait for the tap timeout, as foundation's `clickable`
 * does inside a scrollable: a grid scroll that starts on a card must not flash
 * it. A tap shorter than the timeout still shows press then release.
 */
private suspend fun PressGestureScope.mirrorPress(
    interactionSource: MutableInteractionSource,
    offset: Offset,
) = coroutineScope {
    val press = PressInteraction.Press(offset)
    var shown = false
    val delayed = launch {
        delay(TAP_INDICATION_DELAY_MS)
        interactionSource.emit(press)
        shown = true
    }
    val released = tryAwaitRelease()
    delayed.cancel()
    when {
        released -> {
            if (!shown) interactionSource.emit(press)
            interactionSource.emit(PressInteraction.Release(press))
        }
        shown -> interactionSource.emit(PressInteraction.Cancel(press))
    }
}

private val TAP_INDICATION_DELAY_MS = ViewConfiguration.getTapTimeout().toLong()
