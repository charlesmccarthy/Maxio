package com.nuvio.tv.ui.components

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private const val KEY_REPEAT_THROTTLE_MS = 200L
private const val ITEM_FOCUS_DEBOUNCE_MS = 130L
private const val TRAILER_REQUEST_DEBOUNCE_MS = 50L
private const val SLIDE_ANIM_MS = 180
private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")

/**
 * Netflix-style carousel row: expanded backdrop card on the left, poster strip on the right.
 * The entire row is one focusable unit — D-pad left/right changes the selected index,
 * wrapping around at boundaries. The expanded card never collapses or resizes.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NetflixStyleRow(
    title: String,
    subtitle: String? = null,
    items: List<MetaPreview>,
    onItemClick: (MetaPreview) -> Unit,
    onItemLongPress: ((MetaPreview) -> Unit)? = null,
    isItemWatched: (MetaPreview) -> Boolean = { false },
    posterCardStyle: PosterCardStyle = PosterCardDefaults.Style,
    trailerPreviewUrls: Map<String, String> = emptyMap(),
    trailerPreviewAudioUrls: Map<String, String> = emptyMap(),
    logoOverrides: Map<String, String> = emptyMap(),
    showSelectedPosterInStrip: Boolean = false,
    highlightSelectedPoster: Boolean = false,
    onRequestTrailerPreview: (MetaPreview) -> Unit = {},
    onItemFocus: (MetaPreview) -> Unit = {},
    trailerEnabled: Boolean = false,
    trailerMuted: Boolean = true,
    onSeeAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    initialSelectedIndex: Int = 0,
    onSelectedIndexChange: (Int) -> Unit = {},
    onRowFocused: () -> Unit = {},
    onTrailerProgressChanged: (itemId: String, positionMs: Long) -> Unit = { _, _ -> }
) {
    if (items.isEmpty()) return

    val expandedCardHeight = posterCardStyle.height * 1.15f
    val expandedCardWidth = 390.dp
    val posterWidth = posterCardStyle.width
    val posterHeight = posterCardStyle.height * 1.15f
    val cardShape = remember(posterCardStyle.cornerRadius) { RoundedCornerShape(posterCardStyle.cornerRadius) }
    val visiblePosterCount = 4

    var selectedIndex by remember { mutableIntStateOf(initialSelectedIndex.coerceIn(0, items.size - 1)) }
    var isFocused by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }
    var lastKeyRepeatTime by remember { mutableStateOf(0L) }
    // Track slide direction for animation: true = moving right (slide left), false = moving left
    var slideRight by remember { mutableStateOf(true) }
    val latestOnItemFocus by androidx.compose.runtime.rememberUpdatedState(onItemFocus)

    // Notify parent of index changes
    LaunchedEffect(selectedIndex) {
        onSelectedIndexChange(selectedIndex)
    }

    LaunchedEffect(initialSelectedIndex, items.size) {
        val clampedIndex = initialSelectedIndex.coerceIn(0, items.size - 1)
        if (selectedIndex != clampedIndex) {
            selectedIndex = clampedIndex
        }
    }

    // Request trailer preview with debounce when focused and index changes.
    // trailerEnabled must be a key because it loads asynchronously (300ms debounce
    // on preferences flow) — without it, the effect fires once with false and never retries.
    LaunchedEffect(isFocused, selectedIndex, trailerEnabled) {
        if (isFocused && trailerEnabled) {
            delay(TRAILER_REQUEST_DEBOUNCE_MS)
            if (isFocused) {
                onRequestTrailerPreview(items[selectedIndex])
            }
        }
    }

    LaunchedEffect(isFocused, selectedIndex) {
        if (!isFocused) return@LaunchedEffect
        delay(ITEM_FOCUS_DEBOUNCE_MS)
        if (isFocused) {
            latestOnItemFocus(items[selectedIndex])
        }
    }

    // Notify parent when row gains focus
    LaunchedEffect(isFocused) {
        if (isFocused) onRowFocused()
    }

    val selectedTrailerPreviewUrl = trailerPreviewUrls[items[selectedIndex].id]
    val selectedTrailerPreviewAudioUrl = trailerPreviewAudioUrls[items[selectedIndex].id]

    Column(modifier = modifier.fillMaxWidth()) {
        // Title row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Clip
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioColors.TextTertiary
                    )
                }
            }
        }

        // Card row — single focusable unit
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { state ->
                    isFocused = state.isFocused || state.hasFocus
                }
                .focusable()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent

                    // Throttle ALL D-pad left/right presses (not just repeats) to prevent
                    // rapid index changes that cause AnimatedContent to get into a runaway loop
                    if (native.action == AndroidKeyEvent.ACTION_DOWN &&
                        (native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT ||
                         native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT)
                    ) {
                        val now = System.currentTimeMillis()
                        if (now - lastKeyRepeatTime < KEY_REPEAT_THROTTLE_MS) {
                            return@onPreviewKeyEvent true // consume — too fast
                        }
                        lastKeyRepeatTime = now
                    }

                    if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                        when (native.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                slideRight = true
                                selectedIndex = (selectedIndex + 1) % items.size
                                true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                slideRight = false
                                selectedIndex = (selectedIndex - 1 + items.size) % items.size
                                true
                            }
                            AndroidKeyEvent.KEYCODE_MENU -> {
                                if (onItemLongPress != null) {
                                    longPressTriggered = true
                                    onItemLongPress(items[selectedIndex])
                                    true
                                } else false
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                            AndroidKeyEvent.KEYCODE_ENTER,
                            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                val isLongPress = native.isLongPress || native.repeatCount > 0
                                if (isLongPress && onItemLongPress != null) {
                                    longPressTriggered = true
                                    onItemLongPress(items[selectedIndex])
                                    true
                                } else false
                            }
                            else -> false
                        }
                    } else if (native.action == AndroidKeyEvent.ACTION_UP) {
                        when (native.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                            AndroidKeyEvent.KEYCODE_ENTER,
                            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                if (longPressTriggered) {
                                    longPressTriggered = false
                                    true
                                } else {
                                    onItemClick(items[selectedIndex])
                                    true
                                }
                            }
                            else -> false
                        }
                    } else false
                },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Expanded card (left) — always expanded, shows backdrop for selected item
            ExpandedCarouselCard(
                items = items,
                selectedIndex = selectedIndex,
                width = expandedCardWidth,
                height = expandedCardHeight,
                shape = cardShape,
                isFocused = isFocused,
                trailerPreviewUrl = if (trailerEnabled) selectedTrailerPreviewUrl else null,
                trailerPreviewAudioUrl = if (trailerEnabled) selectedTrailerPreviewAudioUrl else null,
                trailerMuted = trailerMuted,
                logoOverrides = logoOverrides,
                onTrailerProgressChanged = onTrailerProgressChanged
            )

            // Poster strip (right) — fade+slide when index changes
            val actualPosterCount = if (showSelectedPosterInStrip) {
                minOf(visiblePosterCount, items.size)
            } else {
                minOf(visiblePosterCount, items.size - 1)
            }
            if (actualPosterCount > 0) {
                val startOffset = if (showSelectedPosterInStrip) 0 else 1
                // Fixed-width container prevents gap jitter between expanded card and poster strip
                val posterStripWidth = posterWidth * actualPosterCount + 12.dp * (actualPosterCount - 1)

                Box(
                    modifier = Modifier
                        .width(posterStripWidth)
                        .height(posterHeight)
                ) {
                    // Key on selectedIndex (simple Int) — keying on List<Int> causes
                    // AnimatedContent to get into runaway animation loops on rapid scrolling
                    AnimatedContent(
                        targetState = selectedIndex,
                        transitionSpec = {
                            val direction = if (slideRight) 1 else -1
                            (fadeIn(tween(SLIDE_ANIM_MS / 2)) + slideInHorizontally(
                                animationSpec = tween(SLIDE_ANIM_MS),
                                initialOffsetX = { fullWidth -> direction * fullWidth / 4 }
                            )) togetherWith (fadeOut(tween(SLIDE_ANIM_MS / 2)) + slideOutHorizontally(
                                animationSpec = tween(SLIDE_ANIM_MS),
                                targetOffsetX = { fullWidth -> -direction * fullWidth / 4 }
                            )) using SizeTransform(clip = true)
                        },
                        label = "posterSlide"
                    ) { animatedSelectedIndex ->
                        val posterIndices = (0 until actualPosterCount).map { i ->
                            (animatedSelectedIndex + startOffset + i) % items.size
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            posterIndices.forEach { posterIndex ->
                                val posterItem = items[posterIndex]
                                CarouselPosterCard(
                                    item = posterItem,
                                    width = posterWidth,
                                    height = posterHeight,
                                    shape = cardShape,
                                    isWatched = isItemWatched(posterItem),
                                    isSelected = highlightSelectedPoster && isFocused && posterIndex == animatedSelectedIndex
                                )
                            }
                        }
                    }
                }
            }
        }

        // Meta row — below cards: genre · year · rating + description
        ExpandedCardMeta(
            items = items,
            selectedIndex = selectedIndex,
            modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 8.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExpandedCarouselCard(
    items: List<MetaPreview>,
    selectedIndex: Int,
    width: Dp,
    height: Dp,
    shape: RoundedCornerShape,
    isFocused: Boolean,
    trailerPreviewUrl: String?,
    trailerPreviewAudioUrl: String?,
    trailerMuted: Boolean,
    logoOverrides: Map<String, String> = emptyMap(),
    onTrailerProgressChanged: (itemId: String, positionMs: Long) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val requestWidthPx = remember(width, density) { with(density) { width.roundToPx() } }
    val requestHeightPx = remember(height, density) { with(density) { height.roundToPx() } }

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .border(
                width = 2.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = shape
            )
    ) {
        // Inner Box: clipped — contains all visual content (backdrop, trailer, etc.)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
        ) {
            // Crossfade for smooth backdrop transitions (no directional slide)
            Crossfade(
                targetState = selectedIndex,
                animationSpec = tween(SLIDE_ANIM_MS),
                label = "expandedCardFade"
            ) { animatedIndex ->
                val item = items[animatedIndex]
                val backdropUrl = item.backdropUrl
                val imageModel = remember(backdropUrl, requestWidthPx, requestHeightPx) {
                    ImageRequest.Builder(context)
                        .data(backdropUrl)
                        .crossfade(false)
                        .memoryCacheKey("netflix_backdrop_${backdropUrl}_${requestWidthPx}x${requestHeightPx}")
                        .size(width = requestWidthPx, height = requestHeightPx)
                        .build()
                }
                val bgColor = NuvioColors.BackgroundCard
                val backgroundPainter = remember(bgColor) { androidx.compose.ui.graphics.painter.ColorPainter(bgColor) }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (!backdropUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = imageModel,
                            contentDescription = item.name,
                            modifier = Modifier.fillMaxSize(),
                            placeholder = backgroundPainter,
                            error = backgroundPainter,
                            fallback = backgroundPainter,
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(bgColor)
                        )
                    }

                    // Bottom gradient
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(96.dp)
                            .drawWithCache {
                                val gradient = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.76f)
                                    ),
                                    startY = 0f,
                                    endY = size.height
                                )
                                onDrawBehind { drawRect(gradient) }
                            }
                    )

                    // Logo or title overlay
                    ExpandedCardTitle(item = item, context = context, requestWidthPx = requestWidthPx, logoOverrides = logoOverrides)
                }
            }

            // Trailer overlay — outside Crossfade so it persists across transitions
            if (trailerPreviewUrl != null && isFocused) {
                TrailerPlayer(
                    trailerUrl = trailerPreviewUrl,
                    trailerAudioUrl = trailerPreviewAudioUrl,
                    isPlaying = true,
                    onEnded = {},
                    modifier = Modifier.fillMaxSize(),
                    muted = trailerMuted,
                    cropToFill = true,
                    overscanZoom = 1.35f,
                    onProgressChanged = { positionMs, _ ->
                        onTrailerProgressChanged(items[selectedIndex].id, positionMs)
                    }
                )
            }

            // Logo/title overlay — ON TOP of both backdrop and trailer (Netflix-style)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(96.dp)
                    .drawWithCache {
                        val gradient = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.8f)
                            ),
                            startY = 0f,
                            endY = size.height
                        )
                        onDrawBehind { drawRect(gradient) }
                    }
            )
            Crossfade(
                targetState = selectedIndex,
                animationSpec = tween(SLIDE_ANIM_MS),
                label = "titleOverlayFade"
            ) { animatedIndex ->
                ExpandedCardTitle(
                    item = items[animatedIndex],
                    context = context,
                    requestWidthPx = requestWidthPx,
                    logoOverrides = logoOverrides
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExpandedCardTitle(
    item: MetaPreview,
    context: android.content.Context,
    requestWidthPx: Int,
    logoOverrides: Map<String, String> = emptyMap()
) {
    val density = LocalDensity.current
    val logoRequestHeightPx = remember(density) { with(density) { 48.dp.roundToPx() } }
    val effectiveLogoUrl = logoOverrides[item.id] ?: item.logo
    var logoLoadFailed by remember(effectiveLogoUrl) { mutableStateOf(false) }
    val showLogo = !effectiveLogoUrl.isNullOrBlank() && !logoLoadFailed

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        if (showLogo) {
            val logoModel = remember(effectiveLogoUrl, requestWidthPx, logoRequestHeightPx) {
                ImageRequest.Builder(context)
                    .data(effectiveLogoUrl)
                    .crossfade(false)
                    .size(width = requestWidthPx, height = logoRequestHeightPx)
                    .build()
            }
            AsyncImage(
                model = logoModel,
                contentDescription = item.name,
                onError = { logoLoadFailed = true },
                modifier = Modifier
                    .height(48.dp)
                    .fillMaxWidth(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart
            )
        } else {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CarouselPosterCard(
    item: MetaPreview,
    width: Dp,
    height: Dp,
    shape: RoundedCornerShape,
    isWatched: Boolean,
    isSelected: Boolean = false
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val requestWidthPx = remember(width, density) { with(density) { width.roundToPx() } }
    val requestHeightPx = remember(height, density) { with(density) { height.roundToPx() } }
    val bgColor = NuvioColors.BackgroundCard
    val backgroundPainter = remember(bgColor) { androidx.compose.ui.graphics.painter.ColorPainter(bgColor) }

    val imageModel = remember(item.poster, requestWidthPx, requestHeightPx) {
        ImageRequest.Builder(context)
            .data(item.poster)
            .crossfade(false)
            .memoryCacheKey("netflix_poster_${item.poster}_${requestWidthPx}x${requestHeightPx}")
            .size(width = requestWidthPx, height = requestHeightPx)
            .build()
    }

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .border(
                width = 2.dp,
                color = if (isSelected) Color.White else Color.Transparent,
                shape = shape
            )
            .clip(shape)
    ) {
        if (!item.poster.isNullOrBlank()) {
            AsyncImage(
                model = imageModel,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                placeholder = backgroundPainter,
                error = backgroundPainter,
                fallback = backgroundPainter,
                contentScale = ContentScale.Crop
            )
        } else {
            MonochromePosterPlaceholder()
        }

        if (isWatched) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = stringResource(R.string.episodes_cd_watched),
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 6.dp, top = 6.dp)
                    .zIndex(2f)
                    .height(18.dp)
                    .width(18.dp)
                    .drawBehind {
                        drawCircle(
                            color = Color.Black,
                            radius = size.minDimension / 2f + 1.5f
                        )
                    }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExpandedCardMeta(
    items: List<MetaPreview>,
    selectedIndex: Int,
    modifier: Modifier = Modifier
) {
    Crossfade(
        targetState = selectedIndex,
        animationSpec = tween(SLIDE_ANIM_MS),
        label = "metaFade"
    ) { animatedIndex ->
        val item = items[animatedIndex]
        val metaTokens = remember(item.rawType, item.genres, item.releaseInfo, item.imdbRating) {
            buildList {
                add(item.apiType.replaceFirstChar { ch -> ch.uppercase() })
                item.genres.firstOrNull()?.let { add(it) }
                item.releaseInfo
                    ?.let { YEAR_REGEX.find(it)?.value }
                    ?.let { add(it) }
                item.imdbRating?.let { add(String.format("%.1f", it)) }
            }
        }

        Column(modifier = modifier) {
            if (metaTokens.isNotEmpty()) {
                Text(
                    text = metaTokens.joinToString("  \u2022  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.extendedColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            item.description?.takeIf { it.isNotBlank() }?.let { description ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
