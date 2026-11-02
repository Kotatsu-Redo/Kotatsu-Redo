package org.koitharu.kotatsu.discover.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps each chart on disk, so that opening the tab shows something at once and does not cost a request
 * to every source each time. Everything here can be fetched again, so it lives in the cache directory and
 * anything unreadable is simply a miss.
 */
@Singleton
class DiscoverCache @Inject constructor(
	@ApplicationContext context: Context,
) {

	class Entry(val manga: List<Manga>, val savedAt: Long)

	private val directory = File(context.cacheDir, "discover")
	private val memory = ConcurrentHashMap<String, Entry>()
	private val json = Json { ignoreUnknownKeys = true }

	suspend fun get(key: String): Entry? {
		memory[key]?.let { return it }
		return runCatchingCancellable {
			runInterruptible(Dispatchers.IO) {
				val file = fileOf(key)
				if (!file.exists()) {
					return@runInterruptible null
				}
				val stored = json.decodeFromString<StoredList>(file.readText())
				Entry(stored.manga.map { it.toManga() }, stored.savedAt)
			}
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()?.also { memory[key] = it }
	}

	suspend fun put(key: String, manga: List<Manga>) {
		val entry = Entry(manga, System.currentTimeMillis())
		memory[key] = entry
		runCatchingCancellable {
			runInterruptible(Dispatchers.IO) {
				directory.mkdirs()
				val text = json.encodeToString(StoredList(entry.savedAt, manga.map { StoredManga(it) }))
				val file = fileOf(key)
				val temporary = File(directory, file.name + ".tmp")
				temporary.writeText(text)
				temporary.renameTo(file)
				trim()
			}
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	/** Lists come and go with what the reader looks at, and a list that has gone quiet is not worth keeping. */
	private fun trim() {
		val files = directory.listFiles() ?: return
		if (files.size > MAX_FILES) {
			files.sortedBy { it.lastModified() }.take(files.size - KEPT_AFTER_TRIM).forEach { it.delete() }
		}
	}

	private fun fileOf(key: String) = File(directory, key.hashCode().toUInt().toString(16) + ".json")

	private companion object {
		const val MAX_FILES = 200
		const val KEPT_AFTER_TRIM = 150
	}

	@Serializable
	private class StoredList(val savedAt: Long, val manga: List<StoredManga>)

	@Serializable
	private class StoredManga(
		val id: Long,
		val title: String,
		val altTitles: Set<String>,
		val url: String,
		val publicUrl: String,
		val rating: Float,
		val contentRating: String?,
		val coverUrl: String?,
		val largeCoverUrl: String?,
		val state: String?,
		val authors: Set<String>,
		val source: String,
		val tags: List<StoredTag>,
	) {

		constructor(manga: Manga) : this(
			id = manga.id,
			title = manga.title,
			altTitles = manga.altTitles,
			url = manga.url,
			publicUrl = manga.publicUrl,
			rating = manga.rating,
			contentRating = manga.contentRating?.name,
			coverUrl = manga.coverUrl,
			largeCoverUrl = manga.largeCoverUrl,
			state = manga.state?.name,
			authors = manga.authors,
			source = manga.source.name,
			tags = manga.tags.map { StoredTag(it.title, it.key) },
		)

		fun toManga(): Manga {
			val mangaSource = MangaSource(source)
			return Manga(
				id = id,
				title = title,
				altTitles = altTitles,
				url = url,
				publicUrl = publicUrl,
				rating = rating,
				contentRating = contentRating?.let { name -> ContentRating.entries.find { it.name == name } },
				coverUrl = coverUrl,
				tags = tags.mapTo(LinkedHashSet()) { MangaTag(it.title, it.key, mangaSource) },
				state = state?.let { name -> MangaState.entries.find { it.name == name } },
				authors = authors,
				largeCoverUrl = largeCoverUrl,
				source = mangaSource,
			)
		}
	}

	@Serializable
	private class StoredTag(val title: String, val key: String)
}
