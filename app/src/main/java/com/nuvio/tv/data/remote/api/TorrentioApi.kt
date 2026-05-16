package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Url

interface TorrentioApi {
    /**
     * Fetches streams from a fully-qualified Torrentio URL.
     * The URL may embed debrid config in its path (e.g.
     * https://torrentio.strem.fun/torbox=KEY/stream/movie/tt123.json)
     * in which case Torrentio resolves playable debrid links server-side.
     */
    @GET
    suspend fun getStreams(@Url url: String): TorrentioStreamResponse
}

@JsonClass(generateAdapter = true)
data class TorrentioStreamResponse(
    @Json(name = "streams") val streams: List<TorrentioStream>?
)

@JsonClass(generateAdapter = true)
data class TorrentioStream(
    @Json(name = "name") val name: String?,
    @Json(name = "title") val title: String?,
    @Json(name = "description") val description: String?,
    @Json(name = "infoHash") val infoHash: String?,
    @Json(name = "fileIdx") val fileIdx: Int?,
    @Json(name = "url") val url: String?,
    @Json(name = "behaviorHints") val behaviorHints: TorrentioStreamBehaviorHints?
)

@JsonClass(generateAdapter = true)
data class TorrentioStreamBehaviorHints(
    @Json(name = "bingeGroup") val bingeGroup: String?,
    @Json(name = "filename") val filename: String?,
    @Json(name = "videoSize") val videoSize: Long?,
    @Json(name = "videoHash") val videoHash: String?,
    @Json(name = "notWebReady") val notWebReady: Boolean?
)
