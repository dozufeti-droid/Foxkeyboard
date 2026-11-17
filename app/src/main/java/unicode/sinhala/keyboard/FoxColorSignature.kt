package unicode.sinhala.keyboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** FoxProVoiceLearning2026: display-only signature, no size or touch changes. */
class FoxColorSignature @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = resources.displayMetrics.scaledDensity * 11f
        isSubpixelText = true
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = resources.displayMetrics.density * 1.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val signature = "Powered by Damith Wijekoon"
    private val shades = intArrayOf(
        Color.rgb(57, 223, 255), Color.rgb(177, 119, 255),
        Color.rgb(255, 111, 185), Color.rgb(76, 247, 190)
    )
    private var floatPosition = 0f
    private var anim: ValueAnimator? = null
    private var lastDraw = 0L

    init {
        isClickable = false
        isFocusable = false
        isLongClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        // Keep text above/between keys subtle; all key hit targets remain unchanged.
        val measured = textPaint.measureText(signature)
        val room = (width - measured - 45f * resources.displayMetrics.density)
            .coerceAtLeast(0f) * 0.5f
        val x = width * 0.5f + room * (2f * floatPosition - 1f)
        val y = height * (0.22f + floatPosition * 0.60f)
        val gradientWidth = measured * 0.65f
        textPaint.shader = LinearGradient(
            x - gradientWidth, y, x + gradientWidth, y,
            shades, null, Shader.TileMode.CLAMP
        )
        textPaint.alpha = 100 // gentle but visible on the charcoal keyboard
        canvas.drawText(signature, x, y, textPaint)
        textPaint.shader = null

        val half = measured / 2f
        val radius = min(resources.displayMetrics.density * 6.5f, height * 0.015f)
        starPaint.alpha = 145
        drawStar(canvas, x-half-radius*2.4f, y-textPaint.textSize*0.3f, radius, shades[1])
        drawStar(canvas, x+half+radius*2.4f, y-textPaint.textSize*0.3f, radius, shades[3])
    }

    private fun drawStar(canvas: Canvas, x: Float, y: Float, size: Float, color: Int) {
        starPaint.color = color
        canvas.drawLine(x-size, y, x+size, y, starPaint)
        canvas.drawLine(x, y-size, x, y+size, starPaint)
        val diag = size * 0.42f
        canvas.drawLine(x-diag, y-diag, x+diag, y+diag, starPaint)
        canvas.drawLine(x+diag, y-diag, x-diag, y+diag, starPaint)
    }

    private fun startMotion() {
        stopMotion()
        if (visibility != VISIBLE) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                floatPosition = it.animatedValue as Float
                val now = SystemClock.uptimeMillis()
                // Throttle expensive overlay redraw to ~20 frames/sec.
                if (now - lastDraw >= 50L) {
                    lastDraw = now
                    invalidate()
                }
            }
            start()
        }
    }

    private fun stopMotion() {
        anim?.cancel()
        anim = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (windowVisibility == VISIBLE) startMotion()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isAttachedToWindow && anim == null) startMotion()
        if (visibility != VISIBLE) stopMotion()
    }

    override fun onDetachedFromWindow() {
        stopMotion()
        super.onDetachedFromWindow()
    }
}
