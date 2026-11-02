package org.koitharu.kotatsu.reader.ui.erase

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.reader.domain.erase.EraseProposer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import androidx.appcompat.R as appcompatR

/**
 * Everything the erase gesture draws: the smear behind the thumb, the border finding its way to what is
 * being erased, the handles to adjust it with, and the image dissolving at the end.
 *
 * One canvas and one running animator at a time, with no layers, shaders or per-frame allocation, so that
 * it stays smooth on the phones the rest of this feature was written for. Honours the system's "remove
 * animations" setting by jumping straight to the end of each step.
 */
class EraseOverlayView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

	interface Listener {
		fun onSelectionConfirmed(rect: RectF)
		fun onSelectionCancelled()
	}

	private enum class Mode { IDLE, TRAIL, SEARCHING, SETTLING, CROP, ERASING }

	private enum class Handle { NONE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

	var listener: Listener? = null

	var onActiveChanged: ((Boolean) -> Unit)? = null

	/** Whether this is on top of the reader and owns the touch stream, including during the dissolve. */
	val isActive: Boolean
		get() = mode != Mode.IDLE && mode != Mode.TRAIL

	/** Past the point of no return: the image is already remembered, this is only showing it. */
	val isErasing: Boolean
		get() = mode == Mode.ERASING

	private val density = resources.displayMetrics.density
	private val accent = context.getThemeColor(appcompatR.attr.colorPrimary, Color.WHITE)
	private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
		strokeJoin = Paint.Join.ROUND
		strokeWidth = TRAIL_WIDTH_DP * density
	}
	private val dimPaint = Paint().apply { color = Color.BLACK }
	private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 2f * density
		color = accent
	}
	private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 4f * density
		strokeCap = Paint.Cap.ROUND
		color = accent
	}
	private val fillPaint = Paint()
	private val dustPaint = Paint(Paint.ANTI_ALIAS_FLAG)

	private var mode = Mode.IDLE
		set(value) {
			val wasActive = isActive
			field = value
			if (isActive != wasActive) {
				onActiveChanged?.invoke(isActive)
			}
		}
	private var animator: ValueAnimator? = null

	// Trail, as parallel ring buffers: this is written on every touch event
	private val trailX = FloatArray(TRAIL_POINTS)
	private val trailY = FloatArray(TRAIL_POINTS)
	private val trailTime = LongArray(TRAIL_POINTS)
	private var trailSize = 0
	private var trailEnd = 0

	private val selection = RectF()
	private val animFrom = RectF()
	private val animTo = RectF()
	private val eraseRect = RectF()
	private var dimAlpha = 0f
	private var searchPulse = 0f
	private var dissolve = 0f
	private var wipe = 0f

	private var snapX = FloatArray(0)
	private var snapY = FloatArray(0)
	private var activeHandle = Handle.NONE
	private var lastTouchX = 0f
	private var lastTouchY = 0f

	private val dustX = FloatArray(DUST_COUNT)
	private val dustY = FloatArray(DUST_COUNT)
	private val dustVelocityX = FloatArray(DUST_COUNT)
	private val dustVelocityY = FloatArray(DUST_COUNT)

	private val actions: LinearLayout

	init {
		setWillNotDraw(false)
		LayoutInflater.from(context).inflate(R.layout.view_erase_overlay, this, true)
		actions = findViewById(R.id.layout_erase_actions)
		actions.updateLayoutParams<LayoutParams> { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
		actions.isVisible = false
		findViewById<View>(R.id.button_erase_confirm).setOnClickListener {
			if (mode == Mode.CROP) {
				listener?.onSelectionConfirmed(RectF(selection))
			}
		}
		findViewById<View>(R.id.button_erase_cancel).setOnClickListener { cancel() }
	}

	fun beginTrail(xs: FloatArray, ys: FloatArray, count: Int, time: Long) {
		cancelAnimator()
		mode = Mode.TRAIL
		trailSize = 0
		trailEnd = 0
		isVisible = true
		for (i in 0 until count) {
			addTrailPoint(xs[i], ys[i], time)
		}
		invalidate()
	}

	fun addTrailPoint(x: Float, y: Float, time: Long) {
		trailX[trailEnd] = x
		trailY[trailEnd] = y
		trailTime[trailEnd] = time
		trailEnd = (trailEnd + 1) % TRAIL_POINTS
		if (trailSize < TRAIL_POINTS) {
			trailSize++
		}
		postInvalidateOnAnimation()
	}

	/** The touch turned out to be something else: let the smear fade rather than vanish. */
	fun dropTrail() {
		if (mode == Mode.TRAIL) {
			mode = Mode.IDLE
			hideWhenTrailGone()
		}
	}

	/** The scrub is over and the page is being looked at. Shows where, pulsing, for however long that is. */
	fun showSearching(rect: RectF) {
		cancelAnimator()
		mode = Mode.SEARCHING
		selection.set(rect)
		isVisible = true
		if (context.isAnimationsEnabled) {
			animate(SEARCH_PULSE_MS, repeat = true) { searchPulse = it }
		}
		invalidate()
	}

	/**
	 * Sends the border from wherever it is to the probable selection.
	 *
	 * @param offerHandles true to hand it to the reader to adjust and confirm, which is the normal case.
	 *  False only settles it and calls [onSettled], for an image clearly not part of the story
	 * @param snapX edges to settle on, in this view's coordinates
	 */
	fun showSelection(
		rect: RectF,
		offerHandles: Boolean,
		snapX: FloatArray,
		snapY: FloatArray,
		onSettled: () -> Unit = {},
	) {
		cancelAnimator()
		this.snapX = snapX
		this.snapY = snapY
		animFrom.set(selection)
		animTo.set(rect)
		mode = Mode.SETTLING
		performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
		if (offerHandles) {
			showActions()
		}
		animate(SETTLE_MS, OvershootInterpolator(SETTLE_OVERSHOOT), end = {
			if (offerHandles) {
				mode = Mode.CROP
			}
			invalidate()
			onSettled()
		}) { progress ->
			lerp(animFrom, animTo, progress, selection)
			dimAlpha = progress.coerceIn(0f, 1f) * DIM_ALPHA
		}
	}

	/**
	 * Wipes the selection away. The area stays covered with [backgroundColor] once done, until [finish]:
	 * the page behind it still has the image on it until the reader has swapped it out.
	 */
	fun playErase(rect: RectF, backgroundColor: Int) {
		cancelAnimator()
		mode = Mode.ERASING
		eraseRect.set(rect)
		selection.set(rect)
		fillPaint.color = backgroundColor
		actions.isVisible = false
		performHapticFeedback(HapticFeedbackConstants.CONFIRM)
		seedDust(rect)
		animate(ERASE_MS, DecelerateInterpolator()) { progress ->
			dissolve = progress
			wipe = (progress * 2.5f).coerceAtMost(1f)
			dimAlpha = DIM_ALPHA * (1f - progress)
		}
	}

	/** Takes the cover away, now that there is nothing under it. */
	fun finish() {
		cancelAnimator()
		val startAlpha = fillPaint.alpha
		animate(FADE_MS, AccelerateInterpolator(), end = ::reset) { progress ->
			fillPaint.alpha = (startAlpha * (1f - progress)).toInt()
		}
	}

	fun cancel() {
		if (mode == Mode.IDLE) {
			return
		}
		val wasOffering = isActive
		reset()
		if (wasOffering) {
			listener?.onSelectionCancelled()
		}
	}

	private fun reset() {
		cancelAnimator()
		mode = Mode.IDLE
		activeHandle = Handle.NONE
		trailSize = 0
		dimAlpha = 0f
		dissolve = 0f
		wipe = 0f
		searchPulse = 0f
		fillPaint.alpha = 255
		actions.isVisible = false
		isVisible = false
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		if (mode != Mode.CROP) {
			return isActive
		}
		val x = event.x
		val y = event.y
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				activeHandle = findHandle(x, y)
				lastTouchX = x
				lastTouchY = y
			}

			MotionEvent.ACTION_MOVE -> {
				moveHandle(x - lastTouchX, y - lastTouchY)
				lastTouchX = x
				lastTouchY = y
				invalidate()
			}

			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				if (activeHandle != Handle.NONE) {
					settleOnEdges()
				}
				activeHandle = Handle.NONE
			}
		}
		return true
	}

	override fun onDetachedFromWindow() {
		cancelAnimator()
		super.onDetachedFromWindow()
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		when (mode) {
			Mode.IDLE -> if (trailSize > 0) drawTrail(canvas)
			Mode.TRAIL -> drawTrail(canvas)
			Mode.SEARCHING -> {
				drawTrail(canvas)
				borderPaint.alpha = (PULSE_MIN + (255 - PULSE_MIN) * abs(searchPulse * 2f - 1f)).toInt()
				canvas.drawRect(selection, borderPaint)
			}

			Mode.SETTLING -> drawSelection(canvas, withHandles = false)
			Mode.CROP -> drawSelection(canvas, withHandles = true)
			Mode.ERASING -> drawErase(canvas)
		}
	}

	private fun drawTrail(canvas: Canvas) {
		val now = android.os.SystemClock.uptimeMillis()
		val start = (trailEnd - trailSize + TRAIL_POINTS) % TRAIL_POINTS
		var isFading = false
		for (i in 1 until trailSize) {
			val from = (start + i - 1) % TRAIL_POINTS
			val to = (start + i) % TRAIL_POINTS
			val age = now - trailTime[to]
			if (age >= TRAIL_FADE_MS) {
				continue
			}
			isFading = true
			val alpha = (TRAIL_ALPHA * (1f - age.toFloat() / TRAIL_FADE_MS)).toInt()
			trailPaint.color = ColorUtils.setAlphaComponent(accent, alpha)
			canvas.drawLine(trailX[from], trailY[from], trailX[to], trailY[to], trailPaint)
		}
		if (isFading) {
			postInvalidateOnAnimation()
		}
	}

	private fun drawSelection(canvas: Canvas, withHandles: Boolean) {
		drawDim(canvas)
		borderPaint.alpha = 255
		canvas.drawRect(selection, borderPaint)
		if (withHandles) {
			drawHandles(canvas)
		}
	}

	private fun drawDim(canvas: Canvas) {
		dimPaint.alpha = (255 * dimAlpha).toInt()
		val w = width.toFloat()
		val h = height.toFloat()
		val r = selection
		canvas.drawRect(0f, 0f, w, r.top.coerceIn(0f, h), dimPaint)
		canvas.drawRect(0f, r.bottom.coerceIn(0f, h), w, h, dimPaint)
		canvas.drawRect(0f, r.top.coerceIn(0f, h), r.left.coerceIn(0f, w), r.bottom.coerceIn(0f, h), dimPaint)
		canvas.drawRect(r.right.coerceIn(0f, w), r.top.coerceIn(0f, h), w, r.bottom.coerceIn(0f, h), dimPaint)
	}

	/** Drawn on the part of the edge on screen: part of a long selection can be off either end. */
	private fun drawHandles(canvas: Canvas) {
		val r = selection
		val length = HANDLE_LENGTH_DP * density
		val left = r.left.coerceIn(0f, width.toFloat())
		val right = r.right.coerceIn(0f, width.toFloat())
		val top = r.top.coerceIn(0f, height.toFloat())
		val bottom = r.bottom.coerceIn(0f, height.toFloat())
		val midX = (left + right) / 2f
		val midY = (top + bottom) / 2f
		if (r.top >= 0f) {
			canvas.drawLine(left, top, left + length, top, handlePaint)
			canvas.drawLine(right - length, top, right, top, handlePaint)
			canvas.drawLine(midX - length / 2f, top, midX + length / 2f, top, handlePaint)
			canvas.drawLine(left, top, left, top + length, handlePaint)
			canvas.drawLine(right, top, right, top + length, handlePaint)
		}
		if (r.bottom <= height) {
			canvas.drawLine(left, bottom, left + length, bottom, handlePaint)
			canvas.drawLine(right - length, bottom, right, bottom, handlePaint)
			canvas.drawLine(midX - length / 2f, bottom, midX + length / 2f, bottom, handlePaint)
			canvas.drawLine(left, bottom - length, left, bottom, handlePaint)
			canvas.drawLine(right, bottom - length, right, bottom, handlePaint)
		}
		canvas.drawLine(left, midY - length / 2f, left, midY + length / 2f, handlePaint)
		canvas.drawLine(right, midY - length / 2f, right, midY + length / 2f, handlePaint)
	}

	private fun drawErase(canvas: Canvas) {
		drawDim(canvas)
		// Covered from the middle outwards, like a swipe of the eraser across it
		val rect = eraseRect
		val halfHeight = rect.height() / 2f * wipe
		val centerY = rect.centerY()
		canvas.drawRect(rect.left, centerY - halfHeight, rect.right, centerY + halfHeight, fillPaint)
		if (dissolve < 1f) {
			borderPaint.alpha = (255 * (1f - dissolve)).toInt()
			canvas.drawRect(rect, borderPaint)
			val distance = dissolve * dissolve
			dustPaint.color = ColorUtils.setAlphaComponent(accent, (220 * (1f - dissolve)).toInt())
			val radius = DUST_RADIUS_DP * density * (1f - dissolve * 0.5f)
			for (i in 0 until DUST_COUNT) {
				canvas.drawCircle(
					dustX[i] + dustVelocityX[i] * distance,
					dustY[i] + dustVelocityY[i] * distance,
					radius,
					dustPaint,
				)
			}
		}
	}

	private fun seedDust(rect: RectF) {
		val reach = DUST_REACH_DP * density
		for (i in 0 until DUST_COUNT) {
			dustX[i] = Random.nextFloat() * rect.width() + rect.left
			dustY[i] = Random.nextFloat() * rect.height() + rect.top
			val angle = Random.nextFloat() * TWO_PI
			val speed = (0.35f + Random.nextFloat() * 0.65f) * reach
			dustVelocityX[i] = cos(angle) * speed
			dustVelocityY[i] = sin(angle) * speed
		}
	}

	private fun findHandle(x: Float, y: Float): Handle {
		val reach = HANDLE_REACH_DP * density
		val r = selection
		val nearLeft = abs(x - r.left) <= reach
		val nearRight = abs(x - r.right) <= reach
		val nearTop = abs(y - r.top) <= reach
		val nearBottom = abs(y - r.bottom) <= reach
		val withinX = x >= r.left - reach && x <= r.right + reach
		val withinY = y >= r.top - reach && y <= r.bottom + reach
		return when {
			nearTop && nearLeft -> Handle.TOP_LEFT
			nearTop && nearRight -> Handle.TOP_RIGHT
			nearBottom && nearLeft -> Handle.BOTTOM_LEFT
			nearBottom && nearRight -> Handle.BOTTOM_RIGHT
			nearTop && withinX -> Handle.TOP
			nearBottom && withinX -> Handle.BOTTOM
			nearLeft && withinY -> Handle.LEFT
			nearRight && withinY -> Handle.RIGHT
			r.contains(x, y) -> Handle.MOVE
			else -> Handle.NONE
		}
	}

	private fun moveHandle(dx: Float, dy: Float) {
		val r = selection
		val minimum = MIN_SIZE_DP * density
		when (activeHandle) {
			Handle.NONE -> return
			Handle.MOVE -> r.offset(dx, dy)
			else -> {
				if (activeHandle == Handle.LEFT || activeHandle == Handle.TOP_LEFT || activeHandle == Handle.BOTTOM_LEFT) {
					r.left = (r.left + dx).coerceAtMost(r.right - minimum)
				}
				if (activeHandle == Handle.RIGHT || activeHandle == Handle.TOP_RIGHT || activeHandle == Handle.BOTTOM_RIGHT) {
					r.right = (r.right + dx).coerceAtLeast(r.left + minimum)
				}
				if (activeHandle == Handle.TOP || activeHandle == Handle.TOP_LEFT || activeHandle == Handle.TOP_RIGHT) {
					r.top = (r.top + dy).coerceAtMost(r.bottom - minimum)
				}
				if (activeHandle == Handle.BOTTOM || activeHandle == Handle.BOTTOM_LEFT || activeHandle == Handle.BOTTOM_RIGHT) {
					r.bottom = (r.bottom + dy).coerceAtLeast(r.top + minimum)
				}
			}
		}
	}

	/** The refit: let go near a gutter and the border settles on it, with a little spring. */
	private fun settleOnEdges() {
		val tolerance = SNAP_TOLERANCE_DP * density
		animFrom.set(selection)
		animTo.set(
			EraseProposer.snap(selection.left, snapX, tolerance),
			EraseProposer.snap(selection.top, snapY, tolerance),
			EraseProposer.snap(selection.right, snapX, tolerance),
			EraseProposer.snap(selection.bottom, snapY, tolerance),
		)
		if (animTo != animFrom) {
			performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
		}
		animate(SNAP_MS, OvershootInterpolator(SNAP_OVERSHOOT)) { progress ->
			lerp(animFrom, animTo, progress, selection)
		}
	}

	private fun showActions() {
		val bottomInset = ViewCompat.getRootWindowInsets(this)
			?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
		actions.updateLayoutParams<LayoutParams> {
			bottomMargin = bottomInset + (ACTIONS_MARGIN_DP * density).toInt()
		}
		actions.isVisible = true
		if (context.isAnimationsEnabled) {
			actions.alpha = 0f
			actions.translationY = ACTIONS_MARGIN_DP * density
			actions.animate().alpha(1f).translationY(0f).setDuration(SETTLE_MS).setInterpolator(DecelerateInterpolator())
				.start()
		}
	}

	private fun hideWhenTrailGone() {
		animate(TRAIL_FADE_MS, end = {
			if (mode == Mode.IDLE) reset()
		}) { invalidate() }
	}

	private inline fun animate(
		duration: Long,
		interpolator: android.animation.TimeInterpolator? = null,
		repeat: Boolean = false,
		noinline end: (() -> Unit)? = null,
		crossinline update: (Float) -> Unit,
	) {
		if (!context.isAnimationsEnabled) {
			update(1f)
			invalidate()
			end?.invoke()
			return
		}
		animator?.cancel()
		animator = ValueAnimator.ofFloat(0f, 1f).also { anim ->
			anim.duration = duration
			if (interpolator != null) anim.interpolator = interpolator
			if (repeat) anim.repeatCount = ValueAnimator.INFINITE
			anim.addUpdateListener {
				update(it.animatedValue as Float)
				invalidate()
			}
			if (end != null) {
				anim.addListener(object : AnimatorListenerAdapter() {
					private var isCancelled = false
					override fun onAnimationCancel(animation: Animator) {
						isCancelled = true
					}

					override fun onAnimationEnd(animation: Animator) {
						if (!isCancelled) end()
					}
				})
			}
			anim.start()
		}
	}

	private fun cancelAnimator() {
		animator?.cancel()
		animator = null
	}

	private fun lerp(from: RectF, to: RectF, progress: Float, out: RectF) {
		out.set(
			from.left + (to.left - from.left) * progress,
			from.top + (to.top - from.top) * progress,
			from.right + (to.right - from.right) * progress,
			from.bottom + (to.bottom - from.bottom) * progress,
		)
	}

	private companion object {
		const val TRAIL_POINTS = 96
		const val TRAIL_WIDTH_DP = 22f
		const val TRAIL_ALPHA = 150
		const val TRAIL_FADE_MS = 420L
		const val SEARCH_PULSE_MS = 700L
		const val PULSE_MIN = 90
		const val SETTLE_MS = 260L
		const val SETTLE_OVERSHOOT = 1.1f
		const val SNAP_MS = 180L
		const val SNAP_OVERSHOOT = 1.6f
		const val ERASE_MS = 420L
		const val FADE_MS = 160L
		const val DIM_ALPHA = 0.55f
		const val DUST_COUNT = 42
		const val DUST_RADIUS_DP = 2.5f
		const val DUST_REACH_DP = 64f
		const val HANDLE_LENGTH_DP = 22f
		const val HANDLE_REACH_DP = 28f
		const val MIN_SIZE_DP = 32f
		const val SNAP_TOLERANCE_DP = 26f
		const val ACTIONS_MARGIN_DP = 24f
		const val TWO_PI = 6.2831855f
	}
}
