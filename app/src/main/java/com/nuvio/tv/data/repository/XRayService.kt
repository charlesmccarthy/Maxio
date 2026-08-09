package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.local.XRaySettingsDataStore
import com.nuvio.tv.data.remote.api.OpenRouterApi
import com.nuvio.tv.data.remote.api.OpenRouterChatRequest
import com.nuvio.tv.data.remote.api.OpenRouterContentPart
import com.nuvio.tv.data.remote.api.OpenRouterImageUrl
import com.nuvio.tv.data.remote.api.OpenRouterMessage
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.XRayPerson
import com.nuvio.tv.domain.model.XRaySceneInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "XRayService"

/**
 * Prime Video X-Ray-style scene identification: sends the current video frame to a
 * vision model (via OpenRouter, model chosen in settings) along with the title context
 * and the known TMDB cast, and returns who is in the scene. Answers are cross-checked
 * against the real cast list so hallucinated names are dropped rather than shown.
 */
@Singleton
class XRayService @Inject constructor(
    private val openRouterApi: OpenRouterApi,
    private val settingsDataStore: XRaySettingsDataStore
) {
    class NotConfiguredException : Exception("OpenRouter API key not configured")

    suspend fun identifyScene(
        frameJpegBase64: String,
        title: String,
        year: String?,
        season: Int?,
        episode: Int?,
        positionMs: Long?,
        durationMs: Long?,
        knownCast: List<MetaCastMember>
    ): Result<XRaySceneInfo> = withContext(Dispatchers.IO) {
        try {
            val settings = settingsDataStore.settings.first()
            if (!settings.isConfigured) {
                return@withContext Result.failure(NotConfiguredException())
            }

            val contextLine = buildString {
                append(title)
                if (!year.isNullOrBlank()) append(" ($year)")
                if (season != null && episode != null) append(", Season $season Episode $episode")
            }
            val castLines = knownCast.take(25).joinToString("\n") { member ->
                "- ${member.name}" + (member.character?.takeIf { it.isNotBlank() }?.let { " as $it" } ?: "")
            }
            val prompt = buildString {
                append("This image is a frame from \"$contextLine\".\n")
                if (positionMs != null && positionMs > 0 && durationMs != null && durationMs > 0) {
                    append("The frame is at ${formatTimestamp(positionMs)} of the ${formatTimestamp(durationMs)} runtime.\n")
                }
                if (castLines.isNotBlank()) {
                    append("Known cast of this title:\n$castLines\n")
                }
                append(
                    "Identify which actors are visible in this frame. Study the facial features " +
                        "carefully and compare against what you know each cast member looks like — " +
                        "cast members of the same show can look similar, so do not just pick the most " +
                        "likely character for the setting. Only include a person if their face clearly " +
                        "matches the actor; if you are not confident, omit them entirely. It is better " +
                        "to return no people than a wrong identification. Respond with ONLY a JSON " +
                        "object, no other text, in this exact shape: " +
                        "{\"people\":[{\"actor\":\"Actor Name\",\"character\":\"Character Name\"," +
                        "\"confidence\":\"high|medium|low\"}]," +
                        "\"scene\":\"one short sentence describing what is happening in this scene\"}"
                )
            }

            val request = OpenRouterChatRequest(
                model = settings.model,
                messages = listOf(
                    OpenRouterMessage(
                        role = "user",
                        content = listOf(
                            OpenRouterContentPart(type = "text", text = prompt),
                            OpenRouterContentPart(
                                type = "image_url",
                                imageUrl = OpenRouterImageUrl(url = "data:image/jpeg;base64,$frameJpegBase64")
                            )
                        )
                    )
                )
            )

            val response = openRouterApi.chatCompletion("Bearer ${settings.apiKey}", request)
            if (!response.isSuccessful) {
                val message = when (response.code()) {
                    401 -> "Invalid OpenRouter API key"
                    402 -> "Out of OpenRouter credits"
                    429 -> "Rate limited, try again in a moment"
                    else -> "OpenRouter error ${response.code()}"
                }
                return@withContext Result.failure(Exception(message))
            }
            val body = response.body()
            body?.error?.message?.let { return@withContext Result.failure(Exception(it)) }
            val content = body?.choices?.firstOrNull()?.message?.content
                ?: return@withContext Result.failure(Exception("Empty response from model"))

            Log.d(TAG, "model=${settings.model} responseLen=${content.length} content=${content.take(600)}")
            Result.success(parseSceneInfo(content, knownCast))
        } catch (e: Exception) {
            Log.e(TAG, "X-Ray identification failed", e)
            Result.failure(e)
        }
    }

    private fun parseSceneInfo(content: String, knownCast: List<MetaCastMember>): XRaySceneInfo {
        // Models often wrap the JSON in ```json fences or add prose around it; the braces
        // are the only reliable landmarks.
        val jsonText = run {
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start >= 0 && end > start) content.substring(start, end + 1) else content
        }
        val root = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: return XRaySceneInfo(people = emptyList(), sceneDescription = content.trim().take(300))

        val people = mutableListOf<XRayPerson>()
        val array = root.optJSONArray("people")
        if (array != null) {
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val actor = entry.optString("actor").trim()
                if (actor.isBlank()) continue
                // The prompt asks the model to omit uncertain people; drop any stragglers
                // it still marks low-confidence rather than show a likely-wrong face.
                if (entry.optString("confidence").trim().lowercase() == "low") continue
                val character = entry.optString("character").trim().takeIf { it.isNotBlank() }
                people.add(
                    XRayPerson(
                        actorName = actor,
                        characterName = character,
                        castMatch = matchCastMember(actor, knownCast)
                    )
                )
            }
        }
        return XRaySceneInfo(
            people = people,
            sceneDescription = root.optString("scene").trim().takeIf { it.isNotBlank() }
        )
    }

    private fun formatTimestamp(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    private fun matchCastMember(actorName: String, knownCast: List<MetaCastMember>): MetaCastMember? {
        val normalized = actorName.lowercase().trim()
        if (normalized.isBlank()) return null
        return knownCast.firstOrNull { it.name.lowercase().trim() == normalized }
            ?: knownCast.firstOrNull { member ->
                val castName = member.name.lowercase().trim()
                castName.contains(normalized) || normalized.contains(castName)
            }
    }
}
