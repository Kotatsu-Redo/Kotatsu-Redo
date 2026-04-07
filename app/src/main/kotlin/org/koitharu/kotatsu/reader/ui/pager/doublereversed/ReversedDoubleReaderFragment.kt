package org.koitharu.kotatsu.reader.ui.pager.doublereversed

import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import org.koitharu.kotatsu.reader.ui.pager.doublepage.DoubleReaderFragment

class ReversedDoubleReaderFragment : DoubleReaderFragment() {

	override fun switchPageBy(delta: Int) {
		super.switchPageBy(-delta)
	}

	override fun switchPageTo(position: Int, smooth: Boolean) {
		super.switchPageTo(reversed(position), smooth)
	}

	override suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?) {
		super.onPagesChanged(pages.reversed(), pendingState)
	}

	override fun notifyPageChanged(lowerPos: Int, upperPos: Int) {
		// Convert padded positions → reversed positions → original positions
		val revLower = positionMap.getOrElse(upperPos) { -1 }
		val revUpper = positionMap.getOrElse(lowerPos) { -1 }
		val n = originalPageCount
		val origLower = if (revLower >= 0) (n - 1 - revLower) else -1
		val origUpper = if (revUpper >= 0) (n - 1 - revUpper) else -1
		val lower = if (origLower >= 0) origLower else origUpper
		val upper = if (origUpper >= 0) origUpper else origLower
		if (lower >= 0) {
			viewModel.onCurrentPageChanged(lower, upper.coerceAtLeast(lower))
		}
	}

	private fun reversed(position: Int): Int {
		return ((readerAdapter?.itemCount ?: 0) - position - 1).coerceAtLeast(0)
	}
}
