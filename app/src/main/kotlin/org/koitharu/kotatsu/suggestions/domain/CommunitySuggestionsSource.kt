package org.koitharu.kotatsu.suggestions.domain

import dagger.Reusable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.MangaLinkResolver
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.discover.domain.DiscoverRanking
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sourcescore.data.CommunityApi
import org.koitharu.kotatsu.sourcescore.data.CommunitySettings
import org.koitharu.kotatsu.sourcescore.data.SimilarWorkDto
import org.koitharu.kotatsu.sourcescore.domain.CommunityWorkResolver
import javax.inject.Inject

/**
 * Suggestions from the community server.
 *
 * The server answers one question only: *what is like this work*. It is asked that about a handful
 * of recently read or favourited titles, and the answers are merged **here**, on the device - so the
 * combination that makes a suggestion personal is never assembled anywhere else, and no profile of
 * anyone's reading exists on the server to be leaked, sold or subpoenaed.
 *
 * What it buys over asking every source for a tag list: the server knows which works are the same
 * work across sources, which sources actually carry them, and genres agreed by many readers rather
 * than by one site's tagging.
 *
 * Every question is about one manga, so it is asked one of two ways. With community features on, by
 * the work the account has resolved, which is the more accurate answer: resolving links the work
 * across sources and feeds the server's genre quorum. Without them, anonymously, by the manga's
 * source and address - no account, nothing stored - for manga other readers have already
 * established. What it cannot do is work offline or for a manga nobody has opened with community
 * features on, hence the local fallback that remains in [org.koitharu.kotatsu.suggestions.ui.SuggestionsWorker].
 */
@Reusable
class CommunitySuggestionsSource @Inject constructor(
	private val settings: CommunitySettings,
	private val api: CommunityApi,
	private val workResolver: CommunityWorkResolver,
	private val linkResolver: MangaLinkResolver,
	private val sourcesRepository: MangaSourcesRepository,
) {

	/**
	 * @param seed what the reader has been reading, most recent first
	 * @param dropped what the reader opened and left early: never suggested, and works like it are
	 *  ranked down rather than out, because leaving one title says little about a whole genre
	 * @return suggestions ranked by how much of the seed they echo, or empty when the server has
	 *  nothing useful - which is a normal answer for a library nobody else has opened yet.
	 */
	suspend fun getSuggestions(
		seed: List<Manga>,
		dropped: List<Manga>,
		limit: Int,
		blacklist: TagsBlacklist,
		excludeNsfw: Boolean,
		taste: Map<String, Double> = emptyMap(),
		isKnown: (SimilarWorkDto) -> Boolean = { false },
	): List<MangaSuggestion> {
		if (seed.isEmpty()) {
			return emptyList()
		}
		val enabledSources = enabledSources(excludeNsfw)
		if (enabledSources.isEmpty()) {
			return emptyList()
		}

		val candidates = gather(seed.filterAskable(excludeNsfw).take(MAX_SEEDS), excludeNsfw)
		if (candidates.isEmpty()) {
			return emptyList()
		}

		val droppedLookups = lookupAll(dropped.filterAskable(excludeNsfw).take(MAX_DROPPED))
		val droppedWorks = droppedLookups.mapTo(HashSet()) { it.workId }
		val likeDropped = HashMap<String, Int>()
		droppedLookups.forEach { lookup ->
			lookup.similar.forEach { likeDropped.merge(it.workId, 1, Int::plus) }
		}

		// The reader already has these: suggesting what is open in their library is noise.
		val seen = (seed + dropped).mapTo(HashSet()) { it.source.name to it.url }
		val ranked = candidates.values
			.filter { candidate -> candidate.dto.sources.none { (it.source to it.key) in seen } }
			// Before anything is fetched: the rest of their library is no suggestion either, and each one costs a request
			.filterNot { isKnown(it.dto) }
			.filter { candidate -> candidate.dto.sources.any { it.source in enabledSources } }
			.filter { it.dto.workId !in droppedWorks }
			.map { candidate ->
				val hits = likeDropped[candidate.dto.workId] ?: 0
				candidate.copy(
					// Here on the device, as the server is never told what this reader likes
					score = DiscoverRanking.personalize(
						SuggestionRanking.dampPopularity(
							SuggestionRanking.penalizeDropped(candidate.score, hits),
							candidate.dto.readers,
						),
						candidate.dto.genres,
						taste,
					),
				)
			}
		val best = SuggestionRanking.diversify(ranked, MAX_CANDIDATES)
		val topScore = best.firstOrNull()?.score ?: return emptyList()

		val manga = findManga(best.map { it.dto }, enabledSources, MAX_CANDIDATES, BACKGROUND_PARALLELISM)
		val resolved = best.mapIndexedNotNull { index, candidate ->
			manga[index]?.let {
				MangaSuggestion(
					manga = it,
					relevance = (candidate.score / topScore).toFloat().coerceIn(0f, 1f),
				)
			}
		}
		return resolved
			.filter { it.manga !in blacklist }
			.filter { !excludeNsfw || !it.manga.isNsfw() }
			.distinctBy { it.manga.id }
			.take(limit)
	}

	/**
	 * Works like this one, for the details screen's own "related" row.
	 *
	 * The per-manga case the server fits best: one work in, similar works out, no blending needed.
	 * A source's own related list is whatever that one site decided to link; this is every reader's
	 * genres across every source that carries the work.
	 *
	 * The manga's own source is preferred when it carries a result, so opening a suggestion keeps
	 * the reader where they already are.
	 */
	suspend fun getSimilarTo(
		seed: Manga,
		limit: Int,
		excludeNsfw: Boolean,
		maxFetches: Int = limit,
		taste: Map<String, Double> = emptyMap(),
		isKnown: (SimilarWorkDto) -> Boolean = { false },
	): List<Manga> {
		// Each result costs one details fetch on its source, and this runs when a details screen
		// opens, so the list is cut here rather than after the network work is already done.
		@Suppress("NAME_SHADOWING") val limit = limit.coerceAtMost(MAX_RELATED)
		if (excludeNsfw && seed.isNsfw()) {
			return emptyList()
		}
		val enabledSources = enabledSources(excludeNsfw)
		if (enabledSources.isEmpty()) {
			return emptyList()
		}
		val similar = runCatchingCancellable { lookup(seed)?.similar }
			.onFailure { it.printStackTraceDebug() }
			.getOrNull()
			.orEmpty()
			.filter { !excludeNsfw || !it.nsfw }
			.filterNot(isKnown)
			// Before the picking: only so many get picked, and one nobody could open would take up a place
			.filter { work -> work.sources.any { it.source in enabledSources } }
		val picked = SuggestionRanking.diversify(
			similar.map {
				RankedWork(it, DiscoverRanking.personalize(SuggestionRanking.dampPopularity(it.weight, it.readers), it.genres, taste))
			},
			limit,
		)
		if (picked.isEmpty()) {
			return emptyList()
		}

		return findManga(picked.map { it.dto }, enabledSources, maxFetches, RELATED_PARALLELISM, seed.source.name)
			.filterNotNull()
			.filter { it.id != seed.id }
			.distinctBy { it.id }
	}

	/** The first [limit] works from a chart that the reader can open, as manga. Never anything adult. */
	suspend fun resolveChart(works: List<SimilarWorkDto>, limit: Int): Map<String, Manga> {
		val enabledSources = enabledSources(excludeNsfw = true)
		if (enabledSources.isEmpty()) {
			return emptyMap()
		}
		val picked = works.asSequence()
			.filter { work -> !work.nsfw && work.sources.any { it.source in enabledSources } }
			.take(limit)
			.toList()
		val manga = findManga(picked, enabledSources, limit, MAX_PARALLELISM)
		return picked.indices.mapNotNull { index -> manga[index]?.let { picked[index].workId to it } }.toMap()
	}

	private suspend fun enabledSources(excludeNsfw: Boolean): Map<String, MangaSource> =
		sourcesRepository.getEnabledSources()
			.associateBy { it.name }
			.filterValues { !excludeNsfw || !it.isNsfw() }

	/**
	 * What the server says is like [manga], and the work it took [manga] to be.
	 *
	 * With community features on, by the account's resolved work: usually a read of the local
	 * `work_ref` cache, which every opened manga already populates, and one resolve call otherwise.
	 * Without them, the anonymous lookup by source and address. A local file is nothing the server
	 * could know, so it is never asked about.
	 */
	private suspend fun lookup(manga: Manga): Lookup? {
		if (manga.isLocal) {
			return null
		}
		return if (settings.isEnabled) {
			val workId = workResolver.workIdFor(manga) ?: return null
			Lookup(workId, api.fetchSimilar(workId))
		} else {
			api.fetchSimilarPublic(manga.source.name, manga.url)?.let { Lookup(it.workId, it.similar) }
		}
	}

	/** One lookup per manga, in parallel; the same work read on two sources is answered once. */
	private suspend fun lookupAll(manga: List<Manga>): List<Lookup> {
		val semaphore = Semaphore(MAX_PARALLELISM)
		return coroutineScope {
			manga.map { item ->
				async {
					semaphore.withPermit {
						runCatchingCancellable { lookup(item) }
							.onFailure { it.printStackTraceDebug() }
							.getOrNull()
					}
				}
			}.awaitAll()
		}.filterNotNull()
			.distinctBy { it.workId }
	}

	private suspend fun gather(seed: List<Manga>, excludeNsfw: Boolean): Map<String, RankedWork> {
		val answers = lookupAll(seed)
		val seeds = answers.mapTo(HashSet()) { it.workId }
		val candidates = HashMap<String, RankedWork>()
		answers.forEachIndexed { index, (_, similar) ->
			// Earlier seeds are what the reader touched most recently, so let them weigh more.
			val seedWeight = 1.0 / (1.0 + index)
			similar.forEach { dto ->
				if (dto.workId in seeds) return@forEach
				if (excludeNsfw && dto.nsfw) return@forEach
				if (dto.sources.isEmpty()) return@forEach
				val existing = candidates[dto.workId]
				candidates[dto.workId] = RankedWork(
					dto = existing?.dto ?: dto,
					// Agreement across several seeds is the strongest signal available here, so the
					// scores add rather than compete. The server's weight is used rather than the
					// count of shared genres: two works sharing `villainess` are far more alike than
					// two sharing `action`, and counting alone cannot tell them apart. An older
					// server sends no weight, so fall back to the count it does send.
					score = (existing?.score ?: 0.0) + dto.weight * seedWeight,
				)
			}
		}
		return candidates
	}

	/**
	 * Manga worth asking the server about: anything but local files, and nothing adult when the reader
	 * has asked for no adult suggestions - excluding them from the answer is not enough, because the
	 * question itself says what is being read.
	 */
	private fun List<Manga>.filterAskable(excludeNsfw: Boolean): List<Manga> =
		filter { !it.isLocal && !(excludeNsfw && it.isNsfw()) }

	/**
	 * Turns works into something openable: a source the reader has enabled, then that source's own
	 * entry for each, fetched by the exact address the server matched rather than searched by title,
	 * unless this device has it already.
	 *
	 * @return one entry for each of [works], null for those it could not open
	 */
	private suspend fun findManga(
		works: List<SimilarWorkDto>,
		enabledSources: Map<String, MangaSource>,
		maxFetches: Int,
		parallelism: Int,
		preferred: String? = null,
	): List<Manga?> = resolveWorks(
		works = works,
		maxFetches = maxFetches,
		parallelism = parallelism,
		addresses = { dto ->
			val ordered = if (preferred == null) dto.sources else dto.sources.sortedByDescending { it.source == preferred }
			ordered.mapNotNull { address ->
				enabledSources[address.source]?.takeUnless { it == UnknownMangaSource }?.let { WorkAddress(it, address.key) }
			}
		},
		// What this device has, then what the server can say about it: either way the site is spared a request
		known = { dto, address ->
			linkResolver.findKnown(address.source, address.key, dto.title)
				?: dto.sources.firstOrNull { it.source == address.source.name && it.key == address.key }
					?.coverUrl
					?.takeIf { it.startsWith("https://", ignoreCase = true) }
					?.let { linkResolver.fromServer(address.source, address.key, dto.title, it, dto.genres) }
		},
		fetch = { dto, address -> linkResolver.resolveExact(address.source, address.key, dto.title) },
		isOnlyThisPageMissing = { it is NotFoundException },
	)

	private data class Lookup(val workId: String, val similar: List<SimilarWorkDto>)

	/** The server's rarity weight, or the shared-genre count when talking to an older server. */
	private val SimilarWorkDto.weight: Double
		get() = if (score > 0.0) score else sharedGenres.toDouble()

	/** Accounts seen reading the work, summed over its sources - how widely read it is. */
	private val SimilarWorkDto.readers: Int
		get() = sources.sumOf { it.reporters }

	private companion object {
		/** Enough to describe a taste; more would be a slow worker and a bigger request burst. */
		const val MAX_SEEDS = 8

		/** Each one costs a details fetch on its source, so the list is cut before it is resolved. */
		const val MAX_CANDIDATES = 60

		/** The details screen shows a row, not a catalogue, and it must open promptly. */
		const val MAX_RELATED = 8
		const val MAX_PARALLELISM = 3

		/** The row can wait. What the reader opened, on the same site more often than not, cannot. */
		const val RELATED_PARALLELISM = 2

		/** Nobody is waiting for it, and the reader may start using the app while it runs. */
		const val BACKGROUND_PARALLELISM = 2

		/** A few is enough to recognise what the reader turns away from, and each costs a lookup. */
		const val MAX_DROPPED = 3
	}
}
