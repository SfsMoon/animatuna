package io.github.sfsmoon.animatuna.tuning

/**
 * How a window of [windowWidth] × [windowHeight] pixels is shown in a visible
 * rectangle: scaled evenly to cover it, never above its own size, with the
 * part of the window that overflows cropped away around its centre, as an app
 * grows out of its icon. Kept free of Android so it can be unit tested.
 */
data class WindowFit(
    /** How much the window is scaled, the same on both axes. */
    val scale: Float,
    /** Where the scaled window's origin lands on screen. */
    val translateX: Float,
    val translateY: Float,
    /** The crop of the window, in its own pixels: left, top, right, bottom. */
    val crop: FloatArray,
    /** The corner radius in the window's own pixels, which the surface's scale then shrinks. */
    val surfaceRadius: Float,
) {

    override fun equals(other: Any?): Boolean = other is WindowFit && scale == other.scale &&
        translateX == other.translateX && translateY == other.translateY && crop.contentEquals(other.crop) && surfaceRadius == other.surfaceRadius

    override fun hashCode(): Int = ((scale.hashCode() * 31 + translateX.hashCode()) * 31 + translateY.hashCode()) * 31 + crop.contentHashCode()

    companion object {

        fun of(frame: RectSpring.Frame, windowWidth: Float, windowHeight: Float): WindowFit {
            val scale = maxOf(frame.width / windowWidth, frame.height / windowHeight).coerceIn(MIN_SCALE, 1f)
            val cropWidth = (frame.width / scale).coerceAtMost(windowWidth)
            val cropHeight = (frame.height / scale).coerceAtMost(windowHeight)
            val cropLeft = (windowWidth - cropWidth) / 2f
            val cropTop = (windowHeight - cropHeight) / 2f
            return WindowFit(
                scale = scale,
                translateX = frame.left - cropLeft * scale,
                translateY = frame.top - cropTop * scale,
                crop = floatArrayOf(cropLeft, cropTop, cropLeft + cropWidth, cropTop + cropHeight),
                surfaceRadius = frame.radius / scale,
            )
        }

        /** What a window placed by a scale-and-move [matrix] (nine values) and cropped to [crop] shows, with its surface [radius]. */
        fun shown(matrix: FloatArray, crop: FloatArray, radius: Float): RectSpring.Frame {
            val scale = matrix[0]
            return RectSpring.Frame(
                crop[0] * scale + matrix[2], crop[1] * matrix[4] + matrix[5],
                crop[2] * scale + matrix[2], crop[3] * matrix[4] + matrix[5],
                radius * scale,
            )
        }

        private const val MIN_SCALE = 0.01f
    }
}
