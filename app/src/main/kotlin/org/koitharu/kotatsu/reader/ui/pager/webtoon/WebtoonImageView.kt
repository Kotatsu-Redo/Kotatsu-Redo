package org.koitharu.kotatsu.reader.ui.pager.webtoon

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import androidx.core.view.ancestors
import androidx.recyclerview.widget.RecyclerView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.koitharu.kotatsu.core.util.ext.resolveDp
import kotlin.math.roundToInt

class WebtoonImageView @JvmOverloads constructor(
	context: Context,
	attr: AttributeSet? = null,
) : SubsamplingScaleImageView(context, attr) {

	private val ct = PointF()

	private var scrollPos = 0
	private var debugPaint: Paint? = null

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		if (isDebugDrawingEnabled) {
			drawDebug(canvas)
		}
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
		desiredHeight = desiredHeight.coerceAtLeast(suggestedMinimumHeight)
		// A page is never taller than the viewport - it scrolls internally instead - but only cap when
		// the viewport height is actually known. parentHeight() is 0 whenever this view is measured
		// without a RecyclerView above it (while detached, or before being attached), and capping to
		// that collapses the page to zero height, which reads as two pages running into each other.
		val viewportHeight = parentHeight()
		if (viewportHeight > 0) {
			desiredHeight = desiredHeight.coerceAtMost(viewportHeight)
		}
		setMeasuredDimension(desiredWidth, desiredHeight)
	}

	override fun onDownSamplingChanged() {
		super.onDownSamplingChanged()
		if (isReady) {
			adjustScale()
			onImageEventListener.onReady()
		}
	}

	override fun onReady() {
		super.onReady()
		adjustScale()
	}

	/**
	 * [getScrollRange] reports `0` both for an image shorter than the view and for one that has not been
	 * decoded yet, and callers cannot tell those apart. Without this guard the second case divides by
	 * `sWidth == 0`: float division yields `Infinity` rather than throwing, so `minScale` and `maxScale`
	 * both become infinite, and SSIV clamps the image to that scale as soon as it does decode - the page
	 * then renders as a hugely magnified crop, silently and only in webtoon mode.
	 */
	private fun canScale(): Boolean = isReady && sWidth > 0 && sHeight > 0 && width > 0

	private fun scrollToInternal(pos: Int) {
		if (!canScale()) {
			scrollPos = 0
			return
		}
		minScale = width / sWidth.toFloat()
		maxScale = minScale
		scrollPos = pos
		ct.set(sWidth / 2f, (height / 2f + pos.toFloat()) / minScale)
		setScaleAndCenter(minScale, ct)
	}

	private fun adjustScale() {
		if (!canScale()) {
			return
		}
		minScale = width / sWidth.toFloat()
		maxScale = minScale
		minimumScaleType = SCALE_TYPE_CUSTOM
		requestLayout()
	}

	private fun parentHeight(): Int {
		return ancestors.firstNotNullOfOrNull { it as? RecyclerView }?.height ?: 0
	}

	private fun drawDebug(canvas: Canvas) {
		val paint = debugPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = android.graphics.Color.RED
			strokeWidth = context.resources.resolveDp(2f)
			textAlign = Paint.Align.LEFT
			textSize = context.resources.resolveDp(14f)
			debugPaint = this
		}
		paint.style = Paint.Style.STROKE
		canvas.drawRect(1f, 1f, width.toFloat() - 1f, height.toFloat() - 1f, paint)
		paint.style = Paint.Style.FILL
		canvas.drawText("${getScroll()} / ${getScrollRange()}", 100f, 100f, paint)
	}
}
