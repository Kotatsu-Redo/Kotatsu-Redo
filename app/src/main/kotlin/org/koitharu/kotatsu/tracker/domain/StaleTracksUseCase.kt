package org.koitharu.kotatsu.tracker.domain

import android.content.Context
import androidx.core.content.edit
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

/**
 * Finds what the "source may be down" notification should list and remembers what it already did.
 *
 * Only reads the tracks, so it can't affect which manga the tracker checks next. The memory is kept in its own file,
 * not in the app settings: those go into backups, and a restored "already told" would hide it on the new device.
 */
@Reusable
class StaleTracksUseCase @Inject constructor(
	@ApplicationContext private val context: Context,
	private val repository: TrackingRepository,
	private val settings: AppSettings,
) {

	private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

	/** Manga to report now, usually none. Call [markNotified] once they were actually shown. */
	suspend fun findUnreported(now: Long): List<Manga> {
		val lastNotifiedAt = prefs.getLong(KEY_LAST_NOTIFIED_AT, 0L)
		if (!StaleTrackPolicy.canNotify(lastNotifiedAt, now)) {
			return emptyList()
		}
		val previous = getNotifiedIds()
		val notified = StaleTrackPolicy.keepNotified(previous, repository.getSilentTrackIds(now))
		if (notified.size != previous.size) {
			setNotifiedIds(notified)
		}
		val candidates = repository.getStaleTrackCandidates(now, excludeIds = notified)
			// Local files have no site to be down, and the switches that hide NSFW titles from update notifications
			// have to hide them from this one too.
			.filterNot { (manga, _) -> manga.isLocal || (manga.isNsfw() && isNsfwHidden()) }
		val manga = candidates.associate { (manga, track) -> track.mangaId to manga }
		return StaleTrackPolicy.select(candidates.map { it.second }, notified, lastNotifiedAt, now)
			.mapNotNull { manga[it.mangaId] }
	}

	fun markNotified(manga: Collection<Manga>, now: Long) {
		val ids = getNotifiedIds() + manga.map { it.id }
		prefs.edit {
			putLong(KEY_LAST_NOTIFIED_AT, now)
			putStringSet(KEY_NOTIFIED_IDS, ids.mapTo(HashSet()) { it.toString() })
		}
	}

	private fun isNsfwHidden() = settings.isTrackerNsfwDisabled || settings.isNsfwContentDisabled

	private fun getNotifiedIds(): Set<Long> {
		return prefs.getStringSet(KEY_NOTIFIED_IDS, null).orEmpty().mapNotNullTo(HashSet()) { it.toLongOrNull() }
	}

	private fun setNotifiedIds(ids: Set<Long>) {
		prefs.edit { putStringSet(KEY_NOTIFIED_IDS, ids.mapTo(HashSet()) { it.toString() }) }
	}

	private companion object {

		const val PREFS_NAME = "tracker_stale_sources"
		const val KEY_LAST_NOTIFIED_AT = "last_notified_at"
		const val KEY_NOTIFIED_IDS = "notified_ids"
	}
}
