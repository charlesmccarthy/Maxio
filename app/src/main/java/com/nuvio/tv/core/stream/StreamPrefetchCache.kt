package com.nuvio.tv.core.stream

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamPrefetchCache @Inject constructor() {

    data class PrefetchEntry(
        val videoId: String,
        val contentType: String,
        val season: Int?,
        val episode: Int?,
        val addonStreams: List<AddonStreams>,
        val isComplete: Boolean,
        val timestampMs: Long
    )

    @Volatile
    private var entry: PrefetchEntry? = null

    private val _streamFlow = MutableSharedFlow<NetworkResult<List<AddonStreams>>>(
        replay = 1,
        extraBufferCapacity = 16
    )
    val streamFlow: SharedFlow<NetworkResult<List<AddonStreams>>> = _streamFlow.asSharedFlow()

    private companion object {
        const val STALE_THRESHOLD_MS = 5L * 60L * 1000L // 5 minutes
    }

    fun matches(videoId: String, contentType: String, season: Int?, episode: Int?): Boolean {
        val e = entry ?: return false
        return e.videoId == videoId &&
            e.contentType.equals(contentType, ignoreCase = true) &&
            e.season == season &&
            e.episode == episode &&
            (System.currentTimeMillis() - e.timestampMs) < STALE_THRESHOLD_MS
    }

    fun startPrefetch(videoId: String, contentType: String, season: Int?, episode: Int?) {
        entry = PrefetchEntry(
            videoId = videoId,
            contentType = contentType,
            season = season,
            episode = episode,
            addonStreams = emptyList(),
            isComplete = false,
            timestampMs = System.currentTimeMillis()
        )
        _streamFlow.resetReplayCache()
    }

    fun emitResult(result: NetworkResult<List<AddonStreams>>) {
        if (result is NetworkResult.Success) {
            val e = entry ?: return
            entry = e.copy(addonStreams = result.data, timestampMs = System.currentTimeMillis())
        }
        _streamFlow.tryEmit(result)
    }

    fun markComplete() {
        val e = entry ?: return
        entry = e.copy(isComplete = true)
    }

    /**
     * Returns the prefetched entry if it matches and hasn't gone stale.
     * Does NOT consume/clear the entry so the streamFlow can still be collected
     * for partial results if !isComplete.
     */
    fun getIfMatch(videoId: String, contentType: String, season: Int?, episode: Int?): PrefetchEntry? {
        if (!matches(videoId, contentType, season, episode)) return null
        return entry
    }

    fun clear() {
        entry = null
        _streamFlow.resetReplayCache()
    }
}
