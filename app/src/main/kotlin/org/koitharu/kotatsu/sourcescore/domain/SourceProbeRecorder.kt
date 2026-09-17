package org.koitharu.kotatsu.sourcescore.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.exceptions.CloudFlareException
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.sourcescore.data.CommunityDatabase
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Which operation a probe describes. Mirrors the server's enum. */
enum class ProbeOp {
	SEARCH,
	DETAILS,
	PAGES,
}

/**
 * Records what happened when the app touched a source.
 *
 * Two things this deliberately never records: which manga was involved, and when, beyond the day. A
 * probe describes a *source*, so the resulting telemetry cannot describe a person's reading.
 */
@Singleton
class SourceProbeRecorder @Inject constructor(
	private val database: CommunityDatabase,
	@ApplicationContext private val context: Context,
) {

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	/** When each source last answered, in this process only - see [ProbeFailureFilter]. */
	private val lastSuccessAt = ConcurrentHashMap<String, Long>()

	/**
	 * Fire-and-forget: recording must never slow down or fail the operation being measured. A probe
	 * that goes missing costs a rounding error in a score; a probe that throws would break search.
	 */
	fun record(
		source: MangaSource,
		op: ProbeOp,
		isSuccess: Boolean,
		latencyMs: Long,
		isEmptyResult: Boolean = false,
		error: Throwable? = null,
	) {
		// Page loads happen on every chapter - an order of magnitude more traffic than search or
		// details for almost no extra signal, since a source that serves pages reliably serves
		// details reliably. Sampling keeps the table small without changing the picture.
		if (op == ProbeOp.PAGES && Random.nextInt(PAGES_SAMPLE_RATE) != 0) return
		// Only parser sources describe a website. The local library and external plugins went through
		// the same search path and were uploaded as if "LOCAL" were a source anyone else could use.
		// unwrap(): search hands over MangaSourceInfo wrappers, which are never MangaParserSource themselves.
		if (source.unwrap() !is MangaParserSource) return

		val now = System.currentTimeMillis()
		if (isSuccess) {
			lastSuccessAt[source.name] = now
		} else if (isCausedByThisPhone(source, error, now)) {
			// Counted nowhere: not in the local stats that drive the circuit breaker, and so never in
			// an upload either. A phone with no working internet says nothing about any source.
			return
		}

		scope.launch {
			runCatching {
				database.getSourceStatsDao().record(
					source = source.name,
					isSuccess = isSuccess,
					isEmpty = isEmptyResult,
					isCloudflareBlocked = error?.isCloudFlareBlock() == true,
					latencyMs = latencyMs.toInt().coerceIn(0, MAX_LATENCY_MS),
					now = now,
				)
			}.onFailure { it.printStackTraceDebug() }
		}
	}

	/**
	 * Times [block] and records the outcome, including when it throws.
	 *
	 * A cancellation is not a source failure - the user navigated away - so it is rethrown without
	 * being counted. Counting it would punish sources that appear on screens people leave quickly.
	 */
	suspend inline fun <T> measure(
		source: MangaSource,
		op: ProbeOp,
		isEmpty: (T) -> Boolean = { false },
		block: () -> T,
	): T {
		val startedAt = System.currentTimeMillis()
		return try {
			val result = block()
			record(
				source = source,
				op = op,
				isSuccess = true,
				latencyMs = System.currentTimeMillis() - startedAt,
				isEmptyResult = isEmpty(result),
			)
			result
		} catch (e: kotlinx.coroutines.CancellationException) {
			throw e
		} catch (e: Throwable) {
			record(
				source = source,
				op = op,
				isSuccess = false,
				latencyMs = System.currentTimeMillis() - startedAt,
				error = e,
			)
			throw e
		}
	}

	private fun isCausedByThisPhone(source: MangaSource, error: Throwable?, now: Long): Boolean {
		val isValidated = isNetworkValidated()
		if (error == null) return !isValidated
		val hasRecentSuccessElsewhere = lastSuccessAt.any { (name, at) ->
			name != source.name && now - at <= ProbeFailureFilter.RECENT_SUCCESS_WINDOW_MS
		}
		return ProbeFailureFilter.shouldDiscard(error, isValidated, hasRecentSuccessElsewhere)
	}

	/**
	 * Android's own verdict that the active network reaches the internet. Read directly rather than
	 * through NetworkState, which only checks that a network exists and can be forced online by the
	 * "disable offline check" setting - neither of which says whether a failure was the source's fault.
	 */
	private fun isNetworkValidated(): Boolean = runCatching {
		val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching true
		val network = manager.activeNetwork ?: return@runCatching false
		manager.getNetworkCapabilities(network)
			?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
	}.getOrDefault(true) // If the check itself fails, fall back to counting as before.

	companion object {

		const val PAGES_SAMPLE_RATE = 10
		const val MAX_LATENCY_MS = 120_000

		/**
		 * Both the blocked and captcha variants extend [CloudFlareException], and both mean the same
		 * thing for scoring: the source is unreachable for this user through no fault of the parser.
		 * It is counted separately from an ordinary failure because it is usually regional, which is
		 * exactly what the server's per-region stability is there to capture.
		 */
		fun Throwable.isCloudFlareBlock(): Boolean =
			this is CloudFlareException || cause is CloudFlareException
	}
}
