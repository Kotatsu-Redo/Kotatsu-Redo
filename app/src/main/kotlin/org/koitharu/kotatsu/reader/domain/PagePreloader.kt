package org.koitharu.kotatsu.reader.domain

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.imageproxy.ImageProxyInterceptor
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.ensureSuccess
import org.koitharu.kotatsu.core.util.ext.isPowerSaveMode
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toMimeType
import org.koitharu.kotatsu.local.data.LocalStorageCache
import org.koitharu.kotatsu.local.data.PageCache
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.requireBody
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gets a chapter ready while its details screen is open, so that tapping Read has nothing left to wait for.
 *
 * Asks for what the reader would ask for itself a moment later: the page list goes into the same cache the reader
 * reads, and the images into its page cache. A new title only gets the list, which is small. Images are for one the
 * reader has already started, on a connection they have allowed preloading on.
 */
@Singleton
class PagePreloader @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: AppSettings,
	private val networkState: NetworkState,
	private val repositoryFactory: MangaRepository.Factory,
	@MangaHttpClient private val okHttp: OkHttpClient,
	@PageCache private val cache: LocalStorageCache,
	private val imageProxyInterceptor: ImageProxyInterceptor,
) {

	private val downloads = ConcurrentHashMap<String, Job>()

	suspend fun preload(manga: Manga, chapter: MangaChapter, startPage: Int, withImages: Boolean) {
		if (manga.isLocal || networkState.isOfflineOrRestricted()) {
			return
		}
		runCatchingCancellable {
			val repository = repositoryFactory.create(manga.source)
			val pages = repository.getPages(chapter)
			if (!withImages || !isImagePreloadAllowed(repository)) {
				return@runCatchingCancellable
			}
			val first = startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
			coroutineScope {
				pages.drop(first).take(MAX_IMAGES).forEach { page ->
					launch {
						val url = repository.getPageUrl(page)
						download(url) {
							val request = PageLoader.createPageRequest(url, page.source)
							imageProxyInterceptor.interceptPageRequest(request, okHttp).ensureSuccess().use { response ->
								response.requireBody().use {
									cache.set(url, it.source(), it.contentType()?.toMimeType())
								}
							}
						}
					}
				}
			}
		}.onFailure {
			// Nothing depends on this: the reader asks again, and handles a failure properly
			it.printStackTraceDebug()
		}
	}

	/** So that a page tapped into while it is on its way here is not downloaded a second time. */
	suspend fun awaitDownload(url: String) {
		val download = downloads[url] ?: return
		withTimeoutOrNull(MAX_WAIT_MS) { download.join() }
	}

	private suspend fun download(url: String, block: suspend () -> Unit) {
		if (cache.get(url) != null) {
			return
		}
		val job = currentCoroutineContext().job
		if (downloads.putIfAbsent(url, job) != null) {
			return
		}
		try {
			block()
		} finally {
			downloads.remove(url, job)
		}
	}

	private fun isImagePreloadAllowed(repository: MangaRepository): Boolean {
		if (!settings.isPagesPreloadEnabled || context.isPowerSaveMode()) {
			return false
		}
		// Sites that only tolerate a few requests at a time, as PageLoader treats them
		val source = repository.source.name
		if (source == "ONISAGA" || source.startsWith("ONISAGA_")) {
			return false
		}
		return (repository as? ParserMangaRepository)?.isSlowdownEnabled() != true
	}

	private companion object {

		/** Roughly what fills the screen when the reader opens. */
		const val MAX_IMAGES = 3

		/** A stuck download is no reason for the reader to sit there: it starts its own after this. */
		const val MAX_WAIT_MS = 10_000L
	}
}
