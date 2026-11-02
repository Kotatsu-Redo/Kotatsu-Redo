package org.koitharu.kotatsu.discover.domain

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.history.data.LibraryRow
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.suggestions.domain.SuggestionRanking
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a reader's library adds up to.
 *
 * @param taste each genre's share of the library
 * @param categories what they keep coming back to, most of all first
 * @param knownTitles title keys of everything in history or favourites, under every name it has
 * @param knownEntries the same as source and address
 */
class ReaderProfile(
	val taste: Map<String, Double>,
	val categories: List<ReaderCategory>,
	val knownTitles: Set<String>,
	val knownEntries: Set<Pair<String, String>>,
	/** Pages read of each manga, by id, as recorded or else worked out from how far through it they are. */
	val pagesRead: Map<Long, Int> = emptyMap(),
) {

	fun knows(manga: Manga): Boolean = DiscoverRanking.isKnown(manga, knownTitles)

	/** Whether a work the server named is already in the library, under its title or any address it has. */
	fun knows(title: String, entries: List<Pair<String, String>>): Boolean =
		DiscoverRanking.titleKey(title) in knownTitles || entries.any { it in knownEntries }
}

/**
 * Reads the whole library, kept until it changes because everything on the Explore screen asks for it.
 *
 * Goes straight to the columns it needs: the usual way of reading a list loads every field of every manga and
 * of each of its tags, which on 2143 titles took five times as long and only ever got looked at by this.
 */
@Singleton
class ReaderProfiles @Inject constructor(
	@ApplicationContext private val context: Context,
	private val db: MangaDatabase,
) {

	private val lock = Mutex()

	@Volatile
	private var current: ReaderProfile? = null
	private var currentVersion: String? = null

	/**
	 * As the library is now. Asking whether it has changed takes two small queries, so a title goes from being
	 * suggested to being known the moment it is opened, and nobody pays for reading the rest again until then.
	 */
	suspend fun get(): ReaderProfile = lock.withLock {
		val version = db.getHistoryDao().getLibraryVersion() + "/" + db.getFavouritesDao().getLibraryVersion()
		val known = current
		if (known != null && version == currentVersion) {
			return@withLock known
		}
		val read = db.getHistoryDao().findLibraryRows()
		val saved = db.getFavouritesDao().findLibraryRows()
		val pages = db.getStatsDao().findPagesPerManga().associate { it.mangaId to it.pages }
		withContext(Dispatchers.Default) { build(read, saved, pages, Instant.now()) }.also {
			current = it
			currentVersion = version
			saveCategories(it.categories)
		}
	}

	/** The latest there is without waiting for one, however old. */
	fun peek(): ReaderProfile? = current

	/**
	 * What the reader kept coming back to last time. Those change slowly, while working them out takes the whole
	 * library, so this is what lets the rows that depend on them go up the moment the screen opens.
	 */
	suspend fun lastCategories(): List<ReaderCategory>? = withContext(Dispatchers.IO) {
		prefs.getString(KEY_CATEGORIES, null)
			?.takeUnless { it.isEmpty() }
			?.split(CATEGORY_DIVIDER)
			?.map { ReaderCategory(it.split(TAG_DIVIDER)) }
	}

	/** Worked out again next time, with the last answer still there for [peek] until then. */
	fun invalidate() {
		currentVersion = null
	}

	private fun saveCategories(categories: List<ReaderCategory>) {
		prefs.edit {
			putString(KEY_CATEGORIES, categories.joinToString(CATEGORY_DIVIDER.toString()) { it.tags.joinToString(TAG_DIVIDER.toString()) })
		}
	}

	private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

	private fun build(read: List<LibraryRow>, saved: List<LibraryRow>, pages: Map<Long, Int>, now: Instant): ReaderProfile {
		val savedIds = saved.mapTo(HashSet(saved.size)) { it.id }
		val readIds = read.mapTo(HashSet(read.size)) { it.id }
		val library = ArrayList<LibraryEntry>(read.size + saved.size)
		val amounts = HashMap<Long, Int>(read.size * 2)
		read.forEachIndexed { index, row ->
			val percent = row.percent ?: -1f
			val lastRead = Instant.ofEpochMilli(row.updatedAt ?: 0L)
			val amount = SuggestionRanking.pagesRead(pages[row.id] ?: 0, row.percent, row.chapters)
			amounts[row.id] = amount
			library += LibraryEntry(
				tags = row.tagList(),
				weight = DiscoverRanking.entryWeight(
					isFavourite = row.id in savedIds,
					percent = percent,
					isRecent = index < RECENT_ENTRIES,
					isDropped = SuggestionRanking.isDropped(percent, lastRead, now),
				) * SuggestionRanking.engagement(amount) * SuggestionRanking.fade(Duration.between(lastRead, now)),
			)
		}
		saved.forEach { row ->
			if (row.id !in readIds) {
				library += LibraryEntry(row.tagList(), DiscoverRanking.entryWeight(true, null, isRecent = false, isDropped = false))
			}
		}
		val titles = HashSet<String>(library.size * 2)
		val entries = HashSet<Pair<String, String>>(library.size * 2)
		for (row in read.asSequence() + saved.asSequence().filter { it.id !in readIds }) {
			titles += DiscoverRanking.titleKey(row.title)
			row.altTitles?.split(ALT_TITLE_DIVIDER)?.forEach { alt ->
				val key = DiscoverRanking.titleKey(alt)
				if (key.isNotEmpty()) titles += key
			}
			entries += row.source to row.url
		}
		val summary = DiscoverRanking.summarize(library, MAX_CATEGORIES)
		return ReaderProfile(
			taste = summary.taste,
			categories = summary.categories,
			knownTitles = titles,
			knownEntries = entries,
			pagesRead = amounts,
		)
	}

	private fun LibraryRow.tagList(): List<String> = tags?.split(TAG_DIVIDER).orEmpty()

	private companion object {
		const val MAX_CATEGORIES = 3
		const val RECENT_ENTRIES = 60
		const val TAG_DIVIDER = '|'
		const val CATEGORY_DIVIDER = '\n'
		const val PREFS_NAME = "discover_profile"
		const val KEY_CATEGORIES = "categories"

		/** As manga store them. */
		const val ALT_TITLE_DIVIDER = '\n'
	}
}
