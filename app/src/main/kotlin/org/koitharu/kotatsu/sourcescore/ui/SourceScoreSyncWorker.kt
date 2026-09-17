package org.koitharu.kotatsu.sourcescore.ui

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.koitharu.kotatsu.sourcescore.domain.SourceScoreSyncUseCase
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Exchanges telemetry and scores with the community server once a day.
 *
 * Once a day, not per request: probes are already aggregated on device, scores move on a scale of
 * days, and a background job that runs constantly is a battery complaint waiting to happen.
 */
@HiltWorker
class SourceScoreSyncWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val syncUseCase: SourceScoreSyncUseCase,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		val synced = syncUseCase()
		// Retry rather than fail: the next window is a day away, and a transient outage should not
		// cost a day of ranking freshness.
		return if (synced) Result.success() else Result.retry()
	}

	@Reusable
	class Scheduler @Inject constructor(
		private val workManager: WorkManager,
	) {

		/**
		 * Always scheduled: score downloads run for everyone, community account or not. What the job
		 * does beyond that is decided inside it from the current toggles, so changing them never needs
		 * a reschedule.
		 */
		fun schedule() {
			val constraints = Constraints.Builder()
				// Unmetered only: this is a background nicety, and nobody should pay for it.
				.setRequiredNetworkType(NetworkType.UNMETERED)
				.setRequiresBatteryNotLow(true)
				.build()
			val request = PeriodicWorkRequestBuilder<SourceScoreSyncWorker>(1, TimeUnit.DAYS)
				.setConstraints(constraints)
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
				.addTag(TAG)
				.build()
			workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, request)
		}

		fun unschedule() {
			workManager.cancelUniqueWork(TAG)
		}
	}

	private companion object {
		const val TAG = "source_score_sync"
	}
}
