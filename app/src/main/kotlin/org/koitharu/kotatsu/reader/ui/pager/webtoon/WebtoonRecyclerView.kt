package org.koitharu.kotatsu.reader.ui.pager.webtoon

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.EdgeEffect
import androidx.core.view.ViewCompat.TYPE_TOUCH
import androidx.core.view.forEach
import androidx.core.view.isEmpty
import androidx.core.view.isNotEmpty
import androidx.core.view.iterator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import java.util.LinkedList
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.sign

class WebtoonRecyclerView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

	private var onPageScrollListeners = LinkedList<OnWebtoonScrollListener>()
	private val scrollDispatcher = WebtoonScrollDispatcher()
	private val detachedViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
	private var isFixingScroll = false

	/** Sign of the last scroll the reader made - drag, fling or tap-scroll - and not of [repinPage]'s. */
	private var scrollDirection = 0

	private val pullGestureTracker = PullGestureTracker()

	var isPullGestureEnabled: Boolean = false
		set(value) {
			if (field != value) {
				field = value
				setEdgeEffectFactory(
					if (value) {
						NoOpEdgeEffectFactory()
					} else {
						EdgeEffectFactory()
					},
				)
				if (!value) {
					pullGestureTracker.reset(notifyListener = true)
				}
			}
		}
	var pullThreshold: Float = 0.3f
	private var pullListener: OnPullGestureListener? = null

	fun setOnPullGestureListener(listener: OnPullGestureListener?) {
		pullListener = listener
	}

	override fun onChildDetachedFromWindow(child: View) {
		super.onChildDetachedFromWindow(child)
		detachedViews.add(child)
	}

	override fun onChildAttachedToWindow(child: View) {
		super.onChildAttachedToWindow(child)
		detachedViews.remove(child)
	}

	override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
		if (isPullGestureEnabled && pullListener != null) {
			pullGestureTracker.onTouchEvent(ev)
		} else {
			pullGestureTracker.reset(notifyListener = false)
		}
		return super.dispatchTouchEvent(ev)
	}

	override fun onDetachedFromWindow() {
		pullGestureTracker.reset(notifyListener = true)
		super.onDetachedFromWindow()
	}

	override fun startNestedScroll(axes: Int) = startNestedScroll(axes, TYPE_TOUCH)

	override fun startNestedScroll(axes: Int, type: Int): Boolean = isNotEmpty()

	override fun dispatchNestedPreScroll(
		dx: Int,
		dy: Int,
		consumed: IntArray?,
		offsetInWindow: IntArray?
	) = dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, TYPE_TOUCH)

	override fun dispatchNestedPreScroll(
		dx: Int,
		dy: Int,
		consumed: IntArray?,
		offsetInWindow: IntArray?,
		type: Int
	): Boolean {
		if (dy != 0) {
			scrollDirection = dy.sign
		}
		val consumedY = consumeVerticalScroll(dy)
		if (consumed != null) {
			consumed[0] = 0
			consumed[1] = consumedY
		}
		notifyScrollChanged(dy)
		return consumedY != 0 || dy == 0
	}

	/**
	 * Splits a scroll between the list and the pages' own scrolling.
	 *
	 * A page taller than the viewport is laid out viewport-sized and scrolls its image inside itself, so
	 * pages only join up while each one's inner scroll matches where it sits: at its start while its top
	 * edge is on screen, at its end while its bottom edge is, and free only while it fills the viewport.
	 * A page therefore scrolls inside itself only while pinned to the edge the content moves away from -
	 * its top when scrolling down, its bottom when scrolling up - and every page the scroll reaches is
	 * walked, not just the first two, so a fast fling over short pages cannot carry a tall one past the
	 * edge with its image unscrolled, which is what cuts a page off mid-panel.
	 *
	 * @return the part of [dy] taken up inside pages; the list scrolls by the rest
	 */
	private fun consumeVerticalScroll(dy: Int): Int {
		if (dy == 0 || isEmpty()) {
			return 0
		}
		val distance = abs(dy)
		var consumed = 0
		var listScroll = 0
		for (i in 0 until childCount) {
			val child = getChildAt(if (dy > 0) i else childCount - 1 - i) as? WebtoonFrameLayout ?: continue
			// How far the list must still move before this page is pinned to the leading edge
			val toPin = (if (dy > 0) child.top else height - child.bottom) - listScroll
			if (toPin < 0) {
				continue // already past that edge, so it is at its end and has nothing left to scroll
			}
			if (toPin > distance - consumed - listScroll) {
				break // this scroll ends before the page gets there, and so do the ones after it
			}
			listScroll += toPin
			val remaining = distance - consumed - listScroll
			consumed += abs(child.dispatchVerticalScroll(if (dy > 0) remaining else -remaining))
		}
		return if (dy > 0) consumed else -consumed
	}

	fun addOnPageScrollListener(listener: OnWebtoonScrollListener) {
		onPageScrollListeners.add(listener)
	}

	fun removeOnPageScrollListener(listener: OnWebtoonScrollListener) {
		onPageScrollListeners.remove(listener)
	}

	private fun notifyScrollChanged(dy: Int) {
		val listeners = onPageScrollListeners
		if (listeners.isEmpty()) {
			return
		}
		scrollDispatcher.dispatchScroll(this, dy)
	}

	fun relayoutChildren() {
		forEach { child ->
			(child as WebtoonFrameLayout).target.requestLayout()
		}
		detachedViews.forEach { child ->
			(child as WebtoonFrameLayout).target.requestLayout()
		}
	}

	override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
		super.onLayout(changed, l, t, r, b)
		// Re-measuring moves pages and resizes them - a decoded image, zoom, rotation - without scrolling
		updateChildrenScroll()
	}

	/**
	 * Brings every page's inner scroll back in line with where the page sits: at its start when its top
	 * edge is on screen, at its end when its bottom edge is. See [consumeVerticalScroll].
	 *
	 * Snapping can move an image, but leaving a page out of line shows its middle against the next page.
	 */
	fun updateChildrenScroll() {
		if (isFixingScroll) {
			return
		}
		isFixingScroll = true
		try {
			for (child in this) {
				val frame = child as? WebtoonFrameLayout ?: continue
				if (frame.isRepinPending) {
					continue // repinPage() is about to place it without moving what is on screen
				}
				val ssiv = frame.target
				val range = ssiv.getScrollRange()
				val target = when {
					child.top > 0 -> 0
					child.bottom < height -> range
					else -> ssiv.getScroll().coerceIn(0, range)
				}
				if (ssiv.getScroll() != target) {
					ssiv.scrollTo(target)
				}
			}
		} finally {
			isFixingScroll = false
		}
	}

	/**
	 * Called when a page's image has been decoded.
	 *
	 * Until then the page is a viewport-sized placeholder with nothing to scroll, so the list may have
	 * scrolled it partly out of view. Deciding the inner scroll from position alone - end of the image if
	 * the page is above the top - jumps the reader to the end of a page they have barely started, or
	 * shows its middle against the next page. Posted because SSIV reports readiness from `onDraw`.
	 */
	fun onPageReady(child: WebtoonFrameLayout) {
		child.isRepinPending = true
		child.requestLayout()
		requestLayout()
		post { repinPage(child) }
	}

	fun onPageSizeChanged(child: WebtoonFrameLayout) {
		if (child.parent !== this) {
			return
		}
		child.requestLayout()
		requestLayout()
		post {
			if (child.parent === this) {
				updateChildrenScroll()
			}
		}
	}

	/**
	 * Puts a just-decoded page in line.
	 *
	 * Only one case moves the list: reading downwards into a page the list carried past the viewport
	 * top while it was a placeholder. The reader is part-way down it, so the row at the viewport top
	 * stays put and the page is pinned under it - whatever its inner scroll cannot take up, the list
	 * does. Everything else is squared up by position alone: a page decoded off screen must not be
	 * pulled into view, and one arriving from above while scrolling up has its end against the page
	 * below, which is where position puts it.
	 */
	private fun repinPage(child: WebtoonFrameLayout) {
		child.isRepinPending = false
		if (child.parent !== this) {
			return
		}
		val ssiv = child.target
		val range = ssiv.getScrollRange()
		val scroll = ssiv.getScroll().coerceIn(0, range)
		val isOnScreen = child.top < height && child.bottom > 0
		if (isOnScreen && scrollDirection >= 0 && child.top < 0 && scroll < range) {
			val target = minOf(scroll - child.top, range)
			ssiv.scrollTo(target)
			// RecyclerView.scrollBy moves only the list: it does not route through consumeVerticalScroll
			scrollBy(0, scroll - target)
		}
		// Squares up this page by position, and anything the list moving less than asked left out of line
		updateChildrenScroll()
	}

	private class WebtoonScrollDispatcher {

		private var firstPos = NO_POSITION
		private var lastPos = NO_POSITION

		fun dispatchScroll(rv: WebtoonRecyclerView, dy: Int) {
			val lm = rv.layoutManager as? LinearLayoutManager
			if (lm == null) {
				firstPos = NO_POSITION
				lastPos = NO_POSITION
				return
			}
			val newFirstPos = lm.findFirstVisibleItemPosition()
			val newLastPos = lm.findLastVisibleItemPosition()
			if (newFirstPos != firstPos || newLastPos != lastPos) {
				firstPos = newFirstPos
				lastPos = newLastPos
				if (newFirstPos != NO_POSITION && newLastPos != NO_POSITION) {
					rv.onPageScrollListeners.forEach { it.onScrollChanged(rv, dy, newFirstPos, newLastPos) }
				}
			}
		}
	}

	private class NoOpEdgeEffectFactory : EdgeEffectFactory() {

		override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect = object : EdgeEffect(view.context) {
			override fun draw(canvas: Canvas): Boolean = false
		}
	}

	private inner class PullGestureTracker {

		private var edge = PullEdge.NONE
		private var lastY = 0f
		private var distancePx = 0f
		private var isTracking = false

		fun onTouchEvent(ev: MotionEvent) {
			val listener = pullListener ?: return
			if (!isPullGestureEnabled) {
				return
			}
			when (ev.actionMasked) {
				MotionEvent.ACTION_DOWN -> {
					reset(notifyListener = false)
					isTracking = true
					lastY = ev.y
				}

				MotionEvent.ACTION_MOVE -> {
					if (!isTracking) {
						return
					}
					val y = ev.y
					val dy = y - lastY
					lastY = y
					if (dy != 0f) {
						handleMove(dy, listener)
					}
				}

				MotionEvent.ACTION_UP -> {
					if (isTracking) {
						finish(listener, cancelled = false)
					}
				}

				MotionEvent.ACTION_CANCEL -> {
					if (isTracking) {
						finish(listener, cancelled = true)
					}
				}
			}
		}

		fun reset(notifyListener: Boolean) {
			val listener = pullListener
			edge = PullEdge.NONE
			distancePx = 0f
			lastY = 0f
			isTracking = false
			if (notifyListener && listener != null) {
				listener.onPullProgressTop(0f)
				listener.onPullProgressBottom(0f)
				listener.onPullCancelled()
			}
		}

		private fun handleMove(dy: Float, listener: OnPullGestureListener) {
			if (height <= 0) {
				return
			}
			if (edge == PullEdge.NONE) {
				edge = when {
					dy > 0f && isAtAbsoluteTop() -> PullEdge.TOP
					dy < 0f && isAtAbsoluteBottom() -> PullEdge.BOTTOM
					else -> PullEdge.NONE
				}
				if (edge == PullEdge.NONE) {
					return
				}
			}

			val delta = when (edge) {
				PullEdge.TOP -> dy
				PullEdge.BOTTOM -> -dy
				else -> 0f
			}

			distancePx = (distancePx + delta).coerceAtLeast(0f)
			val progress = (distancePx / (height * pullThreshold).coerceAtLeast(1f))
			when (edge) {
				PullEdge.TOP -> listener.onPullProgressTop(progress)
				PullEdge.BOTTOM -> listener.onPullProgressBottom(progress)
				else -> Unit
			}
			if (distancePx <= 0f) {
				edge = PullEdge.NONE
			}
		}

		private fun finish(listener: OnPullGestureListener, cancelled: Boolean) {
			val progress = if (height > 0) (distancePx / (height * pullThreshold).coerceAtLeast(1f)) else 0f
			val edge = this.edge
			this.edge = PullEdge.NONE
			distancePx = 0f
			lastY = 0f
			isTracking = false

			listener.onPullProgressTop(0f)
			listener.onPullProgressBottom(0f)

			when {
				cancelled -> listener.onPullCancelled()
				edge == PullEdge.TOP && progress >= 1f -> listener.onPullTriggeredTop()
				edge == PullEdge.BOTTOM && progress >= 1f -> listener.onPullTriggeredBottom()
				else -> listener.onPullCancelled()
			}
		}

		private fun isAtAbsoluteTop(): Boolean {
			val lm = layoutManager as? LinearLayoutManager ?: return !canScrollVertically(-1)
			if (lm.findFirstVisibleItemPosition() != 0) {
				return false
			}
			val child = getChildAt(0) as? WebtoonFrameLayout ?: return false
			if (child.top < 0) {
				return false
			}
			return child.target.getScroll() <= 0
		}

		private fun isAtAbsoluteBottom(): Boolean {
			val adapter = adapter ?: return false
			val lm = layoutManager as? LinearLayoutManager ?: return !canScrollVertically(1)
			if (lm.findLastVisibleItemPosition() != adapter.itemCount - 1) {
				return false
			}
			if (childCount <= 0) {
				return false
			}
			val child = getChildAt(childCount - 1) as? WebtoonFrameLayout ?: return false
			if (child.bottom > height) {
				return false
			}
			val ssiv = child.target
			return ssiv.getScroll() >= ssiv.getScrollRange()
		}
	}

	private enum class PullEdge {
		NONE,
		TOP,
		BOTTOM,
	}

	interface OnWebtoonScrollListener {

		fun onScrollChanged(
			recyclerView: WebtoonRecyclerView,
			dy: Int,
			firstVisiblePosition: Int,
			lastVisiblePosition: Int,
		)
	}

	interface OnPullGestureListener {
		fun onPullProgressTop(progress: Float)
		fun onPullProgressBottom(progress: Float)
		fun onPullTriggeredTop()
		fun onPullTriggeredBottom()
		fun onPullCancelled()
	}
}