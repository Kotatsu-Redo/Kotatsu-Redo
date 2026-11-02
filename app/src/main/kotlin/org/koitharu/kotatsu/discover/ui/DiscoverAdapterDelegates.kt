package org.koitharu.kotatsu.discover.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegate
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.databinding.ItemDiscoverRankedBinding
import org.koitharu.kotatsu.databinding.ItemListGroupBinding
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.mangaGridItemAD
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver
import org.koitharu.kotatsu.parsers.model.Manga
import kotlin.math.abs

private const val ROW_PLACEHOLDERS = 6
private const val ROW_PREFETCH = 4

fun discoverRankedAD(
	onClick: (Manga) -> Unit,
	onLongClick: (Manga, View) -> Unit,
) = adapterDelegateViewBinding<DiscoverRankedManga, ListModel, ItemDiscoverRankedBinding>(
	{ inflater, parent -> ItemDiscoverRankedBinding.inflate(inflater, parent, false) },
) {
	binding.root.setOnClickListener { onClick(item.manga) }
	binding.root.setOnLongClickListener { view ->
		onLongClick(item.manga, view)
		true
	}
	bind {
		binding.textViewRank.text = item.rank.toString()
		binding.textViewTitle.text = item.manga.title
		binding.imageViewCover.setImageAsync(item.manga.coverUrl, item.manga)
	}
}

fun discoverPlaceholderAD() = adapterDelegate<DiscoverPlaceholder, ListModel>(R.layout.item_discover_placeholder) {}

fun discoverRowAD(
	sizeResolver: ItemSizeResolver,
	coverPool: RecyclerView.RecycledViewPool,
	onOpenSuggestions: () -> Unit,
	clickListener: OnListItemClickListener<MangaListModel>,
) = adapterDelegateViewBinding<DiscoverRowItem, ListModel, ItemListGroupBinding>(
	{ inflater, parent -> ItemListGroupBinding.inflate(inflater, parent, false) },
) {
	val adapter = BaseListAdapter<ListModel>()
		.addDelegate(ListItemType.MANGA_GRID, mangaGridItemAD(sizeResolver, clickListener))
		.addDelegate(ListItemType.FOOTER_LOADING, discoverPlaceholderAD())
	binding.recyclerView.adapter = adapter
	// Every row shows the same size of cover, so what one has scrolled off is what the next needs
	binding.recyclerView.setRecycledViewPool(coverPool)
	(binding.recyclerView.layoutManager as? LinearLayoutManager)?.initialPrefetchItemCount = ROW_PREFETCH
	binding.recyclerView.addOnItemTouchListener(RowInsideSwipingPages(binding.recyclerView))
	binding.buttonMore.setText(R.string.show_all)
	binding.buttonMore.setOnClickListener { onOpenSuggestions() }
	bind {
		val titleText = item.titleText
		val titleArgument = item.titleArgument
		when {
			titleText != null -> binding.textViewTitle.text = titleText
			titleArgument != null -> binding.textViewTitle.text = context.getString(item.title, titleArgument)
			else -> binding.textViewTitle.setText(item.title)
		}
		binding.buttonMore.visibility = if (item.opensSuggestions) View.VISIBLE else View.GONE
		binding.textViewError.isVisible = false
		adapter.items = if (item.isLoading) List(ROW_PLACEHOLDERS) { DiscoverPlaceholder(it) } else item.items
	}
}

/**
 * Lets a sideways row live on a screen that is itself swiped sideways.
 *
 * Whoever is above sees a touch first and would take every sideways drag, so the row asks to be left alone the
 * moment a touch lands on it. It hands the gesture back once that gesture turns out not to be for it: mostly
 * up or down, which scrolls the screen, or sideways with the row already at its end in that direction, which
 * swipes to the next screen.
 */
private class RowInsideSwipingPages(row: RecyclerView) : RecyclerView.SimpleOnItemTouchListener() {

	private val slop = ViewConfiguration.get(row.context).scaledTouchSlop
	private var startX = 0f
	private var startY = 0f

	override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
		when (e.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				startX = e.x
				startY = e.y
				rv.parent.requestDisallowInterceptTouchEvent(true)
			}

			MotionEvent.ACTION_MOVE -> {
				val dx = e.x - startX
				val dy = e.y - startY
				if (abs(dx) > slop || abs(dy) > slop) {
					// Dragging left moves the row's content towards its end
					val isForRow = abs(dx) >= abs(dy) && rv.canScrollHorizontally(if (dx < 0) 1 else -1)
					rv.parent.requestDisallowInterceptTouchEvent(isForRow)
				}
			}
		}
		return false
	}
}
