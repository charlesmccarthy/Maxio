package com.nuvio.tv.ui.screens.cast

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
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
import javax.inject.Inject

@HiltViewModel
class CastDetailViewModel @Inject constructor(
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbService: TmdbService,
    private val tmdbApi: TmdbApi,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val trailerService: TrailerService,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val personId: Int = savedStateHandle.get<String>("personId")?.toIntOrNull() ?: 0
    val personName: String = java.net.URLDecoder.decode(
        savedStateHandle.get<String>("personName") ?: "", "UTF-8"
    )
    private val preferCrew: Boolean = savedStateHandle.get<Boolean>("preferCrew") ?: false

    private val _uiState = MutableStateFlow<CastDetailUiState>(CastDetailUiState.Loading)
    val uiState: StateFlow<CastDetailUiState> = _uiState.asStateFlow()
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

    init {
        loadPersonDetail()
        observeTrailerPrefs()
    }

    fun retry() {
        _uiState.value = CastDetailUiState.Loading
        loadPersonDetail()
    }

    private fun loadPersonDetail() {
        viewModelScope.launch {
            try {
                val detail = tmdbMetadataService.fetchPersonDetail(
                    personId = personId,
                    preferCrewCredits = preferCrew,
                    language = tmdbSettingsDataStore.settings.first().language
                )
                if (detail != null) {
                    _uiState.value = CastDetailUiState.Success(detail)
                } else {
                    _uiState.value = CastDetailUiState.Error("Could not load details for $personName")
                }
            } catch (e: Exception) {
                _uiState.value = CastDetailUiState.Error(e.message ?: "Unknown error")
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

    fun requestTrailerPreview(item: MetaPreview) {
        val itemId = item.id
        if (trailerNegativeCache.contains(itemId)) return
        if (trailerPreviewUrls.containsKey(itemId)) return
        if (!trailerLoadingIds.add(itemId)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tmdbId = runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
                val yearStr = item.releaseInfo?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value }
                val source = trailerService.getTrailerPlaybackSource(
                    title = item.name,
                    year = yearStr,
                    tmdbId = tmdbId,
                    type = item.apiType
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
        if (!item.logo.isNullOrBlank()) return
        if (logoUrls.containsKey(itemId)) return
        if (logoNegativeCache.contains(itemId)) return
        if (!logoLoadingIds.add(itemId)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tmdbId = runCatching { tmdbService.ensureTmdbId(itemId, item.apiType) }.getOrNull()
                    ?.toIntOrNull()
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
}
