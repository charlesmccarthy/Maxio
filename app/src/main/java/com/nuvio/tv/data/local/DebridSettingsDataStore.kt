package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class DebridSettings(
    val enabled: Boolean = false,
    val torboxEnabled: Boolean = false,
    val realDebridEnabled: Boolean = false,
    val allDebridEnabled: Boolean = false,
    val torboxApiKey: String = "",
    val realDebridApiKey: String = "",
    val allDebridApiKey: String = ""
) {
    /** Services that are toggled on AND have a non-blank key. */
    fun activeServices(): List<DebridService> = buildList {
        if (torboxEnabled && torboxApiKey.isNotBlank()) add(DebridService.TORBOX)
        if (realDebridEnabled && realDebridApiKey.isNotBlank()) add(DebridService.REAL_DEBRID)
        if (allDebridEnabled && allDebridApiKey.isNotBlank()) add(DebridService.ALL_DEBRID)
    }

    fun keyFor(service: DebridService): String = when (service) {
        DebridService.TORBOX -> torboxApiKey
        DebridService.REAL_DEBRID -> realDebridApiKey
        DebridService.ALL_DEBRID -> allDebridApiKey
    }
}

enum class DebridService(val displayName: String) {
    TORBOX("Torbox"),
    REAL_DEBRID("Real-Debrid"),
    ALL_DEBRID("AllDebrid")
}

@Singleton
class DebridSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "debrid_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val enabledKey = booleanPreferencesKey("debrid_enabled")
    private val torboxEnabledKey = booleanPreferencesKey("debrid_torbox_enabled")
    private val realDebridEnabledKey = booleanPreferencesKey("debrid_real_debrid_enabled")
    private val allDebridEnabledKey = booleanPreferencesKey("debrid_all_debrid_enabled")
    private val torboxApiKeyKey = stringPreferencesKey("debrid_torbox_api_key")
    private val realDebridApiKeyKey = stringPreferencesKey("debrid_real_debrid_api_key")
    private val allDebridApiKeyKey = stringPreferencesKey("debrid_all_debrid_api_key")

    val settings: Flow<DebridSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            DebridSettings(
                enabled = prefs[enabledKey] ?: false,
                torboxEnabled = prefs[torboxEnabledKey] ?: false,
                realDebridEnabled = prefs[realDebridEnabledKey] ?: false,
                allDebridEnabled = prefs[allDebridEnabledKey] ?: false,
                torboxApiKey = prefs[torboxApiKeyKey] ?: "",
                realDebridApiKey = prefs[realDebridApiKeyKey] ?: "",
                allDebridApiKey = prefs[allDebridApiKeyKey] ?: ""
            )
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setServiceEnabled(service: DebridService, enabled: Boolean) {
        val key = when (service) {
            DebridService.TORBOX -> torboxEnabledKey
            DebridService.REAL_DEBRID -> realDebridEnabledKey
            DebridService.ALL_DEBRID -> allDebridEnabledKey
        }
        store().edit { it[key] = enabled }
    }

    suspend fun setTorboxApiKey(key: String) {
        store().edit { it[torboxApiKeyKey] = key.trim() }
    }

    suspend fun setRealDebridApiKey(key: String) {
        store().edit { it[realDebridApiKeyKey] = key.trim() }
    }

    suspend fun setAllDebridApiKey(key: String) {
        store().edit { it[allDebridApiKeyKey] = key.trim() }
    }
}
