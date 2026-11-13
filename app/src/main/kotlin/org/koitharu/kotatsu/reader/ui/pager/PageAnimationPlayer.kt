package org.koitharu.kotatsu.reader.ui.pager

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.annotation.RequiresApi
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.reader.domain.AnimatedPage
import org.koitharu.kotatsu.reader.domain.AnimatedPages

/**
 * Plays a page that moves over the still of it that the view shows.
 *
 * The frames are drawn in the view's own pass, right after the still and exactly where the view has put the still
 * at that moment. So whatever the view does with a page - fitting it to the screen, zooming, panning, cropping its
 * margins, laying a long strip out - is done to the frames too, with nothing here to keep in step with it. And a
 * page that cannot be played after all is simply left as the still that is already there.
 *
 * @param backdrop the view whose background shows through where a page has none of its own
 */
@RequiresApi(Build.VERSION_CODES.P)
class PageAnimationPlayer(
	private val ssiv: SubsamplingScaleImageView,
	private val backdrop: View,
	private val pages: AnimatedPages,
	private val scope: CoroutineScope,
) : View.OnLayoutChangeListener {

	private var source: Uri? = null
	private var region: Rect? = null
	private var job: Job? = null
	private var frames: Frames? = null

	/** The file last found to have nothing to play, so that it is not opened again each time the page comes back. */
	private var unplayable: Uri? = null

	/**
	 * Starts playing the file at [uri] over the still, or goes on if it already is.
	 *
	 * @param region the part of the picture the view was given, in the picture's own pixels, or null for all of it
	 */
	fun play(uri: Uri, region: Rect?) {
		if (uri == source && region == this.region) {
			frames?.page?.animation?.takeUnless { it.isRunning }?.start()
			return
		}
		stop()
		if (uri == unplayable) {
			return
		}
		source = uri
		this.region = region
		job = scope.launch {
			val page = pages.load(uri)
			if (page == null) {
				unplayable = uri
				source = null
				return@launch
			}
			show(Frames(page, region))
		}
	}

	/** Leaves the still as it is, and lets go of the frames: they take as much memory as several whole pages. */
	fun stop() {
		job?.cancel()
		job = null
		source = null
		region = null
		val shown = frames ?: return
		frames = null
		shown.page.animation.stop()
		shown.page.drawable.callback = null
		ssiv.removeOnLayoutChangeListener(this)
		ssiv.overlay.remove(shown)
		ssiv.invalidate()
	}

	/** For another page in the same view: what could not be played of the last one says nothing about this one. */
	fun reset() {
		stop()
		unplayable = null
	}

	override fun onLayoutChange(
		v: View,
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		oldLeft: Int,
		oldTop: Int,
		oldRight: Int,
		oldBottom: Int,
	) {
		frames?.setBounds(0, 0, right - left, bottom - top)
	}

	private fun show(shown: Frames) {
		frames = shown
		shown.setBounds(0, 0, ssiv.width, ssiv.height)
		shown.page.drawable.callback = shown
		ssiv.addOnLayoutChangeListener(this)
		ssiv.overlay.add(shown)
		shown.page.animation.start()
		ssiv.invalidate()
	}

	/** The frames, put where the still is each time the view is drawn. */
	private inner class Frames(val page: AnimatedPage, private val region: Rect?) : Drawable(), Drawable.Callback {

		private val origin = PointF()

		override fun draw(canvas: Canvas) {
			if (!ssiv.isReady || !isOverItsStill()) {
				return
			}
			val scale = ssiv.scale
			val topLeft = ssiv.sourceToViewCoord(0f, 0f, origin)
			if (topLeft == null || scale <= 0f) {
				return
			}
			val checkpoint = canvas.save()
			canvas.clipRect(
				topLeft.x,
				topLeft.y,
				topLeft.x + ssiv.sWidth * scale,
				topLeft.y + ssiv.sHeight * scale,
			)
			// A frame may be see-through where the first one was not: without this the still would show there
			drawBackdrop(canvas)
			canvas.translate(topLeft.x, topLeft.y)
			canvas.scale(scale, scale)
			if (region != null) {
				canvas.translate(-region.left.toFloat(), -region.top.toFloat())
			}
			val drawable = page.drawable
			val filter = ssiv.colorFilter
			if (drawable.colorFilter !== filter) {
				drawable.colorFilter = filter
			}
			// At the size of the whole picture, whatever size the frames were decoded at to fit in memory
			drawable.setBounds(0, 0, page.width, page.height)
			drawable.draw(canvas)
			canvas.restoreToCount(checkpoint)
		}

		/**
		 * Whether what the view shows is the still these frames belong over. Anything else in it, a preview or a
		 * page turned on its side, is left alone rather than drawn over at the wrong size.
		 */
		private fun isOverItsStill(): Boolean {
			val width = ssiv.sWidth
			val height = ssiv.sHeight
			return if (region == null) {
				width == page.width && height == page.height
			} else {
				width == region.width() && height == region.height() &&
					region.left >= 0 && region.top >= 0 && region.right <= page.width && region.bottom <= page.height
			}
		}

		private fun drawBackdrop(canvas: Canvas) {
			val own = ssiv.background
			if (own != null) {
				own.draw(canvas)
				return
			}
			val background = backdrop.background ?: return
			val checkpoint = canvas.save()
			canvas.translate(-ssiv.left.toFloat(), -ssiv.top.toFloat())
			background.draw(canvas)
			canvas.restoreToCount(checkpoint)
		}

		override fun setAlpha(alpha: Int) = Unit

		override fun setColorFilter(colorFilter: ColorFilter?) = Unit

		@Deprecated("Deprecated in Java")
		override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

		override fun invalidateDrawable(who: Drawable) {
			ssiv.invalidate()
		}

		override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
			ssiv.postDelayed(what, (`when` - SystemClock.uptimeMillis()).coerceAtLeast(0L))
		}

		override fun unscheduleDrawable(who: Drawable, what: Runnable) {
			ssiv.removeCallbacks(what)
		}
	}
}
