package org.koitharu.kotatsu.search.ui.suggestion.model

import androidx.annotation.StringRes
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource

sealed interface SearchSuggestionItem : ListModel {

	data class MangaList(
		val items: List<Manga>,
	) : SearchSuggestionItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is MangaList
		}
	}

	data class RecentQuery(
		val query: String,
	) : SearchSuggestionItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is RecentQuery && query == other.query
		}
	}

	data class Hint(
		val query: String,
	) : SearchSuggestionItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Hint && query == other.query
		}
	}

	data class Author(
		val name: String,
	) : SearchSuggestionItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Author && name == other.name
		}
	}

	data class Source(
		val source: MangaSource,
		val isEnabled: Boolean,
	) : SearchSuggestionItem {

		val isNsfw: Boolean
			get() = source.isNsfw()

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Source && other.source.name == source.name
		}

		override fun getChangePayload(previousState: ListModel): Any? {
			if (previousState !is Source) {
				return super.getChangePayload(previousState)
			}
			return if (isEnabled != previousState.isEnabled) {
				ListModelDiffCallback.PAYLOAD_CHECKED_CHANGED
			} else {
				null
			}
		}
	}

	data class SourceTip(
		val source: MangaSource,
	) : SearchSuggestionItem {

		val isNsfw: Boolean
			get() = source.isNsfw()

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is SourceTip && other.source.name == source.name
		}
	}

	/**
	 * Stands for "favourites" as a whole rather than one list: typing the word offers this single entry,
	 * and tapping it opens up every list instead of making the user guess their names.
	 */
	data class FavouritesGroup(
		val preview: String,
	) : SearchSuggestionItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is FavouritesGroup
		}
	}

	/**
	 * A favourites list offered as something to filter by, shown alongside sources so both read as the
	 * same kind of choice.
	 */
	data class FavouriteTip(
		val category: FavouriteCategory,
	) : SearchSuggestionItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is FavouriteTip && other.category.id == category.id
		}
	}

	/**
	 * Chips that choose what the shared search bar looks through. Only emitted on screens where
	 * narrowing is meaningful, and "everywhere" is always the selected default.
	 */
	data class Scope(
		val chips: List<ChipsView.ChipModel>,
	) : SearchSuggestionItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Scope
		}

		override fun getChangePayload(previousState: ListModel): Any {
			return ListModelDiffCallback.PAYLOAD_NESTED_LIST_CHANGED
		}
	}

	data class Tags(
		val tags: List<ChipsView.ChipModel>,
	) : SearchSuggestionItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Tags
		}

		override fun getChangePayload(previousState: ListModel): Any {
			return ListModelDiffCallback.PAYLOAD_NESTED_LIST_CHANGED
		}
	}

	data class Text(
		@StringRes val textResId: Int,
		val error: Throwable?,
	) : SearchSuggestionItem {

		override fun areItemsTheSame(other: ListModel): Boolean = other is Text
			&& textResId == other.textResId
			&& error?.javaClass == other.error?.javaClass
			&& error?.message == other.error?.message
	}
}
