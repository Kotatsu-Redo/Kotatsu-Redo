package org.koitharu.kotatsu.tracker.domain

import org.koitharu.kotatsu.parsers.model.MangaState
import java.util.concurrent.TimeUnit

/**
 * Decides which tracked manga went quiet in a way that suggests its source is down rather than a slow release
 * schedule. Everything here is plain data so the rules can be tested without a database or a notification.
 *
 * The rules lean towards staying quiet: a notification saying a source may be down is only worth anything if it is
 * rare, and missing a title costs little because the user still sees it stop updating.
 */
object StaleTrackPolicy {

	/** Also the silence that makes a title stale, so a series that has gone this long before is not "regular". */
	const val SILENCE_DAYS = 28L

	/**
	 * Past this the tracker itself calls a title inactive, and it is more likely dropped than down. It also keeps the
	 * first notification after an update from listing every series that ended years ago without being marked finished.
	 */
	const val MAX_SILENCE_DAYS = 90L

	const val NOTIFICATION_INTERVAL_DAYS = 7L

	/** Three gaps: fewer can't tell a schedule from a few chapters that happened to land close together. */
	const val MIN_RELEASE_DAYS = 4

	/** Only recent releases say what the schedule is now; a series may have been slower years ago. */
	const val MAX_RELEASE_DAYS = 8

	/** Two missed chapters at this pace is what makes four weeks unusual, not just one late chapter. */
	const val MAX_MEDIAN_GAP_DAYS = 14L

	private val DAY_MILLIS = TimeUnit.DAYS.toMillis(1)
	private val SILENCE_MILLIS = TimeUnit.DAYS.toMillis(SILENCE_DAYS)
	private val MAX_SILENCE_MILLIS = TimeUnit.DAYS.toMillis(MAX_SILENCE_DAYS)
	private val NOTIFICATION_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(NOTIFICATION_INTERVAL_DAYS)

	class Track(
		val mangaId: Long,
		val state: MangaState?,
		/** Keeps its value when checks fail, so a dead source still has the date of its last known chapter. */
		val lastChapterDate: Long,
		/** When the tracker last found new chapters, for sources whose chapters carry a wrong or old date. */
		val lastLogTime: Long,
		/** Advances on failed checks too. */
		val lastCheckTime: Long,
		/** Upload dates from the stored chapter list, which is also untouched by failed checks. */
		val releaseDates: List<Long>,
	)

	/** The newest track whose last chapter can be stale at [now]; for narrowing the query. */
	fun maxLastChapterDate(now: Long): Long = now - SILENCE_MILLIS

	/** The oldest track whose last chapter can be stale at [now]; for narrowing the query. */
	fun minLastChapterDate(now: Long): Long = now - MAX_SILENCE_MILLIS

	/**
	 * Regular means several release days in a row, usually within two weeks of each other and never as far apart as
	 * the silence being reported. Days rather than chapters, since a whole archive uploaded at once has one date.
	 */
	fun isRegular(releaseDates: Collection<Long>): Boolean {
		val days = releaseDates
			.filter { it > 0L }
			.map { it / DAY_MILLIS }
			.distinct()
			.sortedDescending()
			.take(MAX_RELEASE_DAYS)
		if (days.size < MIN_RELEASE_DAYS) {
			return false
		}
		val gaps = days.zipWithNext { newer, older -> newer - older }.sorted()
		return gaps.last() < SILENCE_DAYS && gaps[gaps.size / 2] <= MAX_MEDIAN_GAP_DAYS
	}

	fun isStale(track: Track, now: Long): Boolean {
		// Finished, abandoned or paused says why it went quiet. Most sources don't report a state at all.
		if (track.state != null && track.state != MangaState.ONGOING) {
			return false
		}
		val lastUpdate = lastUpdate(track)
		val silence = now - lastUpdate
		return lastUpdate > 0L &&
			silence in SILENCE_MILLIS..MAX_SILENCE_MILLIS &&
			// A check has to have been made once it was already overdue. Otherwise weeks with the tracker off,
			// or without a connection, look exactly like weeks without chapters.
			track.lastCheckTime - lastUpdate >= SILENCE_MILLIS &&
			// With the last chapter date among them, a stored list that stopped long before it is not regular:
			// what happened in between is unknown.
			isRegular(track.releaseDates + track.lastChapterDate)
	}

	/** A clock set back counts as time passed, or it could silence these for as long as it was off by. */
	fun canNotify(lastNotifiedAt: Long, now: Long): Boolean {
		return now - lastNotifiedAt !in 0L until NOTIFICATION_INTERVAL_MILLIS
	}

	/** What to report at [now], longest silent first. Empty until a week has passed since the last notification. */
	fun select(
		tracks: Collection<Track>,
		notifiedIds: Set<Long>,
		lastNotifiedAt: Long,
		now: Long,
	): List<Track> {
		if (!canNotify(lastNotifiedAt, now)) {
			return emptyList()
		}
		return tracks
			.filter { it.mangaId !in notifiedIds && isStale(it, now) }
			.sortedBy { lastUpdate(it) }
	}

	/**
	 * A title is reported once per silence: it stays remembered while it is still silent, however long that lasts,
	 * and is forgotten once a new chapter ends the silence (or it is no longer tracked), so the next one counts again.
	 *
	 * Going by the silence rather than by remembering a date, because some sources give relative dates ("3 weeks ago")
	 * that come out differently on every check and would look like a new chapter each time.
	 */
	fun keepNotified(notifiedIds: Set<Long>, silentIds: Set<Long>): Set<Long> = notifiedIds intersect silentIds

	private fun lastUpdate(track: Track): Long = maxOf(
		track.lastChapterDate,
		track.lastLogTime,
		track.releaseDates.maxOrNull() ?: 0L,
	)
}
