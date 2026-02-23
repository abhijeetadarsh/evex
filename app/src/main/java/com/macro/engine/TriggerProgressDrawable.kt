package com.macro.engine

import android.graphics.*
import android.graphics.drawable.Drawable

/**
 * Custom drawable for trigger buttons that shows:
 *   - A filled circle background
 *   - A border that acts as a radial progress indicator (0→360°)
 *
 * The border transitions from [activeColor] to [normalColor] as progress
 * sweeps from 0% to 100%.
 */
class TriggerProgressDrawable(
    private val fillColor: Int = Color.parseColor("#58A6FF"),
    private val normalColor: Int = Color.WHITE,
    private val activeColor: Int = Color.parseColor("#3FB950"),
    private val borderWidth: Float = 6f
) : Drawable() {

    /** Progress from 0f (start) to 1f (complete). */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }

    /** Whether the trigger is currently executing. */
    var isActive: Boolean = false
        set(value) {
            field = value
            invalidateSelf()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        strokeCap = Paint.Cap.ROUND
    }

    private val arcRect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val radius = (minOf(b.width(), b.height()) / 2f) - borderWidth / 2f

        // 1) Filled circle
        canvas.drawCircle(cx, cy, radius, fillPaint)

        // 2) Border
        arcRect.set(
            cx - radius, cy - radius,
            cx + radius, cy + radius
        )

        if (isActive) {
            // Draw full green border first (entire circle is green at start)
            borderPaint.color = activeColor
            canvas.drawCircle(cx, cy, radius, borderPaint)

            // Sweep white arc over it from top (-90°) — "drains" the green away
            if (progress > 0f) {
                borderPaint.color = normalColor
                val sweepAngle = progress * 360f
                canvas.drawArc(arcRect, -90f, sweepAngle, false, borderPaint)
            }
        } else {
            // Idle state — full white border
            borderPaint.color = normalColor
            canvas.drawCircle(cx, cy, radius, borderPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        borderPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        borderPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
