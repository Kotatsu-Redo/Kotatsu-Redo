package org.koitharu.kotatsu.search.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionScope
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The text currently filtering a library screen, held per screen.
 *
 * Shared rather than passed down because the search bar lives in MainActivity while the list it
 * filters lives in a fragment - and for Favourites, inside a pager whose pages come and go.
 *
 * Kept per [SearchSuggestionScope] rather than as one string: History and Favourites both observe
 * this, so a single value would have one screen's filter silently applied to the other whenever both
 * view models happen to be alive at once.
 */
@Singleton
class ScreenSearchQuery @Inject constructor() {

	private val queries = ConcurrentHashMap<SearchSuggestionScope, MutableStateFlow<String>>()
	private val activeScope = MutableStateFlow(SearchSuggestionScope.ALL)

	/** What the screen showing [scope] should be filtered by. Empty means no filter. */
	fun query(scope: SearchSuggestionScope): StateFlow<String> = flowFor(scope)

	/** The filter for whichever screen the search bar currently belongs to. */
	@OptIn(ExperimentalCoroutinesApi::class)
	val activeQuery: Flow<String> = activeScope.flatMapLatest { flowFor(it) }

	fun setActiveScope(scope: SearchSuggestionScope) {
		activeScope.value = scope
	}

	fun set(scope: SearchSuggestionScope, value: String) {
		flowFor(scope).value = value.trim()
	}

	fun clear(scope: SearchSuggestionScope) {
		flowFor(scope).value = ""
	}

	private fun flowFor(scope: SearchSuggestionScope) = queries.getOrPut(scope) { MutableStateFlow("") }
}
