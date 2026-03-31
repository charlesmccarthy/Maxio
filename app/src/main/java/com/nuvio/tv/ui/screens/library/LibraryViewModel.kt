package com.nuvio.tv.ui.screens.library

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.repository.TraktLibraryService
import com.nuvio.tv.data.trailer.ActiveTrailerState
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.Dispatchers
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.TraktListPrivacy
import com.nuvio.tv.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.nuvio.tv.R
import java.util.Locale
import javax.inject.Inject
import kotlin.math.absoluteValue

private const val MIN_DISCOVERY_STYLE_LIBRARY_ITEMS = 12
private const val MAX_LIBRARY_ROW_ITEMS = 24
private const val MAX_LIBRARY_MOVIE_GENRE_ROWS = 8
private const val MAX_LIBRARY_SHOW_GENRE_ROWS = 8
private const val MIN_LIBRARY_SUPPORTING_ROW_ITEMS = 4

data class LibraryTypeTab(
    val key: String,
    val label: String
) {
    companion object {
        const val ALL_KEY = "__all__"
        val All = LibraryTypeTab(key = ALL_KEY, label = "All")
    }
}

enum class LibrarySortOption(
    val key: String,
    val labelResId: Int
) {
    DEFAULT("default", R.string.library_sort_trakt_order),
    ADDED_DESC("added_desc", R.string.library_sort_added_desc),
    ADDED_ASC("added_asc", R.string.library_sort_added_asc),
    TITLE_ASC("title_asc", R.string.library_sort_title_asc),
    TITLE_DESC("title_desc", R.string.library_sort_title_desc),
    YEAR_ASC("year_asc", R.string.library_sort_year_asc),
    YEAR_DESC("year_desc", R.string.library_sort_year_desc),
    RANDOM("random", R.string.library_sort_random);

    companion object {
        val TraktOptions = listOf(DEFAULT, ADDED_DESC, ADDED_ASC, TITLE_ASC, TITLE_DESC, YEAR_ASC, YEAR_DESC, RANDOM)
        val LocalOptions = listOf(ADDED_DESC, ADDED_ASC, TITLE_ASC, TITLE_DESC, YEAR_ASC, YEAR_DESC, RANDOM)
        fun fromKey(key: String): LibrarySortOption? = entries.find { it.key == key }
    }
}

data class LibraryListEditorState(
    val mode: Mode,
    val listId: String? = null,
    val name: String = "",
    val description: String = "",
    val privacy: TraktListPrivacy = TraktListPrivacy.PRIVATE
) {
    enum class Mode {
        CREATE,
        EDIT
    }
}

data class LibraryRowGroup(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val items: List<LibraryEntry>
)

data class LibraryUiState(
    val sourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val allItems: List<LibraryEntry> = emptyList(),
    val visibleItems: List<LibraryEntry> = emptyList(),
    val listTabs: List<LibraryListTab> = emptyList(),
    val availableTypeTabs: List<LibraryTypeTab> = emptyList(),
    val availableSortOptions: List<LibrarySortOption> = emptyList(),
    val selectedListKey: String? = null,
    val selectedTypeTab: LibraryTypeTab? = null,
    val selectedSortOption: LibrarySortOption = LibrarySortOption.DEFAULT,
    val sortSelectionVersion: Long = 0L,
    val posterCardWidthDp: Int = 126,
    val posterCardCornerRadiusDp: Int = 12,
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,
    val transientMessage: String? = null,
    val showManageDialog: Boolean = false,
    val manageSelectedListKey: String? = null,
    val listEditorState: LibraryListEditorState? = null,
    val pendingOperation: Boolean = false,
    val appliedRandomSortVersion: Long = -1L,
    val randomOrderKeys: List<String> = emptyList(),
    val groupedRows: List<LibraryRowGroup> = emptyList()
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val trailerService: TrailerService,
    private val tmdbService: TmdbService,
    private val tmdbApi: TmdbApi,
    private val activeTrailerState: ActiveTrailerState
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var messageClearJob: Job? = null

    // Trailer preview support
    val trailerPreviewUrls = mutableStateMapOf<String, String>()
    val trailerPreviewAudioUrls = mutableStateMapOf<String, String>()
    private val trailerNegativeCache = mutableSetOf<String>()
    private val trailerLoadingIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    var trailerEnabled: Boolean = false
        private set
    var trailerMuted: Boolean = true
        private set

    // Logo URL support
    val logoUrls = mutableStateMapOf<String, String>()
    private val logoNegativeCache = mutableSetOf<String>()
    private val logoLoadingIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        observeLayoutPreferences()
        observeLibraryData()
        observeTrailerPrefs()
    }

    private fun observeTrailerPrefs() {
        viewModelScope.launch {
            combine(
                layoutPreferenceDataStore.focusedPosterBackdropTrailerEnabled,
                layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted
            ) { enabled, muted -> enabled to muted }
                .collect { (enabled, muted) ->
                    trailerEnabled = enabled
                    trailerMuted = muted
                }
        }
    }

    fun requestTrailerPreview(item: MetaPreview) {
        val itemId = item.id
        if (trailerNegativeCache.contains(itemId)) return
        if (trailerPreviewUrls.containsKey(itemId)) return
        if (!trailerLoadingIds.add(itemId)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tmdbId = runCatching { tmdbService.ensureTmdbId(itemId, item.apiType) }.getOrNull()
                val yearStr = item.releaseInfo?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value }
                val source = trailerService.getTrailerPlaybackSource(
                    title = item.name, year = yearStr, tmdbId = tmdbId, type = item.apiType
                )
                if (source?.videoUrl != null) {
                    trailerPreviewUrls[itemId] = source.videoUrl
                    source.audioUrl?.takeIf { it.isNotBlank() }?.let { trailerPreviewAudioUrls[itemId] = it }
                } else {
                    trailerNegativeCache.add(itemId)
                }
            } catch (_: Exception) {
                trailerNegativeCache.add(itemId)
            } finally {
                trailerLoadingIds.remove(itemId)
            }
        }
    }

    fun requestLogo(item: MetaPreview) {
        val itemId = item.id
        // Only fetch if item already has no logo
        if (!item.logo.isNullOrBlank()) return
        if (logoUrls.containsKey(itemId)) return
        if (logoNegativeCache.contains(itemId)) return
        if (!logoLoadingIds.add(itemId)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tmdbIdStr = runCatching { tmdbService.ensureTmdbId(itemId, item.apiType) }.getOrNull()
                val tmdbId = tmdbIdStr?.toIntOrNull()
                if (tmdbId == null) {
                    logoNegativeCache.add(itemId)
                    return@launch
                }
                val isMovie = item.apiType.equals("movie", ignoreCase = true)
                val response = if (isMovie) {
                    tmdbApi.getMovieImages(movieId = tmdbId, apiKey = BuildConfig.TMDB_API_KEY)
                } else {
                    tmdbApi.getTvImages(tvId = tmdbId, apiKey = BuildConfig.TMDB_API_KEY)
                }
                val logoPath = response.body()?.logos
                    ?.firstOrNull { it.iso6391 == "en" || it.iso6391 == null }
                    ?.filePath
                if (logoPath != null) {
                    logoUrls[itemId] = "https://image.tmdb.org/t/p/w500$logoPath"
                } else {
                    logoNegativeCache.add(itemId)
                }
            } catch (_: Exception) {
                logoNegativeCache.add(itemId)
            } finally {
                logoLoadingIds.remove(itemId)
            }
        }
    }

    // Trailer handoff support
    private var lastTrailerItemId: String? = null
    private var lastTrailerPositionMs: Long = 0L

    fun onTrailerProgressChanged(itemId: String, positionMs: Long) {
        lastTrailerItemId = itemId
        lastTrailerPositionMs = positionMs
    }

    fun storeActiveTrailer(item: MetaPreview) {
        val videoUrl = trailerPreviewUrls[item.id] ?: return
        activeTrailerState.store(item.id, videoUrl, trailerPreviewAudioUrls[item.id], lastTrailerPositionMs)
    }

    fun onScreenEntered() {
        Unit
    }

    fun onSelectTypeTab(tab: LibraryTypeTab) {
        _uiState.update { current ->
            val updated = current.copy(selectedTypeTab = tab)
            updated.withVisibleItems()
        }
    }

    fun onSelectListTab(listKey: String) {
        _uiState.update { current ->
            val updated = current.copy(selectedListKey = listKey)
            updated.withVisibleItems()
        }
    }

    fun onSelectSortOption(option: LibrarySortOption) {
        _uiState.update { current ->
            val nextVersion = if (current.selectedSortOption != option) {
                current.sortSelectionVersion + 1L
            } else {
                current.sortSelectionVersion
            }
            val updated = current.copy(
                selectedSortOption = option,
                sortSelectionVersion = nextVersion
            )
            updated.withVisibleItems()
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.setLibrarySortOption(option.key)
        }
    }

    fun onRefresh() {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            setTransientMessage("Syncing Trakt library...")
            runCatching {
                libraryRepository.refreshNow()
                setTransientMessage("Library synced")
            }.onFailure { error ->
                setError(error.message ?: "Failed to refresh library")
            }
        }
    }

    fun onOpenManageLists() {
        _uiState.update { current ->
            if (current.sourceMode != LibrarySourceMode.TRAKT) {
                return@update current
            }
            current.copy(
                showManageDialog = true,
                manageSelectedListKey = current.manageSelectedListKey
                    ?: current.listTabs.firstOrNull { it.type == LibraryListTab.Type.PERSONAL }?.key
            )
        }
    }

    fun onCloseManageLists() {
        _uiState.update { current ->
            current.copy(
                showManageDialog = false,
                listEditorState = null,
                errorMessage = null
            )
        }
    }

    fun onSelectManageList(listKey: String) {
        _uiState.update { it.copy(manageSelectedListKey = listKey) }
    }

    fun onStartCreateList() {
        _uiState.update {
            it.copy(
                listEditorState = LibraryListEditorState(mode = LibraryListEditorState.Mode.CREATE),
                errorMessage = null
            )
        }
    }

    fun onStartEditList() {
        val selected = selectedManagePersonalList() ?: return
        _uiState.update {
            it.copy(
                listEditorState = LibraryListEditorState(
                    mode = LibraryListEditorState.Mode.EDIT,
                    listId = selected.traktListId?.toString(),
                    name = selected.title,
                    description = selected.description.orEmpty(),
                    privacy = selected.privacy ?: TraktListPrivacy.PRIVATE
                ),
                errorMessage = null
            )
        }
    }

    fun onUpdateEditorName(value: String) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(name = value))
        }
    }

    fun onUpdateEditorDescription(value: String) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(description = value))
        }
    }

    fun onUpdateEditorPrivacy(value: TraktListPrivacy) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(privacy = value))
        }
    }

    fun onCancelEditor() {
        _uiState.update { it.copy(listEditorState = null, errorMessage = null) }
    }

    fun onSubmitEditor() {
        val editor = _uiState.value.listEditorState ?: return
        val name = editor.name.trim()
        if (name.isBlank()) {
            setError("List name is required")
            return
        }
        if (_uiState.value.pendingOperation) return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                when (editor.mode) {
                    LibraryListEditorState.Mode.CREATE -> {
                        libraryRepository.createPersonalList(
                            name = name,
                            description = editor.description.trim().ifBlank { null },
                            privacy = editor.privacy
                        )
                        setTransientMessage("List created")
                    }
                    LibraryListEditorState.Mode.EDIT -> {
                        val listId = editor.listId
                            ?: throw IllegalStateException("Invalid list")
                        libraryRepository.updatePersonalList(
                            listId = listId,
                            name = name,
                            description = editor.description.trim().ifBlank { null },
                            privacy = editor.privacy
                        )
                        setTransientMessage("List updated")
                    }
                }
            }.onSuccess {
                _uiState.update { it.copy(listEditorState = null, pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Failed to save list")
            }
        }
    }

    fun onDeleteSelectedList() {
        val selected = selectedManagePersonalList() ?: return
        val listId = selected.traktListId?.toString() ?: return
        if (_uiState.value.pendingOperation) return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                libraryRepository.deletePersonalList(listId)
                setTransientMessage("List deleted")
            }.onSuccess {
                _uiState.update { it.copy(pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Failed to delete list")
            }
        }
    }

    fun onMoveSelectedListUp() {
        reorderSelectedList(moveUp = true)
    }

    fun onMoveSelectedListDown() {
        reorderSelectedList(moveUp = false)
    }

    fun onClearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    private fun observeLibraryData() {
        viewModelScope.launch {
            combine(
                libraryRepository.sourceMode,
                libraryRepository.isSyncing,
                libraryRepository.libraryItems,
                libraryRepository.listTabs
            ) { sourceMode, isSyncing, items, listTabs ->
                DataBundle(
                    sourceMode = sourceMode,
                    isSyncing = isSyncing,
                    items = items,
                    listTabs = listTabs
                )
            }.collectLatest { (sourceMode, isSyncing, items, listTabs) ->
                _uiState.update { current ->
                    val nextSelectedList = when {
                        sourceMode == LibrarySourceMode.TRAKT -> {
                            current.selectedListKey
                                ?.takeIf { key -> listTabs.any { it.key == key } }
                                ?: listTabs.firstOrNull()?.key
                        }
                        else -> null
                    }

                    val nextManageSelected = current.manageSelectedListKey
                        ?.takeIf { key ->
                            listTabs.any { tab ->
                                tab.key == key && tab.type == LibraryListTab.Type.PERSONAL
                            }
                        }
                        ?: listTabs.firstOrNull { it.type == LibraryListTab.Type.PERSONAL }?.key

                    val itemsForTypeTabs = if (sourceMode == LibrarySourceMode.TRAKT) {
                        val listKey = nextSelectedList
                        if (listKey.isNullOrBlank()) items else items.filter { it.listKeys.contains(listKey) }
                    } else {
                        items
                    }
                    val typeTabs = buildTypeTabs(itemsForTypeTabs)
                    val nextSelectedType = current.selectedTypeTab
                        ?.takeIf { selected -> typeTabs.any { it.key == selected.key } }
                        ?: LibraryTypeTab.All
                    val sortOptions = if (sourceMode == LibrarySourceMode.TRAKT) {
                        LibrarySortOption.TraktOptions
                    } else {
                        LibrarySortOption.LocalOptions
                    }
                    val nextSelectedSort = current.selectedSortOption
                        .takeIf { it in sortOptions }
                        ?: if (sourceMode == LibrarySourceMode.TRAKT) LibrarySortOption.DEFAULT else LibrarySortOption.ADDED_DESC

                    val updated = current.copy(
                        sourceMode = sourceMode,
                        allItems = items,
                        listTabs = listTabs,
                        availableTypeTabs = typeTabs,
                        availableSortOptions = sortOptions,
                        selectedTypeTab = nextSelectedType,
                        selectedListKey = nextSelectedList,
                        selectedSortOption = nextSelectedSort,
                        manageSelectedListKey = nextManageSelected,
                        isSyncing = sourceMode == LibrarySourceMode.TRAKT && isSyncing,
                        isLoading = sourceMode == LibrarySourceMode.TRAKT &&
                            isSyncing &&
                            items.isEmpty() &&
                            listTabs.isEmpty()
                    )
                    updated.withVisibleItems()
                }
            }
        }
    }

    private var sortPreferenceLoaded = false

    private fun observeLayoutPreferences() {
        viewModelScope.launch {
            combine(
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp,
                layoutPreferenceDataStore.librarySortOption
            ) { widthDp, cornerRadiusDp, sortKey ->
                Triple(widthDp, cornerRadiusDp, sortKey)
            }.collectLatest { triple ->
                val widthDp = triple.first
                val cornerRadiusDp = triple.second
                val sortKey = triple.third
                val savedSort = sortKey?.let { LibrarySortOption.fromKey(it) }
                _uiState.update { current ->
                    val applySort = savedSort != null && !sortPreferenceLoaded
                    if (applySort) sortPreferenceLoaded = true
                    if (current.posterCardWidthDp == widthDp &&
                        current.posterCardCornerRadiusDp == cornerRadiusDp &&
                        !applySort
                    ) {
                        current
                    } else {
                        val nextSelectedSort = if (applySort) {
                            savedSort
                        } else {
                            current.selectedSortOption
                        }
                        val updated = current.copy(
                            posterCardWidthDp = widthDp,
                            posterCardCornerRadiusDp = cornerRadiusDp,
                            selectedSortOption = nextSelectedSort
                        )
                        if (applySort) updated.withVisibleItems() else updated
                    }
                }
            }
        }
    }

    private data class DataBundle(
        val sourceMode: LibrarySourceMode,
        val isSyncing: Boolean,
        val items: List<LibraryEntry>,
        val listTabs: List<LibraryListTab>
    )

    private fun reorderSelectedList(moveUp: Boolean) {
        val state = _uiState.value
        if (state.pendingOperation) return

        val personalTabs = state.listTabs.filter { it.type == LibraryListTab.Type.PERSONAL }
        val selectedKey = state.manageSelectedListKey ?: return
        val selectedIndex = personalTabs.indexOfFirst { it.key == selectedKey }
        if (selectedIndex < 0) return

        val targetIndex = if (moveUp) selectedIndex - 1 else selectedIndex + 1
        if (targetIndex !in personalTabs.indices) return

        val reordered = personalTabs.toMutableList().apply {
            add(targetIndex, removeAt(selectedIndex))
        }
        val orderedIds = reordered.mapNotNull { tab ->
            tab.traktListId?.toString() ?: tab.key.removePrefix(TraktLibraryService.PERSONAL_KEY_PREFIX)
        }

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                libraryRepository.reorderPersonalLists(orderedIds)
                setTransientMessage("List order updated")
            }.onSuccess {
                _uiState.update { it.copy(pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Failed to reorder lists")
            }
        }
    }

    private fun selectedManagePersonalList(): LibraryListTab? {
        val state = _uiState.value
        val selectedKey = state.manageSelectedListKey ?: return null
        return state.listTabs.firstOrNull { it.key == selectedKey && it.type == LibraryListTab.Type.PERSONAL }
    }

    private fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message, transientMessage = message) }
        messageClearJob?.cancel()
        messageClearJob = viewModelScope.launch {
            delay(2800)
            _uiState.update { it.copy(transientMessage = null) }
        }
    }

    private fun setTransientMessage(message: String) {
        _uiState.update { it.copy(transientMessage = message, errorMessage = null) }
        messageClearJob?.cancel()
        messageClearJob = viewModelScope.launch {
            delay(2200)
            _uiState.update { it.copy(transientMessage = null) }
        }
    }

    private fun buildTypeTabs(items: List<LibraryEntry>): List<LibraryTypeTab> {
        val byKey = linkedMapOf<String, LibraryTypeTab>()
        items.forEach { entry ->
            val key = entry.type.trim().ifBlank { "unknown" }.lowercase(Locale.ROOT)
            if (byKey.containsKey(key)) return@forEach
            byKey[key] = LibraryTypeTab(
                key = key,
                label = prettifyTypeLabel(key)
            )
        }
        return listOf(LibraryTypeTab.All) + byKey.values
    }

    private fun prettifyTypeLabel(key: String): String {
        return key
            .replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
                }
            }
            .ifBlank { "Unknown" }
    }

    private fun buildGroupedRows(
        visibleItems: List<LibraryEntry>,
        sourceMode: LibrarySourceMode,
        selectedSortOption: LibrarySortOption
    ): List<LibraryRowGroup> {
        if (visibleItems.isEmpty()) return emptyList()
        if (selectedSortOption == LibrarySortOption.RANDOM) {
            return buildRandomGroupedRows(visibleItems)
        }
        if (visibleItems.size < MIN_DISCOVERY_STYLE_LIBRARY_ITEMS) {
            return buildBasicGroupedRows(visibleItems)
        }

        val rows = mutableListOf<LibraryRowGroup>()
        val seenRowKeys = linkedSetOf<String>()
        val seenLeadSignatures = linkedSetOf<String>()
        val indexByKey = visibleItems.mapIndexed { index, entry -> libraryEntryContentKey(entry) to index }.toMap()
        val currentYear = java.time.LocalDate.now().year
        val movies = visibleItems.filter { it.type.equals("movie", ignoreCase = true) }
        val series = visibleItems.filter { it.type.equals("series", ignoreCase = true) }

        fun addRow(
            key: String,
            title: String,
            items: List<LibraryEntry>,
            subtitle: String? = null,
            minItems: Int = MIN_LIBRARY_SUPPORTING_ROW_ITEMS
        ) {
            val deduped = items
                .distinctBy(::libraryEntryContentKey)
                .take(MAX_LIBRARY_ROW_ITEMS)
            if (deduped.size < minItems) return
            if (!seenRowKeys.add(key)) return
            val leadSignature = deduped
                .take(10)
                .joinToString("|", transform = ::libraryEntryContentKey)
            if (!seenLeadSignatures.add(leadSignature)) return
            rows += LibraryRowGroup(
                key = key,
                title = title,
                subtitle = subtitle,
                items = deduped
            )
        }

        addRow(
            key = "primary:${selectedSortOption.key}",
            title = primaryLibraryRowTitle(sourceMode, selectedSortOption),
            subtitle = primaryLibraryRowSubtitle(selectedSortOption),
            items = visibleItems,
            minItems = 1
        )

        if (movies.isNotEmpty() && series.isNotEmpty()) {
            addRow(
                key = "movies",
                title = "Movies",
                subtitle = "Movie picks from your library",
                items = movies
            )
            addRow(
                key = "series",
                title = "TV Shows",
                subtitle = "Series picks from your library",
                items = series
            )
        }

        if (movies.size >= MIN_LIBRARY_SUPPORTING_ROW_ITEMS) {
            addRow(
                key = "top_rated_movies",
                title = "Top Rated Movies",
                subtitle = "Highest rated movies in your library",
                items = movies.sortedWith(
                    compareByDescending<LibraryEntry> { it.imdbRating ?: -1f }
                        .thenByDescending { it.releaseYear() ?: 0 }
                        .thenBy { indexByKey[libraryEntryContentKey(it)] ?: Int.MAX_VALUE }
                ).filter { it.imdbRating != null }
            )
            addRow(
                key = "newer_movies",
                title = "Newer Movies",
                subtitle = "Recent releases from your library",
                items = movies
                    .filter { entry -> (entry.releaseYear() ?: 0) >= currentYear - 5 }
                    .sortedWith(
                        compareByDescending<LibraryEntry> { it.releaseYear() ?: 0 }
                            .thenBy { indexByKey[libraryEntryContentKey(it)] ?: Int.MAX_VALUE }
                    )
            )
            addRow(
                key = "classic_movies",
                title = "Classic Movies",
                subtitle = "Older favorites from your library",
                items = movies
                    .filter { entry -> (entry.releaseYear() ?: Int.MAX_VALUE) <= 2009 }
                    .sortedWith(
                        compareByDescending<LibraryEntry> { it.imdbRating ?: -1f }
                            .thenBy { indexByKey[libraryEntryContentKey(it)] ?: Int.MAX_VALUE }
                    )
            )
        }

        if (series.size >= MIN_LIBRARY_SUPPORTING_ROW_ITEMS) {
            addRow(
                key = "top_rated_series",
                title = "Top Rated Shows",
                subtitle = "Highest rated shows in your library",
                items = series.sortedWith(
                    compareByDescending<LibraryEntry> { it.imdbRating ?: -1f }
                        .thenByDescending { it.releaseYear() ?: 0 }
                        .thenBy { indexByKey[libraryEntryContentKey(it)] ?: Int.MAX_VALUE }
                ).filter { it.imdbRating != null }
            )
            addRow(
                key = "recent_series",
                title = "Recent Shows",
                subtitle = "Newer series from your library",
                items = series
                    .filter { entry -> (entry.releaseYear() ?: 0) >= currentYear - 5 }
                    .sortedWith(
                        compareByDescending<LibraryEntry> { it.releaseYear() ?: 0 }
                            .thenBy { indexByKey[libraryEntryContentKey(it)] ?: Int.MAX_VALUE }
                    )
            )
            addRow(
                key = "binge_worthy_series",
                title = "Binge-Worthy Shows",
                subtitle = "Well-rated series from your library",
                items = series
                    .filter { entry -> (entry.imdbRating ?: 0f) >= 7.5f }
                    .sortedWith(
                        compareByDescending<LibraryEntry> { it.imdbRating ?: -1f }
                            .thenByDescending { it.releaseYear() ?: 0 }
                            .thenBy { indexByKey[libraryEntryContentKey(it)] ?: Int.MAX_VALUE }
                    )
            )
        }

        buildGenreBuckets(movies)
            .take(MAX_LIBRARY_MOVIE_GENRE_ROWS)
            .forEach { (genreName, genreItems) ->
                addRow(
                    key = "movie_genre:${genreName.lowercase(Locale.ROOT)}",
                    title = "$genreName Movies",
                    subtitle = "Movie picks from your library",
                    items = genreItems
                )
            }

        buildGenreBuckets(series)
            .take(MAX_LIBRARY_SHOW_GENRE_ROWS)
            .forEach { (genreName, genreItems) ->
                addRow(
                    key = "series_genre:${genreName.lowercase(Locale.ROOT)}",
                    title = "$genreName Shows",
                    subtitle = "Series picks from your library",
                    items = genreItems
                )
            }

        return rows.ifEmpty { buildBasicGroupedRows(visibleItems) }
    }

    private fun buildRandomGroupedRows(visibleItems: List<LibraryEntry>): List<LibraryRowGroup> {
        val rows = mutableListOf<LibraryRowGroup>()
        rows += LibraryRowGroup(
            key = "primary:random",
            title = "Shuffle Picks",
            subtitle = "A stable random mix from your library",
            items = visibleItems.take(MAX_LIBRARY_ROW_ITEMS)
        )

        val movies = visibleItems.filter { it.type.equals("movie", ignoreCase = true) }
        if (movies.size >= MIN_LIBRARY_SUPPORTING_ROW_ITEMS) {
            rows += LibraryRowGroup(
                key = "movies",
                title = "Movies",
                subtitle = "Movie picks from your library",
                items = movies.take(MAX_LIBRARY_ROW_ITEMS)
            )
        }

        val series = visibleItems.filter { it.type.equals("series", ignoreCase = true) }
        if (series.size >= MIN_LIBRARY_SUPPORTING_ROW_ITEMS) {
            rows += LibraryRowGroup(
                key = "series",
                title = "TV Shows",
                subtitle = "Series picks from your library",
                items = series.take(MAX_LIBRARY_ROW_ITEMS)
            )
        }

        val other = visibleItems.filter {
            !it.type.equals("movie", ignoreCase = true) && !it.type.equals("series", ignoreCase = true)
        }
        if (other.size >= MIN_LIBRARY_SUPPORTING_ROW_ITEMS) {
            rows += LibraryRowGroup(
                key = "other",
                title = "Other",
                subtitle = "Other picks from your library",
                items = other.take(MAX_LIBRARY_ROW_ITEMS)
            )
        }
        return rows
    }

    private fun buildBasicGroupedRows(visibleItems: List<LibraryEntry>): List<LibraryRowGroup> {
        if (visibleItems.isEmpty()) return emptyList()
        val groups = mutableListOf<LibraryRowGroup>()
        val movies = visibleItems.filter { it.type.equals("movie", ignoreCase = true) }
        val series = visibleItems.filter { it.type.equals("series", ignoreCase = true) }
        val other = visibleItems.filter {
            !it.type.equals("movie", ignoreCase = true) && !it.type.equals("series", ignoreCase = true)
        }
        if (movies.isNotEmpty()) groups.add(LibraryRowGroup(key = "movies", title = "Movies", items = movies))
        if (series.isNotEmpty()) groups.add(LibraryRowGroup(key = "series", title = "TV Shows", items = series))
        if (other.isNotEmpty()) groups.add(LibraryRowGroup(key = "other", title = "Other", items = other))
        if (groups.size == 1) {
            return listOf(LibraryRowGroup(key = "library", title = "Library", items = visibleItems))
        }
        return groups
    }

    private fun buildGenreBuckets(visibleItems: List<LibraryEntry>): List<Pair<String, List<LibraryEntry>>> {
        val buckets = linkedMapOf<String, Pair<String, MutableList<LibraryEntry>>>()
        visibleItems.forEach { entry ->
            entry.genres
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .forEach { genre ->
                    val key = genre.lowercase(Locale.ROOT)
                    val bucket = buckets.getOrPut(key) { genre to mutableListOf() }
                    bucket.second += entry
                }
        }
        return buckets.values
            .map { (displayName, items) -> displayName to items.toList() }
            .filter { (_, items) -> items.size >= MIN_LIBRARY_SUPPORTING_ROW_ITEMS }
            .sortedWith(
                compareByDescending<Pair<String, List<LibraryEntry>>> { it.second.size }
                    .thenBy { it.first.lowercase(Locale.ROOT) }
            )
    }

    private fun primaryLibraryRowTitle(
        sourceMode: LibrarySourceMode,
        selectedSortOption: LibrarySortOption
    ): String {
        return when (selectedSortOption) {
            LibrarySortOption.DEFAULT -> if (sourceMode == LibrarySourceMode.TRAKT) "In Trakt Order" else "Library"
            LibrarySortOption.ADDED_DESC -> "Recently Added"
            LibrarySortOption.ADDED_ASC -> "Oldest Added"
            LibrarySortOption.TITLE_ASC -> "A to Z"
            LibrarySortOption.TITLE_DESC -> "Z to A"
            LibrarySortOption.YEAR_ASC -> "Older Releases"
            LibrarySortOption.YEAR_DESC -> "Newest Releases"
            LibrarySortOption.RANDOM -> "Shuffle Picks"
        }
    }

    private fun primaryLibraryRowSubtitle(selectedSortOption: LibrarySortOption): String? {
        return when (selectedSortOption) {
            LibrarySortOption.DEFAULT -> "All saved titles"
            LibrarySortOption.ADDED_DESC -> "Newest saves first"
            LibrarySortOption.ADDED_ASC -> "Oldest saves first"
            LibrarySortOption.TITLE_ASC -> "Alphabetical"
            LibrarySortOption.TITLE_DESC -> "Reverse alphabetical"
            LibrarySortOption.YEAR_ASC -> "Sorted by release year"
            LibrarySortOption.YEAR_DESC -> "Sorted by release year"
            LibrarySortOption.RANDOM -> "A fresh mix from your library"
        }
    }

    private fun libraryEntryContentKey(entry: LibraryEntry): String {
        return "${entry.type.lowercase(Locale.ROOT)}:${entry.id}"
    }

    private fun LibraryEntry.releaseYear(): Int? {
        return releaseInfo?.take(4)?.toIntOrNull()
    }

    private fun stableShuffleIndex(contentKey: String, seed: Long): Int {
        val keyHash = contentKey.hashCode().toLong()
        return (keyHash xor (seed * 1103515245L)).absoluteValue.toInt()
    }

    private fun LibraryUiState.withVisibleItems(): LibraryUiState {
        val selectedTypeKey = selectedTypeTab?.key
        val typeFiltered = allItems.filter { entry ->
            selectedTypeKey == null ||
                selectedTypeKey == LibraryTypeTab.ALL_KEY ||
                entry.type.trim().lowercase(Locale.ROOT) == selectedTypeKey
        }

        val listFiltered = if (sourceMode == LibrarySourceMode.TRAKT) {
            val listKey = selectedListKey ?: ""
            typeFiltered.filter { entry -> entry.listKeys.contains(listKey) }
        } else {
            typeFiltered
        }

        val sorted = when (selectedSortOption) {
            LibrarySortOption.DEFAULT -> if (sourceMode == LibrarySourceMode.TRAKT) {
                listFiltered.sortedWith(
                    compareBy<LibraryEntry> { it.traktRank ?: Int.MAX_VALUE }
                        .thenByDescending { it.listedAt }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                        .thenBy { it.id }
                )
            } else {
                listFiltered
            }
            LibrarySortOption.ADDED_DESC -> listFiltered.sortedWith(
                compareByDescending<LibraryEntry> { it.listedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.ADDED_ASC -> listFiltered.sortedWith(
                compareBy<LibraryEntry> { it.listedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.TITLE_ASC -> listFiltered.sortedWith(
                compareBy<LibraryEntry> { it.name.ifBlank { it.id }.lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )
            LibrarySortOption.TITLE_DESC -> listFiltered.sortedWith(
                compareByDescending<LibraryEntry> { it.name.ifBlank { it.id }.lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )
            LibrarySortOption.YEAR_ASC -> listFiltered.sortedWith(
                compareBy<LibraryEntry> { it.releaseInfo?.take(4)?.toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.YEAR_DESC -> listFiltered.sortedWith(
                compareByDescending<LibraryEntry> { it.releaseInfo?.take(4)?.toIntOrNull() ?: 0 }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.RANDOM -> {
                val incomingByKey = listFiltered.associateBy(::libraryEntryContentKey)
                val incomingKeys = listFiltered.map(::libraryEntryContentKey)
                val incomingKeySet = incomingKeys.toHashSet()
                val preservedKeys = if (appliedRandomSortVersion == sortSelectionVersion) {
                    randomOrderKeys.filter { it in incomingKeySet }
                } else {
                    emptyList()
                }
                val preservedKeySet = preservedKeys.toHashSet()
                val newKeys = incomingKeys
                    .filterNot { it in preservedKeySet }
                    .sortedWith(
                        compareBy<String> { stableShuffleIndex(it, sortSelectionVersion) }
                            .thenBy { it }
                    )
                val orderedKeys = preservedKeys + newKeys
                orderedKeys.mapNotNull(incomingByKey::get)
            }
        }

        return copy(
            visibleItems = sorted,
            appliedRandomSortVersion = if (selectedSortOption == LibrarySortOption.RANDOM) {
                sortSelectionVersion
            } else {
                -1L
            },
            randomOrderKeys = if (selectedSortOption == LibrarySortOption.RANDOM) {
                sorted.map(::libraryEntryContentKey)
            } else {
                emptyList()
            },
            groupedRows = buildGroupedRows(
                visibleItems = sorted,
                sourceMode = sourceMode,
                selectedSortOption = selectedSortOption
            )
        )
    }
}
