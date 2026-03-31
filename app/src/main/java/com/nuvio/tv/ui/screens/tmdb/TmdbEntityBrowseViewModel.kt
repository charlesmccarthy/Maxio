package com.nuvio.tv.ui.screens.tmdb

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbEntityBrowseData
import com.nuvio.tv.core.tmdb.TmdbEntityKind
import com.nuvio.tv.core.tmdb.TmdbEntityMediaType
import com.nuvio.tv.core.tmdb.TmdbEntityRail
import com.nuvio.tv.core.tmdb.TmdbEntityRailType
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.trailer.ActiveTrailerState
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.MetaPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.URLDecoder
import javax.inject.Inject

@HiltViewModel
class TmdbEntityBrowseViewModel @Inject constructor(
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tmdbService: TmdbService,
    private val tmdbApi: TmdbApi,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val trailerService: TrailerService,
    private val activeTrailerState: ActiveTrailerState,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val inFlightRailLoads = mutableSetOf<String>()

    val entityKind: TmdbEntityKind = TmdbEntityKind.fromRouteValue(
        savedStateHandle.get<String>("entityKind").orEmpty()
    )
    val entityId: Int = savedStateHandle.get<Int>("entityId") ?: 0
    val entityName: String = URLDecoder.decode(
        savedStateHandle.get<String>("entityName").orEmpty(),
        "UTF-8"
    )
    val sourceType: String = savedStateHandle.get<String>("sourceType").orEmpty()

    private val _uiState = MutableStateFlow<TmdbEntityBrowseUiState>(TmdbEntityBrowseUiState.Loading)
    val uiState: StateFlow<TmdbEntityBrowseUiState> = _uiState.asStateFlow()

    val trailerPreviewUrls = mutableStateMapOf<String, String>()
    val trailerPreviewAudioUrls = mutableStateMapOf<String, String>()
    val logoUrls = mutableStateMapOf<String, String>()

    private val trailerNegativeCache = mutableSetOf<String>()
    private val trailerLoadingIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val logoNegativeCache = mutableSetOf<String>()
    private val logoLoadingIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    var trailerEnabled: Boolean = false
        private set
    var trailerMuted: Boolean = true
        private set

    private var lastTrailerItemId: String? = null
    private var lastTrailerPositionMs: Long = 0L

    init {
        load()
        observeTrailerPrefs()
    }

    fun retry() {
        _uiState.value = TmdbEntityBrowseUiState.Loading
        load()
    }

    fun onTrailerProgressChanged(itemId: String, positionMs: Long) {
        lastTrailerItemId = itemId
        lastTrailerPositionMs = positionMs
    }

    fun storeActiveTrailer(item: MetaPreview) {
        val videoUrl = trailerPreviewUrls[item.id] ?: return
        activeTrailerState.store(
            item.id,
            videoUrl,
            trailerPreviewAudioUrls[item.id],
            if (lastTrailerItemId == item.id) lastTrailerPositionMs else 0L
        )
    }

    fun requestTrailerPreview(item: MetaPreview) {
        val itemId = item.id
        if (trailerNegativeCache.contains(itemId)) return
        if (trailerPreviewUrls.containsKey(itemId)) return
        if (!trailerLoadingIds.add(itemId)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tmdbId = runCatching {
                    item.id.removePrefix("tmdb:")
                        .takeIf { it != item.id }
                        ?: tmdbService.ensureTmdbId(item.id, item.apiType)
                }.getOrNull()
                val yearStr = item.releaseInfo?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value }
                val source = trailerService.getTrailerPlaybackSource(
                    title = item.name,
                    year = yearStr,
                    tmdbId = tmdbId,
                    type = item.apiType
                )
                if (source?.videoUrl != null) {
                    trailerPreviewUrls[itemId] = source.videoUrl
                    source.audioUrl
                        ?.takeIf { it.isNotBlank() }
                        ?.let { trailerPreviewAudioUrls[itemId] = it }
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
        if (!item.logo.isNullOrBlank()) return
        if (logoUrls.containsKey(itemId)) return
        if (logoNegativeCache.contains(itemId)) return
        if (!logoLoadingIds.add(itemId)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tmdbId = item.id.removePrefix("tmdb:").toIntOrNull()
                    ?: runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
                        ?.toIntOrNull()
                if (tmdbId == null) {
                    logoNegativeCache.add(itemId)
                    return@launch
                }
                val response = if (item.apiType.equals("movie", ignoreCase = true)) {
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

    fun loadMoreRail(mediaType: TmdbEntityMediaType, railType: TmdbEntityRailType) {
        val railKey = "${mediaType.value}_${railType.value}"
        val currentSuccess = _uiState.value as? TmdbEntityBrowseUiState.Success ?: return
        val targetRail = currentSuccess.data.rails.firstOrNull {
            it.mediaType == mediaType && it.railType == railType
        } ?: return
        if (!targetRail.hasMore || targetRail.isLoading || !inFlightRailLoads.add(railKey)) return

        _uiState.value = TmdbEntityBrowseUiState.Success(
            currentSuccess.data.withUpdatedRail(mediaType, railType) { it.copy(isLoading = true) }
        )

        viewModelScope.launch {
            try {
                val latestData = (_uiState.value as? TmdbEntityBrowseUiState.Success)?.data ?: return@launch
                val latestRail = latestData.rails.firstOrNull {
                    it.mediaType == mediaType && it.railType == railType
                } ?: return@launch
                val language = tmdbSettingsDataStore.settings.first().language
                val nextPage = latestRail.currentPage + 1
                val pageResult = tmdbMetadataService.fetchEntityRailPage(
                    entityKind = entityKind,
                    entityId = entityId,
                    mediaType = mediaType,
                    railType = railType,
                    language = language,
                    page = nextPage
                )
                val mergedItems = (latestRail.items + pageResult.items)
                    .distinctBy { it.id }

                _uiState.value = TmdbEntityBrowseUiState.Success(
                    latestData.withUpdatedRail(mediaType, railType) {
                        it.copy(
                            items = mergedItems,
                            currentPage = nextPage,
                            hasMore = pageResult.hasMore,
                            isLoading = false
                        )
                    }
                )
            } catch (_: Exception) {
                val fallback = (_uiState.value as? TmdbEntityBrowseUiState.Success)?.data ?: return@launch
                _uiState.value = TmdbEntityBrowseUiState.Success(
                    fallback.withUpdatedRail(mediaType, railType) { it.copy(isLoading = false) }
                )
            } finally {
                inFlightRailLoads.remove(railKey)
            }
        }
    }

    private fun observeTrailerPrefs() {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                layoutPreferenceDataStore.focusedPosterBackdropTrailerEnabled,
                layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted
            ) { enabled, muted -> enabled to muted }
                .collectLatest { (enabled, muted) ->
                    trailerEnabled = enabled
                    trailerMuted = muted
                }
        }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val language = tmdbSettingsDataStore.settings.first().language
                val browseData = tmdbMetadataService.fetchEntityBrowse(
                    entityKind = entityKind,
                    entityId = entityId,
                    sourceType = sourceType,
                    fallbackName = entityName,
                    language = language
                )
                _uiState.value = if (browseData != null) {
                    TmdbEntityBrowseUiState.Success(browseData)
                } else {
                    TmdbEntityBrowseUiState.Error(
                        if (entityName.isNotBlank()) {
                            "Could not load $entityName"
                        } else {
                            "Could not load TMDB entity"
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.value = TmdbEntityBrowseUiState.Error(
                    e.message ?: "Could not load TMDB entity"
                )
            }
        }
    }

    private fun TmdbEntityBrowseData.withUpdatedRail(
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        transform: (TmdbEntityRail) -> TmdbEntityRail
    ): TmdbEntityBrowseData {
        return copy(
            rails = rails.map { rail ->
                if (rail.mediaType == mediaType && rail.railType == railType) {
                    transform(rail)
                } else {
                    rail
                }
            }
        )
    }
}
