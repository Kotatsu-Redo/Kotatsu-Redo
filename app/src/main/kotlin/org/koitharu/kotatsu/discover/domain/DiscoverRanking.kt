package org.koitharu.kotatsu.discover.domain

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.almostEquals
import org.koitharu.kotatsu.sourcescore.data.SimilarWorkDto
import kotlin.math.sqrt
import kotlin.random.Random

enum class DiscoverWindow(val wireName: String, @StringRes val titleRes: Int) {
	DAY("day", R.string.top_today),
	WEEK("week", R.string.top_this_week),
	MONTH("month", R.string.top_this_month),
}

/** What a library adds up to. See [DiscoverRanking.summarize]. */
class LibrarySummary(val taste: Map<String, Double>, val categories: List<ReaderCategory>)

/** One manga in the reader's library: what it is tagged with, and how much it says about them. */
class LibraryEntry(val tags: Collection<String>, val weight: Double)

/**
 * Something the reader keeps coming back to: one genre, or two that usually go together in what they read,
 * since a source asked for both answers with far closer matches than for either alone.
 */
data class ReaderCategory(val tags: List<String>) {

	val title: String
		get() = tags.joinToString(" \u00b7 ") { DiscoverRanking.genreTitle(it) }

	val key: String
		get() = tags.joinToString("+")
}

/** A source's own idea of what is popular, and how far it is from what was asked. */
data class WeightedOrder(val order: SortOrder, val weight: Double)

/** One ranked answer: best first, and how much it counts for in the final list. */
class RankedList(val manga: List<Manga>, val weight: Double)

/**
 * No server knows what is popular across manga sources this week, but the sources know about themselves,
 * so charts are built by asking each and combining the answers.
 */
object DiscoverRanking {

	private const val RANK_DAMPING = 30.0

	/**
	 * How far their taste can move a title within a list, and how far what the community is rating can.
	 * Affinity is averaged by the root of the tag count, which is smaller than the plain sum it was by more the more tags
	 * a title has. At 0.7 a title with three tags is moved as far as it was at 0.4, one with a single tag somewhat
	 * further and one with five somewhat less: over one to five tags it comes to the same on average.
	 */
	private const val TASTE_WEIGHT = 0.7
	private const val PERSONAL_WEIGHT = 0.6
	private const val TASTE_FLOOR = 1.0
	private const val CHART_WEIGHT = 0.5
	private const val DISMISSAL_STRENGTH = 0.5
	private const val DISMISSAL_SOFTNESS = 5.0

	/** A third of a row stays put, and the rest comes from three rows' worth of what is on the list. */
	private const val ROTATE_FIXED_DIVISOR = 3
	private const val ROTATE_POOL = 4

	/** Says what a manga is published as, not what it is about. Nobody's taste is "adaptation". */
	private val NOISE_TAGS = setOf(
		"adaptation", "web comic", "webcomic", "webtoon", "webtoons", "long strip", "full color", "full colour",
		"official colored", "manga", "manhwa", "manhua", "comic", "comics", "oneshot", "one shot", "4 koma", "4-koma",
		"doujinshi", "anthology", "award winning", "ongoing", "completed", "shounen", "shonen", "shoujo", "shojo",
		"seinen", "josei", "sexual violence", "gore", "based on a web novel", "based on a novel", "web novel",
		"colored", "coloured",
	)

	/** [NOISE_TAGS] with spaces and hyphens gone, so that `longstrip` and `web-comic` are caught as well. */
	private val NOISE_COMPACT = NOISE_TAGS.mapTo(HashSet()) { it.replace(" ", "").replace("-", "") }

	/** Different sources' spelling of one genre, by [plainTag], once a demographic marker like `shounen(b)` is gone. */
	private val TAG_ALIASES = mapOf(
		"school" to "school life",
		"sci fi" to "sci-fi",
		"science fiction" to "sci-fi",
		"scifi" to "sci-fi",
		"martial art" to "martial arts",
		"super power" to "superpower",
		"superpowers" to "superpower",
		"reincarnated" to "reincarnation",
		"rom com" to "romance",
	)

	/** How little of a library a genre can be and still be something the reader keeps coming back to. */
	private const val MIN_CATEGORY_SHARE = 0.05
	private const val MIN_CATEGORY_ENTRIES = 3

	/** How often a genre has to come with another, in what the reader reads, to be asked for together. */
	private const val PAIR_SHARE = 0.5

	private const val WEIGHT_READ = 2.0
	private const val WEIGHT_FAVOURITE = 1.0
	private const val WEIGHT_OPENED = 1.0
	private const val WEIGHT_RECENT_FACTOR = 1.5
	private const val READ_PERCENT = 0.5f

	private val LOWERCASE_WORDS = setOf("of", "and", "the", "in")

	private val ADULT_TAGS = setOf(
		"adult", "adults", "adulte", "adultes", "adulto", "adultos", "hentai", "smut", "ecchi", "mature", "erotica",
		"erotic", "erotique", "erotico", "\u00e9rotique", "er\u00f3tico", "nsfw", "18", "18plus", "adult18", "r18",
		"porn", "porno", "pornographic", "pornhwa", "pornographie", "sexual", "xxx",
	)

	/** The chart a source can offer for a window. A nearby period counts for less, all-time least. */
	fun orderFor(window: DiscoverWindow, available: Set<SortOrder>): WeightedOrder? {
		val preferences = when (window) {
			DiscoverWindow.DAY -> listOf(
				WeightedOrder(SortOrder.POPULARITY_TODAY, 1.0),
				WeightedOrder(SortOrder.POPULARITY_HOUR, 0.9),
				WeightedOrder(SortOrder.POPULARITY_WEEK, 0.6),
				WeightedOrder(SortOrder.POPULARITY_MONTH, 0.4),
				WeightedOrder(SortOrder.POPULARITY, 0.25),
			)

			DiscoverWindow.WEEK -> listOf(
				WeightedOrder(SortOrder.POPULARITY_WEEK, 1.0),
				WeightedOrder(SortOrder.POPULARITY_TODAY, 0.8),
				WeightedOrder(SortOrder.POPULARITY_MONTH, 0.8),
				WeightedOrder(SortOrder.POPULARITY_YEAR, 0.4),
				WeightedOrder(SortOrder.POPULARITY, 0.3),
			)

			DiscoverWindow.MONTH -> listOf(
				WeightedOrder(SortOrder.POPULARITY_MONTH, 1.0),
				WeightedOrder(SortOrder.POPULARITY_WEEK, 0.8),
				WeightedOrder(SortOrder.POPULARITY_YEAR, 0.6),
				WeightedOrder(SortOrder.POPULARITY, 0.35),
			)
		}
		return preferences.firstOrNull { it.order in available }
	}

	/** The order that shows what a source has only just got, if it can show that at all. */
	fun newestOrder(available: Set<SortOrder>): SortOrder? =
		listOf(SortOrder.NEWEST, SortOrder.ADDED).firstOrNull { it in available }

	/** The same work on two sources is one entry, whatever punctuation each gives its title. */
	fun titleKey(title: String): String {
		// Run over a whole library at once, and nearly every title is plain ASCII
		val key = CharArray(title.length)
		var size = 0
		for (char in title) {
			when {
				char in 'a'..'z' || char in '0'..'9' -> key[size++] = char
				char in 'A'..'Z' -> key[size++] = char + ('a' - 'A')
				char.code < 128 -> Unit
				else -> return title.lowercase().filter { it.isLetterOrDigit() }
			}
		}
		return String(key, 0, size)
	}

	/**
	 * Reciprocal rank fusion: a work scores for each list it is on, by how high. Absolute numbers from
	 * different sites cannot be compared, while ranks can, and appearing on several at once is exactly
	 * the agreement a chart should reward.
	 */
	fun fuse(
		lists: List<RankedList>,
		taste: Map<String, Double> = emptyMap(),
		chart: Map<String, Int> = emptyMap(),
	): List<Manga> {
		class Entry(val manga: Manga, var score: Double)

		val entries = LinkedHashMap<String, Entry>()
		val adult = HashSet<String>()
		// Heaviest first, so a merged entry opens on the source that answered the question most exactly
		for (list in lists.sortedByDescending { it.weight }) {
			list.manga.forEachIndexed { rank, manga ->
				val key = titleKey(manga.title)
				// One site listing no tags for it says nothing, while another saying it is adult settles it
				if (isAdult(manga)) adult += key
				val entry = entries.getOrPut(key) { Entry(manga, 0.0) }
				entry.score += list.weight / (RANK_DAMPING + rank)
			}
		}
		entries.keys.removeAll(adult)
		if (taste.isNotEmpty() || chart.isNotEmpty()) {
			entries.forEach { (key, entry) ->
				entry.score *= 1.0 + TASTE_WEIGHT * affinity(entry.manga, taste).coerceIn(-TASTE_FLOOR, 1.0)
				chart[key]?.let { rank -> entry.score *= 1.0 + CHART_WEIGHT * RANK_DAMPING / (RANK_DAMPING + rank) }
			}
		}
		return entries.values.sortedByDescending { it.score }.map { it.manga }
	}

	/**
	 * The top of a list as it is, and the rest of a row drawn from further down it, differently each [day]. Otherwise
	 * a row is the same fifteen covers until the reader opens one, however many times they have passed them over.
	 */
	fun rotate(list: List<Manga>, day: Long, limit: Int): List<Manga> {
		if (list.size <= limit) {
			return list
		}
		val fixed = (limit / ROTATE_FIXED_DIVISOR).coerceAtLeast(1)
		val rest = list.subList(fixed, minOf(list.size, limit * ROTATE_POOL)).shuffled(Random(day))
		return list.take(fixed) + rest.take(limit - fixed)
	}

	/** Whether a manga is one the reader already has, under its own name or any other it goes by. */
	fun isKnown(manga: Manga, known: Set<String>): Boolean =
		titleKey(manga.title) in known || manga.altTitles.any { titleKey(it) in known }

	/** What a manga is about, one way of spelling each. */
	fun tagsOf(manga: Manga): Set<String> = manga.tags.mapNotNullTo(HashSet()) { canonicalTag(it.title) }

	/** Whether a work on a chart fits a filter, going by what the chart says about it. */
	fun fits(work: SimilarWorkDto, filter: DiscoverFilter): Boolean {
		if (filter.type != null && work.contentType?.lowercase() != filter.type.name.lowercase()) {
			return false
		}
		val genre = filter.genre ?: return true
		val wanted = canonicalTag(genre) ?: titleKey(genre)
		return work.genres.any { (canonicalTag(it) ?: titleKey(it)) == wanted }
	}

	/**
	 * A reader's taste less what they said they are not interested in, set against what they do read.
	 *
	 * Passing on one title says more about that title than about its genres, so a genre is only counted against
	 * as far as the passes lean on it harder than the reader's own library does: one pass in a genre they love
	 * barely moves it, while a genre they pass on far more often than they read goes clearly negative. The more
	 * is passed on the more this counts, but never past half of how far the passes lean.
	 *
	 * @param dismissed the tags of each title passed on
	 */
	fun applyDismissals(taste: Map<String, Double>, dismissed: Collection<List<String>>): Map<String, Double> {
		if (dismissed.isEmpty()) {
			return taste
		}
		val count = dismissed.size
		val shares = HashMap<String, Int>()
		dismissed.forEach { tags ->
			tags.mapNotNullTo(HashSet()) { canonicalTag(it) }.forEach { shares.merge(it, 1, Int::plus) }
		}
		val strength = DISMISSAL_STRENGTH * count / (count + DISMISSAL_SOFTNESS)
		val result = HashMap(taste)
		shares.forEach { (tag, passed) ->
			val liked = taste[tag] ?: 0.0
			val penalty = strength * maxOf(0.0, passed.toDouble() / count - liked)
			result[tag] = liked - penalty
		}
		return result
	}

	/**
	 * [score] moved towards what this reader reads, by how much of their taste [genres] echo. A nudge rather than
	 * the answer: what is alike still decides, this says which of the alike ones are for them. Averaged by the
	 * root of how many genres there are, or the most heavily tagged title would always come out closest.
	 */
	fun personalize(score: Double, genres: Collection<String>, taste: Map<String, Double>): Double {
		if (taste.isEmpty()) {
			return score
		}
		val tags = genres.mapNotNullTo(HashSet()) { canonicalTag(it) }
		if (tags.isEmpty()) {
			return score
		}
		val echo = echoOf(tags, taste)
		return score * (1.0 + PERSONAL_WEIGHT * echo.coerceIn(-1.0, 1.0))
	}

	/**
	 * How much of the reader's taste a manga's tags echo. [taste] maps lower-case tags to weights. Averaged by the
	 * root of the tag count: as a plain sum, titles never opened (13 tags on average) beat titles read (6 tags)
	 * in 82% of pairs, only because their source lists more tags.
	 */
	fun affinity(manga: Manga, taste: Map<String, Double>): Double =
		echoOf(manga.tags.mapNotNullTo(HashSet()) { canonicalTag(it.title) }, taste)

	private fun echoOf(tags: Set<String>, taste: Map<String, Double>): Double {
		if (tags.isEmpty() || taste.isEmpty()) {
			return 0.0
		}
		return tags.sumOf { taste[it] ?: 0.0 } / sqrt(tags.size.toDouble())
	}

	/**
	 * Picks what is genuinely new to this reader: not on their shelves, close to their taste where that
	 * can be told, and from several places rather than whichever source is most prolific.
	 *
	 * A part of the list is deliberately left to plain newness. Otherwise this only ever shows more of
	 * what the reader already likes, which is not much of a finding, and sources that list no tags would
	 * never appear at all.
	 *
	 * @param candidates per source, newest first
	 * @param known title keys of everything the reader has already opened or saved
	 */
	fun newFinds(
		candidates: List<List<Manga>>,
		taste: Map<String, Double>,
		known: Set<String>,
		limit: Int,
	): List<Manga> {
		val perSource = (limit / 3).coerceAtLeast(1)
		class Candidate(val manga: Manga, val source: Int, val rank: Int, val affinity: Double)

		val seen = HashSet(known)
		// As in fuse: whichever site happens to be listed first must not decide whether it is adult
		candidates.forEach { list -> list.forEach { if (isAdult(it)) seen += titleKey(it.title) } }
		val all = candidates.flatMapIndexed { source, list ->
			list.mapIndexedNotNull { rank, manga ->
				if (seen.add(titleKey(manga.title)) && !isKnown(manga, known)) {
					Candidate(manga, source, rank, affinity(manga, taste))
				} else {
					null
				}
			}
		}
		val taken = HashMap<Int, Int>()
		fun MutableList<Manga>.takeFrom(items: List<Candidate>, count: Int) {
			var left = count
			for (item in items) {
				if (left == 0) return
				val used = taken[item.source] ?: 0
				if (used < perSource && item.manga !in this) {
					add(item.manga)
					taken[item.source] = used + 1
					left--
				}
			}
		}

		val (matching, others) = all.partition { it.affinity > 0.0 }
		return buildList {
			takeFrom(matching.sortedByDescending { it.affinity }, limit - limit / 4)
			// Newest across all sources in turn, and whatever taste left unfilled
			takeFrom(others.sortedBy { it.rank }, limit - size)
			takeFrom(matching.sortedByDescending { it.affinity }, limit - size)
		}
	}

	/** Says what a manga is published as, and nothing about what it is like. */
	fun isNoiseTag(title: String): Boolean = isNoise(plainTag(title))

	private fun isNoise(plain: String): Boolean = plain in NOISE_TAGS || plain.replace(" ", "") in NOISE_COMPACT

	/** A tag in lower case, without a marker in brackets, and with `-`, `_` and runs of spaces read as one space. */
	private fun plainTag(title: String): String {
		val cut = title.trim().lowercase().substringBefore('(')
		val out = StringBuilder(cut.length)
		for (char in cut) {
			val c = if (char == '-' || char == '_') ' ' else char
			if (c.isWhitespace()) {
				if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
			} else {
				out.append(c)
			}
		}
		return out.toString().trim()
	}

	/** One genre however a source spells it, or null if a tag says nothing about what a manga is like. */
	fun canonicalTag(title: String): String? {
		val plain = plainTag(title)
		if (isNoise(plain)) return null
		val tag = TAG_ALIASES[plain] ?: plain
		return tag.takeUnless { it.isEmpty() || isAdultTag(it) }
	}

	/** How a genre reads as a heading: `slice of life` is Slice of Life. */
	fun genreTitle(tag: String): String = tag.trim().split(' ').withIndex().joinToString(" ") { (index, word) ->
		if (index > 0 && word in LOWERCASE_WORDS) word else word.replaceFirstChar { it.uppercase() }
	}

	/**
	 * A source's own tag for a genre. The same genre under any spelling comes first, since going straight by
	 * how alike two words are finds Fiction when asked for Action.
	 */
	fun findTag(available: Collection<MangaTag>, genre: String, threshold: Float): MangaTag? {
		val canonical = canonicalTag(genre)
		return available.find { canonical != null && canonicalTag(it.title) == canonical }
			?: available.find { it.title.almostEquals(genre, threshold) }
	}

	/** What is closest to a reader's taste, best first, of what can be told from its tags. */
	fun closestToTaste(manga: List<Manga>, taste: Map<String, Double>, limit: Int): List<Manga> {
		if (taste.isEmpty()) {
			return emptyList()
		}
		return manga.asSequence()
			.map { it to affinity(it, taste) }
			.filter { it.second > 0.0 }
			.sortedByDescending { it.second }
			.map { it.first }
			.take(limit)
			.toList()
	}

	/** Takes from each list in turn, so that the top of each comes first. */
	fun interleave(lists: List<List<Manga>>): List<Manga> = buildList {
		val longest = lists.maxOfOrNull { it.size } ?: 0
		for (rank in 0 until longest) {
			lists.forEach { list -> list.getOrNull(rank)?.let(::add) }
		}
	}

	/**
	 * What goes in the row of recommendations: what the suggestions worker stored, first, and whenever that is
	 * short of a row, what else is on the screen, closest to the reader's taste first.
	 *
	 * It used to be one or the other, the rest only when nothing at all was stored. A filter leaves few of the
	 * stored ones, though: they were picked with no type or genre in mind, and a type can only be told from a tag
	 * or from a source that carries nothing else. One stored manhwa then made a row of one cover, right above
	 * rows full of manhwa.
	 *
	 * @param stored what the worker stored that can still be shown, best first
	 * @param rows what each row below holds, already asked for with the filter
	 * @param others what else the screen has, such as the new finds
	 * @param size how many covers make a row
	 */
	fun recommendedRow(
		stored: List<Manga>,
		rows: List<List<Manga>>,
		others: List<Manga>,
		taste: Map<String, Double>,
		size: Int,
	): List<Manga> {
		if (stored.size >= size) {
			return stored
		}
		return stored + closestToTaste(rows.flatten() + others, taste, size * 2) + interleave(rows)
	}

	/**
	 * How much one manga says about the reader. Reading most of something says more than opening it, and what
	 * they are reading now more than what they read years ago. Opened, barely read and abandoned says nothing,
	 * unless they kept it as a favourite anyway.
	 *
	 * @param percent how far they got, or null if not known
	 * @param isRecent among the last things they read
	 */
	fun entryWeight(isFavourite: Boolean, percent: Float?, isRecent: Boolean, isDropped: Boolean): Double {
		if (isDropped && !isFavourite) {
			return 0.0
		}
		val read = when {
			percent == null -> if (isFavourite) 0.0 else WEIGHT_OPENED
			percent >= READ_PERCENT -> WEIGHT_READ
			else -> WEIGHT_OPENED
		}
		val total = read + if (isFavourite) WEIGHT_FAVOURITE else 0.0
		return if (isRecent) total * WEIGHT_RECENT_FACTOR else total
	}

	/** A library reduced to one spelling of each genre. A big one has thousands of tags but only a few hundred different. */
	private class Tagged(val weight: Double, val tags: Set<String>)

	private fun tagged(library: List<LibraryEntry>): List<Tagged> {
		val spellings = HashMap<String, String?>()
		return library.map { entry ->
			val tags = HashSet<String>(entry.tags.size * 2)
			for (title in entry.tags) {
				val tag = if (title in spellings) spellings[title] else canonicalTag(title).also { spellings[title] = it }
				if (tag != null) tags += tag
			}
			Tagged(entry.weight, tags)
		}
	}

	/** The share of a library's weight each genre has: what ranking anything for this reader goes by. */
	fun tasteOf(library: List<LibraryEntry>): Map<String, Double> = shares(tagged(library))

	private fun shares(library: List<Tagged>): Map<String, Double> {
		val total = library.sumOf { it.weight }
		if (total <= 0.0) {
			return emptyMap()
		}
		val weights = HashMap<String, Double>()
		for (entry in library) {
			for (tag in entry.tags) {
				weights.merge(tag, entry.weight, Double::plus)
			}
		}
		return weights.mapValues { it.value / total }
	}

	/**
	 * What the reader keeps coming back to, most of all first.
	 *
	 * Taken in order of how much of the library each genre is, each joined by the genre that most often comes
	 * with it when that is at least half the time. A genre already part of a row does not get another: romance
	 * with comedy says it, and a row of plain comedy after it would mostly repeat it.
	 */
	fun categoriesOf(library: List<LibraryEntry>, limit: Int): List<ReaderCategory> =
		summarize(library, limit).categories

	/** [tasteOf] and [categoriesOf] at once, which between them only have to read the tags one time. */
	fun summarize(library: List<LibraryEntry>, limit: Int): LibrarySummary {
		val all = tagged(library)
		val taste = shares(all)
		val entries = all.filter { it.weight > 0.0 }
		val counts = HashMap<String, Int>()
		entries.forEach { entry -> entry.tags.forEach { counts.merge(it, 1, Int::plus) } }
		val ranked = taste.entries
			.filter { (tag, share) -> share >= MIN_CATEGORY_SHARE && (counts[tag] ?: 0) >= MIN_CATEGORY_ENTRIES }
			.sortedByDescending { it.value }
			.map { it.key }
		val covered = HashSet<String>()
		val categories = ArrayList<ReaderCategory>(limit)
		for (tag in ranked) {
			if (categories.size == limit) break
			if (!covered.add(tag)) continue
			var total = 0.0
			val together = HashMap<String, Double>()
			for (entry in entries) {
				if (tag !in entry.tags) continue
				total += entry.weight
				entry.tags.forEach { together.merge(it, entry.weight, Double::plus) }
			}
			// In order of share, so that between two equally often together the one they read more wins
			val partner = ranked.filter { it !in covered }.maxByOrNull { together[it] ?: 0.0 }
				?.takeIf { (together[it] ?: 0.0) >= total * PAIR_SHARE }
			if (partner != null) {
				covered += partner
				categories += ReaderCategory(listOf(tag, partner))
			} else {
				categories += ReaderCategory(listOf(tag))
			}
		}
		return LibrarySummary(taste, categories)
	}

	/**
	 * Whether a manga is adult, as far as it can be told from the manga. Many sources leave the rating
	 * empty and only say so in the tags.
	 */
	fun isAdult(manga: Manga): Boolean =
		manga.contentRating == ContentRating.ADULT || manga.tags.any { isAdultTag(it.title) }

	fun isAdultTag(tag: String): Boolean =
		titleKey(tag) in ADULT_TAGS || titleKey(tag.substringBefore('(')) in ADULT_TAGS

	/** Whether a manga fits a type, as far as anything can tell without the source filtering for it. */
	fun matchesType(manga: Manga, type: ContentType?, sourceType: ContentType?): Boolean {
		if (type == null || sourceType == type) {
			return true
		}
		val name = type.name.lowercase()
		return manga.tags.any { it.title.trim().lowercase() == name }
	}

	/** Whether a manga carries a genre. Sources disagree on spelling, so this goes by the letters only. */
	fun matchesGenre(manga: Manga, genre: String?): Boolean {
		if (genre == null) {
			return true
		}
		val key = titleKey(genre)
		return manga.tags.any { titleKey(it.title) == key }
	}
}
