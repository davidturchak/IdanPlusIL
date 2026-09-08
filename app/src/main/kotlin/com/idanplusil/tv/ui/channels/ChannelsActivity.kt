package com.idanplusil.tv.ui.channels

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.idanplusil.tv.IdanPlusApplication
import com.idanplusil.tv.data.update.UpdateError
import com.idanplusil.tv.ui.player.PlayerActivity
import com.idanplusil.tv.ui.theme.IdanPlusTheme
import com.idanplusil.tv.update.InstallLaunch

class ChannelsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phones and tablets: draw behind the system bars on every API level, not
        // only where Android 15 forces it. The theme is always dark, so the bar
        // icons are pinned light rather than following the device's day/night.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val container = (application as IdanPlusApplication).container
        val installer = container.updateInstaller
        // The ViewModel keeps this lambda across configuration changes; it must not hold the Activity.
        val appContext = applicationContext

        setContent {
            IdanPlusTheme {
                val vm: ChannelsViewModel = viewModel(
                    factory = ChannelsViewModel.Factory(container.channelRepository)
                )
                val state by vm.state.collectAsStateWithLifecycle()

                val updateVm: UpdateViewModel = viewModel(
                    factory = UpdateViewModel.Factory(container) { installer.canRequestInstalls(appContext) }
                )
                val updateState by updateVm.state.collectAsStateWithLifecycle()
                // Coming back from the "install unknown apps" settings screen.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updateVm.onResumed() }

                ChannelsScreen(
                    state = state,
                    onChannelClick = { channel ->
                        startActivity(PlayerActivity.intent(this, channel.id))
                    },
                    onRetry = vm::refresh,
                    updateState = updateState,
                    updateActions = UpdateActions(
                        onCheck = updateVm::checkManually,
                        onDownload = updateVm::startDownload,
                        onCancel = updateVm::cancelDownload,
                        onLater = updateVm::later,
                        onDismiss = updateVm::dismiss,
                        onRetry = updateVm::retry,
                        onInstall = { _, file ->
                            val launch = installer.launch(this, file)
                            if (launch is InstallLaunch.Refused) updateVm.onInstallRefused(launch.reason)
                        },
                        onAllowInstalls = {
                            if (!installer.openUnknownSourcesSettings(this)) {
                                updateVm.onInstallRefused(UpdateError.SettingsUnavailable)
                            }
                        },
                    ),
                )
            }
        }
    }
}
