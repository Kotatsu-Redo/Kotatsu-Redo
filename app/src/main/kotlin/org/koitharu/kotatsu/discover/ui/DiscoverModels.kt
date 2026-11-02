package org.koitharu.kotatsu.discover.ui

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.discover.domain.DiscoverFilter
import org.koitharu.kotatsu.discover.domain.DiscoverWindow
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.Manga

/** Recommendations and what is popular are different questions, so each has its own screen to swipe to. */
enum class DiscoverTab(@StringRes val titleRes: Int) {
	FOR_YOU(R.string.for_you),
	TOP(R.string.top_charts),
}

/** Everything the single row of tags above the screens shows. */
data class DiscoverChips(
	val tab: DiscoverTab,
	val window: DiscoverWindow,
	val filter: DiscoverFilter,
	val genres: List<String>,
)

data class DiscoverRowItem(
	@StringRes val title: Int,
	val items: List<ListModel>,
	val isLoading: Boolean,
	val opensSuggestions: Boolean,
	/** Instead of [title], for a row named after what is in it. */
	val titleText: String? = null,
	/** What goes in [title], for a row that says why it is there. */
	val titleArgument: String? = null,
) : ListModel {
	override fun areItemsTheSame(other: ListModel) =
		other is DiscoverRowItem && other.title == title && other.titleText == titleText && other.titleArgument == titleArgument
}

data class DiscoverRankedManga(
	val rank: Int,
	val manga: Manga,
) : ListModel {
	override fun areItemsTheSame(other: ListModel) = other is DiscoverRankedManga && other.manga.id == manga.id
}

/** Stands in for a cover while sources answer, so the layout does not jump when they do. */
data class DiscoverPlaceholder(val index: Int) : ListModel {
	override fun areItemsTheSame(other: ListModel) = other is DiscoverPlaceholder && other.index == index
}
