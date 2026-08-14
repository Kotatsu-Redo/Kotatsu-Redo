package org.koitharu.kotatsu.alternatives.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.AlternativeSearchEvent
import org.koitharu.kotatsu.alternatives.domain.AlternativeSearchStatus
import org.koitharu.kotatsu.alternatives.domain.AlternativeSortOrder
import org.koitharu.kotatsu.alternatives.domain.AlternativeSourceScope
import org.koitharu.kotatsu.alternatives.domain.AlternativesSearchOptions
import org.koitharu.kotatsu.alternatives.domain.AlternativesUseCase
import org.koitharu.kotatsu.alternatives.domain.MigrateUseCase
import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.ui.model.ButtonFooter
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingFooter
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrDefault
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

@HiltViewModel
class AlternativesViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val alternativesUseCase: AlternativesUseCase,
	private val migrateUseCase: MigrateUseCase,
	private val mangaListMapper: MangaListMapper,
) : BaseViewModel() {

	val manga = savedStateHandle.require<ParcelableManga>(AppRouter.KEY_MANGA).manga

	private val results = MutableStateFlow<List<MangaAlternativeModel>>(emptyList())
	private val sourceSearchStatus = MutableStateFlow(AlternativeSearchStatus())
	val options = MutableStateFlow(AlternativesSearchOptions(query = manga.title))
	val referenceManga = MutableStateFlow(MangaAlternativeReference(manga))

	private var migrationJob: Job? = null
	private var searchJob: Job? = null

	private val mangaDetails = suspendLazy {
		mangaRepositoryFactory.create(manga.source).getDetails(manga)
	}

	val onMigrated = MutableEventFlow<Manga>()

	val searchStatus: StateFlow<AlternativeSearchStatus> = combine(
		sourceSearchStatus,
		results,
		options,
	) { status, results, options ->
		status.copy(
			resultsCount = results.count { !options.hideNoChapters || it.chaptersCount > 0 },
		)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, AlternativeSearchStatus())

	val list: StateFlow<List<ListModel>> = combine(
		results,
		isLoading,
		options,
		referenceManga,
	) { results, loading, options, reference ->
		val list = results
			.filterNot { options.hideNoChapters && it.chaptersCount == 0 }
			.sortedWith(alternativeComparator(options, reference.manga, reference.chaptersCount))
		when {
			list.isEmpty() -> listOf(
				when {
					loading -> LoadingState()
					else -> EmptyState(
						icon = R.drawable.ic_empty_common,
						textPrimary = R.string.nothing_found,
						textSecondary = R.string.text_search_holder_secondary,
						actionStringRes = 0,
					)
				},
			)

			loading -> list + LoadingFooter()
			options.sourceScope == AlternativeSourceScope.ALL -> list
			else -> list + ButtonFooter(R.string.search_disabled_sources)
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState()))

	init {
		doSearch()
	}

	fun retry() {
		searchJob?.cancel()
		doSearch()
	}

	fun continueSearch() {
		if (options.value.sourceScope == AlternativeSourceScope.ALL) {
			return
		}
		setSourceScope(AlternativeSourceScope.ALL)
	}

	fun setQuery(query: String) {
		val value = query.trim()
		if (value.isEmpty() || value == options.value.query) return
		updateSearchOptions { copy(query = value) }
	}

	fun setSourceScope(scope: AlternativeSourceScope) {
		if (scope == options.value.sourceScope) return
		updateSearchOptions { copy(sourceScope = scope) }
	}

	fun setSameLanguageOnly(enabled: Boolean) {
		if (enabled == options.value.sameLanguageOnly) return
		updateSearchOptions { copy(sameLanguageOnly = enabled) }
	}

	fun setSameContentTypeOnly(enabled: Boolean) {
		if (enabled == options.value.sameContentTypeOnly) return
		updateSearchOptions { copy(sameContentTypeOnly = enabled) }
	}

	fun setHideNoChapters(enabled: Boolean) {
		if (enabled == options.value.hideNoChapters) return
		options.update { it.copy(hideNoChapters = enabled) }
	}

	fun setSortOrder(sortOrder: AlternativeSortOrder) {
		if (sortOrder == options.value.sortOrder) return
		options.update { it.copy(sortOrder = sortOrder) }
	}

	fun resetOptions() {
		val current = options.value
		val defaults = AlternativesSearchOptions(query = manga.title)
		if (current == defaults) return
		val requiresSearch = current.query != defaults.query ||
			current.sourceScope != defaults.sourceScope ||
			current.sameLanguageOnly != defaults.sameLanguageOnly ||
			current.sameContentTypeOnly != defaults.sameContentTypeOnly
		options.value = defaults
		if (requiresSearch) doSearch()
	}

	fun migrate(target: Manga) {
		val candidate = results.value.find { it.manga.id == target.id }
		if (migrationJob?.isActive == true || candidate?.isMigrationEnabled != true || candidate.chaptersCount == 0) {
			return
		}
		migrationJob = launchLoadingJob(Dispatchers.Default) {
			migrateUseCase(manga, target)
			onMigrated.call(target)
		}
	}

	private fun updateSearchOptions(block: AlternativesSearchOptions.() -> AlternativesSearchOptions) {
		options.update(block)
		doSearch()
	}

	private fun doSearch() {
		results.update { current ->
			current.map { candidate -> candidate.copy(isMigrationEnabled = false) }
		}
		val prevJob = searchJob
		searchJob = launchLoadingJob(Dispatchers.Default) {
			prevJob?.cancelAndJoin()
			sourceSearchStatus.value = AlternativeSearchStatus(isRunning = true)
			var receivedResult = false
			try {
				val ref = mangaDetails.getOrDefault(manga)
				val refCount = ref.chaptersCount()
				referenceManga.value = MangaAlternativeReference(ref, refCount)
				val searchOptions = options.value
				alternativesUseCase.invoke(ref, searchOptions).collect { event ->
					when (event) {
						is AlternativeSearchEvent.Progress -> sourceSearchStatus.update {
							it.copy(
								completedSources = maxOf(it.completedSources, event.completedSources),
								totalSources = event.totalSources,
							)
						}

						is AlternativeSearchEvent.Result -> {
							if (!receivedResult) {
								results.value = emptyList()
								receivedResult = true
							}
							val model = MangaAlternativeModel(
								mangaModel = mangaListMapper.toListModel(
									event.manga,
									ListMode.GRID,
								) as MangaGridModel,
								referenceChapters = refCount,
							)
							results.update { current ->
								current.filterNot { candidate -> candidate.manga.id == model.manga.id } + model
							}
						}
					}
				}
				if (!receivedResult) results.value = emptyList()
			} finally {
				sourceSearchStatus.update { it.copy(isRunning = false) }
			}
		}
	}

	private fun alternativeComparator(
		options: AlternativesSearchOptions,
		reference: Manga,
		referenceChapters: Int,
	): Comparator<MangaAlternativeModel> = Comparator { left, right ->
		val primary = when (options.sortOrder) {
			AlternativeSortOrder.BEST_MATCH -> compareValues(
				right.manga.titleMatchScore(options.query),
				left.manga.titleMatchScore(options.query),
			).takeUnless { it == 0 } ?: compareValues(
				right.chaptersCount > 0,
				left.chaptersCount > 0,
			).takeUnless { it == 0 } ?: compareValues(
				right.manga.source.priority(reference.source),
				left.manga.source.priority(reference.source),
			)

			AlternativeSortOrder.MOST_CHAPTERS -> compareValues(right.chaptersCount, left.chaptersCount)
			AlternativeSortOrder.CLOSEST_CHAPTER_COUNT -> compareValues(
				left.chapterDistanceFrom(referenceChapters),
				right.chapterDistanceFrom(referenceChapters),
			)
			AlternativeSortOrder.SOURCE_PRIORITY -> compareValues(
				right.manga.source.priority(reference.source),
				left.manga.source.priority(reference.source),
			)
		}
		primary.takeUnless { it == 0 }
			?: compareValues(right.chaptersCount, left.chaptersCount).takeUnless { it == 0 }
			?: left.manga.source.name.compareTo(right.manga.source.name, ignoreCase = true).takeUnless { it == 0 }
			?: left.manga.title.compareTo(right.manga.title, ignoreCase = true).takeUnless { it == 0 }
			?: left.manga.id.compareTo(right.manga.id)
	}

	private fun MangaAlternativeModel.chapterDistanceFrom(referenceChapters: Int): Int = if (chaptersCount == 0) {
		Int.MAX_VALUE
	} else {
		abs(chaptersCount - referenceChapters)
	}

	private fun Manga.titleMatchScore(query: String): Int {
		val normalizedQuery = query.normalizeTitle()
		if (normalizedQuery.isEmpty()) return 0
		return (sequenceOf(title) + altTitles.asSequence()).maxOf { candidate ->
			val normalizedCandidate = candidate.normalizeTitle()
			when {
				normalizedCandidate == normalizedQuery -> 100
				normalizedCandidate.startsWith(normalizedQuery) || normalizedQuery.startsWith(normalizedCandidate) -> 85
				normalizedCandidate.contains(normalizedQuery) || normalizedQuery.contains(normalizedCandidate) -> 75
				else -> {
					val queryTokens = normalizedQuery.splitToSequence(' ').filter(String::isNotEmpty).toSet()
					val candidateTokens = normalizedCandidate.splitToSequence(' ').filter(String::isNotEmpty).toSet()
					val total = maxOf(queryTokens.size, candidateTokens.size)
					if (total == 0) 0 else queryTokens.intersect(candidateTokens).size * 60 / total
				}
			}
		}
	}

	private fun String.normalizeTitle(): String = lowercase(Locale.ROOT)
		.replace(NON_TITLE_CHARACTER, " ")
		.trim()
		.replace(MULTIPLE_SPACES, " ")

	private fun MangaSource.priority(ref: MangaSource): Int {
		if (this !is MangaParserSource || ref !is MangaParserSource) return 0
		var result = 0
		if (locale == ref.locale) {
			result += 4
		} else if (locale.toLocale() == Locale.getDefault()) {
			result += 2
		}
		if (contentType == ref.contentType) result++
		return result
	}

	private companion object {
		val NON_TITLE_CHARACTER = Regex("[^\\p{L}\\p{N}]+")
		val MULTIPLE_SPACES = Regex("\\s+")
	}
}
