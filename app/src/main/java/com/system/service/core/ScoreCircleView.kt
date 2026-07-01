package com.system.service.core

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class ScoreCircleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var currentScore = 0
    private var displayScore = 0

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 24f; strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#0D1033")
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 24f; strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; color = Color.parseColor("#8899AA")
    }
    private val oval = RectF()
    private var shader: LinearGradient? = null

    fun animateTo(score: Int) {
        currentScore = score.coerceIn(0, 100)
        ValueAnimator.ofInt(displayScore, currentScore).apply {
            duration = 1500; interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                displayScore = it.animatedValue as Int
                invalidate()
            }
        }.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            Color.parseColor("#00E5FF"), Color.parseColor("#7C4DFF"),
            Shader.TileMode.CLAMP)
        arcPaint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - 32f
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(oval, 135f, 270f, false, bgPaint)
        val sweep = displayScore / 100f * 270f
        if (sweep > 0) canvas.drawArc(oval, 135f, sweep, false, arcPaint)
        textPaint.textSize = radius * 0.5f
        canvas.drawText("$displayScore", cx, cy + textPaint.textSize * 0.35f, textPaint)
        labelPaint.textSize = radius * 0.2f
        canvas.drawText("/ 100", cx, cy + textPaint.textSize * 0.9f, labelPaint)
    }
}
