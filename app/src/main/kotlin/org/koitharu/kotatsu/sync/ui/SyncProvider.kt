package org.koitharu.kotatsu.sync.ui

import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQueryBuilder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.koitharu.kotatsu.core.db.*
import java.util.concurrent.Callable

abstract class SyncProvider : ContentProvider() {

	private val entryPoint by lazy {
		EntryPointAccessors.fromApplication(checkNotNull(context), SyncProviderEntryPoint::class.java)
	}
	private val database by lazy { entryPoint.database }

	private val supportedTables = setOf(
		TABLE_FAVOURITES,
		TABLE_MANGA,
		TABLE_TAGS,
		TABLE_FAVOURITE_CATEGORIES,
		TABLE_HISTORY,
		TABLE_MANGA_TAGS,
	)

	override fun onCreate(): Boolean {
		return true
	}

	override fun query(
		uri: Uri,
		projection: Array<out String>?,
		selection: String?,
		selectionArgs: Array<out String>?,
		sortOrder: String?,
	): Cursor? {
		val tableName = getTableName(uri) ?: return null
		val sqlQuery = SupportSQLiteQueryBuilder.builder(tableName)
			.columns(projection)
			.selection(selection, selectionArgs)
			.orderBy(sortOrder)
			.create()
		return database.openHelper.readableDatabase.query(sqlQuery)
	}

	override fun getType(uri: Uri): String? {
		return getTableName(uri)?.let { "vnd.android.cursor.dir/" }
	}

	override fun insert(uri: Uri, values: ContentValues?): Uri? {
		val table = getTableName(uri)
		if (values == null || table == null) {
			return null
		}
		val db = database.openHelper.writableDatabase
		if (db.insert(table, SQLiteDatabase.CONFLICT_IGNORE, values) < 0) {
			db.update(table, values, uri.getQueryParameter(PARAM_UNCHANGED_SINCE)?.toLongOrNull())
		}
		return uri
	}

	override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
		val table = getTableName(uri) ?: return 0
		return database.openHelper.writableDatabase.delete(table, selection, selectionArgs)
	}

	override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
		val table = getTableName(uri)
		if (values == null || table == null) {
			return 0
		}
		return database.openHelper.writableDatabase
			.update(table, SQLiteDatabase.CONFLICT_IGNORE, values, selection, selectionArgs)
	}

	override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
		return runAtomicTransaction { super.applyBatch(operations) }
	}

	override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int {
		return runAtomicTransaction { super.bulkInsert(uri, values) }
	}

	private fun getTableName(uri: Uri): String? {
		return uri.pathSegments.singleOrNull()?.takeIf { it in supportedTables }
	}

	private fun <R> runAtomicTransaction(callable: Callable<R>): R {
		return synchronized(database) {
			database.runInTransaction(callable)
		}
	}

	/**
	 * Replaces a row with the copy a sync brought back, unless it has changed here since that sync read it.
	 *
	 * A sync sends everything, waits for the server, and writes back what the server answers, which for a big
	 * library takes a while. Whatever was removed or read further in the meantime was overwritten with the older
	 * copy: a manga taken out of history right after reading it came back a moment later, and so did a favourite.
	 * The row that changed here is the newer one, and goes up with the next sync.
	 *
	 * @param unchangedSince when the sync read its rows, or null to replace the row whatever its state
	 */
	private fun SupportSQLiteDatabase.update(table: String, values: ContentValues, unchangedSince: Long?) {
		val keys = when (table) {
			TABLE_TAGS -> listOf("tag_id")
			TABLE_MANGA_TAGS -> listOf("tag_id", "manga_id")
			TABLE_MANGA -> listOf("manga_id")
			TABLE_FAVOURITES -> listOf("manga_id", "category_id")
			TABLE_FAVOURITE_CATEGORIES -> listOf("category_id")
			TABLE_HISTORY -> listOf("manga_id")
			else -> throw IllegalArgumentException("Update for $table is not supported")
		}
		// The columns that say when a row was last touched here
		val changeColumns = when {
			unchangedSince == null -> emptyList()
			table == TABLE_HISTORY -> listOf("updated_at", "deleted_at")
			table == TABLE_FAVOURITES || table == TABLE_FAVOURITE_CATEGORIES -> listOf("created_at", "deleted_at")
			else -> emptyList()
		}
		val whereClause = keys.joinToString(" AND ") { "`$it` = ?" } + changeColumns.joinToString("") { " AND `$it` < ?" }
		val whereArgs = Array<Any>(keys.size + changeColumns.size) { i ->
			if (i < keys.size) values.get("`${keys[i]}`") ?: values.get(keys[i]) else checkNotNull(unchangedSince)
		}
		this.update(table, SQLiteDatabase.CONFLICT_IGNORE, values, whereClause, whereArgs)
	}

	companion object {

		/** On the address of an insert: the time before which a row must have last changed to be replaced. */
		const val PARAM_UNCHANGED_SINCE = "unchanged_since"
	}

	@EntryPoint
	@InstallIn(SingletonComponent::class)
	interface SyncProviderEntryPoint {

		val database: MangaDatabase
	}
}
