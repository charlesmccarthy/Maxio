package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.XRaySettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class XRaySettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "xray_settings"
        const val DEFAULT_MODEL = "google/gemini-2.5-flash"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val enabledKey = booleanPreferencesKey("xray_enabled")
    private val apiKeyKey = stringPreferencesKey("xray_openrouter_api_key")
    private val modelKey = stringPreferencesKey("xray_model")

    val settings: Flow<XRaySettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            XRaySettings(
                enabled = prefs[enabledKey] ?: true,
                apiKey = prefs[apiKeyKey] ?: "",
                model = (prefs[modelKey] ?: "").ifBlank { DEFAULT_MODEL }
            )
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { prefs -> prefs[enabledKey] = enabled }
    }

    suspend fun setApiKey(apiKey: String) {
        store().edit { prefs -> prefs[apiKeyKey] = apiKey.trim() }
    }

    suspend fun setModel(model: String) {
        store().edit { prefs -> prefs[modelKey] = model.trim() }
    }
}
