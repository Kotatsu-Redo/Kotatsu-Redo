package org.koitharu.kotatsu.settings

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.EInkFlashColor
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.setDefaultValueCompat
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.utils.SliderPreference

class EInkSettingsFragment : BasePreferenceFragment(R.string.e_ink) {

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_reader_eink)
		findPreference<SliderPreference>(AppSettings.KEY_EINK_FLASH_DURATION)?.summaryProvider =
			FlashDurationSummaryProvider
		findPreference<SliderPreference>(AppSettings.KEY_EINK_FLASH_EVERY)?.summaryProvider =
			FlashEverySummaryProvider
		findPreference<ListPreference>(AppSettings.KEY_EINK_FLASH_COLOR)?.run {
			entries = arrayOf(
				getString(R.string.color_white),
				getString(R.string.color_black),
			)
			entryValues = EInkFlashColor.entries.names()
			setDefaultValueCompat(EInkFlashColor.WHITE.name)
		}
	}

	private object FlashDurationSummaryProvider : Preference.SummaryProvider<SliderPreference> {

		override fun provideSummary(preference: SliderPreference): CharSequence {
			return preference.context.getString(R.string.milliseconds_pattern, preference.value)
		}
	}

	private object FlashEverySummaryProvider : Preference.SummaryProvider<SliderPreference> {

		override fun provideSummary(preference: SliderPreference): CharSequence {
			val value = preference.value
			return preference.context.resources.getQuantityStringSafe(R.plurals.pages, value, value)
		}
	}
}
