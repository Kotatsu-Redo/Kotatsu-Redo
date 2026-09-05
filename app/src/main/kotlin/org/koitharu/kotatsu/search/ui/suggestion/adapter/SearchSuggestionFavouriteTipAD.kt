package org.koitharu.kotatsu.search.ui.suggestion.adapter

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemSearchSuggestionSourceTipBinding
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionListener
import org.koitharu.kotatsu.search.ui.suggestion.model.SearchSuggestionItem

/**
 * Reuses the source tip row so a list and a source look like the same kind of thing to filter by -
 * icon, name, subtitle - with a heart standing in for the favicon.
 */
fun searchSuggestionFavouriteTipAD(
	listener: SearchSuggestionListener,
) = adapterDelegateViewBinding<SearchSuggestionItem.FavouriteTip, SearchSuggestionItem, ItemSearchSuggestionSourceTipBinding>(
	{ inflater, parent -> ItemSearchSuggestionSourceTipBinding.inflate(inflater, parent, false) },
) {

	binding.root.setOnClickListener {
		listener.onFavouriteCategoryClick(item.category)
	}

	bind {
		binding.textViewTitle.text = item.category.title
		binding.textViewSubtitle.setText(R.string.favourites)
		binding.imageViewCover.setImageAsync(R.drawable.ic_heart_outline)
	}
}
