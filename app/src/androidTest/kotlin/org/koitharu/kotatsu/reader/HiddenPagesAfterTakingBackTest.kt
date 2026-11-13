package org.koitharu.kotatsu.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.reader.data.erase.HiddenImagesDatabase
import org.koitharu.kotatsu.reader.data.erase.HiddenImagesRepository
import org.koitharu.kotatsu.reader.domain.erase.ImageHider
import org.koitharu.kotatsu.reader.domain.erase.PageVisibility
import org.koitharu.kotatsu.reader.domain.erase.SourceRegion
import java.io.File
import java.util.Random

/**
 * A page that two erased images hide between them is left out of the reader's list. Taking either of them back has to put
 * it back in the list, with what is left of it painted over as before.
 *
 * With a picture drawn here and a database in memory: nothing of the reader's own is read or written.
 */
@RunWith(AndroidJUnit4::class)
class HiddenPagesAfterTakingBackTest {

	private lateinit var database: HiddenImagesDatabase
	private lateinit var repository: HiddenImagesRepository
	private lateinit var hider: ImageHider
	private lateinit var folder: File

	@Before
	fun setUp() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		database = Room.inMemoryDatabaseBuilder(context, HiddenImagesDatabase::class.java).build()
		repository = HiddenImagesRepository(database)
		hider = ImageHider(context, repository)
		folder = File(context.cacheDir, "taking-back-test").apply {
			deleteRecursively()
			mkdirs()
		}
	}

	@After
	fun tearDown() {
		database.close()
		folder.deleteRecursively()
	}

	/** A ribbon of one flat colour across the top and, under a gap, a busy block: two bands, and nothing else on the page. */
	private fun credits(): Uri {
		val width = 720
		val height = 820
		val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		canvas.drawColor(Color.WHITE)
		val paint = Paint()
		paint.color = Color.rgb(255, 224, 138)
		canvas.drawRect(width * 0.08f, height * 0.05f, width * 0.92f, height * 0.13f, paint)
		val random = Random(5)
		val top = height * 0.24f
		val bottom = height * 0.96f
		paint.color = Color.rgb(20, 20, 28)
		canvas.drawRect(0f, top, width.toFloat(), bottom, paint)
		repeat(90) {
			paint.color = if (random.nextInt(3) == 0) Color.rgb(200, 60, 90) else Color.WHITE
			val w = width * (0.03f + random.nextFloat() * 0.22f)
			val h = height * (0.008f + random.nextFloat() * 0.04f)
			val x = random.nextFloat() * (width - w)
			val y = top + random.nextFloat() * (bottom - top - h)
			canvas.drawRect(x, y, x + w, y + h, paint)
		}
		val file = File(folder, "credits.png")
		file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
		return Uri.fromFile(file)
	}

	private val page = MangaPage(id = 1L, url = "/page/1", preview = null, source = UnknownMangaSource)

	/** Both bands gone, the page looked over and left out of the list: where each test starts. */
	private suspend fun eraseBoth(uri: Uri): Pair<Long, Long> {
		val luma = hider.decodeLuma(uri, withChroma = true)
		val ribbon = checkNotNull(hider.remember(page, uri, luma, SourceRegion(0, 0, 720, 150), null))
		val block = checkNotNull(hider.remember(page, uri, luma, SourceRegion(0, 150, 720, 820), null))
		assertEquals(PageVisibility.Hide, hider.resolve(page, uri))
		assertTrue(1L in repository.getHiddenPages())
		return ribbon to block
	}

	@Test
	fun takingTheFirstOneBackPutsThePageBackInTheList() = runBlocking<Unit> {
		val uri = credits()
		val (ribbon, _) = eraseBoth(uri)
		hider.forget(ribbon)
		assertFalse("still left out of the list", 1L in repository.getHiddenPages())
		assertTrue(hider.resolve(page, uri) is PageVisibility.Show)
		assertFalse(1L in repository.getHiddenPages())
	}

	@Test
	fun takingTheSecondOneBackPutsThePageBackInTheList() = runBlocking<Unit> {
		val uri = credits()
		val (_, block) = eraseBoth(uri)
		hider.forget(block)
		assertFalse("still left out of the list", 1L in repository.getHiddenPages())
		assertTrue(hider.resolve(page, uri) is PageVisibility.Show)
		assertFalse(1L in repository.getHiddenPages())
	}

	@Test
	fun aPageHiddenByAnImageThatStaysIsStillLeftOut() = runBlocking<Unit> {
		val uri = credits()
		val luma = hider.decodeLuma(uri, withChroma = true)
		// The whole page as one image, and something else erased on another page
		checkNotNull(hider.remember(page, uri, luma, SourceRegion(0, 0, 720, 820), null))
		assertEquals(PageVisibility.Hide, hider.resolve(page, uri))
		val elsewhere = MangaPage(id = 2L, url = "/page/2", preview = null, source = UnknownMangaSource)
		val other = checkNotNull(hider.remember(elsewhere, uri, luma, SourceRegion(0, 0, 720, 150), null))
		hider.forget(other)
		assertTrue(1L in repository.getHiddenPages())
	}
}
