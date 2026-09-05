package org.koitharu.kotatsu.alternatives.ui.covers

import androidx.lifecycle.SavedStateHandle
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.core.util.ext.toBitmapOrNull
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.launch
import dagger.hilt.android.qualifiers.ApplicationContext
import coil3.request.allowRgb565
import coil3.request.ImageRequest
import coil3.ImageLoader
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Collections
import org.koitharu.kotatsu.alternatives.domain.AlternativeSearchEvent
import org.koitharu.kotatsu.alternatives.domain.AlternativesSearchOptions
import org.koitharu.kotatsu.alternatives.domain.AlternativesUseCase
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

private const val HASH_PARALLELISM = 4

@HiltViewModel
class AlternativeCoversViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val alternativesUseCase: AlternativesUseCase,
	@ApplicationContext private val context: Context,
	private val coil: ImageLoader,
) : BaseViewModel() {

	val manga: Manga = savedStateHandle.require<ParcelableManga>(AppRouter.KEY_MANGA).manga

	/** Appended to as sources answer, so covers show up without waiting for the slowest source. */
	val covers = MutableStateFlow<List<AlternativeCoverModel>>(emptyList())

	/** Which cover the big preview is showing. */
	val selectedUrl = MutableStateFlow<String?>(null)

	/** completed to total sources, for the progress bar. */
	val progress = MutableStateFlow(0 to 0)
	val isRunning = MutableStateFlow(true)

	/** Candidates are processed in parallel, so this must tolerate concurrent access. */
	private val seenKeys = Collections.synchronizedSet(HashSet<String>())

	/** Perceptual hashes of the covers already accepted, guarded because candidates are hashed in parallel. */
	private val acceptedHashes = ArrayList<Long>()
	private val hashMutex = Mutex()
	private val hashSemaphore = Semaphore(HASH_PARALLELISM)
	private var searchJob: Job? = null

	init {
		// The current cover leads the carousel so a candidate can be compared against it, and its key is
		// seeded so no source can offer the same image back as an "alternative".
		manga.coverUrl?.let { current ->
			seenKeys += current.dedupKey()
			covers.value = listOf(
				AlternativeCoverModel(
					coverUrl = current,
					source = manga.source,
					manga = manga,
					isCurrent = true,
					isSelected = true,
				),
			)
			selectedUrl.value = current
		}
		searchJob = launchLoadingJob(Dispatchers.Default) {
			try {
				// Seed the current cover's hash first, so a source offering the same artwork back is
				// dropped rather than shown as an alternative to itself.
				manga.coverUrl?.let { rememberHash(it) }
				// Same rule as the Alternatives screen: the active preset when it has candidates,
				// otherwise every enabled source.
				val scope = alternativesUseCase.getSourceScopeOptions(manga.source).defaultScope
				val options = AlternativesSearchOptions(query = manga.title, sourceScope = scope)
				// loadDetails = false: the cover is already in the search result, and fetching each
				// candidate's details page would add a round trip per result for nothing.
				alternativesUseCase(manga, options, loadDetails = false).collect { event ->
					when (event) {
						is AlternativeSearchEvent.Progress -> {
							progress.value = event.completedSources to event.totalSources
						}

						is AlternativeSearchEvent.Result -> launch { onCandidate(event.manga) }
					}
				}
			} finally {
				isRunning.value = false
			}
		}
	}

	/** Nothing more is worth fetching once a cover is chosen, or once the screen is left. */
	fun cancelSearch() {
		searchJob?.cancel()
		searchJob = null
		isRunning.value = false
	}

	override fun onCleared() {
		cancelSearch()
		super.onCleared()
	}

	fun select(url: String) {
		if (selectedUrl.value == url) {
			return
		}
		selectedUrl.value = url
		covers.update { list -> list.map { it.copy(isSelected = it.coverUrl == url) } }
	}

	private suspend fun onCandidate(candidate: Manga) {
		val url = candidate.coverUrl
		if (url.isNullOrEmpty() || !seenKeys.add(url.dedupKey())) {
			return
		}
		// Cheap url check first, then the image itself: sources republish the same artwork under
		// different names often enough that the url tells you almost nothing.
		if (!rememberHash(url)) {
			return
		}
		// update, not `value = value + x`: several candidates finish hashing at once and a plain
		// read-modify-write silently loses covers.
		covers.update { current ->
			current + AlternativeCoverModel(
				coverUrl = url,
				source = candidate.source,
				manga = candidate,
				isCurrent = false,
				isSelected = false,
			)
		}
	}

	/**
	 * Hashes a cover and records it, returning `false` when it looks like one already accepted.
	 *
	 * The image has to be downloaded to be compared, but it would be downloaded to be shown anyway, so
	 * this warms the same cache the carousel reads from rather than adding a separate cost.
	 *
	 * A cover that cannot be decoded is kept: dropping it would hide artwork over a transient failure,
	 * and a duplicate is a smaller problem than a missing option.
	 */
	private suspend fun rememberHash(url: String): Boolean {
		val hash = hashSemaphore.withPermit { hashOf(url) } ?: return true
		return hashMutex.withLock {
			if (acceptedHashes.any { CoverHash.distance(it, hash) <= CoverHash.MAX_DISTANCE }) {
				false
			} else {
				acceptedHashes.add(hash)
				true
			}
		}
	}

	private suspend fun hashOf(url: String): Long? = runCatchingCancellable {
		val request = ImageRequest.Builder(context)
			.data(url)
			.size(CoverHash.SAMPLE_WIDTH, CoverHash.SAMPLE_HEIGHT)
			.allowRgb565(false)
			.mangaSourceExtra(manga.source)
			.build()
		coil.execute(request).toBitmapOrNull()?.let(CoverHash::of)
	}.getOrNull()

	/**
	 * Sources routinely mirror the same artwork under different urls, so comparing whole urls dedupes
	 * almost nothing. The filename is a decent proxy for "same image" without decoding anything.
	 */
	private fun String.dedupKey(): String = substringBefore('?')
		.substringAfterLast('/')
		.lowercase()
		.ifEmpty { this }
}
