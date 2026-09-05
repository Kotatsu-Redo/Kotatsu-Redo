package org.koitharu.kotatsu.search.ui.suggestion

/**
 * What the shared search bar should look through.
 *
 * The bar is the same on every screen, but on History and Favourites it narrows to that screen's
 * contents instead of offering library-wide suggestions, so typing a title, a source or a list name
 * filters what you were already looking at.
 */
enum class SearchSuggestionScope {

	/** Library-wide suggestions: the behaviour everywhere else. */
	ALL,
	HISTORY,
	FAVOURITES,
}
