package com.idanplusil.tv.ui.channels

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.idanplusil.tv.R
import com.idanplusil.tv.data.update.UpdateError
import com.idanplusil.tv.ui.common.BrandProgressBar
import com.idanplusil.tv.ui.common.MessagePane

/**
 * The update prompt, download progress, permission nudge and failure screens.
 * Replaces the channel grid while visible - no dialog windows on TV.
 */
@Composable
fun UpdatePane(state: UpdateUiState, actions: UpdateActions) {
    val context = LocalContext.current
    when (state) {
        is UpdateUiState.Hidden, UpdateUiState.Checking, UpdateUiState.UpToDate -> Unit

        is UpdateUiState.Available -> MessagePane(
            title = stringResource(R.string.update_available_title, state.manifest.versionName),
            detail = state.manifest.notes?.takeIf { it.isNotBlank() }
                ?: stringResource(
                    R.string.update_available_detail,
                    Formatter.formatShortFileSize(context, state.manifest.sizeBytes),
                ),
            actionLabel = stringResource(R.string.action_download_install),
            onAction = actions.onDownload,
            secondaryLabel = stringResource(R.string.action_later),
            onSecondary = actions.onLater,
        )

        is UpdateUiState.Downloading -> MessagePane(
            title = stringResource(R.string.update_downloading_title, state.manifest.versionName),
            detail = stringResource(
                R.string.update_downloading_progress,
                state.percent,
                Formatter.formatShortFileSize(context, state.bytes),
                Formatter.formatShortFileSize(context, state.manifest.sizeBytes),
            ),
            actionLabel = stringResource(R.string.action_cancel),
            onAction = actions.onCancel,
        ) {
            BrandProgressBar(progress = state.percent / 100f)
        }

        is UpdateUiState.ReadyToInstall -> MessagePane(
            title = stringResource(R.string.update_ready_title, state.manifest.versionName),
            detail = stringResource(R.string.update_ready_detail),
            actionLabel = stringResource(R.string.action_install),
            onAction = { actions.onInstall(state.manifest, state.file) },
            secondaryLabel = stringResource(R.string.action_later),
            onSecondary = actions.onLater,
        )

        is UpdateUiState.NeedsPermission -> MessagePane(
            title = stringResource(R.string.update_permission_title),
            detail = stringResource(R.string.update_permission_detail),
            actionLabel = stringResource(R.string.action_allow_installs),
            onAction = actions.onAllowInstalls,
            secondaryLabel = stringResource(R.string.action_later),
            onSecondary = actions.onLater,
        )

        is UpdateUiState.Failed -> MessagePane(
            title = stringResource(R.string.update_failed_title),
            detail = stringResource(state.reason.messageRes()),
            actionLabel = stringResource(R.string.action_retry),
            onAction = actions.onRetry,
            secondaryLabel = stringResource(R.string.action_close),
            onSecondary = actions.onDismiss,
        )
    }
}

private fun UpdateError.messageRes(): Int = when (this) {
    UpdateError.NoNetwork -> R.string.error_no_network
    UpdateError.Timeout -> R.string.error_update_timeout
    UpdateError.BadResponse -> R.string.error_update_bad_response
    UpdateError.Malformed -> R.string.error_update_malformed
    UpdateError.SizeMismatch -> R.string.error_update_size
    UpdateError.ChecksumMismatch -> R.string.error_update_checksum
    UpdateError.Storage -> R.string.error_update_storage
    UpdateError.WrongPackage -> R.string.error_update_wrong_package
    UpdateError.NoInstaller -> R.string.error_update_no_installer
    UpdateError.SettingsUnavailable -> R.string.error_update_settings_unavailable
}
