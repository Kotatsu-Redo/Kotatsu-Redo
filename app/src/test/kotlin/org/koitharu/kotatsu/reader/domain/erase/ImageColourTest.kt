package org.koitharu.kotatsu.reader.domain.erase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ImageColourTest {

	private val width = 96
	private val bannerRows = 36
	private val bannerTop = 50
	private val white = intArrayOf(252, 252, 252)

	private fun page(ink: IntArray, seed: Int = 3, height: Int = 400, background: IntArray = white, margin: Int = 0): PageLuma {
		val letters = Random(seed)
		val isInk = BooleanArray(width * bannerRows)
		var x = margin + 2
		while (x < width - margin - 4) {
			val letter = letters.nextInt(3, 7)
			val isTop = letters.nextBoolean()
			for (row in 2 until bannerRows - 2) {
				if (isTop != (row < bannerRows / 2)) continue
				for (column in x until (x + letter).coerceAtMost(width - margin - 2)) {
					isInk[row * width + column] = true
				}
			}
			x += letter + 2
		}
		val data = ByteArray(width * height)
		val colour = PageColour.Builder(width, height)
		for (row in 0 until height) {
			for (column in 0 until width) {
				val inBanner = row in bannerTop until bannerTop + bannerRows
				val rgb = when {
					inBanner && isInk[(row - bannerTop) * width + column] -> ink
					row >= height - 60 && (row / 7 + column / 5) % 2 == 0 -> intArrayOf(40, 40, 40)
					row >= height - 60 -> intArrayOf(180, 180, 180)
					inBanner -> background
					else -> white
				}
				data[row * width + column] = ((77 * rgb[0] + 150 * rgb[1] + 29 * rgb[2]) shr 8).toByte()
				colour.add(column, row, rgb[0], rgb[1], rgb[2])
			}
		}
		return PageLuma(width, height, width * 8, height * 8, data, colour = colour.build())
	}

	private fun ruleOf(luma: PageLuma, withColour: Boolean = true) = HiddenImageRule(
		id = 1L,
		fingerprint = ImageFingerprint.of(luma, 0, bannerTop, width, bannerTop + bannerRows),
		aspect = bannerRows.toFloat() / width,
		xStart = 0f,
		xEnd = 1f,
		signature = ImageSignature.of(luma, 0, bannerTop, width, bannerTop + bannerRows),
		colour = if (withColour) ImageColour.of(luma, 0, bannerTop, width, bannerTop + bannerRows) else null,
	)

	/** The page from [row] down, which has to be a multiple of 4 as its colours are kept at a quarter of its size. */
	private fun cutAtRow(luma: PageLuma, row: Int): PageLuma {
		val colour = luma.colour!!
		val colourRow = row / 4
		return PageLuma(
			luma.width,
			luma.height - row,
			luma.sourceWidth,
			(luma.height - row) * 8,
			luma.data.copyOfRange(row * luma.width, luma.data.size),
			colour = PageColour(
				colour.width,
				colour.height - colourRow,
				colour.blue.copyOfRange(colourRow * colour.width, colour.blue.size),
				colour.red.copyOfRange(colourRow * colour.width, colour.red.size),
			),
		)
	}

	private val red = intArrayOf(200, 40, 40)

	/** As bright as [red], to the eye a different banner altogether. */
	private val blue = intArrayOf(60, 75, 255)

	@Test
	fun `the same image in the same colours is found`() {
		assertNotEquals(PageResolution.Visible, HiddenImageMatcher.resolve(page(red), listOf(ruleOf(page(red)))))
	}

	@Test
	fun `the same image in another colour as bright stays`() {
		assertEquals(PageResolution.Visible, HiddenImageMatcher.resolve(page(blue), listOf(ruleOf(page(red)))))
	}

	@Test
	fun `brightness cannot tell those two apart without colour`() {
		assertNotEquals(PageResolution.Visible, HiddenImageMatcher.resolve(page(blue), listOf(ruleOf(page(red), withColour = false))))
	}

	@Test
	fun `grey lettering is not the same lettering in a colour`() {
		val grey = intArrayOf(88, 88, 88)
		assertEquals(PageResolution.Visible, HiddenImageMatcher.resolve(page(red), listOf(ruleOf(page(grey)))))
		assertNotEquals(PageResolution.Visible, HiddenImageMatcher.resolve(page(grey), listOf(ruleOf(page(grey)))))
	}

	@Test
	fun `a colour is judged on the cells that have one, so white around it changes nothing`() {
		val rule = ruleOf(page(red)).colour
		val narrower = page(red, margin = 10)
		assertTrue(ImageColour.isSame(rule, narrower, 0, width, bannerTop, bannerRows))
		assertFalse(ImageColour.isSame(rule, page(blue, margin = 10), 0, width, bannerTop, bannerRows))
	}

	@Test
	fun `only what is on the page counts`() {
		val rule = ruleOf(page(red)).colour
		// Half of it hangs off the top of the page
		assertTrue(ImageColour.isSame(rule, cutAtRow(page(red), 68), 0, width, -18, bannerRows))
		assertFalse(ImageColour.isSame(rule, cutAtRow(page(blue), 68), 0, width, -18, bannerRows))
	}

	@Test
	fun `a page read without colours leaves it to brightness`() {
		val colourless = page(blue).let { PageLuma(it.width, it.height, it.sourceWidth, it.sourceHeight, it.data) }
		assertTrue(ImageColour.isSame(ruleOf(page(red)).colour, colourless, 0, width, bannerTop, bannerRows))
	}

	@Test
	fun `an outline is not looked for elsewhere, and a banner is`() {
		val rim = page(intArrayOf(0, 0, 0)).let { base ->
			val data = ByteArray(width * bannerRows) { 252.toByte() }
			for (column in 0 until width) {
				val offset = column - width / 2
				val row = (bannerRows - 3 - offset * offset * (bannerRows - 6) / (width * width / 4)).coerceIn(0, bannerRows - 1)
				data[row * width + column] = 20
			}
			PageLuma(width, bannerRows, base.sourceWidth, bannerRows * 8, data)
		}
		assertTrue(ImageSignature.isOutline(ImageSignature.of(rim, 0, 0, width, bannerRows)))
		assertFalse(ImageSignature.isOutline(ruleOf(page(red)).signature!!))
	}
}
