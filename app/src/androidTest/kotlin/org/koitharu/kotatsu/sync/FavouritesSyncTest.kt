package org.koitharu.kotatsu.sync

import android.accounts.Account
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.sync.data.model.FavouriteSyncDto
import org.koitharu.kotatsu.sync.data.model.MangaSyncDto
import org.koitharu.kotatsu.sync.data.model.SyncDto
import org.koitharu.kotatsu.sync.domain.SyncHelper
import javax.inject.Inject

/**
 * A manga removed from favourites on one device must go from the others, and stay gone.
 *
 * A sync sends every row before it reads any, and the server keeps whichever copy reaches it last. A second device
 * that still had the manga so put it back on the server each time it synced: it never left that device, and it came
 * back on the first one days later, when the record of its removal had been cleared out there. Here the server's
 * copy is what [SyncHelper.takeNewer] is handed, as it is after being asked for and before anything is sent.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FavouritesSyncTest {

	@get:Rule
	var hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var favouritesRepository: FavouritesRepository

	@Inject
	lateinit var syncHelperFactory: SyncHelper.Factory

	@Inject
	lateinit var database: MangaDatabase

	private val context
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	private val manga
		get() = SampleData.manga

	private var categoryId = 0L

	@Before
	fun setUp() {
		hiltRule.inject()
		database.clearAllTables()
		runBlocking {
			categoryId = favouritesRepository.createCategory(
				title = "Reading",
				sortOrder = ListSortOrder.NEWEST,
				isTrackerEnabled = false,
				isVisibleOnShelf = true,
			).id
			favouritesRepository.addToCategory(categoryId, listOf(manga))
		}
		tick()
	}

	@Test
	fun removedOnAnotherDeviceIsRemovedHereBeforeItIsSentBack() = runTest {
		val added = row().createdAt
		val removedThere = added + 1

		val taken = sync(onServer(createdAt = added, deletedAt = removedThere))

		assertEquals(1, taken)
		assertEquals(removedThere, row().deletedAt)
		assertTrue(database.getFavouritesDao().findAllRaw(manga.id).isEmpty())
	}

	@Test
	fun removedHereIsNotBroughtBackByADeviceThatStillHasIt() = runTest {
		val added = row().createdAt
		favouritesRepository.removeFromFavourites(setOf(manga.id))
		val removedHere = row().deletedAt

		val taken = sync(onServer(createdAt = added, deletedAt = 0))

		assertEquals(0, taken)
		assertEquals(removedHere, row().deletedAt)
	}

	@Test
	fun addedAgainOnAnotherDeviceAfterBeingRemovedHereComesBack() = runTest {
		favouritesRepository.removeFromFavourites(setOf(manga.id))
		val addedAgainThere = row().deletedAt + 1

		val taken = sync(onServer(createdAt = addedAgainThere, deletedAt = 0))

		assertEquals(1, taken)
		assertEquals(Times(createdAt = addedAgainThere, deletedAt = 0), row())
	}

	@Test
	fun addedAgainHereWhileTheServerWasBeingAskedIsLeftAlone() = runTest {
		val added = row().createdAt
		val startedAt = System.currentTimeMillis()
		tick()
		favouritesRepository.addToCategory(categoryId, listOf(manga))
		val addedAgain = row().createdAt

		sync(onServer(createdAt = added, deletedAt = Long.MAX_VALUE), startedAt)

		assertEquals(Times(createdAt = addedAgain, deletedAt = 0), row())
	}

	/** Undo, after a sync has already sent the removal. With its old date the removal would be the later of the two. */
	@Test
	fun putBackAfterItsRemovalWasSentCountsAsAddedAgain() = runTest {
		val added = row().createdAt
		favouritesRepository.removeFromFavourites(setOf(manga.id))
		val removed = row().deletedAt
		tick()

		database.getFavouritesDao().recover(manga.id, syncedUntil = Long.MAX_VALUE)

		assertTrue(row().createdAt > removed)
		assertEquals(0, sync(onServer(createdAt = added, deletedAt = removed)))
		assertEquals(0L, row().deletedAt)
	}

	/** Undo before any sync could have seen the removal: nothing has to be outdone, so the date it was added stays. */
	@Test
	fun putBackBeforeAnySyncKeepsTheDateItWasAdded() = runTest {
		val added = row().createdAt
		val handle = favouritesRepository.removeFromFavourites(setOf(manga.id))
		tick()

		handle.reverse()

		assertEquals(Times(createdAt = added, deletedAt = 0), row())
	}

	/** What a sync does with the server's copy before it sends its own: the same call, on the same provider. */
	private fun sync(remote: SyncDto, startedAt: Long = System.currentTimeMillis()): Int {
		val authority = context.getString(R.string.sync_authority_favourites)
		val provider = checkNotNull(context.contentResolver.acquireContentProviderClient(authority))
		try {
			val account = Account("test", context.getString(R.string.account_type_sync))
			return syncHelperFactory.create(account, provider).takeNewer(remote, startedAt)
		} finally {
			provider.close()
		}
	}

	private fun onServer(createdAt: Long, deletedAt: Long) = SyncDto(
		favourites = listOf(
			FavouriteSyncDto(
				mangaId = manga.id,
				manga = MangaSyncDto(
					id = manga.id,
					title = manga.title,
					altTitle = null,
					url = manga.url,
					publicUrl = manga.publicUrl,
					rating = manga.rating,
					contentRating = null,
					coverUrl = manga.coverUrl.orEmpty(),
					largeCoverUrl = null,
					tags = emptySet(),
					state = null,
					author = null,
					source = manga.source.name,
				),
				categoryId = categoryId.toInt(),
				sortKey = 0,
				pinned = false,
				createdAt = createdAt,
				deletedAt = deletedAt,
			),
		),
		categories = emptyList(),
		timestamp = 0L,
	)

	/** The row as it is, removed or not: nothing else here reads one that has been removed. */
	private fun row(): Times = database.openHelper.readableDatabase.query(
		"SELECT created_at, deleted_at FROM favourites WHERE manga_id = ${manga.id} AND category_id = $categoryId",
	).use { cursor ->
		check(cursor.moveToFirst()) { "No such favourite" }
		Times(createdAt = cursor.getLong(0), deletedAt = cursor.getLong(1))
	}

	/** Times are in milliseconds, and what happens in the same one can not be told apart. */
	private fun tick() = Thread.sleep(TICK_MS)

	private data class Times(val createdAt: Long, val deletedAt: Long)

	private companion object {

		const val TICK_MS = 20L
	}
}
