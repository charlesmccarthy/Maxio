package com.nuvio.tv.ui.screens.discovery

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.data.remote.api.TmdbGenre
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.HeroCarousel
import com.nuvio.tv.ui.components.MediaPosterOptionsDialog
import com.nuvio.tv.ui.components.NetflixStyleRow
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.android.awaitFrame

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    onNavigateToDetail: (String, String) -> Unit,
    onNavigateToDiscoveryBrowse: (String, String, String, String) -> Unit,
    viewModel: DiscoveryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val heroFocusRequester = remember { FocusRequester() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    val rowFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var requestedInitialHeroFocus by rememberSaveable { mutableStateOf(false) }
    var lastFocusedRowKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRowIndices by rememberSaveable { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var restoreRowFocusNonce by rememberSaveable { mutableStateOf(0) }
    var optionsItem by remember { mutableStateOf<MetaPreview?>(null) }

    fun rowKey(row: DiscoveryRow): String = "${state.contentType}:${row.title}"

    val rowIndicesByKey = remember(state.contentType, state.heroItems.size, state.rows) {
        val heroOffset = if (state.heroItems.isNotEmpty()) 1 else 0
        state.rows.mapIndexed { index, row -> rowKey(row) to (index + heroOffset) }.toMap()
    }
    val hasSavedRowFocusTarget = lastFocusedRowKey != null && rowIndicesByKey.containsKey(lastFocusedRowKey)

    DisposableEffect(lifecycleOwner, lastFocusedRowKey, state.rows) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                !lastFocusedRowKey.isNullOrBlank() &&
                state.rows.isNotEmpty()
            ) {
                restoreRowFocusNonce += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(state.heroItems, requestedInitialHeroFocus, hasSavedRowFocusTarget) {
        if (requestedInitialHeroFocus || state.heroItems.isEmpty() || hasSavedRowFocusTarget) return@LaunchedEffect
        repeat(2) { awaitFrame() }
        runCatching { heroFocusRequester.requestFocus() }
        requestedInitialHeroFocus = true
    }

    LaunchedEffect(restoreRowFocusNonce, rowIndicesByKey, lastFocusedRowKey) {
        val targetKey = lastFocusedRowKey ?: return@LaunchedEffect
        val rowIndex = rowIndicesByKey[targetKey] ?: return@LaunchedEffect
        listState.scrollToItem(rowIndex)
        repeat(2) { awaitFrame() }
        rowFocusRequesters.getOrPut(targetKey) { FocusRequester() }.requestFocus()
        requestedInitialHeroFocus = true
    }

    LaunchedEffect(state.contentType) {
        requestedInitialHeroFocus = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
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
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                item(key = "discovery_hero") {
                    if (state.heroItems.isNotEmpty()) {
                        HeroCarousel(
                            items = state.heroItems,
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
                            onFocused = { lastFocusedRowKey = null },
                            focusRequester = heroFocusRequester,
                            modifier = Modifier.padding(horizontal = 48.dp)
                        )
                    }
                }

                // Content rows
                items(state.rows, key = { it.title }) { row ->
                    val rowKey = rowKey(row)
                    NetflixStyleRow(
                        title = row.title,
                        items = row.items,
                        onItemClick = { item ->
                            viewModel.storeActiveTrailer(item)
                            viewModel.onEvent(DiscoveryEvent.OnItemClick(item, onNavigateToDetail))
                        },
                        onItemLongPress = { item -> optionsItem = item },
                        isItemLiked = { item -> viewModel.likedItemStatus["${item.apiType}:${item.id}"] == true },
                        trailerPreviewUrls = viewModel.trailerPreviewUrls,
                        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
                        logoOverrides = viewModel.logoUrls,
                        trailerEnabled = viewModel.trailerEnabled,
                        trailerMuted = viewModel.trailerMuted,
                        onRequestTrailerPreview = { item -> viewModel.requestTrailerPreview(item) },
                        onItemFocus = { item -> viewModel.requestLogo(item) },
                        focusRequester = rowFocusRequesters.getOrPut(rowKey) { FocusRequester() },
                        initialSelectedIndex = selectedRowIndices[rowKey] ?: 0,
                        onSelectedIndexChange = { selectedIndex ->
                            val currentIndex = selectedRowIndices[rowKey]
                            if (currentIndex != selectedIndex) {
                                selectedRowIndices = selectedRowIndices + (rowKey to selectedIndex)
                            }
                        },
                        onRowFocused = {
                            lastFocusedRowKey = rowKey
                        },
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
                            backdropUrls = state.genreBackdropUrls,
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

    optionsItem?.let { item ->
        MediaPosterOptionsDialog(
            title = item.name,
            isLiked = viewModel.likedItemStatus["${item.apiType}:${item.id}"] == true,
            onDismiss = { optionsItem = null },
            onDetails = {
                viewModel.storeActiveTrailer(item)
                viewModel.onEvent(DiscoveryEvent.OnItemClick(item, onNavigateToDetail))
                optionsItem = null
            },
            onToggleLike = {
                viewModel.toggleLiked(item)
                optionsItem = null
            }
        )
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
    backdropUrls: Map<Int, String>,
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
                backdropUrl = backdropUrls[genre.id],
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
    backdropUrl: String?,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(220.dp)
            .height(124.dp)
            .onFocusChanged { isFocused = it.hasFocus || it.isFocused },
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                shape = shape
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, Color.White),
                shape = shape
            )
        ),
        scale = CardDefaults.scale(
            focusedScale = 1.04f
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(
                    if (backdropUrl.isNullOrBlank()) {
                        Brush.linearGradient(
                            colors = listOf(
                                color.copy(alpha = 0.92f),
                                color.copy(alpha = 0.56f),
                                Color.Black.copy(alpha = 0.88f)
                            )
                        )
                    } else {
                        Brush.linearGradient(
                            colors = listOf(Color.Black, Color.Black)
                        )
                    }
                )
        ) {
            if (!backdropUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(backdropUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                color.copy(alpha = if (isFocused) 0.82f else 0.70f),
                                color.copy(alpha = 0.26f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.08f),
                                Color.Black.copy(alpha = 0.28f),
                                Color.Black.copy(alpha = 0.86f)
                            )
                        )
                    )
            )

            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
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
