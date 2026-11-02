package org.koitharu.kotatsu.suggestions.domain

import org.koitharu.kotatsu.sourcescore.data.SimilarWorkDto
import java.time.Duration
import java.time.Instant
import kotlin.math.ln
import kotlin.math.pow

/** A work the server suggested, with the score it has on this device so far. */
internal data class RankedWork(val dto: SimilarWorkDto, val score: Double)

/** Something the reader has read, as a place to start suggesting from. */
internal class SeedCandidate<T>(val item: T, val tags: Set<String>, val weight: Double)

/**
 * The ranking decisions made on the device, kept free of Android and network code so each one can be
 * tested on its own.
 */
internal object SuggestionRanking {

	/** How much a full genre overlap with an earlier pick costs: half its score at most. */
	const val DIVERSITY = 0.5

	/** Re-ranking looks this many times deeper than it returns, so variety has something to pick. */
	const val DIVERSITY_POOL = 3

	/** Per dropped manga a candidate is like: two of them make it a quarter as likely. */
	const val DROPPED_PENALTY = 0.5

	/** Under a tenth read... */
	const val DROPPED_MAX_PERCENT = 0.1f

	/** ...and untouched this long counts as left, not merely paused. */
	val DROPPED_AFTER: Duration = Duration.ofDays(21)

	/**
	 * The best [count] of [candidates], re-ranked so the list is not twenty variations of one thing.
	 *
	 * Greedy maximal marginal relevance: each pick's score is discounted by how much its genres
	 * overlap the closest pick already made, so a slightly weaker match with something new beats a
	 * near-copy of what is already listed. Genre overlap stands in for similarity because genres are
	 * what the scores are made of. The discounted scores come out in descending order, so they are
	 * also what the stored relevance - and therefore the displayed order - is taken from.
	 */
	fun diversify(candidates: Collection<RankedWork>, count: Int): List<RankedWork> {
		val pool = candidates.sortedByDescending { it.score }.take(count * DIVERSITY_POOL).toMutableList()
		val picked = ArrayList<RankedWork>(minOf(count, pool.size))
		while (pool.isNotEmpty() && picked.size < count) {
			var bestIndex = 0
			var bestScore = Double.NEGATIVE_INFINITY
			pool.forEachIndexed { index, candidate ->
				val overlap = picked.maxOfOrNull { genreOverlap(it.dto, candidate.dto) } ?: 0.0
				val score = candidate.score * (1.0 - DIVERSITY * overlap)
				if (score > bestScore) {
					bestScore = score
					bestIndex = index
				}
			}
			picked += pool.removeAt(bestIndex).copy(score = bestScore)
		}
		return picked
	}

	/** Jaccard overlap of two works' genres; nothing in common when either has none. */
	fun genreOverlap(a: SimilarWorkDto, b: SimilarWorkDto): Double {
		if (a.genres.isEmpty() || b.genres.isEmpty()) {
			return 0.0
		}
		val shared = a.genres.count { it in b.genres }
		return shared.toDouble() / (a.genres.size + b.genres.size - shared)
	}

	/**
	 * [score] for a work like [droppedHits] of the manga the reader dropped: ranked down rather than
	 * out, because leaving one title says little about a whole genre.
	 */
	fun penalizeDropped(score: Double, droppedHits: Int): Double =
		if (droppedHits <= 0) score else score * DROPPED_PENALTY.pow(droppedHits)

	/**
	 * Opened, barely read and left for weeks. [percent] is the reading progress from history, which
	 * is negative when unknown - and unknown is not evidence of anything.
	 */
	fun isDropped(percent: Float, updatedAt: Instant, now: Instant): Boolean =
		percent in 0f..DROPPED_MAX_PERCENT && Duration.between(updatedAt, now) > DROPPED_AFTER

	/** How fast reading something longer ago stops saying what they want now: half as much 10 titles back. */
	private const val SEED_RECENCY_SPAN = 10.0
	private const val SEED_FINISHED_BONUS = 1.5
	private const val SEED_FAVOURITE_BONUS = 1.3
	private const val SEED_MIN_FINISHED = 0.5f

	/** Pages read for a title to count twice as much as one merely opened. */
	private const val ENGAGEMENT_PAGES = 200.0
	private const val ENGAGEMENT_MAX = 3.0

	/** Roughly, and only used to compare titles with each other. */
	private const val PAGES_PER_CHAPTER = 25

	/** How long until something read says half as much about what they want now, and how little it can come to. */
	private const val FADE_HALF_LIFE_DAYS = 180.0
	private const val FADE_FLOOR = 0.25

	/**
	 * How much time spent on a title says, beyond having opened it. Percent read cannot say this: a third of a
	 * thousand chapters is far more of someone's time than all of a short one. Grows slowly and stops, so that
	 * the one enormous series somebody has been on for years does not become all there is to them.
	 */
	fun engagement(pagesRead: Int): Double =
		(1.0 + ln(1.0 + pagesRead.coerceAtLeast(0) / ENGAGEMENT_PAGES) / ln(2.0)).coerceAtMost(ENGAGEMENT_MAX)

	/**
	 * Pages read of a title, as well as can be told. Reading statistics are off unless turned on, so for most
	 * people the only record is how far through how many chapters they are. Never less than either says.
	 */
	fun pagesRead(recorded: Int, percent: Float?, chapters: Int?): Int {
		val estimated = if (percent != null && chapters != null && chapters > 0) {
			(percent.coerceIn(0f, 1f) * chapters * PAGES_PER_CHAPTER).toInt()
		} else {
			0
		}
		return maxOf(recorded, estimated)
	}

	/** What they read long ago still says something about them, only less than what they are reading now. */
	fun fade(age: Duration): Double =
		0.5.pow(age.toHours().coerceAtLeast(0) / 24.0 / FADE_HALF_LIFE_DAYS).coerceAtLeast(FADE_FLOOR)

	/**
	 * How much one title has to say as a place to start.
	 *
	 * @param recency how many others they have read since
	 * @param percent how far they got, negative if not known
	 * @param pagesRead how many pages of it they have read, 0 if not known
	 */
	fun seedWeight(recency: Int, percent: Float, isFavourite: Boolean, pagesRead: Int = 0): Double {
		var weight = 1.0 / (1.0 + recency / SEED_RECENCY_SPAN)
		if (percent >= SEED_MIN_FINISHED) weight *= SEED_FINISHED_BONUS
		if (isFavourite) weight *= SEED_FAVOURITE_BONUS
		return weight * engagement(pagesRead)
	}

	/**
	 * The best places to start from, best first, and not all alike. Only so many can be asked about, and eight
	 * villainess titles in a row would be asking the same question eight times, while they also read action.
	 */
	fun <T> pickSeeds(candidates: List<SeedCandidate<T>>, limit: Int): List<T> {
		val pool = candidates.toMutableList()
		val picked = ArrayList<SeedCandidate<T>>(minOf(limit, pool.size))
		while (pool.isNotEmpty() && picked.size < limit) {
			var bestIndex = 0
			var bestScore = Double.NEGATIVE_INFINITY
			pool.forEachIndexed { index, candidate ->
				val overlap = picked.maxOfOrNull { tagOverlap(it.tags, candidate.tags) } ?: 0.0
				val score = candidate.weight * (1.0 - DIVERSITY * overlap)
				if (score > bestScore) {
					bestScore = score
					bestIndex = index
				}
			}
			picked += pool.removeAt(bestIndex)
		}
		return picked.map { it.item }
	}

	private fun tagOverlap(a: Set<String>, b: Set<String>): Double {
		if (a.isEmpty() || b.isEmpty()) {
			return 0.0
		}
		val shared = a.count { it in b }
		return shared.toDouble() / (a.size + b.size - shared)
	}

	/** How strongly being widely read counts against a work - see [dampPopularity]. */
	const val POPULARITY_DAMPING = 0.2

	/** Tag titles compared across sources: `Action`, ` action ` and `ACTION` are one tag. */
	fun normalizeTag(title: String): String = title.trim().lowercase()

	/**
	 * The reader's most telling tags, most telling first: frequent in what they read and rare in
	 * everything else - TF-IDF, with [rarity] as the inverse document frequency.
	 *
	 * Ranking by frequency alone always crowns `action` or `fantasy`, and searching a source for those
	 * returns its most popular titles rather than anything close to this reader. A tag on only one of
	 * the reader's manga comes after every recurring one however rare it is: one title's odd tag is
	 * not a taste.
	 *
	 * @param seedTags the tag titles of every manga read, one entry per manga carrying it
	 * @return original titles, as the sources spell them
	 */
	fun distinctiveTags(seedTags: List<String>, rarity: (String) -> Double, limit: Int): List<String> {
		val counts = LinkedHashMap<String, Pair<String, Int>>()
		seedTags.forEach { title ->
			val key = normalizeTag(title)
			if (key.isEmpty()) return@forEach
			val existing = counts[key]
			counts[key] = (existing?.first ?: title) to ((existing?.second ?: 0) + 1)
		}
		val (recurring, once) = counts.values.partition { it.second >= 2 }
		fun rank(tags: List<Pair<String, Int>>) = tags
			.sortedByDescending { (title, count) -> count * rarity(title) }
			.map { it.first }
		return (rank(recurring) + rank(once)).take(limit)
	}

	/**
	 * Overlap of two tag sets with each tag weighted by its [rarity]: sharing `villainess` counts for
	 * far more than sharing `action`, and a manga carrying thirty tags does not win by sheer number.
	 */
	fun weightedOverlap(a: Set<String>, b: Set<String>, rarity: (String) -> Double): Double {
		if (a.isEmpty() || b.isEmpty()) {
			return 0.0
		}
		val shared = a.sumOf { if (it in b) rarity(it) else 0.0 }
		val union = a.sumOf(rarity) + b.sumOf { if (it in a) 0.0 else rarity(it) }
		return if (union > 0.0) shared / union else 0.0
	}

	/**
	 * [score] discounted by how widely read the work is, [readers] being the accounts the server has
	 * seen it with. Mild and logarithmic: a close match that is also popular still shows, but
	 * popularity alone no longer lifts a looser match above a closer, less known one.
	 */
	fun dampPopularity(score: Double, readers: Int): Double =
		score / (1.0 + POPULARITY_DAMPING * ln(1.0 + readers.coerceAtLeast(0)))
}
