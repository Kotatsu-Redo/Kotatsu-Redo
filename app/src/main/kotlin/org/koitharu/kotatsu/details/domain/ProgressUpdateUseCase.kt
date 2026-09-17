package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.domain.HistoryWriteLog
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

class ProgressUpdateUseCase @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val database: MangaDatabase,
	private val localMangaRepository: LocalMangaRepository,
	private val networkState: NetworkState,
) {

	suspend operator fun invoke(manga: Manga): Float {
		val history = database.getHistoryDao().find(manga.id) ?: return PROGRESS_NONE
		val seed = if (manga.isLocal) {
			localMangaRepository.getRemoteManga(manga) ?: manga
		} else {
			manga
		}
		if (!seed.isLocal && !networkState.value) {
			return PROGRESS_NONE
		}
		val repo = mangaRepositoryFactory.create(seed.source)
		val details = if (manga.source != seed.source || seed.chapters.isNullOrEmpty()) {
			repo.getDetails(seed)
		} else {
			seed
		}
		val chapter = details.findChapterById(history.chapterId)
			?: return estimateFromCounts(details, history)
		val chapters = details.getChapters(chapter.branch)
		val chapterRepo = if (repo.source == chapter.source) {
			repo
		} else {
			mangaRepositoryFactory.create(chapter.source)
		}
		val chaptersCount = chapters.size
		if (chaptersCount == 0) {
			return PROGRESS_NONE
		}
		val chapterIndex = chapters.indexOfFirst { x -> x.id == history.chapterId }
		val pagesCount = chapterRepo.getPages(chapter).size
		if (pagesCount == 0) {
			return PROGRESS_NONE
		}
		val pagePercent = (history.page + 1) / pagesCount.toFloat()
		val ppc = 1f / chaptersCount
		val result = ppc * chapterIndex + ppc * pagePercent
		if (result != history.percent || history.chaptersCount != chaptersCount) {
			HistoryWriteLog.progressRewrite(manga, history.percent, result, history.chaptersCount, chaptersCount)
			database.getHistoryDao().update(
				history.copy(
					chapterId = chapter.id,
					percent = result,
					chaptersCount = chaptersCount,
				),
			)
		}
		return result
	}

	/**
	 * Fallback when the stored [MangaHistory.chapterId] is no longer present in the fresh details
	 * (e.g. the source rotated chapter URLs, which changes derived ids). We can't pinpoint the
	 * reading position anymore, so we estimate it from the previously stored counts: roughly
	 * `percent * oldTotal` chapters were read, rescaled to the new total. The estimate keeps the
	 * progress indicator honest (no longer stuck at "completed") without touching the read position.
	 *
	 * The rescale may only ever move progress *down*. `chaptersCount` and `newTotal` are not
	 * guaranteed to describe the same branch — [getPreferredBranch] is called without history here,
	 * precisely because the anchor that would identify the branch is what went missing — so the
	 * ratio is unreliable in the upward direction. Worse, it saturates: with only one or two
	 * chapters left in the new total, almost any stored percent clamps straight to 1.0 and the manga
	 * is written to the database as completed, permanently, while the chapter list correctly shows
	 * every chapter as unread. A guess must never claim a completion the user never reached.
	 */
	private suspend fun estimateFromCounts(details: Manga, history: HistoryEntity): Float {
		val newTotal = details.getChapters(details.getPreferredBranch(null)).size
			.takeIf { it > 0 } ?: details.chapters?.size ?: 0
		if (newTotal == 0 || history.chaptersCount <= 0 || !ReadingProgress.isValid(history.percent)) {
			return PROGRESS_NONE
		}
		val estimated = if (ReadingProgress.isCompleted(history.percent)) {
			// Genuinely finished before the ids rotated: stay finished.
			history.percent
		} else {
			// Gaining chapters legitimately lowers progress; losing them may not raise it.
			(history.percent * history.chaptersCount / newTotal).coerceIn(0f, history.percent)
		}
		if (estimated != history.percent || history.chaptersCount != newTotal) {
			HistoryWriteLog.progressRewrite(details, history.percent, estimated, history.chaptersCount, newTotal)
			database.getHistoryDao().update(history.copy(percent = estimated, chaptersCount = newTotal))
		}
		return estimated
	}
}
