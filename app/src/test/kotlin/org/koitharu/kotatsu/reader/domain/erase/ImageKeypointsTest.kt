package org.koitharu.kotatsu.reader.domain.erase

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class ImageKeypointsTest {

	/** Rectangles of many colours and sizes, which is as much corners as a real banner has. */
	private fun banner(width: Int, height: Int, seed: Long): IntArray {
		val random = Random(seed)
		val image = IntArray(width * height) { 0xFFFFFF }
		repeat(width * height / 400 + 40) {
			val w = 4 + random.nextInt(width / 6 + 1)
			val h = 4 + random.nextInt(height / 4 + 1)
			val x = random.nextInt((width - w).coerceAtLeast(1))
			val y = random.nextInt((height - h).coerceAtLeast(1))
			val colour = random.nextInt(0x1000000)
			for (row in y until (y + h).coerceAtMost(height)) {
				for (column in x until (x + w).coerceAtMost(width)) {
					image[row * width + column] = colour
				}
			}
		}
		return image
	}

	private fun scaled(image: IntArray, width: Int, height: Int, toWidth: Int, toHeight: Int): IntArray {
		val channels = IntArray(3)
		val out = IntArray(toWidth * toHeight)
		for (shift in 0..2) {
			val shrinker = AreaShrinker(width, height, toWidth, toHeight)
			val row = IntArray(width)
			for (y in 0 until height) {
				for (x in 0 until width) row[x] = image[y * width + x] shr (shift * 8) and 0xFF
				shrinker.addRow(row)
			}
			for (i in out.indices) out[i] = out[i] or ((shrinker.output[i].toInt() and 0xFF) shl (shift * 8))
		}
		channels[0] = 0
		return out
	}

	private fun rgbOf(image: IntArray): ByteArray {
		val rgb = ByteArray(image.size * 3)
		for (i in image.indices) {
			rgb[i * 3] = (image[i] shr 16).toByte()
			rgb[i * 3 + 1] = (image[i] shr 8).toByte()
			rgb[i * 3 + 2] = image[i].toByte()
		}
		return rgb
	}

	private fun pageWith(width: Int, height: Int, image: IntArray?, imageWidth: Int, imageHeight: Int, left: Int, top: Int, seed: Long = 99): PageDetail {
		val background = banner(width, height, seed)
		if (image != null) {
			for (y in 0 until imageHeight) {
				val row = y + top
				if (row < 0 || row >= height) continue
				for (x in 0 until imageWidth) {
					val column = x + left
					if (column in 0 until width) background[row * width + column] = image[y * imageWidth + x]
				}
			}
		}
		val builder = DetailedPageBuilder(width, height, width, height, 1)
		for (y in 0 until height) {
			builder.addRow(IntArray(width) { background[y * width + it] })
		}
		return builder.build().detail!!
	}

	private fun find(page: PageDetail, rule: RuleDetail): List<KeypointMatch> = KeypointMatcher.find(page, KeypointIndex(listOf(rule)))

	@Test
	fun `points of the same picture are the same points every time`() {
		val image = banner(300, 100, 1)
		val first = RuleDetail.of(rgbOf(image), 300, 100)
		val second = RuleDetail.of(rgbOf(image), 300, 100)
		assertTrue(first.keypoints.count >= 20)
		assertArrayEquals(first.keypoints.descriptors, second.keypoints.descriptors)
	}

	@Test
	fun `a rule comes back as it was saved`() {
		val rule = RuleDetail.of(rgbOf(banner(300, 100, 2)), 300, 100)
		val back = RuleDetail.decode(rule.encode())
		assertNotNull(back)
		assertEquals(rule.width, back!!.width)
		assertEquals(rule.height, back.height)
		assertEquals(rule.keypoints.count, back.keypoints.count)
		assertArrayEquals(rule.keypoints.descriptors, back.keypoints.descriptors)
		assertArrayEquals(rule.keypoints.x, back.keypoints.x, 0f)
		assertArrayEquals(rule.thumb, back.thumb)
		assertArrayEquals(rule.colour, back.colour)
	}

	@Test
	fun `a rule in a format it does not know is no rule at all`() {
		val bytes = RuleDetail.of(rgbOf(banner(300, 100, 2)), 300, 100).encode()
		bytes[0] = 77
		assertEquals(null, RuleDetail.decode(bytes))
	}

	@Test
	fun `the same banner with a small part of it changed is left alone`() {
		val image = banner(400, 120, 21)
		val rule = RuleDetail.of(rgbOf(image), 400, 120)
		val edited = image.copyOf()
		val patch = banner(60, 40, 22)
		for (y in 0 until 40) {
			for (x in 0 until 60) {
				edited[(y + 40) * 400 + x + 170] = patch[y * 60 + x]
			}
		}
		assertEquals(1, find(pageWith(480, 900, image, 400, 120, 40, 300), rule).size)
		assertEquals(0, find(pageWith(480, 900, edited, 400, 120, 40, 300), rule).size)
	}

	@Test
	fun `found at the size it was erased`() {
		val image = banner(400, 120, 3)
		val found = find(pageWith(480, 900, image, 400, 120, 40, 300), RuleDetail.of(rgbOf(image), 400, 120))
		assertEquals(1, found.size)
		assertEquals(300.0, found[0].top, 3.0)
		assertEquals(420.0, found[0].bottom, 3.0)
		assertEquals(40.0, found[0].left, 3.0)
		assertEquals(1.0, found[0].scale, 0.02)
	}

	@Test
	fun `found at another size`() {
		val image = banner(400, 120, 4)
		val rule = RuleDetail.of(rgbOf(image), 400, 120)
		val smaller = scaled(image, 400, 120, 280, 84)
		val found = find(pageWith(480, 900, smaller, 280, 84, 100, 500), rule)
		assertEquals(1, found.size)
		assertEquals(500.0, found[0].top, 4.0)
		assertEquals(584.0, found[0].bottom, 4.0)
		assertEquals(0.7, found[0].scale, 0.03)
	}

	@Test
	fun `found cut off by the bottom of the page, and says how much is missing`() {
		val image = banner(400, 200, 5)
		val rule = RuleDetail.of(rgbOf(image), 400, 200)
		val found = find(pageWith(480, 800, image, 400, 200, 40, 660), rule)
		assertEquals(1, found.size)
		assertEquals(660.0, found[0].top, 3.0)
		assertEquals(860.0, found[0].bottom, 4.0)
	}

	@Test
	fun `found cut off by the top of the page`() {
		val image = banner(400, 200, 6)
		val rule = RuleDetail.of(rgbOf(image), 400, 200)
		val found = find(pageWith(480, 800, image, 400, 200, 40, -60), rule)
		assertEquals(1, found.size)
		assertEquals(-60.0, found[0].top, 4.0)
	}

	@Test
	fun `found each time it is on a page`() {
		val image = banner(300, 100, 7)
		val rule = RuleDetail.of(rgbOf(image), 300, 100)
		val page = banner(480, 1200, 99)
		for ((top) in listOf(200 to 0, 800 to 0)) {
			for (y in 0 until 100) {
				for (x in 0 until 300) page[(y + top) * 480 + x + 50] = image[y * 300 + x]
			}
		}
		val builder = DetailedPageBuilder(480, 1200, 480, 1200, 1)
		for (y in 0 until 1200) builder.addRow(IntArray(480) { page[y * 480 + it] })
		val found = find(builder.build().detail!!, rule)
		assertEquals(listOf(200, 800), found.map { (it.top + 0.5).toInt() / 100 * 100 }.sorted())
	}

	@Test
	fun `not on a page without it`() {
		val rule = RuleDetail.of(rgbOf(banner(400, 120, 8)), 400, 120)
		assertTrue(find(pageWith(480, 1600, null, 0, 0, 0, 0), rule).isEmpty())
	}

	@Test
	fun `the same image in other colours stays`() {
		val image = banner(400, 120, 9)
		val rule = RuleDetail.of(rgbOf(image), 400, 120)
		// Red and blue trading places keeps the brightness close, so that only its colour tells them apart
		val other = IntArray(image.size) {
			val pixel = image[it]
			(pixel and 0xFF shl 16) or (pixel and 0xFF00) or (pixel shr 16 and 0xFF)
		}
		assertTrue(find(pageWith(480, 900, other, 400, 120, 40, 300), rule).isEmpty())
		assertEquals(1, find(pageWith(480, 900, image, 400, 120, 40, 300), rule).size)
	}

	@Test
	fun `the same image with a part changed stays`() {
		val image = banner(400, 200, 10)
		val rule = RuleDetail.of(rgbOf(image), 400, 200)
		val edited = image.copyOf()
		for (y in 40 until 180) {
			for (x in 40 until 200) edited[y * 400 + x] = 0x3CA03C
		}
		assertTrue(find(pageWith(480, 900, edited, 400, 200, 40, 300), rule).isEmpty())
	}

	/** Mostly black, with shapes in white and some in one red: what a page of credits is like. */
	private fun credits(width: Int, height: Int, seed: Long, red: Int = 0xC83C5A, pixelsToAShape: Int = 600): IntArray {
		val random = Random(seed)
		val image = IntArray(width * height)
		repeat(width * height / pixelsToAShape + 30) { index ->
			val w = 6 + random.nextInt(width / 6 + 1)
			val h = 4 + random.nextInt(height / 8 + 1)
			val x = random.nextInt((width - w).coerceAtLeast(1))
			val y = random.nextInt((height - h).coerceAtLeast(1))
			val colour = if (index % 3 == 0) red else 0xFFFFFF
			for (row in y until (y + h).coerceAtMost(height)) {
				for (column in x until (x + w).coerceAtMost(width)) {
					image[row * width + column] = colour
				}
			}
		}
		return image
	}

	private fun saved(image: IntArray, change: (Int) -> Int, colour: (luma: Int, value: Int) -> Int = { _, value -> value }): IntArray =
		IntArray(image.size) {
			val pixel = image[it]
			val red = pixel shr 16 and 0xFF
			val green = pixel shr 8 and 0xFF
			val blue = pixel and 0xFF
			val luma = (77 * red + 150 * green + 29 * blue) shr 8
			fun one(value: Int) = change(colour(luma, value)).coerceIn(0, 255)
			(one(red) shl 16) or (one(green) shl 8) or one(blue)
		}

	private fun evidenceOf(page: PageDetail, rule: RuleDetail): KeypointMatcher.Evidence =
		checkNotNull(KeypointMatcher.lookFor(page, KeypointMatcher.keypointsOf(page), rule))

	@Test
	fun `saved again with its blacks a dark grey, it is the same image`() {
		val image = credits(400, 300, 31, pixelsToAShape = 4000)
		val rule = RuleDetail.of(rgbOf(image), 400, 300)
		// As a group saves the same page of credits for another chapter: black comes out lighter, white as it was
		val lighter = pageWith(480, 900, saved(image, { it + (255 - it) * BLACK_SAVED_AGAIN / 255 }), 400, 300, 40, 300)
		// As far as real copies came to, which is further than they were once thought to
		val shift = evidenceOf(lighter, rule).shift
		assertTrue("brighter by $shift", shift > 10.0 && shift < KeypointMatcher.MAX_BRIGHTNESS_SHIFT)
		assertEquals(1, find(lighter, rule).size)
		// Twice as far is no longer a matter of how it was saved
		val washedOut = pageWith(480, 900, saved(image, { it + (255 - it) * BLACK_SAVED_AGAIN * 2 / 255 }), 400, 300, 40, 300)
		assertTrue(evidenceOf(washedOut, rule).shift > KeypointMatcher.MAX_BRIGHTNESS_SHIFT)
		assertTrue(find(washedOut, rule).isEmpty())
	}

	@Test
	fun `saved again with its colours more vivid, it is the same image`() {
		// A dull red, as on the page this was measured on: with a strong one the same share is more than any copy came to
		val image = credits(400, 300, 32, red = 0xA05A6E)
		val rule = RuleDetail.of(rgbOf(image), 400, 300)
		val vivid = pageWith(480, 900, saved(image, { it }, { luma, value -> luma + (value - luma) * 145 / 100 }), 400, 300, 40, 300)
		val evidence = evidenceOf(vivid, rule)
		// A quarter stronger was as far as this used to go
		val share = evidence.colourDifference / evidence.colourStrength
		assertTrue(
			"stronger by $share: ${evidence.colourDifference} of ${evidence.colourStrength}, ${evidence.hue} degrees",
			share > 0.26 && share < 0.45 && evidence.colourDifference < KeypointMatcher.MAX_COLOUR_DIFFERENCE,
		)
		assertEquals(1, find(vivid, rule).size)
	}

	@Test
	fun `the same image with its red an orange stays`() {
		val image = credits(400, 300, 33)
		val rule = RuleDetail.of(rgbOf(image), 400, 300)
		assertEquals(1, find(pageWith(480, 900, image, 400, 300, 40, 300), rule).size)
		assertTrue(find(pageWith(480, 900, credits(400, 300, 33, red = 0xC8783C), 400, 300, 40, 300), rule).isEmpty())
	}

	@Test
	fun `the same image in the red next to its own stays`() {
		val image = credits(400, 300, 34, red = 0xFF0000)
		val rule = RuleDetail.of(rgbOf(image), 400, 300)
		val page = pageWith(480, 900, credits(400, 300, 34, red = 0xFF4500), 400, 300, 40, 300)
		// As near as two colours with names of their own get. With this much colour, still further round than a copy comes to
		val evidence = evidenceOf(page, rule)
		assertTrue(
			"${evidence.hue} degrees round at ${evidence.colourStrength}",
			evidence.hue > 12.0 && evidence.hue < 25.0 && evidence.colourStrength >= 24.0,
		)
		assertTrue(find(page, rule).isEmpty())
	}

	@Test
	fun `colours a saving apart are the same, and other colours are not`() {
		// As measured on a phone, on the same picture saved again: how far apart, the degrees round, and how strong
		assertTrue(KeypointMatcher.isSameColour(6.3, 8.0, 21.7, false))
		assertTrue(KeypointMatcher.isSameColour(7.3, 8.0, 22.7, false))
		assertTrue(KeypointMatcher.isSameColour(7.1, 11.0, 17.8, false))
		assertTrue(KeypointMatcher.isSameColour(4.3, 16.0, 13.0, false))
		assertTrue(KeypointMatcher.isSameColour(3.4, 16.0, 15.0, false))
		// Further round than that
		assertFalse(KeypointMatcher.isSameColour(3.4, 19.0, 15.0, false))
		assertFalse(KeypointMatcher.isSameColour(4.0, 13.0, 30.0, false))
		// Red against gold, however alike in strength
		assertFalse(KeypointMatcher.isSameColour(8.0, 56.0, 60.0, false))
		// Far stronger or weaker
		assertFalse(KeypointMatcher.isSameColour(10.5, 2.0, 22.0, false))
		assertFalse(KeypointMatcher.isSameColour(13.0, 2.0, 60.0, false))
	}

	@Test
	fun `with next to no colour, what counts as the same colour is as it was`() {
		// Thin lettering in red against the same in an orange red: no more than a few steps apart, and a long way round
		assertFalse(KeypointMatcher.isSameColour(4.3, 21.1, 11.1, false))
		assertTrue(KeypointMatcher.isSameColour(3.0, 21.1, 11.1, false))
		assertFalse(KeypointMatcher.isSameColour(2.0, 30.0, 9.0, false))
	}

	@Test
	fun `what was the same colour before still is`() {
		for (strength in 12..60) {
			for (hue in 0..12) {
				val difference = minOf(strength * 0.25, KeypointMatcher.MAX_COLOUR_DIFFERENCE)
				assertTrue("$difference apart, $hue round, at $strength", KeypointMatcher.isSameColour(difference, hue.toDouble(), strength.toDouble(), false))
			}
		}
	}

	@Test
	fun `only the rules that are on a page are looked at properly`() {
		val rules = List(40) { RuleDetail.of(rgbOf(banner(300, 100, 100L + it)), 300, 100) }
		val index = KeypointIndex(rules)
		val image = banner(300, 100, 117)
		val page = pageWith(480, 900, image, 300, 100, 60, 400)
		val shortlist = KeypointMatcher.shortlist(page, index, KeypointMatcher.keypointsOf(page))
		assertTrue(17 in shortlist)
		assertTrue(shortlist.size <= 3)
	}

	@Test
	fun `a smaller image is the average of what each pixel covers`() {
		val shrinker = AreaShrinker(4, 2, 2, 1)
		shrinker.addRow(intArrayOf(0, 100, 200, 200))
		shrinker.addRow(intArrayOf(100, 200, 0, 0))
		assertEquals(100, shrinker.output[0].toInt() and 0xFF)
		assertEquals(100, shrinker.output[1].toInt() and 0xFF)
	}

	@Test
	fun `shrinking by a share that does not divide keeps the brightness`() {
		val shrinker = AreaShrinker(720, 300, 480, 200)
		val row = IntArray(720) { 173 }
		repeat(300) { shrinker.addRow(row) }
		assertTrue(shrinker.output.all { abs((it.toInt() and 0xFF) - 173) <= 1 })
	}

	@Test
	fun `a long strip is looked over smaller, in the same proportions`() {
		assertEquals(640, PageDetail.widthFor(720, 2000))
		assertEquals(1777, PageDetail.heightFor(720, 2000))
		val tall = PageDetail.widthFor(800, 40000)
		assertEquals(160, tall)
		assertEquals(8000, PageDetail.heightFor(800, 40000))
		assertEquals(300, PageDetail.widthFor(300, 400))
	}

	@Test
	fun `both sizes of a page come from one decode`() {
		val builder = DetailedPageBuilder(800, 2000, 1600, 4000, 2)
		val row = IntArray(800) { 0xFF102030.toInt() }
		repeat(2000) { builder.addRow(row) }
		val luma = builder.build()
		assertEquals(1600, luma.sourceWidth)
		assertEquals(4000, luma.sourceHeight)
		assertEquals(1600 / PageLuma.sampleSizeFor(1600, 4000), luma.width)
		assertEquals(640, luma.detail!!.width)
		assertEquals(1600, luma.detail!!.height)
		val gray = (77 * 0x10 + 150 * 0x20 + 29 * 0x30) shr 8
		assertTrue(luma.data.all { abs((it.toInt() and 0xFF) - gray) <= 1 })
		assertTrue(luma.detail!!.gray.all { abs((it.toInt() and 0xFF) - gray) <= 1 })
		assertFalse(luma.colour == null)
	}

	@Test
	fun `found by its points, the matcher hides it and says what is next door`() {
		val image = banner(400, 200, 11)
		val detail = RuleDetail.of(rgbOf(image), 400, 200)
		val page = pageWith(480, 800, image, 400, 200, 40, 660)
		val luma = PageLuma(96, 160, 480, 800, shrunkGray(page), detail = page)
		val rule = HiddenImageRule(
			id = 5L,
			fingerprint = ImageFingerprint(1L, 2L, 128),
			aspect = 0.5f,
			xStart = 0.08f,
			xEnd = 0.92f,
			signature = ByteArray(256) { (it * 7 % 251).toByte() },
			keypoints = detail.encode(),
		)
		val found = HiddenImageMatcher.resolve(luma, listOf(rule))
		assertTrue(found.ruleIds.contains(5L))
		assertEquals(1, found.cuts.size)
		assertFalse(found.cuts[0].isAtTop)
		assertEquals(60f / 480, found.cuts[0].missingShare, 0.02f)
	}

	private fun shrunkGray(page: PageDetail): ByteArray {
		val shrinker = AreaShrinker(page.width, page.height, 96, 160)
		val row = IntArray(page.width)
		for (y in 0 until page.height) {
			for (x in 0 until page.width) row[x] = page.gray[y * page.width + x].toInt() and 0xFF
			shrinker.addRow(row)
		}
		return shrinker.output
	}

	private companion object {

		/** How far up black came out in the copy of a page of credits saved again, of 255. */
		const val BLACK_SAVED_AGAIN = 17
	}
}
