package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val LIBRARY_HOME_ADDON_ID = "nuvio.library"
private const val LIBRARY_HOME_ADDON_NAME = "Library"
// Cap each "Recently Added" row so a large library doesn't build an enormous row.
private const val LIBRARY_ROW_MAX = 40

/**
 * Builds two home rows from the user's Library — movies and shows, each ordered
 * by most-recently-added — and re-merges them into the home rows whenever the
 * Library changes. Mirrors the liked-recommendation rows pipeline.
 */
internal fun HomeViewModel.observeLibraryRecentlyAddedRows() {
    libraryRecentlyAddedJob?.cancel()
    libraryRecentlyAddedJob = viewModelScope.launch {
        libraryRepository.libraryItems.collectLatest { entries ->
            val rows = buildLibraryRecentlyAddedRows(entries)
            val changed = libraryRecentlyAddedRows != rows
            libraryRecentlyAddedRows = rows
            if (changed) {
                scheduleUpdateCatalogRows()
            }
        }
    }
}

private fun buildLibraryRecentlyAddedRows(entries: List<LibraryEntry>): List<CatalogRow> {
    if (entries.isEmpty()) return emptyList()

    fun rowFor(type: ContentType, apiType: String, title: String, catalogId: String): CatalogRow? {
        val items = entries
            .filter { it.type.equals(apiType, ignoreCase = true) }
            .sortedByDescending { it.listedAt }
            .take(LIBRARY_ROW_MAX)
            .map { it.toMetaPreview() }
        if (items.isEmpty()) return null
        return CatalogRow(
            addonId = LIBRARY_HOME_ADDON_ID,
            addonName = LIBRARY_HOME_ADDON_NAME,
            addonBaseUrl = "",
            catalogId = catalogId,
            catalogName = title,
            type = type,
            items = items,
            hasMore = false,
            supportsSkip = false,
            skipStep = LIBRARY_ROW_MAX
        )
    }

    return listOfNotNull(
        rowFor(ContentType.MOVIE, "movie", "Recently Added Movies", "library_recently_added_movies"),
        rowFor(ContentType.SERIES, "series", "Recently Added Shows", "library_recently_added_series")
    )
}
