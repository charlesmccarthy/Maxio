package com.nuvio.tv.ui.screens.home

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import com.nuvio.tv.core.util.filterReleasedItems
import kotlinx.coroutines.withContext
import java.time.LocalDate

private data class CatalogUpdateResult(
    val displayRows: List<CatalogRow>,
    val heroItems: List<com.nuvio.tv.domain.model.MetaPreview>,
    val gridItems: List<GridItem>,
    val fullRows: List<CatalogRow>
)

private const val HOME_HERO_ITEM_COUNT = 12
private const val HOME_HERO_SELECTED_ROW_TARGET = 9

internal fun HomeViewModel.loadHomeCatalogOrderPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.homeCatalogOrderKeys.collectLatest { keys ->
            homeCatalogOrderKeys = keys
            rebuildCatalogOrder(addonsCache)
            scheduleUpdateCatalogRows()
        }
    }
}

internal fun HomeViewModel.loadDisabledHomeCatalogPreferencePipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.disabledHomeCatalogKeys.collectLatest { keys ->
            val newKeys = keys.toSet()
            if (newKeys == disabledHomeCatalogKeys) return@collectLatest
            disabledHomeCatalogKeys = newKeys
            rebuildCatalogOrder(addonsCache)
            if (addonsCache.isNotEmpty()) {
                loadAllCatalogsPipeline(addonsCache)
            } else {
                scheduleUpdateCatalogRows()
            }
        }
    }
}

internal fun HomeViewModel.observeTmdbSettingsPipeline() {
    viewModelScope.launch {
        tmdbSettingsDataStore.settings
            .distinctUntilChanged()
            .collectLatest { settings ->
                currentTmdbSettings = settings
                refreshLikedRecommendationRowsPipeline()
                scheduleUpdateCatalogRows()
            }
    }
}

internal fun HomeViewModel.observeInstalledAddonsPipeline() {
    viewModelScope.launch {
        addonRepository.getInstalledAddons()
            .distinctUntilChanged()
            .collectLatest { addons ->
                addonsCache = addons
                loadAllCatalogsPipeline(addons)
            }
    }
}

internal suspend fun HomeViewModel.loadAllCatalogsPipeline(
    addons: List<Addon>,
    forceReload: Boolean = false
) {
    val signature = buildHomeCatalogLoadSignature(addons)
    if (!forceReload &&
        signature == activeCatalogLoadSignature &&
        (catalogsLoadInProgress || catalogsMap.isNotEmpty())
    ) {
        return
    }

    activeCatalogLoadSignature = signature
    catalogsLoadInProgress = true
    catalogLoadGeneration += 1
    val generation = catalogLoadGeneration
    cancelInFlightCatalogLoads()

    _uiState.update { it.copy(isLoading = true, error = null, installedAddonsCount = addons.size) }
    catalogOrder.clear()
    catalogsMap.clear()
    posterStatusReconcileJob?.cancel()
    reconcilePosterStatusObserversPipeline(emptyList())
    _fullCatalogRows.value = emptyList()
    truncatedRowCache.clear()
    hasRenderedFirstCatalog = false
    trailerPreviewLoadingIds.clear()
    trailerPreviewNegativeCache.clear()
    trailerPreviewRequestSignatures.clear()
    trailerPreviewUrlsState.clear()
    trailerPreviewAudioUrlsState.clear()
    activeTrailerPreviewItemId = null
    trailerPreviewRequestVersion = 0L
    prefetchedExternalMetaIds.clear()
    externalMetaPrefetchInFlightIds.clear()
    externalMetaPrefetchJob?.cancel()
    pendingExternalMetaPrefetchItemId = null
    prefetchedTmdbIds.clear()
    tmdbEnrichFocusJob?.cancel()
    pendingTmdbEnrichItemId = null
    lastHeroEnrichmentSignature = null
    lastHeroEnrichedItems = emptyList()
    heroItemOrder = emptyList()

    try {
        if (addons.isEmpty()) {
            catalogsLoadInProgress = false
            _uiState.update { it.copy(isLoading = false, error = "No addons installed") }
            return
        }

        rebuildCatalogOrder(addons)

        if (catalogOrder.isEmpty()) {
            catalogsLoadInProgress = false
            _uiState.update { it.copy(isLoading = false, error = "No catalog addons installed") }
            return
        }

        val catalogsToLoad = addons.flatMap { addon ->
            addon.catalogs
                .filterNot {
                    !it.shouldShowOnHome() || isCatalogDisabled(
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        type = it.apiType,
                        catalogId = it.id,
                        catalogName = it.name
                    )
                }
                .map { catalog -> addon to catalog }
        }
        pendingCatalogLoads = catalogsToLoad.size
        catalogsToLoad.forEach { (addon, catalog) ->
            loadCatalogPipeline(addon, catalog, generation)
        }
    } catch (e: Exception) {
        catalogsLoadInProgress = false
        _uiState.update { it.copy(isLoading = false, error = e.message) }
    }
}

internal fun HomeViewModel.loadCatalogPipeline(
    addon: Addon,
    catalog: CatalogDescriptor,
    generation: Long
) {
    val loadJob = viewModelScope.launch {
        var hasCountedCompletion = false
        catalogLoadSemaphore.withPermit {
            if (generation != catalogLoadGeneration) return@withPermit
            val supportsSkip = catalog.supportsExtra("skip")
            val skipStep = catalog.skipStep()
            Log.d(
                HomeViewModel.TAG,
                "Loading home catalog addonId=${addon.id} addonName=${addon.name} type=${catalog.apiType} catalogId=${catalog.id} catalogName=${catalog.name} supportsSkip=$supportsSkip skipStep=$skipStep"
            )
            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = catalog.apiType,
                skip = 0,
                skipStep = skipStep,
                supportsSkip = supportsSkip
            ).collect { result ->
                if (generation != catalogLoadGeneration) return@collect
                when (result) {
                    is NetworkResult.Success -> {
                        val key = catalogKey(
                            addonId = addon.id,
                            type = catalog.apiType,
                            catalogId = catalog.id
                        )
                        catalogsMap[key] = result.data
                        if (!hasCountedCompletion) {
                            pendingCatalogLoads = (pendingCatalogLoads - 1).coerceAtLeast(0)
                            hasCountedCompletion = true
                        }
                        Log.d(
                            HomeViewModel.TAG,
                            "Home catalog loaded addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id} items=${result.data.items.size} pending=$pendingCatalogLoads"
                        )
                        if (pendingCatalogLoads == 0) {
                            catalogsLoadInProgress = false
                        }
                        scheduleUpdateCatalogRows()
                    }
                    is NetworkResult.Error -> {
                        if (!hasCountedCompletion) {
                            pendingCatalogLoads = (pendingCatalogLoads - 1).coerceAtLeast(0)
                            hasCountedCompletion = true
                        }
                        Log.w(
                            HomeViewModel.TAG,
                            "Home catalog failed addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id} code=${result.code} message=${result.message}"
                        )
                        if (pendingCatalogLoads == 0) {
                            catalogsLoadInProgress = false
                        }
                        scheduleUpdateCatalogRows()
                    }
                    NetworkResult.Loading -> {
                        /* Handled by individual row */
                    }
                }
            }
        }
    }
    registerCatalogLoadJob(loadJob)
}

internal fun HomeViewModel.loadMoreCatalogItemsPipeline(catalogId: String, addonId: String, type: String) {
    val key = catalogKey(addonId = addonId, type = type, catalogId = catalogId)
    val currentRow = catalogsMap[key] ?: return

    if (currentRow.isLoading || !currentRow.hasMore) return
    if (key in _loadingCatalogs.value) return

    catalogsMap[key] = currentRow.copy(isLoading = true)
    _loadingCatalogs.update { it + key }

    viewModelScope.launch {
        val addon = addonsCache.find { it.id == addonId } ?: return@launch

        val nextSkip = (currentRow.currentPage + 1) * currentRow.skipStep
        catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalogId,
            catalogName = currentRow.catalogName,
            type = currentRow.apiType,
            skip = nextSkip,
            skipStep = currentRow.skipStep,
            supportsSkip = currentRow.supportsSkip
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    val existingIds = currentRow.items.asSequence()
                        .map { "${it.apiType}:${it.id}" }
                        .toHashSet()
                    val newUniqueItems = result.data.items.filter { item ->
                        "${item.apiType}:${item.id}" !in existingIds
                    }
                    val mergedItems = currentRow.items + newUniqueItems
                    val hasMore = if (newUniqueItems.isEmpty()) false else result.data.hasMore
                    catalogsMap[key] = result.data.copy(items = mergedItems, hasMore = hasMore)
                    _loadingCatalogs.update { it - key }
                    scheduleUpdateCatalogRows()
                }
                is NetworkResult.Error -> {
                    catalogsMap[key] = currentRow.copy(isLoading = false)
                    _loadingCatalogs.update { it - key }
                    scheduleUpdateCatalogRows()
                }
                NetworkResult.Loading -> { }
            }
        }
    }
}

internal suspend fun HomeViewModel.updateCatalogRowsPipeline() {
    val orderedKeys = catalogOrder.toList()
    val catalogSnapshot = catalogsMap.toMap()
    val heroCatalogKeys = currentHeroCatalogKeys
    val currentHeroItems = _uiState.value.heroItems
    val currentLayout = _uiState.value.homeLayout
    val currentGridItems = _uiState.value.gridItems
    val heroSectionEnabled = _uiState.value.heroSectionEnabled
    val hideUnreleased = _uiState.value.hideUnreleasedContent
    val likedRowsSnapshot = likedRecommendationRows.toList()
    val libraryRowsSnapshot = libraryRecentlyAddedRows.toList()

    val (displayRows, baseHeroItems, baseGridItems, fullRowsFiltered) = withContext(Dispatchers.Default) {
        val today = LocalDate.now()
        val rawRows = orderedKeys.mapNotNull { key -> catalogSnapshot[key] }
        val orderedRows = if (hideUnreleased) {
            rawRows.map { it.filterReleasedItems(today) }
        } else {
            rawRows
        }
        val selectedHeroCatalogSet = heroCatalogKeys.toSet()
        val selectedHeroRows = if (selectedHeroCatalogSet.isNotEmpty()) {
            orderedRows.filter { row ->
                val key = "${row.addonId}_${row.apiType}_${row.catalogId}"
                key in selectedHeroCatalogSet
            }
        } else {
            emptyList()
        }
        val currentHeroOrder = heroItemOrder
        val heroRotationSeed = buildHomeHeroRotationSeed(
            startupStartedAtMs = startupStartedAtMs,
            today = today
        )

        val computedHeroItems = buildHomeHeroItems(
            orderedRows = orderedRows,
            selectedHeroRows = selectedHeroRows,
            currentOrder = currentHeroOrder,
            rotationSeed = heroRotationSeed
        )
        val stabilizedHeroItems = if (catalogsLoadInProgress || pendingCatalogLoads > 0) {
            stabilizeIncrementalHomeHeroItems(
                previousItems = currentHeroItems,
                nextItems = computedHeroItems,
                availableRows = orderedRows
            )
        } else {
            computedHeroItems
        }


        val displayRowsSource = buildList {
            // Recently-added Library rows sit at the very top (just under Continue Watching).
            addAll(libraryRowsSnapshot)
            if (likedRowsSnapshot.isEmpty()) {
                addAll(orderedRows)
            } else if (orderedRows.isEmpty()) {
                addAll(likedRowsSnapshot)
            } else {
                add(orderedRows.first())
                addAll(likedRowsSnapshot)
                addAll(orderedRows.drop(1))
            }
        }

        val computedDisplayRows = displayRowsSource.map { row ->
            val shouldKeepFullRowInModern = currentLayout == HomeLayout.MODERN && row.supportsSkip
            if (row.items.size > 25 && !shouldKeepFullRowInModern) {
                val key = "${row.addonId}_${row.apiType}_${row.catalogId}"
                val cachedEntry = truncatedRowCache[key]
                if (cachedEntry != null && cachedEntry.sourceRow === row) {
                    cachedEntry.truncatedRow
                } else {
                    val truncatedRow = row.copy(items = row.items.take(25))
                    truncatedRowCache[key] = HomeViewModel.TruncatedRowCacheEntry(
                        sourceRow = row,
                        truncatedRow = truncatedRow
                    )
                    truncatedRow
                }
            } else {
                val key = "${row.addonId}_${row.apiType}_${row.catalogId}"
                truncatedRowCache.remove(key)
                row
            }
        }

        val computedGridItems = if (currentLayout == HomeLayout.GRID) {
            val posterCardWidthDp = _uiState.value.posterCardWidthDp
            val itemsPerRow = when (posterCardWidthDp) {
                104 -> 7   // compact
                112 -> 6   // dense
                120 -> 6   // standard
                126 -> 6   // balanced
                134 -> 5   // comfort
                140 -> 5   // large
                else -> 6
            }
            val rowCount = if (posterCardWidthDp <= 104) 2 else 3
            val seeAllThreshold = itemsPerRow * rowCount + 2
            val maxWithSeeAll = itemsPerRow * rowCount - 1
            val maxWithoutSeeAll = itemsPerRow * rowCount
            buildList {
                if (heroSectionEnabled && stabilizedHeroItems.isNotEmpty()) {
                    add(GridItem.Hero(stabilizedHeroItems))
                }
                computedDisplayRows.filter { it.items.isNotEmpty() }.forEach { row ->
                    add(
                        GridItem.SectionDivider(
                            catalogName = row.catalogName,
                            catalogId = row.catalogId,
                            addonBaseUrl = row.addonBaseUrl,
                            addonId = row.addonId,
                            type = row.apiType
                        )
                    )
                    val hasEnoughForSeeAll = row.items.size >= seeAllThreshold
                    val displayItems = if (hasEnoughForSeeAll) row.items.take(maxWithSeeAll) else row.items.take(maxWithoutSeeAll)
                    displayItems.forEach { item ->
                        add(
                            GridItem.Content(
                                item = item,
                                addonBaseUrl = row.addonBaseUrl,
                                catalogId = row.catalogId,
                                catalogName = row.catalogName
                            )
                        )
                    }
                    if (hasEnoughForSeeAll) {
                        add(
                            GridItem.SeeAll(
                                catalogId = row.catalogId,
                                addonId = row.addonId,
                                type = row.apiType
                            )
                        )
                    }
                }
            }
        } else {
            currentGridItems
        }

        CatalogUpdateResult(computedDisplayRows, stabilizedHeroItems, computedGridItems, orderedRows)
    }

    _fullCatalogRows.update { rows ->
        if (rows == fullRowsFiltered) rows else fullRowsFiltered
    }

    val nextGridItems = if (currentLayout == HomeLayout.GRID) {
        replaceGridHeroItemsPipeline(baseGridItems, baseHeroItems)
    } else {
        baseGridItems
    }

    heroItemOrder = baseHeroItems.map(::heroIdentityKey)

    _uiState.update { state ->
        state.copy(
            catalogRows = if (state.catalogRows == displayRows) state.catalogRows else displayRows,
            heroItems = if (state.heroItems == baseHeroItems) state.heroItems else baseHeroItems,
            gridItems = if (state.gridItems == nextGridItems) state.gridItems else nextGridItems,
            isLoading = false
        )
    }

    val tmdbSettings = currentTmdbSettings
    val tmdbEnabledForCurrentLayout = tmdbSettings.enabled &&
        (currentLayout != HomeLayout.MODERN || tmdbSettings.modernHomeEnabled)
    val shouldUseEnrichedHeroItems = tmdbEnabledForCurrentLayout &&
        (tmdbSettings.useArtwork || tmdbSettings.useBasicInfo || tmdbSettings.useDetails)

    if (shouldUseEnrichedHeroItems && baseHeroItems.isNotEmpty()) {
        heroEnrichmentJob?.cancel()
        heroEnrichmentJob = viewModelScope.launch {
            val enrichmentSignature = heroEnrichmentSignaturePipeline(baseHeroItems, tmdbSettings)
            if (lastHeroEnrichmentSignature == enrichmentSignature) {
                val cached = lastHeroEnrichedItems
                _uiState.update { state ->
                    state.copy(
                        heroItems = if (state.heroItems == cached) state.heroItems else cached,
                        gridItems = if (currentLayout == HomeLayout.GRID) {
                            val enrichedGrid = replaceGridHeroItemsPipeline(state.gridItems, cached)
                            if (state.gridItems == enrichedGrid) state.gridItems else enrichedGrid
                        } else state.gridItems
                    )
                }
            } else {
                val enrichedItems = enrichHeroItemsPipeline(baseHeroItems, tmdbSettings)
                lastHeroEnrichmentSignature = enrichmentSignature
                lastHeroEnrichedItems = enrichedItems
                _uiState.update { state ->
                    state.copy(
                        heroItems = if (state.heroItems == enrichedItems) state.heroItems else enrichedItems,
                        gridItems = if (currentLayout == HomeLayout.GRID) {
                            val enrichedGrid = replaceGridHeroItemsPipeline(state.gridItems, enrichedItems)
                            if (state.gridItems == enrichedGrid) state.gridItems else enrichedGrid
                        } else state.gridItems
                    )
                }
            }
        }
    } else {
        lastHeroEnrichmentSignature = null
        lastHeroEnrichedItems = emptyList()
        heroItemOrder = emptyList()
    }

    schedulePosterStatusReconcilePipeline(displayRows)
    refreshFeaturedStudiosPipeline(displayRows, baseHeroItems)
}

private fun buildHomeHeroItems(
    orderedRows: List<CatalogRow>,
    selectedHeroRows: List<CatalogRow>,
    currentOrder: List<String>,
    rotationSeed: Long
): List<MetaPreview> {
    if (orderedRows.isEmpty()) return emptyList()

    val result = mutableListOf<MetaPreview>()
    val selectedKeys = linkedSetOf<String>()

    val selectedTarget = if (selectedHeroRows.isEmpty()) {
        0
    } else {
        minOf(
            HOME_HERO_ITEM_COUNT,
            maxOf(
                selectedHeroRows.size.coerceAtMost(HOME_HERO_ITEM_COUNT),
                HOME_HERO_SELECTED_ROW_TARGET
            )
        )
    }

    result += takeHomeHeroItemsFromRows(
        rows = selectedHeroRows,
        filter = MetaPreview::hasHeroArtwork,
        currentOrder = currentOrder,
        limit = selectedTarget,
        rotationSeed = rotationSeed,
        selectedKeys = selectedKeys
    )
    if (result.size < selectedTarget) {
        result += takeHomeHeroItemsFromRows(
            rows = selectedHeroRows,
            filter = MetaPreview::isValidCatalogItem,
            currentOrder = currentOrder,
            limit = selectedTarget - result.size,
            rotationSeed = rotationSeed,
            selectedKeys = selectedKeys
        )
    }

    if (result.size < HOME_HERO_ITEM_COUNT) {
        result += takeHomeHeroItemsFromRows(
            rows = orderedRows,
            filter = MetaPreview::hasHeroArtwork,
            currentOrder = currentOrder,
            limit = HOME_HERO_ITEM_COUNT - result.size,
            rotationSeed = rotationSeed,
            selectedKeys = selectedKeys
        )
    }
    if (result.size < HOME_HERO_ITEM_COUNT) {
        result += takeHomeHeroItemsFromRows(
            rows = orderedRows,
            filter = MetaPreview::isValidCatalogItem,
            currentOrder = currentOrder,
            limit = HOME_HERO_ITEM_COUNT - result.size,
            rotationSeed = rotationSeed,
            selectedKeys = selectedKeys
        )
    }

    return result
}

private fun stabilizeIncrementalHomeHeroItems(
    previousItems: List<MetaPreview>,
    nextItems: List<MetaPreview>,
    availableRows: List<CatalogRow>
): List<MetaPreview> {
    if (previousItems.isEmpty()) return nextItems
    if (nextItems.isEmpty()) return emptyList()

    val availableKeys = availableRows
        .asSequence()
        .flatMap { row -> row.items.asSequence() }
        .map(::heroIdentityKey)
        .toSet()

    val result = mutableListOf<MetaPreview>()
    val seenKeys = linkedSetOf<String>()

    previousItems.forEach { item ->
        val key = heroIdentityKey(item)
        if (key in availableKeys && seenKeys.add(key)) {
            result += item
        }
    }

    nextItems.forEach { item ->
        val key = heroIdentityKey(item)
        if (seenKeys.add(key)) {
            result += item
        }
        if (result.size >= HOME_HERO_ITEM_COUNT) {
            return result.take(HOME_HERO_ITEM_COUNT)
        }
    }

    return result.take(HOME_HERO_ITEM_COUNT)
}

private fun takeHomeHeroItemsFromRows(
    rows: List<CatalogRow>,
    filter: (MetaPreview) -> Boolean,
    currentOrder: List<String>,
    limit: Int,
    rotationSeed: Long,
    selectedKeys: MutableSet<String>
): List<MetaPreview> {
    if (limit <= 0 || rows.isEmpty()) return emptyList()

    val currentOrderIndex = currentOrder.withIndex().associate { (index, key) -> key to index }
    val rowStartOffset = positiveModulo(rotationSeed.toInt(), rows.size)

    data class RowCursor(
        val items: List<MetaPreview>,
        var nextIndex: Int = 0
    )

    val cursors = buildList {
        val rotatedRows = rows.drop(rowStartOffset) + rows.take(rowStartOffset)
        rotatedRows.forEach { row ->
            val filteredByIdentity = linkedMapOf<String, MetaPreview>()
            row.items.forEach { item ->
                if (!filter(item)) return@forEach
                val key = heroIdentityKey(item)
                if (key !in filteredByIdentity) {
                    filteredByIdentity[key] = item
                }
            }
            if (filteredByIdentity.isEmpty()) return@forEach
            val orderedItems = filteredByIdentity.values.sortedWith(
                compareBy<MetaPreview> { currentOrderIndex[heroIdentityKey(it)] ?: Int.MAX_VALUE }
                    .thenBy { homeHeroSessionSortKey(row, it, rotationSeed) }
                    .thenBy { heroIdentityKey(it) }
            )
            add(RowCursor(items = orderedItems))
        }
    }

    if (cursors.isEmpty()) return emptyList()

    val result = mutableListOf<MetaPreview>()
    while (result.size < limit) {
        var addedAny = false
        cursors.forEach { cursor ->
            while (cursor.nextIndex < cursor.items.size) {
                val item = cursor.items[cursor.nextIndex++]
                val key = heroIdentityKey(item)
                if (selectedKeys.add(key)) {
                    result += item
                    addedAny = true
                    break
                }
            }
            if (result.size >= limit) return result
        }
        if (!addedAny) break
    }

    return result
}

private fun buildHomeHeroRotationSeed(
    startupStartedAtMs: Long,
    today: LocalDate
): Long {
    return startupStartedAtMs xor today.toEpochDay()
}

private fun homeHeroSessionSortKey(
    row: CatalogRow,
    item: MetaPreview,
    rotationSeed: Long
): Int {
    return buildString {
        append(rotationSeed)
        append('|')
        append(row.addonId)
        append('|')
        append(row.apiType)
        append('|')
        append(row.catalogId)
        append('|')
        append(heroIdentityKey(item))
    }.hashCode()
}

private fun heroIdentityKey(item: MetaPreview): String {
    val apiType = item.apiType.lowercase()
    val imdbId = item.imdbId?.trim()?.lowercase().orEmpty()
    if (imdbId.isNotEmpty()) return "$apiType|imdb|$imdbId"

    val slug = item.slug?.trim()?.lowercase().orEmpty()
    if (slug.isNotEmpty()) return "$apiType|slug|$slug"

    return "$apiType|id|${item.id}"
}

private fun positiveModulo(value: Int, divisor: Int): Int {
    if (divisor == 0) return 0
    val remainder = value % divisor
    return if (remainder < 0) remainder + divisor else remainder
}

internal fun HomeViewModel.schedulePosterStatusReconcilePipeline(rows: List<CatalogRow>) {
    posterStatusReconcileJob?.cancel()
    if (rows.isEmpty()) {
        reconcilePosterStatusObserversPipeline(rows)
        return
    }
    posterStatusReconcileJob = viewModelScope.launch {
        delay(500)
        reconcilePosterStatusObserversPipeline(rows)
    }
}

internal fun HomeViewModel.reconcilePosterStatusObserversPipeline(rows: List<CatalogRow>) {
    val desiredLibraryItemsByKey = linkedMapOf<String, Pair<String, String>>()
    rows.asSequence()
        .flatMap { row -> row.items.asSequence() }
        .take(HomeViewModel.MAX_POSTER_STATUS_OBSERVERS)
        .forEach { item ->
            val key = homeItemStatusKey(item.id, item.apiType)
            if (key !in desiredLibraryItemsByKey) {
                desiredLibraryItemsByKey[key] = item.id to item.apiType
            }
        }
    val desiredLibraryKeys = desiredLibraryItemsByKey.keys

    val allMovieItemsByKey = linkedMapOf<String, String>()
    rows.asSequence()
        .flatMap { row -> row.items.asSequence() }
        .filter { it.apiType.equals("movie", ignoreCase = true) }
        .forEach { item ->
            val key = homeItemStatusKey(item.id, item.apiType)
            if (key !in allMovieItemsByKey) {
                allMovieItemsByKey[key] = item.id
            }
        }
    val desiredMovieKeys = allMovieItemsByKey.keys

    posterLibraryObserverJobs.keys
        .filterNot { it in desiredLibraryKeys }
        .forEach { staleKey ->
            posterLibraryObserverJobs.remove(staleKey)?.cancel()
        }

    desiredLibraryItemsByKey.forEach { (statusKey, itemRef) ->
        val itemId = itemRef.first
        val itemType = itemRef.second

        if (statusKey !in posterLibraryObserverJobs) {
            posterLibraryObserverJobs[statusKey] = viewModelScope.launch {
                libraryRepository.isInLibrary(itemId = itemId, itemType = itemType)
                    .distinctUntilChanged()
                    .collectLatest { isInLibrary ->
                        _uiState.update { state ->
                            if (state.posterLibraryMembership[statusKey] == isInLibrary) {
                                state
                            } else {
                                state.copy(
                                    posterLibraryMembership = state.posterLibraryMembership + (statusKey to isInLibrary)
                                )
                            }
                        }
                    }
            }
        }
    }

    if (desiredMovieKeys != lastMovieWatchedItemKeys) {
        lastMovieWatchedItemKeys = desiredMovieKeys
        movieWatchedObserverJobs.values.forEach { it.cancel() }
        movieWatchedObserverJobs.clear()
        movieWatchedBatchJob?.cancel()

        if (desiredMovieKeys.isNotEmpty()) {
            movieWatchedBatchJob = viewModelScope.launch {
                watchProgressRepository.observeWatchedMovieIds()
                    .collectLatest { watchedIds ->
                        _uiState.update { state ->
                            val newStatus = buildMap {
                                allMovieItemsByKey.forEach { (statusKey, contentId) ->
                                    put(statusKey, contentId in watchedIds)
                                }
                            }
                            if (state.movieWatchedStatus == newStatus) {
                                state
                            } else {
                                state.copy(movieWatchedStatus = newStatus)
                            }
                        }
                    }
            }
        }
    }

    _uiState.update { state ->
        val trimmedLibraryMembership =
            state.posterLibraryMembership.filterKeys { it in desiredLibraryKeys }
        val trimmedMovieWatchedStatus =
            state.movieWatchedStatus.filterKeys { it in desiredMovieKeys }
        val trimmedLibraryPending =
            state.posterLibraryPending.filterTo(linkedSetOf()) { it in desiredLibraryKeys }
        val trimmedMovieWatchedPending =
            state.movieWatchedPending.filterTo(linkedSetOf()) { it in desiredMovieKeys }

        if (
            trimmedLibraryMembership == state.posterLibraryMembership &&
            trimmedMovieWatchedStatus == state.movieWatchedStatus &&
            trimmedLibraryPending == state.posterLibraryPending &&
            trimmedMovieWatchedPending == state.movieWatchedPending
        ) {
            state
        } else {
            state.copy(
                posterLibraryMembership = trimmedLibraryMembership,
                movieWatchedStatus = trimmedMovieWatchedStatus,
                posterLibraryPending = trimmedLibraryPending,
                movieWatchedPending = trimmedMovieWatchedPending
            )
        }
    }
}
