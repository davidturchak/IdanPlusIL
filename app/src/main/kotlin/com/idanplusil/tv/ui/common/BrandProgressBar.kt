package com.idanplusil.tv.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.idanplusil.tv.ui.theme.BrandColors

/**
 * A determinate bar. Hand-drawn for the same reason as [BrandSpinner]: no
 * compose-material3 in a tv-material app. Redraws only when [progress] changes.
 */
@Composable
fun BrandProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    width: Dp = 320.dp,
    height: Dp = 6.dp,
    color: Color = BrandColors.Orange,
) {
    Canvas(modifier.size(width, height)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(color.copy(alpha = 0.2f), cornerRadius = radius)
        drawRoundRect(
            color,
            size = Size(size.width * progress.coerceIn(0f, 1f), size.height),
            cornerRadius = radius,
        )
    }
}
