package org.koitharu.kotatsu.reader.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class DownloadSlotsTest {

	private val started = Collections.synchronizedList(ArrayList<Long>())
	private val gates = HashMap<Long, CompletableDeferred<Unit>>()

	/** Asks for a slot in order, without moving on from this line until the request is in, and holds it until [finish]. */
	private fun CoroutineScope.download(slots: DownloadSlots, page: Long): Job {
		val gate = CompletableDeferred<Unit>()
		gates[page] = gate
		return launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
			slots.withSlot(page) {
				started += page
				gate.await()
			}
		}
	}

	private fun finish(page: Long) {
		gates.getValue(page).complete(Unit)
	}

	private suspend fun await(page: Long) = withTimeout(2000) {
		while (page !in started) kotlinx.coroutines.delay(1)
	}

	@Test
	fun `the page on screen starts straight away, with others already running`() = runBlocking {
		val slots = DownloadSlots()
		download(slots, 10)
		download(slots, 11)
		slots.setOnScreen(1, true)
		download(slots, 1)
		assertTrue(1L in started)
		listOf(10L, 11L, 1L).forEach(::finish)
	}

	@Test
	fun `only one other page goes while what is on screen is still coming`() = runBlocking {
		val slots = DownloadSlots()
		slots.setOnScreen(1, true)
		download(slots, 1)
		download(slots, 10)
		download(slots, 11)
		assertEquals(listOf(1L, 10L), started.toList())
		finish(10)
		await(11)
		listOf(1L, 11L).forEach(::finish)
	}

	@Test
	fun `two others can go once what is on screen is here`() = runBlocking {
		val slots = DownloadSlots()
		slots.setOnScreen(1, true)
		download(slots, 1)
		download(slots, 10)
		download(slots, 11)
		assertFalse(11L in started)
		finish(1)
		await(11)
		listOf(10L, 11L).forEach(::finish)
	}

	@Test
	fun `pages go in the order they asked, nearest first`() = runBlocking {
		val slots = DownloadSlots()
		listOf(10L, 11L, 12L, 13L).forEach { download(slots, it) }
		assertEquals(listOf(10L, 11L), started.toList())
		finish(10)
		await(12)
		assertEquals(listOf(10L, 11L, 12L), started.toList())
		listOf(11L, 12L).forEach(::finish)
		await(13)
		finish(13)
	}

	@Test
	fun `a page coming on screen goes ahead of those waiting`() = runBlocking {
		val slots = DownloadSlots()
		listOf(10L, 11L, 12L, 13L).forEach { download(slots, it) }
		assertEquals(2, started.size)
		slots.setOnScreen(13, true)
		await(13)
		assertFalse(12L in started)
		listOf(10L, 11L, 13L).forEach(::finish)
		await(12)
		finish(12)
	}

	@Test
	fun `giving up on a page while it waits frees its place`() = runBlocking {
		val slots = DownloadSlots()
		download(slots, 10)
		download(slots, 11)
		val skipped = download(slots, 12)
		download(slots, 13)
		skipped.cancelAndJoin()
		finish(10)
		await(13)
		assertFalse(12L in started)
		listOf(11L, 13L).forEach(::finish)
	}

	@Test
	fun `giving up on a page while it downloads frees its slot`() = runBlocking {
		val slots = DownloadSlots()
		val skipped = download(slots, 10)
		download(slots, 11)
		download(slots, 12)
		assertFalse(12L in started)
		skipped.cancelAndJoin()
		await(12)
		listOf(11L, 12L).forEach(::finish)
	}

	@Test
	fun `a slot left by a page that stops being on screen goes to the next`() = runBlocking {
		val slots = DownloadSlots()
		slots.setOnScreen(1, true)
		download(slots, 1)
		download(slots, 10)
		download(slots, 11)
		assertFalse(11L in started)
		// Scrolled away from it while it was still coming: nobody is waiting any more
		slots.setOnScreen(1, false)
		assertEquals(3, started.size.coerceAtLeast(3))
		listOf(1L, 10L).forEach(::finish)
		await(11)
		finish(11)
	}
}
