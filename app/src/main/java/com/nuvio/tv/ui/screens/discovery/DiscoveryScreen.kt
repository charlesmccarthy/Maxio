package com.nuvio.tv.ui.screens.discovery

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.data.remote.api.TmdbGenre
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.NetflixStyleRow
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    onNavigateToDetail: (String, String) -> Unit,
    onNavigateToDiscoveryBrowse: (String, String, String, String) -> Unit,
    viewModel: DiscoveryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Content type toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, top = 24.dp, end = 24.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilterChip(
                selected = state.contentType == "movie",
                onClick = { viewModel.onEvent(DiscoveryEvent.OnContentTypeChanged("movie")) }
            ) { Text("Movies") }
            FilterChip(
                selected = state.contentType == "series",
                onClick = { viewModel.onEvent(DiscoveryEvent.OnContentTypeChanged("series")) }
            ) { Text("TV Shows") }
        }

        if (state.isLoading && state.rows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Loading...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextSecondary
                )
            }
        } else if (state.error != null && state.rows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.error ?: "Something went wrong",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { viewModel.onEvent(DiscoveryEvent.OnRetry) }) {
                        Text("Retry")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Surprise Me card
                item(key = "surprise_me") {
                    SurpriseMeCard(
                        state = state.surpriseMe,
                        contentType = state.contentType,
                        onNext = { viewModel.onEvent(DiscoveryEvent.OnSurpriseMeNext) },
                        onPlay = { viewModel.onEvent(DiscoveryEvent.OnSurpriseMePlay(onNavigateToDetail)) },
                        onDetails = { viewModel.onEvent(DiscoveryEvent.OnSurpriseMeDetails(onNavigateToDetail)) }
                    )
                }

                // Content rows
                items(state.rows, key = { it.title }) { row ->
                    NetflixStyleRow(
                        title = row.title,
                        items = row.items,
                        onItemClick = { item ->
                            viewModel.storeActiveTrailer(item)
                            viewModel.onEvent(DiscoveryEvent.OnItemClick(item, onNavigateToDetail))
                        },
                        trailerPreviewUrls = viewModel.trailerPreviewUrls,
                        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
                        logoOverrides = viewModel.logoUrls,
                        trailerEnabled = viewModel.trailerEnabled,
                        trailerMuted = viewModel.trailerMuted,
                        onRequestTrailerPreview = { item -> viewModel.requestTrailerPreview(item) },
                        onItemFocus = { item -> viewModel.requestLogo(item) },
                        onTrailerProgressChanged = { itemId, positionMs ->
                            viewModel.onTrailerProgressChanged(itemId, positionMs)
                        }
                    )
                }

                // Browse by Genre
                if (state.genres.isNotEmpty()) {
                    item(key = "genre_header") {
                        Text(
                            text = "Browse by Genre",
                            style = MaterialTheme.typography.titleLarge,
                            color = NuvioColors.TextPrimary,
                            modifier = Modifier.padding(start = 48.dp, top = 8.dp)
                        )
                    }
                    item(key = "genres") {
                        GenreCardRow(
                            genres = state.genres,
                            onGenreClick = { genre ->
                                onNavigateToDiscoveryBrowse(
                                    "genre",
                                    genre.id.toString(),
                                    genre.name,
                                    state.contentType
                                )
                            }
                        )
                    }
                }

                // Browse by Decade
                item(key = "decade_header") {
                    Text(
                        text = "Browse by Decade",
                        style = MaterialTheme.typography.titleLarge,
                        color = NuvioColors.TextPrimary,
                        modifier = Modifier.padding(start = 48.dp, top = 8.dp)
                    )
                }
                item(key = "decades") {
                    DecadeCardRow(
                        onDecadeClick = { startYear, label ->
                            onNavigateToDiscoveryBrowse(
                                "decade",
                                startYear,
                                label,
                                state.contentType
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SurpriseMeCard(
    state: SurpriseMeState,
    contentType: String,
    onNext: () -> Unit,
    onPlay: () -> Unit,
    onDetails: () -> Unit
) {
    val item = state.item
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp)
            .height(260.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(NuvioColors.BackgroundCard)
    ) {
        Crossfade(targetState = state.backdropUrl, animationSpec = tween(400), label = "surpriseCrossfade") { backdropUrl ->
            if (backdropUrl != null) {
                val context = LocalContext.current
                val imageModel = remember(backdropUrl) {
                    ImageRequest.Builder(context)
                        .data(backdropUrl)
                        .crossfade(300)
                        .build()
                }
                AsyncImage(
                    model = imageModel,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        // Gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to Color.Black.copy(alpha = 0.85f),
                        0.5f to Color.Black.copy(alpha = 0.6f),
                        1.0f to Color.Transparent
                    )
                )
        )

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Surprise Me",
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioColors.Primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (item != null) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        state.year?.let {
                            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = NuvioColors.TextSecondary)
                        }
                        state.rating?.let {
                            Text(text = "★ $it", style = MaterialTheme.typography.bodyMedium, color = NuvioColors.TextSecondary)
                        }
                    }
                    state.description?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioColors.TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                } else if (state.isLoading) {
                    Text(
                        text = "Finding something great...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextSecondary
                    )
                }
            }

            if (item != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onPlay,
                        colors = ButtonDefaults.colors(containerColor = NuvioColors.Primary)
                    ) { Text("Play") }
                    Button(
                        onClick = onNext,
                        colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = 0.15f))
                    ) { Text("Next") }
                    Button(
                        onClick = onDetails,
                        colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = 0.15f))
                    ) { Text("Details") }
                }
            }
        }
    }
}

private val GENRE_COLORS = listOf(
    Color(0xFF1DB954),
    Color(0xFFE91E63),
    Color(0xFF3F51B5),
    Color(0xFFFF9800),
    Color(0xFF9C27B0),
    Color(0xFF00BCD4),
    Color(0xFFFF5722),
    Color(0xFF4CAF50),
    Color(0xFF2196F3),
    Color(0xFFF44336),
    Color(0xFF607D8B),
    Color(0xFF795548),
    Color(0xFFCDDC39),
    Color(0xFFE040FB),
    Color(0xFF00E676),
    Color(0xFFFF6D00),
    Color(0xFF304FFE),
    Color(0xFFDD2C00),
    Color(0xFF76FF03)
)

@Composable
private fun GenreCardRow(
    genres: List<TmdbGenre>,
    onGenreClick: (TmdbGenre) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(genres, key = { it.id }) { genre ->
            val colorIndex = genre.id % GENRE_COLORS.size
            val color = GENRE_COLORS[colorIndex]
            GenreCard(
                name = genre.name,
                color = color,
                onClick = { onGenreClick(genre) }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GenreCard(
    name: String,
    color: Color,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(160.dp)
            .height(80.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(color.copy(alpha = 0.8f), color.copy(alpha = 0.4f))
                )
            )
            .then(
                if (isFocused) Modifier.background(color.copy(alpha = 0.95f), RoundedCornerShape(12.dp))
                else Modifier
            )
            .onFocusChanged { isFocused = it.isFocused },
        contentAlignment = Alignment.Center
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
            colors = ButtonDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = color
            )
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private val DECADE_ITEMS = listOf(
    "1960" to "60s",
    "1970" to "70s",
    "1980" to "80s",
    "1990" to "90s",
    "2000" to "00s",
    "2010" to "10s",
    "2020" to "20s"
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DecadeCardRow(
    onDecadeClick: (startYear: String, label: String) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(DECADE_ITEMS, key = { it.first }) { (startYear, label) ->
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .height(80.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = { onDecadeClick(startYear, "${startYear}s") },
                    modifier = Modifier.fillMaxSize(),
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        focusedContainerColor = NuvioColors.Primary
                    )
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
