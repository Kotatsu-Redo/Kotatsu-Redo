package org.koitharu.kotatsu.suggestions.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.sourcescore.data.SimilarWorkDto
import java.time.Duration
import java.time.Instant

class SuggestionRankingTest {

	private fun work(id: String, score: Double, vararg genres: String) =
		RankedWork(SimilarWorkDto(workId = id, title = id, genres = genres.toList()), score)

	@Test
	fun varietyBeatsANearCopy() {
		val picked = SuggestionRanking.diversify(
			listOf(
				work("a", 1.0, "action", "fantasy"),
				work("b", 0.95, "action", "fantasy"),
				work("c", 0.8, "romance", "school-life"),
			),
			count = 3,
		)
		// b is a near-copy of a: discounted to 0.475, below c's untouched 0.8
		assertEquals(listOf("a", "c", "b"), picked.map { it.dto.workId })
	}

	@Test
	fun discountedScoresComeOutInDescendingOrder() {
		val picked = SuggestionRanking.diversify(
			(1..30).map { work("w$it", 1.0 / it, if (it % 2 == 0) "action" else "drama", "g$it") },
			count = 10,
		)
		assertEquals(10, picked.size)
		assertEquals(picked.map { it.score }.sortedDescending(), picked.map { it.score })
	}

	@Test
	fun worksWithoutGenresAreNeverDiscounted() {
		val picked = SuggestionRanking.diversify(listOf(work("a", 1.0), work("b", 0.9)), count = 2)
		assertEquals(listOf(1.0, 0.9), picked.map { it.score })
	}

	@Test
	fun diversifyHandlesFewerCandidatesThanAskedFor() {
		assertEquals(emptyList<RankedWork>(), SuggestionRanking.diversify(emptyList(), count = 5))
		assertEquals(1, SuggestionRanking.diversify(listOf(work("a", 1.0, "x")), count = 5).size)
	}

	@Test
	fun genreOverlapIsJaccard() {
		val a = SimilarWorkDto(workId = "a", title = "a", genres = listOf("action", "fantasy", "isekai"))
		val b = SimilarWorkDto(workId = "b", title = "b", genres = listOf("fantasy", "isekai", "romance"))
		assertEquals(0.5, SuggestionRanking.genreOverlap(a, b), 1e-9)
		assertEquals(0.0, SuggestionRanking.genreOverlap(a, b.copy(genres = emptyList())), 1e-9)
	}

	@Test
	fun droppedNeighboursAreRankedDownNotOut() {
		assertEquals(1.0, SuggestionRanking.penalizeDropped(1.0, 0), 1e-9)
		assertEquals(0.5, SuggestionRanking.penalizeDropped(1.0, 1), 1e-9)
		assertEquals(0.25, SuggestionRanking.penalizeDropped(1.0, 2), 1e-9)
	}

	private val rarity = mapOf("action" to 1.1, "fantasy" to 1.2, "villainess" to 4.0, "romance" to 1.5, "oddtag" to 6.0)
	private fun rarityOf(title: String) = rarity[SuggestionRanking.normalizeTag(title)] ?: 1.0

	@Test
	fun distinctiveTagsBeatMerelyFrequentOnes() {
		val seedTags = List(5) { "Action" } + List(4) { "Fantasy" } + List(3) { "Villainess" } +
			List(2) { "Romance" } + "OddTag"
		assertEquals(
			// villainess 3 x 4.0 outranks action 5 x 1.1; the one-off tag comes last however rare
			listOf("Villainess", "Action", "Fantasy", "Romance", "OddTag"),
			SuggestionRanking.distinctiveTags(seedTags, ::rarityOf, limit = 10),
		)
	}

	@Test
	fun withoutStatisticsTagsRankByFrequency() {
		val seedTags = listOf("Drama", "action", "Action ", "ACTION", "Drama", "Comedy")
		// Spellings of one tag count together, and the first spelling seen is kept
		assertEquals(listOf("action", "Drama", "Comedy"), SuggestionRanking.distinctiveTags(seedTags, { 1.0 }, limit = 3))
	}

	@Test
	fun sharingARareTagCountsForMoreThanSharingACommonOne() {
		val seed = setOf("action", "villainess")
		val sharesRare = SuggestionRanking.weightedOverlap(seed, setOf("villainess", "romance"), ::rarityOf)
		val sharesCommon = SuggestionRanking.weightedOverlap(seed, setOf("action", "romance"), ::rarityOf)
		assertTrue(sharesRare > sharesCommon)
		assertEquals(1.0, SuggestionRanking.weightedOverlap(seed, seed, ::rarityOf), 1e-9)
		assertEquals(0.0, SuggestionRanking.weightedOverlap(seed, emptySet(), ::rarityOf), 1e-9)
	}

	@Test
	fun popularityIsDampedMildly() {
		assertEquals(1.0, SuggestionRanking.dampPopularity(1.0, 0), 1e-9)
		val few = SuggestionRanking.dampPopularity(1.0, 3)
		val many = SuggestionRanking.dampPopularity(1.0, 1000)
		assertTrue(many < few && few < 1.0)
		// A best-seller keeps a fair share of its score: a close match still shows
		assertTrue(many > 0.4)
	}

	@Test
	fun droppedMeansBarelyReadAndLeftForWeeks() {
		val now = Instant.parse("2026-10-16T12:00:00Z")
		val longAgo = now - Duration.ofDays(30)
		val recently = now - Duration.ofDays(3)
		assertTrue(SuggestionRanking.isDropped(0.05f, longAgo, now))
		assertTrue(SuggestionRanking.isDropped(0f, longAgo, now))
		assertFalse("still reading", SuggestionRanking.isDropped(0.05f, recently, now))
		assertFalse("read far enough", SuggestionRanking.isDropped(0.5f, longAgo, now))
		assertFalse("progress unknown", SuggestionRanking.isDropped(-1f, longAgo, now))
	}
}
