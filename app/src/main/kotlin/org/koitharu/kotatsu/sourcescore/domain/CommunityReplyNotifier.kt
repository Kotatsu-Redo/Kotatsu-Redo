package org.koitharu.kotatsu.sourcescore.domain

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sourcescore.data.CommunityApi
import org.koitharu.kotatsu.sourcescore.data.CommunitySettings
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CommunityReplies"

/**
 * Tells the user when somebody replied to them.
 *
 * Polled when the app opens rather than pushed: push would mean a device token, a Google dependency
 * and a third party learning when this user opens a manga app, all to save a request that costs
 * nothing (PLAN.md §5).
 *
 * Delivered **once**. The cursor lives here on the device and the server keeps no record of what it
 * has sent, because a table of who-was-told-what is exactly the kind of per-user history this design
 * refuses to hold - so if this cursor is not advanced, the same reply is announced forever.
 */
@Singleton
class CommunityReplyNotifier @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: CommunitySettings,
	private val api: CommunityApi,
) {

	fun pollOnOpen(scope: CoroutineScope) {
		if (!settings.isEnabled) return
		scope.launch { poll() }
	}

	private suspend fun poll() {
		val response = runCatchingCancellable { api.fetchNotifications(settings.replyCursor) }
			.onFailure { Log.d(TAG, "Reply poll failed", it) }
			.getOrNull() ?: return

		// Advanced even when there is nothing to show, so a quiet week does not re-ask for a month of
		// history on every launch.
		response.cursor.takeIf { it.isNotEmpty() }?.let { settings.replyCursor = it }
		if (response.replies.isEmpty()) return

		notify(response.replies.size, response.replies.first().author, response.replies.first().preview)
	}

	private fun notify(count: Int, author: String, preview: String) {
		// The user may simply have said no, which is a perfectly good answer: replies are a courtesy,
		// not something worth prompting for on app open.
		if (
			ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
			PackageManager.PERMISSION_GRANTED
		) {
			Log.d(TAG, "Notifications are not permitted; $count replies not shown")
			return
		}

		val manager = NotificationManagerCompat.from(context)
		manager.createNotificationChannel(
			NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
				.setName(context.getString(R.string.community_replies))
				.setShowBadge(true)
				.setVibrationEnabled(false)
				.setLightsEnabled(false)
				.build(),
		)

		// One notification for the lot, not one each: the point is "somebody replied", and the thread
		// is where the conversation actually is.
		val title = if (count == 1) {
			context.getString(R.string.community_reply_from, author)
		} else {
			context.resources.getQuantityString(R.plurals.community_replies_count, count, count)
		}

		val notification = NotificationCompat.Builder(context, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_stat_comment)
			.setContentTitle(title)
			.setContentText(preview)
			.setStyle(NotificationCompat.BigTextStyle().bigText(preview))
			.setAutoCancel(true)
			.setSilent(true)
			.build()

		runCatching { manager.notify(TAG, NOTIFICATION_ID, notification) }
			.onFailure { Log.w(TAG, "Could not post the reply notification", it) }
	}

	private companion object {
		const val CHANNEL_ID = "community_replies"
		const val NOTIFICATION_ID = 1
	}
}
