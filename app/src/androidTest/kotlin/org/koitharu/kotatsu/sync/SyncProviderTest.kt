package org.koitharu.kotatsu.sync

import android.content.ContentValues
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.sync.ui.SyncProvider
import javax.inject.Inject

/**
 * What a sync writes back must not undo what was done on the phone while it was under way.
 *
 * A sync reads every row, sends them, and writes down what the server answers. With a big library that takes a
 * while, and reading anything starts one. A manga taken out of history in that time used to come back when the
 * answer arrived, since the answer still had it.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SyncProviderTest {

	@get:Rule
	var hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var historyRepository: HistoryRepository

	@Inject
	lateinit var database: MangaDatabase

	private val context
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	private val mangaId
		get() = SampleData.mangaDetails.id

	@Before
	fun setUp() {
		hiltRule.inject()
		database.clearAllTables()
	}

	@Test
	fun removedWhileSyncWasUnderWayStaysRemoved() = runTest {
		read(page = 4)
		val sync = startSync()
		historyRepository.delete(setOf(mangaId))

		sync.writeDown(sync.sent)

		assertNull(database.getHistoryDao().find(mangaId))
	}

	@Test
	fun readFurtherWhileSyncWasUnderWayKeepsTheNewerPage() = runTest {
		read(page = 4)
		val sync = startSync()
		read(page = 9)

		sync.writeDown(sync.sent)

		assertEquals(9, database.getHistoryDao().find(mangaId)?.page)
	}

	@Test
	fun whatAnotherDeviceReadIsTaken() = runTest {
		read(page = 4)
		val sync = startSync()

		sync.writeDown(sync.sent.copy(page = 20, updatedAt = sync.sent.updatedAt + 1))

		assertEquals(20, database.getHistoryDao().find(mangaId)?.page)
	}

	@Test
	fun whatAnotherDeviceRemovedIsRemoved() = runTest {
		read(page = 4)
		val sync = startSync()

		sync.writeDown(sync.sent.copy(deletedAt = sync.sent.updatedAt + 1))

		assertNull(database.getHistoryDao().find(mangaId))
	}

	@Test
	fun aRowNotHereYetIsAdded() = runTest {
		read(page = 4)
		val sync = startSync()
		historyRepository.delete(setOf(mangaId))
		database.getHistoryDao().gc(Long.MAX_VALUE) // gone for good, as after the days a removal is kept for

		sync.writeDown(sync.sent)

		assertNotNull(database.getHistoryDao().find(mangaId))
	}

	private suspend fun read(page: Int) {
		historyRepository.addOrUpdate(
			manga = SampleData.mangaDetails,
			chapterId = SampleData.chapter.id,
			page = page,
			scroll = 0,
			percent = 0.3f,
			force = true,
		)
		// Times are in milliseconds, and what happens in the same one can not be told apart
		Thread.sleep(TICK_MS)
	}

	private suspend fun startSync(): Sync {
		val startedAt = System.currentTimeMillis()
		val sent = checkNotNull(database.getHistoryDao().find(mangaId))
		Thread.sleep(TICK_MS)
		return Sync(startedAt, sent)
	}

	/** One sync: when it started reading, and the row it read and sent. */
	private inner class Sync(val startedAt: Long, val sent: HistoryEntity) {

		/** What a sync does with a row of the server's answer: the same call, on the same provider. */
		fun writeDown(row: HistoryEntity) {
			val authority = context.getString(R.string.sync_authority_history)
			val uri = "content://$authority/history".toUri().buildUpon()
				.appendQueryParameter(SyncProvider.PARAM_UNCHANGED_SINCE, startedAt.toString())
				.build()
			val values = ContentValues(9).apply {
				put("manga_id", row.mangaId)
				put("created_at", row.createdAt)
				put("updated_at", row.updatedAt)
				put("chapter_id", row.chapterId)
				put("page", row.page)
				put("scroll", row.scroll)
				put("percent", row.percent)
				put("deleted_at", row.deletedAt)
				put("chapters", row.chaptersCount)
			}
			val provider = checkNotNull(context.contentResolver.acquireContentProviderClient(authority))
			try {
				provider.insert(uri, values)
			} finally {
				provider.close()
			}
		}
	}

	private companion object {

		const val TICK_MS = 20L
	}
}
