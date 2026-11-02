package org.koitharu.kotatsu.reader.domain.erase

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.reader.domain.indexOfPosition
import org.koitharu.kotatsu.reader.domain.withoutHidden
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import kotlin.random.Random

/**
 * Pages are synthesised: a strip of art with an "ad" pasted into it, varied the way real re-uploads vary
 * (compression noise, a different height on the page, a different resolution).
 */
class HiddenImageMatchingTest {

	private val adSeed = 7

	/**
	 * Comic-like content: flat shapes with hard edges, staggered so that no single row is a clean break
	 * across the whole width, because in real art it never is. Different per seed.
	 */
	private fun drawArt(pixels: IntArray, width: Int, top: Int, bottom: Int, seed: Int) {
		val random = Random(seed)
		var x = 0
		while (x < width) {
			val blockWidth = random.nextInt(6, 18).coerceAtMost(width - x)
			var y = top - random.nextInt(0, 12)
			while (y < bottom) {
				val blockHeight = random.nextInt(5, 16)
				val value = random.nextInt(20, 235)
				for (py in y.coerceAtLeast(top) until (y + blockHeight).coerceAtMost(bottom)) {
					for (px in x until x + blockWidth) {
						pixels[py * width + px] = value
					}
				}
				y += blockHeight
			}
			x += blockWidth
		}
	}

	/** The same picture at any size: drawn on a fixed grid and scaled, like a re-served image. */
	private fun drawAd(pixels: IntArray, width: Int, top: Int, height: Int) {
		val master = IntArray(96 * 48)
		drawArt(master, 96, 0, 48, adSeed)
		for (y in 0 until height) {
			for (x in 0 until width) {
				pixels[(top + y) * width + x] = master[(y * 48 / height) * 96 + x * 96 / width]
			}
		}
	}

	private fun lumaOf(pixels: IntArray, width: Int, noiseSeed: Int? = null, sourceScale: Int = 8): PageLuma {
		val height = pixels.size / width
		val random = noiseSeed?.let { Random(it) }
		val data = ByteArray(pixels.size) { i ->
			val noise = random?.nextInt(-3, 4) ?: 0
			(pixels[i] + noise).coerceIn(0, 255).toByte()
		}
		return PageLuma(width, height, width * sourceScale, height * sourceScale, data)
	}

	private fun ruleFor(luma: PageLuma, top: Int, bottom: Int, left: Int = 0, right: Int = luma.width) = HiddenImageRule(
		id = 1L,
		fingerprint = ImageFingerprint.of(luma, left, top, right, bottom),
		aspect = (bottom - top).toFloat() / (right - left),
		xStart = left.toFloat() / luma.width,
		xEnd = right.toFloat() / luma.width,
	)

	private fun pageWithAd(adTop: Int, height: Int = 800, seed: Int = 1, width: Int = 96, adHeight: Int = 48): IntArray {
		val pixels = IntArray(width * height)
		drawArt(pixels, width, 0, height, seed)
		drawAd(pixels, width, adTop, adHeight)
		return pixels
	}

	private val registered = lumaOf(pageWithAd(adTop = 100), 96).let { ruleFor(it, 100, 148) }

	@Test
	fun `a band is found again at another height on another page`() {
		val other = lumaOf(pageWithAd(adTop = 513, seed = 2), 96, noiseSeed = 11)
		val result = HiddenImageMatcher.resolve(other, listOf(registered))
		assertTrue(result is PageResolution.Masked)
		val band = (result as PageResolution.Masked).bands.single()
		// In source pixels, within a couple of preview rows of where the ad really is
		assertEquals(513 * 8f, band.top.toFloat(), 8f * 3)
		assertEquals((513 + 48) * 8f, band.bottom.toFloat(), 8f * 3)
		assertEquals(0, band.left)
		assertEquals(96 * 8, band.right)
	}

	/** "Never again" cannot depend on where the scan's steps happen to land. */
	@Test
	fun `an ad is found at every possible height`() {
		for (adTop in 300..360) {
			val page = lumaOf(pageWithAd(adTop = adTop, seed = adTop), 96, noiseSeed = adTop)
			val result = HiddenImageMatcher.resolve(page, listOf(registered))
			assertTrue("missed the ad at row $adTop: $result", result is PageResolution.Masked)
			val band = (result as PageResolution.Masked).bands.single()
			assertEquals("wrong edge for the ad at row $adTop", adTop * 8f, band.top.toFloat(), 8f * 2)
		}
	}

	@Test
	fun `the same image at another resolution still matches`() {
		val other = lumaOf(pageWithAd(adTop = 300, seed = 3, width = 128, height = 1000, adHeight = 64), 128, noiseSeed = 5)
		val result = HiddenImageMatcher.resolve(other, listOf(registered))
		assertTrue("expected a match, got $result", result is PageResolution.Masked)
	}

	@Test
	fun `other art on other pages does not match`() {
		for (seed in 100..160) {
			val pixels = IntArray(96 * 1600)
			drawArt(pixels, 96, 0, 1600, seed)
			val result = HiddenImageMatcher.resolve(lumaOf(pixels, 96, noiseSeed = seed), listOf(registered))
			assertEquals("false match with seed $seed", PageResolution.Visible, result)
		}
	}

	@Test
	fun `an ad repeated twice on a page is cut twice`() {
		val pixels = pageWithAd(adTop = 100)
		drawAd(pixels, 96, 600, 48)
		val result = HiddenImageMatcher.resolve(lumaOf(pixels, 96, noiseSeed = 9), listOf(registered))
		assertEquals(2, (result as PageResolution.Masked).bands.size)
	}

	@Test
	fun `an image filling the page hides the page`() {
		val pixels = IntArray(96 * 140)
		drawArt(pixels, 96, 0, 140, adSeed + 1)
		val luma = lumaOf(pixels, 96)
		val rule = ruleFor(luma, 0, 140)
		val result = HiddenImageMatcher.resolve(lumaOf(pixels, 96, noiseSeed = 2), listOf(rule))
		assertEquals(PageResolution.Hidden(setOf(1L)), result)
	}

	@Test
	fun `a corner watermark becomes a patch in the same place`() {
		val first = pageWithAd(adTop = 100)
		val watermark = IntArray(96 * 800)
		drawArt(watermark, 96, 0, 800, 42)
		fun stamp(target: IntArray, top: Int) {
			for (y in 0 until 30) {
				for (x in 60 until 96) {
					target[(top + y) * 96 + x] = watermark[y * 96 + x]
				}
			}
		}
		stamp(first, 700)
		val rule = ruleFor(lumaOf(first, 96), 700, 730, left = 60, right = 96)
		val second = IntArray(96 * 800)
		drawArt(second, 96, 0, 800, 9)
		stamp(second, 200)
		val result = HiddenImageMatcher.resolve(lumaOf(second, 96, noiseSeed = 1), listOf(rule))
		val masked = result as PageResolution.Masked
		assertTrue(masked.bands.isEmpty())
		val patch = masked.patches.single()
		assertEquals(60 * 8f, patch.left.toFloat(), 8f)
		assertEquals(200 * 8f, patch.top.toFloat(), 8f * 3)
	}

	@Test
	fun `a blank panel is not distinctive enough to remember`() {
		val blank = lumaOf(IntArray(96 * 200) { 250 }, 96, noiseSeed = 1)
		assertFalse(ImageFingerprint.of(blank, 0, 0, 96, 200).isDistinctive)
		// White with a little text on it is still nothing to recognise it by
		val sparse = IntArray(96 * 200) { 250 }
		for (x in 40 until 56) sparse[100 * 96 + x] = 30
		assertFalse(ImageFingerprint.of(lumaOf(sparse, 96), 0, 0, 96, 200).isDistinctive)
		assertTrue(registered.fingerprint.isDistinctive)
	}

	@Test
	fun `no rules means no work and nothing hidden`() {
		assertEquals(PageResolution.Visible, HiddenImageMatcher.resolve(lumaOf(pageWithAd(100), 96), emptyList()))
	}

	@Test
	fun `the sample size keeps pages small and long strips bounded`() {
		assertEquals(1, PageLuma.sampleSizeFor(100, 150))
		assertEquals(8, PageLuma.sampleSizeFor(800, 1200))
		assertEquals(4, PageLuma.sampleSizeFor(720, 16000))
		assertTrue(30000 / PageLuma.sampleSizeFor(720, 30000) <= PageLuma.MAX_HEIGHT)
	}

	// region proposals

	private fun gutteredStrip(): PageLuma {
		// art 0-300, gutter, ad 340-400, gutter, art 440-800
		val pixels = IntArray(96 * 800) { 255 }
		drawArt(pixels, 96, 0, 300, 1)
		drawAd(pixels, 96, 340, 60)
		drawArt(pixels, 96, 440, 800, 2)
		return lumaOf(pixels, 96, noiseSeed = 3)
	}

	@Test
	fun `a scrub across an ad between gutters finds exactly the ad`() {
		val luma = gutteredStrip()
		val proposal = EraseProposer.propose(luma, SourceRegion(20 * 8, 360 * 8, 80 * 8, 380 * 8), 2f)
		assertEquals(340 * 8f, proposal.objectRegion.top.toFloat(), 8f)
		assertEquals(400 * 8f, proposal.objectRegion.bottom.toFloat(), 8f)
	}

	// region images cut off by the edge of a page

	private fun cutRule(): HiddenImageRule {
		val whole = gutteredStrip()
		return HiddenImageRule(
			id = 1L,
			fingerprint = ImageFingerprint.of(whole, 0, 340, 96, 400),
			aspect = 60f / 96,
			xStart = 0f,
			xEnd = 1f,
			signature = ImageSignature.of(whole, 0, 340, 96, 400),
		)
	}

	/** The master the strips draw their ad from, cut after [rows] of it and shown from there. */
	private fun pageStartingInsideAd(hiddenRows: Int, height: Int = 700): IntArray {
		val pixels = IntArray(96 * height) { 255 }
		val ad = IntArray(96 * 60).also { drawAd(it, 96, 0, 60) }
		for (y in hiddenRows until 60) {
			for (x in 0 until 96) pixels[(y - hiddenRows) * 96 + x] = ad[y * 96 + x]
		}
		drawArt(pixels, 96, 120, height, 12)
		return pixels
	}

	@Test
	fun `an image with its top cut off by the page is found, however much is missing`() {
		for (hidden in listOf(2, 4, 9, 15, 22, 30, 40)) {
			val page = lumaOf(pageStartingInsideAd(hidden), 96, noiseSeed = hidden)
			val band = (HiddenImageMatcher.resolve(page, listOf(cutRule())) as? PageResolution.Masked)?.bands?.first()
			assertTrue("missed with $hidden rows cut off", band != null)
			assertEquals(0, band!!.top)
			assertEquals("wrong edge with $hidden rows cut off", (60 - hidden) * 8f, band.bottom.toFloat(), 8f * 4)
		}
	}

	@Test
	fun `an image with its bottom cut off by the page is found`() {
		for (kept in listOf(58, 56, 53, 45, 30)) {
			val pixels = IntArray(96 * 700) { 255 }
			drawArt(pixels, 96, 0, 500, 13)
			val ad = IntArray(96 * 60).also { drawAd(it, 96, 0, 60) }
			for (y in 0 until kept) {
				for (x in 0 until 96) pixels[(700 - kept + y) * 96 + x] = ad[y * 96 + x]
			}
			val page = lumaOf(pixels, 96, noiseSeed = kept)
			val band = (HiddenImageMatcher.resolve(page, listOf(cutRule())) as? PageResolution.Masked)?.bands?.last()
			assertTrue("missed with $kept rows left", band != null)
			assertEquals((700 - kept) * 8f, band!!.top.toFloat(), 8f * 4)
			assertEquals(700 * 8, band.bottom)
		}
	}

	@Test
	fun `too little of an image left on the page is not worth guessing at`() {
		val pixels = IntArray(96 * 700) { 255 }
		drawArt(pixels, 96, 0, 500, 13)
		val ad = IntArray(96 * 60).also { drawAd(it, 96, 0, 60) }
		for (y in 0 until 6) {
			for (x in 0 until 96) pixels[(694 + y) * 96 + x] = ad[y * 96 + x]
		}
		assertEquals(PageResolution.Visible, HiddenImageMatcher.resolve(lumaOf(pixels, 96), listOf(cutRule())))
	}

	@Test
	fun `a cut off image says how much of it is on the page next door`() {
		val page = lumaOf(pageStartingInsideAd(hiddenRows = 15), 96, noiseSeed = 3)
		val cut = (HiddenImageMatcher.resolve(page, listOf(cutRule())) as PageResolution.Masked).cuts.single()
		assertTrue(cut.isAtTop)
		assertEquals(15f / 96, cut.missingShare, 4f / 96)
	}

	/** Blank above a banner that is off the page is not missing from anywhere. */
	@Test
	fun `blank space in a rule does not count as being on the page next door`() {
		val first = IntArray(96 * 200) { 255 }
		val ad = IntArray(96 * 60).also { drawAd(it, 96, 0, 60) }
		for (y in 0 until 60) {
			for (x in 0 until 96) first[(70 + y) * 96 + x] = ad[y * 96 + x]
		}
		val whole = lumaOf(first, 96)
		val rule = HiddenImageRule(
			id = 1L,
			fingerprint = ImageFingerprint.of(whole, 0, 65, 96, 130),
			aspect = 65f / 96,
			xStart = 0f,
			xEnd = 1f,
			signature = ImageSignature.of(whole, 0, 65, 96, 130),
		)
		// The top 15 rows of that window are off the page: 5 of them blank, 10 of them the ad
		val page = lumaOf(pageStartingInsideAd(hiddenRows = 10), 96, noiseSeed = 3)
		val cut = (HiddenImageMatcher.resolve(page, listOf(rule)) as PageResolution.Masked).cuts.single()
		assertEquals(10f / 96, cut.missingShare, 6f / 96)
	}

	private fun pageEndingIn(contentRows: Int, blankAbove: Int, height: Int = 700): PageLuma {
		val pixels = IntArray(96 * height) { 255 }
		drawArt(pixels, 96, 0, height - contentRows - blankAbove, 41)
		drawArt(pixels, 96, height - contentRows, height, 42)
		return lumaOf(pixels, 96, noiseSeed = 5)
	}

	private val cutAtNextPage = EdgeCut(1L, isAtTop = true, missingShare = 10f / 96)

	@Test
	fun `the rest of a cut off image is taken from the end of the page before`() {
		val region = EraseProposer.findRemainder(pageEndingIn(contentRows = 10, blankAbove = 80), cutAtNextPage)
		assertTrue("not found", region != null)
		assertEquals(700 * 8, region!!.bottom)
		assertEquals(96 * 8, region.right)
		// The strip, and a little of the blank above it
		assertTrue("${'$'}region", region.top in (700 - 10 - 4) * 8..(700 - 10) * 8)
	}

	@Test
	fun `a page that just carries on is left alone`() {
		val pixels = IntArray(96 * 700)
		drawArt(pixels, 96, 0, 700, 43)
		assertEquals(null, EraseProposer.findRemainder(lumaOf(pixels, 96), cutAtNextPage))
	}

	@Test
	fun `a panel at the end of a page, far taller than what is missing, is left alone`() {
		assertEquals(null, EraseProposer.findRemainder(pageEndingIn(contentRows = 120, blankAbove = 80), cutAtNextPage))
	}

	@Test
	fun `a strip with no blank above it is left alone`() {
		assertEquals(null, EraseProposer.findRemainder(pageEndingIn(contentRows = 10, blankAbove = 2), cutAtNextPage))
	}

	@Test
	fun `a page ending in blank has no rest of anything`() {
		val pixels = IntArray(96 * 700) { 255 }
		drawArt(pixels, 96, 0, 500, 44)
		assertEquals(null, EraseProposer.findRemainder(lumaOf(pixels, 96), cutAtNextPage))
	}

	@Test
	fun `cut off at the bottom means the rest starts the next page`() {
		val pixels = IntArray(96 * 700) { 255 }
		drawArt(pixels, 96, 0, 10, 45)
		drawArt(pixels, 96, 90, 700, 46)
		val region = EraseProposer.findRemainder(lumaOf(pixels, 96, noiseSeed = 7), EdgeCut(1L, isAtTop = false, missingShare = 10f / 96))
		assertTrue("not found", region != null)
		assertEquals(0, region!!.top)
		assertTrue("${'$'}region", region.bottom in 10 * 8..(10 + 4) * 8)
	}

	// Each of these is a mistake that was really made on real pages, and must stay gone

	/**
	 * Sharp text on black, starting 3 columns in. 3/100 kept as a float comes back as 2.99, which used to be cut
	 * down to column 2, so a banner could not be found on the page it was erased from: it came straight back.
	 */
	@Test
	fun `an image is found on the very page it was erased from, wherever its edges fall`() {
		for (start in listOf(3, 7, 9, 11, 13, 17, 19, 23, 29)) {
			val pixels = IntArray(100 * 400)
			for (y in 150 until 190) {
				for (x in start until 95) {
					if ((x / 11 + y / 9) % 2 == 0) pixels[y * 100 + x] = 200
				}
			}
			val page = lumaOf(pixels, 100)
			val shape = EraseProposer.describe(page, SourceRegion(0, 120 * 8, 100 * 8, 220 * 8))!!
			assertTrue(shape.isRecognisable)
			val rule = HiddenImageRule(
				id = 1L,
				fingerprint = shape.fingerprint!!,
				aspect = shape.aspect,
				xStart = shape.xStart,
				xEnd = shape.xEnd,
				signature = shape.signature,
				padTop = shape.padTop,
				padBottom = shape.padBottom,
				removesRows = shape.removesRows,
			)
			assertTrue("not found starting at column $start", HiddenImageMatcher.resolve(page, listOf(rule)) !is PageResolution.Visible)
		}
	}

	/** What keeps an erase from ever coming straight back: this needs no recognising at all. */
	@Test
	fun `what was selected goes from its own page without being recognised`() {
		val pixels = IntArray(96 * 700)
		drawArt(pixels, 96, 0, 700, 80)
		val page = lumaOf(pixels, 96)
		val rule = HiddenImageRule(
			id = 1L,
			fingerprint = ImageFingerprint(0L, 0L, 0),
			aspect = 100f / 96,
			xStart = 0f,
			xEnd = 1f,
			pageId = 42L,
			yStart = 200f / 700,
			removesRows = true,
		)
		val band = (HiddenImageMatcher.resolve(page, listOf(rule)) as PageResolution.Masked).bands.single()
		assertEquals(200 * 8f, band.top.toFloat(), 8f)
		assertEquals(300 * 8f, band.bottom.toFloat(), 8f)
	}

	/** Dark art is close in brightness to a banner on black, and used to lose its top row of panels. */
	@Test
	fun `dark art at the top of a page is not a cut off banner on black`() {
		val whole = IntArray(96 * 200) { 0 }
		for (y in 60 until 120) {
			for (x in 10 until 86) if ((x / 6 + y / 6) % 2 == 0) whole[y * 96 + x] = 90
		}
		val luma = lumaOf(whole, 96)
		val rule = HiddenImageRule(
			id = 1L,
			fingerprint = ImageFingerprint.of(luma, 10, 60, 86, 120),
			aspect = 60f / 76,
			xStart = 10f / 96,
			xEnd = 86f / 96,
			signature = ImageSignature.of(luma, 10, 60, 86, 120),
		)
		for (seed in 300..340) {
			val art = IntArray(96 * 700)
			drawArt(art, 96, 0, 700, seed)
			for (i in art.indices) art[i] = art[i] / 4
			assertEquals("false match with seed $seed", PageResolution.Visible, HiddenImageMatcher.resolve(lumaOf(art, 96, noiseSeed = seed), listOf(rule)))
		}
	}

	/** Part of a tall stretch of story has too little in it to tell from another, so it is never looked for. */
	@Test
	fun `nothing tall is looked for cut off`() {
		val first = IntArray(96 * 300).also { drawArt(it, 96, 0, 300, 51) }
		val luma = lumaOf(first, 96)
		val rule = HiddenImageRule(
			id = 1L,
			fingerprint = ImageFingerprint.of(luma, 0, 0, 96, 300),
			aspect = 300f / 96,
			xStart = 0f,
			xEnd = 1f,
			signature = ImageSignature.of(luma, 0, 0, 96, 300),
		)
		val page = IntArray(96 * 700) { 255 }
		for (y in 0 until 100) {
			for (x in 0 until 96) page[y * 96 + x] = first[(200 + y) * 96 + x]
		}
		assertEquals(PageResolution.Visible, HiddenImageMatcher.resolve(lumaOf(page, 96), listOf(rule)))
	}

	/** What a cut off image has that the story carrying on does not: it stops, and blank space follows. */
	@Test
	fun `a matching start of a page with no blank after it is not a cut off image`() {
		val page = pageStartingInsideAd(hiddenRows = 15)
		// Art straight after the ad, with no gutter
		drawArt(page, 96, 45, 700, 12)
		assertEquals(PageResolution.Visible, HiddenImageMatcher.resolve(lumaOf(page, 96, noiseSeed = 3), listOf(cutRule())))
	}

	/** A spiky speech bubble and another just like it, saying something else. */
	private fun bubble(seed: Int): IntArray {
		val pixels = IntArray(96 * 80) { 255 }
		for (y in 0 until 80) {
			for (x in 0 until 96) {
				val dx = (x - 48) / 46f
				val dy = (y - 40) / 38f
				val distance = dx * dx + dy * dy
				if (distance in 0.85f..1f) pixels[y * 96 + x] = 90
			}
		}
		val random = Random(seed)
		for (line in 0 until 3) {
			var x = 26 + random.nextInt(0, 8)
			val y = 26 + line * 11
			while (x < 68) {
				val word = random.nextInt(4, 11)
				for (px in x until (x + word).coerceAtMost(70)) {
					for (py in y until y + 6) pixels[py * 96 + px] = 20
				}
				x += word + 3
			}
		}
		return pixels
	}

	@Test
	fun `a speech bubble is not another bubble of the same shape saying something else`() {
		val first = lumaOf(bubble(1), 96)
		val rule = HiddenImageRule(
			id = 1L,
			fingerprint = ImageFingerprint.of(first, 0, 0, 96, 80),
			aspect = 80f / 96,
			xStart = 0f,
			xEnd = 1f,
			signature = ImageSignature.of(first, 0, 0, 96, 80),
		)
		for (seed in 2..30) {
			val page = IntArray(96 * 700).also { drawArt(it, 96, 0, 700, seed) }
			val other = bubble(seed)
			for (y in 0 until 80) {
				for (x in 0 until 96) page[(300 + y) * 96 + x] = other[y * 96 + x]
			}
			assertEquals("matched a different bubble, seed $seed", PageResolution.Visible, HiddenImageMatcher.resolve(lumaOf(page, 96), listOf(rule)))
		}
		// The very same bubble elsewhere is still found
		val page = IntArray(96 * 700).also { drawArt(it, 96, 0, 700, 60) }
		val same = bubble(1)
		for (y in 0 until 80) {
			for (x in 0 until 96) page[(300 + y) * 96 + x] = same[y * 96 + x]
		}
		assertTrue(HiddenImageMatcher.resolve(lumaOf(page, 96, noiseSeed = 2), listOf(rule)) is PageResolution.Masked)
	}

	/** The rim of a speech bubble: nothing actually in there, and it would match the rim of every other. */
	@Test
	fun `a thin outline has too little in it to be looked for elsewhere`() {
		val rim = IntArray(96 * 60) { 255 }
		for (y in 0 until 60) {
			for (x in 0 until 96) {
				val dx = (x - 48) / 46f
				val dy = (y - 60) / 58f
				val distance = dx * dx + dy * dy
				if (distance in 0.97f..1f) rim[y * 96 + x] = 60
			}
		}
		val luma = lumaOf(rim, 96)
		val shape = EraseProposer.describe(luma, SourceRegion(0, 0, 96 * 8, 60 * 8))!!
		assertFalse(shape.isRecognisable)
		// A real banner, tight or not, is
		val banner = gutteredStrip()
		assertTrue(EraseProposer.describe(banner, SourceRegion(0, 320 * 8, 96 * 8, 420 * 8))!!.isRecognisable)
	}

	/** A match landing a row short used to leave the bottom of the text showing: a sliver, on any background. */
	@Test
	fun `a match that lands a little short takes the rest of the object with it`() {
		for (background in listOf(0, 255)) {
			val pixels = IntArray(96 * 300) { background }
			for (y in 100 until 160) {
				for (x in 10 until 86) if (x % 3 != 0) pixels[y * 96 + x] = 130
			}
			val page = lumaOf(pixels, 96)
			for (short in 1..3) {
				val rows = EraseProposer.growIntoBlank(page, 100 + short, 160 - short, 0, 96, 0, 0)
				assertTrue("left the top showing on $background, $short short: $rows", rows.first <= 100)
				assertTrue("left the bottom showing on $background, $short short: $rows", rows.last + 1 >= 160)
			}
		}
	}

	@Test
	fun `taking the rest of an object never runs on into the next panel`() {
		val pixels = IntArray(96 * 300)
		drawArt(pixels, 96, 0, 300, 61)
		val rows = EraseProposer.growIntoBlank(lumaOf(pixels, 96), 100, 160, 0, 96, 8, 8)
		assertEquals(100, rows.first)
		assertEquals(159, rows.last)
	}

	/** Recognised by what is in it, not the rectangle drawn around it. */
	@Test
	fun `the same thing with a different gap around it is the same thing`() {
		val selection = IntArray(96 * 400) { 255 }
		drawAd(selection, 96, 170, 60)
		val first = lumaOf(selection, 96)
		// Drawn sloppily, with plenty of blank all round
		val shape = EraseProposer.describe(first, SourceRegion(0, 120 * 8, 96 * 8, 290 * 8))!!
		val rule = HiddenImageRule(
			id = 1L,
			fingerprint = shape.fingerprint!!,
			aspect = shape.aspect,
			xStart = shape.xStart,
			xEnd = shape.xEnd,
			signature = shape.signature,
			padTop = shape.padTop,
			padBottom = shape.padBottom,
			removesRows = shape.removesRows,
		)
		for (gap in listOf(6, 12, 30, 80)) {
			val page = IntArray(96 * 800) { 255 }
			drawArt(page, 96, 0, 300, 70)
			drawAd(page, 96, 300 + gap, 60)
			drawArt(page, 96, 360 + 2 * gap, 800, 71)
			val band = (HiddenImageMatcher.resolve(lumaOf(page, 96, noiseSeed = gap), listOf(rule)) as? PageResolution.Masked)?.bands?.single()
			assertTrue("missed with a gap of $gap", band != null)
			assertTrue("cut into the art above with a gap of $gap: $band", band!!.top >= 300 * 8)
			assertTrue("cut into the art below with a gap of $gap: $band", band.bottom <= (360 + 2 * gap) * 8)
		}
	}

	@Test
	fun `other art starting at the top of a page is not mistaken for a cut off image`() {
		for (seed in 200..260) {
			val pixels = IntArray(96 * 700)
			drawArt(pixels, 96, 0, 700, seed)
			val result = HiddenImageMatcher.resolve(lumaOf(pixels, 96, noiseSeed = seed), listOf(cutRule()))
			assertEquals("false match with seed $seed", PageResolution.Visible, result)
		}
	}

	@Test
	fun `a white page edge does not match the blank part of a rule`() {
		// A rule whose image has a lot of white in it, on a page that is white at both ends
		val first = IntArray(96 * 200) { 255 }
		drawArt(first, 96, 60, 90, 31)
		val whole = lumaOf(first, 96)
		val rule = HiddenImageRule(
			id = 1L,
			fingerprint = ImageFingerprint.of(whole, 0, 0, 96, 200),
			aspect = 200f / 96,
			xStart = 0f,
			xEnd = 1f,
			signature = ImageSignature.of(whole, 0, 0, 96, 200),
		)
		val other = IntArray(96 * 900) { 255 }
		drawArt(other, 96, 200, 700, 32)
		assertEquals(PageResolution.Visible, HiddenImageMatcher.resolve(lumaOf(other, 96, noiseSeed = 1), listOf(rule)))
	}

	// endregion

	// region going a little past the object

	@Test
	fun `with blank all around the borders go a little past the object`() {
		val proposal = EraseProposer.propose(gutteredStrip(), SourceRegion(20 * 8, 360 * 8, 80 * 8, 380 * 8), 2f)
		// 96 wide, so 8 rows either way, well short of the 40 row gutters
		assertEquals((340 - 8) * 8f, proposal.region.top.toFloat(), 8f)
		assertEquals((400 + 8) * 8f, proposal.region.bottom.toFloat(), 8f)
	}

	@Test
	fun `borders never go past the blank space into the art`() {
		val pixels = IntArray(96 * 800) { 255 }
		drawArt(pixels, 96, 0, 335, 1) // a gutter of only 5 rows above
		drawAd(pixels, 96, 340, 60)
		drawArt(pixels, 96, 440, 800, 2)
		val proposal = EraseProposer.propose(lumaOf(pixels, 96), SourceRegion(20 * 8, 360 * 8, 80 * 8, 380 * 8), 2f)
		assertTrue("cut into the art: ${'$'}{proposal.region}", proposal.region.top >= 335 * 8)
	}

	@Test
	fun `against a hard edge with no blank the borders stay on the object`() {
		val pixels = IntArray(96 * 800)
		drawArt(pixels, 96, 0, 800, 21)
		drawAd(pixels, 96, 340, 60)
		val proposal = EraseProposer.propose(lumaOf(pixels, 96, noiseSeed = 4), SourceRegion(20 * 8, 360 * 8, 80 * 8, 380 * 8), 2f)
		assertEquals(proposal.objectRegion.top, proposal.region.top)
		assertEquals(proposal.objectRegion.bottom, proposal.region.bottom)
	}

	/** Otherwise it would be painted over and leave a stripe, instead of the rows closing up. */
	@Test
	fun `an image with blank at its sides takes the whole width`() {
		val pixels = IntArray(96 * 800) { 255 }
		drawArt(pixels, 96, 0, 300, 1)
		val ad = IntArray(56 * 60).also { drawArt(it, 56, 0, 60, 7) }
		for (y in 0 until 60) {
			for (x in 0 until 56) pixels[(340 + y) * 96 + 20 + x] = ad[y * 56 + x]
		}
		drawArt(pixels, 96, 440, 800, 2)
		val proposal = EraseProposer.propose(lumaOf(pixels, 96), SourceRegion(30 * 8, 360 * 8, 70 * 8, 380 * 8), 2f)
		assertEquals(0, proposal.region.left)
		assertEquals(96 * 8, proposal.region.right)
		assertEquals(20 * 8f, proposal.objectRegion.left.toFloat(), 8f)
	}

	@Test
	fun `tightening takes the blank off and says how much there was`() {
		val tight = EraseProposer.tighten(gutteredStrip(), SourceRegion(0, 320 * 8, 96 * 8, 420 * 8))
		assertEquals(340 * 8f, tight.region.top.toFloat(), 8f)
		assertEquals(400 * 8f, tight.region.bottom.toFloat(), 8f)
		assertEquals(20f / 60, tight.padTop, 0.05f)
		assertEquals(20f / 60, tight.padBottom, 0.05f)
	}

	@Test
	fun `tightening something all blank leaves it alone`() {
		val region = SourceRegion(0, 305 * 8, 96 * 8, 335 * 8)
		val tight = EraseProposer.tighten(gutteredStrip(), region)
		assertEquals(region, tight.region)
		assertEquals(0f, tight.padTop, 0f)
	}

	private fun adRule(padTop: Float = 0.5f, padBottom: Float = 0.5f): HiddenImageRule {
		val first = gutteredStrip()
		return HiddenImageRule(
			id = 1L,
			fingerprint = ImageFingerprint.of(first, 0, 340, 96, 400),
			aspect = 60f / 96,
			xStart = 0f,
			xEnd = 1f,
			signature = ImageSignature.of(first, 0, 340, 96, 400),
			padTop = padTop,
			padBottom = padBottom,
		)
	}

	@Test
	fun `a match takes the same blank with it on another page`() {
		val pixels = IntArray(96 * 1000) { 255 }
		drawArt(pixels, 96, 0, 400, 3)
		drawAd(pixels, 96, 500, 60)
		drawArt(pixels, 96, 700, 1000, 4)
		val band = (HiddenImageMatcher.resolve(lumaOf(pixels, 96, noiseSeed = 6), listOf(adRule())) as PageResolution.Masked).bands.single()
		assertEquals((500 - 30) * 8f, band.top.toFloat(), 8f * 2)
		assertEquals((560 + 30) * 8f, band.bottom.toFloat(), 8f * 2)
	}

	/** The reason the recognised area and the removed area are kept apart at all. */
	@Test
	fun `the same ad with a narrower gap still matches, and the art is not cut`() {
		val pixels = IntArray(96 * 1000) { 255 }
		drawArt(pixels, 96, 0, 495, 3) // 5 blank rows above, where the rule would like 30
		drawAd(pixels, 96, 500, 60)
		drawArt(pixels, 96, 565, 1000, 4) // and 5 below
		val band = (HiddenImageMatcher.resolve(lumaOf(pixels, 96, noiseSeed = 6), listOf(adRule())) as PageResolution.Masked).bands.single()
		assertTrue("cut into the art above: ${'$'}band", band.top >= 494 * 8)
		assertTrue("cut into the art below: ${'$'}band", band.bottom <= 566 * 8)
	}

	@Test
	fun `an ad on an otherwise blank page hides the page, not just the ad`() {
		val pixels = IntArray(96 * 700) { 255 }
		drawAd(pixels, 96, 300, 60)
		val result = HiddenImageMatcher.resolve(lumaOf(pixels, 96, noiseSeed = 8), listOf(adRule()))
		assertEquals(PageResolution.Hidden(setOf(1L)), result)
	}

	// endregion

	@Test
	fun `an ad pasted straight into the art with no gutter is still found by its hard edges`() {
		val pixels = IntArray(96 * 800)
		drawArt(pixels, 96, 0, 800, 21)
		drawAd(pixels, 96, 340, 60)
		val proposal = EraseProposer.propose(lumaOf(pixels, 96, noiseSeed = 4), SourceRegion(20 * 8, 360 * 8, 80 * 8, 380 * 8), 2f)
		assertEquals(340 * 8f, proposal.region.top.toFloat(), 8f)
		assertEquals(400 * 8f, proposal.region.bottom.toFloat(), 8f)
	}

	@Test
	fun `hidden pages leave the list but keep their numbers, and a chapter is never emptied`() {
		fun page(id: Long, index: Int, chapter: Long) = ReaderPage(id, "", null, chapter, index, UnknownMangaSource)
		val pages = listOf(page(1, 0, 10), page(2, 1, 10), page(3, 2, 10), page(4, 0, 20), page(5, 1, 20))

		val visible = withoutHidden(pages, setOf(2L, 4L, 5L))
		assertEquals(listOf(1L, 3L, 4L), visible.map { it.id })
		assertEquals(2, visible[1].index)

		// Saved at a page that has since been hidden: on to the next one still there
		assertEquals(1, visible.indexOfPosition(10L, 1))
		// Past the last visible page of a chapter: the last one there is
		assertEquals(1, withoutHidden(pages, setOf(3L)).indexOfPosition(10L, 2))
		assertEquals(null, visible.indexOfPosition(99L, 0))
	}

	@Test
	fun `with no edges to go by the borders start around the whole page`() {
		val pixels = IntArray(96 * 1400)
		drawArt(pixels, 96, 0, 1400, 5)
		val proposal = EraseProposer.propose(lumaOf(pixels, 96), SourceRegion(30 * 8, 700 * 8, 50 * 8, 705 * 8), 2f)
		assertEquals(SourceRegion(0, 0, 96 * 8, 1400 * 8), proposal.region)
	}

	// region masks

	/** A 1000 row page with rows 100-199 and 500-599 taken out. */
	private val twoBands = PageMask(
		sourceWidth = 400,
		sourceHeight = 1000,
		bands = listOf(SourceRegion(0, 100, 400, 200), SourceRegion(0, 500, 400, 600)),
		patches = emptyList(),
	)

	@Test
	fun `a mask makes the page shorter`() {
		assertEquals(800, twoBands.visibleHeight)
		assertEquals(1000, PageMask(400, 1000, emptyList(), emptyList()).visibleHeight)
	}

	@Test
	fun `rows map both ways around what was taken out`() {
		assertEquals(50, twoBands.toSource(50))
		assertEquals(200, twoBands.toSource(100))
		assertEquals(499, twoBands.toSource(399))
		assertEquals(600, twoBands.toSource(400))
		assertEquals(999, twoBands.toSource(799))
		for (visible in 0 until 800) {
			assertEquals(visible, twoBands.toVisible(twoBands.toSource(visible)))
		}
		// A row inside what was taken out lands on the join it left
		assertEquals(100, twoBands.toVisible(150))
		assertEquals(400, twoBands.toVisible(599))
	}

	@Test
	fun `a tile on one side of a band is one stretch of the file`() {
		assertEquals(listOf(0 until 50), twoBands.slices(0, 50))
		assertEquals(listOf(200 until 300), twoBands.slices(100, 200))
		assertEquals(listOf(600 until 800), twoBands.slices(400, 600))
	}

	@Test
	fun `a tile across a band joins the rows either side of it`() {
		assertEquals(listOf(50 until 100, 200 until 250), twoBands.slices(50, 150))
		assertEquals(listOf(0 until 100, 200 until 500, 600 until 1000), twoBands.slices(0, 800))
	}

	@Test
	fun `every visible row comes from the file exactly once, however it is tiled`() {
		for (tile in listOf(1, 7, 64, 256, 800)) {
			val rows = (0 until 800 step tile).flatMap { top ->
				twoBands.slices(top, minOf(top + tile, 800)).flatMap { it.toList() }
			}
			assertEquals(800, rows.size)
			assertEquals(rows.sorted(), rows)
			assertTrue(rows.none { it in 100 until 200 || it in 500 until 600 })
		}
	}

	@Test
	fun `a mask starting at the very top or running to the very bottom`() {
		val edges = PageMask(400, 1000, listOf(SourceRegion(0, 0, 400, 100), SourceRegion(0, 900, 400, 1000)), emptyList())
		assertEquals(800, edges.visibleHeight)
		assertEquals(100, edges.toSource(0))
		assertEquals(listOf(100 until 900), edges.slices(0, 800))
	}

	@Test
	fun `regions survive being saved`() {
		val regions = listOf(SourceRegion(0, 100, 400, 200), SourceRegion(10, 20, 30, 40))
		assertEquals(regions, PageMask.decode(PageMask.encode(regions)))
		assertEquals(emptyList<SourceRegion>(), PageMask.decode(PageMask.encode(emptyList())))
	}

	// endregion

	// region plain images

	/** Mostly white, with a little text wherever it says. Too little for the hash to go on by itself. */
	private fun plainPage(vararg textRows: Int): IntArray {
		val pixels = IntArray(96 * 140) { 250 }
		for (row in textRows) {
			for (y in row until row + 4) {
				for (x in 30 until 66) {
					if (x % 3 != 0) pixels[y * 96 + x] = 30
				}
			}
		}
		return pixels
	}

	private fun plainRule(pixels: IntArray): HiddenImageRule {
		val luma = lumaOf(pixels, 96)
		return HiddenImageRule(
			id = 1L,
			fingerprint = ImageFingerprint.of(luma, 0, 0, 96, 140),
			aspect = 140f / 96,
			xStart = 0f,
			xEnd = 1f,
			signature = ImageSignature.of(luma, 0, 0, 96, 140),
		)
	}

	@Test
	fun `a plain credit page is recognised again after re-encoding`() {
		val rule = plainRule(plainPage(40, 90))
		val again = lumaOf(plainPage(40, 90), 96, noiseSeed = 2)
		assertEquals(PageResolution.Hidden(setOf(1L)), HiddenImageMatcher.resolve(again, listOf(rule)))
	}

	@Test
	fun `a plain page with its text elsewhere is a different page`() {
		val rule = plainRule(plainPage(40, 90))
		val other = lumaOf(plainPage(20, 110), 96, noiseSeed = 2)
		assertEquals(PageResolution.Visible, HiddenImageMatcher.resolve(other, listOf(rule)))
	}

	@Test
	fun `a blank page can not match anything by its looks`() {
		val blank = lumaOf(IntArray(96 * 140) { 250 }, 96)
		assertTrue(ImageSignature.isBlank(ImageSignature.of(blank, 0, 0, 96, 140)))
		assertFalse(ImageSignature.isBlank(ImageSignature.of(lumaOf(plainPage(40), 96), 0, 0, 96, 140)))
	}

	@Test
	fun `a rule for one page applies exactly where it was made`() {
		// A blank stretch on a page that has art on it too, so the page itself stays
		val blank = lumaOf(IntArray(96 * 800) { 250 }.also { drawArt(it, 96, 600, 800, 3) }, 96)
		val rule = HiddenImageRule(
			id = 1L,
			fingerprint = ImageFingerprint(0L, 0L, 250),
			aspect = 0.5f,
			xStart = 0f,
			xEnd = 1f,
			pageId = 42L,
			yStart = 0.25f,
		)
		val band = (HiddenImageMatcher.resolve(blank, listOf(rule)) as PageResolution.Masked).bands.single()
		assertEquals(SourceRegion(0, 200 * 8, 96 * 8, 200 * 8 + 48 * 8), band)
	}

	@Test
	fun `a whole blank page hidden by its own rule hides the page`() {
		val blank = lumaOf(IntArray(96 * 140) { 250 }, 96)
		val rule = HiddenImageRule(
			id = 1L,
			fingerprint = ImageFingerprint(0L, 0L, 250),
			aspect = 140f / 96,
			xStart = 0f,
			xEnd = 1f,
			pageId = 42L,
			yStart = 0f,
		)
		assertEquals(PageResolution.Hidden(setOf(1L)), HiddenImageMatcher.resolve(blank, listOf(rule)))
	}

	/** The second opinion: a busy page whose hash happens to be close still has to look the same. */
	@Test
	fun `a rule with a signature still finds its image at every height`() {
		val first = lumaOf(pageWithAd(adTop = 100), 96)
		val rule = HiddenImageRule(
			id = 1L,
			fingerprint = ImageFingerprint.of(first, 0, 100, 96, 148),
			aspect = 48f / 96,
			xStart = 0f,
			xEnd = 1f,
			signature = ImageSignature.of(first, 0, 100, 96, 148),
		)
		for (adTop in 300..340) {
			val page = lumaOf(pageWithAd(adTop = adTop, seed = adTop), 96, noiseSeed = adTop)
			assertTrue("missed at row $adTop", HiddenImageMatcher.resolve(page, listOf(rule)) is PageResolution.Masked)
		}
	}

	// endregion

	// region erasing without asking

	/** White with lines of dark text: nothing like painted panels. */
	private fun drawTextBanner(pixels: IntArray, width: Int, top: Int, height: Int) {
		for (y in top until top + height) {
			for (x in 0 until width) {
				val isText = (y - top) % 8 < 3 && x % 5 < 3 && x in 8 until width - 8
				pixels[y * width + x] = if (isText) 25 else 245
			}
		}
	}

	private fun stripWith(drawInsert: (IntArray) -> Unit): PageLuma {
		val pixels = IntArray(96 * 800) { 255 }
		drawArt(pixels, 96, 0, 300, 1)
		drawInsert(pixels)
		drawArt(pixels, 96, 440, 800, 2)
		return lumaOf(pixels, 96, noiseSeed = 3)
	}

	private val insideInsert = SourceRegion(20 * 8, 360 * 8, 80 * 8, 380 * 8)

	private fun erasesWithoutAsking(page: PageLuma, stroke: SourceRegion = insideInsert, neighbours: List<PageLuma> = emptyList()): Boolean {
		val proposal = EraseProposer.propose(page, stroke, 2f)
		return proposal.isStandalone && EraseProposer.isUnlikeTheRest(page, proposal, neighbours)
	}

	@Test
	fun `a banner that looks nothing like the panels around it goes without asking`() {
		assertTrue(erasesWithoutAsking(stripWith { drawTextBanner(it, 96, 340, 60) }))
	}

	@Test
	fun `a panel in the same style as the rest of the page always asks`() {
		assertFalse(erasesWithoutAsking(stripWith { drawArt(it, 96, 340, 400, 8) }))
	}

	@Test
	fun `a colour ad in a black and white manga goes without asking, even if the same brightness`() {
		val pixels = IntArray(96 * 800) { 255 }
		drawArt(pixels, 96, 0, 300, 1)
		drawArt(pixels, 96, 340, 400, 8)
		drawArt(pixels, 96, 440, 800, 2)
		val data = ByteArray(pixels.size) { pixels[it].toByte() }
		val chroma = ByteArray(pixels.size) { i -> if (i / 96 in 340 until 400) 90 else 0 }
		val page = PageLuma(96, 800, 96 * 8, 800 * 8, data, chroma)
		assertTrue(erasesWithoutAsking(page))
	}

	@Test
	fun `a scrub that wanders off the image always asks`() {
		val page = stripWith { drawTextBanner(it, 96, 340, 60) }
		assertFalse(erasesWithoutAsking(page, SourceRegion(20 * 8, 250 * 8, 80 * 8, 370 * 8)))
	}

	private fun wholePage(seed: Int) = IntArray(96 * 140).also { drawArt(it, 96, 0, 140, seed) }

	@Test
	fun `a whole page unlike the pages around it goes without asking`() {
		val ad = IntArray(96 * 140) { 255 }.also { drawTextBanner(it, 96, 0, 140) }
		val neighbours = listOf(lumaOf(wholePage(1), 96), lumaOf(wholePage(2), 96))
		assertTrue(erasesWithoutAsking(lumaOf(ad, 96), SourceRegion(20 * 8, 60 * 8, 80 * 8, 80 * 8), neighbours))
	}

	@Test
	fun `a whole page like the pages around it always asks`() {
		val neighbours = listOf(lumaOf(wholePage(1), 96), lumaOf(wholePage(2), 96))
		assertFalse(erasesWithoutAsking(lumaOf(wholePage(3), 96), SourceRegion(20 * 8, 60 * 8, 80 * 8, 80 * 8), neighbours))
	}

	@Test
	fun `with nothing to compare a page to, it always asks`() {
		val ad = IntArray(96 * 140) { 255 }.also { drawTextBanner(it, 96, 0, 140) }
		assertFalse(erasesWithoutAsking(lumaOf(ad, 96), SourceRegion(20 * 8, 60 * 8, 80 * 8, 80 * 8)))
	}

	@Test
	fun `like one neighbour is enough to ask`() {
		val ad = IntArray(96 * 140) { 255 }.also { drawTextBanner(it, 96, 0, 140) }
		val alike = IntArray(96 * 140) { 255 }.also { drawTextBanner(it, 96, 0, 140) }
		val neighbours = listOf(lumaOf(wholePage(1), 96), lumaOf(alike, 96))
		assertFalse(erasesWithoutAsking(lumaOf(ad, 96), SourceRegion(20 * 8, 60 * 8, 80 * 8, 80 * 8), neighbours))
	}

	// endregion

	@Test
	fun `borders offer the detected edges to snap to`() {
		val luma = gutteredStrip()
		val proposal = EraseProposer.propose(luma, SourceRegion(20 * 8, 360 * 8, 80 * 8, 380 * 8), 2f)
		assertTrue(proposal.snapY.any { kotlin.math.abs(it - 340 * 8) <= 8 })
		assertTrue(proposal.snapY.any { kotlin.math.abs(it - 300 * 8) <= 8 })
		assertTrue(proposal.snapY.contains(0))
		assertTrue(proposal.snapY.contains(800 * 8))
	}

	@Test
	fun `snapping moves to a near edge and ignores a far one`() {
		val edges = floatArrayOf(0f, 100f, 400f)
		assertEquals(100f, EraseProposer.snap(110f, edges, 20f), 0f)
		assertEquals(250f, EraseProposer.snap(250f, edges, 20f), 0f)
	}

	// endregion

	// region using every processor

	// endregion
}
