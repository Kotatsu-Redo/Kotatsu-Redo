package org.koitharu.kotatsu.sourcescore.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Maps a source's manga to a server work id, cached permanently.
 *
 * This one table is what makes the community features affordable: without it, every details screen
 * open would be a resolve call and the request rate would climb with usage. With it, a manga is
 * resolved once per device, ever - so traffic is bounded by distinct manga opened, which flattens
 * quickly (PLAN.md §3 Capacity, §5).
 */
@Entity(tableName = "work_ref", primaryKeys = ["source", "source_key"])
data class WorkRefEntity(
	@ColumnInfo(name = "source") val source: String,
	@ColumnInfo(name = "source_key") val sourceKey: String,
	@ColumnInfo(name = "work_id") val workId: String,
	@ColumnInfo(name = "resolved_at") val resolvedAt: Long,
)

@Dao
abstract class WorkRefDao {

	@Query("SELECT * FROM work_ref WHERE source = :source AND source_key = :sourceKey")
	abstract suspend fun find(source: String, sourceKey: String): WorkRefEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	abstract suspend fun upsert(entity: WorkRefEntity)

	/** Used when the server reports a work was merged away, so the stale id is not kept forever. */
	@Query("UPDATE work_ref SET work_id = :newWorkId WHERE work_id = :oldWorkId")
	abstract suspend fun repoint(oldWorkId: String, newWorkId: String)

	@Query("DELETE FROM work_ref")
	abstract suspend fun clear()
}
