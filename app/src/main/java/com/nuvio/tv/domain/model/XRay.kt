package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

data class XRaySettings(
    val enabled: Boolean = true,
    val apiKey: String = "",
    val model: String = ""
) {
    val isConfigured: Boolean
        get() = enabled && apiKey.isNotBlank()
}

@Immutable
data class XRayPerson(
    val actorName: String,
    val characterName: String?,
    // Matched against the title's TMDB cast when possible — enables photo + filmography click-through.
    val castMatch: MetaCastMember?
)

@Immutable
data class XRaySceneInfo(
    val people: List<XRayPerson>,
    val sceneDescription: String?
)
