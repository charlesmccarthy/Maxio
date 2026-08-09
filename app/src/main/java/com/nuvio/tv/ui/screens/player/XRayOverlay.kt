package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.XRayPerson
import com.nuvio.tv.domain.model.XRaySceneInfo
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun XRayOverlay(
    visible: Boolean,
    isLoading: Boolean,
    error: String?,
    scene: XRaySceneInfo?,
    onClose: () -> Unit,
    onPersonClick: (personId: Int, personName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    // With clickable people the cards own focus and D-pad center; otherwise the scaffold
    // captures keys so center/back dismiss the informational states.
    val hasClickablePeople = !isLoading && error == null &&
        scene?.people?.any { it.castMatch?.tmdbId != null } == true
    val firstCardFocusRequester = remember { FocusRequester() }

    LaunchedEffect(visible, hasClickablePeople) {
        if (visible && hasClickablePeople) {
            runCatching { firstCardFocusRequester.requestFocus() }
        }
    }

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onClose,
        modifier = modifier,
        captureKeys = !hasClickablePeople,
        dismissOnCenter = true,
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 36.dp, bottom = 36.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.BottomStart),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                text = stringResource(R.string.xray_title).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioColors.TextTertiary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))

            when {
                isLoading -> {
                    Text(
                        text = stringResource(R.string.xray_identifying),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }

                error != null -> {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioColors.TextSecondary,
                        modifier = Modifier.widthIn(max = 560.dp)
                    )
                }

                scene != null -> {
                    if (scene.people.isEmpty()) {
                        Text(
                            text = stringResource(R.string.xray_no_people),
                            style = MaterialTheme.typography.titleMedium,
                            color = NuvioColors.TextSecondary
                        )
                    } else {
                        val firstClickableName = scene.people
                            .firstOrNull { it.castMatch?.tmdbId != null }?.actorName
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(scene.people, key = { it.actorName }) { person ->
                                val personId = person.castMatch?.tmdbId
                                if (personId != null) {
                                    Card(
                                        onClick = { onPersonClick(personId, person.actorName) },
                                        modifier = if (person.actorName == firstClickableName) {
                                            Modifier.focusRequester(firstCardFocusRequester)
                                        } else {
                                            Modifier
                                        },
                                        colors = CardDefaults.colors(
                                            containerColor = Color.Transparent,
                                            focusedContainerColor = Color.White.copy(alpha = 0.1f)
                                        ),
                                        border = CardDefaults.border(
                                            focusedBorder = Border(
                                                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                                                shape = RoundedCornerShape(14.dp)
                                            )
                                        ),
                                        shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
                                        scale = CardDefaults.scale(focusedScale = 1.05f)
                                    ) {
                                        Box(modifier = Modifier.padding(8.dp)) {
                                            XRayPersonCard(person)
                                        }
                                    }
                                } else {
                                    Box(modifier = Modifier.padding(8.dp)) {
                                        XRayPersonCard(person)
                                    }
                                }
                            }
                        }
                    }
                    scene.sceneDescription?.let { description ->
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 640.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun XRayPersonCard(person: XRayPerson) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(110.dp)
    ) {
        val photo = person.castMatch?.photo
        if (!photo.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(photo)
                    .crossfade(true)
                    .build(),
                contentDescription = person.actorName,
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(NuvioColors.BackgroundCard),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = person.actorName
                        .split(" ")
                        .mapNotNull { it.firstOrNull()?.uppercase() }
                        .take(2)
                        .joinToString(""),
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextSecondary
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = person.actorName,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val character = person.characterName ?: person.castMatch?.character
        if (!character.isNullOrBlank()) {
            Text(
                text = character,
                style = MaterialTheme.typography.labelSmall,
                color = NuvioColors.TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
