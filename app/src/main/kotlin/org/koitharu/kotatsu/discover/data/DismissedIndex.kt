package org.koitharu.kotatsu.discover.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.discover.domain.DiscoverRanking
import org.koitharu.kotatsu.parsers.model.Manga

/** Whether the community server has been told about a pass. Only ever used when community features are on. */
@Serializable
enum class UploadState {
	/** Nothing to tell: the reader had community features off, or the manga has no address the server could know. */
	@SerialName("none")
	NONE,

	/** To be sent, and not yet accepted. */
	@SerialName("pending")
	PENDING,

	/** The server has it, so taking the pass back has to tell it too. */
	@SerialName("uploaded")
	UPLOADED,
}

/**
 * One manga the reader said they are not interested in, as it was when they said so.
 *
 * Kept as a small JSON string. A value written before this existed is only the tags joined by `|`, which
 * [decode] still reads, as an entry that knows nothing but its tags.
 */
@Serializable
data class DismissedEntry(
	val tags: List<String> = emptyList(),
	/** Title keys of the other names it goes by. */
	@SerialName("alt") val altKeys: List<String> = emptyList(),
	val source: String? = null,
	val url: String? = null,
	val title: String? = null,
	val time: Long = 0L,
	@SerialName("work") val workId: String? = null,
	@SerialName("up") val upload: UploadState = UploadState.NONE,
) {

	fun encode(): String = JSON.encodeToString(serializer(), this)

	companion object {

		private const val TAG_DIVIDER = "|"
		private const val MAX_ALT_KEYS = 32

		/**
		 * Other names shorter than this are not gone by: a second title of one or two letters ("S", "II") is
		 * shared by works that have nothing else in common, and one pass would hide them all.
		 */
		const val MIN_ALT_KEY_LENGTH = 4

		/** What some sources put in the field of other names when they have none. They name no work. */
		private val PLACEHOLDER_ALT_KEYS = setOf(
			"updating", "unknown", "none", "null", "comingsoon", "tba", "tbd", "na",
		)

		/** Whether a title key says anything when it is one of the other names of a manga. */
		fun isUsableAltKey(key: String): Boolean = key.length >= MIN_ALT_KEY_LENGTH && key !in PLACEHOLDER_ALT_KEYS

		private val JSON = Json {
			ignoreUnknownKeys = true
			// Most entries have no work id and nothing uploaded: leaving the defaults out keeps each value short
			encodeDefaults = false
		}

		/** What is stored for [manga]. [titleKey] is the key it is stored under. */
		fun of(manga: Manga, titleKey: String, workId: String?, upload: UploadState, time: Long): DismissedEntry =
			DismissedEntry(
				tags = manga.tags.map { it.title },
				altKeys = manga.altTitles.asSequence()
					.map { DiscoverRanking.titleKey(it) }
					.filter { isUsableAltKey(it) && it != titleKey }
					.distinct()
					.take(MAX_ALT_KEYS)
					.toList(),
				source = manga.source.name,
				url = manga.url.takeIf { it.isNotEmpty() },
				title = manga.title,
				time = time,
				workId = workId,
				upload = upload,
			)

		/**
		 * A stored value, whichever way it was written. JSON always starts with a brace, while the old value is
		 * tag titles, and a title starting with one is not something a source has.
		 */
		fun decode(raw: String): DismissedEntry {
			val text = raw.trim()
			if (text.startsWith("{")) {
				// Damaged: better to keep the title hidden by its key than to lose it
				return runCatching { JSON.decodeFromString(serializer(), text) }.getOrDefault(DismissedEntry())
			}
			return DismissedEntry(tags = text.split(TAG_DIVIDER).filter { it.isNotEmpty() })
		}
	}
}

/**
 * Everything the reader passed on, ready to be asked "was this passed on?". The one way anything that suggests
 * manga does that, so a title cannot come back through a place that only knows another name for it.
 *
 * @param entries each entry under the title key it was stored with
 */
class DismissedIndex(val entries: Map<String, DismissedEntry>) {

	private val titleKeys: Set<String>
	private val places: Set<Pair<String, String>>
	private val workIds: Set<String>

	init {
		val keys = HashSet<String>()
		val addresses = HashSet<Pair<String, String>>()
		val works = HashSet<String>()
		for ((key, entry) in entries) {
			keys += key
			entry.altKeys.filterTo(keys) { DismissedEntry.isUsableAltKey(it) }
			val source = entry.source
			val url = entry.url
			if (!source.isNullOrEmpty() && !url.isNullOrEmpty()) addresses += source to url
			entry.workId?.takeIf { it.isNotEmpty() }?.let { works += it }
		}
		titleKeys = keys
		places = addresses
		workIds = works
	}

	val isEmpty: Boolean
		get() = entries.isEmpty()

	val size: Int
		get() = entries.size

	/** The tags of each, for what they count against. */
	val tagLists: List<List<String>>
		get() = entries.values.map { it.tags }

	fun matches(manga: Manga): Boolean =
		matches(manga.title, manga.altTitles, manga.source.name, manga.url, null)

	/**
	 * Whether this is, under any name, address or work, something that was passed on.
	 *
	 * An empty key never matches: a title of only punctuation would otherwise stand for every other one.
	 */
	fun matches(title: String, altTitles: Collection<String>, source: String?, url: String?, workId: String?): Boolean {
		if (entries.isEmpty()) {
			return false
		}
		if (workId != null && workId in workIds) {
			return true
		}
		if (!source.isNullOrEmpty() && !url.isNullOrEmpty() && (source to url) in places) {
			return true
		}
		if (isPassedKey(DiscoverRanking.titleKey(title))) {
			return true
		}
		return altTitles.any { other ->
			val key = DiscoverRanking.titleKey(other)
			DismissedEntry.isUsableAltKey(key) && key in titleKeys
		}
	}

	private fun isPassedKey(key: String) = key.isNotEmpty() && key in titleKeys

	/** Without whether the server has been told: that changes after the fact and says nothing about what to hide. */
	private val identity: Map<String, DismissedEntry> by lazy {
		entries.mapValues { it.value.copy(upload = UploadState.NONE) }
	}

	override fun equals(other: Any?): Boolean = other is DismissedIndex && other.identity == identity

	override fun hashCode(): Int = identity.hashCode()

	companion object {

		val EMPTY = DismissedIndex(emptyMap())
	}
}
