package org.koitharu.kotatsu.tracker.domain

import android.util.Log
import coil3.request.CachePolicy
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.MultiMutex
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toInstantOrNull
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.tracker.domain.model.MangaTracking
import org.koitharu.kotatsu.tracker.domain.model.MangaUpdates
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CheckNewChaptersUseCase @Inject constructor(
	private val repository: TrackingRepository,
	private val historyRepository: HistoryRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val localMangaRepository: LocalMangaRepository,
) {

	private val mutex = MultiMutex<Long>()

	suspend operator fun invoke(manga: Manga): MangaUpdates = mutex.withLock(manga.id) {
		repository.updateTracks()
		val tracking = repository.getTrackOrNull(manga) ?: return@withLock MangaUpdates.Failure(
			manga = manga,
			error = null,
		)
		invokeImpl(tracking)
	}

	suspend operator fun invoke(track: MangaTracking): MangaUpdates = mutex.withLock(track.manga.id) {
		invokeImpl(track)
	}

	suspend operator fun invoke(manga: Manga, currentChapterId: Long) = mutex.withLock(manga.id) {
		runCatchingCancellable {
			repository.updateTracks()
			val details = getFullManga(manga)
			val track = repository.getTrackOrNull(manga) ?: return@withLock
			val branch = checkNotNull(details.chapters?.findById(currentChapterId)).branch
			val chapters = details.getChapters(branch)
			val chapterIndex = chapters.indexOfFirst { x -> x.id == currentChapterId }
			val lastChapter = chapters.lastOrNull()
			// Credit chapters that appeared since the previously tracked last chapter.
			// Without this, opening the reader on an older chapter while a release
			// dropped in the meantime would advance lastChapterId past the unseen
			// chapter without ever flagging it as new, hiding it from the Updates tab.
			val prevLastIndex = chapters.indexOfFirst { it.id == track.lastChapterId }
			val addedSinceLastTrack = if (prevLastIndex >= 0) chapters.lastIndex - prevLastIndex else 0
			val effectiveNew = track.newChapters + addedSinceLastTrack
			val lastNewChapterIndex = chapters.size - effectiveNew
			val tracking = MangaTracking(
				manga = details,
				lastChapterId = lastChapter?.id ?: 0L,
				lastCheck = Instant.now(),
				lastChapterDate = lastChapter?.uploadDate?.toInstantOrNull() ?: track.lastChapterDate,
				newChapters = when {
					effectiveNew == 0 -> 0
					chapterIndex < 0 -> effectiveNew
					chapterIndex >= lastNewChapterIndex -> chapters.lastIndex - chapterIndex
					else -> effectiveNew
				},
			)
			repository.mergeWith(tracking)
		}.onFailure { e ->
			e.printStackTraceDebug()
		}.isSuccess
	}

	private suspend fun invokeImpl(track: MangaTracking): MangaUpdates = runCatchingCancellable {
		val details = getFullManga(track.manga)
		val historyChapterId = historyRepository.getOne(track.manga)?.chapterId ?: 0L
		val branch = getBranch(details, track.lastChapterId, historyChapterId)
		compare(track, details, branch, historyChapterId)
	}.getOrElse { error ->
		MangaUpdates.Failure(
			manga = track.manga,
			error = error,
		)
	}.also { updates ->
		repository.saveUpdates(updates)
	}

	private fun getBranch(manga: Manga, trackChapterId: Long, historyChapterId: Long): String? {
		manga.chapters?.findById(historyChapterId)?.let {
			return it.branch
		}
		manga.chapters?.findById(trackChapterId)?.let {
			return it.branch
		}
		// fallback
		return manga.getPreferredBranch(null)
	}

	private suspend fun getFullManga(manga: Manga): Manga = when {
		manga.isLocal -> fetchDetails(
			requireNotNull(localMangaRepository.getRemoteManga(manga)) {
				"Local manga is not supported"
			},
		)

		manga.chapters.isNullOrEmpty() -> fetchDetails(manga)
		else -> manga
	}

	private suspend fun fetchDetails(manga: Manga): Manga {
		val repo = mangaRepositoryFactory.create(manga.source)
		return if (repo is CachingMangaRepository) {
			repo.getDetails(manga, CachePolicy.WRITE_ONLY)
		} else {
			repo.getDetails(manga)
		}
	}

	/**
	 * The main functionality of tracker: check new chapters in [manga] comparing to the [track].
	 *
	 * Comparison anchors, tried in order:
	 *  1. [MangaTracking.lastChapterId] — the tracker's own baseline.
	 *  2. [MangaTracking.lastChapterDate] — upload date of the last known chapter; robust to id
	 *     churn (some sources rotate chapter URLs, which changes the derived ids on every fetch).
	 *     This is the proper baseline, advanced by the caller on every successful check, so it does
	 *     not re-flag the same chapters on subsequent runs.
	 *  3. [historyChapterId] — the user's reading position; a last resort when the track has no
	 *     usable id or date (e.g. a stale backup). May surface a large batch once; the caller then
	 *     records a fresh date baseline so it does not repeat.
	 *
	 * If none of the anchors are usable we re-baseline silently (no notification).
	 */
	private fun compare(
		track: MangaTracking,
		manga: Manga,
		branch: String?,
		historyChapterId: Long,
	): MangaUpdates.Success {
		if (track.isEmpty()) {
			// first check or manga was empty on last check
			return MangaUpdates.Success(manga, branch, emptyList(), isValid = false)
		}
		val chapters = requireNotNull(manga.getChapters(branch))
		if (BuildConfig.DEBUG && chapters.findById(track.lastChapterId) == null) {
			Log.e("Tracker", "Chapter ${track.lastChapterId} not found")
		}
		compareAgainst(manga, branch, chapters, track.lastChapterId)?.let { return it }
		// lastChapterId is stale (not in the fresh list) -> prefer the date baseline.
		compareByDate(manga, branch, chapters, track.lastChapterDate?.toEpochMilli() ?: 0L)?.let { return it }
		// No usable id or date -> last resort: the user's reading position.
		if (historyChapterId != 0L && historyChapterId != track.lastChapterId) {
			compareAgainst(manga, branch, chapters, historyChapterId)?.let { return it }
		}
		// Nothing usable; can't tell what's new. Re-baseline silently.
		return MangaUpdates.Success(manga, branch, emptyList(), isValid = false)
	}

	/**
	 * Returns a result if [anchorChapterId] is a usable anchor in [chapters] (either it is the last
	 * chapter, or there are some chapters after it), or `null` if the anchor is absent from the list.
	 */
	private fun compareAgainst(
		manga: Manga,
		branch: String?,
		chapters: List<MangaChapter>,
		anchorChapterId: Long,
	): MangaUpdates.Success? {
		val newChapters = chapters.takeLastWhile { x -> x.id != anchorChapterId }
		return when {
			newChapters.isEmpty() -> MangaUpdates.Success(
				manga = manga,
				branch = branch,
				newChapters = emptyList(),
				isValid = chapters.lastOrNull()?.id == anchorChapterId,
			)

			newChapters.size == chapters.size -> null // anchor not found in the list

			else -> MangaUpdates.Success(manga, branch, newChapters, isValid = true)
		}
	}

	/**
	 * Date-based fallback: chapters uploaded strictly after [lastChapterDateMillis] are considered
	 * new. Returns `null` when the date is unusable (zero, or older than every chapter — which would
	 * flag the whole list and is more likely a data glitch than a real update).
	 */
	private fun compareByDate(
		manga: Manga,
		branch: String?,
		chapters: List<MangaChapter>,
		lastChapterDateMillis: Long,
	): MangaUpdates.Success? {
		if (lastChapterDateMillis <= 0L) return null
		val newChapters = chapters.filter { it.uploadDate > lastChapterDateMillis }
		return when {
			newChapters.isEmpty() -> MangaUpdates.Success(manga, branch, emptyList(), isValid = true)
			newChapters.size == chapters.size -> null
			else -> MangaUpdates.Success(manga, branch, newChapters, isValid = true)
		}
	}
}
