@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.DebridService
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun DebridSettingsContent(
    viewModel: DebridSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showApiKeyDialog by remember { mutableStateOf<DebridService?>(null) }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = "Debrid",
            subtitle = "Stream through Torbox, Real-Debrid and AllDebrid — enable as many as you want"
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "debrid_enabled") {
                    SettingsToggleRow(
                        title = "Enable Debrid Streaming",
                        subtitle = "Fetch and resolve torrent streams through your debrid service",
                        checked = uiState.enabled,
                        onToggle = { viewModel.setEnabled(!uiState.enabled) },
                        modifier = if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )
                }

                item(key = "debrid_torbox_toggle") {
                    SettingsToggleRow(
                        title = "Torbox",
                        subtitle = "Use Torbox as a stream source",
                        checked = uiState.torboxEnabled,
                        enabled = uiState.enabled,
                        onToggle = {
                            viewModel.setServiceEnabled(DebridService.TORBOX, !uiState.torboxEnabled)
                        }
                    )
                }
                item(key = "debrid_torbox_key") {
                    SettingsActionRow(
                        title = "Torbox API Key",
                        subtitle = "api.torbox.app",
                        value = maskDebridKey(uiState.torboxApiKey),
                        onClick = { showApiKeyDialog = DebridService.TORBOX },
                        enabled = uiState.enabled && uiState.torboxEnabled
                    )
                }

                item(key = "debrid_rd_toggle") {
                    SettingsToggleRow(
                        title = "Real-Debrid",
                        subtitle = "Use Real-Debrid as a stream source",
                        checked = uiState.realDebridEnabled,
                        enabled = uiState.enabled,
                        onToggle = {
                            viewModel.setServiceEnabled(DebridService.REAL_DEBRID, !uiState.realDebridEnabled)
                        }
                    )
                }
                item(key = "debrid_rd_key") {
                    SettingsActionRow(
                        title = "Real-Debrid API Key",
                        subtitle = "real-debrid.com",
                        value = maskDebridKey(uiState.realDebridApiKey),
                        onClick = { showApiKeyDialog = DebridService.REAL_DEBRID },
                        enabled = uiState.enabled && uiState.realDebridEnabled
                    )
                }

                item(key = "debrid_ad_toggle") {
                    SettingsToggleRow(
                        title = "AllDebrid",
                        subtitle = "Use AllDebrid as a stream source",
                        checked = uiState.allDebridEnabled,
                        enabled = uiState.enabled,
                        onToggle = {
                            viewModel.setServiceEnabled(DebridService.ALL_DEBRID, !uiState.allDebridEnabled)
                        }
                    )
                }
                item(key = "debrid_ad_key") {
                    SettingsActionRow(
                        title = "AllDebrid API Key",
                        subtitle = "alldebrid.com",
                        value = maskDebridKey(uiState.allDebridApiKey),
                        onClick = { showApiKeyDialog = DebridService.ALL_DEBRID },
                        enabled = uiState.enabled && uiState.allDebridEnabled
                    )
                }
            }
        }
    }

    showApiKeyDialog?.let { service ->
        val currentKey = when (service) {
            DebridService.TORBOX -> uiState.torboxApiKey
            DebridService.REAL_DEBRID -> uiState.realDebridApiKey
            DebridService.ALL_DEBRID -> uiState.allDebridApiKey
        }
        DebridApiKeyDialog(
            title = "${service.displayName} API Key",
            currentValue = currentKey,
            onSave = { key ->
                when (service) {
                    DebridService.TORBOX -> viewModel.setTorboxApiKey(key)
                    DebridService.REAL_DEBRID -> viewModel.setRealDebridApiKey(key)
                    DebridService.ALL_DEBRID -> viewModel.setAllDebridApiKey(key)
                }
                showApiKeyDialog = null
            },
            onClear = {
                when (service) {
                    DebridService.TORBOX -> viewModel.setTorboxApiKey("")
                    DebridService.REAL_DEBRID -> viewModel.setRealDebridApiKey("")
                    DebridService.ALL_DEBRID -> viewModel.setAllDebridApiKey("")
                }
                showApiKeyDialog = null
            },
            onDismiss = { showApiKeyDialog = null }
        )
    }
}

@Composable
private fun DebridApiKeyDialog(
    title: String,
    currentValue: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = "Enter your API key",
        width = 700.dp
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioColors.BackgroundElevated,
                focusedContainerColor = NuvioColors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(1.dp, NuvioColors.Border),
                    shape = RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    keyboardActions = KeyboardActions(
                        onDone = { keyboardController?.hide() }
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioColors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NuvioColors.Primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = "Paste API key here",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioColors.TextTertiary
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundElevated,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text("Cancel")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onClear,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundElevated,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text("Clear")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onSave(value) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.BackgroundCard,
                    contentColor = NuvioColors.TextPrimary
                )
            ) {
                Text("Save")
            }
        }
    }
}

private fun maskDebridKey(key: String): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return "Not set"
    return if (trimmed.length <= 4) "••••" else "••••••${trimmed.takeLast(4)}"
}
