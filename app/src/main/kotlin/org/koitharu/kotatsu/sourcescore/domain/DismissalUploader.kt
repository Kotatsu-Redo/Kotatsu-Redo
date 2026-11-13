package org.koitharu.kotatsu.sourcescore.domain

import android.util.Log
import org.koitharu.kotatsu.discover.data.DismissedEntry
import org.koitharu.kotatsu.discover.data.DismissedStore
import org.koitharu.kotatsu.discover.data.UploadState
import org.koitharu.kotatsu.sourcescore.data.CommunityApiException
import org.koitharu.kotatsu.sourcescore.data.PassRequest
import org.koitharu.kotatsu.sourcescore.data.PassResponse
import java.io.IOException

/** Whether the reader has community features on. Asked every time: it can be switched off while this runs. */
fun interface CommunityGate {

	fun isEnabled(): Boolean
}

/** Sends one pass. Throws as the community client does: [CommunityApiException] for a refusal, [IOException] otherwise. */
fun interface PassSender {

	suspend fun send(request: PassRequest): PassResponse
}

/** The work id this device already has for a manga, if any. Never asks the server: that would be a resolve. */
fun interface WorkIdLookup {

	suspend fun find(source: String, key: String): String?
}

/**
 * The rules for telling the community server what a reader passed on. Plain Kotlin over small interfaces so that
 * they can be tested; [DismissalReporter] puts the real client, settings and database under it.
 *
 * Everything is sent at most once per call to [run], and a failure that says the server cannot take it now
 * (not updated, busy, unreachable) ends the run instead of trying the next one.
 */
class DismissalUploader(
	private val store: DismissedStore,
	private val gate: CommunityGate,
	private val sender: PassSender,
	private val works: WorkIdLookup,
) {

	/** What a pass made now starts as: to be sent only if community features are on and the server could know it. */
	fun stateFor(isRemote: Boolean, source: String, url: String): UploadState = when {
		gate.isEnabled() && isRemote && CommunityWorkResolver.workKeyOf(source, url) != null -> UploadState.PENDING
		else -> UploadState.NONE
	}

	/** Puts the work id this device has cached into the entries that lack one, so that it is stored even if nothing is sent. */
	suspend fun attachWorkIds(keys: Collection<String>) {
		if (!gate.isEnabled()) return
		val index = store.index()
		for (key in keys) {
			val entry = index.entries[key] ?: continue
			if (entry.workId != null) continue
			val (source, sourceKey) = CommunityWorkResolver.workKeyOf(entry.source.orEmpty(), entry.url.orEmpty()) ?: continue
			works.find(source, sourceKey)?.let { store.attachWorkId(key, it) }
		}
	}

	/** Withdrawals first, then passes, at most [limit] in all. */
	suspend fun run(limit: Int = MAX_PER_RUN) {
		if (!gate.isEnabled()) {
			// Whatever is waiting will not be sent later either
			store.cancelPending()
			return
		}
		var left = limit
		for ((key, entry) in store.pendingWithdrawals(left)) {
			if (!gate.isEnabled() || withdraw(key, entry) == Outcome.STOP) return
			left--
		}
		if (left <= 0) return
		for ((key, entry) in store.pendingUploads(left)) {
			if (!gate.isEnabled() || upload(key, entry) == Outcome.STOP) return
		}
	}

	private suspend fun upload(key: String, entry: DismissedEntry): Outcome =
		when (val reply = send(entry, passed = true)) {
			is Reply.Answer -> {
				val stillThere = if (reply.recorded) {
					store.markUploaded(key, reply.workId)
				} else {
					// The server does not know that manga and kept nothing, so there is nothing to take back later
					store.markNotOnServer(key, reply.workId)
				}
				if (!stillThere && reply.recorded) {
					// Taken back while the request was on its way: the server has it now and has to hear that too
					val told = entry.copy(upload = UploadState.UPLOADED, workId = reply.workId ?: entry.workId)
					store.addWithdrawal(key, told)
					withdraw(key, told)
				} else {
					Outcome.DONE
				}
			}

			Reply.Refused -> {
				store.markNotOnServer(key, null)
				Outcome.DONE
			}

			Reply.Unavailable -> Outcome.STOP
		}

	private suspend fun withdraw(key: String, entry: DismissedEntry): Outcome =
		when (send(entry, passed = false)) {
			is Reply.Answer, Reply.Refused -> {
				store.clearWithdrawal(key)
				Outcome.DONE
			}

			Reply.Unavailable -> Outcome.STOP
		}

	private suspend fun send(entry: DismissedEntry, passed: Boolean): Reply {
		val (source, sourceKey) = CommunityWorkResolver.workKeyOf(entry.source.orEmpty(), entry.url.orEmpty())
			?: return Reply.Refused
		return try {
			// Only what is already known: asking the server to resolve it would tell it the manga was opened
			val workId = entry.workId ?: works.find(source, sourceKey)
			val response = sender.send(PassRequest(workId = workId, source = source, key = sourceKey, passed = passed))
			Reply.Answer(response.workId, response.recorded)
		} catch (e: CommunityApiException) {
			log("The server answered ${e.status} to a pass", e)
			when (e.status) {
				// The server looked at it and will never take it
				400, 413, 422 -> Reply.Refused
				// Not updated yet, busy, or something not ours to fix: left for the next sync
				else -> Reply.Unavailable
			}
		} catch (e: IOException) {
			log("A pass could not be sent", e)
			Reply.Unavailable
		}
	}

	private fun log(message: String, error: Throwable) {
		// Not available in a plain JVM test
		runCatching { Log.w(TAG, message, error) }
	}

	private sealed interface Reply {
		class Answer(val workId: String?, val recorded: Boolean) : Reply
		data object Refused : Reply
		data object Unavailable : Reply
	}

	private enum class Outcome { DONE, STOP }

	companion object {
		const val MAX_PER_RUN = 50
		private const val TAG = "DismissalUploader"
	}
}
