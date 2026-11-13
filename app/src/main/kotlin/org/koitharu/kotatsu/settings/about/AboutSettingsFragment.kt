package org.koitharu.kotatsu.settings.about

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import androidx.annotation.StringRes
import androidx.fragment.app.viewModels
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.github.AppVersion
import org.koitharu.kotatsu.core.github.VersionId
import org.koitharu.kotatsu.core.github.isStable
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.sourcescore.ui.CommunityOnboardingDialog

@AndroidEntryPoint
class AboutSettingsFragment : BasePreferenceFragment(R.string.about) {

	private val viewModel by viewModels<AboutSettingsViewModel>()

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_about)
		findPreference<Preference>(AppSettings.KEY_APP_VERSION)?.run {
			title = getString(R.string.app_version, BuildConfig.VERSION_NAME)
		}
		findPreference<SwitchPreferenceCompat>(AppSettings.KEY_UPDATES_UNSTABLE)?.run {
			isEnabled = VersionId(BuildConfig.VERSION_NAME).isStable
			if (!isEnabled) isChecked = true
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		combine(viewModel.isUpdateSupported, viewModel.isLoading, ::Pair)
			.observe(viewLifecycleOwner) { (isUpdateSupported, isLoading) ->
				findPreference<Preference>(AppSettings.KEY_UPDATES_UNSTABLE)?.isVisible = isUpdateSupported
				findPreference<Preference>(AppSettings.KEY_APP_VERSION)?.isEnabled = isUpdateSupported && !isLoading
				findPreference<Preference>(KEY_PARSERS_VERSION)?.isEnabled = !isLoading
			}
		viewModel.onUpdateAvailable.observeEvent(viewLifecycleOwner, ::onUpdateAvailable)
		viewModel.parsers.observe(viewLifecycleOwner, ::onParsersChanged)
		viewModel.onParsersChecked.observeEvent(viewLifecycleOwner, ::onParsersChecked)
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean {
		return when (preference.key) {
			AppSettings.KEY_APP_VERSION -> {
				viewModel.checkForUpdates()
				true
			}

			AppSettings.KEY_LINK_WEBLATE -> {
				openLink(R.string.url_weblate, preference.title)
				true
			}

			AppSettings.KEY_LINK_GITHUB -> {
				openLink(R.string.url_github, preference.title)
				true
			}

			AppSettings.KEY_LINK_MANUAL -> {
				openLink(R.string.url_user_manual, preference.title)
				true
			}

			AppSettings.KEY_LINK_DISCORD -> {
				openLink(R.string.url_discord, preference.title)
				true
			}

			KEY_PARSERS_VERSION -> {
				if (viewModel.isParsersUpdateSupported) {
					viewModel.checkParsers()
				}
				true
			}

			KEY_SPONSOR -> {
				router.openExternalBrowser(CommunityOnboardingDialog.SPONSOR_URL, preference.title)
				true
			}

			else -> super.onPreferenceTreeClick(preference)
		}
	}

	private fun onUpdateAvailable(version: AppVersion?) {
		if (version == null) {
			Snackbar.make(listView, R.string.no_update_available, Snackbar.LENGTH_SHORT).show()
		} else {
			startActivity(Intent(requireContext(), AppUpdateActivity::class.java))
		}
	}

	private fun onParsersChanged(info: AboutSettingsViewModel.ParsersInfo?) {
		val preference = findPreference<Preference>(KEY_PARSERS_VERSION) ?: return
		if (info == null) {
			preference.summary = null
			return
		}
		val version = if (info.timestamp > 0L) {
			val date = DateUtils.formatDateTime(
				preference.context,
				info.timestamp,
				DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
			)
			"$date (${info.revision})"
		} else {
			info.revision
		}
		preference.summary = getString(
			if (info.isBuiltIn) R.string.sources_version_built_in else R.string.sources_version_downloaded,
			version,
		)
	}

	private fun onParsersChecked(result: Result<Boolean>) {
		val message = result.fold(
			onSuccess = { getString(if (it) R.string.sources_updated else R.string.sources_up_to_date) },
			onFailure = { it.getDisplayMessage(resources) },
		)
		Snackbar.make(listView, message, Snackbar.LENGTH_SHORT).show()
	}

	private fun openLink(
		@StringRes url: Int,
		title: CharSequence?
	): Boolean = if (router.openExternalBrowser(getString(url), title)) {
		true
	} else {
		Snackbar.make(listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
		false
	}

	private companion object {
		const val KEY_SPONSOR = "sponsor"
		const val KEY_PARSERS_VERSION = "parsers_version"
	}
}
