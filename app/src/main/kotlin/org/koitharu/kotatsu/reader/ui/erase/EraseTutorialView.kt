package org.koitharu.kotatsu.reader.ui.erase

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled

/**
 * Walks the reader through the erase gesture on the page they are actually reading.
 *
 * Only says what to do next: the gesture itself is the real one, run by [EraseController] in practice mode, which
 * shows everything an erase shows without remembering anything. Touches go through to the page, except on the card.
 */
class EraseTutorialView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), EraseController.PracticeListener {

	private enum class Step(@StringRes val title: Int, @StringRes val body: Int) {
		RUB(R.string.erase_tutorial_rub_title, R.string.erase_tutorial_rub_body),
		ADJUST(R.string.erase_tutorial_adjust_title, R.string.erase_tutorial_adjust_body),
		DONE(R.string.erase_tutorial_done_title, R.string.erase_tutorial_done_body),
	}

	/** Called with true if they went all the way through, false if they skipped it. */
	var onFinished: ((Boolean) -> Unit)? = null

	private val card: View
	private val title: TextView
	private val body: TextView
	private val note: View
	private val skip: View
	private val done: View
	private val demo: View
	private val demoHand: View
	private val demoStroke: View
	private var demoAnimator: ValueAnimator? = null

	init {
		LayoutInflater.from(context).inflate(R.layout.view_erase_tutorial, this, true)
		card = findViewById(R.id.card_erase_tutorial)
		title = findViewById(R.id.textView_erase_tutorial_title)
		body = findViewById(R.id.textView_erase_tutorial_body)
		note = findViewById(R.id.textView_erase_tutorial_note)
		skip = findViewById(R.id.button_erase_tutorial_skip)
		done = findViewById(R.id.button_erase_tutorial_done)
		demo = findViewById(R.id.layout_erase_tutorial_demo)
		demoHand = findViewById(R.id.imageView_erase_tutorial_hand)
		demoStroke = findViewById(R.id.view_erase_tutorial_stroke)
		skip.setOnClickListener { finish(completed = false) }
		done.setOnClickListener { finish(completed = true) }
		ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
			val top = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
			card.updateLayoutParams<LayoutParams> { topMargin = top + cardMargin }
			insets
		}
		isVisible = false
	}

	fun start() {
		isVisible = true
		show(Step.RUB)
		ViewCompat.requestApplyInsets(this)
	}

	fun stop() {
		stopDemo()
		isVisible = false
	}

	override fun onRubbing(isRubbing: Boolean) {
		if (isRubbing) {
			stopDemo()
			demo.isVisible = false
		} else if (isVisible) {
			show(Step.RUB)
		}
	}

	override fun onSelectionOffered() = show(Step.ADJUST)

	override fun onPracticeCancelled() {
		if (isVisible) {
			show(Step.RUB)
		}
	}

	override fun onPracticeErased() = show(Step.DONE)

	override fun onDetachedFromWindow() {
		stopDemo()
		super.onDetachedFromWindow()
	}

	private fun finish(completed: Boolean) {
		stop()
		onFinished?.invoke(completed)
	}

	private fun show(step: Step) {
		title.setText(step.title)
		body.setText(step.body)
		note.isVisible = step != Step.DONE
		skip.isVisible = step != Step.DONE
		done.isVisible = step == Step.DONE
		if (step == Step.RUB) {
			demo.isVisible = true
			startDemo()
		} else {
			stopDemo()
			demo.isVisible = false
		}
	}

	/** The same motion the gesture wants: short and quick from side to side. Still, if animations are off. */
	private fun startDemo() {
		if (demoAnimator != null || !context.isAnimationsEnabled) {
			return
		}
		val reach = DEMO_REACH_DP * resources.displayMetrics.density
		demoAnimator = ValueAnimator.ofFloat(-reach, reach).apply {
			duration = DEMO_STROKE_MS
			repeatCount = ValueAnimator.INFINITE
			repeatMode = ValueAnimator.REVERSE
			interpolator = AccelerateDecelerateInterpolator()
			addUpdateListener { demoHand.translationX = it.animatedValue as Float }
			start()
		}
		demoStroke.isVisible = true
	}

	private fun stopDemo() {
		demoAnimator?.cancel()
		demoAnimator = null
	}

	private val cardMargin: Int
		get() = (CARD_MARGIN_DP * resources.displayMetrics.density).toInt()

	private companion object {
		const val DEMO_REACH_DP = 44f
		const val DEMO_STROKE_MS = 420L
		const val CARD_MARGIN_DP = 12f
	}
}
