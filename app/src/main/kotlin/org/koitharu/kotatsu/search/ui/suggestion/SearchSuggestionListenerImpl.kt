package org.koitharu.kotatsu.search.ui.suggestion

import android.text.Editable
import android.view.KeyEvent
import android.widget.TextView
import androidx.core.net.toUri
import com.google.android.material.search.SearchView
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.parser.MangaLinkResolver
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.search.domain.SearchKind

class SearchSuggestionListenerImpl(
	private val router: AppRouter,
	private val searchView: SearchView,
	private val viewModel: SearchSuggestionViewModel,
) : SearchSuggestionListener {

	override fun onScopeChanged(isScoped: Boolean) {
		viewModel.setScoped(isScoped)
	}

	override fun onMangaClick(manga: Manga) {
		router.openDetails(manga)
	}

	override fun onQueryClick(query: String, kind: SearchKind, submit: Boolean) {
		if (viewModel.isScopedSearch.value) {
			// Filtering a screen, not starting a search: apply the text to the list underneath and step
			// out of the way instead of navigating anywhere. The bar keeps the text so it stays obvious
			// that the list is filtered.
			searchView.setText(query)
			if (submit) {
				viewModel.applyScreenFilter(query)
				searchView.hide()
			}
			return
		}
		if (submit && query.isNotEmpty()) {
			if (kind == SearchKind.SIMPLE && MangaLinkResolver.isValidLink(query)) {
				router.openDetails(query.toUri())
			} else {
				router.openSearch(query, kind)
				if (kind != SearchKind.TAG) {
					viewModel.saveQuery(query)
				}
			}
			searchView.hide()
		} else {
			searchView.setText(query)
		}
	}

	override fun onTagClick(tag: MangaTag) {
		if (viewModel.isScopedSearch.value) {
			searchView.setText(tag.title)
			return
		}
		router.openSearch(tag.title, SearchKind.TAG)
	}

	override fun onSourceToggle(source: MangaSource, isEnabled: Boolean) {
		viewModel.onSourceToggle(source, isEnabled)
	}

	override fun onSourceClick(source: MangaSource) {
		if (viewModel.isScopedSearch.value) {
			applyFilter(source.getTitle(searchView.context))
			return
		}
		router.openList(source, null, null)
	}

	override fun onFavouritesGroupClick() {
		viewModel.expandFavouritesGroup()
	}

	override fun onFavouriteCategoryClick(category: FavouriteCategory) {
		applyFilter(category.title)
	}

	/** Narrows the screen behind the overlay and steps out of the way. */
	private fun applyFilter(text: String) {
		searchView.setText(text)
		viewModel.applyScreenFilter(text)
		searchView.hide()
	}

	override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

	override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

	override fun afterTextChanged(s: Editable?) {
		viewModel.onQueryChanged(s?.toString().orEmpty())
	}

	override fun onEditorAction(
		v: TextView?,
		actionId: Int,
		event: KeyEvent?
	): Boolean {
		val query = v?.text?.toString()
		if (query.isNullOrEmpty()) {
			return false
		}
		onQueryClick(query, SearchKind.SIMPLE, true)
		return true
	}
}
