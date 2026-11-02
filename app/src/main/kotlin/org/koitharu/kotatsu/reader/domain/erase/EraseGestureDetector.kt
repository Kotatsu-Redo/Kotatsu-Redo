package org.koitharu.kotatsu.reader.domain.erase

import kotlin.math.abs
import kotlin.math.sign

/**
 * Recognises a thumb scrubbing back and forth, the motion of rubbing something out.
 *
 * It only ever counts sideways strokes, because that is the one motion that is not already something
 * else in every reader mode: a vertical back-and-forth is just someone adjusting a webtoon's scroll.
 * Sideways, a single swipe is still a page turn in the pager, which is why it takes several reversals
 * in one touch and why a stroke that dawdles rejects the whole gesture - panning a zoomed page is slow
 * and sideways too. Nothing is claimed until [Phase.RECOGNIZED], so every other gesture stays untouched.
 */
class EraseGestureDetector(
	private val minStrokePx: Float,
	private val reversalsRequired: Int = 2,
	private val maxStrokeMs: Long = 700L,
) {

	enum class Phase {
		/** No touch, or nothing worth noting yet. */
		IDLE,

		/** Has reversed once: probably scrubbing, so feedback can start, but nothing is claimed. */
		ARMED,

		/** The gesture is an erase. Stays here until the next touch begins. */
		RECOGNIZED,

		/** This touch is something else. Stays here until the next touch begins. */
		REJECTED,
	}

	var phase = Phase.IDLE
		private set

	private var lastX = 0f
	private var lastY = 0f
	private var extremeX = 0f
	private var direction = 0f
	private var reversals = 0
	private var strokeStart = 0L
	private var travelX = 0f
	private var travelY = 0f

	fun onDown(x: Float, y: Float, time: Long) {
		phase = Phase.IDLE
		lastX = x
		lastY = y
		extremeX = x
		direction = 0f
		reversals = 0
		strokeStart = time
		travelX = 0f
		travelY = 0f
	}

	fun onMove(x: Float, y: Float, time: Long): Phase {
		if (phase == Phase.REJECTED || phase == Phase.RECOGNIZED) {
			return phase
		}
		travelX += abs(x - lastX)
		travelY += abs(y - lastY)
		lastX = x
		lastY = y
		// A thumb pivots from the base, so a rub is an arc that can start nearly straight up before it curves.
		// Judging that early would throw away most real attempts, so this waits for a clear scroll.
		if (travelY > minStrokePx * SCROLL_STROKES && travelY > travelX * MAX_VERTICAL_RATIO) {
			phase = Phase.REJECTED
			return phase
		}
		val delta = x - extremeX
		when {
			direction == 0f -> if (abs(delta) >= minStrokePx) {
				direction = delta.sign
				extremeX = x
				strokeStart = time
			}

			delta.sign == direction -> extremeX = x

			abs(delta) >= minStrokePx -> {
				direction = -direction
				extremeX = x
				strokeStart = time
				reversals++
			}
		}
		if (direction != 0f && time - strokeStart > maxStrokeMs) {
			phase = Phase.REJECTED
			return phase
		}
		if (reversals >= reversalsRequired) {
			phase = Phase.RECOGNIZED
		} else if (reversals > 0) {
			phase = Phase.ARMED
		}
		return phase
	}

	/** A second finger means pinch or pan: not a thumb rubbing something out. */
	fun onExtraPointer() {
		if (phase != Phase.RECOGNIZED) {
			phase = Phase.REJECTED
		}
	}

	fun onUpOrCancel() {
		if (phase != Phase.RECOGNIZED) {
			phase = Phase.IDLE
		}
	}

	private companion object {
		const val MAX_VERTICAL_RATIO = 1.5f
		const val SCROLL_STROKES = 3
	}
}
