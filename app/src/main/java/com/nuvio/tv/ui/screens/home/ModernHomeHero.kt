package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MDBListRatings
import kotlinx.coroutines.launch
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.theme.NuvioColors
import androidx.compose.ui.res.stringResource

private data class ModernHeroSecondaryMeta(
    val highlightText: String?,
    val ageRating: String?,
    val status: String?,
    val details: List<String>
)

@Composable
internal fun ModernHeroMediaLayer(
    heroBackdrop: String?,
    enrichmentActive: Boolean,
    shouldPlayHeroTrailer: Boolean,
    heroTrailerFirstFrameRendered: Boolean,
    heroTrailerUrl: String?,
    heroTrailerAudioUrl: String?,
    muted: Boolean,
    onTrailerEnded: () -> Unit,
    onFirstFrameRendered: () -> Unit,
    modifier: Modifier,
    requestWidthPx: Int,
    requestHeightPx: Int
) {
    val transitionProgressState = animateFloatAsState(
        targetValue = if (shouldPlayHeroTrailer && heroTrailerFirstFrameRendered) 1f else 0f,
        animationSpec = tween(durationMillis = 480),
        label = "heroBackdropTrailerCrossfadeProgress"
    )
    val localContext = LocalContext.current

    // Freeze the backdrop URL while enrichment is active — only update when enrichment ends
    // so Coil crossfade starts with the final URL, not an intermediate one.
    var stableBackdrop by remember { mutableStateOf(heroBackdrop) }
    if (!enrichmentActive) stableBackdrop = heroBackdrop

    val imageModel = remember(localContext, stableBackdrop, requestWidthPx, requestHeightPx) {
        ImageRequest.Builder(localContext)
            .data(stableBackdrop)
            .crossfade(400)
            .size(width = requestWidthPx, height = requestHeightPx)
            .build()
    }

    Box(modifier = modifier) {
        AsyncImage(
            model = imageModel,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (transitionProgressState.value > 0f) {
                        Modifier.graphicsLayer {
                            alpha = 1f - transitionProgressState.value
                        }
                    } else {
                        Modifier
                    }
                ),
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopEnd
        )

        if (shouldPlayHeroTrailer) {
            TrailerPlayer(
                trailerUrl = heroTrailerUrl,
                trailerAudioUrl = heroTrailerAudioUrl,
                isPlaying = true,
                onEnded = onTrailerEnded,
                onFirstFrameRendered = onFirstFrameRendered,
                muted = muted,
                cropToFill = true,
                overscanZoom = MODERN_TRAILER_OVERSCAN_ZOOM,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = transitionProgressState.value
                    }
            )
        }
    }
}

@Composable
internal fun ModernHeroGradientLayer(
    bgColor: Color,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .drawWithCache {
                val horizontalFadeEndX = size.width * 0.45f
                val horizontalGradient = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to bgColor,
                        0.22f to bgColor.copy(alpha = 0.86f),
                        0.46f to bgColor.copy(alpha = 0.56f),
                        0.76f to bgColor.copy(alpha = 0.16f),
                        1.0f to Color.Transparent
                    ),
                    startX = 0f,
                    endX = horizontalFadeEndX
                )

                val bottomStripStartY = size.height * 0.82f
                val verticalGradient = Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.40f to bgColor.copy(alpha = 0.25f),
                    0.75f to bgColor.copy(alpha = 0.65f),
                    1.0f to bgColor,
                    startY = bottomStripStartY,
                    endY = size.height
                )

                onDrawBehind {
                    // 1. Horizontal fade
                    drawRect(
                        brush = horizontalGradient,
                        topLeft = Offset(0f, 0f),
                        size = Size(horizontalFadeEndX, size.height)
                    )
                    
                    // 2. Bottom vertical strip
                    drawRect(
                        brush = verticalGradient,
                        topLeft = Offset(0f, bottomStripStartY),
                        size = Size(size.width, size.height - bottomStripStartY)
                    )
                }
            }
    )
}

@Composable
internal fun HeroTitleBlock(
    preview: HeroPreview?,
    enrichmentActive: Boolean = false,
    portraitMode: Boolean,
    mdbListRatings: com.nuvio.tv.domain.model.MDBListRatings? = null,
    modifier: Modifier = Modifier
) {
    var stablePreview by remember { mutableStateOf<HeroPreview?>(null) }

    if (!enrichmentActive && preview != null) stablePreview = preview
    if (enrichmentActive) stablePreview = null

    if (stablePreview == null) return
    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomStart
    ) {
        HeroTitleContent(preview = stablePreview!!, portraitMode = portraitMode, mdbListRatings = mdbListRatings)
    }
}

@Composable
private fun HeroTitleContent(
    preview: HeroPreview?,
    portraitMode: Boolean,
    mdbListRatings: com.nuvio.tv.domain.model.MDBListRatings? = null
) {
    if (preview == null) return
    val descriptionMaxLines = if (portraitMode) 4 else 5
    val descriptionScale = if (portraitMode) 0.90f else 1f
    val titleScale = if (portraitMode) 0.92f else 1f
    val metaScale = 1f
    val titleSpacing = 8.dp * titleScale
    val metaSpacing = 8.dp * metaScale
    val imdbMetaSpacing = 4.dp * metaScale
    val context = LocalContext.current
    val density = LocalDensity.current
    val headlineLarge = MaterialTheme.typography.headlineLarge
    val labelMedium = MaterialTheme.typography.labelMedium
    val bodyMedium = MaterialTheme.typography.bodyMedium
    val logoMaxWidthPx = remember(density) { with(density) { 220.dp.roundToPx() } }
    val logoHeightPx = remember(density) { with(density) { 100.dp.roundToPx() } }
    val logoModel = remember(context, preview.logo, logoMaxWidthPx, logoHeightPx) {
        preview.logo?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(false)
                .decoderFactory(SvgDecoder.Factory())
                .size(width = logoMaxWidthPx, height = logoHeightPx)
                .build()
        }
    }
    val imdbLogoModel = remember(context) {
        ImageRequest.Builder(context)
            .data(com.nuvio.tv.R.raw.imdb_logo_2016)
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    val scaledTitleStyle = remember(headlineLarge, titleScale) {
        headlineLarge.copy(
            fontSize = headlineLarge.fontSize * titleScale,
            lineHeight = headlineLarge.lineHeight * titleScale
        )
    }
    val scaledDescriptionStyle = remember(bodyMedium, descriptionScale) {
        bodyMedium.copy(
            fontSize = bodyMedium.fontSize * descriptionScale,
            lineHeight = bodyMedium.lineHeight * descriptionScale
        )
    }

    // Staggered entrance animation — title, meta/ratings, description
    val heroAnimKey = preview.title + (preview.poster ?: "")
    val titleAlpha = remember { Animatable(0f) }
    val titleOffsetY = remember { Animatable(20f) }
    val metaAlpha = remember { Animatable(0f) }
    val metaOffsetY = remember { Animatable(20f) }
    val descAlpha = remember { Animatable(0f) }
    val descOffsetY = remember { Animatable(20f) }
    val heroAnimSpec = tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)

    val heroAnimScope = rememberCoroutineScope()
    LaunchedEffect(heroAnimKey) {
        // Reset all to hidden
        titleAlpha.snapTo(0f); titleOffsetY.snapTo(20f)
        metaAlpha.snapTo(0f); metaOffsetY.snapTo(20f)
        descAlpha.snapTo(0f); descOffsetY.snapTo(20f)
        // Stagger: title 0ms, meta 80ms, description 160ms
        heroAnimScope.launch { titleAlpha.animateTo(1f, heroAnimSpec) }
        heroAnimScope.launch { titleOffsetY.animateTo(0f, heroAnimSpec) }
        heroAnimScope.launch {
            kotlinx.coroutines.delay(80)
            metaAlpha.animateTo(1f, heroAnimSpec)
        }
        heroAnimScope.launch {
            kotlinx.coroutines.delay(80)
            metaOffsetY.animateTo(0f, heroAnimSpec)
        }
        heroAnimScope.launch {
            kotlinx.coroutines.delay(160)
            descAlpha.animateTo(1f, heroAnimSpec)
        }
        heroAnimScope.launch {
            kotlinx.coroutines.delay(160)
            descOffsetY.animateTo(0f, heroAnimSpec)
        }
    }

    val titleOffsetPx = with(density) { titleOffsetY.value.dp.toPx() }
    val metaOffsetPx = with(density) { metaOffsetY.value.dp.toPx() }
    val descOffsetPx = with(density) { descOffsetY.value.dp.toPx() }

    Column(
        modifier = Modifier,
        verticalArrangement = Arrangement.spacedBy(titleSpacing)
    ) {
        var logoLoadFailed by remember(preview.logo) { mutableStateOf(false) }
        val showLogo = !preview.logo.isNullOrBlank() && !logoLoadFailed
        // Title / Logo — entrance animation group 1
        val titleAnimModifier = Modifier.graphicsLayer {
            alpha = titleAlpha.value
            translationY = titleOffsetPx
        }
        if (showLogo) {
            AsyncImage(
                model = logoModel,
                contentDescription = preview.title,
                onError = { logoLoadFailed = true },
                modifier = titleAnimModifier
                    .height(100.dp)
                    .widthIn(min = 100.dp, max = 220.dp)
                    .fillMaxWidth(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart
            )
        } else {
            Text(
                text = preview.title,
                style = scaledTitleStyle,
                color = NuvioColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = titleAnimModifier
            )
        }

        val strStatusEnded = stringResource(R.string.series_status_ended)
        val strStatusContinuing = stringResource(R.string.series_status_continuing)
        val strStatusCurrent = stringResource(R.string.series_status_current)
        val strStatusCancelled = stringResource(R.string.series_status_cancelled)
        val strStatusReleased = stringResource(R.string.series_status_released)
        val strStatusPlanned = stringResource(R.string.series_status_planned)
        val strStatusRumored = stringResource(R.string.series_status_rumored)
        val strStatusInProduction = stringResource(R.string.series_status_in_production)
        val strStatusPostProduction = stringResource(R.string.series_status_post_production)
        val secondaryMeta = remember(
            preview.secondaryHighlightText,
            preview.ageRatingText,
            preview.statusText,
            preview.languageText
        ) {
            ModernHeroSecondaryMeta(
                highlightText = preview.secondaryHighlightText?.trim()?.takeIf { it.isNotBlank() },
                ageRating = preview.ageRatingText?.trim()?.takeIf { it.isNotBlank() },
                status = when (preview.statusText?.trim()?.lowercase()) {
                    "ended" -> strStatusEnded.uppercase()
                    "continuing", "returning series" -> strStatusContinuing.uppercase()
                    "current" -> strStatusCurrent.uppercase()
                    "cancelled", "canceled" -> strStatusCancelled.uppercase()
                    "released" -> strStatusReleased.uppercase()
                    "planned" -> strStatusPlanned.uppercase()
                    "rumored" -> strStatusRumored.uppercase()
                    "in production" -> strStatusInProduction.uppercase()
                    "post production" -> strStatusPostProduction.uppercase()
                    else -> preview.statusText?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
                },
                details = buildList {
                    preview.languageText?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
                }
            )
        }

        val secondaryHighlightText = secondaryMeta.highlightText
        val ageRatingBadge = secondaryMeta.ageRating
        val statusBadge = secondaryMeta.status
        val secondaryDetails = secondaryMeta.details
        val hasSecondaryBadge = ageRatingBadge != null || statusBadge != null
        val hasMdbListRatings = mdbListRatings != null && !mdbListRatings.isEmpty()
        val showImdbInPrimary = !hasMdbListRatings && !preview.isSeries && !hasSecondaryBadge && !preview.imdbText.isNullOrBlank()
        val showImdbInPrimaryWithHighlight = showImdbInPrimary && secondaryHighlightText == null
        val showImdbInSecondary = !hasMdbListRatings && !preview.imdbText.isNullOrBlank() &&
            (preview.isSeries || hasSecondaryBadge || secondaryHighlightText != null)

        // Meta / ratings — entrance animation group 2
        Row(
            modifier = Modifier.fillMaxWidth().graphicsLayer {
                alpha = metaAlpha.value
                translationY = metaOffsetPx
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metaSpacing)
        ) {
            val leadingMetaText = remember(preview.contentTypeText, preview.genres) {
                buildList {
                    preview.contentTypeText?.takeIf { it.isNotBlank() }?.let(::add)
                    preview.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::add)
                }.joinToString(separator = " • ")
            }
            val hasLeadingMeta = leadingMetaText.isNotBlank()

            val runtimeText = preview.runtimeText
            val yearText = preview.yearText
            val imdbText = preview.imdbText
            val hasTrailingMeta = !runtimeText.isNullOrBlank() ||
                !yearText.isNullOrBlank() ||
                showImdbInPrimaryWithHighlight

            if (hasLeadingMeta) {
                Text(
                    text = leadingMetaText,
                    style = labelMedium,
                    color = NuvioColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (hasTrailingMeta) {
                        Modifier.weight(1f, fill = false)
                    } else {
                        Modifier
                    }
                )
            }

            if (hasTrailingMeta) {
                if (hasLeadingMeta) {
                    HeroMetaDivider(metaScale)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(metaSpacing)
                ) {
                    if (!runtimeText.isNullOrBlank()) {
                        Text(
                            text = runtimeText,
                            style = labelMedium,
                            color = NuvioColors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    if (!runtimeText.isNullOrBlank() && !yearText.isNullOrBlank()) {
                        HeroMetaDivider(metaScale)
                    }
                    if (!yearText.isNullOrBlank()) {
                        Text(
                            text = yearText,
                            style = labelMedium,
                            color = NuvioColors.TextSecondary,
                            maxLines = 1
                        )
                    }
                    if (showImdbInPrimaryWithHighlight && !imdbText.isNullOrBlank()) {
                        HeroImdbMeta(
                            imdbText = imdbText,
                            imdbLogoModel = imdbLogoModel,
                            textStyle = labelMedium,
                            textColor = NuvioColors.TextSecondary,
                            logoSize = 30.dp * metaScale,
                            spacing = imdbMetaSpacing
                        )
                    }
                }
            }
        }

        if (secondaryHighlightText != null || ageRatingBadge != null || showImdbInSecondary || statusBadge != null || secondaryDetails.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    alpha = metaAlpha.value
                    translationY = metaOffsetPx
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(metaSpacing)
            ) {
                val semiBoldLabelMedium = remember(labelMedium) { labelMedium.copy(fontWeight = FontWeight.SemiBold) }
        secondaryHighlightText?.let { text ->
                    Text(
                        text = text,
                        style = semiBoldLabelMedium,
                        color = NuvioColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (secondaryHighlightText != null && (hasSecondaryBadge || showImdbInSecondary || secondaryDetails.isNotEmpty())) {
                    HeroMetaDivider(metaScale)
                }
                if (ageRatingBadge != null && statusBadge != null) {
                    HeroCombinedMetaBadge(
                        leftText = ageRatingBadge,
                        rightText = statusBadge,
                        textStyle = labelMedium,
                        contentColor = NuvioColors.TextPrimary
                    )
                } else {
                    ageRatingBadge?.let { badge ->
                        HeroMetaBadge(
                            text = badge,
                            textStyle = labelMedium,
                            contentColor = NuvioColors.TextPrimary
                        )
                    }
                    statusBadge?.let { badge ->
                        HeroMetaBadge(
                            text = badge,
                            textStyle = labelMedium,
                            contentColor = NuvioColors.TextPrimary
                        )
                    }
                }
                if ((ageRatingBadge != null || statusBadge != null) && (showImdbInSecondary || secondaryDetails.isNotEmpty())) {
                    HeroMetaDivider(metaScale)
                }
                if (showImdbInSecondary) {
                    HeroImdbMeta(
                        imdbText = preview.imdbText.orEmpty(),
                        imdbLogoModel = imdbLogoModel,
                        textStyle = labelMedium,
                        textColor = NuvioColors.TextSecondary,
                        logoSize = 30.dp * metaScale,
                        spacing = imdbMetaSpacing
                    )
                }
                if (showImdbInSecondary && secondaryDetails.isNotEmpty()) {
                    HeroMetaDivider(metaScale)
                }
                secondaryDetails.forEachIndexed { index, value ->
                    Text(
                        text = value,
                        style = labelMedium,
                        color = NuvioColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (index < secondaryDetails.lastIndex) {
                        HeroMetaDivider(metaScale)
                    }
                }
            }
        }

        if (hasMdbListRatings) {
            Box(modifier = Modifier.graphicsLayer {
                alpha = metaAlpha.value
                translationY = metaOffsetPx
            }) {
                HeroMdbListRatingsRow(
                    ratings = mdbListRatings!!,
                    metaScale = metaScale
                )
            }
        }

        // Description — entrance animation group 3
        preview.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = scaledDescriptionStyle,
                color = NuvioColors.TextPrimary,
                maxLines = descriptionMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer {
                    alpha = descAlpha.value
                    translationY = descOffsetPx
                }
            )
        }
    }
}

@Composable
private fun HeroImdbMeta(
    imdbText: String,
    imdbLogoModel: Any,
    textStyle: androidx.compose.ui.text.TextStyle,
    textColor: Color,
    logoSize: androidx.compose.ui.unit.Dp,
    spacing: androidx.compose.ui.unit.Dp
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing)
    ) {
        AsyncImage(
            model = imdbLogoModel,
            contentDescription = "IMDb",
            modifier = Modifier.size(logoSize),
            contentScale = ContentScale.Fit
        )
        Text(
            text = imdbText,
            style = textStyle,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroCombinedMetaBadge(
    leftText: String,
    rightText: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    contentColor: Color
) {
    val dividerColor = contentColor.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(
                border = BorderStroke(1.dp, dividerColor),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val semiBoldStyle = remember(textStyle) { textStyle.copy(fontWeight = FontWeight.SemiBold) }
        Text(
            text = leftText,
            style = semiBoldStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(12.dp)
                .background(dividerColor)
        )
        Text(
            text = rightText,
            style = semiBoldStyle,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroMetaBadge(
    text: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    contentColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(
                border = BorderStroke(1.dp, contentColor.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = remember(textStyle) { textStyle.copy(fontWeight = FontWeight.SemiBold) },
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeroMetaDivider(scale: Float) {
    Box(
        modifier = Modifier
            .size((4.dp * scale).coerceAtLeast(2.dp))
            .clip(RoundedCornerShape(percent = 50))
            .background(NuvioColors.TextTertiary.copy(alpha = 0.78f))
    )
}

@Composable
private fun HeroMdbListRatingsRow(
    ratings: MDBListRatings,
    metaScale: Float
) {
    val context = LocalContext.current
    val logoSize = 22.dp * metaScale
    val textStyle = MaterialTheme.typography.labelMedium
    val textColor = NuvioColors.TextSecondary

    val svgItems = remember(ratings) {
        listOf(
            Triple("trakt", R.raw.mdblist_trakt, ratings.trakt),
            Triple("imdb", R.raw.imdb_logo_2016, ratings.imdb),
            Triple("tmdb", R.raw.mdblist_tmdb, ratings.tmdb),
            Triple("letterboxd", R.raw.mdblist_letterboxd, ratings.letterboxd),
            Triple("tomatoes", R.raw.mdblist_tomatoes, ratings.tomatoes)
        ).filter { it.third != null }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp * metaScale),
        verticalAlignment = Alignment.CenterVertically
    ) {
        svgItems.forEach { (provider, logoRes, rating) ->
            val resolvedRating = rating ?: return@forEach
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp * metaScale),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val model = remember(context, logoRes) {
                    ImageRequest.Builder(context)
                        .data(logoRes)
                        .decoderFactory(SvgDecoder.Factory())
                        .build()
                }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier.size(logoSize),
                    contentScale = ContentScale.Fit
                )
                Text(
                    text = formatHeroRating(provider, resolvedRating),
                    style = textStyle,
                    color = textColor,
                    maxLines = 1
                )
            }
        }

        ratings.audience?.let { rating ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp * metaScale),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.mdblist_audience),
                    contentDescription = null,
                    modifier = Modifier.size(logoSize)
                )
                Text(
                    text = formatHeroRating("audience", rating),
                    style = textStyle,
                    color = textColor,
                    maxLines = 1
                )
            }
        }

        ratings.metacritic?.let { rating ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp * metaScale),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.drawable.mdblist_metacritic),
                    contentDescription = null,
                    modifier = Modifier.size(logoSize)
                )
                Text(
                    text = formatHeroRating("metacritic", rating),
                    style = textStyle,
                    color = textColor,
                    maxLines = 1
                )
            }
        }
    }
}

private fun formatHeroRating(provider: String, rating: Double): String {
    return when (provider) {
        "imdb", "tmdb", "letterboxd" -> String.format("%.1f", rating)
        else -> {
            if (rating % 1.0 == 0.0) rating.toInt().toString() else String.format("%.1f", rating)
        }
    }
}
