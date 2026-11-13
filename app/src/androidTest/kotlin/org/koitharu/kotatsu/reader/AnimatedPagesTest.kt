package org.koitharu.kotatsu.reader

import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.util.ext.URI_SCHEME_ZIP
import org.koitharu.kotatsu.reader.domain.AnimatedPages
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pages that move are found by what their file holds, and played by the system. Checked here with real files, a
 * GIF and a WebP that move and one of each that does not, loose as pages are when read online and inside an
 * archive as they are once saved.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.P)
class AnimatedPagesTest {

	private val instrumentation
		get() = InstrumentationRegistry.getInstrumentation()

	private val pages = AnimatedPages(instrumentation.targetContext)
	private lateinit var dir: File

	@Before
	fun setUp() {
		dir = File(instrumentation.targetContext.cacheDir, "animated_pages_test").apply {
			deleteRecursively()
			mkdirs()
		}
	}

	@After
	fun tearDown() {
		dir.deleteRecursively()
	}

	@Test
	fun aGifThatMovesIsPlayed() = runTest {
		assertPlayed(loose("moving.gif"))
	}

	@Test
	fun aWebpThatMovesIsPlayed() = runTest {
		assertPlayed(loose("moving.webp"))
	}

	@Test
	fun aPageThatMovesIsPlayedOutOfAnArchiveToo() = runTest {
		assertPlayed(archived("moving.gif"))
		assertPlayed(archived("moving.webp"))
	}

	@Test
	fun aGifOfOneFrameIsLeftAsItIs() = runTest {
		assertLeftAlone(loose("still.gif"))
		assertLeftAlone(archived("still.gif"))
	}

	@Test
	fun aWebpThatDoesNotMoveIsLeftAsItIs() = runTest {
		assertLeftAlone(loose("still.webp"))
		assertLeftAlone(archived("still.webp"))
	}

	/** Under a name that says nothing, or the wrong thing: sites serve one kind of picture under another's name. */
	@Test
	fun theNameOfTheFileSaysNothing() = runTest {
		assertPlayed(loose("moving.webp", savedAs = "page.jpg"))
		assertLeftAlone(loose("still.webp", savedAs = "page.gif"))
	}

	@Test
	fun aFileThatIsNotThereIsNoAnimation() = runTest {
		val missing = File(dir, "missing.gif").toUri()
		assertFalse(pages.isAnimated(missing))
		assertNull(pages.load(missing))
		assertFalse(pages.isAnimated(Uri.fromParts(URI_SCHEME_ZIP, File(dir, "missing.cbz").absolutePath, "1.gif")))
	}

	private suspend fun assertPlayed(uri: Uri) {
		assertTrue("Not taken for a page that moves: $uri", pages.isAnimated(uri))
		val page = pages.load(uri)
		assertNotNull("Could not be opened to play: $uri", page)
		assertTrue(page!!.drawable is AnimatedImageDrawable)
		assertEquals(WIDTH, page.width)
		assertEquals(HEIGHT, page.height)
	}

	private suspend fun assertLeftAlone(uri: Uri) {
		assertFalse("Taken for a page that moves: $uri", pages.isAnimated(uri))
		// Even asked to, as a GIF only shows how many frames it has once it is opened
		assertNull(pages.load(uri))
	}

	private fun loose(asset: String, savedAs: String = asset): Uri {
		val file = File(dir, savedAs)
		file.writeBytes(read(asset))
		return file.toUri()
	}

	private fun archived(asset: String): Uri {
		val file = File(dir, "$asset.cbz")
		ZipOutputStream(file.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("001.jpg"))
			zip.write(read("still.webp"))
			zip.closeEntry()
			zip.putNextEntry(ZipEntry(asset))
			zip.write(read(asset))
			zip.closeEntry()
		}
		return Uri.fromParts(URI_SCHEME_ZIP, file.absolutePath, asset)
	}

	private fun read(asset: String): ByteArray = instrumentation.context.assets.open("pages/$asset").use { it.readBytes() }

	private companion object {

		const val WIDTH = 16
		const val HEIGHT = 24
	}
}
