package com.nuvio.tv.ui.screens.discovery

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import androidx.compose.runtime.mutableStateMapOf
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbGenre
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.repository.TraktAuthService
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

private const val TAG = "DiscoveryViewModel"
private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY

private val DECADES = listOf(
    "1960" to "1960s",
    "1970" to "1970s",
    "1980" to "1980s",
    "1990" to "1990s",
    "2000" to "2000s",
    "2010" to "2010s",
    "2020" to "2020s"
)

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val tmdbService: TmdbService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val trailerService: TrailerService,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoveryUiState())
    val uiState: StateFlow<DiscoveryUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var surpriseMeJob: Job? = null

    // Trailer preview support
    val trailerPreviewUrls = mutableStateMapOf<String, String>()
    val trailerPreviewAudioUrls = mutableStateMapOf<String, String>()
    private val trailerNegativeCache = mutableSetOf<String>()
    private val trailerLoadingIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Logo URL support (TMDB items don't include logos in discover responses)
    val logoUrls = mutableStateMapOf<String, String>()
    private val logoNegativeCache = mutableSetOf<String>()
    private val logoLoadingIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    var trailerEnabled: Boolean = false
        private set
    var trailerMuted: Boolean = true
        private set

    init {
        loadContent()
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

    fun onEvent(event: DiscoveryEvent) {
        when (event) {
            is DiscoveryEvent.OnContentTypeChanged -> {
                _uiState.update { it.copy(contentType = event.contentType) }
                loadContent()
            }
            DiscoveryEvent.OnSurpriseMeNext -> loadSurpriseMe()
            is DiscoveryEvent.OnSurpriseMePlay -> {
                val tmdbId = _uiState.value.surpriseMe.tmdbId ?: return
                resolveAndNavigate(tmdbId, event.onNavigate)
            }
            is DiscoveryEvent.OnSurpriseMeDetails -> {
                val tmdbId = _uiState.value.surpriseMe.tmdbId ?: return
                resolveAndNavigate(tmdbId, event.onNavigate)
            }
            is DiscoveryEvent.OnItemClick -> {
                val id = event.item.id
                if (id.startsWith("tmdb:")) {
                    val tmdbId = id.removePrefix("tmdb:").toIntOrNull() ?: return
                    resolveAndNavigate(tmdbId, event.onNavigate)
                } else {
                    event.onNavigate(id, event.item.rawType)
                }
            }
            DiscoveryEvent.OnRetry -> loadContent()
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

    private fun loadContent() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val contentType = _uiState.value.contentType
                val language = tmdbSettingsDataStore.settings.first().language.takeIf { it.isNotBlank() }
                val isMovie = contentType == "movie"

                val rows = mutableListOf<DiscoveryRow>()

                // Fetch all rows in parallel
                coroutineScope {
                    val trendingDeferred = async { fetchTrending(contentType, language) }
                    val topRatedDeferred = async { fetchTopRated(isMovie, language) }
                    val newReleasesDeferred = async { fetchNewReleases(isMovie, language) }
                    val hiddenGemsDeferred = async { fetchHiddenGems(isMovie, language) }
                    val genresDeferred = async { fetchGenres(isMovie, language) }

                    // Pick 2 random decades for rows
                    val shuffledDecades = DECADES.shuffled().take(2)
                    val decade1Deferred = async { fetchDecade(isMovie, shuffledDecades[0], language) }
                    val decade2Deferred = async { fetchDecade(isMovie, shuffledDecades[1], language) }

                    // Trakt personalized rows (if authenticated)
                    val traktAuthenticated = traktAuthDataStore.isAuthenticated.first()
                    val becauseYouWatchedDeferred = if (traktAuthenticated) {
                        async { fetchBecauseYouWatched(isMovie, language) }
                    } else null
                    val watchlistDeferred = if (traktAuthenticated) {
                        async { fetchWatchlist(isMovie, language) }
                    } else null

                    val trending = trendingDeferred.await()
                    val topRated = topRatedDeferred.await()
                    val newReleases = newReleasesDeferred.await()
                    val hiddenGems = hiddenGemsDeferred.await()
                    val genres = genresDeferred.await()
                    val decade1 = decade1Deferred.await()
                    val decade2 = decade2Deferred.await()
                    val becauseYouWatched = becauseYouWatchedDeferred?.await()
                    val watchlist = watchlistDeferred?.await()

                    if (trending.isNotEmpty()) rows.add(DiscoveryRow("Trending This Week", trending))
                    if (topRated.isNotEmpty()) rows.add(DiscoveryRow("Top Rated", topRated))
                    if (newReleases.isNotEmpty()) rows.add(DiscoveryRow("New Releases", newReleases))
                    if (hiddenGems.isNotEmpty()) rows.add(DiscoveryRow("Hidden Gems", hiddenGems))

                    if (becauseYouWatched != null && becauseYouWatched.second.isNotEmpty()) {
                        rows.add(DiscoveryRow("Because You Watched \"${becauseYouWatched.first}\"", becauseYouWatched.second))
                    }
                    if (watchlist != null && watchlist.isNotEmpty()) {
                        rows.add(DiscoveryRow("From Your Watchlist", watchlist))
                    }

                    if (decade1.isNotEmpty()) rows.add(DiscoveryRow("Best of the ${shuffledDecades[0].second}", decade1))
                    if (decade2.isNotEmpty()) rows.add(DiscoveryRow("Best of the ${shuffledDecades[1].second}", decade2))

                    // Shuffle the content rows (not the section order of curated vs decade)
                    val shuffledRows = rows.shuffled()

                    _uiState.update {
                        it.copy(
                            rows = shuffledRows,
                            genres = genres,
                            isLoading = false,
                            error = null
                        )
                    }
                }

                // Load Surprise Me separately
                loadSurpriseMe()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load discovery content", e)
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed to load content") }
            }
        }
    }

    private fun loadSurpriseMe() {
        surpriseMeJob?.cancel()
        surpriseMeJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(surpriseMe = it.surpriseMe.copy(isLoading = true)) }
            try {
                val isMovie = _uiState.value.contentType == "movie"
                val language = tmdbSettingsDataStore.settings.first().language.takeIf { it.isNotBlank() }
                val randomPage = (1..10).random()
                val response = if (isMovie) {
                    tmdbApi.discoverMovies(
                        apiKey = TMDB_API_KEY,
                        language = language,
                        page = randomPage,
                        sortBy = "vote_average.desc",
                        voteCountGte = 500
                    )
                } else {
                    tmdbApi.discoverTv(
                        apiKey = TMDB_API_KEY,
                        language = language,
                        page = randomPage,
                        sortBy = "vote_average.desc",
                        voteCountGte = 500
                    )
                }
                val results = response.body()?.results.orEmpty()
                val pick = results.randomOrNull()
                if (pick != null) {
                    val mediaType = _uiState.value.contentType
                    _uiState.update {
                        it.copy(
                            surpriseMe = SurpriseMeState(
                                item = pick.toMetaPreview(mediaType),
                                backdropUrl = pick.backdropPath?.let { p -> "https://image.tmdb.org/t/p/w1280$p" },
                                description = pick.overview,
                                year = (pick.releaseDate ?: pick.firstAirDate)?.take(4),
                                rating = pick.voteAverage?.let { r -> String.format("%.1f", r) },
                                tmdbId = pick.id,
                                isLoading = false
                            )
                        )
                    }
                } else {
                    _uiState.update { it.copy(surpriseMe = SurpriseMeState(isLoading = false)) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Surprise Me failed", e)
                _uiState.update { it.copy(surpriseMe = SurpriseMeState(isLoading = false)) }
            }
        }
    }

    private suspend fun fetchTrending(mediaType: String, language: String?): List<MetaPreview> {
        return try {
            val response = tmdbApi.getTrending(
                mediaType = mediaType,
                timeWindow = "week",
                apiKey = TMDB_API_KEY,
                language = language
            )
            response.body()?.results.orEmpty().map { it.toMetaPreview(mediaType) }
        } catch (e: Exception) {
            Log.w(TAG, "Trending fetch failed", e)
            emptyList()
        }
    }

    private suspend fun fetchTopRated(isMovie: Boolean, language: String?): List<MetaPreview> {
        return try {
            val mediaType = if (isMovie) "movie" else "series"
            val response = if (isMovie) {
                tmdbApi.discoverMovies(
                    apiKey = TMDB_API_KEY,
                    language = language,
                    sortBy = "vote_average.desc",
                    voteCountGte = 1000
                )
            } else {
                tmdbApi.discoverTv(
                    apiKey = TMDB_API_KEY,
                    language = language,
                    sortBy = "vote_average.desc",
                    voteCountGte = 500
                )
            }
            response.body()?.results.orEmpty().map { it.toMetaPreview(mediaType) }
        } catch (e: Exception) {
            Log.w(TAG, "Top Rated fetch failed", e)
            emptyList()
        }
    }

    private suspend fun fetchNewReleases(isMovie: Boolean, language: String?): List<MetaPreview> {
        return try {
            val mediaType = if (isMovie) "movie" else "series"
            val today = java.time.LocalDate.now().toString()
            val response = if (isMovie) {
                tmdbApi.discoverMovies(
                    apiKey = TMDB_API_KEY,
                    language = language,
                    sortBy = "release_date.desc",
                    releaseDateLte = today,
                    voteCountGte = 50
                )
            } else {
                tmdbApi.discoverTv(
                    apiKey = TMDB_API_KEY,
                    language = language,
                    sortBy = "first_air_date.desc",
                    firstAirDateLte = today,
                    voteCountGte = 50
                )
            }
            response.body()?.results.orEmpty().map { it.toMetaPreview(mediaType) }
        } catch (e: Exception) {
            Log.w(TAG, "New Releases fetch failed", e)
            emptyList()
        }
    }

    private suspend fun fetchHiddenGems(isMovie: Boolean, language: String?): List<MetaPreview> {
        return try {
            val mediaType = if (isMovie) "movie" else "series"
            val response = if (isMovie) {
                tmdbApi.discoverMovies(
                    apiKey = TMDB_API_KEY,
                    language = language,
                    sortBy = "vote_average.desc",
                    voteCountGte = 100,
                    voteAverageGte = 7.5,
                    page = (1..5).random()
                )
            } else {
                tmdbApi.discoverTv(
                    apiKey = TMDB_API_KEY,
                    language = language,
                    sortBy = "vote_average.desc",
                    voteCountGte = 100,
                    voteAverageGte = 7.5,
                    page = (1..5).random()
                )
            }
            response.body()?.results.orEmpty().map { it.toMetaPreview(mediaType) }
        } catch (e: Exception) {
            Log.w(TAG, "Hidden Gems fetch failed", e)
            emptyList()
        }
    }

    private suspend fun fetchDecade(isMovie: Boolean, decade: Pair<String, String>, language: String?): List<MetaPreview> {
        return try {
            val mediaType = if (isMovie) "movie" else "series"
            val startYear = decade.first
            val endYear = (startYear.toInt() + 9).toString()
            val response = if (isMovie) {
                tmdbApi.discoverMovies(
                    apiKey = TMDB_API_KEY,
                    language = language,
                    sortBy = "vote_average.desc",
                    primaryReleaseDateGte = "$startYear-01-01",
                    primaryReleaseDateLte = "$endYear-12-31",
                    voteCountGte = 500
                )
            } else {
                tmdbApi.discoverTv(
                    apiKey = TMDB_API_KEY,
                    language = language,
                    sortBy = "vote_average.desc",
                    firstAirDateGte = "$startYear-01-01",
                    firstAirDateLte = "$endYear-12-31",
                    voteCountGte = 200
                )
            }
            response.body()?.results.orEmpty().map { it.toMetaPreview(mediaType) }
        } catch (e: Exception) {
            Log.w(TAG, "Decade fetch failed for ${decade.second}", e)
            emptyList()
        }
    }

    private suspend fun fetchGenres(isMovie: Boolean, language: String?): List<TmdbGenre> {
        return try {
            val response = if (isMovie) {
                tmdbApi.getMovieGenres(apiKey = TMDB_API_KEY, language = language)
            } else {
                tmdbApi.getTvGenres(apiKey = TMDB_API_KEY, language = language)
            }
            response.body()?.genres.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Genre fetch failed", e)
            emptyList()
        }
    }

    private suspend fun fetchBecauseYouWatched(isMovie: Boolean, language: String?): Pair<String, List<MetaPreview>>? {
        return try {
            val mediaType = if (isMovie) "movie" else "series"
            val traktType = if (isMovie) "movies" else "shows"
            val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                traktApi.getWatched(authorization = authHeader, type = traktType)
            } ?: return null

            if (!response.isSuccessful) return null
            val watchedItems = response.body().orEmpty()
                .sortedByDescending { it.lastWatchedAt }
                .take(5)

            if (watchedItems.isEmpty()) return null

            val seedItem = watchedItems.first()
            val seedTitle = seedItem.movie?.title ?: "recent watch"
            val seedTmdbId = seedItem.movie?.ids?.tmdb ?: return null

            // Get recommendations based on the seed item
            val recsResponse = if (isMovie) {
                tmdbApi.getMovieRecommendations(movieId = seedTmdbId, apiKey = TMDB_API_KEY, language = language)
            } else {
                tmdbApi.getTvRecommendations(tvId = seedTmdbId, apiKey = TMDB_API_KEY, language = language)
            }
            val recs = recsResponse.body()?.results.orEmpty().map { rec ->
                MetaPreview(
                    id = "tmdb:${rec.id}",
                    type = if (isMovie) ContentType.MOVIE else ContentType.SERIES,
                    rawType = mediaType,
                    name = rec.title ?: rec.name ?: "",
                    poster = rec.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                    posterShape = PosterShape.POSTER,
                    background = rec.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
                    logo = null,
                    description = rec.overview,
                    releaseInfo = rec.releaseDate ?: rec.firstAirDate,
                    imdbRating = rec.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
            seedTitle to recs
        } catch (e: Exception) {
            Log.w(TAG, "Because You Watched failed", e)
            null
        }
    }

    private suspend fun fetchWatchlist(isMovie: Boolean, language: String?): List<MetaPreview> {
        return try {
            val mediaType = if (isMovie) "movie" else "series"
            val traktType = if (isMovie) "movies" else "shows"
            val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                traktApi.getWatchlist(authorization = authHeader, type = traktType)
            } ?: return emptyList()

            if (!response.isSuccessful) return emptyList()
            val items = response.body().orEmpty().take(20)

            // Convert Trakt watchlist items to MetaPreview using TMDB poster data
            val previews = coroutineScope {
                items.mapNotNull { item ->
                    val tmdbId = if (isMovie) item.movie?.ids?.tmdb else item.show?.ids?.tmdb
                    val title = if (isMovie) item.movie?.title else item.show?.title
                    val imdbId = if (isMovie) item.movie?.ids?.imdb else item.show?.ids?.imdb
                    if (tmdbId == null || title == null) return@mapNotNull null

                    async {
                        try {
                            val details = if (isMovie) {
                                tmdbApi.getMovieDetails(movieId = tmdbId, apiKey = TMDB_API_KEY, language = language)
                            } else {
                                tmdbApi.getTvDetails(tvId = tmdbId, apiKey = TMDB_API_KEY, language = language)
                            }
                            val body = details.body() ?: return@async null
                            MetaPreview(
                                id = imdbId ?: "tmdb:$tmdbId",
                                type = if (isMovie) ContentType.MOVIE else ContentType.SERIES,
                                rawType = mediaType,
                                name = body.title ?: body.name ?: title,
                                poster = body.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                                posterShape = PosterShape.POSTER,
                                background = body.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
                                logo = null,
                                description = body.overview,
                                releaseInfo = body.releaseDate ?: body.firstAirDate,
                                imdbRating = body.voteAverage?.toFloat(),
                                genres = body.genres?.map { it.name } ?: emptyList()
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            previews
        } catch (e: Exception) {
            Log.w(TAG, "Watchlist fetch failed", e)
            emptyList()
        }
    }
}

private fun TmdbDiscoverResult.toMetaPreview(mediaType: String): MetaPreview {
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
