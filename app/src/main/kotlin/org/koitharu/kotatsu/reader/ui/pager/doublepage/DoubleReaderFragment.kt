package org.koitharu.kotatsu.reader.ui.pager.doublepage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.list.lifecycle.RecyclerViewLifecycleDispatcher
import org.koitharu.kotatsu.core.util.ext.firstVisibleItemPosition
import org.koitharu.kotatsu.databinding.FragmentReaderDoubleBinding
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.pager.BaseReaderAdapter
import org.koitharu.kotatsu.reader.ui.pager.BaseReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import javax.inject.Inject
import kotlin.math.absoluteValue

@AndroidEntryPoint
open class DoubleReaderFragment : BaseReaderFragment<FragmentReaderDoubleBinding>() {

	@Inject
	lateinit var networkState: NetworkState

	@Inject
	lateinit var pageLoader: PageLoader

	@Inject
	lateinit var settings: AppSettings

	private var recyclerLifecycleDispatcher: RecyclerViewLifecycleDispatcher? = null

	/**
	 * Maps padded adapter positions to original (unpadded) page list positions.
	 * Spacer positions are mapped to -1.
	 */
	protected var positionMap: IntArray = IntArray(0)
		private set

	/** Number of pages in the original (unpadded) list. */
	protected var originalPageCount: Int = 0
		private set

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentReaderDoubleBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(
		binding: FragmentReaderDoubleBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		with(binding.recyclerView) {
			clipChildren = false
			clipToPadding = false
			adapter = readerAdapter
			recyclerLifecycleDispatcher = RecyclerViewLifecycleDispatcher().also {
				addOnScrollListener(it)
			}
			addOnScrollListener(PageScrollListener())
			DoublePageSnapHelper(settings).attachToRecyclerView(this)
		}
	}

	override fun onDestroyView() {
		recyclerLifecycleDispatcher = null
		requireViewBinding().recyclerView.adapter = null
		super.onDestroyView()
	}

	override suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?) = coroutineScope {
		originalPageCount = pages.size
		val paddedPages = pages.padForDoublePage(coverPage = settings.isReaderDoubleCoverPage)

		// Build position map: padded index → original index (-1 for spacers)
		positionMap = IntArray(paddedPages.size)
		var origIndex = 0
		for (i in paddedPages.indices) {
			if (paddedPages[i].index < 0) {
				positionMap[i] = -1
			} else {
				positionMap[i] = origIndex++
			}
		}

		val items = launch {
			requireAdapter().setItems(paddedPages)
			yield()
			viewBinding?.recyclerView?.let { rv ->
				recyclerLifecycleDispatcher?.invalidate(rv)
			}
		}
		if (pendingState != null) {
			var position = paddedPages.indexOfFirst {
				it.chapterId == pendingState.chapterId && it.index == pendingState.page
			}
			items.join()
			if (position != -1) {
				position = position.toPagePosition()
				requireViewBinding().recyclerView.firstVisibleItemPosition = position
				notifyPageChanged(position, position + 1)
			} else {
				Snackbar.make(requireView(), R.string.not_found_404, Snackbar.LENGTH_SHORT)
					.show()
			}
		} else {
			items.join()
		}
	}

	override fun onCreateAdapter() = DoublePagesAdapter(
		lifecycleOwner = viewLifecycleOwner,
		loader = pageLoader,
		readerSettingsProducer = viewModel.readerSettingsProducer,
		networkState = networkState,
		exceptionResolver = exceptionResolver,
	)

	override fun onZoomIn() {
		viewBinding?.frame?.onZoomIn()
	}

	override fun onZoomOut() {
		viewBinding?.frame?.onZoomOut()
	}

	override fun switchPageBy(delta: Int) {
		if (delta.absoluteValue > 1 || !isAnimationEnabled()) {
			switchPageTo(getCurrentItem() + delta + delta, false)
			return
		}
		val rv = viewBinding?.recyclerView ?: return
		val distance = rv.width * delta
		rv.smoothScrollBy(distance, 0, AccelerateDecelerateInterpolator())
	}

	override fun switchPageTo(position: Int, smooth: Boolean) {
		val lm = viewBinding?.recyclerView?.layoutManager as? LinearLayoutManager ?: return
		val targetPosition = position.toPagePosition()
		lm.scrollToPositionWithOffset(targetPosition, 0)
	}

	override fun getCurrentState(): ReaderState? = viewBinding?.run {
		val adapter = recyclerView.adapter as? BaseReaderAdapter<*>
		val pos = getCurrentItem()
		val page = adapter?.getItemOrNull(pos) ?: return@run null
		if (page.index < 0) return@run null // Skip spacer
		ReaderState(
			chapterId = page.chapterId,
			page = page.index,
			scroll = 0,
		)
	}

	protected open fun notifyPageChanged(lowerPos: Int, upperPos: Int) {
		// Convert padded adapter positions to original page list positions
		val origLower = positionMap.getOrElse(lowerPos) { -1 }
		val origUpper = positionMap.getOrElse(upperPos) { -1 }
		val lower = if (origLower >= 0) origLower else origUpper
		val upper = if (origUpper >= 0) origUpper else origLower
		if (lower >= 0) {
			viewModel.onCurrentPageChanged(lower, upper.coerceAtLeast(lower))
		}
	}

	private fun getCurrentItem() = (requireViewBinding().recyclerView.layoutManager as LinearLayoutManager)
		.findFirstCompletelyVisibleItemPosition().toPagePosition()

	private fun Int.toPagePosition() = this and 1.inv()

	private inner class PageScrollListener : RecyclerView.OnScrollListener() {

		private var firstPos = RecyclerView.NO_POSITION
		private var lastPos = RecyclerView.NO_POSITION

		override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
			super.onScrolled(recyclerView, dx, dy)
			val lm = recyclerView.layoutManager as? LinearLayoutManager
			if (lm == null) {
				firstPos = RecyclerView.NO_POSITION
				lastPos = RecyclerView.NO_POSITION
				return
			}
			val newFirstPos = lm.findFirstVisibleItemPosition()
			val newLastPos = lm.findLastVisibleItemPosition()
			if (newFirstPos != firstPos || newLastPos != lastPos) {
				firstPos = newFirstPos
				lastPos = newLastPos
				notifyPageChanged(newFirstPos, newLastPos)
			}
		}
	}
}
