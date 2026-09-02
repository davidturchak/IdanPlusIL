package com.idanplusil.tv.ui.common

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/**
 * Error and empty states.
 *
 * The action button takes initial focus deliberately: on a remote, the fix for
 * a dead screen should be the centre key, not a hunt.
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
        Spacer(Modifier.height(12.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onAction,
                // The primary action is the one the remote's centre key should
                // land on, so it has to look unmistakably focused.
                colors = ButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    focusedContainerColor = BrandColors.FocusRing,
                    focusedContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.focusRequester(focusRequester).focusable(),
            ) { Text(actionLabel) }
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSecondary,
                colors = ButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedContainerColor = BrandColors.FocusRing,
                    focusedContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text(secondaryLabel) }
        }
    }
}
