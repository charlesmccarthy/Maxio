package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaCompany
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val MAX_FEATURED_STUDIO_SEED_ITEMS = 40
private const val MAX_FEATURED_STUDIOS = 18
private const val FEATURED_STUDIO_CONCURRENCY = 4
private const val MIN_FEATURED_NETWORK_SLOTS = 4
private const val MAX_DYNAMIC_FEATURED_STUDIOS = 12

private val PINNED_FEATURED_STUDIOS = listOf(
    FeaturedStudio(49, "HBO", tmdbLogo("/tuomPhY2UtuPTqqFnKMVHvSb724.png"), "network", "tv"),
    FeaturedStudio(2552, "Apple TV+", tmdbLogo("/bngHRFi794mnMq34gfVcm9nDxN1.png"), "network", "tv"),
    FeaturedStudio(213, "Netflix", tmdbLogo("/wwemzKWzjKYJFfCeiB57q3r4Bcm.png"), "network", "tv"),
    FeaturedStudio(1024, "Prime Video", tmdbLogo("/w7HfLNm9CWwRmAMU58udl2L7We7.png"), "network", "tv"),
    FeaturedStudio(453, "Hulu", tmdbLogo("/pqUTCleNUiTLAVlelGxUgWn1ELh.png"), "network", "tv"),
    FeaturedStudio(2739, "Disney+", tmdbLogo("/1edZOYAfoyZyZ3rklNSiUpXX30Q.png"), "network", "tv"),
    FeaturedStudio(4330, "Paramount+", tmdbLogo("/fi83B1oztoS47xxcemFdPMhIzK.png"), "network", "tv"),
    FeaturedStudio(3353, "Peacock", tmdbLogo("/gIAcGTjKKr0KOHL5s4O36roJ8p7.png"), "network", "tv"),
    FeaturedStudio(6783, "Max", tmdbLogo("/rAb4M1LjGpWASxpk6Va791A7Nkw.png"), "network", "tv"),
    FeaturedStudio(88, "FX", tmdbLogo("/aexGjtcs42DgRtZh7zOxayiry4J.png"), "network", "tv"),
    FeaturedStudio(174, "AMC", tmdbLogo("/pmvRmATOCaDykE6JrVoeYxlFHw3.png"), "network", "tv"),
    FeaturedStudio(67, "Showtime", tmdbLogo("/Allse9kbjiP6ExaQrnSpIhkurEi.png"), "network", "tv"),
    FeaturedStudio(318, "Starz", tmdbLogo("/qx3Y9LCaK4mq1ykFuDIfjshlo3U.png"), "network", "tv"),
    FeaturedStudio(174, "Warner Bros. Pictures", tmdbLogo("/zhD3hhtKB5qyv7ZeL4uLpNxgMVU.png"), "company", "movie"),
    FeaturedStudio(14, "Miramax", tmdbLogo("/m6AHu84oZQxvq7n1rsvMNJIAsMu.png"), "company", "movie"),
    FeaturedStudio(41077, "A24", tmdbLogo("/1ZXsGaFPgrgS6ZZGS37AqD5uU12.png"), "company", "movie"),
    FeaturedStudio(33, "Universal Pictures", tmdbLogo("/8lvHyhjr8oUKOOy2dKXoALWKdp0.png"), "company", "movie"),
    FeaturedStudio(5, "Columbia Pictures", tmdbLogo("/71BqEFAF4V3qjjMPCpLuyJFB9A.png"), "company", "movie"),
    FeaturedStudio(4, "Paramount Pictures", tmdbLogo("/jay6WcMgagAklUt7i9Euwj1pzTF.png"), "company", "movie"),
    FeaturedStudio(127928, "20th Century Studios", tmdbLogo("/h0rjX5vjW5r8yEnUBStFarjcLT4.png"), "company", "movie"),
    FeaturedStudio(2, "Walt Disney Pictures", tmdbLogo("/wdrCwmRnLFJhEoH8GSfymY85KHT.png"), "company", "movie")
)

private data class FeaturedStudioAggregate(
    val tmdbId: Int,
    val name: String,
    val entityKind: String,
    var logo: String? = null,
    var mentions: Int = 0,
    var movieMentions: Int = 0,
    var tvMentions: Int = 0
)

internal fun HomeViewModel.refreshFeaturedStudiosPipeline(
    rows: List<CatalogRow>,
    heroItems: List<MetaPreview>
) {
    val tmdbEnabledForCurrentLayout = currentTmdbSettings.enabled &&
        (_uiState.value.homeLayout != com.nuvio.tv.domain.model.HomeLayout.MODERN || currentTmdbSettings.modernHomeEnabled)
    if (!tmdbEnabledForCurrentLayout) {
        featuredStudiosSignature = null
        featuredStudiosJob?.cancel()
        _uiState.update { state ->
            if (state.featuredStudios.isEmpty()) state else state.copy(featuredStudios = emptyList())
        }
        return
    }

    val seedItems = buildFeaturedStudioSeedItems(heroItems, rows)
    if (seedItems.isEmpty()) {
        featuredStudiosSignature = null
        featuredStudiosJob?.cancel()
        _uiState.update { state ->
            if (state.featuredStudios.isEmpty()) state else state.copy(featuredStudios = emptyList())
        }
        return
    }

    val signature = buildString {
        append(currentTmdbSettings.language)
        append("|")
        seedItems.forEach { item ->
            append(item.apiType)
            append(":")
            append(item.id)
            append("|")
        }
    }
    if (featuredStudiosSignature == signature) return
    featuredStudiosSignature = signature

    featuredStudiosJob?.cancel()
    featuredStudiosJob = viewModelScope.launch {
        val studios = collectFeaturedStudios(seedItems)
        _uiState.update { state ->
            if (state.featuredStudios == studios) state else state.copy(featuredStudios = studios)
        }
    }
}

private fun buildFeaturedStudioSeedItems(
    heroItems: List<MetaPreview>,
    rows: List<CatalogRow>
): List<MetaPreview> {
    return buildList {
        addAll(heroItems)
        rows.forEach { row ->
            addAll(row.items.take(6))
        }
    }
        .distinctBy { "${it.apiType}:${it.id}" }
        .take(MAX_FEATURED_STUDIO_SEED_ITEMS)
}

private suspend fun HomeViewModel.collectFeaturedStudios(
    seedItems: List<MetaPreview>
): List<FeaturedStudio> = coroutineScope {
    val aggregates = linkedMapOf<String, FeaturedStudioAggregate>()
    val semaphore = Semaphore(FEATURED_STUDIO_CONCURRENCY)
    val language = currentTmdbSettings.language

    seedItems.map { item ->
        async(Dispatchers.IO) {
            semaphore.withPermit {
                val tmdbId = runCatching {
                    tmdbService.ensureTmdbId(item.id, item.apiType)
                }.getOrNull() ?: return@withPermit
                val enrichment = runCatching {
                    tmdbMetadataService.fetchEnrichment(
                        tmdbId = tmdbId,
                        contentType = item.type,
                        language = language
                    )
                }.getOrNull() ?: return@withPermit

                synchronized(aggregates) {
                    val movieSource = item.type == ContentType.MOVIE
                    enrichment.productionCompanies.forEach { company ->
                        aggregates.mergeStudio(
                            company = company,
                            entityKind = "company",
                            sourceType = if (movieSource) "movie" else "tv"
                        )
                    }
                    enrichment.networks.forEach { network ->
                        aggregates.mergeStudio(
                            company = network,
                            entityKind = "network",
                            sourceType = "tv"
                        )
                    }
                }
            }
        }
    }.awaitAll()

    val dynamicStudios = aggregates.values
        .let(::selectFeaturedStudios)
        .map { aggregate ->
            FeaturedStudio(
                tmdbId = aggregate.tmdbId,
                name = aggregate.name,
                logo = aggregate.logo,
                entityKind = aggregate.entityKind,
                sourceType = when {
                    aggregate.entityKind == "network" -> "tv"
                    aggregate.tvMentions > aggregate.movieMentions -> "tv"
                    else -> "movie"
                }
            )
        }
    mergePinnedFeaturedStudios(dynamicStudios)
}

private fun MutableMap<String, FeaturedStudioAggregate>.mergeStudio(
    company: MetaCompany,
    entityKind: String,
    sourceType: String
) {
    val tmdbId = company.tmdbId ?: return
    val name = company.name.trim().takeIf { it.isNotBlank() }?.let(::canonicalStudioDisplayName) ?: return
    val key = "$entityKind:$tmdbId"
    val aggregate = getOrPut(key) {
        FeaturedStudioAggregate(
            tmdbId = tmdbId,
            name = name,
            entityKind = entityKind,
            logo = company.logo
        )
    }
    aggregate.mentions += 1
    if (aggregate.logo.isNullOrBlank() && !company.logo.isNullOrBlank()) {
        aggregate.logo = company.logo
    }
    when (sourceType) {
        "tv" -> aggregate.tvMentions += 1
        else -> aggregate.movieMentions += 1
    }
}

private fun selectFeaturedStudios(
    aggregates: Collection<FeaturedStudioAggregate>
): List<FeaturedStudioAggregate> {
    val sorted = aggregates.sortedWith(
        compareByDescending<FeaturedStudioAggregate> { it.mentions }
            .thenByDescending { !it.logo.isNullOrBlank() }
            .thenBy { it.name.lowercase() }
    )
    if (sorted.isEmpty()) return emptyList()

    val selected = ArrayList<FeaturedStudioAggregate>(MAX_FEATURED_STUDIOS)
    val usedDisplayNames = HashSet<String>(MAX_FEATURED_STUDIOS)

    sorted.asSequence()
        .filter { it.entityKind == "network" }
        .forEach { aggregate ->
            if (selected.size >= MIN_FEATURED_NETWORK_SLOTS) return@forEach
            if (usedDisplayNames.add(aggregate.name.lowercase())) {
                selected += aggregate
            }
        }

    sorted.forEach { aggregate ->
        if (selected.size >= MAX_FEATURED_STUDIOS) return@forEach
        if (usedDisplayNames.add(aggregate.name.lowercase())) {
            selected += aggregate
        }
    }

    return selected
}

private fun canonicalStudioDisplayName(name: String): String {
    val normalized = name.trim()
    val lowercase = normalized.lowercase()
    return when {
        lowercase == "hbo" -> "HBO"
        lowercase.contains("home box office") -> "HBO"
        lowercase == "apple tv" -> "Apple TV+"
        lowercase == "starz" -> "Starz"
        lowercase == "hbo max" -> "Max"
        lowercase == "showtime networks" -> "Showtime"
        else -> normalized
    }
}

private fun mergePinnedFeaturedStudios(dynamicStudios: List<FeaturedStudio>): List<FeaturedStudio> {
    val merged = ArrayList<FeaturedStudio>(PINNED_FEATURED_STUDIOS.size + MAX_DYNAMIC_FEATURED_STUDIOS)
    val seenBrands = HashSet<String>(PINNED_FEATURED_STUDIOS.size + MAX_DYNAMIC_FEATURED_STUDIOS)
    val pinnedByBrand = PINNED_FEATURED_STUDIOS.associateBy(::featuredStudioBrandKey)

    dynamicStudios
        .take(MAX_DYNAMIC_FEATURED_STUDIOS)
        .forEach { studio ->
            val brandKey = featuredStudioBrandKey(studio)
            if (seenBrands.add(brandKey)) {
                val normalizedStudio = pinnedByBrand[brandKey] ?: studio
                merged += normalizedStudio.copy(name = canonicalStudioDisplayName(normalizedStudio.name))
            }
        }

    PINNED_FEATURED_STUDIOS.forEach { studio ->
        val brandKey = featuredStudioBrandKey(studio)
        if (seenBrands.add(brandKey)) {
            merged += studio.copy(name = canonicalStudioDisplayName(studio.name))
        }
    }

    return merged
}

private fun featuredStudioBrandKey(studio: FeaturedStudio): String {
    return canonicalStudioDisplayName(studio.name).lowercase()
}

private fun tmdbLogo(path: String): String = "https://image.tmdb.org/t/p/w300$path"
