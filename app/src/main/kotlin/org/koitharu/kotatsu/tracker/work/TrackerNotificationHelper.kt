package org.koitharu.kotatsu.tracker.work

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE
import androidx.core.app.NotificationCompat.VISIBILITY_SECRET
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.request.ImageRequest
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.model.getLocalizedTitle
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.checkNotificationPermission
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.toBitmapOrNull
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import javax.inject.Inject

class TrackerNotificationHelper @Inject constructor(
	@LocalizedAppContext private val applicationContext: Context,
	private val settings: AppSettings,
	private val coil: ImageLoader,
) {

	fun getAreNotificationsEnabled(): Boolean {
		val manager = NotificationManagerCompat.from(applicationContext)
		if (!manager.areNotificationsEnabled()) {
			return false
		}
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = manager.getNotificationChannel(CHANNEL_ID)
			channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
		} else {
			// fallback
			settings.isTrackerNotificationsEnabled
		}
	}

	suspend fun createNotification(manga: Manga, newChapters: List<MangaChapter>): NotificationInfo? {
		if (newChapters.isEmpty() || !applicationContext.checkNotificationPermission(CHANNEL_ID)) {
			return null
		}
		if (manga.isNsfw() && (settings.isTrackerNsfwDisabled || settings.isNsfwContentDisabled)) {
			return null
		}
		val id = manga.url.hashCode()
		val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
		val summary = applicationContext.resources.getQuantityStringSafe(
			R.plurals.new_chapters,
			newChapters.size,
			newChapters.size,
		)
		with(builder) {
			setContentText(summary)
			setContentTitle(manga.title)
			setNumber(newChapters.size)
			setLargeIcon(
				coil.execute(
					ImageRequest.Builder(applicationContext)
						.data(manga.coverUrl)
						.mangaSourceExtra(manga.source)
						.build(),
				).toBitmapOrNull(),
			)
			setSmallIcon(R.drawable.ic_stat_book_plus)
			setGroup(GROUP_NEW_CHAPTERS)
			val style = NotificationCompat.InboxStyle(this)
			for (chapter in newChapters) {
				style.addLine(chapter.getLocalizedTitle(applicationContext.resources))
			}
			style.setSummaryText(manga.title)
			style.setBigContentTitle(summary)
			setStyle(style)
			val intent = AppRouter.detailsIntent(applicationContext, manga)
			setContentIntent(
				PendingIntentCompat.getActivity(
					applicationContext,
					id,
					intent,
					PendingIntent.FLAG_UPDATE_CURRENT,
					false,
				),
			)
			setVisibility(if (manga.isNsfw()) VISIBILITY_SECRET else VISIBILITY_PRIVATE)
			setShortcutId(manga.id.toString())
			applyCommonSettings(this)
		}
		return NotificationInfo(id, TAG, builder.build(), manga, newChapters.size)
	}

	fun createGroupNotification(
		notifications: List<NotificationInfo>
	): Notification? {
		if (notifications.size <= 1) {
			return null
		}
		val newChaptersCount = notifications.sumOf { it.newChapters }
		val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
		with(builder) {
			val title = applicationContext.resources.getQuantityStringSafe(
				R.plurals.new_chapters,
				newChaptersCount,
				newChaptersCount,
			)
			setContentTitle(title)
			setContentText(notifications.joinToString { it.manga.title })
			setSmallIcon(R.drawable.ic_stat_book_plus)
			val style = NotificationCompat.InboxStyle(this)
			for (item in notifications) {
				style.addLine(
					applicationContext.getString(R.string.new_chapters_pattern, item.manga.title, item.newChapters),
				)
			}
			style.setBigContentTitle(title)
			setStyle(style)
			setNumber(newChaptersCount)
			setGroup(GROUP_NEW_CHAPTERS)
			setGroupSummary(true)
			setVisibility(
				if (notifications.any { it.manga.isNsfw() }) {
					VISIBILITY_SECRET
				} else {
					VISIBILITY_PRIVATE
				},
			)
			val intent = AppRouter.mangaUpdatesIntent(applicationContext)
			setContentIntent(
				PendingIntentCompat.getActivity(
					applicationContext,
					GROUP_NOTIFICATION_ID,
					intent,
					PendingIntent.FLAG_UPDATE_CURRENT,
					false,
				),
			)
			applyCommonSettings(this)
		}
		return builder.build()
	}

	/**
	 * Has its own channel so it can be turned off without losing new chapter notifications. Android 7 and older have
	 * no channels, so there it goes by the one switch for tracker notifications.
	 */
	fun areStaleTrackNotificationsEnabled(): Boolean {
		return applicationContext.checkNotificationPermission(STALE_CHANNEL_ID) &&
			(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || settings.isTrackerNotificationsEnabled)
	}

	fun createStaleTracksNotification(manga: List<Manga>): Notification {
		val builder = NotificationCompat.Builder(applicationContext, STALE_CHANNEL_ID)
		with(builder) {
			val title = applicationContext.resources.getQuantityStringSafe(
				R.plurals.tracker_stale_title,
				manga.size,
				manga.size,
			)
			setContentTitle(title)
			setContentText(
				applicationContext.getString(
					R.string.tracker_stale_text,
					manga.joinToString(limit = STALE_TEXT_TITLES, truncated = "…") { it.title },
				),
			)
			setNumber(manga.size)
			setSmallIcon(R.drawable.ic_stat_paused)
			val style = NotificationCompat.InboxStyle(this)
			for (item in manga.take(STALE_INBOX_LINES)) {
				style.addLine(
					applicationContext.getString(
						R.string.tracker_stale_line,
						item.title,
						item.source.getTitle(applicationContext),
					),
				)
			}
			if (manga.size > STALE_INBOX_LINES) {
				style.addLine("…")
			}
			style.setBigContentTitle(title)
			style.setSummaryText(applicationContext.getString(R.string.tracker_stale_summary))
			setStyle(style)
			setVisibility(if (manga.any { it.isNsfw() }) VISIBILITY_SECRET else VISIBILITY_PRIVATE)
			// No screen lists several of them: the updates screen shows titles with new chapters, the opposite of these
			val intent = manga.singleOrNull()?.let { AppRouter.detailsIntent(applicationContext, it) }
				?: AppRouter.homeIntent(applicationContext)
			setContentIntent(
				PendingIntentCompat.getActivity(
					applicationContext,
					STALE_NOTIFICATION_ID,
					intent,
					PendingIntent.FLAG_UPDATE_CURRENT,
					false,
				),
			)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				addAction(
					R.drawable.ic_settings,
					applicationContext.getString(R.string.notifications_settings),
					PendingIntentCompat.getActivity(
						applicationContext,
						STALE_SETTINGS_ACTION_CODE,
						Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
							.putExtra(Settings.EXTRA_APP_PACKAGE, applicationContext.packageName)
							.putExtra(Settings.EXTRA_CHANNEL_ID, STALE_CHANNEL_ID),
						0,
						false,
					),
				)
			}
			applyCommonSettings(this)
		}
		return builder.build()
	}

	fun updateChannels() {
		val manager = NotificationManagerCompat.from(applicationContext)
		manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
		manager.deleteNotificationChannel(LEGACY_CHANNEL_ID_HISTORY)
		manager.deleteNotificationChannelGroup(LEGACY_CHANNELS_GROUP_ID)

		val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
			.setName(applicationContext.getString(R.string.new_chapters))
			.setDescription(applicationContext.getString(R.string.show_notification_new_chapters_on))
			.setShowBadge(true)
			.setLightColor(ContextCompat.getColor(applicationContext, R.color.blue_primary))
			.build()
		manager.createNotificationChannel(channel)

		val staleChannel = NotificationChannelCompat.Builder(STALE_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
			.setName(applicationContext.getString(R.string.tracker_stale_channel))
			.setDescription(applicationContext.getString(R.string.tracker_stale_channel_description))
			.setShowBadge(false)
			.setLightColor(ContextCompat.getColor(applicationContext, R.color.blue_primary))
			.build()
		manager.createNotificationChannel(staleChannel)
	}

	private fun applyCommonSettings(builder: NotificationCompat.Builder) {
		builder.setAutoCancel(true)
		builder.setCategory(NotificationCompat.CATEGORY_SOCIAL)
		builder.priority = NotificationCompat.PRIORITY_DEFAULT
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			builder.setSound(settings.notificationSound)
			var defaults = if (settings.notificationLight) {
				builder.setLights(ContextCompat.getColor(applicationContext, R.color.blue_primary), 1000, 5000)
				NotificationCompat.DEFAULT_LIGHTS
			} else 0
			if (settings.notificationVibrate) {
				builder.setVibrate(longArrayOf(500, 500, 500, 500))
				defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
			}
			builder.setDefaults(defaults)
		}
	}

	class NotificationInfo(
		val id: Int,
		val tag: String,
		val notification: Notification,
		val manga: Manga,
		val newChapters: Int,
	)

	companion object {

		const val CHANNEL_ID = "tracker_chapters"
		const val GROUP_NOTIFICATION_ID = 0
		const val GROUP_NEW_CHAPTERS = "org.koitharu.kotatsu.NEW_CHAPTERS"
		const val TAG = "tracker"
		const val STALE_CHANNEL_ID = "tracker_stale_sources"
		const val STALE_NOTIFICATION_ID = 1

		// Its own tag: per-manga notifications use ids from url hashes, which could land on any id under theirs
		const val STALE_TAG = "tracker_stale"

		private const val STALE_TEXT_TITLES = 3
		private const val STALE_INBOX_LINES = 5
		private const val STALE_SETTINGS_ACTION_CODE = 6

		private const val LEGACY_CHANNELS_GROUP_ID = "trackers"
		private const val LEGACY_CHANNEL_ID_HISTORY = "track_history"
		private const val LEGACY_CHANNEL_ID = "tracking"
	}
}
