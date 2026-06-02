package org.koitharu.kotatsu.tracker.domain

import org.koitharu.kotatsu.tracker.domain.model.MangaTracking
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class GetTracksUseCase @Inject constructor(
	private val repository: TrackingRepository,
) {

	suspend operator fun invoke(limit: Int): List<MangaTracking> {
		repository.updateTracks()
		return repository.getTracks(
			offset = 0,
			limit = limit,
			minActivityTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(MAX_INACTIVE_DAYS),
		)
	}

	private companion object {

		const val MAX_INACTIVE_DAYS = 90L
	}
}
