package org.koitharu.kotatsu.sync.domain

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.Context
import android.content.OperationApplicationException
import android.content.SyncResult
import android.content.SyncStats
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITE_CATEGORIES
import org.koitharu.kotatsu.core.db.TABLE_HISTORY
import org.koitharu.kotatsu.core.db.TABLE_MANGA
import org.koitharu.kotatsu.core.db.TABLE_MANGA_TAGS
import org.koitharu.kotatsu.core.db.TABLE_TAGS
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.util.ext.buildContentValues
import org.koitharu.kotatsu.core.util.ext.map
import org.koitharu.kotatsu.core.util.ext.mapToSet
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.sync.data.SyncAuthApi
import org.koitharu.kotatsu.sync.data.SyncAuthenticator
import org.koitharu.kotatsu.sync.data.SyncInterceptor
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.data.model.FavouriteCategorySyncDto
import org.koitharu.kotatsu.sync.data.model.FavouriteSyncDto
import org.koitharu.kotatsu.sync.data.model.HistorySyncDto
import org.koitharu.kotatsu.sync.data.model.MangaSyncDto
import org.koitharu.kotatsu.sync.data.model.MangaTagSyncDto
import org.koitharu.kotatsu.sync.data.model.SyncDto
import org.koitharu.kotatsu.sync.ui.SyncProvider
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

/**
 * Whether the server's copy of a row is to replace the one here before the one here is sent: only when one of the
 * two has been removed and the other has not, and the server's is the later of them.
 *
 * @param localChangedAt when the row here was added
 * @param remoteChangedAt when the server's was
 */
internal fun isRemoteCopyNewer(
	localChangedAt: Long,
	localDeletedAt: Long,
	remoteChangedAt: Long,
	remoteDeletedAt: Long,
): Boolean {
	if ((localDeletedAt == 0L) == (remoteDeletedAt == 0L)) {
		return false
	}
	return maxOf(remoteChangedAt, remoteDeletedAt) > maxOf(localChangedAt, localDeletedAt)
}

class SyncHelper @AssistedInject constructor(
	@ApplicationContext context: Context,
	@BaseHttpClient baseHttpClient: OkHttpClient,
	@Assisted private val account: Account,
	@Assisted private val provider: ContentProviderClient,
	private val settings: SyncSettings,
) {

	private val json = Json {
		encodeDefaults = true
	}

	private val authorityHistory = context.getString(R.string.sync_authority_history)
	private val authorityFavourites = context.getString(R.string.sync_authority_favourites)
	private val mediaTypeJson = "application/json".toMediaType()
	private val httpClient = baseHttpClient.newBuilder()
		.authenticator(SyncAuthenticator(context, account, settings, SyncAuthApi(OkHttpClient())))
		.addInterceptor(SyncInterceptor(context, account))
		.build()
	private val baseUrl: String by lazy {
		settings.syncUrl
	}
	private val defaultGcPeriod: Long // gc period if sync enabled
		get() = TimeUnit.DAYS.toMillis(4)

	@WorkerThread
	fun syncFavourites(stats: SyncStats) {
		// Before anything is read: whatever changes here from now on is newer than what comes back
		val startedAt = System.currentTimeMillis()
		fetchFavourites()?.let { stats.numUpdates += takeNewer(it, startedAt) }
		val payload = json.encodeToString(
			SyncDto(
				history = null,
				favourites = getFavourites(),
				categories = getFavouriteCategories(),
				timestamp = System.currentTimeMillis(),
			),
		)
		val request = Request.Builder()
			.url("$baseUrl/resource/$TABLE_FAVOURITES")
			.post(payload.toRequestBody(mediaTypeJson))
			.build()
		val response = httpClient.newCall(request).execute().parseDtoOrNull()
		val hasChangedMeanwhile = hasChangedSince(authorityFavourites, TABLE_FAVOURITES, "created_at", startedAt)
		response?.categories?.let { categories ->
			val categoriesResult = upsertFavouriteCategories(categories, startedAt)
			stats.numDeletes += categoriesResult.firstOrNull()?.count?.toLong() ?: 0L
			stats.numInserts += categoriesResult.drop(1).sumOf { it.count?.toLong() ?: 0L }
		}
		response?.favourites?.let { favourites ->
			val favouritesResult = upsertFavourites(favourites, startedAt)
			stats.numDeletes += favouritesResult.firstOrNull()?.count?.toLong() ?: 0L
			stats.numInserts += favouritesResult.drop(1).sumOf { it.count?.toLong() ?: 0L }
			stats.numEntries += stats.numInserts + stats.numDeletes
		}
		gcFavourites()
		if (hasChangedMeanwhile) {
			ContentResolver.requestSync(account, authorityFavourites, Bundle.EMPTY)
		}
	}

	@Blocking
	@WorkerThread
	fun syncHistory(stats: SyncStats) {
		// Before anything is read: whatever changes here from now on is newer than what comes back
		val startedAt = System.currentTimeMillis()
		val payload = json.encodeToString(
			SyncDto(
				history = getHistory(),
				favourites = null,
				categories = null,
				timestamp = System.currentTimeMillis(),
			),
		)
		val request = Request.Builder()
			.url("$baseUrl/resource/$TABLE_HISTORY")
			.post(payload.toRequestBody(mediaTypeJson))
			.build()
		val response = httpClient.newCall(request).execute().parseDtoOrNull()
		val hasChangedMeanwhile = hasChangedSince(authorityHistory, TABLE_HISTORY, "updated_at", startedAt)
		response?.history?.let { history ->
			val result = upsertHistory(history, startedAt)
			stats.numDeletes += result.firstOrNull()?.count?.toLong() ?: 0L
			stats.numInserts += result.drop(1).sumOf { it.count?.toLong() ?: 0L }
			stats.numEntries += stats.numInserts + stats.numDeletes
		}
		gcHistory()
		if (hasChangedMeanwhile) {
			ContentResolver.requestSync(account, authorityHistory, Bundle.EMPTY)
		}
	}

	/**
	 * Whether anything was changed here while the server was being waited for. Such rows are kept as they are when
	 * the answer is written down, but the server has not heard of them, and the change itself asked for no sync as
	 * one was running. So another is asked for once this one is done, which ends when nothing changes any more.
	 *
	 * Only up to now: a row another phone wrote with its clock running ahead would otherwise count every time.
	 */
	private fun hasChangedSince(authority: String, table: String, changedColumn: String, startedAt: Long): Boolean {
		val from = startedAt.toString()
		val until = System.currentTimeMillis().toString()
		return provider.query(
			uri(authority, table),
			arrayOf("manga_id"),
			"(`$changedColumn` >= ? AND `$changedColumn` <= ?) OR (deleted_at >= ? AND deleted_at <= ?)",
			arrayOf(from, until, from, until),
			null,
		)?.use { it.count > 0 } == true
	}

	/**
	 * Takes over what another device has removed, or added back, later than the copy here was last touched, before
	 * the copy here is sent.
	 *
	 * The server keeps whichever copy of a row reaches it last, and a sync sends every row before it reads any. So a
	 * device that still had a manga put it back on the server each time, over its removal on another device: it never
	 * went from the one, and came back on the other once the record of the removal was cleared out there, days later.
	 *
	 * Rows that are not here at all, and everything else about a row, are left to the answer to what is sent.
	 *
	 * @return how many rows were taken
	 */
	@WorkerThread
	internal fun takeNewer(remote: SyncDto, startedAt: Long): Int {
		var taken = 0
		remote.categories?.let { categories ->
			val local = getTimes(TABLE_FAVOURITE_CATEGORIES, "category_id", null)
			val newer = categories.filter { remoteRow ->
				val times = local[RowKey(remoteRow.categoryId.toLong(), 0L)] ?: return@filter false
				isRemoteCopyNewer(times.createdAt, times.deletedAt, remoteRow.createdAt, remoteRow.deletedAt)
			}
			if (newer.isNotEmpty()) {
				upsertFavouriteCategories(newer, startedAt)
				taken += newer.size
			}
		}
		remote.favourites?.let { favourites ->
			val local = getTimes(TABLE_FAVOURITES, "manga_id", "category_id")
			val newer = favourites.filter { remoteRow ->
				val times = local[RowKey(remoteRow.mangaId, remoteRow.categoryId.toLong())] ?: return@filter false
				isRemoteCopyNewer(times.createdAt, times.deletedAt, remoteRow.createdAt, remoteRow.deletedAt)
			}
			if (newer.isNotEmpty()) {
				upsertFavourites(newer, startedAt)
				taken += newer.size
			}
		}
		return taken
	}

	/** What the server has, without sending it anything. Null from a server that cannot be asked that way. */
	private fun fetchFavourites(): SyncDto? {
		val request = Request.Builder()
			.url("$baseUrl/resource/$TABLE_FAVOURITES")
			.get()
			.build()
		val response = httpClient.newCall(request).execute()
		if (response.code == HttpURLConnection.HTTP_NOT_FOUND || response.code == HttpURLConnection.HTTP_BAD_METHOD) {
			// Synced as it always was, which is no worse than it was
			response.close()
			return null
		}
		return response.parseDtoOrNull()
	}

	/** When each row of [table] was added and removed, by its key. */
	private fun getTimes(table: String, keyColumn: String, secondKeyColumn: String?): Map<RowKey, RowTimes> {
		val uri = uri(authorityFavourites, table)
		val projection = listOfNotNull(keyColumn, secondKeyColumn, "created_at", "deleted_at").toTypedArray()
		val cursor = provider.query(uri, projection, null, null, null)
			?: throw OperationApplicationException("Query failed: $uri")
		return cursor.use {
			val result = HashMap<RowKey, RowTimes>(it.count)
			val timesAt = projection.size - 2
			while (it.moveToNext()) {
				val key = RowKey(it.getLong(0), if (secondKeyColumn != null) it.getLong(1) else 0L)
				result[key] = RowTimes(createdAt = it.getLong(timesAt), deletedAt = it.getLong(timesAt + 1))
			}
			result
		}
	}

	fun onError(e: Throwable) {
		e.printStackTraceDebug()
	}

	fun onSyncComplete(result: SyncResult) {
		if (BuildConfig.DEBUG) {
			Log.i("Sync", "Sync finished: ${result.toDebugString()}")
		}
	}

	private fun upsertHistory(history: List<HistorySyncDto>, startedAt: Long): Array<ContentProviderResult> {
		val uri = uri(authorityHistory, TABLE_HISTORY).unchangedSince(startedAt)
		val operations = ArrayList<ContentProviderOperation>()
		history.mapTo(operations) {
			operations.addAll(upsertManga(it.manga, authorityHistory))
			ContentProviderOperation.newInsert(uri)
				.withValues(it.toContentValues())
				.build()
		}
		return provider.applyBatch(operations)
	}

	private fun upsertFavouriteCategories(categories: List<FavouriteCategorySyncDto>, startedAt: Long): Array<ContentProviderResult> {
		val uri = uri(authorityFavourites, TABLE_FAVOURITE_CATEGORIES).unchangedSince(startedAt)
		val operations = ArrayList<ContentProviderOperation>()
		categories.mapTo(operations) {
			ContentProviderOperation.newInsert(uri)
				.withValues(it.toContentValues())
				.build()
		}
		return provider.applyBatch(operations)
	}

	private fun upsertFavourites(favourites: List<FavouriteSyncDto>, startedAt: Long): Array<ContentProviderResult> {
		val uri = uri(authorityFavourites, TABLE_FAVOURITES).unchangedSince(startedAt)
		val operations = ArrayList<ContentProviderOperation>()
		favourites.mapTo(operations) {
			operations.addAll(upsertManga(it.manga, authorityFavourites))
			ContentProviderOperation.newInsert(uri)
				.withValues(it.toContentValues())
				.build()
		}
		return provider.applyBatch(operations)
	}

	private fun upsertManga(manga: MangaSyncDto, authority: String): List<ContentProviderOperation> {
		val tags = manga.tags
		val result = ArrayList<ContentProviderOperation>(tags.size * 2 + 1)
		for (tag in tags) {
			result += ContentProviderOperation.newInsert(uri(authority, TABLE_TAGS))
				.withValues(tag.toContentValues())
				.build()
			result += ContentProviderOperation.newInsert(uri(authority, TABLE_MANGA_TAGS))
				.withValues(
					buildContentValues(2) {
						put("manga_id", manga.id)
						put("tag_id", tag.id)
					},
				).build()
		}
		result.add(
			0,
			ContentProviderOperation.newInsert(uri(authority, TABLE_MANGA))
				.withValues(manga.toContentValues())
				.build(),
		)
		return result
	}

	private fun getHistory(): List<HistorySyncDto> {
		return provider.query(authorityHistory, TABLE_HISTORY).use { cursor ->
			val result = ArrayList<HistorySyncDto>(cursor.count)
			if (cursor.moveToFirst()) {
				do {
					val mangaId = cursor.getLong(cursor.getColumnIndexOrThrow("manga_id"))
					result.add(HistorySyncDto(cursor, getManga(authorityHistory, mangaId)))
				} while (cursor.moveToNext())
			}
			result
		}
	}

	private fun getFavourites(): List<FavouriteSyncDto> {
		return provider.query(authorityFavourites, TABLE_FAVOURITES).map { cursor ->
			val manga = getManga(authorityFavourites, cursor.getLong(cursor.getColumnIndexOrThrow("manga_id")))
			FavouriteSyncDto(cursor, manga)
		}
	}

	private fun getFavouriteCategories(): List<FavouriteCategorySyncDto> =
		provider.query(authorityFavourites, TABLE_FAVOURITE_CATEGORIES).map { cursor ->
			FavouriteCategorySyncDto(cursor)
		}

	private fun getManga(authority: String, id: Long): MangaSyncDto {
		val tags = requireNotNull(
			provider.query(
				uri(authority, TABLE_MANGA_TAGS),
				arrayOf("tag_id"),
				"manga_id = ?",
				arrayOf(id.toString()),
				null,
			)?.mapToSet {
				val tagId = it.getLong(it.getColumnIndexOrThrow("tag_id"))
				getTag(authority, tagId)
			},
		)
		return requireNotNull(
			provider.query(
				uri(authority, TABLE_MANGA),
				null,
				"manga_id = ?",
				arrayOf(id.toString()),
				null,
			)?.use { cursor ->
				cursor.moveToFirst()
				MangaSyncDto(cursor, tags)
			},
		)
	}

	private fun getTag(authority: String, tagId: Long): MangaTagSyncDto = requireNotNull(
		provider.query(
			uri(authority, TABLE_TAGS),
			null,
			"tag_id = ?",
			arrayOf(tagId.toString()),
			null,
		)?.use { cursor ->
			if (cursor.moveToFirst()) {
				MangaTagSyncDto(cursor)
			} else {
				null
			}
		},
	)

	private fun gcFavourites() {
		val deletedAt = System.currentTimeMillis() - defaultGcPeriod
		val selection = "deleted_at != 0 AND deleted_at < ?"
		val args = arrayOf(deletedAt.toString())
		provider.delete(uri(authorityFavourites, TABLE_FAVOURITES), selection, args)
		provider.delete(uri(authorityFavourites, TABLE_FAVOURITE_CATEGORIES), selection, args)
	}

	private fun gcHistory() {
		val deletedAt = System.currentTimeMillis() - defaultGcPeriod
		val selection = "deleted_at != 0 AND deleted_at < ?"
		val args = arrayOf(deletedAt.toString())
		provider.delete(uri(authorityHistory, TABLE_HISTORY), selection, args)
	}

	private fun ContentProviderClient.query(authority: String, table: String): Cursor {
		val uri = uri(authority, table)
		return query(uri, null, null, null, null)
			?: throw OperationApplicationException("Query failed: $uri")
	}

	private fun uri(authority: String, table: String) = "content://$authority/$table".toUri()

	/** See [SyncProvider.PARAM_UNCHANGED_SINCE]. */
	private fun Uri.unchangedSince(time: Long): Uri = buildUpon()
		.appendQueryParameter(SyncProvider.PARAM_UNCHANGED_SINCE, time.toString())
		.build()

	private fun Response.parseDtoOrNull(): SyncDto? = use {
		when {
			!isSuccessful -> throw IOException(body.string())
			code == HttpURLConnection.HTTP_NO_CONTENT -> null
			else -> Json.decodeFromString<SyncDto>(body.string())
		}
	}

	private data class RowKey(val id: Long, val secondId: Long)

	private class RowTimes(val createdAt: Long, val deletedAt: Long)

	@AssistedFactory
	interface Factory {

		fun create(
			account: Account,
			contentProviderClient: ContentProviderClient,
		): SyncHelper
	}
}
