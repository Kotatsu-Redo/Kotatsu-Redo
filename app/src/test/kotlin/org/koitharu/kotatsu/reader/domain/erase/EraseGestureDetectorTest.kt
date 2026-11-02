package org.koitharu.kotatsu.reader.domain.erase

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.reader.domain.erase.EraseGestureDetector.Phase

class EraseGestureDetectorTest {

	private val stroke = 60f

	private fun detector() = EraseGestureDetector(minStrokePx = stroke)

	/** Feeds a path of points through the detector, 16ms apart, returning the last phase. */
	private fun EraseGestureDetector.run(vararg points: Pair<Float, Float>, stepMs: Long = 16L): Phase {
		onDown(points.first().first, points.first().second, 0L)
		var phase = Phase.IDLE
		var time = 0L
		for (i in 1 until points.size) {
			val (fromX, fromY) = points[i - 1]
			val (toX, toY) = points[i]
			for (t in 1..6) {
				time += stepMs
				phase = onMove(fromX + (toX - fromX) * t / 6, fromY + (toY - fromY) * t / 6, time)
			}
		}
		return phase
	}

	@Test
	fun `a thumb scrubbing back and forth is recognised`() {
		val phase = detector().run(300f to 500f, 450f to 505f, 300f to 495f, 450f to 500f)
		assertEquals(Phase.RECOGNIZED, phase)
	}

	@Test
	fun `a thumb arc with real vertical travel still counts`() {
		val phase = detector().run(300f to 500f, 450f to 540f, 300f to 500f, 450f to 540f)
		assertEquals(Phase.RECOGNIZED, phase)
	}

	/** The way a thumb actually moves when the phone is held in the same hand. */
	@Test
	fun `a steep thumb arc counts`() {
		val phase = detector().run(300f to 500f, 420f to 640f, 300f to 500f, 420f to 640f)
		assertEquals(Phase.RECOGNIZED, phase)
	}

	@Test
	fun `an arc that starts going almost straight up is not thrown away`() {
		val phase = detector().run(300f to 500f, 310f to 400f, 450f to 340f, 310f to 400f, 450f to 340f)
		assertEquals(Phase.RECOGNIZED, phase)
	}

	@Test
	fun `a brief hesitation mid rub is forgiven`() {
		val phase = detector().run(300f to 500f, 450f to 500f, 300f to 500f, 450f to 500f, stepMs = 100L)
		assertEquals(Phase.RECOGNIZED, phase)
	}

	@Test
	fun `one reversal only arms it`() {
		assertEquals(Phase.ARMED, detector().run(300f to 500f, 450f to 500f, 300f to 500f))
	}

	@Test
	fun `a plain swipe is not an erase`() {
		assertEquals(Phase.IDLE, detector().run(600f to 500f, 100f to 500f))
	}

	@Test
	fun `scrolling a webtoon up and down is rejected`() {
		val phase = detector().run(300f to 500f, 305f to 800f, 300f to 500f, 305f to 800f)
		assertEquals(Phase.REJECTED, phase)
	}

	@Test
	fun `wobbles too small to be strokes never arm it`() {
		val phase = detector().run(300f to 500f, 330f to 500f, 300f to 500f, 330f to 500f, 300f to 500f)
		assertEquals(Phase.IDLE, phase)
	}

	@Test
	fun `slowly panning a zoomed page is rejected`() {
		val phase = detector().run(300f to 500f, 450f to 500f, 300f to 500f, 450f to 500f, stepMs = 150L)
		assertEquals(Phase.REJECTED, phase)
	}

	@Test
	fun `a second finger rejects the touch for good`() {
		val detector = detector()
		detector.run(300f to 500f, 450f to 500f, 300f to 500f)
		detector.onExtraPointer()
		assertEquals(Phase.REJECTED, detector.onMove(450f, 500f, 400L))
		assertEquals(Phase.REJECTED, detector.onMove(300f, 500f, 450L))
	}

	@Test
	fun `a new touch starts over`() {
		val detector = detector()
		detector.run(300f to 500f, 305f to 900f)
		assertEquals(Phase.REJECTED, detector.phase)
		assertEquals(Phase.RECOGNIZED, detector.run(300f to 500f, 450f to 500f, 300f to 500f, 450f to 500f))
	}
}
