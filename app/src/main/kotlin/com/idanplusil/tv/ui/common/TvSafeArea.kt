package com.idanplusil.tv.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * TV overscan safe area, 5% of a 960x540dp layout space.
 *
 * Uses start/end rather than left/right throughout, so adding a Hebrew locale
 * later is a translation task with no layout consequences.
 */
val TvSafeAreaHorizontal = 48.dp
val TvSafeAreaVertical = 27.dp

val TvSafeAreaPadding = PaddingValues(
    start = TvSafeAreaHorizontal,
    end = TvSafeAreaHorizontal,
    top = TvSafeAreaVertical,
    bottom = TvSafeAreaVertical,
)

fun Modifier.tvSafeArea(): Modifier = padding(TvSafeAreaPadding)
