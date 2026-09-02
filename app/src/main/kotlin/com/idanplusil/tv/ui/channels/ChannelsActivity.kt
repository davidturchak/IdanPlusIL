package com.idanplusil.tv.ui.channels

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.getValue
import com.idanplusil.tv.IdanPlusApplication
import com.idanplusil.tv.ui.player.PlayerActivity
import com.idanplusil.tv.ui.theme.IdanPlusTheme

class ChannelsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as IdanPlusApplication).container

        setContent {
            IdanPlusTheme {
                val vm: ChannelsViewModel = viewModel(
                    factory = ChannelsViewModel.Factory(container.channelRepository)
                )
                val state by vm.state.collectAsStateWithLifecycle()

                ChannelsScreen(
                    state = state,
                    onChannelClick = { channel ->
                        startActivity(PlayerActivity.intent(this, channel.id))
                    },
                    onRetry = vm::refresh,
                )
            }
        }
    }
}
