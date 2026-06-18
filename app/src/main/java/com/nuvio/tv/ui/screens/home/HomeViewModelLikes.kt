package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.core.util.isUnreleased
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val LIKED_HOME_ADDON_ID = "nuvio.likes"
private const val LIKED_HOME_ADDON_NAME = "Nuvio"
// Liked titles aggregated into the combined "More Like ... You Liked" row and
// used as the rotating pool for the per-title rows.
private const val LIKED_SEEDS_PER_TYPE = 8
// How many per-title "Because you liked X" rows to feature per type each visit.
private const val FEATURED_LIKED_ROWS_PER_TYPE = 4
// How many recommendations to keep in each per-title featured row.
private const val RECS_PER_LIKED_ROW = 20
// How many recommendations to keep in the combined row.
private const val COMBINED_ROW_MAX = 25
// How many recommendations to request from TMDB per seed.
private const val MORE_LIKE_THIS_FETCH = 15

private val LIKED_YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")

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

/**
 * Advances the rotation so a different set of liked titles is featured, then
 * rebuilds. Called when the home screen resumes so the rows feel fresh on each
 * visit. No-op when there are no liked items.
 */
internal fun HomeViewModel.rotateLikedRecommendationRowsPipeline() {
    if (likedItems.isEmpty()) return
    likedRotationToken += 1
    refreshLikedRecommendationRowsPipeline()
}

private suspend fun HomeViewModel.buildLikedRecommendationRowsPipeline(): List<CatalogRow> {
    val settings = currentTmdbSettings
    if (!settings.enabled || !settings.useMoreLikeThis || likedItems.isEmpty()) {
        return emptyList()
    }

    val hideUnreleased = _uiState.value.hideUnreleasedContent
    val today = LocalDate.now()
    val rotationToken = likedRotationToken
    val groups = likedItems.groupBy { likedItemContentType(it) }

    val movie = buildLikedRowsForType(
        contentType = ContentType.MOVIE,
        pool = groups[ContentType.MOVIE].orEmpty(),
        rotationToken = rotationToken,
        settings = settings,
        hideUnreleased = hideUnreleased,
        today = today
    )
    val show = buildLikedRowsForType(
        contentType = ContentType.SERIES,
        pool = groups[ContentType.SERIES].orEmpty(),
        rotationToken = rotationToken,
        settings = settings,
        hideUnreleased = hideUnreleased,
        today = today
    )

    return buildList {
        // Broad "More Like Movies/Shows You Liked" rows first, then the
        // per-title "Because you liked X" rows interleaved by type.
        movie.combined?.let(::add)
        show.combined?.let(::add)
        addAll(interleaveRows(movie.perTitle, show.perTitle))
    }
}

private data class LikedRowsForType(
    val combined: CatalogRow?,
    val perTitle: List<CatalogRow>
)

/**
 * Builds the combined "More Like ... You Liked" row plus up to
 * [FEATURED_LIKED_ROWS_PER_TYPE] per-title "Because you liked <Title>" rows for
 * one content type. Seeds are rotated by the session token so different liked
 * titles get featured across visits; recommendations come back era-ranked
 * (same-decade first) from [fetchMoreLikeThis].
 */
private suspend fun HomeViewModel.buildLikedRowsForType(
    contentType: ContentType,
    pool: List<MetaPreview>,
    rotationToken: Int,
    settings: TmdbSettings,
    hideUnreleased: Boolean,
    today: LocalDate
): LikedRowsForType {
    if (pool.isEmpty()) return LikedRowsForType(null, emptyList())

    // Stable order so rotation is well-defined, then rotate by the session token.
    val ordered = pool.sortedBy { it.id }
    val offset = positiveModulo(rotationToken, ordered.size)
    val rotated = ordered.drop(offset) + ordered.take(offset)
    val seeds = rotated.take(LIKED_SEEDS_PER_TYPE)

    val likedKeys = pool.map(::likedItemStatusKey).toHashSet()

    // Fetch each seed's recommendations once (cached) and reuse for both the
    // combined row and the per-title rows. Era-ranked + de-liked + released.
    val seedRecs = LinkedHashMap<MetaPreview, List<MetaPreview>>()
    seeds.forEach { seed ->
        val tmdbId = runCatching { tmdbService.ensureTmdbId(seed.id, seed.apiType) }.getOrNull()
            ?: return@forEach
        val recommendations = runCatching {
            tmdbMetadataService.fetchMoreLikeThis(
                tmdbId = tmdbId,
                contentType = contentType,
                language = settings.language,
                maxItems = MORE_LIKE_THIS_FETCH,
                seedYear = extractLikedYear(seed.releaseInfo)
            )
        }.getOrDefault(emptyList())
        val filtered = recommendations.filter { rec ->
            val key = likedItemStatusKey(rec)
            key !in likedKeys && !(hideUnreleased && rec.isUnreleased(today))
        }
        if (filtered.isNotEmpty()) seedRecs[seed] = filtered
    }

    if (seedRecs.isEmpty()) return LikedRowsForType(null, emptyList())

    // Combined row — round-robin across seeds for diversity, then dedupe.
    val combinedItems = roundRobinMerge(seedRecs.values.toList())
        .distinctBy(::likedItemStatusKey)
        .take(COMBINED_ROW_MAX)
    val combined = if (combinedItems.isNotEmpty()) {
        val title = when (contentType) {
            ContentType.MOVIE -> "More Like Movies You Liked"
            else -> "More Like Shows You Liked"
        }
        CatalogRow(
            addonId = LIKED_HOME_ADDON_ID,
            addonName = LIKED_HOME_ADDON_NAME,
            addonBaseUrl = "",
            catalogId = "liked_more_like_${contentType.name.lowercase()}",
            catalogName = title,
            type = contentType,
            items = combinedItems,
            hasMore = false,
            supportsSkip = false,
            skipStep = COMBINED_ROW_MAX
        )
    } else null

    // Per-title rows — top featured seeds, each its own "Because you liked X".
    val perTitle = seedRecs.entries.take(FEATURED_LIKED_ROWS_PER_TYPE).map { (seed, recs) ->
        CatalogRow(
            addonId = LIKED_HOME_ADDON_ID,
            addonName = LIKED_HOME_ADDON_NAME,
            addonBaseUrl = "",
            catalogId = "liked_because_${contentType.name.lowercase()}_${seed.id}",
            catalogName = "Because you liked ${seed.name}",
            type = contentType,
            items = recs.take(RECS_PER_LIKED_ROW),
            hasMore = false,
            supportsSkip = false,
            skipStep = RECS_PER_LIKED_ROW
        )
    }

    return LikedRowsForType(combined, perTitle)
}

private fun extractLikedYear(releaseInfo: String?): Int? {
    if (releaseInfo.isNullOrBlank()) return null
    return LIKED_YEAR_REGEX.find(releaseInfo)?.value?.toIntOrNull()
}

private fun roundRobinMerge(lists: List<List<MetaPreview>>): List<MetaPreview> {
    val result = mutableListOf<MetaPreview>()
    val maxSize = lists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until maxSize) {
        lists.forEach { list -> list.getOrNull(i)?.let(result::add) }
    }
    return result
}

private fun interleaveRows(a: List<CatalogRow>, b: List<CatalogRow>): List<CatalogRow> {
    val result = mutableListOf<CatalogRow>()
    val maxSize = maxOf(a.size, b.size)
    for (i in 0 until maxSize) {
        a.getOrNull(i)?.let(result::add)
        b.getOrNull(i)?.let(result::add)
    }
    return result
}

private fun positiveModulo(value: Int, modulus: Int): Int {
    if (modulus <= 0) return 0
    val remainder = value % modulus
    return if (remainder < 0) remainder + modulus else remainder
}

private fun likedItemContentType(item: MetaPreview): ContentType {
    return when (item.apiType.lowercase()) {
        "movie" -> ContentType.MOVIE
        "series", "tv" -> ContentType.SERIES
        else -> ContentType.UNKNOWN
    }
}

private fun likedItemStatusKey(item: MetaPreview): String = "${item.apiType}:${item.id}"
