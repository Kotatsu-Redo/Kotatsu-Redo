package org.koitharu.kotatsu.sourcescore.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.discover.data.DismissedEntry
import org.koitharu.kotatsu.discover.data.DismissedStore
import org.koitharu.kotatsu.discover.data.InMemoryKeyValueStore
import org.koitharu.kotatsu.discover.data.UploadState
import org.koitharu.kotatsu.discover.data.testEntry
import org.koitharu.kotatsu.discover.data.testManga
import org.koitharu.kotatsu.discover.domain.DiscoverRanking
import org.koitharu.kotatsu.sourcescore.data.ApiErrorDto
import org.koitharu.kotatsu.sourcescore.data.CommunityApiException
import org.koitharu.kotatsu.sourcescore.data.PassRequest
import org.koitharu.kotatsu.sourcescore.data.PassResponse
import java.io.IOException

class DismissalUploaderTest {

	private val entries = InMemoryKeyValueStore()
	private val withdrawals = InMemoryKeyValueStore()
	private val store = DismissedStore(entries, withdrawals)

	private var enabled = true
	private val sent = ArrayList<PassRequest>()

	/** What the server answers; may also do something while the request is on its way, or throw. */
	private var answer: (PassRequest) -> PassResponse = { PassResponse(workId = "w-${it.key}", recorded = true) }
	private val cachedWorks = HashMap<Pair<String, String>, String>()

	private val uploader = DismissalUploader(
		store = store,
		gate = { enabled },
		sender = { request ->
			sent += request
			answer(request)
		},
		works = { source, key -> cachedWorks[source to key] },
	)

	private fun keyOf(title: String) = DiscoverRanking.titleKey(title)

	private fun pass(title: String, url: String = "/$title", state: UploadState = UploadState.PENDING) {
		store.addAll(listOf(testEntry(testManga(title, url), state)))
	}

	private fun upload(title: String) = store.index().entries.getValue(keyOf(title)).upload

	private fun refused(status: Int): (PassRequest) -> PassResponse = { throw CommunityApiException(status, ApiErrorDto()) }

	@Test
	fun `nothing is sent while community features are off`() = runBlocking {
		enabled = false
		assertEquals(UploadState.NONE, uploader.stateFor(isRemote = true, source = "SRC", url = "/a"))
		pass("Alpha", state = uploader.stateFor(true, "SRC", "/Alpha"))
		uploader.run()
		assertTrue(sent.isEmpty())
	}

	@Test
	fun `a pass made while off is never sent after turning on`() = runBlocking {
		enabled = false
		pass("Alpha", state = uploader.stateFor(true, "SRC", "/Alpha"))
		enabled = true
		uploader.run()
		assertTrue(sent.isEmpty())
		assertEquals(UploadState.NONE, upload("Alpha"))
	}

	@Test
	fun `what was waiting when community features went off is dropped by the next run`() = runBlocking {
		pass("Alpha")
		enabled = false
		uploader.run()
		enabled = true
		uploader.run()
		assertTrue(sent.isEmpty())
		assertEquals(UploadState.NONE, upload("Alpha"))
	}

	@Test
	fun `only a manga the server could know starts out pending`() {
		assertEquals(UploadState.PENDING, uploader.stateFor(isRemote = true, source = "MANGADEX", url = "/title/1"))
		assertEquals(UploadState.NONE, uploader.stateFor(isRemote = false, source = "LOCAL", url = "/files/1"))
		assertEquals(UploadState.NONE, uploader.stateFor(isRemote = true, source = "MANGADEX", url = ""))
		assertEquals(UploadState.NONE, uploader.stateFor(isRemote = true, source = "bad source!", url = "/title/1"))
	}

	@Test
	fun `a recorded pass ends uploaded with the work id the server gave`() = runBlocking {
		pass("Alpha")
		uploader.run()
		assertEquals(1, sent.size)
		assertTrue(sent.single().passed)
		assertEquals(UploadState.UPLOADED, upload("Alpha"))
		assertEquals("w-/Alpha", store.index().entries.getValue(keyOf("Alpha")).workId)
	}

	@Test
	fun `the cached work id goes with the request, and a resolve is never asked for`() = runBlocking {
		cachedWorks["UNKNOWN" to "/Alpha"] = "w77"
		pass("Alpha")
		uploader.run()
		assertEquals("w77", sent.single().workId)
		assertEquals("UNKNOWN", sent.single().source)
		assertEquals("/Alpha", sent.single().key)
	}

	@Test
	fun `a manga the server does not know ends as none, so taking it back sends nothing`() = runBlocking {
		answer = { PassResponse(workId = null, recorded = false) }
		pass("Alpha")
		uploader.run()
		assertEquals(UploadState.NONE, upload("Alpha"))
		assertEquals(0, store.undo(listOf(keyOf("Alpha"))))
		answer = { PassResponse("x", true) }
		uploader.run()
		assertEquals(1, sent.size)
	}

	@Test
	fun `a work id is kept whenever the server gives one`() = runBlocking {
		answer = { PassResponse(workId = "w5", recorded = false) }
		pass("Alpha")
		uploader.run()
		assertEquals(UploadState.NONE, upload("Alpha"))
		assertEquals("w5", store.index().entries.getValue(keyOf("Alpha")).workId)
	}

	@Test
	fun `a refusal as invalid ends as none and is not tried again`() = runBlocking {
		answer = refused(400)
		pass("Alpha")
		pass("Beta")
		uploader.run()
		// The next one is still tried: a refusal of one says nothing about the server
		assertEquals(2, sent.size)
		assertEquals(UploadState.NONE, upload("Alpha"))
		assertEquals(UploadState.NONE, upload("Beta"))
		uploader.run()
		assertEquals(2, sent.size)
	}

	@Test
	fun `a server that does not know the call yet, is busy or is not there leaves it pending and stops the run`() {
		val failures: List<(PassRequest) -> PassResponse> = listOf(
			refused(404),
			refused(429),
			{ throw IOException("no network") },
		)
		for (failure in failures) {
			sent.clear()
			store.undoAll()
			answer = failure
			pass("Alpha")
			pass("Beta")
			runBlocking { uploader.run() }
			assertEquals("one request, then stop", 1, sent.size)
			assertEquals(UploadState.PENDING, upload("Alpha"))
			assertEquals(UploadState.PENDING, upload("Beta"))
		}
	}

	@Test
	fun `a run sends at most fifty`() = runBlocking {
		repeat(60) { pass("Series number $it") }
		uploader.run()
		assertEquals(50, sent.size)
		assertEquals(10, store.pendingUploads(100).size)
		uploader.run()
		assertEquals(60, sent.size)
	}

	@Test
	fun `withdrawals go first and count against the fifty`() = runBlocking {
		repeat(5) { pass("Taken back $it") }
		uploader.run()
		store.undo(store.index().entries.keys.toList())
		sent.clear()
		repeat(60) { pass("Series number $it") }
		uploader.run()
		assertEquals(50, sent.size)
		assertEquals(List(5) { false }, sent.take(5).map { it.passed })
		assertTrue(sent.drop(5).all { it.passed })
	}

	@Test
	fun `passing again on what the server has sends nothing, and taking it back after sends a withdrawal`() = runBlocking {
		pass("Alpha")
		uploader.run()
		assertEquals(1, sent.size)

		pass("Alpha")
		assertEquals(UploadState.UPLOADED, upload("Alpha"))
		assertEquals("w-/Alpha", store.index().entries.getValue(keyOf("Alpha")).workId)
		uploader.run()
		assertEquals(1, sent.size)

		store.undo(listOf(keyOf("Alpha")))
		uploader.run()
		assertEquals(2, sent.size)
		assertFalse(sent.last().passed)
		assertEquals("w-/Alpha", sent.last().workId)
		assertEquals(0, store.pendingWithdrawals(10).size)
	}

	@Test
	fun `passing again cancels a withdrawal that was waiting, and the server keeps the pass`() = runBlocking {
		pass("Alpha")
		uploader.run()
		store.undo(listOf(keyOf("Alpha")))
		assertEquals(1, store.pendingWithdrawals(10).size)

		pass("Alpha")

		assertEquals(0, store.pendingWithdrawals(10).size)
		assertEquals(UploadState.UPLOADED, upload("Alpha"))
		uploader.run()
		assertEquals(1, sent.size)
	}

	@Test
	fun `taking a pass back while it is on its way ends with a withdrawal`() = runBlocking {
		answer = { request ->
			if (request.passed) store.undo(listOf(keyOf("Alpha")))
			PassResponse(workId = "w1", recorded = true)
		}
		pass("Alpha")
		uploader.run()
		assertEquals(listOf(true, false), sent.map { it.passed })
		assertEquals("w1", sent.last().workId)
		assertEquals(0, store.pendingWithdrawals(10).size)
		assertTrue(store.index().isEmpty)
	}

	@Test
	fun `a withdrawal that cannot be sent waits for the next run`() = runBlocking {
		pass("Alpha")
		uploader.run()
		store.undo(listOf(keyOf("Alpha")))
		answer = { throw IOException("offline") }
		uploader.run()
		assertEquals(1, store.pendingWithdrawals(10).size)
		answer = { PassResponse(null, true) }
		uploader.run()
		assertEquals(0, store.pendingWithdrawals(10).size)
	}

	@Test
	fun `nothing restored from a backup is ever sent`() = runBlocking {
		store.restore(listOf(testEntry(testManga("Alpha"), UploadState.PENDING), testEntry(testManga("Beta"), UploadState.UPLOADED)))
		uploader.run()
		assertTrue(sent.isEmpty())
	}

	@Test
	fun `after the account is deleted nothing is left to send`() = runBlocking {
		pass("Alpha")
		pass("Beta")
		uploader.run()
		pass("Gamma")
		store.undo(listOf(keyOf("Beta")))
		sent.clear()

		store.forgetServerState()
		uploader.run()

		assertTrue(sent.isEmpty())
	}

	@Test
	fun `the cached work id is stored in the entry even when nothing is sent`() = runBlocking {
		cachedWorks["UNKNOWN" to "/Alpha"] = "w77"
		pass("Alpha", state = UploadState.NONE)
		uploader.attachWorkIds(listOf(keyOf("Alpha")))
		assertEquals("w77", store.index().entries.getValue(keyOf("Alpha")).workId)
		assertTrue(sent.isEmpty())
		// And the index then knows the work
		assertTrue(store.index().matches("Another title", emptyList(), null, null, "w77"))
	}

	@Test
	fun `no work id is looked up while community features are off`() = runBlocking {
		enabled = false
		cachedWorks["UNKNOWN" to "/Alpha"] = "w77"
		pass("Alpha", state = UploadState.NONE)
		uploader.attachWorkIds(listOf(keyOf("Alpha")))
		assertNull(store.index().entries.getValue(keyOf("Alpha")).workId)
	}

	@Test
	fun `entries from before addresses were kept are never sent`() = runBlocking {
		entries.values["oldtitle"] = "Action|Harem"
		val old = DismissedStore(entries, withdrawals)
		val oldUploader = DismissalUploader(old, { true }, { sent += it; PassResponse(null, true) }, { _, _ -> null })
		oldUploader.run()
		assertTrue(sent.isEmpty())
		assertEquals(DismissedEntry(tags = listOf("Action", "Harem")), old.index().entries.getValue("oldtitle"))
	}
}
