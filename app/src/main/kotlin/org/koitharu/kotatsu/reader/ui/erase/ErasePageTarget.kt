package org.koitharu.kotatsu.reader.ui.erase

import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.view.View
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.reader.domain.erase.PageMask
import org.koitharu.kotatsu.reader.domain.erase.SourceRegion
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import org.koitharu.kotatsu.reader.ui.pager.vm.PageState

/**
 * One page on screen, and the way between screen pixels and that page's source pixels.
 *
 * Every reader mode ends in a [SubsamplingScaleImageView], but webtoon zoom scales its parent instead of
 * the image, a page transformer may be moving the pager, a trimmed page is a region of its file, and a page
 * with something already hidden shows a shorter picture than the file. So the path is walked view by view,
 * rather than trusting any one view's position on screen.
 */
class ErasePageTarget(
	val page: ReaderPage,
	val imageUri: Uri,
	private val view: SubsamplingScaleImageView,
	region: Rect?,
	private val mask: PageMask?,
	private val pageState: StateFlow<PageState>,
) {

	private val offsetX = region?.left ?: 0
	private val offsetY = region?.top ?: 0
	private val shownState = pageState.value
	private val point = FloatArray(2)
	private val inverse = Matrix()

	fun screenToSource(rawX: Float, rawY: Float): PointF? {
		point[0] = rawX
		point[1] = rawY
		screenToLocal(view)
		val source = view.viewToSourceCoord(point[0], point[1]) ?: return null
		source.offset(offsetX.toFloat(), offsetY.toFloat())
		if (mask != null) {
			source.y = mask.toSource(source.y.toInt()).toFloat()
		}
		return source
	}

	fun sourceToScreen(sourceX: Float, sourceY: Float): PointF? {
		val visibleY = mask?.toVisible(sourceY.toInt())?.toFloat() ?: sourceY
		val local = view.sourceToViewCoord(sourceX - offsetX, visibleY - offsetY) ?: return null
		point[0] = local.x
		point[1] = local.y
		localToScreen(view)
		return PointF(point[0], point[1])
	}

	/** The region in source pixels between two screen corners, kept within the image. */
	fun screenToRegion(left: Float, top: Float, right: Float, bottom: Float, width: Int, height: Int): SourceRegion? {
		val start = screenToSource(left, top) ?: return null
		val end = screenToSource(right, bottom) ?: return null
		val regionLeft = minOf(start.x, end.x).toInt().coerceIn(0, width)
		val regionTop = minOf(start.y, end.y).toInt().coerceIn(0, height)
		return SourceRegion(
			left = regionLeft,
			top = regionTop,
			right = maxOf(start.x, end.x).toInt().coerceIn(regionLeft, width),
			bottom = maxOf(start.y, end.y).toInt().coerceIn(regionTop, height),
		)
	}

	/**
	 * Waits for the page to stop showing the erased image, which is either having a masked copy on screen
	 * or having gone. What covers the image on screen stays up until then, so that it never shows through.
	 */
	suspend fun awaitErased() {
		withTimeoutOrNull(MAX_APPLY_WAIT_MS) {
			pageState.first { state ->
				when (state) {
					is PageState.Hidden, is PageState.Empty -> true
					is PageState.Shown -> state != shownState
					else -> false
				}
			}
		}
	}

	private fun screenToLocal(target: View) {
		val parent = target.parent as? View
		if (parent == null) {
			val location = IntArray(2)
			target.getLocationOnScreen(location)
			point[0] -= location[0]
			point[1] -= location[1]
			return
		}
		screenToLocal(parent)
		point[0] += parent.scrollX - target.left
		point[1] += parent.scrollY - target.top
		val matrix = target.matrix
		if (!matrix.isIdentity && matrix.invert(inverse)) {
			inverse.mapPoints(point)
		}
	}

	private fun localToScreen(target: View) {
		val parent = target.parent as? View
		if (parent == null) {
			val location = IntArray(2)
			target.getLocationOnScreen(location)
			point[0] += location[0]
			point[1] += location[1]
			return
		}
		val matrix = target.matrix
		if (!matrix.isIdentity) {
			matrix.mapPoints(point)
		}
		point[0] += target.left - parent.scrollX
		point[1] += target.top - parent.scrollY
		localToScreen(parent)
	}

	private companion object {
		/** Touches are held for this long at most: the reader should never be left unable to scroll. */
		const val MAX_APPLY_WAIT_MS = 1500L
	}
}
