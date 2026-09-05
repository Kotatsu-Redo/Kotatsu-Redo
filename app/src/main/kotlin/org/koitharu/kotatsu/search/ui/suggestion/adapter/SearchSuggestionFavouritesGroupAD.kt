package org.koitharu.kotatsu.search.ui.suggestion.adapter

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemSearchSuggestionSourceTipBinding
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionListener
import org.koitharu.kotatsu.search.ui.suggestion.model.SearchSuggestionItem

/**
 * "Favourites" as one entry. The subtitle previews the list names so it is obvious what tapping it
 * will open, without needing a count nobody can interpret.
 */
fun searchSuggestionFavouritesGroupAD(
	listener: SearchSuggestionListener,
) = adapterDelegateViewBinding<SearchSuggestionItem.FavouritesGroup, SearchSuggestionItem, ItemSearchSuggestionSourceTipBinding>(
	{ inflater, parent -> ItemSearchSuggestionSourceTipBinding.inflate(inflater, parent, false) },
) {

	binding.root.setOnClickListener {
		listener.onFavouritesGroupClick()
	}

	bind {
		binding.textViewTitle.setText(R.string.favourites)
		binding.textViewSubtitle.text = item.preview
		binding.imageViewCover.setImageAsync(R.drawable.ic_heart_outline)
	}
}
