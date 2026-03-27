package com.nuvio.tv.data.trailer

import javax.inject.Inject
import javax.inject.Singleton

data class ActiveTrailerInfo(
    val videoUrl: String,
    val audioUrl: String?,
    val positionMs: Long
)

@Singleton
class ActiveTrailerState @Inject constructor() {
    var itemId: String? = null
        private set
    var videoUrl: String? = null
        private set
    var audioUrl: String? = null
        private set
    var positionMs: Long = 0L
        private set

    fun store(itemId: String, videoUrl: String, audioUrl: String?, positionMs: Long) {
        this.itemId = itemId
        this.videoUrl = videoUrl
        this.audioUrl = audioUrl
        this.positionMs = positionMs
    }

    fun consume(forItemId: String): ActiveTrailerInfo? {
        if (this.itemId != forItemId) return null
        val url = videoUrl ?: return null
        val info = ActiveTrailerInfo(
            videoUrl = url,
            audioUrl = audioUrl,
            positionMs = positionMs
        )
        clear()
        return info
    }

    fun clear() {
        itemId = null
        videoUrl = null
        audioUrl = null
        positionMs = 0L
    }
}
