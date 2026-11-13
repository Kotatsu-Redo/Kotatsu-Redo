package org.koitharu.kotatsu.sourcescore.domain

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.discover.data.DiscoverDismissals
import org.koitharu.kotatsu.discover.data.UploadState
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.ParserMangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sourcescore.data.CommunityApi
import org.koitharu.kotatsu.sourcescore.data.CommunityDatabase
import org.koitharu.kotatsu.sourcescore.data.CommunitySettings
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DismissalReporter"

/**
 * Tells the community server what a reader passed on, for people who turned community features on and nobody else.
 * The rules are in [DismissalUploader]; this is the real client under it, and the background run.
 *
 * Best effort and quiet: one capped run after a pass or an undo, and whatever it could not send waits for the
 * daily community sync ([retryPending]). It never resolves the manga: that call tells the server the reader
 * opened it, which is the opposite of what a pass says.
 */
@Singleton
class DismissalReporter @Inject constructor(
	private val settings: CommunitySettings,
	api: CommunityApi,
	database: CommunityDatabase,
	dismissals: DiscoverDismissals,
) {

	private val uploader = DismissalUploader(
		store = dismissals.store,
		gate = { settings.isEnabled },
		sender = { api.reportPass(it) },
		works = { source, key -> database.getWorkRefDao().find(source, key)?.workId },
	)

	/** One run at a time, so that a burst of passes is never sent twice over. */
	private val runLock = Mutex()

	fun stateFor(manga: Manga): UploadState =
		uploader.stateFor(manga.source is ParserMangaSource, manga.source.name, manga.url)

	/**
	 * Works out the work ids of these and then sends what is waiting, once, without anyone waiting for it.
	 *
	 * @param keys what was just passed on
	 */
	fun runAsync(keys: Collection<String> = emptyList()) {
		if (!settings.isEnabled) return
		processLifecycleScope.launch(Dispatchers.Default) {
			runCatchingCancellable {
				runLock.withLock {
					uploader.attachWorkIds(keys)
					uploader.run()
				}
			}.onFailure { Log.w(TAG, "Reporting passes failed", it) }
		}
	}

	/** What could not be sent earlier, once each: at most 50, and no more after the first sign the server is not there. */
	suspend fun retryPending() = runLock.withLock { uploader.run() }
}
