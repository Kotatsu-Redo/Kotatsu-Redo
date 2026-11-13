package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Telling a page that moves from one that does not, by its file and never by its name.
 *
 * Going by the name is what had moving pages switched off: every WebP page, which is nearly every page, was taken
 * for one and lost the reader's zooming.
 */
class AnimatedImageSnifferTest {

	@Test
	fun `a GIF of one frame is a still picture`() {
		assertFalse(isAnimated(gif(frames = 1)))
		assertFalse(isAnimated(gif(frames = 1, hasGlobalColors = false, hasLocalColors = true)))
	}

	@Test
	fun `a GIF of several frames moves`() {
		assertTrue(isAnimated(gif(frames = 2)))
		assertTrue(isAnimated(gif(frames = 5, hasGlobalColors = false, hasLocalColors = true)))
		assertTrue(isAnimated(gif(frames = 2, signature = "GIF87a", withExtensions = false)))
	}

	@Test
	fun `a frame of many parts is gone over whole before the next one counts`() {
		// Bytes that look like the start of a frame, inside the data of the first one
		val data = ByteArray(700) { 0x2C }
		assertFalse(isAnimated(gif(frames = 1, frameData = data)))
		assertTrue(isAnimated(gif(frames = 2, frameData = data)))
	}

	@Test
	fun `a GIF cut short anywhere is read without failing`() {
		val whole = gif(frames = 3)
		for (length in 0 until whole.size) {
			// Either answer will do once a second frame has started: the system has the last word on playing it
			isAnimated(whole.copyOf(length))
		}
		assertFalse(isAnimated(whole.copyOf(13)))
		assertFalse(isAnimated(whole.copyOf(6)))
		assertFalse(isAnimated(gif(frames = 2, frameData = ByteArray(300)).copyOf(200)))
	}

	@Test
	fun `a WebP says for itself whether it moves`() {
		assertTrue(isAnimated(webp("VP8X", flags = 0x02)))
		assertTrue(isAnimated(webp("VP8X", flags = 0x12)))
		assertFalse(isAnimated(webp("VP8X", flags = 0x10)))
		assertFalse(isAnimated(webp("VP8X", flags = 0x00)))
		assertFalse(isAnimated(webp("VP8 ", flags = 0x02)))
		assertFalse(isAnimated(webp("VP8L", flags = 0x02)))
		assertFalse(isAnimated(webp("VP8X", flags = 0x02).copyOf(20)))
	}

	@Test
	fun `anything else is a still picture`() {
		assertFalse(isAnimated(ByteArray(0)))
		assertFalse(isAnimated(byteArrayOf(0x47)))
		assertFalse(isAnimated(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64)))
		assertFalse(isAnimated(byteArrayOf(0x89.toByte()) + "PNG".toByteArray() + ByteArray(64)))
		assertFalse(isAnimated("RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray() + ByteArray(32)))
	}

	@Test
	fun `a picture too large for memory is played smaller, by halves`() {
		assertEquals(1, AnimatedPages.sampleSizeFor(pixels = 1280L * 1800, maxPixels = 4_000_000))
		assertEquals(1, AnimatedPages.sampleSizeFor(pixels = 4_000_000, maxPixels = 4_000_000))
		assertEquals(2, AnimatedPages.sampleSizeFor(pixels = 800L * 10_000, maxPixels = 4_000_000))
		assertEquals(4, AnimatedPages.sampleSizeFor(pixels = 4000L * 6000, maxPixels = 4_000_000))
		assertEquals(16, AnimatedPages.sampleSizeFor(pixels = Long.MAX_VALUE / 4, maxPixels = 1))
	}

	/** Three frames are held at once: 12 MB of them on the oldest phones, and never more than 48 MB anywhere. */
	@Test
	fun `an old phone plays smaller frames, and a usual page still fits them whole`() {
		val low = AnimatedPages.maxFramePixelsFor(memoryClassMb = 96, isLowRamDevice = false)
		assertEquals(1_000_000L, low)
		assertEquals(low, AnimatedPages.maxFramePixelsFor(memoryClassMb = 128, isLowRamDevice = false))
		assertEquals(low, AnimatedPages.maxFramePixelsFor(memoryClassMb = 512, isLowRamDevice = true))
		// Not known: taken for the least
		assertEquals(low, AnimatedPages.maxFramePixelsFor(memoryClassMb = 0, isLowRamDevice = false))
		assertEquals(2_000_000L, AnimatedPages.maxFramePixelsFor(memoryClassMb = 192, isLowRamDevice = false))
		assertEquals(4_000_000L, AnimatedPages.maxFramePixelsFor(memoryClassMb = 256, isLowRamDevice = false))
		assertEquals(4_000_000L, AnimatedPages.maxFramePixelsFor(memoryClassMb = 512, isLowRamDevice = false))

		assertEquals(1, AnimatedPages.sampleSizeFor(pixels = 720L * 1024, maxPixels = low))
		assertEquals(1, AnimatedPages.sampleSizeFor(pixels = 800L * 1200, maxPixels = low))
		assertEquals(2, AnimatedPages.sampleSizeFor(pixels = 1280L * 1800, maxPixels = low))
	}

	private fun isAnimated(file: ByteArray): Boolean = AnimatedImageSniffer.isAnimated(file.inputStream())

	/** A file laid out as a GIF is, with nothing in it that would decode: only its layout is read. */
	private fun gif(
		frames: Int,
		signature: String = "GIF89a",
		hasGlobalColors: Boolean = true,
		hasLocalColors: Boolean = false,
		withExtensions: Boolean = true,
		frameData: ByteArray = byteArrayOf(0x44, 0x01),
	): ByteArray {
		val out = ByteArrayOutputStream()
		out.write(signature.toByteArray())
		// Width, height, then what follows, a colour and a shape
		out.write(byteArrayOf(2, 0, 2, 0, if (hasGlobalColors) 0x80.toByte() else 0, 0, 0))
		if (hasGlobalColors) {
			out.write(ByteArray(3 * 2))
		}
		if (withExtensions) {
			// How many times to play it
			out.write(byteArrayOf(0x21, 0xFF.toByte(), 11))
			out.write("NETSCAPE2.0".toByteArray())
			out.write(byteArrayOf(3, 1, 0, 0, 0))
		}
		repeat(frames) {
			if (withExtensions) {
				// How long this frame stays
				out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0))
			}
			out.write(byteArrayOf(0x2C, 0, 0, 0, 0, 2, 0, 2, 0, if (hasLocalColors) 0x81.toByte() else 0))
			if (hasLocalColors) {
				out.write(ByteArray(3 * 4))
			}
			out.write(2)
			var offset = 0
			while (offset < frameData.size) {
				val size = minOf(255, frameData.size - offset)
				out.write(size)
				out.write(frameData, offset, size)
				offset += size
			}
			out.write(0)
		}
		out.write(0x3B)
		return out.toByteArray()
	}

	private fun webp(firstChunk: String, flags: Int): ByteArray {
		val out = ByteArrayOutputStream()
		out.write("RIFF".toByteArray())
		out.write(byteArrayOf(64, 0, 0, 0))
		out.write("WEBP".toByteArray())
		out.write(firstChunk.toByteArray())
		out.write(byteArrayOf(10, 0, 0, 0))
		out.write(flags)
		out.write(ByteArray(32))
		return out.toByteArray()
	}
}
