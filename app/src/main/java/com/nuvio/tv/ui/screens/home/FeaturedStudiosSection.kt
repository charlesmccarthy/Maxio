@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun FeaturedStudiosSection(
    studios: List<FeaturedStudio>,
    onStudioClick: (FeaturedStudio) -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.home_featured_studios)
) {
    if (studios.isEmpty()) return

    val lifecycleOwner = LocalLifecycleOwner.current
    val focusRequesters = remember(studios) {
        studios.associate { studio -> studio.focusKey() to FocusRequester() }
    }
    var selectedStudioKey by rememberSaveable(studios.map { it.focusKey() }) {
        mutableStateOf<String?>(null)
    }
    var restoreFocusNonce by rememberSaveable(studios.map { it.focusKey() }) {
        mutableIntStateOf(0)
    }

    DisposableEffect(lifecycleOwner, selectedStudioKey, studios) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                selectedStudioKey != null &&
                studios.any { it.focusKey() == selectedStudioKey }
            ) {
                restoreFocusNonce += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(restoreFocusNonce, selectedStudioKey, studios) {
        val key = selectedStudioKey ?: return@LaunchedEffect
        if (restoreFocusNonce <= 0) return@LaunchedEffect
        val requester = focusRequesters[key] ?: return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = NuvioColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 48.dp)
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(
                items = studios,
                key = { index, studio ->
                    "${studio.entityKind}-${studio.tmdbId}-$index-${studio.logo.orEmpty()}"
                }
            ) { _, studio ->
                FeaturedStudioCard(
                    studio = studio,
                    focusRequester = focusRequesters[studio.focusKey()],
                    onClick = {
                        selectedStudioKey = studio.focusKey()
                        onStudioClick(studio)
                    }
                )
            }
        }
    }
}

@Composable
private fun FeaturedStudioCard(
    studio: FeaturedStudio,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var isFocused by remember(studio.focusKey()) { mutableStateOf(false) }
    val outerShape = remember { RoundedCornerShape(18.dp) }
    val spacerShape = remember { RoundedCornerShape(16.dp) }
    val cardShape = remember { RoundedCornerShape(12.dp) }
    val outlinePadding = if (isFocused) 5.dp else 0.dp
    val spacerPadding = if (isFocused) 4.dp else 0.dp
    val logoWidthPx = remember(density) { with(density) { 148.dp.roundToPx() } }
    val logoHeightPx = remember(density) { with(density) { 60.dp.roundToPx() } }
    val logoModel = remember(context, studio.logo, logoWidthPx, logoHeightPx) {
        studio.logo?.let { logo ->
            ImageRequest.Builder(context)
                .data(logo)
                .crossfade(true)
                .size(width = logoWidthPx, height = logoHeightPx)
                .build()
        }
    }
    var logoLoadFailed by remember(studio.logo) { mutableStateOf(false) }

    Column(
        modifier = Modifier.width(148.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .clip(outerShape)
                .background(if (isFocused) Color.White else Color.Transparent)
                .padding(outlinePadding)
        ) {
            Box(
                modifier = Modifier
                    .clip(spacerShape)
                    .background(if (isFocused) Color.Black.copy(alpha = 0.92f) else Color.Transparent)
                    .padding(spacerPadding)
            ) {
                Card(
                    onClick = onClick,
                    modifier = Modifier
                        .width(148.dp)
                        .height(60.dp)
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .onFocusChanged { state ->
                            isFocused = state.isFocused || state.hasFocus
                        },
                    colors = CardDefaults.colors(
                        containerColor = Color.White,
                        focusedContainerColor = Color.White
                    ),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = androidx.compose.foundation.BorderStroke(0.dp, Color.Transparent),
                            shape = cardShape
                        )
                    ),
                    scale = CardDefaults.scale(focusedScale = 1.02f)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .clip(cardShape)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        if (logoModel != null && !logoLoadFailed) {
                            AsyncImage(
                                model = logoModel,
                                contentDescription = studio.name,
                                onError = { logoLoadFailed = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Text(
                                text = studio.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = NuvioTheme.extendedColors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = studio.name,
            style = MaterialTheme.typography.labelMedium,
            color = if (isFocused) Color.White else NuvioColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 8.dp)
                .padding(horizontal = 4.dp)
                .fillMaxWidth()
        )
    }
}

private fun FeaturedStudio.focusKey(): String = "${entityKind}:${tmdbId}"
