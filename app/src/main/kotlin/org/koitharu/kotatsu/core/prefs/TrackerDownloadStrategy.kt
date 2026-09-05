package org.koitharu.kotatsu.core.prefs

import androidx.annotation.Keep

@Keep
enum class TrackerDownloadStrategy {

	/** Never download new chapters automatically. */
	DISABLED,

	/**
	 * Only for manga that already has chapters saved locally.
	 *
	 * Note this keys off downloaded files, not reading: a series read online never qualifies, and one
	 * whose read chapters were cleaned up silently stops qualifying.
	 */
	DOWNLOADED,

	/**
	 * For manga opened within [RECENT_READ_WINDOW_DAYS], whether or not anything is saved locally.
	 *
	 * "Have I read this lately" is the honest proxy for "do I still care about this", so a short break
	 * from a series keeps working while one abandoned months ago stops downloading on its own.
	 */
	RECENTLY_READ,

	;

	companion object {

		/** How recently a manga must have been read to count for [RECENTLY_READ]. */
		const val RECENT_READ_WINDOW_DAYS = 30L

		/**
		 * Newest chapters downloaded per manga per run. A dormant series that suddenly backfills its
		 * archive would otherwise fill the device in a single tick.
		 */
		const val MAX_CHAPTERS_PER_RUN = 5

		/** Auto-download is skipped below this much free space. */
		const val MIN_FREE_SPACE_BYTES = 2L * 1024 * 1024 * 1024
	}
}
