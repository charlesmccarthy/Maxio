package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val FEATURE = "liked_media"
private const val MAX_LIKED_ITEMS = 250

@Singleton
class LikedMediaDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
    moshi: Moshi
) {
    private val likedItemsKey = stringPreferencesKey("liked_items_json")
    private val listType = Types.newParameterizedType(List::class.java, LikedMediaJson::class.java)
    private val adapter = moshi.adapter<List<LikedMediaJson>>(listType)

    val likedItems: Flow<List<MetaPreview>> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            factory.get(profileId, FEATURE).data.map { prefs ->
                parse(prefs[likedItemsKey])
                    .sortedByDescending { it.likedAt }
                    .map(LikedMediaJson::toDomain)
            }
        }

    suspend fun toggle(item: MetaPreview): Boolean {
        var added = false
        val store = factory.get(profileManager.activeProfileId.value, FEATURE)
        store.edit { prefs ->
            val current = parse(prefs[likedItemsKey]).toMutableList()
            val index = current.indexOfFirst { it.id == item.id && it.rawType.equals(item.apiType, ignoreCase = true) }
            if (index >= 0) {
                current.removeAt(index)
                added = false
            } else {
                current.add(
                    0,
                    LikedMediaJson.fromDomain(item)
                )
                added = true
            }
            prefs[likedItemsKey] = adapter.toJson(current.take(MAX_LIKED_ITEMS))
        }
        return added
    }

    private fun parse(raw: String?): List<LikedMediaJson> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { adapter.fromJson(raw).orEmpty() }
            .getOrDefault(emptyList())
    }
}

@JsonClass(generateAdapter = true)
internal data class LikedMediaJson(
    val id: String,
    val rawType: String,
    val name: String,
    val poster: String?,
    val posterShape: String,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: Float?,
    val genres: List<String>,
    val runtime: String?,
    val status: String?,
    val ageRating: String?,
    val language: String?,
    val released: String?,
    val country: String?,
    val imdbId: String?,
    val slug: String?,
    val landscapePoster: String?,
    val rawPosterUrl: String?,
    val likedAt: Long
) {
    fun toDomain(): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.fromString(rawType),
            rawType = rawType,
            name = name,
            poster = poster,
            posterShape = runCatching { PosterShape.valueOf(posterShape) }.getOrDefault(PosterShape.POSTER),
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            runtime = runtime,
            status = status,
            ageRating = ageRating,
            language = language,
            released = released,
            country = country,
            imdbId = imdbId,
            slug = slug,
            landscapePoster = landscapePoster,
            rawPosterUrl = rawPosterUrl
        )
    }

    companion object {
        fun fromDomain(item: MetaPreview): LikedMediaJson {
            return LikedMediaJson(
                id = item.id,
                rawType = item.apiType,
                name = item.name,
                poster = item.poster,
                posterShape = item.posterShape.name,
                background = item.background,
                logo = item.logo,
                description = item.description,
                releaseInfo = item.releaseInfo,
                imdbRating = item.imdbRating,
                genres = item.genres,
                runtime = item.runtime,
                status = item.status,
                ageRating = item.ageRating,
                language = item.language,
                released = item.released,
                country = item.country,
                imdbId = item.imdbId,
                slug = item.slug,
                landscapePoster = item.landscapePoster,
                rawPosterUrl = item.rawPosterUrl,
                likedAt = System.currentTimeMillis()
            )
        }
    }
}
