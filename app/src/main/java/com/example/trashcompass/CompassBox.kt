package com.example.trashcompass

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Holds the compass ring and arrow in a centred square that is as large as
 * the space it is given, up to [maxSide].
 *
 * It takes whatever height is left over on screen (it is the weighted row of
 * the main column), so on a small phone the compass shrinks instead of
 * sliding underneath the search bar or the distance read-out. When the
 * column is measured without a height limit (inside the ScrollView) it asks
 * only for its minimum height, so the page scrolls only once the compass is
 * already at its smallest.
 *
 * Children should use match_parent with layout_gravity="center".
 */
class CompassBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val maxSide = (300 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height =
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) suggestedMinimumHeight
            else MeasureSpec.getSize(heightMeasureSpec)
        val side = minOf(width, height, maxSide).coerceAtLeast(0)
        val square = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        super.onMeasure(square, square)
        setMeasuredDimension(width, height)
    }
}
