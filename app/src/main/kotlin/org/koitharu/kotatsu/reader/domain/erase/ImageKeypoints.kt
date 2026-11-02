package org.koitharu.kotatsu.reader.domain.erase

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A page at the size small points on it can be told apart at, for [ImageKeypoints]. [PageLuma] is far too small for that.
 *
 * @param gray one unsigned byte per pixel, row by row
 * @param colour at a quarter of this size each way
 */
class PageDetail(
	val width: Int,
	val height: Int,
	val gray: ByteArray,
	val colour: PageColour?,
) {

	/** Worked out once, as they take far longer to find than anything else about a page. */
	@Volatile
	internal var keypoints: Keypoints? = null

	/** Sums of [gray], for averaging any rectangle in four lookups. Only built for a page that gets as far as needing it. */
	internal val integral: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
		val stride = width + 1
		val table = IntArray(stride * (height + 1))
		for (y in 0 until height) {
			var row = 0
			val offset = y * width
			val above = y * stride
			val current = above + stride
			for (x in 0 until width) {
				row += gray[offset + x].toInt() and 0xFF
				table[current + x + 1] = table[above + x + 1] + row
			}
		}
		table
	}

	companion object {

		/**
		 * The size a page is looked over at, however large it really is. At 480, a logo on a page comes to too few pixels to have
		 * points: 57% of small images were found again then, and 73% at this, for about 1.6 times the work.
		 */
		const val MAX_WIDTH = 640

		/** A long strip is looked over at less than that, or it would take all day and all of the memory. */
		const val MAX_HEIGHT = 8000

		/** How wide a page of this size is looked over at. */
		fun widthFor(sourceWidth: Int, sourceHeight: Int): Int {
			val capped = minOf(sourceWidth, MAX_WIDTH)
			val tall = (sourceHeight.toLong() * capped / sourceWidth.coerceAtLeast(1)).toInt()
			return if (tall > MAX_HEIGHT) (capped.toLong() * MAX_HEIGHT / tall).toInt().coerceAtLeast(1) else capped
		}

		fun heightFor(sourceWidth: Int, sourceHeight: Int): Int {
			val width = widthFor(sourceWidth, sourceHeight)
			return (sourceHeight.toLong() * width / sourceWidth.coerceAtLeast(1)).toInt().coerceIn(1, MAX_HEIGHT)
		}
	}
}

/**
 * Builds a page's [PageLuma] and [PageDetail] from one decode, row by row.
 *
 * @param sample how far the rows were decoded down from the page's real size
 */
class DetailedPageBuilder(
	private val decodedWidth: Int,
	private val decodedHeight: Int,
	private val sourceWidth: Int,
	private val sourceHeight: Int,
	sample: Int,
) {

	private val every = (PageLuma.sampleSizeFor(sourceWidth, sourceHeight) / sample).coerceAtLeast(1)
	private val lumaWidth = (decodedWidth / every).coerceAtLeast(1)
	private val lumaHeight = (decodedHeight / every).coerceAtLeast(1)
	private val data = ByteArray(lumaWidth * lumaHeight)
	private val colour = PageColour.Builder(lumaWidth, lumaHeight)
	private val detailWidth = PageDetail.widthFor(sourceWidth, sourceHeight)
	private val detailHeight = PageDetail.heightFor(sourceWidth, sourceHeight)
	private val shrinker = AreaShrinker(decodedWidth, decodedHeight, detailWidth, detailHeight)
	private val detailColour = PageColour.Builder(detailWidth, detailHeight)
	private val grays = IntArray(decodedWidth)
	private val reds = IntArray(lumaWidth)
	private val greens = IntArray(lumaWidth)
	private val blues = IntArray(lumaWidth)
	private val counts = IntArray(lumaWidth)
	private val lumaColumn = IntArray(decodedWidth) { (it / every).coerceAtMost(lumaWidth - 1) }
	private val detailColumn = IntArray(decodedWidth) { (it.toLong() * detailWidth / decodedWidth).toInt() }
	private var y = 0

	/** One in two each way, as long as each cell of the small copy still has plenty. */
	private val sampleMask = if (every >= 4) 1 else 0

	/**
	 * @param rows ARGB, as a bitmap gives it, one row after another from [offset]. Rows have to come in order, from the top.
	 *
	 * Every pixel goes into the larger copy, as points need it sharp. The small copy and colours are averages, of many pixels
	 * each, and every other one of every other row comes to the same: this was most of what a page cost on a slow processor.
	 */
	fun addRow(rows: IntArray, offset: Int = 0) {
		for (x in 0 until decodedWidth) {
			val pixel = rows[offset + x]
			grays[x] = (77 * (pixel shr 16 and 0xFF) + 150 * (pixel shr 8 and 0xFF) + 29 * (pixel and 0xFF)) shr 8
		}
		shrinker.addRow(grays)
		if (y and sampleMask == 0) {
			val detailRow = (y.toLong() * detailHeight / decodedHeight).toInt()
			var x = 0
			while (x < decodedWidth) {
				val pixel = rows[offset + x]
				val red = pixel shr 16 and 0xFF
				val green = pixel shr 8 and 0xFF
				val blue = pixel and 0xFF
				val cell = lumaColumn[x]
				reds[cell] += red
				greens[cell] += green
				blues[cell] += blue
				counts[cell]++
				detailColour.add(detailColumn[x], detailRow, red, green, blue)
				x += sampleMask + 1
			}
		}
		val lumaRow = (y / every).coerceAtMost(lumaHeight - 1)
		val nextRow = ((y + 1) / every).coerceAtMost(lumaHeight - 1)
		if (y == decodedHeight - 1 || nextRow != lumaRow) {
			val at = lumaRow * lumaWidth
			for (x in 0 until lumaWidth) {
				val count = counts[x].coerceAtLeast(1)
				val red = reds[x] / count
				val green = greens[x] / count
				val blue = blues[x] / count
				data[at + x] = ((77 * red + 150 * green + 29 * blue) shr 8).toByte()
				// Each cell as a whole, as the larger size of it was decoded from the smaller
				colour.add(x, lumaRow, red, green, blue)
				reds[x] = 0
				greens[x] = 0
				blues[x] = 0
				counts[x] = 0
			}
		}
		y++
	}

	fun build(): PageLuma = PageLuma(
		width = lumaWidth,
		height = lumaHeight,
		sourceWidth = sourceWidth,
		sourceHeight = sourceHeight,
		data = data,
		colour = colour.build(),
		detail = PageDetail(detailWidth, detailHeight, shrinker.output, detailColour.build()),
	)

	companion object {

		/** The largest power of two that still leaves a page as wide as it is looked over at. */
		fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int): Int {
			val wanted = PageDetail.widthFor(sourceWidth, sourceHeight)
			var sample = 1
			while (sourceWidth / (sample * 2) >= wanted) {
				sample *= 2
			}
			return sample
		}
	}
}

/** Small points that stand out in an image, and what is around each. Four longs of [descriptors] to a point. */
class Keypoints(
	val count: Int,
	val x: FloatArray,
	val y: FloatArray,
	val descriptors: LongArray,
	/** Which of [ImageKeypoints]' sizes each was found at, which says about how large what is around it is. */
	val levels: ByteArray,
) {
	companion object {
		val EMPTY = Keypoints(0, FloatArray(0), FloatArray(0), LongArray(0), ByteArray(0))
	}
}

/**
 * Turns the rows of a decoded image into a smaller one by exact area, each output pixel the average of what it covers.
 * Works on a row at a time, as a long strip is too much to hold twice.
 */
class AreaShrinker(private val sourceWidth: Int, private val sourceHeight: Int, private val width: Int, private val height: Int) {

	val output = ByteArray(width * height)
	private val starts = IntArray(width)
	private val ends = IntArray(width)
	private val firstWeight = IntArray(width)
	private val lastWeight = IntArray(width)
	private val line = IntArray(width)
	private val total = LongArray(width)
	private var row = 0
	private var filled = 0.0
	private var rowWeight = 0L
	private val rowsPerOutput = sourceHeight.toDouble() / height

	init {
		for (x in 0 until width) {
			val from = x.toDouble() * sourceWidth / width
			val to = (x + 1).toDouble() * sourceWidth / width
			val first = floor(from).toInt()
			val last = ceil(to).toInt().coerceAtMost(sourceWidth)
			starts[x] = first
			ends[x] = last
			firstWeight[x] = ((minOf(first + 1.0, to) - from) * WEIGHT).roundToInt()
			lastWeight[x] = if (last - 1 > first) ((to - (last - 1)) * WEIGHT).roundToInt() else 0
		}
	}

	/** Rows have to come in order, from the top. */
	fun addRow(values: IntArray) {
		for (x in 0 until width) {
			val first = starts[x]
			val last = ends[x] - 1
			var sum = values[first] * firstWeight[x]
			for (i in first + 1 until last) {
				sum += values[i] * WEIGHT
			}
			if (last > first) {
				sum += values[last] * lastWeight[x]
			}
			line[x] = sum
		}
		var remaining = 1.0
		while (remaining > 0.0 && row < height) {
			val take = minOf(remaining, rowsPerOutput - filled)
			val weight = (take * WEIGHT).roundToInt()
			for (x in 0 until width) {
				total[x] += line[x].toLong() * weight
			}
			rowWeight += weight
			filled += take
			remaining -= take
			if (filled >= rowsPerOutput - 1e-9) {
				finishRow()
			}
		}
	}

	private fun finishRow() {
		val offset = row * width
		for (x in 0 until width) {
			val spanned = (ends[x] - starts[x] - 2).coerceAtLeast(0) * WEIGHT + firstWeight[x] + lastWeight[x]
			val weight = (spanned.toLong() * rowWeight).coerceAtLeast(1L)
			output[offset + x] = ((total[x] + weight / 2) / weight).coerceIn(0L, 255L).toByte()
			total[x] = 0L
		}
		filled = 0.0
		rowWeight = 0L
		row++
	}

	private companion object {
		const val WEIGHT = 256
	}
}

/**
 * Finds points that stand out in an image and says what is around each one, in a way that hardly changes with the image's
 * size, its compression, or what is next to it: the same small corner of a letter is the same wherever the image is.
 *
 * Going by small points is what lets an image be found cut off, against art, or at another size all the same. Comparing
 * an image as a whole has to line up all of it first, and a part missing or a margin added throws it all off.
 *
 * This is the ORB way of doing it without working out which way a point faces, as images do not turn on a page, which
 * leaves it faster and better at telling points apart. Plain code with no native library, so it costs the app no size.
 */
object ImageKeypoints {

	private const val LEVELS = 8
	private const val SCALE = 1.2f

	/**
	 * How much brighter or darker a ring around a point has to be. Low, as a logo may be faint, but not so low that noise in a
	 * JPEG counts: from 7 to 12 found as many images on 7,524 samples, for a fifth less time on a phone. At 16 it began to miss them.
	 */
	private const val FAST_THRESHOLD = 12
	private const val ARC = 9

	/** Out to how far around a point is looked at. Nearer the edge than [BORDER], there is nothing to look at. */
	private const val PATCH_RADIUS = 7
	private const val BOX_RADIUS = 2
	private const val BORDER = PATCH_RADIUS + BOX_RADIUS + 1

	private const val HARRIS_RADIUS = 3

	/** How finely points are shared out over an image, in pixels of each size, and how many of each part are in the running. */
	private const val SPREAD_CELL = 32
	private const val PER_CELL = 4

	private const val MAX_SPARE_SCRATCH = 2

	const val DESCRIPTOR_LONGS = 4
	private const val DESCRIPTOR_BITS = 256

	/** A long page is looked over in pieces, so that a busy part cannot use up what a plain ad in another part needs. */
	const val TILE_ROWS = 600
	private const val TILE_OVERLAP = 40
	const val POINTS_PER_TILE = 530

	/** Which two spots around a point to compare for each bit. Saved rules depend on it, so it must never change. */
	private val pattern: IntArray = IntArray(DESCRIPTOR_BITS * 4).also { pairs ->
		var state = 0x34985739L
		val span = PATCH_RADIUS * 2 + 1
		for (i in pairs.indices) {
			state = (state * 6364136223846793005L + 1442695040888963407L)
			pairs[i] = (((state ushr 33) % span).toInt()) - PATCH_RADIUS
		}
	}

	/** By the difference from a pixel plus 255: 1 for brighter than it by the threshold, 2 for darker, 0 for neither. */
	private val ringClass = ByteArray(511) { i ->
		val difference = i - 255
		when {
			difference < -FAST_THRESHOLD -> 2
			difference > FAST_THRESHOLD -> 1
			else -> 0
		}.toByte()
	}

	/** All of a whole image, such as what was erased. */
	fun ofImage(gray: ByteArray, width: Int, height: Int, limit: Int): Keypoints {
		val scratch = Scratch()
		return ofArea(gray, width, 0, height, limit, scratch)
	}

	private val spareScratch = java.util.concurrent.ConcurrentLinkedQueue<Scratch>()

	/**
	 * Working memory, kept for the next page. Asking for megabytes of it for every page has the garbage collector busy all
	 * the while someone is scrolling, which costs battery and is what they would see as stutter.
	 */
	fun <T> withScratch(block: (Scratch) -> T): T {
		val scratch = spareScratch.poll() ?: Scratch()
		try {
			return block(scratch)
		} finally {
			if (spareScratch.size < MAX_SPARE_SCRATCH) spareScratch.offer(scratch)
		}
	}

	/** How many pieces [ofTile] takes for a page this tall. */
	fun tilesOf(height: Int): Int = (height + TILE_ROWS - 1) / TILE_ROWS

	fun ofTile(page: PageDetail, tile: Int, scratch: Scratch = Scratch()): Keypoints {
		val top = tile * TILE_ROWS
		val bottom = minOf(top + TILE_ROWS + TILE_OVERLAP, page.height)
		return ofArea(page.gray, page.width, top, bottom, POINTS_PER_TILE, scratch)
	}

	fun join(parts: List<Keypoints>): Keypoints {
		val count = parts.sumOf { it.count }
		if (count == 0) return Keypoints.EMPTY
		val x = FloatArray(count)
		val y = FloatArray(count)
		val descriptors = LongArray(count * DESCRIPTOR_LONGS)
		val levels = ByteArray(count)
		var at = 0
		for (part in parts) {
			part.levels.copyInto(levels, at, 0, part.count)
			part.x.copyInto(x, at, 0, part.count)
			part.y.copyInto(y, at, 0, part.count)
			part.descriptors.copyInto(descriptors, at * DESCRIPTOR_LONGS, 0, part.count * DESCRIPTOR_LONGS)
			at += part.count
		}
		return Keypoints(count, x, y, descriptors, levels)
	}

	/** Grown as needed and used again, as a phone should not be asked for all of this for every piece of every page. */
	class Scratch {
		var level = ByteArray(0)
		var next = ByteArray(0)
		var integral = IntArray(0)
		var corners = IntArray(1024)
		var scores = IntArray(1024)
		var order = LongArray(1024)
		var rowA = IntArray(0)
		var rowB = IntArray(0)
		var rowC = IntArray(0)
		val inRank = IntArray(PER_CELL)
	}

	private fun ofArea(gray: ByteArray, stride: Int, top: Int, bottom: Int, limit: Int, scratch: Scratch): Keypoints {
		val height = bottom - top
		if (stride <= BORDER * 2 || height <= BORDER * 2) {
			return Keypoints.EMPTY
		}
		// As many at each size, and what one size has no use for goes to the rest: an image that comes back at half the size
		// has only the points of its smaller sizes to be found by
		var share = limit.toFloat() / LEVELS
		val xs = FloatArray(limit + LEVELS)
		val ys = FloatArray(limit + LEVELS)
		val descriptors = LongArray((limit + LEVELS) * DESCRIPTOR_LONGS)
		val levels = ByteArray(limit + LEVELS)
		var found = 0
		var levelWidth = stride
		var levelHeight = height
		var scale = 1f
		// Both as large as the largest: they trade places going down the sizes, and either may be first for the next piece
		if (scratch.level.size < stride * height) {
			scratch.level = ByteArray(stride * height)
		}
		if (scratch.next.size < stride * height) {
			scratch.next = ByteArray(stride * height)
		}
		System.arraycopy(gray, top * stride, scratch.level, 0, stride * height)
		for (index in 0 until LEVELS) {
			if (levelWidth <= BORDER * 2 + 1 || levelHeight <= BORDER * 2 + 1) break
			val quota = minOf(share.roundToInt().coerceAtLeast(1), limit + LEVELS - found)
			val level = scratch.level
			val corners = detect(level, levelWidth, levelHeight, quota, scratch)
			if (corners > 0) {
				val table = integralOf(level, levelWidth, levelHeight, scratch)
				for (i in 0 until corners) {
					val at = scratch.corners[i]
					val x = at % levelWidth
					val y = at / levelWidth
					describe(table, levelWidth + 1, x, y, descriptors, found * DESCRIPTOR_LONGS)
					xs[found] = x * scale
					ys[found] = y * scale + top
					levels[found] = index.toByte()
					found++
				}
			}
			share = (limit - found).toFloat() / (LEVELS - index - 1).coerceAtLeast(1)
			val nextWidth = (stride / Math.pow(SCALE.toDouble(), (index + 1).toDouble())).roundToInt()
			val nextHeight = (height / Math.pow(SCALE.toDouble(), (index + 1).toDouble())).roundToInt()
			if (nextWidth <= BORDER * 2 + 1 || nextHeight <= BORDER * 2 + 1) break
			shrink(level, levelWidth, levelHeight, nextWidth, nextHeight, scratch)
			val swap = scratch.level
			scratch.level = scratch.next
			scratch.next = swap
			scale *= levelWidth.toFloat() / nextWidth
			levelWidth = nextWidth
			levelHeight = nextHeight
		}
		return Keypoints(found, xs.copyOf(found), ys.copyOf(found), descriptors.copyOf(found * DESCRIPTOR_LONGS), levels.copyOf(found))
	}

	private fun shrink(from: ByteArray, width: Int, height: Int, toWidth: Int, toHeight: Int, scratch: Scratch) {
		val to = scratch.next
		// Bilinear, in fixed point, each level from the last
		val stepX = (width shl 12) / toWidth
		val stepY = (height shl 12) / toHeight
		var sourceY = 0
		for (y in 0 until toHeight) {
			val row = (sourceY shr 12).coerceAtMost(height - 2)
			val partY = sourceY and 0xFFF
			val rowA = row * width
			val rowB = rowA + width
			val offset = y * toWidth
			var sourceX = 0
			for (x in 0 until toWidth) {
				val column = (sourceX shr 12).coerceAtMost(width - 2)
				val partX = sourceX and 0xFFF
				val a = from[rowA + column].toInt() and 0xFF
				val b = from[rowA + column + 1].toInt() and 0xFF
				val c = from[rowB + column].toInt() and 0xFF
				val d = from[rowB + column + 1].toInt() and 0xFF
				val upper = (a shl 12) + (b - a) * partX
				val lower = (c shl 12) + (d - c) * partX
				to[offset + x] = ((upper.toLong() * (4096 - partY) + lower.toLong() * partY + (1L shl 23)) shr 24).toInt().toByte()
				sourceX += stepX
			}
			sourceY += stepY
		}
	}

	/** @return how many, left at the start of [Scratch.corners] */
	private fun detect(level: ByteArray, width: Int, height: Int, quota: Int, scratch: Scratch): Int {
		val ring = intArrayOf(
			-3 * width, -3 * width + 1, -2 * width + 2, -width + 3,
			3, width + 3, 2 * width + 2, 3 * width + 1,
			3 * width, 3 * width - 1, 2 * width - 2, width - 3,
			-3, -width - 3, -2 * width - 2, -3 * width - 1,
		)
		val r0 = ring[0]; val r1 = ring[1]; val r2 = ring[2]; val r3 = ring[3]
		val r4 = ring[4]; val r5 = ring[5]; val r6 = ring[6]; val r7 = ring[7]
		val r8 = ring[8]; val r9 = ring[9]; val r10 = ring[10]; val r11 = ring[11]
		val r12 = ring[12]; val r13 = ring[13]; val r14 = ring[14]; val r15 = ring[15]
		val classes = ringClass
		val threshold = FAST_THRESHOLD
		if (scratch.rowA.size < width) {
			scratch.rowA = IntArray(width)
			scratch.rowB = IntArray(width)
			scratch.rowC = IntArray(width)
		}
		var previous2 = scratch.rowA
		var previous = scratch.rowB
		var current = scratch.rowC
		previous2.fill(0, 0, width)
		previous.fill(0, 0, width)
		current.fill(0, 0, width)
		var count = 0
		val diffs = IntArray(16)
		var currentHasAny = false
		var previousHasAny = false
		var previous2HasAny = false
		for (y in BORDER until height - BORDER) {
			val offset = y * width
			for (x in BORDER until width - BORDER) {
				val at = offset + x
				val v = level[at].toInt() and 0xFF
				// Most pixels of a page go no further than these two, so no table for them
				val above = (level[at + r0].toInt() and 0xFF) - v
				val below = (level[at + r8].toInt() and 0xFF) - v
				if (above <= threshold && above >= -threshold && below <= threshold && below >= -threshold) continue
				val base = 255 - v
				var d = classes[above + 255].toInt() or classes[below + 255].toInt()
				d = d and (classes[base + (level[at + r4].toInt() and 0xFF)].toInt() or classes[base + (level[at + r12].toInt() and 0xFF)].toInt())
				if (d == 0) continue
				d = d and (classes[base + (level[at + r2].toInt() and 0xFF)].toInt() or classes[base + (level[at + r10].toInt() and 0xFF)].toInt())
				d = d and (classes[base + (level[at + r6].toInt() and 0xFF)].toInt() or classes[base + (level[at + r14].toInt() and 0xFF)].toInt())
				if (d == 0) continue
				diffs[0] = (level[at + r0].toInt() and 0xFF) - v
				diffs[1] = (level[at + r1].toInt() and 0xFF) - v
				diffs[2] = (level[at + r2].toInt() and 0xFF) - v
				diffs[3] = (level[at + r3].toInt() and 0xFF) - v
				diffs[4] = (level[at + r4].toInt() and 0xFF) - v
				diffs[5] = (level[at + r5].toInt() and 0xFF) - v
				diffs[6] = (level[at + r6].toInt() and 0xFF) - v
				diffs[7] = (level[at + r7].toInt() and 0xFF) - v
				diffs[8] = (level[at + r8].toInt() and 0xFF) - v
				diffs[9] = (level[at + r9].toInt() and 0xFF) - v
				diffs[10] = (level[at + r10].toInt() and 0xFF) - v
				diffs[11] = (level[at + r11].toInt() and 0xFF) - v
				diffs[12] = (level[at + r12].toInt() and 0xFF) - v
				diffs[13] = (level[at + r13].toInt() and 0xFF) - v
				diffs[14] = (level[at + r14].toInt() and 0xFF) - v
				diffs[15] = (level[at + r15].toInt() and 0xFF) - v
				var bright = 0
				var dark = 0
				for (i in 0 until 16) {
					val difference = diffs[i]
					if (difference > threshold) bright = bright or (1 shl i) else if (difference < -threshold) dark = dark or (1 shl i)
				}
				val brightArcs = arcsOf(bright)
				val darkArcs = arcsOf(dark)
				if (brightArcs == 0 && darkArcs == 0) continue
				current[x] = maxOf(scoreOf(diffs, brightArcs, 1), scoreOf(diffs, darkArcs, -1))
				currentHasAny = true
			}
			// The row above can now be told apart from its neighbours on both sides: only the strongest of three each way stays
			if (y > BORDER && previousHasAny) {
				val rowOffset = (y - 1) * width
				for (x in BORDER until width - BORDER) {
					val score = previous[x]
					if (score == 0) continue
					if (score > previous[x - 1] && score >= previous[x + 1] &&
						score > previous2[x - 1] && score > previous2[x] && score > previous2[x + 1] &&
						score >= current[x - 1] && score >= current[x] && score >= current[x + 1]
					) {
						if (count == scratch.corners.size) {
							scratch.corners = scratch.corners.copyOf(count * 2)
							scratch.scores = scratch.scores.copyOf(count * 2)
						}
						scratch.corners[count] = rowOffset + x
						scratch.scores[count] = score
						count++
					}
				}
			}
			val spare = previous2
			previous2 = previous
			previous = current
			current = spare
			// A row with nothing in it is still clear
			if (previous2HasAny) current.fill(0, 0, width)
			previous2HasAny = previousHasAny
			previousHasAny = currentHasAny
			currentHasAny = false
		}
		if (count == 0) return 0
		return spread(level, width, count, quota, scratch)
	}

	/**
	 * The best of each part of an image before the second best of any. Going by how much points stand out alone, a page of
	 * busy art leaves next to none for a plain logo on it, and that is the very thing being looked for.
	 */
	private fun spread(level: ByteArray, width: Int, count: Int, quota: Int, scratch: Scratch): Int {
		if (scratch.order.size < count) {
			scratch.order = LongArray(count)
		}
		val order = scratch.order
		val columns = width / SPREAD_CELL + 1
		for (i in 0 until count) {
			val at = scratch.corners[i]
			val cell = (at / width / SPREAD_CELL) * columns + (at % width) / SPREAD_CELL
			// In each cell, strongest first
			order[i] = (cell.toLong() shl 42) or ((1023 - scratch.scores[i].coerceAtMost(1023)).toLong() shl 32) or at.toLong()
		}
		java.util.Arrays.sort(order, 0, count)
		val inRank = scratch.inRank
		inRank.fill(0)
		var kept = 0
		var rank = 0
		var lastCell = -1L
		for (i in 0 until count) {
			val cell = order[i] ushr 42
			if (cell != lastCell) {
				lastCell = cell
				rank = 0
			}
			if (rank < PER_CELL) {
				scratch.corners[kept] = (order[i] and 0xFFFFFFFFL).toInt()
				scratch.scores[kept] = rank
				inRank[rank]++
				kept++
			}
			rank++
		}
		if (kept <= quota) return kept
		// Everything of the best in each cell goes, then the second best, and so on until the one that does not all fit.
		// Only that one has to be told apart, by how much of a corner each really is, which takes far longer than the rest
		var deciding = 0
		var before = 0
		while (before + inRank[deciding] < quota) {
			before += inRank[deciding]
			deciding++
		}
		var result = 0
		var contenders = 0
		for (i in 0 until kept) {
			val at = scratch.corners[i]
			when {
				scratch.scores[i] < deciding -> scratch.corners[result++] = at
				scratch.scores[i] == deciding -> order[contenders++] = ((0x7FFFFFFFFL - harris(level, width, at).toLong().coerceIn(0L, 0x7FFFFFFFFL)) shl 24) or at.toLong()
			}
		}
		java.util.Arrays.sort(order, 0, contenders)
		for (i in 0 until quota - before) {
			scratch.corners[result++] = (order[i] and 0xFFFFFFL).toInt()
		}
		return result
	}

	/** Where nine in a row of the ring begin, going around. Halving what is left of a run each time takes four steps. */
	private fun arcsOf(ring: Int): Int {
		if (ring == 0) return 0
		var runs = ring or (ring shl 16)
		runs = runs and (runs ushr 1)
		runs = runs and (runs ushr 2)
		runs = runs and (runs ushr 4)
		return runs and (runs ushr 1) and 0xFFFF
	}

	/**
	 * The most that every one of an arc of nine differs by, for the best arc. Only those that made it can be the best, as all
	 * of those are over the threshold and all of the others are not, which is one or two arcs rather than sixteen.
	 *
	 * @param sign 1 for brighter than the middle, -1 for darker
	 */
	private fun scoreOf(diffs: IntArray, arcs: Int, sign: Int): Int {
		var best = 0
		var left = arcs
		while (left != 0) {
			val start = Integer.numberOfTrailingZeros(left)
			left = left and (left - 1)
			var low = Int.MAX_VALUE
			for (i in 0 until ARC) {
				val value = diffs[(start + i) and 15] * sign
				if (value < low) low = value
			}
			if (low > best) best = low
		}
		return best
	}

	private fun harris(level: ByteArray, width: Int, at: Int): Int {
		var xx = 0L
		var yy = 0L
		var xy = 0L
		for (dy in -HARRIS_RADIUS..HARRIS_RADIUS) {
			val row = at + dy * width
			for (dx in -HARRIS_RADIUS..HARRIS_RADIUS) {
				val p = row + dx
				val a = level[p - width - 1].toInt() and 0xFF
				val b = level[p - width].toInt() and 0xFF
				val c = level[p - width + 1].toInt() and 0xFF
				val d = level[p - 1].toInt() and 0xFF
				val e = level[p + 1].toInt() and 0xFF
				val f = level[p + width - 1].toInt() and 0xFF
				val g = level[p + width].toInt() and 0xFF
				val h = level[p + width + 1].toInt() and 0xFF
				val ix = (c + 2 * e + h) - (a + 2 * d + f)
				val iy = (f + 2 * g + h) - (a + 2 * b + c)
				xx += (ix * ix).toLong()
				yy += (iy * iy).toLong()
				xy += (ix * iy).toLong()
			}
		}
		val trace = (xx + yy).toDouble()
		val score = (xx.toDouble() * yy - xy.toDouble() * xy - 0.04 * trace * trace) / (1 shl 16)
		return score.coerceIn(0.0, Int.MAX_VALUE.toDouble() / 2).toInt().coerceAtLeast(1)
	}

	private fun integralOf(level: ByteArray, width: Int, height: Int, scratch: Scratch): IntArray {
		val stride = width + 1
		if (scratch.integral.size < stride * (height + 1)) {
			scratch.integral = IntArray(stride * (height + 1))
		}
		val table = scratch.integral
		table.fill(0, 0, stride)
		for (y in 0 until height) {
			var row = 0
			val offset = y * width
			val above = y * stride
			val current = above + stride
			table[current] = 0
			for (x in 0 until width) {
				row += level[offset + x].toInt() and 0xFF
				table[current + x + 1] = table[above + x + 1] + row
			}
		}
		return table
	}

	private fun describe(table: IntArray, stride: Int, x: Int, y: Int, out: LongArray, outAt: Int) {
		val pattern = pattern
		val size = BOX_RADIUS * 2 + 1
		var bit = 0
		for (word in 0 until DESCRIPTOR_LONGS) {
			var value = 0L
			for (b in 0 until 64) {
				val i = bit * 4
				val ax = x + pattern[i] - BOX_RADIUS
				val ay = y + pattern[i + 1] - BOX_RADIUS
				val bx = x + pattern[i + 2] - BOX_RADIUS
				val by = y + pattern[i + 3] - BOX_RADIUS
				val top1 = ay * stride + ax
				val bottom1 = top1 + size * stride
				val first = table[bottom1 + size] - table[bottom1] - table[top1 + size] + table[top1]
				val top2 = by * stride + bx
				val bottom2 = top2 + size * stride
				val second = table[bottom2 + size] - table[bottom2] - table[top2 + size] + table[top2]
				if (first < second) {
					value = value or (1L shl b)
				}
				bit++
			}
			out[outAt + word] = value
		}
	}

	fun distance(a: LongArray, aAt: Int, b: LongArray, bAt: Int): Int =
		java.lang.Long.bitCount(a[aAt] xor b[bAt]) +
			java.lang.Long.bitCount(a[aAt + 1] xor b[bAt + 1]) +
			java.lang.Long.bitCount(a[aAt + 2] xor b[bAt + 2]) +
			java.lang.Long.bitCount(a[aAt + 3] xor b[bAt + 3])
}

/**
 * All that a rule needs to be found by its points, and to say whether what they found really is it.
 *
 * @param width its size, at the size its points were found at
 * @param thumb a small copy, to compare with what is on a page once its points say where to look
 * @param colour see [ImageColour]: taken at this size, not that of a [PageLuma], so a small image still has some
 */
class RuleDetail(
	val width: Int,
	val height: Int,
	val keypoints: Keypoints,
	val thumbWidth: Int,
	val thumbHeight: Int,
	val thumb: ByteArray,
	val colour: ByteArray,
	/** A larger copy than [thumb], so large that letters are still letters: see [KeypointMatcher.isTheSame]. */
	val fineWidth: Int,
	val fineHeight: Int,
	val fine: ByteArray,
) {

	/**
	 * The share of columns of [fine] with anything in them that have it in more than one place. A rim or the hatching around a bubble
	 * has it in one, with nothing else to tell it from any other, where lettering, a logo or art have it in several.
	 */
	val several: Double by lazy(LazyThreadSafetyMode.PUBLICATION) {
		val counts = IntArray(32)
		for (value in fine) counts[(value.toInt() and 0xFF) / 8]++
		var mode = 0
		for (i in counts.indices) if (counts[i] > counts[mode]) mode = i
		val background = mode * 8 + 4
		var inked = 0
		var inSeveral = 0
		for (column in 0 until fineWidth) {
			var runs = 0
			var gap = SEVERAL_GAP
			for (row in 0 until fineHeight) {
				if (abs((fine[row * fineWidth + column].toInt() and 0xFF) - background) >= SEVERAL_INK) {
					if (gap >= SEVERAL_GAP) runs++
					gap = 0
				} else {
					gap++
				}
			}
			if (runs > 0) inked++
			if (runs > 1) inSeveral++
		}
		if (inked == 0) 0.0 else inSeveral.toDouble() / inked
	}

	/**
	 * How much of it is plain background. Measured on every image of 1,951 real pages: nearly all of what is found by mistake is
	 * a speech bubble, at 70% and up, as all there is to one is a rim much like any other. Logos and ads were at 63% and under.
	 */
	val blankShare: Double by lazy(LazyThreadSafetyMode.PUBLICATION) {
		val counts = IntArray(32)
		for (value in thumb) counts[(value.toInt() and 0xFF) / 8]++
		var best = 0
		for (i in counts.indices) if (counts[i] > counts[best]) best = i
		val background = best * 8 + 4
		var blank = 0
		for (value in thumb) if (abs((value.toInt() and 0xFF) - background) <= 24) blank++
		blank.toDouble() / thumb.size.coerceAtLeast(1)
	}

	/** The same grid as an [ImageSignature], for what it can say about a part of the image. */
	internal val signature: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
		val grid = ImageSignature.GRID
		val result = ByteArray(grid * grid)
		val table = thumbIntegral
		val stride = thumbWidth + 1
		for (row in 0 until grid) {
			val top = thumbHeight * row / grid
			val bottom = (thumbHeight * (row + 1) / grid).coerceAtLeast(top + 1).coerceAtMost(thumbHeight)
			for (column in 0 until grid) {
				val left = thumbWidth * column / grid
				val right = (thumbWidth * (column + 1) / grid).coerceAtLeast(left + 1).coerceAtMost(thumbWidth)
				val sum = table[bottom * stride + right] - table[top * stride + right] - table[bottom * stride + left] + table[top * stride + left]
				result[row * grid + column] = (sum / ((bottom - top) * (right - left)).coerceAtLeast(1)).toByte()
			}
		}
		result
	}

	/** Sums of [thumb], for averaging any part of it. */
	internal val thumbIntegral: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
		val stride = thumbWidth + 1
		val table = IntArray(stride * (thumbHeight + 1))
		for (y in 0 until thumbHeight) {
			var row = 0
			for (x in 0 until thumbWidth) {
				row += thumb[y * thumbWidth + x].toInt() and 0xFF
				table[(y + 1) * stride + x + 1] = table[y * stride + x + 1] + row
			}
		}
		table
	}

	fun encode(): ByteArray {
		val n = keypoints.count
		val buffer = ByteBuffer.allocate(1 + 2 * 7 + colour.size + thumb.size + fine.size + n * (9 + 8 * ImageKeypoints.DESCRIPTOR_LONGS))
		buffer.put(FORMAT)
		buffer.putShort(width.toShort())
		buffer.putShort(height.toShort())
		buffer.putShort(thumbWidth.toShort())
		buffer.putShort(thumbHeight.toShort())
		buffer.putShort(n.toShort())
		buffer.putShort(fineWidth.toShort())
		buffer.putShort(fineHeight.toShort())
		buffer.put(colour)
		buffer.put(thumb)
		buffer.put(fine)
		for (i in 0 until n) {
			buffer.putFloat(keypoints.x[i])
			buffer.putFloat(keypoints.y[i])
		}
		for (value in keypoints.descriptors) {
			buffer.putLong(value)
		}
		buffer.put(keypoints.levels, 0, n)
		return buffer.array()
	}

	companion object {

		/** The size an image is looked over at, as with a page. Smaller, it stays as it is. */
		const val MAX_WIDTH = 640

		const val POINTS = 300
		private const val MAX_POINTS = 600

		private const val FORMAT: Byte = 2
		private const val THUMB_WIDTH = 64
		private const val THUMB_MAX_HEIGHT = 256
		private const val SEVERAL_GAP = 3
		private const val SEVERAL_INK = 48

		/** The hatching around a shouted bubble came to 0.29, and it matches other hatching that is not the same at all. */
		const val MIN_SEVERAL = 0.3
		// At 128 wide and 16,384 in all, twice as many small edits got through as at this, and an added word on 2 real pages
		private const val FINE_MAX_WIDTH = 160
		private const val FINE_MAX_PIXELS = 25600
		const val COLOUR_GRID = 8

		/** @return null for one made by a version that found its points another way, which cannot be compared with these */
		fun decode(bytes: ByteArray): RuleDetail? {
			val buffer = ByteBuffer.wrap(bytes)
			if (bytes.size < 15 || buffer.get() != FORMAT) return null
			val width = buffer.getShort().toInt() and 0xFFFF
			val height = buffer.getShort().toInt() and 0xFFFF
			val thumbWidth = buffer.getShort().toInt() and 0xFFFF
			val thumbHeight = buffer.getShort().toInt() and 0xFFFF
			val n = buffer.getShort().toInt() and 0xFFFF
			val fineWidth = buffer.getShort().toInt() and 0xFFFF
			val fineHeight = buffer.getShort().toInt() and 0xFFFF
			val colour = ByteArray(COLOUR_GRID * COLOUR_GRID * 2)
			buffer.get(colour)
			val thumb = ByteArray(thumbWidth * thumbHeight)
			buffer.get(thumb)
			val fine = ByteArray(fineWidth * fineHeight)
			buffer.get(fine)
			val x = FloatArray(n)
			val y = FloatArray(n)
			for (i in 0 until n) {
				x[i] = buffer.getFloat()
				y[i] = buffer.getFloat()
			}
			val descriptors = LongArray(n * ImageKeypoints.DESCRIPTOR_LONGS)
			for (i in descriptors.indices) {
				descriptors[i] = buffer.getLong()
			}
			val levels = ByteArray(n)
			buffer.get(levels)
			return RuleDetail(width, height, Keypoints(n, x, y, descriptors, levels), thumbWidth, thumbHeight, thumb, colour, fineWidth, fineHeight, fine)
		}

		/**
		 * From the rows of a decoded image, shrinking it to the size it is looked over at.
		 *
		 * @param rowAt gives a row of ARGB, as a bitmap does
		 */
		fun ofRows(decodedWidth: Int, decodedHeight: Int, sourceWidth: Int, sourceHeight: Int, rowAt: (Int) -> IntArray): RuleDetail {
			val width = PageDetail.widthFor(sourceWidth, sourceHeight)
			val height = PageDetail.heightFor(sourceWidth, sourceHeight)
			val reds = AreaShrinker(decodedWidth, decodedHeight, width, height)
			val greens = AreaShrinker(decodedWidth, decodedHeight, width, height)
			val blues = AreaShrinker(decodedWidth, decodedHeight, width, height)
			val red = IntArray(decodedWidth)
			val green = IntArray(decodedWidth)
			val blue = IntArray(decodedWidth)
			for (y in 0 until decodedHeight) {
				val row = rowAt(y)
				for (x in 0 until decodedWidth) {
					red[x] = row[x] shr 16 and 0xFF
					green[x] = row[x] shr 8 and 0xFF
					blue[x] = row[x] and 0xFF
				}
				reds.addRow(red)
				greens.addRow(green)
				blues.addRow(blue)
			}
			val rgb = ByteArray(width * height * 3)
			for (i in 0 until width * height) {
				rgb[i * 3] = reds.output[i]
				rgb[i * 3 + 1] = greens.output[i]
				rgb[i * 3 + 2] = blues.output[i]
			}
			// A long image is only ever partly on a page, and the part there has to have enough of them
			val points = (POINTS * height / width.coerceAtLeast(1)).coerceIn(POINTS, MAX_POINTS)
			return of(rgb, width, height, points)
		}

		/** @param rgb three bytes per pixel, at no more than [MAX_WIDTH] across */
		fun of(rgb: ByteArray, width: Int, height: Int, points: Int = POINTS): RuleDetail {
			val gray = ByteArray(width * height)
			val blue = IntArray(COLOUR_GRID * COLOUR_GRID)
			val red = IntArray(COLOUR_GRID * COLOUR_GRID)
			val counts = IntArray(COLOUR_GRID * COLOUR_GRID)
			for (y in 0 until height) {
				val cellY = y * COLOUR_GRID / height
				// The outer cells only from their inner half, as on a page. See compareColour
				val rowCounts = y * COLOUR_GRID * 2 / height in 1 until COLOUR_GRID * 2 - 1
				for (x in 0 until width) {
					val at = (y * width + x) * 3
					val r = rgb[at].toInt() and 0xFF
					val g = rgb[at + 1].toInt() and 0xFF
					val b = rgb[at + 2].toInt() and 0xFF
					gray[y * width + x] = ((77 * r + 150 * g + 29 * b) shr 8).toByte()
					if (!rowCounts || x * COLOUR_GRID * 2 / width !in 1 until COLOUR_GRID * 2 - 1) continue
					val cell = cellY * COLOUR_GRID + x * COLOUR_GRID / width
					blue[cell] += (-43 * r - 85 * g + 128 * b)
					red[cell] += (128 * r - 107 * g - 21 * b)
					counts[cell]++
				}
			}
			val colour = ByteArray(COLOUR_GRID * COLOUR_GRID * 2)
			for (i in counts.indices) {
				val count = counts[i].coerceAtLeast(1)
				colour[i] = (128 + blue[i] / count / 256).coerceIn(0, 255).toByte()
				colour[COLOUR_GRID * COLOUR_GRID + i] = (128 + red[i] / count / 256).coerceIn(0, 255).toByte()
			}
			var thumbWidth = minOf(width, THUMB_WIDTH)
			var thumbHeight = (height.toLong() * thumbWidth / width).toInt().coerceAtLeast(1)
			if (thumbHeight > THUMB_MAX_HEIGHT) {
				thumbHeight = THUMB_MAX_HEIGHT
				thumbWidth = (width.toLong() * thumbHeight / height).toInt().coerceAtLeast(1)
			}
			val shrinker = AreaShrinker(width, height, thumbWidth, thumbHeight)
			val row = IntArray(width)
			for (y in 0 until height) {
				for (x in 0 until width) {
					row[x] = gray[y * width + x].toInt() and 0xFF
				}
				shrinker.addRow(row)
			}
			val keypoints = ImageKeypoints.ofImage(gray, width, height, points)
			val fineShare = minOf(1.0, Math.sqrt(FINE_MAX_PIXELS.toDouble() / (width.toLong() * height).coerceAtLeast(1L)), FINE_MAX_WIDTH.toDouble() / width)
			val fineWidth = (width * fineShare).roundToInt().coerceIn(1, width)
			val fineHeight = (height * fineShare).roundToInt().coerceIn(1, height)
			val fineShrinker = AreaShrinker(width, height, fineWidth, fineHeight)
			for (y in 0 until height) {
				for (x in 0 until width) {
					row[x] = gray[y * width + x].toInt() and 0xFF
				}
				fineShrinker.addRow(row)
			}
			return RuleDetail(width, height, keypoints, thumbWidth, thumbHeight, shrinker.output, colour, fineWidth, fineHeight, fineShrinker.output)
		}
	}
}

/** A rule that may be on a page, and where it would be if so. */
class KeypointCandidate(val rule: Int, val agreeing: Int, val scale: Float, val x: Float, val y: Float)

/** Where a rule was found by its points, on a [PageDetail], which can hang off it. And what the points came to. */
class KeypointMatch(
	val ruleIndex: Int,
	val left: Double,
	val top: Double,
	val right: Double,
	val bottom: Double,
	val agreeing: Int,
	val scale: Double,
)

/**
 * Finds rules on a page by their points. With hundreds of rules, comparing every point of each with every point of a page
 * would take seconds on a phone. So all their points go in tables by pieces of what they look like, and a point of a page
 * only looks in the row of each table its own pieces say. Only a rule with a good many points turned up that way gets
 * looked at properly, which on most pages is none.
 */
class KeypointIndex(val details: List<RuleDetail>, private val maxBucket: Int = MAX_BUCKET) {

	private val owners: IntArray
	private val firstOf: IntArray
	private val descriptors: LongArray
	private val starts: Array<IntArray>
	private val entries: Array<IntArray>
	private val total: Int

	init {
		var count = 0
		for (detail in details) count += detail.keypoints.count
		total = count
		owners = IntArray(count)
		firstOf = IntArray(details.size + 1)
		descriptors = LongArray(count * ImageKeypoints.DESCRIPTOR_LONGS)
		var at = 0
		details.forEachIndexed { index, detail ->
			firstOf[index] = at
			val points = detail.keypoints
			points.descriptors.copyInto(descriptors, at * ImageKeypoints.DESCRIPTOR_LONGS, 0, points.count * ImageKeypoints.DESCRIPTOR_LONGS)
			owners.fill(index, at, at + points.count)
			at += points.count
		}
		firstOf[details.size] = at
		starts = Array(TABLES) { IntArray(BUCKETS + 1) }
		entries = Array(TABLES) { IntArray(count * 2) }
		for (table in 0 until TABLES) {
			val start = starts[table]
			for (i in 0 until count) {
				start[bucketOf(descriptors, i * ImageKeypoints.DESCRIPTOR_LONGS, table) + 1]++
			}
			for (bucket in 0 until BUCKETS) {
				start[bucket + 1] += start[bucket]
			}
			val fill = start.copyOf()
			val entry = entries[table]
			for (i in 0 until count) {
				val at = i * ImageKeypoints.DESCRIPTOR_LONGS
				val place = fill[bucketOf(descriptors, at, table)]++ * 2
				entry[place] = i
				entry[place + 1] = sampleOf(descriptors, at, table)
			}
		}
	}

	/**
	 * Which rules have enough of their points on this page, best first.
	 *
	 * Counting points that look alike is not enough: text looks like other text, so a rule with any text in it has plenty on
	 * any page that has some. What sets the real thing apart is that its points all say the image is in one place. Each
	 * says where it would be, going by the sizes the two were found at, which is rough. So roughly is how it is counted.
	 */
	fun candidates(page: Keypoints): List<KeypointCandidate> {
		if (total == 0 || page.count == 0) return emptyList()
		var hits = LongArray(4096)
		var hitCount = 0
		val query = page.descriptors
		// Only the closest few of each point of the page. Corners of letters look alike, and one on a page has hundreds of
		// others that look like it among hundreds of rules, which was nearly all of the time this took
		val closest = IntArray(CLOSEST)
		val distances = IntArray(CLOSEST)
		for (point in 0 until page.count) {
			val at = point * ImageKeypoints.DESCRIPTOR_LONGS
			var kept = 0
			var limit = MAX_LOOKUP_DISTANCE
			for (table in 0 until TABLES) {
				val bucket = bucketOf(query, at, table)
				val from = starts[table][bucket]
				val to = starts[table][bucket + 1]
				if (to - from > maxBucket) continue
				val entry = entries[table]
				val sample = sampleOf(query, at, table)
				for (e in from until to) {
					// Going to every descriptor would be a trip to memory that a slow processor's cache does not have for each,
					// and most of them go no further than this, which is kept right here
					if (Integer.bitCount(entry[e * 2 + 1] xor sample) > MAX_SAMPLE_DISTANCE) continue
					val other = entry[e * 2]
					val distance = ImageKeypoints.distance(query, at, descriptors, other * ImageKeypoints.DESCRIPTOR_LONGS)
					if (distance > limit) continue
					// From another table, it may be here already
					var place = 0
					var isHere = false
					while (place < kept) {
						if (closest[place] == other) {
							isHere = true
							break
						}
						place++
					}
					if (isHere) continue
					place = if (kept < CLOSEST) kept++ else CLOSEST - 1
					while (place > 0 && distances[place - 1] > distance) {
						closest[place] = closest[place - 1]
						distances[place] = distances[place - 1]
						place--
					}
					closest[place] = other
					distances[place] = distance
					if (kept == CLOSEST) limit = distances[CLOSEST - 1] - 1
				}
			}
			for (i in 0 until kept) {
				if (hitCount == hits.size) hits = hits.copyOf(hitCount * 2)
				hits[hitCount++] = (closest[i].toLong() shl 32) or point.toLong()
			}
		}
		java.util.Arrays.sort(hits, 0, hitCount)
		val counts = IntArray(VOTE_BINS)
		// Each point counts once: one on either side can look like many on the other, and that says nothing
		val lastOwn = IntArray(VOTE_BINS) { -1 }
		val lastPage = IntArray(page.count) { -1 }
		val touched = IntArray(hitCount.coerceAtMost(MAX_RULE_HITS))
		val binOf = IntArray(hitCount.coerceAtMost(MAX_RULE_HITS))
		val pairs = IntArray(MAX_PEAK_PAIRS)
		val chosen = ArrayList<KeypointCandidate>()
		var i = 0
		while (i < hitCount) {
			val rule = owners[(hits[i] ushr 32).toInt()]
			val detail = details[rule]
			val first = firstOf[rule]
			val points = detail.keypoints
			var touchedCount = 0
			var peak = 0
			var peakBin = 0
			var previous = -1L
			val ruleFrom = i
			while (i < hitCount && owners[(hits[i] ushr 32).toInt()] == rule) {
				val hit = hits[i++]
				// The same pair again, from another table
				if (i - 1 - ruleFrom < binOf.size) binOf[i - 1 - ruleFrom] = -1
				if (hit == previous) continue
				previous = hit
				if (touchedCount == touched.size) continue
				val own = (hit ushr 32).toInt() - first
				val other = (hit and 0xFFFFFFFFL).toInt()
				val scale = SCALES[page.levels[other] - points.levels[own] + SCALE_MIDDLE]
				val binWidth = maxOf(MIN_BIN, scale * detail.width * BIN_SHARE)
				val binHeight = maxOf(MIN_BIN, scale * detail.height * BIN_SHARE)
				val centreX = page.x[other] - scale * (points.x[own] - detail.width / 2f)
				val centreY = page.y[other] - scale * (points.y[own] - detail.height / 2f)
				val key = (page.levels[other] - points.levels[own]) * 31 * 31 * 31 +
					Math.floor((centreX / binWidth).toDouble()).toInt() * 31 * 31 +
					Math.floor((centreY / binHeight).toDouble()).toInt()
				val bin = (key * -1640531527 ushr 16) and (VOTE_BINS - 1)
				val stamp = rule * VOTE_BINS + bin
				if (lastOwn[bin] == own || lastPage[other] == stamp) continue
				lastOwn[bin] = own
				lastPage[other] = stamp
				touched[touchedCount++] = bin
				if (i - 1 - ruleFrom < binOf.size) binOf[i - 1 - ruleFrom] = bin
				val count = ++counts[bin]
				if (count > peak) {
					peak = count
					peakBin = bin
				}
			}
			for (t in 0 until touchedCount) {
				counts[touched[t]] = 0
				lastOwn[touched[t]] = -1
			}
			if (peak >= MIN_PEAK) {
				agreement(hits, ruleFrom, i, first, peakBin, binOf, points, page, pairs, rule)?.let { chosen += it }
			}
		}
		chosen.sortByDescending { it.agreeing }
		return chosen
	}

	/**
	 * How many of a rule's lookalikes fit one exact place and size, tried from pairs of those roughly there already. Only
	 * this tells a real copy from text that looks like other text around the same part of a page, and it takes next to
	 * no time, as there are few of them to go through, compared with every point on each side.
	 */
	private fun agreement(
		hits: LongArray,
		from: Int,
		to: Int,
		first: Int,
		peakBin: Int,
		binOf: IntArray,
		points: Keypoints,
		page: Keypoints,
		pairs: IntArray,
		rule: Int,
	): KeypointCandidate? {
		var pairCount = 0
		val end = minOf(to, from + binOf.size)
		for (h in from until end) {
			if (binOf[h - from] == peakBin && pairCount < pairs.size) {
				pairs[pairCount++] = h
			}
		}
		if (pairCount < 2) return null
		var best = 0
		var bestScale = 1f
		var bestX = 0f
		var bestY = 0f
		var seed = 0x2545F491L + pairCount
		for (attempt in 0 until AGREEMENT_TRIES) {
			seed = seed * 6364136223846793005L + 1442695040888963407L
			val a = hits[pairs[((seed ushr 33) % pairCount).toInt()]]
			seed = seed * 6364136223846793005L + 1442695040888963407L
			val b = hits[pairs[((seed ushr 33) % pairCount).toInt()]]
			val ownA = (a ushr 32).toInt() - first
			val ownB = (b ushr 32).toInt() - first
			if (ownA == ownB) continue
			val otherA = (a and 0xFFFFFFFFL).toInt()
			val otherB = (b and 0xFFFFFFFFL).toInt()
			val ruleDx = points.x[ownB] - points.x[ownA]
			val ruleDy = points.y[ownB] - points.y[ownA]
			val pageDx = page.x[otherB] - page.x[otherA]
			val pageDy = page.y[otherB] - page.y[otherA]
			val ruleSquare = ruleDx * ruleDx + ruleDy * ruleDy
			val pageSquare = pageDx * pageDx + pageDy * pageDy
			if (ruleSquare < 64f || pageSquare < 1f) continue
			val ratio = pageSquare / ruleSquare
			// From 0.2 to 6 times the size, and not turned
			if (ratio < 0.04f || ratio > 36f) continue
			val cross = ruleDx * pageDy - ruleDy * pageDx
			if (cross * cross > 0.0036f * ruleSquare * pageSquare) continue
			val scale = Math.sqrt(ratio.toDouble()).toFloat()
			val tx = page.x[otherA] - scale * points.x[ownA]
			val ty = page.y[otherA] - scale * points.y[ownA]
			var count = 0
			var lastOwn = -1
			for (h in from until end) {
				val hit = hits[h]
				val own = (hit ushr 32).toInt() - first
				if (own == lastOwn) continue
				val other = (hit and 0xFFFFFFFFL).toInt()
				val dx = scale * points.x[own] + tx - page.x[other]
				val dy = scale * points.y[own] + ty - page.y[other]
				if (dx <= AGREEMENT_ERROR && dx >= -AGREEMENT_ERROR && dy <= AGREEMENT_ERROR && dy >= -AGREEMENT_ERROR) {
					count++
					lastOwn = own
				}
			}
			if (count > best) {
				best = count
				bestScale = scale
				bestX = tx
				bestY = ty
			}
		}
		if (best < MIN_AGREEMENT) return null
		// All that agree say where it is better than the two that were tried
		var sumAx = 0f
		var sumAy = 0f
		var sumBx = 0f
		var sumBy = 0f
		var n = 0
		var sumAA = 0f
		var sumAB = 0f
		var lastOwn = -1
		for (h in from until end) {
			val hit = hits[h]
			val own = (hit ushr 32).toInt() - first
			if (own == lastOwn) continue
			val other = (hit and 0xFFFFFFFFL).toInt()
			val dx = bestScale * points.x[own] + bestX - page.x[other]
			val dy = bestScale * points.y[own] + bestY - page.y[other]
			if (dx <= AGREEMENT_ERROR && dx >= -AGREEMENT_ERROR && dy <= AGREEMENT_ERROR && dy >= -AGREEMENT_ERROR) {
				sumAx += points.x[own]
				sumAy += points.y[own]
				sumBx += page.x[other]
				sumBy += page.y[other]
				sumAA += points.x[own] * points.x[own] + points.y[own] * points.y[own]
				sumAB += points.x[own] * page.x[other] + points.y[own] * page.y[other]
				n++
				lastOwn = own
			}
		}
		// Going by all that agree, not the two tried, or a wrong size at the far end of a large image fails the quick look
		val spread = sumAA - (sumAx * sumAx + sumAy * sumAy) / n
		val scale = if (n >= 6 && spread > 0f) ((sumAB - (sumAx * sumBx + sumAy * sumBy) / n) / spread).takeIf { it >= bestScale * 0.9f && it <= bestScale * 1.1f } ?: bestScale else bestScale
		return KeypointCandidate(rule, best, scale, sumBx / n - scale * sumAx / n, sumBy / n - scale * sumAy / n)
	}

	/** 32 bits a long way from the ones this table goes by. */
	private fun sampleOf(values: LongArray, at: Int, table: Int): Int {
		val bit = (table * (256 / TABLES) + 128) and 255
		val word = bit / 64
		val shift = bit and 63
		val low = values[at + word] ushr shift
		return (if (shift == 0) low else low or (values[at + ((word + 1) and 3)] shl (64 - shift))).toInt()
	}

	private fun bucketOf(values: LongArray, at: Int, table: Int): Int {
		// Tables take evenly spread pieces of the 256 bits, so that they have little to do with one another
		val bit = table * (256 / TABLES)
		val word = bit / 64
		val shift = bit and 63
		val low = values[at + word] ushr shift
		val value = if (shift + TABLE_BITS <= 64) low else low or (values[at + ((word + 1) and 3)] shl (64 - shift))
		return (value and (BUCKETS - 1).toLong()).toInt()
	}

	companion object {
		private const val TABLES = 8
		private const val TABLE_BITS = 13
		private const val BUCKETS = 1 shl TABLE_BITS
		
		/** Measured from 8 to 16 on 7,524 samples: no fewer copies found at the lowest, for half the work of the highest. */
		private const val CLOSEST = 8
		private const val MAX_SAMPLE_DISTANCE = 12

		/** Points on flat colour all look alike, and a row with this many in it says nothing about which rule this is. */
		private const val MAX_BUCKET = 512
		private const val MAX_LOOKUP_DISTANCE = 40
		private const val MIN_PEAK = 4
		private const val BIN_SHARE = 0.25f
		private const val MIN_BIN = 12f
		private const val VOTE_BINS = 1 shl 12
		private const val MIN_AGREEMENT = 4
		private const val AGREEMENT_TRIES = 48
		private const val AGREEMENT_ERROR = 3f
		private const val MAX_PEAK_PAIRS = 256
		private const val MAX_RULE_HITS = 1 shl 16

		private const val SCALE_MIDDLE = 8
		private val SCALES = FloatArray(SCALE_MIDDLE * 2 + 1) { Math.pow(1.2, (it - SCALE_MIDDLE).toDouble()).toFloat() }
	}
}

/**
 * Whether a rule is on a page, going by its points and then by what is really there.
 *
 * Points agreeing is not enough on its own: the rim of a bubble has a few that agree with the rim of any other. So once
 * they say where it would be, what is there has to be that very image, in its colours, and for an image cut off by the
 * page only what is on the page counts.
 */
object KeypointMatcher {

	private const val RATIO = 0.85
	private const val MAX_DISTANCE = 64
	private const val MIN_GOOD = 8
	private const val RANSAC_TRIES = 400
	private const val QUICK_VISIBLE_SLACK = 0.1
	private const val QUICK_CORRELATION = 0.8
	private const val QUICK_FAR_SHARE = 0.1
	private const val QUICK_SHIFT = 16.0
	private const val MAX_SHORTLIST = 16
	private const val MAX_ERROR = 3.0
	private const val MIN_SCALE = 0.2
	private const val MAX_SCALE = 6.0
	private const val MAX_TURN = 0.06

	/** How the points that agree have to be spread out over an image, on a grid of this many cells each way. */
	private const val COVER_GRID = 6

	/** From 66 images on 7,524 sample pages and every image on 1,951 real ones, checking what they found by eye. See [isTheSame]. */
	const val MIN_AGREEING = 10
	const val MIN_AGREEING_BLANK = 70
	const val MIN_AGREEING_BLANK_COMPARED = 14
	private const val NEARLY_ALL_VISIBLE = 0.95
	private const val MIN_BLOCKS_BLANK = 4
	private const val MIN_VISIBLE_SEVERAL = 0.05
	private const val VISIBLE_SEVERAL_GAP = 2
	private const val VISIBLE_SEVERAL_INK = 48f

	/** A small logo has few points to spread out. What needs them spread is a rim, and that is what mostly blank goes by. */
	const val MIN_COVER = 4
	const val MIN_COVER_BLANK = 6

	/** Spots along a curved band of colour have plenty that agree, where two rims that merely look alike do not. */
	const val MIN_AGREEING_CURVE = 40
	const val MIN_CORRELATION = 0.97
	const val MAX_FAR_SHARE = 0.05
	private const val FAR_DIFFERENCE = 40.0
	private const val MOSTLY_BLANK = 0.68
	const val MAX_BRIGHTNESS_SHIFT = 8.0

	/**
	 * Less than half of it on a page is left to the page with the rest of it, where it is found and has this removed too. On
	 * real pages, nearly all that was found with less than this was the top of a bubble, cut off where another one was.
	 */
	const val MIN_VISIBLE = 0.5

	/** Pages end at their top and bottom. An image off the side is half of one image lined up with something else altogether. */
	const val MAX_OVERHANG = 0.05

	/**
	 * Over the cells with a colour on either side, the same cells on both. Copies came to 3.8 and 13 degrees at the 99th percentile,
	 * where going by each side's own coloured cells they came to 6.7 and 22: a pale image has cells on the edge of counting, and
	 * which ones do differs from copy to copy.
	 */
	const val MAX_COLOUR_DIFFERENCE = 12.0
	private const val MIN_COLOUR_DIFFERENCE_ALLOWED = 3.0
	private const val MAX_COLOUR_DIFFERENCE_SHARE = 0.25
	const val MAX_HUE_DEGREES = 25.0

	/** With a strong colour, which colour it is has little room to change. */
	private const val STRONG_COLOUR = 12.0
	private const val MAX_HUE_DEGREES_STRONG = 12.0

	/** All the colour there is on the page is in a row cut off by its edge, which the rule has more of. Which colour still tells. */
	private const val MAX_HUE_DEGREES_PARTLY = 15.0
	private const val MIN_COLOURFUL = 8
	private const val MAX_ONE_SIDED_COLOUR = 2
	private const val MAX_FOUND_PER_RULE = 4

	/** What a look at one rule came to, for measuring. */
	class Evidence(
		val agreeing: Int,
		val cover: Int,
		val correlation: Double,
		val farShare: Double,
		val shift: Double,
		val visible: Double,
		/** How much of it would be off the side of the page, as a share of its width. */
		val overhang: Double,
		val colourDifference: Double,
		val hue: Double,
		/** Whether all that is on the page of it is one curve, which says nothing about what the rest of it is. */
		val isOnlyACurve: Boolean,
		/** See [RuleDetail.blankShare]. */
		val isMostlyBlank: Boolean,
		val colourfulRule: Int,
		val colourfulPage: Int,
		/** Cell by cell: on average, and at the worst cell. A fit is close enough now for the same cells to be the same part of it. */
		val cellColour: Double,
		val worstCellColour: Double,
		/** How strong the colours are, the stronger of the two sides. */
		val colourStrength: Double,
		/** Whether all that has a colour is in cells only partly on the page, so [colourDifference] has more to it than a difference. */
		val isColourPartly: Boolean,
		val left: Double,
		val top: Double,
		val right: Double,
		val bottom: Double,
		val scale: Double,
		private val compareFinely: () -> FineComparison,
	) {
		/** Only worked out if everything else about it passes, as next to nothing does. */
		val fine: FineComparison by lazy(LazyThreadSafetyMode.NONE, compareFinely)
	}

	/** See [compareFine]. */
	class FineComparison(
		/** Of all of it, at the place it fits best. */
		val correlation: Double,
		/** How many blocks have something in them, and of those how many have something else on the page. */
		val blocks: Int,
		val farBlocks: Int,
		/** The most of those all touching: copies have the odd one here and there, and what was changed is all in one place. */
		val farTogether: Int,
		/** See [severalOf]: whether what is on the page of it is more than a rim. */
		val several: Double,
	)

	/** What stands out on a page, worked out once. */
	fun keypointsOf(page: PageDetail): Keypoints {
		page.keypoints?.let { return it }
		val result = ImageKeypoints.withScratch { scratch ->
			ImageKeypoints.join(List(ImageKeypoints.tilesOf(page.height)) { ImageKeypoints.ofTile(page, it, scratch) })
		}
		page.keypoints = result
		return result
	}

	fun find(page: PageDetail, index: KeypointIndex, points: Keypoints = keypointsOf(page)): List<KeypointMatch> {
		val found = ArrayList<KeypointMatch>()
		for (rule in shortlist(page, index, points)) {
			findRule(page, points, index.details[rule], rule, found)
		}
		return found
	}

	/**
	 * The rules worth comparing every point of, which takes a while. Comparing what is on the page where the lookalikes say
	 * it would be takes next to no time, and hardly any of what gets that far is really there.
	 */
	fun shortlist(page: PageDetail, index: KeypointIndex, points: Keypoints, limit: Int = MAX_SHORTLIST): IntArray {
		val kept = ArrayList<Int>()
		for (candidate in index.candidates(points)) {
			val rule = index.details[candidate.rule]
			// Where it would be says as much as what is there for many of them, for no work, and the page's sums often go unbuilt
			if (!canBeThere(page, rule, candidate)) continue
			val e = verify(page, rule, candidate.scale.toDouble(), candidate.x.toDouble(), candidate.y.toDouble(), candidate.agreeing, 0) ?: continue
			if (e.correlation >= QUICK_CORRELATION && e.farShare <= QUICK_FAR_SHARE && e.shift <= QUICK_SHIFT && e.visible >= MIN_VISIBLE - QUICK_VISIBLE_SLACK) {
				kept += candidate.rule
				if (kept.size == limit) break
			}
		}
		return kept.toIntArray()
	}

	private fun canBeThere(page: PageDetail, rule: RuleDetail, candidate: KeypointCandidate): Boolean {
		val width = rule.width * candidate.scale
		val height = rule.height * candidate.scale
		val overhang = maxOf(0f, -candidate.x, candidate.x + width - page.width) / width.coerceAtLeast(1f)
		val visibleRows = minOf(candidate.y + height, page.height.toFloat()) - maxOf(candidate.y, 0f)
		// With room to spare, as this is only roughly where it is
		return overhang <= MAX_OVERHANG + QUICK_VISIBLE_SLACK && visibleRows / height.coerceAtLeast(1f) >= MIN_VISIBLE - QUICK_VISIBLE_SLACK
	}

	/** May be there more than once, so what was found is taken away and it is looked for again. */
	private fun findRule(page: PageDetail, all: Keypoints, rule: RuleDetail, ruleIndex: Int, into: MutableList<KeypointMatch>) {
		var points = all
		repeat(MAX_FOUND_PER_RULE) {
			val evidence = lookFor(page, points, rule) ?: return
			if (!isTheSame(evidence)) return
			into += KeypointMatch(ruleIndex, evidence.left, evidence.top, evidence.right, evidence.bottom, evidence.agreeing, evidence.scale)
			points = without(points, evidence)
			if (points.count < MIN_GOOD) return
		}
	}

	fun isTheSame(e: Evidence): Boolean {
		if ((e.colourfulRule == 0 && e.colourfulPage > MAX_ONE_SIDED_COLOUR) || (e.colourfulPage == 0 && e.colourfulRule > MAX_ONE_SIDED_COLOUR)) {
			return false
		}
		if (e.agreeing < MIN_AGREEING ||
			e.cover < MIN_COVER ||
			e.correlation < MIN_CORRELATION ||
			e.farShare > MAX_FAR_SHARE ||
			e.shift > MAX_BRIGHTNESS_SHIFT ||
			e.visible < MIN_VISIBLE ||
			e.overhang > MAX_OVERHANG ||
			!isSameColour(e.colourDifference, e.hue, e.colourStrength, e.isColourPartly)
		) {
			return false
		}
		// Next to nothing gets this far on a page that does not have it, and this is what tells it from all that looks like it
		val fine = e.fine
		if (fine.farBlocks > fine.blocks * MAX_FAR_BLOCKS_SHARE || fine.farTogether > MAX_FAR_BLOCKS_TOGETHER) {
			return false
		}
		if (e.isMostlyBlank) {
			// Big lettering on white has its letters compared just now. Part of a bubble can be all rim, with what it says on the
			// part that is not, and then all there is to go by is how many points agree, and how widely
			val isCompared = !e.isOnlyACurve && fine.blocks >= MIN_BLOCKS_BLANK && (e.visible >= NEARLY_ALL_VISIBLE || fine.several >= MIN_VISIBLE_SEVERAL)
			return if (isCompared) {
				e.agreeing >= MIN_AGREEING_BLANK_COMPARED
			} else {
				e.agreeing >= MIN_AGREEING_BLANK && e.cover >= MIN_COVER_BLANK
			}
		}
		if (!e.isOnlyACurve || e.agreeing >= MIN_AGREEING_CURVE) {
			return true
		}
		// A soft band of colour is no rim: a rim is sharp, and has something to compare all along it
		return fine.blocks <= MAX_SOFT_BLOCKS && fine.correlation >= MIN_SOFT_CORRELATION
	}

	/**
	 * Copies differ most in how strong a colour is, at the edge of what is on a page, and hardly in which colour it is. The same
	 * image in other colours is the other way round.
	 */
	private fun isSameColour(difference: Double, hue: Double, strength: Double, isPartly: Boolean): Boolean {
		if (difference > MAX_COLOUR_DIFFERENCE) return false
		if (isPartly) return hue <= MAX_HUE_DEGREES_PARTLY
		return hue <= (if (strength >= STRONG_COLOUR) MAX_HUE_DEGREES_STRONG else MAX_HUE_DEGREES) &&
			difference <= maxOf(MIN_COLOUR_DIFFERENCE_ALLOWED, strength * MAX_COLOUR_DIFFERENCE_SHARE)
	}

	/**
	 * Whether what is at this place on a page has the rule's colours, at a size that can tell. The other ways of finding an
	 * image go by a far smaller copy, where a small gold logo and the same one in red come to almost the same thing.
	 */
	fun isSameColourAt(page: PageDetail, rule: RuleDetail, left: Double, top: Double, right: Double): Boolean {
		if (right <= left) return true
		val scale = (right - left) / rule.width
		val onLeft = floor(left).toInt().coerceIn(0, page.width)
		val onTop = floor(top).toInt().coerceIn(0, page.height)
		val onRight = ceil(right).toInt().coerceIn(onLeft, page.width)
		val onBottom = ceil(top + rule.height * scale).toInt().coerceIn(onTop, page.height)
		val colour = compareColour(page, rule, scale, left, top, onLeft, onTop, onRight, onBottom)
		if ((colour[2] == 0.0 && colour[3] > MAX_ONE_SIDED_COLOUR) || (colour[3] == 0.0 && colour[2] > MAX_ONE_SIDED_COLOUR)) {
			return false
		}
		return isSameColour(colour[0], colour[1], colour[6], colour[7] != 0.0)
	}

	private fun without(points: Keypoints, e: Evidence): Keypoints {
		val keep = IntArray(points.count)
		var kept = 0
		for (i in 0 until points.count) {
			if (points.x[i] < e.left || points.x[i] >= e.right || points.y[i] < e.top || points.y[i] >= e.bottom) {
				keep[kept++] = i
			}
		}
		val x = FloatArray(kept)
		val y = FloatArray(kept)
		val descriptors = LongArray(kept * ImageKeypoints.DESCRIPTOR_LONGS)
		val levels = ByteArray(kept)
		for (i in 0 until kept) {
			val from = keep[i]
			levels[i] = points.levels[from]
			x[i] = points.x[from]
			y[i] = points.y[from]
			points.descriptors.copyInto(descriptors, i * ImageKeypoints.DESCRIPTOR_LONGS, from * ImageKeypoints.DESCRIPTOR_LONGS, (from + 1) * ImageKeypoints.DESCRIPTOR_LONGS)
		}
		return Keypoints(kept, x, y, descriptors, levels)
	}

	/** @return null if its points agree on no place for it */
	fun lookFor(page: PageDetail, points: Keypoints, rule: RuleDetail): Evidence? {
		val from = rule.keypoints
		if (from.count < MIN_GOOD || points.count < MIN_GOOD) return null
		val goodRule = IntArray(from.count)
		val goodPage = IntArray(from.count)
		var good = 0
		val a = from.descriptors
		val b = points.descriptors
		for (i in 0 until from.count) {
			var best = Int.MAX_VALUE
			var second = Int.MAX_VALUE
			var bestAt = -1
			val at = i * ImageKeypoints.DESCRIPTOR_LONGS
			val a0 = a[at]
			val a1 = a[at + 1]
			val a2 = a[at + 2]
			val a3 = a[at + 3]
			var other = 0
			for (j in 0 until points.count) {
				val distance = java.lang.Long.bitCount(a0 xor b[other]) +
					java.lang.Long.bitCount(a1 xor b[other + 1]) +
					java.lang.Long.bitCount(a2 xor b[other + 2]) +
					java.lang.Long.bitCount(a3 xor b[other + 3])
				if (distance < best) {
					second = best
					best = distance
					bestAt = j
				} else if (distance < second) {
					second = distance
				}
				other += ImageKeypoints.DESCRIPTOR_LONGS
			}
			if (best < MAX_DISTANCE && best < RATIO * second) {
				goodRule[good] = i
				goodPage[good] = bestAt
				good++
			}
		}
		if (good < MIN_GOOD) return null
		return fit(page, points, rule, goodRule, goodPage, good)
	}

	private fun fit(page: PageDetail, points: Keypoints, rule: RuleDetail, goodRule: IntArray, goodPage: IntArray, good: Int): Evidence? {
		val from = rule.keypoints
		var bestCount = 0
		var bestScale = 1.0
		var bestX = 0.0
		var bestY = 0.0
		var seed = 0x2545F491L + good
		for (attempt in 0 until RANSAC_TRIES) {
			seed = seed * 6364136223846793005L + 1442695040888963407L
			val first = ((seed ushr 33) % good).toInt()
			seed = seed * 6364136223846793005L + 1442695040888963407L
			val second = ((seed ushr 33) % good).toInt()
			if (first == second) continue
			val ax = from.x[goodRule[first]].toDouble()
			val ay = from.y[goodRule[first]].toDouble()
			val bx = from.x[goodRule[second]].toDouble()
			val by = from.y[goodRule[second]].toDouble()
			val px = points.x[goodPage[first]].toDouble()
			val py = points.y[goodPage[first]].toDouble()
			val qx = points.x[goodPage[second]].toDouble()
			val qy = points.y[goodPage[second]].toDouble()
			val ruleDx = bx - ax
			val ruleDy = by - ay
			val pageDx = qx - px
			val pageDy = qy - py
			val ruleLength = hypot(ruleDx, ruleDy)
			val pageLength = hypot(pageDx, pageDy)
			if (ruleLength < MIN_PAIR_DISTANCE || pageLength < 1.0) continue
			val scale = pageLength / ruleLength
			if (scale < MIN_SCALE || scale > MAX_SCALE) continue
			// Images do not turn on a page
			if (abs(ruleDx * pageDy - ruleDy * pageDx) > MAX_TURN * ruleLength * pageLength) continue
			val tx = px - scale * ax
			val ty = py - scale * ay
			val count = inliers(from, points, goodRule, goodPage, good, scale, tx, ty, null)
			if (count > bestCount) {
				bestCount = count
				bestScale = scale
				bestX = tx
				bestY = ty
				if (count * 10 >= good * 9) break
			}
		}
		if (bestCount < MIN_GOOD / 2) return null
		// Two points are only roughly right. All the ones that agree say much better
		val inside = BooleanArray(good)
		var count = inliers(from, points, goodRule, goodPage, good, bestScale, bestX, bestY, inside)
		repeat(2) {
			var sumAx = 0.0
			var sumAy = 0.0
			var sumBx = 0.0
			var sumBy = 0.0
			for (i in 0 until good) {
				if (!inside[i]) continue
				sumAx += from.x[goodRule[i]]
				sumAy += from.y[goodRule[i]]
				sumBx += points.x[goodPage[i]]
				sumBy += points.y[goodPage[i]]
			}
			val meanAx = sumAx / count
			val meanAy = sumAy / count
			val meanBx = sumBx / count
			val meanBy = sumBy / count
			var cross = 0.0
			var square = 0.0
			for (i in 0 until good) {
				if (!inside[i]) continue
				val dx = from.x[goodRule[i]] - meanAx
				val dy = from.y[goodRule[i]] - meanAy
				cross += dx * (points.x[goodPage[i]] - meanBx) + dy * (points.y[goodPage[i]] - meanBy)
				square += dx * dx + dy * dy
			}
			if (square > 0.0) {
				val scale = cross / square
				if (scale in MIN_SCALE..MAX_SCALE) {
					val tx = meanBx - scale * meanAx
					val ty = meanBy - scale * meanAy
					val recount = inliers(from, points, goodRule, goodPage, good, scale, tx, ty, null)
					if (recount >= count) {
						bestScale = scale
						bestX = tx
						bestY = ty
						count = inliers(from, points, goodRule, goodPage, good, scale, tx, ty, inside)
					}
				}
			}
		}
		return verify(page, rule, bestScale, bestX, bestY, count, coverOf(from, rule, goodRule, inside, good))
	}

	private fun inliers(
		from: Keypoints,
		points: Keypoints,
		goodRule: IntArray,
		goodPage: IntArray,
		good: Int,
		scale: Double,
		tx: Double,
		ty: Double,
		marks: BooleanArray?,
	): Int {
		var count = 0
		for (i in 0 until good) {
			val dx = scale * from.x[goodRule[i]] + tx - points.x[goodPage[i]]
			val dy = scale * from.y[goodRule[i]] + ty - points.y[goodPage[i]]
			val isIn = abs(dx) <= MAX_ERROR && abs(dy) <= MAX_ERROR
			if (isIn) count++
			if (marks != null) marks[i] = isIn
		}
		return count
	}

	private fun coverOf(from: Keypoints, rule: RuleDetail, goodRule: IntArray, inside: BooleanArray, good: Int): Int {
		var cells = 0L
		for (i in 0 until good) {
			if (!inside[i]) continue
			val x = (from.x[goodRule[i]] * COVER_GRID / rule.width).toInt().coerceIn(0, COVER_GRID - 1)
			val y = (from.y[goodRule[i]] * COVER_GRID / rule.height).toInt().coerceIn(0, COVER_GRID - 1)
			cells = cells or (1L shl (y * COVER_GRID + x))
		}
		return java.lang.Long.bitCount(cells)
	}

	/** What is on the page where its points say, cell by cell, against what the rule has there. Only what is on the page counts. */
	private fun verify(
		page: PageDetail,
		rule: RuleDetail,
		scale: Double,
		tx: Double,
		ty: Double,
		agreeing: Int,
		cover: Int,
	): Evidence? {
		val left = tx
		val top = ty
		val right = tx + rule.width * scale
		val bottom = ty + rule.height * scale
		val onLeft = floor(left).toInt().coerceAtLeast(0)
		val onTop = floor(top).toInt().coerceAtLeast(0)
		val onRight = ceil(right).toInt().coerceAtMost(page.width)
		val onBottom = ceil(bottom).toInt().coerceAtMost(page.height)
		val onWidth = onRight - onLeft
		val onHeight = onBottom - onTop
		if (onWidth < MIN_WINDOW_WIDTH || onHeight < MIN_WINDOW_HEIGHT) return null
		val visible = onWidth.toDouble() * onHeight / ((right - left) * (bottom - top)).coerceAtLeast(1.0)
		// A cell is no smaller than the points can say where it is to
		val columns = (onWidth / MIN_CELL).coerceIn(MIN_FINE_COLUMNS, FINE_COLUMNS)
		val rows = (columns * onHeight / onWidth).coerceIn(4, MAX_FINE_ROWS)
		val coarseColumns = (columns / 2).coerceAtLeast(4)
		val ruleCells = DoubleArray(columns * rows)
		val pageCells = DoubleArray(columns * rows)
		val thumbScaleX = rule.thumbWidth.toDouble() / rule.width
		val thumbScaleY = rule.thumbHeight.toDouble() / rule.height
		val thumbStride = rule.thumbWidth + 1
		val table = rule.thumbIntegral
		val pageTable = page.integral
		val pageStride = page.width + 1
		for (row in 0 until rows) {
			val cellTop = onTop + onHeight * row / rows
			val cellBottom = (onTop + onHeight * (row + 1) / rows).coerceAtLeast(cellTop + 1)
			for (column in 0 until columns) {
				val cellLeft = onLeft + onWidth * column / columns
				val cellRight = (onLeft + onWidth * (column + 1) / columns).coerceAtLeast(cellLeft + 1)
				val sum = pageTable[cellBottom * pageStride + cellRight] - pageTable[cellBottom * pageStride + cellLeft] -
					pageTable[cellTop * pageStride + cellRight] + pageTable[cellTop * pageStride + cellLeft]
				pageCells[row * columns + column] = sum.toDouble() / ((cellBottom - cellTop) * (cellRight - cellLeft))
				ruleCells[row * columns + column] = thumbMean(
					table,
					thumbStride,
					rule,
					(cellLeft - tx) / scale * thumbScaleX,
					(cellTop - ty) / scale * thumbScaleY,
					(cellRight - tx) / scale * thumbScaleX,
					(cellBottom - ty) / scale * thumbScaleY,
				)
			}
		}
		// Two of each way together, for the correlation: one row of fine cells going wrong says less about a whole image
		val halfRows = (rows / 2).coerceAtLeast(2)
		val coarseRule = DoubleArray(coarseColumns * halfRows)
		val coarsePage = DoubleArray(coarseColumns * halfRows)
		val coarseCounts = IntArray(coarseColumns * halfRows)
		for (row in 0 until rows) {
			val coarseRow = (row * halfRows / rows).coerceAtMost(halfRows - 1)
			for (column in 0 until columns) {
				val cell = coarseRow * coarseColumns + column * coarseColumns / columns
				coarseRule[cell] += ruleCells[row * columns + column]
				coarsePage[cell] += pageCells[row * columns + column]
				coarseCounts[cell]++
			}
		}
		for (i in coarseCounts.indices) {
			val n = coarseCounts[i].coerceAtLeast(1)
			coarseRule[i] /= n.toDouble()
			coarsePage[i] /= n.toDouble()
		}
		val correlation = correlationOf(coarseRule, coarsePage)
		val shift = abs(coarseRule.average() - coarsePage.average())
		val far = farShareOf(ruleCells, pageCells)
		val colour = compareColour(page, rule, scale, tx, ty, onLeft, onTop, onRight, onBottom)
		return Evidence(
			agreeing = agreeing,
			cover = cover,
			correlation = correlation,
			farShare = far,
			shift = shift,
			visible = visible,
			overhang = maxOf(0.0, -left, right - page.width) / (right - left).coerceAtLeast(1.0),
			colourDifference = colour[0],
			hue = colour[1],
			isOnlyACurve = visibleIsOneCurve(rule, onTop, onBottom, ty, scale),
			isMostlyBlank = rule.blankShare >= MOSTLY_BLANK,
			colourfulRule = colour[2].toInt(),
			colourfulPage = colour[3].toInt(),
			cellColour = colour[4],
			worstCellColour = colour[5],
			colourStrength = colour[6],
			isColourPartly = colour[7] != 0.0,
			left = left,
			top = top,
			right = right,
			bottom = bottom,
			scale = scale,
			compareFinely = { compareFine(page, rule, scale, tx, ty, onLeft, onTop, onRight, onBottom) },
		)
	}

	/**
	 * What is on the page against the rule's larger copy, in blocks of cells a few pixels across, small enough to tell one
	 * letter from another. Two bubbles with the same rim and other words in them come to 0.98 at the size of [verify]'s cells, where
	 * letters are a smudge. Here they had 24% of their blocks far off, and nearly all copies none. It tells something added to an
	 * image the same way, as that is far off in one place however close the rest is.
	 *
	 * Points only say where an image is to within a pixel or two, so it looks around that for the best place, on a quarter of the
	 * cells. Each block then can be a little off that, as a size a little off comes to more than that over all of a large image.
	 */
	private fun compareFine(page: PageDetail, rule: RuleDetail, scale: Double, tx: Double, ty: Double, onLeft: Int, onTop: Int, onRight: Int, onBottom: Int): FineComparison {
		var cell = maxOf(FINE_MIN_CELL, ceil(scale * rule.width / rule.fineWidth - 0.01).toInt())
		// Not right up to its edges, where a fit a little off has what is next to it
		val inset = FINE_SEARCH + FINE_BLOCK_SEARCH + 1
		val left = onLeft + inset
		val top = onTop + inset
		val right = onRight - inset
		val bottom = onBottom - inset
		if (right - left < cell * 4 || bottom - top < cell * 4) return FineComparison(0.0, 0, 0, 0, 0.0)
		while ((right - left) / cell * ((bottom - top) / cell) > FINE_MAX_CELLS) cell++
		val columns = (right - left) / cell
		val rows = (bottom - top) / cell
		val ruleCells = fineCellsOf(rule, scale, tx, ty, left, top, cell, columns, rows)
		val pageCells = FloatArray(columns * rows)
		var bestX = 0
		var bestY = 0
		var best = Double.NEGATIVE_INFINITY
		for (dy in -FINE_SEARCH..FINE_SEARCH) {
			for (dx in -FINE_SEARCH..FINE_SEARCH) {
				readFineCells(page, left + dx, top + dy, cell, pageCells, columns, 0, rows, 0, columns, 2)
				val here = fineCorrelation(ruleCells, pageCells, columns, 0, rows, 0, columns, 2, 1.0)
				if (here > best) {
					best = here
					bestX = dx
					bestY = dy
				}
			}
		}
		readFineCells(page, left + bestX, top + bestY, cell, pageCells, columns, 0, rows, 0, columns, 1)
		val correlation = fineCorrelation(ruleCells, pageCells, columns, 0, rows, 0, columns, 1, 1.0)
		var apartOverall = 0.0
		for (i in ruleCells.indices) apartOverall += pageCells[i] - ruleCells[i]
		apartOverall /= ruleCells.size
		val blockColumns = (columns + FINE_BLOCK - 1) / FINE_BLOCK
		val blockRows = (rows + FINE_BLOCK - 1) / FINE_BLOCK
		val isFar = BooleanArray(blockColumns * blockRows)
		var blocks = 0
		var blockTop = 0
		while (blockTop < rows) {
			val blockBottom = minOf(blockTop + FINE_BLOCK, rows)
			var blockLeft = 0
			while (blockLeft < columns) {
				val blockRight = minOf(blockLeft + FINE_BLOCK, columns)
				val cells = (blockBottom - blockTop) * (blockRight - blockLeft)
				if (cells * 2 >= FINE_BLOCK * FINE_BLOCK) {
					// In brightness first, while the cells are still those of the best place. Plain against plain has no correlation to go by
					var apart = 0.0
					for (row in blockTop until blockBottom) {
						for (column in blockLeft until blockRight) {
							apart += pageCells[row * columns + column] - ruleCells[row * columns + column]
						}
					}
					val isFarInBrightness = abs(apart / cells - apartOverall) > FINE_FAR_BRIGHTNESS
					var bestHere = Double.NEGATIVE_INFINITY
					for (dy in -1..1) {
						for (dx in -1..1) {
							readFineCells(page, left + bestX + dx, top + bestY + dy, cell, pageCells, columns, blockTop, blockBottom, blockLeft, blockRight, 1)
							val here = fineCorrelation(ruleCells, pageCells, columns, blockTop, blockBottom, blockLeft, blockRight, 1, FINE_INK * FINE_INK)
							if (here > bestHere) bestHere = here
						}
					}
					// A size a little off comes to a few pixels across a large image, and only here is that worth looking for
					if (bestHere > PLAIN && bestHere < FINE_MIN_BLOCK_CORRELATION) {
						for (dy in -FINE_BLOCK_SEARCH..FINE_BLOCK_SEARCH) {
							for (dx in -FINE_BLOCK_SEARCH..FINE_BLOCK_SEARCH) {
								readFineCells(page, left + bestX + dx, top + bestY + dy, cell, pageCells, columns, blockTop, blockBottom, blockLeft, blockRight, 1)
								val here = fineCorrelation(ruleCells, pageCells, columns, blockTop, blockBottom, blockLeft, blockRight, 1, FINE_INK * FINE_INK)
								if (here > bestHere) bestHere = here
							}
						}
					}
					if (bestHere > PLAIN || isFarInBrightness) {
						blocks++
						if (isFarInBrightness || bestHere < FINE_MIN_BLOCK_CORRELATION) {
							isFar[blockTop / FINE_BLOCK * blockColumns + blockLeft / FINE_BLOCK] = true
						}
					}
				}
				blockLeft += FINE_BLOCK
			}
			blockTop += FINE_BLOCK
		}
		var farBlocks = 0
		var farTogether = 0
		val stack = IntArray(isFar.size)
		for (start in isFar.indices) {
			if (!isFar[start]) continue
			isFar[start] = false
			var depth = 0
			stack[depth++] = start
			var size = 0
			while (depth > 0) {
				val at = stack[--depth]
				size++
				val x = at % blockColumns
				val y = at / blockColumns
				for (ny in maxOf(y - 1, 0)..minOf(y + 1, blockRows - 1)) {
					for (nx in maxOf(x - 1, 0)..minOf(x + 1, blockColumns - 1)) {
						val next = ny * blockColumns + nx
						if (isFar[next]) {
							isFar[next] = false
							stack[depth++] = next
						}
					}
				}
			}
			farBlocks += size
			if (size > farTogether) farTogether = size
		}
		return FineComparison(correlation, blocks, farBlocks, farTogether, severalOf(ruleCells, columns, rows))
	}

	/** For the part of a rule on a page, the share of columns with anything in them that have it in more than one place. */
	private fun severalOf(cells: FloatArray, columns: Int, rows: Int): Double {
		val counts = IntArray(32)
		for (value in cells) counts[value.toInt().coerceIn(0, 255) / 8]++
		var mode = 0
		for (i in counts.indices) if (counts[i] > counts[mode]) mode = i
		val background = mode * 8 + 4
		var inked = 0
		var inSeveral = 0
		for (column in 0 until columns) {
			var runs = 0
			var gap = VISIBLE_SEVERAL_GAP
			for (row in 0 until rows) {
				if (abs(cells[row * columns + column] - background) >= VISIBLE_SEVERAL_INK) {
					if (gap >= VISIBLE_SEVERAL_GAP) runs++
					gap = 0
				} else {
					gap++
				}
			}
			if (runs > 0) inked++
			if (runs > 1) inSeveral++
		}
		return if (inked == 0) 0.0 else inSeveral.toDouble() / inked
	}

	/** Worked out here each time rather than kept: this is rare, and with hundreds of rules all of those would add up. */
	private fun fineCellsOf(rule: RuleDetail, scale: Double, tx: Double, ty: Double, left: Int, top: Int, cell: Int, columns: Int, rows: Int): FloatArray {
		val width = rule.fineWidth
		val height = rule.fineHeight
		val stride = width + 1
		val table = IntArray(stride * (height + 1))
		for (y in 0 until height) {
			var sum = 0
			for (x in 0 until width) {
				sum += rule.fine[y * width + x].toInt() and 0xFF
				table[(y + 1) * stride + x + 1] = table[y * stride + x + 1] + sum
			}
		}
		val perX = width.toDouble() / rule.width / scale
		val perY = height.toDouble() / rule.height / scale
		val cells = FloatArray(columns * rows)
		for (row in 0 until rows) {
			val y0 = ((top + row * cell - ty) * perY).coerceIn(0.0, height.toDouble())
			val y1 = ((top + (row + 1) * cell - ty) * perY).coerceIn(0.0, height.toDouble())
			for (column in 0 until columns) {
				val x0 = ((left + column * cell - tx) * perX).coerceIn(0.0, width.toDouble())
				val x1 = ((left + (column + 1) * cell - tx) * perX).coerceIn(0.0, width.toDouble())
				val area = (x1 - x0) * (y1 - y0)
				cells[row * columns + column] = if (area <= 1e-9) {
					(rule.fine[y0.toInt().coerceAtMost(height - 1) * width + x0.toInt().coerceAtMost(width - 1)].toInt() and 0xFF).toFloat()
				} else {
					((interpolated(table, stride, width, height, x1, y1) - interpolated(table, stride, width, height, x0, y1) -
						interpolated(table, stride, width, height, x1, y0) + interpolated(table, stride, width, height, x0, y0)) / area).toFloat()
				}
			}
		}
		return cells
	}

	private fun interpolated(table: IntArray, stride: Int, width: Int, height: Int, x: Double, y: Double): Double {
		val column = x.toInt().coerceAtMost(width - 1)
		val row = y.toInt().coerceAtMost(height - 1)
		val at = row * stride + column
		val upper = table[at] + (table[at + 1] - table[at]) * (x - column)
		val lower = table[at + stride] + (table[at + stride + 1] - table[at + stride]) * (x - column)
		return upper + (lower - upper) * (y - row)
	}

	private fun readFineCells(page: PageDetail, left: Int, top: Int, cell: Int, into: FloatArray, columns: Int, fromRow: Int, toRow: Int, fromColumn: Int, toColumn: Int, every: Int) {
		val table = page.integral
		val stride = page.width + 1
		val area = (cell * cell).toFloat()
		var row = fromRow
		while (row < toRow) {
			val y0 = (top + row * cell) * stride
			val y1 = y0 + cell * stride
			var column = fromColumn
			while (column < toColumn) {
				val x0 = left + column * cell
				val x1 = x0 + cell
				into[row * columns + column] = (table[y1 + x1] - table[y1 + x0] - table[y0 + x1] + table[y0 + x0]) / area
				column += every
			}
			row += every
		}
	}

	/** @return [PLAIN] or under if neither side varies by more than [least] on average, squared */
	private fun fineCorrelation(a: FloatArray, b: FloatArray, columns: Int, fromRow: Int, toRow: Int, fromColumn: Int, toColumn: Int, every: Int, least: Double): Double {
		var sumA = 0.0
		var sumB = 0.0
		var n = 0
		var row = fromRow
		while (row < toRow) {
			var column = fromColumn
			while (column < toColumn) {
				sumA += a[row * columns + column]
				sumB += b[row * columns + column]
				n++
				column += every
			}
			row += every
		}
		val meanA = sumA / n
		val meanB = sumB / n
		var aa = 0.0
		var bb = 0.0
		var ab = 0.0
		row = fromRow
		while (row < toRow) {
			var column = fromColumn
			while (column < toColumn) {
				val da = a[row * columns + column] - meanA
				val db = b[row * columns + column] - meanB
				aa += da * da
				bb += db * db
				ab += da * db
				column += every
			}
			row += every
		}
		if (aa < least * n && bb < least * n) return PLAIN - 1.0
		return ab / Math.sqrt((aa + n) * (bb + n))
	}

	private fun thumbMean(table: IntArray, stride: Int, rule: RuleDetail, left: Double, top: Double, right: Double, bottom: Double): Double {
		val x0 = left.coerceIn(0.0, rule.thumbWidth.toDouble())
		val y0 = top.coerceIn(0.0, rule.thumbHeight.toDouble())
		val x1 = right.coerceIn(0.0, rule.thumbWidth.toDouble())
		val y1 = bottom.coerceIn(0.0, rule.thumbHeight.toDouble())
		val area = (x1 - x0) * (y1 - y0)
		if (area <= 1e-9) {
			// A cell smaller than a pixel of the small copy has nothing to average, so it takes the pixel it is on
			val x = x0.toInt().coerceIn(0, rule.thumbWidth - 1)
			val y = y0.toInt().coerceIn(0, rule.thumbHeight - 1)
			return (rule.thumb[y * rule.thumbWidth + x].toInt() and 0xFF).toDouble()
		}
		val sum = sumTo(table, stride, rule, x1, y1) - sumTo(table, stride, rule, x0, y1) - sumTo(table, stride, rule, x1, y0) + sumTo(table, stride, rule, x0, y0)
		return sum / area
	}

	private fun sumTo(table: IntArray, stride: Int, rule: RuleDetail, x: Double, y: Double): Double {
		val column = x.toInt().coerceIn(0, rule.thumbWidth - 1)
		val row = y.toInt().coerceIn(0, rule.thumbHeight - 1)
		val partX = x - column
		val partY = y - row
		val at = row * stride + column
		val upper = table[at] + (table[at + 1] - table[at]) * partX
		val lower = table[at + stride] + (table[at + stride + 1] - table[at + stride]) * partX
		return upper + (lower - upper) * partY
	}

	private fun correlationOf(a: DoubleArray, b: DoubleArray): Double {
		val meanA = a.average()
		val meanB = b.average()
		var ab = 0.0
		var aa = 0.0
		var bb = 0.0
		for (i in a.indices) {
			val da = a[i] - meanA
			val db = b[i] - meanB
			ab += da * db
			aa += da * da
			bb += db * db
		}
		// Next to nothing varies, on one side or the other, so there is nothing to go by
		if (aa <= a.size || bb <= a.size) return 0.0
		return ab / Math.sqrt(aa * bb)
	}

	private fun visibleIsOneCurve(rule: RuleDetail, onTop: Int, onBottom: Int, ty: Double, scale: Double): Boolean {
		val signature = rule.signature
		val first = (((onTop - ty) / scale) * ImageSignature.GRID / rule.height).toInt().coerceIn(0, ImageSignature.GRID - 1)
		val last = (ceil(((onBottom - ty) / scale) * ImageSignature.GRID / rule.height).toInt() - 1).coerceIn(first, ImageSignature.GRID - 1)
		return ImageSignature.isOneCurveInRows(signature, first, last)
	}

	/** The share of cells far off, allowing for an overall difference in contrast. A change all in one place shows here. */
	private fun farShareOf(ruleCells: DoubleArray, pageCells: DoubleArray): Double {
		val meanRule = ruleCells.average()
		val meanPage = pageCells.average()
		var cross = 0.0
		var square = 0.0
		for (i in ruleCells.indices) {
			val d = ruleCells[i] - meanRule
			cross += d * (pageCells[i] - meanPage)
			square += d * d
		}
		val gain = if (square > 0.0) cross / square else 1.0
		var far = 0
		for (i in ruleCells.indices) {
			if (abs(pageCells[i] - (gain * (ruleCells[i] - meanRule) + meanPage)) > FAR_DIFFERENCE) far++
		}
		return far.toDouble() / ruleCells.size
	}

	/**
	 * @return the difference, the hue difference, how many cells have a colour on the rule and on the page, those cell by cell
	 * on average and at worst, how strong the colours are, and whether all of them are in cells only partly on the page
	 */
	private fun compareColour(
		page: PageDetail,
		rule: RuleDetail,
		scale: Double,
		tx: Double,
		ty: Double,
		onLeft: Int,
		onTop: Int,
		onRight: Int,
		onBottom: Int,
	): DoubleArray {
		val colour = page.colour ?: return doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
		val grid = RuleDetail.COLOUR_GRID
		var countRule = 0
		var countPage = 0
		// The cells with a colour on either side, all of each on the page and then the rest
		val whole = LongArray(5)
		val partly = LongArray(5)
		var cellTotal = 0.0
		var cellWorst = 0.0
		var cellCount = 0
		val cellX = page.width.toDouble() / colour.width
		val cellY = page.height.toDouble() / colour.height
		for (row in 0 until grid) {
			val top = ty + scale * rule.height * row / grid
			val bottom = ty + scale * rule.height * (row + 1) / grid
			// Only a cell that is on the page, more or less. Any more of it off the page and it says what is next door
			val centreY = (top + bottom) / 2
			if (centreY < onTop || centreY >= onBottom) continue
			for (column in 0 until grid) {
				val left = tx + scale * rule.width * column / grid
				val right = tx + scale * rule.width * (column + 1) / grid
				val centreX = (left + right) / 2
				if (centreX < onLeft || centreX >= onRight) continue
				val index = row * grid + column
				val ruleBlue = (rule.colour[index].toInt() and 0xFF) - 128
				val ruleRed = (rule.colour[grid * grid + index].toInt() and 0xFF) - 128
				if (abs(ruleBlue) + abs(ruleRed) > MIN_COLOURFUL) {
					countRule++
				}
				// The outer cells only from their inner half: their outer edge may already be what is next to the image
				val insetLeft = if (column == 0) (right - left) / 2 else 0.0
				val insetRight = if (column == grid - 1) (right - left) / 2 else 0.0
				val insetTop = if (row == 0) (bottom - top) / 2 else 0.0
				val insetBottom = if (row == grid - 1) (bottom - top) / 2 else 0.0
				val x0 = ((left + insetLeft).coerceAtLeast(0.0) / cellX).toInt().coerceIn(0, colour.width - 1)
				val x1 = ceil((right - insetRight).coerceAtMost(page.width.toDouble()) / cellX).toInt().coerceIn(x0 + 1, colour.width)
				val y0 = ((top + insetTop).coerceAtLeast(0.0) / cellY).toInt().coerceIn(0, colour.height - 1)
				val y1 = ceil((bottom - insetBottom).coerceAtMost(page.height.toDouble()) / cellY).toInt().coerceIn(y0 + 1, colour.height)
				var sumBlue = 0
				var sumRed = 0
				for (y in y0 until y1) {
					val offset = y * colour.width
					for (x in x0 until x1) {
						sumBlue += colour.blue[offset + x].toInt() and 0xFF
						sumRed += colour.red[offset + x].toInt() and 0xFF
					}
				}
				val cells = (x1 - x0) * (y1 - y0)
				val hereBlue = sumBlue / cells - 128
				val hereRed = sumRed / cells - 128
				if (abs(hereBlue) + abs(hereRed) > MIN_COLOURFUL) {
					countPage++
				}
				if (abs(ruleBlue) + abs(ruleRed) > MIN_COLOURFUL || abs(hereBlue) + abs(hereRed) > MIN_COLOURFUL) {
					// Of one only partly on the page, the rule has what is off the page in it, and the page does not
					val shared = if (top + insetTop >= 0.0 && bottom - insetBottom <= page.height) whole else partly
					shared[0]++
					shared[1] += ruleBlue.toLong()
					shared[2] += ruleRed.toLong()
					shared[3] += hereBlue.toLong()
					shared[4] += hereRed.toLong()
				}
				val apart = (abs(ruleBlue - hereBlue) + abs(ruleRed - hereRed)).toDouble()
				cellTotal += apart
				if (apart > cellWorst) cellWorst = apart
				cellCount++
			}
		}
		val isPartly = whole[0] == 0L && partly[0] != 0L
		val shared = if (isPartly) partly else whole
		val count = shared[0].coerceAtLeast(1L).toDouble()
		val ruleB = shared[1] / count
		val ruleR = shared[2] / count
		val pageB = shared[3] / count
		val pageR = shared[4] / count
		val difference = abs(ruleB - pageB) + abs(ruleR - pageR)
		val turned = abs(Math.toDegrees(Math.atan2(ruleR, ruleB) - Math.atan2(pageR, pageB)))
		val strength = maxOf(abs(ruleB) + abs(ruleR), abs(pageB) + abs(pageR))
		return doubleArrayOf(difference, minOf(turned, 360.0 - turned), countRule.toDouble(), countPage.toDouble(), cellTotal / cellCount.coerceAtLeast(1), cellWorst, strength, if (isPartly) 1.0 else 0.0)
	}

	/** How far around where the points say to look for the best place, and then how far each block may be from that. */
	private const val FINE_SEARCH = 3
	private const val FINE_BLOCK_SEARCH = 2

	/** A cell is never under this many pixels of a page, and there are never more than this many cells, for what it takes. */
	private const val FINE_MIN_CELL = 2
	private const val FINE_MAX_CELLS = 8000

	/** Cells to a block each way. */
	private const val FINE_BLOCK = 8

	/** How much a block has to vary on one side or the other for its correlation to say anything. */
	private const val FINE_INK = 12.0
	private const val FINE_MIN_BLOCK_CORRELATION = 0.7
	private const val FINE_FAR_BRIGHTNESS = 28.0
	private const val PLAIN = -9.0

	/**
	 * Of real copies on 1,951 pages none were turned away by these, and one bubble with other words was, and two images with a
	 * sound effect added. Copies have the odd block off here and there, and something added is all in one place.
	 */
	private const val MAX_FAR_BLOCKS_SHARE = 0.06
	private const val MAX_FAR_BLOCKS_TOGETHER = 2
	private const val MAX_SOFT_BLOCKS = 8
	private const val MIN_SOFT_CORRELATION = 0.96
	private const val MIN_PAIR_DISTANCE = 8.0
	private const val MIN_WINDOW_WIDTH = 8
	private const val MIN_WINDOW_HEIGHT = 4
	private const val FINE_COLUMNS = 32
	private const val MIN_FINE_COLUMNS = 8
	private const val MIN_CELL = 4
	private const val MAX_FINE_ROWS = 128
}
