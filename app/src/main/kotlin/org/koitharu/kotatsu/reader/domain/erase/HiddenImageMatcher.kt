package org.koitharu.kotatsu.reader.domain.erase

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One remembered image: what it looks like, and how it sits across a page's width. */
class HiddenImageRule(
	val id: Long,
	val fingerprint: ImageFingerprint,
	/** Height over width, in source pixels. */
	val aspect: Float,
	/** Where it starts and ends across the page, 0 to 1. Spanning both is a band across the page. */
	val xStart: Float,
	val xEnd: Float,
	/** A second opinion on every match. Absent for rules made before it existed. */
	val signature: ByteArray? = null,
	/**
	 * Set for an image too blank to recognise anywhere else: it applies to this one page only, at [yStart]
	 * (0 to 1 down the page), since all it could ever match elsewhere is other blank space.
	 */
	val pageId: Long? = null,
	val yStart: Float? = null,
	/** Blank space to take with it when there is some, as a share of its height. See [TightRegion]. */
	val padTop: Float = 0f,
	val padBottom: Float = 0f,
	/**
	 * Whether the rows it is on go away, closing the page up, rather than it being painted over. Says what
	 * the selection was: it says nothing about the content, which is what the other fields describe. Absent
	 * on rules made before they were tight around the content, whose width says the same thing.
	 */
	val removesRows: Boolean? = null,
	/** See [ImageColour]. Absent for rules made before it existed, which go by brightness alone. */
	val colour: ByteArray? = null,
	/** See [RuleDetail]. Absent for rules made before it existed, which are only found the other ways. */
	val keypoints: ByteArray? = null,
) {

	/** Where its ink is, and whether it has the detail to be recognised by its shape. Worked out once, as every page asks. */
	internal val inkBox: ImageSignature.InkBox? by lazy(LazyThreadSafetyMode.PUBLICATION) {
		signature?.takeIf { pageId == null && fingerprint.isDistinctive }?.let { ImageSignature.inkBoxOf(it) }
	}

	/**
	 * See [ImageSignature.isOutline]. Goes from the page it was erased from, and on any other is only looked for as a whole
	 * file. Only something short and wide, as the rim of a bubble is: a plain page of credits has as little to it, but it is
	 * that page.
	 */
	internal val isOutline: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
		pageId == null && signature != null && aspect <= HiddenImageMatcher.MAX_OUTLINE_ASPECT && ImageSignature.isOutline(signature)
	}

	/** What the search for it at other sizes goes by, worked out once, as every page and every size asks. */
	internal val shape: HiddenImageMatcher.RuleShape? by lazy(LazyThreadSafetyMode.PUBLICATION) {
		signature?.let { HiddenImageMatcher.RuleShape(it, inkBox?.isDetailed == true) }
	}

	/**
	 * What finding it by its points goes by. Never for the rim of a bubble or one curve: however far its points reach,
	 * they say as much about every other one. What the signature takes for one, at its size, may have far more to it at a
	 * larger one: nearly all of what it turned away that was not a bubble was art, titles and logos, never found by anything.
	 */
	internal val detail: RuleDetail? by lazy(LazyThreadSafetyMode.PUBLICATION) {
		if (pageId != null || signature == null) {
			null
		} else {
			val isCurve = isOutline || ImageSignature.isOneCurve(signature)
			keypoints?.let { runCatching { RuleDetail.decode(it) }.getOrNull() }?.takeIf { !isCurve || it.several >= RuleDetail.MIN_SEVERAL }
		}
	}
}

/** All it takes to remember what is in a selection on a page. See [EraseProposer.describe]. */
class RememberedShape(
	/** What is really there, without blank space around it, in source pixels. */
	val content: SourceRegion,
	val fingerprint: ImageFingerprint?,
	val signature: ByteArray?,
	val colour: ByteArray?,
	val aspect: Float,
	val xStart: Float,
	val xEnd: Float,
	val yStart: Float,
	val padTop: Float,
	val padBottom: Float,
	val removesRows: Boolean,
	val isRecognisable: Boolean,
)

/** A rectangle in source pixels of the page it was found on. */
data class SourceRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
	val width: Int get() = right - left
	val height: Int get() = bottom - top
}

/**
 * A remembered image found cut off by the edge of a page, which means the rest of it is on the page next door.
 *
 * @param isAtTop whether it hangs off the top of this page, so the rest ends the page before
 * @param missingShare how much of it, not counting blank space, is not on this page: rows over page width
 */
data class EdgeCut(val ruleId: Long, val isAtTop: Boolean, val missingShare: Float)

/** What has to happen to one page, given what was found on it. */
sealed interface PageResolution {

	val ruleIds: Set<Long>

	val cuts: List<EdgeCut>

	data object Visible : PageResolution {
		override val ruleIds: Set<Long> get() = emptySet()
		override val cuts: List<EdgeCut> get() = emptyList()
	}

	data class Hidden(
		override val ruleIds: Set<Long>,
		override val cuts: List<EdgeCut> = emptyList(),
	) : PageResolution

	data class Masked(
		override val ruleIds: Set<Long>,
		val mask: PageMask,
		override val cuts: List<EdgeCut> = emptyList(),
	) : PageResolution {
		val bands: List<SourceRegion> get() = mask.bands
		val patches: List<SourceRegion> get() = mask.patches
	}
}

/**
 * Looks for remembered images on a page.
 *
 * It slides a window down the page rather than comparing against detected panels: scanlators slice long
 * strips wherever they like, so a banner lands at a different height every chapter and its detected
 * edges differ by a few rows. Sliding needs nothing to line up, and the integral image behind [PageLuma]
 * makes each position cheap. Horizontal position is not searched, since a watermark in the corner of one
 * page stays in the corner of the next.
 */
object HiddenImageMatcher {

	/**
	 * Bumped whenever this finds something it would not have before, because answers saved for a page say
	 * nothing about what a better matcher would find on it.
	 */
	const val VERSION = 16

	/** A cell this far from an image's background has some of the image in it. */
	private const val CONTENT_DISTANCE = 24

	/** How much of what is in an image has to be on the page to recognise it cut off by the page's edge. */
	private const val MIN_EDGE_CONTENT_PERCENT = 25
	private const val MIN_EDGE_CONTENT_CELLS = 24
	/**
	 * Measured, on 147 pages of real long strips against a real cut off banner: the true page scored 27 to 29
	 * and the closest wrong one 41. Cutting real panels out of a story is the far worse mistake, so this sits
	 * nearer the true page. An image with less of it left than this allows just shows, and can be erased again.
	 */
	private const val MAX_EDGE_DIFFERENCE = 33

	/** On average, brighter or darker than the rule: more than this is the same image in another colour. */
	private const val MAX_EDGE_SHIFT = 10

	/**
	 * How much of it may be far off. An average says little about a change all in one place: an ad with a mascot added
	 * to it, or other lettering, is another image however close the rest of it is.
	 */
	private const val EDGE_FAR_CELL = 48
	private const val MAX_EDGE_FAR_PERCENT = 8

	/**
	 * With nearly all of it on the page there is plenty to compare, and it is no longer really cut off: the same
	 * ad at another size with other lettering measured 6, and the same banner cut off by the page 5.
	 */
	private const val NEARLY_ALL_PERCENT = 85
	private const val MAX_EDGE_FAR_PERCENT_NEARLY_ALL = 5

	/** A line of text on a plain background is mostly background, which agrees with any other line of text. */
	private const val MAX_EDGE_FAR_PERCENT_SPARSE = 4
	private const val MIN_EDGE_CORRELATION = 84

	/** So little of an image says less about it: a title with 24% of it on the page measured 85 against a nearly blank strip. */
	private const val MAX_SLIVER_PERCENT = 40
	private const val MIN_SLIVER_CORRELATION = 90

	internal const val MAX_OUTLINE_ASPECT = 0.7f

	/** Taller than this over its width is not a banner, and is never looked for cut off. */
	private const val MAX_EDGE_ASPECT = 0.7f

	/** Blank rows that have to follow a cut off image, as a share of page width, and how far off they may start. */
	private const val EDGE_BLANK_DIVISOR = 24
	private const val EDGE_BLANK_SLACK_ROWS = 2

	/** Found by its points hanging off the page by less than this share of it, it is all here but for rounding. */
	private const val MIN_MISSING_SHARE = 0.03

	/** A window whose brightness is this far off cannot be the same image, whatever its hash says. */
	private const val MEAN_TOLERANCE = 24

	/** A band covering this much of a page's width is treated as the whole width. */
	const val FULL_WIDTH = 0.92f

	/** Covering this much of a page leaves nothing worth showing. */
	private const val FULL_PAGE = 0.9f

	/** A quarter of a hash cell per step, so that the true alignment is never far from a position tried. */
	private const val WINDOW_STEPS = 32
	private const val COARSE_SLACK = 2
	private const val SETTLE_ROWS = 2
	private const val SETTLE_SIZE = 1
	private const val SETTLE_COLUMNS = 1
	private const val UNKNOWN = -1

	/**
	 * How far a block's proportions may be from a rule's to be the same image at another size. Measured: a real
	 * banner across five real pages was within 6%, however wide its page, and a faint logo within 13%, as its edges
	 * are less clear.
	 */
	private const val MAX_BLOCK_ASPECT_DIFFERENCE = 0.14f

	/**
	 * A file of its own has proportions it was made with, not ones worked out from where a slice happened to fall,
	 * and real copies of an ad at three sizes were within 1%.
	 */
	private const val MAX_INSERT_ASPECT_DIFFERENCE = 0.04f

	/** This much of a file across and down is the file. */
	private const val MIN_INSERT_FILL_PERCENT = 85

	/**
	 * The sizes to look for an image at, as percentages of the size a rule would have on this page. Sliding a window
	 * takes one size, and looking at blocks takes blank space around it. A banner on a page of another width, right
	 * up against the art, gets neither. A logo erased small can be served much larger, so this goes well past either
	 * way, with steps of a share of the size so that all of them are as close together.
	 */
	private const val MIN_ANY_SIZE_PERCENT = 50
	private const val MAX_ANY_SIZE_PERCENT = 180
	private const val ANY_SIZE_STEP_DIVISOR = 25

	/**
	 * Comparing the brightness of each row of a window is a fraction of the work of comparing the whole of it, and
	 * tells most of what is not there, so it goes first. These are well under what the real thing comes to.
	 */
	private const val MIN_ROW_PROFILE_CORRELATION = 0.6
	private const val ROW_SEARCH_STEP = 2

	/** Fitting moves a window's edges far enough to make up for the size being this far off, so no more are tried. */
	private const val ANY_SIZE_SEARCH_DIVISOR = 16
	private const val MIN_ANY_SIZE_FIRST_CORRELATION = 0.6

	/**
	 * Only the very image counts, and that comes to the same brightness to within [ImageSignature] allows, with a little
	 * more for a window not yet lined up. On most of a page nothing else gets past this, which makes it most of the saving.
	 */
	private const val MAX_ANY_SIZE_MEAN_DIFFERENCE = 24

	/**
	 * The average a window's rows may be off by, given up on the moment it cannot come in under, which on most of a page
	 * is after a row or two. Generous, as a window at a size between two tried is not lined up yet.
	 */
	private const val MAX_ANY_SIZE_ROW_DIFFERENCE = 40

	/** What a window lined up cheaply has to reach to be worth lining up properly: well under what a real one does. */
	private const val MIN_ANY_SIZE_ROUGH_CORRELATION = 0.85

	/** The same image varies as much in brightness from row to row. A window far flatter or busier is something else. */
	private const val MAX_ROW_SPREAD_RATIO = 4

	/** The same image at another size cut off by the page's edge has less to be told by, so a little more is asked of it. */
	private const val MIN_SCALED_EDGE_CORRELATION = 88

	/** And of one that is not a banner, as much as of an exact copy. */
	private const val MIN_COPY_EDGE_CORRELATION = 95
	private const val MAX_COPY_EDGE_FAR_PERCENT = 2

	/** Its edges on a page are only known to about this share of its size, and a cell off is a long way off. */
	private const val BLOCK_FIT_DIVISOR = 40

	/**
	 * Below this at the first try, however far off the edges are, it is something else. The real banner measured
	 * 0.65 and up there, on its way to 0.98, and most of what else is on a page is at about zero.
	 */
	private const val MIN_BLOCK_FIRST_CORRELATION = 0.3

	/** For anything standing on its own that may be an exact copy, see [ImageSignature.isExactCopy]. */
	private const val MAX_COPY_ASPECT_DIFFERENCE = 0.1f
	private const val MIN_COPY_FIRST_CORRELATION = 0.5
	private const val MIN_ROWS_TO_JUDGE_UNFITTED = 24

	/** Columns a rectangle may hang off the side of a page by, being blank margin the page has no room for. */
	private const val BLOCK_MAX_OVERHANG = 3
	private const val BLOCK_ROW_OVERHANG_DIVISOR = 8

	/**
	 * Always on one thread. Measured on a phone with 500 rules: 160 ms a page on average, as the system gives a busy thread a
	 * fast core, against 473 ms on four, with the work staying on slow cores all the while and taking more processor time.
	 *
	 * @param rules those that apply to this page: everything, less other pages' single-page rules
	 */
	fun resolve(luma: PageLuma, rules: List<HiddenImageRule>): PageResolution {
		if (rules.isEmpty()) {
			return PageResolution.Visible
		}
		val points = findByPoints(luma, rules, luma.detail?.let(KeypointMatcher::keypointsOf))
		return conclude(luma, look(luma, rules, points))
	}

	/**
	 * Which rules are on this page going by their points, by rule id. Found this way, an image needs no other looking for,
	 * so with most of what comes back found here the rest of the work on a page goes down as well.
	 */
	private fun findByPoints(luma: PageLuma, rules: List<HiddenImageRule>, keypoints: Keypoints?): Map<Long, List<KeypointMatch>> {
		val detail = luma.detail
		if (detail == null || keypoints == null) {
			return emptyMap()
		}
		val withPoints = rules.filter { it.detail != null }
		if (withPoints.isEmpty()) {
			return emptyMap()
		}
		val found = HashMap<Long, MutableList<KeypointMatch>>()
		for (match in KeypointMatcher.find(detail, indexOf(withPoints), keypoints)) {
			found.getOrPut(withPoints[match.ruleIndex].id) { ArrayList() } += match
		}
		return found
	}

	/** Takes as long as a dozen pages' lookups, and rules rarely change. Two, as the page after a new rule only asks about that. */
	@Volatile
	private var lastIndex: Pair<List<HiddenImageRule>, KeypointIndex>? = null

	@Volatile
	private var largestIndex: Pair<List<HiddenImageRule>, KeypointIndex>? = null

	private fun indexOf(rules: List<HiddenImageRule>): KeypointIndex {
		lastIndex?.let { (key, index) -> if (key == rules) return index }
		largestIndex?.let { (key, index) -> if (key == rules) return index }
		val made = rules to KeypointIndex(rules.map { it.detail!! })
		lastIndex = made
		if (rules.size >= (largestIndex?.first?.size ?: 0)) {
			largestIndex = made
		}
		return made.second
	}

	/** What rules were found on a page, in the order they were looked for. */
	private class Findings {
		val matched = HashSet<Long>()
		val bands = ArrayList<SourceRegion>()
		val patches = ArrayList<SourceRegion>()
		val cuts = ArrayList<EdgeCut>()
	}

	private fun look(luma: PageLuma, rules: List<HiddenImageRule>, byPoints: Map<Long, List<KeypointMatch>>): Findings {
		val scratch = Scratch(luma.height)
		val findings = Findings()
		val matched = findings.matched
		val bands = findings.bands
		val patches = findings.patches
		val cuts = findings.cuts
		for (rule in rules) {
			val isBand = rule.removesRows ?: (rule.xEnd - rule.xStart >= FULL_WIDTH)
			var isWholeOnPage = false
			val onFoundBetween = { top: Int, bottom: Int, patchLeft: Int, patchRight: Int ->
				matched += rule.id
				val region = SourceRegion(
					left = if (isBand) 0 else patchLeft,
					top = top,
					right = if (isBand) luma.sourceWidth else patchRight,
					bottom = bottom,
				)
				if (isBand) bands += region else patches += region
			}
			val onFound = { top: Int, bottom: Int ->
				onFoundBetween(top, bottom, (rule.xStart * luma.sourceWidth).toInt(), (rule.xEnd * luma.sourceWidth).toInt())
			}
			if (rule.pageId != null) {
				val top = ((rule.yStart ?: 0f) * luma.sourceHeight).toInt()
				val height = ((rule.xEnd - rule.xStart) * luma.sourceWidth * rule.aspect).toInt()
				onFound(top, (top + height).coerceAtMost(luma.sourceHeight))
			} else {
				val left = (rule.xStart * luma.width).roundToInt().coerceIn(0, luma.width - 1)
				val right = (rule.xEnd * luma.width).roundToInt().coerceIn(left + 1, luma.width)
				// Rows that go away have to be blank all the way across, whatever the image itself spans
				val growLeft = if (isBand) 0 else left
				val growRight = if (isBand) luma.width else right
				val detail = luma.detail
				val foundByPoints = byPoints[rule.id]
				if (foundByPoints != null && detail != null) {
					isWholeOnPage = true
					for (match in foundByPoints) {
						val foundTop = (match.top.coerceAtLeast(0.0) * luma.height / detail.height).toInt().coerceIn(0, luma.height - 1)
						val foundBottom = ceil(match.bottom.coerceAtMost(detail.height.toDouble()) * luma.height / detail.height).toInt().coerceIn(foundTop + 1, luma.height)
						val foundLeft = (match.left.coerceAtLeast(0.0) * luma.width / detail.width).toInt().coerceIn(0, luma.width - 1)
						val foundRight = ceil(match.right.coerceAtMost(detail.width.toDouble()) * luma.width / detail.width).toInt().coerceIn(foundLeft + 1, luma.width)
						val rows = EraseProposer.growIntoBlank(
							luma = luma,
							top = foundTop,
							bottom = foundBottom,
							left = if (isBand) 0 else foundLeft,
							right = if (isBand) luma.width else foundRight,
							rowsAbove = (rule.padTop * (foundBottom - foundTop)).roundToInt(),
							rowsBelow = (rule.padBottom * (foundBottom - foundTop)).roundToInt(),
						)
						onFoundBetween(luma.sourceY(rows.first), luma.sourceY(rows.last + 1), luma.sourceX(foundLeft), luma.sourceX(foundRight))
						// The rest of it is on the page next door, and the reader is told to go and take that too
						val missingAbove = -match.top
						val missingBelow = match.bottom - detail.height
						val needed = (match.bottom - match.top) * MIN_MISSING_SHARE
						if (missingAbove > needed) cuts += EdgeCut(rule.id, true, (missingAbove / detail.width).toFloat())
						if (missingBelow > needed) cuts += EdgeCut(rule.id, false, (missingBelow / detail.width).toFloat())
					}
				}
				val ruleDetail = rule.detail
				// A rule with points has its colours at a larger size, and so does a page read for them
				val hasColoursAt = { windowLeft: Int, windowTop: Int, windowRight: Int ->
					detail == null || ruleDetail == null || KeypointMatcher.isSameColourAt(
						page = detail,
						rule = ruleDetail,
						left = windowLeft.toDouble() * detail.width / luma.width,
						top = windowTop.toDouble() * detail.height / luma.height,
						right = windowRight.toDouble() * detail.width / luma.width,
					)
				}
				if (!isWholeOnPage && isSameFile(luma, rule) && hasColoursAt(0, 0, luma.width)) {
					isWholeOnPage = true
					onFoundBetween(0, luma.sourceHeight, 0, luma.sourceWidth)
				}
				// Looking for it at other sizes or cut off is what its points are for, and they find more of it. Doing both, every
				// rule on every page, was most of the time a page took on a slow phone, for next to nothing that points had not found
				val isFoundByPointsIfAtAll = detail != null && ruleDetail != null
				if (!rule.isOutline && !isWholeOnPage) find(luma, rule, scratch) { top, bottom ->
					if (!ImageColour.isSame(rule.colour, luma, left, right, top, bottom - top) || !hasColoursAt(left, top, right)) {
						return@find
					}
					isWholeOnPage = true
					val rows = EraseProposer.growIntoBlank(
						luma = luma,
						top = top,
						bottom = bottom,
						left = growLeft,
						right = growRight,
						rowsAbove = (rule.padTop * (bottom - top)).roundToInt(),
						rowsBelow = (rule.padBottom * (bottom - top)).roundToInt(),
					)
					onFound(luma.sourceY(rows.first), luma.sourceY(rows.last + 1))
				}
				val onFoundAtSize = { top: Int, bottom: Int, foundLeft: Int, foundRight: Int ->
					if (ImageColour.isSame(rule.colour, luma, foundLeft, foundRight, top, bottom - top) && hasColoursAt(foundLeft, top, foundRight)) {
						isWholeOnPage = true
						val rows = EraseProposer.growIntoBlank(
							luma = luma,
							top = top,
							bottom = bottom,
							left = if (isBand) 0 else foundLeft,
							right = if (isBand) luma.width else foundRight,
							rowsAbove = (rule.padTop * (bottom - top)).roundToInt(),
							rowsBelow = (rule.padBottom * (bottom - top)).roundToInt(),
						)
						onFoundBetween(luma.sourceY(rows.first), luma.sourceY(rows.last + 1), luma.sourceX(foundLeft), luma.sourceX(foundRight))
					}
				}
				if (!isFoundByPointsIfAtAll && !isWholeOnPage) findAtAnotherSize(luma, rule, onFoundAtSize)
				if (!isFoundByPointsIfAtAll && !isWholeOnPage && !rule.isOutline) bestAtAnySize(luma, rule)?.let { onFoundAtSize(it.top, it.bottom, it.left, it.right) }
				// All of it is here, so nothing about it is cut off. Without this, an image that nearly fills a
				// page also looks like one hanging off both of its ends.
				if (!isFoundByPointsIfAtAll && !isWholeOnPage && !rule.isOutline) findCutOff(luma, rule, left, right) { top, bottom, isAtTop, missingRows ->
					if (!hasColoursAt(left, if (isAtTop) top - missingRows else top, right)) {
						return@findCutOff
					}
					if (missingRows > 0) {
						val missing = missingRows.toFloat() * luma.sourceHeight / luma.height
						cuts += EdgeCut(rule.id, isAtTop, missing / luma.sourceWidth)
					}
					val rows = EraseProposer.growIntoBlank(
						luma = luma,
						top = top,
						bottom = bottom,
						left = growLeft,
						right = growRight,
						rowsAbove = (rule.padTop * (bottom - top)).roundToInt(),
						rowsBelow = (rule.padBottom * (bottom - top)).roundToInt(),
					)
					onFound(luma.sourceY(rows.first), luma.sourceY(rows.last + 1))
				}
			}
		}
		return findings
	}

	private fun conclude(luma: PageLuma, findings: Findings): PageResolution {
		val matched = findings.matched
		val patches = findings.patches
		val cuts = findings.cuts
		if (matched.isEmpty()) {
			return PageResolution.Visible
		}
		val merged = mergeBands(findings.bands)
		val isNothingLeft = merged.sumOf { it.height } >= luma.sourceHeight * FULL_PAGE ||
			(merged.isNotEmpty() && patches.isEmpty() && EraseProposer.isBlankExcept(luma, merged))
		return if (isNothingLeft) {
			PageResolution.Hidden(matched, cuts)
		} else {
			PageResolution.Masked(matched, PageMask(luma.sourceWidth, luma.sourceHeight, merged, patches), cuts)
		}
	}

	/** What one rule learns about a page's rows while being looked for, so that no row is worked out twice. */
	private class Scratch(rows: Int) {
		val distances = IntArray(rows)
		val settled = BooleanArray(rows)

		fun reset() {
			distances.fill(UNKNOWN)
			settled.fill(false)
		}
	}

	private inline fun find(luma: PageLuma, rule: HiddenImageRule, scratch: Scratch, onFound: (top: Int, bottom: Int) -> Unit) {
		// Rounded, not cut down: 3 columns of 100, kept as 0.03, comes back as 2.99, and a column off is a long
		// way off for sharp text. It kept an image from being found on the very page it was erased from.
		val left = (rule.xStart * luma.width).roundToInt().coerceIn(0, luma.width)
		val right = (rule.xEnd * luma.width).roundToInt().coerceIn(left, luma.width)
		val width = right - left
		if (width < ImageFingerprint.MIN_SIZE) {
			return
		}
		// The rule's width in source pixels, times its aspect, back into this preview's rows
		val sourceWidth = width.toFloat() * luma.sourceWidth / luma.width
		val height = (sourceWidth * rule.aspect * luma.height / luma.sourceHeight)
			.roundToInt()
			.coerceAtLeast(ImageFingerprint.MIN_SIZE)
		// A rule slightly taller than this page is rounding, not a different image. Far taller cannot fit.
		if (height > luma.height + luma.height / 16 + 1) {
			return
		}
		val window = height.coerceAtMost(luma.height)
		val step = (window / WINDOW_STEPS).coerceAtLeast(1)
		val signature = rule.signature
		val tolerance = if (signature != null) rule.fingerprint.searchTolerance else rule.fingerprint.matchTolerance
		val isPlain = !rule.fingerprint.isDistinctive
		val coarseLimit = tolerance * COARSE_SLACK
		val probe = ImageFingerprint.Probe(luma, left, right, window)
		scratch.reset()
		var top = 0
		while (top + window <= luma.height) {
			val coarse = distanceAt(luma, rule, probe, scratch, left, top, right, window, coarseLimit)
			// Between two positions the real alignment can be half a step away from both, and even that
			// flips bits. So a loose first pass only says "worth a closer look": the row-by-row look below
			// is what has to meet the tolerance. Judging the coarse pass strictly misses images outright.
			if (coarse > coarseLimit) {
				top += step
				continue
			}
			var distance = coarse
			var bestTop = top
			var offset = (top - step).coerceAtLeast(0)
			val limit = (top + step * 2).coerceAtMost(luma.height - window)
			while (offset <= limit) {
				val candidate = distanceAt(luma, rule, probe, scratch, left, offset, right, window, coarseLimit)
				if (candidate < distance) {
					distance = candidate
					bestTop = offset
				}
				offset++
			}
			// The hash only says roughly where. See settle for where it really is.
			val settled = if (distance > tolerance) {
				null
			} else if (signature == null) {
				bestTop until bestTop + window
			} else if (scratch.settled[bestTop]) {
				// Neighbouring positions keep landing on the same best row, and it came to nothing the last time
				null
			} else {
				scratch.settled[bestTop] = true
				settle(luma, signature, left, right, bestTop, window, isPlain)
					?: if (rule.inkBox?.isDetailed != true) null else settleShape(luma, signature, left, right, bestTop, window)
			}
			if (settled == null) {
				top += step
				continue
			}
			onFound(settled.first, settled.last + 1)
			top = settled.last + 1
		}
	}

	/**
	 * The rows of an image near where the hash found it, going by the signature, which has the final say.
	 *
	 * A rule keeps its shape as fractions of a page, and those come back a row or a column off either way when
	 * turned back into a small copy's pixels. On sharp text, one off is a long way off: the same image can fail
	 * to match on the very page it was erased from. No rounding fixes that, so the nearby sizes and positions
	 * are all tried, and the one that fits best is where its edges really are.
	 */
	private fun settle(
		luma: PageLuma,
		signature: ByteArray,
		left: Int,
		right: Int,
		top: Int,
		window: Int,
		isPlain: Boolean,
	): IntRange? {
		// Anything over what could pass is out right away. Were it the best fit it would fail, so this changes nothing
		var bestDifference = ImageSignature.maxMeanDifference(isPlain) + 1
		var bestLeft = -1
		var bestRight = -1
		var bestTop = -1
		var bestSize = -1
		forEachNearbyFit(luma, left, right, top, window) { l, y, r, size ->
			val difference = ImageSignature.differenceWithin(signature, luma, l, y, r, y + size, bestDifference)
			if (difference < bestDifference) {
				bestDifference = difference
				bestLeft = l
				bestRight = r
				bestTop = y
				bestSize = size
			}
		}
		if (bestSize < 0) {
			return null
		}
		// Only the best fit gets judged. Asking of every one whether it passes would let a poor alignment through,
		// since the worst cell allowed grows with the average, and being misaligned raises the average.
		val here = ImageSignature.of(luma, bestLeft, bestTop, bestRight, bestTop + bestSize)
		return (bestTop until bestTop + bestSize).takeIf { ImageSignature.isSameHere(signature, here, isPlain) }
	}

	/**
	 * The same search for the same image in other colours, where it has to find the best fit by shape, since going
	 * by brightness all of its positions are out of the question. Only reached where the hash already agrees, and
	 * [settle] did not, which on real pages is a handful of times in a chapter.
	 */
	private fun settleShape(
		luma: PageLuma,
		signature: ByteArray,
		left: Int,
		right: Int,
		top: Int,
		window: Int,
	): IntRange? {
		var bestCorrelation = Double.NEGATIVE_INFINITY
		var best: ByteArray? = null
		var bestTop = -1
		var bestSize = -1
		forEachNearbyFit(luma, left, right, top, window) { l, y, r, size ->
			val here = ImageSignature.of(luma, l, y, r, y + size)
			val correlation = ImageSignature.correlation(signature, here)
			if (correlation > bestCorrelation) {
				bestCorrelation = correlation
				best = here
				bestTop = y
				bestSize = size
			}
		}
		val here = best ?: return null
		return (bestTop until bestTop + bestSize).takeIf { ImageSignature.isSameShape(signature, here) }
	}

	/**
	 * Finds the same image on a page that has it at another size.
	 *
	 * Sliding a window takes a rule's size as a share of the page's width, which holds until a site serves one
	 * banner on pages 800 wide and another 690, with its margins different as well. All it keeps then is its
	 * proportions. So this looks at each thing on the page standing on its own, and fits the rule's ink over any
	 * of the same proportions. Brightness has no say: it goes by [ImageSignature.isSameShape], and the only way to
	 * pass is to be the very same image. An image with too little detail for that gets one other way to pass, if
	 * what is on the page is a file all of its own, see [ImageSignature.isSameInsert].
	 *
	 * @param onFound rows and columns on this page
	 */
	private inline fun findAtAnotherSize(
		luma: PageLuma,
		rule: HiddenImageRule,
		onFound: (top: Int, bottom: Int, left: Int, right: Int) -> Unit,
	) {
		val signature = rule.signature ?: return
		val ink = rule.inkBox ?: return
		val grid = ImageSignature.GRID
		// What counts as ink is a guess on something faint, which can make it look far smaller than the rectangle it
		// was cut down to, so the rectangle itself gets a try as well
		val whole = ImageSignature.InkBox(0, 0, grid, grid, ink.background, ink.isDetailed)
		val isInkAll = ink.columns == grid && ink.rows == grid
		for (block in shapeBlocks(luma)) {
			val fit = fitBlock(luma, rule, signature, block, ink) ?: if (isInkAll) null else fitBlock(luma, rule, signature, block, whole)
			if (fit != null) {
				onFound(fit.top, fit.bottom, fit.left, fit.right)
			}
		}
	}

	/**
	 * Whether a whole page is another copy of a rule that was a whole page, however it comes apart into blocks: a flat
	 * stretch across a file splits it in two, and an image with a lot of plain space does not fill either.
	 */
	private fun isSameFile(luma: PageLuma, rule: HiddenImageRule): Boolean {
		val signature = rule.signature ?: return false
		if (rule.pageId != null || rule.xEnd - rule.xStart < MIN_INSERT_FILL_PERCENT / 100f || rule.aspect > MAX_EDGE_ASPECT) {
			return false
		}
		val aspect = luma.sourceHeight.toFloat() / luma.sourceWidth
		if (abs(aspect / rule.aspect - 1f) > MAX_COPY_ASPECT_DIFFERENCE) {
			return false
		}
		val here = luma.wholeSignature ?: ImageSignature.of(luma, 0, 0, luma.width, luma.height).also { luma.wholeSignature = it }
		return ImageSignature.isSameFile(signature, here) && ImageColour.isSame(rule.colour, luma, 0, luma.width, 0, luma.height)
	}

	/** The fit of a rule on something standing on its own on a page, with [ink] over it, if that is the same image. */
	private fun fitBlock(luma: PageLuma, rule: HiddenImageRule, signature: ByteArray, block: ShapeBlock, ink: ImageSignature.InkBox): Fit? {
		val aspectDifference = abs(block.aspect / (rule.aspect * ink.rows / ink.columns) - 1f)
		val isInsert = rule.aspect <= MAX_EDGE_ASPECT && block.fillsPage && aspectDifference <= MAX_INSERT_ASPECT_DIFFERENCE
		val isShape = !rule.isOutline && ink.isDetailed && block.isDetailed && aspectDifference <= MAX_BLOCK_ASPECT_DIFFERENCE
		val isCopy = !rule.isOutline && aspectDifference <= MAX_COPY_ASPECT_DIFFERENCE
		if (!isInsert && !isShape && !isCopy) return null
		// On something faint the commonest brightness says little, and an exact copy has more to go by anyway
		val isSameBackground = ImageSignature.isSameBackground(ink.background, block.background)
		if (!isSameBackground && !isCopy) return null
		// The rectangle the rule would have, with its ink over this block
		val cellWidth = (block.right - block.left).toFloat() / ink.columns
		val cellHeight = (block.bottom - block.top).toFloat() / ink.rows
		val top = (block.top - ink.top * cellHeight).roundToInt()
		val bottom = (block.bottom + (ImageSignature.GRID - ink.bottom) * cellHeight).roundToInt()
		val left = (block.left - ink.left * cellWidth).roundToInt()
		val right = (block.right + (ImageSignature.GRID - ink.right) * cellWidth).roundToInt()
		// A rectangle is cut down to what is in it, but not always the whole way, and what is left of its margin
		// has no room to be on a page with less of one
		val rowSlack = (bottom - top) / BLOCK_ROW_OVERHANG_DIVISOR
		if (top < -rowSlack || bottom > luma.height + rowSlack || left < -BLOCK_MAX_OVERHANG || right > luma.width + BLOCK_MAX_OVERHANG) return null
		val first = ImageSignature.of(luma, left.coerceAtLeast(0), top.coerceAtLeast(0), right.coerceAtMost(luma.width), bottom.coerceAtMost(luma.height))
		// A thin strip says nothing until it is lined up: a real one measured 0.07 here, and 0.98 once it was
		val isThin = bottom - top < MIN_ROWS_TO_JUDGE_UNFITTED
		val minFirst = if (isSameBackground && (isInsert || isShape)) MIN_BLOCK_FIRST_CORRELATION else MIN_COPY_FIRST_CORRELATION
		if (!isThin && ImageSignature.correlation(signature, first) < minFirst) return null
		val stepX = ((right - left) / BLOCK_FIT_DIVISOR).coerceAtLeast(1)
		val stepY = ((bottom - top) / BLOCK_FIT_DIVISOR).coerceAtLeast(1)
		// Roughly, and then to the pixel: the difference between 0.97 and 0.98 is a row or a column
		val rough = bestFit(luma, signature, left, top, right, bottom, stepX, stepY) ?: return null
		val fit = bestFit(luma, signature, rough.left, rough.top, rough.right, rough.bottom, 1, 1) ?: rough
		return fit.takeIf {
			(isShape && ImageSignature.isSameShape(signature, it.signature)) ||
				(isInsert && ImageSignature.isSameInsert(signature, it.signature)) ||
				(isCopy && ImageSignature.isExactCopy(signature, it.signature))
		}
	}

	/**
	 * Finds the same image anywhere on a page, at any of a range of sizes, with or without blank space around it.
	 *
	 * A rule with the detail and the ink of a banner goes by [ImageSignature.isSameAtAnySize], and any other has to be
	 * an exact copy. A hash is no use for this: moving a rectangle slightly off an image turns over a quarter of its
	 * bits, on the very same image.
	 */
	internal fun bestAtAnySize(luma: PageLuma, rule: HiddenImageRule): Fit? {
		val signature = rule.signature ?: return null
		val shape = rule.shape ?: return null
		if (!shape.canCopy || shape.spreadProfile <= 0.0) {
			return null
		}
		val isBanner = shape.isBanner
		val grid = ImageSignature.GRID
		val profile = shape.profile
		val sumProfile = shape.sumProfile
		val spreadProfile = shape.spreadProfile
		val ruleWidth = (rule.xEnd - rule.xStart) * luma.width
		val centre = (rule.xStart + rule.xEnd) / 2f * luma.width
		val ruleMean = shape.mean
		val maxOffBy = MAX_ANY_SIZE_ROW_DIFFERENCE.toLong() * grid * grid
		// The two sizes that look best at first go on to be fitted, as that costs as much as all of the rest together
		var first: Fit? = null
		var second: Fit? = null
		// A small logo can be served right across another page
		var scale = maxOf(MAX_ANY_SIZE_PERCENT, (100f * luma.width / ruleWidth).roundToInt())
		while (scale >= MIN_ANY_SIZE_PERCENT) {
			val width = (ruleWidth * scale / 100f).roundToInt()
			scale -= (scale / ANY_SIZE_SEARCH_DIVISOR).coerceAtLeast(1)
			if (width < grid || width > luma.width) continue
			val height = (width.toFloat() * luma.sourceWidth / luma.width * rule.aspect * luma.height / luma.sourceHeight).roundToInt()
			if (height < grid || height > luma.height) continue
			val left = (centre - width / 2f).roundToInt().coerceIn(0, luma.width - width)
			val right = left + width
			var bestRow = -1
			var bestRowScore = MIN_ROW_PROFILE_CORRELATION
			var y = -ROW_SEARCH_STEP
			var refineFrom = Int.MAX_VALUE
			var refineTo = Int.MIN_VALUE
			val lastRow = luma.height - height
			while (true) {
				// Every other row, and then the rows next to the best of those
				if (refineFrom <= refineTo) {
					y = refineFrom++
				} else if (refineTo != Int.MIN_VALUE) {
					break
				} else {
					y += ROW_SEARCH_STEP
					if (y > lastRow) {
						if (bestRow < 0) break
						refineFrom = (bestRow - 1).coerceAtLeast(0)
						refineTo = (bestRow + 1).coerceAtMost(lastRow)
						continue
					}
				}
				// Next to nothing to work out, and most of a page is nothing like as bright or as dark as the image
				if (abs(luma.mean(left, y, right, y + height) - ruleMean) > MAX_ANY_SIZE_MEAN_DIFFERENCE) continue
				var sumHere = 0L
				var sumHereSquared = 0L
				var sumProduct = 0L
				var offBy = 0L
				var isFar = false
				for (row in 0 until grid) {
					val cellTop = y + height * row / grid
					val cellBottom = (y + height * (row + 1) / grid).coerceAtLeast(cellTop + 1)
					val here = luma.mean(left, cellTop, right, cellBottom).toLong() * grid
					offBy += abs(here - profile[row])
					if (offBy > maxOffBy) {
						isFar = true
						break
					}
					sumHere += here
					sumHereSquared += here * here
					sumProduct += here * profile[row]
				}
				if (isFar) continue
				val spreadHere = (grid * sumHereSquared - sumHere * sumHere).toDouble()
				if (spreadHere * MAX_ROW_SPREAD_RATIO < spreadProfile || spreadHere > spreadProfile * MAX_ROW_SPREAD_RATIO) continue
				val score = (grid * sumProduct - sumHere * sumProfile) / sqrt(spreadHere * spreadProfile)
				if (score > bestRowScore) {
					bestRowScore = score
					bestRow = y
				}
			}
			if (bestRow < 0) continue
			val here = ImageSignature.of(luma, left, bestRow, right, bestRow + height)
			val correlation = ImageSignature.correlation(signature, here)
			if (correlation < MIN_ANY_SIZE_FIRST_CORRELATION) continue
			val candidate = Fit(left, bestRow, right, bestRow + height, here, correlation)
			if (correlation > (first?.correlation ?: Double.NEGATIVE_INFINITY)) {
				second = first
				first = candidate
			} else if (correlation > (second?.correlation ?: Double.NEGATIVE_INFINITY)) {
				second = candidate
			}
		}
		var best: Fit? = null
		for (candidate in arrayOf(first, second)) {
			candidate ?: continue
			val stepX = ((candidate.right - candidate.left) / BLOCK_FIT_DIVISOR).coerceAtLeast(1)
			val stepY = ((candidate.bottom - candidate.top) / BLOCK_FIT_DIVISOR).coerceAtLeast(1)
			val rough = moveEdges(luma, signature, moveEdges(luma, signature, candidate, stepX, stepY), 1, 1)
			if (rough.correlation < MIN_ANY_SIZE_ROUGH_CORRELATION) continue
			val fine = bestFit(luma, signature, rough.left, rough.top, rough.right, rough.bottom, stepX, stepY) ?: continue
			val fit = bestFit(luma, signature, fine.left, fine.top, fine.right, fine.bottom, 1, 1) ?: fine
			if (fit.correlation > (best?.correlation ?: Double.NEGATIVE_INFINITY)) {
				best = fit
			}
		}
		return best?.takeIf { (isBanner && ImageSignature.isSameAtAnySize(signature, it.signature)) || ImageSignature.isExactCopy(signature, it.signature) }
	}

	/** What a rule's signature comes to, for looking at it at other sizes. Depends on nothing else. */
	internal class RuleShape(signature: ByteArray, isDetailed: Boolean) {
		val canCopy = ImageSignature.hasInkToCopy(signature)
		val fills = ImageSignature.fillsItsBox(signature)
		val isBanner = isDetailed && fills
		val mean = signature.sumOf { it.toInt() and 0xFF } / signature.size
		val background = ImageSignature.backgroundOf(signature)
		val contentCells = signature.count { abs((it.toInt() and 0xFF) - background) > CONTENT_DISTANCE }
		val blankRowsFromTop = blankGridRows(signature, background, fromTop = true)
		val blankRowsFromBottom = blankGridRows(signature, background, fromTop = false)
		val profile = IntArray(ImageSignature.GRID)
		val sumProfile: Long
		val spreadProfile: Double

		init {
			for (i in signature.indices) {
				profile[i / ImageSignature.GRID] += signature[i].toInt() and 0xFF
			}
			var sum = 0L
			var sumSquared = 0L
			for (value in profile) {
				sum += value
				sumSquared += value.toLong() * value
			}
			sumProfile = sum
			spreadProfile = (ImageSignature.GRID * sumSquared - sum * sum).toDouble()
		}
	}

	internal class Fit(val left: Int, val top: Int, val right: Int, val bottom: Int, val signature: ByteArray, val correlation: Double)

	/** Moves each edge of a fit in turn, twice over, where that helps: a fraction of the work of [bestFit], for most of what it finds. */
	private fun moveEdges(luma: PageLuma, signature: ByteArray, start: Fit, stepX: Int, stepY: Int): Fit {
		var fit = start
		repeat(2) {
			for (edge in 0 until 4) {
				for (direction in intArrayOf(-1, 1)) {
					val left = fit.left + if (edge == 0) direction * stepX else 0
					val top = fit.top + if (edge == 1) direction * stepY else 0
					val right = fit.right + if (edge == 2) direction * stepX else 0
					val bottom = fit.bottom + if (edge == 3) direction * stepY else 0
					if (left < 0 || top < 0 || right > luma.width || bottom > luma.height) continue
					if (bottom - top < ImageFingerprint.MIN_SIZE || right - left < ImageFingerprint.MIN_SIZE) continue
					val here = ImageSignature.of(luma, left, top, right, bottom)
					val correlation = ImageSignature.correlation(signature, here)
					if (correlation > fit.correlation) {
						fit = Fit(left, top, right, bottom, here, correlation)
					}
				}
			}
		}
		return fit
	}

	/** Of a rectangle with each edge where it is or a step either way, whichever goes with [signature] the best. */
	internal fun bestFit(
		luma: PageLuma,
		signature: ByteArray,
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		stepX: Int,
		stepY: Int,
	): Fit? {
		var best: Fit? = null
		for (y0 in intArrayOf(top - stepY, top, top + stepY)) {
			for (y1 in intArrayOf(bottom - stepY, bottom, bottom + stepY)) {
				for (x0 in intArrayOf(left - stepX, left, left + stepX)) {
					for (x1 in intArrayOf(right - stepX, right, right + stepX)) {
						val fitTop = y0.coerceAtLeast(0)
						val fitBottom = y1.coerceAtMost(luma.height)
						val fitLeft = x0.coerceAtLeast(0)
						val fitRight = x1.coerceAtMost(luma.width)
						if (fitBottom - fitTop < ImageFingerprint.MIN_SIZE || fitRight - fitLeft < ImageFingerprint.MIN_SIZE) continue
						val here = ImageSignature.of(luma, fitLeft, fitTop, fitRight, fitBottom)
						val correlation = ImageSignature.correlation(signature, here)
						if (correlation > (best?.correlation ?: Double.NEGATIVE_INFINITY)) {
							best = Fit(fitLeft, fitTop, fitRight, fitBottom, here, correlation)
						}
					}
				}
			}
		}
		return best
	}

	/** Something on a page standing on its own, in this preview's pixels, and its height over width in source pixels. */
	internal class ShapeBlock(
		val left: Int,
		val top: Int,
		val right: Int,
		val bottom: Int,
		val aspect: Float,
		val background: Int,
		val isDetailed: Boolean,
		val fillsPage: Boolean,
	)

	/** Kept with the page: it depends only on the page. */
	private fun shapeBlocks(luma: PageLuma): List<ShapeBlock> {
		luma.shapeBlocks?.let { return it }
		val blocks = EraseProposer.contentBlocks(luma).mapNotNull { (left, top, right, bottom) ->
			if (right - left < ImageFingerprint.MIN_SIZE || bottom - top < ImageFingerprint.MIN_SIZE) {
				return@mapNotNull null
			}
			val signature = ImageSignature.of(luma, left, top, right, bottom)
			val isDetailed = ImageSignature.hasDetailToCompare(signature)
			val fillsPage = (right - left) * 100 >= luma.width * MIN_INSERT_FILL_PERCENT &&
				(bottom - top) * 100 >= luma.height * MIN_INSERT_FILL_PERCENT
			val height = (bottom - top).toFloat() * luma.sourceHeight / luma.height
			val width = (right - left).toFloat() * luma.sourceWidth / luma.width
			ShapeBlock(left, top, right, bottom, height / width, ImageSignature.backgroundOf(signature), isDetailed, fillsPage)
		}
		luma.shapeBlocks = blocks
		return blocks
	}

	private inline fun forEachNearbyFit(
		luma: PageLuma,
		left: Int,
		right: Int,
		top: Int,
		window: Int,
		block: (l: Int, y: Int, r: Int, size: Int) -> Unit,
	) {
		for (size in window - SETTLE_SIZE..window + SETTLE_SIZE) {
			if (size < ImageFingerprint.MIN_SIZE) continue
			for (y in (top - SETTLE_ROWS).coerceAtLeast(0)..(top + SETTLE_ROWS).coerceAtMost(luma.height - size)) {
				for (l in (left - SETTLE_COLUMNS).coerceAtLeast(0)..left + SETTLE_COLUMNS) {
					for (r in right - SETTLE_COLUMNS..(right + SETTLE_COLUMNS).coerceAtMost(luma.width)) {
						if (r - l < ImageFingerprint.MIN_SIZE) continue
						block(l, y, r, size)
					}
				}
			}
		}
	}

	/**
	 * Finds an image hanging off the top or bottom of the page.
	 *
	 * Long strips get sliced into pages wherever the scanlator's tool felt like it, including straight through
	 * an image, so part of it ends one page and the rest starts the next. A window sliding over the page
	 * can never find those, since the whole image is not there to fit in it. So this compares only the part
	 * of the signature that is on the page, row by row. It takes a signature, so a rule without one still
	 * only matches whole.
	 */
	private inline fun findCutOff(
		luma: PageLuma,
		rule: HiddenImageRule,
		left: Int,
		right: Int,
		onFound: (top: Int, bottom: Int, isAtTop: Boolean, missingRows: Int) -> Unit,
	) {
		val top = bestCutOff(luma, rule, left, right, isAtTop = true)
		val isTopFound = top != null && top.isSameImage() && top.isSameColour(luma, rule, left, right, isAtTop = true) && !top.isOnlyACurve(rule, isAtTop = true)
		if (isTopFound) {
			onFound(0, top!!.visibleRows, true, top.missingContentRows)
		}
		val bottom = bestCutOff(luma, rule, left, right, isAtTop = false)
		val isBottomFound = bottom != null && bottom.isSameImage() && bottom.isSameColour(luma, rule, left, right, isAtTop = false) && !bottom.isOnlyACurve(rule, isAtTop = false)
		if (isBottomFound) {
			onFound(luma.height - bottom!!.visibleRows, luma.height, false, bottom.missingContentRows)
		}
		val shape = rule.shape ?: return
		if (isTopFound || isBottomFound || !shape.canCopy) {
			return
		}
		val isBanner = shape.isBanner
		// Not at this page's size, then
		var scale = MAX_ANY_SIZE_PERCENT
		while (scale >= MIN_ANY_SIZE_PERCENT) {
			val width = (right - left) * scale / 100
			scale -= (scale / ANY_SIZE_STEP_DIVISOR).coerceAtLeast(1)
			if (width > luma.width) continue
			val scaledLeft = ((left + right - width) / 2).coerceIn(0, luma.width - width)
			val scaledRight = scaledLeft + width
			val scaledTop = bestCutOff(luma, rule, scaledLeft, scaledRight, isAtTop = true)
			if (scaledTop != null && scaledTop.isSameImageScaled(isBanner) && scaledTop.isSameColour(luma, rule, scaledLeft, scaledRight, isAtTop = true) && !scaledTop.isOnlyACurve(rule, isAtTop = true)) {
				onFound(0, scaledTop.visibleRows, true, scaledTop.missingContentRows)
				return
			}
			val scaledBottom = bestCutOff(luma, rule, scaledLeft, scaledRight, isAtTop = false)
			if (scaledBottom != null && scaledBottom.isSameImageScaled(isBanner) && scaledBottom.isSameColour(luma, rule, scaledLeft, scaledRight, isAtTop = false) && !scaledBottom.isOnlyACurve(rule, isAtTop = false)) {
				onFound(luma.height - scaledBottom.visibleRows, luma.height, false, scaledBottom.missingContentRows)
				return
			}
		}
	}

	private fun CutOff.isSameImageScaled(isBanner: Boolean) = if (isBanner) {
		correlation >= MIN_SCALED_EDGE_CORRELATION && isSameInBrightness()
	} else {
		correlation >= MIN_COPY_EDGE_CORRELATION && farPercent <= MAX_COPY_EDGE_FAR_PERCENT && isSameInBrightness()
	}

	/** Whether all that the part of it on this page has in it is a curve, see [ImageSignature.isOneCurveInRows]. */
	private fun CutOff.isOnlyACurve(rule: HiddenImageRule, isAtTop: Boolean): Boolean {
		val signature = rule.signature ?: return false
		val rows = (visibleRows * ImageSignature.GRID / window).coerceIn(1, ImageSignature.GRID)
		return if (isAtTop) {
			ImageSignature.isOneCurveInRows(signature, ImageSignature.GRID - rows, ImageSignature.GRID - 1)
		} else {
			ImageSignature.isOneCurveInRows(signature, 0, rows - 1)
		}
	}

	private fun CutOff.isSameColour(luma: PageLuma, rule: HiddenImageRule, left: Int, right: Int, isAtTop: Boolean) = ImageColour.isSame(
		rule.colour,
		luma,
		left,
		right,
		if (isAtTop) visibleRows - window else luma.height - visibleRows,
		window,
	)

	private fun CutOff.isSameImage() =
		correlation >= (if (visibleRows * 100 < window * MAX_SLIVER_PERCENT) MIN_SLIVER_CORRELATION else MIN_EDGE_CORRELATION) && isSameInBrightness()

	private fun CutOff.isSameInBrightness() = difference <= MAX_EDGE_DIFFERENCE && abs(shift) <= MAX_EDGE_SHIFT &&
		farPercent <= when {
			!fillsItsBox -> MAX_EDGE_FAR_PERCENT_SPARSE
			visibleRows * 100 >= (visibleRows + missingContentRows) * NEARLY_ALL_PERCENT -> MAX_EDGE_FAR_PERCENT_NEARLY_ALL
			else -> MAX_EDGE_FAR_PERCENT
		}

	internal class CutOff(
		val correlation: Int,
		val difference: Int,
		val shift: Int,
		val farPercent: Int,
		val fillsItsBox: Boolean,
		val visibleRows: Int,
		val missingContentRows: Int,
		val window: Int,
	)

	/** The best alignment of a rule hanging off one edge of a page, if enough of it could be on the page. */
	internal fun bestCutOff(luma: PageLuma, rule: HiddenImageRule, left: Int, right: Int, isAtTop: Boolean): CutOff? {
		val signature = rule.signature ?: return null
		val shape = rule.shape ?: return null
		// A banner, not a stretch of story: there is too little in a slice of a tall image to tell it by
		if (rule.aspect > MAX_EDGE_ASPECT) {
			return null
		}
		val width = right - left
		val sourceWidth = width.toFloat() * luma.sourceWidth / luma.width
		val window = (sourceWidth * rule.aspect * luma.height / luma.sourceHeight).roundToInt()
		if (window < ImageSignature.GRID || width < ImageSignature.GRID) {
			return null
		}
		val background = shape.background
		// Where its content stops short of the window's end that is on the page
		val onPageBlank = window * (if (isAtTop) shape.blankRowsFromBottom else shape.blankRowsFromTop) / ImageSignature.GRID
		val required = (shape.contentCells * MIN_EDGE_CONTENT_PERCENT / 100).coerceAtLeast(MIN_EDGE_CONTENT_CELLS)
		// Blank at the end that is off the page is not missing from anywhere: only the rest is next door
		val offPageBlank = window * (if (isAtTop) shape.blankRowsFromTop else shape.blankRowsFromBottom) / ImageSignature.GRID
		val fills = shape.fills
		var best: CutOff? = null
		val limit = window.coerceAtMost(luma.height)
		// What sets a cut off image apart from the story carrying on: it stops, and blank space follows. So these rows
		// are the only ones it can end at. Up to all but a row: sliding a window needs all of it on the page, so this is
		// all that can find one cut off by very little, which slicing a strip does as readily as anywhere else. A real
		// banner measured 0.97 at 94%.
		for (contentEnd in edgeRows(luma, isAtTop)) {
			val visible = contentEnd + onPageBlank
			if (visible >= limit) {
				break
			}
			val offset = if (isAtTop) visible - window else luma.height - visible
			val candidate = compareVisible(luma, signature, left, right, offset, window, background, required) ?: continue
			if (candidate.correlation > (best?.correlation ?: Int.MIN_VALUE)) {
				best = CutOff(candidate.correlation, candidate.difference, candidate.shift, candidate.farPercent, fills, visible, window - visible - offPageBlank, window)
			}
		}
		return best
	}

	/**
	 * Where on this page content is followed by blank space. Depends only on the page, and every rule asks about
	 * the same rows, which on real pages was nearly a third of all the time spent on a page.
	 */
	internal fun edgeBlanks(luma: PageLuma, isAtTop: Boolean): BooleanArray {
		(if (isAtTop) luma.blankAfterRowsAtTop else luma.blankAfterRowsAtBottom)?.let { return it }
		val need = (luma.width / EDGE_BLANK_DIVISOR).coerceAtLeast(2)
		val rows = BooleanArray(luma.height + 1) { end ->
			end > 0 && EraseProposer.isFollowedByBlank(luma, end, isAtTop, need, EDGE_BLANK_SLACK_ROWS)
		}
		if (isAtTop) luma.blankAfterRowsAtTop = rows else luma.blankAfterRowsAtBottom = rows
		return rows
	}

	private fun edgeRows(luma: PageLuma, isAtTop: Boolean): IntArray {
		(if (isAtTop) luma.edgeRowsAtTop else luma.edgeRowsAtBottom)?.let { return it }
		val blanks = edgeBlanks(luma, isAtTop)
		val rows = blanks.indices.filter { blanks[it] }.toIntArray()
		if (isAtTop) luma.edgeRowsAtTop = rows else luma.edgeRowsAtBottom = rows
		return rows
	}

	/**
	 * How well the grid cells of a window at row [offset] that are wholly on the page agree with the signature:
	 * correlation in hundredths, and the average difference of the cells with something in them. Null if too
	 * little of what the image has in it is on the page for an answer to mean anything.
	 *
	 * Being close in brightness is not enough, since all dark art is close to a banner on black, and all white
	 * space to a banner on white. Correlation asks whether it is bright and dark in the same places, which
	 * those are not, whatever their overall brightness. The difference then rules out the same shapes in other
	 * tones.
	 */
	private fun compareVisible(
		luma: PageLuma,
		signature: ByteArray,
		left: Int,
		right: Int,
		offset: Int,
		window: Int,
		background: Int,
		requiredContent: Int,
	): VisibleComparison? {
		val grid = ImageSignature.GRID
		val width = right - left
		var contentSeen = 0
		var compared = 0
		var total = 0
		var signedTotal = 0
		var far = 0
		var cells = 0
		var sumExpected = 0L
		var sumActual = 0L
		var sumExpectedSquared = 0L
		var sumActualSquared = 0L
		var sumProduct = 0L
		for (row in 0 until grid) {
			val cellTop = offset + window * row / grid
			val cellBottom = offset + window * (row + 1) / grid
			if (cellTop < 0 || cellBottom > luma.height || cellBottom <= cellTop) {
				continue
			}
			val exactTop = offset + window.toDouble() * row / grid
			val exactBottom = offset + window.toDouble() * (row + 1) / grid
			for (column in 0 until grid) {
				val exactLeft = left + width.toDouble() * column / grid
				val exactRight = left + width.toDouble() * (column + 1) / grid
				val expected = signature[row * grid + column].toInt() and 0xFF
				val actual = luma.meanBetween(exactLeft, exactTop, exactRight, exactBottom)
				val isExpectedContent = abs(expected - background) > CONTENT_DISTANCE
				if (isExpectedContent) contentSeen++
				if (isExpectedContent || abs(actual - background) > CONTENT_DISTANCE) {
					compared++
					total += abs(actual - expected)
					signedTotal += actual - expected
					if (abs(actual - expected) > EDGE_FAR_CELL) far++
				}
				cells++
				sumExpected += expected
				sumActual += actual
				sumExpectedSquared += expected * expected
				sumActualSquared += actual * actual
				sumProduct += expected * actual
			}
		}
		if (contentSeen < requiredContent || compared == 0) {
			return null
		}
		val covariance = (cells * sumProduct - sumExpected * sumActual).toDouble()
		val spreadExpected = (cells * sumExpectedSquared - sumExpected * sumExpected).toDouble()
		val spreadActual = (cells * sumActualSquared - sumActual * sumActual).toDouble()
		if (spreadExpected <= 0.0 || spreadActual <= 0.0) {
			return null
		}
		return VisibleComparison((100 * covariance / sqrt(spreadExpected * spreadActual)).toInt(), total / compared, signedTotal / compared, far * 100 / compared)
	}

	private class VisibleComparison(val correlation: Int, val difference: Int, val shift: Int, val farPercent: Int)

	/** How many whole grid rows at one end of a signature have nothing in them. */
	private fun blankGridRows(signature: ByteArray, background: Int, fromTop: Boolean): Int {
		val grid = ImageSignature.GRID
		var rows = 0
		while (rows < grid) {
			val row = if (fromTop) rows else grid - 1 - rows
			val isBlank = (0 until grid).all { column ->
				abs((signature[row * grid + column].toInt() and 0xFF) - background) <= CONTENT_DISTANCE
			}
			if (!isBlank) break
			rows++
		}
		return rows
	}

	private fun distanceAt(
		luma: PageLuma,
		rule: HiddenImageRule,
		probe: ImageFingerprint.Probe,
		scratch: Scratch,
		left: Int,
		top: Int,
		right: Int,
		window: Int,
		limit: Int,
	): Int {
		val known = scratch.distances[top]
		if (known != UNKNOWN) {
			return known
		}
		val mean = luma.mean(left, top, right, top + window)
		val distance = if (abs(mean - rule.fingerprint.mean) > MEAN_TOLERANCE) {
			Int.MAX_VALUE
		} else {
			probe.distanceTo(rule.fingerprint, top, limit)
		}
		scratch.distances[top] = distance
		return distance
	}

	private fun mergeBands(bands: List<SourceRegion>): List<SourceRegion> {
		if (bands.size < 2) {
			return bands
		}
		val sorted = bands.sortedBy { it.top }
		val result = ArrayList<SourceRegion>(sorted.size)
		var current = sorted.first()
		for (band in sorted.drop(1)) {
			current = if (band.top <= current.bottom) {
				current.copy(bottom = maxOf(current.bottom, band.bottom))
			} else {
				result += current
				band
			}
		}
		result += current
		return result
	}
}
