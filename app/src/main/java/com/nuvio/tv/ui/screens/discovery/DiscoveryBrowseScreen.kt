package com.nuvio.tv.ui.screens.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.ContentCard
import com.nuvio.tv.ui.theme.NuvioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DiscoveryBrowseScreen(
    onNavigateToDetail: (String, String) -> Unit,
    onBackPress: () -> Unit,
    viewModel: DiscoveryBrowseViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()

    // Trigger load more when near the end
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = gridState.layoutInfo.totalItemsCount
            lastVisibleItem >= totalItems - 6
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && !state.isLoadingMore && state.hasMorePages) {
            viewModel.loadNextPage()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Text(
            text = state.browseName,
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioColors.TextPrimary,
            modifier = Modifier.padding(start = 48.dp, top = 24.dp, bottom = 16.dp)
        )

        if (state.isLoading && state.items.isEmpty()) {
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
        } else if (state.error != null && state.items.isEmpty()) {
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
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                state = gridState,
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.items, key = { it.id }) { item ->
                    ContentCard(
                        item = item,
                        onClick = {
                            viewModel.onItemClick(item, onNavigateToDetail)
                        },
                        modifier = Modifier.width(140.dp)
                    )
                }

                // Loading more indicator
                if (state.isLoadingMore) {
                    item {
                        Box(
                            modifier = Modifier
                                .width(140.dp)
                                .height(210.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Loading...",
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioColors.TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}
