package org.koitharu.kotatsu.reader.domain.erase

/**
 * A difference hash of one rectangle of a page: which way brightness steps between neighbouring cells
 * of a 9x9 grid, both across and down. It survives the same image being re-encoded or served at another
 * size, which a file hash would not.
 *
 * [org.koitharu.kotatsu.alternatives.ui.covers.CoverHash] is not reused here: it centre-crops everything
 * to a cover's 2:3, which would throw away most of a banner, and its 63 bits are tuned for "could these
 * be the same cover" rather than for silently deleting content.
 */
data class ImageFingerprint(
	val horizontal: Long,
	val vertical: Long,
	val mean: Int,
) {

	fun distanceTo(other: ImageFingerprint): Int =
		java.lang.Long.bitCount(horizontal xor other.horizontal) + java.lang.Long.bitCount(vertical xor other.vertical)

	/**
	 * Whether enough brightness steps were found to recognise this image again. Nearly flat images hash
	 * to nearly all zeroes, and so do each other: remembering a blank panel would then hide every blank
	 * panel in every manga.
	 */
	val isDistinctive: Boolean
		get() = setBits >= MIN_SET_BITS

	/**
	 * How many bits another image may differ by and still be this one. A plain image has few bits to
	 * lose, and anything else plain sits within a few of them, so a fixed allowance would let a sparse
	 * credit page swallow every sparse page. Scaling it means a false match has to agree on most of what
	 * little there is.
	 */
	val matchTolerance: Int
		get() = (setBits / 3).coerceAtMost(MAX_TOLERANCE)

	/**
	 * The same for when a signature gets the final say, as this then only has to say where to look. Sharp text
	 * sampled a little differently can use up all of the tighter allowance while plainly being the same image.
	 */
	val searchTolerance: Int
		get() = (setBits / 3).coerceIn(MIN_SEARCH_TOLERANCE, MAX_SEARCH_TOLERANCE)

	private val setBits: Int
		get() = java.lang.Long.bitCount(horizontal) + java.lang.Long.bitCount(vertical)

	/**
	 * Measures one rectangle's size at many heights of a page against a fingerprint, as sliding a window down a
	 * page does thousands of times. Gives the same distances as [of] and [distanceTo], without allocating for
	 * each, and it stops as soon as a window is too far off to matter, which nearly all of them are.
	 */
	internal class Probe(private val luma: PageLuma, left: Int, right: Int, height: Int) {

		private val cellLeft = IntArray(GRID)
		private val cellRight = IntArray(GRID)
		private val cellTop = IntArray(GRID)
		private val cellBottom = IntArray(GRID)
		private var upper = IntArray(GRID)
		private var lower = IntArray(GRID)

		init {
			val width = right - left
			for (i in 0 until GRID) {
				cellLeft[i] = left + width * i / GRID
				cellRight[i] = (left + width * (i + 1) / GRID).coerceAtLeast(cellLeft[i] + 1).coerceAtMost(luma.width)
				cellTop[i] = height * i / GRID
				cellBottom[i] = (height * (i + 1) / GRID).coerceAtLeast(cellTop[i] + 1)
			}
		}

		/** @return how many bits the window at row [top] differs by, or [Int.MAX_VALUE] if that is over [limit] */
		fun distanceTo(target: ImageFingerprint, top: Int, limit: Int): Int {
			fillRow(0, top, upper)
			var distance = 0
			var bit = 0
			for (row in 0 until GRID - 1) {
				fillRow(row + 1, top, lower)
				for (column in 0 until GRID - 1) {
					val cell = upper[column]
					val horizontal = upper[column + 1] > cell + DEAD_ZONE
					val vertical = lower[column] > cell + DEAD_ZONE
					if (horizontal != (target.horizontal ushr bit and 1L == 1L)) distance++
					if (vertical != (target.vertical ushr bit and 1L == 1L)) distance++
					bit++
				}
				if (distance > limit) {
					return Int.MAX_VALUE
				}
				val swap = upper
				upper = lower
				lower = swap
			}
			return distance
		}

		private fun fillRow(row: Int, top: Int, into: IntArray) {
			val rowTop = top + cellTop[row]
			val rowBottom = (top + cellBottom[row]).coerceAtMost(luma.height)
			for (column in 0 until GRID) {
				into[column] = luma.mean(cellLeft[column], rowTop, cellRight[column], rowBottom)
			}
		}
	}

	companion object {

		/**
		 * Of 128 bits. Deliberately tight: a miss shows an ad once more and the reader can erase it again,
		 * while a false match silently removes part of a story.
		 */
		private const val MAX_TOLERANCE = 12
		private const val MIN_SEARCH_TOLERANCE = 2
		private const val MAX_SEARCH_TOLERANCE = 18


		private const val GRID = 9

		/** A step must exceed this to count, so re-encoding noise on flat areas cannot flip bits. */
		private const val DEAD_ZONE = 2
		private const val MIN_SET_BITS = 12

		/** The smallest rectangle, in [PageLuma] pixels, with a cell per grid position. */
		const val MIN_SIZE = GRID

		fun of(luma: PageLuma, left: Int, top: Int, right: Int, bottom: Int): ImageFingerprint {
			val cells = IntArray(GRID * GRID)
			val width = right - left
			val height = bottom - top
			for (row in 0 until GRID) {
				val cellTop = top + height * row / GRID
				val cellBottom = (top + height * (row + 1) / GRID).coerceAtLeast(cellTop + 1).coerceAtMost(luma.height)
				for (column in 0 until GRID) {
					val cellLeft = left + width * column / GRID
					val cellRight = (left + width * (column + 1) / GRID).coerceAtLeast(cellLeft + 1).coerceAtMost(luma.width)
					cells[row * GRID + column] = luma.mean(cellLeft, cellTop, cellRight, cellBottom)
				}
			}
			var horizontal = 0L
			var vertical = 0L
			var bit = 0
			for (row in 0 until GRID - 1) {
				for (column in 0 until GRID - 1) {
					val cell = cells[row * GRID + column]
					if (cells[row * GRID + column + 1] > cell + DEAD_ZONE) {
						horizontal = horizontal or (1L shl bit)
					}
					if (cells[(row + 1) * GRID + column] > cell + DEAD_ZONE) {
						vertical = vertical or (1L shl bit)
					}
					bit++
				}
			}
			return ImageFingerprint(horizontal, vertical, luma.mean(left, top, right, bottom))
		}
	}
}
