package com.idanplusil.tv.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.idanplusil.tv.data.config.ChannelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChannelsViewModel(private val repository: ChannelRepository) : ViewModel() {

    private val _state = MutableStateFlow<ChannelsUiState>(ChannelsUiState.Loading)
    val state: StateFlow<ChannelsUiState> = _state.asStateFlow()

    init {
        // Render immediately from the disk cache or the bundled copy, then
        // refresh in the background. The network is never on the path to the
        // first frame.
        emit(repository.localSnapshot())
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { emit(repository.refresh()) }
    }

    private fun emit(snapshot: com.idanplusil.tv.data.config.CatalogSnapshot) {
        _state.value = when {
            snapshot.channels.isNotEmpty() -> ChannelsUiState.Content(snapshot.channels, stale = snapshot.error != null)
            snapshot.error != null -> ChannelsUiState.Error(snapshot.error)
            else -> ChannelsUiState.Empty
        }
    }

    class Factory(private val repository: ChannelRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChannelsViewModel(repository) as T
    }
}
