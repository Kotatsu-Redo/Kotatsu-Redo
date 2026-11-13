package org.koitharu.kotatsu.reader.ui.erase

import android.graphics.RectF
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.domain.erase.EraseGestureDetector
import org.koitharu.kotatsu.reader.domain.erase.EraseGestureDetector.Phase
import org.koitharu.kotatsu.reader.domain.erase.EraseProposal
import org.koitharu.kotatsu.reader.domain.erase.EraseProposer
import org.koitharu.kotatsu.reader.domain.erase.ImageHider
import org.koitharu.kotatsu.reader.domain.erase.PageLuma
import org.koitharu.kotatsu.reader.domain.erase.SourceRegion
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import kotlin.math.abs

/**
 * Runs an erase from the first stroke to the undo offer: watches every touch before the reader does, and
 * once a scrub is certain, takes the touch away from whatever had it.
 */
class EraseController(
	private val overlay: EraseOverlayView,
	private val hider: ImageHider,
	private val scope: CoroutineScope,
	private val host: Host,
) : EraseOverlayView.Listener {

	interface Host {
		val isEraseGestureEnabled: Boolean
		val mangaTitle: String?

		@get:ColorInt
		val pageBackgroundColor: Int

		fun canStartEraseAt(rawX: Int, rawY: Int): Boolean
		fun findEraseTarget(rawX: Float, rawY: Float): ErasePageTarget?

		/** The pages either side of [page], as far as they are at hand without keeping the reader waiting. */
		suspend fun findNeighbourImages(page: ReaderPage): List<Uri>

		/** Everything below has seen this touch begin, so it must be told it has ended. */
		fun cancelTouchFor(event: MotionEvent)
		fun onEraseGestureRecognized()
		fun onImageErased(ruleIds: List<Long>)
		fun showEraseMessage(@StringRes message: Int)
	}

	/** Told how a practice run is going. While there is one, nothing gets remembered. */
	interface PracticeListener {
		fun onRubbing(isRubbing: Boolean)
		fun onSelectionOffered()
		fun onPracticeCancelled()
		fun onPracticeErased()
	}

	enum class TouchResult {
		/** Nothing to do with erasing: the reader handles it as usual. */
		NOT_CLAIMED,

		/** Part of a scrub: nothing below may see it. */
		CONSUMED,

		/** Handles on screen: the overlay gets it, and no reader gesture does. */
		TO_OVERLAY,
	}

	private val detector = EraseGestureDetector(
		minStrokePx = MIN_STROKE_DP * overlay.resources.displayMetrics.density,
	)
	private var isClaimed = false
	private var isTrailVisible = false
	private var strokeCount = 0
	private val strokeX = FloatArray(MAX_BUFFERED_POINTS)
	private val strokeY = FloatArray(MAX_BUFFERED_POINTS)
	private var left = 0f
	private var top = 0f
	private var right = 0f
	private var bottom = 0f
	private var job: Job? = null

	private var session: Session? = null

	/** Set for the tutorial: everything looks and feels the same, and the image is back at the end of it. */
	var practice: PracticeListener? = null

	init {
		overlay.listener = this
	}

	fun onTouchEvent(event: MotionEvent): TouchResult {
		if (overlay.isActive) {
			return TouchResult.TO_OVERLAY
		}
		if (!host.isEraseGestureEnabled) {
			return TouchResult.NOT_CLAIMED
		}
		val x = event.rawX
		val y = event.rawY
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				isClaimed = false
				isTrailVisible = false
				strokeCount = 0
				left = x
				right = x
				top = y
				bottom = y
				detector.onDown(x, y, event.eventTime)
				if (!host.canStartEraseAt(x.toInt(), y.toInt())) {
					detector.onExtraPointer()
				}
				buffer(x, y)
			}

			MotionEvent.ACTION_POINTER_DOWN -> detector.onExtraPointer()

			MotionEvent.ACTION_MOVE -> {
				if (isClaimed) {
					extendStroke(x, y)
					overlay.addTrailPoint(x, y, event.eventTime)
					return TouchResult.CONSUMED
				}
				// Touches arrive batched per frame, and a quick rub can turn around inside one batch
				val offsetX = x - event.x
				val offsetY = y - event.y
				for (i in 0 until event.historySize) {
					detector.onMove(event.getHistoricalX(i) + offsetX, event.getHistoricalY(i) + offsetY, event.getHistoricalEventTime(i))
				}
				val phase = detector.onMove(x, y, event.eventTime)
				buffer(x, y)
				when (phase) {
					Phase.IDLE -> Unit
					Phase.ARMED -> showTrail(x, y, event.eventTime)
					Phase.REJECTED -> dropTrail()
					Phase.RECOGNIZED -> {
						showTrail(x, y, event.eventTime)
						isClaimed = true
						// From here on the stroke is what was rubbed out, not how the reader got going
						left = x
						right = x
						top = y
						bottom = y
						for (i in strokeCount / 2 until strokeCount) {
							extendStroke(strokeX[i], strokeY[i])
						}
						overlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
						host.cancelTouchFor(event)
						host.onEraseGestureRecognized()
						return TouchResult.CONSUMED
					}
				}
			}

			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				detector.onUpOrCancel()
				if (isClaimed) {
					isClaimed = false
					if (event.actionMasked == MotionEvent.ACTION_UP) {
						onScrubFinished()
					} else {
						overlay.cancel()
					}
					return TouchResult.CONSUMED
				}
				dropTrail()
			}
		}
		return TouchResult.NOT_CLAIMED
	}

	/** Backs out of a selection. Once the wipe has started there is nothing to back out of: undo is next. */
	fun onBackPressed() {
		if (!overlay.isErasing) {
			overlay.cancel()
		}
	}

	override fun onSelectionConfirmed(rect: RectF) {
		val current = session ?: return
		practice?.let { listener ->
			playPractice(listener, rect)
			return
		}
		val offered = current.offered
		val offeredRegion = current.offeredRegion
		if (offered != null && offeredRegion != null && rect.isSameAs(offered)) {
			// Taken as offered: the object, and the blank around it that the borders leave out
			erase(current, offeredRegion, rect)
			return
		}
		val region = current.target.screenToRegion(
			rect.left, rect.top, rect.right, rect.bottom,
			current.luma.sourceWidth, current.luma.sourceHeight,
		)
		if (region == null) {
			abort(R.string.erase_not_possible)
			return
		}
		erase(current, EraseProposer.withBlankAround(current.luma, region), rect)
	}

	override fun onSelectionCancelled() {
		job?.cancel()
		session = null
		practice?.onPracticeCancelled()
	}

	private fun playPractice(listener: PracticeListener, rect: RectF) {
		job = scope.launch {
			overlay.playErase(rect, host.pageBackgroundColor)
			delay(PRACTICE_ERASE_MS)
			overlay.finish()
			session = null
			listener.onPracticeErased()
		}
	}

	private fun onScrubFinished() {
		val stroke = RectF(left, top, right, bottom)
		overlay.showSearching(stroke)
		job = scope.launch {
			val target = host.findEraseTarget(stroke.centerX(), stroke.centerY())
			if (target == null) {
				abort(R.string.erase_not_possible)
				return@launch
			}
			val analysis = runCatchingCancellable {
				val luma = hider.decodeLuma(target.imageUri, withChroma = true)
				val region = target.screenToRegion(
					stroke.left, stroke.top, stroke.right, stroke.bottom, luma.sourceWidth, luma.sourceHeight,
				) ?: error("The page is no longer on screen")
				val aspect = overlay.height.toFloat() / overlay.width.coerceAtLeast(1)
				val proposal = withContext(Dispatchers.Default) { EraseProposer.propose(luma, region, aspect) }
				// Nothing is ever erased without asking while learning how
				Analysis(luma, proposal, practice == null && isClearlyAnInsert(target, luma, proposal))
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrNull()
			if (analysis == null) {
				abort(R.string.erase_not_possible)
				return@launch
			}
			val current = Session(target, analysis.luma)
			session = current
			present(current, analysis.proposal, analysis.isInsert)
		}
	}

	/** Borders are the rule. Erasing without asking takes a separate image that looks like nothing around it. */
	private suspend fun isClearlyAnInsert(target: ErasePageTarget, luma: PageLuma, proposal: EraseProposal): Boolean {
		if (!proposal.isStandalone || !hider.isRecognisable(luma, proposal.objectRegion)) {
			return false
		}
		val neighbours = if (EraseProposer.needsNeighbours(proposal)) {
			host.findNeighbourImages(target.page).mapNotNull { uri ->
				runCatchingCancellable { hider.decodeLuma(uri, withChroma = true) }
					.onFailure { it.printStackTraceDebug() }
					.getOrNull()
			}
		} else {
			emptyList()
		}
		return withContext(Dispatchers.Default) { EraseProposer.isUnlikeTheRest(luma, proposal, neighbours) }
	}

	/**
	 * The borders go around the object itself. The blank space around it goes with it all the same, but showing
	 * that put the borders on white, out to both edges of the page for anything narrower than it.
	 *
	 * An object far bigger than what was rubbed gets them around the rub instead, unless it is going without
	 * asking: rubbing a watermark in the corner of a panel used to put them around the whole panel.
	 */
	private fun present(current: Session, proposal: EraseProposal, eraseNow: Boolean) {
		val aroundStroke = proposal.aroundStroke?.takeUnless { eraseNow }
		val shown = aroundStroke ?: proposal.objectRegion
		val rect = current.target.regionToScreen(shown)
		if (rect == null) {
			abort(R.string.erase_not_possible)
			return
		}
		current.offered = RectF(rect)
		current.offeredRegion = if (aroundStroke != null) EraseProposer.withBlankAround(current.luma, aroundStroke) else proposal.region
		val snapX = proposal.snapX.asList().mapNotNull { current.target.sourceToScreen(it.toFloat(), shown.top.toFloat())?.x }
		val snapY = proposal.snapY.asList().mapNotNull { current.target.sourceToScreen(shown.left.toFloat(), it.toFloat())?.y }
		overlay.showSelection(
			rect = rect,
			offerHandles = !eraseNow,
			snapX = snapX.toFloatArray(),
			snapY = snapY.toFloatArray(),
			onSettled = { if (eraseNow) erase(current, proposal.region, rect) },
		)
		practice?.onSelectionOffered()
	}

	private fun erase(current: Session, region: SourceRegion, rect: RectF) {
		job = scope.launch {
			// Before anything is remembered, as that changes what is where on screen
			val others = otherPagesUnder(current, rect)
			// Saved before anything is shown to have happened, so that it holds even if this is the
			// last thing the app does: the reader is told it is gone only once it really is.
			val ruleId = runCatchingCancellable {
				hider.remember(current.target.page.toMangaPage(), current.target.imageUri, current.luma, region, host.mangaTitle)
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrNull()
			if (ruleId == null) {
				abort(R.string.erase_not_possible)
				return@launch
			}
			val ruleIds = arrayListOf(ruleId)
			for (other in others) {
				runCatchingCancellable {
					hider.remember(other.target.page.toMangaPage(), other.target.imageUri, other.luma, other.region, host.mangaTitle)
				}.onFailure {
					it.printStackTraceDebug()
				}.getOrNull()?.let { ruleIds += it }
			}
			overlay.playErase(rect, host.pageBackgroundColor)
			current.target.awaitErased()
			others.forEach { it.target.awaitErased() }
			overlay.finish()
			session = null
			host.onImageErased(ruleIds)
		}
	}

	/**
	 * What a selection covers of the pages next to the one it started on.
	 *
	 * Sites slice one picture into several files, which on screen are one picture, with nothing to say where one
	 * ends. Whoever selects all of it means all of it, so each file under the selection loses its part. Each is
	 * remembered as an image of its own, since that is how the next chapter serves them too.
	 *
	 * A page whose picture cannot be had is left as it is, as it would have been before: the rest still goes.
	 */
	private suspend fun otherPagesUnder(current: Session, rect: RectF): List<OtherPage> {
		val density = overlay.resources.displayMetrics.density
		val pageIds = hashSetOf(current.target.page.id)
		val others = ArrayList<OtherPage>()
		var y = rect.top
		while (y <= rect.bottom) {
			val target = host.findEraseTarget(rect.centerX(), y)
			y += OTHER_PAGE_PROBE_DP * density
			if (target == null || !pageIds.add(target.page.id)) continue
			runCatchingCancellable {
				val luma = hider.decodeLuma(target.imageUri)
				val region = target.screenToRegion(rect.left, rect.top, rect.right, rect.bottom, luma.sourceWidth, luma.sourceHeight)
					?: return@runCatchingCancellable
				val top = target.sourceToScreen(region.left.toFloat(), region.top.toFloat())?.y
					?: return@runCatchingCancellable
				val bottom = target.sourceToScreen(region.left.toFloat(), region.bottom.toFloat())?.y
					?: return@runCatchingCancellable
				// A handle dragged a little too far is not a request to take a sliver of the next page
				if (bottom - top >= MIN_OTHER_PAGE_DP * density) {
					others += OtherPage(target, luma, region)
				}
			}.onFailure {
				it.printStackTraceDebug()
			}
		}
		return others
	}

	private fun abort(@StringRes message: Int) {
		session = null
		overlay.listener = null
		overlay.cancel()
		overlay.listener = this
		practice?.onPracticeCancelled()
		host.showEraseMessage(message)
	}

	private fun showTrail(x: Float, y: Float, time: Long) {
		if (isTrailVisible) {
			overlay.addTrailPoint(x, y, time)
		} else {
			isTrailVisible = true
			overlay.beginTrail(strokeX, strokeY, strokeCount, time)
			practice?.onRubbing(true)
		}
	}

	private fun dropTrail() {
		if (isTrailVisible) {
			isTrailVisible = false
			overlay.dropTrail()
			practice?.onRubbing(false)
		}
	}

	private fun buffer(x: Float, y: Float) {
		if (strokeCount < MAX_BUFFERED_POINTS) {
			strokeX[strokeCount] = x
			strokeY[strokeCount] = y
			strokeCount++
		}
	}

	private fun extendStroke(x: Float, y: Float) {
		left = minOf(left, x)
		right = maxOf(right, x)
		top = minOf(top, y)
		bottom = maxOf(bottom, y)
	}

	private fun ErasePageTarget.regionToScreen(region: SourceRegion): RectF? {
		val start = sourceToScreen(region.left.toFloat(), region.top.toFloat()) ?: return null
		val end = sourceToScreen(region.right.toFloat(), region.bottom.toFloat()) ?: return null
		return RectF(start.x, start.y, end.x, end.y)
	}

	/** Borders the reader has not moved are still where they were put, give or take what drawing them rounds off. */
	private fun RectF.isSameAs(other: RectF): Boolean =
		abs(left - other.left) <= SAME_BORDER_PX && abs(top - other.top) <= SAME_BORDER_PX &&
			abs(right - other.right) <= SAME_BORDER_PX && abs(bottom - other.bottom) <= SAME_BORDER_PX

	private class Session(val target: ErasePageTarget, val luma: PageLuma) {

		/** Where on screen the borders were put, to tell whether the reader has moved them since. */
		var offered: RectF? = null

		/** What goes if they have not: more than is inside the borders, by the blank around it. */
		var offeredRegion: SourceRegion? = null
	}

	private class OtherPage(val target: ErasePageTarget, val luma: PageLuma, val region: SourceRegion)

	private class Analysis(val luma: PageLuma, val proposal: EraseProposal, val isInsert: Boolean)

	private companion object {
		/**
		 * Short, because that is how far a thumb rubs when the phone is held in the same hand. A false start
		 * costs little: it brings up borders with a cancel button, and most touches never reverse twice at all.
		 */
		const val MIN_STROKE_DP = 18f
		const val MAX_BUFFERED_POINTS = 96

		/** How far apart to look for other pages under a selection: closer than any page is short. */
		const val OTHER_PAGE_PROBE_DP = 8

		const val MIN_OTHER_PAGE_DP = 24

		const val SAME_BORDER_PX = 1.5f

		/** Long enough for the wipe to play out, with nothing to wait for since no page is changing. */
		const val PRACTICE_ERASE_MS = 650L
	}
}
