package com.idanplusil.tv.ui.channels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.idanplusil.tv.data.update.DownloadEvent
import com.idanplusil.tv.data.update.UpdateCheck
import com.idanplusil.tv.data.update.UpdateError
import com.idanplusil.tv.data.update.UpdateManifest
import com.idanplusil.tv.di.AppContainer
import com.idanplusil.tv.di.UpdateSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The update state machine.
 *
 * Startup: a silent check a couple of seconds after the grid is up. Only an
 * available update changes what the user sees; every failure is a log line.
 * The check path cannot throw, so it can never be the reason the app fails to
 * open.
 */
class UpdateViewModel(
    private val check: suspend () -> UpdateCheck,
    private val download: (UpdateManifest) -> Flow<DownloadEvent>,
    private val prune: () -> Unit,
    private val canInstall: () -> Boolean,
    private val session: UpdateSession,
    private val log: (String) -> Unit = {},
    private val startupCheck: Boolean = true,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Hidden(session.dismissed))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { runCatching(prune) }
        if (startupCheck && session.dismissed == null) {
            viewModelScope.launch {
                delay(STARTUP_CHECK_DELAY_MS)
                runCheck(manual = false)
            }
        }
    }

    fun checkManually() {
        val s = _state.value
        if (s is UpdateUiState.Hidden && s.pending != null) {
            _state.value = UpdateUiState.Available(s.pending)
            return
        }
        if (s !is UpdateUiState.Hidden && s != UpdateUiState.UpToDate) return
        viewModelScope.launch { runCheck(manual = true) }
    }

    private suspend fun runCheck(manual: Boolean) {
        if (manual) _state.value = UpdateUiState.Checking
        val result = runCatching { check() }.getOrElse { UpdateCheck.Failed(UpdateError.NoNetwork) }
        when (result) {
            is UpdateCheck.Available -> {
                // A startup check must not interrupt something the user started meanwhile.
                if (manual || _state.value is UpdateUiState.Hidden) {
                    _state.value = UpdateUiState.Available(result.manifest)
                }
            }
            UpdateCheck.UpToDate -> if (manual) {
                _state.value = UpdateUiState.UpToDate
                delay(UP_TO_DATE_LINGER_MS)
                if (_state.value == UpdateUiState.UpToDate) _state.value = UpdateUiState.Hidden()
            }
            is UpdateCheck.Failed -> if (manual) {
                _state.value = UpdateUiState.Failed(null, result.reason)
            } else {
                log("update: startup check failed: ${result.reason}")
            }
        }
    }

    fun startDownload() {
        val manifest = _state.value.manifestOrNull ?: return
        // The old producer may still be blocked in a socket read; each attempt
        // writes its own .part file, so it can unwind on its own without
        // touching this one, and its cancelled collector can no longer update state.
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _state.value = UpdateUiState.Downloading(manifest, 0, 0)
            download(manifest).collect { event ->
                _state.value = when (event) {
                    is DownloadEvent.Progress -> UpdateUiState.Downloading(manifest, event.percent, event.bytes)
                    is DownloadEvent.Done -> if (canInstall()) {
                        UpdateUiState.ReadyToInstall(manifest, event.file)
                    } else {
                        UpdateUiState.NeedsPermission(manifest, event.file)
                    }
                    is DownloadEvent.Failed -> UpdateUiState.Failed(manifest, event.reason)
                }
            }
        }
    }

    fun cancelDownload() {
        val manifest = (_state.value as? UpdateUiState.Downloading)?.manifest
        downloadJob?.cancel()
        downloadJob = null
        _state.value = manifest?.let { UpdateUiState.Available(it) } ?: UpdateUiState.Hidden(session.dismissed)
    }

    /** "Later": hide for the rest of this process; the header button offers the update. */
    fun later() {
        val manifest = _state.value.manifestOrNull
        session.dismissed = manifest
        _state.value = UpdateUiState.Hidden(manifest)
    }

    /** "Close" on a failure or on an up-to-date notice. */
    fun dismiss() {
        _state.value = UpdateUiState.Hidden(session.dismissed)
    }

    fun retry() {
        val s = _state.value as? UpdateUiState.Failed ?: return
        if (s.manifest == null) {
            _state.value = UpdateUiState.Hidden(session.dismissed)
            viewModelScope.launch { runCheck(manual = true) }
        } else {
            _state.value = UpdateUiState.Available(s.manifest)
            startDownload()
        }
    }

    /** Called on every resume: the user may have just granted the install permission. */
    fun onResumed() {
        val s = _state.value
        if (s is UpdateUiState.NeedsPermission && canInstall()) {
            _state.value = UpdateUiState.ReadyToInstall(s.manifest, s.file)
        }
    }

    fun onInstallRefused(reason: UpdateError) {
        _state.value = UpdateUiState.Failed(_state.value.manifestOrNull, reason)
    }

    class Factory(
        private val container: AppContainer,
        private val canInstall: () -> Boolean,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = UpdateViewModel(
            check = { container.updateChecker.check() },
            download = { container.apkDownloader.download(it, container.apkStore) },
            prune = {
                val removed = container.apkStore.prune(com.idanplusil.tv.BuildConfig.VERSION_CODE)
                if (removed.isNotEmpty()) Log.i(TAG, "update: pruned ${removed.map { it.name }}")
            },
            canInstall = canInstall,
            session = container.updateSession,
            log = { Log.i(TAG, it) },
        ) as T
    }

    companion object {
        const val STARTUP_CHECK_DELAY_MS = 2_000L
        const val UP_TO_DATE_LINGER_MS = 2_500L
        private const val TAG = "IdanPlusIL"
    }
}
