package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.PreferredColorConfig
import com.awxkee.jxlcoder.ScaleMode
import com.davemorrissey.labs.subscaleview.decoder.ImageDecodeException
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.IOException
import okio.buffer
import okio.source
import org.aomedia.avif.android.AvifDecoder
import org.aomedia.avif.android.AvifDecoder.Info
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.readByteBuffer
import org.koitharu.kotatsu.core.util.ext.toByteBuffer
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

object BitmapDecoderCompat {

	private const val FORMAT_AVIF = "avif"
	private const val FORMAT_JXL = "jxl"

	/** A bare JPEG XL codestream. */
	private val JXL_CODESTREAM = byteArrayOf(0xFF.toByte(), 0x0A).toByteString()

	/** The signature box that opens a JPEG XL in its ISOBMFF container. */
	private val JXL_CONTAINER = byteArrayOf(
		0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A,
	).toByteString()

	private const val JXL_HEADER_SIZE = 12

	private val FTYP_BOX = "ftyp".encodeUtf8()
	private val AVIF_BRANDS = setOf("avif".encodeUtf8(), "avis".encodeUtf8())
	private const val FTYP_MIN_SIZE = 16L
	private const val FTYP_MAX_SIZE = 256L

	/**
	 * The platform decodes an AVIF as one frame of AV1 video, and video decoders have a size they stop at. A Pixel 8 Pro
	 * refuses 720x16965 outright: it opens the file, then fails on every part of it, with nothing to say so.
	 */
	private const val MAX_PLATFORM_AVIF_SIZE = 4096

	@Blocking
	fun decode(file: File): Bitmap {
		// By content, not name: sites serve JPEG XL and AVIF under .jpg URLs, labelled as JPEG
		if (isJxl(file)) {
			return decodeJxl(file.readBytes())
		}
		return when (val format = probeMimeType(file)?.subtype) {
			FORMAT_AVIF -> file.source().buffer().use { decodeAvif(it.readByteBuffer()) }
			else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				ImageDecoder.decodeBitmap(ImageDecoder.createSource(file))
			} else {
				checkBitmapNotNull(BitmapFactory.decodeFile(file.absolutePath), format)
			}
		}
	}

	@Blocking
	fun decode(stream: InputStream, type: MimeType?, isMutable: Boolean = false): Bitmap {
		val format = type?.subtype
		if (format == FORMAT_AVIF) {
			return decodeAvif(stream.toByteBuffer())
		}
		if (format == FORMAT_JXL) {
			return decodeJxl(stream.readBytes(), isMutable)
		}
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
			val input = if (stream.markSupported()) stream else stream.buffered()
			input.mark(JXL_HEADER_SIZE)
			val header = ByteArray(JXL_HEADER_SIZE)
			val headerSize = input.readNBytesCompat(header)
			input.reset()
			if (isJxl(header.toByteString(0, headerSize))) {
				return decodeJxl(input.readBytes(), isMutable)
			}
			val opts = BitmapFactory.Options()
			opts.inMutable = isMutable
			return checkBitmapNotNull(BitmapFactory.decodeStream(input, null, opts), format)
		}
		val byteBuffer = stream.toByteBuffer()
		return when {
			AvifDecoder.isAvifImage(byteBuffer) -> decodeAvif(byteBuffer)
			isJxl(byteBuffer) -> decodeJxl(ByteArray(byteBuffer.remaining()).also { byteBuffer.duplicate().get(it) }, isMutable)
			else -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(byteBuffer), DecoderConfigListener(isMutable))
		}
	}

	/** Checks the signature without consuming it, which is what Coil's decoder factories need. */
	fun isJxl(source: BufferedSource): Boolean =
		source.rangeEquals(0, JXL_CONTAINER) || source.rangeEquals(0, JXL_CODESTREAM)

	@Blocking
	fun isJxl(file: File): Boolean = runCatchingCancellable {
		file.source().buffer().use { isJxl(it) }
	}.getOrDefault(false)

	/**
	 * Checks the brands of the opening `ftyp` box without consuming it. HEIC shares this container,
	 * so the box alone says nothing: only the brands tell an AVIF apart.
	 */
	fun isAvif(source: BufferedSource): Boolean {
		val peek = source.peek()
		if (!peek.request(8)) {
			return false
		}
		val size = peek.readInt().toLong() and 0xFFFFFFFFL
		if (peek.readByteString(4) != FTYP_BOX || size !in FTYP_MIN_SIZE..FTYP_MAX_SIZE || !peek.request(size - 8)) {
			return false
		}
		val majorBrand = peek.readByteString(4)
		if (majorBrand in AVIF_BRANDS) {
			return true
		}
		peek.skip(4) // minor version
		return (0 until (size - FTYP_MIN_SIZE) / 4).any { peek.readByteString(4) in AVIF_BRANDS }
	}

	@Blocking
	fun isAvif(file: File): Boolean = runCatchingCancellable {
		file.source().buffer().use { isAvif(it) }
	}.getOrDefault(false)

	/** Whether [file] is an AVIF the platform cannot be relied on for, see [MAX_PLATFORM_AVIF_SIZE]. */
	@Blocking
	fun isAvifTooLargeForPlatform(file: File): Boolean = runCatchingCancellable {
		if (!isAvif(file)) {
			return@runCatchingCancellable false
		}
		val info = Info()
		val bytes = file.source().buffer().use { it.readByteBuffer() }
		AvifDecoder.getInfo(bytes, bytes.remaining(), info) && maxOf(info.width, info.height) > MAX_PLATFORM_AVIF_SIZE
	}.getOrDefault(false)

	/**
	 * Free memory to ask for before decoding [file].
	 *
	 * Twice the file size is the rule for the formats this was written for. A JPEG XL or AVIF is often a
	 * tenth of the PNG it decodes to, so for those that guess is read from the header instead.
	 */
	@Blocking
	fun estimateDecodedSize(file: File): Long {
		val guess = file.length() * 2
		if (isAvif(file)) {
			val info = Info()
			val bytes = file.source().buffer().use { it.readByteBuffer() }
			return if (AvifDecoder.getInfo(bytes, bytes.remaining(), info)) {
				maxOf(guess, info.width.toLong() * info.height * 4)
			} else {
				guess
			}
		}
		if (!isJxl(file) || !JxlSupport.isAvailable) {
			return guess
		}
		val size = runCatchingCancellable { JxlCoder.getSize(file.readBytes()) }.getOrNull() ?: return guess
		return maxOf(guess, size.width.toLong() * size.height * 4)
	}

	@Blocking
	fun createRegionDecoder(inoutStream: InputStream): BitmapRegionDecoder? = try {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			BitmapRegionDecoder.newInstance(inoutStream)
		} else {
			@Suppress("DEPRECATION")
			BitmapRegionDecoder.newInstance(inoutStream, false)
		}
	} catch (e: IOException) {
		e.printStackTraceDebug()
		null
	}

	@Blocking
	fun probeMimeType(file: File): MimeType? {
		if (isJxl(file)) {
			return MimeTypes.JXL
		}
		if (isAvif(file)) {
			return MimeTypes.AVIF
		}
		return MimeTypes.probeMimeType(file) ?: detectBitmapType(file)
	}

	@Blocking
	private fun detectBitmapType(file: File): MimeType? = runCatchingCancellable {
		val options = BitmapFactory.Options().apply {
			inJustDecodeBounds = true
		}
		BitmapFactory.decodeFile(file.path, options)?.recycle()
		options.outMimeType?.toMimeTypeOrNull()
	}.getOrNull()

	private fun checkBitmapNotNull(bitmap: Bitmap?, format: String?): Bitmap =
		bitmap ?: throw ImageDecodeException(null, format)

	private fun decodeAvif(bytes: ByteBuffer): Bitmap {
		val info = Info()
		if (!AvifDecoder.getInfo(bytes, bytes.remaining(), info)) {
			throw ImageDecodeException(
				null,
				FORMAT_AVIF,
				"Requested to decode byte buffer which cannot be handled by AvifDecoder",
			)
		}
		val config = if (info.depth == 8 || info.alphaPresent) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
		val bitmap = createBitmap(info.width, info.height, config)
		if (!AvifDecoder.decode(bytes, bytes.remaining(), bitmap)) {
			bitmap.recycle()
			throw ImageDecodeException(null, FORMAT_AVIF)
		}
		return bitmap
	}

	private fun decodeJxl(bytes: ByteArray, isMutable: Boolean = false): Bitmap {
		if (!JxlSupport.isAvailable) {
			throw ImageDecodeException(null, FORMAT_JXL, "JPEG XL is not supported on this device")
		}
		// 8-bit RGBA whatever the source depth: a HARDWARE or F16 bitmap cannot be re-encoded to PNG
		// by the reader's conversion fallback the same way
		val bitmap = JxlCoder.decode(bytes, PreferredColorConfig.RGBA_8888, ScaleMode.FIT)
		if (!isMutable || bitmap.isMutable) {
			return bitmap
		}
		return bitmap.copy(Bitmap.Config.ARGB_8888, true).also { bitmap.recycle() }
	}

	private fun isJxl(header: ByteString): Boolean =
		header.startsWith(JXL_CONTAINER) || header.startsWith(JXL_CODESTREAM)

	private fun isJxl(buffer: ByteBuffer): Boolean {
		val size = minOf(buffer.remaining(), JXL_HEADER_SIZE)
		val header = ByteArray(size).also { buffer.duplicate().get(it) }
		return isJxl(header.toByteString())
	}

	private fun InputStream.readNBytesCompat(buffer: ByteArray): Int {
		var total = 0
		while (total < buffer.size) {
			val read = read(buffer, total, buffer.size - total)
			if (read < 0) {
				break
			}
			total += read
		}
		return total
	}

	@RequiresApi(Build.VERSION_CODES.P)
	private class DecoderConfigListener(
		private val isMutable: Boolean,
	) : ImageDecoder.OnHeaderDecodedListener {

		override fun onHeaderDecoded(
			decoder: ImageDecoder,
			info: ImageDecoder.ImageInfo,
			source: ImageDecoder.Source
		) {
			decoder.isMutableRequired = isMutable
		}
	}
}
