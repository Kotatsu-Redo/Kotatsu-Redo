package org.koitharu.kotatsu.discover.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.discover.domain.DiscoverRanking
import org.koitharu.kotatsu.parsers.model.Manga

/** Keeps strings in memory, the way preferences would on a device. */
class InMemoryKeyValueStore : KeyValueStore {

	val values = LinkedHashMap<String, String>()

	override fun all(): Map<String, String> = LinkedHashMap(values)

	override fun put(values: Map<String, String>) {
		this.values.putAll(values)
	}

	override fun remove(keys: Collection<String>) {
		keys.forEach { values.remove(it) }
	}

	override fun clear() {
		values.clear()
	}
}

fun testManga(title: String, url: String = "/$title"): Manga = Manga(
	id = title.hashCode().toLong(),
	title = title,
	altTitles = emptySet(),
	url = url,
	publicUrl = "https://example.com$url",
	rating = -1f,
	contentRating = null,
	coverUrl = null,
	tags = emptySet(),
	state = null,
	authors = emptySet(),
	source = UnknownMangaSource,
)

fun testEntry(manga: Manga, upload: UploadState, time: Long = 1L, workId: String? = null): Pair<String, DismissedEntry> {
	val key = DiscoverRanking.titleKey(manga.title)
	return key to DismissedEntry.of(manga, key, workId, upload, time)
}

class DismissedStoreTest {

	private val entries = InMemoryKeyValueStore()
	private val withdrawals = InMemoryKeyValueStore()
	private val store = DismissedStore(entries, withdrawals)
	private val a = testManga("Alpha Series")

	@Test
	fun `marking uploaded is seen by the very next read`() {
		store.addAll(listOf(testEntry(a, UploadState.PENDING)))
		val key = DiscoverRanking.titleKey(a.title)
		assertEquals(1, store.pendingUploads(10).size)
		assertTrue(store.markUploaded(key, "w1"))
		// The index of the two is equal, which a flow would have dropped
		assertEquals(UploadState.UPLOADED, store.index().entries.getValue(key).upload)
		assertEquals("w1", store.index().entries.getValue(key).workId)
		assertEquals(0, store.pendingUploads(10).size)
		// And what is stored is the same
		assertEquals(UploadState.UPLOADED, DismissedEntry.decode(entries.values.getValue(key)).upload)
	}

	@Test
	fun `taking an uploaded pass back leaves a withdrawal, and a pending one nothing`() {
		val b = testManga("Beta Series")
		store.addAll(listOf(testEntry(a, UploadState.PENDING), testEntry(b, UploadState.PENDING)))
		store.markUploaded(DiscoverRanking.titleKey(a.title), "w1")
		assertEquals(1, store.undo(listOf(DiscoverRanking.titleKey(a.title), DiscoverRanking.titleKey(b.title))))
		assertEquals(1, store.pendingWithdrawals(10).size)
		assertEquals("w1", store.pendingWithdrawals(10).single().second.workId)
		assertTrue(store.index().isEmpty)
	}

	@Test
	fun `undoing brings the title back`() {
		val key = store.run { addAll(listOf(testEntry(a, UploadState.NONE))); DiscoverRanking.titleKey(a.title) }
		assertTrue(store.index().matches(a))
		store.undo(listOf(key))
		assertFalse(store.index().matches(a))
		assertNull(entries.values[key])
	}

	@Test
	fun `a batch is all there for whoever looks next`() = runBlocking {
		assertTrue(store.observe().first().isEmpty)
		store.addAll((1..30).map { testEntry(testManga("Series number $it"), UploadState.NONE) })
		assertEquals(30, store.observe().first().size)
		assertEquals(30, entries.values.size)
	}

	@Test
	fun `switching off turns what is pending into none, and keeps what the server has and what waits to be taken back`() {
		val b = testManga("Beta Series")
		val c = testManga("Gamma Series")
		store.addAll(listOf(testEntry(a, UploadState.PENDING), testEntry(b, UploadState.PENDING), testEntry(c, UploadState.PENDING)))
		store.markUploaded(DiscoverRanking.titleKey(b.title), "w2")
		store.markUploaded(DiscoverRanking.titleKey(c.title), "w3")
		store.undo(listOf(DiscoverRanking.titleKey(c.title)))

		store.cancelPending()

		assertEquals(UploadState.NONE, store.index().entries.getValue(DiscoverRanking.titleKey(a.title)).upload)
		assertEquals(UploadState.UPLOADED, store.index().entries.getValue(DiscoverRanking.titleKey(b.title)).upload)
		assertEquals(0, store.pendingUploads(10).size)
		assertEquals(1, store.pendingWithdrawals(10).size)
		assertEquals(UploadState.NONE, DismissedEntry.decode(entries.values.getValue(DiscoverRanking.titleKey(a.title))).upload)
	}

	@Test
	fun `deleting the account clears what was sent and what waits to be taken back`() {
		val b = testManga("Beta Series")
		store.addAll(listOf(testEntry(a, UploadState.PENDING), testEntry(b, UploadState.PENDING)))
		store.markUploaded(DiscoverRanking.titleKey(a.title), "w1")
		store.markUploaded(DiscoverRanking.titleKey(b.title), "w2")
		store.undo(listOf(DiscoverRanking.titleKey(b.title)))

		store.forgetServerState()

		assertTrue(store.index().entries.values.all { it.upload == UploadState.NONE })
		assertEquals(0, store.pendingWithdrawals(10).size)
		assertTrue(withdrawals.values.isEmpty())
		// Still passed on, on this device
		assertTrue(store.index().matches(a))
	}

	@Test
	fun `a restored entry is never sent, and an entry already here wins`() {
		val key = DiscoverRanking.titleKey(a.title)
		store.restore(listOf(testEntry(a, UploadState.UPLOADED, workId = "w9"), "other" to DismissedEntry(tags = listOf("X"))))
		assertEquals(UploadState.NONE, store.index().entries.getValue(key).upload)
		assertEquals("w9", store.index().entries.getValue(key).workId)
		assertEquals(0, store.pendingUploads(10).size)

		store.restore(listOf(key to DismissedEntry(tags = listOf("Different"))))
		assertEquals(emptyList<String>(), store.index().entries.getValue(key).tags)
	}

	@Test
	fun `what was stored is read back by a new store`() {
		store.addAll(listOf(testEntry(a, UploadState.PENDING, workId = "w1")))
		val again = DismissedStore(entries, withdrawals)
		assertTrue(again.index().matches(a))
		assertEquals("w1", again.index().entries.values.single().workId)
	}
}
