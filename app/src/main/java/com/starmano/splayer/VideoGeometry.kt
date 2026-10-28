package com.starmano.splayer

import kotlin.math.roundToInt

/** Android rejects picture-in-picture aspect ratios wider than 2.39:1 or taller than 1:2.39. */
private const val MAX_PIP_ASPECT = 2.39f

/** A picture-in-picture aspect ratio (numerator to denominator) that Android will accept. */
internal fun pipAspectRatio(videoWidth: Int, videoHeight: Int): Pair<Int, Int> {
    if (videoWidth <= 0 || videoHeight <= 0) return 16 to 9
    val aspect = videoWidth.toFloat() / videoHeight
    return when {
        aspect > MAX_PIP_ASPECT -> 239 to 100
        aspect < 1 / MAX_PIP_ASPECT -> 100 to 239
        else -> videoWidth to videoHeight
    }
}

internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Where a video of the given [aspect] ratio is drawn when fitted and centred in a container. */
internal fun fitCenter(containerWidth: Int, containerHeight: Int, aspect: Float): Box {
    val containerAspect = containerWidth.toFloat() / containerHeight
    val (width, height) = if (aspect > containerAspect) {
        containerWidth to (containerWidth / aspect).roundToInt()
    } else {
        (containerHeight * aspect).roundToInt() to containerHeight
    }
    val left = (containerWidth - width) / 2
    val top = (containerHeight - height) / 2
    return Box(left, top, left + width, top + height)
}
