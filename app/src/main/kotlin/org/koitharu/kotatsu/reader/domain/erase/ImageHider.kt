package org.koitharu.kotatsu.reader.domain.erase

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Process
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.decoder.SkiaPooledImageRegionDecoder
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.util.SynchronizedSieveCache
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.data.erase.HiddenImageEntity
import org.koitharu.kotatsu.reader.data.erase.HiddenImagesRepository
import org.koitharu.kotatsu.reader.data.erase.PageScan
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/** What the reader should do with a page once it has been checked. */
sealed interface PageVisibility {
	/** @param mask what to leave out while decoding it, if anything */
	data class Show(val mask: PageMask?) : PageVisibility
	data object Hide : PageVisibility
}

/**
 * Stands between a downloaded page and the screen, so that a remembered image is gone before any of it
 * can be drawn.
 *
 * Costs nothing until the reader has erased something. After that a page is decoded small and looked over
 * once, and the answer is kept on disk: seeing it again costs one database read until another image is
 * erased. The page's own file is never changed.
 */
@ActivityRetainedScoped
class ImageHider @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val repository: HiddenImagesRepository,
) {

	/** Emits when the set of remembered images changes, so pages on screen can be checked again. */
	val rulesChanges = repository.rules

	/** Pages an image was found cut off on: the reader knows which page is next door, this does not. */
	private val foundCuts = MutableSharedFlow<FoundCut>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
	val cuts: Flow<FoundCut> = foundCuts

	private val decodeLock = Mutex()

	/** One page is looked over once, even with the preloader and the screen both asking for it at the same moment. */
	private val scanLocks = Array(SCAN_LOCKS) { Mutex() }

	/**
	 * Pages looked over ahead of time go one at a time on one thread, whatever else is going on. The page someone
	 * is looking at has the rest of the processor, and is at most one decode behind them in the queue for it.
	 */
	private val aheadPermit = Semaphore(1)
	@OptIn(ExperimentalCoroutinesApi::class)
	private val aheadDispatcher = Dispatchers.Default.limitedParallelism(1)
	private val memory = SynchronizedSieveCache<Long, PageScan>(MEMORY_CACHE_SIZE)

	/** Which pages are on the screen right now, and how many of those are being looked over: nothing else goes until they are done. */
	private val pagesOnScreen = MutableStateFlow(emptySet<Long>())
	private val urgentScans = MutableStateFlow(0)

	fun setOnScreen(pageId: Long, isOnScreen: Boolean) {
		pagesOnScreen.update { if (isOnScreen) it + pageId else it - pageId }
	}

	/**
	 * Whether a preview may be shown before the page has been checked: it is a small copy of the very image
	 * the reader asked never to see again. So only for a page already known to have none on it.
	 */
	suspend fun canShowPreview(page: MangaPage): Boolean {
		val rules = applicableRules(page)
		if (rules.isEmpty()) {
			return true
		}
		return savedScan(page, rules)?.resolution == PageResolution.Visible
	}

	/**
	 * @return how to show the page behind [uri]. Unreadable images are shown as they are, because that is
	 *  what the existing convert-and-retry path needs to see, and it comes back here afterwards.
	 */
	suspend fun resolve(page: MangaPage, uri: Uri): PageVisibility {
		val rules = applicableRules(page)
		if (rules.isEmpty()) {
			return PageVisibility.Show(null)
		}
		val saved = savedScan(page, rules)?.resolution
		// Looked over already, but the rest of what was cut off here may still have been out of reach
		saved?.cuts?.forEach { foundCuts.tryEmit(FoundCut(page.id, it)) }
		val resolution = saved
			?: scan(page, uri, rules)
			?: return PageVisibility.Show(null)
		return visibilityOf(page, resolution)
	}

	private suspend fun visibilityOf(page: MangaPage, resolution: PageResolution): PageVisibility {
		return when (resolution) {
			is PageResolution.Visible -> PageVisibility.Show(null)
			is PageResolution.Masked -> PageVisibility.Show(resolution.mask)
			is PageResolution.Hidden -> {
				// Remembered by page too: next time it is left out of the list without being loaded
				repository.markPageHidden(page.id, resolution.ruleIds.first())
				PageVisibility.Hide
			}
		}
	}

	/**
	 * Looks a preloaded page over ahead of time, so it is ready when the reader gets there. One that turns
	 * out to be nothing but a hidden image leaves the list before they ever do.
	 */
	suspend fun checkAhead(page: MangaPage, uri: Uri) {
		runCatchingCancellable {
			val rules = applicableRules(page)
			if (rules.isNotEmpty() && savedScan(page, rules) == null) {
				aheadPermit.withPermit {
					urgentScans.first { it == 0 }
					// What it was waiting for may well have been this very page, by a reader who got there first
					val resolution = savedScan(page, rules)?.resolution
						?: if (lockOf(page).isLocked) null else lookOver(page, uri, rules, isAhead = true)
					resolution?.let { visibilityOf(page, it) }
				}
			}
		}.onFailure { it.printStackTraceDebug() }
	}

	/** @param withChroma for comparing styles; a plain scan has no use for colour */
	suspend fun decodeLuma(uri: Uri, withChroma: Boolean = false, withDetail: Boolean = false): PageLuma = decodeLock.withLock {
		runInterruptible(Dispatchers.IO) {
			if (withDetail) {
				// A page that takes more memory than there is at this size is still looked over the other ways
				try {
					decodeFileWithDetail(uri) ?: decodeAnyUriWithDetail(uri)
				} catch (e: OutOfMemoryError) {
					e.printStackTraceDebug()
					decodeFile(uri, withChroma) ?: decodeAnyUri(uri, withChroma)
				}
			} else {
				decodeFile(uri, withChroma) ?: decodeAnyUri(uri, withChroma)
			}
		}
	}

	/** One decode for both sizes: decoding twice would take twice as long, for no more than this comes to. */
	private fun decodeFileWithDetail(uri: Uri): PageLuma? {
		val path = filePathOf(uri) ?: return null
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeFile(path, bounds)
		if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
			return null
		}
		val sample = DetailedPageBuilder.sampleSizeFor(bounds.outWidth, bounds.outHeight)
		val options = BitmapFactory.Options().apply {
			inPreferredConfig = Bitmap.Config.ARGB_8888
			inSampleSize = sample
		}
		val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
		try {
			return toDetailedLuma(bitmap, bounds.outWidth, bounds.outHeight, sample)
		} finally {
			bitmap.recycle()
		}
	}

	private fun decodeAnyUriWithDetail(uri: Uri): PageLuma {
		val decoder = SkiaPooledImageRegionDecoder(Bitmap.Config.ARGB_8888)
		try {
			val size = decoder.init(context, ImageSource.uri(uri))
			val sample = DetailedPageBuilder.sampleSizeFor(size.x, size.y)
			val bitmap = decoder.decodeRegion(Rect(0, 0, size.x, size.y), sample)
			try {
				return toDetailedLuma(bitmap, size.x, size.y, sample)
			} finally {
				bitmap.recycle()
			}
		} finally {
			decoder.recycle()
		}
	}

	private fun toDetailedLuma(bitmap: Bitmap, sourceWidth: Int, sourceHeight: Int, sample: Int): PageLuma {
		val builder = DetailedPageBuilder(bitmap.width, bitmap.height, sourceWidth, sourceHeight, sample)
		val width = bitmap.width
		val rows = IntArray(width * BITMAP_ROWS_AT_ONCE)
		var y = 0
		while (y < bitmap.height) {
			val count = minOf(BITMAP_ROWS_AT_ONCE, bitmap.height - y)
			bitmap.getPixels(rows, 0, width, 0, y, width, count)
			for (row in 0 until count) {
				builder.addRow(rows, row * width)
			}
			y += count
		}
		return builder.build()
	}

	/** What to find it by from now on, from the file itself at its full size. A page's preview would lose small images. */
	private suspend fun createDetail(uri: Uri, region: SourceRegion): ByteArray = decodeLock.withLock {
		runInterruptible(Dispatchers.IO) {
			var sample = 1
			while (region.width / (sample * 2) >= PageDetail.MAX_WIDTH) {
				sample *= 2
			}
			val rect = Rect(region.left, region.top, region.right, region.bottom)
			val bitmap = decodeFileRegion(uri, rect, sample, Bitmap.Config.ARGB_8888)
				?: decodeAnyUriRegion(uri, rect, sample, Bitmap.Config.ARGB_8888)
			try {
				val row = IntArray(bitmap.width)
				RuleDetail.ofRows(bitmap.width, bitmap.height, region.width, region.height) { y ->
					bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
					row
				}.encode()
			} finally {
				bitmap.recycle()
			}
		}
	}

	/**
	 * The usual case, read straight through. A pooled decoder exists for a page being scrolled around, and starts
	 * up more of itself in the background that this has no use for. Measured on 157 real pages, the result is the
	 * same to the last pixel.
	 *
	 * @return null to go the longer way, which also says why if the file really cannot be read
	 */
	private fun decodeFile(uri: Uri, withChroma: Boolean): PageLuma? {
		val path = filePathOf(uri) ?: return null
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeFile(path, bounds)
		if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
			return null
		}
		val options = BitmapFactory.Options().apply {
			inPreferredConfig = Bitmap.Config.RGB_565
			inSampleSize = PageLuma.sampleSizeFor(bounds.outWidth, bounds.outHeight)
		}
		val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
		try {
			return toLuma(bitmap, bounds.outWidth, bounds.outHeight, withChroma)
		} finally {
			bitmap.recycle()
		}
	}

	private fun decodeAnyUri(uri: Uri, withChroma: Boolean): PageLuma {
		val decoder = SkiaPooledImageRegionDecoder(Bitmap.Config.RGB_565)
		try {
			val size = decoder.init(context, ImageSource.uri(uri))
			val sample = PageLuma.sampleSizeFor(size.x, size.y)
			val bitmap = decoder.decodeRegion(Rect(0, 0, size.x, size.y), sample)
			try {
				return toLuma(bitmap, size.x, size.y, withChroma)
			} finally {
				bitmap.recycle()
			}
		} finally {
			decoder.recycle()
		}
	}

	private fun toLuma(bitmap: Bitmap, sourceWidth: Int, sourceHeight: Int, withChroma: Boolean): PageLuma {
		val width = bitmap.width
		val height = bitmap.height
		val row = IntArray(width)
		val data = ByteArray(width * height)
		val chroma = if (withChroma) ByteArray(width * height) else null
		val colour = PageColour.Builder(width, height)
		for (y in 0 until height) {
			bitmap.getPixels(row, 0, width, 0, y, width, 1)
			val offset = y * width
			for (x in 0 until width) {
				val pixel = row[x]
				val red = pixel shr 16 and 0xFF
				val green = pixel shr 8 and 0xFF
				val blue = pixel and 0xFF
				data[offset + x] = ((77 * red + 150 * green + 29 * blue) shr 8).toByte()
				colour.add(x, y, red, green, blue)
				if (chroma != null) {
					chroma[offset + x] = (maxOf(red, green, blue) - minOf(red, green, blue)).toByte()
				}
			}
		}
		return PageLuma(width, height, sourceWidth, sourceHeight, data, chroma, colour.build())
	}

	/** A plain file. An entry inside an archive is a file uri with more to it, and is not one of these. */
	private fun filePathOf(uri: Uri): String? =
		uri.path.takeIf { uri.scheme == ContentResolver.SCHEME_FILE && uri.fragment == null && uri.query == null }

	/**
	 * Removes the rest of a cut off image from the page next to where it was found, if it is there.
	 *
	 * Remembered as a rule for that one page, belonging to the image's own rule, so that bringing the image
	 * back brings this back with it.
	 */
	suspend fun hasRemainder(page: MangaPage, cut: EdgeCut): Boolean = repository.hasRemainder(cut.ruleId, page.id)

	suspend fun hideRemainder(page: MangaPage, uri: Uri, cut: EdgeCut) {
		if (repository.hasRemainder(cut.ruleId, page.id)) {
			return
		}
		val luma = decodeLuma(uri)
		val region = withContext(Dispatchers.Default) { EraseProposer.findRemainder(luma, cut) } ?: return
		repository.add(
			HiddenImageEntity(
				hashHorizontal = 0L,
				hashVertical = 0L,
				mean = 0,
				aspect = region.height.toFloat() / region.width,
				xStart = 0f,
				xEnd = 1f,
				createdAt = System.currentTimeMillis(),
				mangaTitle = null,
				thumbnail = null,
				pageId = page.id,
				yStart = region.top.toFloat() / luma.sourceHeight,
				parentId = cut.ruleId,
			),
		)
	}

	/**
	 * Remembers an image, and returns once that is on disk and in force.
	 *
	 * Anything can be remembered. What there is to recognise it by decides how far that reaches: something
	 * with detail, wherever it turns up; something blank, only on this page, since elsewhere it would match
	 * every other blank panel.
	 *
	 * Either way what was selected goes from this page, exactly as selected. Recognising an image can only ever
	 * be a judgement, and it must not be able to bring back what the reader just asked to remove.
	 *
	 * @param region where it is on the page behind [uri], in source pixels
	 * @return the new rule, or null if the region has no area at all
	 */
	suspend fun remember(page: MangaPage, uri: Uri, luma: PageLuma, region: SourceRegion, mangaTitle: String?): Long? {
		val shape = EraseProposer.describe(luma, region) ?: return null
		val thumbnail = runCatchingCancellable { createThumbnail(uri, region) }
			.onFailure { it.printStackTraceDebug() }
			.getOrNull()
		// Should it fail for whatever reason, the image still goes, and is still found the other ways
		val keypoints = if (shape.isRecognisable) {
			runCatchingCancellable { createDetail(uri, shape.content) }
				.onFailure { it.printStackTraceDebug() }
				.getOrNull()
		} else {
			null
		}
		val id = repository.add(
			HiddenImageEntity(
				hashHorizontal = shape.fingerprint?.horizontal ?: 0L,
				hashVertical = shape.fingerprint?.vertical ?: 0L,
				mean = shape.fingerprint?.mean ?: 0,
				aspect = shape.aspect,
				xStart = shape.xStart,
				xEnd = shape.xEnd,
				createdAt = System.currentTimeMillis(),
				mangaTitle = mangaTitle,
				thumbnail = thumbnail,
				signature = shape.signature,
				colour = shape.colour,
				keypoints = keypoints,
				pageId = if (shape.isRecognisable) null else page.id,
				yStart = shape.yStart,
				padTop = shape.padTop,
				padBottom = shape.padBottom,
				removesRows = shape.removesRows,
			),
		)
		if (shape.isRecognisable) {
			repository.add(
				HiddenImageEntity(
					hashHorizontal = 0L,
					hashVertical = 0L,
					mean = 0,
					aspect = region.height.toFloat() / region.width,
					xStart = region.left.toFloat() / luma.sourceWidth,
					xEnd = region.right.toFloat() / luma.sourceWidth,
					createdAt = System.currentTimeMillis(),
					mangaTitle = null,
					thumbnail = null,
					pageId = page.id,
					yStart = region.top.toFloat() / luma.sourceHeight,
					parentId = id,
					removesRows = shape.removesRows,
				),
			)
		}
		return id
	}

	suspend fun forget(id: Long) = repository.delete(id)

	/** Whether there is enough here to act on without asking: plain images always get the borders. */
	fun isRecognisable(luma: PageLuma, region: SourceRegion): Boolean {
		val left = luma.scaleX(region.left)
		val top = luma.scaleY(region.top)
		val right = luma.scaleX(region.right)
		val bottom = luma.scaleY(region.bottom)
		if (right - left < ImageFingerprint.MIN_SIZE || bottom - top < ImageFingerprint.MIN_SIZE) {
			return false
		}
		return ImageFingerprint.of(luma, left, top, right, bottom).isDistinctive
	}

	private suspend fun applicableRules(page: MangaPage): List<HiddenImageRule> =
		repository.getRules().filter { it.pageId == null || it.pageId == page.id }

	/**
	 * The answer already worked out for this page, if it still holds: nothing has been remembered since,
	 * and everything it found is still remembered. Rule ids are never reused, which is what makes the first
	 * of those a single comparison.
	 */
	private suspend fun savedScan(page: MangaPage, rules: List<HiddenImageRule>): PageScan? {
		val scan = memory[page.id] ?: repository.getScan(page.id)?.also { memory.put(page.id, it) } ?: return null
		val isCurrent = scan.matcherVersion == HiddenImageMatcher.VERSION &&
			scan.newestRuleId >= rules.maxOf { it.id } &&
			scan.resolution.ruleIds.all { id -> rules.any { it.id == id } }
		return if (isCurrent) scan else null
	}

	private fun lockOf(page: MangaPage) = scanLocks[(page.id and (SCAN_LOCKS - 1).toLong()).toInt()]

	private suspend fun scan(page: MangaPage, uri: Uri, rules: List<HiddenImageRule>): PageResolution? {
		// A page next to the screen has a view waiting for it too, and it waits behind what is really being looked at
		if (page.id !in pagesOnScreen.value) {
			combine(pagesOnScreen, urgentScans) { onScreen, urgent -> page.id in onScreen || urgent == 0 }.first { it }
		}
		val isUrgent = page.id in pagesOnScreen.value
		if (isUrgent) urgentScans.update { it + 1 }
		try {
			return lockOf(page).withLock {
				// Whoever was looking it over while this waited has already done the work
				savedScan(page, rules)?.let { return@withLock it.resolution }
				lookOver(page, uri, rules, isAhead = !isUrgent)
			}
		} finally {
			if (isUrgent) urgentScans.update { it - 1 }
		}
	}

	/** A scan from ahead of time is never waited on: one for the same page simply runs next to it, and both are right. */
	private suspend fun lookOver(page: MangaPage, uri: Uri, rules: List<HiddenImageRule>, isAhead: Boolean): PageResolution? {
		val toCheck = rulesToCheck(page, rules)
		val resolution = runCatchingCancellable {
			// Decoded at its usual priority all the same: it holds up anyone else who needs a page decoded
			val luma = decodeLuma(uri, withDetail = toCheck.any { it.keypoints != null })
			if (isAhead) {
				withContext(aheadDispatcher) { atBackgroundPriority { HiddenImageMatcher.resolve(luma, toCheck) } }
			} else {
				withContext(Dispatchers.Default) { HiddenImageMatcher.resolve(luma, toCheck) }
			}
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull() ?: return null
		val scan = PageScan(rules.maxOf { it.id }, resolution, HiddenImageMatcher.VERSION)
		memory.put(page.id, scan)
		// With an image cut off here there is more to do next door, which may not be possible yet: the page next door may not
		// be loaded, or the reader may be gone by then. Saved on disk as done, nothing would ever ask again after a restart.
		// In memory it keeps what was cut off, which is asked about each time, so this page is not looked over again for that
		if (resolution.cuts.isEmpty()) {
			repository.saveScan(page.id, scan)
		}
		resolution.cuts.forEach { foundCuts.tryEmit(FoundCut(page.id, it)) }
		return resolution
	}

	/**
	 * Nobody is waiting for this, and someone may be scrolling: the system runs it when there is time to spare, which is also
	 * what takes the least battery. Nothing here may suspend, or the rest would run on another thread and this one stay as it is.
	 */
	private inline fun <T> atBackgroundPriority(block: () -> T): T {
		val priority = Process.getThreadPriority(Process.myTid())
		Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
		try {
			return block()
		} finally {
			Process.setThreadPriority(priority)
		}
	}

	/**
	 * A page already known to have none of the older images on it can only be changed by the newer ones, which is
	 * nearly every page. One with something found on it is worked out again whole, to put the pieces together.
	 */
	private fun rulesToCheck(page: MangaPage, rules: List<HiddenImageRule>): List<HiddenImageRule> {
		val earlier = memory[page.id] ?: return rules
		if (earlier.matcherVersion != HiddenImageMatcher.VERSION || earlier.resolution != PageResolution.Visible) {
			return rules
		}
		return rules.filter { it.id > earlier.newestRuleId }.ifEmpty { rules }
	}

	private suspend fun createThumbnail(uri: Uri, region: SourceRegion): ByteArray = decodeLock.withLock {
		runInterruptible(Dispatchers.IO) {
			var sample = 1
			while (region.width / (sample * 2) >= THUMBNAIL_WIDTH) {
				sample *= 2
			}
			val rect = Rect(region.left, region.top, region.right, region.bottom)
			val bitmap = decodeFileRegion(uri, rect, sample) ?: decodeAnyUriRegion(uri, rect, sample)
			try {
				ByteArrayOutputStream().use { output ->
					bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, output)
					output.toByteArray()
				}
			} finally {
				bitmap.recycle()
			}
		}
	}

	@Suppress("DEPRECATION")
	private fun decodeFileRegion(uri: Uri, rect: Rect, sample: Int, config: Bitmap.Config = Bitmap.Config.RGB_565): Bitmap? {
		val path = filePathOf(uri) ?: return null
		val decoder = runCatching { BitmapRegionDecoder.newInstance(path, false) }.getOrNull() ?: return null
		try {
			val options = BitmapFactory.Options().apply {
				inPreferredConfig = config
				inSampleSize = sample
			}
			return decoder.decodeRegion(rect, options)
		} finally {
			decoder.recycle()
		}
	}

	private fun decodeAnyUriRegion(uri: Uri, rect: Rect, sample: Int, config: Bitmap.Config = Bitmap.Config.RGB_565): Bitmap {
		val decoder = SkiaPooledImageRegionDecoder(config)
		try {
			decoder.init(context, ImageSource.uri(uri))
			return decoder.decodeRegion(rect, sample)
		} finally {
			decoder.recycle()
		}
	}

	class FoundCut(val pageId: Long, val cut: EdgeCut)

	private companion object {

		/** Asking a bitmap for pixels has a cost of its own, per ask. */
		const val BITMAP_ROWS_AT_ONCE = 64

		const val MEMORY_CACHE_SIZE = 128

		/** A power of two. */
		const val SCAN_LOCKS = 16
		const val THUMBNAIL_WIDTH = 160
		const val THUMBNAIL_QUALITY = 70
	}
}
