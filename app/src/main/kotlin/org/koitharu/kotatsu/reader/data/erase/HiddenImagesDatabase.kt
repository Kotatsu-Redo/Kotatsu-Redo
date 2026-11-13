package org.koitharu.kotatsu.reader.data.erase

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

/** One image the reader has rubbed out. Never derived and never expired: only they can remove it. */
@Entity(tableName = "hidden_images")
data class HiddenImageEntity(
	@PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0L,
	@ColumnInfo(name = "hash_horizontal") val hashHorizontal: Long,
	@ColumnInfo(name = "hash_vertical") val hashVertical: Long,
	@ColumnInfo(name = "mean") val mean: Int,
	@ColumnInfo(name = "aspect") val aspect: Float,
	@ColumnInfo(name = "x_start") val xStart: Float,
	@ColumnInfo(name = "x_end") val xEnd: Float,
	@ColumnInfo(name = "created_at") val createdAt: Long,
	@ColumnInfo(name = "manga_title") val mangaTitle: String?,
	/** A small JPEG, only so the settings screen can show what would come back. Absent after a restore. */
	@ColumnInfo(name = "thumbnail", typeAffinity = ColumnInfo.BLOB) val thumbnail: ByteArray?,
	/** See [org.koitharu.kotatsu.reader.domain.erase.ImageSignature]. Absent on rows from version 1. */
	@ColumnInfo(name = "signature", typeAffinity = ColumnInfo.BLOB) val signature: ByteArray? = null,
	/** Set when too blank to recognise elsewhere: it then applies to this page only, at [yStart]. */
	@ColumnInfo(name = "page_id") val pageId: Long? = null,
	@ColumnInfo(name = "y_start") val yStart: Float? = null,
	/** Blank space to take with it, as a share of its height. See [org.koitharu.kotatsu.reader.domain.erase.TightRegion]. */
	@ColumnInfo(name = "pad_top") val padTop: Float? = null,
	@ColumnInfo(name = "pad_bottom") val padBottom: Float? = null,
	/** Set on the rest of a cut off image found next door: it comes and goes with the image it belongs to. */
	@ColumnInfo(name = "parent_id") val parentId: Long? = null,
	/** See [org.koitharu.kotatsu.reader.domain.erase.HiddenImageRule.removesRows]. */
	@ColumnInfo(name = "removes_rows") val removesRows: Boolean? = null,
	/** See [org.koitharu.kotatsu.reader.domain.erase.ImageColour]. Absent on rows from before version 6. */
	@ColumnInfo(name = "colour", typeAffinity = ColumnInfo.BLOB) val colour: ByteArray? = null,
	/** See [org.koitharu.kotatsu.reader.domain.erase.RuleDetail]. Absent on rows from before version 7. */
	@ColumnInfo(name = "keypoints", typeAffinity = ColumnInfo.BLOB) val keypoints: ByteArray? = null,
) {
	override fun equals(other: Any?) = other is HiddenImageEntity && other.id == id
	override fun hashCode() = id.hashCode()
}

/**
 * A page already found to be nothing but a hidden image.
 *
 * What lets the reader leave it out of the list before it is loaded at all, rather than finding out again
 * each time by downloading it: the difference between never seeing it and seeing a spinner in its place.
 */
@Entity(
	tableName = "hidden_pages",
	foreignKeys = [
		ForeignKey(
			entity = HiddenImageEntity::class,
			parentColumns = ["id"],
			childColumns = ["image_id"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index("image_id")],
)
data class HiddenPageEntity(
	@PrimaryKey @ColumnInfo(name = "page_id") val pageId: Long,
	@ColumnInfo(name = "image_id") val imageId: Long,
)

/**
 * The answer last worked out for a page, so that seeing it again does not mean decoding it again. Derived,
 * so it can go any time, and it is not part of a backup.
 */
@Entity(tableName = "page_scans", indices = [Index("scanned_at")])
data class PageScanEntity(
	@PrimaryKey @ColumnInfo(name = "page_id") val pageId: Long,
	/** Rule ids only ever grow, so anything above this was remembered after the page was looked at. */
	@ColumnInfo(name = "newest_rule_id") val newestRuleId: Long,
	@ColumnInfo(name = "rule_ids") val ruleIds: String,
	@ColumnInfo(name = "is_hidden") val isHidden: Boolean,
	@ColumnInfo(name = "source_width") val sourceWidth: Int,
	@ColumnInfo(name = "source_height") val sourceHeight: Int,
	@ColumnInfo(name = "bands") val bands: String,
	@ColumnInfo(name = "patches") val patches: String,
	@ColumnInfo(name = "scanned_at") val scannedAt: Long,
	/** Which matcher gave this answer. Zero on rows from before this was recorded, so they are redone. */
	@ColumnInfo(name = "matcher_version", defaultValue = "0") val matcherVersion: Int,
)

@Dao
abstract class HiddenImagesDao {

	@Query("SELECT * FROM page_scans WHERE page_id = :pageId")
	abstract suspend fun getScan(pageId: Long): PageScanEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	abstract suspend fun upsertScan(entity: PageScanEntity)

	@Query("DELETE FROM page_scans WHERE page_id NOT IN (SELECT page_id FROM page_scans ORDER BY scanned_at DESC LIMIT :keep)")
	abstract suspend fun trimScans(keep: Int)

	@Query("SELECT * FROM hidden_images ORDER BY created_at DESC")
	abstract fun observeAll(): Flow<List<HiddenImageEntity>>

	@Query("SELECT page_id FROM hidden_pages")
	abstract fun observeHiddenPages(): Flow<List<Long>>

	@Query("SELECT page_id FROM hidden_pages")
	abstract suspend fun getHiddenPages(): List<Long>

	@Insert
	abstract suspend fun insert(entity: HiddenImageEntity): Long

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun insertHiddenPage(entity: HiddenPageEntity)

	@Query("DELETE FROM hidden_pages WHERE image_id = :id")
	protected abstract suspend fun deleteHiddenPagesOf(id: Long)

	@Query("DELETE FROM hidden_images WHERE id = :id")
	protected abstract suspend fun deleteImage(id: Long)

	@Query("DELETE FROM hidden_pages WHERE image_id IN (SELECT id FROM hidden_images WHERE parent_id = :id)")
	protected abstract suspend fun deleteHiddenPagesOfRemainders(id: Long)

	@Query("DELETE FROM hidden_images WHERE parent_id = :id")
	protected abstract suspend fun deleteRemainders(id: Long)

	@Query("SELECT COUNT(*) FROM hidden_images WHERE parent_id = :parentId AND page_id = :pageId")
	abstract suspend fun countRemainders(parentId: Long, pageId: Long): Int

	/**
	 * Pages left out of the reader's list for this image together with others, whichever of them the page was noted against.
	 *
	 * A page is noted against one of the images that hid it. Two bands of a page of credits hide it between them, and with
	 * one of them taken back the note against the other stayed: the page was left out for good, the part of it that was
	 * meant to show again with it. A page whose reasons were not kept is looked over again too, as it cannot be known.
	 */
	@Query(
		"""DELETE FROM hidden_pages WHERE page_id IN (
			SELECT page_id FROM page_scans WHERE is_hidden = 1 AND (',' || rule_ids || ',') LIKE '%,' || :id || ',%'
		) OR page_id NOT IN (SELECT page_id FROM page_scans)""",
	)
	protected abstract suspend fun deleteHiddenPagesHiddenWith(id: Long)

	/** The same for what was found next door of this image, which goes with it. */
	@Query(
		"""DELETE FROM hidden_pages WHERE page_id IN (
			SELECT scan.page_id FROM page_scans scan JOIN hidden_images rest ON rest.parent_id = :id
			WHERE scan.is_hidden = 1 AND (',' || scan.rule_ids || ',') LIKE '%,' || rest.id || ',%'
		)""",
	)
	protected abstract suspend fun deleteHiddenPagesHiddenWithRemaindersOf(id: Long)

	@Transaction
	open suspend fun delete(id: Long) {
		deleteHiddenPagesHiddenWith(id)
		deleteHiddenPagesHiddenWithRemaindersOf(id)
		deleteHiddenPagesOfRemainders(id)
		deleteRemainders(id)
		deleteHiddenPagesOf(id)
		deleteImage(id)
	}

	@Query("SELECT COUNT(*) FROM hidden_images WHERE hash_horizontal = :horizontal AND hash_vertical = :vertical AND page_id IS :pageId")
	abstract suspend fun countSame(horizontal: Long, vertical: Long, pageId: Long?): Int
}

/**
 * Its own database for the reason [org.koitharu.kotatsu.sourcescore.data.CommunityDatabase] gives: a fork
 * adding `Migration28To29` to the shared one collides with upstream the day upstream adds its own.
 *
 * Unlike that one this holds what the reader decided rather than something that rebuilds itself, so it has
 * no destructive fallback. A schema change here has to ship a migration.
 */
@Database(
	entities = [HiddenImageEntity::class, HiddenPageEntity::class, PageScanEntity::class],
	version = 7,
	autoMigrations = [
		AutoMigration(from = 1, to = 2),
		AutoMigration(from = 2, to = 3),
		AutoMigration(from = 3, to = 4),
		AutoMigration(from = 4, to = 5, spec = ForgetUnreliableMatches::class),
		AutoMigration(from = 5, to = 6),
		AutoMigration(from = 6, to = 7),
	],
)
abstract class HiddenImagesDatabase : RoomDatabase() {
	abstract fun getDao(): HiddenImagesDao
}

/**
 * Builds before this one could mistake ordinary pages for a cut off image, and what they concluded was saved:
 * pages hidden whole, and strips removed from their neighbours. All of that is worked out from the images the
 * reader erased, which stay, so it is dropped and found again by a matcher that gets it right.
 */
class ForgetUnreliableMatches : AutoMigrationSpec {

	override fun onPostMigrate(db: SupportSQLiteDatabase) {
		db.execSQL("DELETE FROM hidden_pages")
		db.execSQL("DELETE FROM hidden_images WHERE parent_id IS NOT NULL")
		db.execSQL("DELETE FROM page_scans")
	}
}

@Module
@InstallIn(SingletonComponent::class)
object HiddenImagesModule {

	@Provides
	@Singleton
	fun provideHiddenImagesDatabase(@ApplicationContext context: Context): HiddenImagesDatabase = Room
		.databaseBuilder(context, HiddenImagesDatabase::class.java, "kotatsu-hidden-images-db")
		.build()
}
