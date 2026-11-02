package org.koitharu.kotatsu.settings

import androidx.preference.Preference
import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.ui.ScrobblerAuthHelper
import javax.inject.Inject

/** Everything that signs in to somewhere else. */
@AndroidEntryPoint
class AccountsSettingsFragment : BasePreferenceFragment(R.string.accounts_and_sync) {

	@Inject
	lateinit var scrobblerAuthHelper: ScrobblerAuthHelper

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_accounts)
	}

	override fun onResume() {
		super.onResume()
		bindScrobblerSummary(AppSettings.KEY_SHIKIMORI, ScrobblerService.SHIKIMORI)
		bindScrobblerSummary(AppSettings.KEY_ANILIST, ScrobblerService.ANILIST)
		bindScrobblerSummary(AppSettings.KEY_MAL, ScrobblerService.MAL)
		bindScrobblerSummary(AppSettings.KEY_KITSU, ScrobblerService.KITSU)
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean {
		return when (preference.key) {
			AppSettings.KEY_SHIKIMORI -> {
				handleScrobblerClick(ScrobblerService.SHIKIMORI)
				true
			}

			AppSettings.KEY_MAL -> {
				handleScrobblerClick(ScrobblerService.MAL)
				true
			}

			AppSettings.KEY_ANILIST -> {
				handleScrobblerClick(ScrobblerService.ANILIST)
				true
			}

			AppSettings.KEY_KITSU -> {
				handleScrobblerClick(ScrobblerService.KITSU)
				true
			}

			else -> super.onPreferenceTreeClick(preference)
		}
	}

	private fun bindScrobblerSummary(
		key: String,
		scrobblerService: ScrobblerService,
	) {
		val pref = findPreference<Preference>(key) ?: return
		if (!scrobblerAuthHelper.isAuthorized(scrobblerService)) {
			pref.setSummary(R.string.disabled)
			return
		}
		val username = scrobblerAuthHelper.getCachedUser(scrobblerService)?.nickname
		if (username != null) {
			pref.summary = getString(R.string.logged_in_as, username)
		} else {
			pref.setSummary(R.string.loading_)
			viewLifecycleScope.launch {
				pref.summary = withContext(Dispatchers.Default) {
					runCatching {
						val user = scrobblerAuthHelper.getUser(scrobblerService)
						getString(R.string.logged_in_as, user.nickname)
					}.getOrElse {
						it.printStackTraceDebug()
						it.getDisplayMessage(resources)
					}
				}
			}
		}
	}

	private fun handleScrobblerClick(scrobblerService: ScrobblerService) {
		if (!scrobblerAuthHelper.isAuthorized(scrobblerService)) {
			confirmScrobblerAuth(scrobblerService)
		} else {
			router.openScrobblerSettings(scrobblerService)
		}
	}

	private fun confirmScrobblerAuth(scrobblerService: ScrobblerService) {
		buildAlertDialog(context ?: return, isCentered = true) {
			setIcon(scrobblerService.iconResId)
			setTitle(scrobblerService.titleResId)
			setMessage(context.getString(R.string.scrobbler_auth_intro, context.getString(scrobblerService.titleResId)))
			setPositiveButton(R.string.sign_in) { _, _ ->
				scrobblerAuthHelper.startAuth(context, scrobblerService).onFailure {
					Snackbar.make(listView, it.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
				}
			}
			setNegativeButton(android.R.string.cancel, null)
		}.show()
	}
}
