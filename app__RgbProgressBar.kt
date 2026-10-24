package dev.sk2andy.materialbrowser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * Page-loading line: a thin rounded bar that grows with the load progress and is filled with an
 * RGB rainbow that keeps flowing while it is visible.
 */
class RgbProgressBar(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()
    private var shader: LinearGradient? = null

    private var shown = 0f      // fraction currently drawn, 0..1
    private var phase = 0f      // rainbow offset, 0..1
    private var grow: ValueAnimator? = null
    private var flow: ValueAnimator? = null

    /** Loop-friendly rainbow: the last color equals the first so the scroll has no seam. */
    private val colors = intArrayOf(
        0xFFFF2D55.toInt(), 0xFFFF9500.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt(),
        0xFF00C7BE.toInt(), 0xFF0A84FF.toInt(), 0xFF5E5CE6.toInt(), 0xFFBF5AF2.toInt(),
        0xFFFF2D55.toInt(),
    )

    /** [percent] is 0..100. */
    fun setProgressAnimated(percent: Int) {
        val target = (percent / 100f).coerceIn(0f, 1f)
        grow?.cancel()
        grow = ValueAnimator.ofFloat(shown, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // one full rainbow per screen width, repeated so it can scroll forever
        shader = LinearGradient(0f, 0f, w.toFloat().coerceAtLeast(1f), 0f, colors, null, Shader.TileMode.REPEAT)
    }

    private fun startFlow() {
        if (flow != null) return
        flow = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopFlow() {
        flow?.cancel(); flow = null
        grow?.cancel(); grow = null
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) startFlow() else { stopFlow(); shown = 0f }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) startFlow()
    }

    override fun onDetachedFromWindow() {
        stopFlow()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val sh = shader ?: return
        val w = width * shown
        if (w <= 0f) return
        matrix.setTranslate(phase * width, 0f)
        sh.setLocalMatrix(matrix)
        paint.shader = sh
        rect.set(0f, 0f, w, height.toFloat())
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)
    }
}
