package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.XRaySettingsDataStore
import com.nuvio.tv.data.remote.api.OpenRouterApi
import com.nuvio.tv.domain.model.XRaySettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class XRaySettingsViewModel @Inject constructor(
    private val settingsDataStore: XRaySettingsDataStore,
    private val openRouterApi: OpenRouterApi
) : ViewModel() {

    val uiState: StateFlow<XRaySettings> = settingsDataStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), XRaySettings())

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    private val _validationError = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val validationError = _validationError.asSharedFlow()

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setEnabled(enabled) }
    }

    fun validateAndSaveApiKey(value: String, onSuccess: () -> Unit) {
        val trimmed = value.trim()
        viewModelScope.launch {
            if (trimmed.isBlank()) {
                settingsDataStore.setApiKey("")
                onSuccess()
                return@launch
            }
            _validating.value = true
            try {
                val response = runCatching { openRouterApi.getKeyInfo("Bearer $trimmed") }.getOrNull()
                if (response?.isSuccessful == true) {
                    settingsDataStore.setApiKey(trimmed)
                    onSuccess()
                } else {
                    _validationError.tryEmit(Unit)
                }
            } finally {
                _validating.value = false
            }
        }
    }

    fun saveModel(value: String, onDone: () -> Unit) {
        viewModelScope.launch {
            settingsDataStore.setModel(value)
            onDone()
        }
    }
}
