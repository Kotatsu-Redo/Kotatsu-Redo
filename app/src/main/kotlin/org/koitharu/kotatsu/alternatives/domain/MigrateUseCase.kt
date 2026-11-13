package org.koitharu.kotatsu.alternatives.domain

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.details.domain.ProgressUpdateUseCase
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.data.toMangaHistory
import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.tracker.data.TrackEntity
import javax.inject.Inject

class MigrateUseCase
@Inject
constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val mangaDataRepository: MangaDataRepository,
	private val database: MangaDatabase,
	private val progressUpdateUseCase: ProgressUpdateUseCase,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
) {
	suspend operator fun invoke(
		oldManga: Manga,
		newManga: Manga,
	) {
		Log.i(TAG, "Migration started: ${oldManga.debugRef()} -> ${newManga.debugRef()}")
		try {
			migrateInternal(oldManga, newManga)
			Log.i(TAG, "Migration completed: ${oldManga.debugRef()} -> ${newManga.debugRef()}")
		} catch (e: CancellationException) {
			Log.d(TAG, "Migration cancelled: ${oldManga.debugRef()} -> ${newManga.debugRef()}")
			throw e
		} catch (e: Throwable) {
			Log.e(TAG, "Migration failed: ${oldManga.debugRef()} -> ${newManga.debugRef()}", e)
			throw e
		}
	}

	private suspend fun migrateInternal(
		oldManga: Manga,
		newManga: Manga,
	) {
		Log.d(TAG, "Migration stage=load_old_details old=${oldManga.debugRef()}")
		val oldDetails = if (oldManga.chapters.isNullOrEmpty()) {
			val result = runCatchingCancellable {
				mangaRepositoryFactory.create(oldManga.source).getDetails(oldManga)
			}
			result.exceptionOrNull()?.let {
				Log.w(TAG, "Could not load old details; using cached manga: ${oldManga.debugRef()}", it)
			}
			result.getOrDefault(oldManga)
		} else {
			oldManga
		}
		Log.d(TAG, "Migration stage=load_target_details target=${newManga.debugRef()}")
		val newDetails = if (newManga.chapters.isNullOrEmpty()) {
			mangaRepositoryFactory.create(newManga.source).getDetails(newManga)
		} else {
			newManga
		}
		Log.d(TAG, "Migration stage=store_target target=${newDetails.debugRef()} chapters=${newDetails.chapters?.size ?: 0}")
		mangaDataRepository.storeManga(newDetails, replaceExisting = true)
		Log.d(TAG, "Migration stage=database_transaction oldId=${oldDetails.id} targetId=${newDetails.id}")
		database.withTransaction {
			// replace favorites
			Log.d(TAG, "Migration stage=favorites oldId=${oldDetails.id} targetId=${newDetails.id}")
			val favoritesDao = database.getFavouritesDao()
			val oldFavourites = favoritesDao.findAllRaw(oldDetails.id)
			if (oldFavourites.isNotEmpty()) {
				favoritesDao.delete(oldManga.id)
				for (f in oldFavourites) {
					val e =
						f.copy(
							mangaId = newManga.id,
						)
					favoritesDao.upsert(e)
				}
			}
			// replace history
			Log.d(TAG, "Migration stage=history oldId=${oldDetails.id} targetId=${newDetails.id}")
			val historyDao = database.getHistoryDao()
			val oldHistory = historyDao.find(oldDetails.id)
			val newHistory =
				if (oldHistory != null) {
					val newHistory = makeNewHistory(oldDetails, newDetails, oldHistory)
					database.replaceHistory(oldDetails.id, newHistory)
					newHistory
				} else {
					null
				}
			// track
			Log.d(TAG, "Migration stage=tracking oldId=${oldDetails.id} targetId=${newDetails.id}")
			val tracksDao = database.getTracksDao()
			val oldTrack = tracksDao.find(oldDetails.id)
			if (oldTrack != null) {
				val lastChapter = newDetails.chapters?.lastOrNull()
				val newTrack =
					TrackEntity(
						mangaId = newDetails.id,
						lastChapterId = lastChapter?.id ?: 0L,
						newChapters = 0,
						lastCheckTime = System.currentTimeMillis(),
						lastChapterDate = lastChapter?.uploadDate ?: 0L,
						lastResult = TrackEntity.RESULT_EXTERNAL_MODIFICATION,
						lastError = null,
					)
				tracksDao.delete(oldDetails.id)
				tracksDao.upsert(newTrack)
			}
			// scrobbling
			Log.d(TAG, "Migration stage=scrobbling oldId=${oldDetails.id} targetId=${newDetails.id}")
			for (scrobbler in scrobblers) {
				if (!scrobbler.isEnabled) {
					continue
				}
				val prevInfo = scrobbler.getScrobblingInfoOrNull(oldDetails.id) ?: continue
				scrobbler.unregisterScrobbling(oldDetails.id)
				scrobbler.linkManga(newDetails.id, prevInfo.targetId)
				scrobbler.updateScrobblingInfo(
					mangaId = newDetails.id,
					rating = prevInfo.rating,
					status =
						prevInfo.status ?: when {
							newHistory == null -> ScrobblingStatus.PLANNED
							newHistory.percent == 1f -> ScrobblingStatus.COMPLETED
							else -> ScrobblingStatus.READING
						},
					comment = prevInfo.comment,
				)
				if (newHistory != null) {
					scrobbler.scrobble(
						manga = newDetails,
						chapterId = newHistory.chapterId,
					)
				}
			}
		}
		Log.d(TAG, "Migration stage=progress_update target=${newManga.debugRef()}")
		progressUpdateUseCase(newManga)
	}

	private fun Manga.debugRef(): String = "title=\"$title\" id=$id source=${source.name}"

	private fun makeNewHistory(
		oldManga: Manga,
		newManga: Manga,
		history: HistoryEntity,
	): HistoryEntity {
		if (oldManga.chapters.isNullOrEmpty()) { // probably broken manga/source
			val branch = newManga.getPreferredBranch(null)
			val chapters = checkNotNull(newManga.getChapters(branch))
			val currentChapter =
				if (history.percent in 0f..1f) {
					chapters[(chapters.lastIndex * history.percent).toInt()]
				} else {
					chapters.first()
				}
			return HistoryEntity(
				mangaId = newManga.id,
				createdAt = history.createdAt,
				updatedAt = history.updatedAt,
				chapterId = currentChapter.id,
				page = history.page,
				scroll = history.scroll,
				percent = history.percent,
				deletedAt = 0,
				chaptersCount = chapters.count { it.branch == currentChapter.branch },
			)
		}
		val branch = oldManga.getPreferredBranch(history.toMangaHistory())
		val oldChapters = checkNotNull(oldManga.getChapters(branch))
		var index = oldChapters.indexOfFirst { it.id == history.chapterId }
		if (index < 0) {
			index =
				if (history.percent in 0f..1f) {
					(oldChapters.lastIndex * history.percent).toInt()
				} else {
					0
				}
		}
		val newChapters = checkNotNull(newManga.chapters).groupBy { it.branch }
		val newBranch =
			if (newChapters.containsKey(branch)) {
				branch
			} else {
				newManga.getPreferredBranch(null)
			}
		val newChapterId =
			checkNotNull(newChapters[newBranch])
				.let {
					val oldChapter = oldChapters[index]
					it.findByNumber(oldChapter.volume, oldChapter.number) ?: it.getOrNull(index) ?: it.last()
				}.id

		return HistoryEntity(
			mangaId = newManga.id,
			createdAt = history.createdAt,
			updatedAt = history.updatedAt,
			chapterId = newChapterId,
			page = history.page,
			scroll = history.scroll,
			percent = PROGRESS_NONE,
			deletedAt = 0,
			chaptersCount = checkNotNull(newChapters[newBranch]).size,
		)
	}

	private fun List<MangaChapter>.findByNumber(
		volume: Int,
		number: Float,
	): MangaChapter? =
		if (number <= 0f) {
			null
		} else {
			firstOrNull { it.volume == volume && it.number == number }
		}

	private companion object {
		const val TAG = "SourceReplacement"
	}
}

/**
 * Puts [newHistory] in place of the history of [oldMangaId], and with it the time and pages counted while reading.
 *
 * Those are kept under the id of the manga and hang on its history. Left where they were they stopped showing
 * the moment the old history was removed, and were deleted for good when it was cleaned up later: someone who
 * moved a manga to another source lost everything they had read of it from their statistics.
 *
 * To be called inside a transaction.
 */
internal suspend fun MangaDatabase.replaceHistory(oldMangaId: Long, newHistory: HistoryEntity) {
	val historyDao = getHistoryDao()
	historyDao.delete(oldMangaId)
	historyDao.upsert(newHistory)
	if (oldMangaId != newHistory.mangaId) {
		getStatsDao().moveAll(oldMangaId, newHistory.mangaId)
	}
}
