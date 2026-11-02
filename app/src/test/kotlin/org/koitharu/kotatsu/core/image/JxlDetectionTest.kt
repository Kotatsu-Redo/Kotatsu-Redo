package org.koitharu.kotatsu.core.image

import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.util.MimeTypes

class JxlDetectionTest {

	private val container = byteArrayOf(
		0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A, 0x00, 0x00, 0x00, 0x14,
	)
	private val codestream = byteArrayOf(0xFF.toByte(), 0x0A, 0xFA.toByte(), 0x1F)

	@Test
	fun detectsContainerAndCodestream() {
		assertTrue(BitmapDecoderCompat.isJxl(Buffer().write(container)))
		assertTrue(BitmapDecoderCompat.isJxl(Buffer().write(codestream)))
	}

	@Test
	fun rejectsOtherFormatsAndTruncatedInput() {
		val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
		val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
		assertFalse(BitmapDecoderCompat.isJxl(Buffer().write(jpeg)))
		assertFalse(BitmapDecoderCompat.isJxl(Buffer().write(png)))
		assertFalse(BitmapDecoderCompat.isJxl(Buffer()))
		assertFalse(BitmapDecoderCompat.isJxl(Buffer().write(byteArrayOf(0xFF.toByte()))))
		assertFalse(BitmapDecoderCompat.isJxl(Buffer().write(container.copyOf(8))))
	}

	@Test
	fun detectionDoesNotConsumeTheSource() {
		// Coil hands the same source on to whichever decoder is picked
		val source = Buffer().write(container)
		BitmapDecoderCompat.isJxl(source)
		assertArrayEquals(container, source.readByteArray())
	}

	@Test
	fun mimeTypeRoundTrip() {
		assertEquals(MimeTypes.JXL, MimeTypes.getMimeTypeFromExtension("001.jxl"))
		assertEquals(MimeTypes.JXL, MimeTypes.getMimeTypeFromExtension("PAGE.JXL"))
		assertEquals(MimeTypes.JXL, MimeTypes.getMimeTypeFromUrl("https://cdn.example.com/c/12/001.jxl?token=abc#x"))
		assertEquals("jxl", MimeTypes.getExtension(MimeTypes.JXL))
	}
}
