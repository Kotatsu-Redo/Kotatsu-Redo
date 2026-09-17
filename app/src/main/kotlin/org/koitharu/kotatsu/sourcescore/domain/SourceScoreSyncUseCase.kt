package org.koitharu.kotatsu.sourcescore.domain

import android.util.Log
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sourcescore.data.CommunityApi
import org.koitharu.kotatsu.sourcescore.data.CommunityDatabase
import org.koitharu.kotatsu.sourcescore.data.CommunitySettings
import org.koitharu.kotatsu.sourcescore.data.ProbeDto
import org.koitharu.kotatsu.sourcescore.data.SourceScoreEntity
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

private const val TAG = "SourceScoreSync"

/**
 * One pass of the community exchange: register if needed, push what this device learned, pull what
 * everyone else did.
 *
 * Only the pull is for everyone. Scores are public aggregate data fetched without any identity, so a
 * user who never turned community features on still gets search and the source list ordered by what
 * the community measured. Registration, the nickname and the telemetry upload all need an account and
 * stay behind the community switch.
 *
 * Ordering matters. Uploading before downloading means a device's own recent experience is already
 * reflected in the scores it receives on the next run, and a failed upload never blocks the download
 * that makes ranking useful.
 */
class SourceScoreSyncUseCase @Inject constructor(
	private val api: CommunityApi,
	private val database: CommunityDatabase,
	private val settings: CommunitySettings,
) {

	suspend operator fun invoke(): Boolean {
		if (settings.isEnabled) {
			syncAccount()
		}
		return downloadScores()
	}

	private suspend fun syncAccount() {
		// Registration is no longer this job's to do - the api registers on its first 401, so it
		// happens the moment anything is actually used rather than whenever this worker next gets
		// unmetered Wi-Fi. Kept here only so a device that syncs before it browses still arrives
		// registered, and so the device identifiers travel on this call rather than an ordinary one.
		if (!settings.isRegistered) {
			runCatchingCancellable { api.hello() }
				.onFailure { Log.w(TAG, "Registration failed; the next authenticated call retries", it) }
				.onSuccess { identity ->
					settings.isRegistered = true
					// A key can arrive without its nickname - an older backup, or one typed in by
					// hand on a new phone. The server knows it, so take it instead of asking.
					if (settings.nickname.isNullOrEmpty() && !identity.nickname.isNullOrEmpty()) {
						settings.nickname = identity.nickname
						settings.syncedNickname = identity.nickname
					}
				}
		}

		// The nickname is chosen during onboarding, before the identity exists, so it is pushed on the
		// first sync after registration rather than from the dialog.
		pushNicknameIfPending()

		if (settings.isTelemetryEnabled) {
			uploadPending()
		}
	}

	/** Retried until the server accepts it, and skipped once it has. */
	private suspend fun pushNicknameIfPending() {
		val nickname = settings.nickname ?: return
		if (nickname == settings.syncedNickname) return
		runCatchingCancellable { api.setNickname(nickname) }
			.onFailure { Log.w(TAG, "Nickname push failed; will retry next sync", it) }
			.onSuccess { settings.syncedNickname = nickname }
	}

	/**
	 * Uploads only the delta since the last successful upload, and marks each source as sent
	 * individually. A partial failure therefore costs at most a retry, never double counting.
	 */
	private suspend fun uploadPending() {
		val today = LocalDate.now(ZoneOffset.UTC).toEpochDay()
		if (settings.lastProbeUploadDay == today) {
			Log.d(TAG, "Probe snapshot already uploaded today; carrying new deltas into tomorrow")
			return
		}
		val dao = database.getSourceStatsDao()
		val pending = dao.findAll().filter { it.hasPending() }
		if (pending.isEmpty()) return

		// The server caps a request at 200 distinct sources. Mark each successful chunk immediately:
		// if a later chunk fails, retrying must not send an already accepted daily delta again under
		// tomorrow's reporter id and accidentally give it a second day of influence.
		for (batch in pending.chunked(MAX_PROBE_SOURCES_PER_REQUEST)) {
			val probes = batch.map { stats ->
				ProbeDto(
					source = stats.source,
					op = ProbeOp.SEARCH.name,
					ok = stats.pendingOk(),
					fail = stats.pendingFail(),
					empty = stats.pendingEmpty(),
					cfBlocked = stats.pendingCf(),
					latencyP50Ms = stats.latencyEmaMs,
					latencyP90Ms = stats.latencyEmaMs * 2,
				)
			}
			val uploaded = runCatchingCancellable { api.uploadProbes(probes) }
				.onFailure { Log.w(TAG, "Probe upload failed; will retry the remaining delta", it) }
				.isSuccess
			if (!uploaded) return
			batch.forEach { dao.markUploaded(it.source) }
			// A daily row is replaced, not added to. Once any chunk succeeds, do not run a second
			// upload pass today: a source with new activity in between would otherwise replace its
			// accepted snapshot with only that newer delta. Unsent chunks stay pending for tomorrow.
			settings.lastProbeUploadDay = today
			Log.i(TAG, "Uploaded probes for ${probes.size} source(s)")
		}
	}

	private suspend fun downloadScores(): Boolean = runCatchingCancellable { api.fetchScores() }
		.onFailure { Log.w(TAG, "Score download failed; keeping the cached snapshot", it) }
		.map { response ->
			val now = System.currentTimeMillis()
			database.getSourceScoreDao().replaceAll(
				response.sources.map { dto ->
					SourceScoreEntity(
						source = dto.source,
						stability = dto.stability.toFloat(),
						popularity = dto.popularity.toFloat(),
						composite = dto.composite.toFloat(),
						samples = dto.samples,
						updatedAt = now,
					)
				},
			)
			settings.lastScoreSyncAt = now
			if (BuildConfig.DEBUG) {
				Log.i(TAG, "Cached ${response.sources.size} score(s) for region ${response.region}")
			}
			true
		}
		.getOrDefault(false)

	private companion object {
		/** Mirrors ProbeLimits.MAX_DISTINCT_SOURCES on the server. */
		const val MAX_PROBE_SOURCES_PER_REQUEST = 200
	}
}
