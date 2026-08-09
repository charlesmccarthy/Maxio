package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface OpenRouterApi {

    @POST("v1/chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: OpenRouterChatRequest
    ): Response<OpenRouterChatResponse>

    // Cheap key-validity probe used by the settings screen.
    @GET("v1/key")
    suspend fun getKeyInfo(
        @Header("Authorization") authorization: String
    ): Response<OpenRouterKeyResponse>
}

@JsonClass(generateAdapter = true)
data class OpenRouterChatRequest(
    @Json(name = "model") val model: String,
    @Json(name = "messages") val messages: List<OpenRouterMessage>,
    // Reasoning models (e.g. Gemini Pro) spend thinking tokens against this cap before
    // emitting the answer; a small cap truncates the JSON mid-response.
    @Json(name = "max_tokens") val maxTokens: Int = 16384
)

@JsonClass(generateAdapter = true)
data class OpenRouterMessage(
    @Json(name = "role") val role: String,
    @Json(name = "content") val content: List<OpenRouterContentPart>
)

@JsonClass(generateAdapter = true)
data class OpenRouterContentPart(
    @Json(name = "type") val type: String,
    @Json(name = "text") val text: String? = null,
    @Json(name = "image_url") val imageUrl: OpenRouterImageUrl? = null
)

@JsonClass(generateAdapter = true)
data class OpenRouterImageUrl(
    @Json(name = "url") val url: String
)

@JsonClass(generateAdapter = true)
data class OpenRouterChatResponse(
    @Json(name = "choices") val choices: List<OpenRouterChoice> = emptyList(),
    @Json(name = "error") val error: OpenRouterError? = null
)

@JsonClass(generateAdapter = true)
data class OpenRouterChoice(
    @Json(name = "message") val message: OpenRouterResponseMessage? = null
)

@JsonClass(generateAdapter = true)
data class OpenRouterResponseMessage(
    @Json(name = "content") val content: String? = null
)

@JsonClass(generateAdapter = true)
data class OpenRouterError(
    @Json(name = "message") val message: String? = null,
    @Json(name = "code") val code: Int? = null
)

@JsonClass(generateAdapter = true)
data class OpenRouterKeyResponse(
    @Json(name = "data") val data: OpenRouterKeyData? = null
)

@JsonClass(generateAdapter = true)
data class OpenRouterKeyData(
    @Json(name = "label") val label: String? = null
)
