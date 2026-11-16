package com.nuvio.tv.core.sync

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.local.LikedEntry
import com.nuvio.tv.data.local.LikedMediaDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the active profile's liked list to MovieGoated so likes feed its recommendations.
 *
 * Only runs when a moviegoated.com addon is installed on the profile; the secret key in that
 * addon URL identifies the account. The whole list is sent each time (it is small), and
 * nothing in the app depends on the call succeeding: failures are silent and the next change
 * or app start tries again.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MovieGoatedLikesSyncService @Inject constructor(
    private val likedMediaDataStore: LikedMediaDataStore,
    private val addonPreferences: AddonPreferences,
    private val profileManager: ProfileManager,
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "MovieGoatedLikes"
        const val HOST = "moviegoated.com"
        const val PREFS = "moviegoated_likes_sync"
        const val DEBOUNCE_MS = 2_000L
        const val MAX_ITEMS = 300
        // An empty list wipes every Maxio like on the server, so only send one when the
        // list was already nearly empty (i.e. the user plausibly unliked the last items).
        const val MAX_COUNT_BEFORE_EMPTY_SEND = 3
    }

    private data class Snapshot(val profileId: Int, val key: String?, val entries: List<LikedEntry>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    init {
        scope.launch {
            profileManager.activeProfileId
                .flatMapLatest { profileId ->
                    combine(
                        likedMediaDataStore.likedEntries(profileId),
                        addonPreferences.installedAddonUrls
                    ) { entries, urls -> Snapshot(profileId, extractKey(urls), entries) }
                }
                .debounce(DEBOUNCE_MS)
                .collect { snapshot ->
                    try {
                        sync(snapshot)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Likes sync failed: ${e.message}")
                    }
                }
        }
    }

    private fun extractKey(addonUrls: List<String>): String? =
        addonUrls.firstNotNullOfOrNull { raw ->
            val url = raw.trim().toHttpUrlOrNull() ?: return@firstNotNullOfOrNull null
            val segments = url.pathSegments
            if (url.host.equals(HOST, ignoreCase = true) &&
                segments.size >= 3 && segments[0] == "api" && segments[1] == "addon"
            ) {
                segments[2].takeIf { it.isNotBlank() }
            } else {
                null
            }
        }

    private fun normalizeType(rawType: String): String = when (val type = rawType.trim().lowercase()) {
        "tv", "show", "shows" -> "series"
        "movies", "film" -> "movie"
        else -> type
    }

    private fun sync(snapshot: Snapshot) {
        val key = snapshot.key ?: return
        val items = snapshot.entries.take(MAX_ITEMS)
        val p = "p${snapshot.profileId}_"

        // "changedAt" is this device's own clock for when the list last changed, bumped on
        // likes and unlikes alike (including ones that arrived through a cloud pull).
        val listSignature = items.map { "${it.id}|${normalizeType(it.type)}" }.sorted().joinToString(",").let {
            "${items.size}:${it.hashCode()}"
        }
        val seenSignature = prefs.getString(p + "seen_signature", null)
        var changedAt = prefs.getLong(p + "changed_at", 0L)
        if (seenSignature == null) {
            // First run on this device: an empty list may just mean nothing has synced down
            // yet, and a stale list must not outrank a newer one from another device.
            if (items.isEmpty()) return
            changedAt = items.maxOf { it.likedAt }
        } else if (seenSignature != listSignature) {
            changedAt = System.currentTimeMillis()
        }
        if (seenSignature != listSignature) {
            prefs.edit()
                .putString(p + "seen_signature", listSignature)
                .putLong(p + "changed_at", changedAt)
                .apply()
        }

        val keyTag = key.hashCode().toString()
        val sendSignature = "$keyTag|$listSignature|$changedAt"
        if (sendSignature == prefs.getString(p + "sent_signature", null)) return
        if (keyTag == prefs.getString(p + "invalid_key", null)) return
        if (items.isEmpty()) {
            val lastSentCount = prefs.getInt(p + "sent_count", -1)
            if (lastSentCount !in 0..MAX_COUNT_BEFORE_EMPTY_SEND) {
                Log.d(TAG, "Not sending an empty list (last sent count=$lastSentCount)")
                return
            }
        }

        val body = JSONObject()
            .put("changedAt", changedAt)
            .put("items", JSONArray().apply {
                items.forEach { entry ->
                    put(
                        JSONObject()
                            .put("id", entry.id)
                            .put("type", normalizeType(entry.type))
                            .apply { entry.imdbId?.takeIf { it.isNotBlank() }?.let { put("imdbId", it) } }
                            .put("likedAt", entry.likedAt)
                    )
                }
            })
            .toString()

        val request = Request.Builder()
            .url("https://$HOST/api/addon/$key/likes")
            .put(body.toRequestBody(jsonMediaType))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty().take(500)
            when (response.code) {
                200 -> {
                    Log.d(TAG, "Sent ${items.size} likes (changedAt=$changedAt): $responseBody")
                    prefs.edit()
                        .putString(p + "sent_signature", sendSignature)
                        .putInt(p + "sent_count", items.size)
                        .remove(p + "invalid_key")
                        .apply()
                }
                404 -> {
                    // Addon address is no longer valid; stay quiet until the URL changes.
                    Log.w(TAG, "Likes endpoint returned 404 for the installed addon key")
                    prefs.edit().putString(p + "invalid_key", keyTag).apply()
                }
                else -> Log.w(TAG, "Likes sync got HTTP ${response.code}: $responseBody")
            }
        }
    }
}
