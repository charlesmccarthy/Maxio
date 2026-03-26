package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.LocalContentFocusRequester
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.ContinueWatchingSection
import com.nuvio.tv.ui.components.HeroCarousel
import com.nuvio.tv.ui.components.NetflixStyleRow
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.util.formatAddonTypeLabel
import com.nuvio.tv.R
import androidx.compose.ui.res.stringResource

/** Minimum interval between processed key repeat events to prevent HWUI overload. */
private const val KEY_REPEAT_THROTTLE_MS = 80L

private class FocusSnapshot(
    var rowIndex: Int,
    var itemIndex: Int
)

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ClassicHomeContent(
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    focusState: HomeScreenFocusState,
    trailerPreviewUrls: Map<String, String>,
    trailerPreviewAudioUrls: Map<String, String>,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = {},
    showContinueWatchingManualPlayOption: Boolean = false,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean = { false },
    onCatalogItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    onRequestTrailerPreview: (MetaPreview) -> Unit,
    onItemFocus: (MetaPreview) -> Unit = {},
    onSaveFocusState: (Int, Int, Int, Int, Map<String, Int>) -> Unit
) {

    // Nested prefetch: when LazyColumn prefetches a row ahead of scrolling,
    // pre-compose up to 2 ContentCards in its nested LazyRow across multiple frames.
    // This spreads the composition work and prevents frame spikes when a new row scrolls in.
    val nestedPrefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 2) }

    val columnListState = rememberLazyListState(
        initialFirstVisibleItemIndex = focusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = focusState.verticalScrollOffset,
        prefetchStrategy = nestedPrefetchStrategy
    )

    LaunchedEffect(focusState.verticalScrollIndex, focusState.verticalScrollOffset) {
        val targetIndex = focusState.verticalScrollIndex
        val targetOffset = focusState.verticalScrollOffset
        if (columnListState.firstVisibleItemIndex == targetIndex &&
            columnListState.firstVisibleItemScrollOffset == targetOffset
        ) {
            return@LaunchedEffect
        }
        if (targetIndex > 0 || targetOffset > 0) {
            columnListState.scrollToItem(
                targetIndex,
                targetOffset
            )
        }
    }

    val currentFocusSnapshot = remember {
        FocusSnapshot(
            rowIndex = focusState.focusedRowIndex,
            itemIndex = focusState.focusedItemIndex
        )
    }

    // Store selected index per row for Netflix carousel state
    val rowSelectedIndices = remember { mutableMapOf<String, Int>() }
    val rowFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    var restoringFocus by remember { mutableStateOf(focusState.hasSavedFocus) }
    val heroFocusRequester = remember { FocusRequester() }
    val shouldRequestInitialFocus = remember(focusState) {
        !focusState.hasSavedFocus &&
            focusState.verticalScrollIndex == 0 &&
            focusState.verticalScrollOffset == 0
    }
    val visibleCatalogRows = remember(uiState.catalogRows) {
        uiState.catalogRows.filter { it.items.isNotEmpty() }
    }
    val visibleCatalogKeys = remember(visibleCatalogRows) {
        visibleCatalogRows.mapTo(mutableSetOf()) { "${it.addonId}_${it.apiType}_${it.catalogId}" }
    }

    LaunchedEffect(visibleCatalogKeys) {
        rowSelectedIndices.keys.retainAll(visibleCatalogKeys)
        rowFocusRequesters.keys.retainAll(visibleCatalogKeys)
    }

    DisposableEffect(Unit) {
        onDispose {
            onSaveFocusState(
                columnListState.firstVisibleItemIndex,
                columnListState.firstVisibleItemScrollOffset,
                currentFocusSnapshot.rowIndex,
                currentFocusSnapshot.itemIndex,
                focusState.catalogRowScrollStates + rowSelectedIndices
            )
        }
    }

    val heroVisible = uiState.heroSectionEnabled && uiState.heroItems.isNotEmpty()

    LaunchedEffect(shouldRequestInitialFocus, heroVisible, uiState.heroItems.size) {
        if (!shouldRequestInitialFocus || !heroVisible) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        try {
            heroFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {
        }
    }

    // Throttle D-pad key repeats to prevent HWUI overload when a key is held down.
    var lastKeyRepeatTime by remember { mutableStateOf(0L) }
    val contentFocusRequester = LocalContentFocusRequester.current

    LazyColumn(
        state = columnListState,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(contentFocusRequester)
            .focusRestorer()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount > 0) {
                    val now = System.currentTimeMillis()
                    if (now - lastKeyRepeatTime < KEY_REPEAT_THROTTLE_MS) {
                        return@onPreviewKeyEvent true // consume — too fast
                    }
                    lastKeyRepeatTime = now
                }
                false
            },
        contentPadding = PaddingValues(top = if (heroVisible) 0.dp else 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        if (heroVisible) {
            item(key = "hero_carousel", contentType = "hero") {
                HeroCarousel(
                    items = uiState.heroItems,
                    focusRequester = if (shouldRequestInitialFocus) heroFocusRequester else null,
                    onItemFocus = onItemFocus,
                    onItemClick = { item ->
                        onNavigateToDetail(
                            item.id,
                            item.apiType,
                            ""
                        )
                    }
                )
            }
        }

        if (uiState.continueWatchingItems.isNotEmpty()) {
            item(key = "continue_watching", contentType = "continue_watching") {
                ContinueWatchingSection(
                    items = uiState.continueWatchingItems,
                    onItemClick = { item ->
                        onContinueWatchingClick(item)
                    },
                    onStartFromBeginning = onContinueWatchingStartFromBeginning,
                    showManualPlayOption = showContinueWatchingManualPlayOption,
                    onPlayManually = onContinueWatchingPlayManually,
                    onDetailsClick = { item ->
                        onNavigateToDetail(
                            when (item) {
                                is ContinueWatchingItem.InProgress -> item.progress.contentId
                                is ContinueWatchingItem.NextUp -> item.info.contentId
                            },
                            when (item) {
                                is ContinueWatchingItem.InProgress -> item.progress.contentType
                                is ContinueWatchingItem.NextUp -> item.info.contentType
                            },
                            ""
                        )
                    },
                    onRemoveItem = { item ->
                        val contentId = when (item) {
                            is ContinueWatchingItem.InProgress -> item.progress.contentId
                            is ContinueWatchingItem.NextUp -> item.info.contentId
                        }
                        val season = when (item) {
                            is ContinueWatchingItem.InProgress -> item.progress.season
                            is ContinueWatchingItem.NextUp -> item.info.seedSeason
                        }
                        val episode = when (item) {
                            is ContinueWatchingItem.InProgress -> item.progress.episode
                            is ContinueWatchingItem.NextUp -> item.info.seedEpisode
                        }
                        val isNextUp = item is ContinueWatchingItem.NextUp
                        onRemoveContinueWatching(contentId, season, episode, isNextUp)
                    },
                    focusedItemIndex = when {
                        focusState.hasSavedFocus && focusState.focusedRowIndex == -1 -> focusState.focusedItemIndex
                        shouldRequestInitialFocus && !heroVisible -> 0
                        else -> -1
                    },
                    onItemFocused = { itemIndex ->
                        currentFocusSnapshot.rowIndex = -1
                        currentFocusSnapshot.itemIndex = itemIndex
                    }
                )
            }
        }

        itemsIndexed(
            items = visibleCatalogRows,
            key = { _, item -> "${item.addonId}_${item.apiType}_${item.catalogId}" },
            contentType = { _, _ -> "catalog_row" }
        ) { index, catalogRow ->
            val catalogKey = "${catalogRow.addonId}_${catalogRow.apiType}_${catalogRow.catalogId}"
            val shouldRestoreFocus = restoringFocus && index == focusState.focusedRowIndex
            val shouldInitialFocusFirstCatalogRow =
                shouldRequestInitialFocus &&
                    !heroVisible &&
                    uiState.continueWatchingItems.isEmpty() &&
                    index == 0

            val strTypeMovie = stringResource(R.string.type_movie)
            val strTypeSeries = stringResource(R.string.type_series)
            val typeLabel = remember(catalogRow.rawType, catalogRow.apiType, strTypeMovie, strTypeSeries) {
                val raw = catalogRow.rawType.takeIf { it.isNotBlank() } ?: catalogRow.apiType
                when (raw.lowercase()) {
                    "movie" -> strTypeMovie
                    "series" -> strTypeSeries
                    else -> formatAddonTypeLabel(raw)
                }
            }
            val catalogTitle = remember(catalogRow.catalogName, typeLabel, uiState.catalogTypeSuffixEnabled) {
                val formattedName = catalogRow.catalogName.replaceFirstChar { it.uppercase() }
                if (uiState.catalogTypeSuffixEnabled && typeLabel.isNotEmpty()) "$formattedName - $typeLabel" else formattedName
            }
            val catalogSubtitle = if (uiState.catalogAddonNameEnabled) {
                stringResource(R.string.catalog_from_addon, catalogRow.addonName)
            } else null

            val rowFocusRequester = rowFocusRequesters.getOrPut(catalogKey) { FocusRequester() }
            val savedIndex = rowSelectedIndices[catalogKey] ?: 0
            val initialIndex = when {
                shouldRestoreFocus -> focusState.focusedItemIndex.coerceIn(0, (catalogRow.items.size - 1).coerceAtLeast(0))
                else -> savedIndex.coerceIn(0, (catalogRow.items.size - 1).coerceAtLeast(0))
            }

            // Get the selected item for this row to pass its trailer URLs
            val currentSelectedIndex = rowSelectedIndices[catalogKey] ?: initialIndex
            val selectedItem = catalogRow.items.getOrNull(currentSelectedIndex)

            // Request initial focus if needed
            LaunchedEffect(shouldRestoreFocus, shouldInitialFocusFirstCatalogRow) {
                if (shouldRestoreFocus || shouldInitialFocusFirstCatalogRow) {
                    repeat(2) { withFrameNanos { } }
                    try {
                        rowFocusRequester.requestFocus()
                        if (restoringFocus) restoringFocus = false
                    } catch (_: IllegalStateException) {}
                }
            }

            NetflixStyleRow(
                title = catalogTitle,
                subtitle = catalogSubtitle,
                items = catalogRow.items,
                onItemClick = { item ->
                    onNavigateToDetail(item.id, item.apiType, catalogRow.addonBaseUrl)
                },
                onItemLongPress = { item ->
                    onCatalogItemLongPress(item, catalogRow.addonBaseUrl)
                },
                isItemWatched = isCatalogItemWatched,
                posterCardStyle = posterCardStyle,
                trailerPreviewUrl = selectedItem?.let { trailerPreviewUrls[it.id] },
                trailerPreviewAudioUrl = selectedItem?.let { trailerPreviewAudioUrls[it.id] },
                onRequestTrailerPreview = onRequestTrailerPreview,
                trailerEnabled = uiState.focusedPosterBackdropTrailerEnabled,
                trailerMuted = uiState.focusedPosterBackdropTrailerMuted,
                focusRequester = rowFocusRequester,
                initialSelectedIndex = initialIndex,
                onSelectedIndexChange = { newIndex ->
                    rowSelectedIndices[catalogKey] = newIndex
                    currentFocusSnapshot.rowIndex = index
                    currentFocusSnapshot.itemIndex = newIndex
                },
                onRowFocused = {
                    if (restoringFocus) restoringFocus = false
                    currentFocusSnapshot.rowIndex = index
                }
            )
        }
    }
}
