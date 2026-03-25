package com.nuvio.tv.ui.screens.discovery

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "DiscoveryBrowseVM"
private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY

data class DiscoveryBrowseUiState(
    val browseType: String = "",
    val browseValue: String = "",
    val browseName: String = "",
    val contentType: String = "movie",
    val items: List<MetaPreview> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val currentPage: Int = 1,
    val hasMorePages: Boolean = true,
    val isResolvingId: Boolean = false
)

@HiltViewModel
class DiscoveryBrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoveryBrowseUiState())
    val uiState: StateFlow<DiscoveryBrowseUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        val browseType = savedStateHandle.get<String>("browseType") ?: ""
        val browseValue = savedStateHandle.get<String>("browseValue") ?: ""
        val browseName = savedStateHandle.get<String>("browseName") ?: ""
        val contentType = savedStateHandle.get<String>("contentType") ?: "movie"

        _uiState.update {
            it.copy(
                browseType = browseType,
                browseValue = browseValue,
                browseName = browseName,
                contentType = contentType
            )
        }
        loadPage(1)
    }

    fun loadNextPage() {
        val state = _uiState.value
        if (state.isLoadingMore || !state.hasMorePages) return
        loadPage(state.currentPage + 1)
    }

    fun onRetry() {
        loadPage(1)
    }

    fun onItemClick(item: MetaPreview, onNavigate: (String, String) -> Unit) {
        val id = item.id
        if (id.startsWith("tmdb:")) {
            val tmdbId = id.removePrefix("tmdb:").toIntOrNull() ?: return
            resolveAndNavigate(tmdbId, onNavigate)
        } else {
            onNavigate(id, item.rawType)
        }
    }

    private fun resolveAndNavigate(tmdbId: Int, onNavigate: (String, String) -> Unit) {
        val mediaType = _uiState.value.contentType
        _uiState.update { it.copy(isResolvingId = true) }
        viewModelScope.launch {
            try {
                val imdbId = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    tmdbService.tmdbToImdb(tmdbId, mediaType)
                }
                val resolvedId = imdbId ?: "tmdb:$tmdbId"
                _uiState.update { it.copy(isResolvingId = false) }
                onNavigate(resolvedId, mediaType)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve TMDB ID $tmdbId", e)
                _uiState.update { it.copy(isResolvingId = false) }
                onNavigate("tmdb:$tmdbId", mediaType)
            }
        }
    }

    private fun loadPage(page: Int) {
        val isFirstPage = page == 1
        if (isFirstPage) loadJob?.cancel()
        if (!isFirstPage && (loadJob?.isActive == true)) return

        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                if (isFirstPage) it.copy(isLoading = true, error = null, items = emptyList())
                else it.copy(isLoadingMore = true)
            }

            try {
                val state = _uiState.value
                val language = tmdbSettingsDataStore.settings.first().language.takeIf { it.isNotBlank() }
                val isMovie = state.contentType == "movie"

                val response = when (state.browseType) {
                    "genre" -> fetchByGenre(isMovie, state.browseValue, language, page)
                    "decade" -> fetchByDecade(isMovie, state.browseValue, language, page)
                    else -> emptyList<MetaPreview>() to 0
                }

                val (results, totalPages) = response

                _uiState.update {
                    val existingIds = if (isFirstPage) emptySet() else it.items.map { item -> item.id }.toSet()
                    val deduped = results.filter { item -> item.id !in existingIds }
                    it.copy(
                        items = if (isFirstPage) deduped else it.items + deduped,
                        currentPage = page,
                        hasMorePages = page < totalPages,
                        isLoading = false,
                        isLoadingMore = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load browse page $page", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        error = e.message ?: "Failed to load content"
                    )
                }
            }
        }
    }

    private suspend fun fetchByGenre(
        isMovie: Boolean,
        genreId: String,
        language: String?,
        page: Int
    ): Pair<List<MetaPreview>, Int> {
        val mediaType = if (isMovie) "movie" else "series"
        val response = if (isMovie) {
            tmdbApi.discoverMovies(
                apiKey = TMDB_API_KEY,
                language = language,
                page = page,
                sortBy = "popularity.desc",
                withGenres = genreId,
                voteCountGte = 50
            )
        } else {
            tmdbApi.discoverTv(
                apiKey = TMDB_API_KEY,
                language = language,
                page = page,
                sortBy = "popularity.desc",
                withGenres = genreId,
                voteCountGte = 50
            )
        }
        val body = response.body()
        val results = body?.results.orEmpty().map { it.toBrowseMetaPreview(mediaType) }
        val totalPages = body?.totalPages ?: 0
        return results to totalPages
    }

    private suspend fun fetchByDecade(
        isMovie: Boolean,
        startYear: String,
        language: String?,
        page: Int
    ): Pair<List<MetaPreview>, Int> {
        val mediaType = if (isMovie) "movie" else "series"
        val endYear = (startYear.toInt() + 9).toString()
        val response = if (isMovie) {
            tmdbApi.discoverMovies(
                apiKey = TMDB_API_KEY,
                language = language,
                page = page,
                sortBy = "vote_average.desc",
                primaryReleaseDateGte = "$startYear-01-01",
                primaryReleaseDateLte = "$endYear-12-31",
                voteCountGte = 300
            )
        } else {
            tmdbApi.discoverTv(
                apiKey = TMDB_API_KEY,
                language = language,
                page = page,
                sortBy = "vote_average.desc",
                firstAirDateGte = "$startYear-01-01",
                firstAirDateLte = "$endYear-12-31",
                voteCountGte = 100
            )
        }
        val body = response.body()
        val results = body?.results.orEmpty().map { it.toBrowseMetaPreview(mediaType) }
        val totalPages = body?.totalPages ?: 0
        return results to totalPages
    }
}

private fun TmdbDiscoverResult.toBrowseMetaPreview(mediaType: String): MetaPreview {
    val isMovie = mediaType == "movie"
    return MetaPreview(
        id = "tmdb:$id",
        type = if (isMovie) ContentType.MOVIE else ContentType.SERIES,
        rawType = mediaType,
        name = title ?: name ?: "",
        poster = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
        posterShape = PosterShape.POSTER,
        background = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
        logo = null,
        description = overview,
        releaseInfo = releaseDate ?: firstAirDate,
        imdbRating = voteAverage?.toFloat(),
        genres = emptyList()
    )
}
