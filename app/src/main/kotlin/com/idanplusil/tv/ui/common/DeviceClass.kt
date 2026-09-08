package com.idanplusil.tv.ui.common

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/**
 * The app runs on TVs, tablets and phones from one layout. Two questions decide
 * what changes: what kind of screen this is (metrics: overscan margins, grid
 * density) and how the viewer is driving it (focus ring vs. finger).
 *
 * The split is deliberate: uiMode decides which controls exist and how far from
 * the edge they sit, input mode decides only focus and emphasis. A mixed-input
 * device (air mouse on a TV, gamepad on a phone) is not a third case.
 */

/** True on Android TV (leanback UI mode). Chooses overscan margins and card spacing. */
fun Configuration.isTelevision(): Boolean =
    (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

@Composable
fun isTelevision(): Boolean = LocalConfiguration.current.isTelevision()

/**
 * True while a D-pad or keyboard is driving the UI, so focus is what the viewer
 * sees and the initial focus requests matter. In touch mode a programmatic
 * focus would paint a permanent focus ring on a card nobody is pointing at.
 */
@Composable
fun isFocusDriven(): Boolean = LocalInputModeManager.current.inputMode == InputMode.Keyboard

/** A phone-width window (under 600dp, the Material compact breakpoint): shorter labels, a smaller lockup. */
@Composable
fun isCompactWidth(): Boolean = LocalWindowInfo.current.containerDpSize.width < 600.dp
