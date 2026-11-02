package org.koitharu.kotatsu.reader.domain.erase

/**
 * What to leave out of one page while it is being decoded.
 *
 * The file itself is never touched. Bands are skipped row by row, so the image view is simply told about a
 * shorter picture and asks for parts of that, which costs no extra memory and no quality however long the
 * strip is. Coordinates called "visible" are that shorter picture; "source" is the file.
 *
 * @param bands sorted, not overlapping: rows to skip
 * @param patches rectangles to paint over, in source pixels, because leaving out part of a row would shear it
 */
data class PageMask(
	val sourceWidth: Int,
	val sourceHeight: Int,
	val bands: List<SourceRegion>,
	val patches: List<SourceRegion>,
) {

	val visibleHeight: Int = sourceHeight - bands.sumOf { it.height }

	/** Where a source row ended up. A row in a skipped band lands on the join it left behind. */
	fun toVisible(sourceY: Int): Int {
		var removed = 0
		for (band in bands) {
			if (sourceY <= band.top) break
			removed += minOf(sourceY, band.bottom) - band.top
		}
		return sourceY - removed
	}

	fun toSource(visibleY: Int): Int {
		var y = visibleY
		for (band in bands) {
			if (y < band.top) break
			y += band.height
		}
		return y
	}

	/** The stretches of the file that make up rows [visibleTop] until [visibleBottom], top to bottom. */
	fun slices(visibleTop: Int, visibleBottom: Int): List<IntRange> {
		val slices = ArrayList<IntRange>(1)
		var y = toSource(visibleTop)
		var left = visibleBottom - visibleTop
		var bandIndex = bands.indexOfFirst { it.bottom > y }.let { if (it < 0) bands.size else it }
		while (left > 0 && y < sourceHeight) {
			val nextBandTop = if (bandIndex < bands.size) bands[bandIndex].top else sourceHeight
			val end = minOf(y + left, nextBandTop)
			if (end > y) {
				slices += y until end
				left -= end - y
			}
			if (bandIndex < bands.size) {
				y = bands[bandIndex].bottom
				bandIndex++
			} else {
				y = end
			}
		}
		return slices
	}

	companion object {

		private const val FIELD = ','
		private const val ITEM = ';'

		fun encode(regions: List<SourceRegion>): String = regions.joinToString(ITEM.toString()) {
			"${it.left}$FIELD${it.top}$FIELD${it.right}$FIELD${it.bottom}"
		}

		fun decode(text: String): List<SourceRegion> {
			if (text.isEmpty()) {
				return emptyList()
			}
			return text.split(ITEM).map { item ->
				val (left, top, right, bottom) = item.split(FIELD).map(String::toInt)
				SourceRegion(left, top, right, bottom)
			}
		}
	}
}
