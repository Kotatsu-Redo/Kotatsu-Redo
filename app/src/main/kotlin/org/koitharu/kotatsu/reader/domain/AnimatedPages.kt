package org.koitharu.kotatsu.reader.domain

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.net.toFile
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.util.ext.activityManager
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isLowRamDevice
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.ramAvailable
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import javax.inject.Inject

/**
 * Tells a picture that moves from one that does not, by what its file holds.
 *
 * Never by its name. Nearly every page is a WebP and nearly none of them moves, and going by the name once gave
 * every one of them a plain image view in place of the reader's own: zooming went with it, and moving pages were
 * switched off altogether.
 */
internal object AnimatedImageSniffer {

	/** Enough for a WebP, which says at its start whether it holds an animation. */
	private const val HEADER_LENGTH = 21
	private const val WEBP_FLAGS_AT = 20
	private const val WEBP_FLAG_ANIMATION = 0x02

	private const val GIF_SCREEN_END = 13
	private const val GIF_FLAGS_AT = 10
	private const val GIF_FLAG_COLOR_TABLE = 0x80
	private const val GIF_BLOCK_EXTENSION = 0x21
	private const val GIF_BLOCK_IMAGE = 0x2C
	private const val GIF_IMAGE_DESCRIPTOR_LENGTH = 9

	/** Read at a time while a GIF is gone over. Nothing else is read past its first few bytes. */
	private const val GIF_BUFFER_BYTES = 16 * 1024

	/**
	 * Whether the file read from [input] holds more than one frame. Anything that is not what it should be counts
	 * as a picture that does not move, which is what it is then shown as.
	 *
	 * This is asked of every page there is, on every phone, so for all but a GIF it costs one read of the first
	 * [HEADER_LENGTH] bytes and nothing more.
	 */
	@Throws(IOException::class)
	fun isAnimated(input: InputStream): Boolean {
		val header = ByteArray(HEADER_LENGTH)
		val length = input.readUpTo(header)
		return when {
			header.startsWith("GIF87a", 0, length) || header.startsWith("GIF89a", 0, length) -> {
				length >= GIF_SCREEN_END && hasSecondFrame(
					flags = header[GIF_FLAGS_AT].toInt(),
					// What was read past the part every GIF starts with is still to be gone through
					input = SequenceInputStream(
						ByteArrayInputStream(header, GIF_SCREEN_END, length - GIF_SCREEN_END),
						BufferedInputStream(input, GIF_BUFFER_BYTES),
					),
				)
			}

			header.startsWith("RIFF", 0, length) && header.startsWith("WEBP", 8, length) -> {
				header.startsWith("VP8X", 12, length) && length > WEBP_FLAGS_AT &&
					header[WEBP_FLAGS_AT].toInt() and WEBP_FLAG_ANIMATION != 0
			}

			else -> false
		}
	}

	/**
	 * A GIF does not say how many frames it has: they are counted, by going over the first without decoding it.
	 * Many pages are a GIF of one frame, and are left to the reader as they always were.
	 */
	private fun hasSecondFrame(flags: Int, input: InputStream): Boolean {
		val buffer = ByteArray(256)
		if (!input.skipColorTable(flags, buffer)) {
			return false
		}
		var frames = 0
		while (true) {
			when (input.read()) {
				GIF_BLOCK_EXTENSION -> {
					if (input.read() < 0 || !input.skipSubBlocks(buffer)) {
						return false
					}
				}

				GIF_BLOCK_IMAGE -> {
					if (++frames > 1) {
						return true
					}
					if (input.readUpTo(buffer, GIF_IMAGE_DESCRIPTOR_LENGTH) < GIF_IMAGE_DESCRIPTOR_LENGTH) {
						return false
					}
					val imageFlags = buffer[GIF_IMAGE_DESCRIPTOR_LENGTH - 1].toInt()
					// Its colours, then how its data is packed, then the data
					if (!input.skipColorTable(imageFlags, buffer) || input.read() < 0 || !input.skipSubBlocks(buffer)) {
						return false
					}
				}

				// The end of the file, of the GIF, or of what can be made sense of
				else -> return false
			}
		}
	}

	private fun InputStream.skipColorTable(flags: Int, buffer: ByteArray): Boolean {
		if (flags and GIF_FLAG_COLOR_TABLE == 0) {
			return true
		}
		return skipFully(3 * (1 shl ((flags and 0x07) + 1)), buffer)
	}

	/** Each part says how long it is, and one of no length ends them. */
	private fun InputStream.skipSubBlocks(buffer: ByteArray): Boolean {
		while (true) {
			val size = read()
			when {
				size < 0 -> return false
				size == 0 -> return true
				!skipFully(size, buffer) -> return false
			}
		}
	}

	private fun InputStream.skipFully(count: Int, buffer: ByteArray): Boolean {
		var left = count
		while (left > 0) {
			val read = read(buffer, 0, minOf(left, buffer.size))
			if (read < 0) {
				return false
			}
			left -= read
		}
		return true
	}

	private fun InputStream.readUpTo(buffer: ByteArray, count: Int = buffer.size): Int {
		var total = 0
		while (total < count) {
			val read = read(buffer, total, count - total)
			if (read < 0) {
				break
			}
			total += read
		}
		return total
	}

	private fun ByteArray.startsWith(text: String, at: Int, length: Int): Boolean {
		if (at + text.length > minOf(length, size)) {
			return false
		}
		for (i in text.indices) {
			if (this[at + i] != text[i].code.toByte()) {
				return false
			}
		}
		return true
	}
}

/** The frames of a page that moves, ready to be played, and the size of the whole picture they are frames of. */
class AnimatedPage(
	val drawable: Drawable,
	val width: Int,
	val height: Int,
) {

	val animation: Animatable
		get() = drawable as Animatable
}

/**
 * Opens the pages that move: an animated GIF or WebP. They are played by the system, which has only been able to
 * since Android 9; before that they stay as still as they always were.
 */
@Reusable
class AnimatedPages @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	val isSupported: Boolean
		get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

	/**
	 * Whether the page file at [uri] is one that moves. Nothing of it is decoded, and for nearly every page no more
	 * is read than how it starts. Never fails: a file that cannot be read is no animation.
	 */
	suspend fun isAnimated(uri: Uri): Boolean = withContext(Dispatchers.IO) {
		try {
			open(uri)?.use { AnimatedImageSniffer.isAnimated(it) } == true
		} catch (e: IOException) {
			false
		} catch (e: RuntimeException) {
			e.printStackTraceDebug()
			false
		}
	}

	/**
	 * The frames of the page at [uri], or null if it has only the one, or cannot be played on this phone.
	 *
	 * A frame is kept whole in memory, and a few of them at a time, so a picture too large for that is played at a
	 * half or a quarter of its size rather than not at all.
	 */
	@RequiresApi(Build.VERSION_CODES.P)
	suspend fun load(uri: Uri): AnimatedPage? = withContext(Dispatchers.IO) {
		try {
			val source = when {
				uri.isZipUri() -> ImageDecoder.createSource(readZipEntry(uri) ?: return@withContext null)
				uri.isFileUri() -> ImageDecoder.createSource(uri.toFile())
				else -> return@withContext null
			}
			var width = 0
			var height = 0
			val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
				if (!info.isAnimated) {
					// Before anything of it is decoded: there is nothing here that the still does not already show
					throw NotAnimatedException()
				}
				width = info.size.width
				height = info.size.height
				val sampleSize = sampleSizeFor(width.toLong() * height, maxFramePixels())
				val frameBytes = (width / sampleSize).toLong() * (height / sampleSize) * BYTES_PER_PIXEL
				if (context.ramAvailable < frameBytes * FRAMES_HELD + RAM_TO_LEAVE) {
					throw IOException("Not enough free memory to play an animation of ${width}x$height")
				}
				if (sampleSize > 1) {
					decoder.setTargetSampleSize(sampleSize)
				}
			}
			if (drawable is Animatable && width > 0 && height > 0) AnimatedPage(drawable, width, height) else null
		} catch (e: NotAnimatedException) {
			null
		} catch (e: IOException) {
			e.printStackTraceDebug()
			null
		} catch (e: RuntimeException) {
			e.printStackTraceDebug()
			null
		}
	}

	private fun maxFramePixels(): Long = maxFramePixelsFor(
		memoryClassMb = context.activityManager?.memoryClass ?: 0,
		isLowRamDevice = context.isLowRamDevice(),
	)

	private fun open(uri: Uri): InputStream? = when {
		uri.isZipUri() -> {
			val zip = ZipFile(uri.schemeSpecificPart)
			val entry = zip.getEntry(uri.fragment)
			if (entry == null) {
				zip.close()
				null
			} else {
				ZipEntryStream(zip, zip.getInputStream(entry))
			}
		}

		uri.isFileUri() -> uri.toFile().inputStream()
		else -> null
	}

	/**
	 * The whole of a file kept in an archive, which is what playing it takes: frames are read out of it for as long
	 * as it plays. Held outside the memory the app's own objects live in. Read into that, a megabyte or so for each
	 * page that moves had the app stop to tidy up after it, which is felt as the screen stuttering.
	 */
	private fun readZipEntry(uri: Uri): ByteBuffer? = ZipFile(uri.schemeSpecificPart).use { zip ->
		val entry = zip.getEntry(uri.fragment) ?: return null
		val size = entry.size
		if (size <= 0 || size > MAX_ZIPPED_FILE_BYTES || context.ramAvailable < size + RAM_TO_LEAVE) {
			return null
		}
		val buffer = ByteBuffer.allocateDirect(size.toInt())
		val chunk = ByteArray(ZIP_CHUNK_BYTES)
		zip.getInputStream(entry).use { input ->
			while (true) {
				val read = input.read(chunk)
				if (read < 0) {
					break
				}
				if (read > buffer.remaining()) {
					// Longer than the archive says it is: not something to go on with
					return null
				}
				buffer.put(chunk, 0, read)
			}
		}
		if (buffer.hasRemaining()) {
			return null
		}
		buffer.flip()
		buffer
	}

	/** Closes the archive along with the one file read out of it. */
	private class ZipEntryStream(private val zip: ZipFile, private val stream: InputStream) : InputStream() {

		override fun read(): Int = stream.read()

		override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)

		override fun close() {
			stream.use { zip.close() }
		}
	}

	private class NotAnimatedException : RuntimeException() {

		override fun fillInStackTrace(): Throwable = this
	}

	companion object {

		/** About a full page of a manga at the size sites serve them. */
		private const val MAX_FRAME_PIXELS = 4_000_000L
		private const val MAX_FRAME_PIXELS_MODEST = 2_000_000L
		private const val MAX_FRAME_PIXELS_LOW_RAM = 1_000_000L
		private const val MEMORY_CLASS_MODEST_MB = 256
		private const val MEMORY_CLASS_LOW_MB = 128
		private const val BYTES_PER_PIXEL = 4

		/**
		 * How large a frame may be on a phone that gives each app [memoryClassMb] of memory. A frame is held whole and
		 * [FRAMES_HELD] at a time, so this is what keeps a page that moves from costing an old phone more than a few
		 * still ones do: 12 MB at the least, 48 MB on a phone with room for it. A page as sites usually serve them
		 * fits the smallest of these as it is.
		 */
		internal fun maxFramePixelsFor(memoryClassMb: Int, isLowRamDevice: Boolean): Long = when {
			isLowRamDevice || memoryClassMb <= MEMORY_CLASS_LOW_MB -> MAX_FRAME_PIXELS_LOW_RAM
			memoryClassMb < MEMORY_CLASS_MODEST_MB -> MAX_FRAME_PIXELS_MODEST
			else -> MAX_FRAME_PIXELS
		}

		/** The one on screen, the one being decoded for after it, and one to go back to between them. */
		private const val FRAMES_HELD = 3
		private const val RAM_TO_LEAVE = 64L * 1024 * 1024
		private const val MAX_ZIPPED_FILE_BYTES = 48L * 1024 * 1024
		private const val ZIP_CHUNK_BYTES = 16 * 1024

		/** By how much each side has to be divided, in halves, for a frame to hold no more than [maxPixels]. */
		internal fun sampleSizeFor(pixels: Long, maxPixels: Long): Int {
			var sampleSize = 1
			while (pixels / (sampleSize.toLong() * sampleSize) > maxPixels && sampleSize < MAX_SAMPLE_SIZE) {
				sampleSize *= 2
			}
			return sampleSize
		}

		private const val MAX_SAMPLE_SIZE = 16
	}
}
