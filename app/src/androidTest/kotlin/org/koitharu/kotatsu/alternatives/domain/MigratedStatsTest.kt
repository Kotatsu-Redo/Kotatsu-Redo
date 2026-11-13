package org.koitharu.kotatsu.alternatives.domain

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.stats.data.StatsEntity

/**
 * Moving a manga to another source gives it a new id. What was read of it, the time and the pages, is kept by id,
 * and has to follow.
 */
@RunWith(AndroidJUnit4::class)
class MigratedStatsTest {

	private lateinit var db: MangaDatabase

	private val sessions = listOf(
		StatsEntity(mangaId = OLD, startedAt = 1_000L, duration = 60_000L, pages = 12),
		StatsEntity(mangaId = OLD, startedAt = 2_000L, duration = 90_000L, pages = 20),
		StatsEntity(mangaId = OLD, startedAt = 3_000L, duration = 30_000L, pages = 5),
	)

	@Before
	fun setUp() {
		db = Room.inMemoryDatabaseBuilder(
			InstrumentationRegistry.getInstrumentation().targetContext,
			MangaDatabase::class.java,
		).build()
	}

	@After
	fun tearDown() {
		db.close()
	}

	@Test
	fun whatWasReadFollowsAMangaToItsNewSource() = runBlocking<Unit> {
		store(manga(OLD, "OLD_SOURCE"), history(OLD))
		db.getMangaDao().upsert(manga(NEW, "NEW_SOURCE"))
		sessions.forEach { db.getStatsDao().upsert(it) }

		db.withTransaction { db.replaceHistory(OLD, history(NEW)) }

		val stats = db.getStatsDao()
		assertEquals(sessions.map { it.copy(mangaId = NEW) }, stats.findAll(NEW))
		assertEquals(emptyList<StatsEntity>(), stats.findAll(OLD))
		assertEquals(37, stats.getReadPagesCount(NEW))
		assertNull(db.getHistoryDao().find(OLD))
		assertNotNull(db.getHistoryDao().find(NEW))
		// What the statistics screen reads: the new one with all of the time, and no trace of the old one
		assertEquals(mapOf(NEW to 180_000L), shownOnTheStatisticsScreen())

		// The old history is only marked as removed at first, and deleted for good later
		db.getHistoryDao().gc(Long.MAX_VALUE)
		assertEquals(sessions.map { it.copy(mangaId = NEW) }, stats.findAll(NEW))
		assertEquals(mapOf(NEW to 180_000L), shownOnTheStatisticsScreen())
	}

	@Test
	fun whatWasAlreadyReadOfTheNewOneIsKept() = runBlocking<Unit> {
		store(manga(OLD, "OLD_SOURCE"), history(OLD))
		store(manga(NEW, "NEW_SOURCE"), history(NEW))
		sessions.forEach { db.getStatsDao().upsert(it) }
		val own = StatsEntity(mangaId = NEW, startedAt = 9_000L, duration = 15_000L, pages = 3)
		db.getStatsDao().upsert(own)

		db.withTransaction { db.replaceHistory(OLD, history(NEW).copy(page = 7)) }

		assertEquals(sessions.map { it.copy(mangaId = NEW) } + own, db.getStatsDao().findAll(NEW))
		assertEquals(40, db.getStatsDao().getReadPagesCount(NEW))
		assertEquals(mapOf(NEW to 195_000L), shownOnTheStatisticsScreen())
		assertEquals(7, checkNotNull(db.getHistoryDao().find(NEW)).page)
	}

	@Test
	fun aSessionBothHaveFromTheSameMomentIsNotCountedTwice() = runBlocking<Unit> {
		store(manga(OLD, "OLD_SOURCE"), history(OLD))
		store(manga(NEW, "NEW_SOURCE"), history(NEW))
		sessions.forEach { db.getStatsDao().upsert(it) }
		val own = StatsEntity(mangaId = NEW, startedAt = 2_000L, duration = 1L, pages = 1)
		db.getStatsDao().upsert(own)

		db.withTransaction { db.replaceHistory(OLD, history(NEW)) }

		assertEquals(
			listOf(sessions[0].copy(mangaId = NEW), own, sessions[2].copy(mangaId = NEW)),
			db.getStatsDao().findAll(NEW),
		)
	}

	@Test
	fun aMangaPutInItsOwnPlaceKeepsWhatWasRead() = runBlocking<Unit> {
		store(manga(OLD, "OLD_SOURCE"), history(OLD))
		sessions.forEach { db.getStatsDao().upsert(it) }

		db.withTransaction { db.replaceHistory(OLD, history(OLD).copy(page = 3)) }

		assertEquals(sessions, db.getStatsDao().findAll(OLD))
		assertEquals(3, checkNotNull(db.getHistoryDao().find(OLD)).page)
		db.getHistoryDao().gc(Long.MAX_VALUE)
		assertEquals(mapOf(OLD to 180_000L), shownOnTheStatisticsScreen())
	}

	private suspend fun shownOnTheStatisticsScreen(): Map<Long, Long> =
		db.getStatsDao().getDurationStats(fromDate = 0L, isNsfw = null, favouriteCategories = emptySet())
			.mapKeys { it.key.id }

	private suspend fun store(manga: MangaEntity, history: HistoryEntity) {
		db.getMangaDao().upsert(manga)
		db.getHistoryDao().upsert(history)
	}

	private fun history(mangaId: Long) = HistoryEntity(
		mangaId = mangaId,
		createdAt = 500L,
		updatedAt = 4_000L,
		chapterId = 1L,
		page = 0,
		scroll = 0f,
		percent = 0.5f,
		deletedAt = 0L,
		chaptersCount = 10,
	)

	private fun manga(id: Long, source: String) = MangaEntity(
		id = id,
		title = "Title",
		altTitles = null,
		url = "/$id",
		publicUrl = "https://example.org/$id",
		rating = -1f,
		isNsfw = false,
		contentRating = null,
		coverUrl = "",
		largeCoverUrl = null,
		state = null,
		authors = null,
		source = source,
	)

	private companion object {
		const val OLD = 1L
		const val NEW = 2L
	}
}
