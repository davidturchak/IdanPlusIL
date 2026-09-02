package com.idanplusil.tv.ui.channels

import com.idanplusil.tv.data.update.DownloadEvent
import com.idanplusil.tv.data.update.UpdateCheck
import com.idanplusil.tv.data.update.UpdateError
import com.idanplusil.tv.data.update.UpdateManifest
import com.idanplusil.tv.di.UpdateSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val manifest = UpdateManifest(2, "1.1.0", "https://x/y.apk", "00".repeat(32), 10)
    private val file = File("update-2.apk")

    @Before fun setMain() { Dispatchers.setMain(dispatcher) }
    @After fun resetMain() { Dispatchers.resetMain() }

    private fun vm(
        check: suspend () -> UpdateCheck,
        download: (UpdateManifest) -> Flow<DownloadEvent> = { flowOf(DownloadEvent.Done(file)) },
        canInstall: () -> Boolean = { true },
        session: UpdateSession = UpdateSession(),
    ) = UpdateViewModel(check, download, prune = {}, canInstall = canInstall, session = session)

    @Test
    fun `startup failure stays hidden`() = runTest(dispatcher) {
        val vm = vm({ UpdateCheck.Failed(UpdateError.NoNetwork) })
        advanceUntilIdle()
        assertEquals(UpdateUiState.Hidden(), vm.state.value)
    }

    @Test
    fun `startup finds an update after the delay`() = runTest(dispatcher) {
        val vm = vm({ UpdateCheck.Available(manifest) })
        advanceTimeBy(UpdateViewModel.STARTUP_CHECK_DELAY_MS - 1)
        assertEquals(UpdateUiState.Hidden(), vm.state.value)
        advanceUntilIdle()
        assertEquals(UpdateUiState.Available(manifest), vm.state.value)
    }

    @Test
    fun `manual check surfaces failure and up to date`() = runTest(dispatcher) {
        var result: UpdateCheck = UpdateCheck.Failed(UpdateError.Timeout)
        val vm = vm({ result })
        advanceUntilIdle()

        vm.checkManually()
        advanceUntilIdle()
        assertEquals(UpdateUiState.Failed(null, UpdateError.Timeout), vm.state.value)

        vm.dismiss()
        result = UpdateCheck.UpToDate
        vm.checkManually()
        advanceTimeBy(1)
        assertEquals(UpdateUiState.UpToDate, vm.state.value)
        advanceUntilIdle()
        assertEquals(UpdateUiState.Hidden(), vm.state.value)
    }

    @Test
    fun `later hides but keeps the pending update, and check resumes it`() = runTest(dispatcher) {
        val session = UpdateSession()
        val vm = vm({ UpdateCheck.Available(manifest) }, session = session)
        advanceUntilIdle()
        vm.later()
        assertEquals(UpdateUiState.Hidden(manifest), vm.state.value)
        assertEquals(manifest, session.dismissed)

        vm.checkManually()
        assertEquals(UpdateUiState.Available(manifest), vm.state.value)

        // A fresh ViewModel in the same process does not re-check.
        val again = vm({ error("must not be called") }, session = session)
        advanceUntilIdle()
        assertEquals(UpdateUiState.Hidden(manifest), again.state.value)
    }

    @Test
    fun `download without permission waits for the grant`() = runTest(dispatcher) {
        var granted = false
        val vm = vm(
            check = { UpdateCheck.Available(manifest) },
            download = { flowOf(DownloadEvent.Progress(50, 5), DownloadEvent.Done(file)) },
            canInstall = { granted },
        )
        advanceUntilIdle()
        vm.startDownload()
        advanceUntilIdle()
        assertEquals(UpdateUiState.NeedsPermission(manifest, file), vm.state.value)

        vm.onResumed()
        assertEquals(UpdateUiState.NeedsPermission(manifest, file), vm.state.value)
        granted = true
        vm.onResumed()
        assertEquals(UpdateUiState.ReadyToInstall(manifest, file), vm.state.value)
    }

    @Test
    fun `download failure offers retry back into the download`() = runTest(dispatcher) {
        var attempts = 0
        val vm = vm(
            check = { UpdateCheck.Available(manifest) },
            download = {
                attempts++
                if (attempts == 1) flowOf(DownloadEvent.Failed(UpdateError.ChecksumMismatch))
                else flowOf(DownloadEvent.Done(file))
            },
        )
        advanceUntilIdle()
        vm.startDownload()
        advanceUntilIdle()
        assertEquals(UpdateUiState.Failed(manifest, UpdateError.ChecksumMismatch), vm.state.value)
        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value is UpdateUiState.ReadyToInstall)
        assertEquals(2, attempts)
    }
}
