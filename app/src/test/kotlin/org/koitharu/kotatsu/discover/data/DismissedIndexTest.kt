package org.koitharu.kotatsu.discover.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.discover.domain.DiscoverRanking
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag

class DismissedIndexTest {

	private fun manga(title: String, url: String = "/$title", alt: Set<String> = emptySet(), vararg tags: String) = Manga(
		id = title.hashCode().toLong(),
		title = title,
		altTitles = alt,
		url = url,
		publicUrl = "https://example.com$url",
		rating = -1f,
		contentRating = null,
		coverUrl = null,
		tags = tags.mapTo(LinkedHashSet()) { MangaTag(it, it, UnknownMangaSource) },
		state = null,
		authors = emptySet(),
		source = UnknownMangaSource,
	)

	private fun indexOf(manga: Manga, workId: String? = null): DismissedIndex {
		val key = DiscoverRanking.titleKey(manga.title)
		return DismissedIndex(mapOf(key to DismissedEntry.of(manga, key, workId, UploadState.NONE, 1000L)))
	}

	private val passed = manga("Solo Leveling", url = "/series/12", alt = setOf("Na Honjaman Level-Up", "!!"), "Action")

	@Test
	fun `a second name of a letter or two does not stand for every work that shares it`() {
		val index = indexOf(manga("Tower of God", alt = setOf("S", "II", "Sin-ui Tap")))
		assertFalse(index.matches(manga("Another One", url = "/other", alt = setOf("S"))))
		assertFalse(index.matches(manga("II", url = "/second")))
		assertTrue(index.matches(manga("Sin-ui Tap", url = "/elsewhere")))
		assertTrue(index.matches(manga("Kami no Tou", url = "/third", alt = setOf("Tower of God"))))
	}

	@Test
	fun `the same title matches whatever its punctuation`() {
		val index = indexOf(passed)
		assertTrue(index.matches(manga("solo-leveling!", url = "/other/99")))
	}

	@Test
	fun `another name of a passed title matches`() {
		val index = indexOf(passed)
		assertTrue(index.matches(manga("Only I Level Up", url = "/x/1", alt = setOf("Na Honjaman Level Up"))))
	}

	@Test
	fun `a stored other name matches a new title`() {
		val index = indexOf(passed)
		assertTrue(index.matches(manga("Na Honjaman Level Up", url = "/x/2")))
	}

	@Test
	fun `the same address matches under another title`() {
		val index = indexOf(passed)
		assertTrue(index.matches("Something else entirely", emptyList(), UnknownMangaSource.name, "/series/12", null))
	}

	@Test
	fun `the same address on another source does not match`() {
		val index = indexOf(passed)
		assertFalse(index.matches("Something else entirely", emptyList(), "OTHER", "/series/12", null))
	}

	@Test
	fun `the same work matches under another title and address`() {
		val index = indexOf(passed, workId = "309")
		assertTrue(index.matches("Something else entirely", emptyList(), "OTHER", "/y/1", "309"))
		assertFalse(index.matches("Something else entirely", emptyList(), "OTHER", "/y/1", "310"))
	}

	@Test
	fun `a near miss does not match`() {
		val index = indexOf(passed, workId = "309")
		assertFalse(index.matches(manga("Solo Leveling Ragnarok", url = "/series/13")))
		assertFalse(index.matches(manga("Solo Level", url = "/series/14")))
		assertFalse(index.matches("Solo", listOf("Leveling"), null, null, null))
	}

	@Test
	fun `a title of nothing but punctuation never stands for the rest`() {
		// Passed on itself: its key is empty, and so is that of every other title made of symbols
		val blank = DismissedEntry(tags = listOf("Action"), source = "SRC", url = "/blank", title = "???")
		val index = DismissedIndex(mapOf("" to blank))
		assertFalse(index.matches(manga("!!!", url = "/z/1")))
		assertFalse(index.matches("...", listOf("---"), null, null, null))
		assertFalse(index.matches(manga("Anything", url = "/z/2")))
		// Still found by where it is
		assertTrue(index.matches("...", emptyList(), "SRC", "/blank", null))
	}

	@Test
	fun `placeholders in the field of other names are no names`() {
		val placeholders = setOf("Updating", "Unknown", "None", "null", "Coming Soon", "TBA", "TBD", "N/A")
		val index = indexOf(manga("Real Title", alt = placeholders + "Actual Other Name"))
		val entry = index.entries.values.single()
		assertEquals(listOf(DiscoverRanking.titleKey("Actual Other Name")), entry.altKeys)
		assertFalse(index.matches(manga("Another Work", url = "/o/1", alt = placeholders)))
		// A main title is a title, whatever it is called
		val unknown = indexOf(manga("Unknown"))
		assertTrue(unknown.matches(manga("Unknown", url = "/o/2")))
		assertFalse(unknown.matches(manga("Other Work", url = "/o/3", alt = setOf("Unknown"))))
	}

	@Test
	fun `an index with nothing in it matches nothing`() {
		assertFalse(DismissedIndex.EMPTY.matches("A", listOf("B"), "S", "/u", "1"))
	}

	@Test
	fun `a value from before entries were kept as JSON is read as its tags`() {
		val entry = DismissedEntry.decode("Action|Harem|")
		assertEquals(listOf("Action", "Harem"), entry.tags)
		assertNull(entry.source)
		assertNull(entry.url)
		assertEquals(UploadState.NONE, entry.upload)
		assertEquals(emptyList<String>(), DismissedEntry.decode("").tags)

		val index = DismissedIndex(mapOf(DiscoverRanking.titleKey("Solo Leveling") to entry))
		assertTrue(index.matches(manga("Solo Leveling", url = "/anything")))
		assertEquals(listOf(listOf("Action", "Harem")), index.tagLists)
	}

	@Test
	fun `an entry survives being stored`() {
		val key = DiscoverRanking.titleKey(passed.title)
		val entry = DismissedEntry.of(passed, key, "309", UploadState.PENDING, 1234L)
		val back = DismissedEntry.decode(entry.encode())
		assertEquals(entry, back)
		assertEquals(listOf("Action"), back.tags)
		assertEquals(listOf(DiscoverRanking.titleKey("Na Honjaman Level-Up")), back.altKeys)
		assertEquals("Solo Leveling", back.title)
		assertEquals("/series/12", back.url)
		assertEquals(UnknownMangaSource.name, back.source)
		assertEquals("309", back.workId)
		assertEquals(UploadState.PENDING, back.upload)
	}

	@Test
	fun `the stored form is small json, and a damaged one still reads`() {
		val entry = DismissedEntry(tags = listOf("Action"), source = "MANGADEX", url = "/m/1", title = "T", time = 5L)
		assertEquals("""{"tags":["Action"],"source":"MANGADEX","url":"/m/1","title":"T","time":5}""", entry.encode())
		assertEquals(emptyList<String>(), DismissedEntry.decode("{not json").tags)
	}

	@Test
	fun `whether the server was told does not change what the index is`() {
		val key = DiscoverRanking.titleKey(passed.title)
		val pending = DismissedIndex(mapOf(key to DismissedEntry.of(passed, key, null, UploadState.PENDING, 1L)))
		val sent = DismissedIndex(mapOf(key to DismissedEntry.of(passed, key, null, UploadState.UPLOADED, 1L)))
		assertEquals(pending, sent)
	}
}
