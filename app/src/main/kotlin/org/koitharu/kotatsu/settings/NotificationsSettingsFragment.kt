package org.koitharu.kotatsu.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.core.app.NotificationManagerCompat
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.settings.utils.RingtonePickContract
import org.koitharu.kotatsu.tracker.work.TrackerNotificationHelper
import javax.inject.Inject

/**
 * Where notifications are set up. Android 8 and later keep sound, vibration and light for each channel in their
 * own settings, so there this links to those. Only older versions go by the switches here.
 */
@AndroidEntryPoint
class NotificationsSettingsFragment :
	BasePreferenceFragment(R.string.notifications),
	SharedPreferences.OnSharedPreferenceChangeListener {

	@Inject
	lateinit var notificationHelper: TrackerNotificationHelper

	private val hasSystemChannels = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

	private val ringtonePickContract = registerForActivityResult(
		RingtonePickContract(R.string.notification_sound),
	) { uri ->
		settings.notificationSound = uri ?: return@registerForActivityResult
		findPreference<Preference>(AppSettings.KEY_NOTIFICATIONS_SOUND)?.run {
			summary = RingtoneManager.getRingtone(context, uri)?.getTitle(context)
				?: getString(R.string.silent)
		}
	}

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_notifications)
		listOf(
			AppSettings.KEY_TRACKER_NOTIFICATIONS,
			AppSettings.KEY_NOTIFICATIONS_SOUND,
			AppSettings.KEY_NOTIFICATIONS_VIBRATE,
			AppSettings.KEY_NOTIFICATIONS_LIGHT,
		).forEach { key ->
			findPreference<Preference>(key)?.isVisible = !hasSystemChannels
		}
		if (!hasSystemChannels) {
			findPreference<Preference>(AppSettings.KEY_NOTIFICATIONS_SOUND)?.run {
				summary = RingtoneManager.getRingtone(context, settings.notificationSound)?.getTitle(context)
					?: getString(R.string.silent)
			}
		}
		bindInfo()
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		settings.subscribe(this)
	}

	override fun onDestroyView() {
		settings.unsubscribe(this)
		super.onDestroyView()
	}

	override fun onResume() {
		super.onResume()
		bindSystemSettings()
		findPreference<Preference>(AppSettings.KEY_SUGGESTIONS_NOTIFICATIONS)?.isEnabled = settings.isSuggestionsEnabled
	}

	override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_TRACKER_NOTIFICATIONS -> bindInfo()
		}
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean {
		return when (preference.key) {
			AppSettings.KEY_NOTIFICATIONS_SETTINGS -> {
				openSystemSettings()
				true
			}

			AppSettings.KEY_NOTIFICATIONS_SOUND -> {
				ringtonePickContract.launch(settings.notificationSound)
				true
			}

			else -> super.onPreferenceTreeClick(preference)
		}
	}

	private fun bindSystemSettings() {
		val pref = findPreference<Preference>(AppSettings.KEY_NOTIFICATIONS_SETTINGS) ?: return
		// Older versions have nothing to link to, unless the whole app has been blocked there
		pref.isVisible = hasSystemChannels || !NotificationManagerCompat.from(pref.context).areNotificationsEnabled()
		pref.setSummary(
			if (notificationHelper.getAreNotificationsEnabled()) {
				R.string.show_notification_new_chapters_on
			} else {
				R.string.show_notification_new_chapters_off
			},
		)
	}

	private fun openSystemSettings() {
		val context = context ?: return
		val intent = if (hasSystemChannels) {
			Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
		} else {
			Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", context.packageName, null))
		}
		try {
			startActivity(intent)
		} catch (_: ActivityNotFoundException) {
			Snackbar.make(listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
		}
	}

	private fun bindInfo() {
		findPreference<Preference>(AppSettings.KEY_NOTIFICATIONS_INFO)?.isVisible =
			!hasSystemChannels && !settings.isTrackerNotificationsEnabled
	}
}
