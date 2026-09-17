package org.koitharu.kotatsu.sourcescore.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import androidx.core.content.ContextCompat
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import org.koitharu.kotatsu.R

/**
 * The flame after a really popular source's name (see SourceRank.hotSources).
 *
 * An inline span rather than a compound drawable: both titles that carry it stretch to fill their
 * row, so a compound drawable would sit at the far edge of the screen instead of beside the name.
 */
class HotSourceMarker(context: Context) {

	private val icon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_trending_small)?.apply {
		setBounds(0, 0, intrinsicWidth, intrinsicHeight)
	}

	/**
	 * @param atStart put the flame before the name. Needed wherever the title is ellipsized to a
	 * narrow width, like the source grid: a trailing flame is the first thing the ellipsis cuts.
	 */
	fun decorate(title: CharSequence, isHot: Boolean, atStart: Boolean = false): CharSequence {
		val drawable = icon
		if (!isHot || drawable == null) return title
		return buildSpannedString {
			if (!atStart) {
				append(title)
				append('\u00A0')
			}
			// Baseline alignment: ALIGN_CENTER would be nicer but needs API 29.
			inSpans(ImageSpan(drawable, DynamicDrawableSpan.ALIGN_BASELINE)) { append('\uFFFC') }
			if (atStart) {
				append('\u00A0')
				append(title)
			}
		}
	}
}
