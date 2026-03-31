package com.nuvio.tv.ui.screens.discovery

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import androidx.compose.runtime.mutableStateMapOf
import com.nuvio.tv.core.util.tmdbImageUrl
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.LikedMediaDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.trailer.ActiveTrailerState
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbGenre
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.core.util.isUnreleased
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.random.Random
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

private const val MAX_DISCOVERY_GENRE_ROWS = 14

private val MOVIE_DISCOVERY_GENRE_PRIORITY = listOf(
    "Action",
    "Adventure",
    "Animation",
    "Comedy",
    "Crime",
    "Documentary",
    "Drama",
    "Family",
    "Fantasy",
    "History",
    "Horror",
    "Mystery",
    "Romance",
    "Science Fiction",
    "Thriller",
    "War",
    "Western"
)

private val TV_DISCOVERY_GENRE_PRIORITY = listOf(
    "Action & Adventure",
    "Animation",
    "Comedy",
    "Crime",
    "Documentary",
    "Drama",
    "Family",
    "Kids",
    "Mystery",
    "News",
    "Reality",
    "Sci-Fi & Fantasy",
    "Soap",
    "Talk",
    "War & Politics",
    "Western"
)

private data class DiscoveryRowSpec(
    val title: String,
    val order: Int,
    val fetcher: suspend () -> List<MetaPreview>
)

private data class DiscoveryRowResult(
    val order: Int,
    val row: DiscoveryRow?
)

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val trailerService: TrailerService,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val likedMediaDataStore: LikedMediaDataStore,
    private val activeTrailerState: ActiveTrailerState
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoveryUiState())
    val uiState: StateFlow<DiscoveryUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var surpriseMeJob: Job? = null
    private var likedRowJob: Job? = null
    private var curatedRows: List<DiscoveryRow> = emptyList()
    private var likedItems: List<MetaPreview> = emptyList()
    private var likedRecommendationRow: DiscoveryRow? = null
    val likedItemStatus = mutableStateMapOf<String, Boolean>()

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

    init {
        loadContent()
        observeTrailerPrefs()
        observeLikedItems()
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

    private fun observeLikedItems() {
        viewModelScope.launch {
            likedMediaDataStore.likedItems.collectLatest { items ->
                likedItems = items
                likedItemStatus.clear()
                items.forEach { item ->
                    likedItemStatus[likedStatusKey(item)] = true
                }
                refreshLikedRecommendationRow()
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

    fun toggleLiked(item: MetaPreview) {
        viewModelScope.launch {
            likedMediaDataStore.toggle(item)
        }
    }

    fun onEvent(event: DiscoveryEvent) {
        when (event) {
            is DiscoveryEvent.OnContentTypeChanged -> {
                _uiState.update { it.copy(contentType = event.contentType) }
                refreshLikedRecommendationRow()
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

                // Fetch all rows in parallel
                coroutineScope {
                    val genresDeferred = async { fetchGenres(isMovie, language) }
                    val shuffledDecades = DECADES.shuffled().take(2)
                    val traktAuthenticated = traktAuthDataStore.isAuthenticated.first()
                    val genres = genresDeferred.await()
                    val rowSpecs = buildCuratedRowSpecs(
                        contentType = contentType,
                        isMovie = isMovie,
                        language = language,
                        shuffledDecades = shuffledDecades
                    ) + buildGenreRowSpecs(
                        genres = genres,
                        isMovie = isMovie,
                        language = language
                    )
                    val rowResults = rowSpecs.map { spec ->
                        async {
                            val items = spec.fetcher()
                            DiscoveryRowResult(
                                order = spec.order,
                                row = items.takeIf { it.isNotEmpty() }?.let { DiscoveryRow(spec.title, it) }
                            )
                        }
                    }

                    val becauseYouWatchedDeferred = if (traktAuthenticated) async {
                        fetchBecauseYouWatched(isMovie, language)
                    } else null
                    val watchlistDeferred = if (traktAuthenticated) async {
                        fetchWatchlist(isMovie, language)
                    } else null

                    val genreBackdropUrlsDeferred = async { fetchGenreBackdropUrls(isMovie, genres, language) }
                    val becauseYouWatched = becauseYouWatchedDeferred?.await()
                    val watchlist = watchlistDeferred?.await()
                    val genreBackdropUrls = genreBackdropUrlsDeferred.await()
                    val allRowResults = rowResults.awaitAll().toMutableList()

                    if (becauseYouWatched != null && becauseYouWatched.second.isNotEmpty()) {
                        allRowResults += DiscoveryRowResult(
                            order = 6,
                            row = DiscoveryRow(
                                "Because You Watched \"${becauseYouWatched.first}\"",
                                becauseYouWatched.second
                            )
                        )
                    }
                    if (watchlist != null && watchlist.isNotEmpty()) {
                        allRowResults += DiscoveryRowResult(
                            order = 7,
                            row = DiscoveryRow("From Your Watchlist", watchlist)
                        )
                    }

                    val rows = allRowResults
                        .sortedBy { it.order }
                        .mapNotNull { it.row }

                    val heroItems = rows.firstOrNull()?.items?.take(10).orEmpty()
                    curatedRows = rows
                    _uiState.update { it.copy(heroItems = heroItems) }
                    publishRows(
                        genres = genres,
                        genreBackdropUrls = genreBackdropUrls,
                        isLoading = false,
                        error = null
                    )
                }

                // Load Surprise Me separately
                loadSurpriseMe()
                refreshLikedRecommendationRow()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load discovery content", e)
                curatedRows = emptyList()
                _uiState.update { it.copy(heroItems = emptyList()) }
                publishRows(
                    genres = _uiState.value.genres,
                    genreBackdropUrls = _uiState.value.genreBackdropUrls,
                    isLoading = false,
                    error = e.message ?: "Failed to load content"
                )
            }
        }
    }

    private fun publishRows(
        genres: List<TmdbGenre>,
        genreBackdropUrls: Map<Int, String> = _uiState.value.genreBackdropUrls,
        isLoading: Boolean,
        error: String?
    ) {
        val combinedRows = if (likedRecommendationRow == null) {
            curatedRows
        } else {
            buildList {
                if (curatedRows.isEmpty()) {
                    add(likedRecommendationRow!!)
                } else {
                    add(curatedRows.first())
                    add(likedRecommendationRow!!)
                    addAll(curatedRows.drop(1))
                }
            }
        }
        _uiState.update {
            it.copy(
                rows = combinedRows,
                genres = genres,
                genreBackdropUrls = genreBackdropUrls,
                isLoading = isLoading,
                error = error
            )
        }
    }

    private fun refreshLikedRecommendationRow() {
        likedRowJob?.cancel()
        likedRowJob = viewModelScope.launch(Dispatchers.IO) {
            val contentType = when (_uiState.value.contentType) {
                "movie" -> ContentType.MOVIE
                else -> ContentType.SERIES
            }
            val settings = tmdbSettingsDataStore.settings.first()
            if (!settings.enabled || !settings.useMoreLikeThis) {
                likedRecommendationRow = null
                publishRows(
                    genres = _uiState.value.genres,
                    genreBackdropUrls = _uiState.value.genreBackdropUrls,
                    isLoading = _uiState.value.isLoading,
                    error = _uiState.value.error
                )
                return@launch
            }

            val sourceItems = likedItems.filter { likedContentType(it) == contentType }
            if (sourceItems.isEmpty()) {
                likedRecommendationRow = null
                publishRows(
                    genres = _uiState.value.genres,
                    genreBackdropUrls = _uiState.value.genreBackdropUrls,
                    isLoading = _uiState.value.isLoading,
                    error = _uiState.value.error
                )
                return@launch
            }

            val seedItems = sourceItems
                .shuffled(Random(sourceItems.joinToString("|") { it.id }.hashCode()))
                .take(5)
            val sourceKeys = sourceItems.map(::likedStatusKey).toHashSet()
            val recommendations = linkedMapOf<String, MetaPreview>()
            val today = LocalDate.now()

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
                    val key = likedStatusKey(recommendation)
                    if (key in sourceKeys) return@forEach
                    if (recommendation.isUnreleased(today)) {
                        return@forEach
                    }
                    recommendations.putIfAbsent(key, recommendation)
                }
            }

            likedRecommendationRow = recommendations.values
                .take(25)
                .takeIf { it.isNotEmpty() }
                ?.let { items ->
                    DiscoveryRow(
                        title = if (contentType == ContentType.MOVIE) {
                            "More Like Your Liked Movies"
                        } else {
                            "More Like Your Liked Shows"
                        },
                        items = items
                    )
                }
            publishRows(
                genres = _uiState.value.genres,
                genreBackdropUrls = _uiState.value.genreBackdropUrls,
                isLoading = _uiState.value.isLoading,
                error = _uiState.value.error
            )
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
                val picks = results
                    .shuffled()
                    .take(8)
                val pick = picks.firstOrNull()
                if (pick != null) {
                    val mediaType = _uiState.value.contentType
                    _uiState.update {
                        it.copy(
                            heroItems = picks.map { result -> result.toMetaPreview(mediaType) },
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
                    _uiState.update { it.copy(heroItems = emptyList(), surpriseMe = SurpriseMeState(isLoading = false)) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Surprise Me failed", e)
                _uiState.update { it.copy(heroItems = emptyList(), surpriseMe = SurpriseMeState(isLoading = false)) }
            }
        }
    }

    private suspend fun fetchTrending(
        mediaType: String,
        language: String?,
        timeWindow: String = "week"
    ): List<MetaPreview> {
        return try {
            val response = tmdbApi.getTrending(
                mediaType = mediaType,
                timeWindow = timeWindow,
                apiKey = TMDB_API_KEY,
                language = language
            )
            response.body()?.results.orEmpty().map { it.toMetaPreview(mediaType) }
        } catch (e: Exception) {
            Log.w(TAG, "Trending fetch failed", e)
            emptyList()
        }
    }

    private suspend fun fetchPopular(isMovie: Boolean, language: String?): List<MetaPreview> {
        val today = LocalDate.now().toString()
        return fetchDiscoverList(
            isMovie = isMovie,
            language = language,
            sortBy = "popularity.desc",
            releaseDateLte = if (isMovie) today else null,
            firstAirDateLte = if (isMovie) null else today,
            voteCountGte = if (isMovie) 250 else 120
        )
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

    private suspend fun fetchCriticallyAcclaimed(isMovie: Boolean, language: String?): List<MetaPreview> {
        return fetchDiscoverList(
            isMovie = isMovie,
            language = language,
            sortBy = "vote_average.desc",
            voteCountGte = if (isMovie) 1500 else 750,
            voteAverageGte = if (isMovie) 7.8 else 7.8
        )
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

    private suspend fun fetchRecentHits(isMovie: Boolean, language: String?): List<MetaPreview> {
        val today = LocalDate.now()
        val since = today.minusYears(3).toString()
        return fetchDiscoverList(
            isMovie = isMovie,
            language = language,
            sortBy = "popularity.desc",
            primaryReleaseDateGte = if (isMovie) since else null,
            primaryReleaseDateLte = if (isMovie) today.toString() else null,
            firstAirDateGte = if (isMovie) null else since,
            firstAirDateLte = if (isMovie) null else today.toString(),
            voteCountGte = if (isMovie) 300 else 150
        )
    }

    private suspend fun fetchCrowdPleasers(isMovie: Boolean, language: String?): List<MetaPreview> {
        val today = LocalDate.now().toString()
        return fetchDiscoverList(
            isMovie = isMovie,
            language = language,
            sortBy = "popularity.desc",
            releaseDateLte = if (isMovie) today else null,
            firstAirDateLte = if (isMovie) null else today,
            voteCountGte = if (isMovie) 1500 else 700,
            voteAverageGte = 7.0
        )
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

    private suspend fun fetchGenreHighlights(
        genre: TmdbGenre,
        isMovie: Boolean,
        language: String?
    ): List<MetaPreview> {
        val today = LocalDate.now().toString()
        return fetchDiscoverList(
            isMovie = isMovie,
            language = language,
            sortBy = "popularity.desc",
            withGenres = genre.id.toString(),
            releaseDateLte = if (isMovie) today else null,
            firstAirDateLte = if (isMovie) null else today,
            voteCountGte = if (isMovie) 100 else 50
        )
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

    private suspend fun fetchDiscoverList(
        isMovie: Boolean,
        language: String?,
        sortBy: String? = null,
        page: Int = 1,
        releaseDateLte: String? = null,
        releaseDateGte: String? = null,
        primaryReleaseDateGte: String? = null,
        primaryReleaseDateLte: String? = null,
        firstAirDateLte: String? = null,
        firstAirDateGte: String? = null,
        voteCountGte: Int? = null,
        voteAverageGte: Double? = null,
        withGenres: String? = null
    ): List<MetaPreview> {
        return try {
            val mediaType = if (isMovie) "movie" else "series"
            val response = if (isMovie) {
                tmdbApi.discoverMovies(
                    apiKey = TMDB_API_KEY,
                    language = language,
                    page = page,
                    sortBy = sortBy,
                    releaseDateLte = releaseDateLte,
                    releaseDateGte = releaseDateGte,
                    primaryReleaseDateGte = primaryReleaseDateGte,
                    primaryReleaseDateLte = primaryReleaseDateLte,
                    voteCountGte = voteCountGte,
                    voteAverageGte = voteAverageGte,
                    withGenres = withGenres
                )
            } else {
                tmdbApi.discoverTv(
                    apiKey = TMDB_API_KEY,
                    language = language,
                    page = page,
                    sortBy = sortBy,
                    firstAirDateLte = firstAirDateLte,
                    firstAirDateGte = firstAirDateGte,
                    voteCountGte = voteCountGte,
                    voteAverageGte = voteAverageGte,
                    withGenres = withGenres
                )
            }
            response.body()?.results.orEmpty().map { it.toMetaPreview(mediaType) }
        } catch (e: Exception) {
            Log.w(TAG, "Discover fetch failed for sort=$sortBy genres=$withGenres", e)
            emptyList()
        }
    }

    private fun buildCuratedRowSpecs(
        contentType: String,
        isMovie: Boolean,
        language: String?,
        shuffledDecades: List<Pair<String, String>>
    ): List<DiscoveryRowSpec> {
        val typeLabelPlural = if (isMovie) "Movies" else "Shows"
        val newReleaseTitle = if (isMovie) "New Releases" else "New & Returning Shows"
        return buildList {
            add(
                DiscoveryRowSpec(
                    title = "Trending Today",
                    order = 0,
                    fetcher = { fetchTrending(contentType, language, timeWindow = "day") }
                )
            )
            add(
                DiscoveryRowSpec(
                    title = "Trending This Week",
                    order = 1,
                    fetcher = { fetchTrending(contentType, language, timeWindow = "week") }
                )
            )
            add(
                DiscoveryRowSpec(
                    title = "Popular $typeLabelPlural",
                    order = 2,
                    fetcher = { fetchPopular(isMovie, language) }
                )
            )
            add(
                DiscoveryRowSpec(
                    title = "Top Rated",
                    order = 3,
                    fetcher = { fetchTopRated(isMovie, language) }
                )
            )
            add(
                DiscoveryRowSpec(
                    title = newReleaseTitle,
                    order = 4,
                    fetcher = { fetchNewReleases(isMovie, language) }
                )
            )
            add(
                DiscoveryRowSpec(
                    title = if (isMovie) "Recent Hits" else "Recent Hit Shows",
                    order = 5,
                    fetcher = { fetchRecentHits(isMovie, language) }
                )
            )
            add(
                DiscoveryRowSpec(
                    title = if (isMovie) "Crowd Pleasers" else "Binge-Worthy",
                    order = 8,
                    fetcher = { fetchCrowdPleasers(isMovie, language) }
                )
            )
            add(
                DiscoveryRowSpec(
                    title = "Hidden Gems",
                    order = 9,
                    fetcher = { fetchHiddenGems(isMovie, language) }
                )
            )
            add(
                DiscoveryRowSpec(
                    title = "Critically Acclaimed",
                    order = 10,
                    fetcher = { fetchCriticallyAcclaimed(isMovie, language) }
                )
            )
            shuffledDecades.getOrNull(0)?.let { decade ->
                add(
                    DiscoveryRowSpec(
                        title = "Best of the ${decade.second}",
                        order = 11,
                        fetcher = { fetchDecade(isMovie, decade, language) }
                    )
                )
            }
            shuffledDecades.getOrNull(1)?.let { decade ->
                add(
                    DiscoveryRowSpec(
                        title = "Best of the ${decade.second}",
                        order = 12,
                        fetcher = { fetchDecade(isMovie, decade, language) }
                    )
                )
            }
        }
    }

    private fun buildGenreRowSpecs(
        genres: List<TmdbGenre>,
        isMovie: Boolean,
        language: String?
    ): List<DiscoveryRowSpec> {
        val prioritizedNames = if (isMovie) {
            MOVIE_DISCOVERY_GENRE_PRIORITY
        } else {
            TV_DISCOVERY_GENRE_PRIORITY
        }
        val genresByLowerName = genres.associateBy { it.name.trim().lowercase() }
        val prioritizedGenres = prioritizedNames.mapNotNull { name ->
            genresByLowerName[name.lowercase()]
        }
        val remainingGenres = genres
            .filterNot { genre -> prioritizedGenres.any { it.id == genre.id } }
            .sortedBy { it.name.lowercase() }
        val selectedGenres = (prioritizedGenres + remainingGenres)
            .distinctBy { it.id }
            .take(MAX_DISCOVERY_GENRE_ROWS)

        return selectedGenres.mapIndexed { index, genre ->
            DiscoveryRowSpec(
                title = genreDiscoveryTitle(genre.name, isMovie),
                order = 20 + index,
                fetcher = { fetchGenreHighlights(genre, isMovie, language) }
            )
        }
    }

    private fun genreDiscoveryTitle(genreName: String, isMovie: Boolean): String {
        val normalizedName = when (genreName) {
            "Science Fiction" -> "Sci-Fi"
            "Sci-Fi & Fantasy" -> "Sci-Fi & Fantasy"
            else -> genreName
        }
        return if (isMovie) {
            "$normalizedName Movies"
        } else {
            "$normalizedName Shows"
        }
    }

    private suspend fun fetchGenreBackdropUrls(
        isMovie: Boolean,
        genres: List<TmdbGenre>,
        language: String?
    ): Map<Int, String> = coroutineScope {
        val today = LocalDate.now().toString()
        genres.map { genre ->
            async {
                val response = if (isMovie) {
                    tmdbApi.discoverMovies(
                        apiKey = TMDB_API_KEY,
                        language = language,
                        sortBy = "popularity.desc",
                        voteCountGte = 100,
                        releaseDateLte = today,
                        withGenres = genre.id.toString()
                    )
                } else {
                    tmdbApi.discoverTv(
                        apiKey = TMDB_API_KEY,
                        language = language,
                        sortBy = "popularity.desc",
                        voteCountGte = 50,
                        firstAirDateLte = today,
                        withGenres = genre.id.toString()
                    )
                }
                val backdropUrl = response.body()
                    ?.results
                    .orEmpty()
                    .firstOrNull { !it.backdropPath.isNullOrBlank() }
                    ?.backdropPath
                    ?.let { path -> "https://image.tmdb.org/t/p/w780$path" }
                genre.id to backdropUrl
            }
        }.awaitAll()
            .mapNotNull { (genreId, backdropUrl) -> backdropUrl?.let { genreId to it } }
            .toMap()
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
                val posterUrl = tmdbPosterUrl(rec.posterPath, rec.backdropPath)
                val backdropUrl = tmdbBackdropUrl(rec.backdropPath, rec.posterPath)
                MetaPreview(
                    id = "tmdb:${rec.id}",
                    type = if (isMovie) ContentType.MOVIE else ContentType.SERIES,
                    rawType = mediaType,
                    name = rec.title ?: rec.name ?: "",
                    poster = posterUrl,
                    posterShape = PosterShape.POSTER,
                    background = backdropUrl,
                    landscapePoster = backdropUrl,
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
                            val posterUrl = tmdbPosterUrl(body.posterPath, body.backdropPath)
                            val backdropUrl = tmdbBackdropUrl(body.backdropPath, body.posterPath)
                            MetaPreview(
                                id = imdbId ?: "tmdb:$tmdbId",
                                type = if (isMovie) ContentType.MOVIE else ContentType.SERIES,
                                rawType = mediaType,
                                name = body.title ?: body.name ?: title,
                                poster = posterUrl,
                                posterShape = PosterShape.POSTER,
                                background = backdropUrl,
                                landscapePoster = backdropUrl,
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
    val posterUrl = tmdbPosterUrl(posterPath, backdropPath)
    val backdropUrl = tmdbBackdropUrl(backdropPath, posterPath)
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

private fun likedStatusKey(item: MetaPreview): String = "${item.apiType}:${item.id}"

private fun tmdbPosterUrl(posterPath: String?, backdropPath: String?): String? {
    return tmdbImageUrl(posterPath, "w500") ?: tmdbImageUrl(backdropPath, "w780")
}

private fun tmdbBackdropUrl(backdropPath: String?, posterPath: String?): String? {
    return tmdbImageUrl(backdropPath, "w1280") ?: tmdbPosterUrl(posterPath, backdropPath)
}

private fun likedContentType(item: MetaPreview): ContentType {
    return when (item.apiType.lowercase()) {
        "movie" -> ContentType.MOVIE
        "series", "tv" -> ContentType.SERIES
        else -> ContentType.UNKNOWN
    }
}
