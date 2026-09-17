package org.koitharu.kotatsu.sourcescore.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A source score downloaded from the community server, cached on device.
 *
 * Cached rather than fetched on demand so that ranking keeps working with no network: a score that is
 * a few days stale is far better than no ordering at all, and these move on a scale of days anyway.
 */
@Entity(tableName = "source_score")
data class SourceScoreEntity(
	@PrimaryKey
	@ColumnInfo(name = "source") val source: String,
	@ColumnInfo(name = "stability") val stability: Float,
	@ColumnInfo(name = "popularity") val popularity: Float,
	@ColumnInfo(name = "composite") val composite: Float,
	/**
	 * Reporter-days behind the score. Below [MIN_TRUSTED_SAMPLES] the score is treated as unproven
	 * and replaced with the median, so a new source is not starved of the traffic it needs to earn
	 * one. Also what lets the UI honestly say "new source" instead of showing a confident number.
	 */
	@ColumnInfo(name = "samples") val samples: Int,
	@ColumnInfo(name = "updated_at") val updatedAt: Long,
) {

	val isProven: Boolean get() = samples >= MIN_TRUSTED_SAMPLES

	companion object {
		const val MIN_TRUSTED_SAMPLES = 5
	}
}
