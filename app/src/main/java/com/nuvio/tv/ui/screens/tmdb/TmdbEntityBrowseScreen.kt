package com.nuvio.tv.ui.screens.tmdb

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.tmdb.TmdbEntityBrowseData
import com.nuvio.tv.core.tmdb.TmdbEntityKind
import com.nuvio.tv.core.tmdb.TmdbEntityMediaType
import com.nuvio.tv.core.tmdb.TmdbEntityRail
import com.nuvio.tv.core.tmdb.TmdbEntityRailType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NetflixStyleRow
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun TmdbEntityBrowseScreen(
    viewModel: TmdbEntityBrowseViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler { onBackPress() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
    ) {
        Crossfade(
            targetState = uiState,
            label = "TmdbEntityBrowseState"
        ) { state ->
            when (state) {
                TmdbEntityBrowseUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                }

                is TmdbEntityBrowseUiState.Error -> {
                    ErrorState(
                        message = state.message.ifBlank { "Could not load TMDB entity" },
                        onRetry = { viewModel.retry() }
                    )
                }

                is TmdbEntityBrowseUiState.Success -> {
                    TmdbEntityBrowseContent(
                        data = state.data,
                        sourceType = viewModel.sourceType,
                        trailerPreviewUrls = viewModel.trailerPreviewUrls,
                        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
                        logoOverrides = viewModel.logoUrls,
                        trailerEnabled = viewModel.trailerEnabled,
                        trailerMuted = viewModel.trailerMuted,
                        onItemClick = { item ->
                            viewModel.storeActiveTrailer(item)
                            onNavigateToDetail(item.id, item.apiType, null)
                        },
                        onRequestTrailerPreview = viewModel::requestTrailerPreview,
                        onRequestLogo = viewModel::requestLogo,
                        onTrailerProgressChanged = viewModel::onTrailerProgressChanged,
                        onLoadMoreRail = { mediaType, railType ->
                            viewModel.loadMoreRail(mediaType = mediaType, railType = railType)
                        }
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TmdbEntityBrowseContent(
    data: TmdbEntityBrowseData,
    sourceType: String,
    trailerPreviewUrls: Map<String, String>,
    trailerPreviewAudioUrls: Map<String, String>,
    logoOverrides: Map<String, String>,
    trailerEnabled: Boolean,
    trailerMuted: Boolean,
    onItemClick: (MetaPreview) -> Unit,
    onRequestTrailerPreview: (MetaPreview) -> Unit,
    onRequestLogo: (MetaPreview) -> Unit,
    onTrailerProgressChanged: (String, Long) -> Unit,
    onLoadMoreRail: (TmdbEntityMediaType, TmdbEntityRailType) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingRestoreItemId by rememberSaveable(data.header.id) { mutableStateOf<String?>(null) }
    var restoreFocusToken by rememberSaveable(data.header.id) { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner, pendingRestoreItemId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingRestoreItemId != null) {
                restoreFocusToken += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(restoreFocusToken, pendingRestoreItemId, data.rails) {
        val restoreId = pendingRestoreItemId ?: return@LaunchedEffect
        if (restoreFocusToken <= 0) return@LaunchedEffect
        if (data.rails.none { rail -> rail.items.any { it.id == restoreId } }) {
            pendingRestoreItemId = null
        }
    }

    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val localDensity = LocalDensity.current
    val focusedItemIndexByRail = remember { mutableMapOf<String, Int>() }
    val backgroundRequest = rememberBackgroundRequest(
        data = data,
        sourceType = sourceType
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (backgroundRequest != null) {
            AsyncImage(
                model = backgroundRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.14f
            )
        }

        if (data.rails.isEmpty()) {
            EmptyScreenState(
                title = stringResource(R.string.tmdb_entity_empty_title),
                subtitle = stringResource(R.string.tmdb_entity_empty_subtitle),
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            val railsTailPadding = maxHeight * 0.55f
            val railHeaderFocusInset = 32.dp
            val railsBringIntoViewSpec = remember(localDensity, defaultBringIntoViewSpec) {
                val topInsetPx = with(localDensity) { railHeaderFocusInset.toPx() }
                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                object : BringIntoViewSpec {
                    override val scrollAnimationSpec: AnimationSpec<Float> =
                        defaultBringIntoViewSpec.scrollAnimationSpec

                    override fun calculateScrollDistance(
                        offset: Float,
                        size: Float,
                        containerSize: Float
                    ): Float = offset - topInsetPx
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 24.dp)
            ) {
                TmdbEntityHero(
                    data = data,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )

                CompositionLocalProvider(LocalBringIntoViewSpec provides railsBringIntoViewSpec) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(top = 8.dp, bottom = railsTailPadding),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        items(
                            items = data.rails,
                            key = { rail -> "${rail.mediaType.value}_${rail.railType.value}" }
                        ) { rail ->
                            val railKey = "${rail.mediaType.value}_${rail.railType.value}"
                            EntityRailRow(
                                rail = rail,
                                rememberedFocusedIndex = focusedItemIndexByRail[railKey] ?: 0,
                                trailerPreviewUrls = trailerPreviewUrls,
                                trailerPreviewAudioUrls = trailerPreviewAudioUrls,
                                logoOverrides = logoOverrides,
                                trailerEnabled = trailerEnabled,
                                trailerMuted = trailerMuted,
                                restoreItemId = pendingRestoreItemId,
                                restoreFocusToken = restoreFocusToken,
                                onRestoreFocusHandled = { pendingRestoreItemId = null },
                                onFocusedItemIndexChanged = { focusedIndex ->
                                    focusedItemIndexByRail[railKey] = focusedIndex
                                },
                                onRequestTrailerPreview = onRequestTrailerPreview,
                                onRequestLogo = onRequestLogo,
                                onItemClick = { item ->
                                    pendingRestoreItemId = item.id
                                    onItemClick(item)
                                },
                                onTrailerProgressChanged = onTrailerProgressChanged,
                                onLoadMore = onLoadMoreRail
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberBackgroundRequest(
    data: TmdbEntityBrowseData,
    sourceType: String
): ImageRequest? {
    val context = LocalContext.current
    return remember(context, data, sourceType) {
        val backgroundItem = data.rails.firstOrNull {
            it.mediaType == if (sourceType.trim().equals("movie", ignoreCase = true)) {
                TmdbEntityMediaType.MOVIE
            } else {
                TmdbEntityMediaType.TV
            }
        }?.items?.firstOrNull()?.background ?: data.rails.firstOrNull()?.items?.firstOrNull()?.background
        backgroundItem?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(true)
                .build()
        }
    }
}

@Composable
private fun TmdbEntityHero(
    data: TmdbEntityBrowseData,
    modifier: Modifier = Modifier
) {
    val hasLogo = !data.header.logo.isNullOrBlank()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        val context = LocalContext.current

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = if (hasLogo) 32.dp else 0.dp)
        ) {
            Text(
                text = entityKindLabel(data.header.kind),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.4.sp
                ),
                color = NuvioColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = data.header.name,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 56.sp,
                    lineHeight = 60.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1).sp
                ),
                color = NuvioColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val metaLine = listOfNotNull(
                data.header.originCountry?.takeIf { it.isNotBlank() },
                data.header.secondaryLabel?.takeIf { it.isNotBlank() }
            ).joinToString(" • ")
            if (metaLine.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextSecondary
                )
            }
            data.header.description?.takeIf { it.isNotBlank() }?.let { description ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                    color = NuvioColors.TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.88f)
                )
            }
        }

        if (hasLogo) {
            val logoPainter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(context)
                    .data(data.header.logo)
                    .crossfade(true)
                    .allowHardware(false)
                    .build()
            )
            var logoColorFilter by remember { mutableStateOf<ColorFilter?>(null) }
            val painterState = logoPainter.state
            LaunchedEffect(painterState) {
                if (painterState is AsyncImagePainter.State.Success) {
                    val bitmap = (painterState.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        logoColorFilter = if (isLogoDarkAndMonochrome(bitmap)) {
                            ColorFilter.tint(Color.White, BlendMode.SrcIn)
                        } else {
                            null
                        }
                    }
                }
            }

            Image(
                painter = logoPainter,
                contentDescription = data.header.name,
                modifier = Modifier
                    .width(280.dp)
                    .height(120.dp),
                contentScale = ContentScale.Fit,
                colorFilter = logoColorFilter
            )
        }
    }
}

@Composable
private fun EntityRailRow(
    rail: TmdbEntityRail,
    rememberedFocusedIndex: Int,
    trailerPreviewUrls: Map<String, String>,
    trailerPreviewAudioUrls: Map<String, String>,
    logoOverrides: Map<String, String>,
    trailerEnabled: Boolean,
    trailerMuted: Boolean,
    restoreItemId: String?,
    restoreFocusToken: Int,
    onRestoreFocusHandled: () -> Unit,
    onFocusedItemIndexChanged: (Int) -> Unit,
    onRequestTrailerPreview: (MetaPreview) -> Unit,
    onRequestLogo: (MetaPreview) -> Unit,
    onItemClick: (MetaPreview) -> Unit,
    onTrailerProgressChanged: (String, Long) -> Unit,
    onLoadMore: (TmdbEntityMediaType, TmdbEntityRailType) -> Unit
) {
    if (rail.items.isEmpty()) return

    val rowFocusRequester = remember(rail.mediaType, rail.railType) { FocusRequester() }
    val restoreIndex = remember(restoreItemId, rail.items) {
        restoreItemId?.let { restoreId ->
            rail.items.indexOfFirst { it.id == restoreId }
        } ?: -1
    }
    val safeRememberedIndex = rememberedFocusedIndex
        .coerceIn(0, (rail.items.size - 1).coerceAtLeast(0))
    val initialSelectedIndex = if (restoreIndex >= 0) restoreIndex else safeRememberedIndex
    var lastLoadMoreRequestTotal by remember(rail.mediaType, rail.railType) { mutableIntStateOf(-1) }

    LaunchedEffect(restoreItemId, restoreFocusToken, restoreIndex) {
        if (restoreFocusToken <= 0 || restoreIndex < 0) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { rowFocusRequester.requestFocus() }
        onRestoreFocusHandled()
    }

    NetflixStyleRow(
        title = railTitle(rail),
        items = rail.items,
        onItemClick = onItemClick,
        trailerPreviewUrls = trailerPreviewUrls,
        trailerPreviewAudioUrls = trailerPreviewAudioUrls,
        logoOverrides = logoOverrides,
        trailerEnabled = trailerEnabled,
        trailerMuted = trailerMuted,
        onRequestTrailerPreview = onRequestTrailerPreview,
        onItemFocus = onRequestLogo,
        focusRequester = rowFocusRequester,
        initialSelectedIndex = initialSelectedIndex,
        showDescriptionInExpandedCard = true,
        onSelectedIndexChange = { selectedIndex ->
            onFocusedItemIndexChanged(selectedIndex)
            val total = rail.items.size
            val nearEnd = total > 0 && selectedIndex >= total - 4
            if (!nearEnd) {
                lastLoadMoreRequestTotal = -1
            } else if (rail.hasMore && !rail.isLoading && lastLoadMoreRequestTotal != total) {
                lastLoadMoreRequestTotal = total
                onLoadMore(rail.mediaType, rail.railType)
            }
        },
        onTrailerProgressChanged = onTrailerProgressChanged
    )
}

@Composable
private fun entityKindLabel(kind: TmdbEntityKind): String = when (kind) {
    TmdbEntityKind.COMPANY -> stringResource(R.string.tmdb_entity_kind_company)
    TmdbEntityKind.NETWORK -> stringResource(R.string.tmdb_entity_kind_network)
}

@Composable
private fun railTitle(rail: TmdbEntityRail): String {
    val mediaLabel = when (rail.mediaType) {
        TmdbEntityMediaType.MOVIE -> stringResource(R.string.type_movie)
        TmdbEntityMediaType.TV -> stringResource(R.string.type_series)
    }
    val railLabel = when (rail.railType) {
        TmdbEntityRailType.POPULAR -> stringResource(R.string.tmdb_entity_rail_popular)
        TmdbEntityRailType.TOP_RATED -> stringResource(R.string.tmdb_entity_rail_top_rated)
        TmdbEntityRailType.RECENT -> stringResource(R.string.tmdb_entity_rail_recent)
    }
    return "$mediaLabel • $railLabel"
}

private fun isLogoDarkAndMonochrome(bitmap: Bitmap): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    val step = maxOf(1, minOf(width, height) / 20)
    var totalLuminance = 0.0
    var totalSaturation = 0.0
    var count = 0
    val hsv = FloatArray(3)
    var x = 0
    while (x < width) {
        var y = 0
        while (y < height) {
            val pixel = bitmap.getPixel(x, y)
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha > 50) {
                val r = ((pixel ushr 16) and 0xFF) / 255.0
                val g = ((pixel ushr 8) and 0xFF) / 255.0
                val b = (pixel and 0xFF) / 255.0
                totalLuminance += 0.2126 * r + 0.7152 * g + 0.0722 * b
                android.graphics.Color.colorToHSV(pixel, hsv)
                totalSaturation += hsv[1]
                count++
            }
            y += step
        }
        x += step
    }
    if (count == 0) return false
    val avgLuminance = totalLuminance / count
    val avgSaturation = totalSaturation / count
    return avgLuminance < 0.3 && avgSaturation < 0.2
}
