package com.nuvio.tv.core.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide media cache used to pre-buffer the opening of the pre-selected
 * stream on the details page (Tier 2 instant play). The player reads through the
 * same cache, so the first frame comes from disk instead of the network.
 *
 * Only one SimpleCache may exist per directory per process, hence the singleton.
 * [cache] is null if init fails so the rest of playback degrades gracefully.
 */
@Singleton
class PlayerMediaCache @Inject constructor(
    @ApplicationContext context: Context
) {
    @OptIn(UnstableApi::class)
    val cache: Cache? = runCatching {
        SimpleCache(
            File(context.cacheDir, "media-preload"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context)
        )
    }.getOrNull()

    private companion object {
        const val MAX_BYTES = 256L * 1024 * 1024 // 256 MB
    }
}
