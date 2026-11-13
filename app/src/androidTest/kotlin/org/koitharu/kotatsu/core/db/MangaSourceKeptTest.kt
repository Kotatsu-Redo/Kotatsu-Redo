package org.koitharu.kotatsu.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.model.UnknownMangaSource

/**
 * A source can be unknown to the app for a while and known again later: one that only downloaded parsers have, until
 * those are loaded or downloaded again. A manga stored during that time must still be of its source afterwards.
 */
@RunWith(AndroidJUnit4::class)
class MangaSourceKeptTest {

	private lateinit var db: MangaDatabase

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
	fun aMangaStoredAgainWhileItsSourceIsUnknownKeepsItsSource() = runBlocking {
		val dao = db.getMangaDao()
		val manga = MangaEntity(
			id = 1L,
			title = "Title",
			altTitles = null,
			url = "/1",
			publicUrl = "https://example.org/1",
			rating = -1f,
			isNsfw = false,
			contentRating = null,
			coverUrl = "",
			largeCoverUrl = null,
			state = null,
			authors = null,
			source = "ONLY_DOWNLOADED",
		)
		dao.upsert(manga)

		// Read back as of no source, and stored again with something else changed
		dao.upsert(manga.copy(title = "Newer title", source = UnknownMangaSource.name))
		val stored = checkNotNull(dao.find(1L)).manga
		assertEquals("ONLY_DOWNLOADED", stored.source)
		assertEquals("Newer title", stored.title)

		// Never known to be of any source: nothing to keep
		dao.upsert(manga.copy(id = 2L, source = UnknownMangaSource.name))
		assertEquals(UnknownMangaSource.name, checkNotNull(dao.find(2L)).manga.source)

		// A source the app knows replaces what was there, as before
		dao.upsert(manga.copy(source = "ANOTHER"))
		assertEquals("ANOTHER", checkNotNull(dao.find(1L)).manga.source)
	}
}
