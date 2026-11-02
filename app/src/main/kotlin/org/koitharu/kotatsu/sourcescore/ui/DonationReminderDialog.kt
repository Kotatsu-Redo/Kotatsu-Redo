package org.koitharu.kotatsu.sourcescore.ui

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router

/** See [org.koitharu.kotatsu.sourcescore.domain.DonationReminder] for when this comes up. */
class DonationReminderDialog : DialogFragment() {

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
		MaterialAlertDialogBuilder(requireContext())
			.setIcon(R.drawable.ic_heart)
			.setTitle(R.string.community_sponsor)
			.setMessage(R.string.community_donation_reminder)
			.setPositiveButton(R.string.community_sponsor_open) { _, _ ->
				router.openExternalBrowser(CommunityOnboardingDialog.SPONSOR_URL, getString(R.string.community_sponsor))
			}
			.setNegativeButton(R.string.community_sponsor_dismiss, null)
			.create()

	companion object {

		private const val TAG = "donation_reminder"

		fun show(fm: FragmentManager) {
			if (fm.findFragmentByTag(TAG) == null) DonationReminderDialog().show(fm, TAG)
		}
	}
}
