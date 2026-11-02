package org.koitharu.kotatsu.suggestions.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class WorkResolutionTest {

	private class Missing : Exception()

	private class Site(
		val known: Set<String> = emptySet(),
		val failing: Map<String, () -> Throwable> = emptyMap(),
	) {
		val requests = ArrayList<String>()
		private val running = AtomicInteger()
		var mostAtOnce = 0

		suspend fun fetch(key: String): String {
			synchronized(requests) { requests += key }
			mostAtOnce = maxOf(mostAtOnce, running.incrementAndGet())
			delay(10)
			running.decrementAndGet()
			failing[key]?.let { throw it() }
			return key
		}
	}

	private fun address(source: String, key: String) = WorkAddress(source, key)

	private suspend fun resolve(
		site: Site,
		works: List<List<WorkAddress<String>>>,
		maxFetches: Int = 4,
		parallelism: Int = 2,
	) = resolveWorks(
		works = works,
		maxFetches = maxFetches,
		parallelism = parallelism,
		addresses = { it },
		known = { _, a -> a.key.takeIf { it in site.known } },
		fetch = { _, a -> site.fetch(a.key) },
		isOnlyThisPageMissing = { it is Missing },
	)

	@Test
	fun `what the device already has costs no request`() = runTest {
		val site = Site(known = setOf("a", "b", "c"))
		val result = resolve(site, listOf("a", "b", "c").map { listOf(address("S", it)) })
		assertEquals(listOf("a", "b", "c"), result)
		assertEquals(emptyList<String>(), site.requests)
	}

	@Test
	fun `no more requests than the limit however many works there are`() = runTest {
		val site = Site()
		val result = resolve(site, (1..8).map { listOf(address("S", "w$it")) })
		assertEquals(4, site.requests.size)
		assertEquals(listOf("w1", "w2", "w3", "w4"), result.filterNotNull())
	}

	@Test
	fun `a known work does not use up the limit`() = runTest {
		val site = Site(known = setOf("w8"))
		val result = resolve(site, (1..8).map { listOf(address("S", "w$it")) }, maxFetches = 2)
		assertEquals(listOf("w1", "w2", "w8"), result.filterNotNull())
		assertEquals(2, site.requests.size)
	}

	@Test
	fun `no more at once than allowed`() = runTest {
		val site = Site()
		resolve(site, (1..8).map { listOf(address("S", "w$it")) }, maxFetches = 8, parallelism = 2)
		assertEquals(2, site.mostAtOnce)
	}

	@Test
	fun `a failed attempt counts against the limit`() = runTest {
		val site = Site(failing = mapOf("a1" to ::Missing, "a2" to ::Missing, "a3" to ::Missing))
		val work = listOf(address("A", "a1"), address("B", "a2"), address("C", "a3"), address("D", "a4"), address("E", "a5"))
		assertEquals(listOf("a4"), resolve(site, listOf(work)))
		assertEquals(4, site.requests.size)
	}

	@Test
	fun `a failing site is not asked again in the same pass`() = runTest {
		val site = Site(failing = (1..8).associate { "w$it" to { IOException("refused") } })
		resolve(site, (1..8).map { listOf(address("S", "w$it")) }, maxFetches = 8, parallelism = 1)
		assertEquals(listOf("w1"), site.requests)
	}

	@Test
	fun `one missing page says nothing about the site`() = runTest {
		val site = Site(failing = mapOf("w1" to ::Missing))
		val result = resolve(site, (1..3).map { listOf(address("S", "w$it")) }, parallelism = 1)
		assertEquals(listOf(null, "w2", "w3"), result)
	}

	@Test
	fun `a work moves on to its next source when one fails`() = runTest {
		val site = Site(failing = mapOf("a" to { IOException("refused") }))
		val result = resolve(site, listOf(listOf(address("A", "a"), address("B", "b"))))
		assertEquals(listOf("b"), result)
	}
}
