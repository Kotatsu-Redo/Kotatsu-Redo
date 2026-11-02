package org.koitharu.kotatsu.reader.domain.erase

/**
 * A page reduced to a small greyscale copy, the only thing every erase decision is made from.
 *
 * Pure data with no Android types, so everything built on it runs in plain unit tests. Sums come from
 * an integral image, which is what keeps matching cheap enough for a low-end phone: averaging any
 * rectangle costs four lookups however large it is, so sliding a window down a long strip costs the
 * same per position as sliding it down a short one.
 *
 * @param width width of [data] in pixels, a fraction of [sourceWidth]
 * @param data one unsigned byte per pixel, row by row
 * @param chroma how colourful each pixel is, the same way round. Only filled in to compare styles: matching
 *  never needs it, and it would double what every scanned page costs
 */
class PageLuma(
	val width: Int,
	val height: Int,
	val sourceWidth: Int,
	val sourceHeight: Int,
	val data: ByteArray,
	private val chroma: ByteArray? = null,
	/** Absent only where it plays no part: without it, an image's colours do not get compared. */
	val colour: PageColour? = null,
	/** Absent unless something to be found by its points is being looked for. See [ImageKeypoints]. */
	val detail: PageDetail? = null,
) {

	init {
		require(width > 0 && height > 0) { "Empty image" }
		require(data.size == width * height) { "Expected ${width * height} pixels, got ${data.size}" }
	}

	/** Built on first use. Filled in before it is set, so threads that race to build it merely do so twice. */
	@Volatile
	private var integral: IntArray? = null

	/** Which whole rows are blank, kept once worked out: many rules ask about the same rows of one page. */
	@Volatile
	internal var flatRows: BooleanArray? = null

	/** For each row, whether content ending there is followed by blank space, one way and the other. */
	@Volatile
	internal var blankAfterRowsAtTop: BooleanArray? = null

	@Volatile
	internal var blankAfterRowsAtBottom: BooleanArray? = null

	/** All of this page as one signature, as many rules may be asked about it. See [HiddenImageMatcher]. */
	@Volatile
	internal var wholeSignature: ByteArray? = null

	/** The rows those are true for, which are few, so that nothing has to go through all of the rest. */
	@Volatile
	internal var edgeRowsAtTop: IntArray? = null

	@Volatile
	internal var edgeRowsAtBottom: IntArray? = null

	/** What on this page stands on its own, with enough detail to compare shapes with. */
	@Volatile
	internal var shapeBlocks: List<HiddenImageMatcher.ShapeBlock>? = null

	private fun buildIntegral(): IntArray {
		val stride = width + 1
		val result = IntArray(stride * (height + 1))
		for (y in 0 until height) {
			var row = 0
			val offset = y * width
			val above = y * stride
			val current = (y + 1) * stride
			for (x in 0 until width) {
				row += data[offset + x].toInt() and 0xFF
				result[current + x + 1] = result[above + x + 1] + row
			}
		}
		integral = result
		return result
	}

	operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF

	fun chromaAt(x: Int, y: Int): Int = if (chroma == null) 0 else chroma[y * width + x].toInt() and 0xFF

	/** The average brightness of a rectangle, right and bottom exclusive. */
	fun mean(left: Int, top: Int, right: Int, bottom: Int): Int {
		val area = (right - left) * (bottom - top)
		if (area <= 0) {
			return 0
		}
		val stride = width + 1
		val table = integral ?: buildIntegral()
		val sum = table[bottom * stride + right] - table[top * stride + right] -
			table[bottom * stride + left] + table[top * stride + left]
		return sum / area
	}

	/**
	 * The sum of all brightness above and to the left of a point that need not be on a pixel's edge, as a rectangle's sum
	 * comes from four of these. Going from pixel to pixel in a straight line is what a pixel evenly filled comes to.
	 */
	fun sumUpTo(x: Double, y: Double): Double {
		val stride = width + 1
		val table = integral ?: buildIntegral()
		val column = x.toInt().coerceIn(0, width - 1)
		val row = y.toInt().coerceIn(0, height - 1)
		val partX = (x - column).coerceIn(0.0, 1.0)
		val partY = (y - row).coerceIn(0.0, 1.0)
		val at = row * stride + column
		val top = table[at] + (table[at + 1] - table[at]) * partX
		val bottom = table[at + stride] + (table[at + stride + 1] - table[at + stride]) * partX
		return top + (bottom - top) * partY
	}

	/** The average of a rectangle whose edges need not be on those of pixels. See [sumUpTo]. */
	fun meanBetween(left: Double, top: Double, right: Double, bottom: Double): Int {
		val area = (right - left) * (bottom - top)
		if (area <= 0.0) {
			return 0
		}
		val sum = sumUpTo(right, bottom) - sumUpTo(left, bottom) - sumUpTo(right, top) + sumUpTo(left, top)
		return (sum / area + 0.5).toInt().coerceIn(0, 255)
	}

	/** How much the pixels of a rectangle vary: near zero for a flat fill. Visits every pixel. */
	fun contrast(left: Int, top: Int, right: Int, bottom: Int): Int {
		val mean = mean(left, top, right, bottom)
		var total = 0L
		var count = 0
		for (y in top until bottom) {
			val offset = y * width
			for (x in left until right) {
				total += kotlin.math.abs((data[offset + x].toInt() and 0xFF) - mean)
				count++
			}
		}
		return if (count == 0) 0 else (total / count).toInt()
	}

	fun scaleX(sourceX: Int): Int = (sourceX.toLong() * width / sourceWidth).toInt().coerceIn(0, width)

	fun scaleY(sourceY: Int): Int = (sourceY.toLong() * height / sourceHeight).toInt().coerceIn(0, height)

	fun sourceX(x: Int): Int = (x.toLong() * sourceWidth / width).toInt().coerceIn(0, sourceWidth)

	fun sourceY(y: Int): Int = (y.toLong() * sourceHeight / height).toInt().coerceIn(0, sourceHeight)

	companion object {

		/** Wide enough to see gutters and to tell two banners apart, small enough to scan in a moment. */
		const val TARGET_WIDTH = 96

		/** Keeps the integral image from overflowing and bounds memory on absurdly long strips. */
		const val MAX_HEIGHT = 4096

		/** The largest power of two that keeps a page at or above [TARGET_WIDTH] and below [MAX_HEIGHT]. */
		fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int): Int {
			var sample = 1
			while (sourceWidth / (sample * 2) >= TARGET_WIDTH) {
				sample *= 2
			}
			while (sourceHeight / sample > MAX_HEIGHT) {
				sample *= 2
			}
			return sample
		}
	}
}
