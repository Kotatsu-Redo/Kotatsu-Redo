package org.koitharu.kotatsu.discover

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.backups.data.BackupRepository
import org.koitharu.kotatsu.backups.domain.BackupSection
import org.koitharu.kotatsu.discover.data.DiscoverDismissals
import org.koitharu.kotatsu.discover.data.DismissedEntry
import org.koitharu.kotatsu.discover.data.UploadState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * What the reader is not interested in goes into a backup and comes back out of it, through the real backup code.
 *
 * It only reads the library, and writes nothing but the two files the passes are kept in. Those are put back as
 * they were afterwards, so this is safe to run where someone's own data is.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DismissedBackupTest {

	@get:Rule
	var hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var backupRepository: BackupRepository

	@Inject
	lateinit var dismissals: DiscoverDismissals

	private lateinit var files: List<SharedPreferences>
	private lateinit var kept: List<Map<String, String>>

	@Before
	fun setUp() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		files = listOf("discover_dismissed", "discover_dismissed_withdrawn").map {
			context.getSharedPreferences(it, Context.MODE_PRIVATE)
		}
		kept = files.map { file -> file.all.mapValues { it.value as String } }
		// Before anything reads them: the store keeps what it read
		files.forEach { check(it.edit().clear().commit()) }
		hiltRule.inject()
	}

	@After
	fun tearDown() {
		files.forEachIndexed { index, file ->
			val editor = file.edit().clear()
			kept[index].forEach { (key, value) -> editor.putString(key, value) }
			check(editor.commit())
		}
	}

	@Test
	fun passesGoIntoABackupAndComeBackAsNotSent() = runBlocking<Unit> {
		val store = dismissals.store
		val told = DismissedEntry(tags = listOf("Action"), source = "SRC", url = "/told", title = "Told", time = 5L, workId = "77", upload = UploadState.UPLOADED)
		val untold = DismissedEntry(tags = listOf("Drama", "Romance"), altKeys = listOf("othername"), source = "SRC", url = "/untold", title = "Untold", time = 6L)
		store.addAll(listOf("told" to told, "untold" to untold))
		assertEquals(mapOf("told" to told, "untold" to untold), store.index().entries)

		val bytes = ByteArrayOutputStream()
		ZipOutputStream(bytes).use { backupRepository.createBackup(it, null) }
		val backup = bytes.toByteArray()
		assertTrue("the backup has no section for passes", sectionsOf(backup).contains(BackupSection.DISMISSED.entryName))

		// One is still here and the other is not: what is here stays as it is, and the other comes back
		assertEquals(0, store.undo(listOf("untold")))
		assertEquals(setOf("told"), store.index().entries.keys)
		restore(backup)
		assertEquals(mapOf("told" to told, "untold" to untold), store.index().entries)
		assertEquals(UploadState.UPLOADED, store.index().entries.getValue("told").upload)
		assertEquals(UploadState.NONE, store.index().entries.getValue("untold").upload)

		// Neither is here, as on another phone: both come back, and neither as something the server was told
		store.undo(listOf("told", "untold"))
		assertTrue(store.index().entries.isEmpty())
		restore(backup)
		assertEquals(
			mapOf("told" to told.copy(upload = UploadState.NONE), "untold" to untold),
			store.index().entries,
		)
		assertEquals(UploadState.NONE, store.index().entries.getValue("told").upload)
		assertEquals("77", store.index().entries.getValue("told").workId)
		assertEquals(listOf("othername"), store.index().entries.getValue("untold").altKeys)

		// And it is what is in the file, not only what the store remembers
		val written = files[0].all.mapValues { DismissedEntry.decode(it.value as String) }
		assertEquals(store.index().entries, written)
	}

	@Test
	fun aBackupRestoredWithoutItsPassesBringsNoneBack() = runBlocking<Unit> {
		val store = dismissals.store
		store.addAll(listOf("one" to DismissedEntry(tags = listOf("Action"), source = "SRC", url = "/one", title = "One", time = 1L)))
		val bytes = ByteArrayOutputStream()
		ZipOutputStream(bytes).use { backupRepository.createBackup(it, null) }
		store.undo(listOf("one"))

		ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use {
			backupRepository.restoreBackup(it, emptySet(), null)
		}
		assertTrue(store.index().entries.isEmpty())
	}

	private suspend fun restore(backup: ByteArray) {
		ZipInputStream(ByteArrayInputStream(backup)).use {
			backupRepository.restoreBackup(it, setOf(BackupSection.DISMISSED), null)
		}
	}

	private fun sectionsOf(backup: ByteArray): List<String> = ZipInputStream(ByteArrayInputStream(backup)).use { zip ->
		generateSequence { zip.nextEntry }.map { it.name }.toList()
	}
}
