package org.koitharu.kotatsu.sourcescore.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import android.widget.FrameLayout
import android.view.View
import androidx.preference.EditTextPreference
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import java.io.File
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.snackbar.Snackbar
import androidx.preference.SwitchPreferenceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sourcescore.data.CommunityApi
import org.koitharu.kotatsu.sourcescore.data.CommunityDatabase
import org.koitharu.kotatsu.sourcescore.data.CommunitySettings
import javax.inject.Inject

/**
 * Community settings.
 *
 * Preferences are backed by [CommunitySettings]'s own file rather than the app-wide one, so the
 * PreferenceFragment is pointed at that file instead of using the default SharedPreferences.
 */
@AndroidEntryPoint
class CommunitySettingsFragment : BasePreferenceFragment(R.string.community) {

	@Inject
	lateinit var communitySettings: CommunitySettings

	@Inject
	lateinit var api: CommunityApi

	@Inject
	lateinit var database: CommunityDatabase

	@Inject
	lateinit var syncScheduler: SourceScoreSyncWorker.Scheduler

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		preferenceManager.sharedPreferencesName = CommunitySettings.FILE_NAME
		addPreferencesFromResource(R.xml.pref_community)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		findPreference<SwitchPreferenceCompat>("enabled")?.setOnPreferenceChangeListener { _, value ->
			// The daily job always runs for score downloads and reads the toggles itself, so only the
			// visible options need updating.
			view.post { updateVisibility() }
			true
		}

		findPreference<Preference>("server")?.let { preference ->
			preference.summary = communitySettings.serverUrl
			preference.setOnPreferenceClickListener {
				showServerDialog(preference)
				true
			}
			checkConnection(preference)
		}

		findPreference<EditTextPreference>("nickname")?.let { preference ->
			preference.text = communitySettings.nickname
			preference.summary = communitySettings.nickname ?: getString(R.string.community_nickname_summary)
			preference.setOnPreferenceChangeListener { _, value ->
				// Returning false leaves the stored value alone. Clearing the field would put the
				// account back to anon#xxxx, which onboarding no longer allows anyone to choose.
				val candidate = (value as? String)?.trim().orEmpty()
				if (candidate.isEmpty()) {
					false
				} else {
					applyNickname(candidate)
					true
				}
			}
		}

		findPreference<Preference>("recovery")?.setOnPreferenceClickListener {
			showRecoveryDialog()
			true
		}

		findPreference<Preference>("export")?.setOnPreferenceClickListener {
			exportMyData()
			true
		}
		findPreference<Preference>("delete")?.setOnPreferenceClickListener {
			confirmDeleteEverything()
			true
		}

		findPreference<Preference>("intro")?.setOnPreferenceClickListener {
			CommunityOnboardingDialog.show(parentFragmentManager)
			true
		}

		findPreference<Preference>("sponsor")?.setOnPreferenceClickListener {
			router.openExternalBrowser(
				CommunityOnboardingDialog.SPONSOR_URL,
				getString(R.string.community_sponsor),
			)
			true
		}

		updateVisibility()
	}

	/** The account section is meaningless until the feature is on, so it is hidden rather than greyed. */
	private fun updateVisibility() {
		findPreference<PreferenceCategory>("account")?.isVisible = communitySettings.isEnabled
	}

	private fun showServerDialog(preference: Preference) {
		val input = android.widget.EditText(requireContext()).apply {
			setText(communitySettings.serverUrl)
			setSingleLine()
		}
		buildAlertDialog(requireContext(), isCentered = false) {
			setTitle(R.string.community_server)
			setView(input)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(android.R.string.ok) { _, _ ->
				val value = input.text?.toString()?.trim().orEmpty()
				if (value.isNotEmpty()) {
					communitySettings.serverUrl = value
					preference.summary = communitySettings.serverUrl
					// A different server is a different identity space, so registration starts over.
					communitySettings.isRegistered = false
				}
			}
		}.show()
	}

	/**
	 * Reports whether the server is actually reachable, in the summary line under its address.
	 *
	 * Without this the only symptom of a wrong address is that nothing appears anywhere, which is
	 * indistinguishable from the feature being broken.
	 */
	private fun checkConnection(preference: Preference) {
		viewLifecycleScope.launch {
			preference.summary = getString(R.string.community_server_checking)
			val reachable = withContext(Dispatchers.Default) { api.ping() }
			preference.summary = buildString {
				appendLine(communitySettings.serverUrl)
				append(
					getString(
						if (reachable) R.string.community_server_ok else R.string.community_server_unreachable,
					),
				)
			}
		}
	}

	private fun applyNickname(value: String?) {
		val nickname = value?.trim().orEmpty()
		communitySettings.nickname = nickname.takeIf { it.isNotEmpty() }
		findPreference<Preference>("nickname")?.summary =
			communitySettings.nickname ?: getString(R.string.community_nickname_summary)
		if (nickname.isEmpty()) return
		viewLifecycleScope.launch {
			runCatchingCancellable {
				withContext(Dispatchers.Default) { api.setNickname(nickname) }
			}.onFailure {
				// Left unsynced on purpose: the daily sync retries it.
				it.printStackTraceDebug()
			}.onSuccess {
				communitySettings.syncedNickname = nickname
			}
		}
	}

	/**
	 * The key, and the way back in.
	 *
	 * Copying alone would be pointless without somewhere to paste it, so this is deliberately
	 * two-way: the same dialog shows the key and accepts one. It is also the only thing standing
	 * between a user and losing their comments when they change phone, so it is presented as
	 * something to write down rather than buried behind an export.
	 */
	private fun showRecoveryDialog() {
		val existing = communitySettings.peekSecret()
		val input = TextInputEditText(requireContext()).apply {
			setText(existing.orEmpty())
			setSingleLine()
			setSelectAllOnFocus(true)
			hint = getString(R.string.community_recovery_hint)
		}
		val container = FrameLayout(requireContext()).apply {
			val padding = resources.getDimensionPixelOffset(R.dimen.margin_normal)
			setPadding(padding + padding / 2, padding / 2, padding + padding / 2, 0)
			addView(input)
		}

		buildAlertDialog(requireContext(), isCentered = false) {
			setTitle(R.string.community_recovery)
			setMessage(
				if (existing == null) {
					getString(R.string.community_recovery_none)
				} else {
					getString(R.string.community_recovery_explain)
				},
			)
			setView(container)
			setNeutralButton(android.R.string.copy, null)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.community_recovery_restore, null)
		}.also { dialog ->
			// Wired after show() so an invalid key can report an error without closing the dialog.
			dialog.setOnShowListener {
				dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
					copyToClipboard(input.text?.toString().orEmpty())
				}
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
					restoreIdentity(input.text?.toString().orEmpty(), dialog)
				}
			}
		}.show()
	}

	private fun copyToClipboard(value: String) {
		if (value.isEmpty()) return
		requireContext().getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
			ClipData.newPlainText(getString(R.string.community_recovery), value),
		)
	}

	/**
	 * Adopting a key replaces this device's identity with another. Rejected rather than accepted on
	 * a malformed value: silently storing a typo would create a brand-new identity that looks exactly
	 * like a successful restore, and the user's real comments would be gone with no way back.
	 */
	private fun restoreIdentity(value: String, dialog: AlertDialog) {
		if (!communitySettings.isPlausibleSecret(value)) {
			Snackbar.make(
				requireView(),
				R.string.community_recovery_invalid,
				Snackbar.LENGTH_LONG,
			).show()
			return
		}
		if (value.trim() == communitySettings.peekSecret()) {
			dialog.dismiss()
			return
		}

		val previous = communitySettings.peekSecret()
		if (!communitySettings.adoptSecret(value)) return
		val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
		button.isEnabled = false
		viewLifecycleScope.launch {
			val result = runCatchingCancellable { api.helloWithStatus() }.getOrNull()
			when {
				result == null -> {
					communitySettings.revertSecret(previous)
					Snackbar.make(requireView(), R.string.community_recover_failed, Snackbar.LENGTH_LONG).show()
				}

				result.created -> {
					// A plausible typo is still a different valid key. The server created an empty
					// account for it, which proves it was not a recovery key.
					communitySettings.revertSecret(previous)
					Snackbar.make(requireView(), R.string.community_recover_unknown, Snackbar.LENGTH_LONG).show()
				}

				else -> {
					communitySettings.isRegistered = true
					communitySettings.nickname = result.identity.nickname
					communitySettings.syncedNickname = result.identity.nickname
					communitySettings.replyCursor = null
					communitySettings.lastProbeUploadDay = Long.MIN_VALUE
					// The adopted identity has its own ratings and comments; anything cached here
					// belonged to the previous one.
					withContext(Dispatchers.Default) {
						database.getSourceScoreDao().clear()
					}
					syncScheduler.schedule()
					Snackbar.make(
						requireView(),
						R.string.community_recovery_restored,
						Snackbar.LENGTH_LONG,
					).show()
					dialog.dismiss()
				}
			}
			if (dialog.isShowing) button.isEnabled = true
		}
	}

	/**
	 * Downloads the server's copy of this account's data and hands it to the share sheet.
	 *
	 * The file is whatever the server produced, unparsed: re-encoding it through a data class here
	 * would quietly drop any field the app does not happen to model, which is the one thing a copy of
	 * your own data must not do.
	 */
	private fun exportMyData() {
		viewLifecycleScope.launch {
			val json = runCatchingCancellable {
				withContext(Dispatchers.Default) { api.exportMyData() }
			}.onFailure { it.printStackTraceDebug() }.getOrNull()

			if (json == null) {
				Snackbar.make(requireView(), R.string.community_export_failed, Snackbar.LENGTH_SHORT).show()
				return@launch
			}

			val uri = withContext(Dispatchers.IO) {
				val directory = File(requireContext().cacheDir, "export").apply { mkdirs() }
				val file = File(directory, "kotatsu-community-data.json")
				file.writeText(json)
				FileProvider.getUriForFile(requireContext(), "${BuildConfig.APPLICATION_ID}.files", file)
			}
			// Built here rather than through AppRouter, whose file-sharing helper is private and typed
			// to CBZ - and this fork keeps its community code out of upstream files where it can.
			ShareCompat.IntentBuilder(requireContext())
				.setType("application/json")
				.addStream(uri)
				.setChooserTitle(getString(R.string.community_export))
				.startChooser()
		}
	}

	private fun confirmDeleteEverything() {
		buildAlertDialog(requireContext(), isCentered = true) {
			setTitle(R.string.community_delete)
			setMessage(R.string.community_delete_confirm)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.community_delete) { _, _ -> deleteEverything() }
		}.show()
	}

	/**
	 * Server first, then local. In that order deliberately: if the network call fails the local
	 * identity survives, so the user can retry rather than being left with data on a server they can
	 * no longer prove they own.
	 */
	private fun deleteEverything() {
		viewLifecycleScope.launch {
			val deleted = runCatchingCancellable {
				withContext(Dispatchers.Default) { api.deleteIdentity() }
			}.onFailure { it.printStackTraceDebug() }.isSuccess
			if (!deleted) return@launch

			withContext(Dispatchers.Default) {
				database.getSourceScoreDao().clear()
				database.getSourceStatsDao().clear()
			}
			communitySettings.forget()
			updateVisibility()
			findPreference<SwitchPreferenceCompat>("enabled")?.isChecked = false
			findPreference<SwitchPreferenceCompat>("telemetry")?.isChecked = false
		}
	}
}
