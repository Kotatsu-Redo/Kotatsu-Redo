package org.koitharu.kotatsu.reader.ui.pager.doublepage

import androidx.core.view.children
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import org.koitharu.kotatsu.reader.ui.pager.standard.PageHolder

fun RecyclerView.visiblePageHolders(): Sequence<PageHolder> {
	val lm = layoutManager as? LinearLayoutManager ?: return emptySequence()
	return (lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition()).asSequence()
		.mapNotNull { findViewHolderForAdapterPosition(it) as? PageHolder }
}

fun RecyclerView.allPageHolders(): Sequence<PageHolder> {
	return children.mapNotNull {
		findContainingViewHolder(it) as? PageHolder
	}
}

/**
 * Pads the page list so that chapters with an odd number of pages get a spacer at the end.
 * This ensures pages from different chapters are never paired in the same double-page spread.
 * When [coverPage] is true, a spacer is inserted before each chapter's first page so it
 * displays as a solo cover/intro page.
 * Spacer pages have [ReaderPage.index] == -1.
 */
fun List<ReaderPage>.padForDoublePage(coverPage: Boolean): List<ReaderPage> {
	if (isEmpty()) return this
	val result = ArrayList<ReaderPage>(size + size / 20 + 1)
	var currentChapterId = first().chapterId

	if (coverPage) {
		// Insert spacer before the very first page so it displays solo as a cover
		val first = first()
		result.add(
			ReaderPage(
				id = Long.MIN_VALUE,
				url = "",
				preview = null,
				chapterId = first.chapterId,
				index = -1,
				source = first.source,
			),
		)
	}

	for (page in this) {
		if (page.chapterId != currentChapterId) {
			// Pad previous chapter to even count
			if (result.size % 2 != 0) {
				val last = result.last()
				result.addSpacer(last.chapterId, last.source)
			}
			currentChapterId = page.chapterId
			if (coverPage) {
				// Insert spacer before chapter's first page so it displays solo as a cover
				result.addSpacer(page.chapterId, page.source)
			}
		}
		result.add(page)
	}
	return result
}

private fun MutableList<ReaderPage>.addSpacer(chapterId: Long, source: MangaSource) {
	add(
		ReaderPage(
			id = Long.MIN_VALUE + size,
			url = "",
			preview = null,
			chapterId = chapterId,
			index = -1,
			source = source,
		),
	)
}
