package org.koitharu.kotatsu.discover.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow

/** Strings kept under keys, which is all [DismissedStore] asks of where it keeps them. */
interface KeyValueStore {

	fun all(): Map<String, String>

	fun put(values: Map<String, String>)

	fun remove(keys: Collection<String>)

	fun clear()
}

/**
 * What the reader passed on, and what the community server has been told about it.
 *
 * Plain Kotlin over a [KeyValueStore] so that the rules can be tested. The truth is [current], a field that is
 * replaced on every change: the flow is only for whoever wants to be told, and it may drop a value that equals
 * the last one (an index ignores whether the server was told), so nothing here ever reads state back from it.
 */
class DismissedStore(
	private val entries: KeyValueStore,
	/** Passes taken back after the server was told of them, until it has been told that too. */
	private val withdrawals: KeyValueStore,
) {

	private val lock = Any()

	@Volatile
	private var current: DismissedIndex? = null
	private val changes = MutableStateFlow<DismissedIndex?>(null)

	fun observe(): Flow<DismissedIndex> = flow {
		emit(index())
		emitAll(changes.filterNotNull())
	}.distinctUntilChanged()

	fun index(): DismissedIndex = current ?: synchronized(lock) {
		current ?: DismissedIndex(
			entries.all().mapValues { (_, value) -> DismissedEntry.decode(value) },
		).also { current = it }
	}

	private fun publish(map: Map<String, DismissedEntry>) {
		val index = DismissedIndex(map)
		current = index
		changes.value = index
	}

	private fun withdrawalMap(): Map<String, DismissedEntry> =
		withdrawals.all().mapValues { (_, value) -> DismissedEntry.decode(value) }

	/**
	 * Passes on all of these with one rebuild of the index.
	 *
	 * Passing again on something the server already has leaves it as the server has it: uploaded, with its work id,
	 * and nothing to send. The same goes for one that was taken back but whose withdrawal has not been sent yet.
	 */
	fun addAll(items: List<Pair<String, DismissedEntry>>) {
		if (items.isEmpty()) return
		synchronized(lock) {
			val map = LinkedHashMap(index().entries)
			val waiting = withdrawalMap()
			val cancelled = ArrayList<String>()
			val written = LinkedHashMap<String, String>()
			for ((key, entry) in items) {
				val existing = map[key]
				val withdrawn = waiting[key]
				val resolved = when {
					existing?.upload == UploadState.UPLOADED ->
						entry.copy(upload = UploadState.UPLOADED, workId = existing.workId ?: entry.workId)

					withdrawn != null -> {
						cancelled += key
						entry.copy(upload = UploadState.UPLOADED, workId = withdrawn.workId ?: entry.workId)
					}

					else -> entry.copy(workId = entry.workId ?: existing?.workId)
				}
				map[key] = resolved
				written[key] = resolved.encode()
			}
			entries.put(written)
			if (cancelled.isNotEmpty()) withdrawals.remove(cancelled)
			publish(map)
		}
	}

	/**
	 * Takes these back. Those the server was told of are left for a withdrawal.
	 *
	 * @return how many withdrawals now wait to be sent
	 */
	fun undo(keys: Collection<String>): Int = synchronized(lock) {
		val map = LinkedHashMap(index().entries)
		val toTell = LinkedHashMap<String, String>()
		for (key in keys) {
			val removed = map.remove(key) ?: continue
			if (removed.upload == UploadState.UPLOADED && removed.source != null && removed.url != null) {
				toTell[key] = removed.encode()
			}
		}
		entries.remove(keys)
		if (toTell.isNotEmpty()) withdrawals.put(toTell)
		publish(map)
		toTell.size
	}

	/** @return how many withdrawals now wait to be sent */
	fun undoAll(): Int = undo(index().entries.keys.toList())

	/**
	 * A backup brought these back. What is already here wins, and nothing restored is ever sent: the reader
	 * decides again, by passing on it again, whether the server hears of it.
	 */
	fun restore(items: List<Pair<String, DismissedEntry>>) {
		synchronized(lock) {
			val map = LinkedHashMap(index().entries)
			val written = LinkedHashMap<String, String>()
			for ((key, entry) in items) {
				if (key in map) continue
				val local = entry.copy(upload = UploadState.NONE)
				map[key] = local
				written[key] = local.encode()
			}
			if (written.isEmpty()) return
			entries.put(written)
			publish(map)
		}
	}

	/**
	 * The server recorded it.
	 *
	 * @return false if the entry is gone by now: it was taken back while the request was on its way
	 */
	fun markUploaded(key: String, workId: String?): Boolean = update(key) {
		it.copy(upload = UploadState.UPLOADED, workId = workId ?: it.workId)
	}

	/** The server does not take it, or did not know the manga, so it stays on this device only. */
	fun markNotOnServer(key: String, workId: String?): Boolean = update(key) {
		it.copy(upload = UploadState.NONE, workId = workId ?: it.workId)
	}

	/** The work it is, as far as this device has found out. */
	fun attachWorkId(key: String, workId: String): Boolean = update(key) {
		if (it.workId == null) it.copy(workId = workId) else it
	}

	private fun update(key: String, change: (DismissedEntry) -> DismissedEntry): Boolean = synchronized(lock) {
		val map = LinkedHashMap(index().entries)
		val entry = map[key] ?: return@synchronized false
		val updated = change(entry)
		if (updated != entry) {
			map[key] = updated
			entries.put(mapOf(key to updated.encode()))
			publish(map)
		}
		true
	}

	/** Waiting to be told to the server, oldest first. Entries from before addresses were kept are never in it. */
	fun pendingUploads(limit: Int): List<Pair<String, DismissedEntry>> = index().entries.asSequence()
		.filter { (_, entry) -> entry.upload == UploadState.PENDING && entry.source != null && entry.url != null }
		.sortedBy { (_, entry) -> entry.time }
		.take(limit)
		.map { (key, entry) -> key to entry }
		.toList()

	fun pendingWithdrawals(limit: Int): List<Pair<String, DismissedEntry>> = synchronized(lock) {
		withdrawalMap().entries.asSequence()
			.sortedBy { (_, entry) -> entry.time }
			.take(limit)
			.map { (key, entry) -> key to entry }
			.toList()
	}

	fun addWithdrawal(key: String, entry: DismissedEntry) = synchronized(lock) {
		withdrawals.put(mapOf(key to entry.encode()))
	}

	fun clearWithdrawal(key: String) = synchronized(lock) {
		withdrawals.remove(listOf(key))
	}

	/**
	 * Community features were switched off: whatever was still waiting to be sent never will be. What the server
	 * already has stays marked so, and withdrawals stay, as taking something back later only removes data.
	 */
	fun cancelPending() = synchronized(lock) {
		val map = LinkedHashMap(index().entries)
		val written = LinkedHashMap<String, String>()
		for ((key, entry) in map.entries.toList()) {
			if (entry.upload == UploadState.PENDING) {
				val local = entry.copy(upload = UploadState.NONE)
				map[key] = local
				written[key] = local.encode()
			}
		}
		if (written.isNotEmpty()) {
			entries.put(written)
			publish(map)
		}
	}

	/** The account was deleted, and with it everything the server had: nothing is left to send or take back. */
	fun forgetServerState() = synchronized(lock) {
		val map = LinkedHashMap(index().entries)
		val written = LinkedHashMap<String, String>()
		for ((key, entry) in map.entries.toList()) {
			if (entry.upload != UploadState.NONE) {
				val local = entry.copy(upload = UploadState.NONE)
				map[key] = local
				written[key] = local.encode()
			}
		}
		if (written.isNotEmpty()) {
			entries.put(written)
			publish(map)
		}
		withdrawals.clear()
	}
}
