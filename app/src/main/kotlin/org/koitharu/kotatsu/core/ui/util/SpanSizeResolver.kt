package org.koitharu.kotatsu.core.ui.util

import android.view.View
import androidx.annotation.Px
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.parsers.util.toIntUp
import kotlin.math.abs

class SpanSizeResolver(
	private val recyclerView: RecyclerView,
	@Px minItemWidth: Int,
) : View.OnLayoutChangeListener {

	/** The narrowest a column may get. Changing it lays the list out again with as many as now fit. */
	@Px
	var minItemWidth: Int = minItemWidth
		set(value) {
			if (field != value) {
				field = value
				invalidate()
			}
		}

	fun attach() {
		recyclerView.addOnLayoutChangeListener(this)
	}

	fun detach() {
		recyclerView.removeOnLayoutChangeListener(this)
	}

	override fun onLayoutChange(
		v: View?,
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		oldLeft: Int,
		oldTop: Int,
		oldRight: Int,
		oldBottom: Int,
	) {
		invalidateInternal(abs(right - left))
	}

	fun invalidate() {
		invalidateInternal(recyclerView.width)
	}

	private fun invalidateInternal(width: Int) {
		if (width <= 0) {
			return
		}
		val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
		val estimatedCount = (width / minItemWidth.coerceAtLeast(1).toFloat()).toIntUp()
		if (lm.spanCount != estimatedCount) {
			lm.spanCount = estimatedCount
			lm.spanSizeLookup?.run {
				invalidateSpanGroupIndexCache()
				invalidateSpanIndexCache()
			}
		}
	}
}
