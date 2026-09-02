package com.idanplusil.tv.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.idanplusil.tv.ui.theme.BrandColors

@Composable
fun LoadingPane(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandLockup(height = 96.dp)
        Spacer(Modifier.height(28.dp))
        // A static bar, not a shimmer: a shimmer is a per-frame gradient
        // translation that competes with the content it is masking, and this
        // GPU has nothing to spare.
        androidx.compose.foundation.layout.Box(
            Modifier
                .width(200.dp)
                .height(4.dp)
                .then(Modifier)
        ) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                drawRect(BrandColors.Orange)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The one button look used everywhere: quiet at rest, unmistakably orange when focused. */
@Composable
fun brandButtonColors(primary: Boolean = true): ButtonColors = ButtonDefaults.colors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant,
    contentColor = if (primary) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    focusedContainerColor = BrandColors.FocusRing,
    focusedContentColor = MaterialTheme.colorScheme.onPrimary,
)

/**
 * Error, empty and prompt states.
 *
 * The action button takes initial focus deliberately: on a remote, the fix for
 * a dead screen should be the centre key, not a hunt. Focus is re-requested
 * whenever [actionLabel] changes, so consecutive states must use distinct
 * primary labels. [content] renders between the detail text and the buttons.
 */
@Composable
fun MessagePane(
    title: String,
    detail: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(actionLabel) {
        if (actionLabel != null) runCatching { focusRequester.requestFocus() }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (content != null) {
            Spacer(Modifier.height(24.dp))
            content()
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onAction,
                // The primary action is the one the remote's centre key should
                // land on, so it has to look unmistakably focused. No extra
                // focusable() here: it would add a second focus target outside
                // the Button, which then never shows focus or receives the click.
                colors = brandButtonColors(primary = true),
                modifier = Modifier.focusRequester(focusRequester),
            ) { Text(actionLabel) }
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSecondary,
                colors = brandButtonColors(primary = false),
            ) { Text(secondaryLabel) }
        }
    }
}
