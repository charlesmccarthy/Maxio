package com.nuvio.tv.ui.components

import android.net.Uri
import android.view.SurfaceView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.R

/**
 * Fullscreen intro video player that plays the Maxio intro animation.
 * Calls [onFinished] when the video completes playback.
 */
@Composable
fun IntroVideoPlayer(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var videoEnded by remember { mutableStateOf(false) }

    // Fade out when video ends
    val alpha by animateFloatAsState(
        targetValue = if (videoEnded) 0f else 1f,
        animationSpec = tween(durationMillis = 400),
        finishedListener = { if (videoEnded) onFinished() },
        label = "intro_fade"
    )

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val uri = RawResourceDataSource.buildRawResourceUri(R.raw.maxio_intro)
            setMediaItem(MediaItem.fromUri(uri))
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            playWhenReady = true
            prepare()
        }
    }

    // Listen for playback end
    LaunchedEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    videoEnded = true
                }
            }
        }
        exoPlayer.addListener(listener)
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(alpha)
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also { surfaceView ->
                    exoPlayer.setVideoSurfaceView(surfaceView)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
