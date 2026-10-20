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
		// Rounded exactly as onMeasure rounds the view height. Rounding these two differently gave short
		// pages a 1px inner scroll and tall ones a last row that never showed - a hairline at the join.
		val totalHeight = (sHeight.toDouble() / sWidth.toDouble() * width).toInt()
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
			applyScroll()
			onImageEventListener.onReady()
		}
	}

	override fun onReady() {
		super.onReady()
		adjustScale()
		applyScroll()
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		applyScroll()
		(parent?.parent as? WebtoonRecyclerView)?.let { recyclerView ->
			(parent as? WebtoonFrameLayout)?.let(recyclerView::onPageSizeChanged)
		}
	}

	/**
	 * Shows the image at [scrollPos], whatever SSIV would have done on its own.
	 *
	 * SSIV centres a newly decoded image, and keeps that centre when the view is resized. A webtoon
	 * page is positioned from its top instead, so wherever the image becomes ready or the view
	 * changes size, the position has to be applied again here. This was missing for pages
	 * RecyclerView prefetches: they decode while detached and still zero-sized, the scroll asked for
	 * then could not be applied, and the page came on screen showing its middle - everything above
	 * that line skipped, which is the cut readers saw where two pages meet.
	 */
	private fun applyScroll() {
		if (canScale()) {
			scrollToInternal(scrollPos.coerceIn(0, getScrollRange()))
		}
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
			// Kept rather than dropped, so applyScroll can put it into effect once the view can
			scrollPos = pos.coerceAtLeast(0)
			return
		}
		minScale = width / sWidth.toFloat()
		maxScale = minScale
		// The width-fit mode too, not just its value: a page decoded while still zero-sized never got
		// it from adjustScale, and SSIV's default would fit the whole page inside the view instead -
		// a long strip shrunk to a sliver in the middle of the screen
		minimumScaleType = SCALE_TYPE_CUSTOM
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
