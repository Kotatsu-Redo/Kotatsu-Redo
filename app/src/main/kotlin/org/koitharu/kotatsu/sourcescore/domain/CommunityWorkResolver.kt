package org.koitharu.kotatsu.sourcescore.domain

import android.util.Log
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.ParserMangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sourcescore.data.CommunityApi
import org.koitharu.kotatsu.sourcescore.data.CommunityDatabase
import org.koitharu.kotatsu.sourcescore.data.CommunitySettings
import org.koitharu.kotatsu.sourcescore.data.FingerprintDto
import org.koitharu.kotatsu.sourcescore.data.WorkRefEntity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CommunityWork"

/**
 * Turns a manga from any source into the work id the community server knows it by.
 *
 * One network round trip per manga per device, and an indexed local read every time after: the
 * mapping never changes, so caching it permanently is what makes the whole feature affordable at
 * this userbase size (PLAN.md §5).
 */
@Singleton
class CommunityWorkResolver @Inject constructor(
	private val settings: CommunitySettings,
	private val api: CommunityApi,
	private val database: CommunityDatabase,
) {

	val isAvailable: Boolean get() = settings.isEnabled

	/** Resolves on their way to the server, by (source, key). See [workIdFor]. */
	private val inFlight = ConcurrentHashMap<Pair<String, String>, Deferred<String?>>()
	private val coverReports = ConcurrentHashMap.newKeySet<Pair<String, String>>()

	suspend fun workIdFor(manga: Manga): String? {
		if (!settings.isEnabled) return null
		// Local files and the unknown/test placeholders have no site behind them: the key is a path on
		// this device and the title a folder name, so there is no community work to find.
		val parserSource = manga.source as? ParserMangaSource ?: return null
		if (manga.url.isEmpty()) return null
		val (source, sourceKey) = workKeyOf(manga.source.name, manga.url) ?: run {
			Log.w(TAG, "Skipping a work fingerprint that exceeds the server's source/key limits")
			return null
		}
		val title = manga.title.toWireTitle() ?: return null
		database.getWorkRefDao().find(source, sourceKey)?.let { ref ->
			reportCoverIfNew(manga, parserSource, ref, title)
			return ref.workId
		}

		// One request per entry, however many parts of the screen ask: the details screen asks from
		// its rating row and its related row at once, and again on each refresh of its data, and the
		// server charges every resolve of something new against an hourly quota. Run in the process
		// scope so a caller that goes away - the related row restarts on every refresh - does not
		// cancel the answer the others are waiting on.
		val entry = source to sourceKey
		val created = processLifecycleScope.async(Dispatchers.Default, start = CoroutineStart.LAZY) {
			resolveRemote(manga, parserSource, source, sourceKey, title)
		}
		// Registered outside the map's own locking: the completion handler writes to the map, which
		// ConcurrentHashMap refuses from inside computeIfAbsent.
		val request = inFlight.putIfAbsent(entry, created)?.also { created.cancel() }
			?: created.also { it.invokeOnCompletion { inFlight.remove(entry, created) } }
		request.start()
		return request.await()
	}

	/**
	 * An entry is only resolved once, so one resolved before covers were part of the question would otherwise
	 * never mention its cover. Goes out once for each, the next time it is opened, and nobody waits for it.
	 */
	private fun reportCoverIfNew(manga: Manga, parserSource: ParserMangaSource, ref: WorkRefEntity, title: String) {
		if (ref.resolvedAt >= settings.coverReportingSince || manga.wireCoverUrl() == null) {
			return
		}
		val entry = ref.source to ref.sourceKey
		if (!coverReports.add(entry)) {
			return
		}
		processLifecycleScope.launch(Dispatchers.Default) {
			try {
				resolveRemote(manga, parserSource, ref.source, ref.sourceKey, title, isCoverReport = true)
			} finally {
				coverReports.remove(entry)
			}
		}
	}

	private fun Manga.wireCoverUrl(): String? = coverUrl
		?.takeIf { it.length <= MAX_COVER_URL && it.startsWith("https://", ignoreCase = true) && it.none(Char::isISOControl) }

	private suspend fun resolveRemote(
		manga: Manga,
		parserSource: ParserMangaSource,
		source: String,
		sourceKey: String,
		title: String,
		isCoverReport: Boolean = false,
	): String? {
		val dao = database.getWorkRefDao()
		// A request that finished between the caller's cache read and this one starting has already
		// stored the answer.
		if (!isCoverReport) {
			dao.find(source, sourceKey)?.let { return it.workId }
		}

		val resolved = runCatchingCancellable {
			api.resolveWork(
				FingerprintDto(
					source = source,
					key = sourceKey,
					title = title,
					// Every rendering this source knows, because an exact key hit is what makes
					// resolution cheap - and the alt title is often the one another source uses.
					altTitles = listOfNotNull(manga.altTitle)
						.mapNotNull { it.toWireTitle() }
						.distinct()
						.take(MAX_ALT_TITLES),
					contentType = parserSource.contentType.name.lowercase(),
					nsfw = manga.isNsfw,
					// What this source calls the work's genres. Sent with the fingerprint that was
					// going out anyway: it describes the manga, not the reader, and it is how works
					// no catalogue has ever listed - most of them - get categories at all.
					genres = manga.tags.asSequence()
						.map { it.title.trim() }
						.filter { it.length in 1..MAX_GENRE_LABEL && it.none(Char::isISOControl) }
						.distinct()
						.take(MAX_GENRES)
						.toList(),
					coverUrl = manga.wireCoverUrl(),
				),
			)
		}.onFailure { Log.w(TAG, "Work resolution failed for ${manga.title}", it) }.getOrNull()
			?: return null

		withContext(Dispatchers.Default) {
			dao.upsert(
				WorkRefEntity(
					source = source,
					sourceKey = sourceKey,
					workId = resolved.workId,
					resolvedAt = System.currentTimeMillis(),
				),
			)
		}
		return resolved.workId
	}

	/** Repoints every cached source entry after the server reports that a work was merged. */
	suspend fun repoint(oldWorkId: String, newWorkId: String) {
		if (oldWorkId == newWorkId) return
		withContext(Dispatchers.Default) {
			database.getWorkRefDao().repoint(oldWorkId, newWorkId)
		}
	}

	/**
	 * The opaque chapter key a comment thread is scoped to.
	 *
	 * Until chapters are aligned across sources this is the chapter number scaled by 100, so 12.5 is
	 * 1250. That is stable for any source numbering its chapters the same way and wrong for the ones
	 * that do not - which is exactly the problem chapter alignment exists to solve.
	 */
	fun chapterKey(chapterNumber: Float): Long? =
		if (chapterNumber <= 0f) null else (chapterNumber * CHAPTER_SCALE).toLong()

	private fun String.toWireTitle(): String? = map { if (it.isISOControl()) ' ' else it }
		.joinToString("")
		.trim()
		.take(MAX_TITLE_LENGTH)
		.takeIf(String::isNotEmpty)

	companion object {

		/**
		 * The source and key a manga is known to the server by, or null if the server would refuse them. Every call
		 * that names a manga uses this, so that they all name it the same way.
		 */
		fun workKeyOf(source: String, key: String): Pair<String, String>? {
			if (
				source.length !in 1..MAX_SOURCE_LENGTH ||
				!SOURCE_PATTERN.matches(source) ||
				key.length !in 1..MAX_SOURCE_KEY_LENGTH ||
				key.any(Char::isISOControl)
			) {
				return null
			}
			return source to key
		}

		private const val CHAPTER_SCALE = 100
		private const val MAX_SOURCE_LENGTH = 64
		private const val MAX_SOURCE_KEY_LENGTH = 512
		private const val MAX_TITLE_LENGTH = 500
		private const val MAX_ALT_TITLES = 32

		/** Bounded well under the server's own cap: a work with fifty labels has ten real genres. */
		private const val MAX_GENRES = 20
		private const val MAX_GENRE_LABEL = 60
		private const val MAX_COVER_URL = 1024
		private val SOURCE_PATTERN = Regex("[A-Za-z0-9_.-]+")
	}
}
