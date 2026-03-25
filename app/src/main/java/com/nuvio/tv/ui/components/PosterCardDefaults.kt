package com.nuvio.tv.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class PosterCardStyle(
    val width: Dp = 126.dp,
    val height: Dp = 189.dp,
    val cornerRadius: Dp = 12.dp,
    val focusedBorderWidth: Dp = 2.dp,
    val focusedScale: Float = 1.08f
) {
    val aspectRatio: Float
        get() = width.value / height.value
}

object PosterCardDefaults {
    val Style = PosterCardStyle()

    /** Shared spring spec for card focus scale/shadow animations. */
    val FocusSpring: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = 400f
    )

    /** Focused card shadow elevation. */
    val FocusedElevation = 12.dp

    /** Unfocused card shadow elevation. */
    val UnfocusedElevation = 0.dp

    /** Scale applied to focused cards (matches PosterCardStyle default). */
    const val FocusedScale = 1.08f

    /** Scale applied to unfocused cards. */
    const val UnfocusedScale = 1.0f
}
