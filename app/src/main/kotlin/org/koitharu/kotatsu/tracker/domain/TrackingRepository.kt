package org.koitharu.kotatsu.tracker.domain

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.mapItems
import org.koitharu.kotatsu.core.util.ext.toInstantOrNull
import org.koitharu.kotatsu.details.domain.ProgressUpdateUseCase
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.ifZero
import org.koitharu.kotatsu.tracker.data.TrackEntity
import org.koitharu.kotatsu.tracker.data.TrackLogEntity
import org.koitharu.kotatsu.tracker.data.toTrackingLogItem
import org.koitharu.kotatsu.tracker.domain.model.MangaTracking
import org.koitharu.kotatsu.tracker.domain.model.MangaUpdates
import org.koitharu.kotatsu.tracker.domain.model.TrackingLogItem
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val NO_ID = 0L
private const val MAX_LOG_SIZE = 120

@Reusable
class TrackingRepository @Inject constructor(
	private val db: MangaDatabase,
	private val settings: AppSettings,
	private val progressUpdateUseCase: ProgressUpdateUseCase,
	@ApplicationContext private val context: Context,
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {

	private var isGcCalled = AtomicBoolean(false)

	suspend fun getNewChaptersCount(mangaId: Long): Int {
		return db.getTracksDao().findNewChapters(mangaId)
	}

	fun observeNewChaptersCount(mangaId: Long): Flow<Int> {
		return db.getTracksDao().observeNewChapters(mangaId)
	}

	suspend fun getLastUpdateTime(mangaId: Long): Long {
		return db.getTrackLogsDao().getLastLogTime(mangaId) ?: 0L
	}

	@Deprecated("")
	fun observeUpdatedMangaCount(): Flow<Int> {
		return db.getTracksDao().observeUpdateMangaCount()
			.onStart { gcIfNotCalled() }
	}

	fun observeUnreadUpdatesCount(): Flow<Int> {
		return db.getTrackLogsDao().observeUnreadCount()
	}

	fun observeUpdatedManga(limit: Int, filterOptions: Set<ListFilterOption>): Flow<List<MangaTracking>> {
		return db.getTracksDao().observeUpdatedManga(limit, filterOptions)
			.mapItems {
				MangaTracking(
					manga = it.manga.toManga(it.tags.toMangaTags(), null),
					lastChapterId = it.track.lastChapterId,
					lastCheck = it.track.lastCheckTime.toInstantOrNull(),
					lastChapterDate = it.track.lastChapterDate.toInstantOrNull(),
					newChapters = it.track.newChapters,
				)
			}.distinctUntilChanged()
			.onStart { gcIfNotCalled() }
	}

	suspend fun getTracks(offset: Int, limit: Int, minActivityTime: Long, staleCheckTime: Long): List<MangaTracking> {
		// Tracks from sources with update checking disabled never get their last_check_time
		// advanced, so they permanently sort to the front of the batch. Filtering them after
		// the SQL LIMIT would let them occupy batch slots forever, starving the rest of the
		// queue — page through until the requested amount of checkable tracks is collected.
		val result = ArrayList<MangaTracking>(if (limit == Int.MAX_VALUE) 16 else limit)
		var currentOffset = offset
		while (result.size < limit) {
			val window = db.getTracksDao().findAll(
				offset = currentOffset,
				limit = limit - result.size,
				minActivityTime = minActivityTime,
				staleCheckTime = staleCheckTime,
			)
			if (window.isEmpty()) {
				break
			}
			currentOffset += window.size
			for (item in window) {
				val manga = item.manga.toManga(emptySet(), null)
				if (isUpdateCheckingDisabled(manga)) {
					Log.i(
						TAG,
						"getTracks: [${manga.id}] \"${manga.title}\" skipped: " +
							"update checking disabled for source ${manga.source.name}",
					)
					continue
				}
				result.add(
					MangaTracking(
						manga = manga,
						lastChapterId = item.track.lastChapterId,
						lastCheck = item.track.lastCheckTime.toInstantOrNull(),
						lastChapterDate = item.track.lastChapterDate.toInstantOrNull(),
						newChapters = item.track.newChapters,
					),
				)
				if (result.size >= limit) {
					break
				}
			}
		}
		Log.i(
			TAG,
			"getTracks: collected ${result.size} of ${if (limit == Int.MAX_VALUE) "all" else limit} requested, " +
				"scanned ${currentOffset - offset} eligible row(s), " +
				"total tracks in db: ${db.getTracksDao().getTracksCount()}, " +
				"minActivityTime=${minActivityTime.toInstantOrNull()}, staleCheckTime=${staleCheckTime.toInstantOrNull()}",
		)
		return result
	}

	private fun isUpdateCheckingDisabled(manga: Manga): Boolean {
		val repository = mangaRepositoryFactory.create(manga.source)
		if (repository !is ParserMangaRepository) {
			return false // Non-parser sources (local, etc.) are not disabled
		}

		// Check if parser has DisableUpdateChecking ConfigKey
		val configKeys = repository.getConfigKeys()
		val disableKey = configKeys.filterIsInstance<ConfigKey.DisableUpdateChecking>().firstOrNull()
		return disableKey?.defaultValue == true
	}

	@Deprecated("")
	suspend fun getTrack(manga: Manga): MangaTracking {
		return getTrackOrNull(manga) ?: MangaTracking(
			manga = manga,
			lastChapterId = NO_ID,
			lastCheck = null,
			lastChapterDate = null,
			newChapters = 0,
		)
	}

	suspend fun getTrackOrNull(manga: Manga): MangaTracking? {
		val track = db.getTracksDao().find(manga.id) ?: return null
		return MangaTracking(
			manga = manga,
			lastChapterId = track.lastChapterId,
			lastCheck = track.lastCheckTime.toInstantOrNull(),
			lastChapterDate = track.lastChapterDate.toInstantOrNull(),
			newChapters = track.newChapters,
		)
	}

	@VisibleForTesting
	suspend fun deleteTrack(mangaId: Long) {
		db.getTracksDao().delete(mangaId)
	}

	fun observeTrackingLog(limit: Int, filterOptions: Set<ListFilterOption>): Flow<List<TrackingLogItem>> {
		return db.getTrackLogsDao().observeAll(limit, filterOptions)
			.mapItems { it.toTrackingLogItem() }
			.onStart { gcIfNotCalled() }
	}

	suspend fun getLogsCount() = db.getTrackLogsDao().count()

	suspend fun clearLogs() = db.getTrackLogsDao().clear()

	suspend fun clearCounters() = db.getTracksDao().clearCounters()

	suspend fun markAsRead(trackLogId: Long) = db.getTrackLogsDao().markAsRead(trackLogId)

	/**
	 * Deletes a single feed entry and returns it, so the caller can offer to put it back.
	 * Returns null if it was already gone.
	 */
	suspend fun removeLog(trackLogId: Long): TrackLogEntity? = db.withTransaction {
		val dao = db.getTrackLogsDao()
		dao.find(trackLogId)?.also { dao.delete(trackLogId) }
	}

	/** Reinserts an entry removed by [removeLog]; the entity carries its original id, so it goes back
	 *  in the same place in the feed rather than to the top. */
	suspend fun restoreLog(entity: TrackLogEntity) {
		db.getTrackLogsDao().insert(entity)
	}

	suspend fun gc() = db.withTransaction {
		db.getTracksDao().gc()
		db.getTracksDao().clearStaleCounters(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(MAX_STALE_UPDATE_DAYS))
		db.getTrackLogsDao().run {
			gc()
			trim(MAX_LOG_SIZE)
		}
	}

	suspend fun saveUpdates(updates: MangaUpdates) {
		db.withTransaction {
			val track = getOrCreateTrack(updates.manga.id).mergeWith(updates)
			db.getTracksDao().upsert(track)
			if (updates is MangaUpdates.Success && updates.isValid && updates.newChapters.isNotEmpty()) {
				progressUpdateUseCase(updates.manga)
				val logEntity = TrackLogEntity(
					mangaId = updates.manga.id,
					chapters = updates.newChapters.joinToString("\n") { x -> x.name },
					createdAt = System.currentTimeMillis(),
					isUnread = true,
				)
				db.getTrackLogsDao().insert(logEntity)
			}
		}
	}

	suspend fun clearUpdates(ids: Collection<Long>) {
		when {
			ids.isEmpty() -> return
			ids.size == 1 -> db.getTracksDao().clearCounter(ids.single())
			else -> db.withTransaction {
				for (id in ids) {
					db.getTracksDao().clearCounter(id)
				}
			}
		}
	}

	suspend fun mergeWith(tracking: MangaTracking) {
		val entity = TrackEntity(
			mangaId = tracking.manga.id,
			lastChapterId = tracking.lastChapterId,
			newChapters = tracking.newChapters,
			lastCheckTime = tracking.lastCheck?.toEpochMilli() ?: 0L,
			lastChapterDate = tracking.lastChapterDate?.toEpochMilli() ?: 0L,
			lastResult = TrackEntity.RESULT_EXTERNAL_MODIFICATION,
			lastError = null,
		)
		db.getTracksDao().upsert(entity)
	}

	suspend fun getCategoriesCount(): IntArray {
		val categories = db.getFavouriteCategoriesDao().findAll()
		return intArrayOf(
			categories.count { it.track },
			categories.size,
		)
	}

	suspend fun updateTracks() = db.withTransaction {
		val dao = db.getTracksDao()
		dao.gc()
		val ids = dao.findAllIds().toMutableSet()
		val size = ids.size
		var addedFromHistory = 0
		var addedFromFavourites = 0
		// A manga can be tracked by both history and favourites: once the history pass has
		// handled an id, the favourites pass must not touch it — recreating the row would
		// wipe its baseline (lastChapterId), silently resetting the track on every run.
		val handled = HashSet<Long>(size)
		// history
		if (AppSettings.TRACK_HISTORY in settings.trackSources) {
			val historyIds = db.getHistoryDao().findAllIds()
			for (mangaId in historyIds) {
				if (handled.add(mangaId) && !ids.remove(mangaId)) {
					dao.upsert(TrackEntity.create(mangaId))
					addedFromHistory++
				}
			}
		}
		// favorites
		if (AppSettings.TRACK_FAVOURITES in settings.trackSources) {
			val favoritesIds = db.getFavouritesDao().findIdsWithTrack()
			for (mangaId in favoritesIds) {
				if (handled.add(mangaId) && !ids.remove(mangaId)) {
					dao.upsert(TrackEntity.create(mangaId))
					addedFromFavourites++
				}
			}
		}
		// remove unused
		for (mangaId in ids) {
			dao.delete(mangaId)
		}
		if (addedFromHistory != 0 || addedFromFavourites != 0 || ids.isNotEmpty()) {
			Log.i(
				TAG,
				"updateTracks: existing=$size, +history=$addedFromHistory, +favourites=$addedFromFavourites, " +
					"removed=${ids.size}" +
					(if (ids.isEmpty()) "" else " (ids: ${ids.take(REMOVED_IDS_LOG_LIMIT)})") +
					" trackSources=${settings.trackSources}",
			)
		}
		size - ids.size
	}

	private suspend fun getOrCreateTrack(mangaId: Long): TrackEntity {
		return db.getTracksDao().find(mangaId) ?: TrackEntity.create(mangaId)
	}

	private fun TrackEntity.mergeWith(updates: MangaUpdates): TrackEntity {
		return when (updates) {
			is MangaUpdates.Failure -> TrackEntity(
				mangaId = mangaId,
				lastChapterId = lastChapterId,
				newChapters = newChapters,
				lastCheckTime = System.currentTimeMillis(),
				lastChapterDate = lastChapterDate,
				lastResult = TrackEntity.RESULT_FAILED,
				lastError = updates.error?.toString(),
			)

			is MangaUpdates.Success -> {
				val chapters = updates.manga.getChapters(updates.branch)
				TrackEntity(
					mangaId = mangaId,
					lastChapterId = chapters.lastOrNull()?.id ?: NO_ID,
					// Cap at the total chapter count: the unread counter can never exceed how many
					// chapters exist, even if a transient detection glitch tries to inflate it.
					newChapters = if (updates.isValid) {
						(newChapters + updates.newChapters.size).coerceIn(0, chapters.size)
					} else {
						0
					},
					lastCheckTime = System.currentTimeMillis(),
					lastChapterDate = updates.lastChapterDate().ifZero { lastChapterDate },
					lastResult = if (updates.isNotEmpty()) TrackEntity.RESULT_HAS_UPDATE else TrackEntity.RESULT_NO_UPDATE,
					lastError = null,
				)
			}
		}
	}

	private suspend fun gcIfNotCalled() {
		if (isGcCalled.compareAndSet(false, true)) {
			gc()
		}
	}

	private companion object {

		const val TAG = "Tracker"
		const val MAX_STALE_UPDATE_DAYS = 90L
		const val REMOVED_IDS_LOG_LIMIT = 20
	}
}
