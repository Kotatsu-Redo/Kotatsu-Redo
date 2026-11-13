package org.koitharu.kotatsu.reader.domain.erase

import kotlin.math.abs

/**
 * The most probable thing a scrub meant.
 *
 * @param region what goes if the borders are taken as offered: the object, and whatever blank space is around it
 * @param objectRegion the object itself: where the borders start, and what it is judged and recognised by
 * @param isStandalone whether this is one separate image that the scrub stayed on: the first thing it takes
 *  to erase without asking. See [EraseProposer.isUnlikeTheRest] for the second
 * @param rest the other content on the page, for comparing with
 * @param snapX edges, in source pixels, that the borders should settle on when let go near them
 * @param aroundStroke where the borders start instead when the object is far bigger than what was rubbed, which
 *  is someone after a logo in a panel and not the panel. See [EraseProposer.regionAroundStroke]
 */
class EraseProposal(
	val region: SourceRegion,
	val objectRegion: SourceRegion,
	val isStandalone: Boolean,
	val rest: List<SourceRegion>,
	val snapX: IntArray,
	val snapY: IntArray,
	val aroundStroke: SourceRegion? = null,
)

/**
 * An image without the blank rows around it, and how much of that there was.
 *
 * Recognising it and removing it want different things. What it looks like must not depend on the gap it
 * happened to have this time, or the same ad with a narrower gutter on the next page would not match. What
 * goes away should include that gap, or removing an ad leaves a white stripe where it was.
 *
 * @param padTop blank rows above it, as a share of its height
 */
class TightRegion(val region: SourceRegion, val padTop: Float, val padBottom: Float)

/**
 * Works out what is under a scrub from the page itself, by finding where its content is separated.
 *
 * Comics are unusually kind to this: panels and inserted banners sit between gutters, rows or columns of
 * one flat colour, and an ad pasted into a strip usually starts with a hard cut. Walking rows of a tiny
 * greyscale copy finds both in well under a frame's worth of work per page, with no model to ship or load.
 */
object EraseProposer {

	/** Of [ImageStyle.distanceTo]. High on purpose: anything less clear-cut gets borders, and costs a tap. */
	private const val VERY_DIFFERENT = 0.4f
	private const val MIN_STROKE_INSIDE_TENTHS = 6
	private const val WHOLE_PAGE_PERCENT = 85
	private const val MAX_SCREENS = 1.6f

	/** A row this close to its own average is a gutter. Allows for compression noise. */
	private const val FLAT_ROW = 4

	/** Compression noise stays well below this; see [isHardCut]. */
	private const val CUT_PIXEL_CHANGE = 12
	private const val CUT_SHARE_PERCENT = 80

	/** Of a page's width: how far past the object the borders go, in blank space and never over content. */
	private const val OVERFIT_ONE_SIDE_DIVISOR = 32
	private const val OVERFIT_SURROUNDED_DIVISOR = 12
	private const val REMAINDER_MIN_SLACK_ROWS = 4

	/** Of the stroke's own size: how far the borders start from it either way when nothing says where the object ends. */
	private const val STROKE_GROW_DIVISOR = 2

	/**
	 * What makes an object far bigger than what was rubbed: a good part of a screen tall, and many times the
	 * height of the stroke. A banner is neither, and rubbing up and down something tall is not the second.
	 */
	private const val PANEL_SCREENS = 0.4f
	private const val MUCH_TALLER = 6

	/** How far past a match to look for the rest of it: an eighth of its height, and a couple of rows at least. */
	private const val LEFTOVER_SHARE_DIVISOR = 8
	private const val LEFTOVER_MIN_ROWS = 2

	/** Whether what surrounds it on the page is too little to judge it by, so other pages are needed. */
	fun needsNeighbours(proposal: EraseProposal): Boolean {
		val around = proposal.rest.sumOf { it.width.toLong() * it.height }
		return around < proposal.objectRegion.width.toLong() * proposal.objectRegion.height / 2
	}

	/**
	 * Whether an image looks nothing like what surrounds it: what makes it probably an ad rather than part
	 * of the story. It has to be unlike everything it is compared with, and with nothing to compare it to
	 * the answer is no, since being wrong here removes part of a story without asking first.
	 *
	 * @param neighbours neighbouring pages, whole
	 */
	fun isUnlikeTheRest(luma: PageLuma, proposal: EraseProposal, neighbours: List<PageLuma>): Boolean {
		val style = ImageStyle.of(luma, listOf(proposal.objectRegion)) ?: return false
		val references = buildList {
			if (proposal.rest.isNotEmpty()) {
				add(ImageStyle.of(luma, proposal.rest))
			}
			neighbours.forEach { page ->
				add(ImageStyle.of(page, listOf(SourceRegion(0, 0, page.sourceWidth, page.sourceHeight))))
			}
		}.filterNotNull()
		return references.isNotEmpty() && references.all { style.distanceTo(it) >= VERY_DIFFERENT }
	}

	/**
	 * @param stroke where the scrub went, in source pixels
	 * @param viewportAspect the screen's height over width: a selection much taller than the screen means
	 *  the gutters were not found, not that the reader meant all of it
	 */
	fun propose(luma: PageLuma, stroke: SourceRegion, viewportAspect: Float): EraseProposal {
		val strokeLeft = luma.scaleX(stroke.left)
		val strokeRight = luma.scaleX(stroke.right).coerceAtLeast(strokeLeft + 1).coerceAtMost(luma.width)
		val strokeTop = luma.scaleY(stroke.top)
		val strokeBottom = luma.scaleY(stroke.bottom).coerceAtLeast(strokeTop + 1).coerceAtMost(luma.height)

		// Wider than the space between lines of text, or a banner falls apart into its separate lines
		val minGap = (luma.width / 12).coerceAtLeast(2)
		val bands = findRowBands(luma, minGap)
		val band = bands.maxByOrNull { overlap(it.start, it.end, strokeTop, strokeBottom) }
			?: Span(0, luma.height, isPageStart = true, isPageEnd = true)
		val columns = findColumnSpans(luma, band, minGap)
		val column = columns.maxByOrNull { overlap(it.start, it.end, strokeLeft, strokeRight) }
			?: Span(0, luma.width, isPageStart = true, isPageEnd = true)

		val selectionHeight = band.end - band.start
		val staysInside = overlap(band.start, band.end, strokeTop, strokeBottom) * 10 >=
			(strokeBottom - strokeTop) * MIN_STROKE_INSIDE_TENTHS
		val isWholePage = selectionHeight * 100 >= luma.height * WHOLE_PAGE_PERCENT
		val hasEdge = !(band.isPageStart && band.isPageEnd)
		val isSaneSize = selectionHeight <= (column.end - column.start) * viewportAspect * MAX_SCREENS

		// Screens of it mean the gutters were not found. A panel around a small rub means a logo or a watermark
		// in that panel. Either way nobody rubbing one spot meant all of it, unless it turns out to be an insert
		val screenRows = luma.width * viewportAspect
		val isFarBigger = selectionHeight > screenRows * MAX_SCREENS ||
			(!isWholePage && selectionHeight > screenRows * PANEL_SCREENS && selectionHeight >= (strokeBottom - strokeTop) * MUCH_TALLER)
		val aroundStroke = if (isFarBigger) {
			// Across the whole width: columns worked out for a band this size say little about what is near the stroke
			val width = Span(0, luma.width, isPageStart = true, isPageEnd = true)
			regionAroundStroke(luma, band, width, strokeLeft, strokeTop, strokeRight, strokeBottom, screenRows.toInt())
		} else {
			null
		}

		// Blank on both sides means nothing nearby can be caught by going further, so it does
		val blankAbove = flatRowsFrom(luma, band.start - 1, -1, 0, luma.width)
		val blankBelow = flatRowsFrom(luma, band.end, 1, 0, luma.width)
		val isSurrounded = (blankAbove > 0 || band.start == 0) && (blankBelow > 0 || band.end == luma.height)
		val rowPad = if (isSurrounded) {
			(luma.width / OVERFIT_SURROUNDED_DIVISOR).coerceAtLeast(3)
		} else {
			(luma.width / OVERFIT_ONE_SIDE_DIVISOR).coerceAtLeast(2)
		}
		val columnPad = (luma.width / OVERFIT_ONE_SIDE_DIVISOR).coerceAtLeast(2)
		// With nothing else on those rows, the rows themselves are what is going away, not a box within them
		val isAloneOnRows = columns.size == 1
		val region = SourceRegion(
			left = if (isAloneOnRows) 0 else luma.sourceX(column.start - minOf(columnPad, flatColumnsFrom(luma, column.start - 1, -1, band))),
			top = luma.sourceY(band.start - minOf(rowPad, blankAbove)),
			right = if (isAloneOnRows) luma.sourceWidth else luma.sourceX(column.end + minOf(columnPad, flatColumnsFrom(luma, column.end, 1, band))),
			bottom = luma.sourceY(band.end + minOf(rowPad, blankBelow)),
		)

		return EraseProposal(
			region = region,
			objectRegion = SourceRegion(
				left = luma.sourceX(column.start),
				top = luma.sourceY(band.start),
				right = luma.sourceX(column.end),
				bottom = luma.sourceY(band.end),
			),
			isStandalone = staysInside && (isWholePage || (hasEdge && isSaneSize)),
			rest = bands.filter { it !== band }.map {
				SourceRegion(0, luma.sourceY(it.start), luma.sourceWidth, luma.sourceY(it.end))
			},
			snapX = (edges(columns, luma.width) { luma.sourceX(it) } + intArrayOf(region.left, region.right) +
				(aroundStroke?.let { intArrayOf(it.left, it.right) } ?: IntArray(0))).distinct().sorted().toIntArray(),
			snapY = (edges(bands, luma.height) { luma.sourceY(it) } + intArrayOf(region.top, region.bottom) +
				(aroundStroke?.let { intArrayOf(it.top, it.bottom) } ?: IntArray(0))).distinct().sorted().toIntArray(),
			aroundStroke = aroundStroke,
		)
	}

	/**
	 * Borders around what was rubbed, when the page gives no better idea of where the object ends.
	 *
	 * From the stroke each side goes on over content until blank space or a hard edge, as that is where a logo
	 * on a plain background or a label pasted onto the art ends. A side that finds neither within half a screen
	 * is in the middle of art, and stays near the stroke: the reader drags it from there, which beats starting
	 * at the far ends of the panel, or screens away.
	 */
	internal fun regionAroundStroke(
		luma: PageLuma,
		band: Span,
		column: Span,
		strokeLeft: Int,
		strokeTop: Int,
		strokeRight: Int,
		strokeBottom: Int,
		screenRows: Int,
	): SourceRegion {
		val startLeft = strokeLeft.coerceIn(column.start, column.end - 1)
		val startRight = strokeRight.coerceIn(startLeft + 1, column.end)
		val startTop = strokeTop.coerceIn(band.start, band.end - 1)
		val startBottom = strokeBottom.coerceIn(startTop + 1, band.end)
		val reach = (screenRows / 2).coerceAtLeast(1)
		val growX = ((startRight - startLeft) / STROKE_GROW_DIVISOR).coerceAtLeast(2)
		val growY = ((startBottom - startTop) / STROKE_GROW_DIVISOR).coerceAtLeast(2)
		var left = startLeft
		var right = startRight
		var top = startTop
		var bottom = startBottom
		// Smaller than a gutter: a logo's own lines are closer together than panels are, and it is its edge that is wanted
		val gap = (luma.width / 32).coerceAtLeast(2)
		// Twice: how far a side goes depends on the rows or columns the other sides have settled on
		repeat(2) {
			val rowBlank = { y: Int -> rowDeviation(luma, y, left, right) <= FLAT_ROW }
			val rowCut = { y: Int -> isHardCut(luma, y, left, right) }
			val newTop = growUntilEnd(startTop, -1, band.start, reach, gap, rowBlank, rowCut)
			val newBottom = growUntilEnd(startBottom, 1, band.end, reach, gap, rowBlank, rowCut)
			top = if (newTop == NOT_FOUND) (startTop - growY).coerceAtLeast(band.start) else newTop
			bottom = if (newBottom == NOT_FOUND) (startBottom + growY).coerceAtMost(band.end) else newBottom
			val columnBlank = { x: Int -> columnDeviation(luma, x, top, bottom) <= FLAT_ROW }
			val columnCut = { x: Int -> isHardColumnCut(luma, x, top, bottom) }
			val newLeft = growUntilEnd(startLeft, -1, column.start, reach, gap, columnBlank, columnCut)
			val newRight = growUntilEnd(startRight, 1, column.end, reach, gap, columnBlank, columnCut)
			left = if (newLeft == NOT_FOUND) (startLeft - growX).coerceAtLeast(column.start) else newLeft
			right = if (newRight == NOT_FOUND) (startRight + growX).coerceAtMost(column.end) else newRight
			// Art running from one edge of its column to the other, with no end above or below: no sides to find
			if (left == column.start && right == column.end && (newTop == NOT_FOUND || newBottom == NOT_FOUND)) {
				left = (startLeft - growX).coerceAtLeast(column.start)
				right = (startRight + growX).coerceAtMost(column.end)
			}
		}
		return SourceRegion(luma.sourceX(left), luma.sourceY(top), luma.sourceX(right), luma.sourceY(bottom))
	}

	/**
	 * Where content stops, going one way from [start]: at blank space at least [gap] deep, at a hard edge, or at
	 * [edge], which is as far as it may go. Less blank than that is a gap inside the object and is crossed.
	 *
	 * @param start for going up or left the first line that is in, and otherwise the first that is out
	 * @param isCut whether there is a hard edge between a line and the one before it
	 * @return [NOT_FOUND] if it goes on for [reach] lines with no end
	 */
	private inline fun growUntilEnd(
		start: Int,
		direction: Int,
		edge: Int,
		reach: Int,
		gap: Int,
		isBlank: (Int) -> Boolean,
		isCut: (Int) -> Boolean,
	): Int {
		var at = start
		var steps = 0
		while (steps < reach) {
			if (if (direction > 0) at >= edge else at <= edge) {
				return at
			}
			if (isCut(at)) {
				return at
			}
			val outside = if (direction > 0) at else at - 1
			if (isBlank(outside)) {
				var run = 0
				var line = outside
				while (run < gap && (if (direction > 0) line < edge else line >= edge) && isBlank(line)) {
					run++
					line += direction
				}
				val reachedEdge = if (direction > 0) line >= edge else line < edge
				if (run >= gap || reachedEdge) {
					return at
				}
				at += run * direction
				steps += run
			} else {
				at += direction
				steps++
			}
		}
		return NOT_FOUND
	}

	private const val NOT_FOUND = Int.MIN_VALUE

	/** [isHardCut] between two columns, over some rows only. */
	private fun isHardColumnCut(luma: PageLuma, x: Int, top: Int, bottom: Int): Boolean {
		if (x <= 0 || x >= luma.width || bottom <= top) {
			return false
		}
		var changed = 0
		for (y in top until bottom) {
			if (abs(luma[x, y] - luma[x - 1, y]) > CUT_PIXEL_CHANGE) changed++
		}
		return changed * 100 >= (bottom - top) * CUT_SHARE_PERCENT
	}

	/**
	 * A selection the reader set by hand, with the blank space around it: what [propose] adds to its own.
	 *
	 * Blank rows above and below go with it, a little way, and with nothing else on its rows it takes the
	 * whole width. Otherwise an image narrower than the page is painted over and leaves a stripe where it
	 * was, instead of the rows closing up. Only blank is ever added, never content.
	 */
	fun withBlankAround(luma: PageLuma, region: SourceRegion): SourceRegion {
		val left = luma.scaleX(region.left)
		val right = luma.scaleX(region.right).coerceAtLeast(left + 1).coerceAtMost(luma.width)
		val top = luma.scaleY(region.top)
		val bottom = luma.scaleY(region.bottom).coerceAtLeast(top + 1).coerceAtMost(luma.height)
		if (left >= right || top >= bottom) {
			return region
		}
		for (x in 0 until luma.width) {
			if ((x < left || x >= right) && columnDeviation(luma, x, top, bottom) > FLAT_ROW) {
				return region // something else is on these rows, and stays
			}
		}
		val blankAbove = flatRowsFrom(luma, top - 1, -1, 0, luma.width)
		val blankBelow = flatRowsFrom(luma, bottom, 1, 0, luma.width)
		val isSurrounded = (blankAbove > 0 || top == 0) && (blankBelow > 0 || bottom == luma.height)
		val rowPad = if (isSurrounded) {
			(luma.width / OVERFIT_SURROUNDED_DIVISOR).coerceAtLeast(3)
		} else {
			(luma.width / OVERFIT_ONE_SIDE_DIVISOR).coerceAtLeast(2)
		}
		return SourceRegion(
			left = 0,
			top = minOf(region.top, luma.sourceY(top - minOf(rowPad, blankAbove))),
			right = luma.sourceWidth,
			bottom = maxOf(region.bottom, luma.sourceY(bottom + minOf(rowPad, blankBelow))),
		)
	}

	/**
	 * What is actually in a selection, and how much blank was around it.
	 *
	 * A banner with its own background has nothing to trim, so all of it counts. Text or art sitting in blank
	 * space is what gets recognised, without the space, which could be anything on the next page.
	 */
	fun tighten(luma: PageLuma, region: SourceRegion): TightRegion {
		var left = luma.scaleX(region.left)
		var right = luma.scaleX(region.right).coerceAtLeast(left + 1).coerceAtMost(luma.width)
		var top = luma.scaleY(region.top)
		var bottom = luma.scaleY(region.bottom)
		while (top < bottom && isFlatRow(luma, top, left, right)) top++
		while (bottom > top && isFlatRow(luma, bottom - 1, left, right)) bottom--
		if (top >= bottom) {
			return TightRegion(region, 0f, 0f) // all blank: there is no object to be tight around
		}
		while (left < right - 1 && columnDeviation(luma, left, top, bottom) <= FLAT_ROW) left++
		while (right > left + 1 && columnDeviation(luma, right - 1, top, bottom) <= FLAT_ROW) right--
		val tight = SourceRegion(luma.sourceX(left), luma.sourceY(top), luma.sourceX(right), luma.sourceY(bottom))
		val height = tight.height.coerceAtLeast(1).toFloat()
		return TightRegion(tight, (tight.top - region.top) / height, (region.bottom - tight.bottom) / height)
	}

	/**
	 * Rows [top] until [bottom] of an object found on a page, grown by up to the given number of rows either
	 * way, as far as those rows are blank. The gap around an image differs from page to page, so what was
	 * worked out on one must not be applied blindly to another: it would cut into the art.
	 */
	fun growIntoBlank(
		luma: PageLuma,
		top: Int,
		bottom: Int,
		left: Int,
		right: Int,
		rowsAbove: Int,
		rowsBelow: Int,
	): IntRange {
		val slack = ((bottom - top) / LEFTOVER_SHARE_DIVISOR).coerceAtLeast(LEFTOVER_MIN_ROWS)
		val tailAbove = leftoverRows(luma, top - 1, -1, left, right, slack)
		val tailBelow = leftoverRows(luma, bottom, 1, left, right, slack)
		val newTop = top - tailAbove
		val newBottom = bottom + tailBelow
		// A row either way at the least: an edge found on a small copy can be that far from the real one
		val up = minOf(rowsAbove.coerceAtLeast(1), flatRowsFrom(luma, newTop - 1, -1, left, right))
		val down = minOf(rowsBelow.coerceAtLeast(1), flatRowsFrom(luma, newBottom, 1, left, right))
		return (newTop - up) until (newBottom + down)
	}

	/**
	 * How many rows of the object itself lie past where it was found: the sliver that would otherwise show.
	 *
	 * A match can land a row or two off, most of all on a plain background where nothing pins it down. What
	 * tells a leftover from the next panel is what comes after it: blank space or the edge of the page within
	 * a few rows. Content that just carries on is something else, and is left alone.
	 */
	private fun leftoverRows(luma: PageLuma, from: Int, direction: Int, left: Int, right: Int, slack: Int): Int {
		var rows = 0
		while (rows <= slack) {
			val y = from + rows * direction
			if (y !in 0 until luma.height) {
				return rows
			}
			if (isFlatRow(luma, y, left, right)) {
				val next = y + direction
				val isSettled = next !in 0 until luma.height || isFlatRow(luma, next, left, right)
				if (isSettled) return rows
			}
			rows++
		}
		return 0
	}

	/**
	 * Whether content [rows] in from a page's edge stops there, with blank space after it.
	 *
	 * @param fromTop counts from the top edge and looks downwards, otherwise from the bottom and upwards
	 * @param need how many blank rows make it blank space, rather than a gap between lines
	 * @param slack how far off that row the blank may begin
	 */
	internal fun isFollowedByBlank(luma: PageLuma, rows: Int, fromTop: Boolean, need: Int, slack: Int): Boolean {
		val direction = if (fromTop) 1 else -1
		val start = if (fromTop) rows else luma.height - 1 - rows
		for (shift in -slack..slack) {
			val first = start + shift * direction
			if (first !in 0 until luma.height) {
				continue
			}
			val blank = flatRowsFrom(luma, first, direction, 0, luma.width)
			val reachesEdge = first + blank * direction !in 0 until luma.height
			// The row before has to be content, or this says nothing about where the image stops
			val before = first - direction
			val isEdgeOfContent = before in 0 until luma.height && !isFlatRow(luma, before, 0, luma.width)
			if (isEdgeOfContent && (blank >= need || (reachesEdge && blank > 0))) {
				return true
			}
		}
		return false
	}

	/**
	 * Everything on a page with blank rows either side, each cut down to the columns it is on, the same way a
	 * selection gets cut down to what is in it by [tighten].
	 *
	 * @return left, top, right and bottom of each, in this preview's pixels
	 */
	fun contentBlocks(luma: PageLuma): List<IntArray> {
		val blocks = ArrayList<IntArray>()
		var top = 0
		while (top < luma.height) {
			if (isFlatRow(luma, top, 0, luma.width)) {
				top++
				continue
			}
			var bottom = top + 1
			while (bottom < luma.height && !isFlatRow(luma, bottom, 0, luma.width)) {
				bottom++
			}
			var left = 0
			var right = luma.width
			while (left < right - 1 && columnDeviation(luma, left, top, bottom) <= FLAT_ROW) left++
			while (right > left + 1 && columnDeviation(luma, right - 1, top, bottom) <= FLAT_ROW) right--
			blocks += intArrayOf(left, top, right, bottom)
			top = bottom
		}
		return blocks
	}

	/** What to remember about a selection. Everything recognising it again will go by. */
	fun describe(luma: PageLuma, region: SourceRegion): RememberedShape? {
		if (region.width <= 0 || region.height <= 0) {
			return null
		}
		val tight = tighten(luma, region)
		val left = luma.scaleX(tight.region.left)
		val top = luma.scaleY(tight.region.top)
		val right = luma.scaleX(tight.region.right)
		val bottom = luma.scaleY(tight.region.bottom)
		val isBigEnough = right - left >= ImageFingerprint.MIN_SIZE && bottom - top >= ImageFingerprint.MIN_SIZE
		val signature = if (isBigEnough) ImageSignature.of(luma, left, top, right, bottom) else null
		val isRecognisable = signature != null && !ImageSignature.isBlank(signature) && ImageSignature.hasEnoughContent(signature)
		// One that can only ever apply to this page is applied where it was made, borders and all
		val shape = if (isRecognisable) tight.region else region
		return RememberedShape(
			content = shape,
			fingerprint = if (isBigEnough) ImageFingerprint.of(luma, left, top, right, bottom) else null,
			signature = signature,
			colour = if (isBigEnough) ImageColour.of(luma, left, top, right, bottom) else null,
			aspect = shape.height.toFloat() / shape.width,
			xStart = shape.left.toFloat() / luma.sourceWidth,
			xEnd = shape.right.toFloat() / luma.sourceWidth,
			yStart = region.top.toFloat() / luma.sourceHeight,
			padTop = if (isRecognisable) tight.padTop else 0f,
			padBottom = if (isRecognisable) tight.padBottom else 0f,
			removesRows = region.width >= luma.sourceWidth * HiddenImageMatcher.FULL_WIDTH,
			isRecognisable = isRecognisable,
		)
	}

	/** Every separate stretch of content on a page, top to bottom. */
	internal fun contentBands(luma: PageLuma): List<SourceRegion> =
		findRowBands(luma, (luma.width / 12).coerceAtLeast(2)).map {
			SourceRegion(0, luma.sourceY(it.start), luma.sourceWidth, luma.sourceY(it.end))
		}

	/**
	 * The rest of an image found cut off on the next page, if this page ends the way it should for that.
	 *
	 * Too little of an image to recognise by itself, so this goes by what the other page says should be here:
	 * content of about the right height, right at the edge, with blank space on its other side. Anything else
	 * is the story carrying on, which is why finding no such thing is the usual answer.
	 *
	 * @param cut what was found on the neighbouring page
	 * @return what to remove from this page, or null if it does not end like that
	 */
	fun findRemainder(luma: PageLuma, cut: EdgeCut): SourceRegion? {
		// The neighbour cut off at its top means the rest of it ends this page, and the other way round
		val atBottom = cut.isAtTop
		val expected = (cut.missingShare * luma.sourceWidth * luma.height / luma.sourceHeight).toInt().coerceAtLeast(1)
		val slack = (expected / 2).coerceAtLeast(REMAINDER_MIN_SLACK_ROWS)
		var content = 0
		while (content < luma.height) {
			val y = if (atBottom) luma.height - 1 - content else content
			if (isFlatRow(luma, y, 0, luma.width)) break
			content++
		}
		if (content == 0 || content > expected + slack) {
			return null
		}
		val blank = if (atBottom) {
			flatRowsFrom(luma, luma.height - 1 - content, -1, 0, luma.width)
		} else {
			flatRowsFrom(luma, content, 1, 0, luma.width)
		}
		if (blank < (luma.width / OVERFIT_SURROUNDED_DIVISOR).coerceAtLeast(content)) {
			return null
		}
		val pad = minOf(blank, (luma.width / OVERFIT_ONE_SIDE_DIVISOR).coerceAtLeast(2))
		return if (atBottom) {
			SourceRegion(0, luma.sourceY(luma.height - content - pad), luma.sourceWidth, luma.sourceHeight)
		} else {
			SourceRegion(0, 0, luma.sourceWidth, luma.sourceY(content + pad))
		}
	}

	/** Whether every row outside [removed] is blank: then nothing is left worth showing a page for. */
	fun isBlankExcept(luma: PageLuma, removed: List<SourceRegion>): Boolean {
		var y = 0
		for (region in removed) {
			val top = luma.scaleY(region.top)
			while (y < top) {
				if (!isFlatRow(luma, y, 0, luma.width)) return false
				y++
			}
			y = maxOf(y, luma.scaleY(region.bottom))
		}
		while (y < luma.height) {
			if (!isFlatRow(luma, y, 0, luma.width)) return false
			y++
		}
		return true
	}

	/** Snaps [value] onto the nearest of [edges] within [tolerance], or leaves it alone. */
	fun snap(value: Float, edges: FloatArray, tolerance: Float): Float {
		var best = value
		var bestDistance = tolerance
		for (edge in edges) {
			val distance = abs(edge - value)
			if (distance <= bestDistance) {
				best = edge
				bestDistance = distance
			}
		}
		return best
	}

	internal class Span(val start: Int, val end: Int, val isPageStart: Boolean, val isPageEnd: Boolean)

	private fun findRowBands(luma: PageLuma, minGap: Int): List<Span> {
		val height = luma.height
		val isFlat = BooleanArray(height) { y -> rowDeviation(luma, y) <= FLAT_ROW }
		val spans = spansBetweenGaps(isFlat, minGap)
		return spans.flatMap { splitAtCuts(luma, it, minGap * 4) }
	}

	private fun findColumnSpans(luma: PageLuma, band: Span, minGap: Int): List<Span> {
		val isFlat = BooleanArray(luma.width) { x -> columnDeviation(luma, x, band.start, band.end) <= FLAT_ROW }
		return spansBetweenGaps(isFlat, minGap)
	}

	/** The stretches of content left once flat runs of at least [minGap] are taken out. */
	private fun spansBetweenGaps(isFlat: BooleanArray, minGap: Int): List<Span> {
		val size = isFlat.size
		val spans = ArrayList<Span>()
		var start = -1
		var position = 0
		while (position < size) {
			if (!isFlat[position]) {
				if (start < 0) start = position
				position++
				continue
			}
			var gapEnd = position
			while (gapEnd < size && isFlat[gapEnd]) gapEnd++
			if (start >= 0 && (gapEnd - position >= minGap || gapEnd == size)) {
				spans += Span(start, position, isPageStart = start == 0, isPageEnd = false)
				start = -1
			}
			position = gapEnd
		}
		if (start >= 0) {
			spans += Span(start, size, isPageStart = start == 0, isPageEnd = true)
		}
		// Nothing but flat colour: the whole page is all there is to select.
		return spans.ifEmpty { listOf(Span(0, size, isPageStart = true, isPageEnd = true)) }
	}

	private fun splitAtCuts(luma: PageLuma, span: Span, minHeight: Int): List<Span> {
		var start = span.start
		var result: ArrayList<Span>? = null
		var y = span.start + minHeight
		while (y <= span.end - minHeight) {
			if (isHardCut(luma, y)) {
				val list = result ?: ArrayList<Span>().also { result = it }
				list += Span(start, y, isPageStart = start == 0, isPageEnd = false)
				start = y
				y += minHeight
			} else {
				y++
			}
		}
		val list = result ?: return listOf(span)
		list += Span(start, span.end, isPageStart = false, isPageEnd = span.isPageEnd)
		return list
	}

	private fun rowDeviation(luma: PageLuma, y: Int, left: Int = 0, right: Int = luma.width): Int {
		val mean = luma.mean(left, y, right, y + 1)
		var total = 0
		for (x in left until right) {
			total += abs(luma[x, y] - mean)
		}
		return total / (right - left).coerceAtLeast(1)
	}

	private fun isFlatRow(luma: PageLuma, y: Int, left: Int, right: Int): Boolean {
		if (left != 0 || right != luma.width) {
			return rowDeviation(luma, y, left, right) <= FLAT_ROW
		}
		val rows = luma.flatRows ?: BooleanArray(luma.height) { rowDeviation(luma, it) <= FLAT_ROW }.also { luma.flatRows = it }
		return rows[y]
	}

	/** How many blank rows run from [from], going in [direction], until content or the edge of the page. */
	private fun flatRowsFrom(luma: PageLuma, from: Int, direction: Int, left: Int, right: Int): Int {
		var count = 0
		var y = from
		while (y in 0 until luma.height && isFlatRow(luma, y, left, right)) {
			count++
			y += direction
		}
		return count
	}

	private fun flatColumnsFrom(luma: PageLuma, from: Int, direction: Int, band: Span): Int {
		var count = 0
		var x = from
		while (x in 0 until luma.width && columnDeviation(luma, x, band.start, band.end) <= FLAT_ROW) {
			count++
			x += direction
		}
		return count
	}

	private fun columnDeviation(luma: PageLuma, x: Int, top: Int, bottom: Int): Int {
		val mean = luma.mean(x, top, x + 1, bottom)
		var total = 0
		for (y in top until bottom) {
			total += abs(luma[x, y] - mean)
		}
		return total / (bottom - top).coerceAtLeast(1)
	}

	/**
	 * Whether nearly every pixel changes between this row and the last. How much they change would not
	 * do: art has rows where half of them jump a long way, but only a cut between two images moves all of
	 * them at once.
	 */
	private fun isHardCut(luma: PageLuma, y: Int, left: Int = 0, right: Int = luma.width): Boolean {
		if (y <= 0 || y >= luma.height || right <= left) {
			return false
		}
		var changed = 0
		for (x in left until right) {
			if (abs(luma[x, y] - luma[x, y - 1]) > CUT_PIXEL_CHANGE) {
				changed++
			}
		}
		return changed * 100 >= (right - left) * CUT_SHARE_PERCENT
	}

	private fun overlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int =
		(minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0)

	private inline fun edges(spans: List<Span>, size: Int, toSource: (Int) -> Int): IntArray {
		val values = sortedSetOf(toSource(0), toSource(size))
		for (span in spans) {
			values += toSource(span.start)
			values += toSource(span.end)
		}
		return values.toIntArray()
	}
}
