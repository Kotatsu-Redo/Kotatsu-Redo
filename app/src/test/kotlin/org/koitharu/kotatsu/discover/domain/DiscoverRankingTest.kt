package org.koitharu.kotatsu.discover.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.sourcescore.data.ChartsResponse
import org.koitharu.kotatsu.sourcescore.data.SimilarWorkDto

class DiscoverRankingTest {

	private fun manga(title: String, vararg tags: String) = Manga(
		id = title.hashCode().toLong(),
		title = title,
		altTitles = emptySet(),
		url = "/$title",
		publicUrl = "https://example.com/$title",
		rating = -1f,
		contentRating = null,
		coverUrl = null,
		tags = tags.mapTo(LinkedHashSet()) { MangaTag(it, it, UnknownMangaSource) },
		state = null,
		authors = emptySet(),
		source = UnknownMangaSource,
	)

	@Test
	fun `each window prefers its own chart and falls back to the nearest`() {
		val everything = setOf(SortOrder.POPULARITY, SortOrder.POPULARITY_TODAY, SortOrder.POPULARITY_WEEK, SortOrder.POPULARITY_MONTH)
		assertEquals(SortOrder.POPULARITY_TODAY, DiscoverRanking.orderFor(DiscoverWindow.DAY, everything)?.order)
		assertEquals(SortOrder.POPULARITY_WEEK, DiscoverRanking.orderFor(DiscoverWindow.WEEK, everything)?.order)
		assertEquals(SortOrder.POPULARITY_MONTH, DiscoverRanking.orderFor(DiscoverWindow.MONTH, everything)?.order)

		val allTimeOnly = DiscoverRanking.orderFor(DiscoverWindow.DAY, setOf(SortOrder.POPULARITY))
		assertEquals(SortOrder.POPULARITY, allTimeOnly?.order)
		assertTrue("all-time must count for less than a real daily chart", allTimeOnly!!.weight < 0.5)
	}

	@Test
	fun `a source with no popularity order has no chart`() {
		assertNull(DiscoverRanking.orderFor(DiscoverWindow.WEEK, setOf(SortOrder.ALPHABETICAL, SortOrder.UPDATED)))
	}

	@Test
	fun `a work high on several lists beats one that tops a single list`() {
		val first = RankedList(listOf(manga("Solo Hit"), manga("Everywhere")), 1.0)
		val second = RankedList(listOf(manga("Other"), manga("Everywhere")), 1.0)
		val third = RankedList(listOf(manga("Third"), manga("Everywhere")), 1.0)
		assertEquals("Everywhere", DiscoverRanking.fuse(listOf(first, second, third)).first().title)
	}

	@Test
	fun `the same work on two sources merges despite punctuation and case`() {
		val fused = DiscoverRanking.fuse(
			listOf(
				RankedList(listOf(manga("Solo Leveling!")), 1.0),
				RankedList(listOf(manga("SOLO  LEVELING")), 1.0),
			),
		)
		assertEquals(1, fused.size)
	}

	@Test
	fun `a real chart outranks an all-time list at the same position`() {
		val fused = DiscoverRanking.fuse(
			listOf(
				RankedList(listOf(manga("All Time Classic")), 0.25),
				RankedList(listOf(manga("This Week")), 1.0),
			),
		)
		assertEquals("This Week", fused.first().title)
	}

	@Test
	fun `new finds leave out what the reader already has`() {
		val finds = DiscoverRanking.newFinds(
			candidates = listOf(listOf(manga("Read Already"), manga("Brand New"))),
			taste = emptyMap(),
			known = setOf(DiscoverRanking.titleKey("Read Already")),
			limit = 10,
		)
		assertEquals(listOf("Brand New"), finds.map { it.title })
	}

	@Test
	fun `new finds put taste first but still leave room for the unexpected`() {
		val matching = List(10) { manga("Romance $it", "romance") }
		val others = List(10) { manga("Other $it", "cooking") }
		val finds = DiscoverRanking.newFinds(
			candidates = listOf(others, matching),
			taste = mapOf("romance" to 1.0),
			known = emptySet(),
			limit = 8,
		)
		assertTrue(finds.first().title.startsWith("Romance"))
		assertTrue("nothing outside their taste made the list", finds.any { it.title.startsWith("Other") })
	}

	@Test
	fun `one prolific source cannot fill the list`() {
		val finds = DiscoverRanking.newFinds(
			candidates = listOf(List(30) { manga("Big $it") }, List(3) { manga("Small $it") }),
			taste = emptyMap(),
			known = emptySet(),
			limit = 9,
		)
		assertEquals(3, finds.count { it.title.startsWith("Big") })
		assertEquals(3, finds.count { it.title.startsWith("Small") })
	}

	@Test
	fun `sources that list no tags still produce finds for someone with a taste`() {
		val finds = DiscoverRanking.newFinds(listOf(listOf(manga("No Tags"))), mapOf("romance" to 1.0), emptySet(), 5)
		assertEquals(1, finds.size)
	}

	private fun entry(vararg tags: String, weight: Double = 1.0) = LibraryEntry(tags.toList(), weight)

	@Test
	fun `taste is the share of a library and ignores case and spelling`() {
		val taste = DiscoverRanking.tasteOf(listOf(entry("Villainess", "Action"), entry("VILLAINESS"), entry("Sci-Fi")))
		assertTrue(taste.getValue("villainess") > taste.getValue("action"))
		assertTrue(DiscoverRanking.affinity(manga("x", "VILLAINESS"), taste) > 0.0)
		assertEquals(taste.getValue("sci-fi"), DiscoverRanking.affinity(manga("x", "Science Fiction"), taste), 0.0)
	}

	@Test
	fun `what a manga is published as is not a taste`() {
		for (tag in listOf("Adaptation", "Web Comic", "Manga", "Manhwa", "Long Strip", "Shounen(B)", "Seinen(M)", "Ecchi", "Mature")) {
			assertNull(tag, DiscoverRanking.canonicalTag(tag))
		}
		assertEquals("school life", DiscoverRanking.canonicalTag("School"))
		assertEquals("slice of life", DiscoverRanking.canonicalTag(" Slice of Life "))
	}

	@Test
	fun `genres that usually go together become one row`() {
		val library = List(10) { entry("Romance", "Comedy") } + List(6) { entry("Fantasy") } + List(2) { entry("Romance") }
		val categories = DiscoverRanking.categoriesOf(library, 3)
		assertEquals(listOf("romance", "comedy"), categories[0].tags)
		assertEquals("Romance \u00b7 Comedy", categories[0].title)
		assertEquals(listOf("fantasy"), categories[1].tags)
	}

	@Test
	fun `one site saying a title is adult settles it for every site`() {
		val clean = manga("Same Title")
		val tagged = manga("Same Title", "Hentai")
		for (lists in listOf(listOf(clean, tagged), listOf(tagged, clean))) {
			val fused = DiscoverRanking.fuse(lists.map { RankedList(listOf(it, manga("Other")), 1.0) })
			assertEquals(listOf("Other"), fused.map { it.title })
			val finds = DiscoverRanking.newFinds(lists.map { listOf(it, manga("Other")) }, emptyMap(), emptySet(), 10)
			assertEquals(listOf("Other"), finds.map { it.title })
		}
	}

	@Test
	fun `the ways sites say adult are recognised, and never become a row or a chip`() {
		for (tag in listOf("R-18", "Pornhwa", "Adulte", "Adult (18+)", "18+", "NSFW", "Erotica", "Porn")) {
			assertTrue(tag, DiscoverRanking.isAdultTag(tag))
			assertNull(tag, DiscoverRanking.canonicalTag(tag))
		}
		for (tag in listOf("Romance", "Mature Protagonist", "Adventure", "Sexual Harassment Survivor")) {
			assertFalse(tag, DiscoverRanking.isAdultTag(tag))
		}
	}

	@Test
	fun `a title key is the same with or without the shortcut`() {
		for (title in listOf("Solo Leveling!", "SOLO  LEVELING", "Re:Zero - 2nd", "", "...")) {
			assertEquals(title, title.lowercase().filter { it.isLetterOrDigit() }, DiscoverRanking.titleKey(title))
		}
		assertEquals("\u00e9t\u00e9", DiscoverRanking.titleKey("\u00c9T\u00c9!"))
		assertEquals("\u4fbf\u5229", DiscoverRanking.titleKey("\u4fbf \u5229"))
	}

	@Test
	fun `what is closest to a taste comes first and the rest is left out`() {
		val closest = DiscoverRanking.closestToTaste(
			listOf(manga("No tags"), manga("One", "Comedy"), manga("Both", "Comedy", "Romance")),
			mapOf("comedy" to 0.5, "romance" to 0.5),
			5,
		)
		assertEquals(listOf("Both", "One"), closest.map { it.title })
		assertTrue(DiscoverRanking.closestToTaste(listOf(manga("x", "Comedy")), emptyMap(), 5).isEmpty())
	}

	@Test
	fun `a row the stored suggestions leave short is filled with what else is closest to the taste`() {
		// One stored manhwa under the Manhwa filter used to make a row of one cover
		val rows = listOf(
			listOf(manga("Row A1", "Romance"), manga("Row A2", "Action")),
			listOf(manga("Row B1", "Fantasy")),
		)
		val row = DiscoverRanking.recommendedRow(
			stored = listOf(manga("Stored", "Action")),
			rows = rows,
			others = listOf(manga("Find", "Action")),
			taste = mapOf("action" to 0.6, "fantasy" to 0.3),
			size = 4,
		)
		// What was stored stays first, then the closest to the taste, then the rows in turn for what no taste tells
		assertEquals(
			listOf("Stored", "Row A2", "Find", "Row B1", "Row A1"),
			row.map { it.title }.distinct(),
		)
	}

	@Test
	fun `a row the stored suggestions fill is left to them`() {
		val stored = listOf(manga("One", "Action"), manga("Two", "Drama"))
		val row = DiscoverRanking.recommendedRow(
			stored = stored,
			rows = listOf(listOf(manga("Row", "Action"))),
			others = emptyList(),
			taste = mapOf("action" to 1.0),
			size = 2,
		)
		assertEquals(listOf("One", "Two"), row.map { it.title })
	}

	@Test
	fun `with nothing stored the row is what is closest to the taste, then the rows in turn`() {
		val row = DiscoverRanking.recommendedRow(
			stored = emptyList(),
			rows = listOf(listOf(manga("Plain"), manga("Liked", "Comedy")), listOf(manga("Other"))),
			others = emptyList(),
			taste = mapOf("comedy" to 1.0),
			size = 3,
		)
		assertEquals(listOf("Liked", "Plain", "Other"), row.map { it.title }.distinct())
	}

	@Test
	fun `publishing tags are noise and adult ones are not what that means`() {
		assertTrue(DiscoverRanking.isNoiseTag("Adaptation"))
		assertTrue(DiscoverRanking.isNoiseTag("Shounen(B)"))
		assertFalse(DiscoverRanking.isNoiseTag("Ecchi"))
		assertFalse(DiscoverRanking.isNoiseTag("Villainess"))
	}

	@Test
	fun `a row reads like a heading`() {
		assertEquals("Slice of Life", ReaderCategory(listOf("slice of life")).title)
		assertEquals("Of Mice", DiscoverRanking.genreTitle("of mice"))
	}

	@Test
	fun `the same genre on a source wins over a word that merely looks alike`() {
		fun tags(vararg titles: String) = titles.mapTo(LinkedHashSet()) { MangaTag(it, it, UnknownMangaSource) }
		assertEquals("Action", DiscoverRanking.findTag(tags("Fiction", "Action"), "action", 0.4f)?.title)
		assertEquals("School", DiscoverRanking.findTag(tags("Sports", "School"), "school life", 0.4f)?.title)
		assertEquals("Romances", DiscoverRanking.findTag(tags("Romances"), "romance", 0.4f)?.title)
		assertNull(DiscoverRanking.findTag(tags("Horror"), "comedy", 0.4f))
	}

	@Test
	fun `interleaving puts the top of every list first`() {
		val mixed = DiscoverRanking.interleave(listOf(listOf(manga("A1"), manga("A2"), manga("A3")), listOf(manga("B1"))))
		assertEquals(listOf("A1", "B1", "A2", "A3"), mixed.map { it.title })
		assertTrue(DiscoverRanking.interleave(emptyList()).isEmpty())
	}

	@Test
	fun `taste and the community chart move a title up a list`() {
		val list = RankedList(listOf(manga("Plain"), manga("Liked", "Romance"), manga("Charted")), 1.0)
		assertEquals("Plain", DiscoverRanking.fuse(listOf(list)).first().title)
		assertEquals("Liked", DiscoverRanking.fuse(listOf(list), taste = mapOf("romance" to 1.0)).first().title)
		val ranks = mapOf(DiscoverRanking.titleKey("Charted") to 0)
		assertEquals("Charted", DiscoverRanking.fuse(listOf(list), chart = ranks).first().title)
	}

	@Test
	fun `what the reader passed on counts against its genres`() {
		val taste = DiscoverRanking.applyDismissals(mapOf("harem" to 0.2, "romance" to 0.5), listOf(listOf("Harem"), listOf("Harem", "Horror")))
		assertTrue(taste.getValue("harem") < 0.2)
		assertEquals(0.5, taste.getValue("romance"), 0.0)
		assertTrue(taste.getValue("horror") < 0.0)
		val ranked = DiscoverRanking.fuse(
			listOf(RankedList(listOf(manga("Unwanted", "Horror"), manga("Neutral")), 1.0)),
			taste = mapOf("horror" to -1.0),
		)
		assertEquals("Neutral", ranked.first().title)
	}

	@Test
	fun `one pass in a genre the reader loves barely moves it`() {
		val taste = DiscoverRanking.applyDismissals(mapOf("romance" to 0.9), listOf(listOf("Romance")))
		assertEquals(0.9, taste.getValue("romance"), 0.02)
		assertTrue(taste.getValue("romance") < 0.9)
	}

	@Test
	fun `a genre passed on over and over by a reader who never reads it goes clearly negative, but only so far`() {
		val twenty = List(20) { listOf("Harem") }
		val taste = DiscoverRanking.applyDismissals(mapOf("romance" to 0.5), twenty)
		assertTrue(taste.getValue("harem") < -0.2)
		assertTrue(taste.getValue("harem") >= -0.5)
		assertEquals(0.5, taste.getValue("romance"), 0.0)
		val many = DiscoverRanking.applyDismissals(emptyMap(), List(5000) { listOf("Harem") })
		assertTrue(many.getValue("harem") >= -0.5)
	}

	@Test
	fun `nothing passed on leaves the taste as it was`() {
		val taste = mapOf("romance" to 0.5)
		assertEquals(taste, DiscoverRanking.applyDismissals(taste, emptyList()))
	}

	@Test
	fun `passing on more of a genre than the reader reads of it counts, and reading plenty of it cancels that`() {
		val passes = List(10) { listOf("Horror") }
		val reads = DiscoverRanking.applyDismissals(mapOf("horror" to 1.0), passes).getValue("horror")
		val doesNotRead = DiscoverRanking.applyDismissals(mapOf("horror" to 0.05), passes).getValue("horror")
		assertEquals(1.0, reads, 0.0)
		assertTrue(doesNotRead < 0.05)
	}

	@Test
	fun `titles with no usable tag still count as passes`() {
		val taste = DiscoverRanking.applyDismissals(emptyMap(), List(9) { listOf("Webtoon") } + listOf(listOf("Horror")))
		// One of ten carries it: a tenth of the way, and softened by how few passes there are
		assertEquals(-0.5 * (10 / 15.0) * 0.1, taste.getValue("horror"), 1e-9)
	}

	@Test
	fun `a title known under another name is known`() {
		val known = setOf(DiscoverRanking.titleKey("Na Honjaman Level Up"))
		val manga = manga("Solo Leveling").copy(altTitles = setOf("Na Honjaman Level-Up"))
		assertTrue(DiscoverRanking.isKnown(manga, known))
		assertFalse(DiscoverRanking.isKnown(manga("Other"), known))
	}

	@Test
	fun `a row keeps its top and changes the rest from day to day`() {
		val list = List(60) { manga("Title $it") }
		val today = DiscoverRanking.rotate(list, 100, 15)
		val tomorrow = DiscoverRanking.rotate(list, 101, 15)
		assertEquals(15, today.size)
		assertEquals(today, DiscoverRanking.rotate(list, 100, 15))
		assertEquals(list.take(5), today.take(5))
		assertEquals(list.take(5), tomorrow.take(5))
		assertFalse(today == tomorrow)
		assertEquals(list.take(3), DiscoverRanking.rotate(list.take(3), 100, 15))
	}

	@Test
	fun `a chart is narrowed down by what it says about each work`() {
		val work = SimilarWorkDto(workId = "1", title = "x", contentType = "manhwa", genres = listOf("romance", "slice of life"))
		assertTrue(DiscoverRanking.fits(work, DiscoverFilter()))
		assertTrue(DiscoverRanking.fits(work, DiscoverFilter(ContentType.MANHWA, "Slice Of Life")))
		assertFalse(DiscoverRanking.fits(work, DiscoverFilter(ContentType.MANGA)))
		assertFalse(DiscoverRanking.fits(work, DiscoverFilter(genre = "Action")))
	}

	@Test
	fun `a genre already in a row does not get its own`() {
		val library = List(10) { entry("Romance", "Comedy") } + List(9) { entry("Comedy", "Horror") }
		val all = DiscoverRanking.categoriesOf(library, 3).flatMap { it.tags }
		assertEquals(all.distinct(), all)
	}

	@Test
	fun `one odd title is not something the reader keeps coming back to`() {
		val library = List(20) { entry("Romance") } + entry("Cooking", weight = 2.0)
		assertEquals(listOf(listOf("romance")), DiscoverRanking.categoriesOf(library, 3).map { it.tags })
	}

	@Test
	fun `an empty library has no categories`() {
		assertTrue(DiscoverRanking.categoriesOf(emptyList(), 3).isEmpty())
		assertTrue(DiscoverRanking.tasteOf(listOf(entry("Romance", weight = 0.0))).isEmpty())
	}

	@Test
	fun `reading says more than opening, and abandoning says nothing`() {
		val read = DiscoverRanking.entryWeight(isFavourite = false, percent = 0.9f, isRecent = false, isDropped = false)
		val opened = DiscoverRanking.entryWeight(isFavourite = false, percent = 0.2f, isRecent = false, isDropped = false)
		val recent = DiscoverRanking.entryWeight(isFavourite = false, percent = 0.9f, isRecent = true, isDropped = false)
		val dropped = DiscoverRanking.entryWeight(isFavourite = false, percent = 0.02f, isRecent = false, isDropped = true)
		val keptAnyway = DiscoverRanking.entryWeight(isFavourite = true, percent = 0.02f, isRecent = false, isDropped = true)
		assertTrue(read > opened)
		assertTrue(recent > read)
		assertEquals(0.0, dropped, 0.0)
		assertTrue(keptAnyway > 0.0)
	}

	@Test
	fun `type and genre matching`() {
		assertTrue(DiscoverRanking.matchesType(manga("x"), null, ContentType.MANGA))
		assertTrue(DiscoverRanking.matchesType(manga("x"), ContentType.MANHWA, ContentType.MANHWA))
		assertTrue(DiscoverRanking.matchesType(manga("x", "Manhwa"), ContentType.MANHWA, ContentType.MANGA))
		assertFalse(DiscoverRanking.matchesType(manga("x"), ContentType.MANHWA, ContentType.MANGA))
		assertTrue(DiscoverRanking.matchesGenre(manga("x", "Slice-of-Life"), "Slice of life"))
		assertFalse(DiscoverRanking.matchesGenre(manga("x", "Action"), "Romance"))
	}

	@Test
	fun `adult manga are recognised from their tags when the rating is empty`() {
		assertTrue(DiscoverRanking.isAdult(manga("x", "Ecchi")))
		assertTrue(DiscoverRanking.isAdult(manga("x", "HENTAI")))
		assertTrue(DiscoverRanking.isAdult(manga("x", "18+")))
		assertFalse(DiscoverRanking.isAdult(manga("x", "Romance", "Mature Protagonist")))
		assertFalse(DiscoverRanking.isAdult(manga("x")))
	}

	@Test
	fun `hyphen, underscore and space spellings of one genre are one genre`() {
		for (tag in listOf("martial-arts", "martial_arts", "Martial  Arts", " MARTIAL arts (x)")) {
			assertEquals(tag, "martial arts", DiscoverRanking.canonicalTag(tag))
		}
		assertEquals("time travel", DiscoverRanking.canonicalTag("Time-Travel"))
		assertEquals("slice of life", DiscoverRanking.canonicalTag("slice-of-life"))
		for (tag in listOf("sci-fi", "sci fi", "scifi", "science fiction", "Sci_Fi", "Science-Fiction")) {
			assertEquals(tag, "sci-fi", DiscoverRanking.canonicalTag(tag))
		}
		assertEquals("superpower", DiscoverRanking.canonicalTag("Superpowers"))
		assertEquals("romance", DiscoverRanking.canonicalTag("Rom-Com"))
	}

	@Test
	fun `a publishing word run together or hyphenated is still noise`() {
		for (tag in listOf("longstrip", "full-color", "webcomic", "web-comic", "fullcolor", "One-Shot", "4-koma", "Based on a Web Novel", "Colored")) {
			assertNull(tag, DiscoverRanking.canonicalTag(tag))
			assertTrue(tag, DiscoverRanking.isNoiseTag(tag))
		}
		assertFalse(DiscoverRanking.isNoiseTag("Martial-Arts"))
	}

	@Test
	fun `a title is not scored higher for listing more tags`() {
		// The ten others are in the taste a little, as the tags of a real library are: summed, they used to put Many first
		val taste = mapOf("action" to 0.3, "romance" to 0.3, "comedy" to 0.3) + (0 until 10).associate { "other $it" to 0.02 }
		val few = manga("Few", "Action", "Romance", "Comedy")
		val many = manga("Many", "Action", "Romance", "Comedy", *Array(10) { "Other $it" })
		assertTrue(DiscoverRanking.affinity(few, taste) > DiscoverRanking.affinity(many, taste))
		assertEquals(listOf("Few", "Many"), DiscoverRanking.closestToTaste(listOf(many, few), taste, 5).map { it.title })
	}

	@Test
	fun `taste still moves a matching title up, and more tags do not move it further`() {
		val taste = mapOf("action" to 0.5, "romance" to 0.5, "comedy" to 0.5, "drama" to 0.5, "horror" to 0.5) +
			(0 until 15).associate { "other $it" to 0.02 }
		val five = manga("Five", "Action", "Romance", "Comedy", "Drama", "Horror")
		val twenty = manga("Twenty", "Action", "Romance", "Comedy", "Drama", "Horror", *Array(15) { "Other $it" })
		val plain = manga("Plain", "Cooking")
		val match = manga("Match", "Action")
		val fusedPlain = DiscoverRanking.fuse(listOf(RankedList(listOf(plain), 1.0), RankedList(listOf(match), 1.0)), taste)
		assertEquals("Match", fusedPlain.first().title)
		val fused = DiscoverRanking.fuse(listOf(RankedList(listOf(twenty), 1.0), RankedList(listOf(five), 1.0)), taste)
		assertEquals(listOf("Five", "Twenty"), fused.map { it.title })
	}

	@Test
	fun `personalize averages by the root of the tag count`() {
		val taste = mapOf("romance" to 0.5, "comedy" to 0.3)
		// (0.5 + 0.3) / sqrt(4) = 0.4
		assertEquals(2.0 * (1.0 + 0.6 * 0.4), DiscoverRanking.personalize(2.0, listOf("Romance", "Comedy", "Action", "Cooking"), taste), 1e-9)
		assertEquals(2.0, DiscoverRanking.personalize(2.0, listOf("Manga"), taste), 0.0)
		assertEquals(2.0, DiscoverRanking.personalize(2.0, listOf("Romance"), emptyMap()), 0.0)
	}

	/** A response captured from the server's own route test, so the two sides cannot drift unnoticed. */
	@Test
	fun `the server's chart response parses`() {
		val body = """{"window":"week","generated_at":1793456531761,"works":[{"work_id":"6058","title":"The Work","year":2020,"content_type":"manhwa","nsfw":false,"genres":["romance","villainess"],"shared_genres":0,"score":2.55,"sources":[{"source":"SOURCE_A","key":"/6058","reporters":2,"last_seen":"2026-10-31","verified":false}]}]}"""
		val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<ChartsResponse>(body)
		assertEquals("week", parsed.window)
		val work = parsed.works.single()
		assertEquals("6058", work.workId)
		assertEquals("/6058", work.sources.single().key)
	}
}
