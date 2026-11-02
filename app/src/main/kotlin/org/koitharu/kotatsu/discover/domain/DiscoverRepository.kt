package org.koitharu.kotatsu.discover.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.discover.data.DiscoverCache
import org.koitharu.kotatsu.discover.data.DiscoverDismissals
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sourcescore.data.CommunityApi
import org.koitharu.kotatsu.sourcescore.data.CommunitySettings
import org.koitharu.kotatsu.sourcescore.data.SimilarWorkDto
import org.koitharu.kotatsu.suggestions.domain.CommunitySuggestionsSource
import org.koitharu.kotatsu.suggestions.domain.SeedCandidate
import org.koitharu.kotatsu.suggestions.domain.SuggestionRanking
import org.koitharu.kotatsu.suggestions.domain.SuggestionRepository
import org.koitharu.kotatsu.suggestions.domain.TagsBlacklist
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class DiscoverFilter(val type: ContentType? = null, val genre: String? = null)

/** A list as far as it has got. [isFinal] false means more sources are still answering. */
data class DiscoverSection(val manga: List<Manga>, val isFinal: Boolean)

/**
 * Where everything on the Explore screen comes from, none of it adult whatever the other settings say.
 * Charts are what the reader's enabled sources say is
 * popular, with what the community has been rating and discussing folded in when community features are
 * on; finds are what those sources have only just got, picked for this reader on the device. What the
 * reader likes stays on the phone: a chart is asked for whole and narrowed down here, so the only
 * question it takes is "what is on the chart this week".
 */
class DiscoverRepository @Inject constructor(
	private val settings: AppSettings,
	private val sourcesRepository: MangaSourcesRepository,
	private val repositoryFactory: MangaRepository.Factory,
	private val suggestions: SuggestionRepository,
	private val history: HistoryRepository,
	private val cache: DiscoverCache,
	private val communitySettings: CommunitySettings,
	private val communityApi: CommunityApi,
	private val communitySource: CommunitySuggestionsSource,
	private val profiles: ReaderProfiles,
	private val dismissals: DiscoverDismissals,
) {

	/** Every row asks the same few sites, which should not see that as a burst. */
	private val sourceLocks = ConcurrentHashMap<String, Mutex>()

	private val chartLock = Mutex()
	private val charts = ConcurrentHashMap<DiscoverWindow, Timed<List<SimilarWorkDto>>>()

	private val sourcesLock = Mutex()

	@Volatile
	private var sources: Timed<List<MangaSource>>? = null

	private val blacklist: TagsBlacklist
		get() = TagsBlacklist(settings.suggestionsTagsBlacklist, TAG_MATCH_THRESHOLD)

	suspend fun hasSources(): Boolean = sourcesRepository.getEnabledSources().isNotEmpty()

	/** What the reader reads most first, then the genres everyone looks for. */
	suspend fun getGenres(): List<String> {
		val own = runCatchingCancellable { profiles.get().taste }.getOrDefault(emptyMap())
			.entries.sortedByDescending { it.value }
			.take(MAX_OWN_GENRES)
			.map { (tag, _) -> DiscoverRanking.genreTitle(tag) }
		return (own + COMMON_GENRES).distinctBy { DiscoverRanking.titleKey(it) }
	}

	/** What the reader keeps coming back to: last time's answer at once if there is one, then the current one. */
	fun observeCategories(): Flow<List<ReaderCategory>> = flow {
		runCatchingCancellable { profiles.lastCategories() }.getOrNull()?.let { emit(it) }
		emit(runCatchingCancellable { profiles.get().categories }.onFailure { it.printStackTraceDebug() }.getOrDefault(emptyList()))
	}.distinctUntilChanged()

	/** What the library adds up to, so far as that is known: nothing on screen has to wait to find out. */
	fun observeProfile(): Flow<ReaderProfile?> = flow {
		emit(profiles.peek())
		emit(runCatchingCancellable { profiles.get() }.onFailure { it.printStackTraceDebug() }.getOrNull())
	}.distinctUntilChanged { old, new -> old === new }

	/** Asks for everything again, however recently it was fetched. */
	fun invalidate() {
		profiles.invalidate()
		sources = null
		charts.clear()
	}

	/**
	 * What is popular in something the reader keeps coming back to, that they do not have yet.
	 *
	 * Only asks the sources they read on, and only a few: this is one row of several, and those sites are
	 * where they would rather read it anyway. Someone with a big library has already read the top of any list,
	 * so this goes further down it until there is enough left. What the community is rating moves a title up
	 * at no cost to any site, since it only takes one chart, already fetched.
	 */
	fun observeCategory(category: ReaderCategory, type: ContentType?, force: Boolean): Flow<DiscoverSection> =
		cachedSection("category:${category.key}:$type", CATEGORY_MAX_AGE_MS, force) { emit ->
			val taste = currentTaste()
			val known = knownTitles()
			val lists = ArrayList<RankedList>()
			val mutex = Mutex()
			val ranks = coroutineScope {
				val chart = async { chartRanks() }
				coroutineScope {
					for (source in chartSources().take(MAX_CATEGORY_SOURCES)) {
						launch {
							val list = fetchCategory(source, category, type, known)
							if (list.isEmpty()) return@launch
							val partial = mutex.withLock {
								lists += RankedList(list, 1.0)
								DiscoverRanking.fuse(lists, taste)
							}
							emit(partial)
						}
					}
				}
				chart.await()
			}
			mutex.withLock { DiscoverRanking.fuse(lists, taste, ranks).take(MAX_TOP) }
		}

	/**
	 * Works like one the reader has been enjoying, asked of the server one manga at a time like everything it is
	 * asked: the combination of them is what would say something about the reader, and that stays here.
	 */
	fun observeLike(seed: Manga, force: Boolean): Flow<DiscoverSection> =
		cachedSection("like:${seed.id}", LIKE_MAX_AGE_MS, force) { _ ->
			runCatchingCancellable {
				val profile = profiles.get()
				communitySource.getSimilarTo(seed, MAX_LIKE, excludeNsfw = true, taste = currentTaste()) { work ->
					// Unlike a chart, this answer lists adult genres: asking costs a request, and it would go anyway
					work.genres.any(DiscoverRanking::isAdultTag) || profile.knows(work.title, work.sources.map { it.source to it.key })
				}
			}.onFailure { it.printStackTraceDebug() }.getOrDefault(emptyList())
		}

	/**
	 * What to base rows of "because you read" on: a few of what they have been enjoying most, not all alike, and
	 * different ones from one day to the next. None at all when they have turned off everything that would ask
	 * the server about a manga.
	 */
	suspend fun getLikeSeeds(): List<Manga> {
		if (!communitySettings.isEnabled && !settings.isSuggestionsEnabled) {
			return emptyList()
		}
		val now = Instant.now()
		val pagesRead = runCatchingCancellable { profiles.get().pagesRead }.getOrDefault(emptyMap())
		val candidates = runCatchingCancellable { history.getListWithProgress(0, LIKE_SEED_POOL) }
			.onFailure { it.printStackTraceDebug() }
			.getOrDefault(emptyList())
			.filterNot { (manga, progress) ->
				manga.isLocal || manga.isNsfw() || DiscoverRanking.isAdult(manga) ||
					SuggestionRanking.isDropped(progress.percent, progress.updatedAt, now)
			}
			.mapIndexed { index, (manga, progress) ->
				SeedCandidate(manga, DiscoverRanking.tagsOf(manga), SuggestionRanking.seedWeight(index, progress.percent, isFavourite = false, pagesRead = pagesRead[manga.id] ?: 0))
			}
		val picks = SuggestionRanking.pickSeeds(candidates, MAX_LIKE_POOL)
		if (picks.isEmpty()) {
			return emptyList()
		}
		val first = (LocalDate.now().toEpochDay() % picks.size).toInt()
		return List(minOf(MAX_LIKE_ROWS, picks.size)) { picks[(first + it) % picks.size] }
	}

	fun observeTop(window: DiscoverWindow, filter: DiscoverFilter, force: Boolean): Flow<DiscoverSection> =
		// The name changes with the size of the list, so a shorter one cached earlier is not shown for the rest of its day
		cachedSection("top$MAX_TOP:${window.wireName}:${filter.type}:${filter.genre}", TOP_MAX_AGE_MS, force) { emit ->
			val lists = ArrayList<RankedList>()
			val sourceCharts = ArrayList<SourceChart>()
			val mutex = Mutex()
			suspend fun add(list: RankedList) {
				if (list.manga.isEmpty()) return
				val partial = mutex.withLock {
					lists += list
					DiscoverRanking.fuse(lists)
				}
				emit(partial)
			}
			coroutineScope {
				val chart = async { chartWorks(window).filter { DiscoverRanking.fits(it, filter) } }
				val semaphore = Semaphore(MAX_PARALLELISM)
				chartSources().map { source ->
					launch {
						semaphore.withPermit {
							fetchSourceChart(source, window, filter)?.let { sourceChart ->
								mutex.withLock { sourceCharts += sourceChart }
								add(sourceChart.shown)
							}
						}
					}
				}.joinAll()
				// Last, as most of it is usually on the lists by now, and those titles cost no site another request
				val listed = mutex.withLock { lists.flatMap { it.manga } }
				add(RankedList(communityList(chart.await(), listed), COMMUNITY_WEIGHT))
				// Too few sites answered, or they all list the same titles: further down those lists, one site at a
				// time and no further than it takes, as this is requests a fuller answer would not have needed
				for (sourceChart in sourceCharts) {
					var pages = 0
					while (mutex.withLock { DiscoverRanking.fuse(lists).size } < MAX_TOP && pages <= MAX_TOP_EXTRA_PAGES) {
						val previous = sourceChart.shown
						val hasMore = sourceChart.goFurther(filter, isFirstStep = pages == 0)
						if (sourceChart.shown !== previous) {
							val partial = mutex.withLock {
								lists[lists.indexOf(previous)] = sourceChart.shown
								DiscoverRanking.fuse(lists)
							}
							emit(partial)
						}
						if (!hasMore) break
						pages++
					}
				}
			}
			mutex.withLock { DiscoverRanking.fuse(lists).take(MAX_TOP) }
		}

	fun observeNewFinds(filter: DiscoverFilter, force: Boolean): Flow<DiscoverSection> =
		cachedSection("new:${filter.type}:${filter.genre}", NEW_MAX_AGE_MS, force) { emit ->
			val taste = currentTaste()
			val known = knownTitles()
			val lists = ArrayList<List<Manga>>()
			val mutex = Mutex()
			coroutineScope {
				val semaphore = Semaphore(MAX_PARALLELISM)
				for (source in chartSources()) {
					launch {
						semaphore.withPermit {
							val list = fetchNewest(source, filter) ?: return@withPermit
							val partial = mutex.withLock {
								lists += list
								DiscoverRanking.newFinds(lists, taste, known, MAX_NEW)
							}
							emit(partial)
						}
					}
				}
			}
			mutex.withLock { DiscoverRanking.newFinds(lists, taste, known, MAX_NEW) }
		}

	/**
	 * What the suggestions worker has already worked out for this reader. Never asked to do it again here:
	 * it is a slow job with its own schedule.
	 */
	suspend fun getForYou(filter: DiscoverFilter): List<Manga> {
		// Turning them off stops the worker, which leaves whatever it last stored
		if (!settings.isSuggestionsEnabled) {
			return emptyList()
		}
		val list = runCatchingCancellable { suggestions.getTopList(MAX_FOR_YOU * 4) }
			.onFailure { it.printStackTraceDebug() }
			.getOrDefault(emptyList())
		return list.applyReaderFilters().asSequence()
			.filter { DiscoverRanking.matchesGenre(it, filter.genre) }
			.filter { DiscoverRanking.matchesType(it, filter.type, (it.source as? MangaParserSource)?.contentType) }
			.take(MAX_FOR_YOU)
			.toList()
	}

	/**
	 * Shows what is cached straight away; if that is missing, out of date or a refresh was asked for, then
	 * fetches. A section with nothing cached fills in as sources answer, while one replacing an older list
	 * does it in one go, so the list does not shrink to one source's answer and grow back. A fetch that
	 * finds nothing at all keeps the older list: a bad connection should not empty the screen.
	 */
	private fun cachedSection(
		key: String,
		maxAgeMs: Long,
		force: Boolean,
		fetch: suspend (emit: suspend (List<Manga>) -> Unit) -> List<Manga>,
	): Flow<DiscoverSection> = flow {
		val cached = cache.get(key)
		val isFresh = cached != null && System.currentTimeMillis() - cached.savedAt < maxAgeMs
		if (cached != null) {
			emit(DiscoverSection(cached.manga.applyReaderFilters(), isFinal = isFresh && !force))
		}
		if (isFresh && !force) {
			return@flow
		}
		val showPartial = cached == null
		val partials = Channel<List<Manga>>(Channel.CONFLATED)
		val result = coroutineScope {
			val job = async {
				try {
					fetch { partials.trySend(it) }
				} finally {
					partials.close()
				}
			}
			for (partial in partials) {
				if (showPartial) {
					emit(DiscoverSection(partial.applyReaderFilters().take(MAX_TOP), isFinal = false))
				}
			}
			job.await()
		}
		if (result.isNotEmpty()) {
			cache.put(key, result)
			emit(DiscoverSection(result.applyReaderFilters(), isFinal = true))
		} else {
			emit(DiscoverSection(cached?.manga?.applyReaderFilters().orEmpty(), isFinal = true))
		}
	}

	/** Applied on the way out, not when saved: changing these settings takes effect without a refetch. */
	private fun List<Manga>.applyReaderFilters(): List<Manga> {
		val tagsBlacklist = blacklist
		return filter { manga ->
			!DiscoverRanking.isAdult(manga) && !manga.source.isNsfw() && manga !in tagsBlacklist
		}
	}

	/**
	 * The whole of one window's chart, kept briefly: asking once and narrowing it down here makes every filter
	 * instant, and means the server never gets told which genres this reader cares about. Off means off: the
	 * request is anonymous, but it is still a request the reader did not agree to.
	 */
	private suspend fun chartWorks(window: DiscoverWindow): List<SimilarWorkDto> {
		if (!communitySettings.isEnabled) {
			return emptyList()
		}
		return chartLock.withLock {
			charts[window]?.takeIf { it.isYoungerThan(CHART_MAX_AGE_MS) }?.value
				?: runCatchingCancellable {
					withTimeoutOrNull(SOURCE_TIMEOUT_MS) { communityApi.fetchCharts(window.wireName, MAX_CHART) }
				}.onFailure {
					it.printStackTraceDebug()
				}.getOrNull().orEmpty().also { charts[window] = Timed(it) }
		}
	}

	private suspend fun chartRanks(): Map<String, Int> {
		val ranks = HashMap<String, Int>()
		chartWorks(DiscoverWindow.MONTH).forEachIndexed { index, work ->
			ranks.putIfAbsent(DiscoverRanking.titleKey(work.title), index)
		}
		return ranks
	}

	/**
	 * A chart as manga to open, in its order. A title a source has just listed is that same manga, and only those
	 * no source listed are worth a request to get.
	 */
	private suspend fun communityList(works: List<SimilarWorkDto>, listed: List<Manga>): List<Manga> {
		if (works.isEmpty()) {
			return emptyList()
		}
		val byTitle = HashMap<String, Manga>(listed.size)
		listed.forEach { byTitle.putIfAbsent(DiscoverRanking.titleKey(it.title), it) }
		val missing = works.filter { DiscoverRanking.titleKey(it.title) !in byTitle }
		val resolved = runCatchingCancellable { communitySource.resolveChart(missing, MAX_COMMUNITY_RESOLVED) }
			.onFailure { it.printStackTraceDebug() }
			.getOrDefault(emptyMap())
		return works.mapNotNull { byTitle[DiscoverRanking.titleKey(it.title)] ?: resolved[it.workId] }
	}

	private suspend fun fetchSourceChart(
		source: MangaSource,
		window: DiscoverWindow,
		filter: DiscoverFilter,
	): SourceChart? = fetchFrom(source) { repository ->
		val order = DiscoverRanking.orderFor(window, repository.sortOrders) ?: return@fetchFrom null
		val list = repository.getFiltered(source, order.order, filter) ?: return@fetchFrom null
		SourceChart(source, order.order, order.weight, list)
	}

	/** One site's chart. Only the top of what it answered counts at first, so no site outweighs the others. */
	private inner class SourceChart(
		private val source: MangaSource,
		private val order: SortOrder,
		private val weight: Double,
		private var fetched: List<Manga>,
	) {

		var shown = RankedList(fetched.take(MAX_PER_SOURCE), weight)
			private set

		/**
		 * Adds what was already fetched if some was left out, as that costs nothing, and the next page otherwise.
		 *
		 * @return whether there may be more
		 */
		suspend fun goFurther(filter: DiscoverFilter, isFirstStep: Boolean): Boolean {
			if (isFirstStep && fetched.size > shown.manga.size) {
				shown = RankedList(fetched, weight)
				return true
			}
			val next = fetchFrom(source) { repository ->
				repository.getFiltered(source, order, filter, offset = fetched.size)
			}.orEmpty()
			if (next.isEmpty()) {
				return false
			}
			fetched = fetched + next
			shown = RankedList(fetched, weight)
			return true
		}
	}

	private suspend fun fetchNewest(source: MangaSource, filter: DiscoverFilter): List<Manga>? = fetchFrom(source) { repository ->
		val order = DiscoverRanking.newestOrder(repository.sortOrders) ?: return@fetchFrom null
		repository.getFiltered(source, order, filter)?.take(MAX_PER_SOURCE)
	}

	/** Whatever a site got through before it failed or ran out of time still counts. */
	private suspend fun fetchCategory(
		source: MangaSource,
		category: ReaderCategory,
		type: ContentType?,
		known: Set<String>,
	): List<Manga> {
		val fresh = ArrayList<Manga>(MAX_PER_SOURCE)
		sourceLocks.computeIfAbsent(source.name) { Mutex() }.withLock {
			runCatchingCancellable {
				withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
					fillCategory(repositoryFactory.create(source), source, category, type, known, fresh)
				}
			}.onFailure { it.printStackTraceDebug() }
		}
		return fresh.take(MAX_PER_SOURCE)
	}

	private suspend fun fillCategory(
		repository: MangaRepository,
		source: MangaSource,
		category: ReaderCategory,
		type: ContentType?,
		known: Set<String>,
		into: MutableList<Manga>,
	) {
		val order = DiscoverRanking.orderFor(DiscoverWindow.MONTH, repository.sortOrders)?.order
			?: SortOrder.RATING.takeIf { it in repository.sortOrders }
			?: return
		val options = repository.getFilterOptions()
		val matched = category.tags.mapNotNull { DiscoverRanking.findTag(options.availableTags, it, TAG_MATCH_THRESHOLD) }
		val first = matched.firstOrNull() ?: return
		val tags = if (repository.filterCapabilities.isMultipleTagsSupported) matched.toSet() else setOf(first)
		val types = HashSet<ContentType>()
		if (type != null) {
			if (type in options.availableContentTypes) {
				types += type
			} else if (!source.servesOnly(type)) {
				return
			}
		}
		val filter = MangaListFilter(tags = tags, types = types)
		var offset = 0
		repeat(MAX_CATEGORY_PAGES) {
			val list = repository.getList(offset = offset, order = order, filter = filter)
			if (list.isEmpty()) return
			offset += list.size
			list.filterTo(into) { manga ->
				!DiscoverRanking.isKnown(manga, known) &&
					// Asked for only one of them, so what the site lists is the only check of the other
					(tags.size == matched.size || manga.tags.isEmpty() || matched.all { tag -> DiscoverRanking.findTag(manga.tags, tag.title, TAG_MATCH_THRESHOLD) != null })
			}
			if (into.size >= MAX_PER_SOURCE) return
		}
	}

	/** One slow or blocked site must only cost the list its own entries. */
	private suspend fun <T : Any> fetchFrom(source: MangaSource, block: suspend (MangaRepository) -> T?): T? =
		runCatchingCancellable {
			withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
				block(repositoryFactory.create(source))
			}
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()

	/** @return null if this source has no way of answering the filter, rather than an unfiltered list */
	private suspend fun MangaRepository.getFiltered(
		source: MangaSource,
		order: SortOrder,
		filter: DiscoverFilter,
		offset: Int = 0,
	): List<Manga>? {
		val tags = HashSet<MangaTag>()
		val types = HashSet<ContentType>()
		if (filter.genre != null || filter.type != null) {
			val options = getFilterOptions()
			if (filter.genre != null) {
				tags += DiscoverRanking.findTag(options.availableTags, filter.genre, TAG_MATCH_THRESHOLD)
					?: return null
			}
			if (filter.type != null) {
				if (filter.type in options.availableContentTypes) {
					types += filter.type
				} else if (!source.servesOnly(filter.type)) {
					return null
				}
			}
		}
		return getList(offset = offset, order = order, filter = MangaListFilter(tags = tags, types = types))
	}

	/**
	 * A source that cannot filter by type still answers when it only carries that type. "Manga" is the
	 * parsers' default, which also covers mixed catalogues, so that alone counts as an answer for manga.
	 */
	private fun MangaSource.servesOnly(type: ContentType): Boolean {
		val own = (this as? MangaParserSource)?.contentType ?: return false
		return own == type
	}

	/** The sources the reader actually reads on first: what is there is what they would open anyway. */
	private suspend fun chartSources(): List<MangaSource> = sourcesLock.withLock {
		sources?.takeIf { it.isYoungerThan(SOURCES_MAX_AGE_MS) }?.value?.let { return it }
		val enabled = sourcesRepository.getEnabledSources().filterNot { it.isNsfw() }
		val used = runCatchingCancellable { history.getPopularSources(MAX_SOURCES) }.getOrDefault(emptyList())
		// By name: enabled sources come wrapped with their settings, so they never equal the plain ones from history
		val enabledByName = enabled.associateBy { it.name }
		(used.mapNotNull { enabledByName[it.name] } + enabled).distinctBy { it.name }.take(MAX_SOURCES)
			.also { sources = if (it.isEmpty()) null else Timed(it) }
	}

	/** The reader's taste with what they said they are not interested in counted against it. */
	private suspend fun currentTaste(): Map<String, Double> {
		val taste = runCatchingCancellable { profiles.get().taste }.getOrDefault(emptyMap())
		return DiscoverRanking.applyDismissals(taste, dismissals.get().values)
	}

	private suspend fun knownTitles(): Set<String> =
		runCatchingCancellable { profiles.get().knownTitles }.getOrDefault(emptySet())

	private class Timed<T>(val value: T) {
		private val takenAt = System.currentTimeMillis()

		fun isYoungerThan(ms: Long) = System.currentTimeMillis() - takenAt < ms
	}

	private companion object {

		const val TOP_MAX_AGE_MS = 3 * 60 * 60 * 1000L
		const val NEW_MAX_AGE_MS = 6 * 60 * 60 * 1000L
		const val CATEGORY_MAX_AGE_MS = 12 * 60 * 60 * 1000L
		const val LIKE_MAX_AGE_MS = 24 * 60 * 60 * 1000L

		/** As long as the server keeps it as one answer: asking sooner gets the same thing. */
		const val CHART_MAX_AGE_MS = 15 * 60 * 1000L
		const val SOURCES_MAX_AGE_MS = 5 * 60 * 1000L

		/** Rows of what the reader keeps coming back to: how many sites and pages each may ask. */
		const val MAX_CATEGORY_SOURCES = 4
		const val MAX_CATEGORY_PAGES = 3

		/**
		 * "Because you read" rows. Each costs a site request per title once a day, so there are few, but the
		 * pool they are chosen from is deeper so the screen is not the same every day.
		 */
		const val MAX_LIKE_ROWS = 2
		const val MAX_LIKE_POOL = 7
		const val MAX_LIKE = 8
		const val LIKE_SEED_POOL = 30

		/** Long enough for a site that is merely slow. A blocked one would otherwise hold up the finished list. */
		const val SOURCE_TIMEOUT_MS = 8_000L

		/** Each is a different website, so this burdens none of them, and the first answers show up sooner. */
		const val MAX_PARALLELISM = 6

		/** Every one costs a request per chart, so this is what the first open of the screen costs. */
		const val MAX_SOURCES = 8
		const val MAX_PER_SOURCE = 24
		const val MAX_TOP = 100

		/** How far down one site's chart to go for a list that is still short. */
		const val MAX_TOP_EXTRA_PAGES = 6
		const val MAX_NEW = 36
		const val MAX_FOR_YOU = 24

		/** Deep enough that a narrow genre still has a chart of its own once narrowed down here. */
		const val MAX_CHART = 200

		/** Each costs a details fetch on a source, on top of the chart requests. */
		const val MAX_COMMUNITY_RESOLVED = 10
		const val COMMUNITY_WEIGHT = 1.2
		const val TAG_MATCH_THRESHOLD = 0.4f
		const val MAX_OWN_GENRES = 6

		val COMMON_GENRES = listOf(
			"Action", "Adventure", "Comedy", "Drama", "Fantasy", "Romance", "Isekai", "Reincarnation",
			"Martial arts", "Slice of life", "School life", "Mystery", "Horror", "Psychological",
			"Supernatural", "Sci-fi", "Sports", "Historical", "Thriller", "Villainess",
		)
	}
}
