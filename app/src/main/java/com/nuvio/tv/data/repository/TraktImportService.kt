package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.sync.LibrarySyncService
import com.nuvio.tv.core.sync.WatchProgressSyncService
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time migration that copies a user's Trakt state — watchlist, in-progress items,
 * next-up seeds and watched movies — into the local stores, then flips the sync source to
 * Maxio Cloud (Supabase) and pushes everything up so it lands on every linked device.
 *
 * Reads reuse the existing Trakt services/flows so imported data is enriched and mapped
 * identically to what the user currently sees in Continue Watching / Library. Writes go
 * straight to the local preference stores (never through the repositories) so they can't be
 * re-routed back to Trakt while Trakt is still the active source during the import.
 */
@Singleton
class TraktImportService @Inject constructor(
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val traktAuthService: TraktAuthService,
    private val traktApi: TraktApi,
    private val traktLibraryService: TraktLibraryService,
    private val traktProgressService: TraktProgressService,
    private val libraryPreferences: LibraryPreferences,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val librarySyncService: LibrarySyncService,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val watchedItemsSyncService: WatchedItemsSyncService
) {
    data class ImportSummary(
        val libraryCount: Int,
        val continueWatchingCount: Int,
        val watchedMoviesCount: Int
    )

    suspend fun importFromTrakt(): Result<ImportSummary> = withContext(Dispatchers.IO) {
        try {
            if (!traktAuthDataStore.isEffectivelyAuthenticated.first()) {
                return@withContext Result.failure(
                    IllegalStateException("Connect Trakt on this profile before importing.")
                )
            }

            val libraryCount = importLibrary()
            val continueWatchingCount = importContinueWatching()
            val watchedMoviesCount = importWatchedMovies()

            // Make Maxio Cloud the active source so the pushes below (and all future writes)
            // sync to Supabase instead of being suppressed while Trakt is connected.
            traktSettingsDataStore.setWatchProgressSource(WatchProgressSource.NUVIO_SYNC)

            librarySyncService.pushToRemote()
            watchProgressSyncService.pushToRemote()
            watchedItemsSyncService.pushToRemote()

            Log.d(
                TAG,
                "Trakt import complete: library=$libraryCount continueWatching=$continueWatchingCount watchedMovies=$watchedMoviesCount"
            )
            Result.success(ImportSummary(libraryCount, continueWatchingCount, watchedMoviesCount))
        } catch (e: Exception) {
            Log.e(TAG, "Trakt import failed", e)
            Result.failure(e)
        }
    }

    private suspend fun importLibrary(): Int {
        traktLibraryService.refreshNow()
        val entries = withTimeoutOrNull(FLOW_TIMEOUT_MS) {
            traktLibraryService.observeAllItems().first { it.isNotEmpty() }
        } ?: emptyList()

        entries.forEach { entry ->
            libraryPreferences.addItem(
                SavedLibraryItem(
                    id = entry.id,
                    type = entry.type,
                    name = entry.name,
                    poster = entry.poster,
                    posterShape = entry.posterShape,
                    background = entry.background,
                    description = entry.description,
                    releaseInfo = entry.releaseInfo,
                    imdbRating = entry.imdbRating,
                    genres = entry.genres,
                    addonBaseUrl = entry.addonBaseUrl,
                    addedAt = entry.listedAt
                )
            )
        }
        return entries.size
    }

    private suspend fun importContinueWatching(): Int {
        traktProgressService.refreshNow()
        val inProgress = withTimeoutOrNull(FLOW_TIMEOUT_MS) {
            traktProgressService.observeAllProgress().first()
        } ?: emptyList()
        val seeds = withTimeoutOrNull(SEED_TIMEOUT_MS) {
            traktProgressService.observeWatchedShowSeeds().first()
        } ?: emptyList()

        val merged = linkedMapOf<String, WatchProgress>()
        (inProgress + seeds).forEach { progress ->
            val key = "${progress.contentId}|${progress.season ?: -1}|${progress.episode ?: -1}"
            merged[key] = progress
        }

        // Trakt seeds and completed markers use position/duration of 1ms, which the Supabase
        // sync filter treats as an empty marker and drops. Collapse those to 0 so the row
        // survives the push (progressPercent still carries the real completion state).
        val toStore = merged.values.map { progress ->
            val normalized = if (progress.duration <= 1L) {
                progress.copy(position = 0L, duration = 0L)
            } else {
                progress
            }
            normalized.copy(source = WatchProgress.SOURCE_LOCAL)
        }

        if (toStore.isNotEmpty()) {
            watchProgressPreferences.saveProgressBatch(toStore)
        }
        return toStore.size
    }

    private suspend fun importWatchedMovies(): Int {
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getWatched(authorization = authHeader, type = "movies")
        }
        val watched = response?.takeIf { it.isSuccessful }?.body().orEmpty()

        var count = 0
        watched.forEach { item ->
            val movie = item.movie ?: return@forEach
            val contentId = normalizeContentId(movie.ids)
            if (contentId.isBlank()) return@forEach
            watchedItemsPreferences.markAsWatched(
                WatchedItem(
                    contentId = contentId,
                    contentType = "movie",
                    title = movie.title ?: contentId,
                    season = null,
                    episode = null,
                    watchedAt = parseIsoToMillis(item.lastWatchedAt)
                )
            )
            count++
        }
        return count
    }

    companion object {
        private const val TAG = "TraktImportService"
        private const val FLOW_TIMEOUT_MS = 20_000L
        private const val SEED_TIMEOUT_MS = 15_000L
    }
}
