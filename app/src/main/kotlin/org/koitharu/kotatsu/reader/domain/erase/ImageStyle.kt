package org.koitharu.kotatsu.reader.domain.erase

import kotlin.math.abs

/**
 * What an area of a page looks like overall, rather than what it shows: how bright it is, how busy it is,
 * and how colourful. An ad pasted into a comic gives itself away on these long before anything could read
 * it - a saturated banner in a black and white manga, or white text on a plain fill between painted panels.
 */
class ImageStyle private constructor(
	private val brightness: FloatArray,
	private val edges: Float,
	private val colour: Float,
) {

	/** 0 for the same style, 1 for nothing in common. */
	fun distanceTo(other: ImageStyle): Float {
		// A colour image among greys needs nothing else said: scans do not turn colourful for one panel.
		if (minOf(colour, other.colour) < GREY && maxOf(colour, other.colour) > COLOURFUL) {
			return 1f
		}
		var difference = 0f
		for (i in brightness.indices) {
			difference += abs(brightness[i] - other.brightness[i])
		}
		return 0.5f * (difference / 2f) +
			0.25f * (abs(edges - other.edges) / EDGE_RANGE).coerceAtMost(1f) +
			0.25f * (abs(colour - other.colour) / COLOUR_RANGE).coerceAtMost(1f)
	}

	companion object {

		private const val BINS = 8
		private const val EDGE_STEP = 18
		private const val EDGE_RANGE = 0.25f
		private const val COLOUR_RANGE = 48f
		private const val GREY = 10f
		private const val COLOURFUL = 28f

		fun of(luma: PageLuma, areas: List<SourceRegion>): ImageStyle? {
			val counts = IntArray(BINS)
			var total = 0
			var edges = 0
			var colour = 0L
			for (area in areas) {
				val left = luma.scaleX(area.left)
				val right = luma.scaleX(area.right)
				for (y in luma.scaleY(area.top) until luma.scaleY(area.bottom)) {
					for (x in left until right) {
						val value = luma[x, y]
						counts[value * BINS / 256]++
						if (x > left && abs(value - luma[x - 1, y]) > EDGE_STEP) edges++
						colour += luma.chromaAt(x, y)
						total++
					}
				}
			}
			if (total == 0) {
				return null
			}
			return ImageStyle(
				brightness = FloatArray(BINS) { counts[it].toFloat() / total },
				edges = edges.toFloat() / total,
				colour = colour.toFloat() / total,
			)
		}
	}
}
