package org.koitharu.kotatsu.suggestions.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.discover.domain.DiscoverRanking
import java.time.Duration

class PersonalizationTest {

	private val taste = mapOf("romance" to 0.6, "comedy" to 0.4, "horror" to -0.3)

	@Test
	fun `time spent on a title counts for more, up to a point`() {
		assertEquals(1.0, SuggestionRanking.engagement(0), 0.001)
		assertEquals(2.0, SuggestionRanking.engagement(200), 0.001)
		assertTrue(SuggestionRanking.engagement(600) > SuggestionRanking.engagement(200))
		assertEquals(3.0, SuggestionRanking.engagement(100_000), 0.001)
	}

	@Test
	fun `how much was read is worked out from progress when nothing recorded it`() {
		assertEquals(1250, SuggestionRanking.pagesRead(0, 0.5f, 100))
		assertEquals("recorded pages win when they are more", 2000, SuggestionRanking.pagesRead(2000, 0.5f, 100))
		assertEquals("estimate wins when statistics were only on for a while", 1250, SuggestionRanking.pagesRead(30, 0.5f, 100))
		assertEquals(0, SuggestionRanking.pagesRead(0, null, null))
		assertEquals(0, SuggestionRanking.pagesRead(0, -1f, 100))
		assertEquals(0, SuggestionRanking.pagesRead(0, 0.5f, 0))
	}

	@Test
	fun `what was read long ago says less but never nothing`() {
		assertEquals(1.0, SuggestionRanking.fade(Duration.ZERO), 0.001)
		assertEquals(0.5, SuggestionRanking.fade(Duration.ofDays(180)), 0.01)
		assertEquals(0.25, SuggestionRanking.fade(Duration.ofDays(5000)), 0.001)
		assertEquals("a clock set wrong is not the future", 1.0, SuggestionRanking.fade(Duration.ofDays(-30)), 0.001)
	}

	@Test
	fun `a title read for longer is a better place to start`() {
		assertTrue(
			SuggestionRanking.seedWeight(5, 0.2f, false, pagesRead = 1000) >
				SuggestionRanking.seedWeight(0, 0.2f, false, pagesRead = 0),
		)
	}

	@Test
	fun `of two equally alike titles the one this reader reads comes first`() {
		val theirs = DiscoverRanking.personalize(1.0, listOf("Romance", "Comedy"), taste)
		val neutral = DiscoverRanking.personalize(1.0, listOf("Sports"), taste)
		val avoided = DiscoverRanking.personalize(1.0, listOf("Horror"), taste)
		assertTrue(theirs > neutral)
		assertEquals(1.0, neutral, 0.001)
		assertTrue(avoided < neutral)
	}

	@Test
	fun `it nudges rather than overturns how alike they are`() {
		val closeButNotTheirs = DiscoverRanking.personalize(3.0, listOf("Sports"), taste)
		val theirsButFar = DiscoverRanking.personalize(1.0, listOf("Romance", "Comedy"), taste)
		assertTrue(closeButNotTheirs > theirsButFar)
	}

	@Test
	fun `piling on genres does not make a title closer`() {
		val focused = DiscoverRanking.personalize(1.0, listOf("Romance"), mapOf("romance" to 0.5, "a" to 0.1, "b" to 0.1, "c" to 0.1, "d" to 0.1))
		val tagged = DiscoverRanking.personalize(1.0, listOf("a", "b", "c", "d"), mapOf("a" to 0.1, "b" to 0.1, "c" to 0.1, "d" to 0.1))
		assertTrue(focused > tagged)
	}

	@Test
	fun `nothing known about the reader or the title changes nothing`() {
		assertEquals(1.5, DiscoverRanking.personalize(1.5, listOf("Romance"), emptyMap()), 0.0)
		assertEquals(1.5, DiscoverRanking.personalize(1.5, emptyList(), taste), 0.0)
	}
}
