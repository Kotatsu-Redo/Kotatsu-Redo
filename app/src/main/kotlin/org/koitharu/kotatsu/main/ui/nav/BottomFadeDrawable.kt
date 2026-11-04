package org.koitharu.kotatsu.main.ui.nav

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.annotation.Px
import androidx.core.graphics.ColorUtils

/**
 * The scrim behind the floating navigation bar: transparent at the top, settling over [fadeHeight] into a
 * mostly opaque wash of [color] that it keeps down to the bottom edge, so content scrolling under the bar
 * fades out instead of showing through at full strength.
 *
 * The stops are the ones Telegram's main tabs use behind their floating bar (BlurredBackgroundWithFadeDrawable,
 * with its opacity flag set): they top out a little short of opaque, so a hint of what is behind still shows.
 */
class BottomFadeDrawable(
	@ColorInt color: Int,
	@Px private val fadeHeight: Int,
) : Drawable() {

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val matrix = Matrix()
	private var paintAlpha = 255

	init {
		// A unit-height gradient, stretched to fadeHeight by the local matrix in draw(); CLAMP holds the
		// last stop below it.
		paint.shader = LinearGradient(
			0f,
			0f,
			0f,
			1f,
			STOPS.map { ColorUtils.setAlphaComponent(color, it * 255 / 285) }.toIntArray(),
			null,
			Shader.TileMode.CLAMP,
		)
	}

	override fun draw(canvas: Canvas) {
		val bounds = bounds
		if (bounds.isEmpty || paintAlpha == 0) return
		matrix.setScale(1f, fadeHeight.toFloat())
		matrix.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
		paint.shader.setLocalMatrix(matrix)
		paint.alpha = paintAlpha
		canvas.drawRect(bounds, paint)
	}

	override fun setAlpha(alpha: Int) {
		paintAlpha = alpha
		invalidateSelf()
	}

	override fun getAlpha(): Int = paintAlpha

	override fun setColorFilter(colorFilter: ColorFilter?) {
		paint.colorFilter = colorFilter
		invalidateSelf()
	}

	@Deprecated("Deprecated in Java")
	override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

	private companion object {

		val STOPS = intArrayOf(0x00, 0x60, 0xB0, 0xE8)
	}
}
