package com.silversniffle.multicam.ui

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

/**
 * A [TextureView] that measures itself to a fixed aspect ratio (letterboxing within the
 * space the parent gives it) so previews aren't stretched and the grid stays uniform.
 */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : TextureView(context, attrs, defStyle) {

    private var ratioWidth = 0
    private var ratioHeight = 0

    fun setAspectRatio(width: Int, height: Int) {
        if (width < 0 || height < 0) return
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (ratioWidth == 0 || ratioHeight == 0 || width == 0) {
            setMeasuredDimension(width, MeasureSpec.getSize(heightMeasureSpec))
            return
        }
        // Drive the height from the (known) width and the preview aspect ratio. We deliberately
        // ignore the height spec: when this view is laid out with wrap_content height, the parent
        // measures it with an unspecified/zero height spec, which would otherwise collapse the tile.
        setMeasuredDimension(width, width * ratioHeight / ratioWidth)
    }
}
