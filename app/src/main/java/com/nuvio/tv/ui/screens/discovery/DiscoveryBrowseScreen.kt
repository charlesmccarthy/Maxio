package com.nuvio.tv.ui.screens.discovery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NetflixStyleRow
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DiscoveryBrowseScreen(
    onNavigateToDetail: (String, String) -> Unit,
    onBackPress: () -> Unit,
    viewModel: DiscoveryBrowseViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Text(
            text = state.browseName,
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioColors.TextPrimary,
            modifier = Modifier.padding(start = 48.dp, top = 24.dp, bottom = 16.dp)
        )

        if (state.isLoading && state.rows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
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
                    Button(onClick = { viewModel.onRetry() }) {
                        Text("Retry")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.rows, key = { it.title }) { row ->
                    NetflixStyleRow(
                        title = row.title,
                        items = row.items,
                        onItemClick = { item ->
                            viewModel.storeActiveTrailer(item)
                            viewModel.onItemClick(item, onNavigateToDetail)
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

                item(key = "spacer") { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}
