package org.koitharu.kotatsu.sourcescore.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SourceStatsDao {

	@Query("SELECT * FROM source_stats")
	abstract suspend fun findAll(): List<SourceStatsEntity>

	@Query("SELECT * FROM source_stats")
	abstract fun observeAll(): Flow<List<SourceStatsEntity>>

	@Query("SELECT * FROM source_stats WHERE source = :source")
	abstract suspend fun find(source: String): SourceStatsEntity?

	@Upsert
	abstract suspend fun upsert(entity: SourceStatsEntity)

	@Query("DELETE FROM source_stats")
	abstract suspend fun clear()

	/**
	 * Records one observation.
	 *
	 * Read-modify-write inside a transaction rather than a bare UPDATE with arithmetic, because the
	 * latency EMA and the consecutive-failure reset both depend on the previous value.
	 */
	@Transaction
	open suspend fun record(
		source: String,
		isSuccess: Boolean,
		isEmpty: Boolean,
		isCloudflareBlocked: Boolean,
		latencyMs: Int,
		now: Long,
	) {
		val previous = find(source) ?: SourceStatsEntity(source = source)
		val latency = when {
			latencyMs <= 0 -> previous.latencyEmaMs
			previous.latencyEmaMs == 0 -> latencyMs
			// EMA at 0.25, so the score reacts within a handful of requests without being defined by
			// one unlucky one.
			else -> (previous.latencyEmaMs * 0.75 + latencyMs * 0.25).toInt()
		}
		upsert(
			previous.copy(
				okCount = previous.okCount + if (isSuccess) 1 else 0,
				failCount = previous.failCount + if (isSuccess) 0 else 1,
				emptyCount = previous.emptyCount + if (isEmpty) 1 else 0,
				cfCount = previous.cfCount + if (isCloudflareBlocked) 1 else 0,
				latencyEmaMs = latency,
				lastOkAt = if (isSuccess) now else previous.lastOkAt,
				lastFailAt = if (isSuccess) previous.lastFailAt else now,
				// Any success closes the circuit breaker. A source that came back should be usable
				// immediately, not after a cooldown that no longer reflects reality.
				consecutiveFailures = if (isSuccess) 0 else previous.consecutiveFailures + 1,
			),
		)
	}

	/** Called after a successful upload, so the next upload sends only what happened since. */
	@Query(
		"""UPDATE source_stats SET uploaded_ok = ok_count, uploaded_fail = fail_count,
			uploaded_empty = empty_count, uploaded_cf = cf_count WHERE source = :source""",
	)
	abstract suspend fun markUploaded(source: String)
}

@Dao
abstract class SourceScoreDao {

	@Query("SELECT * FROM source_score")
	abstract suspend fun findAll(): List<SourceScoreEntity>

	@Query("SELECT * FROM source_score")
	abstract fun observeAll(): Flow<List<SourceScoreEntity>>

	@Query("SELECT * FROM source_score WHERE source = :source")
	abstract suspend fun find(source: String): SourceScoreEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	abstract suspend fun insertAll(entities: Collection<SourceScoreEntity>)

	@Query("DELETE FROM source_score")
	abstract suspend fun clear()

	/** Scores arrive as a complete snapshot, so replacing wholesale keeps removals in sync. */
	@Transaction
	open suspend fun replaceAll(entities: Collection<SourceScoreEntity>) {
		clear()
		insertAll(entities)
	}

	@Query("SELECT MAX(updated_at) FROM source_score")
	abstract suspend fun lastUpdatedAt(): Long?
}
