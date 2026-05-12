package org.koitharu.kotatsu.tracker.domain

import coil3.request.CachePolicy
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toInstantOrNull
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.tracker.domain.model.MangaTracking
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_PARALLELISM = 4

/**
 * One-shot migration that recovers tracks bugged by the previous reader-side
 * "swallow new chapters" defect. For each track whose last_chapter_id was
 * silently advanced past chapters the user never actually opened, this credits
 * those chapters as new so they reappear in the Updates tab.
 *
 * Heuristic: if the user's history.chapterId for a manga differs from the
 * track's last_chapter_id, the gap between them is credited as new. Tracks
 * where history matches last_chapter_id (user genuinely caught up) are
 * skipped, as are mangas without history or whose history chapter no longer
 * exists in the fresh chapter list.
 */
@Singleton
class TrackerUnstuckMigrationUseCase @Inject constructor(
	private val db: MangaDatabase,
	private val trackingRepository: TrackingRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val localMangaRepository: LocalMangaRepository,
	private val networkState: NetworkState,
	private val settings: AppSettings,
) {

	suspend fun runIfNeeded() {
		// Offline -> defer all passes; setting flags now would burn the one-shots.
		if (!networkState.value) return
		if (!settings.isTrackerUnstuckMigrationDone) {
			runCatchingCancellable {
				runImpl()
			}.onSuccess {
				settings.isTrackerUnstuckMigrationDone = true
			}.onFailure { e ->
				e.printStackTraceDebug()
			}
		}
		if (!settings.isTrackerProgressRefreshDone) {
			runCatchingCancellable {
				refreshProgressImpl()
			}.onSuccess {
				settings.isTrackerProgressRefreshDone = true
			}.onFailure { e ->
				e.printStackTraceDebug()
			}
		}
	}

	/**
	 * Second-pass migration: refreshes history.chaptersCount and history.percent
	 * for every track that already has chapters_new > 0 (mangas the tracker
	 * itself credited new chapters for, before ProgressUpdateUseCase was
	 * teaching history.chaptersCount about the new total). Without this, those
	 * mangas keep rendering as "completed" even though new chapters exist.
	 */
	private suspend fun refreshProgressImpl() = coroutineScope {
		val tracksDao = db.getTracksDao()
		val historyDao = db.getHistoryDao()
		val candidates = tracksDao.findAll(offset = 0, limit = Int.MAX_VALUE)
			.filter { it.track.newChapters > 0 }
		val semaphore = Semaphore(MAX_PARALLELISM)
		for (entry in candidates) {
			val history = historyDao.find(entry.track.mangaId) ?: continue
			launch {
				semaphore.withPermit {
					runCatchingCancellable {
						refreshOne(
							manga = entry.manga.toManga(emptySet(), null),
							historyChapterId = history.chapterId,
						)
					}.onFailure { it.printStackTraceDebug() }
				}
			}
		}
	}

	private suspend fun refreshOne(manga: Manga, historyChapterId: Long) {
		val details = getFullManga(manga) ?: return
		val historyChapter = details.chapters?.findById(historyChapterId) ?: return
		val chapters = details.getChapters(historyChapter.branch)
		if (chapters.isEmpty()) return
		val historyIndex = chapters.indexOfFirst { it.id == historyChapterId }
		if (historyIndex < 0) return
		fixHistoryProgress(manga.id, historyIndex, chapters.size)
	}

	private suspend fun runImpl() = coroutineScope {
		val tracksDao = db.getTracksDao()
		val historyDao = db.getHistoryDao()
		val candidates = tracksDao.findAll(offset = 0, limit = Int.MAX_VALUE)
			.filter { it.track.newChapters == 0 && it.track.lastChapterId != 0L }
		val semaphore = Semaphore(MAX_PARALLELISM)
		for (entry in candidates) {
			val history = historyDao.find(entry.track.mangaId) ?: continue
			if (history.chapterId == entry.track.lastChapterId) continue
			launch {
				semaphore.withPermit {
					runCatchingCancellable {
						processOne(
							manga = entry.manga.toManga(emptySet(), null),
							trackLastChapterId = entry.track.lastChapterId,
							historyChapterId = history.chapterId,
						)
					}.onFailure { it.printStackTraceDebug() }
				}
			}
		}
	}

	private suspend fun processOne(manga: Manga, trackLastChapterId: Long, historyChapterId: Long) {
		val details = getFullManga(manga) ?: return
		val historyChapter = details.chapters?.findById(historyChapterId)
		val trackChapter = details.chapters?.findById(trackLastChapterId)
		if (historyChapter == null && trackChapter == null) return
		val branch = historyChapter?.branch ?: trackChapter?.branch
		val chapters = details.getChapters(branch)
		if (chapters.isEmpty()) return
		val historyIndex = chapters.indexOfFirst { it.id == historyChapterId }
		if (historyIndex < 0) return
		val creditable = chapters.lastIndex - historyIndex
		if (creditable <= 0) return
		val lastChapter = chapters.last()
		trackingRepository.mergeWith(
			MangaTracking(
				manga = details,
				lastChapterId = lastChapter.id,
				lastCheck = Instant.now(),
				lastChapterDate = lastChapter.uploadDate.toInstantOrNull(),
				newChapters = creditable,
			),
		)
		fixHistoryProgress(manga.id, historyIndex, chapters.size)
	}

	/**
	 * Refreshes display-only history fields against the new chapter count so
	 * that mangas freshly credited as having new chapters no longer render as
	 * "100% complete". Read position (chapter_id, page, scroll) is preserved.
	 */
	private suspend fun fixHistoryProgress(mangaId: Long, historyIndex: Int, totalChapters: Int) {
		if (totalChapters <= 0) return
		val historyDao = db.getHistoryDao()
		val history = historyDao.find(mangaId) ?: return
		val wasCompleted = history.percent >= 0.99f
		val newPercent = if (wasCompleted) {
			(historyIndex + 1).toFloat() / totalChapters
		} else {
			history.percent
		}
		if (history.chaptersCount == totalChapters && history.percent == newPercent) return
		historyDao.update(history.copy(percent = newPercent, chaptersCount = totalChapters))
	}

	private suspend fun getFullManga(manga: Manga): Manga? = runCatchingCancellable {
		when {
			manga.isLocal -> {
				val remote = localMangaRepository.getRemoteManga(manga) ?: return@runCatchingCancellable null
				fetchDetails(remote)
			}

			manga.chapters.isNullOrEmpty() -> fetchDetails(manga)
			else -> manga
		}
	}.getOrNull()

	private suspend fun fetchDetails(manga: Manga): Manga {
		val repo = mangaRepositoryFactory.create(manga.source)
		return if (repo is CachingMangaRepository) {
			repo.getDetails(manga, CachePolicy.WRITE_ONLY)
		} else {
			repo.getDetails(manga)
		}
	}
}
