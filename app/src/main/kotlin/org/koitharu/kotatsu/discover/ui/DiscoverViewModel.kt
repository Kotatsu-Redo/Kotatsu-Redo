package org.koitharu.kotatsu.discover.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.discover.data.DiscoverDismissals
import org.koitharu.kotatsu.discover.data.DismissedIndex
import org.koitharu.kotatsu.discover.domain.DiscoverFilter
import org.koitharu.kotatsu.discover.domain.DiscoverRanking
import org.koitharu.kotatsu.discover.domain.DiscoverRepository
import org.koitharu.kotatsu.discover.domain.DiscoverSection
import org.koitharu.kotatsu.discover.domain.DiscoverWindow
import org.koitharu.kotatsu.discover.domain.MangaDismisser
import org.koitharu.kotatsu.discover.domain.ReaderCategory
import org.koitharu.kotatsu.discover.domain.ReaderProfile
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.ui.model.EmptyHint
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.model.TipModel
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.ParserMangaSource
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class DiscoverViewModel @Inject constructor(
	private val repository: DiscoverRepository,
	private val mapper: MangaListMapper,
	dismissals: DiscoverDismissals,
	private val dismisser: MangaDismisser,
	private val settings: AppSettings,
) : BaseViewModel() {

	val tab = MutableStateFlow(DiscoverTab.FOR_YOU)
	val filter = MutableStateFlow(DiscoverFilter())
	val window = MutableStateFlow(DiscoverWindow.DAY)

	/** Bumping it asks for everything again. Zero is the first load, which may use the cache. */
	private val refreshes = MutableStateFlow(0)

	/** Coming back to this screen: they may have read something, or enabled a source. */
	private val returns = MutableStateFlow(0)
	private var hasOpened = false
	private var hadSources: Boolean? = null

	/**
	 * Both screens sit side by side and are always being watched, so this is what keeps the one not yet looked
	 * at from asking every source for its chart the moment the tab opens.
	 */
	private val hasOpenedTop = MutableStateFlow(tab.value == DiscoverTab.TOP)

	/** Kept between one drawing of the screen and the next, which happens every time another source answers. */
	private val models = ConcurrentHashMap<Long, MangaListModel>()

	private val dismissed: Flow<DismissedIndex> = dismissals.observe()
		.onEach { index ->
			// Passes made by an older version, or brought back by a backup, count: the hint is for whoever has not done it
			if (!index.isEmpty) settings.closeTip(AppSettings.TIP_DISCOVER_DISMISS)
		}
		.shared()

	/** The hint about long pressing a cover, until it is closed. Watched, so closing it anywhere closes it here. */
	private val isTipOpen: Flow<Boolean> = settings.observeAsFlow(AppSettings.KEY_TIPS_CLOSED) {
		isTipEnabled(AppSettings.TIP_DISCOVER_DISMISS)
	}

	/** Worked out from the whole library, and so not something the screen should wait for. */
	private val profile: Flow<ReaderProfile?> = combine(refreshes, returns, ::Pair)
		.flatMapLatest { repository.observeProfile() }
		.distinctUntilChanged { old, new -> old === new }
		.shared()

	private val isNewTopRefresh = refreshGate()

	private val top: Flow<DiscoverSection> = combine(window, filter, refreshes, ::Triple)
		.flatMapLatest { (window, filter, refresh) -> repository.observeTop(window, filter, isNewTopRefresh(refresh)).loading() }
		.shared()

	private val isNewFindsRefresh = refreshGate()

	private val newFinds: Flow<DiscoverSection> = combine(filter, refreshes, ::Pair)
		.flatMapLatest { (filter, refresh) -> repository.observeNewFinds(filter, isNewFindsRefresh(refresh)).loading() }
		.shared()

	private val forYou: Flow<List<Manga>> = combine(filter, refreshes, ::Pair)
		.flatMapLatest { (filter, _) -> flow { emit(repository.getForYou(filter)) } }
		.shared()

	/** The rows of what the reader keeps coming back to. A genre picked up top already says what to show. */
	private val isNewCategoryRefresh = refreshGate()

	private val categoryRows: Flow<List<Pair<ReaderCategory, DiscoverSection>>> = combine(filter, refreshes, ::Pair)
		.flatMapLatest { (filter, refresh) ->
			val force = isNewCategoryRefresh(refresh)
			repository.observeCategories().flatMapLatest { categories ->
				if (filter.genre != null || categories.isEmpty()) {
					flowOf(emptyList())
				} else {
					combine(
						categories.map { category ->
							repository.observeCategory(category, filter.type, force).loading().map { category to it }
						},
					) { it.toList() }
				}
			}
		}
		.shared()

	/** "Because you read" rows. They appear when they are ready, as there may turn out to be nothing to show. */
	private val isNewLikeRefresh = refreshGate()

	private val likeRows: Flow<List<Pair<Manga, DiscoverSection>>> = combine(filter, refreshes, ::Pair)
		.flatMapLatest { (filter, refresh) ->
			val force = isNewLikeRefresh(refresh)
			flow { emit(repository.getLikeSeeds()) }.flatMapLatest { seeds ->
				if (filter.genre != null || seeds.isEmpty()) {
					flowOf(emptyList())
				} else {
					combine(
						seeds.map { seed ->
							repository.observeLike(seed, force).loading().map { section ->
								seed to section.copy(
									manga = section.manga.filter { manga ->
										DiscoverRanking.matchesType(manga, filter.type, (manga.source as? ParserMangaSource)?.contentType)
									},
								)
							}
						},
					) { it.toList() }
				}
			}
		}
		.shared()

	private val genres: Flow<List<String>> = refreshes
		.flatMapLatest { flow { emit(repository.getGenres()) } }
		.onStart { emit(emptyList()) }
		.shared()

	private val hasSources: Flow<Boolean> = combine(refreshes, returns, ::Pair)
		.flatMapLatest { flow { emit(repository.hasSources()) } }
		.distinctUntilChanged()
		.onEach { hasThem ->
			// Everything was asked of no sources at all, so all of it has to be asked again
			if (hadSources == false && hasThem) refresh()
			hadSources = hasThem
		}
		.shared()

	val chips = combine(tab, window, filter, genres) { tab, window, filter, genres ->
		// A genre still in force has to stay there to be tapped off, wherever the list has moved on to
		val picked = filter.genre?.takeUnless { it in genres }
		DiscoverChips(tab, window, filter, if (picked != null) genres + picked else genres)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), DiscoverChips(tab.value, window.value, filter.value, emptyList()))

	val forYouContent = combine(
		combine(filter, hasSources, dismissed, profile, isTipOpen, ::ForYouSetting),
		forYou,
		newFinds,
		combine(categoryRows, likeRows, ::Pair),
	) { setting, forYou, finds, (rows, likes) ->
		ForYouSnapshot(setting, forYou, finds, rows, likes)
	}
		// Answers come in bursts, and drawing all of it for each is the slowest thing on this screen
		.debounce { if (it.isSettled) 0L else REDRAW_DELAY_MS }
		.mapLatest { if (it.setting.hasSources) buildForYou(it) else noSources() }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), listOf<ListModel>(LoadingState()))

	val topContent = hasOpenedTop.flatMapLatest { isOpened ->
		if (isOpened) {
			combine(filter, hasSources, dismissed, top) { filter, hasSources, dismissed, top ->
				if (hasSources) buildTop(filter, top, dismissed) else noSources()
			}
		} else {
			flowOf(listOf<ListModel>(LoadingState()))
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), listOf<ListModel>(LoadingState()))

	fun setTab(value: DiscoverTab) {
		tab.value = value
		if (value == DiscoverTab.TOP) {
			hasOpenedTop.value = true
		}
	}

	/** Tapping the type already picked clears it. */
	fun toggleType(type: ContentType) = filter.update { it.copy(type = type.takeUnless { _ -> it.type == type }) }

	/** Tapping the genre already picked clears it. */
	fun toggleGenre(genre: String) = filter.update { it.copy(genre = genre.takeUnless { _ -> it.genre == genre }) }

	fun setWindow(value: DiscoverWindow) {
		window.value = value
	}

	fun onScreenResumed() {
		if (hasOpened) {
			returns.update { it + 1 }
		}
		hasOpened = true
	}

	fun refresh() {
		repository.invalidate()
		models.clear()
		refreshes.update { it + 1 }
	}

	/** @return what to pass to [undoDismiss] to take it back */
	fun dismiss(manga: Manga): String = dismisser.dismiss(manga)

	fun undoDismiss(key: String) = dismisser.undo(key)

	fun clearDismissed() = dismisser.undoAll()

	fun closeTip() {
		settings.closeTip(AppSettings.TIP_DISCOVER_DISMISS)
	}

	private suspend fun buildForYou(snapshot: ForYouSnapshot): List<ListModel> = buildList {
		val forYou = snapshot.forYou
		val finds = snapshot.finds
		val rows = snapshot.rows
		// Each row leaves out what a row above has already shown: repeating a cover wastes the space
		val shown = HashSet<String>()
		val dismissed = snapshot.setting.dismissed
		// Told once, where the reader will see it, and never after they have passed on anything
		if (snapshot.setting.showTip && dismissed.isEmpty) {
			add(
				TipModel(
					key = AppSettings.TIP_DISCOVER_DISMISS,
					title = R.string.not_interested,
					text = R.string.not_interested_tip,
					icon = R.drawable.ic_tap,
					primaryButtonText = R.string.got_it,
					secondaryButtonText = 0,
				),
			)
		}
		val readerProfile = snapshot.setting.profile
		val known = readerProfile?.knownTitles.orEmpty()
		fun List<Manga>.available() = this.asSequence()
			.filter { manga -> DiscoverRanking.titleKey(manga.title) !in shown && !dismissed.matches(manga) }
			// Lists are kept for hours, and they may have read or saved something on one since
			.filterNot { manga -> DiscoverRanking.isKnown(manga, known) }
			.distinctBy { manga -> DiscoverRanking.titleKey(manga.title) }

		fun List<Manga>.notShownYet(limit: Int) = available().take(limit).toList()
			.also { list -> list.mapTo(shown) { DiscoverRanking.titleKey(it.title) } }

		// The top stays put and the rest changes daily, so looking again is not the same row of what they passed over
		fun List<Manga>.rotatedRow() = DiscoverRanking.rotate(available().toList(), LocalDate.now().toEpochDay(), ROW_SIZE)
			.also { list -> list.mapTo(shown) { DiscoverRanking.titleKey(it.title) } }

		// Until the suggestions worker has run there is nothing stored, and under a filter few of what it stored
		// are left. The rest of the screen still has a lot to go on: many sources list no tags, so taste alone can
		// come up short, but the rows are in their genres, and of the type asked for, by asking for them
		val rowManga = rows.flatMap { (_, section) -> section.manga }
		val recommended = DiscoverRanking.recommendedRow(
			stored = forYou.available().toList(),
			rows = rows.map { (_, section) -> section.manga },
			others = finds.manga,
			taste = DiscoverRanking.applyDismissals(readerProfile?.taste.orEmpty(), dismissed.tagLists),
			size = ROW_SIZE,
		).notShownYet(ROW_SIZE)
		if (recommended.isNotEmpty()) {
			add(
				DiscoverRowItem(
					title = R.string.for_you,
					items = recommended.toModels(),
					isLoading = false,
					opensSuggestions = forYou.isNotEmpty(),
				),
			)
		}
		for ((category, section) in rows) {
			val row = section.manga.rotatedRow()
			if (row.isNotEmpty() || !section.isFinal) {
				add(
					DiscoverRowItem(
						title = 0,
						titleText = category.title,
						items = row.toModels(),
						isLoading = !section.isFinal && row.isEmpty(),
						opensSuggestions = false,
					),
				)
			}
		}
		for ((seed, section) in snapshot.likes) {
			val row = section.manga.notShownYet(ROW_SIZE)
			if (row.isNotEmpty()) {
				add(
					DiscoverRowItem(
						title = R.string.because_you_read,
						titleArgument = seed.title,
						items = row.toModels(),
						isLoading = false,
						opensSuggestions = false,
					),
				)
			}
		}
		val newRow = finds.manga.notShownYet(ROW_SIZE)
		if (newRow.isNotEmpty() || !finds.isFinal) {
			add(
				DiscoverRowItem(
					title = R.string.new_finds,
					items = newRow.toModels(),
					isLoading = !finds.isFinal && newRow.isEmpty(),
					opensSuggestions = false,
				),
			)
		}
		val more = (forYou + rowManga + finds.manga).notShownYet(MAX_GRID)
		if (more.isNotEmpty()) {
			add(ListHeader(R.string.more_for_you))
			addAll(more.toModels())
		}
		if (shown.isEmpty() && snapshot.isSettled) {
			add(nothingFound(snapshot.setting.filter))
		}
	}

	/**
	 * Nothing on this screen is in the library, so there is no progress or new chapter to look up for any of it,
	 * and a cover already worked out stays as it is while the rest of the screen fills in around it.
	 */
	private suspend fun List<Manga>.toModels(): List<MangaListModel> {
		if (models.size > MAX_KEPT_MODELS) {
			models.clear()
		}
		val missing = filter { models[it.id]?.manga !== it }
		if (missing.isNotEmpty()) {
			mapper.toListModelList(missing, ListMode.GRID, MangaListMapper.NO_BADGES).forEach { models[it.manga.id] = it }
		}
		return mapNotNull { models[it.id] }
	}

	private fun buildTop(
		filter: DiscoverFilter,
		top: DiscoverSection,
		dismissed: DismissedIndex,
	): List<ListModel> = buildList {
		val manga = top.manga.filterNot { dismissed.matches(it) }
		when {
			manga.isNotEmpty() -> manga.forEachIndexed { index, item -> add(DiscoverRankedManga(index + 1, item)) }
			top.isFinal -> add(nothingFound(filter))
			else -> repeat(PLACEHOLDERS) { add(DiscoverPlaceholder(it)) }
		}
	}

	private fun nothingFound(filter: DiscoverFilter) = EmptyHint(
		icon = R.drawable.ic_empty_common,
		textPrimary = R.string.nothing_found,
		textSecondary = if (filter != DiscoverFilter()) R.string.text_empty_holder_secondary_filtered else 0,
		actionStringRes = 0,
	)

	private fun noSources() = listOf<ListModel>(
		EmptyHint(
			icon = R.drawable.ic_empty_common,
			textPrimary = R.string.no_manga_sources,
			textSecondary = R.string.no_manga_sources_text,
			actionStringRes = R.string.manage,
		),
	)

	/**
	 * Whether a refresh has not been acted on yet. A refresh is asked for once: picking a filter after it is not
	 * asking for every site to be fetched from again.
	 */
	private fun refreshGate(): (Int) -> Boolean {
		var seen = 0
		return { refresh -> (refresh > seen).also { seen = refresh } }
	}

	/** So that changing a filter shows a list loading at once, not the previous answer until the new one. */
	private fun Flow<DiscoverSection>.loading(): Flow<DiscoverSection> =
		onStart { emit(DiscoverSection(emptyList(), isFinal = false)) }

	private fun <T> Flow<T>.shared(): Flow<T> =
		shareIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(KEEP_RUNNING_MS), replay = 1)

	private class ForYouSetting(
		val filter: DiscoverFilter,
		val hasSources: Boolean,
		val dismissed: DismissedIndex,
		val profile: ReaderProfile?,
		val showTip: Boolean,
	)

	private class ForYouSnapshot(
		val setting: ForYouSetting,
		val forYou: List<Manga>,
		val finds: DiscoverSection,
		val rows: List<Pair<ReaderCategory, DiscoverSection>>,
		val likes: List<Pair<Manga, DiscoverSection>>,
	) {
		/** Nothing more is on its way, so this is worth drawing right away. */
		val isSettled: Boolean
			get() = finds.isFinal && rows.all { (_, section) -> section.isFinal } && likes.all { (_, section) -> section.isFinal }
	}

	private companion object {
		const val ROW_SIZE = 15
		const val MAX_GRID = 60
		const val PLACEHOLDERS = 9
		const val KEEP_RUNNING_MS = 0L
		const val REDRAW_DELAY_MS = 120L
		const val MAX_KEPT_MODELS = 1000
	}
}
