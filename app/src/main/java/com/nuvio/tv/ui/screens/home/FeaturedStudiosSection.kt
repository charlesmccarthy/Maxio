@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    val rowFocusRequester = remember { FocusRequester() }
    val rowListState = rememberLazyListState()
    var selectedStudioKey by rememberSaveable(studios.map { it.focusKey() }) {
        mutableStateOf<String?>(null)
    }
    var selectedIndex by rememberSaveable(studios.map { it.focusKey() }) {
        mutableIntStateOf(0)
    }
    var restoreFocusNonce by rememberSaveable(studios.map { it.focusKey() }) {
        mutableIntStateOf(0)
    }
    var rowFocused by remember { mutableStateOf(false) }

    LaunchedEffect(studios, selectedStudioKey) {
        val selectedKey = selectedStudioKey ?: return@LaunchedEffect
        val resolvedIndex = studios.indexOfFirst { it.focusKey() == selectedKey }
        if (resolvedIndex >= 0 && selectedIndex != resolvedIndex) {
            selectedIndex = resolvedIndex
        }
    }

    LaunchedEffect(selectedIndex, studios.size) {
        if (studios.isEmpty()) return@LaunchedEffect
        val clampedIndex = selectedIndex.coerceIn(0, studios.lastIndex)
        if (selectedIndex != clampedIndex) {
            selectedIndex = clampedIndex
            return@LaunchedEffect
        }
        runCatching { rowListState.scrollToItem(clampedIndex) }
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
        if (selectedStudioKey == null) return@LaunchedEffect
        if (restoreFocusNonce <= 0) return@LaunchedEffect
        runCatching { rowListState.scrollToItem(selectedIndex.coerceIn(0, studios.lastIndex)) }
        repeat(2) { withFrameNanos { } }
        runCatching { rowFocusRequester.requestFocus() }
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
            state = rowListState,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(rowFocusRequester)
                .onFocusChanged { state ->
                    rowFocused = state.isFocused || state.hasFocus
                }
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    val native = keyEvent.nativeKeyEvent
                    when {
                        native.action == AndroidKeyEvent.ACTION_DOWN &&
                            native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (studios.isNotEmpty()) {
                                selectedIndex = (selectedIndex + 1) % studios.size
                            }
                            true
                        }
                        native.action == AndroidKeyEvent.ACTION_DOWN &&
                            native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (studios.isNotEmpty()) {
                                selectedIndex = (selectedIndex - 1 + studios.size) % studios.size
                            }
                            true
                        }
                        native.action == AndroidKeyEvent.ACTION_UP &&
                            (native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                                native.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                                native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER) -> {
                            val studio = studios.getOrNull(selectedIndex) ?: return@onPreviewKeyEvent true
                            selectedStudioKey = studio.focusKey()
                            onStudioClick(studio)
                            true
                        }
                        else -> false
                    }
                },
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(
                items = studios,
                key = { index, studio ->
                    "${studio.entityKind}-${studio.tmdbId}-$index-${studio.logo.orEmpty()}"
                }
            ) { index, studio ->
                FeaturedStudioCard(
                    studio = studio,
                    isFocused = rowFocused && index == selectedIndex
                )
            }
        }
    }
}

@Composable
private fun FeaturedStudioCard(
    studio: FeaturedStudio,
    isFocused: Boolean
) {
    val context = LocalContext.current
    val density = LocalDensity.current
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
                Box(
                    modifier = Modifier
                        .width(148.dp)
                        .height(60.dp)
                        .clip(cardShape)
                        .background(Color.White)
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
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 16.dp)
                        )
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
