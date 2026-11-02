package org.koitharu.kotatsu.reader.ui.pager.vm

import android.graphics.Rect
import android.net.Uri
import androidx.core.net.toFile
import androidx.annotation.WorkerThread
import com.davemorrissey.labs.subscaleview.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.ImageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import okio.IOException
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.throttle
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.domain.erase.PageMask
import org.koitharu.kotatsu.reader.domain.erase.PageVisibility
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings

class PageViewModel(
	private val loader: PageLoader,
	val settingsProducer: ReaderSettings.Producer,
	private val networkState: NetworkState,
	private val exceptionResolver: ExceptionResolver,
	private val isWebtoon: Boolean,
) : DefaultOnImageEventListener {

	private val scope = loader.loaderScope + Dispatchers.Main.immediate
	private var job: Job? = null
	private var cachedBounds: Rect? = null
	private var boundPage: MangaPage? = null

	/** The page as downloaded. Hidden parts are left out while decoding, so this is always the whole of it. */
	private var rawUri: Uri? = null

	/** The file behind what is on screen, once it is on screen. */
	val shownImage: Uri?
		get() = rawUri.takeIf { state.value is PageState.Shown }

	/** What the view is showing of that file, in the coordinates it was given. */
	val shownRegion: Rect?
		get() = cachedBounds

	val shownMask: PageMask?
		get() = (state.value as? PageState.Shown)?.mask

	val state = MutableStateFlow<PageState>(PageState.Empty)

	fun isLoading() = job?.isActive == true

	fun onBind(page: MangaPage) {
		val prevJob = job
		job = scope.launch(Dispatchers.Default) {
			prevJob?.cancelAndJoin()
			doLoad(page, force = false)
		}
	}

	fun retry(page: MangaPage, isFromUser: Boolean) {
		val prevJob = job
		job = scope.launch {
			prevJob?.cancelAndJoin()
			val e = (state.value as? PageState.Error)?.error
			if (e != null && ExceptionResolver.canResolve(e)) {
				if (isFromUser) {
					exceptionResolver.resolve(e, tryAutoResolve = false)
				}
			}
			withContext(Dispatchers.Default) {
				doLoad(page, force = true)
			}
		}
	}

	fun showErrorDetails(url: String?) {
		val e = (state.value as? PageState.Error)?.error ?: return
		exceptionResolver.showErrorDetails(e, url)
	}

	fun onRecycle() {
		state.value = PageState.Empty
		cachedBounds = null
		rawUri = null
		boundPage = null
		job?.cancel()
	}

	/**
	 * Checks a page that is already loaded against the current remembered images. Only touches the image
	 * if the answer changed, so erasing something does not make every page on screen blink.
	 */
	fun recheck() {
		val prevJob = job
		job = scope.launch(Dispatchers.Default) {
			prevJob?.join()
			val page = boundPage ?: return@launch
			val uri = rawUri ?: return@launch
			val visibility = loader.imageHider.resolve(page, uri)
			val isUnchanged = when (val current = state.value) {
				is PageState.Shown -> visibility == PageVisibility.Show(current.mask)
				is PageState.Loaded -> visibility == PageVisibility.Show(current.mask)
				else -> false
			}
			if (!isUnchanged) {
				present(uri, visibility, isConverted = false)
			}
		}
	}

	override fun onImageLoaded() {
		state.update { currentState ->
			if (currentState is PageState.Loaded) {
				PageState.Shown(currentState.source, currentState.isConverted, currentState.mask)
			} else {
				currentState
			}
		}
	}

	override fun onImageLoadError(e: Throwable) = onLoadFailed(e, canConvert = e is IOException)

	/**
	 * A phone can open an AVIF it then cannot decode, at a size under what is converted ahead of time. The view reports
	 * nothing but this and goes on waiting for the page, so before it is first shown this counts as not loading at all.
	 * Only for an AVIF: a part of any other image failing to decode has always been left at that.
	 */
	override fun onTileLoadError(e: Throwable) {
		val uri = ((state.value as? PageState.Loaded)?.source as? ImageSource.Uri)?.uri ?: return
		if (!uri.isFileUri()) {
			return
		}
		scope.launch(Dispatchers.IO) {
			if (BitmapDecoderCompat.isAvif(uri.toFile())) {
				onLoadFailed(e, canConvert = true)
			}
		}
	}

	private fun onLoadFailed(e: Throwable, canConvert: Boolean) {
		e.printStackTraceDebug()

		state.update { currentState ->
			if (currentState is PageState.Loaded) {
				val uri = (currentState.source as? ImageSource.Uri)?.uri
				if (!currentState.isConverted && uri != null && canConvert) {
					tryConvert(uri, e)
					PageState.Converting()
				} else {
					PageState.Error(e)
				}
			} else {
				currentState
			}
		}
	}

	private fun tryConvert(uri: Uri, e: Throwable) {
		val prevJob = job
		job = scope.launch(Dispatchers.Default) {
			prevJob?.join()
			state.value = PageState.Converting()
			try {
				val newUri = loader.convertBimap(uri)
				rawUri = newUri
				// The check before this could not read the image, which is why it needed converting
				val page = boundPage ?: return@launch
				present(newUri, loader.imageHider.resolve(page, newUri), isConverted = true)
			} catch (ce: CancellationException) {
				throw ce
			} catch (e2: Throwable) {
				e2.printStackTrace()
				e.addSuppressed(e2)
				state.value = PageState.Error(e)
			}
		}
	}

	@WorkerThread
	private suspend fun doLoad(data: MangaPage, force: Boolean) = coroutineScope {
		boundPage = data
		state.value = PageState.Loading(null, -1)
		val previewJob = launch {
			if (!loader.imageHider.canShowPreview(data)) return@launch
			val preview = loader.loadPreview(data) ?: return@launch
			state.update {
				if (it is PageState.Loading) it.copy(preview = preview) else it
			}
		}
		try {
			val task = loader.loadPageAsync(data, force)
			val progressObserver = observeProgress(this, task.progressAsFlow())
			val uri = try {
				task.await()
			} catch (e: CancellationException) {
				// Given up on by a view that went away just as this one came for it, which is no reason to sit here spinning
				ensureActive()
				loader.loadPage(data, force = false)
			}
			progressObserver.cancelAndJoin()
			previewJob.cancel()
			rawUri = uri
			present(uri, loader.imageHider.resolve(data, uri), isConverted = false)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Throwable) {
			e.printStackTraceDebug()
			state.value = PageState.Error(e)
			if (e is IOException && !networkState.value) {
				networkState.awaitForConnection()
				retry(data, isFromUser = false)
			}
		}
	}

	private fun observeProgress(scope: CoroutineScope, progress: Flow<Float>) = progress
		.throttle(250)
		.onEach {
			val progressValue = (100 * it).toInt()
			state.update { currentState ->
				if (currentState is PageState.Loading) {
					currentState.copy(progress = progressValue)
				} else {
					currentState
				}
			}
		}.launchIn(scope)

	private suspend fun present(uri: Uri, visibility: PageVisibility, isConverted: Boolean) {
		if (visibility !is PageVisibility.Show) {
			state.value = PageState.Hidden
			return
		}
		val trim = if (settingsProducer.value.isPagesCropEnabled(isWebtoon)) loader.getTrimmedBounds(uri) else null
		val mask = visibility.mask
		// A masked page always gets a region, which is what makes the view use the region decoder that
		// knows about the mask, rather than loading the file whole for being small enough.
		cachedBounds = when {
			mask == null -> trim
			trim == null -> Rect(0, 0, mask.sourceWidth, mask.visibleHeight)
			else -> Rect(trim.left, mask.toVisible(trim.top), trim.right, mask.toVisible(trim.bottom))
		}
		state.value = PageState.Loaded(uri.toImageSource(cachedBounds), isConverted, mask)
	}

	private fun Uri.toImageSource(bounds: Rect?): ImageSource {
		val source = ImageSource.uri(this)
		return if (bounds != null) {
			source.region(bounds)
		} else {
			source
		}
	}
}
