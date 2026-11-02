package org.koitharu.kotatsu.settings

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ScreenshotsPolicy
import org.koitharu.kotatsu.core.prefs.TriStateOption
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.setDefaultValueCompat
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.protect.ProtectSetupActivity
import org.koitharu.kotatsu.settings.utils.SplitSwitchPreference
import org.koitharu.kotatsu.sourcescore.data.CommunitySettings
import org.koitharu.kotatsu.suggestions.ui.SuggestionsWorker
import javax.inject.Inject

/**
 * Everything about what shows up where and who can see it. Several of these only mean anything while something
 * on another screen is on, which an XML dependency cannot say across screens, so that is worked out here.
 */
@AndroidEntryPoint
class PrivacySettingsFragment :
	BasePreferenceFragment(R.string.privacy_and_content),
	SharedPreferences.OnSharedPreferenceChangeListener {

	@Inject
	lateinit var appShortcutManager: AppShortcutManager

	@Inject
	lateinit var communitySettings: CommunitySettings

	@Inject
	lateinit var suggestionsScheduler: SuggestionsWorker.Scheduler

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_privacy)
		findPreference<ListPreference>(AppSettings.KEY_INCOGNITO_NSFW)?.run {
			entryValues = TriStateOption.entries.names()
			setDefaultValueCompat(TriStateOption.ASK.name)
		}
		findPreference<ListPreference>(AppSettings.KEY_SCREENSHOTS_POLICY)?.run {
			entryValues = ScreenshotsPolicy.entries.names()
			setDefaultValueCompat(ScreenshotsPolicy.ALLOW.name)
		}
		findPreference<Preference>(AppSettings.KEY_SHORTCUTS_NO_NSFW)?.isVisible =
			appShortcutManager.isDynamicShortcutsAvailable()
		bindAppProtection()
		findPreference<SplitSwitchPreference>(AppSettings.KEY_STATS_ENABLED)?.onContainerClickListener =
			Preference.OnPreferenceClickListener {
				router.openStatistic()
				true
			}
		findPreference<TwoStatePreference>(CommunitySettings.KEY_TELEMETRY)?.setOnPreferenceChangeListener { _, value ->
			communitySettings.isTelemetryEnabled = value == true
			true
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		bindStatsSummary()
		settings.subscribe(this)
	}

	override fun onDestroyView() {
		settings.unsubscribe(this)
		super.onDestroyView()
	}

	override fun onResume() {
		super.onResume()
		bindAvailability()
	}

	override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_APP_PASSWORD -> bindAppProtection()
			AppSettings.KEY_STATS_ENABLED -> bindStatsSummary()
			AppSettings.KEY_SUGGESTIONS_EXCLUDE_NSFW -> if (settings.isSuggestionsEnabled) {
				viewLifecycleScope.launch(Dispatchers.Default) {
					suggestionsScheduler.startNow()
				}
			}
		}
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean {
		return when (preference.key) {
			AppSettings.KEY_PROTECT_APP -> {
				val pref = (preference as? TwoStatePreference ?: return false)
				if (pref.isChecked) {
					pref.isChecked = false
					startActivity(Intent(preference.context, ProtectSetupActivity::class.java))
				} else {
					settings.appPassword = null
				}
				true
			}

			else -> super.onPreferenceTreeClick(preference)
		}
	}

	/** Whatever the other screens have been set to, including while away on them. */
	private fun bindAvailability() {
		findPreference<Preference>(AppSettings.KEY_SHORTCUTS_NO_NSFW)?.isEnabled = isOn(AppSettings.KEY_SHORTCUTS, true)
		findPreference<Preference>(AppSettings.KEY_TRACKER_NO_NSFW)?.isEnabled = settings.isTrackerEnabled
		findPreference<Preference>(AppSettings.KEY_SUGGESTIONS_EXCLUDE_NSFW)?.isEnabled = settings.isSuggestionsEnabled
		findPreference<Preference>(AppSettings.KEY_DISCORD_RPC_SKIP_NSFW)?.isEnabled = isOn(AppSettings.KEY_DISCORD_RPC, false)
		findPreference<TwoStatePreference>(CommunitySettings.KEY_TELEMETRY)?.run {
			isChecked = communitySettings.isTelemetryEnabled
			isEnabled = communitySettings.isEnabled
		}
	}

	private fun isOn(key: String, default: Boolean): Boolean =
		preferenceManager.sharedPreferences?.getBoolean(key, default) ?: default

	private fun bindAppProtection() {
		findPreference<TwoStatePreference>(AppSettings.KEY_PROTECT_APP)?.isChecked = !settings.appPassword.isNullOrEmpty()
	}

	private fun bindStatsSummary() {
		findPreference<Preference>(AppSettings.KEY_STATS_ENABLED)?.setSummary(
			if (settings.isStatsEnabled) R.string.enabled else R.string.disabled,
		)
	}
}
