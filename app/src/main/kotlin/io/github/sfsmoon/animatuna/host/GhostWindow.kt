package io.github.sfsmoon.animatuna.host

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Choreographer
import android.view.View

import io.github.sfsmoon.animatuna.tuning.HomeFlight

/**
 * A picture of an app's window flying on in its springs above the home
 * screen, for the moments the system no longer shows the window itself.
 *
 * Opening an app again while its window flies home, Pixel Launcher ends the
 * flight and the gesture at the tap (`FloatingIconView.fastFinish`), and the
 * system hides the window; the launch only starts about 100 ms later. ColorOS
 * keeps the window moving through that. Here its picture does, on the same
 * springs, until the launch takes the window from where the picture is
 * ([handOff]), or the picture lands in its icon.
 */
internal class GhostWindow(
    private val root: View,
    picture: Bitmap,
    /** The window's springs, flying on from where the window was let go. */
    val window: MovingWindow,
    private val flight: HomeFlight,
    private val shapeFrom: Float,
    private val onGone: () -> Unit,
) {
    private val drawable = PictureDrawable(picture)
    private val choreographer = Choreographer.getInstance()
    private val startedAt = SystemClock.uptimeMillis()
    private var moving = true
    private var shown = true

    fun show() {
        drawable.setBounds(0, 0, root.width, root.height)
        root.overlay.add(drawable)
        frame()
    }

    private fun frame() {
        if (!moving) return
        window.step()
        val now = window.spring.frame()
        val flown = flight.progress(now, window.spring.target)
        drawable.place(RectF(now.left, now.top, now.right, now.bottom), now.radius, HomeFlight.windowAlpha(flown, shapeFrom))
        if (flown >= LANDED || SystemClock.uptimeMillis() - startedAt > LONGEST_MS) {
            remove()
            return
        }
        choreographer.postFrameCallback { frame() }
    }

    /**
     * The launch takes the window from here: the picture stops, and is gone
     * a frame later, once the window shows in its place. Whether it was still
     * flying, so the launch carries its speed on.
     */
    fun handOff(): Boolean {
        val was = moving
        moving = false
        root.postDelayed(::remove, HAND_OFF_MS)
        return was
    }

    private fun remove() {
        moving = false
        if (!shown) return
        shown = false
        root.overlay.remove(drawable)
        onGone()
    }

    /** The picture cropped around its centre into a rounded rectangle, as the window shows. */
    private class PictureDrawable(private val picture: Bitmap) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        private val frame = RectF()
        private var radius = 0f

        fun place(where: RectF, corner: Float, opacity: Float) {
            frame.set(where)
            radius = corner
            val scale = maxOf(where.width() / picture.width, where.height() / picture.height)
            paint.shader.setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(where.centerX() - picture.width * scale / 2f, where.centerY() - picture.height * scale / 2f)
            })
            paint.alpha = (opacity.coerceIn(0f, 1f) * OPAQUE).toInt()
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            canvas.drawRoundRect(frame, radius, radius, paint)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val OPAQUE = 255

        /** How far home the picture must have flown to count as landed in its icon. */
        const val LANDED = 0.99f

        /** The longest a picture flies, should neither a launch nor its icon take it. */
        const val LONGEST_MS = 800L

        /** How long the picture stays once the launch has taken the window: about a frame. */
        const val HAND_OFF_MS = 16L
    }
}
