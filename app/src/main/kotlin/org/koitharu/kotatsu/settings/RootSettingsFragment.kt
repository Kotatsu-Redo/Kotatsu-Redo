package org.koitharu.kotatsu.settings

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ColorScheme
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.settings.search.SettingsSearchMenuProvider
import org.koitharu.kotatsu.settings.search.SettingsSearchViewModel

@AndroidEntryPoint
class RootSettingsFragment : BasePreferenceFragment(0) {

	private val viewModel: RootSettingsViewModel by viewModels()
	private val activityViewModel: SettingsSearchViewModel by activityViewModels()

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_root)
		addPreferencesFromResource(R.xml.pref_root_debug)
		bindPreferenceSummary("reader", R.string.read_mode, R.string.scale_mode, R.string.switch_pages)
		bindPreferenceSummary("appearance", R.string.theme, R.string.list_mode, R.string.language)
		bindPreferenceSummary("tracker", R.string.frequency_of_check, R.string.track_sources)
		bindPreferenceSummary("downloads", R.string.manga_save_location, R.string.storage_usage, R.string.data_removal)
		bindPreferenceSummary("userdata", R.string.create_or_restore_backup, R.string.periodic_backups)
		bindPreferenceSummary("accounts", R.string.sync, R.string.tracking, R.string.community)
		bindPreferenceSummary("notifications", R.string.new_chapters, R.string.suggestions)
		bindPreferenceSummary("privacy", R.string.adult_content, R.string.app_security, R.string.your_activity)
		bindPreferenceSummary("network", R.string.mobile_data, R.string.proxy, R.string.dns_over_https)
		findPreference<Preference>("about")?.summary = getString(R.string.app_version, BuildConfig.VERSION_NAME)
		applyExpressiveIconBackgrounds()
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		findPreference<Preference>(AppSettings.KEY_REMOTE_SOURCES)?.let { pref ->
			val total = viewModel.totalSourcesCount
			viewModel.enabledSourcesCount.observe(viewLifecycleOwner) {
				pref.summary = if (it >= 0) {
					getString(R.string.enabled_d_of_d, it, total)
				} else {
					resources.getQuantityStringSafe(R.plurals.items, total, total)
				}
			}
		}
		addMenuProvider(SettingsSearchMenuProvider(activityViewModel))
	}

	override fun onResume() {
		super.onResume()
		findPreference<Preference>(AppSettings.KEY_SUGGESTIONS)?.setSummary(
			if (settings.isSuggestionsEnabled) R.string.enabled else R.string.disabled,
		)
	}

	override fun setTitle(title: CharSequence?) {
		if (!resources.getBoolean(R.bool.is_tablet)) {
			super.setTitle(title)
		}
	}

	private fun bindPreferenceSummary(key: String, @StringRes vararg items: Int) {
		findPreference<Preference>(key)?.summary = items.joinToString { getString(it) }
	}

	private fun applyExpressiveIconBackgrounds() {
		if (settings.colorScheme != ColorScheme.EXPRESSIVE) {
			return
		}
		val context = requireContext()
		val iconColors = arrayOf(
			"reader" to R.color.settings_icon_reader,
			"appearance" to R.color.settings_icon_appearance,
			AppSettings.KEY_REMOTE_SOURCES to R.color.settings_icon_sources,
			AppSettings.KEY_SUGGESTIONS to R.color.settings_icon_suggestions,
			"tracker" to R.color.settings_icon_tracker,
			"downloads" to R.color.settings_icon_downloads,
			"userdata" to R.color.settings_icon_backup,
			"accounts" to R.color.settings_icon_services,
			"notifications" to R.color.settings_icon_notifications,
			"privacy" to R.color.settings_icon_privacy,
			"network" to R.color.settings_icon_network,
			"about" to R.color.settings_icon_about,
		)
		val density = resources.displayMetrics.density
		val size = (40f * density).toInt()
		val iconInset = (8f * density).toInt()
		val foregroundColor = ContextCompat.getColor(context, android.R.color.white)

		iconColors.forEach { (key, backgroundColorRes) ->
			val preference = findPreference<Preference>(key) ?: return@forEach
			val icon = preference.icon ?: return@forEach
			val background = GradientDrawable().apply {
				shape = GradientDrawable.OVAL
				setColor(ContextCompat.getColor(context, backgroundColorRes))
				setSize(size, size)
			}
			val foreground = DrawableCompat.wrap(icon.mutate()).also {
				DrawableCompat.setTint(it, foregroundColor)
			}
			preference.icon = LayerDrawable(arrayOf(background, foreground)).apply {
				setLayerInset(1, iconInset, iconInset, iconInset, iconInset)
			}
		}
	}
}
