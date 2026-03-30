package com.nuvio.tv.core.util

fun String?.normalizeImageUrl(): String? {
    val clean = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        clean.startsWith("//") -> "https:$clean"
        else -> clean
    }
}

fun tmdbImageUrl(path: String?, size: String): String? {
    val clean = path.normalizeImageUrl() ?: return null
    if (clean.startsWith("http://") || clean.startsWith("https://")) {
        return clean
    }
    val normalizedPath = if (clean.startsWith("/")) clean else "/$clean"
    return "https://image.tmdb.org/t/p/$size$normalizedPath"
}
