package org.koitharu.kotatsu.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.network.DoHProvider
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.TriStateOption
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.setDefaultValueCompat
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.parsers.util.names
import java.net.Proxy
import javax.inject.Inject

@AndroidEntryPoint
class NetworkSettingsFragment :
	BasePreferenceFragment(R.string.network),
	SharedPreferences.OnSharedPreferenceChangeListener {

	@Inject
	lateinit var downloadsScheduler: DownloadWorker.Scheduler

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_network)
		findPreference<ListPreference>(AppSettings.KEY_DOWNLOADS_METERED_NETWORK)?.run {
			entryValues = TriStateOption.entries.names()
			setDefaultValueCompat(TriStateOption.ASK.name)
		}
		findPreference<ListPreference>(AppSettings.KEY_DOH)?.run {
			entryValues = DoHProvider.entries.names()
			setDefaultValueCompat(DoHProvider.NONE.name)
		}
		bindProxySummary()
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
		// Whether they are on is set on their own screens, which an XML dependency cannot reach
		findPreference<Preference>(AppSettings.KEY_TRACKER_WIFI_ONLY)?.isEnabled = settings.isTrackerEnabled
		findPreference<Preference>(AppSettings.KEY_SUGGESTIONS_WIFI_ONLY)?.isEnabled = settings.isSuggestionsEnabled
	}

	override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_DOWNLOADS_METERED_NETWORK -> updateDownloadsConstraints()

			AppSettings.KEY_SSL_BYPASS -> {
				Snackbar.make(listView, R.string.settings_apply_restart_required, Snackbar.LENGTH_INDEFINITE).show()
			}

			AppSettings.KEY_PROXY_TYPE,
			AppSettings.KEY_PROXY_ADDRESS,
			AppSettings.KEY_PROXY_PORT -> bindProxySummary()
		}
	}

	private fun updateDownloadsConstraints() {
		val preference = findPreference<Preference>(AppSettings.KEY_DOWNLOADS_METERED_NETWORK)
		viewLifecycleScope.launch {
			try {
				preference?.isEnabled = false
				withContext(Dispatchers.Default) {
					val option = when (settings.allowDownloadOnMeteredNetwork) {
						TriStateOption.ENABLED -> true
						TriStateOption.ASK -> return@withContext
						TriStateOption.DISABLED -> false
					}
					downloadsScheduler.updateConstraints(option)
				}
			} catch (e: Exception) {
				e.printStackTraceDebug()
			} finally {
				preference?.isEnabled = true
			}
		}
	}

	private fun bindProxySummary() {
		findPreference<Preference>(AppSettings.KEY_PROXY)?.run {
			val type = settings.proxyType
			val address = settings.proxyAddress
			val port = settings.proxyPort
			summary = when {
				type == Proxy.Type.DIRECT -> context.getString(R.string.disabled)
				address.isNullOrEmpty() || port == 0 -> context.getString(R.string.invalid_proxy_configuration)
				else -> "$address:$port"
			}
		}
	}
}
