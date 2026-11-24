package io.github.sfsmoon.animatuna.host

import android.graphics.Matrix
import android.graphics.Rect
import android.view.SurfaceControl

import java.lang.reflect.Method

import io.github.sfsmoon.animatuna.tuning.WindowFit

/**
 * Sets a window's place on its surface directly, for the frames in which
 * Pixel Launcher sets none: the transaction's matrix, window crop and corner
 * setters are hidden from apps and are reached by name.
 */
internal object SurfacePlacer {

    private const val MATRIX_VALUES = 9

    private val setMatrix: Method? = runCatching {
        SurfaceControl.Transaction::class.java.getMethod("setMatrix", SurfaceControl::class.java, Matrix::class.java, FloatArray::class.java)
    }.getOrNull()
    private val setWindowCrop: Method? = runCatching {
        SurfaceControl.Transaction::class.java.getMethod("setWindowCrop", SurfaceControl::class.java, Rect::class.java)
    }.getOrNull()
    private val setCorner: Method? = runCatching {
        SurfaceControl.Transaction::class.java.getMethod("setCornerRadius", SurfaceControl::class.java, Float::class.javaPrimitiveType)
    }.getOrNull()

    /** Shows [surface] as [fit] says, now, at [alpha] if given; false where it cannot. */
    fun place(surface: Any?, fit: WindowFit, alpha: Float? = null): Boolean {
        val control = surface as? SurfaceControl ?: return false
        if (!control.isValid) return false
        SurfaceControl.Transaction().use { transaction ->
            if (!put(transaction, control, fit)) return false
            alpha?.let { transaction.setAlpha(control, it) }
            transaction.apply()
        }
        return true
    }

    /** Puts [fit] for [surface] into [transaction], to be applied with it; false where it cannot. */
    fun put(transaction: SurfaceControl.Transaction, surface: SurfaceControl, fit: WindowFit): Boolean {
        val matrix = setMatrix ?: return false
        matrix.invoke(transaction, surface, matrixOf(fit), FloatArray(MATRIX_VALUES))
        setWindowCrop?.invoke(transaction, surface, rectOf(fit.crop)) ?: transaction.setCrop(surface, rectOf(fit.crop))
        setCorner?.invoke(transaction, surface, fit.surfaceRadius)
        return true
    }

    fun matrixOf(fit: WindowFit) = Matrix().apply {
        setScale(fit.scale, fit.scale)
        postTranslate(fit.translateX, fit.translateY)
    }

    fun rectOf(crop: FloatArray) = Rect(crop[0].toInt(), crop[1].toInt(), crop[2].toInt(), crop[3].toInt())
}
