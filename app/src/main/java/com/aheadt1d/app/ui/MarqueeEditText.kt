package com.aheadt1d.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat

/**
 * An EditText that automatically scrolls its hint/ghost text horizontally
 * like a continuous banner (marquee) whenever the hint exceeds the visible width
 * of the field and the field is empty.
 *
 * Designed as a drop-in replacement for any [android.widget.EditText] in layouts.
 */
class MarqueeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private var customHintColor: Int = 0
    private var lastSeenHint: String = ""
    private var animator: ValueAnimator? = null
    private var scrollOffset: Float = 0f

    private val speedDpPerSec = 45f
    private val gapDp = 40f

    init {
        var initialColor = currentHintTextColor
        if (initialColor == 0 || initialColor == Color.TRANSPARENT) {
            val typedValue = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.textColorHint, typedValue, true)) {
                initialColor = if (typedValue.resourceId != 0) {
                    ContextCompat.getColor(context, typedValue.resourceId)
                } else {
                    typedValue.data
                }
            }
        }
        customHintColor = initialColor
        // Suppress default TextView hint drawing so only our custom marquee/static hint is drawn
        setHintTextColor(Color.TRANSPARENT)
    }

    fun setCustomHintColor(color: Int) {
        customHintColor = color
        invalidate()
    }

    private fun updateMarquee() {
        val hintText = hint?.toString().orEmpty()
        if (width <= 0 || !isAttachedToWindow || visibility != View.VISIBLE || !text.isNullOrEmpty() || hintText.isEmpty()) {
            stopAnimation()
            return
        }

        val availableWidth = (width - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(0)
        val textWidth = paint.measureText(hintText)

        if (textWidth > availableWidth) {
            startAnimation(textWidth)
        } else {
            stopAnimation()
        }
    }

    private fun startAnimation(textWidth: Float) {
        val gapPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            gapDp,
            resources.displayMetrics
        )
        val span = textWidth + gapPx
        val speedPxPerSec = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            speedDpPerSec,
            resources.displayMetrics
        ).coerceAtLeast(10f)

        val durationMs = ((span / speedPxPerSec) * 1000).toLong().coerceAtLeast(500L)

        if (animator?.isRunning == true && animator?.duration == durationMs) {
            return
        }

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, span).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { anim ->
                scrollOffset = anim.animatedValue as Float
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
        scrollOffset = 0f
    }

    override fun onDraw(canvas: Canvas) {
        val currentHint = hint?.toString().orEmpty()
        if (currentHint != lastSeenHint) {
            lastSeenHint = currentHint
            updateMarquee()
        }

        if (text.isNullOrEmpty() && currentHint.isNotEmpty()) {
            val availableWidth = (width - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(0)
            val textWidth = paint.measureText(currentHint)

            val originalColor = paint.color
            paint.color = customHintColor

            val y = if (baseline > 0) {
                baseline.toFloat()
            } else {
                val fm = paint.fontMetrics
                val textHeight = fm.descent - fm.ascent
                paddingTop + (height - paddingTop - paddingBottom - textHeight) / 2f - fm.ascent
            }

            val clipLeft = compoundPaddingLeft.toFloat()
            val clipRight = (width - compoundPaddingRight).toFloat()

            if (textWidth > availableWidth) {
                val gapPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    gapDp,
                    resources.displayMetrics
                )
                val span = textWidth + gapPx
                val offset = if (span > 0) scrollOffset % span else 0f

                canvas.save()
                canvas.clipRect(clipLeft, 0f, clipRight, height.toFloat())

                var x = clipLeft - offset
                while (x < clipRight) {
                    canvas.drawText(currentHint, x, y, paint)
                    x += span
                }

                canvas.restore()
            } else {
                canvas.save()
                canvas.clipRect(clipLeft, 0f, clipRight, height.toFloat())
                canvas.drawText(currentHint, clipLeft, y, paint)
                canvas.restore()
            }

            paint.color = originalColor
        }

        super.onDraw(canvas)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        updateMarquee()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMarquee()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateMarquee()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateMarquee()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateMarquee()
    }
}
