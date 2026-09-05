package org.koitharu.kotatsu.search.ui.suggestion.adapter

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.databinding.ItemSearchSuggestionTagsBinding
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionListener
import org.koitharu.kotatsu.search.ui.suggestion.model.SearchSuggestionItem

/**
 * Reuses the tags row layout: both are a single horizontal strip of chips, and giving the scope
 * switch its own look would make it read as unrelated to the rest of the suggestions.
 */
fun searchSuggestionScopeAD(
	listener: SearchSuggestionListener,
) = adapterDelegateViewBinding<SearchSuggestionItem.Scope, SearchSuggestionItem, ItemSearchSuggestionTagsBinding>(
	{ layoutInflater, parent -> ItemSearchSuggestionTagsBinding.inflate(layoutInflater, parent, false) },
) {

	binding.chipsGenres.onChipClickListener = ChipsView.OnChipClickListener { _, data ->
		listener.onScopeChanged(data as? Boolean ?: return@OnChipClickListener)
	}

	bind {
		binding.chipsGenres.setChips(item.chips)
	}
}
