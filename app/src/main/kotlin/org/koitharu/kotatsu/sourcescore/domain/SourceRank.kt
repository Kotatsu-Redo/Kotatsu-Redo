package org.koitharu.kotatsu.sourcescore.domain

import org.koitharu.kotatsu.sourcescore.data.SourceScoreEntity
import org.koitharu.kotatsu.sourcescore.data.SourceStatsEntity
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How a source earns its place in the queue.
 *
 * Pure functions with no Android or database dependency, so the ranking can be unit tested - which
 * matters more here than almost anywhere else in the app, because a regression would show up as
 * "search feels worse" rather than as a crash.
 */
object SourceRank {

	private const val GLOBAL_STABILITY_WEIGHT = 0.40
	private const val GLOBAL_POPULARITY_WEIGHT = 0.20
	private const val LOCAL_STABILITY_WEIGHT = 0.30
	private const val AFFINITY_WEIGHT = 0.10

	/** Local confidence half-point: at 10 local observations the device trusts itself as much as the crowd. */
	private const val LOCAL_CONFIDENCE_PIVOT = 10.0

	const val CIRCUIT_BREAKER_THRESHOLD = 5
	private const val BREAKER_BASE_MINUTES = 2L
	private const val BREAKER_MAX_MINUTES = 6L * 60L

	/**
	 * Wilson lower bound, matching the server's stability maths.
	 *
	 * Without it a source that succeeded once and never failed would score 1.0 and jump ahead of one
	 * with hundreds of successes - locally that happens constantly, because most sources have only
	 * been touched a handful of times.
	 */
	fun wilsonLowerBound(successes: Int, total: Int, z: Double = 1.96): Double {
		if (total <= 0 || successes < 0) return 0.0
		val p = (successes.toDouble() / total).coerceIn(0.0, 1.0)
		val z2 = z * z
		val denominator = 1.0 + z2 / total
		val centre = p + z2 / (2.0 * total)
		val margin = z * sqrt((p * (1.0 - p) + z2 / (4.0 * total)) / total)
		return ((centre - margin) / denominator).coerceIn(0.0, 1.0)
	}

	fun localStability(stats: SourceStatsEntity?): Double {
		if (stats == null || stats.total == 0) return 0.0
		val success = wilsonLowerBound(stats.okCount, stats.total)
		val latency = (1.0 - (stats.latencyEmaMs - 800.0) / 5000.0).coerceIn(0.0, 1.0)
		val emptyPenalty = (stats.emptyCount.toDouble() / stats.okCount.coerceAtLeast(1)).coerceIn(0.0, 1.0)
		val cfPenalty = (stats.cfCount.toDouble() / stats.total).coerceIn(0.0, 1.0)
		return ((0.55 * success + 0.25 * latency) / 0.80 * (1.0 - 0.7 * cfPenalty) * (1.0 - 0.4 * emptyPenalty))
			.coerceIn(0.0, 1.0)
	}

	/**
	 * How much to trust this device's own history over the crowd's, as a Bayesian blend rather than a
	 * threshold: a fresh install leans entirely on the global score, a heavy user's own experience
	 * takes over gradually. No cliff, no special-casing of the first run.
	 */
	fun localConfidence(stats: SourceStatsEntity?): Double {
		val n = (stats?.total ?: 0).toDouble()
		return n / (n + LOCAL_CONFIDENCE_PIVOT)
	}

	/**
	 * @param medianComposite the server's median, used as an optimistic prior for sources with too
	 * little data. This is what breaks the starvation loop: unproven means unproven, not bad, so a new
	 * source still gets queried often enough to earn a real score.
	 */
	fun composite(
		score: SourceScoreEntity?,
		stats: SourceStatsEntity?,
		affinity: Double,
		medianComposite: Double,
	): Double {
		val proven = score?.takeIf { it.isProven }
		val globalStability = proven?.stability?.toDouble() ?: medianComposite
		val globalPopularity = proven?.popularity?.toDouble() ?: medianComposite

		val confidence = localConfidence(stats)
		// Below any local evidence this collapses to the global value, so the term never drags a
		// source down merely for being unfamiliar to this device.
		val local = localStability(stats) * confidence + globalStability * (1.0 - confidence)

		return (
			GLOBAL_STABILITY_WEIGHT * globalStability +
				GLOBAL_POPULARITY_WEIGHT * globalPopularity +
				LOCAL_STABILITY_WEIGHT * local +
				AFFINITY_WEIGHT * affinity.coerceIn(0.0, 1.0)
			).coerceIn(0.0, 1.0)
	}

	/**
	 * Exponential backoff after repeated failures, capped at six hours.
	 *
	 * Returns the instant at which the source may be tried again, or 0 when it is not tripped.
	 */
	fun breakerOpenUntil(stats: SourceStatsEntity?): Long {
		if (stats == null || stats.consecutiveFailures < CIRCUIT_BREAKER_THRESHOLD) return 0L
		val overshoot = stats.consecutiveFailures - CIRCUIT_BREAKER_THRESHOLD
		val minutes = min(BREAKER_BASE_MINUTES * 2.0.pow(overshoot).toLong(), BREAKER_MAX_MINUTES)
		return stats.lastFailAt + minutes * 60_000L
	}

	fun isCircuitOpen(stats: SourceStatsEntity?, now: Long): Boolean = breakerOpenUntil(stats) > now

	/** Ten is enough to be useful and few enough that the flame still means something. */
	const val HOT_LIMIT = 10

	/**
	 * Popularity is log-scaled against the busiest source in the region, so 0.8 keeps the flame
	 * to the genuinely big sources. Without a floor, a region with little traffic would put a flame
	 * on whatever happened to be used twice.
	 */
	const val HOT_MIN_POPULARITY = 0.8

	/**
	 * The "really popular" sources that earn the flame and sort first.
	 *
	 * Chosen from **every** scored source, not from the ones a caller happens to show. Picking from
	 * the visible list meant a user with three obscure sources enabled saw a flame on all three.
	 *
	 * Sources in the user's own language are preferred, then the rest fill the remaining places:
	 * ranked globally, every flame would land on the big English sources and tell a French or
	 * Indonesian reader nothing. Unproven scores never qualify, so a flame never means "no idea".
	 *
	 * @param localeOf the source's language, or null when this build has no such source - a score
	 * for a source the app cannot open must not take one of the places.
	 */
	fun hotSources(
		scores: Collection<SourceScoreEntity>,
		language: String,
		localeOf: (String) -> String?,
		limit: Int = HOT_LIMIT,
		minPopularity: Double = HOT_MIN_POPULARITY,
	): Set<String> {
		val candidates = scores
			.filter { it.isProven && it.popularity >= minPopularity }
			.mapNotNull { score -> localeOf(score.source)?.let { locale -> score to locale } }
		return candidates
			.sortedWith(
				compareByDescending<Pair<SourceScoreEntity, String>> { (_, locale) -> locale == language }
					.thenByDescending { (score, _) -> score.popularity }
					.thenBy { (score, _) -> score.source },
			)
			.take(limit)
			.mapTo(HashSet()) { (score, _) -> score.source }
	}
}
