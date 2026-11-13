package org.koitharu.kotatsu.list.ui.size

import android.content.SharedPreferences
import android.content.res.Resources
import android.view.View
import android.widget.TextView
import androidx.annotation.StyleRes
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.getThemeResId
import org.koitharu.kotatsu.history.ui.util.ReadingProgressView
import kotlin.math.roundToInt

class DynamicItemSizeResolver(
	resources: Resources,
	private val lifecycleOwner: LifecycleOwner,
	private val settings: AppSettings,
	private val adjustWidth: Boolean,
	/** The setting the grid takes its size from, and how to read it: the library's unless told otherwise. */
	private val sizeKey: String = AppSettings.KEY_GRID_SIZE,
	private val sizeOf: (AppSettings) -> Int = { it.gridSize },
) : ItemSizeResolver {

	private val gridWidth = resources.getDimension(R.dimen.preferred_grid_width)
	private val scaleFactor: Float
		get() = sizeOf(settings) / 100f

	override val cellWidth: Int
		get() = (gridWidth * scaleFactor).roundToInt()

	override fun attachToView(
		view: View,
		textView: TextView?,
		progressView: ReadingProgressView?
	) {
		val observer = SizeObserver(view, textView, progressView)
		view.addOnAttachStateChangeListener(observer)
		lifecycleOwner.lifecycle.addObserver(observer)
		if (view.isAttachedToWindow) {
			observer.update()
		}
	}

	private inner class SizeObserver(
		private val view: View,
		private val textView: TextView?,
		private val progressView: ReadingProgressView?,
	) : DefaultLifecycleObserver, SharedPreferences.OnSharedPreferenceChangeListener, View.OnAttachStateChangeListener {

		private val widthThreshold = view.resources.getDimensionPixelSize(R.dimen.small_grid_width)

		@StyleRes
		private var prevTextAppearance = 0

		override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
			if (key == sizeKey) {
				update()
			}
		}

		override fun onViewAttachedToWindow(v: View) {
			settings.subscribe(this)
			update()
		}

		override fun onViewDetachedFromWindow(v: View) {
			settings.unsubscribe(this)
		}

		override fun onDestroy(owner: LifecycleOwner) {
			super.onDestroy(owner)
			settings.unsubscribe(this)
			view.removeOnAttachStateChangeListener(this)
		}

		fun update() {
			val newWidth = cellWidth
			textView?.adjustTextAppearance(newWidth)
			if (adjustWidth) {
				val lp = view.layoutParams
				if (lp.width != newWidth) {
					lp.width = newWidth
					view.layoutParams = lp
				}
			}
			progressView?.adjustSize(newWidth)
		}

		private fun ReadingProgressView.adjustSize(width: Int) {
			val lp = layoutParams
			val size = resources.getDimensionPixelSize(
				if (width < widthThreshold) {
					R.dimen.card_indicator_size_small
				} else {
					R.dimen.card_indicator_size
				},
			)
			if (lp.width != size || lp.height != size) {
				lp.width = size
				lp.height = size
				layoutParams = lp
			}
		}

		private fun TextView.adjustTextAppearance(width: Int) {
			// Resolved from the theme rather than referenced directly: the Expressive theme draws grid
			// titles over the cover art and supplies its own appearance, which this swap would otherwise
			// overwrite every time the grid size changed.
			val textAppearanceResId = if (width < widthThreshold) {
				context.getThemeResId(R.attr.gridTitleTextAppearanceSmall, R.style.TextAppearance_Kotatsu_GridTitle_Small)
			} else {
				context.getThemeResId(R.attr.gridTitleTextAppearance, R.style.TextAppearance_Kotatsu_GridTitle)
			}
			if (textAppearanceResId != prevTextAppearance) {
				prevTextAppearance = textAppearanceResId
				TextViewCompat.setTextAppearance(this, textAppearanceResId)
				requestLayout()
			}
		}
	}
}
