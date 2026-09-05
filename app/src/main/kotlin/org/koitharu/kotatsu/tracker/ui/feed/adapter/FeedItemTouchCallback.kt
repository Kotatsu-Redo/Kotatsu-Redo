package org.koitharu.kotatsu.tracker.ui.feed.adapter

import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.core.util.ext.getItem
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem

/**
 * Swipe a feed entry away to delete it. Only the update rows are swipeable: the date headers, the
 * updated manga strip, the quick filter and the state placeholders are not entries and have nothing
 * to delete.
 */
class FeedItemTouchCallback(
	private val listener: FeedItemListener,
) : ItemTouchHelper.Callback() {

	private val movementFlags = makeMovementFlags(
		0,
		ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
	)

	override fun getMovementFlags(
		recyclerView: RecyclerView,
		viewHolder: RecyclerView.ViewHolder,
	): Int = if (viewHolder.itemViewType == ListItemType.FEED.ordinal) {
		movementFlags
	} else {
		0
	}

	override fun onMove(
		recyclerView: RecyclerView,
		viewHolder: RecyclerView.ViewHolder,
		target: RecyclerView.ViewHolder,
	): Boolean = false

	override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
		val item = viewHolder.getItem(FeedItem::class.java) ?: return
		listener.onRemoveFeedItem(item)
	}

	fun interface FeedItemListener {

		fun onRemoveFeedItem(item: FeedItem)
	}
}
