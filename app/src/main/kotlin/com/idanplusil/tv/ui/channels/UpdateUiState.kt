package com.idanplusil.tv.ui.channels

import androidx.compose.runtime.Immutable
import com.idanplusil.tv.data.update.UpdateError
import com.idanplusil.tv.data.update.UpdateManifest
import java.io.File

@Immutable
sealed interface UpdateUiState {
    /** Nothing shown. [pending] is set after "Later": the header button offers to resume. */
    data class Hidden(val pending: UpdateManifest? = null) : UpdateUiState

    /** Manual check in flight; shown inline in the header, not as a pane. */
    data object Checking : UpdateUiState

    /** Manual check found nothing; transient, shown inline in the header. */
    data object UpToDate : UpdateUiState

    data class Available(val manifest: UpdateManifest) : UpdateUiState

    data class Downloading(val manifest: UpdateManifest, val percent: Int, val bytes: Long) : UpdateUiState

    data class ReadyToInstall(val manifest: UpdateManifest, val file: File) : UpdateUiState

    data class NeedsPermission(val manifest: UpdateManifest, val file: File) : UpdateUiState

    data class Failed(val manifest: UpdateManifest?, val reason: UpdateError) : UpdateUiState

    val manifestOrNull: UpdateManifest?
        get() = when (this) {
            is Hidden -> pending
            Checking, UpToDate -> null
            is Available -> manifest
            is Downloading -> manifest
            is ReadyToInstall -> manifest
            is NeedsPermission -> manifest
            is Failed -> manifest
        }

    /** True when the update UI replaces the channel grid. */
    val showsPane: Boolean
        get() = this !is Hidden && this !is Checking && this !is UpToDate
}

/** Callbacks for the update UI, bundled so ChannelsScreen's signature stays readable. */
@Immutable
data class UpdateActions(
    val onCheck: () -> Unit,
    val onDownload: () -> Unit,
    val onCancel: () -> Unit,
    val onLater: () -> Unit,
    val onDismiss: () -> Unit,
    val onRetry: () -> Unit,
    val onInstall: (UpdateManifest, File) -> Unit,
    val onAllowInstalls: () -> Unit,
)
