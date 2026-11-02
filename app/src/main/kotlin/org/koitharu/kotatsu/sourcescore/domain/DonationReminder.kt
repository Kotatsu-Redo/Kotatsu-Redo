package org.koitharu.kotatsu.sourcescore.domain

import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sourcescore.data.CommunityApi
import org.koitharu.kotatsu.sourcescore.data.CommunitySettings
import javax.inject.Inject

/**
 * Asks for a donation about once a month, and never someone who already has.
 *
 * The month starts counting the first time this is asked, so nobody is asked on the day they install the app or
 * update to the version that has this. It is counted from when the reminder came up rather than from how it was
 * answered: being sent away from the app without answering is still having been asked.
 */
class DonationReminder @Inject constructor(
	private val settings: CommunitySettings,
	private val api: CommunityApi,
) {

	suspend fun takeIfDue(): Boolean {
		val now = System.currentTimeMillis()
		val last = settings.donationReminderAt
		if (!isDue(now, last)) {
			// A clock that has gone backwards would otherwise silence this until it caught up again
			if (last == NEVER || now < last) {
				settings.donationReminderAt = now
			}
			return false
		}
		settings.donationReminderAt = now
		return !isSupporter()
	}

	/** Asks the server only when it is about to matter, and only with an account that already exists. */
	private suspend fun isSupporter(): Boolean {
		if (settings.isEnabled && settings.isRegistered) {
			withTimeoutOrNull(SERVER_TIMEOUT_MS) {
				runCatchingCancellable { api.helloWithStatus() }
			}
		}
		return settings.isSupporter
	}

	companion object {

		private const val NEVER = 0L
		private const val INTERVAL_MS = 30L * 24 * 60 * 60 * 1000
		private const val SERVER_TIMEOUT_MS = 3_000L

		internal fun isDue(now: Long, last: Long): Boolean = last != NEVER && now - last >= INTERVAL_MS
	}
}
