package unicode.sinhala.keyboard

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/**
 * A faint decorative overlay in the existing keyboard FrameLayout.
 * Does not contribute to keyboard height or receive touches; the
 * actual keys and their hit areas are unchanged.
 * Animation is cancelled when the keyboard view detaches.
 */
class FoxColorSignature @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextView(context, attrs) {
    private var colorCycle: ValueAnimator? = null
    private var softPulse: ObjectAnimator? = null
    private var lastColorFrame = -1

    init {
        isClickable = false
        isLongClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // Pass every touch through to the real keys below this drawing-only view.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopAnimation()
        alpha = 0.10f
        lastColorFrame = -1
        // Cyan -> violet -> pink -> mint: only ~3 visible color updates per second.
        colorCycle = ValueAnimator.ofArgb(
            0xFF40D9FF.toInt(),
            0xFFB78AFF.toInt(),
            0xFFFF83BA.toInt(),
            0xFF62F0C6.toInt(),
            0xFF40D9FF.toInt()
        ).apply {
            duration = 16000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animation ->
                val frame = (animation.animatedFraction * 48f).toInt()
                if (frame != lastColorFrame) {
                    lastColorFrame = frame
                    setTextColor(animation.animatedValue as Int)
                }
            }
            start()
        }
        // Alpha animation runs as an inexpensive view-property animation.
        softPulse = ObjectAnimator.ofFloat(this, View.ALPHA, 0.10f, 0.17f, 0.10f).apply {
            duration = 8000L
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopAnimation() {
        colorCycle?.cancel()
        softPulse?.cancel()
        colorCycle = null
        softPulse = null
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }
}
