package com.idanplusil.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Screen margins.
 *
 * On a TV this is the overscan safe area, 5% of a 960x540dp layout space. On a
 * phone or tablet the system-bar insets are handled separately with
 * [androidx.compose.foundation.layout.WindowInsets] and the margin is an
 * ordinary content gutter.
 *
 * Uses start/end rather than left/right throughout, so adding a Hebrew locale
 * later is a translation task with no layout consequences.
 */
private val TvSafeAreaHorizontal = 48.dp
private val TvSafeAreaVertical = 27.dp
private val TouchMarginHorizontal = 16.dp
private val TouchMarginVertical = 12.dp

@Composable
fun screenMarginHorizontal(): Dp = if (isTelevision()) TvSafeAreaHorizontal else TouchMarginHorizontal

@Composable
fun screenMarginVertical(): Dp = if (isTelevision()) TvSafeAreaVertical else TouchMarginVertical
