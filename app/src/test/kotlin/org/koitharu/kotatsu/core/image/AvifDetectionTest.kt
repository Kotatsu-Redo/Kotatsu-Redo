package org.koitharu.kotatsu.core.image

import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvifDetectionTest {

	private fun ftyp(major: String, vararg compatible: String) = Buffer().apply {
		writeInt(16 + compatible.size * 4)
		write("ftyp".encodeUtf8())
		write(major.encodeUtf8())
		writeInt(0)
		compatible.forEach { write(it.encodeUtf8()) }
		write("mdat-and-the-rest".encodeUtf8())
	}

	@Test
	fun `an AVIF is known by its content whatever it is called`() {
		assertTrue(BitmapDecoderCompat.isAvif(ftyp("avif", "mif1")))
		assertTrue(BitmapDecoderCompat.isAvif(ftyp("avis")))
		assertTrue(BitmapDecoderCompat.isAvif(ftyp("mif1", "miaf", "avif")))
	}

	@Test
	fun `HEIC shares the container but is not an AVIF`() {
		assertFalse(BitmapDecoderCompat.isAvif(ftyp("heic", "mif1")))
	}

	@Test
	fun `other images are not an AVIF`() {
		assertFalse(BitmapDecoderCompat.isAvif(Buffer().write("\u00FF\u00D8\u00FF\u00E0 not a box at all".encodeUtf8())))
		assertFalse(BitmapDecoderCompat.isAvif(Buffer()))
	}

	@Test
	fun `looking does not use anything up`() {
		val source = ftyp("avif")
		val size = source.size
		BitmapDecoderCompat.isAvif(source)
		assertTrue(source.size == size)
	}
}
