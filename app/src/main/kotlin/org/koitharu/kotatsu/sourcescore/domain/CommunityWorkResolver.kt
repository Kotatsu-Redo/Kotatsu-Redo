package org.koitharu.kotatsu.sourcescore.domain

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sourcescore.data.CommunityApi
import org.koitharu.kotatsu.sourcescore.data.CommunityDatabase
import org.koitharu.kotatsu.sourcescore.data.CommunitySettings
import org.koitharu.kotatsu.sourcescore.data.FingerprintDto
import org.koitharu.kotatsu.sourcescore.data.WorkRefEntity
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

	suspend fun workIdFor(manga: Manga): String? {
		if (!settings.isEnabled) return null
		val sourceKey = manga.url.ifEmpty { return null }
		val source = manga.source.name
		if (
			source.length !in 1..MAX_SOURCE_LENGTH ||
			!SOURCE_PATTERN.matches(source) ||
			sourceKey.length !in 1..MAX_SOURCE_KEY_LENGTH ||
			sourceKey.any(Char::isISOControl)
		) {
			Log.w(TAG, "Skipping a work fingerprint that exceeds the server's source/key limits")
			return null
		}
		val title = manga.title.toWireTitle() ?: return null
		val dao = database.getWorkRefDao()
		dao.find(source, sourceKey)?.let { return it.workId }

		val parserSource = manga.source as? MangaParserSource
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
					contentType = parserSource?.contentType?.name?.lowercase(),
					nsfw = manga.isNsfw,
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

	private companion object {
		const val CHAPTER_SCALE = 100
		const val MAX_SOURCE_LENGTH = 64
		const val MAX_SOURCE_KEY_LENGTH = 512
		const val MAX_TITLE_LENGTH = 500
		const val MAX_ALT_TITLES = 32
		val SOURCE_PATTERN = Regex("[A-Za-z0-9_.-]+")
	}
}
