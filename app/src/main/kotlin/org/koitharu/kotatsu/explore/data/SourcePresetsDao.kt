package org.koitharu.kotatsu.explore.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SourcePresetsDao {

	@Query("SELECT * FROM source_presets WHERE deleted_at = 0 ORDER BY sort_key")
	abstract suspend fun findAll(): List<SourcePresetEntity>

	@Query("SELECT * FROM source_presets WHERE deleted_at = 0 ORDER BY sort_key")
	abstract fun observeAll(): Flow<List<SourcePresetEntity>>

	@Query("SELECT * FROM source_presets WHERE preset_id = :id AND deleted_at = 0")
	abstract suspend fun find(id: Long): SourcePresetEntity?

	@Query("SELECT * FROM source_presets WHERE preset_id = :id AND deleted_at = 0")
	abstract fun observe(id: Long): Flow<SourcePresetEntity?>

	@Insert(onConflict = OnConflictStrategy.ABORT)
	abstract suspend fun insert(entity: SourcePresetEntity): Long

	@Query("UPDATE source_presets SET title = :title, languages = :languages WHERE preset_id = :id")
	abstract suspend fun update(id: Long, title: String, languages: String)

	@Query("UPDATE source_presets SET sources = :sources WHERE preset_id = :id")
	abstract suspend fun updateSources(id: Long, sources: String)

	@Query("UPDATE source_presets SET sort_key = :sortKey WHERE preset_id = :id")
	abstract suspend fun updateSortKey(id: Long, sortKey: Int)

	suspend fun delete(id: Long) = setDeletedAt(id, System.currentTimeMillis())

	@Query("DELETE FROM source_presets WHERE deleted_at != 0 AND deleted_at < :maxDeletionTime")
	abstract suspend fun gc(maxDeletionTime: Long)

	@Query("SELECT MAX(sort_key) FROM source_presets WHERE deleted_at = 0")
	protected abstract suspend fun getMaxSortKey(): Int?

	suspend fun getNextSortKey(): Int {
		return (getMaxSortKey() ?: 0) + 1
	}

	@Query("UPDATE source_presets SET deleted_at = :deletedAt WHERE preset_id = :id")
	protected abstract suspend fun setDeletedAt(id: Long, deletedAt: Long)
}
