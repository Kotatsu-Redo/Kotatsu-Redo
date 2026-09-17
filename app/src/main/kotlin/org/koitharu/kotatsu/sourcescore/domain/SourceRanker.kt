package org.koitharu.kotatsu.sourcescore.domain

import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.sourcescore.data.CommunityDatabase
import org.koitharu.kotatsu.sourcescore.data.SourceScoreEntity
import org.koitharu.kotatsu.sourcescore.data.SourceStatsEntity
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** A source with everything needed to order it, resolved once so callers do not re-query per item. */
data class RankedSource(
	val source: MangaSource,
	val composite: Double,
	val isCircuitOpen: Boolean,
	val isProven: Boolean,
	/** Really popular: shown with a flame and placed first. */
	val isHot: Boolean = false,
)

/**
 * Orders sources for the three surfaces that are the point of the whole scoring effort: search,
 * alternatives, and the source list (PLAN.md §5).
 */
@Singleton
class SourceRanker @Inject constructor(
	private val database: CommunityDatabase,
) {

	/** One in this many sweep slots goes to an under-sampled source rather than the best-known one. */
	private val explorationRate = 0.15

	/**
	 * The most popular sources among [sources], for the flame marker in the source list and search.
	 *
	 * The candidates come from every scored source (see [SourceRank.hotSources]); [sources] only
	 * narrows the answer to what the caller is showing.
	 */
	suspend fun trendingSources(sources: Collection<MangaSource>): Set<String> {
		if (sources.isEmpty()) return emptySet()
		val hot = snapshot().hotSources
		return sources.mapNotNullTo(HashSet()) { source -> source.name.takeIf { it in hot } }
	}

	/** Emits whenever the downloaded community scores change. */
	fun observeScores(): Flow<List<SourceScoreEntity>> = database.getSourceScoreDao().observeAll()

	suspend fun snapshot(): RankingSnapshot {
		val scoreList = database.getSourceScoreDao().findAll()
		val scores = scoreList.associateBy { it.source }
		val stats = database.getSourceStatsDao().findAll().associateBy { it.source }
		val median = scores.values
			.filter { it.isProven }
			.map { it.composite.toDouble() }
			.median()
		val hot = SourceRank.hotSources(
			scores = scoreList,
			language = Locale.getDefault().language,
			localeOf = { name -> parserLocales[name] },
		)
		return RankingSnapshot(scores, stats, median, hot)
	}

	private fun List<Double>.median(): Double {
		if (isEmpty()) return DEFAULT_PRIOR
		val sorted = sorted()
		val middle = sorted.size / 2
		return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
	}

	private companion object {

		/** Name to language for every source this build can open; built once, the registry is static. */
		val parserLocales: Map<String, String> by lazy {
			MangaParserSource.entries.associate { it.name to it.locale }
		}

		/**
		 * Used before any score has been downloaded. Deliberately mid-range rather than zero: on a
		 * fresh install every source is unknown, and starting them all at zero would make the ranking
		 * arbitrary instead of neutral.
		 */
		const val DEFAULT_PRIOR = 0.5
	}

	inner class RankingSnapshot(
		private val scores: Map<String, SourceScoreEntity>,
		private val stats: Map<String, SourceStatsEntity>,
		val medianComposite: Double,
		/** Names of the sources that earn the flame - see [SourceRank.hotSources]. */
		val hotSources: Set<String>,
	) {

		fun rank(source: MangaSource, affinity: Double = 0.0, now: Long = System.currentTimeMillis()): RankedSource {
			val score = scores[source.name]
			val stat = stats[source.name]
			return RankedSource(
				source = source,
				composite = SourceRank.composite(score, stat, affinity, medianComposite),
				isCircuitOpen = SourceRank.isCircuitOpen(stat, now),
				isProven = score?.isProven == true,
				isHot = source.name in hotSources,
			)
		}

		/**
		 * Orders sources best-first, then reserves a share of the leading positions for under-sampled
		 * ones.
		 *
		 * Without this the scoring has a starvation bug that would only show up months later: a new
		 * source has no data, so it sorts last, so it is never queried, so it never gets data. The same
		 * trap catches any source recovering from an outage. Epsilon-greedy is the cheapest fix that
		 * actually works, and it costs one slot in a sweep of eight.
		 *
		 * @param random injectable so the behaviour can be tested rather than hoped at.
		 */
		fun order(
			sources: List<MangaSource>,
			affinityOf: (MangaSource) -> Double = { 0.0 },
			now: Long = System.currentTimeMillis(),
			random: Random = Random.Default,
		): List<RankedSource> {
			if (sources.isEmpty()) return emptyList()
			val ranked = sources.map { rank(it, affinityOf(it), now) }

			// Sources whose breaker is open go last rather than being dropped: the caller decides
			// whether to skip them, and a user who explicitly asked to search everything should get
			// everything.
			val (available, tripped) = ranked.partition { !it.isCircuitOpen }
			// Hot sources lead: they are what the user most likely wants, so they are asked first and
			// answer first. Exploration still gets its slots straight after them.
			val byScore = available.sortedWith(
				compareByDescending<RankedSource> { it.isHot }.thenByDescending { it.composite },
			)
			val (hot, rest) = byScore.partition { it.isHot }

			val unproven = rest.filter { !it.isProven }
			if (unproven.isEmpty()) return byScore + tripped

			val explorationSlots = (byScore.size * explorationRate).toInt().coerceAtLeast(1)
			val promoted = unproven.shuffled(random).take(explorationSlots).toSet()
			return hot + promoted.toList() + rest.filterNot { it in promoted } + tripped
		}
	}
}
