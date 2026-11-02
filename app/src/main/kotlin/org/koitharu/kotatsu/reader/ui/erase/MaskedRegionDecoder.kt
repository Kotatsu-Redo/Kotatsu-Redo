package org.koitharu.kotatsu.reader.ui.erase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.reader.domain.erase.PageMask
import org.koitharu.kotatsu.reader.domain.erase.SourceRegion

/**
 * Makes the image view see a page without its hidden parts, by answering for a shorter picture and fetching
 * around them. Since the view only ever asks for the tiles it is drawing, leaving a band out of a long strip
 * costs no more memory than showing it, loses nothing to re-encoding, and undoing it is immediate.
 */
class MaskedRegionDecoder(
	private val delegate: ImageRegionDecoder,
	private val mask: PageMask,
) : ImageRegionDecoder {

	private var patchColors = IntArray(0)

	override fun init(context: Context, uri: Uri): Point = onInit(delegate.init(context, uri))

	override fun init(context: Context, source: ImageSource): Point = onInit(delegate.init(context, source))

	override val isReady: Boolean
		get() = delegate.isReady

	override fun recycle() = delegate.recycle()

	override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
		val slices = mask.slices(sRect.top, sRect.bottom)
		val touchedPatches = mask.patches.indices.filter { index ->
			val patch = mask.patches[index]
			patch.left < sRect.right && patch.right > sRect.left && slices.any { patch.top <= it.last && patch.bottom > it.first }
		}
		// By far the most common tile: wholly on one side of anything hidden, and nothing to do
		if (slices.size == 1 && touchedPatches.isEmpty()) {
			return decodeSlice(sRect, slices.single(), sampleSize)
		}
		val parts = slices.map { decodeSlice(sRect, it, sampleSize) }
		try {
			val width = parts.maxOf { it.width }
			val output = Bitmap.createBitmap(width, parts.sumOf { it.height }, parts.first().config ?: Bitmap.Config.ARGB_8888)
			val canvas = Canvas(output)
			var y = 0f
			parts.forEachIndexed { index, part ->
				canvas.drawBitmap(part, 0f, y, null)
				touchedPatches.forEach { paintPatch(canvas, it, sRect, slices[index], y, sampleSize) }
				y += part.height
			}
			return output
		} finally {
			parts.forEach { it.recycle() }
		}
	}

	private fun decodeSlice(sRect: Rect, slice: IntRange, sampleSize: Int): Bitmap =
		delegate.decodeRegion(Rect(sRect.left, slice.first, sRect.right, slice.last + 1), sampleSize)

	private fun paintPatch(canvas: Canvas, index: Int, sRect: Rect, slice: IntRange, y: Float, sampleSize: Int) {
		val patch = mask.patches[index]
		val top = maxOf(patch.top, slice.first)
		val bottom = minOf(patch.bottom, slice.last + 1)
		if (bottom <= top) {
			return
		}
		val paint = Paint().apply { color = patchColors.getOrElse(index) { Color.WHITE } }
		canvas.drawRect(
			(maxOf(patch.left, sRect.left) - sRect.left).toFloat() / sampleSize,
			y + (top - slice.first).toFloat() / sampleSize,
			(minOf(patch.right, sRect.right) - sRect.left).toFloat() / sampleSize,
			y + (bottom - slice.first).toFloat() / sampleSize,
			paint,
		)
	}

	private fun onInit(size: Point): Point {
		patchColors = IntArray(mask.patches.size) { colorAround(mask.patches[it], size) }
		return Point(size.x, mask.visibleHeight)
	}

	/** The colour along the patch's top and bottom edges: on a page's flat background it then disappears. */
	private fun colorAround(patch: SourceRegion, size: Point): Int = try {
		var red = 0L
		var green = 0L
		var blue = 0L
		var count = 0L
		val rows = listOf(
			Rect(patch.left, (patch.top - EDGE_ROWS).coerceAtLeast(0), patch.right, patch.top.coerceAtLeast(1)),
			Rect(patch.left, patch.bottom.coerceAtMost(size.y - 1), patch.right, (patch.bottom + EDGE_ROWS).coerceAtMost(size.y)),
		)
		for (rect in rows) {
			val bitmap = delegate.decodeRegion(rect, EDGE_SAMPLE)
			val pixels = IntArray(bitmap.width * bitmap.height)
			bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
			bitmap.recycle()
			for (pixel in pixels) {
				red += Color.red(pixel)
				green += Color.green(pixel)
				blue += Color.blue(pixel)
				count++
			}
		}
		Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
	} catch (e: Exception) {
		e.printStackTraceDebug()
		Color.WHITE
	}

	class Factory(
		val delegate: DecoderFactory<out ImageRegionDecoder>,
		private val mask: PageMask,
	) : DecoderFactory<ImageRegionDecoder> {

		override val bitmapConfig: Bitmap.Config?
			get() = delegate.bitmapConfig

		override fun make(): ImageRegionDecoder = MaskedRegionDecoder(delegate.make(), mask)
	}

	private companion object {
		const val EDGE_ROWS = 8
		const val EDGE_SAMPLE = 4
	}
}
