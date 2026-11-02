package org.koitharu.kotatsu.reader.domain.erase

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A rectangle's brightness on a 16x16 grid: a very small copy of it, compared value by value.
 *
 * Where [ImageFingerprint] only records which way brightness steps between cells, this records how much,
 * and where. So it can tell one mostly white credit page from another by where the text sits, which is what
 * lets plain images be remembered at all, and it gives every match a second opinion before anything goes.
 */
object ImageSignature {

	const val GRID = 16

	/** Cells this close across the whole image: nothing to recognise it by, whatever the method. */
	private const val BLANK_RANGE = 6

	/**
	 * Measured on real pages: the same image found elsewhere differs by 0 to 6 on average. Two speech bubbles of
	 * the same shape with different words can be as close as that, so the average cannot be all that counts.
	 *
	 * What differs is how the difference is spread. Being sampled differently touches every edge in an image
	 * a little, so a high worst cell comes with a high average: 65 and 6 for sharp text on black. A real change
	 * is all in one place: 100 and 7 for different words, 63 and 1 for a word of another length. So the worst
	 * cell allowed grows with the average.
	 */
	private const val MAX_MEAN_DIFFERENCE = 6
	private const val MAX_CELL_DIFFERENCE_BASE = 40
	private const val MAX_CELL_DIFFERENCE_PER_MEAN = 5
	private const val FAR_CELL_DIFFERENCE = 40
	private const val MAX_FAR_CELLS = 4

	/**
	 * A cell this far from an image's commonest brightness has real ink in it, not just a faint line. Cells are true
	 * averages, so thin lettering comes to less than its own brightness.
	 */
	private const val INK_DISTANCE = 32

	/**
	 * Less of a rectangle than this with real ink in it is a thin outline or a few specks. All such things look
	 * alike, however exactly they match: the rim of one speech bubble is the rim of any other. Measured on real
	 * pages: bubble rims have 0 to 1%, and everything worth recognising 7% or more. This sits low on purpose: what
	 * gets erased is mostly logos and title text, and wrongly keeping a thin one to a single page means it comes
	 * back every chapter.
	 */
	private const val MIN_INK_PERCENT = 3

	/** A plain image has to agree closely, since most of its cells agree with anything plain. */
	private const val MAX_MEAN_DIFFERENCE_PLAIN = 4
	private const val MAX_CELL_DIFFERENCE_PLAIN = 24

	/**
	 * What the same image has to do to count, wherever a rectangle of it is not exactly the same size: see
	 * [isSameShape]. Only the very image counts. A banner in another colour, or an ad with a mascot added, is another
	 * image, and stays until it is erased as well.
	 */
	private const val MIN_SHAPE_OVERLAP_PERCENT = 85
	private const val SHAPE_CONTENT_DISTANCE = 24
	private const val MAX_SHAPE_BACKGROUND_DIFFERENCE = 8
	private const val FAR_FROM_SHAPE = 50.0

	/**
	 * Another colour moves the whole image's average: 9 to 20 between real banners, against up to 6 for copies of one
	 * at other sizes, and for bubbles of one shape with different words, which can otherwise look just as alike. So
	 * this far off is another image, and under it an image has to be all but exact. The same banner at another size
	 * or crop correlates at 0.97 and 0.98 with at most 2 cells over the nearer of these and none over
	 * [FAR_FROM_SHAPE], and the closest different words at 0.98 with 2 of them over it.
	 */
	private const val MIN_COLOUR_SHIFT = 8
	private const val MIN_SAME_COLOUR_CORRELATION = 0.95
	private const val NEAR_FROM_SAME_COLOUR = 40.0
	private const val MAX_CELLS_NEAR_FROM_SAME_COLOUR = 2

	/**
	 * How much brightness changes from cell to cell, as a share of its whole range. Lettering filling a rectangle
	 * measured 0.12 to 0.20, and art and captions under that. Those can pass everything else with different content,
	 * since a small grid sees their rough shape and not what they say.
	 */
	private const val MIN_RULE_DETAIL = 0.12
	private const val MIN_PAGE_DETAIL = 0.1

	/**
	 * Each cell is the true average of its share of the rectangle, parts of pixels included. Cut to whole pixels, an image
	 * of a hundred pixels across comes out differently depending on how it falls on them, and the same image at another
	 * size stops looking like itself, by far the most of what kept real copies from being found.
	 */
	fun of(luma: PageLuma, left: Int, top: Int, right: Int, bottom: Int): ByteArray {
		val corners = DoubleArray((GRID + 1) * (GRID + 1))
		val cellWidth = (right - left).toDouble() / GRID
		val cellHeight = (bottom - top).toDouble() / GRID
		for (row in 0..GRID) {
			for (column in 0..GRID) {
				corners[row * (GRID + 1) + column] = luma.sumUpTo(left + column * cellWidth, top + row * cellHeight)
			}
		}
		val area = cellWidth * cellHeight
		return ByteArray(GRID * GRID) { index ->
			val at = index / GRID * (GRID + 1) + index % GRID
			val sum = corners[at + GRID + 2] - corners[at + 1] - corners[at + GRID + 1] + corners[at]
			(sum / area + 0.5).toInt().coerceIn(0, 255).toByte()
		}
	}

	/** Whether there is enough actually in an image to tell it from anything else, anywhere. */
	fun hasEnoughContent(signature: ByteArray): Boolean {
		val background = backgroundOf(signature)
		val ink = signature.count { abs((it.toInt() and 0xFF) - background) > INK_DISTANCE }
		return ink * 100 >= signature.size * MIN_INK_PERCENT
	}

	/** The commonest brightness in a signature: what the image sits on, more or less. */
	fun backgroundOf(signature: ByteArray): Int {
		val buckets = IntArray(32)
		for (cell in signature) {
			buckets[(cell.toInt() and 0xFF) / 8]++
		}
		return buckets.indices.maxByOrNull { buckets[it] }!! * 8 + 4
	}

	fun isBlank(signature: ByteArray): Boolean {
		var low = 255
		var high = 0
		for (value in signature) {
			val cell = value.toInt() and 0xFF
			if (cell < low) low = cell
			if (cell > high) high = cell
		}
		return high - low <= BLANK_RANGE
	}

	/** The largest average difference [matches] could accept, so a search can give up on anything above it. */
	fun maxMeanDifference(isPlain: Boolean): Int = if (isPlain) MAX_MEAN_DIFFERENCE_PLAIN else MAX_MEAN_DIFFERENCE

	/**
	 * The average difference between [signature] and a rectangle of a page, measured without making a signature
	 * of it, and given up on the moment it cannot come in under [below].
	 *
	 * @return the difference, or [Int.MAX_VALUE] if it is [below] or more
	 */
	fun differenceWithin(signature: ByteArray, luma: PageLuma, left: Int, top: Int, right: Int, bottom: Int, below: Int): Int {
		val cellWidth = (right - left).toDouble() / GRID
		val cellHeight = (bottom - top).toDouble() / GRID
		val area = cellWidth * cellHeight
		val limit = below * signature.size
		var total = 0
		var index = 0
		var above = DoubleArray(GRID + 1) { luma.sumUpTo(left + it * cellWidth, top.toDouble()) }
		for (row in 0 until GRID) {
			val below2 = DoubleArray(GRID + 1) { luma.sumUpTo(left + it * cellWidth, top + (row + 1) * cellHeight) }
			for (column in 0 until GRID) {
				val sum = below2[column + 1] - above[column + 1] - below2[column] + above[column]
				total += abs((signature[index].toInt() and 0xFF) - (sum / area + 0.5).toInt().coerceIn(0, 255))
				index++
			}
			above = below2
			if (total >= limit) {
				return Int.MAX_VALUE
			}
		}
		return total / signature.size
	}

	fun matches(a: ByteArray, b: ByteArray, isPlain: Boolean): Boolean {
		var total = 0
		var largest = 0
		var far = 0
		for (i in a.indices) {
			val difference = abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
			total += difference
			if (difference > largest) largest = difference
			if (difference > FAR_CELL_DIFFERENCE) far++
		}
		val mean = total / a.size
		return if (isPlain) {
			mean <= MAX_MEAN_DIFFERENCE_PLAIN && largest <= MAX_CELL_DIFFERENCE_PLAIN
		} else {
			mean <= MAX_MEAN_DIFFERENCE &&
				largest <= MAX_CELL_DIFFERENCE_BASE + mean * MAX_CELL_DIFFERENCE_PER_MEAN &&
				far <= MAX_FAR_CELLS
		}
	}

	/** Whether there is enough fine detail in a rule for its shape alone to tell it apart. See [isSameShape]. */
	fun canMatchByShape(signature: ByteArray): Boolean = detailOf(signature) >= MIN_RULE_DETAIL

	/**
	 * What an image that is a whole file of its own has to do to be another copy of one, see [isSameInsert]. Measured on
	 * every short file in a real page cache: one ad at three sizes correlates at 0.995 and up, the same ad with other
	 * lettering at 0.975 with 12 cells far off, and with a mascot added at 0.94. Ink in most of it is what sets those
	 * apart from a caption or a bubble, which at this size are as alike as that with different words: those measured
	 * 34% to 47%, and the ad 65% to 69%.
	 */
	private const val MIN_INSERT_CORRELATION = 0.97
	private const val MIN_INSERT_INK_PERCENT = 50

	/**
	 * For [isSameAtAnySize]. Searching every size and position of all 396 pages of a real cache, the real banner came
	 * to 0.91 to 0.99, and the closest thing that was not, a three-line caption, to 0.75. A few cells may be off,
	 * as it is not the same rendering every time. A banner fills its rectangle, at 55% to 83%: bubbles and captions,
	 * which can look just as alike with other words in them, do not, at 34% to 47%.
	 */
	private const val MIN_ANY_SIZE_CORRELATION = 0.93
	private const val MAX_CELLS_FAR_AT_ANY_SIZE = 6

	/** For [isExactCopy]. Under this much ink is a thin outline or a line of text, which all look alike. */
	private const val MIN_COPY_INK_PERCENT = 25
	private const val MIN_COPY_CORRELATION = 0.97

	private const val MAX_OUTLINE_INK_PERCENT = 30
	private const val MAX_OUTLINE_DETAIL = 0.11
	private const val MAX_OUTLINE_DETAIL_ON_WHITE = 0.14
	private const val MIN_PAGE_WHITE = 200

	/** Under this it is a gradient, and any gradient correlates with any other going the same way. Logos measure 0.075 up. */
	private const val MIN_COPY_DETAIL = 0.06

	/**
	 * Two panels with the same diagonal across them came to 0.97 and up with nothing else alike: with this little detail
	 * a shape is all there is. The same image at another size does better than that, from 0.99.
	 */
	private const val MAX_PLAIN_COPY_DETAIL = 0.1

	/** For [isOneCurve]. A few lines may be off, as a real panel edge has specks on it, and how far it may move per line. */
	private const val MAX_CURVE_BREAKS = 3

	/** With so much less of it to go by, it has to be all but perfect. */
	private const val MAX_CURVE_BREAKS_IN_ROWS = 1
	private const val MAX_CURVE_STEP = 2

	/**
	 * Short lettering on a strip of its own also runs from side to side, but it fills its rectangle, and has more going on:
	 * 0.119 and up. Hatching around a bubble can have as much going on, but leaves the most of its rectangle empty.
	 */
	private const val MAX_CURVE_DETAIL = 0.115

	/** And smooth art filling a whole file runs from side to side as well, with no empty side for a curve to be the edge of. */
	private const val MAX_CURVE_INK_PERCENT = 65
	private const val MIN_PLAIN_COPY_CORRELATION = 0.985

	/**
	 * For [isSameHere]. Two mostly white rectangles agree on most of their cells whatever is in them, so being close says
	 * little about the rest: the rims of two different speech bubbles came out close, at a correlation of about zero.
	 */
	private const val MIN_SAME_HERE_CORRELATION = 0.95

	/** Whether a block of a page has enough fine detail to be worth comparing shapes with at all. */
	fun hasDetailToCompare(signature: ByteArray): Boolean = detailOf(signature) >= MIN_PAGE_DETAIL

	/** The cells of a signature that have something in them, right and bottom exclusive. */
	class InkBox(val left: Int, val top: Int, val right: Int, val bottom: Int, val background: Int, val isDetailed: Boolean) {
		val columns: Int get() = right - left
		val rows: Int get() = bottom - top
	}

	/** Where in its rectangle a rule's ink is, if it has any. */
	fun inkBoxOf(signature: ByteArray): InkBox? {
		val background = backgroundOf(signature)
		var left = GRID
		var top = GRID
		var right = 0
		var bottom = 0
		for (row in 0 until GRID) {
			for (column in 0 until GRID) {
				if (abs((signature[row * GRID + column].toInt() and 0xFF) - background) > SHAPE_CONTENT_DISTANCE) {
					if (column < left) left = column
					if (column >= right) right = column + 1
					if (row < top) top = row
					if (row >= bottom) bottom = row + 1
				}
			}
		}
		return if (right > left && bottom > top) {
			InkBox(left, top, right, bottom, background, canMatchByShape(signature))
		} else {
			null
		}
	}

	/** Whether a rule fills its rectangle as a banner does, and so can be looked for at any size and position. */
	fun fillsItsBox(signature: ByteArray): Boolean = inkPercent(signature, backgroundOf(signature)) >= MIN_INSERT_INK_PERCENT

	/** Whether [here], found by searching a page at many sizes, is the image of [rule]. See [MIN_ANY_SIZE_CORRELATION]. */
	fun isSameAtAnySize(rule: ByteArray, here: ByteArray): Boolean {
		val backgroundRule = backgroundOf(rule)
		val backgroundHere = backgroundOf(here)
		return isSameBackground(backgroundRule, backgroundHere) &&
			inkPercent(here, backgroundHere) >= MIN_INSERT_INK_PERCENT &&
			isSameBrightness(rule, here) &&
			correlation(rule, here) >= MIN_ANY_SIZE_CORRELATION &&
			isInkInSameCells(rule, here, backgroundRule, backgroundHere) &&
			cellsOffBy(rule, here, FAR_FROM_SHAPE) <= MAX_CELLS_FAR_AT_ANY_SIZE
	}

	/**
	 * Whether [here], a whole file of its own, is another copy of [rule].
	 *
	 * Sites serve the same ad as its own file at several sizes, and much of an ad is smooth art, too little detail for
	 * [isSameShape]. What makes up for it is that nothing else is like it: filling a file of the same proportions, the
	 * same art in the same places, with no cell far off. That last is what tells it from the same ad with a mascot added.
	 */
	fun isSameInsert(rule: ByteArray, here: ByteArray): Boolean {
		val background = backgroundOf(rule)
		val backgroundHere = backgroundOf(here)
		return isSameBackground(background, backgroundHere) &&
			inkPercent(rule, background) >= MIN_INSERT_INK_PERCENT &&
			inkPercent(here, backgroundHere) >= MIN_INSERT_INK_PERCENT &&
			isSameBrightness(rule, here) &&
			correlation(rule, here) >= MIN_INSERT_CORRELATION &&
			cellsOffBy(rule, here, FAR_FROM_SHAPE) == 0
	}

	private fun inkPercent(signature: ByteArray, background: Int): Int =
		signature.count { abs((it.toInt() and 0xFF) - background) > SHAPE_CONTENT_DISTANCE } * 100 / signature.size

	/** Whether two brightnesses are close enough to be the same background to [isSameShape]. */
	fun isSameBackground(a: Int, b: Int): Boolean = abs(a - b) <= MAX_SHAPE_BACKGROUND_DIFFERENCE

	/** How alike two signatures are in where they are bright and dark, whatever the brightness: -1 to 1. */
	fun correlation(a: ByteArray, b: ByteArray): Double {
		var sumA = 0L
		var sumB = 0L
		var sumAA = 0L
		var sumBB = 0L
		var sumAB = 0L
		for (i in a.indices) {
			val x = a[i].toInt() and 0xFF
			val y = b[i].toInt() and 0xFF
			sumA += x
			sumB += y
			sumAA += x * x
			sumBB += y * y
			sumAB += x * y
		}
		val spreadA = (a.size * sumAA - sumA * sumA).toDouble()
		val spreadB = (a.size * sumBB - sumB * sumB).toDouble()
		if (spreadA <= 0.0 || spreadB <= 0.0) {
			return 0.0
		}
		return (a.size * sumAB - sumA * sumB) / sqrt(spreadA * spreadB)
	}

	/**
	 * Whether [here] is the image of [rule], from a rectangle that is not quite the same as the one it was erased from.
	 *
	 * The ink has to sit in the same cells on the same background, at the same brightness, so that the same banner in
	 * another colour is not it, and one brightness has to map onto the other across the whole image. It only goes for
	 * images with plenty of detail, see [canMatchByShape], as there is little else to tell the rest by.
	 */
	fun isSameShape(rule: ByteArray, here: ByteArray): Boolean {
		if (detailOf(here) < MIN_PAGE_DETAIL || correlation(rule, here) < MIN_SAME_COLOUR_CORRELATION || !isSameBrightness(rule, here)) {
			return false
		}
		val backgroundRule = backgroundOf(rule)
		val backgroundHere = backgroundOf(here)
		if (!isSameBackground(backgroundRule, backgroundHere) || !isInkInSameCells(rule, here, backgroundRule, backgroundHere)) {
			return false
		}
		return cellsOffBy(rule, here, NEAR_FROM_SAME_COLOUR) <= MAX_CELLS_NEAR_FROM_SAME_COLOUR && cellsOffBy(rule, here, FAR_FROM_SHAPE) == 0
	}

	/**
	 * Whether [here] is a copy of [rule] at another size, for an image with too little detail for [isSameShape], such
	 * as a logo or smooth art standing on its own. All it has to go by is agreeing everywhere, which the same image
	 * resized does: 0.98 with no cell off, against 0.975 and 12 cells for the same ad with other lettering.
	 */
	fun isExactCopy(rule: ByteArray, here: ByteArray): Boolean {
		val background = backgroundOf(rule)
		return inkPercent(rule, background) >= MIN_COPY_INK_PERCENT &&
			detailOf(rule) >= MIN_COPY_DETAIL &&
			!isOneCurve(rule) &&
			isSameBrightness(rule, here) &&
			correlation(rule, here) >= (if (detailOf(rule) < MAX_PLAIN_COPY_DETAIL) MIN_PLAIN_COPY_CORRELATION else MIN_COPY_CORRELATION) &&
			cellsOffBy(rule, here, NEAR_FROM_SAME_COLOUR) == 0
	}

	/**
	 * Whether a rule is hardly more than an outline, which says nothing about what it is an outline of. Every real match
	 * of one, 96 among 939 pages, was the rim of some other speech bubble. Logos have more ink or more detail than this.
	 */
	fun isOutline(signature: ByteArray): Boolean {
		val background = backgroundOf(signature)
		// Hatching around a bubble has as much detail as lettering, and is always on the white of the page
		val maxDetail = if (background >= MIN_PAGE_WHITE) MAX_OUTLINE_DETAIL_ON_WHITE else MAX_OUTLINE_DETAIL
		return (inkPercent(signature, background) < MAX_OUTLINE_INK_PERCENT && detailOf(signature) < maxDetail) || isOneCurve(signature)
	}

	/**
	 * Whether all there is to a rectangle is one curve from side to side: the rim of a bubble, the hatching or spikes around
	 * a shouted one, the diagonal side of a panel, a dome of white on black. Going along it, each line across meets one
	 * stretch of ink, about where the last one did. However well two of these agree, at up to 0.999, that says nothing about
	 * what they are the edge of. In lettering and logos, lines meet several stretches, or none, and they jump about.
	 */
	fun isOneCurve(signature: ByteArray): Boolean {
		val background = backgroundOf(signature)
		val ink = inkPercent(signature, background)
		if (ink >= MAX_CURVE_INK_PERCENT || (ink >= MIN_INSERT_INK_PERCENT && detailOf(signature) >= MAX_CURVE_DETAIL)) {
			return false
		}
		return followsOneCurve(signature, background, GRID, 1, false) || followsOneCurve(signature, background, 1, GRID, false) ||
			followsOneCurve(signature, background, GRID, 1, true) || followsOneCurve(signature, background, 1, GRID, true)
	}

	/**
	 * Whether the rows from [first] to [last] of a signature come to one curve, as they can when all that is on a page of a
	 * cut off image is the rim of a bubble, with what it says on the next page.
	 */
	fun isOneCurveInRows(signature: ByteArray, first: Int, last: Int): Boolean {
		val background = backgroundOf(signature)
		var ink = 0
		for (row in first..last) {
			for (column in 0 until GRID) {
				if (abs((signature[row * GRID + column].toInt() and 0xFF) - background) > SHAPE_CONTENT_DISTANCE) ink++
			}
		}
		// Lettering on a strip of its own is one stretch from side to side as well, but it fills what is there
		if (ink * 100 >= (last - first + 1) * GRID * MIN_INSERT_INK_PERCENT) {
			return false
		}
		return followsOneCurveInRows(signature, background, first, last, false) || followsOneCurveInRows(signature, background, first, last, true)
	}

	private fun followsOneCurveInRows(signature: ByteArray, background: Int, first: Int, last: Int, isInverted: Boolean): Boolean {
		var breaks = 0
		var previous = -1
		for (column in 0 until GRID) {
			var stretches = 0
			var isInk = false
			var total = 0
			var count = 0
			for (row in first..last) {
				val value = signature[row * GRID + column].toInt() and 0xFF
				val isInkHere = (abs(value - background) > SHAPE_CONTENT_DISTANCE) != isInverted
				if (isInkHere) {
					if (!isInk) stretches++
					total += row
					count++
				}
				isInk = isInkHere
			}
			val centre = if (count > 0) total / count else -1
			if (stretches != 1 || (previous >= 0 && abs(centre - previous) > MAX_CURVE_STEP)) breaks++
			previous = centre
		}
		return breaks <= MAX_CURVE_BREAKS_IN_ROWS
	}

	/** @param isInverted to follow what is left of the rectangle instead, as when the curve goes around the middle of it */
	private fun followsOneCurve(signature: ByteArray, background: Int, along: Int, across: Int, isInverted: Boolean): Boolean {
		var breaks = 0
		var last = -1
		for (line in 0 until GRID) {
			var stretches = 0
			var isInk = false
			var total = 0
			var count = 0
			for (cell in 0 until GRID) {
				val value = signature[line * across + cell * along].toInt() and 0xFF
				val isInkHere = (abs(value - background) > SHAPE_CONTENT_DISTANCE) != isInverted
				if (isInkHere) {
					if (!isInk) stretches++
					total += cell
					count++
				}
				isInk = isInkHere
			}
			val centre = if (count > 0) total / count else -1
			if (stretches != 1 || (last >= 0 && abs(centre - last) > MAX_CURVE_STEP)) breaks++
			last = centre
		}
		return breaks <= MAX_CURVE_BREAKS
	}

	/** Whether there is enough in a rule for [isExactCopy] to mean anything. */
	fun hasInkToCopy(signature: ByteArray): Boolean =
		inkPercent(signature, backgroundOf(signature)) >= MIN_COPY_INK_PERCENT &&
			detailOf(signature) >= MIN_COPY_DETAIL &&
			!isOneCurve(signature)

	/**
	 * Whether [here], a whole file of the same proportions as [rule], is another copy of it. All that is asked of what is in
	 * it is to agree everywhere, as this is a far stronger thing to find than a shape somewhere on a page: an outline
	 * matching a whole file really is that file, where one on a page is the edge of anything.
	 */
	fun isSameFile(rule: ByteArray, here: ByteArray): Boolean =
		isSameBrightness(rule, here) &&
			correlation(rule, here) >= MIN_PLAIN_COPY_CORRELATION &&
			cellsOffBy(rule, here, NEAR_FROM_SAME_COLOUR) == 0

	/** Whether [here], at the size and position of a rule, is the image: see [matches] and [MIN_SAME_HERE_CORRELATION]. */
	fun isSameHere(rule: ByteArray, here: ByteArray, isPlain: Boolean): Boolean =
		matches(rule, here, isPlain) && correlation(rule, here) >= MIN_SAME_HERE_CORRELATION

	private fun isSameBrightness(rule: ByteArray, here: ByteArray): Boolean {
		var difference = 0L
		for (i in rule.indices) {
			difference += (here[i].toInt() and 0xFF) - (rule[i].toInt() and 0xFF)
		}
		return abs(difference) < MIN_COLOUR_SHIFT * rule.size
	}

	private fun isInkInSameCells(rule: ByteArray, here: ByteArray, backgroundRule: Int, backgroundHere: Int): Boolean {
		var inBoth = 0
		var inEither = 0
		for (i in rule.indices) {
			val isInkRule = abs((rule[i].toInt() and 0xFF) - backgroundRule) > SHAPE_CONTENT_DISTANCE
			val isInkHere = abs((here[i].toInt() and 0xFF) - backgroundHere) > SHAPE_CONTENT_DISTANCE
			if (isInkRule && isInkHere) inBoth++
			if (isInkRule || isInkHere) inEither++
		}
		return inBoth * 100 >= inEither * MIN_SHAPE_OVERLAP_PERCENT
	}

	/** How many cells are further than [distance] from the brightness that best turns one signature into the other. */
	private fun cellsOffBy(rule: ByteArray, here: ByteArray, distance: Double): Int {
		var sumRule = 0L
		var sumHere = 0L
		var sumRuleRule = 0L
		var sumRuleHere = 0L
		for (i in rule.indices) {
			val x = rule[i].toInt() and 0xFF
			val y = here[i].toInt() and 0xFF
			sumRule += x
			sumHere += y
			sumRuleRule += x * x
			sumRuleHere += x * y
		}
		val spread = rule.size * sumRuleRule - sumRule * sumRule
		if (spread == 0L) {
			return rule.size
		}
		val gain = (rule.size * sumRuleHere - sumRule * sumHere).toDouble() / spread
		val offset = (sumHere - gain * sumRule) / rule.size
		return rule.indices.count { i ->
			abs((here[i].toInt() and 0xFF) - (gain * (rule[i].toInt() and 0xFF) + offset)) > distance
		}
	}

	internal fun detailOf(signature: ByteArray): Double {
		var low = 255
		var high = 0
		var steps = 0L
		for (row in 0 until GRID) {
			for (column in 0 until GRID) {
				val cell = signature[row * GRID + column].toInt() and 0xFF
				if (cell < low) low = cell
				if (cell > high) high = cell
				if (column + 1 < GRID) steps += abs((signature[row * GRID + column + 1].toInt() and 0xFF) - cell)
				if (row + 1 < GRID) steps += abs((signature[(row + 1) * GRID + column].toInt() and 0xFF) - cell)
			}
		}
		if (high == low) {
			return 0.0
		}
		return steps.toDouble() / (2 * GRID * (GRID - 1)) / (high - low)
	}
}
