package com.nuvio.tv.core.stream

import com.nuvio.tv.domain.model.Subtitle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds addon subtitles prefetched on the details page for the stream the Play
 * button will use, so the player's startup subtitle preparation can read them
 * instead of blocking on a network fetch. Keyed on everything the subtitle fetch
 * depends on (including the resolved stream's filename/hash/size, which some addons
 * use for moviehash matching), so a lookup only hits when it matches exactly.
 */
@Singleton
class SubtitlePrefetchCache @Inject constructor() {

    data class Key(
        val videoId: String,
        val contentType: String,
        val season: Int?,
        val episode: Int?,
        val filename: String?,
        val videoHash: String?,
        val videoSize: Long?
    )

    private data class Entry(
        val key: Key,
        val subtitles: List<Subtitle>,
        val isComplete: Boolean,
        val timestampMs: Long
    )

    @Volatile
    private var entry: Entry? = null

    private companion object {
        const val STALE_THRESHOLD_MS = 5L * 60L * 1000L // 5 minutes
    }

    /** True if a completed, fresh entry already exists for [key]. */
    fun hasFreshComplete(key: Key): Boolean {
        val e = entry ?: return false
        return e.isComplete && e.key == key &&
            (System.currentTimeMillis() - e.timestampMs) < STALE_THRESHOLD_MS
    }

    fun start(key: Key) {
        entry = Entry(key = key, subtitles = emptyList(), isComplete = false, timestampMs = System.currentTimeMillis())
    }

    fun complete(key: Key, subtitles: List<Subtitle>) {
        entry = Entry(key = key, subtitles = subtitles, isComplete = true, timestampMs = System.currentTimeMillis())
    }

    /** Returns the prefetched subtitles only if a completed, fresh entry matches [key]. */
    fun getIfMatch(key: Key): List<Subtitle>? {
        val e = entry ?: return null
        if (!e.isComplete) return null
        if (e.key != key) return null
        if ((System.currentTimeMillis() - e.timestampMs) >= STALE_THRESHOLD_MS) return null
        return e.subtitles
    }

    fun clear() {
        entry = null
    }
}
