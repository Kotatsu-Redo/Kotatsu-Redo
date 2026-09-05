package org.koitharu.kotatsu.search.ui.suggestion

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SearchSuggestionType
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.search.domain.MangaSearchRepository
import org.koitharu.kotatsu.search.domain.ScreenSearchQuery
import org.koitharu.kotatsu.search.ui.suggestion.model.SearchSuggestionItem
import javax.inject.Inject

private const val DEBOUNCE_TIMEOUT = 300L
private const val MAX_MANGA_ITEMS = 12
private const val MAX_QUERY_ITEMS = 16
private const val MAX_HINTS_ITEMS = 3
private const val MAX_AUTHORS_ITEMS = 2
private const val MAX_TAGS_ITEMS = 8
private const val MAX_SOURCES_ITEMS = 6
private const val MAX_SOURCES_TIPS_ITEMS = 2
private const val MAX_SCOPE_TIPS_ITEMS = 12

@HiltViewModel
class SearchSuggestionViewModel @Inject constructor(
	private val repository: MangaSearchRepository,
	private val settings: AppSettings,
	private val sourcesRepository: MangaSourcesRepository,
	private val screenSearchQuery: ScreenSearchQuery,
) : BaseViewModel() {

	private val query = MutableStateFlow("")
	private val invalidationTrigger = MutableStateFlow(0)

	/**
	 * Which screen the search bar currently belongs to, set by MainActivity as the section changes.
	 * Only decides whether narrowing is *offered*, never whether it is applied.
	 */
	private val screenScope = MutableStateFlow(SearchSuggestionScope.ALL)

	/**
	 * Whether the user has actually asked to narrow to the current screen.
	 *
     * Defaults to `false` and resets whenever the section changes, so search behaves exactly as it
	 * always has until someone taps the chip - existing habits are never silently changed.
	 */
	private val isScoped = MutableStateFlow(false)

	/** Set by tapping the "favourites" entry; shows every list until the query changes. */
	private val areListsExpanded = MutableStateFlow(false)

	/** Whether the bar is currently filtering one screen rather than searching everywhere. */
	val isScopedSearch: StateFlow<Boolean>
		get() = isScoped

	val isIncognitoModeEnabled = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_INCOGNITO_MODE,
		valueProducer = { isIncognitoModeEnabled },
	)

	val suggestion: Flow<List<SearchSuggestionItem>> = combine(
		query.debounce(DEBOUNCE_TIMEOUT),
		sourcesRepository.observeEnabledSources().map { it.mapToSet { x -> x.name } },
		settings.observeAsFlow(AppSettings.KEY_SEARCH_SUGGESTION_TYPES) { searchSuggestionTypes },
		invalidationTrigger,
		screenScope,
		isScoped,
		areListsExpanded,
	) { values ->
		@Suppress("UNCHECKED_CAST")
		SuggestionInput(
			query = values[0] as String,
			enabledSources = values[1] as Set<String>,
			types = values[2] as Set<SearchSuggestionType>,
			screenScope = values[4] as SearchSuggestionScope,
			isScoped = values[5] as Boolean,
			areListsExpanded = values[6] as Boolean,
		)
	}.mapLatest { input ->
		buildSearchSuggestion(input)
	}.distinctUntilChanged()
		.withErrorHandling()
		.flowOn(Dispatchers.Default)

	fun onQueryChanged(newQuery: String) {
		query.value = newQuery
		// Typing again is a new intent; collapse back to the single entry.
		areListsExpanded.value = false
		if (isScoped.value && newQuery.isEmpty()) {
			// In filter mode an empty bar means an unfiltered screen. Without this the filter is only
			// ever written on submit, so clearing the text leaves the old one applied and it reappears
			// in the bar the next time this screen is shown.
			screenSearchQuery.clear(screenScope.value)
		}
	}

	fun expandFavouritesGroup() {
		areListsExpanded.value = true
	}

	fun setScope(value: SearchSuggestionScope) {
		if (screenScope.value != value) {
			// Leaving a screen drops its narrowing, and its filter, with it.
			screenSearchQuery.clear(screenScope.value)
			screenScope.value = value
			isScoped.value = false
			screenSearchQuery.setActiveScope(value)
		}
	}

	fun setScoped(value: Boolean) {
		isScoped.value = value
		if (!value) {
			// Back to searching everywhere: the screen should stop being filtered.
			screenSearchQuery.clear(screenScope.value)
		}
	}

	fun applyScreenFilter(value: String) {
		screenSearchQuery.set(screenScope.value, value)
	}

	fun saveQuery(query: String) {
		if (!settings.isIncognitoModeEnabled) {
			repository.saveSearchQuery(query)
			invalidationTrigger.value++
		}
	}

	fun clearSearchHistory() {
		launchJob(Dispatchers.Default) {
			repository.clearSearchHistory()
			invalidationTrigger.value++
		}
	}

	fun onSourceToggle(source: MangaSource, isEnabled: Boolean) {
		launchJob(Dispatchers.Default) {
			sourcesRepository.setSourcesEnabled(setOf(source), isEnabled)
		}
	}

	fun deleteQuery(query: String) {
		launchJob(Dispatchers.Default) {
			repository.deleteSearchQuery(query)
			invalidationTrigger.value++
		}
	}

	private suspend fun buildSearchSuggestion(
		input: SuggestionInput,
	): List<SearchSuggestionItem> = coroutineScope {
		val searchQuery = input.query
		val enabledSources = input.enabledSources
		val types = input.types
		val scope = input.appliedScope
		if (input.isScoped) {
			// Pure filter mode: just the switch and the matching titles. The other sections all lead
			// somewhere else - a source, a tag search, a saved query - and offering them here would
			// defeat the point of staying on the screen you are filtering.
			// With nothing typed yet, offer everything worth narrowing to - every list, and the sources
			// actually present on this screen - so the mode opens with choices rather than a blank sheet.
			val tipLimit = if (searchQuery.isEmpty()) MAX_SCOPE_TIPS_ITEMS else MAX_SOURCES_TIPS_ITEMS
			// Typing the word "favourites" offers one entry rather than every list at once; tapping it
			// expands. Collapsed, it previews the names so it is clear what it opens.
			val isFavouritesKeyword = searchQuery.isNotEmpty() && repository.matchesFavouritesKeyword(searchQuery)
			return@coroutineScope buildList {
				add(SearchSuggestionItem.Scope(scopeChips(input)))
				if (isFavouritesKeyword && !input.areListsExpanded) {
					val all = repository.getAllCategories()
					if (all.isNotEmpty()) {
						add(SearchSuggestionItem.FavouritesGroup(all.joinToString { it.title }))
					}
				} else if (isFavouritesKeyword) {
					repository.getAllCategories().mapTo(this) { SearchSuggestionItem.FavouriteTip(it) }
				} else {
					repository.getScopedCategories(searchQuery, scope, tipLimit).mapTo(this) {
						SearchSuggestionItem.FavouriteTip(it)
					}
				}
				repository.getScopedSources(searchQuery, scope, tipLimit).mapTo(this) {
					SearchSuggestionItem.SourceTip(it)
				}
				if (searchQuery.isNotEmpty()) {
					// An empty query would otherwise fall through to library-wide "top manga", which is
					// not this screen's content.
					addAll(getManga(searchQuery, scope))
				}
			}
		}
		listOfNotNull(
			if (input.screenScope != SearchSuggestionScope.ALL) {
				async { listOf(SearchSuggestionItem.Scope(scopeChips(input))) }
			} else {
				null
			},
			// Tags are a suggestion for what has been typed. With an empty field getTagsSuggestion falls
			// back to arbitrary popular tags, which renders a strip of filter chips under the scope
			// chips before the user has asked for anything - so they only appear once there is a query.
			if (SearchSuggestionType.GENRES in types && searchQuery.isNotEmpty()) {
				async { getTags(searchQuery) }
			} else {
				null
			},
			if (SearchSuggestionType.MANGA in types) {
				async { getManga(searchQuery, scope) }
			} else {
				null
			},
			if (SearchSuggestionType.QUERIES_RECENT in types) {
				async { getRecentQueries(searchQuery) }
			} else {
				null
			},
			if (SearchSuggestionType.QUERIES_SUGGEST in types) {
				async { getQueryHints(searchQuery) }
			} else {
				null
			},
			if (SearchSuggestionType.SOURCES in types) {
				async { getSources(searchQuery, enabledSources) }
			} else {
				null
			},
			if (SearchSuggestionType.RECENT_SOURCES in types) {
				async { getRecentSources(searchQuery) }
			} else {
				null
			},
			if (SearchSuggestionType.AUTHORS in types) {
				async {
					getAuthors(searchQuery)
				}
			} else {
				null
			},
		).flatMap { it.await() }
	}

	private suspend fun getAuthors(searchQuery: String): List<SearchSuggestionItem> = runCatchingCancellable {
		repository.getAuthorsSuggestion(searchQuery, MAX_AUTHORS_ITEMS)
			.map { SearchSuggestionItem.Author(it) }
	}.getOrElse { e ->
		e.printStackTraceDebug()
		listOf(SearchSuggestionItem.Text(0, e))
	}

	private suspend fun getQueryHints(searchQuery: String): List<SearchSuggestionItem> = runCatchingCancellable {
		repository.getQueryHintSuggestion(searchQuery, MAX_HINTS_ITEMS)
			.map { SearchSuggestionItem.Hint(it) }
	}.getOrElse { e ->
		e.printStackTraceDebug()
		listOf(SearchSuggestionItem.Text(0, e))
	}

	private suspend fun getRecentQueries(searchQuery: String): List<SearchSuggestionItem> = runCatchingCancellable {
		repository.getQuerySuggestion(searchQuery, MAX_QUERY_ITEMS)
			.map { SearchSuggestionItem.RecentQuery(it) }
	}.getOrElse { e ->
		e.printStackTraceDebug()
		listOf(SearchSuggestionItem.Text(0, e))
	}

	private suspend fun getTags(searchQuery: String): List<SearchSuggestionItem> = runCatchingCancellable {
		val tags = repository.getTagsSuggestion(searchQuery, MAX_TAGS_ITEMS, null)
		if (tags.isEmpty()) {
			emptyList()
		} else {
			listOf(SearchSuggestionItem.Tags(mapTags(tags)))
		}
	}.getOrElse { e ->
		e.printStackTraceDebug()
		listOf(SearchSuggestionItem.Text(0, e))
	}

	private fun scopeChips(input: SuggestionInput): List<ChipsView.ChipModel> = listOf(
		ChipsView.ChipModel(
			titleResId = R.string.search,
			isChecked = !input.isScoped,
			data = false,
		),
		ChipsView.ChipModel(
			titleResId = when (input.screenScope) {
				SearchSuggestionScope.FAVOURITES -> R.string.favourites
				else -> R.string.history
			},
			isChecked = input.isScoped,
			data = true,
		),
	)

	private data class SuggestionInput(
		val query: String,
		val enabledSources: Set<String>,
		val types: Set<SearchSuggestionType>,
		val screenScope: SearchSuggestionScope,
		val isScoped: Boolean,
		val areListsExpanded: Boolean,
	) {

		/** The scope actually used for lookups: narrowing only applies once the user opts in. */
		val appliedScope: SearchSuggestionScope
			get() = if (isScoped) screenScope else SearchSuggestionScope.ALL
	}

	private suspend fun getManga(
		searchQuery: String,
		scope: SearchSuggestionScope,
	): List<SearchSuggestionItem> = runCatchingCancellable {
		val manga = repository.getMangaSuggestion(searchQuery, MAX_MANGA_ITEMS, null, scope)
		if (manga.isEmpty()) {
			emptyList()
		} else {
			listOf(SearchSuggestionItem.MangaList(manga))
		}
	}.getOrElse { e ->
		e.printStackTraceDebug()
		listOf(SearchSuggestionItem.Text(0, e))
	}

	private fun getSources(searchQuery: String, enabledSources: Set<String>): List<SearchSuggestionItem> =
		runCatchingCancellable {
			repository.getSourcesSuggestion(searchQuery, MAX_SOURCES_ITEMS)
				.map { SearchSuggestionItem.Source(it, it.name in enabledSources) }
		}.getOrElse { e ->
			e.printStackTraceDebug()
			listOf(SearchSuggestionItem.Text(0, e))
		}

	private suspend fun getRecentSources(searchQuery: String): List<SearchSuggestionItem> = if (searchQuery.isEmpty()) {
		runCatchingCancellable {
			repository.getSourcesSuggestion(MAX_SOURCES_TIPS_ITEMS)
				.map { SearchSuggestionItem.SourceTip(it) }
		}.getOrElse { e ->
			e.printStackTraceDebug()
			listOf(SearchSuggestionItem.Text(0, e))
		}
	} else {
		emptyList()
	}

	private fun mapTags(tags: List<MangaTag>): List<ChipsView.ChipModel> = tags.map { tag ->
		ChipsView.ChipModel(
			title = tag.title,
			data = tag,
		)
	}
}
