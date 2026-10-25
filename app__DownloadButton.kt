package dev.sk2andy.materialbrowser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as M

/**
 * Top-bar download button: a down arrow on a tonal circle (M3 color roles, so it follows light/dark/dynamic color).
 * While something downloads the circle fills from the bottom (like water) to show progress;
 * when it ends the circle flashes full (green) or red (failed) and fades back to idle.
 */
class DownloadButton(context: Context) : View(context) {

    private enum class Mode { IDLE, PROGRESS, WAITING, ENDING }

    private val d = resources.displayMetrics.density
    private fun role(attr: Int) = MaterialColors.getColor(context, attr, 0xFFFF00FF.toInt())
    private val okColor = role(M.attr.colorPrimaryContainer)
    private val failColor = role(M.attr.colorErrorContainer)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = role(M.attr.colorSurfaceContainerHighest) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = role(M.attr.colorOnSurfaceVariant)
    }
    private val circle = Path()
    private val icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_download)?.mutate()
        ?.also { it.setTint(role(M.attr.colorOnSurface)) }

    private var mode = Mode.IDLE
    private var level = 0f          // 0..1, how much of the circle is filled
    private var fillAlpha = 255     // 0..255
    private var fillColor = okColor
    private var anim: ValueAnimator? = null

    init {
        contentDescription = "Downloads"
    }

    /** Download running, size unknown: the fill rises and falls. */
    fun showWaiting() {
        if (mode == Mode.WAITING) return
        stopAnim()
        mode = Mode.WAITING
        fillColor = okColor; fillAlpha = 255; level = 0.15f
        play(ValueAnimator.ofFloat(0.15f, 0.85f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { level = it.animatedValue as Float; invalidate() }
        })
    }

    /** Download running: [fraction] is 0..1. */
    fun showProgress(fraction: Float) {
        val target = fraction.coerceIn(0f, 1f)
        if (mode != Mode.PROGRESS) {
            stopAnim()
            mode = Mode.PROGRESS
            fillColor = okColor; fillAlpha = 255
        } else stopAnim()
        val from = level
        play(ValueAnimator.ofFloat(from, target).apply {
            duration = 250
            addUpdateListener { level = it.animatedValue as Float; invalidate() }
        })
    }

    /** All downloads ended: fill up (or flash red), hold briefly, fade back to idle. */
    fun finish(success: Boolean) {
        stopAnim()
        mode = Mode.ENDING
        fillColor = if (success) okColor else failColor
        fillAlpha = 255
        val from = if (success) level else 1f
        play(ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                when {
                    t < 0.2f -> { level = from + (1f - from) * (t / 0.2f); fillAlpha = 255 }
                    t < 0.6f -> { level = 1f; fillAlpha = 255 }
                    else -> { level = 1f; fillAlpha = (255 * (1f - (t - 0.6f) / 0.4f)).toInt() }
                }
                invalidate()
            }
        }) { reset() }
    }

    private fun play(a: ValueAnimator, onEnd: (() -> Unit)? = null) {
        anim = a
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (anim === a && onEnd != null) onEnd()
            }
        })
        a.start()
    }

    private fun stopAnim() {
        val a = anim
        anim = null // so a cancelled animator's end callback is ignored
        a?.cancel()
    }

    private fun reset() {
        anim = null
        mode = Mode.IDLE
        level = 0f
        fillAlpha = 255
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(14f * d, minOf(width, height) / 2f)

        canvas.drawCircle(cx, cy, r, bgPaint)

        if (level > 0f && fillAlpha > 0) {
            circle.reset()
            circle.addCircle(cx, cy, r, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(circle)
            fillPaint.color = fillColor
            fillPaint.alpha = fillAlpha
            val top = cy + r - 2f * r * level
            canvas.drawRect(cx - r, top, cx + r, cy + r, fillPaint)
            canvas.restoreToCount(save)
        }

        val half = (11f * d).toInt()
        icon?.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        icon?.draw(canvas)
    }
}
