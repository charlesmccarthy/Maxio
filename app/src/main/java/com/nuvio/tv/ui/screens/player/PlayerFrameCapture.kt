package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

private const val TAG = "PlayerFrameCapture"
private const val MAX_FRAME_WIDTH = 1280
private const val JPEG_QUALITY = 88

/**
 * Captures the currently displayed video frame as a base64 JPEG.
 *
 * The player renders into a SurfaceView, whose content is composited outside the view
 * hierarchy — a normal draw pass returns black. PixelCopy reads the actual surface
 * buffer. Returns null if the surface isn't ready or the copy fails (e.g. secure
 * content), which callers should surface as "couldn't capture this frame".
 */
suspend fun capturePlayerFrame(playerView: PlayerView): String? {
    val surfaceView = playerView.videoSurfaceView as? SurfaceView ?: return null
    if (surfaceView.width <= 0 || surfaceView.height <= 0) return null
    if (!surfaceView.holder.surface.isValid) return null

    val bitmap = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Bitmap?> { continuation ->
            val target = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(
                    surfaceView,
                    target,
                    { result ->
                        if (result == PixelCopy.SUCCESS) {
                            continuation.resume(target)
                        } else {
                            Log.w(TAG, "PixelCopy failed: $result")
                            target.recycle()
                            continuation.resume(null)
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            } catch (e: Exception) {
                Log.w(TAG, "PixelCopy request threw", e)
                target.recycle()
                continuation.resume(null)
            }
        }
    } ?: return null

    return withContext(Dispatchers.Default) {
        try {
            val scaled = if (bitmap.width > MAX_FRAME_WIDTH) {
                val ratio = MAX_FRAME_WIDTH.toFloat() / bitmap.width
                Bitmap.createScaledBitmap(
                    bitmap,
                    MAX_FRAME_WIDTH,
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Frame encode failed", e)
            null
        }
    }
}
