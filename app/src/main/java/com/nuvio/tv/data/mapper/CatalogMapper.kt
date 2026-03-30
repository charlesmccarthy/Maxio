package com.nuvio.tv.data.mapper

import com.nuvio.tv.core.util.normalizeImageUrl
import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape

fun MetaPreviewDto.toDomain(): MetaPreview {
    val normalizedPoster = poster.normalizeImageUrl() ?: rawPosterUrl.normalizeImageUrl()
    val normalizedLandscapePoster = landscapePoster.normalizeImageUrl()
    val normalizedBackground = background.normalizeImageUrl()
    return MetaPreview(
        id = id,
        type = ContentType.fromString(type),
        rawType = type,
        name = name,
        poster = normalizedPoster ?: normalizedLandscapePoster ?: normalizedBackground,
        posterShape = PosterShape.fromString(posterShape),
        background = normalizedBackground ?: normalizedLandscapePoster ?: normalizedPoster,
        logo = logo.normalizeImageUrl(),
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating?.toFloatOrNull(),
        genres = genres ?: emptyList(),
        runtime = runtime,
        status = status?.trim()?.takeIf { it.isNotBlank() },
        released = released,
        country = country,
        imdbId = imdbId,
        slug = slug,
        landscapePoster = normalizedLandscapePoster ?: normalizedBackground ?: normalizedPoster,
        rawPosterUrl = rawPosterUrl.normalizeImageUrl(),
        director = coerceStringList(director),
        writer = coerceStringList(writer).ifEmpty { coerceStringList(writers) },
        links = links?.mapNotNull { it.toDomain() } ?: emptyList(),
        behaviorHints = mapBehaviorHints(behaviorHints),
        trailers = mapTrailers(trailers, trailerStreams),
        trailerYtIds = collectTrailerYtIds(trailers, trailerStreams)
    )
}
