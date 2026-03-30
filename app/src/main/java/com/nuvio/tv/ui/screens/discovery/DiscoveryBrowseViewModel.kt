package com.nuvio.tv.ui.screens.discovery

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.util.tmdbImageUrl
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.trailer.ActiveTrailerState
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "DiscoveryBrowseVM"
private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY

data class BrowseRow(
    val title: String,
    val items: List<MetaPreview>
)

data class DiscoveryBrowseUiState(
    val browseType: String = "",
    val browseValue: String = "",
    val browseName: String = "",
    val contentType: String = "movie",
    val rows: List<BrowseRow> = emptyList(),
    // Keep flat items for backward compat
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
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val trailerService: TrailerService,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val activeTrailerState: ActiveTrailerState
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoveryBrowseUiState())
    val uiState: StateFlow<DiscoveryBrowseUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    // Trailer preview support
    val trailerPreviewUrls = mutableStateMapOf<String, String>()
    val trailerPreviewAudioUrls = mutableStateMapOf<String, String>()
    private val trailerNegativeCache = mutableSetOf<String>()
    private val trailerLoadingIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    var trailerEnabled: Boolean = false
        private set
    var trailerMuted: Boolean = true
        private set

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

    // Logo URL support
    val logoUrls = mutableStateMapOf<String, String>()
    private val logoNegativeCache = mutableSetOf<String>()
    private val logoLoadingIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

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
        loadCuratedRows()
        observeTrailerPrefs()
    }

    private fun observeTrailerPrefs() {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
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
        if (logoUrls.containsKey(itemId)) return
        if (logoNegativeCache.contains(itemId)) return
        if (!logoLoadingIds.add(itemId)) return

        val tmdbId = itemId.removePrefix("tmdb:").toIntOrNull()
        if (tmdbId == null) {
            logoLoadingIds.remove(itemId)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val isMovie = _uiState.value.contentType == "movie"
                val response = if (isMovie) {
                    tmdbApi.getMovieImages(movieId = tmdbId, apiKey = TMDB_API_KEY)
                } else {
                    tmdbApi.getTvImages(tvId = tmdbId, apiKey = TMDB_API_KEY)
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

    fun loadNextPage() {
        // No-op — curated rows load everything upfront
    }

    fun onRetry() {
        loadCuratedRows()
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

    private fun loadCuratedRows() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, error = null, rows = emptyList()) }

            try {
                val state = _uiState.value
                val language = tmdbSettingsDataStore.settings.first().language.takeIf { it.isNotBlank() }
                val isMovie = state.contentType == "movie"

                val rows = when (state.browseType) {
                    "genre" -> fetchGenreCuratedRows(isMovie, state.browseValue, language)
                    "decade" -> fetchDecadeCuratedRows(isMovie, state.browseValue, language)
                    else -> emptyList()
                }

                _uiState.update {
                    it.copy(
                        rows = rows.filter { row -> row.items.size >= 5 },
                        isLoading = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load curated rows", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load content"
                    )
                }
            }
        }
    }

    private suspend fun fetchGenreCuratedRows(
        isMovie: Boolean,
        genreId: String,
        language: String?
    ): List<BrowseRow> = coroutineScope {
        val mediaType = if (isMovie) "movie" else "series"
        val currentYear = java.time.Year.now().value

        // Parallel fetch all curated categories — 5 pages each for deep results
        val popularDeferred = async { fetchGenreDiscover(isMovie, genreId, language, "popularity.desc", voteCountGte = 50, pages = 5) }
        val highestRatedDeferred = async { fetchGenreDiscover(isMovie, genreId, language, "vote_average.desc", voteCountGte = 300, voteAverageGte = 7.0, pages = 5) }
        val newReleasesDeferred = async {
            fetchGenreDiscover(
                isMovie, genreId, language, if (isMovie) "primary_release_date.desc" else "first_air_date.desc",
                voteCountGte = 20,
                releaseDateGte = "${currentYear - 2}-01-01",
                pages = 3
            )
        }
        val classicDeferred = async {
            fetchGenreDiscover(
                isMovie, genreId, language, "vote_average.desc",
                voteCountGte = 500,
                releaseDateLte = "2005-12-31",
                pages = 5
            )
        }
        val hiddenGemsDeferred = async {
            fetchGenreDiscover(
                isMovie, genreId, language, "vote_average.desc",
                voteCountGte = 50,
                voteAverageGte = 7.5,
                pages = 3
            )
        }

        val popular = popularDeferred.await()
        val highestRated = highestRatedDeferred.await()
        val newReleases = newReleasesDeferred.await()
        val classics = classicDeferred.await()
        val hiddenGems = hiddenGemsDeferred.await()

        // Deduplicate across rows — each item only appears in its first row
        val seen = mutableSetOf<String>()
        fun dedup(items: List<MetaPreview>): List<MetaPreview> {
            return items.filter { seen.add(it.id) }
        }

        listOf(
            BrowseRow("Most Popular", dedup(popular)),
            BrowseRow("New Releases", dedup(newReleases)),
            BrowseRow("Highest Rated", dedup(highestRated)),
            BrowseRow("Hidden Gems", dedup(hiddenGems)),
            BrowseRow("Classic Favorites", dedup(classics))
        )
    }

    private suspend fun fetchDecadeCuratedRows(
        isMovie: Boolean,
        startYear: String,
        language: String?
    ): List<BrowseRow> = coroutineScope {
        val startYearInt = startYear.toInt()
        val endYear = (startYearInt + 9).toString()
        val midYear = startYearInt + 5

        // Fetch genres + curated rows in parallel
        val genresDeferred = async {
            try {
                val response = if (isMovie) {
                    tmdbApi.getMovieGenres(apiKey = TMDB_API_KEY, language = language)
                } else {
                    tmdbApi.getTvGenres(apiKey = TMDB_API_KEY, language = language)
                }
                response.body()?.genres.orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
        }
        val bestDeferred = async {
            fetchDecadeDiscover(isMovie, startYear, endYear, language, "vote_average.desc", voteCountGte = 300)
        }
        val popularDeferred = async {
            fetchDecadeDiscover(isMovie, startYear, endYear, language, "popularity.desc", voteCountGte = 50)
        }
        val earlyDeferred = async {
            fetchDecadeDiscover(isMovie, startYear, "${midYear - 1}", language, "vote_average.desc", voteCountGte = 100)
        }
        val lateDeferred = async {
            fetchDecadeDiscover(isMovie, "$midYear", endYear, language, "vote_average.desc", voteCountGte = 100)
        }

        val best = bestDeferred.await()
        val popular = popularDeferred.await()
        val early = earlyDeferred.await()
        val late = lateDeferred.await()
        val genres = genresDeferred.await()

        // Fetch genre rows in parallel — 5 pages each for ~100 items before dedup
        val genreRows = genres.map { genre ->
            async {
                try {
                    val items = fetchDecadeDiscover(
                        isMovie, startYear, endYear, language,
                        "popularity.desc", voteCountGte = 10, genreId = genre.id.toString(),
                        pages = 5
                    )
                    BrowseRow(genre.name, items)
                } catch (_: Exception) {
                    BrowseRow(genre.name, emptyList())
                }
            }
        }.awaitAll()

        val seen = mutableSetOf<String>()
        fun dedup(items: List<MetaPreview>): List<MetaPreview> {
            return items.filter { seen.add(it.id) }
        }

        val rows = mutableListOf(
            BrowseRow("Best of the ${startYear}s", dedup(best)),
            BrowseRow("Most Popular", dedup(popular)),
            BrowseRow("Early ${startYear}s (${startYear}–${midYear - 1})", dedup(early)),
            BrowseRow("Late ${startYear}s (${midYear}–${endYear})", dedup(late))
        )

        // Add genre rows after the curated rows
        for (row in genreRows) {
            val dedupedItems = dedup(row.items)
            if (dedupedItems.isNotEmpty()) {
                rows.add(BrowseRow(row.title, dedupedItems))
            }
        }

        rows
    }

    private suspend fun fetchGenreDiscover(
        isMovie: Boolean,
        genreId: String,
        language: String?,
        sortBy: String,
        voteCountGte: Int = 50,
        voteAverageGte: Double? = null,
        releaseDateGte: String? = null,
        releaseDateLte: String? = null,
        pages: Int = 1
    ): List<MetaPreview> = coroutineScope {
        val mediaType = if (isMovie) "movie" else "series"
        (1..pages).map { page ->
            async {
                try {
                    val response = if (isMovie) {
                        tmdbApi.discoverMovies(
                            apiKey = TMDB_API_KEY,
                            language = language,
                            page = page,
                            sortBy = sortBy,
                            withGenres = genreId,
                            voteCountGte = voteCountGte,
                            voteAverageGte = voteAverageGte,
                            primaryReleaseDateGte = releaseDateGte,
                            primaryReleaseDateLte = releaseDateLte
                        )
                    } else {
                        tmdbApi.discoverTv(
                            apiKey = TMDB_API_KEY,
                            language = language,
                            page = page,
                            sortBy = sortBy,
                            withGenres = genreId,
                            voteCountGte = voteCountGte,
                            voteAverageGte = voteAverageGte,
                            firstAirDateGte = releaseDateGte,
                            firstAirDateLte = releaseDateLte
                        )
                    }
                    response.body()?.results.orEmpty().map { it.toBrowseMetaPreview(mediaType) }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten().distinctBy { it.id }
    }

    private suspend fun fetchDecadeDiscover(
        isMovie: Boolean,
        startYear: String,
        endYear: String,
        language: String?,
        sortBy: String,
        voteCountGte: Int = 100,
        genreId: String? = null,
        pages: Int = 1
    ): List<MetaPreview> = coroutineScope {
        val mediaType = if (isMovie) "movie" else "series"
        (1..pages).map { page ->
            async {
                try {
                    val response = if (isMovie) {
                        tmdbApi.discoverMovies(
                            apiKey = TMDB_API_KEY,
                            language = language,
                            page = page,
                            sortBy = sortBy,
                            primaryReleaseDateGte = "$startYear-01-01",
                            primaryReleaseDateLte = "$endYear-12-31",
                            voteCountGte = voteCountGte,
                            withGenres = genreId
                        )
                    } else {
                        tmdbApi.discoverTv(
                            apiKey = TMDB_API_KEY,
                            language = language,
                            page = page,
                            sortBy = sortBy,
                            firstAirDateGte = "$startYear-01-01",
                            firstAirDateLte = "$endYear-12-31",
                            voteCountGte = voteCountGte,
                            withGenres = genreId
                        )
                    }
                    response.body()?.results.orEmpty().map { it.toBrowseMetaPreview(mediaType) }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten().distinctBy { it.id }
    }
}

private fun TmdbDiscoverResult.toBrowseMetaPreview(mediaType: String): MetaPreview {
    val isMovie = mediaType == "movie"
    val posterUrl = tmdbBrowsePosterUrl(posterPath, backdropPath)
    val backdropUrl = tmdbBrowseBackdropUrl(backdropPath, posterPath)
    return MetaPreview(
        id = "tmdb:$id",
        type = if (isMovie) ContentType.MOVIE else ContentType.SERIES,
        rawType = mediaType,
        name = title ?: name ?: "",
        poster = posterUrl,
        posterShape = PosterShape.POSTER,
        background = backdropUrl,
        landscapePoster = backdropUrl,
        logo = null,
        description = overview,
        releaseInfo = releaseDate ?: firstAirDate,
        imdbRating = voteAverage?.toFloat(),
        genres = emptyList()
    )
}

private fun tmdbBrowsePosterUrl(posterPath: String?, backdropPath: String?): String? {
    return tmdbImageUrl(posterPath, "w500") ?: tmdbImageUrl(backdropPath, "w780")
}

private fun tmdbBrowseBackdropUrl(backdropPath: String?, posterPath: String?): String? {
    return tmdbImageUrl(backdropPath, "w1280") ?: tmdbBrowsePosterUrl(posterPath, backdropPath)
}
