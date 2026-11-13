package org.koitharu.kotatsu.backups.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backups.ui.restore.BackupSectionModel
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupSectionTest {

	/** Zips entries the way an archiver would, then reads them back the way a restore does. */
	private fun sectionsOf(names: List<String>, level: Int = Deflater.DEFAULT_COMPRESSION): Set<BackupSection> {
		val bytes = ByteArrayOutputStream()
		ZipOutputStream(bytes).use { zip ->
			zip.setLevel(level)
			names.forEach { name ->
				zip.putNextEntry(ZipEntry(name))
				if (!name.endsWith('/')) zip.write("[]".toByteArray())
				zip.closeEntry()
			}
		}
		val found = HashSet<BackupSection>()
		ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
			while (true) {
				val entry = zip.nextEntry ?: break
				BackupSection.of(entry)?.let(found::add)
				zip.closeEntry()
			}
		}
		return found
	}

	private val expected = setOf(BackupSection.INDEX, BackupSection.HISTORY, BackupSection.FAVOURITES)

	@Test
	fun `the app's own layout`() {
		assertEquals(expected, sectionsOf(listOf("index", "history", "favourites")))
	}

	@Test
	fun `every compression level reads the same`() {
		for (level in listOf(Deflater.NO_COMPRESSION, 1, 5, 6, Deflater.BEST_COMPRESSION)) {
			assertEquals("level $level", expected, sectionsOf(listOf("index", "history", "favourites"), level))
		}
	}

	@Test
	fun `zipped again from the folder it was unzipped to`() {
		assertEquals(
			expected,
			sectionsOf(listOf("backup/", "backup/index", "backup/history", "backup/favourites")),
		)
	}

	@Test
	fun `folders named by a windows tool, and files given an extension`() {
		assertEquals(expected, sectionsOf(listOf("backup\\index", "backup\\history", "backup\\favourites")))
		assertEquals(expected, sectionsOf(listOf("index.json", "History.json", "FAVOURITES")))
	}

	@Test
	fun `what an archiver adds on the side is not a section`() {
		assertEquals(
			expected,
			sectionsOf(listOf("__MACOSX/", "__MACOSX/._index", ".DS_Store", "index", "history", "favourites")),
		)
	}

	@Test
	fun `a folder that happens to be named like a section is not one`() {
		assertEquals(setOf(BackupSection.INDEX), sectionsOf(listOf("history/", "index")))
	}

	@Test
	fun `what was passed on is its own section, found by its name`() {
		assertEquals("dismissed", BackupSection.DISMISSED.entryName)
		assertEquals(
			setOf(BackupSection.INDEX, BackupSection.DISMISSED),
			sectionsOf(listOf("index", "dismissed")),
		)
		assertEquals(setOf(BackupSection.DISMISSED), sectionsOf(listOf("backup/Dismissed.json")))
	}

	@Test
	fun `every section can be told apart by name and has its own label to pick it by`() {
		assertEquals(BackupSection.entries.size, BackupSection.entries.map { it.entryName }.toSet().size)
		val labelled = BackupSection.entries.filter { it != BackupSection.INDEX }
		val labels = labelled.map { BackupSectionModel(it, isChecked = true, isEnabled = true).titleResId }
		assertTrue(labels.all { it != 0 })
		assertEquals("two sections share a label", labelled.size, labels.toSet().size)
		assertEquals(R.string.dismissed_titles, BackupSectionModel(BackupSection.DISMISSED, true, true).titleResId)
	}
}
