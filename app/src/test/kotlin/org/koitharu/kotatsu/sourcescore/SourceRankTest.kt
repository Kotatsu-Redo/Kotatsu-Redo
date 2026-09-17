package org.koitharu.kotatsu.sourcescore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.sourcescore.data.SourceScoreEntity
import org.koitharu.kotatsu.sourcescore.data.SourceStatsEntity
import org.koitharu.kotatsu.sourcescore.domain.SourceRank

/**
 * The ranking maths. Worth testing carefully because a regression here surfaces as "search feels
 * worse" rather than as a crash, which is the kind of bug that survives for months.
 */
class SourceRankTest {

	private fun stats(
		ok: Int = 0,
		fail: Int = 0,
		empty: Int = 0,
		cf: Int = 0,
		latency: Int = 700,
		consecutiveFailures: Int = 0,
		lastFailAt: Long = 0L,
	) = SourceStatsEntity(
		source = "SRC",
		okCount = ok,
		failCount = fail,
		emptyCount = empty,
		cfCount = cf,
		latencyEmaMs = latency,
		lastOkAt = 0L,
		lastFailAt = lastFailAt,
		consecutiveFailures = consecutiveFailures,
	)

	private fun score(stability: Float, popularity: Float, samples: Int = 50) = SourceScoreEntity(
		source = "SRC",
		stability = stability,
		popularity = popularity,
		composite = (stability + popularity) / 2f,
		samples = samples,
		updatedAt = 0L,
	)

	@Test
	fun `one lucky success does not outrank a proven source`() {
		val lucky = SourceRank.wilsonLowerBound(1, 1)
		val proven = SourceRank.wilsonLowerBound(400, 402)

		assertTrue("1/1 ($lucky) must not outrank 400/402 ($proven)", lucky < proven)
	}

	@Test
	fun `local stability punishes a source that fails for this device`() {
		val working = SourceRank.localStability(stats(ok = 100, fail = 1))
		val brokenHere = SourceRank.localStability(stats(ok = 2, fail = 98))

		assertTrue("$brokenHere should be well below $working", brokenHere < working / 2)
	}

	@Test
	fun `a source returning empty results is penalised locally too`() {
		val fine = SourceRank.localStability(stats(ok = 50))
		val empty = SourceRank.localStability(stats(ok = 50, empty = 50))

		assertTrue("empty-returning ($empty) must rank below working ($fine)", empty < fine)
	}

	// -- the blend -------------------------------------------------------------------------------

	/**
	 * A fresh install has no local history, so the global score must carry the whole weight. If local
	 * data counted immediately, every source would look broken on day one.
	 */
	@Test
	fun `with no local history the global score decides`() {
		val composite = SourceRank.composite(
			score = score(stability = 0.9f, popularity = 0.8f),
			stats = null,
			affinity = 0.0,
			medianComposite = 0.5,
		)
		assertTrue("expected a high composite for a healthy source, got $composite", composite > 0.75)
	}

	@Test
	fun `local confidence grows with evidence`() {
		assertEquals(0.0, SourceRank.localConfidence(null), 1e-9)
		assertTrue(SourceRank.localConfidence(stats(ok = 10)) > 0.4)
		assertTrue(SourceRank.localConfidence(stats(ok = 1000)) > 0.98)
	}

	/**
	 * The case a purely server-side score gets wrong: globally healthy, broken here. Geo-blocks and
	 * ISP-level DNS blocks look exactly like this.
	 */
	@Test
	fun `a source that is healthy globally but broken locally is demoted`() {
		val healthyEverywhere = SourceRank.composite(
			score = score(0.95f, 0.9f),
			stats = stats(ok = 200, fail = 1),
			affinity = 0.0,
			medianComposite = 0.5,
		)
		val brokenForMe = SourceRank.composite(
			score = score(0.95f, 0.9f),
			stats = stats(ok = 1, fail = 200),
			affinity = 0.0,
			medianComposite = 0.5,
		)

		assertTrue(
			"broken-for-me ($brokenForMe) must rank below healthy ($healthyEverywhere)",
			brokenForMe < healthyEverywhere,
		)
	}

	/**
	 * The starvation loop: an unproven source must score near the median, not near zero, or it is
	 * never queried, so it never earns data, so it is never queried.
	 */
	@Test
	fun `an unproven source scores at the median rather than at zero`() {
		val unproven = SourceRank.composite(
			score = score(0.9f, 0.9f, samples = 1),
			stats = null,
			affinity = 0.0,
			medianComposite = 0.6,
		)
		assertTrue("unproven source scored $unproven, expected near the 0.6 median", unproven > 0.45)
	}

	@Test
	fun `a completely unknown source is not scored as bad`() {
		val unknown = SourceRank.composite(null, null, affinity = 0.0, medianComposite = 0.5)
		assertTrue("unknown source scored $unknown; unknown must not mean bad", unknown > 0.4)
	}

	@Test
	fun `affinity breaks ties between equally reliable sources`() {
		val matching = SourceRank.composite(score(0.8f, 0.8f), null, affinity = 1.0, medianComposite = 0.5)
		val unrelated = SourceRank.composite(score(0.8f, 0.8f), null, affinity = 0.0, medianComposite = 0.5)

		assertTrue("same-language source ($matching) should edge out ($unrelated)", matching > unrelated)
	}

	// -- circuit breaker -------------------------------------------------------------------------

	@Test
	fun `the breaker stays closed below the failure threshold`() {
		val now = 1_000_000L
		assertFalse(SourceRank.isCircuitOpen(stats(consecutiveFailures = 4, lastFailAt = now), now))
	}

	@Test
	fun `the breaker opens after repeated failures and reopens later`() {
		val now = 1_000_000L
		val tripped = stats(consecutiveFailures = 5, lastFailAt = now)

		assertTrue(SourceRank.isCircuitOpen(tripped, now))
		assertFalse("should close again once the backoff elapses", SourceRank.isCircuitOpen(tripped, now + 60 * 60_000L))
	}

	@Test
	fun `backoff grows with consecutive failures but is capped`() {
		val now = 1_000_000L
		val few = SourceRank.breakerOpenUntil(stats(consecutiveFailures = 5, lastFailAt = now))
		val many = SourceRank.breakerOpenUntil(stats(consecutiveFailures = 9, lastFailAt = now))
		val absurd = SourceRank.breakerOpenUntil(stats(consecutiveFailures = 100, lastFailAt = now))

		assertTrue(many > few)
		assertTrue("cap is six hours", absurd - now <= 6 * 60 * 60_000L)
	}

	/** A single success must clear the breaker: a recovered source should be usable immediately. */
	@Test
	fun `a success closes the breaker`() {
		assertFalse(SourceRank.isCircuitOpen(stats(ok = 1, consecutiveFailures = 0), 1_000_000L))
	}
}
