package com.touchlock

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 滑动解锁条（iPhone 滑动关机样式）。
 *
 * 防误触设计：
 * - 手指必须按在滑块上（含 12dp 容差）才开始拖动，按在轨道其它位置不响应；
 * - 必须横向拖到行程的 [UNLOCK_THRESHOLD] 以上才解锁；
 * - 松手未到位自动回弹，因此单点、长按、慢速拖动都不会解锁。
 *
 * 全部图形由代码绘制，不额外依赖 drawable 资源；配色取自 colors.xml，
 * 在 screenBrightness=0.0f 的最低背光下仍保持足够对比度。
 */
class SlideToUnlockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 拖到底时回调（由 TouchLockService 绑定为 stopSelf） */
    var onUnlocked: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    /** 滑块与轨道边缘的留白 */
    private val trackPadding = dp(6f)

    /** 按下判定容差：手指落在滑块外扩该范围内也算命中 */
    private val touchSlopExtra = dp(12f)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.slide_track)
        style = Paint.Style.FILL
    }

    private val trackStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.slide_track_stroke)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }

    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.slide_knob)
        style = Paint.Style.FILL
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.slide_knob_icon)
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.slide_text)
        textSize = dp(16f)
        textAlign = Paint.Align.CENTER
    }

    private val trackRect = RectF()
    private val arrowPath = Path()
    private val labelText = context.getString(R.string.slide_to_unlock)

    /** 滑块左边缘 x */
    private var knobX = 0f

    /** 拖动进度 0f..1f */
    private var progress = 0f
    private var dragging = false
    private var grabOffset = 0f
    private var animator: ValueAnimator? = null

    /** 滑块直径（依赖布局高度） */
    private val knobSize: Float
        get() = (height - trackPadding * 2).coerceAtLeast(0f)

    /** 可拖动总行程 */
    private val maxTravel: Float
        get() = (width - trackPadding * 2 - knobSize).coerceAtLeast(0f)

    init {
        contentDescription = labelText
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        knobX = trackPadding
        progress = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = h / 2f

        trackRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)
        canvas.drawRoundRect(trackRect, radius, radius, trackStrokePaint)

        // 提示文字随拖动渐隐，让位给滑块
        textPaint.alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
        val fm = textPaint.fontMetrics
        canvas.drawText(labelText, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, textPaint)

        // 滑块
        val knob = knobSize
        val centerX = knobX + knob / 2f
        val centerY = h / 2f
        canvas.drawCircle(centerX, centerY, knob / 2f, knobPaint)
        drawArrows(canvas, centerX, centerY, knob)
    }

    /** 滑块内绘制两个向右的箭头 */
    private fun drawArrows(canvas: Canvas, cx: Float, cy: Float, knob: Float) {
        val halfHeight = knob * 0.18f
        val halfWidth = halfHeight * 0.6f
        val gap = knob * 0.16f
        for (index in 0..1) {
            val x = cx - gap / 2f + index * gap
            arrowPath.reset()
            arrowPath.moveTo(x - halfWidth, cy - halfHeight)
            arrowPath.lineTo(x + halfWidth, cy)
            arrowPath.lineTo(x - halfWidth, cy + halfHeight)
            canvas.drawPath(arrowPath, arrowPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                // 只有按在滑块上才开始拖动，这是防误触的关键
                val left = knobX - touchSlopExtra
                val right = knobX + knobSize + touchSlopExtra
                if (event.x < left || event.x > right) return false
                dragging = true
                grabOffset = event.x - knobX
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                updateKnobX(event.x - grabOffset)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                dragging = false
                if (progress >= UNLOCK_THRESHOLD) {
                    onUnlocked?.invoke()
                } else {
                    animateBack()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                animateBack()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateKnobX(targetX: Float) {
        knobX = targetX.coerceIn(trackPadding, trackPadding + maxTravel)
        progress = if (maxTravel > 0f) (knobX - trackPadding) / maxTravel else 0f
    }

    private fun animateBack() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(knobX, trackPadding).apply {
            duration = REBOUND_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                updateKnobX(it.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    companion object {
        /** 拖到行程的 92% 以上视为解锁 */
        private const val UNLOCK_THRESHOLD = 0.92f
        private const val REBOUND_DURATION_MS = 250L
    }
}
