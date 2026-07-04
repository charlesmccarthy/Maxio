package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Feature name is also the profile DataStore file name and the key in the cloud
// settings blob — it must match the entry added to ProfileSettingsSyncService so
// recent searches sync across clients on the same account/profile.
private const val FEATURE = "recent_searches"
private const val MAX_RECENT_SEARCHES = 15
private const val MIN_QUERY_LENGTH = 2

@Singleton
class RecentSearchesDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    moshi: Moshi
) {
    private val recentSearchesKey = stringPreferencesKey("recent_searches_json")
    private val listType = Types.newParameterizedType(List::class.java, String::class.java)
    private val adapter = moshi.adapter<List<String>>(listType)

    val recentSearches: Flow<List<String>> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            factory.get(profileId, FEATURE).data.map { prefs ->
                parse(prefs[recentSearchesKey])
            }
        }

    /** Adds [query] to the front of the list, de-duplicated (case-insensitive) and capped. */
    suspend fun add(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) return
        val store = factory.get(profileManager.activeProfileId.value, FEATURE)
        store.edit { prefs ->
            val current = parse(prefs[recentSearchesKey])
            val deduped = current.filterNot { it.equals(trimmed, ignoreCase = true) }
            prefs[recentSearchesKey] = adapter.toJson((listOf(trimmed) + deduped).take(MAX_RECENT_SEARCHES))
        }
    }

    suspend fun remove(query: String) {
        val store = factory.get(profileManager.activeProfileId.value, FEATURE)
        store.edit { prefs ->
            val current = parse(prefs[recentSearchesKey])
            prefs[recentSearchesKey] = adapter.toJson(current.filterNot { it.equals(query, ignoreCase = true) })
        }
    }

    suspend fun clear() {
        val store = factory.get(profileManager.activeProfileId.value, FEATURE)
        store.edit { prefs -> prefs.remove(recentSearchesKey) }
    }

    private fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { adapter.fromJson(raw).orEmpty() }.getOrDefault(emptyList())
    }
}
