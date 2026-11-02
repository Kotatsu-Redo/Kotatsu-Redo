package org.koitharu.kotatsu.suggestions.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedPickingTest {

	@Test
	fun `recent, finished and favourite titles say the most`() {
		val base = SuggestionRanking.seedWeight(0, 0.2f, isFavourite = false)
		assertTrue(SuggestionRanking.seedWeight(0, 0.9f, isFavourite = false) > base)
		assertTrue(SuggestionRanking.seedWeight(0, 0.2f, isFavourite = true) > base)
		assertTrue(SuggestionRanking.seedWeight(10, 0.2f, isFavourite = false) < base)
	}

	@Test
	fun `seeds are not all alike`() {
		val picked = SuggestionRanking.pickSeeds(
			listOf(
				SeedCandidate("villainess 1", setOf("villainess", "romance"), 1.0),
				SeedCandidate("villainess 2", setOf("villainess", "romance"), 0.95),
				SeedCandidate("action", setOf("action", "martial arts"), 0.8),
			),
			2,
		)
		assertEquals(listOf("villainess 1", "action"), picked)
	}

	@Test
	fun `fewer candidates than asked for are all kept`() {
		assertEquals(listOf("a"), SuggestionRanking.pickSeeds(listOf(SeedCandidate("a", emptySet(), 1.0)), 5))
		assertTrue(SuggestionRanking.pickSeeds(emptyList<SeedCandidate<String>>(), 5).isEmpty())
	}
}
