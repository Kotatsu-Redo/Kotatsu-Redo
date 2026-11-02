package org.koitharu.kotatsu.suggestions.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One place a work can be opened from. */
internal data class WorkAddress<S : Any>(val source: S, val key: String)

/**
 * Turns [works], best first, into something to show, with a hard limit on what that may cost.
 *
 * Everything here is optional content, such as a row of similar titles, fetched from the same sites the reader is
 * waiting on for the thing they actually opened. So the device's own copy is used wherever there is one, and only
 * the best of the rest get a request. A site that fails for any reason but a missing page is left alone for the rest
 * of the pass: more requests to one that has started refusing them only earn the reader a Cloudflare challenge.
 *
 * @param maxFetches how many requests all of this may cost, the failed ones included
 * @return one entry for each work, in the same order, null for those not resolved
 */
internal suspend fun <W, S : Any, R : Any> resolveWorks(
	works: List<W>,
	maxFetches: Int,
	parallelism: Int,
	addresses: (W) -> List<WorkAddress<S>>,
	known: suspend (W, WorkAddress<S>) -> R?,
	fetch: suspend (W, WorkAddress<S>) -> R,
	isOnlyThisPageMissing: (Throwable) -> Boolean,
): List<R?> = coroutineScope {
	val knownResults = works.map { work ->
		addresses(work).firstNotNullOfOrNull { address ->
			runCatchingCancellable { known(work, address) }.onFailure { it.printStackTraceDebug() }.getOrNull()
		}
	}
	val fetchesLeft = AtomicInteger(maxFetches)
	val failingSources = ConcurrentHashMap.newKeySet<S>()
	val permits = Semaphore(parallelism)
	works.mapIndexed { index, work ->
		async {
			knownResults[index] ?: permits.withPermit {
				for (address in addresses(work)) {
					if (address.source in failingSources) {
						continue
					}
					if (fetchesLeft.getAndDecrement() <= 0) {
						return@withPermit null
					}
					val result = runCatchingCancellable { fetch(work, address) }
					result.getOrNull()?.let { return@withPermit it }
					result.exceptionOrNull()?.let { error ->
						error.printStackTraceDebug()
						if (!isOnlyThisPageMissing(error)) {
							failingSources += address.source
						}
					}
				}
				null
			}
		}
	}.awaitAll()
}
