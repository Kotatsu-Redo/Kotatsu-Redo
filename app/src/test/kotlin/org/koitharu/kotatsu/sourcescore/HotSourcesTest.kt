package org.koitharu.kotatsu.sourcescore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.sourcescore.data.SourceScoreEntity
import org.koitharu.kotatsu.sourcescore.domain.SourceRank

/** Which sources earn the flame and sort first. */
class HotSourcesTest {

	private fun score(source: String, popularity: Float, samples: Int = 50) = SourceScoreEntity(
		source = source,
		stability = 0.5f,
		popularity = popularity,
		composite = 0.5f,
		samples = samples,
		updatedAt = 0L,
	)

	private val locales = mapOf(
		"EN_BIG" to "en", "EN_MID" to "en", "EN_TAIL" to "en",
		"FR_BIG" to "fr", "FR_MID" to "fr",
		"MULTI" to "",
	)

	private fun hot(scores: List<SourceScoreEntity>, language: String = "en", limit: Int = SourceRank.HOT_LIMIT) =
		SourceRank.hotSources(scores, language, { locales[it] }, limit)

	@Test
	fun `the long tail never gets a flame however few sources there are`() {
		// The old behaviour picked the top ten of whatever was on screen, so three obscure sources
		// all got flames.
		val result = hot(listOf(score("EN_TAIL", 0.2f), score("FR_MID", 0.79f)))

		assertTrue(result.isEmpty())
	}

	@Test
	fun `unproven scores never qualify`() {
		val result = hot(listOf(score("EN_BIG", 1.0f, samples = SourceScoreEntity.MIN_TRUSTED_SAMPLES - 1)))

		assertFalse("EN_BIG" in result)
	}

	@Test
	fun `a score for a source this build cannot open takes no place`() {
		val result = hot(listOf(score("REMOVED_SOURCE", 1.0f), score("EN_MID", 0.85f)), limit = 1)

		assertEquals(setOf("EN_MID"), result)
	}

	@Test
	fun `the reader's own language is preferred before the rest fill in`() {
		val scores = listOf(score("EN_BIG", 1.0f), score("EN_MID", 0.9f), score("FR_MID", 0.82f))

		assertEquals(setOf("FR_MID", "EN_BIG"), hot(scores, language = "fr", limit = 2))
		assertEquals(setOf("EN_BIG", "EN_MID"), hot(scores, language = "en", limit = 2))
	}

	@Test
	fun `the limit keeps the flame meaningful`() {
		val scores = (1..30).map { i -> score("S$i", 0.8f + i / 200f) }
		val result = SourceRank.hotSources(scores, "en", { "en" })

		assertEquals(SourceRank.HOT_LIMIT, result.size)
		assertTrue("the most popular must be included", "S30" in result)
		assertFalse("the least popular must not be", "S1" in result)
	}
}
