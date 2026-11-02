package org.koitharu.kotatsu.reader.domain.erase

import kotlin.math.abs
import kotlin.math.atan2

/**
 * A page's colour, at a quarter of the size of its [PageLuma] each way, which is all that comparing colours takes.
 * Stored as how blue and how red each cell is, with 128 as neither, the way a JPEG stores it.
 */
class PageColour(val width: Int, val height: Int, val blue: ByteArray, val red: ByteArray) {

	class Builder(private val pageWidth: Int, private val pageHeight: Int) {

		private val width = (pageWidth / CELL).coerceAtLeast(1)
		private val height = (pageHeight / CELL).coerceAtLeast(1)
		private val reds = IntArray(width * height)
		private val greens = IntArray(width * height)
		private val blues = IntArray(width * height)
		private val counts = IntArray(width * height)

		fun add(x: Int, y: Int, red: Int, green: Int, blue: Int) {
			val cell = (y / CELL).coerceAtMost(height - 1) * width + (x / CELL).coerceAtMost(width - 1)
			reds[cell] += red
			greens[cell] += green
			blues[cell] += blue
			counts[cell]++
		}

		fun build(): PageColour {
			val blue = ByteArray(width * height)
			val red = ByteArray(width * height)
			for (i in counts.indices) {
				val count = counts[i].coerceAtLeast(1)
				val r = reds[i] / count
				val g = greens[i] / count
				val b = blues[i] / count
				blue[i] = (128 + (-43 * r - 85 * g + 128 * b) / 256).coerceIn(0, 255).toByte()
				red[i] = (128 + (128 * r - 107 * g - 21 * b) / 256).coerceIn(0, 255).toByte()
			}
			return PageColour(width, height, blue, red)
		}
	}

	private companion object {
		const val CELL = 4
	}
}

/**
 * What colour an image is. Brightness alone cannot tell a blue banner from the same one in purple, and an image is only
 * the same image in its own colours. Only ever asked once brightness has found something, so it costs nothing on the
 * great majority of pages.
 *
 * Measured on 939 real pages and 4,590 samples. Going cell by cell does not work: a window a row off moves sharp colour
 * from cell to cell, and a real copy looks as far off as a recolour. An average of the whole image does not either, as
 * more white margin dilutes it. The average of the cells that have a colour does neither. The hue is asked about as well,
 * since a pale image can change hue while staying close by that average.
 */
object ImageColour {

	private const val GRID = 8

	/** How far from neutral a cell has to be to count as having a colour. Being grey says nothing about anything. */
	private const val MIN_COLOURFUL = 8

	/**
	 * Copies of one image came to 2.5 or under in half the cases and 8 or under in 96%, when the same images recoloured
	 * came to 6 and up. Every real page match this turned away was a different image, and no copy.
	 */
	private const val MAX_DIFFERENCE = 8.0
	private const val MAX_HUE_DEGREES = 25.0

	/** The colours of a rectangle of a page, or null for a page read without them. */
	fun of(luma: PageLuma, left: Int, top: Int, right: Int, bottom: Int): ByteArray? {
		val colour = luma.colour ?: return null
		val result = ByteArray(GRID * GRID * 2)
		for (row in 0 until GRID) {
			val cellTop = top + (bottom - top) * row / GRID
			val cellBottom = top + (bottom - top) * (row + 1) / GRID
			for (column in 0 until GRID) {
				val cellLeft = left + (right - left) * column / GRID
				val cellRight = left + (right - left) * (column + 1) / GRID
				val index = row * GRID + column
				result[index] = mean(colour.blue, colour, luma, cellLeft, cellTop, cellRight, cellBottom).toByte()
				result[GRID * GRID + index] = mean(colour.red, colour, luma, cellLeft, cellTop, cellRight, cellBottom).toByte()
			}
		}
		return result
	}

	/**
	 * Whether what is on a page in a window of [rows] from [offset] has the colours of [rule]. The window can hang off
	 * the page, and then the part on it is what counts. Without colours to go by on either side, it is left to brightness.
	 */
	fun isSame(rule: ByteArray?, luma: PageLuma, left: Int, right: Int, offset: Int, rows: Int): Boolean {
		val colour = luma.colour
		if (rule == null || colour == null || right <= left || rows <= 0) {
			return true
		}
		var countRule = 0
		var blueRule = 0
		var redRule = 0
		var countHere = 0
		var blueHere = 0
		var redHere = 0
		for (row in 0 until GRID) {
			val cellTop = offset + rows * row / GRID
			val cellBottom = offset + rows * (row + 1) / GRID
			if (cellTop < 0 || cellBottom > luma.height) continue
			for (column in 0 until GRID) {
				val cellLeft = left + (right - left) * column / GRID
				val cellRight = left + (right - left) * (column + 1) / GRID
				val index = row * GRID + column
				val blueOfRule = (rule[index].toInt() and 0xFF) - 128
				val redOfRule = (rule[GRID * GRID + index].toInt() and 0xFF) - 128
				if (abs(blueOfRule) + abs(redOfRule) > MIN_COLOURFUL) {
					countRule++
					blueRule += blueOfRule
					redRule += redOfRule
				}
				val blueOfHere = mean(colour.blue, colour, luma, cellLeft, cellTop, cellRight, cellBottom) - 128
				val redOfHere = mean(colour.red, colour, luma, cellLeft, cellTop, cellRight, cellBottom) - 128
				if (abs(blueOfHere) + abs(redOfHere) > MIN_COLOURFUL) {
					countHere++
					blueHere += blueOfHere
					redHere += redOfHere
				}
			}
		}
		if (countRule == 0 || countHere == 0) {
			// Grey is the same as grey, and nothing else: a black outline is not the same one in pink
			return countRule == countHere
		}
		val blueA = blueRule.toDouble() / countRule
		val redA = redRule.toDouble() / countRule
		val blueB = blueHere.toDouble() / countHere
		val redB = redHere.toDouble() / countHere
		if (abs(blueA - blueB) + abs(redA - redB) > MAX_DIFFERENCE) {
			return false
		}
		val hue = abs(Math.toDegrees(atan2(redA, blueA) - atan2(redB, blueB)))
		return minOf(hue, 360.0 - hue) <= MAX_HUE_DEGREES
	}

	private fun mean(plane: ByteArray, colour: PageColour, luma: PageLuma, left: Int, top: Int, right: Int, bottom: Int): Int {
		val x0 = (left * colour.width / luma.width).coerceIn(0, colour.width - 1)
		val x1 = (right * colour.width / luma.width).coerceIn(x0 + 1, colour.width)
		val y0 = (top * colour.height / luma.height).coerceIn(0, colour.height - 1)
		val y1 = (bottom * colour.height / luma.height).coerceIn(y0 + 1, colour.height)
		var total = 0
		for (y in y0 until y1) {
			val offset = y * colour.width
			for (x in x0 until x1) {
				total += plane[offset + x].toInt() and 0xFF
			}
		}
		return total / ((x1 - x0) * (y1 - y0))
	}
}
