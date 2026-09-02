package com.idanplusil.tv.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.idanplusil.tv.ui.theme.BrandColors

/**
 * A single rotating arc.
 *
 * Hand-drawn rather than pulled from compose-material3: adding that dependency
 * for one indicator would drag a second Material implementation into a
 * tv-material app, and this is a couple of GPU-cheap draw calls.
 */
@Composable
fun BrandSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = BrandColors.Orange,
) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "angle",
    )

    Canvas(modifier.size(size)) {
        val stroke = Stroke(width = 4.dp.toPx())
        drawArc(
            color = color.copy(alpha = 0.20f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = stroke,
        )
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 90f,
            useCenter = false,
            style = stroke,
        )
    }
}
