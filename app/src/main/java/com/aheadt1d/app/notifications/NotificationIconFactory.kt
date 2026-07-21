package com.aheadt1d.app.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import com.aheadt1d.app.R

/**
 * Renders status-bar / RemoteViews icons at runtime by rotating (and, for
 * double arrows, stacking) a single chevron drawable. Colors in the source
 * drawable don't matter for the small icon specifically - API 21+ status
 * bars render small icons as a flat alpha-channel silhouette, so only the
 * shape survives.
 */
object NotificationIconFactory {
    const val STATUS_BAR_ICON_SIZE_PX = 96
    const val EXPANDED_ICON_SIZE_PX = 144

    /**
     * Small icon shown in the status bar and notification header when a live
     * reading is available. Renders "110→" (value immediately followed by the
     * arrow label, no space) as a single line of monospace bold text, centered
     * in a [sizePx]×[sizePx] bitmap. Omitting the space between the number and
     * arrow lets us start at a larger font size — the auto-scale loop steps
     * down by 1px at a time until the text fits, so 2-, 3-, and 4-digit values
     * all render as large as possible without clipping.
     */
    fun readingIcon(context: Context, value: Int, arrow: GlucoseTrendArrow, sizePx: Int = STATUS_BAR_ICON_SIZE_PX): Icon {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val text = "$value${arrow.label}"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.46f
            // Step down until the text fits within 97% of the bitmap width.
            while (measureText(text) > sizePx * 0.97f && textSize > 8f) {
                textSize -= 1f
            }
        }

        // Vertical center: offset by half cap-height so glyphs sit mid-bitmap
        // rather than the baseline, which would push everything above center.
        val fm = paint.fontMetrics
        val y = sizePx / 2f - (fm.ascent + fm.descent) / 2f

        canvas.drawText(text, sizePx / 2f, y, paint)
        return Icon.createWithBitmap(bitmap)
    }

    /** Arrow-only icon — used for the expanded RemoteViews image view where
     *  the value is displayed separately as text, and as a fallback for stale
     *  or no-data states where [readingIcon] isn't applicable. */
    fun arrowIcon(context: Context, arrow: GlucoseTrendArrow, sizePx: Int = STATUS_BAR_ICON_SIZE_PX): Icon {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_trend_arrow)!!.mutate()
        drawable.setBounds(0, 0, sizePx, sizePx)

        fun drawRotated(offsetY: Float) {
            canvas.save()
            canvas.translate(0f, offsetY)
            canvas.rotate(arrow.rotationDegrees, sizePx / 2f, sizePx / 2f)
            drawable.draw(canvas)
            canvas.restore()
        }

        if (arrow.isDouble) {
            val offset = sizePx * 0.18f
            drawRotated(-offset)
            drawRotated(offset)
        } else {
            drawRotated(0f)
        }

        return Icon.createWithBitmap(bitmap)
    }

    /** A warning triangle with an exclamation mark, drawn at runtime. Used for
     *  the stale / "no new data" state so the status bar itself signals that the
     *  reading isn't current, not just the notification text. Rendered in white
     *  since the status bar flattens small icons to an alpha silhouette. */
    fun warningIcon(context: Context, sizePx: Int = STATUS_BAR_ICON_SIZE_PX): Icon {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val margin = sizePx * 0.13f

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.08f
            strokeJoin = Paint.Join.ROUND
        }
        val triangle = android.graphics.Path().apply {
            moveTo(sizePx / 2f, margin)
            lineTo(sizePx - margin, sizePx - margin)
            lineTo(margin, sizePx - margin)
            close()
        }
        canvas.drawPath(triangle, stroke)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val cx = sizePx / 2f
        canvas.drawRect(cx - sizePx * 0.035f, sizePx * 0.44f, cx + sizePx * 0.035f, sizePx * 0.66f, fill)
        canvas.drawCircle(cx, sizePx * 0.74f, sizePx * 0.05f, fill)
        return Icon.createWithBitmap(bitmap)
    }

    fun noDataIcon(context: Context, sizePx: Int = STATUS_BAR_ICON_SIZE_PX): Icon {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_trend_no_data)!!.mutate()
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return Icon.createWithBitmap(bitmap)
    }
}
