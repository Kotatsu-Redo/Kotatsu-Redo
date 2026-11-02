package org.koitharu.kotatsu.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.tracker.domain.StaleTrackPolicy.Track
import java.util.concurrent.TimeUnit

class StaleTrackPolicyTest {

	private val now = TimeUnit.DAYS.toMillis(20_000)

	private fun daysAgo(days: Int) = now - TimeUnit.DAYS.toMillis(days.toLong())

	/** Upload dates of a series that released every [gap] days, the newest one [lastAgo] days ago. */
	private fun releases(lastAgo: Int, gap: Int, count: Int = 8) = List(count) { daysAgo(lastAgo + it * gap) }

	private fun track(
		id: Long = 1L,
		state: MangaState? = MangaState.ONGOING,
		lastAgo: Int = 30,
		checkedAgo: Int = 0,
		releaseDates: List<Long> = releases(lastAgo, gap = 7),
		lastLogAgo: Int? = null,
	) = Track(
		mangaId = id,
		state = state,
		lastChapterDate = daysAgo(lastAgo),
		lastLogTime = lastLogAgo?.let { daysAgo(it) } ?: 0L,
		lastCheckTime = daysAgo(checkedAgo),
		releaseDates = releaseDates,
	)

	private fun select(
		vararg tracks: Track,
		notified: Set<Long> = emptySet(),
		lastNotifiedAt: Long = 0L,
		at: Long = now,
	) = StaleTrackPolicy.select(tracks.toList(), notified, lastNotifiedAt, at).map { it.mangaId }

	// What counts as regular

	@Test
	fun `weekly and fortnightly series are regular`() {
		assertTrue(StaleTrackPolicy.isRegular(releases(0, gap = 7)))
		assertTrue(StaleTrackPolicy.isRegular(releases(0, gap = 14)))
	}

	@Test
	fun `monthly and three-weekly series are not, four weeks is normal for them`() {
		assertFalse(StaleTrackPolicy.isRegular(releases(0, gap = 30)))
		assertFalse(StaleTrackPolicy.isRegular(releases(0, gap = 28)))
		assertFalse(StaleTrackPolicy.isRegular(releases(0, gap = 21)))
	}

	@Test
	fun `three release days are too few to call it a schedule`() {
		assertFalse(StaleTrackPolicy.isRegular(releases(0, gap = 7, count = 3)))
		assertTrue(StaleTrackPolicy.isRegular(releases(0, gap = 7, count = 4)))
	}

	@Test
	fun `a whole archive uploaded on one day is one release`() {
		assertFalse(StaleTrackPolicy.isRegular(List(200) { daysAgo(5) + it }))
	}

	@Test
	fun `several chapters on one day count once, without shrinking the gaps`() {
		val doubled = releases(0, gap = 30, count = 5).flatMap { listOf(it, it + 1000L) }
		assertFalse(StaleTrackPolicy.isRegular(doubled))
	}

	@Test
	fun `chapters without a date say nothing`() {
		assertFalse(StaleTrackPolicy.isRegular(List(50) { 0L }))
		assertFalse(StaleTrackPolicy.isRegular(emptyList()))
	}

	@Test
	fun `a recent hiatus means four weeks of silence has happened before`() {
		val dates = releases(0, gap = 7, count = 3) + releases(14 + 60, gap = 7, count = 5)
		assertFalse(StaleTrackPolicy.isRegular(dates))
	}

	@Test
	fun `only the recent schedule matters, not how it was years ago`() {
		val dates = releases(0, gap = 7, count = 8) + releases(400, gap = 90, count = 10)
		assertTrue(StaleTrackPolicy.isRegular(dates))
	}

	@Test
	fun `mostly long gaps are not regular even if none reaches four weeks`() {
		val dates = listOf(0, 3, 25, 47, 69, 91).map { daysAgo(it) }
		assertFalse(StaleTrackPolicy.isRegular(dates))
	}

	// What counts as stale

	@Test
	fun `no chapter for four weeks makes a regular series stale, not a day sooner`() {
		assertTrue(StaleTrackPolicy.isStale(track(lastAgo = 28), now))
		assertFalse(StaleTrackPolicy.isStale(track(lastAgo = 27), now))
	}

	@Test
	fun `a source that fails every check still qualifies from what was stored before it died`() {
		// Nothing about failing changes the inputs but the check time, which is the point of the feature
		val dead = track(lastAgo = 40, checkedAgo = 0, releaseDates = releases(40, gap = 7))
		assertTrue(StaleTrackPolicy.isStale(dead, now))
	}

	@Test
	fun `a long dead series is left alone`() {
		assertTrue(StaleTrackPolicy.isStale(track(lastAgo = 90), now))
		assertFalse(StaleTrackPolicy.isStale(track(lastAgo = 91), now))
	}

	@Test
	fun `weeks without a check prove nothing`() {
		// The tracker was off or the phone offline: the last look at it was before it was overdue
		assertFalse(StaleTrackPolicy.isStale(track(lastAgo = 40, checkedAgo = 20), now))
		assertFalse(StaleTrackPolicy.isStale(track(lastAgo = 40, checkedAgo = 39), now))
		assertTrue(StaleTrackPolicy.isStale(track(lastAgo = 40, checkedAgo = 12), now))
	}

	@Test
	fun `a series never checked is not stale`() {
		val neverChecked = Track(1L, MangaState.ONGOING, daysAgo(40), 0L, 0L, releases(40, gap = 7))
		assertFalse(StaleTrackPolicy.isStale(neverChecked, now))
	}

	@Test
	fun `only ongoing or unknown state can be stale`() {
		assertTrue(StaleTrackPolicy.isStale(track(state = MangaState.ONGOING), now))
		assertTrue(StaleTrackPolicy.isStale(track(state = null), now))
		assertFalse(StaleTrackPolicy.isStale(track(state = MangaState.FINISHED), now))
		assertFalse(StaleTrackPolicy.isStale(track(state = MangaState.ABANDONED), now))
		assertFalse(StaleTrackPolicy.isStale(track(state = MangaState.PAUSED), now))
	}

	@Test
	fun `a series that was never regular is not stale`() {
		assertFalse(StaleTrackPolicy.isStale(track(releaseDates = releases(30, gap = 45)), now))
		assertFalse(StaleTrackPolicy.isStale(track(releaseDates = emptyList()), now))
	}

	@Test
	fun `no known date at all is not stale`() {
		val undated = Track(1L, MangaState.ONGOING, 0L, 0L, now, emptyList())
		assertFalse(StaleTrackPolicy.isStale(undated, now))
	}

	@Test
	fun `a chapter found recently counts even if the source dates it old`() {
		assertFalse(StaleTrackPolicy.isStale(track(lastAgo = 40, lastLogAgo = 3), now))
		assertTrue(StaleTrackPolicy.isStale(track(lastAgo = 40, lastLogAgo = 35), now))
	}

	@Test
	fun `a newer chapter in the stored list counts even before the track catches up`() {
		val dates = releases(3, gap = 7)
		assertFalse(StaleTrackPolicy.isStale(track(lastAgo = 40, releaseDates = dates), now))
	}

	@Test
	fun `a stored list that stopped long before the last chapter is not evidence of a schedule`() {
		// The list was stored when it was last opened, months back; the months since are unknown
		val dates = releases(85, gap = 7)
		assertFalse(StaleTrackPolicy.isStale(track(lastAgo = 30, releaseDates = dates), now))
	}

	// One grouped notification a week at most

	@Test
	fun `everything stale goes into one list, longest silent first`() {
		val selected = select(
			track(id = 1L, lastAgo = 30),
			track(id = 2L, lastAgo = 60),
			track(id = 3L, lastAgo = 45),
		)
		assertEquals(listOf(2L, 3L, 1L), selected)
	}

	@Test
	fun `nothing stale selects nothing`() {
		assertEquals(emptyList<Long>(), select(track(lastAgo = 3)))
		assertEquals(emptyList<Long>(), select())
	}

	@Test
	fun `at most once a week`() {
		val stale = track()
		assertEquals(emptyList<Long>(), select(stale, lastNotifiedAt = now))
		assertEquals(emptyList<Long>(), select(stale, lastNotifiedAt = daysAgo(6)))
		assertEquals(listOf(1L), select(stale, lastNotifiedAt = daysAgo(7)))
		assertEquals(listOf(1L), select(stale, lastNotifiedAt = 0L))
	}

	@Test
	fun `a clock set back does not silence it`() {
		assertTrue(StaleTrackPolicy.canNotify(lastNotifiedAt = now + TimeUnit.DAYS.toMillis(30), now = now))
	}

	// No repeats

	@Test
	fun `what was already reported is not reported again`() {
		assertEquals(emptyList<Long>(), select(track(id = 1L), notified = setOf(1L)))
		assertEquals(listOf(2L), select(track(id = 1L), track(id = 2L), notified = setOf(1L)))
	}

	@Test
	fun `reported stays remembered while silent, and is forgotten once it updates or stops being tracked`() {
		assertEquals(setOf(1L), StaleTrackPolicy.keepNotified(setOf(1L, 2L, 3L), silentIds = setOf(1L, 9L)))
		assertEquals(emptySet<Long>(), StaleTrackPolicy.keepNotified(emptySet(), silentIds = setOf(1L)))
	}

	@Test
	fun `over weeks a silent series is reported once, then again only after a new chapter and a new silence`() {
		var notified = emptySet<Long>()
		var lastNotifiedAt = 0L
		var reports = 0
		val week = TimeUnit.DAYS.toMillis(7)
		fun run(at: Long, track: Track, isSilent: Boolean) {
			notified = StaleTrackPolicy.keepNotified(notified, if (isSilent) setOf(track.mangaId) else emptySet())
			val selected = StaleTrackPolicy.select(listOf(track), notified, lastNotifiedAt, at)
			if (selected.isNotEmpty()) {
				reports++
				lastNotifiedAt = at
				notified = notified + selected.map { it.mangaId }
			}
		}

		val silent = track(lastAgo = 30)
		repeat(6) { run(now + it * week, silent, isSilent = true) }
		assertEquals("one silence, one report", 1, reports)

		// A new chapter arrives: forgotten
		val updatedAt = now + 6 * week
		fun updated(checkAt: Long) = Track(
			mangaId = 1L,
			state = MangaState.ONGOING,
			lastChapterDate = updatedAt,
			lastLogTime = updatedAt,
			lastCheckTime = checkAt,
			releaseDates = releases(0, gap = 7).map { it + 6 * week },
		)
		run(updatedAt + week, updated(updatedAt + week), isSilent = false)
		assertEquals(1, reports)
		assertEquals(emptySet<Long>(), notified)

		// Four weeks later it is quiet again
		val later = updatedAt + TimeUnit.DAYS.toMillis(29)
		run(later, updated(later), isSilent = true)
		assertEquals("a new silence, a new report", 2, reports)
	}

	@Test
	fun `a series going quiet during the cooldown waits for the next week`() {
		val first = track(id = 1L, lastAgo = 30)
		assertEquals(listOf(1L), select(first))
		val second = track(id = 2L, lastAgo = 29)
		assertEquals(emptyList<Long>(), select(first, second, notified = setOf(1L), lastNotifiedAt = daysAgo(3)))
		assertEquals(listOf(2L), select(first, second, notified = setOf(1L), lastNotifiedAt = daysAgo(8)))
	}
}
