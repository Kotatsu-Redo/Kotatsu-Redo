package org.koitharu.kotatsu.sourcescore.ui

import android.util.Log
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sourcescore.data.CommunityApi
import org.koitharu.kotatsu.sourcescore.data.CommunityApiException
import org.koitharu.kotatsu.sourcescore.data.CommunitySettings
import org.koitharu.kotatsu.sourcescore.data.ApiErrorDto
import org.koitharu.kotatsu.sourcescore.domain.CommunityWorkResolver
import org.koitharu.kotatsu.sourcescore.data.RatingResponse
import javax.inject.Inject

private const val TAG = "CommunityRating"

/**
 * Drives the rating row on the details screen.
 *
 * A delegate rather than state on `DetailsViewModel`: that file is upstream Kotatsu's and this fork
 * has to keep rebasing onto it, so the community feature stays in its own package and touches the
 * screen at exactly one point.
 */
class CommunityRatingDelegate @Inject constructor(
	private val settings: CommunitySettings,
	private val api: CommunityApi,
	private val resolver: CommunityWorkResolver,
) {

	val isAvailable: Boolean get() = settings.isEnabled

	/**
	 * Resolves the manga to a work and loads its rating.
	 *
	 * The work id is cached permanently, so this costs a network round trip once per manga per device
	 * and an indexed local read every time after (PLAN.md §5).
	 */
	suspend fun load(manga: Manga): RatingResponse? {
		if (!settings.isEnabled) return null
		val workId = resolver.workIdFor(manga) ?: return null
		return runCatchingCancellable { retryAfterMove(workId, api::fetchRating) }
			.onFailure { Log.w(TAG, "Rating fetch failed for work $workId", it) }
			.getOrNull()
	}

	/**
	 * The community's id for this manga, for the comment sheet.
	 *
	 * Exposed here rather than injecting the resolver separately, so the details screen keeps exactly
	 * one community collaborator and the fork has one line to re-apply after an upstream rebase.
	 */
	suspend fun workId(manga: Manga): String? = resolver.workIdFor(manga)

	/**
	 * The key a chapter's comment thread is scoped to - the chapter *number*, not the local id.
	 *
	 * Kotatsu's chapter id is a hash of the source's own url: different on every source, and
	 * different again on another device. Scoping a thread to it would give every reader their own
	 * private chapter thread and call it a community.
	 */
	fun chapterKey(chapterNumber: Float): Long? = resolver.chapterKey(chapterNumber)

	/** @param stars 0.5..5.0, sent as half-star steps. Zero clears the rating. */
	suspend fun rate(manga: Manga, stars: Float): RatingResponse? {
		if (!settings.isEnabled) return null
		val workId = resolver.workIdFor(manga) ?: return null
		val value = (stars * 2).toInt().coerceIn(0, 10)
		return runCatchingCancellable {
			retryAfterMove(workId) { id ->
				if (value == 0) api.clearRating(id) else api.setRating(id, value)
			}
		}.onFailure { Log.w(TAG, "Rating update failed for work $workId", it) }.getOrNull()
	}

	private suspend fun <T> retryAfterMove(workId: String, request: suspend (String) -> T): T =
		try {
			request(workId)
		} catch (error: CommunityApiException) {
			val movedTo = error.error.movedTo
			if (error.error.error != ApiErrorDto.WORK_MOVED || movedTo.isNullOrBlank()) throw error
			resolver.repoint(workId, movedTo)
			request(movedTo)
		}

}

/**
 * Binds a row of star buttons to the rating.
 *
 * Only user taps are reported: a value arriving from the server sets the icons directly, because a
 * listener that fired on every programmatic change would re-submit the rating every time the screen
 * opened.
 *
 * Tapping the star you already have clears the rating, which is the only way to undo one - there is
 * no zeroth star to press.
 */
class StarRowBinder(
	private val stars: List<MaterialButton>,
	private val scope: CoroutineScope,
	private val onRate: suspend (Float) -> Unit,
) {

	private var current = 0

	init {
		stars.forEachIndexed { index, button ->
			button.setOnClickListener {
				val value = if (current == index + 1) 0 else index + 1
				render(value)
				scope.launch { onRate(value.toFloat()) }
			}
		}
	}

	/** @param value whole stars, 0 for unrated. Fractions round to the nearest star. */
	fun setRating(value: Float) = render(Math.round(value).coerceIn(0, stars.size))

	/** Stars are enabled only once there is a work to rate. */
	fun setEnabled(enabled: Boolean) {
		stars.forEach { it.isEnabled = enabled }
	}

	private fun render(value: Int) {
		current = value
		stars.forEachIndexed { index, button ->
			button.setIconResource(
				if (index < value) R.drawable.ic_star_rate else R.drawable.ic_star_rate_outline,
			)
		}
	}
}
