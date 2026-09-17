package org.koitharu.kotatsu.search.ui.multi

import androidx.collection.ArraySet
import androidx.collection.LongSet
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
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.append
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.explore.data.SourcePresetsRepository
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.ui.model.ButtonFooter
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingFooter
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchV2Helper
import org.koitharu.kotatsu.sourcescore.domain.SourceRanker
import java.util.Locale
import javax.inject.Inject

private const val MAX_PARALLELISM = 4

/** Highest value [SearchViewModel.priority] returns: +2 for a locale match. */
private const val MAX_LOCALE_PRIORITY = 2.0

@HiltViewModel
class SearchViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val mangaListMapper: MangaListMapper,
	private val searchHelperFactory: SearchV2Helper.Factory,
	private val sourcesRepository: MangaSourcesRepository,
	private val historyRepository: HistoryRepository,
	private val favouritesRepository: FavouritesRepository,
	private val settings: AppSettings,
	private val presetsRepository: SourcePresetsRepository,
	private val sourceRanker: SourceRanker,
) : BaseViewModel() {

	val query = savedStateHandle.get<String>(AppRouter.KEY_QUERY).orEmpty()
	val kind = savedStateHandle.get<SearchKind>(AppRouter.KEY_KIND) ?: SearchKind.SIMPLE

	private var includeDisabledSources = MutableStateFlow(false)
	private var pinnedOnly = MutableStateFlow(false)
	private var hideEmpty = MutableStateFlow(false)
	private val results = MutableStateFlow<List<SearchResultsListModel>>(emptyList())

	/**
	 * Where each source's results belong on screen, keyed by source name. Sources answer in whatever
	 * order the network allows; without this a fast obscure source would push a slow popular one
	 * down the page. Filled in before a sweep starts, so every result finds its entry.
	 */
	private val displayRanks = java.util.concurrent.ConcurrentHashMap<String, DisplayRank>()

	private var searchJob: Job? = null

	val list: StateFlow<List<ListModel>> = combine(
		results,
		isLoading.dropWhile { !it },
		includeDisabledSources,
		hideEmpty,
	) { list, loading, includeDisabled, hideEmptyVal ->
		val filteredList = if (hideEmptyVal) {
			list.filter { it.list.isNotEmpty() }
		} else {
			list
		}.sortedForDisplay()
		when {
			filteredList.isEmpty() -> listOf(
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

			loading -> filteredList + LoadingFooter()
			includeDisabled -> filteredList
			else -> filteredList + ButtonFooter(R.string.search_disabled_sources)
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState()))

	init {
		doSearch()
	}

	fun getItems(ids: LongSet): Set<Manga> {
		val snapshot = results.value
		val result = ArraySet<Manga>(ids.size)
		snapshot.forEach { x ->
			for (item in x.list) {
				if (item.id in ids) {
					result.add(item.manga)
				}
			}
		}
		return result
	}

	fun retry() {
		searchJob?.cancel()
		results.value = emptyList()
		displayRanks.clear()
		includeDisabledSources.value = false
		doSearch()
	}

	fun setPinnedOnly(value: Boolean) {
		if (pinnedOnly.value != value) {
			pinnedOnly.value = value
			retry()
		}
	}

	fun setHideEmpty(value: Boolean) {
		hideEmpty.value = value
	}

	fun continueSearch() {
		if (includeDisabledSources.value) {
			return
		}
		val prevJob = searchJob
		searchJob = launchLoadingJob(Dispatchers.Default) {
			includeDisabledSources.value = true
			prevJob?.join()
			val sources = if (pinnedOnly.value) {
				emptyList()
			} else {
				sourcesRepository.getDisabledSources().toList().rankedForSweep(batch = 1)
			}
			val semaphore = Semaphore(MAX_PARALLELISM)
			sources.map { source ->
				launch {
					semaphore.withPermit {
						appendResult(searchSource(source))
					}
				}
			}.joinAll()
		}
	}

	private fun doSearch() {
		val prevJob = searchJob
		searchJob = launchLoadingJob(Dispatchers.Default) {
			prevJob?.cancelAndJoin()
			appendResult(searchHistory())
			appendResult(searchFavorites())
			appendResult(searchLocal())
			val sources = getPresetSourcesOrDefault().rankedForSweep(batch = 0)
			val semaphore = Semaphore(MAX_PARALLELISM)
			sources.map { source ->
				launch {
					semaphore.withPermit {
						appendResult(searchSource(source))
					}
				}
			}.joinAll()
		}
	}

	// impl

	private suspend fun searchSource(source: MangaSource): SearchResultsListModel? = runCatchingCancellable {
		val searchHelper = searchHelperFactory.create(source)
		searchHelper(query, kind)
	}.fold(
		onSuccess = { result ->
			if (result == null || result.manga.isEmpty()) {
				null
			} else {
				val list = mangaListMapper.toListModelList(
					manga = result.manga,
					mode = ListMode.GRID,
				)
				SearchResultsListModel(
					titleResId = 0,
					source = source,
					list = list,
					error = null,
					listFilter = result.listFilter,
					sortOrder = result.sortOrder,
					isHot = displayRanks[source.name]?.isHot == true,
				)
			}
		},
		onFailure = { error ->
			error.printStackTraceDebug()
			if (source is MangaParserSource && source.isBroken) {
				null
			} else {
				SearchResultsListModel(
					titleResId = 0,
					source = source,
					listFilter = null,
					sortOrder = null,
					list = emptyList(),
					error = error,
					isHot = displayRanks[source.name]?.isHot == true,
				)
			}
		},
	)

	private suspend fun searchHistory(): SearchResultsListModel? = runCatchingCancellable {
		historyRepository.search(query, kind, Int.MAX_VALUE)
	}.fold(
		onSuccess = { result ->
			if (result.isNotEmpty()) {
				SearchResultsListModel(
					titleResId = R.string.history,
					source = UnknownMangaSource,
					list = mangaListMapper.toListModelList(manga = result, mode = ListMode.GRID),
					error = null,
					listFilter = null,
					sortOrder = null,
				)
			} else {
				null
			}
		},
		onFailure = { error ->
			SearchResultsListModel(
				titleResId = R.string.history,
				source = UnknownMangaSource,
				list = emptyList(),
				error = error,
				listFilter = null,
				sortOrder = null,
			)
		},
	)

	private suspend fun searchFavorites(): SearchResultsListModel? = runCatchingCancellable {
		favouritesRepository.search(query, kind, Int.MAX_VALUE)
	}.fold(
		onSuccess = { result ->
			if (result.isNotEmpty()) {
				SearchResultsListModel(
					titleResId = R.string.favourites,
					source = UnknownMangaSource,
					list = mangaListMapper.toListModelList(
						manga = result,
						mode = ListMode.GRID,
						flags = MangaListMapper.NO_FAVORITE,
					),
					error = null,
					listFilter = null,
					sortOrder = null,
				)
			} else {
				null
			}
		},
		onFailure = { error ->
			SearchResultsListModel(
				titleResId = R.string.favourites,
				source = UnknownMangaSource,
				list = emptyList(),
				error = error,
				listFilter = null,
				sortOrder = null,
			)
		},
	)

	private suspend fun searchLocal(): SearchResultsListModel? = runCatchingCancellable {
		searchHelperFactory.create(LocalMangaSource).invoke(query, kind)
	}.fold(
		onSuccess = { result ->
			if (!result?.manga.isNullOrEmpty()) {
				SearchResultsListModel(
					titleResId = 0,
					source = LocalMangaSource,
					list = mangaListMapper.toListModelList(
						manga = result.manga,
						mode = ListMode.GRID,
						flags = MangaListMapper.NO_SAVED,
					),
					error = null,
					listFilter = result.listFilter,
					sortOrder = result.sortOrder,
				)
			} else {
				null
			}
		},
		onFailure = { error ->
			SearchResultsListModel(
				titleResId = 0,
				source = LocalMangaSource,
				list = emptyList(),
				error = error,
				listFilter = null,
				sortOrder = null,
			)
		},
	)

	private fun appendResult(item: SearchResultsListModel?) {
		if (item != null) {
			results.append(item)
		}
	}

	private fun MangaSource.priority(): Int {
		var res = 0
		if (this is MangaParserSource) {
			if (locale.toLocale() == Locale.getDefault()) res += 2
		}
		return res
	}

	/**
	 * Orders a sweep so the sources most likely to answer are asked first.
	 *
	 * The semaphore below already serialises work in list order, so ordering the list *is* the
	 * scheduling - no queue rewrite needed. Results therefore surface best-source-first instead of
	 * in whatever order the source table happened to return.
	 *
	 * [SourceRanker.RankingSnapshot.order] also reserves a slot for an under-sampled source, which is
	 * what stops a newly added source from never being queried and therefore never earning a score.
	 *
	 * Falls back to the previous locale heuristic if the community database cannot be read.
	 */
	private suspend fun List<MangaSource>.rankedForSweep(batch: Int): List<MangaSource> {
		if (isEmpty()) return this
		val snapshot = runCatchingCancellable { sourceRanker.snapshot() }.getOrNull()
			?: return sortedByDescending { it.priority() }
		val ranked = snapshot.order(
			sources = this,
			affinityOf = { source -> source.priority() / MAX_LOCALE_PRIORITY },
		)
		ranked.forEach { displayRanks[it.source.name] = DisplayRank(batch, it.isHot, it.composite) }
		return ranked.map { it.source }
	}

	/**
	 * History, favourites and local results keep their place at the top. Source results follow by
	 * rank: popular sources first, then best score. Deliberately not the sweep order, which puts a
	 * random under-sampled source near the front to let it earn a score - right for querying, wrong
	 * for what the user reads first.
	 *
	 * The sort is stable, so anything without a rank (the ranker was unavailable) keeps arrival order.
	 */
	private fun List<SearchResultsListModel>.sortedForDisplay(): List<SearchResultsListModel> = sortedWith(
		compareBy<SearchResultsListModel> { displayRanks[it.source.name]?.batch ?: -1 }
			.thenByDescending { displayRanks[it.source.name]?.isHot == true }
			.thenByDescending { displayRanks[it.source.name]?.composite ?: 0.0 },
	)

	private data class DisplayRank(
		/** 0 for the user's own sources, 1 for the "search disabled sources" follow-up, which stays below. */
		val batch: Int,
		val isHot: Boolean,
		val composite: Double,
	)

	private suspend fun getPresetSourcesOrDefault(): List<MangaSource> {
		val presetId = settings.activeSourcePresetId
		if (presetId != 0L) {
			val preset = presetsRepository.getById(presetId)
			if (preset != null) {
				if (preset.sources.isEmpty()) return emptyList()
				val skipNsfw = settings.isNsfwContentDisabled
				return sourcesRepository.allMangaSources.filter { source ->
					source.name in preset.sources && (!skipNsfw || !source.isNsfw())
				}
			}
		}
		return if (pinnedOnly.value) {
			sourcesRepository.getPinnedSources().toList()
		} else {
			sourcesRepository.getEnabledSources()
		}
	}
}
