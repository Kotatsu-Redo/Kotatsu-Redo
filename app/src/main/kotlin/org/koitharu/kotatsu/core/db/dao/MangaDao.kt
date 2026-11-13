package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.MangaTagsEntity
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.core.db.entity.TagEntity
import org.koitharu.kotatsu.core.model.UnknownMangaSource

@Dao
abstract class MangaDao {

	@Transaction
	@Query("SELECT * FROM manga WHERE manga_id = :id")
	abstract suspend fun find(id: Long): MangaWithTags?

	@Query("SELECT EXISTS(SELECT * FROM manga WHERE manga_id = :id)")
	abstract suspend operator fun contains(id: Long): Boolean

	@Transaction
	@Query("SELECT * FROM manga WHERE public_url = :publicUrl")
	abstract suspend fun findByPublicUrl(publicUrl: String): MangaWithTags?

	@Transaction
	@Query("SELECT * FROM manga WHERE source = :source")
	abstract suspend fun findAllBySource(source: String): List<MangaWithTags>

	@Transaction
	@Query("SELECT * FROM manga WHERE source = :source AND manga_id IN (SELECT manga_id FROM favourites UNION SELECT manga_id FROM history)")
	abstract suspend fun findLibraryBySource(source: String): List<MangaWithTags>

	@Query("SELECT author FROM manga WHERE author LIKE :query GROUP BY author ORDER BY COUNT(author) DESC LIMIT :limit")
	abstract suspend fun findAuthors(query: String, limit: Int): List<String>

    @Query("SELECT author FROM manga WHERE manga.source = :source AND author IS NOT NULL AND author != '' GROUP BY author ORDER BY COUNT(author) DESC LIMIT :limit")
    abstract suspend fun findAuthorsBySource(source: String, limit: Int): List<String>

	@Transaction
	@Query("SELECT * FROM manga WHERE (title LIKE :query OR alt_title LIKE :query) AND manga_id IN (SELECT manga_id FROM favourites UNION SELECT manga_id FROM history) LIMIT :limit")
	abstract suspend fun searchByTitle(query: String, limit: Int): List<MangaWithTags>

	@Transaction
	@Query("SELECT * FROM manga WHERE (title LIKE :query OR alt_title LIKE :query) AND source = :source AND manga_id IN (SELECT manga_id FROM favourites UNION SELECT manga_id FROM history) LIMIT :limit")
	abstract suspend fun searchByTitle(query: String, source: String, limit: Int): List<MangaWithTags>

	@Upsert
	protected abstract suspend fun upsert(manga: MangaEntity)

	@Query("SELECT source FROM manga WHERE manga_id = :id")
	protected abstract suspend fun findSourceName(id: Long): String?

	@Update(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun update(manga: MangaEntity): Int

	/** Touches nothing if they are the same already, so that nothing watching the table goes and reloads for no reason. */
	@Query(
		"""
		UPDATE manga SET cover_url = :coverUrl, large_cover_url = :largeCoverUrl
		WHERE manga_id = :id AND (cover_url != :coverUrl OR large_cover_url IS NOT :largeCoverUrl)
		""",
	)
	abstract suspend fun updateCover(id: Long, coverUrl: String, largeCoverUrl: String?)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun insertTagRelation(tag: MangaTagsEntity): Long

	@Query("DELETE FROM manga_tags WHERE manga_id = :mangaId")
	abstract suspend fun clearTagRelation(mangaId: Long)

	@Transaction
	@Delete
	abstract suspend fun delete(subjects: Collection<MangaEntity>)

	@Query(
		"""
		DELETE FROM manga WHERE NOT EXISTS(SELECT * FROM history WHERE history.manga_id == manga.manga_id) 
			AND NOT EXISTS(SELECT * FROM favourites WHERE favourites.manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM bookmarks WHERE bookmarks.manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM suggestions WHERE suggestions.manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM scrobblings WHERE scrobblings.manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM local_index WHERE local_index.manga_id == manga.manga_id)
			AND manga.manga_id NOT IN (:idsToKeep)
		""",
	)
	abstract suspend fun cleanup(idsToKeep: Set<Long>)

	@Transaction
	open suspend fun upsert(manga: MangaEntity, tags: Iterable<TagEntity>? = null) {
		val stored = manga.keepingItsSource()
		upsert(stored)
		// Tags that came with a manga of no source are tags of no source: the ones it has are left as they are
		if (tags != null && stored === manga) {
			clearTagRelation(manga.id)
			tags.map {
				MangaTagsEntity(manga.id, it.id)
			}.forEach {
				insertTagRelation(it)
			}
		}
	}

	/**
	 * A manga read while the app did not know its source comes back with no source at all, and written as such it
	 * would lose for good which source it is of. Sources come and go while the app runs now: one that only
	 * downloaded parsers have is unknown for as long as those are not loaded.
	 */
	private suspend fun MangaEntity.keepingItsSource(): MangaEntity {
		if (source != UnknownMangaSource.name) {
			return this
		}
		val known = findSourceName(id)
		return if (known == null || known == source) this else copy(source = known)
	}
}
