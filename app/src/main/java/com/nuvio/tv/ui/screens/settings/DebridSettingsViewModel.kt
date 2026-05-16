package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.DebridService
import com.nuvio.tv.data.local.DebridSettings
import com.nuvio.tv.data.local.DebridSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebridSettingsViewModel @Inject constructor(
    private val dataStore: DebridSettingsDataStore
) : ViewModel() {

    val uiState = dataStore.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        DebridSettings()
    )

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { dataStore.setEnabled(enabled) }
    }

    fun setServiceEnabled(service: DebridService, enabled: Boolean) {
        viewModelScope.launch { dataStore.setServiceEnabled(service, enabled) }
    }

    fun setTorboxApiKey(key: String) {
        viewModelScope.launch { dataStore.setTorboxApiKey(key) }
    }

    fun setRealDebridApiKey(key: String) {
        viewModelScope.launch { dataStore.setRealDebridApiKey(key) }
    }

    fun setAllDebridApiKey(key: String) {
        viewModelScope.launch { dataStore.setAllDebridApiKey(key) }
    }
}
