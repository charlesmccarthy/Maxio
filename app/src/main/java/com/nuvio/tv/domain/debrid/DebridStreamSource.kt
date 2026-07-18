package com.nuvio.tv.domain.debrid

import android.util.Log
import com.nuvio.tv.data.local.DebridService
import com.nuvio.tv.data.local.DebridSettings
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.TorrentioApi
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DebridStreamSource"

@Singleton
class DebridStreamSource @Inject constructor(
    private val torrentioApi: TorrentioApi,
    private val debridSettingsDataStore: DebridSettingsDataStore
) {
    /** True if debrid is on and at least one service is configured. */
    suspend fun isEnabled(): Boolean {
        val settings = debridSettingsDataStore.settings.first()
        return settings.enabled && settings.activeServices().isNotEmpty()
    }

    /** Source labels (e.g. "Debrid (Torbox)") for each active service, for
     *  showing loading chips before results arrive. Same labels fetchAll emits. */
    suspend fun activeSourceLabels(): List<String> {
        val settings = debridSettingsDataStore.settings.first()
        if (!settings.enabled) return emptyList()
        return settings.activeServices().map { "Debrid (${it.displayName})" }
    }

    /**
     * Queries every enabled debrid service in parallel using its proven
     * already-resolving endpoint, invoking [onResult] for each service as soon
     * as it produces playable streams — so a fast service isn't held up by a
     * slow/rate-limited one, and a failing service can't lose another's result.
     *
     * No eager per-torrent resolution is done here — these endpoints return
     * ready-to-play URLs, so we never flood a debrid service's add limits.
     */
    suspend fun fetchAll(
        type: String,
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
        onResult: suspend (AddonStreams) -> Unit
    ) {
        val settings = debridSettingsDataStore.settings.first()
        if (!settings.enabled) return
        val services = settings.activeServices()
        if (services.isEmpty()) return

        val videoId = buildString {
            append(imdbId)
            if (season != null && episode != null) append(":$season:$episode")
        }
        val torrentioType = when (type.lowercase()) {
            "series", "tv", "show" -> "series"
            else -> "movie"
        }

        coroutineScope {
            services.forEach { service ->
                launch {
                    try {
                        val result = fetchService(service, settings, torrentioType, videoId)
                        if (result != null) onResult(result)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "${service.displayName} fetch failed: ${e.message}", e)
                    }
                }
            }
        }
    }

    private suspend fun fetchService(
        service: DebridService,
        settings: DebridSettings,
        torrentioType: String,
        videoId: String
    ): AddonStreams? {
        val key = settings.keyFor(service)
        if (key.isBlank()) return null

        // Each service points at the endpoint already proven to work for the
        // user (and that returns ready-to-play URLs):
        //  - Torbox: its official Stremio addon
        //  - RD/AD : Torrentio with the key embedded in the path
        val url = when (service) {
            DebridService.TORBOX ->
                "https://stremio.torbox.app/$key/stream/$torrentioType/$videoId.json"
            DebridService.REAL_DEBRID ->
                "https://torrentio.strem.fun/realdebrid=$key/stream/$torrentioType/$videoId.json"
            DebridService.ALL_DEBRID ->
                "https://torrentio.strem.fun/alldebrid=$key/stream/$torrentioType/$videoId.json"
        }

        Log.d(TAG, "Fetching ${service.displayName} streams: $torrentioType/$videoId")

        val response = torrentioApi.getStreams(url)
        val streams = response.streams.orEmpty()
        if (streams.isEmpty()) {
            Log.d(TAG, "${service.displayName}: no streams for $videoId")
            return null
        }

        // Keep only streams with a playable URL; drop uncached (infoHash-only)
        // entries and the placeholder Torrentio injects when rate-limited.
        val playable = streams.filter { s ->
            !s.url.isNullOrBlank() &&
                !isRateLimitPlaceholder(s.name) &&
                !isRateLimitPlaceholder(s.title)
        }
        if (playable.isEmpty()) {
            Log.d(TAG, "${service.displayName}: ${streams.size} streams, none playable/cached")
            return null
        }

        Log.d(TAG, "${service.displayName}: ${playable.size} playable streams (of ${streams.size})")

        val label = "Debrid (${service.displayName})"
        return AddonStreams(
            addonName = label,
            addonLogo = null,
            streams = playable.map { ts ->
                // Different sources put the filename in different places:
                //  - Torbox addon: name="TorBox (Instant) (4k)",
                //    description="...\nName: <file>\n...", filename in hints
                //  - Torrentio: name="Torrentio\n4k", title="<file>\n👤 .. 💾 .."
                // Prefer the clean filename so the row shows the actual release.
                val cleanFilename = ts.behaviorHints?.filename
                    ?.takeIf { it.isNotBlank() }
                val firstTitleLine = ts.title
                    ?.lineSequence()?.firstOrNull()?.trim()
                    ?.takeIf { it.isNotBlank() }
                val sourceLabel = ts.name
                    ?.replace('\n', ' ')?.replace(Regex("\\s+"), " ")?.trim()
                    ?.takeIf { it.isNotBlank() }

                val displayName = cleanFilename ?: firstTitleLine ?: sourceLabel ?: "Stream"
                val sizeText = formatSize(ts.behaviorHints?.videoSize)
                    ?: extractSizeFromText(ts.description)
                    ?: extractSizeFromText(ts.title)
                // Torbox mixes torrent + usenet in one response; surface which
                // it is (plus age/seeders) so the user can prefer usenet.
                val sourceKind = extractSourceKind(ts.description, ts.title)
                val displayDescription = listOfNotNull(
                    sourceLabel?.takeIf { it != displayName },
                    sizeText,
                    sourceKind
                ).joinToString(" • ").takeIf { it.isNotBlank() }

                Stream(
                    name = displayName,
                    title = null,
                    description = displayDescription,
                    url = ts.url,
                    ytId = null,
                    infoHash = null,
                    fileIdx = null,
                    externalUrl = null,
                    behaviorHints = StreamBehaviorHints(
                        notWebReady = ts.behaviorHints?.notWebReady,
                        bingeGroup = ts.behaviorHints?.bingeGroup,
                        countryWhitelist = null,
                        proxyHeaders = null,
                        videoHash = ts.behaviorHints?.videoHash,
                        filename = ts.behaviorHints?.filename,
                        videoSize = ts.behaviorHints?.videoSize
                    ),
                    addonName = label,
                    addonLogo = null,
                    isCached = detectDebridCached(ts.name, ts.title, ts.description)
                )
            }
        )
    }

    // Classifies a debrid stream as cached (instantly playable) vs uncached
    // (needs downloading) from the addon's text markers. Covers Torbox
    // ("Instant" / "Download"), Torrentio/RD/AD ("RD+"/"AD+" / "download"),
    // and the ⚡ symbol some addons use. Uncached wins if "download" appears.
    private fun detectDebridCached(vararg texts: String?): Boolean {
        val combined = texts.filterNotNull().joinToString(" ").lowercase().trim()
        if (combined.isEmpty()) return false
        if (Regex("\\bdownload(ing|ed)?\\b|\\bqueued\\b|\\bdownloading\\b").containsMatchIn(combined)) {
            return false
        }
        return combined.contains("instant") ||
            combined.contains("cached") ||
            combined.contains("⚡") ||
            Regex("\\b[a-z]{2}\\+").containsMatchIn(combined)
    }

    private fun formatSize(bytes: Long?): String? {
        if (bytes == null || bytes <= 0) return null
        val gb = bytes / 1_000_000_000.0
        if (gb >= 1.0) return "%.2f GB".format(gb)
        val mb = bytes / 1_000_000.0
        return "%.0f MB".format(mb)
    }

    /**
     * Builds a short source indicator from Torbox/Torrentio metadata:
     *  - Torbox desc has "Type: Usenet | Age: 9d" or "Type: Torrent | Seeders: 30"
     *  - Torrentio title has a seeder marker ("👤 30") — always torrent
     * Returns e.g. "📡 Usenet · 9d", "🌱 Torrent · 30 seeders", or null.
     */
    private fun extractSourceKind(description: String?, title: String?): String? {
        val text = listOfNotNull(description, title).joinToString("\n")
        if (text.isBlank()) return null

        val type = Regex("Type:\\s*(Usenet|Torrent)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.lowercase()

        when (type) {
            "usenet" -> {
                val age = Regex("Age:\\s*([0-9]+\\s*[a-zA-Z]+)", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.get(1)?.replace(" ", "")
                return "📡 Usenet" + (age?.let { " · $it" } ?: "")
            }
            "torrent" -> {
                val seeders = Regex("Seeders:\\s*([0-9]+)", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.get(1)
                return "🌱 Torrent" + (seeders?.let { " · $it seeders" } ?: "")
            }
        }

        // Torrentio has no explicit Type; a seeder marker means torrent.
        val seeders = Regex("👤\\s*([0-9]+)").find(text)?.groupValues?.get(1)
        if (seeders != null) return "🌱 Torrent · $seeders seeders"
        return null
    }

    /** Pulls a size like "9GB", "1.5 GiB", "750 MB" out of free text. */
    private fun extractSizeFromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = Regex(
            "(\\d+(?:[.,]\\d+)?)\\s?(TB|GB|GiB|MB|MiB)",
            RegexOption.IGNORE_CASE
        ).find(text) ?: return null
        return match.value.trim()
    }

    private fun isRateLimitPlaceholder(text: String?): Boolean {
        if (text == null) return false
        val lower = text.lowercase()
        return lower.contains("rate limit") ||
            lower.contains("rate-limit") ||
            lower.contains("too many requests") ||
            lower.contains("limit exceeded")
    }
}
