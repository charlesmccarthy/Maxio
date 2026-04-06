package com.nuvio.tv.ui.screens.player

import android.app.Activity
import java.lang.ref.WeakReference

internal fun PlayerRuntimeController.attachHostActivity(activity: Activity?) {
    hostActivityRef = activity?.let { WeakReference(it) }
}

internal fun PlayerRuntimeController.startInitialPlaybackIfNeeded() {
    if (initialPlaybackStarted) return

    initialPlaybackStarted = true
    preparePlaybackBeforeStart(
        url = currentStreamUrl,
        headers = currentHeaders,
        loadSavedProgress = false
    )
}

internal fun PlayerRuntimeController.resumePlaybackAfterSecondaryNavigationIfNeeded() {
    if (!releasedForSecondaryNavigation) return
    if (_exoPlayer != null) return
    if (currentStreamUrl.isBlank()) return

    releasedForSecondaryNavigation = false
    preparePlaybackBeforeStart(
        url = currentStreamUrl,
        headers = currentHeaders,
        loadSavedProgress = false
    )
}

internal fun PlayerRuntimeController.currentHostActivity(): Activity? {
    return hostActivityRef?.get()
}
