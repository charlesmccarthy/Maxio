package com.nuvio.tv.ui.screens.discovery

import com.nuvio.tv.data.remote.api.TmdbGenre
import com.nuvio.tv.domain.model.MetaPreview

data class DiscoveryRow(
    val title: String,
    val items: List<MetaPreview>,
    val isLoading: Boolean = false
)

data class SurpriseMeState(
    val item: MetaPreview? = null,
    val backdropUrl: String? = null,
    val description: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val tmdbId: Int? = null,
    val isLoading: Boolean = false
)

data class DiscoveryUiState(
    val contentType: String = "movie",
    val heroItems: List<MetaPreview> = emptyList(),
    val rows: List<DiscoveryRow> = emptyList(),
    val genres: List<TmdbGenre> = emptyList(),
    val genreBackdropUrls: Map<Int, String> = emptyMap(),
    val surpriseMe: SurpriseMeState = SurpriseMeState(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isResolvingId: Boolean = false
)

sealed interface DiscoveryEvent {
    data class OnContentTypeChanged(val contentType: String) : DiscoveryEvent
    data object OnSurpriseMeNext : DiscoveryEvent
    data class OnSurpriseMePlay(val onNavigate: (String, String) -> Unit) : DiscoveryEvent
    data class OnSurpriseMeDetails(val onNavigate: (String, String) -> Unit) : DiscoveryEvent
    data class OnItemClick(val item: MetaPreview, val onNavigate: (String, String) -> Unit) : DiscoveryEvent
    data object OnRetry : DiscoveryEvent
}
