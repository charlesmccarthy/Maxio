package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.core.util.isUnreleased
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.random.Random

private const val LIKED_HOME_ADDON_ID = "nuvio.likes"
private const val LIKED_HOME_ADDON_NAME = "Nuvio"
private const val MAX_LIKED_SEEDS_PER_TYPE = 5
private const val MAX_LIKED_RECOMMENDATIONS_PER_ROW = 25

internal fun HomeViewModel.observeLikedItemsPipeline() {
    viewModelScope.launch {
        likedMediaDataStore.likedItems.collectLatest { items ->
            likedItems = items
            likedItemStatusState.clear()
            items.forEach { item ->
                likedItemStatusState[likedItemStatusKey(item)] = true
            }
            refreshLikedRecommendationRowsPipeline()
        }
    }
}

fun HomeViewModel.toggleLikedItem(item: MetaPreview) {
    viewModelScope.launch {
        likedMediaDataStore.toggle(item)
    }
}

internal fun HomeViewModel.refreshLikedRecommendationRowsPipeline() {
    likedRecommendationJob?.cancel()
    likedRecommendationJob = viewModelScope.launch {
        val rows = buildLikedRecommendationRowsPipeline()
        val changed = likedRecommendationRows != rows
        likedRecommendationRows = rows
        if (changed) {
            scheduleUpdateCatalogRows()
        }
    }
}

private suspend fun HomeViewModel.buildLikedRecommendationRowsPipeline(): List<CatalogRow> {
    val settings = currentTmdbSettings
    if (!settings.enabled || !settings.useMoreLikeThis || likedItems.isEmpty()) {
        return emptyList()
    }

    val hideUnreleased = _uiState.value.hideUnreleasedContent
    val today = LocalDate.now()
    val groups = likedItems.groupBy { likedItemContentType(it) }

    return buildList {
        listOf(ContentType.MOVIE, ContentType.SERIES).forEach { contentType ->
            val sourceItems = groups[contentType].orEmpty()
            if (sourceItems.isEmpty()) return@forEach

            val seedSource = sourceItems.joinToString("|") { it.id }
            val seedItems = sourceItems
                .shuffled(Random(seedSource.hashCode()))
                .take(MAX_LIKED_SEEDS_PER_TYPE)

            val sourceKeys = sourceItems.map(::likedItemStatusKey).toHashSet()
            val recommendations = linkedMapOf<String, MetaPreview>()

            seedItems.forEach { item ->
                val tmdbId = runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
                    ?: return@forEach
                val moreLikeThis = runCatching {
                    tmdbMetadataService.fetchMoreLikeThis(
                        tmdbId = tmdbId,
                        contentType = contentType,
                        language = settings.language,
                        maxItems = 12
                    )
                }.getOrDefault(emptyList())

                moreLikeThis.forEach { recommendation ->
                    val key = likedItemStatusKey(recommendation)
                    if (key in sourceKeys) return@forEach
                    if (hideUnreleased && recommendation.isUnreleased(today)) return@forEach
                    recommendations.putIfAbsent(key, recommendation)
                }
            }

            val items = recommendations.values.take(MAX_LIKED_RECOMMENDATIONS_PER_ROW)
            if (items.isNotEmpty()) {
                val title = when (contentType) {
                    ContentType.MOVIE -> "More Like Your Liked Movies"
                    else -> "More Like Your Liked Shows"
                }
                add(
                    CatalogRow(
                        addonId = LIKED_HOME_ADDON_ID,
                        addonName = LIKED_HOME_ADDON_NAME,
                        addonBaseUrl = "",
                        catalogId = "liked_more_like_${contentType.name.lowercase()}",
                        catalogName = title,
                        type = contentType,
                        items = items,
                        hasMore = false,
                        supportsSkip = false,
                        skipStep = MAX_LIKED_RECOMMENDATIONS_PER_ROW
                    )
                )
            }
        }
    }
}

private fun likedItemContentType(item: MetaPreview): ContentType {
    return when (item.apiType.lowercase()) {
        "movie" -> ContentType.MOVIE
        "series", "tv" -> ContentType.SERIES
        else -> ContentType.UNKNOWN
    }
}

private fun likedItemStatusKey(item: MetaPreview): String = "${item.apiType}:${item.id}"
