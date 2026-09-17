package org.koitharu.kotatsu.explore.data

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R

enum class SourcesSortOrder(
	@StringRes val titleResId: Int,
) {
	ALPHABETIC(R.string.by_name),

	/**
	 * Note this is *local* popularity - how many manga from the source are in this device's own
	 * database. [SCORE] is the community measure.
	 */
	POPULARITY(R.string.popular),
	MANUAL(R.string.manual),
	LAST_USED(R.string.last_used),

	/**
	 * Community reliability and popularity blended with this device's own experience of each source
	 * (`sourcescore/domain/SourceRank.kt`).
	 *
	 * Ordered in Kotlin rather than SQL, because the scores live in a separate Room database - kept
	 * apart so upstream Kotatsu's migrations never collide with this fork's.
	 */
	SCORE(R.string.by_reliability),
	;

	/** True when the ordering cannot be expressed as an ORDER BY over the sources table alone. */
	val isExternallyRanked: Boolean get() = this == SCORE
}
