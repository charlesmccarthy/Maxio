package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.AddonSyncService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

class AddonRepositoryImpl @Inject constructor(
    private val api: AddonApi,
    private val preferences: AddonPreferences,
    private val addonSyncService: AddonSyncService,
    private val authManager: AuthManager,
    private val profileManager: ProfileManager,
    @ApplicationContext private val context: Context
) : AddonRepository {

    companion object {
        private const val TAG = "AddonRepository"
        private const val MANIFEST_CACHE_PREFS = "addon_manifest_cache"
        private const val MANIFEST_CACHE_KEY = "manifests_v2"
        private const val LEGACY_MANIFEST_CACHE_KEY = "manifests"
        private const val MANIFEST_SUFFIX = "/manifest.json"
        private const val MANIFEST_CACHE_TTL_MS = 6 * 60 * 60 * 1000L 
        private const val SYNC_STATE_PREFS = "addon_sync_state"
        private const val PENDING_PUSH_KEY_PREFIX = "pending_push_p"
    }

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    var isSyncingFromRemote = false

    private fun canonicalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return if (trimmed.endsWith(MANIFEST_SUFFIX, ignoreCase = true)) {
            trimmed.dropLast(MANIFEST_SUFFIX.length).trimEnd('/')
        } else {
            trimmed
        }
    }

    private fun normalizeUrl(url: String): String = canonicalizeUrl(url).lowercase()

    // Local addon edits that haven't reached the server yet. A remote pull treats the
    // server list as the source of truth and drops anything missing from it, so while an
    // edit is still unpushed (debounce window, failed push, app killed) the pull has to
    // push first instead of reconciling, otherwise a freshly installed addon disappears.
    private val localEditMutex = Mutex()
    private val localEditSeq = AtomicLong(0)
    private val syncStatePrefs by lazy {
        context.getSharedPreferences(SYNC_STATE_PREFS, Context.MODE_PRIVATE)
    }

    private fun pendingPushKey(): String =
        PENDING_PUSH_KEY_PREFIX + profileManager.activeProfileId.value

    private fun hasPendingPush(): Boolean =
        syncStatePrefs.getBoolean(pendingPushKey(), false)

    private fun markPendingPush() {
        localEditSeq.incrementAndGet()
        syncStatePrefs.edit().putBoolean(pendingPushKey(), true).apply()
    }

    private suspend fun pushPendingEdits(): Boolean {
        val key = pendingPushKey()
        val seq = localEditSeq.get()
        val result = addonSyncService.pushToRemote()
        Log.d(TAG, "pushPendingEdits: result=${result.isSuccess} ${result.exceptionOrNull()?.message ?: ""}")
        if (result.isSuccess && localEditSeq.get() == seq) {
            syncStatePrefs.edit().remove(key).apply()
        }
        return result.isSuccess
    }

    private fun triggerRemoteSync() {
        if (!authManager.isAuthenticated) {
            Log.d(TAG, "triggerRemoteSync: skipped (not authenticated, state=${authManager.authState.value})")
            return
        }
        markPendingPush()
        Log.d(TAG, "triggerRemoteSync: scheduling push in 500ms")
        syncJob?.cancel()
        syncJob = syncScope.launch {
            delay(500)
            pushPendingEdits()
        }
    }

    private val gson = Gson()
    private val manifestCache = mutableMapOf<String, Addon>()
    @Volatile
    private var lastManifestRefreshTime = 0L
    private var manifestRefreshJob: Job? = null

    init {
        syncScope.launch { loadManifestCacheFromDisk() }
    }

    private fun isCacheStale(): Boolean =
        System.currentTimeMillis() - lastManifestRefreshTime > MANIFEST_CACHE_TTL_MS

    private fun scheduleManifestRefresh(urls: List<String>) {
        if (manifestRefreshJob?.isActive == true) return
        manifestRefreshJob = syncScope.launch {
            val refreshed = urls.map { url ->
                async {
                    fetchAddon(url)
                }
            }.awaitAll()
            val anyUpdated = refreshed.any { it is NetworkResult.Success }
            if (anyUpdated) {
                lastManifestRefreshTime = System.currentTimeMillis()
                Log.d(TAG, "Background manifest refresh completed")
            }
        }
    }

    private suspend fun loadManifestCacheFromDisk() = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences(MANIFEST_CACHE_PREFS, Context.MODE_PRIVATE)
            if (prefs.contains(LEGACY_MANIFEST_CACHE_KEY)) {
                prefs.edit().remove(LEGACY_MANIFEST_CACHE_KEY).apply()
            }
            val json = prefs.getString(MANIFEST_CACHE_KEY, null) ?: return@withContext
            val type = object : TypeToken<Map<String, Addon>>() {}.type
            val cached: Map<String, Addon> = gson.fromJson(json, type) ?: return@withContext
            manifestCache.putAll(cached)
            Log.d(TAG, "Loaded ${cached.size} cached manifests from disk")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load manifest cache from disk", e)
        }
    }

    private fun persistManifestCacheToDisk() {
        syncScope.launch {
            try {
                val snapshot = manifestCache.toMap()
                val prefs = context.getSharedPreferences(MANIFEST_CACHE_PREFS, Context.MODE_PRIVATE)
                prefs.edit().putString(MANIFEST_CACHE_KEY, gson.toJson(snapshot)).apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist manifest cache to disk", e)
            }
        }
    }

    override fun getInstalledAddons(): Flow<List<Addon>> =
        preferences.installedAddonUrls.flatMapLatest { urls ->
            flow {
                val cached = urls.mapNotNull { manifestCache[canonicalizeUrl(it)] }
                if (cached.isNotEmpty()) {
                    emit(applyDisplayNames(cached))
                }

                val hasCacheMiss = cached.size < urls.size
                if (hasCacheMiss) {
                    val fresh = coroutineScope {
                        urls.map { url ->
                            async {
                                val canonical = canonicalizeUrl(url)
                                manifestCache[canonical] ?: when (val result = fetchAddon(url)) {
                                    is NetworkResult.Success -> result.data
                                    else -> null
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }

                    if (fresh != cached) {
                        emit(applyDisplayNames(fresh))
                    }
                } else if (isCacheStale() && urls.isNotEmpty()) {
                    scheduleManifestRefresh(urls)
                }
            }.flowOn(Dispatchers.IO)
        }

    override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> {
        val cleanBaseUrl = canonicalizeUrl(baseUrl)
        val manifestUrl = "$cleanBaseUrl/manifest.json"

        return when (val result = safeApiCall { api.getManifest(manifestUrl) }) {
            is NetworkResult.Success -> {
                val addon = result.data.toDomain(cleanBaseUrl)
                manifestCache[cleanBaseUrl] = addon
                persistManifestCacheToDisk()
                NetworkResult.Success(addon)
            }
            is NetworkResult.Error -> {
                Log.w(TAG, "Failed to fetch addon manifest for url=$manifestUrl code=${result.code} message=${result.message}")
                result
            }
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    override suspend fun addAddon(url: String) {
        val cleanUrl = canonicalizeUrl(url)
        localEditMutex.withLock {
            preferences.addAddon(cleanUrl)
            triggerRemoteSync()
        }
    }

    override suspend fun removeAddon(url: String) {
        val cleanUrl = canonicalizeUrl(url)
        manifestCache.remove(cleanUrl)
        localEditMutex.withLock {
            preferences.removeAddon(cleanUrl)
            triggerRemoteSync()
        }
    }

    override suspend fun setAddonOrder(urls: List<String>) {
        localEditMutex.withLock {
            preferences.setAddonOrder(urls)
            triggerRemoteSync()
        }
    }

    suspend fun reconcileWithRemoteAddonUrls(
        remoteUrls: List<String>,
        removeMissingLocal: Boolean = true
    ) {
        if (hasPendingPush()) {
            Log.d(TAG, "reconcileWithRemoteAddonUrls: local edits not pushed yet, pushing instead of reconciling")
            pushPendingEdits()
            return
        }
        localEditMutex.withLock {
            // An edit may have landed while the remote list was being fetched.
            if (hasPendingPush()) return
            applyRemoteAddonUrls(remoteUrls, removeMissingLocal)
        }
    }

    private suspend fun applyRemoteAddonUrls(
        remoteUrls: List<String>,
        removeMissingLocal: Boolean
    ) {
        val normalizedRemote = remoteUrls
            .map { canonicalizeUrl(it) }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeUrl(it) }
        val remoteSet = normalizedRemote.map { normalizeUrl(it) }.toSet()

        val initialLocalUrls = preferences.installedAddonUrls.first()
        val initialLocalSet = initialLocalUrls.map { normalizeUrl(it) }.toSet()
        val shouldRemoveMissingLocal = if (removeMissingLocal && normalizedRemote.isEmpty() && initialLocalUrls.isNotEmpty()) {
            Log.w(
                TAG,
                "reconcileWithRemoteAddonUrls: remote list empty while local has ${initialLocalUrls.size} entries; preserving local addons"
            )
            false
        } else {
            removeMissingLocal
        }

     
        val localByNormalized = linkedMapOf<String, String>()
        initialLocalUrls.forEach { url ->
            localByNormalized.putIfAbsent(normalizeUrl(url), canonicalizeUrl(url))
        }

        val remoteOrdered = normalizedRemote.map { remote ->
            localByNormalized[normalizeUrl(remote)] ?: remote
        }

        val finalList = if (shouldRemoveMissingLocal) {
            remoteOrdered
        } else {
            val extras = initialLocalUrls
                .map { canonicalizeUrl(it) }
                .filter { normalizeUrl(it) !in remoteSet }
            remoteOrdered + extras
        }

        if (shouldRemoveMissingLocal) {
            initialLocalUrls
                .filter { normalizeUrl(it) !in remoteSet }
                .forEach { manifestCache.remove(canonicalizeUrl(it)) }
        }


        val currentCanonical = initialLocalUrls.map { canonicalizeUrl(it) }
        if (finalList != currentCanonical) {
            preferences.setAddonOrder(finalList)
        }
    }

    private fun applyDisplayNames(addons: List<Addon>): List<Addon> {
        val nameCounts = mutableMapOf<String, Int>()
        for (addon in addons) {
            nameCounts[addon.name] = (nameCounts[addon.name] ?: 0) + 1
        }

        val nameCounters = mutableMapOf<String, Int>()
        return addons.map { addon ->
            if ((nameCounts[addon.name] ?: 0) <= 1) {
                addon.copy(displayName = addon.name)
            } else {
                val occurrence = (nameCounters[addon.name] ?: 0) + 1
                nameCounters[addon.name] = occurrence
                if (occurrence == 1) {
                    addon.copy(displayName = addon.name)
                } else {
                    addon.copy(displayName = "${addon.name} ($occurrence)")
                }
            }
        }
    }
}
