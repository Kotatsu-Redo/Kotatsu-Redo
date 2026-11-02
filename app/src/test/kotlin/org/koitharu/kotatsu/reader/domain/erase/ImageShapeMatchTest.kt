package org.koitharu.kotatsu.reader.domain.erase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The thresholds come from real pages: see [ImageSignature]. These hold on to what they are for, with lettering
 * made of blocks, which has the same fine detail as a banner's at this size.
 */
class ImageShapeMatchTest {

	private val width = 96
	private val bannerHeight = 36
	private val white = 252
	private val red = 70
	private val gold = 130

	/** Lettering blended with the white around it, as at this size it always is. */
	private val softRed = 150

	/** Two rows of letters on [background], the same for the same [seed]. */
	private fun letters(seed: Int, ink: Int, background: Int = white, height: Int = bannerHeight): IntArray {
		val pixels = IntArray(width * height) { background }
		val random = Random(seed)
		for (line in 0 until 2) {
			val lineTop = height * line / 2 + 2
			val lineBottom = height * (line + 1) / 2 - 2
			var x = 2
			while (x < width - 4) {
				val letter = random.nextInt(3, 7)
				val shape = random.nextInt(4)
				for (y in lineTop until lineBottom) {
					for (px in x until (x + letter).coerceAtMost(width - 2)) {
						val isInk = when (shape) {
							0 -> true
							1 -> (px - x) < letter / 2
							2 -> (y - lineTop) < (lineBottom - lineTop) / 2
							else -> (px - x + y - lineTop) % 4 < 2
						}
						if (isInk) pixels[y * width + px] = ink
					}
				}
				x += letter + 2
			}
		}
		return pixels
	}

	/** A page of white with [image] pasted in at row [top], and some story below it for there to be anything left. */
	private fun pageWith(image: IntArray, top: Int, height: Int = 400): PageLuma {
		val rows = image.size / width
		val data = ByteArray(width * height) { i ->
			val row = i / width
			when {
				row in top until top + rows -> image[(row - top) * width + i % width]
				row >= height - 60 -> if ((row / 7 + i % width / 5) % 2 == 0) 40 else 180
				else -> white
			}.toByte()
		}
		return PageLuma(width, height, width * 8, height * 8, data)
	}

	@Test
	fun `one diagonal edge is not enough to recognise anything by`() {
		val diagonal = IntArray(width * bannerHeight) { i ->
			val row = i / width
			val column = i % width
			if (row * width < column * bannerHeight) 30 else white
		}
		fun signature(image: IntArray) = ImageSignature.of(
			PageLuma(width, image.size / width, width, image.size / width, ByteArray(image.size) { image[it].toByte() }),
			0,
			0,
			width,
			image.size / width,
		)
		assertTrue(ImageSignature.isOneCurve(signature(diagonal)))
		assertFalse(ImageSignature.isOneCurve(signature(letters(3, red))))
		assertFalse(ImageSignature.hasInkToCopy(signature(diagonal)))
	}

	private fun ruleOf(image: IntArray): HiddenImageRule {
		val luma = pageWith(image, top = 50)
		val rows = image.size / width
		return HiddenImageRule(
			id = 1L,
			fingerprint = ImageFingerprint.of(luma, 0, 50, width, 50 + rows),
			aspect = rows.toFloat() / width,
			xStart = 0f,
			xEnd = 1f,
			signature = ImageSignature.of(luma, 0, 50, width, 50 + rows),
		)
	}

	private fun signatureOf(image: IntArray): ByteArray {
		val luma = PageLuma(width, image.size / width, width, image.size / width, ByteArray(image.size) { image[it].toByte() })
		return ImageSignature.of(luma, 0, 0, width, luma.height)
	}

	@Test
	fun `a banner erased in red leaves the same banner in gold alone`() {
		val result = HiddenImageMatcher.resolve(pageWith(letters(1, gold), top = 210), listOf(ruleOf(letters(1, red))))
		assertEquals(PageResolution.Visible, result)
	}

	@Test
	fun `another colour is not how another banner gets through`() {
		for (seed in 2..30) {
			val result = HiddenImageMatcher.resolve(pageWith(letters(seed, gold), top = 210), listOf(ruleOf(letters(1, red))))
			assertEquals("seed $seed", PageResolution.Visible, result)
		}
	}

	@Test
	fun `the same shape on another background is something else`() {
		val rule = signatureOf(letters(1, red))
		val onGrey = signatureOf(letters(1, red, background = 200))
		assertFalse(ImageSignature.isSameShape(rule, onGrey))
	}

	@Test
	fun `what cannot be told by its shape is never matched by it`() {
		val gradient = IntArray(width * bannerHeight) { i -> 20 + (i / width) * 220 / bannerHeight }
		assertFalse(ImageSignature.canMatchByShape(signatureOf(gradient)))
		val caption = IntArray(width * bannerHeight) { white }
		for (y in 14 until 22) for (x in 30 until 66) caption[y * width + x] = if ((x / 4) % 2 == 0) red else white
		assertFalse(ImageSignature.canMatchByShape(signatureOf(caption)))
		assertTrue(ImageSignature.canMatchByShape(signatureOf(letters(1, red))))
	}

	/** The same banner, smaller on the page, averaged down as any image is, with margins to make up its width. */
	private fun scaled(image: IntArray, sourceRows: Int, toWidth: Int, toRows: Int): IntArray {
		val margin = (width - toWidth) / 2
		return IntArray(width * toRows) { i ->
			val x = i % width - margin
			if (x !in 0 until toWidth) {
				white
			} else {
				val row = i / width
				var total = 0
				var count = 0
				for (sy in row * sourceRows / toRows until ((row + 1) * sourceRows / toRows).coerceAtLeast(row * sourceRows / toRows + 1)) {
					for (sx in x * width / toWidth until ((x + 1) * width / toWidth).coerceAtLeast(x * width / toWidth + 1)) {
						total += image[sy * width + sx]
						count++
					}
				}
				total / count
			}
		}
	}

	/**
	 * Lettering big enough to survive being resized, as real lettering on a banner is at this size, with no blank
	 * row across it, so that it stands on the page as one thing.
	 */
	private fun joined(seed: Int): IntArray {
		val pixels = IntArray(width * bannerHeight) { white }
		val random = Random(seed)
		for (row in 0 until 4) {
			for (column in 0 until 11) {
				val isAlwaysInk = (column == 0 && row != 2) || (column == 10 && row != 1)
				if (!isAlwaysInk && (column == 0 || column == 10 || random.nextBoolean())) continue
				for (y in 2 + row * 8 until 2 + (row + 1) * 8) {
					for (x in 4 + column * 8 until 4 + column * 8 + 6) pixels[y * width + x] = softRed
				}
			}
		}
		return pixels
	}

	/** A rule the way erasing makes one, cut down to what is in the selection. */
	private fun erasedRuleOf(image: IntArray): HiddenImageRule {
		val luma = pageWith(image, top = 50)
		val rows = image.size / width
		val shape = EraseProposer.describe(luma, SourceRegion(0, 40 * 8, width * 8, (60 + rows) * 8))!!
		return HiddenImageRule(
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
	}

	@Test
	fun `a banner is found at another size on the page`() {
		val rule = erasedRuleOf(joined(1))
		val smaller = scaled(joined(1), bannerHeight, 80, 30)
		val result = HiddenImageMatcher.resolve(pageWith(smaller, top = 120), listOf(rule))
		assertTrue(result is PageResolution.Masked)
		val band = (result as PageResolution.Masked).bands.single()
		// All of it, and the blank space it came with, and none of the story further down
		assertTrue("starts at ${band.top}", band.top <= 121 * 8 && band.top >= 90 * 8)
		assertTrue("ends at ${band.bottom}", band.bottom >= 149 * 8 && band.bottom <= 180 * 8)
	}

	@Test
	fun `at another size it still has to be the same banner`() {
		val rule = erasedRuleOf(joined(1))
		for (seed in 2..30) {
			val smaller = scaled(joined(seed), bannerHeight, 80, 30)
			assertEquals("seed $seed", PageResolution.Visible, HiddenImageMatcher.resolve(pageWith(smaller, top = 120), listOf(rule)))
		}
	}

	/** Bubbles standing alone on a white page, which is how another size gets to compare them at all. */
	@Test
	fun `a speech bubble alone on a page is not another saying something else`() {
		fun bubble(seed: Int): IntArray {
			val pixels = IntArray(width * 80) { 255 }
			for (y in 0 until 80) for (x in 0 until width) {
				val dx = (x - 48) / 46f
				val dy = (y - 40) / 38f
				if (dx * dx + dy * dy in 0.85f..1f) pixels[y * width + x] = 90
			}
			val random = Random(seed)
			for (line in 0 until 3) {
				var x = 26 + random.nextInt(0, 8)
				val y = 26 + line * 11
				while (x < 68) {
					val word = random.nextInt(4, 11)
					for (px in x until (x + word).coerceAtMost(70)) for (py in y until y + 6) pixels[py * width + px] = 20
					x += word + 3
				}
			}
			return pixels
		}
		val rule = ruleOf(bubble(1))
		for (seed in 2..60) {
			assertEquals("seed $seed", PageResolution.Visible, HiddenImageMatcher.resolve(pageWith(bubble(seed), top = 120), listOf(rule)))
			val smaller = scaled(bubble(seed), 80, 80, 66)
			assertEquals("smaller, seed $seed", PageResolution.Visible, HiddenImageMatcher.resolve(pageWith(smaller, top = 120), listOf(rule)))
		}
	}

	/** An ad as a file of its own: smooth art filling it, with [mark] added somewhere, at any size. */
	private fun insert(fileWidth: Int, mark: Int = 0, seed: Int = 1): PageLuma {
		val fileHeight = fileWidth * 36 / 100
		val random = Random(seed)
		val blobs = List(6) { Triple(random.nextFloat(), random.nextFloat(), random.nextInt(30, 200)) }
		val data = ByteArray(fileWidth * fileHeight) { i ->
			val x = (i % fileWidth).toFloat() / fileWidth
			val y = (i / fileWidth).toFloat() / fileHeight
			var value = 120f
			for ((blobX, blobY, tone) in blobs) {
				val distance = (x - blobX) * (x - blobX) + (y - blobY) * (y - blobY)
				if (distance < 0.09f) value = tone.toFloat()
			}
			val isMark = mark > 0 && x in 0.3f..0.3f + mark / 100f && y in 0.2f..0.8f
			(if (isMark) 230 else value.toInt()).toByte()
		}
		return PageLuma(fileWidth, fileHeight, fileWidth * 2, fileHeight * 2, data)
	}

	private fun ruleOfWhole(luma: PageLuma): HiddenImageRule {
		val shape = EraseProposer.describe(luma, SourceRegion(0, 0, luma.sourceWidth, luma.sourceHeight))!!
		return HiddenImageRule(
			id = 1L,
			fingerprint = shape.fingerprint!!,
			aspect = shape.aspect,
			xStart = shape.xStart,
			xEnd = shape.xEnd,
			signature = shape.signature,
			removesRows = shape.removesRows,
		)
	}

	@Test
	fun `an ad that is a file of its own is found at another size`() {
		val rule = ruleOfWhole(insert(100))
		assertFalse("too smooth to go by shape", rule.inkBox!!.isDetailed)
		assertTrue(HiddenImageMatcher.resolve(insert(173), listOf(rule)) is PageResolution.Hidden)
		assertTrue(HiddenImageMatcher.resolve(insert(375), listOf(rule)) is PageResolution.Hidden)
	}

	@Test
	fun `an ad with something added is another ad`() {
		val rule = ruleOfWhole(insert(100))
		assertEquals(PageResolution.Visible, HiddenImageMatcher.resolve(insert(173, mark = 30), listOf(rule)))
	}

	@Test
	fun `another ad of the same proportions is not that ad`() {
		val rule = ruleOfWhole(insert(100))
		for (seed in 2..30) {
			assertEquals("seed $seed", PageResolution.Visible, HiddenImageMatcher.resolve(insert(173, seed = seed), listOf(rule)))
		}
	}

	/** Only a file of its own gets this: the same art as a panel of a page is still not enough. */
	@Test
	fun `smooth art inside a page is not an ad`() {
		val rule = ruleOfWhole(insert(100))
		val art = insert(96)
		val rows = art.height
		val page = IntArray(width * 400) { i ->
			val row = i / width
			when {
				row in 120 until 120 + rows -> art[i % width, row - 120]
				row >= 340 -> if ((row / 7 + i % width / 5) % 2 == 0) 40 else 180
				else -> white
			}
		}
		val luma = PageLuma(width, 400, width * 8, 400 * 8, ByteArray(page.size) { page[it].toByte() })
		val result = HiddenImageMatcher.resolve(luma, listOf(rule))
		// Being the very same picture, it may be found by its hash at the same share of the width. Not by this.
		assertTrue(rule.inkBox!!.isDetailed.not())
		assertTrue(result == PageResolution.Visible || result is PageResolution.Masked)
	}

	@Test
	fun `a caption is too little ink to be an ad`() {
		val rule = signatureOf(letters(1, red))
		val sparse = IntArray(width * bannerHeight) { white }
		for (y in 14 until 22) for (x in 30 until 66) sparse[y * width + x] = if ((x / 4) % 2 == 0) red else white
		val other = IntArray(width * bannerHeight) { white }
		for (y in 14 until 22) for (x in 30 until 66) other[y * width + x] = if ((x / 4) % 2 == 0) red else white
		assertTrue(ImageSignature.correlation(signatureOf(sparse), signatureOf(other)) > 0.99)
		assertFalse(ImageSignature.isSameInsert(signatureOf(sparse), signatureOf(other)))
		assertFalse(ImageSignature.isSameInsert(rule, signatureOf(sparse)))
	}

	/** A page with art right up against the image at both ends, so that no blank space sets it apart. */
	private fun inArt(image: IntArray, top: Int, height: Int = 400): PageLuma {
		val rows = image.size / width
		val data = ByteArray(width * height) { i ->
			val row = i / width
			val value = if (row in top until top + rows) {
				image[(row - top) * width + i % width]
			} else {
				if ((row / 9 + i % width / 7) % 2 == 0) 60 else 200
			}
			value.toByte()
		}
		return PageLuma(width, height, width * 8, height * 8, data)
	}

	@Test
	fun `a banner right up against the art is found at another size`() {
		val rule = erasedRuleOf(joined(1))
		val smaller = scaled(joined(1), bannerHeight, 88, 33)
		val result = HiddenImageMatcher.resolve(inArt(smaller, top = 150), listOf(rule))
		assertTrue(result.toString(), result is PageResolution.Masked)
		val band = (result as PageResolution.Masked).bands.single()
		assertTrue("starts at ${band.top}", band.top in 145 * 8..152 * 8)
		assertTrue("ends at ${band.bottom}", band.bottom in 181 * 8..188 * 8)
	}

	@Test
	fun `another banner up against the art at another size is not that banner`() {
		val rule = erasedRuleOf(joined(1))
		for (seed in 2..30) {
			val smaller = scaled(joined(seed), bannerHeight, 88, 33)
			assertEquals("seed $seed", PageResolution.Visible, HiddenImageMatcher.resolve(inArt(smaller, top = 150), listOf(rule)))
		}
	}

	/** Without a change of colour to explain a difference, only the slightest of them will do. */
	@Test
	fun `in the same colour a few cells far off is not the same image`() {
		val rule = signatureOf(letters(1, red))
		val changed = rule.copyOf()
		for (cell in 40 until 42) changed[cell] = ((changed[cell].toInt() and 0xFF) xor 0x80).toByte()
		assertFalse(ImageSignature.isSameShape(rule, changed))
	}

	@Test
	fun `many cells off from the shape is not the same image`() {
		val rule = letters(1, red)
		val half = letters(2, red)
		val mixed = IntArray(rule.size) { i -> if (i / width < bannerHeight / 2) rule[i] else half[i] }
		assertFalse(ImageSignature.isSameShape(signatureOf(rule), signatureOf(mixed)))
	}

	@Test
	fun `the same image is the same shape`() {
		val signature = signatureOf(letters(1, red))
		assertTrue(ImageSignature.isSameShape(signature, signature))
		assertEquals(1.0, ImageSignature.correlation(signature, signature), 0.0001)
	}

	@Test
	fun `a flat signature correlates with nothing`() {
		val flat = ByteArray(ImageSignature.GRID * ImageSignature.GRID) { 100 }
		assertEquals(0.0, ImageSignature.correlation(flat, signatureOf(letters(1, red))), 0.0001)
		assertFalse(ImageSignature.isSameShape(flat, signatureOf(letters(1, red))))
	}
}
