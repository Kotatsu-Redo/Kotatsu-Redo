package org.koitharu.kotatsu.core.ui

import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.acra.dialog.CrashReportDialog
import org.koitharu.kotatsu.databinding.DialogCrashReportBinding

/**
 * ACRA's crash prompt, restyled to match the app.
 *
 * Subclasses [CrashReportDialog] rather than replacing it outright, for two reasons: ACRA's report
 * plumbing (loading the pending report, sending or discarding it) is inherited untouched, and
 * `AppProtectHelper` waives the app lock for `activity is CrashReportDialog`, which a subclass still
 * satisfies - a separate activity would get the passcode screen thrown in front of the crash dialog.
 *
 * Only the presentation is overridden. The inherited [onClick] is the whole contract: `BUTTON_POSITIVE`
 * sends the report, anything else discards it, and either way it finishes the activity.
 */
class CrashReportActivity : CrashReportDialog() {

	override fun buildAndShowDialog(savedInstanceState: Bundle?) {
		val binding = DialogCrashReportBinding.inflate(layoutInflater)
		val alertDialog = MaterialAlertDialogBuilder(this)
			.setView(binding.root)
			.create()
		binding.button1.setOnClickListener { complete(alertDialog, DialogInterface.BUTTON_POSITIVE) }
		binding.buttonClose.setOnClickListener { complete(alertDialog, DialogInterface.BUTTON_NEGATIVE) }
		// Back or a tap outside discards the report rather than leaving it pending.
		alertDialog.setOnCancelListener { complete(alertDialog, DialogInterface.BUTTON_NEGATIVE) }
		alertDialog.setOnDismissListener { finish() }
		// The inherited `dialog` field is left unset: it is typed android.app.AlertDialog, which a
		// MaterialAlertDialogBuilder cannot produce, and nothing in the base class reads it - only its
		// own getter and setter touch it.
		alertDialog.show()
	}

	private fun complete(alertDialog: AlertDialog, which: Int) {
		// Take the window down before finishing, otherwise it is leaked with the activity. Clearing the
		// listener first keeps that dismissal from racing onClick's own finish().
		alertDialog.setOnDismissListener(null)
		alertDialog.dismiss()
		onClick(alertDialog, which)
	}
}
