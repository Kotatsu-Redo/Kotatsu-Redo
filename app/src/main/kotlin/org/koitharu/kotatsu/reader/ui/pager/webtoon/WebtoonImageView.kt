package org.koitharu.kotatsu.reader.ui.pager.webtoon

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.util.AttributeSet
import androidx.core.view.ancestors
import androidx.recyclerview.widget.RecyclerView
import com.sonai.ssiv.SubsamplingScaleImageView
import kotlin.math.roundToInt

class WebtoonImageView @JvmOverloads constructor(
    context: Context,
    attr: AttributeSet? = null,
) : SubsamplingScaleImageView(context, attr) {

    private val ct = PointF()

    private var scrollPos = 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
    }

    fun scrollBy(delta: Int) {
        val maxScroll = getScrollRange()
        if (maxScroll == 0) {
            return
        }
        val newScroll = scrollPos + delta
        scrollToInternal(newScroll.coerceIn(0, maxScroll))
    }

    fun scrollTo(y: Int) {
        val maxScroll = getScrollRange()
        if (maxScroll == 0) {
            scrollToInternal(0)
            return
        }
        scrollToInternal(y.coerceIn(0, maxScroll))
    }

    fun getScroll() = scrollPos

    fun getScrollRange(): Int {
        if (!isReady) {
            return 0
        }
        val totalHeight = (sHeight * width / sWidth.toFloat()).roundToInt()
        return (totalHeight - height).coerceAtLeast(0)
    }

    override fun recycle() {
        scrollPos = 0
        super.recycle()
    }

    override fun getSuggestedMinimumHeight(): Int {
        var desiredHeight = super.getSuggestedMinimumHeight()
        if (sHeight == 0) {
            val parentHeight = parentHeight()
            if (desiredHeight < parentHeight) {
                desiredHeight = parentHeight
            }
        }
        return desiredHeight
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSpecMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightSpecMode = MeasureSpec.getMode(heightMeasureSpec)
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = MeasureSpec.getSize(heightMeasureSpec)
        val resizeWidth = widthSpecMode != MeasureSpec.EXACTLY
        val resizeHeight = heightSpecMode != MeasureSpec.EXACTLY
        var desiredWidth = parentWidth
        var desiredHeight = parentHeight
        if (sWidth > 0 && sHeight > 0) {
            if (resizeWidth && resizeHeight) {
                desiredWidth = sWidth
                desiredHeight = sHeight
            } else if (resizeHeight) {
                desiredHeight = (sHeight.toDouble() / sWidth.toDouble() * desiredWidth).toInt()
            } else if (resizeWidth) {
                desiredWidth = (sWidth.toDouble() / sHeight.toDouble() * desiredHeight).toInt()
            }
        }
        desiredWidth = desiredWidth.coerceAtLeast(suggestedMinimumWidth)
        desiredHeight = desiredHeight.coerceAtLeast(suggestedMinimumHeight).coerceAtMost(parentHeight())
        setMeasuredDimension(desiredWidth, desiredHeight)
    }

    // Removed invalid override of onDownSamplingChanged

    override fun onReady() {
        super.onReady()
        adjustScale()
    }

    private fun scrollToInternal(pos: Int) {
        minScale = width / sWidth.toFloat()
        maxScale = minScale
        scrollPos = pos
        ct.set(sWidth / 2f, (height / 2f + pos.toFloat()) / minScale)
        setScaleAndCenter(minScale, ct)
    }

    private fun adjustScale() {
        minScale = width / sWidth.toFloat()
        maxScale = minScale
        minimumScaleType = SCALE_TYPE_CUSTOM
        requestLayout()
    }

    private fun parentHeight(): Int {
        return ancestors.firstNotNullOfOrNull { it as? RecyclerView }?.height ?: 0
    }
}
