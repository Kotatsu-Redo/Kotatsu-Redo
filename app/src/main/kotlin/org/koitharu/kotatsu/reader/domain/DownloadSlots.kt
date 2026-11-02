package org.koitharu.kotatsu.reader.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Who gets to download next. What is on the screen goes straight away. Everything else takes turns, in the order it asked,
 * which is nearest first: one while something on the screen is still coming, so that it has the connection to itself, and a
 * little more once it is here, to keep ahead of whoever is scrolling.
 *
 * A page that comes on screen while waiting goes to the front. One already downloading carries on, as it is nearly always the
 * nearest one, and stopping it would only throw away what has come so far.
 */
class DownloadSlots(
	private val maxOnScreen: Int = MAX_ON_SCREEN,
	private val maxAhead: Int = MAX_AHEAD,
	private val maxAheadWhileWaitedFor: Int = MAX_AHEAD_WHILE_WAITED_FOR,
) {

	private class Waiter(val pageId: Long) {
		val turn = CompletableDeferred<Unit>()
	}

	private val waiting = ArrayList<Waiter>()
	private val running = ArrayList<Long>()
	private val onScreen = HashSet<Long>()

	/** Whether this page is on the screen. A page can start or stop being, whatever it is doing at the time. */
	fun setOnScreen(pageId: Long, isOnScreen: Boolean) {
		synchronized(this) {
			val isChanged = if (isOnScreen) onScreen.add(pageId) else onScreen.remove(pageId)
			if (isChanged) startWhatCan()
		}
	}

	suspend fun <T> withSlot(pageId: Long, block: suspend () -> T): T {
		val waiter = Waiter(pageId)
		synchronized(this) {
			waiting += waiter
			startWhatCan()
		}
		try {
			waiter.turn.await()
		} catch (e: CancellationException) {
			synchronized(this) {
				// It may have been given a turn just as it was cancelled, and that has to go to someone
				if (!waiting.remove(waiter)) running.remove(pageId)
				startWhatCan()
			}
			throw e
		}
		try {
			return block()
		} finally {
			synchronized(this) {
				running.remove(pageId)
				startWhatCan()
			}
		}
	}

	private fun startWhatCan() {
		while (true) {
			val next = next() ?: return
			waiting.remove(next)
			running += next.pageId
			next.turn.complete(Unit)
		}
	}

	private fun next(): Waiter? {
		val total = running.size
		waiting.firstOrNull { it.pageId in onScreen }?.let { first ->
			if (total < maxOnScreen) return first
		}
		val runningAhead = running.count { it !in onScreen }
		val isWaitedFor = running.any { it in onScreen } || waiting.any { it.pageId in onScreen }
		if (runningAhead >= if (isWaitedFor) maxAheadWhileWaitedFor else maxAhead) {
			return null
		}
		return waiting.firstOrNull { it.pageId !in onScreen }
	}

	private companion object {
		const val MAX_ON_SCREEN = 3
		const val MAX_AHEAD = 2
		const val MAX_AHEAD_WHILE_WAITED_FOR = 1
	}
}
