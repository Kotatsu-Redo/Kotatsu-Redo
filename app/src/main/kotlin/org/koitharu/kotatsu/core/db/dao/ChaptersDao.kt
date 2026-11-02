package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.koitharu.kotatsu.core.db.entity.ChapterEntity

@Dao
abstract class ChaptersDao {

	@Query("SELECT * FROM chapters WHERE manga_id = :mangaId ORDER BY `index` ASC")
	abstract suspend fun findAll(mangaId: Long): List<ChapterEntity>

	@Query("SELECT * FROM chapters WHERE manga_id = :mangaId AND chapter_id = :chapterId")
	abstract suspend fun find(mangaId: Long, chapterId: Long): ChapterEntity?

	/**
	 * Newest upload dates in the branch of [chapterId]. Other translations release on their own schedule and together
	 * would look more frequent than the one being followed.
	 */
	@Query(
		"""
		SELECT upload_date FROM chapters
		WHERE manga_id = :mangaId
			AND upload_date > 0
			AND branch IS (SELECT branch FROM chapters WHERE manga_id = :mangaId AND chapter_id = :chapterId)
		ORDER BY upload_date DESC
		LIMIT :limit
		""",
	)
	abstract suspend fun findUploadDates(mangaId: Long, chapterId: Long, limit: Int): List<Long>

	@Query("DELETE FROM chapters WHERE manga_id = :mangaId")
	abstract suspend fun deleteAll(mangaId: Long)

	@Query("DELETE FROM chapters WHERE manga_id NOT IN (SELECT manga_id FROM history WHERE deleted_at = 0) AND manga_id NOT IN (SELECT manga_id FROM favourites WHERE deleted_at = 0)")
	abstract suspend fun gc()

	@Transaction
	open suspend fun replaceAll(mangaId: Long, entities: Collection<ChapterEntity>) {
		deleteAll(mangaId)
		insert(entities)
	}

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	protected abstract suspend fun insert(entities: Collection<ChapterEntity>)
}
